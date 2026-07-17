"""Local Korean universe builder that does not require Google Sheets.

This module is intentionally small and cache-friendly: it combines live Naver
market snapshots with the existing OpenDART local lake.  It gives research and
developer workflows a way to produce ``KR_Universe`` rows even when the Sheets
service-account setup is unavailable.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

import pandas as pd
import requests
from bs4 import BeautifulSoup

from kr_sector_map import UNCLASSIFIED_SECTOR
from pipeline.data.kr_dart_lake import DEFAULT_OUTPUT_DIR, read_latest_quality_history_features
from quantbridge.schemas import UNIVERSE_COLS


HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    )
}


DEFAULT_KR_SECTOR_OVERRIDES = {
    "000270": "Automobiles",
    "000660": "Semiconductors",
    "003550": "Holding Companies",
    "005380": "Automobiles",
    "005490": "Steel",
    "005930": "Semiconductors",
    "006400": "Battery",
    "009150": "Electronic Components",
    "010130": "Metals",
    "011200": "Shipping",
    "012330": "Auto Parts",
    "015760": "Utilities",
    "017670": "Telecom",
    "018260": "IT Services",
    "028260": "Holding Companies",
    "032830": "Insurance",
    "033780": "Consumer Staples",
    "034730": "Holding Companies",
    "035420": "Internet",
    "035720": "Internet",
    "051910": "Chemicals",
    "055550": "Banking",
    "066570": "Consumer Electronics",
    "068270": "Biopharma",
    "086790": "Banking",
    "090430": "Cosmetics",
    "096770": "Energy",
    "105560": "Banking",
    "207940": "Biopharma",
    "373220": "Battery",
}


@dataclass(frozen=True)
class LocalKrUniverseConfig:
    kospi_limit: int = 30
    kosdaq_limit: int = 0
    delay: float = 0.12
    output_dir: Path = DEFAULT_OUTPUT_DIR


def kr_code(value: object) -> str:
    text = str(value or "").strip().upper()
    if re.fullmatch(r"\d{1,6}", text):
        return text.zfill(6)
    match = re.search(r"(\d{6})", text)
    return match.group(1) if match else ""


def normalize_kr_ticker(value: object, *, default_suffix: str = ".KS") -> str:
    text = str(value or "").strip().upper()
    code = kr_code(text)
    if not code:
        return ""
    if text.endswith((".KS", ".KQ")):
        return f"{code}{text[-3:]}"
    suffix = default_suffix if default_suffix in {".KS", ".KQ"} else ".KS"
    return f"{code}{suffix}"


def _num(value) -> float | None:
    try:
        if value in ("", None):
            return None
        out = float(str(value).replace(",", "").strip())
        return out if pd.notna(out) else None
    except Exception:
        return None


def parse_naver_market_cap(text: str) -> int | None:
    """Parse Korean market-cap text such as ``1,198조 7,267억원`` to KRW."""
    clean = str(text or "").replace("\xa0", " ")
    jo = re.search(r"([\d,]+)\s*조", clean)
    eok = re.search(r"([\d,]+)\s*억", clean)
    jo_val = int(jo.group(1).replace(",", "")) if jo else 0
    eok_val = int(eok.group(1).replace(",", "")) if eok else 0
    total = jo_val * 10**12 + eok_val * 10**8
    return total or None


def _parse_percent(raw: str) -> float | None:
    val = _num(str(raw).replace("%", ""))
    return None if val is None else val / 100.0


def fetch_naver_snapshot(code: str, *, session: requests.Session | None = None, timeout: int = 10) -> dict:
    """Fetch a single Naver Finance snapshot for KR universe/scoring inputs."""
    code = kr_code(code)
    if not code:
        return {}
    sess = session or requests.Session()
    resp = sess.get(
        "https://finance.naver.com/item/main.naver",
        params={"code": code},
        headers=HEADERS,
        timeout=timeout,
    )
    resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")

    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    name = title.split(":")[0].strip() if ":" in title else ""
    out = {
        "Code": code,
        "Name": name,
        "MarketCap": None,
        "PER": None,
        "PBR": None,
        "ROE": None,
        "OperatingMargin": None,
        "GrossMargin": None,
        "RevenueGrowth": None,
        "DebtToEquity": None,
    }

    per_table = soup.select_one("table.per_table")
    if per_table:
        for row in per_table.select("tr"):
            th = row.find("th")
            td = row.find("td")
            if not th or not td:
                continue
            label = th.get_text(strip=True)
            raw = td.get_text(strip=True).split("배")[0]
            val = _num(raw)
            if val is None:
                continue
            if label.startswith("PER") and "추정" not in label:
                out["PER"] = val
            elif label.startswith("PBR"):
                out["PBR"] = val

    label_map = {
        "ROE(지배주주)": ("ROE", True),
        "영업이익률": ("OperatingMargin", True),
        "매출총이익률": ("GrossMargin", True),
        "부채비율": ("DebtToEquity", False),
    }
    fin_table = soup.select_one("table.tb_type1_ifrs")
    if fin_table:
        for row in fin_table.select("tr"):
            cells = [cell.get_text(strip=True) for cell in row.select("td, th")]
            if len(cells) <= 3:
                continue
            label = cells[0]
            if label in label_map:
                field, pct = label_map[label]
                out[field] = _parse_percent(cells[3]) if pct else _num(cells[3])
            if label == "매출액" and len(cells) > 3:
                curr = _num(cells[3])
                prev = _num(cells[2])
                if curr is not None and prev and prev > 0:
                    out["RevenueGrowth"] = round((curr - prev) / prev, 4)

    for row in soup.find_all("tr"):
        cells = [td.get_text(separator=" ", strip=True) for td in row.find_all(["th", "td"])]
        text = " ".join(cells)
        if "시가총액" in text and "시가총액(억)" not in text and "순위" not in text and "억" in text:
            parsed_market_cap = parse_naver_market_cap(text)
            if parsed_market_cap:
                out["MarketCap"] = parsed_market_cap
                break

    return out


def fetch_naver_top_tickers(kospi_limit: int = 30, kosdaq_limit: int = 0) -> list[str]:
    """Return market-cap-sorted KR tickers from Naver's public market-sum pages."""
    tickers: list[str] = []
    seen: set[str] = set()
    for sosok, suffix, limit in ((0, ".KS", kospi_limit), (1, ".KQ", kosdaq_limit)):
        if limit <= 0:
            continue
        collected = 0
        for page in range(1, 60):
            resp = requests.get(
                "https://finance.naver.com/sise/sise_market_sum.nhn",
                params={"sosok": str(sosok), "page": str(page)},
                headers=HEADERS,
                timeout=15,
            )
            soup = BeautifulSoup(resp.text, "html.parser")
            found = 0
            for a in soup.select('a[href*="code="]'):
                code = kr_code(a.get("href", ""))
                if not code or code in seen:
                    continue
                seen.add(code)
                tickers.append(f"{code}{suffix}")
                collected += 1
                found += 1
                if collected >= limit:
                    break
            if collected >= limit or found == 0:
                break
            time.sleep(0.2)
    return tickers


def read_latest_kr_pit_fundamentals(*, output_dir: str | Path = DEFAULT_OUTPUT_DIR) -> pd.DataFrame:
    root = Path(output_dir).expanduser() / "pit_fundamentals"
    if not root.exists():
        return pd.DataFrame()
    snapshots = sorted(
        [path for path in root.glob("snapshot_date=*") if path.is_dir()],
        key=lambda path: path.name,
        reverse=True,
    )
    for snapshot in snapshots:
        frames = []
        for path in sorted(snapshot.glob("*.parquet")):
            try:
                frames.append(pd.read_parquet(path))
            except Exception:
                continue
        if frames:
            return pd.concat(frames, ignore_index=True)
    return pd.DataFrame()


def tickers_from_latest_dart_features(*, output_dir: str | Path = DEFAULT_OUTPUT_DIR) -> list[str]:
    features = read_latest_quality_history_features(output_dir=output_dir)
    if features.empty or "Ticker" not in features.columns:
        return []
    return [normalize_kr_ticker(ticker) for ticker in features["Ticker"].dropna().astype(str).tolist()]


def _latest_pit_by_code(pit_df: pd.DataFrame) -> pd.DataFrame:
    if pit_df.empty or "Ticker" not in pit_df.columns:
        return pd.DataFrame()
    df = pit_df.copy()
    df["Code"] = df["Ticker"].map(kr_code)
    df["Fiscal_Year"] = pd.to_numeric(df.get("Fiscal_Year"), errors="coerce")
    df = df[df["Code"].ne("")]
    if df.empty:
        return pd.DataFrame()
    return df.sort_values(["Code", "Fiscal_Year"]).drop_duplicates("Code", keep="last").set_index("Code")


def build_local_kr_universe(
    tickers: Iterable[str] | None = None,
    *,
    config: LocalKrUniverseConfig | None = None,
    pit_df: pd.DataFrame | None = None,
    naver_fetcher: Callable[[str], dict] | None = fetch_naver_snapshot,
    top_tickers_fetcher: Callable[[int, int], list[str]] | None = fetch_naver_top_tickers,
) -> pd.DataFrame:
    """Build a ``KR_Universe`` DataFrame from local/OpenDART plus Naver snapshots."""
    cfg = config or LocalKrUniverseConfig()
    if tickers is None:
        seed_tickers = tickers_from_latest_dart_features(output_dir=cfg.output_dir)
        if top_tickers_fetcher is not None:
            seed_tickers.extend(top_tickers_fetcher(cfg.kospi_limit, cfg.kosdaq_limit))
        tickers = seed_tickers

    clean_tickers = []
    seen: set[str] = set()
    for ticker in tickers:
        normal = normalize_kr_ticker(ticker)
        if normal and normal not in seen:
            seen.add(normal)
            clean_tickers.append(normal)

    pit = read_latest_kr_pit_fundamentals(output_dir=cfg.output_dir) if pit_df is None else pit_df
    pit_latest = _latest_pit_by_code(pit)
    rows = []

    for ticker in clean_tickers:
        code = kr_code(ticker)
        naver = {}
        if naver_fetcher is not None:
            try:
                naver = naver_fetcher(code) or {}
            except Exception:
                naver = {}
            if cfg.delay > 0:
                time.sleep(cfg.delay)

        pit_row = pit_latest.loc[code].to_dict() if code in pit_latest.index else {}
        sector = DEFAULT_KR_SECTOR_OVERRIDES.get(code, UNCLASSIFIED_SECTOR)

        def first(*values):
            for value in values:
                num = _num(value)
                if num is not None:
                    return num
            return None

        rows.append({
            "Ticker": ticker,
            "Name": naver.get("Name") or ticker,
            "Market": "KR",
            "Sector": sector,
            "MarketCap": first(naver.get("MarketCap")),
            "PER": first(naver.get("PER")),
            "PBR": first(naver.get("PBR")),
            "ROE": first(naver.get("ROE"), pit_row.get("ROE")),
            "Revenue": first(pit_row.get("Revenue")),
            "RevenueGrowth": first(naver.get("RevenueGrowth"), pit_row.get("RevGrowth")),
            "OperatingMargin": first(naver.get("OperatingMargin"), pit_row.get("OperatingMargin")),
            "GrossMargin": first(naver.get("GrossMargin"), pit_row.get("GrossMargin")),
            "DebtToEquity": first(naver.get("DebtToEquity"), pit_row.get("DebtToEquity")),
            "Last_Updated": pd.Timestamp.now().strftime("%Y-%m-%d"),
        })

    df = pd.DataFrame(rows)
    for col in UNIVERSE_COLS:
        if col not in df.columns:
            df[col] = ""
    return df[UNIVERSE_COLS].astype(object).where(pd.notna(df[UNIVERSE_COLS]), "")
