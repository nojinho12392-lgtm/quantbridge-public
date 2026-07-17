from __future__ import annotations

from typing import Callable

import pandas as pd


class RiskReportBuilder:
    def __init__(
        self,
        *,
        load_storage_df: Callable[..., pd.DataFrame],
        spreadsheet: Callable[[], object],
        sheet_values_to_df: Callable[[list, list], pd.DataFrame],
        coerce: Callable[[pd.DataFrame, list[str]], pd.DataFrame],
        record_data_source: Callable[..., None],
        df_to_records: Callable[[pd.DataFrame], list[dict]],
        safe_float: Callable[[object], float | None],
        load_table: Callable[[str, list[str] | None], pd.DataFrame],
    ) -> None:
        self._load_storage_df = load_storage_df
        self._spreadsheet = spreadsheet
        self._sheet_values_to_df = sheet_values_to_df
        self._coerce = coerce
        self._record_data_source = record_data_source
        self._df_to_records = df_to_records
        self._safe_float = safe_float
        self._load_table = load_table

    def load_backtest_df(self, sheet_name: str) -> pd.DataFrame:
        market = "KR" if sheet_name.startswith("KR_") else "US"
        df = self._load_storage_df(sheet_name, market=market)
        if df.empty:
            try:
                data = self._spreadsheet().worksheet(sheet_name).get_all_values()
            except Exception as exc:
                self._record_data_source(
                    sheet_name,
                    "sheet_error",
                    market=market,
                    detail=f"{type(exc).__name__}: {exc}",
                )
                return pd.DataFrame()
            header_idx = next(
                (
                    idx for idx, row in enumerate(data)
                    if "Date" in [str(col).strip() for col in row]
                    and ("Net_Return" in row or "Return" in row or "Cumulative_Ret" in row)
                ),
                None,
            )
            if header_idx is None or header_idx + 1 >= len(data):
                self._record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
                return pd.DataFrame()
            df = self._sheet_values_to_df(data[header_idx + 1:], data[header_idx])
            self._record_data_source(sheet_name, "sheet", market=market, rows=len(df))

        if df.empty:
            return df
        if "Return" in df.columns and "Net_Return" not in df.columns:
            df = df.rename(columns={"Return": "Net_Return"})
        for col in ["Net_Return", "Cumulative_Ret", "Drawdown", "Gross_Return", "Turnover_Pct"]:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")
        if "Date" in df.columns:
            df["Date"] = pd.to_datetime(df["Date"], errors="coerce")
            df = df.dropna(subset=["Date"]).sort_values("Date")
        return df.reset_index(drop=True)

    def backtest_payload(self, sheet_name: str, market: str) -> dict:
        df = self.load_backtest_df(sheet_name)
        summary = {
            "Market": market,
            "Sheet": sheet_name,
            "Periods": int(len(df)),
            "Latest_Date": "",
            "Cumulative_Ret": None,
            "Max_Drawdown": None,
            "Avg_Return": None,
        }
        if not df.empty:
            if "Date" in df.columns:
                summary["Latest_Date"] = str(df["Date"].iloc[-1].date())
            if "Cumulative_Ret" in df.columns:
                values = df["Cumulative_Ret"].dropna()
                summary["Cumulative_Ret"] = self._safe_float(values.iloc[-1]) if not values.empty else None
            if "Drawdown" in df.columns:
                values = df["Drawdown"].dropna()
                summary["Max_Drawdown"] = self._safe_float(values.min()) if not values.empty else None
            if "Net_Return" in df.columns:
                values = df["Net_Return"].dropna()
                summary["Avg_Return"] = self._safe_float(values.mean()) if not values.empty else None
        rows = df.tail(80).copy()
        if "Date" in rows.columns:
            rows["Date"] = rows["Date"].dt.strftime("%Y-%m-%d")
        return {"summary": summary, "rows": self._df_to_records(rows)}

    def risk_drift_payload(self) -> dict:
        summary = self._load_storage_df("Portfolio_Drift_Summary", market="GLOBAL")
        detail = self._load_storage_df("Portfolio_Drift_Alert", market="GLOBAL")
        for df in (summary, detail):
            if df.empty:
                continue
            self._coerce(df, [
                "Target_Weight", "Current_Weight", "Drift_Abs", "Drift_Pct",
                "Return_Since_Rebal", "Total_Drift", "Stocks_Rebalance",
                "Stocks_Watch", "Stocks_OK", "Days_Since_Rebal",
            ])
        return {
            "summaries": self._df_to_records(summary),
            "items": self._df_to_records(detail.sort_values("Drift_Abs", ascending=False).head(60))
            if not detail.empty and "Drift_Abs" in detail.columns
            else self._df_to_records(detail.head(60)),
        }

    def load_sectioned_sheet_table(
        self,
        sheet_name: str,
        section_title: str,
        num_cols: list[str] | None = None,
    ) -> pd.DataFrame:
        try:
            values = self._spreadsheet().worksheet(sheet_name).get_all_values()
        except Exception:
            return pd.DataFrame()

        marker = f"-- {section_title} --"
        header_idx = None
        for idx, row in enumerate(values):
            first = str(row[0] if row else "").strip()
            if first == marker or first.strip("- ").strip() == section_title:
                header_idx = idx + 1
                break
        if header_idx is None or header_idx >= len(values):
            return pd.DataFrame()

        header = [str(col).strip() for col in values[header_idx]]
        rows = []
        for row in values[header_idx + 1:]:
            if not any(str(cell).strip() for cell in row):
                break
            first = str(row[0] if row else "").strip()
            if first.startswith("-- ") and first.endswith(" --"):
                break
            rows.append(row)
        if not rows:
            return pd.DataFrame(columns=header)

        df = self._sheet_values_to_df(rows, header)
        if num_cols:
            self._coerce(df, num_cols)
        return df.reset_index(drop=True)

    def load_report_dataset(
        self,
        dataset: str,
        *,
        market: str | None,
        num_cols: list[str],
        fallback_sheet: str | None = None,
        section_title: str | None = None,
    ) -> pd.DataFrame:
        df = self._load_storage_df(dataset, market=market)
        if df.empty and fallback_sheet and section_title:
            df = self.load_sectioned_sheet_table(fallback_sheet, section_title, num_cols)
        elif df.empty and fallback_sheet:
            df = self._load_table(fallback_sheet, num_cols)
        if not df.empty:
            self._coerce(df, num_cols)
        return df.reset_index(drop=True)

    def portfolio_risk_payload(self, market: str, limit: int = 30) -> dict:
        safe_market = market.upper()
        sheet = f"{safe_market}_Final_Portfolio_Risk"
        summary = self.load_report_dataset(
            f"{safe_market}_Final_Portfolio_Risk_Summary",
            market=safe_market,
            fallback_sheet=sheet,
            section_title="Portfolio Risk Summary",
            num_cols=["Value"],
        )
        holdings = self.load_report_dataset(
            f"{safe_market}_Final_Portfolio_Risk",
            market=safe_market,
            fallback_sheet=sheet,
            section_title="Holding Risk Contribution",
            num_cols=[
                "Portfolio_Weight", "Sleeve_Weight", "Asset_Vol", "Marginal_Risk",
                "Risk_Contribution", "Risk_Contribution_Pct", "Weight_Risk_Ratio",
                "Total_Score", "MarketCap",
            ],
        )
        sectors = self.load_report_dataset(
            f"{safe_market}_Final_Portfolio_Risk_Sectors",
            market=safe_market,
            fallback_sheet=sheet,
            section_title="Sector Risk Contribution",
            num_cols=[
                "Holdings", "Sector_Weight", "Sector_Risk_Contribution",
                "Sector_Risk_Contribution_Pct", "Max_Holding_Risk_Contribution_Pct",
            ],
        )
        if not holdings.empty and "Risk_Contribution_Pct" in holdings.columns:
            holdings = holdings.sort_values("Risk_Contribution_Pct", ascending=False)
        if not sectors.empty and "Sector_Risk_Contribution_Pct" in sectors.columns:
            sectors = sectors.sort_values("Sector_Risk_Contribution_Pct", ascending=False)
        safe_limit = max(1, min(int(limit or 30), 100))
        return {
            "market": safe_market,
            "summary": self._df_to_records(summary),
            "holdings": self._df_to_records(holdings.head(safe_limit)),
            "sectors": self._df_to_records(sectors.head(safe_limit)),
        }

    def rebalance_payload(self, market: str, limit: int = 50) -> dict:
        safe_market = market.upper()
        sheet = f"{safe_market}_Rebalance_Execution"
        summary = self.load_report_dataset(
            f"{safe_market}_Rebalance_Execution_Summary",
            market=safe_market,
            fallback_sheet=sheet,
            section_title="Rebalance Execution Summary",
            num_cols=[
                "Portfolio_Value", "Current_Cash_Value", "Target_Cash_Value",
                "Projected_Cash_Value", "Gross_Buy_Value", "Gross_Sell_Value",
                "Net_Cash_Needed", "One_Way_Turnover", "Gross_Turnover",
                "Trade_Count", "Buy_Count", "Sell_Count", "Hold_Count",
                "Estimated_Fees", "Estimated_Slippage", "Estimated_Total_Cost",
                "Rebalance_Band", "Min_Trade_Value",
            ],
        )
        orders = self.load_report_dataset(
            f"{safe_market}_Rebalance_Execution",
            market=safe_market,
            fallback_sheet=sheet,
            section_title="Suggested Orders",
            num_cols=[
                "Current_Weight", "Target_Weight", "Delta_Weight", "Current_Value",
                "Target_Value", "Trade_Value", "Executable_Trade_Value",
                "Estimated_Shares", "Price", "Fee_Est", "Slippage_Est", "Cost_Est",
            ],
        )
        if not orders.empty and "Executable_Trade_Value" in orders.columns:
            orders = orders.assign(_abs_trade=orders["Executable_Trade_Value"].abs())
            orders = orders.sort_values("_abs_trade", ascending=False).drop(columns=["_abs_trade"])
        safe_limit = max(1, min(int(limit or 50), 200))
        return {
            "market": safe_market,
            "summary": self._df_to_records(summary),
            "orders": self._df_to_records(orders.head(safe_limit)),
        }

    def shadow_attribution_payload(self, market: str = "ALL", limit: int = 50) -> dict:
        safe_market = str(market or "ALL").upper()
        summary = self.load_report_dataset(
            "Shadow_Portfolio_Attribution_Summary",
            market=None,
            fallback_sheet="Shadow_Portfolio_Attribution_Summary",
            num_cols=[
                "Horizon_Trading_Days", "Holdings", "Coverage", "Invested_Weight",
                "Cash_Weight", "Actual_Return", "Benchmark_Return", "Alpha_Actual",
                "Stock_Excess_Contribution", "Cash_Opportunity_Cost", "Explained_Alpha",
                "Top_Contribution", "Worst_Contribution", "Top_Sector_Contribution",
                "Worst_Sector_Contribution", "Hit_Rate", "Positive_Rate", "Score_Return_IC",
            ],
        )
        detail = self.load_report_dataset(
            "Shadow_Portfolio_Attribution",
            market=None,
            fallback_sheet="Shadow_Portfolio_Attribution",
            num_cols=[
                "Horizon_Trading_Days", "Rank", "Weight", "Sleeve_Weight", "Equal_Weight",
                "Total_Score", "Start_Price", "End_Price", "Stock_Return",
                "Benchmark_Return", "Actual_Contribution", "Sleeve_Contribution",
                "Equal_Contribution", "Benchmark_Contribution", "Excess_Contribution",
            ],
        )
        sectors = self.load_report_dataset(
            "Shadow_Portfolio_Sector_Attribution",
            market=None,
            fallback_sheet="Shadow_Portfolio_Sector_Attribution",
            num_cols=[
                "Horizon_Trading_Days", "Holdings", "Coverage", "Sector_Weight",
                "Sector_Sleeve_Weight", "Mean_Return", "Weighted_Return",
                "Benchmark_Return", "Actual_Contribution", "Sleeve_Contribution",
                "Benchmark_Contribution", "Excess_Contribution", "Hit_Rate",
            ],
        )
        if safe_market in {"US", "KR"}:
            for frame in (summary, detail, sectors):
                if not frame.empty and "Market" in frame.columns:
                    frame.drop(frame[frame["Market"].astype(str).str.upper() != safe_market].index, inplace=True)
        if not detail.empty and "Actual_Contribution" in detail.columns:
            detail = detail.assign(_abs_contribution=detail["Actual_Contribution"].abs())
            detail = detail.sort_values("_abs_contribution", ascending=False).drop(columns=["_abs_contribution"])
        if not sectors.empty and "Actual_Contribution" in sectors.columns:
            sectors = sectors.assign(_abs_contribution=sectors["Actual_Contribution"].abs())
            sectors = sectors.sort_values("_abs_contribution", ascending=False).drop(columns=["_abs_contribution"])
        safe_limit = max(1, min(int(limit or 50), 200))
        return {
            "market": safe_market,
            "summary": self._df_to_records(summary.head(safe_limit)),
            "items": self._df_to_records(detail.head(safe_limit)),
            "sectors": self._df_to_records(sectors.head(safe_limit)),
        }

    def industry_payload(self, limit: int = 30) -> dict:
        df = self._load_table("US_Industry_Ranking", [
            "Rank", "Stock_Count", "Mean_Return", "Breadth",
            "Mean_Return_Rank", "Breadth_Rank", "Combined_Rank", "Lookback_Days",
        ])
        if not df.empty and "Rank" in df.columns:
            df = df.sort_values("Rank")
        return {"items": self._df_to_records(df.head(max(1, min(limit, 100))))}

    def order_flow_payload(self, limit: int = 30) -> dict:
        df = self._load_table("KR_Dual_Net_Buyers", ["Rank", "Consecutive_Days", "Foreign_Net_Buy", "Inst_Net_Buy"])
        if not df.empty and "Rank" in df.columns:
            df = df.sort_values("Rank")
        return {"items": self._df_to_records(df.head(max(1, min(limit, 100))))}
