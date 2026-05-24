"""Refresh ETF holdings from live data providers with curated fallback."""

from __future__ import annotations

import json
import os
import time
from datetime import date
from typing import Callable, Iterable
from urllib.parse import urlencode
from urllib.request import Request, urlopen


KIS_DEFAULT_BASE_URL = "https://openapi.koreainvestment.com:9443"
KIS_TOKEN_PATH = "/oauth2/tokenP"
KIS_COMPONENT_PATH = "/uapi/etfetn/v1/quotations/inquire-component-stock-price"
KIS_COMPONENT_TR_ID = "FHKST121600C0"
KIS_COMPONENT_SCREEN_CODE = "11216"
KETF_BASE_URL = "https://www.k-etf.com/api/v0"


def refresh_etf_holdings(
    items: Iterable[dict],
    *,
    limit: int | None = None,
    max_holdings: int = 10,
    pause_seconds: float = 0.2,
) -> list[dict]:
    """Return ETF insight items with refreshed holdings where live data is available.

    The function is intentionally conservative: if a provider fails or returns an
    empty holdings table, the existing curated holdings are preserved.
    """

    rows = [dict(item) for item in items]
    stats = {
        "attempted": 0,
        "refreshed": 0,
        "provider": "yfinance,kis,pykrx,ketf",
        "usAttempted": 0,
        "krAttempted": 0,
        "yfinanceRefreshed": 0,
        "kisRefreshed": 0,
        "pykrxRefreshed": 0,
        "ketfRefreshed": 0,
    }
    kis_client = KisEtfHoldingsClient.from_env()
    for item in rows:
        if limit is not None and stats["attempted"] >= limit:
            break
        if not should_refresh_holdings(item):
            continue
        stats["attempted"] += 1
        region = item_region(item)
        if region == "US":
            stats["usAttempted"] += 1
        elif region == "KR":
            stats["krAttempted"] += 1

        live_holdings, provider = fetch_live_holdings(item, kis_client, max_holdings=max_holdings)
        if not live_holdings:
            continue
        item["holdings"] = live_holdings
        item["asOf"] = date.today().isoformat()
        item["dataSource"] = append_data_source(item.get("dataSource"), f"holdings:{provider}")
        stats["refreshed"] += 1
        if provider == "yfinance":
            stats["yfinanceRefreshed"] += 1
        elif provider == "kis":
            stats["kisRefreshed"] += 1
        elif provider == "pykrx":
            stats["pykrxRefreshed"] += 1
        elif provider == "ketf":
            stats["ketfRefreshed"] += 1
        if pause_seconds > 0:
            time.sleep(pause_seconds)

    for item in rows:
        item["_holdingsRefresh"] = dict(stats)
    return rows


def fetch_live_holdings(item: dict, kis_client: "KisEtfHoldingsClient", *, max_holdings: int = 10) -> tuple[list[dict], str]:
    region = item_region(item)
    ticker = item_ticker(item)
    if region == "US":
        holdings = fetch_yfinance_holdings(ticker, max_holdings=max_holdings)
        return (holdings, "yfinance") if holdings else ([], "")
    if region == "KR":
        holdings = kis_client.fetch_holdings(ticker, max_holdings=max_holdings)
        if holdings:
            return holdings, "kis"
        holdings = fetch_ketf_etf_holdings(ticker, max_holdings=max_holdings)
        if holdings:
            return holdings, "ketf"
        holdings = fetch_pykrx_etf_holdings(ticker, max_holdings=max_holdings)
        if holdings:
            return holdings, "pykrx"
        return [], ""
    return [], ""


def should_refresh_holdings(item: dict) -> bool:
    region = item_region(item)
    if region == "US":
        return should_refresh_with_yfinance(item)
    if region == "KR":
        return bool(clean_kr_etf_ticker(item_ticker(item)))
    return False


def should_refresh_with_yfinance(item: dict) -> bool:
    region = item_region(item)
    ticker = item_ticker(item)
    category = str(item.get("category") or item.get("Category") or "").strip()
    if region != "US" or not ticker:
        return False
    if category in {"채권", "원자재"}:
        return False
    return True


def item_region(item: dict) -> str:
    return str(item.get("region") or item.get("Market") or "").strip().upper()


def item_ticker(item: dict) -> str:
    return str(item.get("ticker") or item.get("Ticker") or "").strip().upper()


def fetch_yfinance_holdings(ticker: str, *, max_holdings: int = 10) -> list[dict]:
    try:
        import yfinance as yf
    except Exception:
        return []

    try:
        table = yf.Ticker(ticker).funds_data.top_holdings
    except Exception:
        return []
    return holdings_from_table(table, max_holdings=max_holdings)


class KisEtfHoldingsClient:
    def __init__(
        self,
        app_key: str,
        app_secret: str,
        *,
        base_url: str = KIS_DEFAULT_BASE_URL,
        session=None,
    ):
        self.app_key = app_key.strip()
        self.app_secret = app_secret.strip()
        self.base_url = base_url.rstrip("/")
        self.session = session
        self._token: str | None = None

    @classmethod
    def from_env(cls) -> "KisEtfHoldingsClient":
        app_key = first_env("KIS_APP_KEY", "KOREA_INVESTMENT_APP_KEY", "KIS_MY_APP")
        app_secret = first_env("KIS_APP_SECRET", "KOREA_INVESTMENT_APP_SECRET", "KIS_MY_SEC")
        base_url = first_env("KIS_BASE_URL", "KOREA_INVESTMENT_BASE_URL") or KIS_DEFAULT_BASE_URL
        return cls(app_key or "", app_secret or "", base_url=base_url)

    def available(self) -> bool:
        return bool(self.app_key and self.app_secret)

    def fetch_holdings(self, ticker: str, *, max_holdings: int = 10) -> list[dict]:
        code = clean_kr_etf_ticker(ticker)
        if not self.available() or not code:
            return []
        token = self.access_token()
        if not token:
            return []
        try:
            requests = self._requests()
            response = requests.get(
                f"{self.base_url}{KIS_COMPONENT_PATH}",
                headers={
                    "Content-Type": "application/json",
                    "Accept": "text/plain",
                    "authorization": f"Bearer {token}",
                    "appkey": self.app_key,
                    "appsecret": self.app_secret,
                    "tr_id": KIS_COMPONENT_TR_ID,
                    "custtype": "P",
                    "tr_cont": "",
                },
                params={
                    "FID_COND_MRKT_DIV_CODE": "J",
                    "FID_INPUT_ISCD": code,
                    "FID_COND_SCR_DIV_CODE": KIS_COMPONENT_SCREEN_CODE,
                },
                timeout=20,
            )
            response.raise_for_status()
            body = response.json()
        except Exception:
            return []
        if str(body.get("rt_cd", "0")) not in {"0", ""}:
            return []
        return kis_holdings_from_rows(body.get("output2") or [], max_holdings=max_holdings)

    def access_token(self) -> str | None:
        if self._token:
            return self._token
        if not self.available():
            return None
        try:
            requests = self._requests()
            response = requests.post(
                f"{self.base_url}{KIS_TOKEN_PATH}",
                json={
                    "grant_type": "client_credentials",
                    "appkey": self.app_key,
                    "appsecret": self.app_secret,
                },
                headers={
                    "Content-Type": "application/json",
                    "Accept": "text/plain",
                },
                timeout=20,
            )
            response.raise_for_status()
            token = str(response.json().get("access_token") or "").strip()
        except Exception:
            return None
        self._token = token or None
        return self._token

    def _requests(self):
        if self.session is not None:
            return self.session
        import requests

        return requests


def fetch_pykrx_etf_holdings(ticker: str, *, max_holdings: int = 10) -> list[dict]:
    code = clean_kr_etf_ticker(ticker)
    if not code:
        return []
    try:
        from pykrx import stock
    except Exception:
        return []
    try:
        table = stock.get_etf_portfolio_deposit_file(code)
    except Exception:
        return []
    return pykrx_holdings_from_table(table, name_lookup=safe_pykrx_name_lookup(stock), max_holdings=max_holdings)


def fetch_ketf_etf_holdings(ticker: str, *, max_holdings: int = 10) -> list[dict]:
    code = clean_kr_etf_ticker(ticker)
    if not code:
        return []
    try:
        latest = http_json(f"{KETF_BASE_URL}/holds/latest?{urlencode({'code': f'XKRX-EF-{code}'})}")
        dates = [str(value).strip() for value in latest if str(value or "").strip()]
        if not dates:
            return []
        as_of = dates[0]
        payload = {
            "dates": [as_of.replace("-", "")],
            "code": code,
            "market": "XKRX",
        }
        body = http_json(f"{KETF_BASE_URL}/holds/top20holds/indates", payload=payload)
    except Exception:
        return []

    rows = ketf_rows_for_date(body, as_of)
    return ketf_holdings_from_rows(rows, max_holdings=max_holdings)


def kis_holdings_from_rows(rows: Iterable[dict], *, max_holdings: int = 10) -> list[dict]:
    holdings: list[dict] = []
    for row in rows:
        ticker = clean_kr_component_ticker(value_from(row, ["stck_shrn_iscd", "STCK_SHRN_ISCD", "ticker", "Ticker"]))
        name = str(value_from(row, ["hts_kor_isnm", "HTS_KOR_ISNM", "name", "Name"]) or ticker).strip()
        weight = parse_percent_weight(value_from(row, ["etf_cnfg_issu_rlim", "ETF구성종목비중", "weight", "Weight"]))
        if not ticker or not name or weight is None or weight <= 0:
            continue
        holdings.append({"ticker": ticker, "name": name, "weight": weight})
    return sorted(holdings, key=lambda row: row["weight"], reverse=True)[:max_holdings]


def ketf_rows_for_date(body, as_of: str):
    if isinstance(body, dict):
        rows = body.get(as_of) or body.get(as_of.replace("-", ""))
        if rows is not None:
            return rows
        for value in body.values():
            if isinstance(value, list):
                return value
    return body if isinstance(body, list) else []


def ketf_holdings_from_rows(rows: Iterable[dict], *, max_holdings: int = 10) -> list[dict]:
    holdings: list[dict] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = str(value_from(row, ["name", "Name", "hname", "HNAME"]) or "").strip()
        raw_ticker = value_from(row, ["ticker", "Ticker", "code", "Code", "symbol", "Symbol"])
        ticker = clean_kr_component_ticker(raw_ticker) if raw_ticker else name
        weight = parse_percent_weight(value_from(row, ["ratio", "Ratio", "weight", "Weight"]))
        if not ticker or not name or weight is None or weight <= 0:
            continue
        holdings.append({"ticker": ticker, "name": name, "weight": weight})
    return sorted(holdings, key=lambda row: row["weight"], reverse=True)[:max_holdings]


def pykrx_holdings_from_table(
    table,
    *,
    name_lookup: Callable[[str], str] | None = None,
    max_holdings: int = 10,
) -> list[dict]:
    if table is None or getattr(table, "empty", False):
        return []
    columns = {str(column).strip(): column for column in getattr(table, "columns", [])}
    name_column = first_present(columns, ["종목명", "구성종목명", "한글명", "Name", "name"])
    weight_column = first_present(columns, ["비중", "ETF구성종목비중", "weight", "Weight"])
    if weight_column is None:
        return []

    holdings: list[dict] = []
    for symbol, row in table.iterrows():
        ticker = clean_kr_component_ticker(symbol)
        name = str(row.get(name_column) if name_column is not None else "").strip()
        if not name and name_lookup is not None:
            name = name_lookup(short_kr_ticker(ticker))
        if not name:
            name = ticker
        weight = parse_percent_weight(row.get(weight_column))
        if not ticker or weight is None or weight <= 0:
            continue
        holdings.append({"ticker": ticker, "name": name, "weight": weight})
    return sorted(holdings, key=lambda row: row["weight"], reverse=True)[:max_holdings]


def holdings_from_table(table, *, max_holdings: int = 10) -> list[dict]:
    if table is None or getattr(table, "empty", False):
        return []
    columns = {str(column).strip().lower(): column for column in getattr(table, "columns", [])}
    name_column = columns.get("name") or columns.get("holding")
    weight_column = (
        columns.get("holding percent")
        or columns.get("holding_percent")
        or columns.get("weight")
        or columns.get("% assets")
    )
    if weight_column is None:
        return []

    holdings: list[dict] = []
    for symbol, row in table.iterrows():
        ticker = str(symbol or "").strip().upper()
        if not ticker or ticker.lower() in {"nan", "none"}:
            continue
        name = str(row.get(name_column) if name_column is not None else ticker).strip()
        weight = parse_weight(row.get(weight_column))
        if not name or weight is None or weight <= 0:
            continue
        holdings.append({
            "ticker": ticker,
            "name": name,
            "weight": weight,
        })
        if len(holdings) >= max_holdings:
            break
    return holdings


def clean_kr_etf_ticker(ticker: str) -> str:
    code = short_kr_ticker(ticker)
    return code if len(code) == 6 and code.isdigit() else ""


def clean_kr_component_ticker(ticker) -> str:
    code = short_kr_ticker(str(ticker or ""))
    if len(code) == 6 and code.isdigit():
        return f"{code}.KS"
    return code.upper()


def short_kr_ticker(ticker: str) -> str:
    text = str(ticker or "").strip().upper()
    for suffix in (".KS", ".KQ"):
        if text.endswith(suffix):
            return text[:-3]
    return text


def safe_pykrx_name_lookup(stock_module) -> Callable[[str], str]:
    def lookup(ticker: str) -> str:
        try:
            return str(stock_module.get_market_ticker_name(ticker) or "").strip()
        except Exception:
            return ""

    return lookup


def http_json(url: str, *, payload: dict | None = None, timeout: int = 20):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = Request(
        url,
        data=data,
        method="POST" if payload is not None else "GET",
        headers={
            "Accept": "application/json",
            "User-Agent": "QuantBridge ETF holdings refresh",
            **({"Content-Type": "application/json"} if payload is not None else {}),
        },
    )
    with urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def first_env(*names: str) -> str | None:
    for name in names:
        value = os.getenv(name)
        if value:
            return value
    return None


def first_present(mapping: dict, names: list[str]):
    for name in names:
        if name in mapping:
            return mapping[name]
    return None


def value_from(row: dict, keys: list[str]):
    for key in keys:
        try:
            value = row.get(key)
        except AttributeError:
            value = None
        if value not in (None, ""):
            return value
    return None


def parse_weight(value) -> float | None:
    try:
        text = str(value).replace("%", "").replace(",", "").strip()
        if not text or text.lower() in {"nan", "none", "null"}:
            return None
        number = float(text)
    except (TypeError, ValueError):
        return None
    if number > 1:
        number /= 100
    return number


def parse_percent_weight(value) -> float | None:
    try:
        text = str(value).replace("%", "").replace(",", "").strip()
        if not text or text.lower() in {"nan", "none", "null"}:
            return None
        number = float(text)
    except (TypeError, ValueError):
        return None
    return number / 100


def append_data_source(existing, marker: str) -> str:
    parts = [part.strip() for part in str(existing or "").split(";") if part.strip()]
    if marker not in parts:
        parts.append(marker)
    return ";".join(parts) if parts else marker
