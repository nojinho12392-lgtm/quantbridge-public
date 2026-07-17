"""Mobile API v1 contract notes.

This module is intentionally lightweight: it gives the iOS, Android, and API
tests one stable place to assert endpoint paths and response field names.
"""

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, RootModel

MOBILE_API_VERSION = "mobile-v1"

ENDPOINTS = {
    "portfolio": {
        "path": "/portfolio/{market}",
        "markets": ["US", "KR"],
        "response": {
            "root": ["meta", "stocks"],
            "stock": ["Ticker", "Name", "Rank", "Current_Price", "Return_1M", "MarketCap"],
        },
    },
    "portfolio_prices": {
        "path": "/portfolio/{market}/prices",
        "markets": ["US", "KR"],
        "query": ["tickers", "limit", "refresh"],
        "response": {
            "root": ["market", "metrics", "source", "updated_at"],
            "metric": [
                "Ticker",
                "Current_Price",
                "Return_1M",
                "Daily_Change_Pct",
                "Daily_Change_Horizon",
                "Price_Updated_At",
            ],
        },
    },
    "sector_themes": {
        "path": "/sectors/themes",
        "markets": ["ALL", "US", "KR"],
        "query": ["market", "limit", "members", "refresh"],
        "response": {
            "root": ["market", "generated_at", "count", "items"],
            "theme": [
                "label",
                "member_count",
                "priced_count",
                "missing_price_count",
                "price_coverage_ratio",
                "weighting_method",
                "rising_count",
                "falling_count",
                "avg_change_pct",
                "avg_return_1m",
                "leader",
                "members",
            ],
            "member": [
                "Ticker",
                "Name",
                "Currency",
                "MarketCap",
                "Current_Price",
                "Daily_Change_Pct",
                "Daily_Change_Horizon",
                "Return_1M",
                "Score_Value",
            ],
        },
    },
    "search_universe": {
        "path": "/search/universe",
        "query": ["q", "market", "limit"],
        "response": {
            "root": ["stocks", "count", "query", "market", "groups"],
            "stock": ["Ticker", "Name", "Market", "Sector", "MarketCap", "In_Portfolio", "In_SmallCap", "Currency"],
            "group": ["label", "count", "tickers"],
        },
    },
    "scored": {
        "path": "/scored/{market}",
        "markets": ["US", "KR"],
        "query": ["limit"],
        "response": {
            "root": ["stocks", "count", "total_count", "generated_at", "source"],
            "stock": [
                "Ticker",
                "Name",
                "Market",
                "Rank",
                "Final_Score",
                "Investability_Score",
                "Business_Quality_Score",
                "Quality_Data_Confidence",
                "Quality_Red_Flags",
                "Quality_Category",
            ],
        },
    },
    "blind_financial_quiz": {
        "path": "/training/blind-financial-quiz",
        "markets": ["ALL", "US", "KR"],
        "query": ["quiz_id", "market", "refresh"],
        "response": {
            "root": ["id", "title", "prompt", "correct_option_id", "answer_rule", "options"],
            "option": ["id", "blind_label", "metrics", "company"],
            "metric": ["label", "value", "tone"],
            "company": ["ticker", "name", "market", "currency", "logo_url", "price_points", "three_year_return_pct"],
            "price_point": ["date", "return_pct"],
        },
    },
    "policy_adjusted_ranking": {
        "path": "/research/policy-adjusted-ranking",
        "markets": ["US", "KR"],
        "query": ["market", "limit"],
        "response": {
            "root": ["market", "items", "count", "summary", "top_up", "top_down", "source", "mode"],
            "item": [
                "Ticker",
                "Name",
                "Market",
                "Policy_Rank",
                "Base_Rank",
                "Rank_Change",
                "Policy_Final_Score",
                "Base_Final_Score",
                "Policy_Actions",
                "Policy_Mode",
            ],
            "summary": ["Rows", "Top_Up_Ticker", "Top_Down_Ticker", "Mean_Abs_Rank_Change"],
        },
    },
    "etfs": {
        "path": "/etfs",
        "markets": ["ALL", "US", "KR"],
        "query": ["market", "category", "q", "limit", "refresh"],
        "response": {
            "root": ["items", "count", "source"],
            "item": [
                "ticker",
                "name",
                "market",
                "category",
                "price",
                "changePct",
                "updatedAt",
                "topHoldings",
            ],
        },
    },
    "etf_detail": {
        "path": "/etfs/{ticker}",
        "response": {
            "root": ["item", "holdings", "exposures", "summary"],
        },
    },
    "news_issues": {
        "path": "/news/issues",
        "markets": ["ALL", "US", "KR"],
        "query": ["q", "market", "limit"],
        "response": {
            "root": ["configured", "items"],
            "item": ["title", "summary", "source", "url", "market", "relatedTickers", "impactScore"],
        },
    },
    "market_indicators": {
        "path": "/market/indicators",
        "query": ["category", "refresh"],
        "response": {
            "root": ["items", "count", "updated_at", "source"],
            "item": ["symbol", "label", "value", "change_abs", "change_pct", "updated_at"],
        },
    },
    "market_indicator_history": {
        "path": "/market/indicators/history",
        "query": ["symbols", "period", "interval", "refresh"],
        "response": {
            "root": ["series", "updated_at"],
        },
    },
    "watchlist": {
        "path": "/me/watchlist",
        "methods": ["GET", "POST", "DELETE"],
        "response": {
            "item": ["ticker", "name", "market", "currency", "note", "added_at"],
        },
    },
}

FRESHNESS_FIELDS = [
    "updated_at",
    "generated_at",
    "Price_Updated_At",
    "Daily_Change_Horizon",
]


class MobileBase(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="allow")


class EmptyResponse(MobileBase):
    ok: bool | None = None


class CacheClearResponse(MobileBase):
    cleared: bool


class HealthResponse(MobileBase):
    status: str
    ts: str


class ReadyResponse(MobileBase):
    status: str
    api: str | None = None
    auth_store: str | None = None
    sqlite: str | None = None
    postgres: str | None = None
    cache: dict[str, Any] = Field(default_factory=dict)
    ts: str | None = None


class AuthUser(MobileBase):
    id: str
    email: str
    display_name: str | None = None
    created_at: str | None = None


class AuthResponse(MobileBase):
    access_token: str
    token_type: str
    user: AuthUser


class CurrentUserResponse(MobileBase):
    user: AuthUser


class WatchlistItem(MobileBase):
    ticker: str
    name: str
    market: str
    currency: str
    note: str = ""
    added_at: str | None = None


class WatchlistResponse(MobileBase):
    items: list[WatchlistItem] = Field(default_factory=list)


class PortfolioStockModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    previous_rank: int | None = Field(default=None, alias="Previous_Rank")
    rank_change: int | None = Field(default=None, alias="Rank_Change")
    rank_status: str | None = Field(default=None, alias="Rank_Status")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    market: str | None = Field(default=None, alias="Market")
    sector: str | None = Field(default=None, alias="Sector")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    weight: float | None = Field(default=None, alias="Weight(%)")
    current_price: float | None = Field(default=None, alias="Current_Price")
    return_1m: float | None = Field(default=None, alias="Return_1M")
    mom_1m: float | None = Field(default=None, alias="Mom_1M")
    total_score: float | None = Field(default=None, alias="Total_Score")
    roic: float | None = Field(default=None, alias="ROIC")
    rev_growth: float | None = Field(default=None, alias="RevGrowth")
    gross_margin: float | None = Field(default=None, alias="GrossMargin")
    expected_return: float | None = Field(default=None, alias="Expected_Return")
    last_updated: str | None = Field(default=None, alias="Last_Updated")
    source: str | None = Field(default=None, alias="Source")
    generated_at: str | None = None


class PortfolioResponse(MobileBase):
    meta: dict[str, Any] = Field(default_factory=dict)
    stocks: list[PortfolioStockModel] = Field(default_factory=list)


class PortfolioPriceMetric(MobileBase):
    ticker: str | None = Field(default=None, alias="Ticker")
    current_price: float | None = Field(default=None, alias="Current_Price")
    return_1m: float | None = Field(default=None, alias="Return_1M")
    daily_change_pct: float | None = Field(default=None, alias="Daily_Change_Pct")
    daily_change_horizon: str | None = Field(default=None, alias="Daily_Change_Horizon")
    price_updated_at: str | None = Field(default=None, alias="Price_Updated_At")


class PortfolioPricesResponse(MobileBase):
    market: str
    metrics: list[PortfolioPriceMetric] = Field(default_factory=list)
    source: str | None = None
    updated_at: str | None = None


class SmallCapStockModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    previous_rank: int | None = Field(default=None, alias="Previous_Rank")
    rank_change: int | None = Field(default=None, alias="Rank_Change")
    rank_status: str | None = Field(default=None, alias="Rank_Status")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    market: str | None = Field(default=None, alias="Market")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    current_price: float | None = Field(default=None, alias="Current_Price")
    return_1m: float | None = Field(default=None, alias="Return_1M")
    roic: float | None = Field(default=None, alias="ROIC")
    rev_growth: float | None = Field(default=None, alias="RevGrowth")
    rev_accel: float | None = Field(default=None, alias="Rev_Accel")
    gross_margin: float | None = Field(default=None, alias="GrossMargin")
    fcf_margin: float | None = Field(default=None, alias="FCF_Margin")
    debt_ebitda: float | None = Field(default=None, alias="Debt_EBITDA")
    volume_surge: float | None = Field(default=None, alias="Volume_Surge")
    smallcap_bonus: float | None = Field(default=None, alias="SmallCap_Bonus")
    total_score: float | None = Field(default=None, alias="Total_Score")
    last_updated: str | None = Field(default=None, alias="Last_Updated")
    source: str | None = Field(default=None, alias="Source")
    generated_at: str | None = None


class SmallCapResponse(MobileBase):
    stocks: list[SmallCapStockModel] = Field(default_factory=list)


class ScoredStockModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    market: str | None = Field(default=None, alias="Market")
    sector: str | None = Field(default=None, alias="Sector")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    value_score: float | None = Field(default=None, alias="Value_Score")
    quality_score: float | None = Field(default=None, alias="Quality_Score")
    momentum_score: float | None = Field(default=None, alias="Momentum_Score")
    total_score: float | None = Field(default=None, alias="Total_Score")
    final_score: float | None = Field(default=None, alias="Final_Score")
    score_neutral: float | None = Field(default=None, alias="Score_Neutral")
    ml_score: float | None = Field(default=None, alias="ML_Score")
    combined_score: float | None = Field(default=None, alias="Combined_Score")
    profitability_quality: float | None = Field(default=None, alias="Profitability_Quality")
    cash_quality: float | None = Field(default=None, alias="Cash_Quality")
    growth_quality: float | None = Field(default=None, alias="Growth_Quality")
    balance_sheet_strength: float | None = Field(default=None, alias="BalanceSheet_Strength")
    valuation_discipline: float | None = Field(default=None, alias="Valuation_Discipline")
    timing_overlay: float | None = Field(default=None, alias="Timing_Overlay")
    persistence_quality: float | None = Field(default=None, alias="Persistence_Quality")
    business_quality_score: float | None = Field(default=None, alias="Business_Quality_Score")
    investability_score: float | None = Field(default=None, alias="Investability_Score")
    quality_data_confidence: float | None = Field(default=None, alias="Quality_Data_Confidence")
    quality_red_flags: str | None = Field(default=None, alias="Quality_Red_Flags")
    investability_rank: int | None = Field(default=None, alias="Investability_Rank")
    business_quality_rank: int | None = Field(default=None, alias="Business_Quality_Rank")
    quality_rank_delta: float | None = Field(default=None, alias="Quality_Rank_Delta")
    quality_category: str | None = Field(default=None, alias="Quality_Category")
    roic: float | None = Field(default=None, alias="ROIC")
    rev_growth: float | None = Field(default=None, alias="RevGrowth")
    gross_margin: float | None = Field(default=None, alias="GrossMargin")
    fcf_margin: float | None = Field(default=None, alias="FCF_Margin")
    debt_ebitda: float | None = Field(default=None, alias="Debt_EBITDA")
    peg: float | None = Field(default=None, alias="PEG")


class ScoredResponse(MobileBase):
    stocks: list[ScoredStockModel] = Field(default_factory=list)
    count: int | None = None
    total_count: int | None = None
    generated_at: str | None = None
    source: str | None = None


class BlindQuizMetric(MobileBase):
    label: str
    value: str
    tone: str | None = None


class BlindQuizPricePoint(MobileBase):
    date: str
    return_pct: float


class BlindQuizCompany(MobileBase):
    ticker: str
    name: str
    market: str
    currency: str
    sector: str | None = None
    logo_url: str | None = None
    price_points: list[BlindQuizPricePoint] = Field(default_factory=list)
    three_year_return_pct: float | None = None


class BlindQuizOption(MobileBase):
    id: str
    blind_label: str
    thesis: str | None = None
    metrics: list[BlindQuizMetric] = Field(default_factory=list)
    company: BlindQuizCompany


class BlindFinancialQuizResponse(MobileBase):
    id: str
    title: str
    prompt: str
    market: str
    as_of: str | None = None
    source: str | None = None
    generated_at: str | None = None
    correct_option_id: str
    answer_rule: str
    options: list[BlindQuizOption] = Field(default_factory=list)


class SearchStockModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    market: str | None = Field(default=None, alias="Market")
    sector: str | None = Field(default=None, alias="Sector")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    in_portfolio: bool | None = Field(default=None, alias="In_Portfolio")
    in_smallcap: bool | None = Field(default=None, alias="In_SmallCap")
    currency: str | None = Field(default=None, alias="Currency")


class SearchGroupModel(MobileBase):
    label: str
    count: int
    tickers: list[str] = Field(default_factory=list)


class SearchUniverseResponse(MobileBase):
    stocks: list[SearchStockModel] = Field(default_factory=list)
    count: int | None = None
    query: str | None = None
    market: str | None = None
    groups: list[SearchGroupModel] = Field(default_factory=list)


class SectorThemeMember(MobileBase):
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    market: str | None = Field(default=None, alias="Market")
    sector: str | None = Field(default=None, alias="Sector")
    currency: str | None = Field(default=None, alias="Currency")
    source: str | None = Field(default=None, alias="Source")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    current_price: float | None = Field(default=None, alias="Current_Price")
    daily_change_pct: float | None = Field(default=None, alias="Daily_Change_Pct")
    daily_change_horizon: str | None = Field(default=None, alias="Daily_Change_Horizon")
    return_1m: float | None = Field(default=None, alias="Return_1M")
    score_value: float | None = Field(default=None, alias="Score_Value")
    in_portfolio: bool | None = Field(default=None, alias="In_Portfolio")
    in_smallcap: bool | None = Field(default=None, alias="In_SmallCap")


class SectorTheme(MobileBase):
    label: str
    market: str | None = None
    member_count: int | None = None
    priced_count: int | None = None
    missing_price_count: int | None = None
    price_coverage_ratio: float | None = None
    weighting_method: str | None = None
    rising_count: int | None = None
    falling_count: int | None = None
    avg_change_pct: float | None = None
    avg_return_1m: float | None = None
    leader: SectorThemeMember | None = None
    members: list[SectorThemeMember] = Field(default_factory=list)


class SectorThemesResponse(MobileBase):
    market: str
    generated_at: str | None = None
    count: int | None = None
    source: str | None = None
    mode: str | None = None
    items: list[SectorTheme] = Field(default_factory=list)


class SectorThemeDetailResponse(MobileBase):
    market: str
    generated_at: str | None = None
    item: SectorTheme


class EtfHolding(MobileBase):
    ticker: str | None = None
    name: str | None = None
    weight: float | None = None


class EtfExposure(MobileBase):
    label: str
    weight: float | None = None


class EtfItem(MobileBase):
    ticker: str
    name: str
    market: str | None = None
    region: str | None = None
    category: str | None = None
    theme: str | None = None
    summary: str | None = None
    price: float | None = None
    change_pct: float | None = Field(default=None, alias="changePct")
    daily_change_pct: float | None = Field(default=None, alias="dailyChangePct")
    daily_price_change: float | None = Field(default=None, alias="dailyPriceChange")
    daily_change_horizon: str | None = Field(default=None, alias="dailyChangeHorizon")
    updated_at: str | None = Field(default=None, alias="updatedAt")
    top_holdings: list[EtfHolding] = Field(default_factory=list, alias="topHoldings")
    holdings: list[EtfHolding] = Field(default_factory=list)
    exposures: list[EtfExposure] = Field(default_factory=list)


class EtfListResponse(MobileBase):
    items: list[EtfItem] = Field(default_factory=list)
    count: int | None = None
    source: str | None = None
    generated_at: str | None = None


class EtfDetailResponse(MobileBase):
    item: EtfItem
    holdings: list[EtfHolding] = Field(default_factory=list)
    exposures: list[EtfExposure] = Field(default_factory=list)
    summary: str | dict[str, Any] | None = None


class NewsItemModel(MobileBase):
    id: str | None = None
    title: str
    summary: str | None = None
    source: str | None = None
    url: str | None = None
    imageUrl: str | None = Field(default=None, validation_alias="image_url", serialization_alias="imageUrl")
    publishedAt: str | None = Field(default=None, validation_alias="published_at", serialization_alias="publishedAt")
    market: str | None = None
    ticker: str | None = None
    kind: str | None = None
    impactLabel: str | None = Field(default=None, validation_alias="impact_label", serialization_alias="impactLabel")
    impactLabelKo: str | None = Field(default=None, validation_alias="impact_label_ko", serialization_alias="impactLabelKo")
    impactScore: float | None = Field(default=None, validation_alias="impact_score", serialization_alias="impactScore")
    impactReason: str | None = Field(default=None, validation_alias="impact_reason", serialization_alias="impactReason")
    impactScope: str | None = Field(default=None, validation_alias="impact_scope", serialization_alias="impactScope")
    impactHorizon: str | None = Field(default=None, validation_alias="impact_horizon", serialization_alias="impactHorizon")
    impactConfidence: str | None = Field(default=None, validation_alias="impact_confidence", serialization_alias="impactConfidence")
    relatedTickers: list[str] = Field(default_factory=list, validation_alias="related_tickers", serialization_alias="relatedTickers")
    relatedChangePct: float | None = Field(default=None, validation_alias="related_change_pct", serialization_alias="relatedChangePct")
    relatedChangeLabel: str | None = Field(default=None, validation_alias="related_change_label", serialization_alias="relatedChangeLabel")
    relatedChangeHorizon: str | None = Field(default=None, validation_alias="related_change_horizon", serialization_alias="relatedChangeHorizon")


class NewsIssuesResponse(MobileBase):
    configured: bool | None = None
    items: list[NewsItemModel] = Field(default_factory=list)
    generated_at: str | None = None
    source: str | None = None


class MarketIndexQuote(MobileBase):
    symbol: str
    label: str
    value: float
    change_abs: float = 0
    change_pct: float = 0
    updated_at: str | None = None


class MarketIndicesResponse(MobileBase):
    indices: list[MarketIndexQuote] = Field(default_factory=list)
    updated_at: str | None = None
    source: str | None = None


class MarketIndicatorQuote(MobileBase):
    symbol: str
    label: str
    category: str | None = None
    region: str | None = None
    value: float | None = None
    change_abs: float | None = None
    change_pct: float | None = None
    updated_at: str | None = None


class MarketIndicatorsResponse(MobileBase):
    items: list[MarketIndicatorQuote] = Field(default_factory=list)
    count: int | None = None
    updated_at: str | None = None
    source: str | None = None


class MarketIndicatorPoint(MobileBase):
    timestamp: str
    close: float


class MarketIndicatorSeries(MobileBase):
    symbol: str
    points: list[MarketIndicatorPoint] = Field(default_factory=list)


class MarketIndicatorHistoryResponse(MobileBase):
    series: list[MarketIndicatorSeries] = Field(default_factory=list)
    updated_at: str | None = None
    source: str | None = None


class EarningsStockModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    sector: str | None = Field(default=None, alias="Sector")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    earnings_date: str | None = Field(default=None, alias="Earnings_Date")
    days_since: float | None = Field(default=None, alias="Days_Since_Earnings")
    surprise_pct: float | None = Field(default=None, alias="Surprise_Pct")
    return_since: float | None = Field(default=None, alias="Return_Since")
    volume_surge: float | None = Field(default=None, alias="Volume_Surge")
    signal_strength: float | None = Field(default=None, alias="Signal_Strength")


class EarningsResponse(MobileBase):
    stocks: list[EarningsStockModel] = Field(default_factory=list)


class EarningsCalendarItemModel(MobileBase):
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    market: str | None = Field(default=None, alias="Market")
    sector: str | None = Field(default=None, alias="Sector")
    market_cap: float | None = Field(default=None, alias="MarketCap")
    next_earnings_date: str | None = Field(default=None, alias="Next_Earnings_Date")
    days_until: int | None = Field(default=None, alias="Days_Until")


class EarningsCalendarResponse(MobileBase):
    items: list[EarningsCalendarItemModel] = Field(default_factory=list)
    generated_at: str | None = None
    source: str | None = None
    total: int | None = None


class MacroResponse(RootModel[dict[str, str]]):
    pass


class PricePointModel(MobileBase):
    date: str
    open: float
    high: float
    low: float
    close: float
    volume: float | None = None


class StockInfoModel(MobileBase):
    name: str | None = None
    current_price: float | None = None
    prev_close: float | None = None
    daily_change_pct: float | None = None
    daily_change_horizon: str | None = None
    week52_high: float | None = None
    week52_low: float | None = None
    market_cap: float | None = None
    pe_ratio: float | None = None
    forward_pe: float | None = None
    price_to_sales: float | None = None
    price_to_book: float | None = None
    beta: float | None = None
    sector: str | None = None
    industry: str | None = None
    country: str | None = None
    city: str | None = None
    exchange: str | None = None
    website: str | None = None
    employees: int | None = None
    total_revenue: float | None = None
    revenue_growth: float | None = None
    gross_margin: float | None = None
    operating_margin: float | None = None
    profit_margin: float | None = None
    ebitda_margin: float | None = None
    ebitda: float | None = None
    free_cashflow: float | None = None
    total_debt: float | None = None
    debt_to_equity: float | None = None
    return_on_equity: float | None = None
    target_mean_price: float | None = None
    recommendation: str | None = None
    description: str | None = None


class StockDetailResponse(MobileBase):
    prices: list[PricePointModel] = Field(default_factory=list)
    info: StockInfoModel = Field(default_factory=StockInfoModel)
    error: str | None = None
    source: str | None = None
    updated_at: str | None = None
    storage_complete: bool | None = None


class SignalEventModel(MobileBase):
    event_id: str | None = Field(default=None, alias="Event_ID")
    market: str | None = Field(default=None, alias="Market")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    kind: str | None = Field(default=None, alias="Kind")
    severity: int | None = Field(default=None, alias="Severity")
    title: str | None = Field(default=None, alias="Title")
    detail: str | None = Field(default=None, alias="Detail")
    metric_label: str | None = Field(default=None, alias="Metric_Label")
    metric_value: str | None = Field(default=None, alias="Metric_Value")
    event_time: str | None = Field(default=None, alias="Event_Time")
    source: str | None = Field(default=None, alias="Source")
    updated_at: str | None = Field(default=None, alias="Updated_At")


class SignalEventsResponse(MobileBase):
    items: list[SignalEventModel] = Field(default_factory=list)
    generated_at: str | None = None
    count: int | None = None


class ComparisonRecommendationItemModel(SectorThemeMember):
    expected_return: float | None = Field(default=None, alias="Expected_Return")
    revenue_growth: float | None = Field(default=None, alias="RevGrowth")
    roic: float | None = Field(default=None, alias="ROIC")
    gross_margin: float | None = Field(default=None, alias="GrossMargin")
    rank_change: int | None = Field(default=None, alias="Rank_Change")
    weight: float | None = Field(default=None, alias="Weight(%)")
    fcf_margin: float | None = Field(default=None, alias="FCF_Margin")
    volume_surge: float | None = Field(default=None, alias="Volume_Surge")
    updated_at: str | None = Field(default=None, alias="Last_Updated")
    reason: str | None = Field(default=None, alias="Recommendation_Reason")


class ComparisonRecommendationsResponse(MobileBase):
    anchor: dict[str, Any] | None = None
    items: list[ComparisonRecommendationItemModel] = Field(default_factory=list)
    generated_at: str | None = None
    count: int | None = None


class BacktestSummaryModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    sheet: str | None = Field(default=None, alias="Sheet")
    periods: int | None = Field(default=None, alias="Periods")
    latest_date: str | None = Field(default=None, alias="Latest_Date")
    cumulative_return: float | None = Field(default=None, alias="Cumulative_Return")
    max_drawdown: float | None = Field(default=None, alias="Max_Drawdown")
    avg_return: float | None = Field(default=None, alias="Avg_Return")


class BacktestResponse(MobileBase):
    summary: list[BacktestSummaryModel] = Field(default_factory=list)
    source: str | None = None
    generated_at: str | None = None


class DriftItemModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    status: str | None = Field(default=None, alias="Status")
    drift_abs: float | None = Field(default=None, alias="Drift_Abs")
    target_weight: float | None = Field(default=None, alias="Target_Weight")
    current_weight: float | None = Field(default=None, alias="Current_Weight")
    return_since_rebal: float | None = Field(default=None, alias="Return_Since_Rebal")


class DriftAlertsResponse(MobileBase):
    items: list[DriftItemModel] = Field(default_factory=list)
    count: int | None = None
    generated_at: str | None = None


class IndustryItemModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    industry: str | None = Field(default=None, alias="Industry")
    stock_count: int | None = Field(default=None, alias="Stock_Count")
    mean_return: float | None = Field(default=None, alias="Mean_Return")
    breadth: float | None = Field(default=None, alias="Breadth")


class IndustryRankingResponse(MobileBase):
    items: list[IndustryItemModel] = Field(default_factory=list)
    count: int | None = None
    generated_at: str | None = None


class OrderFlowItemModel(MobileBase):
    rank: int | None = Field(default=None, alias="Rank")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    consecutive_days: int | None = Field(default=None, alias="Consecutive_Days")
    foreign_net_buy: float | None = Field(default=None, alias="Foreign_Net_Buy")
    inst_net_buy: float | None = Field(default=None, alias="Inst_Net_Buy")


class OrderFlowResponse(MobileBase):
    items: list[OrderFlowItemModel] = Field(default_factory=list)
    count: int | None = None
    generated_at: str | None = None


class RiskHoldingModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    sector: str | None = Field(default=None, alias="Sector")
    portfolio_weight: float | None = Field(default=None, alias="Portfolio_Weight")
    asset_vol: float | None = Field(default=None, alias="Asset_Vol")
    risk_contribution_pct: float | None = Field(default=None, alias="Risk_Contribution_Pct")
    weight_risk_ratio: float | None = Field(default=None, alias="Weight_Risk_Ratio")


class RiskSectorModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    sector: str | None = Field(default=None, alias="Sector")
    holdings: float | None = Field(default=None, alias="Holdings")
    sector_weight: float | None = Field(default=None, alias="Sector_Weight")
    risk_contribution_pct: float | None = Field(default=None, alias="Risk_Contribution_Pct")


class PortfolioRiskResponse(MobileBase):
    holdings: list[RiskHoldingModel] = Field(default_factory=list)
    sectors: list[RiskSectorModel] = Field(default_factory=list)
    generated_at: str | None = None


class RebalanceOrderModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    action: str | None = Field(default=None, alias="Action")
    current_weight: float | None = Field(default=None, alias="Current_Weight")
    target_weight: float | None = Field(default=None, alias="Target_Weight")
    delta_weight: float | None = Field(default=None, alias="Delta_Weight")
    executable_trade_value: float | None = Field(default=None, alias="Executable_Trade_Value")
    cost_estimate: float | None = Field(default=None, alias="Cost_Estimate")


class RebalanceResponse(MobileBase):
    orders: list[RebalanceOrderModel] = Field(default_factory=list)
    generated_at: str | None = None


class ShadowAttributionSummaryModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    horizon_trading_days: float | None = Field(default=None, alias="Horizon_Trading_Days")
    actual_return: float | None = Field(default=None, alias="Actual_Return")
    benchmark_return: float | None = Field(default=None, alias="Benchmark_Return")
    alpha_actual: float | None = Field(default=None, alias="Alpha_Actual")
    hit_rate: float | None = Field(default=None, alias="Hit_Rate")
    score_return_ic: float | None = Field(default=None, alias="Score_Return_IC")


class ShadowAttributionItemModel(MobileBase):
    market: str | None = Field(default=None, alias="Market")
    ticker: str | None = Field(default=None, alias="Ticker")
    name: str | None = Field(default=None, alias="Name")
    horizon_trading_days: float | None = Field(default=None, alias="Horizon_Trading_Days")
    weight: float | None = Field(default=None, alias="Weight")
    stock_return: float | None = Field(default=None, alias="Stock_Return")
    benchmark_return: float | None = Field(default=None, alias="Benchmark_Return")
    actual_contribution: float | None = Field(default=None, alias="Actual_Contribution")
    excess_contribution: float | None = Field(default=None, alias="Excess_Contribution")


class ShadowAttributionResponse(MobileBase):
    summaries: list[ShadowAttributionSummaryModel] = Field(default_factory=list)
    items: list[ShadowAttributionItemModel] = Field(default_factory=list)
    generated_at: str | None = None


class TableResponse(MobileBase):
    items: list[dict[str, Any]] = Field(default_factory=list)
    status_counts: dict[str, int] = Field(default_factory=dict)
    source: str | None = None
    generated_at: str | None = None


class ResearchQualityResponse(TableResponse):
    overall_status: str | None = None
    warning_count: int | None = None
    production_ready_count: int | None = None
    proxy_evidence_count: int | None = None
    best_factors: list[dict[str, Any]] = Field(default_factory=list)
    weak_factors: list[dict[str, Any]] = Field(default_factory=list)


class ResearchPolicyResponse(TableResponse):
    review_count: int | None = None
    hold_count: int | None = None
    production_ready_count: int | None = None
    proxy_evidence_count: int | None = None
    review_items: list[dict[str, Any]] = Field(default_factory=list)
    mode: str | None = None


class MLBlendResponse(MobileBase):
    status: str
    generated_at: str | None = None
    source: str | None = None
    latest: dict[str, Any] | None = None
    items: list[dict[str, Any]] = Field(default_factory=list)
    error: str | None = None


class PolicyBacktestResponse(TableResponse):
    improved_count: int | None = None
    worse_count: int | None = None
    production_ready_count: int | None = None
    proxy_evidence_count: int | None = None
    best_windows: list[dict[str, Any]] = Field(default_factory=list)
    weak_windows: list[dict[str, Any]] = Field(default_factory=list)
    mode: str | None = None


class PolicyAdjustedRankingResponse(TableResponse):
    market: str | None = None
    summary: dict[str, Any] | None = None
    top_up: list[dict[str, Any]] = Field(default_factory=list)
    top_down: list[dict[str, Any]] = Field(default_factory=list)
    mode: str | None = None


class RemediationPlanResponse(TableResponse):
    severity_counts: dict[str, int] = Field(default_factory=dict)
    urgent_count: int | None = None
    production_ready_count: int | None = None
    top_actions: list[dict[str, Any]] = Field(default_factory=list)


class OpsTableResponse(TableResponse):
    count: int | None = None
    status: str | None = None
    healthy: bool | None = None


class OpsHealthCheck(MobileBase):
    name: str
    status: str
    message: str | None = None


class OpsHealthResponse(MobileBase):
    healthy: bool
    status: str
    generated_at: str | None = None
    checks: list[OpsHealthCheck] = Field(default_factory=list)


class CacheWarmResponse(MobileBase):
    status: str | None = None
    started_at: str | None = None
    finished_at: str | None = None
    message: str | None = None


class AnyObjectResponse(MobileBase):
    pass
