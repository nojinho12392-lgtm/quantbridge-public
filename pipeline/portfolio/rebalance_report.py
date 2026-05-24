from __future__ import annotations

from dataclasses import dataclass
import math
import os

import numpy as np
import pandas as pd

from pipeline.backtest.costs import liquidity_slippage_rate


REBALANCE_ORDER_COLS = [
    "Market",
    "Ticker",
    "Name",
    "Sector",
    "Action",
    "Current_Weight",
    "Target_Weight",
    "Delta_Weight",
    "Current_Value",
    "Target_Value",
    "Trade_Value",
    "Executable_Trade_Value",
    "Estimated_Shares",
    "Price",
    "Fee_Est",
    "Slippage_Est",
    "Cost_Est",
    "Reason",
    "Warnings",
    "Generated_At",
]

REBALANCE_SUMMARY_COLS = [
    "Market",
    "Portfolio_Value",
    "Current_Cash_Value",
    "Target_Cash_Value",
    "Projected_Cash_Value",
    "Gross_Buy_Value",
    "Gross_Sell_Value",
    "Net_Cash_Needed",
    "One_Way_Turnover",
    "Gross_Turnover",
    "Trade_Count",
    "Buy_Count",
    "Sell_Count",
    "Hold_Count",
    "Estimated_Fees",
    "Estimated_Slippage",
    "Estimated_Total_Cost",
    "Rebalance_Band",
    "Min_Trade_Value",
    "Status",
    "Warnings",
    "Generated_At",
]

CASH_LABELS = {"CASH", "CASH_USD", "CASH_KRW", "USD", "KRW", "현금"}


@dataclass(frozen=True)
class RebalanceConfig:
    portfolio_value: float = 100_000.0
    cash_value: float | None = None
    rebalance_band: float = 0.005
    min_trade_value: float = 50.0
    fee_rate: float = 0.001
    fractional_shares: bool = False
    lot_size: int = 1
    max_turnover_warn: float = 0.30
    max_cost_warn: float = 0.005
    max_trade_weight_warn: float = 0.10


def rebalance_config_from_env(
    market: str,
    *,
    default_portfolio_value: float,
    default_min_trade_value: float,
    default_fee_rate: float,
    default_fractional_shares: bool = False,
) -> RebalanceConfig:
    market_name = str(market).upper()
    prefix = f"QUANT_{market_name}"
    return RebalanceConfig(
        portfolio_value=_env_float(f"{prefix}_PORTFOLIO_VALUE", default_portfolio_value),
        cash_value=_env_optional_float(f"{prefix}_CASH_VALUE"),
        rebalance_band=_env_float(f"{prefix}_REBALANCE_BAND", 0.005),
        min_trade_value=_env_float(f"{prefix}_MIN_TRADE_VALUE", default_min_trade_value),
        fee_rate=_env_float(f"{prefix}_TRADE_FEE_RATE", default_fee_rate),
        fractional_shares=_env_bool(f"{prefix}_ALLOW_FRACTIONAL_SHARES", default_fractional_shares),
        lot_size=int(_env_float(f"{prefix}_LOT_SIZE", 1)),
        max_turnover_warn=_env_float(f"{prefix}_MAX_REBALANCE_TURNOVER_WARN", 0.30),
        max_cost_warn=_env_float(f"{prefix}_MAX_REBALANCE_COST_WARN", 0.005),
        max_trade_weight_warn=_env_float(f"{prefix}_MAX_TRADE_WEIGHT_WARN", 0.10),
    )


def read_current_holdings_sheet(spreadsheet, market: str) -> pd.DataFrame:
    """Read optional actual holdings from Sheets.

    Supported sheet names are, in order:
      - US_Current_Holdings / KR_Current_Holdings
      - Current_Holdings_US / Current_Holdings_KR
      - Current_Holdings

    Expected columns are intentionally forgiving: Ticker plus any of Shares,
    Quantity, Qty, Current_Price, Price, Market_Value, Value, Weight(%), Weight,
    Name, Sector, or Cash.
    """

    market_name = str(market).upper()
    titles = [
        f"{market_name}_Current_Holdings",
        f"Current_Holdings_{market_name}",
        "Current_Holdings",
    ]
    for title in titles:
        try:
            values = spreadsheet.worksheet(title).get_all_values()
        except Exception:
            continue
        frame = _frame_from_values(values, required_header="Ticker")
        if frame.empty:
            continue
        if "Market" in frame.columns:
            frame = frame[frame["Market"].astype(str).str.upper().str.strip().isin(["", market_name])].copy()
        return frame.reset_index(drop=True)
    return pd.DataFrame()


def read_previous_portfolio_sheet(spreadsheet, sheet_name: str) -> pd.DataFrame:
    """Read the current Final_Portfolio sheet before it is overwritten."""

    try:
        values = spreadsheet.worksheet(sheet_name).get_all_values()
    except Exception:
        return pd.DataFrame()
    return _frame_from_values(values, required_header="Ticker")


def build_rebalance_report(
    *,
    market: str,
    target_portfolio: pd.DataFrame,
    current_holdings: pd.DataFrame | None = None,
    previous_portfolio: pd.DataFrame | None = None,
    generated_at: pd.Timestamp | None = None,
    config: RebalanceConfig | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    config = config or RebalanceConfig()
    generated_at = generated_at or pd.Timestamp.now()
    generated = generated_at.strftime("%Y-%m-%d %H:%M:%S")
    market_name = str(market).upper()

    targets = _target_frame(target_portfolio, market_name)
    if targets.empty:
        return _empty_summary(market_name, generated), _empty_orders()

    current, portfolio_value, current_cash = _current_frame(
        market=market_name,
        current_holdings=current_holdings,
        previous_portfolio=previous_portfolio,
        targets=targets,
        config=config,
    )
    if portfolio_value <= 0:
        portfolio_value = max(float(config.portfolio_value), 1.0)

    target_weight_sum = float(targets["Target_Weight"].sum())
    target_cash_value = max(0.0, portfolio_value * (1.0 - target_weight_sum))

    index = sorted(set(targets.index) | set(current.index))
    records: list[dict] = []
    for ticker in index:
        target_row = targets.loc[ticker] if ticker in targets.index else pd.Series(dtype=object)
        current_row = current.loc[ticker] if ticker in current.index else pd.Series(dtype=object)

        target_weight = _finite_float(target_row.get("Target_Weight"), 0.0)
        current_value = _finite_float(current_row.get("Current_Value"), 0.0)
        current_weight = current_value / portfolio_value if portfolio_value else 0.0
        target_value = target_weight * portfolio_value
        raw_trade_value = target_value - current_value
        delta_weight = target_weight - current_weight

        price = _first_valid_number(
            target_row.get("Price"),
            current_row.get("Price"),
        )
        market_cap = _first_valid_number(
            target_row.get("MarketCap"),
            current_row.get("MarketCap"),
        )
        slippage_rate = liquidity_slippage_rate(
            market=market_name,
            market_cap=market_cap,
            trading_value_20d=_first_valid_number(target_row.get("TradingValue_20D"), current_row.get("TradingValue_20D")),
        )

        action, reason = _trade_action(raw_trade_value, delta_weight, config)
        shares, executable_trade_value, row_warnings = _executable_trade(
            raw_trade_value=raw_trade_value,
            price=price,
            action=action,
            config=config,
        )
        if action == "HOLD":
            executable_trade_value = 0.0
            shares = np.nan
        elif abs(executable_trade_value) <= 0:
            action = "HOLD"
            reason = "rounded_to_zero"
            row_warnings.append("ROUNDING_TO_ZERO")

        abs_executable = abs(executable_trade_value)
        fee = abs_executable * config.fee_rate
        slippage = abs_executable * slippage_rate
        cost = fee + slippage
        if price is None:
            row_warnings.append("MISSING_PRICE")
        if portfolio_value and abs_executable / portfolio_value >= config.max_trade_weight_warn:
            row_warnings.append("HIGH_TRADE_WEIGHT")

        records.append(
            {
                "Market": market_name,
                "Ticker": ticker,
                "Name": _first_text(target_row.get("Name"), current_row.get("Name")),
                "Sector": _first_text(target_row.get("Sector"), current_row.get("Sector")),
                "Action": action,
                "Current_Weight": round(current_weight, 6),
                "Target_Weight": round(target_weight, 6),
                "Delta_Weight": round(delta_weight, 6),
                "Current_Value": round(current_value, 2),
                "Target_Value": round(target_value, 2),
                "Trade_Value": round(raw_trade_value, 2),
                "Executable_Trade_Value": round(executable_trade_value, 2),
                "Estimated_Shares": "" if pd.isna(shares) else shares,
                "Price": "" if price is None else round(price, 4),
                "Fee_Est": round(fee, 2),
                "Slippage_Est": round(slippage, 2),
                "Cost_Est": round(cost, 2),
                "Reason": reason,
                "Warnings": ";".join(sorted(set(row_warnings))),
                "Generated_At": generated,
            }
        )

    orders = pd.DataFrame(records)
    if orders.empty:
        return _empty_summary(market_name, generated), _empty_orders()

    orders = orders.sort_values(
        by=["Action", "Executable_Trade_Value"],
        key=lambda col: col.map(_sort_action) if col.name == "Action" else col.abs(),
        ascending=[True, False],
    ).reset_index(drop=True)

    summary = _summary_report(
        market=market_name,
        portfolio_value=portfolio_value,
        current_cash=current_cash,
        target_cash=target_cash_value,
        orders=orders,
        generated=generated,
        config=config,
    )
    return summary, orders[REBALANCE_ORDER_COLS]


def write_rebalance_report_sheet(
    spreadsheet,
    title: str,
    summary: pd.DataFrame,
    orders: pd.DataFrame,
) -> None:
    rows = _section_rows("Rebalance Execution Summary", summary)
    rows += [[""]]
    rows += _section_rows("Suggested Orders", orders)

    width = max(len(row) for row in rows) if rows else 1
    padded = [row + [""] * (width - len(row)) for row in rows]
    try:
        worksheet = spreadsheet.worksheet(title)
    except Exception:
        worksheet = spreadsheet.add_worksheet(title=title, rows=max(len(padded) + 10, 100), cols=max(width + 2, 10))

    worksheet.clear()
    worksheet.update(range_name="A1", values=padded, value_input_option="USER_ENTERED")


def _target_frame(target_portfolio: pd.DataFrame, market: str) -> pd.DataFrame:
    if target_portfolio is None or target_portfolio.empty or "Ticker" not in target_portfolio.columns:
        return pd.DataFrame()
    frame = target_portfolio.copy()
    frame["Ticker"] = frame["Ticker"].astype(str).str.strip()
    frame = frame[frame["Ticker"] != ""].drop_duplicates(subset=["Ticker"], keep="first")
    weight_col = _first_existing(frame, ["Weight(%)", "Target_Weight", "Weight", "Portfolio_Weight"])
    if weight_col is None:
        return pd.DataFrame()
    out = pd.DataFrame(index=frame["Ticker"])
    out["Target_Weight"] = frame[weight_col].map(_parse_weight).fillna(0.0).values
    out["Name"] = _col_or_blank(frame, "Name")
    out["Sector"] = _col_or_blank(frame, "Sector")
    out["Price"] = _col_numeric(frame, ["Current_Price", "Price", "Close", "Last_Price"])
    out["MarketCap"] = _col_numeric(frame, ["MarketCap", "Market_Cap"])
    out["TradingValue_20D"] = _col_numeric(frame, ["TradingValue_20D", "Trading_Value_20D"])
    out["Market"] = market
    return out[out["Target_Weight"] > 0]


def _current_frame(
    *,
    market: str,
    current_holdings: pd.DataFrame | None,
    previous_portfolio: pd.DataFrame | None,
    targets: pd.DataFrame,
    config: RebalanceConfig,
) -> tuple[pd.DataFrame, float, float]:
    current = current_holdings if current_holdings is not None else pd.DataFrame()
    if current is not None and not current.empty and "Ticker" in current.columns:
        return _actual_current_frame(current, targets, config)
    previous = previous_portfolio if previous_portfolio is not None else pd.DataFrame()
    if previous is not None and not previous.empty and "Ticker" in previous.columns:
        return _previous_portfolio_frame(previous, targets, config)
    portfolio_value = max(_finite_float(config.portfolio_value, 0.0), 1.0)
    cash_value = portfolio_value if config.cash_value is None else _finite_float(config.cash_value, 0.0)
    return pd.DataFrame(), portfolio_value, cash_value


def _actual_current_frame(
    frame: pd.DataFrame,
    targets: pd.DataFrame,
    config: RebalanceConfig,
) -> tuple[pd.DataFrame, float, float]:
    clean = frame.copy()
    clean["Ticker"] = clean["Ticker"].astype(str).str.strip()
    clean = clean[clean["Ticker"] != ""].copy()
    ticker_upper = clean["Ticker"].str.upper()
    cash_rows = clean[ticker_upper.isin(CASH_LABELS)]
    security_rows = clean[~ticker_upper.isin(CASH_LABELS)].copy()

    cash_value = 0.0
    for _, row in cash_rows.iterrows():
        cash_value += _first_valid_number(row.get("Cash")) or _row_value(row, targets, config, allow_weight=False)
    if config.cash_value is not None:
        cash_value = _finite_float(config.cash_value, 0.0)

    values = []
    for _, row in security_rows.iterrows():
        ticker = str(row.get("Ticker") or "").strip()
        price = _first_valid_number(
            row.get("Current_Price"),
            row.get("Price"),
            row.get("Last_Price"),
            targets.loc[ticker, "Price"] if ticker in targets.index else None,
        )
        value = _row_value(row, targets, config, allow_weight=True)
        values.append(
            {
                "Ticker": ticker,
                "Current_Value": value,
                "Price": price,
                "Name": _first_text(row.get("Name"), targets.loc[ticker, "Name"] if ticker in targets.index else ""),
                "Sector": _first_text(row.get("Sector"), targets.loc[ticker, "Sector"] if ticker in targets.index else ""),
                "MarketCap": _first_valid_number(row.get("MarketCap"), targets.loc[ticker, "MarketCap"] if ticker in targets.index else None),
                "TradingValue_20D": _first_valid_number(row.get("TradingValue_20D"), targets.loc[ticker, "TradingValue_20D"] if ticker in targets.index else None),
            }
        )
    out = pd.DataFrame(values)
    if out.empty:
        invested = 0.0
        out = pd.DataFrame(columns=["Ticker", "Current_Value", "Price", "Name", "Sector", "MarketCap", "TradingValue_20D"])
    else:
        out = out.groupby("Ticker", as_index=False).agg(
            Current_Value=("Current_Value", "sum"),
            Price=("Price", "first"),
            Name=("Name", "first"),
            Sector=("Sector", "first"),
            MarketCap=("MarketCap", "first"),
            TradingValue_20D=("TradingValue_20D", "first"),
        )
        invested = float(out["Current_Value"].sum())

    explicit_total = _sheet_total_value(clean)
    if explicit_total is not None:
        portfolio_value = explicit_total
    elif _uses_weight_only_holdings(security_rows):
        portfolio_value = _finite_float(config.portfolio_value, invested + cash_value)
    else:
        portfolio_value = invested + cash_value
    if portfolio_value <= 0:
        portfolio_value = _finite_float(config.portfolio_value, 0.0)
    if config.cash_value is None and portfolio_value > invested + cash_value:
        cash_value = portfolio_value - invested
    return out.set_index("Ticker"), max(portfolio_value, 1.0), max(cash_value, 0.0)


def _previous_portfolio_frame(
    frame: pd.DataFrame,
    targets: pd.DataFrame,
    config: RebalanceConfig,
) -> tuple[pd.DataFrame, float, float]:
    clean = frame.copy()
    clean["Ticker"] = clean["Ticker"].astype(str).str.strip()
    clean = clean[clean["Ticker"] != ""].drop_duplicates(subset=["Ticker"], keep="first")
    weight_col = _first_existing(clean, ["Weight(%)", "Current_Weight", "Weight", "Portfolio_Weight"])
    if weight_col is None:
        portfolio_value = max(_finite_float(config.portfolio_value, 0.0), 1.0)
        return pd.DataFrame(), portfolio_value, portfolio_value if config.cash_value is None else _finite_float(config.cash_value, 0.0)

    portfolio_value = max(_finite_float(config.portfolio_value, 0.0), 1.0)
    weights = clean[weight_col].map(_parse_weight).fillna(0.0)
    out = pd.DataFrame(index=clean["Ticker"])
    out["Current_Value"] = weights.values * portfolio_value
    out["Price"] = _col_numeric(clean, ["Current_Price", "Price", "Close", "Last_Price"])
    out["Name"] = _col_or_blank(clean, "Name")
    out["Sector"] = _col_or_blank(clean, "Sector")
    out["MarketCap"] = _col_numeric(clean, ["MarketCap", "Market_Cap"])
    out["TradingValue_20D"] = _col_numeric(clean, ["TradingValue_20D", "Trading_Value_20D"])
    for ticker in out.index:
        if ticker in targets.index:
            if pd.isna(out.at[ticker, "Price"]):
                out.at[ticker, "Price"] = targets.at[ticker, "Price"]
            if pd.isna(out.at[ticker, "MarketCap"]):
                out.at[ticker, "MarketCap"] = targets.at[ticker, "MarketCap"]
            if not str(out.at[ticker, "Name"] or "").strip():
                out.at[ticker, "Name"] = targets.at[ticker, "Name"]
            if not str(out.at[ticker, "Sector"] or "").strip():
                out.at[ticker, "Sector"] = targets.at[ticker, "Sector"]
    invested = float(out["Current_Value"].sum())
    inferred_cash = max(0.0, portfolio_value - invested)
    cash_value = inferred_cash if config.cash_value is None else _finite_float(config.cash_value, 0.0)
    return out, portfolio_value, cash_value


def _row_value(row: pd.Series, targets: pd.DataFrame, config: RebalanceConfig, *, allow_weight: bool) -> float:
    value = _first_valid_number(row.get("Market_Value"), row.get("Value"), row.get("Current_Value"))
    if value is not None:
        return value
    shares = _first_valid_number(row.get("Shares"), row.get("Quantity"), row.get("Qty"))
    ticker = str(row.get("Ticker") or "").strip()
    price = _first_valid_number(
        row.get("Current_Price"),
        row.get("Price"),
        row.get("Last_Price"),
        targets.loc[ticker, "Price"] if ticker in targets.index else None,
    )
    if shares is not None and price is not None:
        return shares * price
    if allow_weight:
        weight_col_value = _first_existing_value(row, ["Weight(%)", "Weight", "Current_Weight"])
        weight = _parse_weight(weight_col_value)
        if weight is not None:
            return weight * _finite_float(config.portfolio_value, 0.0)
    return 0.0


def _sheet_total_value(frame: pd.DataFrame) -> float | None:
    for column in ["Portfolio_Value", "Total_Portfolio_Value", "Total_Equity", "Account_Value"]:
        if column in frame.columns:
            value = _first_valid_number(*frame[column].tolist())
            if value is not None and value > 0:
                return value
    return None


def _uses_weight_only_holdings(frame: pd.DataFrame) -> bool:
    has_weight = _first_existing(frame, ["Weight(%)", "Weight", "Current_Weight"]) is not None
    has_value = _first_existing(frame, ["Market_Value", "Value", "Current_Value"]) is not None
    has_shares = _first_existing(frame, ["Shares", "Quantity", "Qty"]) is not None
    return has_weight and not has_value and not has_shares


def _trade_action(raw_trade_value: float, delta_weight: float, config: RebalanceConfig) -> tuple[str, str]:
    if abs(delta_weight) <= config.rebalance_band:
        return "HOLD", "within_band"
    if abs(raw_trade_value) < config.min_trade_value:
        return "HOLD", "below_min_trade"
    return ("BUY", "trade") if raw_trade_value > 0 else ("SELL", "trade")


def _executable_trade(
    *,
    raw_trade_value: float,
    price: float | None,
    action: str,
    config: RebalanceConfig,
) -> tuple[float, float, list[str]]:
    if action == "HOLD":
        return np.nan, 0.0, []
    if price is None or price <= 0:
        return np.nan, raw_trade_value, []
    raw_shares = raw_trade_value / price
    if config.fractional_shares:
        shares = round(raw_shares, 6)
    else:
        lot = max(int(config.lot_size), 1)
        shares_abs = math.floor(abs(raw_shares) / lot) * lot
        shares = shares_abs if raw_shares > 0 else -shares_abs
    executable = shares * price
    return shares, executable, []


def _summary_report(
    *,
    market: str,
    portfolio_value: float,
    current_cash: float,
    target_cash: float,
    orders: pd.DataFrame,
    generated: str,
    config: RebalanceConfig,
) -> pd.DataFrame:
    active = orders[orders["Action"].isin(["BUY", "SELL"])].copy()
    buys = active[active["Action"].eq("BUY")]
    sells = active[active["Action"].eq("SELL")]
    gross_buy = float(buys["Executable_Trade_Value"].abs().sum()) if not buys.empty else 0.0
    gross_sell = float(sells["Executable_Trade_Value"].abs().sum()) if not sells.empty else 0.0
    fees = float(active["Fee_Est"].sum()) if not active.empty else 0.0
    slippage = float(active["Slippage_Est"].sum()) if not active.empty else 0.0
    total_cost = fees + slippage
    projected_cash = current_cash + gross_sell - gross_buy - total_cost
    one_way_turnover = max(gross_buy, gross_sell) / portfolio_value if portfolio_value else 0.0
    gross_turnover = (gross_buy + gross_sell) / portfolio_value if portfolio_value else 0.0
    net_cash_needed = max(0.0, gross_buy + total_cost - gross_sell - current_cash)
    cost_weight = total_cost / portfolio_value if portfolio_value else 0.0

    warnings = []
    if one_way_turnover >= config.max_turnover_warn:
        warnings.append("HIGH_TURNOVER")
    if projected_cash < -config.min_trade_value:
        warnings.append("INSUFFICIENT_CASH")
    if cost_weight >= config.max_cost_warn:
        warnings.append("HIGH_COST")
    status = "WARN" if warnings else "OK"

    row = {
        "Market": market,
        "Portfolio_Value": round(portfolio_value, 2),
        "Current_Cash_Value": round(current_cash, 2),
        "Target_Cash_Value": round(target_cash, 2),
        "Projected_Cash_Value": round(projected_cash, 2),
        "Gross_Buy_Value": round(gross_buy, 2),
        "Gross_Sell_Value": round(gross_sell, 2),
        "Net_Cash_Needed": round(net_cash_needed, 2),
        "One_Way_Turnover": round(one_way_turnover, 6),
        "Gross_Turnover": round(gross_turnover, 6),
        "Trade_Count": int(len(active)),
        "Buy_Count": int(len(buys)),
        "Sell_Count": int(len(sells)),
        "Hold_Count": int(orders["Action"].eq("HOLD").sum()),
        "Estimated_Fees": round(fees, 2),
        "Estimated_Slippage": round(slippage, 2),
        "Estimated_Total_Cost": round(total_cost, 2),
        "Rebalance_Band": config.rebalance_band,
        "Min_Trade_Value": config.min_trade_value,
        "Status": status,
        "Warnings": ";".join(warnings),
        "Generated_At": generated,
    }
    return pd.DataFrame([row], columns=REBALANCE_SUMMARY_COLS)


def _frame_from_values(values: list[list[str]], *, required_header: str) -> pd.DataFrame:
    if not values:
        return pd.DataFrame()
    header_idx = None
    for idx, row in enumerate(values):
        normalized = [str(col).strip() for col in row]
        if required_header in normalized:
            header_idx = idx
            break
    if header_idx is None:
        return pd.DataFrame()
    header = [str(col).strip() for col in values[header_idx]]
    data = [row for row in values[header_idx + 1 :] if any(str(cell).strip() for cell in row)]
    if not data:
        return pd.DataFrame(columns=header)
    width = len(header)
    padded = [row + [""] * (width - len(row)) for row in data]
    return pd.DataFrame(padded, columns=header)


def _first_existing(frame: pd.DataFrame, names: list[str]) -> str | None:
    for name in names:
        if name in frame.columns:
            return name
    return None


def _first_existing_value(row: pd.Series, names: list[str]):
    for name in names:
        if name in row.index:
            value = row.get(name)
            if str(value or "").strip() != "":
                return value
    return None


def _col_or_blank(frame: pd.DataFrame, column: str) -> pd.Series:
    if column in frame.columns:
        return frame[column].fillna("").astype(str).values
    return pd.Series([""] * len(frame), index=frame.index).values


def _col_numeric(frame: pd.DataFrame, names: list[str]) -> pd.Series:
    for name in names:
        if name in frame.columns:
            return frame[name].map(_parse_number).values
    return pd.Series([np.nan] * len(frame), index=frame.index).values


def _parse_weight(value: object) -> float | None:
    number = _parse_number(value)
    if number is None:
        return None
    text = str(value or "").strip()
    if text.endswith("%"):
        return number / 100.0
    if abs(number) > 1.5:
        return number / 100.0
    return number


def _parse_number(value: object) -> float | None:
    try:
        if value is None or pd.isna(value):
            return None
        text = str(value).strip()
        if text == "":
            return None
        text = text.replace(",", "").replace("$", "").replace("₩", "").replace("%", "")
        number = float(text)
        if not math.isfinite(number):
            return None
        return number
    except Exception:
        return None


def _env_float(name: str, default: float) -> float:
    value = _parse_number(os.environ.get(name))
    return default if value is None else value


def _env_optional_float(name: str) -> float | None:
    return _parse_number(os.environ.get(name))


def _env_bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None or str(value).strip() == "":
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


def _finite_float(value: object, default: float) -> float:
    parsed = _parse_number(value)
    return default if parsed is None else parsed


def _first_valid_number(*values: object) -> float | None:
    for value in values:
        parsed = _parse_number(value)
        if parsed is not None:
            return parsed
    return None


def _first_text(*values: object) -> str:
    for value in values:
        text = str(value or "").strip()
        if text and text.lower() not in {"nan", "none"}:
            return text
    return ""


def _sort_action(value: str) -> int:
    return {"BUY": 0, "SELL": 1, "HOLD": 2}.get(str(value), 3)


def _section_rows(title: str, df: pd.DataFrame) -> list[list[str]]:
    if df is None or df.empty:
        return [[f"-- {title} --"], ["Status"], ["No data"]]
    clean = df.copy()
    clean = clean.astype(object).where(pd.notna(clean), "")
    return [[f"-- {title} --"], clean.columns.tolist()] + clean.astype(str).values.tolist()


def _empty_orders() -> pd.DataFrame:
    return pd.DataFrame(columns=REBALANCE_ORDER_COLS)


def _empty_summary(market: str, generated: str) -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "Market": market,
                "Portfolio_Value": 0.0,
                "Current_Cash_Value": 0.0,
                "Target_Cash_Value": 0.0,
                "Projected_Cash_Value": 0.0,
                "Gross_Buy_Value": 0.0,
                "Gross_Sell_Value": 0.0,
                "Net_Cash_Needed": 0.0,
                "One_Way_Turnover": 0.0,
                "Gross_Turnover": 0.0,
                "Trade_Count": 0,
                "Buy_Count": 0,
                "Sell_Count": 0,
                "Hold_Count": 0,
                "Estimated_Fees": 0.0,
                "Estimated_Slippage": 0.0,
                "Estimated_Total_Cost": 0.0,
                "Rebalance_Band": 0.0,
                "Min_Trade_Value": 0.0,
                "Status": "WARN",
                "Warnings": "NO_TARGET_PORTFOLIO",
                "Generated_At": generated,
            }
        ],
        columns=REBALANCE_SUMMARY_COLS,
    )
