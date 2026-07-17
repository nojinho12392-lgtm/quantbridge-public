from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException


@dataclass(frozen=True)
class RankingPayloadBuilder:
    repository: Callable
    safe_float: Callable[[object], float | None]
    kr_code: Callable[[object], str | None]
    load_simple: Callable[[str, list[str]], list[dict]]
    enrich_kr_company_identities: Callable[[list[dict]], list[dict]]
    enrich_portfolio_price_fields: Callable[[list[dict], str, int | None], list[dict]]
    apply_localized_names: Callable[[dict], dict]
    data_source_events: Callable[[], dict]
    utc_now_iso: Callable[[], str]

    def smallcap_payload(self, market: str) -> dict:
        sheet = f"{market}_SmallCap_Gems"
        num_cols = [
            "ROIC", "RevGrowth", "Rev_Accel", "GrossMargin", "FCF_Margin",
            "Debt_EBITDA", "Volume_Surge", "SmallCap_Bonus", "Data_Confidence",
            "Total_Score", "MarketCap", "Rank", "Current_Price", "Return_1M",
            "Previous_Rank", "Rank_Change",
        ]

        stocks = self.load_simple(sheet, num_cols)
        if market == "KR":
            stocks = self.enrich_kr_company_identities(stocks)
        stocks = self.enrich_portfolio_price_fields(stocks, market, 0)
        stocks = self.enrich_rank_change_fields(stocks, sheet, market)
        return self.apply_localized_names({"stocks": stocks})

    def scored_payload(self, market: str, safe_limit: int = 200) -> dict:
        sheet = f"{market}_Scored_Stocks"
        num_cols = [
            "Value_Score", "Quality_Score", "Momentum_Score",
            "Total_Score", "Final_Score", "Score_Neutral", "ML_Score", "Combined_Score",
            "Profitability_Quality", "Cash_Quality", "Growth_Quality",
            "BalanceSheet_Strength", "Valuation_Discipline", "Timing_Overlay",
            "Persistence_Quality",
            "Business_Quality_Score", "Investability_Score", "Quality_Data_Confidence",
            "Investability_Rank", "Business_Quality_Rank", "Quality_Rank_Delta",
            "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin",
            "Debt_EBITDA", "PEG", "MarketCap", "Rank",
        ]

        records = self.load_simple(sheet, num_cols)
        source = self.data_source_events().get(f"{market}:{sheet}", {}).get("last_source") or "unknown"
        for row in records:
            row["Market"] = row.get("Market") or market
        if market == "KR":
            records = self.enrich_kr_company_identities(records)
        records = self.enrich_portfolio_price_fields(records, market, 0)
        generated_at = next(
            (
                str(row.get(key) or "").strip()
                for row in records
                for key in ("Last_Updated", "Price_Updated_At", "snapshot_date", "Generated_At", "Updated_At")
                if str(row.get(key) or "").strip().lower() not in {"", "nan", "nat", "none", "null"}
            ),
            self.utc_now_iso(),
        )
        stocks = records[:safe_limit]
        return self.apply_localized_names({
            "stocks": stocks,
            "count": len(stocks),
            "total_count": len(records),
            "generated_at": generated_at,
            "source": source,
        })

    def enrich_rank_change_fields(self, records: list[dict], dataset: str, market: str | None) -> list[dict]:
        if not records:
            return records
        previous = self.repository().read_previous_ranks(dataset, market=market)
        if previous.empty:
            return records

        previous_by_ticker: dict[str, int] = {}
        for _, row in previous.iterrows():
            previous_rank = self.safe_float(row.get("Previous_Rank"))
            if previous_rank is None:
                continue
            for key in self.rank_match_keys(row.get("Ticker")):
                previous_by_ticker.setdefault(key, int(previous_rank))

        if not previous_by_ticker:
            return records

        for row in records:
            current_rank = self.safe_float(row.get("Rank"))
            if current_rank is None:
                continue
            previous_rank = None
            for key in self.rank_match_keys(row.get("Ticker")):
                previous_rank = previous_by_ticker.get(key)
                if previous_rank is not None:
                    break

            if previous_rank is None:
                row["Previous_Rank"] = None
                row["Rank_Change"] = None
                row["Rank_Status"] = "new"
                continue

            current = int(current_rank)
            change = previous_rank - current
            row["Previous_Rank"] = previous_rank
            row["Rank_Change"] = change
            row["Rank_Status"] = "up" if change > 0 else "down" if change < 0 else "same"
        return records

    def rank_match_keys(self, ticker: str | None) -> set[str]:
        text = str(ticker or "").strip().upper()
        if not text:
            return set()
        keys = {text}
        code = self.kr_code(text)
        if code:
            keys.update({code, f"{code}.KS", f"{code}.KQ"})
        return keys


@dataclass(frozen=True)
class RankingApiService:
    cached: Callable
    smallcap_payload: Callable[[str], dict]
    scored_payload: Callable[[str, int], dict]

    @staticmethod
    def _market(value: str) -> str:
        market = str(value or "").strip().upper()
        if market not in {"US", "KR"}:
            raise HTTPException(status_code=400, detail="market must be US or KR")
        return market

    def smallcap(self, market: str) -> dict:
        safe_market = self._market(market)
        return self.cached(
            f"sc_{safe_market}",
            lambda: self.smallcap_payload(safe_market),
            ttl=60,
            stale_ttl=0,
        )

    def scored(self, market: str, limit: int = 200) -> dict:
        safe_market = self._market(market)
        safe_limit = max(1, min(int(limit or 200), 2000))
        return self.cached(
            f"scored_{safe_market}_{safe_limit}",
            lambda: self.scored_payload(safe_market, safe_limit),
            ttl=60,
            stale_ttl=0,
        )
