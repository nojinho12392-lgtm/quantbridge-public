from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Mapping, Sequence

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class RiskReportConfig:
    single_name_rc_warn: float = 0.20
    sector_rc_warn: float = 0.35
    max_position_warn: float = 0.10


def build_risk_report(
    *,
    market: str,
    tickers: Sequence[str],
    weights: Sequence[float] | pd.Series | Mapping[str, float],
    cov_matrix: np.ndarray | pd.DataFrame,
    metadata: pd.DataFrame,
    target_vol: float,
    invested_fraction: float,
    cash_weight: float,
    generated_at: pd.Timestamp | None = None,
    config: RiskReportConfig | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    config = config or RiskReportConfig()
    generated_at = generated_at or pd.Timestamp.now()
    generated = generated_at.strftime("%Y-%m-%d %H:%M:%S")
    market_name = str(market).upper()

    w = _weight_series(tickers, weights)
    cov = _covariance_frame(cov_matrix, list(w.index))
    common = [ticker for ticker in w.index if ticker in cov.index and ticker in cov.columns]
    w = w.reindex(common).fillna(0.0)
    cov = cov.loc[common, common]
    meta = metadata.reindex(common) if metadata is not None and not metadata.empty else pd.DataFrame(index=common)

    if w.empty or float(w.abs().sum()) <= 0 or cov.empty:
        return _empty_summary(market_name, generated), _empty_holdings(), _empty_sectors()

    variance = float(w.to_numpy() @ cov.to_numpy() @ w.to_numpy())
    portfolio_vol = math.sqrt(max(variance, 0.0))
    marginal = pd.Series(0.0, index=w.index, dtype=float)
    component = pd.Series(0.0, index=w.index, dtype=float)
    rc_pct = pd.Series(0.0, index=w.index, dtype=float)
    if portfolio_vol > 0:
        marginal = cov.dot(w) / portfolio_vol
        component = w * marginal
        rc_pct = component / portfolio_vol

    sleeve_weight = w / invested_fraction if invested_fraction and invested_fraction > 0 else w
    asset_vol = pd.Series(np.sqrt(np.clip(np.diag(cov), 0, None)), index=w.index)

    holdings = pd.DataFrame(
        {
            "Market": market_name,
            "Ticker": w.index,
            "Name": [_meta_value(meta, ticker, "Name") for ticker in w.index],
            "Sector": [_sector(meta, ticker) for ticker in w.index],
            "Portfolio_Weight": w.values,
            "Sleeve_Weight": sleeve_weight.reindex(w.index).values,
            "Asset_Vol": asset_vol.reindex(w.index).values,
            "Marginal_Risk": marginal.reindex(w.index).values,
            "Risk_Contribution": component.reindex(w.index).values,
            "Risk_Contribution_Pct": rc_pct.reindex(w.index).values,
            "Weight_Risk_Ratio": _safe_ratio(rc_pct.reindex(w.index), w.reindex(w.index)),
            "Total_Score": [_meta_value(meta, ticker, "Total_Score") for ticker in w.index],
            "MarketCap": [_meta_value(meta, ticker, "MarketCap") for ticker in w.index],
            "Warnings": [
                _holding_warnings(w.loc[ticker], rc_pct.loc[ticker], config)
                for ticker in w.index
            ],
            "Generated_At": generated,
        }
    ).sort_values("Risk_Contribution_Pct", ascending=False, na_position="last")

    sectors = _sector_report(holdings, generated=generated, config=config)
    summary = _summary_report(
        market=market_name,
        holdings=holdings,
        sectors=sectors,
        portfolio_vol=portfolio_vol,
        target_vol=target_vol,
        invested_fraction=invested_fraction,
        cash_weight=cash_weight,
        generated=generated,
        config=config,
    )
    return summary, holdings.reset_index(drop=True), sectors.reset_index(drop=True)


def write_risk_report_sheet(
    spreadsheet,
    title: str,
    summary: pd.DataFrame,
    holdings: pd.DataFrame,
    sectors: pd.DataFrame,
) -> None:
    rows = _section_rows("Portfolio Risk Summary", summary)
    rows += [[""]]
    rows += _section_rows("Sector Risk Contribution", sectors)
    rows += [[""]]
    rows += _section_rows("Holding Risk Contribution", holdings)

    width = max(len(row) for row in rows) if rows else 1
    padded = [row + [""] * (width - len(row)) for row in rows]
    try:
        worksheet = spreadsheet.worksheet(title)
    except Exception:
        worksheet = spreadsheet.add_worksheet(title=title, rows=max(len(padded) + 10, 100), cols=max(width + 2, 10))

    worksheet.clear()
    worksheet.update(range_name="A1", values=padded, value_input_option="USER_ENTERED")


def _sector_report(holdings: pd.DataFrame, *, generated: str, config: RiskReportConfig) -> pd.DataFrame:
    if holdings.empty:
        return _empty_sectors()
    clean = holdings.copy()
    clean["Sector"] = clean["Sector"].replace("", "Unknown").fillna("Unknown")
    grouped = clean.groupby("Sector", dropna=False)
    sectors = grouped.agg(
        Market=("Market", "first"),
        Holdings=("Ticker", "count"),
        Sector_Weight=("Portfolio_Weight", "sum"),
        Sector_Risk_Contribution=("Risk_Contribution", "sum"),
        Sector_Risk_Contribution_Pct=("Risk_Contribution_Pct", "sum"),
        Max_Holding_Risk_Contribution_Pct=("Risk_Contribution_Pct", "max"),
    ).reset_index()
    sectors["Warnings"] = np.where(
        sectors["Sector_Risk_Contribution_Pct"] >= config.sector_rc_warn,
        "HIGH_SECTOR_RISK",
        "",
    )
    sectors["Generated_At"] = generated
    return sectors.sort_values("Sector_Risk_Contribution_Pct", ascending=False, na_position="last")


def _summary_report(
    *,
    market: str,
    holdings: pd.DataFrame,
    sectors: pd.DataFrame,
    portfolio_vol: float,
    target_vol: float,
    invested_fraction: float,
    cash_weight: float,
    generated: str,
    config: RiskReportConfig,
) -> pd.DataFrame:
    sleeve_weights = pd.to_numeric(holdings["Sleeve_Weight"], errors="coerce").fillna(0.0)
    portfolio_weights = pd.to_numeric(holdings["Portfolio_Weight"], errors="coerce").fillna(0.0)
    rc_pct = pd.to_numeric(holdings["Risk_Contribution_Pct"], errors="coerce").fillna(0.0)
    top5_weight = float(portfolio_weights.sort_values(ascending=False).head(5).sum())
    top5_risk = float(rc_pct.sort_values(ascending=False).head(5).sum())
    effective_holdings = 1.0 / float((sleeve_weights ** 2).sum()) if float((sleeve_weights ** 2).sum()) > 0 else np.nan
    risk_utilization = portfolio_vol / target_vol if target_vol else np.nan
    max_position = float(portfolio_weights.max()) if not portfolio_weights.empty else 0.0
    max_holding_rc = float(rc_pct.max()) if not rc_pct.empty else 0.0
    max_sector_rc = float(pd.to_numeric(sectors.get("Sector_Risk_Contribution_Pct", pd.Series(dtype=float)), errors="coerce").max()) if not sectors.empty else 0.0

    rows = [
        ("Estimated_Portfolio_Vol", portfolio_vol, _status(portfolio_vol <= target_vol if target_vol else True), f"Target={target_vol:.4f}"),
        ("Target_Vol", target_vol, "INFO", ""),
        ("Risk_Utilization", risk_utilization, _status(risk_utilization <= 1.0 if pd.notna(risk_utilization) else True), ""),
        ("Invested_Fraction", invested_fraction, "INFO", ""),
        ("Cash_Weight", cash_weight, "INFO", ""),
        ("Effective_Holdings", effective_holdings, "INFO", ""),
        ("Top5_Weight", top5_weight, "INFO", ""),
        ("Top5_Risk_Contribution_Pct", top5_risk, "INFO", ""),
        ("Max_Position_Weight", max_position, _status(max_position <= config.max_position_warn), f"Warn>{config.max_position_warn:.2f}"),
        ("Max_Holding_Risk_Contribution_Pct", max_holding_rc, _status(max_holding_rc < config.single_name_rc_warn), f"Warn>={config.single_name_rc_warn:.2f}"),
        ("Max_Sector_Risk_Contribution_Pct", max_sector_rc, _status(max_sector_rc < config.sector_rc_warn), f"Warn>={config.sector_rc_warn:.2f}"),
    ]
    return pd.DataFrame(
        [
            {
                "Market": market,
                "Metric": metric,
                "Value": value,
                "Status": status,
                "Details": details,
                "Generated_At": generated,
            }
            for metric, value, status, details in rows
        ]
    )


def _weight_series(tickers: Sequence[str], weights: Sequence[float] | pd.Series | Mapping[str, float]) -> pd.Series:
    if isinstance(weights, pd.Series):
        out = pd.to_numeric(weights, errors="coerce")
    elif isinstance(weights, Mapping):
        out = pd.Series(weights, dtype=float)
    else:
        out = pd.Series(list(weights), index=list(tickers), dtype=float)
    out.index = out.index.map(lambda value: str(value).strip())
    out = out.replace([np.inf, -np.inf], np.nan).dropna()
    return out[out > 0]


def _covariance_frame(cov_matrix: np.ndarray | pd.DataFrame, tickers: list[str]) -> pd.DataFrame:
    if isinstance(cov_matrix, pd.DataFrame):
        cov = cov_matrix.copy()
        cov.index = cov.index.map(lambda value: str(value).strip())
        cov.columns = cov.columns.map(lambda value: str(value).strip())
        return cov.apply(pd.to_numeric, errors="coerce").fillna(0.0)
    return pd.DataFrame(np.asarray(cov_matrix, dtype=float), index=tickers, columns=tickers)


def _safe_ratio(numerator: pd.Series, denominator: pd.Series) -> pd.Series:
    denom = denominator.replace(0, np.nan)
    return (numerator / denom).replace([np.inf, -np.inf], np.nan)


def _holding_warnings(weight: float, rc_pct: float, config: RiskReportConfig) -> str:
    warnings = []
    if rc_pct >= config.single_name_rc_warn:
        warnings.append("HIGH_RISK_CONTRIB")
    if weight >= config.max_position_warn:
        warnings.append("HIGH_WEIGHT")
    return ";".join(warnings)


def _meta_value(meta: pd.DataFrame, ticker: str, column: str):
    if meta.empty or column not in meta.columns or ticker not in meta.index:
        return ""
    value = meta.loc[ticker, column]
    return "" if pd.isna(value) else value


def _sector(meta: pd.DataFrame, ticker: str) -> str:
    value = _meta_value(meta, ticker, "Sector")
    text = str(value or "").strip()
    return "" if text.lower() in {"nan", "none", "unknown"} else text


def _status(ok: bool) -> str:
    return "OK" if ok else "WARN"


def _section_rows(title: str, df: pd.DataFrame) -> list[list[str]]:
    if df is None or df.empty:
        return [[f"-- {title} --"], ["Status"], ["No data"]]
    clean = df.copy()
    clean = clean.astype(object).where(pd.notna(clean), "")
    return [[f"-- {title} --"], clean.columns.tolist()] + clean.astype(str).values.tolist()


def _empty_summary(market: str, generated: str) -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "Market": market,
                "Metric": "Status",
                "Value": "No data",
                "Status": "WARN",
                "Details": "",
                "Generated_At": generated,
            }
        ]
    )


def _empty_holdings() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Market",
            "Ticker",
            "Name",
            "Sector",
            "Portfolio_Weight",
            "Sleeve_Weight",
            "Asset_Vol",
            "Marginal_Risk",
            "Risk_Contribution",
            "Risk_Contribution_Pct",
            "Weight_Risk_Ratio",
            "Total_Score",
            "MarketCap",
            "Warnings",
            "Generated_At",
        ]
    )


def _empty_sectors() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Sector",
            "Market",
            "Holdings",
            "Sector_Weight",
            "Sector_Risk_Contribution",
            "Sector_Risk_Contribution_Pct",
            "Max_Holding_Risk_Contribution_Pct",
            "Warnings",
            "Generated_At",
        ]
    )
