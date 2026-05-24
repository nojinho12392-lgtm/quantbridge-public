from __future__ import annotations

from datetime import datetime, time, timedelta, timezone
from typing import Any, Callable
from zoneinfo import ZoneInfo


_US_MARKET_TZ = ZoneInfo("America/New_York")


class StockDetailService:
    def __init__(
        self,
        *,
        repository: Callable[[], Any],
        cached: Callable[[str, Callable[[], dict]], dict],
        infer_market: Callable[[str], str],
        utc_now_iso: Callable[[], str],
        identity_has_valuation: Callable[[dict | None], bool],
        identity_from_storage: Callable[[str, str], dict | None],
        merge_identity_payload: Callable[[dict | None, dict | None, str], dict],
        info_from_cached: Callable[[dict | None, list[dict]], dict],
        df_to_records: Callable[[Any], list[dict]],
        price_records_from_yfinance: Callable[[Any], list[dict]],
        first_float: Callable[..., float | None],
        merge_company_profile: Callable[[dict, dict], dict],
        allow_external_fetch: Callable[[], bool] | None = None,
        kr_quote_batch: Callable[[list[str]], dict] | None = None,
    ) -> None:
        self.repository = repository
        self.cached = cached
        self.infer_market = infer_market
        self.utc_now_iso = utc_now_iso
        self.identity_has_valuation = identity_has_valuation
        self.identity_from_storage = identity_from_storage
        self.merge_identity_payload = merge_identity_payload
        self.info_from_cached = info_from_cached
        self.df_to_records = df_to_records
        self.price_records_from_yfinance = price_records_from_yfinance
        self.first_float = first_float
        self.merge_company_profile = merge_company_profile
        self.allow_external_fetch = allow_external_fetch or (lambda: False)
        self.kr_quote_batch = kr_quote_batch or (lambda tickers: {})

    def detail(self, *, ticker: str, period: str = "6mo", refresh: bool = False, profile: bool = False) -> dict:
        safe_period = period if period in ("1mo", "3mo", "6mo", "1y", "2y", "3y", "5y") else "6mo"
        normal_ticker = str(ticker or "").strip().upper()
        market = self.infer_market(normal_ticker)

        def load() -> dict:
            now = self.utc_now_iso()
            repo = self.repository()
            identity = repo.read_identity(normal_ticker, market=market) or {}
            if not self.identity_has_valuation(identity):
                storage_identity = self.identity_from_storage(normal_ticker, market)
                identity = self.merge_identity_payload(identity, storage_identity, normal_ticker)
            else:
                identity = self.merge_identity_payload(identity, None, normal_ticker)
            if identity and identity.get("ticker"):
                try:
                    repo.upsert_identity(identity)
                except Exception:
                    pass
            price_snapshot = self._latest_price_snapshot(repo, normal_ticker, market)
            kr_quote = self._kr_realtime_quote(normal_ticker, market)

            cached_price_records: list[dict] = []
            cached_history_complete = False
            cached_prices = repo.read_prices(normal_ticker, period=safe_period, market=market)
            if not cached_prices.empty:
                cached_price_records = self.df_to_records(
                    cached_prices[["date", "open", "high", "low", "close", "volume"]]
                )
                cached_price_records = self._normalized_price_records(cached_price_records, market)
                cached_history_complete = self._cached_history_covers_period(cached_price_records, safe_period)
            if not refresh and cached_price_records and cached_history_complete:
                info = self.info_from_cached(identity, cached_price_records)
                info.update(self._info_from_price_snapshot(price_snapshot, cached_price_records, market))
                info.update(self._info_from_kr_quote(kr_quote))
                return {
                    "prices": cached_price_records,
                    "info": info,
                    "source": "storage",
                    "updated_at": now,
                    "error": None,
                }
            initial_info = self.info_from_cached(identity, cached_price_records)
            initial_info.update(self._info_from_price_snapshot(price_snapshot, cached_price_records, market))
            initial_info.update(self._info_from_kr_quote(kr_quote))
            result: dict = {
                "prices": cached_price_records,
                "info": initial_info,
                "source": "storage_partial" if cached_price_records else "storage_snapshot",
                "updated_at": now,
                "error": None,
            }
            errors: list[str] = []

            if not self.allow_external_fetch():
                result["storage_complete"] = cached_history_complete
                if cached_history_complete:
                    result["source"] = "storage"
                return result

            try:
                import yfinance as yf
            except ImportError:
                return {
                    **result,
                    "source": "storage",
                    "error": "yfinance is not installed on the API server",
                }

            result["source"] = "storage+profile" if cached_price_records else "yfinance"
            should_fetch_prices = refresh or not result["prices"] or not cached_history_complete

            if should_fetch_prices:
                try:
                    incremental = bool(result["prices"] and cached_history_complete)
                    latest_date = self._latest_record_date(result["prices"]) if incremental else None
                    if incremental and latest_date is not None:
                        start = (latest_date - timedelta(days=3)).date().isoformat()
                        raw = yf.download(
                            normal_ticker,
                            start=start,
                            auto_adjust=True,
                            progress=False,
                            ignore_tz=False,
                        )
                    else:
                        raw = yf.download(
                            normal_ticker,
                            period=safe_period,
                            auto_adjust=True,
                            progress=False,
                            ignore_tz=False,
                        )
                    fetched_prices = self.price_records_from_yfinance(raw)
                    if fetched_prices:
                        try:
                            repo.upsert_prices(normal_ticker, market, fetched_prices, source="yfinance")
                        except Exception as exc:
                            errors.append(f"price cache write failed: {exc}")
                        result["prices"] = (
                            self._merge_price_records(result["prices"], fetched_prices)
                            if incremental
                            else fetched_prices
                        )
                        result["prices"] = self._normalized_price_records(result["prices"], market)
                        result["source"] = "storage+yfinance_incremental" if incremental else "yfinance"
                    elif not result["prices"]:
                        errors.append("price history is empty")
                except Exception as exc:
                    errors.append(f"price history fetch failed: {exc}")

            try:
                stock = yf.Ticker(normal_ticker)
                fast_info = stock.fast_info

                info: dict = self.info_from_cached(identity, result["prices"])
                info.update(self._info_from_price_snapshot(price_snapshot, result["prices"], market))
                info.update(self._info_from_kr_quote(kr_quote))
                fast_info_updates = {
                    "week52_high": self.first_float(
                        getattr(fast_info, "year_high", None), info.get("week52_high")
                    ),
                    "week52_low": self.first_float(
                        getattr(fast_info, "year_low", None), info.get("week52_low")
                    ),
                    "market_cap": self.first_float(
                        getattr(fast_info, "market_cap", None), info.get("market_cap")
                    ),
                }
                if not self._should_use_last_regular_close_change(market):
                    fast_info_updates.update(
                        {
                            "current_price": self.first_float(
                                getattr(fast_info, "last_price", None), info.get("current_price")
                            ),
                            "prev_close": self.first_float(
                                getattr(fast_info, "previous_close", None), info.get("prev_close")
                            ),
                            "daily_change_horizon": "오늘",
                        }
                    )
                info.update(fast_info_updates)
                if not self._should_use_last_regular_close_change(market):
                    current = self.first_float(info.get("current_price"))
                    previous = self.first_float(info.get("prev_close"))
                    if current is not None and previous is not None and previous > 0:
                        info["daily_change_pct"] = current / previous - 1.0
                        info["daily_change_horizon"] = "오늘"
                info.update(self._info_from_kr_quote(kr_quote))

                if profile:
                    full = stock.info
                    if isinstance(full, dict) and len(full) > 5:
                        info = self.merge_company_profile(info, full)

                result["info"] = info
                try:
                    identity.update(
                        {
                            "market_cap": info.get("market_cap") or identity.get("market_cap"),
                            "sector": info.get("sector") or identity.get("sector"),
                        }
                    )
                    repo.upsert_identity(identity)
                except Exception:
                    pass
            except Exception as exc:
                if not result.get("info"):
                    result["info"] = self.info_from_cached(identity, result["prices"])
                errors.append(f"market info fetch failed: {exc}")

            if errors:
                result["error"] = "; ".join(errors)
            return result

        if refresh:
            return load()
        return self.cached(f"stock_{normal_ticker}_{safe_period}_{int(profile)}", load)

    def _latest_price_snapshot(self, repo: Any, ticker: str, market: str) -> dict:
        try:
            metrics = repo.read_price_metrics([ticker], market=market)
        except Exception:
            return {}
        if getattr(metrics, "empty", True):
            return {}
        try:
            row = metrics.iloc[0].to_dict()
        except Exception:
            return {}
        return row if isinstance(row, dict) else {}

    def _kr_realtime_quote(self, ticker: str, market: str) -> dict:
        if str(market or "").strip().upper() != "KR":
            return {}
        try:
            quotes = self.kr_quote_batch([ticker])
        except Exception:
            return {}
        if not isinstance(quotes, dict):
            return {}
        normal = str(ticker or "").strip().upper()
        candidates = [normal]
        code = normal.split(".", 1)[0]
        if code and code not in candidates:
            candidates.append(code)
        for candidate in candidates:
            quote = quotes.get(candidate)
            if isinstance(quote, dict) and self.first_float(quote.get("current_price")) is not None:
                return quote
        return {}

    def _info_from_kr_quote(self, quote: dict) -> dict:
        current = self.first_float(quote.get("current_price"))
        if current is None:
            return {}
        change_pct = self.first_float(quote.get("daily_change_pct"))
        info = {
            "current_price": current,
            "daily_change_horizon": str(quote.get("daily_change_horizon") or "오늘"),
            "price_updated_at": self._iso_or_text(quote.get("updated_at")),
        }
        if change_pct is not None:
            info["daily_change_pct"] = change_pct
            if change_pct > -0.9999:
                info["prev_close"] = current / (1.0 + change_pct)
        return {key: value for key, value in info.items() if value not in (None, "")}

    def _info_from_price_snapshot(self, snapshot: dict, records: list[dict], market: str) -> dict:
        if self._should_use_last_regular_close_change(market):
            regular_info = self._regular_close_info(records)
            if regular_info:
                snapshot_updated = self._iso_or_text(snapshot.get("as_of") or snapshot.get("updated_at"))
                if snapshot_updated:
                    regular_info["price_updated_at"] = snapshot_updated
                return regular_info

        current = self.first_float(snapshot.get("current_price"), snapshot.get("Current_Price"))
        if current is None:
            return {}
        info = {
            "current_price": current,
            "daily_change_horizon": "오늘",
            "price_updated_at": self._iso_or_text(snapshot.get("as_of") or snapshot.get("updated_at")),
        }
        previous_close = self._previous_close_for_snapshot(records, snapshot.get("as_of"))
        if previous_close is not None:
            info["prev_close"] = previous_close
            if previous_close > 0:
                info["daily_change_pct"] = current / previous_close - 1.0
        return {key: value for key, value in info.items() if value not in (None, "")}

    def _regular_close_info(self, records: list[dict]) -> dict:
        dated_closes: list[tuple[datetime, float]] = []
        for record in records:
            date = self._record_date(record)
            close = self.first_float(record.get("close"), record.get("Close"))
            if date is not None and close is not None:
                dated_closes.append((date, close))
        dated_closes.sort(key=lambda item: item[0])
        if len(dated_closes) < 2:
            return {}

        latest_date, current = dated_closes[-1]
        _, previous = dated_closes[-2]
        info = {
            "current_price": current,
            "prev_close": previous,
            "daily_change_horizon": "전장",
            "price_updated_at": latest_date.date().isoformat(),
        }
        if previous > 0:
            info["daily_change_pct"] = current / previous - 1.0
        return info

    def _previous_close_for_snapshot(self, records: list[dict], as_of: Any) -> float | None:
        dated_closes: list[tuple[datetime, float]] = []
        for record in records:
            date = self._record_date(record)
            close = self.first_float(record.get("close"), record.get("Close"))
            if date is not None and close is not None:
                dated_closes.append((date, close))
        dated_closes.sort(key=lambda item: item[0])
        if len(dated_closes) < 2:
            return None

        snapshot_date = self._record_date({"date": self._iso_or_text(as_of)})
        if snapshot_date is not None:
            prior = [close for date, close in dated_closes if date.date() < snapshot_date.date()]
            if prior:
                return prior[-1]
        return dated_closes[-2][1]

    def _normalized_price_records(self, records: list[dict], market: str) -> list[dict]:
        records = self._contract_price_records(records)
        records = [
            record
            for record in records
            if (self._record_date(record) is None or self._record_date(record).weekday() < 5)
        ]
        if str(market or "").strip().upper() != "US":
            return records
        if self._us_equity_regular_session_phase() != "pre_open":
            return records
        today = self._us_equity_local_date()
        filtered = [
            record
            for record in records
            if (self._record_date(record) is not None and self._record_date(record).date() < today)
        ]
        return filtered or records

    def _contract_price_records(self, records: list[dict]) -> list[dict]:
        normalized: list[dict] = []
        for record in records or []:
            date = self._record_date(record)
            close = self.first_float(record.get("close"), record.get("Close"))
            if date is None or close is None:
                continue
            open_price = self.first_float(record.get("open"), record.get("Open"), close) or close
            high = self.first_float(record.get("high"), record.get("High"), max(open_price, close))
            low = self.first_float(record.get("low"), record.get("Low"), min(open_price, close))
            high = max(high or close, open_price, close)
            low = min(low or close, open_price, close)
            normalized.append(
                {
                    "date": date.date().isoformat(),
                    "open": open_price,
                    "high": high,
                    "low": low,
                    "close": close,
                    "volume": self.first_float(record.get("volume"), record.get("Volume")),
                }
            )
        return normalized

    def _should_use_last_regular_close_change(self, market: str) -> bool:
        return str(market or "").strip().upper() == "US" and self._us_equity_regular_session_phase() != "open"

    @staticmethod
    def _us_equity_regular_session_phase(now: datetime | None = None) -> str:
        local = (now or datetime.now(timezone.utc)).astimezone(_US_MARKET_TZ)
        if local.weekday() >= 5:
            return "closed_day"
        if local.time() < time(9, 30):
            return "pre_open"
        if local.time() <= time(16, 0):
            return "open"
        return "after_close"

    @staticmethod
    def _us_equity_local_date(now: datetime | None = None):
        return (now or datetime.now(timezone.utc)).astimezone(_US_MARKET_TZ).date()

    @staticmethod
    def _iso_or_text(value: Any) -> str:
        if value is None:
            return ""
        if hasattr(value, "isoformat"):
            return value.isoformat()
        return str(value)

    def _latest_record_date(self, records: list[dict]) -> datetime | None:
        dates = [self._record_date(record) for record in records]
        dates = [date for date in dates if date is not None]
        return max(dates) if dates else None

    def _merge_price_records(self, existing: list[dict], incoming: list[dict]) -> list[dict]:
        by_date: dict[str, dict] = {}
        for record in existing + incoming:
            date = self._record_date(record)
            if date is None:
                continue
            by_date[date.date().isoformat()] = record
        return [
            by_date[key]
            for key in sorted(by_date)
        ]

    def _cached_history_covers_period(self, records: list[dict], period: str) -> bool:
        if not records:
            return False

        min_points_by_period = {
            "1mo": 15,
            "3mo": 45,
            "6mo": 90,
            "1y": 180,
            "2y": 360,
            "3y": 540,
            "5y": 900,
        }
        min_span_days_by_period = {
            "1mo": 20,
            "3mo": 60,
            "6mo": 120,
            "1y": 240,
            "2y": 480,
            "3y": 720,
            "5y": 1200,
        }
        min_points = min_points_by_period.get(period, 90)
        min_span_days = min_span_days_by_period.get(period, 120)
        if len(records) >= min_points:
            return True

        dates = [self._record_date(record) for record in records]
        dates = [date for date in dates if date is not None]
        if len(dates) < 2:
            return False
        span_days = (max(dates) - min(dates)).days
        return span_days >= min_span_days

    @staticmethod
    def _record_date(record: dict) -> datetime | None:
        raw = record.get("date") or record.get("Date") or record.get("timestamp")
        if raw is None:
            return None
        text = str(raw).strip()
        if not text:
            return None
        try:
            return datetime.fromisoformat(text[:10])
        except ValueError:
            return None
