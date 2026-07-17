from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass

import pandas as pd


def fast_info_get(info, key: str):
    try:
        return info.get(key)
    except AttributeError:
        try:
            return info[key]
        except Exception:
            return None


@dataclass(frozen=True)
class YFinanceQuoteFrameBuilder:
    kr_code: Callable[[object], str | None]

    def stock_symbol(self, ticker: str, market: str) -> str:
        normal = str(ticker or "").strip().upper()
        if market.upper() == "KR":
            code = self.kr_code(normal)
            return f"{code}.KS" if code else normal
        return normal.replace(".", "-")

    @staticmethod
    def close_frame(raw: pd.DataFrame, symbols: list[str]) -> pd.DataFrame:
        if raw.empty:
            return pd.DataFrame()
        if isinstance(raw.columns, pd.MultiIndex):
            levels = [set(map(str, raw.columns.get_level_values(level))) for level in range(raw.columns.nlevels)]
            if "Close" in levels[0]:
                frame = raw["Close"].copy()
            elif raw.columns.nlevels > 1 and "Close" in levels[1]:
                frame = raw.xs("Close", axis=1, level=1).copy()
            else:
                return pd.DataFrame()
        else:
            if "Close" not in raw.columns:
                return pd.DataFrame()
            close = raw["Close"].copy()
            frame = close.to_frame(name=symbols[0]) if isinstance(close, pd.Series) else close

        if isinstance(frame, pd.Series):
            frame = frame.to_frame(name=symbols[0])
        frame.columns = [str(column).strip().upper() for column in frame.columns]
        frame.index = pd.to_datetime(frame.index, errors="coerce")
        frame = frame[frame.index.notna()]
        return frame.sort_index()

    def download_close_frame(
        self,
        yf,
        symbols: list[str],
        *,
        period: str,
        interval: str,
        batch_size: int = 45,
    ) -> pd.DataFrame:
        frames: list[pd.DataFrame] = []
        clean_symbols = [symbol for symbol in symbols if symbol]
        for index in range(0, len(clean_symbols), max(1, batch_size)):
            batch = clean_symbols[index:index + batch_size]
            try:
                raw = yf.download(
                    batch,
                    period=period,
                    interval=interval,
                    auto_adjust=False,
                    progress=False,
                    ignore_tz=False,
                    threads=True,
                    timeout=8,
                )
                frame = self.close_frame(raw, batch)
                if not frame.empty:
                    frames.append(frame)
            except Exception:
                continue
        if not frames:
            return pd.DataFrame()
        merged = pd.concat(frames, axis=1)
        return merged.loc[:, ~merged.columns.duplicated()].sort_index()


@dataclass(frozen=True)
class YFinanceQuoteBatcher:
    yf_stock_symbol: Callable[[str, str], str]
    should_use_last_regular_close_change: Callable[[str | None], bool]
    safe_float: Callable[[object], float | None]
    yf_download_close_frame: Callable[..., pd.DataFrame]
    drop_unsettled_us_daily_series: Callable[[pd.Series, str | None], pd.Series]
    comparison_match_keys: Callable[[str], set[str]]
    utc_now_iso: Callable[[], str]

    def yfinance_stock_quote_batch(self, tickers: list[str], market: str) -> dict[str, dict]:
        if market.upper() == "KR":
            return {}
        requested = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
        if not requested:
            return {}
        symbol_by_ticker = {ticker: self.yf_stock_symbol(ticker, market) for ticker in requested}
        try:
            import yfinance as yf
        except ImportError:
            return {}

        use_regular_close = self.should_use_last_regular_close_change(market)
        quotes: dict[str, dict] = {}
        fallback_tickers = requested

        if not use_regular_close:
            def fast_quote(ticker: str, symbol: str) -> tuple[str, dict | None]:
                try:
                    fast_info = getattr(yf.Ticker(symbol), "fast_info", {}) or {}
                    last = self.safe_float(fast_info_get(fast_info, "last_price"))
                    previous = self.safe_float(fast_info_get(fast_info, "previous_close"))
                    if last is None or previous is None or previous <= 0:
                        return ticker, None
                    return ticker, {
                        "current_price": last,
                        "daily_change_pct": (last / previous) - 1.0,
                        "daily_change_horizon": "오늘",
                        "updated_at": self.utc_now_iso(),
                        "source": "yfinance_fast_info",
                    }
                except Exception:
                    return ticker, None

            workers = max(1, min(24, len(requested)))
            with ThreadPoolExecutor(max_workers=workers) as executor:
                futures = {
                    executor.submit(fast_quote, ticker, symbol_by_ticker[ticker]): ticker
                    for ticker in requested
                }
                for future in as_completed(futures):
                    ticker, payload = future.result()
                    if not payload:
                        continue
                    for key in self.comparison_match_keys(ticker):
                        quotes[key] = payload
                    quotes[ticker] = payload

            fallback_tickers = [ticker for ticker in requested if ticker not in quotes]
            if not fallback_tickers:
                return quotes

        fallback_symbols = list(dict.fromkeys(symbol_by_ticker[ticker] for ticker in fallback_tickers))
        intraday = (
            pd.DataFrame()
            if use_regular_close
            else self.yf_download_close_frame(yf, fallback_symbols, period="2d", interval="15m")
        )
        daily = self.yf_download_close_frame(yf, fallback_symbols, period="7d", interval="1d")

        for ticker in fallback_tickers:
            symbol = symbol_by_ticker[ticker]
            symbol_key = symbol.upper()
            current_price = None
            observed_at = None
            if symbol_key in intraday.columns:
                series = intraday[symbol_key].dropna().sort_index()
                if not series.empty:
                    current_price = self.safe_float(series.iloc[-1])
                    observed_at = pd.Timestamp(series.index[-1])

            daily_series = daily[symbol_key].dropna().sort_index() if symbol_key in daily.columns else pd.Series(dtype=float)
            daily_series = self.drop_unsettled_us_daily_series(daily_series, market)
            previous_close = None
            if (use_regular_close or current_price is None) and not daily_series.empty:
                current_price = self.safe_float(daily_series.iloc[-1])
                observed_at = pd.Timestamp(daily_series.index[-1])

            if not daily_series.empty:
                observed_date = pd.Timestamp(observed_at).date() if observed_at is not None else None
                daily_dates = [pd.Timestamp(index).date() for index in daily_series.index]
                latest_daily_date = daily_dates[-1]
                if observed_date and observed_date > latest_daily_date:
                    previous_close = self.safe_float(daily_series.iloc[-1])
                elif observed_date and observed_date == latest_daily_date and len(daily_series) >= 2:
                    previous_close = self.safe_float(daily_series.iloc[-2])
                elif len(daily_series) >= 2:
                    previous_close = self.safe_float(daily_series.iloc[-2])
                else:
                    previous_close = self.safe_float(daily_series.iloc[-1])

            daily_change = None
            if current_price is not None and previous_close is not None and previous_close > 0:
                daily_change = (current_price / previous_close) - 1.0
            if current_price is None and daily_change is None:
                continue

            payload = {
                "current_price": current_price,
                "daily_change_pct": daily_change,
                "daily_change_horizon": "전장" if use_regular_close and daily_change is not None else "오늘" if daily_change is not None else "",
                "updated_at": observed_at.isoformat() if observed_at is not None else None,
                "source": "yfinance_daily_close" if use_regular_close else "yfinance_intraday",
            }
            for key in self.comparison_match_keys(ticker):
                quotes[key] = payload
            quotes[ticker] = payload
        return quotes
