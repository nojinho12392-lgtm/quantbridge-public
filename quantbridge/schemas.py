"""Canonical sheet/table schemas for QuantBridge outputs."""

from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


Market = Literal["US", "KR"]

UNIVERSE_COLS = [
    "Ticker", "Name", "Market", "Sector", "MarketCap",
    "PER", "PBR", "ROE", "Revenue", "RevenueGrowth",
    "OperatingMargin", "GrossMargin", "DebtToEquity", "Last_Updated",
]

SCORED_COLS = [
    "Rank", "Ticker", "Name", "Market", "Sector", "MarketCap",
    "Value_Score", "Quality_Score", "Momentum_Score", "Total_Score",
    "Final_Score", "Score_Neutral",
    "Profitability_Quality", "Cash_Quality", "Growth_Quality",
    "BalanceSheet_Strength", "Valuation_Discipline", "Timing_Overlay",
    "Persistence_Quality",
    "Business_Quality_Score", "Investability_Score", "Quality_Data_Confidence",
    "Quality_Red_Flags",
    "Investability_Rank", "Business_Quality_Rank", "Quality_Rank_Delta",
    "Quality_Category",
    "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Debt_EBITDA", "PEG",
    "Last_Updated",
]

SCORED_COLS_ML = [
    "Rank", "Ticker", "Name", "Market", "Sector", "MarketCap",
    "Value_Score", "Quality_Score", "Momentum_Score", "Total_Score",
    "Final_Score", "Score_Neutral",
    "ML_Score", "Combined_Score",
    "Profitability_Quality", "Cash_Quality", "Growth_Quality",
    "BalanceSheet_Strength", "Valuation_Discipline", "Timing_Overlay",
    "Persistence_Quality",
    "Business_Quality_Score", "Investability_Score", "Quality_Data_Confidence",
    "Quality_Red_Flags",
    "Investability_Rank", "Business_Quality_Rank", "Quality_Rank_Delta",
    "Quality_Category",
    "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Debt_EBITDA", "PEG",
    "Last_Updated",
]

PORTFOLIO_COLS = [
    "Rank", "Ticker", "Name", "Market", "Sector", "MarketCap",
    "Weight(%)", "Current_Price", "Return_1M", "Total_Score", "ROIC", "RevGrowth",
    "GrossMargin", "Expected_Return", "Last_Updated",
]

SMALLCAP_COLS = [
    "Rank", "Ticker", "Name", "Market", "MarketCap",
    "ROIC", "RevGrowth", "Rev_Accel", "GrossMargin", "FCF_Margin",
    "Debt_EBITDA", "Volume_Surge", "SmallCap_Bonus", "Data_Confidence",
    "Total_Score", "Last_Updated",
]

SHEET_SCHEMAS = {
    "US_Universe": UNIVERSE_COLS,
    "KR_Universe": UNIVERSE_COLS,
    "US_Scored_Stocks": SCORED_COLS_ML,
    "KR_Scored_Stocks": SCORED_COLS,
    "US_Final_Portfolio": PORTFOLIO_COLS,
    "KR_Final_Portfolio": PORTFOLIO_COLS,
    "US_SmallCap_Gems": SMALLCAP_COLS,
    "KR_SmallCap_Gems": SMALLCAP_COLS,
}

STORAGE_SHEETS = [
    "US_Universe",
    "KR_Universe",
    "US_Scored_Stocks",
    "KR_Scored_Stocks",
    "US_Final_Portfolio",
    "KR_Final_Portfolio",
    "US_SmallCap_Gems",
    "KR_SmallCap_Gems",
    "US_Backtest_Results",
    "KR_Backtest_Results",
    "US_SmallCap_Backtest",
    "KR_SmallCap_Backtest",
    "US_Earnings_Momentum",
    "KR_Earnings_Momentum",
    "US_Industry_Ranking",
    "KR_Dual_Net_Buyers",
    "Earnings_Calendar",
    "Portfolio_Drift_Alert",
    "Factor_Attribution",
    "Factor_IC_Report",
    "Factor_IC_Detail",
    "Factor_Score_Snapshots",
    "Factor_Snapshot_Backfill_Log",
    "Signal_Quality_Gates",
    "Factor_Weight_Policy",
    "Factor_Policy_Backtest",
    "Factor_Remediation_Plan",
    "US_Policy_Adjusted_Ranking",
    "KR_Policy_Adjusted_Ranking",
    "Policy_Adjusted_Ranking_Summary",
    "Macro_Regime",
]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)


class UniverseRecord(StrictModel):
    Ticker: str
    Name: str = ""
    Market: Market
    Sector: str = ""
    MarketCap: float | None = None
    Last_Updated: str | date | None = None


class ScoredStockRecord(StrictModel):
    Rank: int | None = None
    Ticker: str
    Name: str = ""
    Market: Market
    Sector: str = ""
    MarketCap: float | None = None
    Total_Score: float | None = None
    Final_Score: float | None = None
    Score_Neutral: float | None = None


class PortfolioRecord(StrictModel):
    Rank: int | None = None
    Ticker: str
    Name: str = ""
    Market: Market
    Sector: str = ""
    MarketCap: float | None = None
    Weight_Pct: float | None = Field(default=None, alias="Weight(%)")
    Current_Price: float | None = None
    Return_1M: float | None = None
    Total_Score: float | None = None


class SmallCapRecord(StrictModel):
    Rank: int | None = None
    Ticker: str
    Name: str = ""
    Market: Market
    MarketCap: float | None = None
    Data_Confidence: float | None = None
    Total_Score: float | None = None


def missing_columns(sheet_name: str, columns: list[str]) -> list[str]:
    expected = SHEET_SCHEMAS.get(sheet_name)
    if expected is None:
        return []
    return [col for col in expected if col not in columns]


def ordered_columns(sheet_name: str, columns: list[str]) -> list[str]:
    expected = SHEET_SCHEMAS.get(sheet_name, [])
    extras = [col for col in columns if col not in expected]
    return [col for col in expected if col in columns] + extras
