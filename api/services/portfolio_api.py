from __future__ import annotations

import math
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, time, timezone
from typing import Any

from fastapi import HTTPException
import pandas as pd

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class PortfolioPayloadBuilder:
    infer_storage_market: Callable[[str], str | None]
    load_storage_df: Callable[[str, str | None], pd.DataFrame]
    normalize_portfolio_price_columns: Callable[[pd.DataFrame], pd.DataFrame]
    coerce: Callable[[pd.DataFrame, list[str]], pd.DataFrame]
    df_to_records: Callable[[pd.DataFrame], list[dict]]
    enrich_price_fields: Callable[[list[dict], str | None, int | None], list[dict]]
    enrich_rank_change_fields: Callable[[list[dict], str, str | None], list[dict]]
    portfolio_display_overrides: Callable[[list[dict], str | None], list[dict]]
    spreadsheet: Callable[[], Any]
    sheet_values_to_df: Callable[[list[list[str]], list[str]], pd.DataFrame]
    record_data_source: Callable[..., None]
    clean_meta_value: Callable[[object], str | None]
    portfolio_numeric_cols: list[str]

    def load_portfolio(self, sheet_name: str) -> tuple[dict, list[dict]]:
        """
        Portfolio sheets have a key-value summary block before the column headers.
        Detect the header row as the first row with 3+ non-empty cells.
        """
        market = self.infer_storage_market(sheet_name)
        storage_df = self.load_storage_df(sheet_name, market)
        if not storage_df.empty:
            storage_df = self.normalize_portfolio_price_columns(storage_df)
            storage_df = self.coerce(storage_df, self.portfolio_numeric_cols)
            records = self.enrich_price_fields(self.df_to_records(storage_df), market, 0)
            records = self.enrich_rank_change_fields(records, sheet_name, market)
            records = self.portfolio_display_overrides(records, market)
            return self.portfolio_meta_from_storage(market or "GLOBAL", storage_df), records

        try:
            ws = self.spreadsheet().worksheet(sheet_name)
            data = ws.get_all_values()
        except Exception as exc:
            self.record_data_source(
                sheet_name,
                "sheet_error",
                market=market,
                detail=f"{type(exc).__name__}: {exc}",
            )
            return {}, []
        if not data:
            self.record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
            return {}, []

        header_idx = next(
            (i for i, row in enumerate(data) if sum(1 for c in row if c.strip()) >= 3),
            0,
        )

        meta = {
            row[0].strip(): row[1].strip()
            for row in data[:header_idx]
            if len(row) >= 2 and row[0].strip() and row[1].strip()
        }
        meta = self.portfolio_meta_aliases(meta)

        if header_idx >= len(data) - 1:
            self.record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
            return meta, []

        df = self.sheet_values_to_df(data[header_idx + 1:], data[header_idx])
        df = df[df.apply(lambda row: row.str.strip().ne("").any(), axis=1)]
        if "Ticker" in df.columns:
            df = df[df["Ticker"].str.strip() != ""]

        df = self.normalize_portfolio_price_columns(df)
        df = self.coerce(df, self.portfolio_numeric_cols)

        self.record_data_source(sheet_name, "sheet", market=market, rows=len(df))
        records = self.enrich_price_fields(self.df_to_records(df.reset_index(drop=True)), market, 0)
        records = self.enrich_rank_change_fields(records, sheet_name, market)
        records = self.portfolio_display_overrides(records, market)
        return meta, records

    def weighted_expected_return(self, portfolio: pd.DataFrame) -> str | None:
        if portfolio.empty or "Expected_Return" not in portfolio.columns or "Weight(%)" not in portfolio.columns:
            return None
        expected = pd.to_numeric(portfolio["Expected_Return"], errors="coerce")
        weights = pd.to_numeric(portfolio["Weight(%)"], errors="coerce")
        valid = expected.notna() & weights.notna()
        if not valid.any():
            return None
        value = float((expected[valid] * weights[valid]).sum())
        if not math.isfinite(value):
            return None
        return f"{value:.4f}"

    def portfolio_meta_aliases(self, meta: dict) -> dict:
        out = dict(meta)

        def copy_first(target: str, sources: list[str]) -> None:
            if self.clean_meta_value(out.get(target)):
                return
            for source in sources:
                value = self.clean_meta_value(out.get(source))
                if value:
                    out[target] = value
                    return

        copy_first("Cash_Weight", ["Cash Weight", "CashWeight"])
        copy_first("Cash Weight", ["Cash_Weight", "CashWeight"])
        copy_first("Generated", ["Generated_At", "Last_Updated"])
        copy_first("Generated_At", ["Generated", "Last_Updated"])
        copy_first("Expected_Return", ["Ann. Return (hist. est.)", "Ann. Return", "Expected Return"])
        copy_first("Ann. Return (hist. est.)", ["Expected_Return", "Ann. Return", "Expected Return"])
        copy_first("Regime", ["Macro Regime", "Macro_Regime"])
        copy_first("Macro_Regime", ["Regime", "Macro Regime"])
        return out

    def portfolio_meta_from_storage(self, market: str, portfolio: pd.DataFrame) -> dict:
        meta: dict[str, str] = {"Source": "storage"}
        summary = self.load_storage_df(f"{market}_Final_Portfolio_Risk_Summary", market)

        cash_weight = self.summary_metric_value(summary, "Cash_Weight")
        if cash_weight:
            meta["Cash_Weight"] = cash_weight
            meta["Cash Weight"] = cash_weight

        generated = (
            self.first_frame_value(summary, ["Generated_At", "Generated"])
            or self.summary_metric_value(summary, "Generated_At")
            or self.summary_metric_value(summary, "Generated")
            or self.first_frame_value(portfolio, ["Last_Updated"])
        )
        if generated:
            meta["Generated"] = generated
            meta["Generated_At"] = generated

        expected_return = self.weighted_expected_return(portfolio)
        if expected_return:
            meta["Expected_Return"] = expected_return
            meta["Ann. Return (hist. est.)"] = expected_return

        return self.portfolio_meta_aliases(meta)

    def first_frame_value(self, df: pd.DataFrame, keys: list[str]) -> str | None:
        if df.empty:
            return None
        for key in keys:
            if key not in df.columns:
                continue
            for value in df[key].tolist():
                cleaned = self.clean_meta_value(value)
                if cleaned:
                    return cleaned
        return None

    def summary_metric_value(self, summary: pd.DataFrame, metric: str) -> str | None:
        if summary.empty or "Metric" not in summary.columns:
            return None
        value_cols = [col for col in ("Value", "Metric_Value", "Result") if col in summary.columns]
        if not value_cols:
            return None
        targets = {self.meta_key(metric), self.meta_key(metric.replace("_", " "))}
        for _, row in summary.iterrows():
            if self.meta_key(row.get("Metric")) not in targets:
                continue
            for col in value_cols:
                cleaned = self.clean_meta_value(row.get(col))
                if cleaned:
                    return cleaned
        return None

    @staticmethod
    def meta_key(value) -> str:
        return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


@dataclass(frozen=True)
class PortfolioPriceEnricher:
    repository: Callable[[], Any]
    safe_float: Callable[[object], float | None]
    first_float: Callable[..., float | None]
    us_market_tz: Any
    is_us_equity_regular_session_open_loader: Callable[[], bool] | None = None
    price_snapshot_batch_loader: Callable[[list[str], str | None], dict[str, dict]] | None = None
    daily_change_batch_loader: Callable[[list[str], str | None, dict[str, dict]], dict[str, tuple[float | None, str]]] | None = None

    def us_equity_regular_session_phase(self, now: datetime | None = None) -> str:
        local = (now or datetime.now(timezone.utc)).astimezone(self.us_market_tz)
        if local.weekday() >= 5:
            return "closed_day"
        open_time, close_time = time(9, 30), time(16, 0)
        if local.time() < open_time:
            return "pre_open"
        if local.time() <= close_time:
            return "open"
        return "after_close"

    def us_equity_local_date(self, now: datetime | None = None):
        return (now or datetime.now(timezone.utc)).astimezone(self.us_market_tz).date()

    def drop_unsettled_us_daily_rows(self, frame: pd.DataFrame, market: str | None) -> pd.DataFrame:
        if str(market or "").strip().upper() != "US" or frame.empty or "date" not in frame.columns:
            return frame
        if self.us_equity_regular_session_phase() != "pre_open":
            return frame
        today = self.us_equity_local_date()
        dates = pd.to_datetime(frame["date"], errors="coerce").dt.date
        filtered = frame[dates < today]
        return filtered if not filtered.empty else frame

    def drop_unsettled_us_daily_series(self, series: pd.Series, market: str | None) -> pd.Series:
        if str(market or "").strip().upper() != "US" or series.empty:
            return series
        if self.us_equity_regular_session_phase() != "pre_open":
            return series
        today = self.us_equity_local_date()
        dates = pd.to_datetime(series.index, errors="coerce")
        keep = [pd.Timestamp(index).date() < today for index in dates]
        filtered = series[keep]
        return filtered if not filtered.empty else series

    def portfolio_price_metrics(self, ticker: str, market: str | None) -> tuple[float | None, float | None]:
        try:
            prices = self.repository().read_prices(ticker, period="3mo", market=market)
        except Exception:
            return None, None
        return self.portfolio_price_metrics_from_frame(prices, market=market)

    def portfolio_price_metrics_from_frame(
        self,
        prices: pd.DataFrame,
        market: str | None = None,
    ) -> tuple[float | None, float | None]:
        if prices.empty or "close" not in prices.columns:
            return None, None

        frame = prices.copy()
        frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
        frame["close"] = pd.to_numeric(frame["close"], errors="coerce")
        frame = frame.dropna(subset=["date", "close"]).sort_values("date")
        frame = self.drop_unsettled_us_daily_rows(frame, market)
        if frame.empty:
            return None, None

        current_price = float(frame["close"].iloc[-1])
        target_date = frame["date"].iloc[-1] - pd.Timedelta(days=30)
        base_rows = frame[frame["date"] <= target_date]
        if base_rows.empty:
            base_price = float(frame["close"].iloc[0]) if len(frame) > 1 else None
        else:
            base_price = float(base_rows["close"].iloc[-1])
        if base_price is None or base_price <= 0:
            return current_price, None
        return current_price, (current_price / base_price) - 1.0

    def is_us_equity_regular_session_open(self, now: datetime | None = None) -> bool:
        return self.us_equity_regular_session_phase(now) == "open"

    def should_use_last_regular_close_change(self, market: str | None) -> bool:
        is_open = (
            self.is_us_equity_regular_session_open_loader()
            if self.is_us_equity_regular_session_open_loader is not None
            else self.is_us_equity_regular_session_open()
        )
        return str(market or "").strip().upper() == "US" and not is_open

    def portfolio_price_metrics_batch(
        self,
        tickers: list[str],
        market: str | None,
    ) -> dict[str, tuple[float | None, float | None]]:
        clean_tickers = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
        if not clean_tickers:
            return {}
        try:
            prices = self.repository().read_prices_batch(clean_tickers, period="3mo", market=market)
        except Exception:
            prices = pd.DataFrame()

        if prices.empty or "ticker" not in prices.columns:
            return {
                ticker: self.portfolio_price_metrics(ticker, market)
                for ticker in clean_tickers
            }

        metrics: dict[str, tuple[float | None, float | None]] = {}
        for ticker, group in prices.groupby("ticker", sort=False):
            metrics[str(ticker).strip().upper()] = self.portfolio_price_metrics_from_frame(group, market=market)

        for ticker in clean_tickers:
            metrics.setdefault(ticker, (None, None))
        return metrics

    def price_snapshot_batch(self, tickers: list[str], market: str | None) -> dict[str, dict]:
        clean_tickers = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
        if not clean_tickers:
            return {}
        try:
            metrics = self.repository().read_price_metrics(clean_tickers, market=market)
        except Exception:
            metrics = pd.DataFrame()
        if metrics.empty or "ticker" not in metrics.columns:
            return {}

        snapshots: dict[str, dict] = {}
        for _, row in metrics.iterrows():
            ticker = str(row.get("ticker") or "").strip().upper()
            if not ticker:
                continue
            payload = row.get("payload")
            if not isinstance(payload, dict):
                payload = {}
            as_of = row.get("as_of")
            updated_at = row.get("updated_at") or as_of
            if hasattr(as_of, "isoformat"):
                as_of = as_of.isoformat()
            if hasattr(updated_at, "isoformat"):
                updated_at = updated_at.isoformat()
            snapshots[ticker] = {
                "current_price": self.safe_float(row.get("current_price")),
                "return_1m": self.safe_float(row.get("return_1m")),
                "daily_change_pct": self.safe_float(payload.get("daily_change_pct")),
                "daily_change_horizon": str(payload.get("daily_change_horizon") or ""),
                "previous_close": self.safe_float(payload.get("previous_close")),
                "as_of": as_of,
                "updated_at": updated_at,
                "source": payload.get("source") or row.get("source"),
            }
        return snapshots

    def daily_change_from_price_frame(
        self,
        prices: pd.DataFrame,
        snapshot: dict | None = None,
        market: str | None = None,
    ) -> tuple[float | None, str]:
        if prices.empty or "date" not in prices.columns or "close" not in prices.columns:
            return None, ""

        frame = prices.copy()
        frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
        frame["close"] = pd.to_numeric(frame["close"], errors="coerce")
        frame = frame.dropna(subset=["date", "close"]).sort_values("date")
        frame = self.drop_unsettled_us_daily_rows(frame, market)
        if len(frame) < 2:
            return None, ""

        snapshot = snapshot or {}
        snapshot_daily_change = self.safe_float(snapshot.get("daily_change_pct"))
        if snapshot_daily_change is not None:
            return snapshot_daily_change, str(snapshot.get("daily_change_horizon") or "\uc624\ub298")
        snapshot_price = self.safe_float(snapshot.get("current_price"))
        snapshot_time = pd.to_datetime(
            snapshot.get("as_of") or snapshot.get("updated_at"),
            errors="coerce",
            utc=False,
        )
        latest_date = frame["date"].iloc[-1].date()
        latest_close = self.safe_float(frame["close"].iloc[-1])
        previous_close = self.safe_float(frame["close"].iloc[-2])
        current_price = latest_close
        base_price = previous_close
        horizon = "\uc804\uc7a5"

        if self.should_use_last_regular_close_change(market):
            if current_price is None or base_price is None or base_price <= 0:
                return None, ""
            return (current_price / base_price) - 1.0, "\uc804\uc7a5"

        if snapshot_price is not None:
            current_price = snapshot_price
            if not pd.isna(snapshot_time):
                snapshot_date = snapshot_time.date()
                if snapshot_date > latest_date:
                    base_price = latest_close
                    horizon = "\uc624\ub298"
                elif snapshot_date == latest_date:
                    base_price = previous_close
                    horizon = "\uc624\ub298"
                else:
                    current_price = latest_close
                    base_price = previous_close
            elif latest_close is not None:
                if abs(snapshot_price - latest_close) / max(abs(latest_close), 1e-9) > 0.0005:
                    base_price = latest_close
                    horizon = "\uc624\ub298"

        if current_price is None or base_price is None or base_price <= 0:
            return None, ""
        return (current_price / base_price) - 1.0, horizon

    def daily_change_batch(
        self,
        tickers: list[str],
        market: str | None,
        snapshots: dict[str, dict] | None = None,
    ) -> dict[str, tuple[float | None, str]]:
        clean_tickers = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
        if not clean_tickers:
            return {}
        try:
            prices = self.repository().read_prices_batch(clean_tickers, period="10d", market=market)
        except Exception:
            prices = pd.DataFrame()

        snapshots = snapshots or {}
        if prices.empty or "ticker" not in prices.columns:
            return {ticker: (None, "") for ticker in clean_tickers}

        changes: dict[str, tuple[float | None, str]] = {}
        for ticker, group in prices.groupby("ticker", sort=False):
            key = str(ticker).strip().upper()
            changes[key] = self.daily_change_from_price_frame(group, snapshots.get(key), market=market)

        for ticker in clean_tickers:
            changes.setdefault(ticker, (None, ""))
        return changes

    def enrich_price_fields(
        self,
        records: list[dict],
        market: str | None,
        max_fetch: int | None = None,
    ) -> list[dict]:
        tickers = [str(row.get("Ticker") or "") for row in records]
        snapshots = self._price_snapshot_batch(tickers, market)
        daily_changes = self._daily_change_batch(tickers, market, snapshots)
        fetch_count = 0
        for row in records:
            ticker = str(row.get("Ticker") or "").strip()
            if not ticker:
                continue
            ticker_key = ticker.upper()
            current_price = self.safe_float(row.get("Current_Price"))
            return_1m = self.first_float(row.get("Return_1M"), row.get("Mom_1M"))
            snapshot = snapshots.get(ticker_key)
            if snapshot:
                current_price = snapshot["current_price"] if snapshot["current_price"] is not None else current_price
                return_1m = snapshot["return_1m"] if snapshot["return_1m"] is not None else return_1m
                if snapshot.get("updated_at"):
                    row["Price_Updated_At"] = snapshot["updated_at"]
                    row["Last_Updated"] = snapshot["updated_at"]
                if snapshot.get("source"):
                    row["Price_Source"] = snapshot["source"]
                    row["Source"] = row.get("Source") or snapshot["source"]
            elif current_price is None or return_1m is None:
                should_fetch = max_fetch is None or fetch_count < max_fetch
                if should_fetch:
                    fetched_price, fetched_return = self.portfolio_price_metrics(ticker, market)
                    fetch_count += 1
                    current_price = current_price if current_price is not None else fetched_price
                    return_1m = return_1m if return_1m is not None else fetched_return
            row["Current_Price"] = current_price
            row["Return_1M"] = return_1m
            daily_change, daily_horizon = daily_changes.get(ticker_key, (None, ""))
            if daily_change is not None:
                row["Daily_Change_Pct"] = daily_change
                row["Daily_Change_Horizon"] = daily_horizon
        return records

    def _price_snapshot_batch(self, tickers: list[str], market: str | None) -> dict[str, dict]:
        if self.price_snapshot_batch_loader is not None:
            return self.price_snapshot_batch_loader(tickers, market)
        return self.price_snapshot_batch(tickers, market)

    def _daily_change_batch(
        self,
        tickers: list[str],
        market: str | None,
        snapshots: dict[str, dict],
    ) -> dict[str, tuple[float | None, str]]:
        if self.daily_change_batch_loader is not None:
            return self.daily_change_batch_loader(tickers, market, snapshots)
        return self.daily_change_batch(tickers, market, snapshots)


@dataclass(frozen=True)
class PortfolioApiService:
    cached: Callable
    invalidate: Callable[[str], None]
    load_portfolio: Callable[[str], tuple[dict, list[dict]]]
    price_snapshot_batch: Callable[[list[str], str], dict]
    price_metrics_batch: Callable[[list[str], str], dict]
    daily_change_batch: Callable[[list[str], str, dict], dict]
    naver_kr_quote_batch: Callable[[list[str]], dict]
    utc_now_iso: Callable[[], str]

    def portfolio(self, market: str) -> dict:
        safe_market = self._market(market)
        sheet = f"{safe_market}_Final_Portfolio"

        def load() -> dict:
            meta, stocks = self.load_portfolio(sheet)
            return apply_localized_names({"meta": meta, "stocks": stocks})

        return self.cached(f"port_{safe_market}", load, ttl=60)

    def portfolio_prices(self, market: str, tickers: str = "", limit: int = 30, refresh: bool = False) -> dict:
        safe_market = self._market(market)
        clean_limit = max(1, min(int(limit or 30), 100))
        requested = [
            str(ticker).strip().upper()
            for ticker in str(tickers or "").split(",")
            if str(ticker).strip()
        ][:clean_limit]
        if not requested:
            _, stocks = self.load_portfolio(f"{safe_market}_Final_Portfolio")
            requested = [
                str(stock.get("Ticker") or "").strip().upper()
                for stock in stocks
                if str(stock.get("Ticker") or "").strip()
            ][:clean_limit]

        cache_requested = sorted(dict.fromkeys(requested))
        cache_key = "portfolio_prices_{}_{}".format(safe_market, ",".join(cache_requested))
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            snapshots = self.price_snapshot_batch(cache_requested, safe_market)
            missing = [ticker for ticker in cache_requested if ticker not in snapshots]
            batch_metrics: dict[str, tuple[float | None, float | None]] = (
                self.price_metrics_batch(missing, safe_market) if missing else {}
            )
            daily_changes = self.daily_change_batch(cache_requested, safe_market, snapshots)
            naver_kr_quotes = self.naver_kr_quote_batch(cache_requested) if safe_market == "KR" else {}
            metrics = []
            for ticker in cache_requested:
                snapshot = snapshots.get(ticker)
                if snapshot:
                    current_price = snapshot.get("current_price")
                    return_1m = snapshot.get("return_1m")
                    updated_at = snapshot.get("updated_at")
                else:
                    current_price, return_1m = batch_metrics.get(ticker, (None, None))
                    updated_at = None
                daily_change, daily_horizon = daily_changes.get(ticker, (None, ""))
                naver_quote = naver_kr_quotes.get(ticker)
                if naver_quote:
                    current_price = naver_quote.get("current_price") or current_price
                    daily_change = naver_quote.get("daily_change_pct")
                    daily_horizon = str(naver_quote.get("daily_change_horizon") or daily_horizon or "")
                    updated_at = naver_quote.get("updated_at") or updated_at
                metrics.append({
                    "Ticker": ticker,
                    "Current_Price": current_price,
                    "Return_1M": return_1m,
                    "Daily_Change_Pct": daily_change,
                    "Daily_Change_Horizon": daily_horizon,
                    "Price_Updated_At": updated_at,
                })
            return {
                "market": safe_market,
                "metrics": metrics,
                "source": "storage",
                "updated_at": self.utc_now_iso(),
            }

        return self.cached(cache_key, load, ttl=30)

    @staticmethod
    def _market(market: str) -> str:
        safe_market = str(market or "").upper()
        if safe_market not in ("US", "KR"):
            raise HTTPException(400, "market must be US or KR")
        return safe_market
