from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone

import pandas as pd
import requests

from api.services.company_names import apply_localized_names


def is_yahoo_etf_search_query(query: str) -> bool:
    clean = str(query or "").strip()
    if not clean or len(clean) > 80:
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9 .,&'’/\-]+", clean))


def yahoo_etf_search_records(
    query: str,
    limit: int = 12,
    *,
    record_data_source: Callable[..., None] | None = None,
    utc_now_iso: Callable[[], str] | None = None,
) -> list[dict]:
    clean = str(query or "").strip()
    if not clean or not is_yahoo_etf_search_query(clean):
        return []
    try:
        response = requests.get(
            "https://query1.finance.yahoo.com/v1/finance/search",
            params={"q": clean, "quotesCount": max(1, min(limit * 3, 30)), "newsCount": 0},
            timeout=6,
            headers={"User-Agent": "Qubit/1.0"},
        )
        response.raise_for_status()
        quotes = response.json().get("quotes") or []
    except Exception as exc:
        if record_data_source:
            record_data_source("ETF_Insights", "yahoo_search_error", detail=f"{clean}: {type(exc).__name__}")
        return []

    now_iso = utc_now_iso or (lambda: datetime.now(timezone.utc).replace(microsecond=0).isoformat())
    records: list[dict] = []
    for quote in quotes:
        quote_type = str(quote.get("quoteType") or quote.get("typeDisp") or "").strip().upper()
        symbol = str(quote.get("symbol") or "").strip().upper()
        if quote_type != "ETF" or not symbol or len(symbol) > 12:
            continue
        name = str(quote.get("longname") or quote.get("shortname") or symbol).strip()
        exchange = str(quote.get("exchDisp") or quote.get("exchange") or "").strip()
        theme = exchange or "ETF 검색"
        records.append(
            {
                "rank": 9000 + len(records),
                "ticker": symbol,
                "name": name,
                "region": "US",
                "category": "검색",
                "theme": theme,
                "summary": f"{name} 검색 결과입니다. 가격 차트와 ETF 기본 정보부터 확인하세요.",
                "expenseRatio": "확인 필요",
                "aum": "확인 필요",
                "distribution": "확인 필요",
                "outlook": "검색으로 추가된 ETF입니다. 가격 흐름, 추종 대상, 비용을 먼저 확인하세요.",
                "risk": "신규 또는 소형 ETF일 수 있으므로 유동성, 스프레드, 구성종목 데이터를 함께 점검하세요.",
                "holdings": [],
                "exposures": [],
                "asOf": now_iso(),
                "dataSource": "yahoo_search",
            }
        )
        if len(records) >= limit:
            break
    if records and record_data_source:
        record_data_source("ETF_Insights", "yahoo_search", rows=len(records), detail=clean)
    return records


@dataclass(frozen=True)
class SearchApiService:
    cached: Callable
    payload: Callable[[str, int], dict] | None = None
    load_table: Callable[[str, list[str] | None], pd.DataFrame] | None = None
    load_portfolio: Callable[[str], tuple[dict, list[dict]]] | None = None
    load_simple: Callable[[str, list[str]], list[dict]] | None = None
    coerce: Callable[[pd.DataFrame, list[str]], pd.DataFrame] | None = None
    df_to_records: Callable[[pd.DataFrame], list[dict]] | None = None
    etf_storage_records: Callable[[], list[dict]] | None = None
    etf_payload_from_records: Callable[..., dict] | None = None
    enrich_kr_company_identities: Callable[[list[dict]], list[dict]] | None = None
    default_logo_url: Callable[[str, str], str] | None = None
    kr_code: Callable[[object], str] | None = None
    utc_now_iso: Callable[[], str] | None = None
    record_data_source: Callable[..., None] | None = None

    def search_universe(self, q: str = "", limit: int = 100) -> dict:
        safe_limit = max(1, min(int(limit or 100), 200))
        clean_query = str(q or "").strip()
        cache_key = f"search_universe_{clean_query.lower()}_{safe_limit}"
        return self.cached(cache_key, lambda: apply_localized_names(self._payload(clean_query, safe_limit)))

    def yahoo_etf_search_records(self, query: str, limit: int = 12) -> list[dict]:
        return yahoo_etf_search_records(
            query,
            limit=limit,
            record_data_source=self.record_data_source,
            utc_now_iso=self.utc_now_iso,
        )

    def _payload(self, query: str, limit: int) -> dict:
        if self.payload:
            return self.payload(query, limit)
        return self._search_universe_payload(query, limit)

    def _search_universe_payload(self, query: str = "", limit: int = 100) -> dict:
        frames: list[pd.DataFrame] = []
        q = str(query or "").strip()
        safe_limit = max(1, min(int(limit or 100), 200))
        for sheet_name, market in (("US_Universe", "US"), ("KR_Universe", "KR")):
            df = self._load_table(sheet_name, ["MarketCap"])
            if df.empty:
                continue
            keep = [col for col in ["Ticker", "Name", "Market", "Sector", "MarketCap"] if col in df.columns]
            if "Ticker" not in keep:
                continue
            out = df[keep].copy()
            if "Name" not in out.columns:
                out["Name"] = out["Ticker"]
            if "Market" not in out.columns:
                out["Market"] = market
            if "Sector" not in out.columns:
                out["Sector"] = ""
            if "MarketCap" not in out.columns:
                out["MarketCap"] = None
            frames.append(out)

        etf_frame = self._etf_search_universe_frame(q, safe_limit)
        if not etf_frame.empty:
            frames.append(etf_frame)

        if frames:
            universe = pd.concat(frames, ignore_index=True)
            universe = self._coerce(universe, ["MarketCap"])
            universe["Ticker"] = universe["Ticker"].fillna("").astype(str).str.strip()
            universe["Name"] = universe["Name"].fillna("").astype(str).str.strip()
            universe["Market"] = universe["Market"].fillna("").astype(str).str.upper().str.strip()
            universe["Sector"] = universe["Sector"].fillna("").astype(str).str.strip()
            universe = universe[universe["Ticker"] != ""].drop_duplicates(subset=["Ticker"]).reset_index(drop=True)
        else:
            universe = pd.DataFrame(columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

        portfolio_set = self._ticker_membership(["US_Final_Portfolio", "KR_Final_Portfolio"])
        gem_set = self._ticker_membership(["US_SmallCap_Gems", "KR_SmallCap_Gems"])

        if q and not universe.empty:
            q_up = q.upper()
            q_low = q.lower()
            tickers = universe["Ticker"].str.upper()
            names = universe["Name"].str.lower()

            mask_exact = tickers == q_up
            mask_t_starts = tickers.str.startswith(q_up)
            mask_n_starts = names.str.startswith(q_low)
            mask_t_has = tickers.str.contains(q_up, regex=False)
            mask_n_has = names.str.contains(q_low, regex=False, na=False)
            mask = mask_exact | mask_t_starts | mask_n_starts | mask_t_has | mask_n_has

            result = universe[mask].copy()
            priority = pd.Series(4, index=result.index)
            priority[mask_n_has[mask]] = 4
            priority[mask_t_has[mask]] = 3
            priority[mask_n_starts[mask]] = 2
            priority[mask_t_starts[mask]] = 1
            priority[mask_exact[mask]] = 0
            result["_priority"] = priority
            result = result.sort_values(["_priority", "MarketCap"], ascending=[True, False], na_position="last")
            result = result.drop(columns=["_priority"]).head(safe_limit).reset_index(drop=True)
        elif not universe.empty:
            market_tops = []
            for market in ("KR", "US"):
                market_tops.append(
                    universe[universe["Market"].str.upper() == market]
                    .sort_values("MarketCap", ascending=False, na_position="last")
                    .head(max(1, safe_limit // 2))
                )
            result = pd.concat(market_tops, ignore_index=True).head(safe_limit).reset_index(drop=True)
        else:
            result = universe

        if not result.empty:
            kr_code = self._kr_code
            portfolio_codes = {kr_code(ticker) for ticker in portfolio_set if kr_code(ticker)}
            gem_codes = {kr_code(ticker) for ticker in gem_set if kr_code(ticker)}
            result["Rank"] = range(1, len(result) + 1)
            result["In_Portfolio"] = result["Ticker"].apply(
                lambda ticker: ticker in portfolio_set or (kr_code(ticker) in portfolio_codes if kr_code(ticker) else False)
            )
            result["In_SmallCap"] = result["Ticker"].apply(
                lambda ticker: ticker in gem_set or (kr_code(ticker) in gem_codes if kr_code(ticker) else False)
            )
            result["Currency"] = result["Market"].apply(lambda market: "KRW" if str(market).upper() == "KR" else "USD")
            result["Logo_URL"] = result.apply(
                lambda row: self._default_logo_url(str(row.get("Ticker") or ""), str(row.get("Market") or "")),
                axis=1,
            )
            kr_mask = result["Market"].str.upper() == "KR"
            if kr_mask.any():
                kr_records = self._enrich_kr_company_identities(self._df_to_records(result[kr_mask]))
                kr_df = pd.DataFrame(kr_records)
                for idx, (_, row) in zip(result[kr_mask].index, kr_df.iterrows()):
                    result.at[idx, "Ticker"] = row.get("Ticker") or result.at[idx, "Ticker"]
                    result.at[idx, "Name"] = row.get("Name") or result.at[idx, "Name"]

        return {
            "query": q,
            "count": int(len(result)),
            "portfolio_count": len(portfolio_set),
            "smallcap_count": len(gem_set),
            "groups": self._search_result_groups(result),
            "stocks": self._df_to_records(result),
        }

    def _ticker_membership(self, sheet_names: list[str]) -> set[str]:
        tickers: set[str] = set()
        for sheet_name in sheet_names:
            try:
                if sheet_name.endswith("_Final_Portfolio"):
                    _, records = self._load_portfolio(sheet_name)
                else:
                    records = self._load_simple(sheet_name, [])
            except Exception:
                records = []
            tickers.update(str(row.get("Ticker") or "").strip() for row in records)
        return {ticker for ticker in tickers if ticker}

    def _search_result_groups(self, result: pd.DataFrame) -> list[dict]:
        if result.empty:
            return []
        records = self._df_to_records(result)
        order = {"기업": 0, "ETF": 1, "지수": 2, "기타": 3}
        grouped: dict[str, list[dict]] = {}
        for record in records:
            grouped.setdefault(self._search_result_group_name(record), []).append(record)
        return [
            {
                "label": label,
                "count": len(items),
                "tickers": [str(item.get("Ticker") or "") for item in items[:8]],
            }
            for label, items in sorted(grouped.items(), key=lambda item: order.get(item[0], 9))
        ]

    def _search_result_group_name(self, row: dict) -> str:
        sector = str(row.get("Sector") or "").strip()
        ticker = str(row.get("Ticker") or "").strip().upper()
        if sector.upper().startswith("ETF"):
            return "ETF"
        if ticker.startswith("^") or ticker.endswith("=F") or ticker.endswith("=X"):
            return "지수"
        if sector:
            return "기업"
        return "기타"

    def _etf_search_universe_frame(self, query: str, limit: int) -> pd.DataFrame:
        clean = str(query or "").strip()
        if not clean:
            return pd.DataFrame(columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

        payload = self._etf_payload_from_records(self._etf_storage_records(), q=clean, limit=limit)
        records = list(payload.get("items") or [])
        seen = {str(item.get("ticker") or "").strip().upper() for item in records}
        for item in self.yahoo_etf_search_records(clean, limit=12):
            ticker = str(item.get("ticker") or "").strip().upper()
            if ticker and ticker not in seen:
                records.append(item)
                seen.add(ticker)
        if not records:
            return pd.DataFrame(columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

        rows = []
        for item in records:
            ticker = str(item.get("ticker") or "").strip().upper()
            if not ticker:
                continue
            category = str(item.get("category") or "ETF").strip()
            theme = str(item.get("theme") or "").strip()
            rows.append(
                {
                    "Ticker": ticker,
                    "Name": str(item.get("name") or ticker).strip(),
                    "Market": str(item.get("region") or "US").strip().upper(),
                    "Sector": "ETF" + (f" · {category}" if category else "") + (f" · {theme}" if theme else ""),
                    "MarketCap": None,
                }
            )
        return pd.DataFrame(rows, columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

    def _load_table(self, sheet_name: str, num_cols: list[str] | None = None) -> pd.DataFrame:
        if self.load_table is None:
            raise RuntimeError("SearchApiService.load_table is required")
        return self.load_table(sheet_name, num_cols)

    def _load_portfolio(self, sheet_name: str) -> tuple[dict, list[dict]]:
        if self.load_portfolio is None:
            raise RuntimeError("SearchApiService.load_portfolio is required")
        return self.load_portfolio(sheet_name)

    def _load_simple(self, sheet_name: str, num_cols: list[str]) -> list[dict]:
        if self.load_simple is None:
            raise RuntimeError("SearchApiService.load_simple is required")
        return self.load_simple(sheet_name, num_cols)

    def _coerce(self, df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
        if self.coerce is None:
            raise RuntimeError("SearchApiService.coerce is required")
        return self.coerce(df, cols)

    def _df_to_records(self, df: pd.DataFrame) -> list[dict]:
        if self.df_to_records is None:
            raise RuntimeError("SearchApiService.df_to_records is required")
        return self.df_to_records(df)

    def _etf_storage_records(self) -> list[dict]:
        if self.etf_storage_records is None:
            raise RuntimeError("SearchApiService.etf_storage_records is required")
        return self.etf_storage_records()

    def _etf_payload_from_records(self, records: list[dict], *, q: str, limit: int) -> dict:
        if self.etf_payload_from_records is None:
            raise RuntimeError("SearchApiService.etf_payload_from_records is required")
        return self.etf_payload_from_records(records, q=q, limit=limit)

    def _enrich_kr_company_identities(self, rows: list[dict]) -> list[dict]:
        if self.enrich_kr_company_identities is None:
            raise RuntimeError("SearchApiService.enrich_kr_company_identities is required")
        return self.enrich_kr_company_identities(rows)

    def _default_logo_url(self, ticker: str, market: str) -> str:
        if self.default_logo_url is None:
            raise RuntimeError("SearchApiService.default_logo_url is required")
        return self.default_logo_url(ticker, market)

    def _kr_code(self, value: object) -> str:
        if self.kr_code is None:
            raise RuntimeError("SearchApiService.kr_code is required")
        return self.kr_code(value)
