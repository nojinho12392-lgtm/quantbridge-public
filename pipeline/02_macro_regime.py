from __future__ import annotations

# ── Path bootstrap ─────────────────────────────────────────────────────────
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
# ───────────────────────────────────────────────────────────────────────────
"""
pipeline/macro_regime.py
=========================
Macro Regime Detector

Reads five market signals via yfinance and writes the Macro_Regime sheet. The
module is import-safe; network and Sheets writes happen only through run/main.

Run standalone:
  python pipeline/02_macro_regime.py
"""

import time
import warnings
from datetime import datetime
from typing import Callable

import gspread
import pandas as pd
import yfinance as yf

from pipeline.data.fred import fred_api_key, latest_fred_value
from sheets_client import get_spreadsheet
from quantbridge.writers.dual_write import dual_write_key_values


warnings.filterwarnings("ignore")

WEIGHTS = {
    "RISK_ON": {"US": (0.30, 0.25, 0.45), "KR": (0.30, 0.25, 0.45)},
    "NEUTRAL": {"US": (0.40, 0.30, 0.30), "KR": (0.40, 0.35, 0.25)},
    "RISK_OFF": {"US": (0.45, 0.40, 0.15), "KR": (0.45, 0.40, 0.15)},
}

FETCH_DELAY = 0.5


def _download(ticker: str, period: str = "1y") -> pd.Series | None:
    """Download adjusted close price series. Returns None on failure."""
    try:
        df = yf.download(ticker, period=period, auto_adjust=True, progress=False)
        if df.empty:
            return None
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = df.columns.get_level_values(0)
        series = df["Close"].dropna()
        return series if not series.empty else None
    except Exception as exc:
        print(f"  ⚠️  Download failed for {ticker}: {exc}")
        return None


def _latest(series: pd.Series | None) -> float | None:
    if series is None or series.empty:
        return None
    return float(series.iloc[-1])


def _ma200(series: pd.Series | None) -> float | None:
    if series is None or len(series) < 50:
        return None
    return float(series.rolling(min(200, len(series))).mean().iloc[-1])


def _ret_n(series: pd.Series | None, n: int) -> float | None:
    if series is None or len(series) < n + 1:
        return None
    return float(series.iloc[-1] / series.iloc[-n - 1] - 1)


def compute_macro_regime(
    download: Callable[[str, str], pd.Series | None] = _download,
    fred_value: Callable[[str], float | None] | None = None,
    sleep: Callable[[float], None] = time.sleep,
    verbose: bool = True,
) -> dict:
    def log(message: str = "") -> None:
        if verbose:
            print(message)

    log("\n" + "=" * 65)
    log("  MACRO REGIME DETECTOR")
    log("=" * 65)

    log("\n[REGIME] Fetching VIX (^VIX)...")
    vix = _latest(download("^VIX", "1mo"))
    sleep(FETCH_DELAY)
    if vix is None:
        vix_signal, vix_str = 0, "N/A"
        log("  ⚠️  VIX unavailable — signal = 0")
    else:
        vix_str = f"{vix:.1f}"
        if vix < 20:
            vix_signal = 1
        elif vix < 25:
            vix_signal = 0
        else:
            vix_signal = -1
        zone = "Low" if vix < 20 else ("Elevated" if vix < 25 else "High/Extreme")
        log(f"  VIX = {vix:.1f}  ({zone})  → signal {vix_signal:+d}")

    fred_value = fred_value or (lambda series_id: latest_fred_value(series_id, observation_start="2020-01-01"))
    fred_enabled = bool(fred_api_key()) or fred_value is not None

    log("\n[REGIME] Fetching yield curve (FRED DGS10/DTB3, fallback ^TNX/^IRX)...")
    tnx = _latest(download("^TNX", "5d"))
    sleep(FETCH_DELAY)
    irx = _latest(download("^IRX", "5d"))
    sleep(FETCH_DELAY)
    fred_10y = fred_value("DGS10") if fred_enabled else None
    fred_3m = fred_value("DTB3") if fred_enabled else None
    if fred_10y is not None and fred_3m is not None:
        tnx, irx = fred_10y, fred_3m
        yield_source = "FRED"
    else:
        yield_source = "Yahoo"
    if tnx is None or irx is None:
        yield_signal, yield_str = 0, "N/A"
        log("  ⚠️  Yield data unavailable — signal = 0")
    else:
        yield_spread = tnx - irx
        yield_str = f"{yield_spread:+.2f}%"
        if yield_spread > 1.5:
            yield_signal = 1
        elif yield_spread < 0.0:
            yield_signal = -1
        else:
            yield_signal = 0
        shape = "Steep" if yield_spread > 1.5 else ("Normal" if yield_spread >= 0 else "INVERTED")
        log(f"  10Y={tnx:.2f}%  3M={irx:.2f}%  Spread={yield_str}  ({shape}, {yield_source})  → signal {yield_signal:+d}")

    log("\n[REGIME] Fetching S&P 500 trend (^GSPC)...")
    sp_series = download("^GSPC", "1y")
    sp_px = _latest(sp_series)
    sp_ma = _ma200(sp_series)
    sleep(FETCH_DELAY)
    if sp_px is None or sp_ma is None:
        sp_signal, sp_str = 0, "N/A"
        log("  ⚠️  S&P 500 data unavailable — signal = 0")
    else:
        sp_vs_ma = (sp_px / sp_ma) - 1
        sp_str = f"{sp_vs_ma:+.1%}"
        if sp_vs_ma > 0.03:
            sp_signal = 1
        elif sp_vs_ma < -0.03:
            sp_signal = -1
        else:
            sp_signal = 0
        trend = "Bull (above MA)" if sp_vs_ma > 0 else "Bear (below MA)"
        log(f"  S&P={sp_px:,.0f}  200MA={sp_ma:,.0f}  Gap={sp_str}  ({trend})  → signal {sp_signal:+d}")

    log("\n[REGIME] Fetching credit conditions (HYG / IEF)...")
    hyg_ret = _ret_n(download("HYG", "3mo"), 20)
    ief_ret = _ret_n(download("IEF", "3mo"), 20)
    sleep(FETCH_DELAY)
    fred_baa10y = fred_value("BAA10Y") if fred_enabled else None
    if hyg_ret is None or ief_ret is None:
        if fred_baa10y is None:
            credit_signal, credit_str = 0, "N/A"
            log("  ⚠️  Credit data unavailable — signal = 0")
        else:
            credit_str = f"BAA10Y spread: {fred_baa10y:.2f}%"
            if fred_baa10y < 2.0:
                credit_signal = 1
            elif fred_baa10y > 3.5:
                credit_signal = -1
            else:
                credit_signal = 0
            cond = "Tight" if fred_baa10y < 2.0 else ("Stressed" if fred_baa10y > 3.5 else "Normal")
            log(f"  {credit_str}  ({cond}, FRED)  → signal {credit_signal:+d}")
    else:
        spread_chg = hyg_ret - ief_ret
        credit_str = f"HYG-IEF 20d: {spread_chg:+.2%}"
        if spread_chg > 0.01:
            credit_signal = 1
        elif spread_chg < -0.01:
            credit_signal = -1
        else:
            credit_signal = 0
        cond = "Tightening" if spread_chg > 0.01 else ("Widening" if spread_chg < -0.01 else "Stable")
        log(f"  {credit_str}  ({cond})  → signal {credit_signal:+d}")

    log("\n[REGIME] Computing market momentum (S&P 500 1M)...")
    sp_1m = _ret_n(sp_series, 21)
    if sp_1m is None:
        mom_signal, mom_str = 0, "N/A"
        log("  ⚠️  Momentum unavailable — signal = 0")
    else:
        mom_str = f"{sp_1m:+.2%}"
        if sp_1m > 0.03:
            mom_signal = 1
        elif sp_1m < -0.03:
            mom_signal = -1
        else:
            mom_signal = 0
        log(f"  S&P 1M return = {mom_str}  → signal {mom_signal:+d}")

    regime_score = vix_signal + yield_signal + sp_signal + credit_signal + mom_signal
    if regime_score >= 2:
        regime = "RISK_ON"
    elif regime_score <= -2:
        regime = "RISK_OFF"
    else:
        regime = "NEUTRAL"

    us_v, us_q, us_m = WEIGHTS[regime]["US"]
    kr_v, kr_q, kr_m = WEIGHTS[regime]["KR"]

    if verbose:
        regime_icon = {"RISK_ON": "🟢", "NEUTRAL": "🟡", "RISK_OFF": "🔴"}[regime]
        log(f"\n{'=' * 65}")
        log(f"  {regime_icon}  MACRO REGIME : {regime}  (score {regime_score:+d})")
        log(f"{'=' * 65}")
        log("\n  Signal breakdown:")
        log(f"    VIX          {vix_signal:+d}   ({vix_str})")
        log(f"    Yield curve  {yield_signal:+d}   ({yield_str})")
        log(f"    S&P vs 200MA {sp_signal:+d}   ({sp_str})")
        log(f"    Credit       {credit_signal:+d}   ({credit_str})")
        log(f"    Momentum     {mom_signal:+d}   ({mom_str})")
        log("    ──────────────────────────")
        log(f"    Composite    {regime_score:+d}   → {regime}")
        log("\n  Factor weights applied:")
        log(f"    US → Value:{us_v:.2f}  Quality:{us_q:.2f}  Momentum:{us_m:.2f}")
        log(f"    KR → Value:{kr_v:.2f}  Quality:{kr_q:.2f}  Momentum:{kr_m:.2f}")

    return {
        "Regime": regime,
        "Regime_Score": regime_score,
        "VIX": vix_str,
        "VIX_Signal": vix_signal,
        "Yield_Spread": yield_str,
        "Yield_Signal": yield_signal,
        "Yield_Source": yield_source if "yield_source" in locals() else "N/A",
        "SP500_vs_200MA": sp_str,
        "SP500_Signal": sp_signal,
        "Credit_Conditions": credit_str,
        "Credit_Signal": credit_signal,
        "Momentum_1M": mom_str,
        "Momentum_Signal": mom_signal,
        "US_V_Weight": us_v,
        "US_Q_Weight": us_q,
        "US_M_Weight": us_m,
        "KR_V_Weight": kr_v,
        "KR_Q_Weight": kr_q,
        "KR_M_Weight": kr_m,
        "Generated": datetime.now().strftime("%Y-%m-%d %H:%M"),
    }


def macro_rows(result: dict) -> list[list[str]]:
    return [
        ["Metric", "Value"],
        ["Regime", str(result["Regime"])],
        ["Regime_Score", str(result["Regime_Score"])],
        ["VIX", str(result["VIX"])],
        ["VIX_Signal", str(result["VIX_Signal"])],
        ["Yield_Spread", str(result["Yield_Spread"])],
        ["Yield_Signal", str(result["Yield_Signal"])],
        ["Yield_Source", str(result.get("Yield_Source", ""))],
        ["SP500_vs_200MA", str(result["SP500_vs_200MA"])],
        ["SP500_Signal", str(result["SP500_Signal"])],
        ["Credit_Conditions", str(result["Credit_Conditions"])],
        ["Credit_Signal", str(result["Credit_Signal"])],
        ["Momentum_1M", str(result["Momentum_1M"])],
        ["Momentum_Signal", str(result["Momentum_Signal"])],
        [""],
        ["US_V_Weight", str(result["US_V_Weight"])],
        ["US_Q_Weight", str(result["US_Q_Weight"])],
        ["US_M_Weight", str(result["US_M_Weight"])],
        ["KR_V_Weight", str(result["KR_V_Weight"])],
        ["KR_Q_Weight", str(result["KR_Q_Weight"])],
        ["KR_M_Weight", str(result["KR_M_Weight"])],
        [""],
        ["Generated", str(result["Generated"])],
    ]


def write_macro_regime(result: dict, spreadsheet=None) -> None:
    rows = macro_rows(result)
    print("\n[REGIME] Writing to Macro_Regime sheet...")
    try:
        spreadsheet = spreadsheet or get_spreadsheet()
        ws = spreadsheet.worksheet("Macro_Regime")
    except gspread.exceptions.WorksheetNotFound:
        ws = spreadsheet.add_worksheet(title="Macro_Regime", rows=50, cols=4)
        ws.clear()
        ws.update(range_name="A1", values=rows, value_input_option="USER_ENTERED")
    except Exception as exc:
        print(f"[REGIME] Sheet write skipped: {type(exc).__name__}: {exc}")
    else:
        ws.clear()
        ws.update(range_name="A1", values=rows, value_input_option="USER_ENTERED")
    dual_write_key_values("Macro_Regime", rows, market="GLOBAL")
    print(
        f"✅ [REGIME] Macro_Regime updated — {result['Regime']}  "
        f"(score {int(result['Regime_Score']):+d})  at {result['Generated']}"
    )


def run(write: bool = True) -> dict:
    result = compute_macro_regime()
    if write:
        write_macro_regime(result)
    return result


def main() -> None:
    run(write=True)


if __name__ == "__main__":
    main()
