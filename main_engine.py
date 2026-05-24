"""
main_engine.py  ─  Quant Pipeline Orchestrator  [Cache-Enabled]
================================================================
US Pipeline  (USD)
  1. pipeline/01_universe_expander.py     – S&P 500 + NASDAQ-100 fundamentals  → US_Universe (USD)
                                             KOSPI300 + KOSDAQ200 fundamentals  → KR_Universe (KRW)
  2. pipeline/02_macro_regime.py          – Macro regime detection              → Macro_Regime
  3. pipeline/03a_factor_scorer_us.py     – Value / Quality / Momentum scoring  → US_Scored_Stocks
  4. pipeline/04_ml_model.py              – RandomForest model; adds ML_Score + Combined_Score
  5. pipeline/05a_backtest_us.py          – 3-yr monthly-rebalancing backtest   → US_Backtest_Results
  6. pipeline/06a_portfolio_optimizer_us.py – Risk-parity optimisation          → US_Final_Portfolio

Korean Pipeline  (KRW, completely separate – no USD mix)
  Step 1 above also populates KR_Universe (Naver Finance fundamentals).
  3. pipeline/03b_factor_scorer_kr.py     – KR Value / Quality / Momentum       → KR_Scored_Stocks
  5. pipeline/05b_backtest_kr.py          – KR 3-yr backtest                    → KR_Backtest_Results
  6. pipeline/06b_portfolio_optimizer_kr.py – Risk-parity optimisation          → KR_Final_Portfolio

Cache Layer (NEW):
  - Company_Master   : fundamental snapshot per ticker, refreshed every 90 days
                       OR when earnings passed → auto-invalidates stale rows
  - Earnings_Calendar: next earnings dates; updated weekly at start of main()
  - Estimated API call reduction: 80%+ on subsequent pipeline runs

Run:  python main_engine.py
Test: python main_engine.py --test   (50 tickers only, ~5 min)
"""

import subprocess
import sys
import os
import time
import argparse
from datetime import datetime, timedelta
from pathlib import Path

# Force line-buffered stdout so progress prints appear immediately in pipes/files
sys.stdout.reconfigure(line_buffering=True)

# ── Cache integration (direct — main_engine connects to Sheets) ───────────────
from sheets_client import get_spreadsheet
from cache_manager import CacheManager
from quantbridge.config import get_settings
from quantbridge.orchestration import PipelineStep, run_steps as run_dag_steps
from quantbridge.ticker_policy import banned_tickers_label, filter_banned_tickers

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))


def _connect_sheets(max_retries: int = 5):
    """Authenticate and return (creds, client, spreadsheet). Retries on transient network errors."""
    last_err = None
    for attempt in range(1, max_retries + 1):
        try:
            _ss = get_spreadsheet()
            return None, None, _ss
        except Exception as e:
            last_err = e
            wait = 5 * attempt
            print(f"  ⚠️  Sheets 연결 실패 (시도 {attempt}/{max_retries}): {e.__class__.__name__} — {wait}s 후 재시도")
            time.sleep(wait)
    raise RuntimeError(f"Google Sheets 연결 {max_retries}회 모두 실패: {last_err}") from last_err

# ── Pipeline steps ─────────────────────────────────────────────────────────────
US_PIPELINE = [
    ("pipeline/01_universe_expander.py",
     "Fetch Universe  [US: S&P500+NASDAQ100 → US_Universe (USD)]"
     "  [KR: KOSPI300+KOSDAQ200 → KR_Universe (KRW)]"),
    ("pipeline/03a_factor_scorer_us.py",
     "US  – Factor Scoring & Filtering  → US_Scored_Stocks"),
    ("pipeline/04_ml_model.py",
     "US  – ML Factor Model (RandomForest)  → US_Scored_Stocks [ML_Score + Combined_Score added]"),
    ("pipeline/05a_backtest_us.py",
     "US  – Backtest Engine (3-Year Monthly Rebalancing)  → US_Backtest_Results"),
    ("pipeline/06a_portfolio_optimizer_us.py",
     "US  – Portfolio Optimisation (Risk Parity)  → US_Final_Portfolio"),
]

KR_PIPELINE = [
    ("pipeline/03b_factor_scorer_kr.py",
     "KR  – Factor Scoring (Naver data)  → KR_Scored_Stocks"),
    ("pipeline/05b_backtest_kr.py",
     "KR  – Backtest Engine (3-Year Monthly Rebalancing)  → KR_Backtest_Results"),
    ("pipeline/06b_portfolio_optimizer_kr.py",
     "KR  – Portfolio Optimisation (Risk Parity)  → KR_Final_Portfolio"),
]

# SmallCap pipeline — runs independently after US and KR pipelines.
# 07a = US only (Russell 2000 / S&P 600),  07b = KR only (KOSDAQ + KOSPI B30%)
SMALLCAP_PIPELINE = [
    ("pipeline/07a_smallcap_scanner_us.py",
     "SmallCap US  – Russell2000/S&P600 ($100M~$1B)  → US_SmallCap_Gems"),
    ("pipeline/07b_smallcap_scanner_kr.py",
     "SmallCap KR  – KOSDAQ+KOSPI-B30% (1000억~10조)  → KR_SmallCap_Gems"),
    ("pipeline/08_smallcap_backtest.py",
     "SmallCap  – Backtests  → US_SmallCap_Backtest / KR_SmallCap_Backtest"),
]

# Industry pipeline — runs after the US pipeline (reads US_Scored_Stocks).
# Caches ticker→industry mapping in Industry_Map (refreshed every 30 days).
# Failure here is non-blocking.
INDUSTRY_PIPELINE = [
    ("pipeline/09_industry_ranking.py",
     "Industry  – Bottom-up Power Ranking (20d)  → Industry_Map / US_Industry_Ranking"),
]

# KR Order Flow pipeline — reads KR_Scored_Stocks; Naver Finance scraping.
# Runs after the KR pipeline. Failure is non-blocking.
KR_FLOW_PIPELINE = [
    ("pipeline/11_order_flow_kr.py",
     "KR Flow   – Consecutive Dual Net Buying scanner  → KR_Dual_Net_Buyers"),
]

# Macro Regime Detection — must run BEFORE factor scorers (03a, 03b) so they can
# read regime-adjusted V/Q/M weights from the Macro_Regime sheet.
# Non-blocking: if it fails, scorers fall back to hardcoded default weights.
MACRO_PIPELINE = [
    ("pipeline/02_macro_regime.py",
     "Macro Regime  – VIX / Yield / Trend / Credit  → Macro_Regime"),
]

# Shadow Portfolio — records fresh recommendations and evaluates aged snapshots.
# Runs AFTER US + KR pipelines (needs fresh Final_Portfolio sheets). Non-blocking.
SHADOW_PIPELINE = [
    ("pipeline/19_shadow_portfolio.py",
     "Shadow Portfolio  – Snapshot/evaluation/attribution  → Shadow_Portfolio_*"),
]

# Portfolio Drift Monitor — reads US/KR Final Portfolio target weights,
# computes current weights from price changes, flags holdings that have drifted.
# Runs AFTER US + KR pipelines (needs fresh portfolio weights). Non-blocking.
DRIFT_PIPELINE = [
    ("pipeline/12_portfolio_drift_monitor.py",
     "Drift Monitor  – Weight drift since last rebalance  → Portfolio_Drift_Alert"),
]

# Factor Attribution — Barra-lite return decomposition (Value / Quality / Momentum / Residual).
# Runs AFTER drift monitor (both need fresh Final_Portfolio + Scored_Stocks). Non-blocking.
ATTRIBUTION_PIPELINE = [
    ("pipeline/13_factor_attribution.py",
     "Factor Attribution  – Barra-lite V/Q/M decomposition  → Factor_Attribution"),
]

# Factor IC Report — stores point-in-time score snapshots and evaluates aged
# snapshots against 1M/3M/6M forward returns. Non-blocking.
FACTOR_IC_PIPELINE = [
    ("pipeline/14_factor_ic_report.py",
     "Factor IC  – Walk-forward score validation  → Factor_IC_Report / Factor_Score_Snapshots"),
]

# Signal Quality Gates — converts IC diagnostics into operational PASS/WATCH/FAIL
# rows for API/dashboard use. Non-blocking.
SIGNAL_QUALITY_PIPELINE = [
    ("pipeline/15_signal_quality_gate.py",
     "Signal Quality  – PASS/WATCH/FAIL gates from factor IC  → Signal_Quality_Gates"),
]

# Factor Weight Policy — observation-only recommendations from signal quality.
# Does not mutate scorer weights. Non-blocking.
FACTOR_POLICY_PIPELINE = [
    ("pipeline/16_factor_weight_policy.py",
     "Factor Policy  – Observation-only factor weight recommendations  → Factor_Weight_Policy"),
]

# Factor Policy Backtest — validates observation-only policy recommendations
# against aged factor score snapshots. Does not mutate scorer weights.
FACTOR_POLICY_BACKTEST_PIPELINE = [
    ("pipeline/17_factor_policy_backtest.py",
     "Factor Policy Backtest  – Base vs policy-adjusted V/Q/M composite  → Factor_Policy_Backtest"),
]

# Factor Remediation Plan — turns quality/policy diagnostics into an
# operator-facing work queue. Does not mutate scorer weights.
FACTOR_REMEDIATION_PIPELINE = [
    ("pipeline/18_factor_remediation_plan.py",
     "Factor Remediation  – Prioritised weak-factor action plan  → Factor_Remediation_Plan"),
]

# Earnings Surprise pipeline — scans US_Universe and KR_Universe for PEAD candidates.
# Reads universe sheets (populated by step 01). Non-blocking.
EARNINGS_PIPELINE = [
    ("pipeline/10a_earnings_surprise_us.py",
     "Earnings US  – Post-Earnings Announcement Drift (PEAD)  → US_Earnings_Momentum"),
    ("pipeline/10b_earnings_surprise_kr.py",
     "Earnings KR  – PEAD Scanner (yfinance + Naver YoY fallback)  → KR_Earnings_Momentum"),
]

# Detail cache warmer — runs after final portfolio/smallcap outputs exist.
# Non-blocking. Keeps app stock-detail screens fast by preloading identity + OHLCV.
DETAIL_CACHE_PIPELINE = [
    ("tools/warm_detail_cache.py",
     "Detail Cache  – Warm portfolio/sector identity + stored OHLCV in PostgreSQL"),
    ("tools/sync_price_snapshots.py",
     "Price Snapshots  – Refresh latest app prices from stored OHLCV context"),
]

BAR  = "=" * 65
THIN = "─" * 65

# ── Test mode flag (set via --test argument) ───────────────────────────────────
# Usage: python main_engine.py --test
# Effect: passes TEST_MODE=true env var to all pipeline scripts
#         → each script processes only 50 tickers instead of full universe
#         → full run ~30min becomes ~2min
_parser = argparse.ArgumentParser(
    description="QuantBridge — Quant Pipeline Orchestrator",
    add_help=True,
)
_parser.add_argument('--test', action='store_true', help='Run in test mode (50 tickers only)')
_parser.add_argument('--analyze-only', action='store_true',
                     help='Skip all data fetching; re-run scoring on existing sheet data')
_parser.add_argument('--ussmallcap', action='store_true',
                     help='Run only the US SmallCap pipeline (07 + 08 US), skip main pipelines')
_parser.add_argument('--krsmallcap', action='store_true',
                     help='Run only the KR SmallCap pipeline (07 + 08 KR), skip main pipelines')
_parser.add_argument('--from-smallcap', action='store_true',
                     help='Resume at SmallCap: run 07a/07b/08, then downstream report stages')
_parser.add_argument('--smallcap-after-us', action='store_true',
                     help='Resume after US SmallCap: run 07b KR, 08 backtest, then downstream report stages')
_parser.add_argument('--smallcap-backtest-only', action='store_true',
                     help='Run only 08_smallcap_backtest.py against existing SmallCap gem sheets')
_parser.add_argument('--smallcap-backtest-to-end', action='store_true',
                     help='Run 08_smallcap_backtest.py, then downstream report stages 09-19')
_parser.add_argument('--shared-prep-only', action='store_true',
                     help='Run only shared prep: universe expansion, earnings refresh, and macro regime')
_parser.add_argument('--us-core-only', action='store_true',
                     help='Run only the US core pipeline after shared prep')
_parser.add_argument('--kr-core-only', action='store_true',
                     help='Run only the KR core pipeline after shared prep')
_parser.add_argument('--downstream-only', action='store_true',
                     help='Run only downstream report stages 09-19 after core and smallcap outputs exist')
_parser.add_argument('--us-only', action='store_true',
                     help='Run only the US main pipeline (03a→04→05a→06a), skip KR scoring/backtest')
_parser.add_argument('--kr-only', action='store_true',
                     help='Run only the KR main pipeline (03b→05b→06b), skip US scoring/backtest')
_parser.add_argument('--runner', choices=['legacy', 'prefect'], default=None,
                     help='Pipeline runner. legacy keeps the original subprocess flow; prefect uses the DAG runner.')
_parser.add_argument('--skip-detail-cache', action='store_true',
                     help='Skip app stock-detail cache warming at the end')
_args, _ = _parser.parse_known_args()
TEST_MODE        = _args.test
ANALYZE_ONLY     = _args.analyze_only
US_SMALLCAP_ONLY = _args.ussmallcap
KR_SMALLCAP_ONLY = _args.krsmallcap
FROM_SMALLCAP = _args.from_smallcap
SMALLCAP_AFTER_US = _args.smallcap_after_us
SMALLCAP_BACKTEST_ONLY = _args.smallcap_backtest_only
SMALLCAP_BACKTEST_TO_END = _args.smallcap_backtest_to_end
SHARED_PREP_ONLY = _args.shared_prep_only
US_CORE_ONLY = _args.us_core_only
KR_CORE_ONLY = _args.kr_core_only
DOWNSTREAM_ONLY = _args.downstream_only
RESUME_DOWNSTREAM_FROM_SMALLCAP = (
    FROM_SMALLCAP or SMALLCAP_AFTER_US or SMALLCAP_BACKTEST_TO_END or DOWNSTREAM_ONLY
)
SMALLCAP_ONLY    = (
    US_SMALLCAP_ONLY
    or KR_SMALLCAP_ONLY
    or FROM_SMALLCAP
    or SMALLCAP_AFTER_US
    or SMALLCAP_BACKTEST_ONLY
    or SMALLCAP_BACKTEST_TO_END
    or DOWNSTREAM_ONLY
)
US_MAIN_ONLY     = _args.us_only
KR_MAIN_ONLY     = _args.kr_only
RUNNER           = _args.runner or get_settings().pipeline_runner
SKIP_DETAIL_CACHE = _args.skip_detail_cache

# ── Earnings calendar update frequency ────────────────────────────────────────
# File path to store the timestamp of the last earnings update
_EARNINGS_STAMP = os.path.join(PROJECT_DIR, '.earnings_last_updated')
EARNINGS_UPDATE_DAYS = 7      # Refresh earnings calendar at most once per week


def _fmt_elapsed(seconds: float) -> str:
    """Return a human-readable elapsed time string."""
    if seconds < 60:
        return f"{seconds:.1f}s"
    m, s = divmod(int(seconds), 60)
    return f"{m}m {s:02d}s"


def run_step(script: str, description: str, step: int, total: int,
             label: str = "") -> bool:
    prefix = f"[{label}] " if label else ""
    print(f"\n{BAR}")
    print(f"  {prefix}STEP {step}/{total} : {description}")
    print(f"  Script  : {script}")
    print(f"  Started : {datetime.now().strftime('%H:%M:%S')}")
    if TEST_MODE:
        print(f"  ⚠️  TEST MODE  : 50 tickers only")
    print(BAR)

    # Pass mode flags to child scripts via environment variables
    env = os.environ.copy()
    env['PYTHONUNBUFFERED'] = '1'  # prevent stdout buffering in non-TTY mode
    if TEST_MODE:
        env['QUANT_TEST_MODE'] = 'true'
    if ANALYZE_ONLY:
        env['QUANT_ANALYZE_ONLY'] = 'true'
    # SmallCap market filter: set only when one flag is given (not both)
    if US_SMALLCAP_ONLY and not KR_SMALLCAP_ONLY:
        env['QUANT_SMALLCAP_MARKET'] = 'US'
    elif KR_SMALLCAP_ONLY and not US_SMALLCAP_ONLY:
        env['QUANT_SMALLCAP_MARKET'] = 'KR'

    t0 = time.time()
    try:
        result = subprocess.run(
            [sys.executable, script], cwd=PROJECT_DIR, env=env,
            stderr=subprocess.PIPE, text=True,
            timeout=21600,  # 6-hour hard limit per step
        )
    except subprocess.TimeoutExpired:
        elapsed = time.time() - t0
        print(f"\n❌  TIMEOUT  [{script}]  (exceeded 6hr — {_fmt_elapsed(elapsed)})")
        return False
    elapsed = time.time() - t0

    if result.returncode != 0:
        print(f"\n❌  FAILED  [{script}]  (exit {result.returncode})")
        if result.stderr:
            last_lines = result.stderr.strip().splitlines()[-15:]
            print("  ── stderr (last 15 lines) " + "─" * 38)
            for line in last_lines:
                print(f"  {line}")
            print("  " + "─" * 63)
        return False

    print(f"\n✅  Done in {_fmt_elapsed(elapsed)}")
    return True


def run_pipeline(steps, label: str) -> bool:
    """Run a list of (script, description) steps; return False on first failure."""
    total = len(steps)
    for i, (script, description) in enumerate(steps, 1):
        ok = run_step(script, description, i, total, label=label)
        if not ok:
            print(f"\n⚠️  {label} pipeline halted after step {i}/{total}.")
            print(f"   Fix the error in [{script}] and re-run.\n")
            return False
    return True


def run_optional_pipeline(steps, label: str, title: str, skip: bool = False,
                          skip_reason: str = "") -> bool:
    """Non-blocking pipeline wrapper — prints header, skips or runs, never aborts."""
    print(f"\n{'#' * 65}")
    print(f"  {title}")
    print(f"{'#' * 65}")
    if skip:
        print(f"  ⏭  Skipping {label} ({skip_reason})")
        return True
    ok = run_pipeline(steps, label=label)
    if not ok:
        print(f"  ⚠️  {label} failed — continuing (non-blocking)")
    return ok


# ══════════════════════════════════════════════════════════════════════════════
# Earnings Calendar Update  (weekly, non-blocking)
# ══════════════════════════════════════════════════════════════════════════════

def _earnings_update_needed() -> bool:
    """Return True if more than EARNINGS_UPDATE_DAYS have passed since last run."""
    if not os.path.exists(_EARNINGS_STAMP):
        return True
    try:
        with open(_EARNINGS_STAMP) as f:
            last = datetime.fromisoformat(f.read().strip())
        return (datetime.now() - last).days >= EARNINGS_UPDATE_DAYS
    except Exception:
        return True


def _mark_earnings_updated():
    """Write current timestamp to the stamp file."""
    with open(_EARNINGS_STAMP, 'w') as f:
        f.write(datetime.now().isoformat())


def run_earnings_update(cache: CacheManager, spreadsheet):
    """
    Weekly earnings calendar refresh.

    Reads the combined US + KR universe from US_Universe and KR_Universe,
    then calls cache.update_earnings_calendar() for all tickers.
    Tickers whose earnings have passed since the last fundamental fetch will
    be auto-refreshed on the next load_or_fetch_financials() call.

    Skipped if the calendar was already updated within the last 7 days.
    """
    if not _earnings_update_needed():
        print("\n📅 Earnings calendar is up-to-date (updated within last 7 days). Skipping.")
        return

    print(f"\n{BAR}")
    print("  EARNINGS CALENDAR UPDATE  (weekly refresh)")
    print(BAR)

    tickers = []

    # ── Read US tickers from US_Universe ──────────────────────────────────────
    try:
        sheet1 = spreadsheet.worksheet('US_Universe')
        rows   = sheet1.get_all_values()
        if len(rows) >= 2:
            hdr = rows[0]
            idx = hdr.index('Ticker') if 'Ticker' in hdr else 0
            tickers += [r[idx].strip() for r in rows[1:] if r and r[idx].strip()]
        print(f"  US tickers from US_Universe : {len(tickers)}")
    except Exception as e:
        print(f"  ⚠️  Could not read US_Universe: {e}")

    # ── Read KR tickers from KR_Universe ──────────────────────────────────────
    kr_tickers = []
    try:
        kr_ws  = spreadsheet.worksheet('KR_Universe')
        rows   = kr_ws.get_all_values()
        if len(rows) >= 2:
            hdr    = rows[0]
            idx    = hdr.index('Ticker') if 'Ticker' in hdr else 0
            kr_tickers = [r[idx].strip() for r in rows[1:] if r and r[idx].strip()]
            tickers += kr_tickers
        print(f"  KR tickers from KR_Universe : {len(kr_tickers)}")
    except Exception as e:
        print(f"  ⚠️  Could not read KR_Universe: {e}")

    # ── Also add any tickers already in the Company_Master cache ──────────────
    # In TEST_MODE, only update the universe tickers (50 US + 48 KR) so the
    # weekly refresh doesn't iterate over all 6000+ cached tickers.
    if not TEST_MODE:
        tickers = list(dict.fromkeys(tickers + cache.get_all_tickers()))  # deduplicate
    else:
        tickers = list(dict.fromkeys(tickers))
    before_policy = len(tickers)
    tickers = filter_banned_tickers(tickers)
    if len(tickers) != before_policy:
        print(f"  Banned tickers excluded from earnings update : {banned_tickers_label()}")
    print(f"  Total unique tickers for earnings update : {len(tickers)}")

    if not tickers:
        print("  ⚠️  No tickers found. Run pipeline/01_universe_expander.py first.")
        return

    cache.update_earnings_calendar(tickers, delay=0.2)
    _mark_earnings_updated()
    print(f"  ✅ Earnings calendar updated. Stamp: {_EARNINGS_STAMP}")


# ══════════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════════

def main():
    if RUNNER == 'prefect':
        dag_step_defs = (
            US_PIPELINE[:1]
            + MACRO_PIPELINE
            + ([] if KR_MAIN_ONLY else US_PIPELINE[1:])
            + ([] if US_MAIN_ONLY else KR_PIPELINE)
            + SHADOW_PIPELINE
            + DRIFT_PIPELINE
            + ATTRIBUTION_PIPELINE
            + FACTOR_IC_PIPELINE
            + SIGNAL_QUALITY_PIPELINE
            + FACTOR_POLICY_PIPELINE
            + FACTOR_POLICY_BACKTEST_PIPELINE
            + FACTOR_REMEDIATION_PIPELINE
            + EARNINGS_PIPELINE
            + INDUSTRY_PIPELINE
            + KR_FLOW_PIPELINE
            + SMALLCAP_PIPELINE
            + ([] if SKIP_DETAIL_CACHE else DETAIL_CACHE_PIPELINE)
        )
        steps = [
            PipelineStep(
                script=s,
                description=d,
                label='DAG',
                optional=(s in {"pipeline/19_shadow_portfolio.py", "tools/warm_detail_cache.py", "tools/sync_price_snapshots.py"}),
            )
            for s, d in dag_step_defs
        ]
        print("\n🚦 Running QuantBridge with DAG runner (Prefect when available)")
        run_dag_steps(
            steps,
            cwd=Path(PROJECT_DIR),
            test_mode=TEST_MODE,
            analyze_only=ANALYZE_ONLY,
            smallcap_market='US' if US_SMALLCAP_ONLY and not KR_SMALLCAP_ONLY else (
                'KR' if KR_SMALLCAP_ONLY and not US_SMALLCAP_ONLY else None
            ),
            use_prefect=True,
        )
        return

    t_start = time.time()

    print(f"\n{'#' * 65}")
    print(f"  QUANT PIPELINE  ─  {datetime.now().strftime('%Y-%m-%d  %H:%M:%S')}")
    if TEST_MODE:
        print(f"  ⚠️  TEST MODE ENABLED  (50 tickers per pipeline)")
        print(f"  ⚠️  Results will NOT reflect full universe")
    if FROM_SMALLCAP:
        print(f"  🔬 RESUME FROM SMALLCAP MODE  (07a/07b/08, then downstream reports)")
    elif SMALLCAP_AFTER_US:
        print(f"  🔬 SMALLCAP RESUME-AFTER-US MODE  (KR scan, then US/KR backtest)")
    elif SMALLCAP_BACKTEST_ONLY:
        print(f"  🔬 SMALLCAP BACKTEST-ONLY MODE  (uses existing US/KR gem sheets)")
    elif SMALLCAP_BACKTEST_TO_END:
        print(f"  🔬 SMALLCAP BACKTEST-TO-END MODE  (08 backtest, then 09→19)")
    elif SHARED_PREP_ONLY:
        print(f"  🧩 SHARED PREP-ONLY MODE  (01 universe, earnings refresh, 02 macro)")
    elif US_CORE_ONLY:
        print(f"  🇺🇸 US CORE-ONLY MODE  (03a→04→05a→06a)")
    elif KR_CORE_ONLY:
        print(f"  🇰🇷 KR CORE-ONLY MODE  (03b→05b→06b)")
    elif DOWNSTREAM_ONLY:
        print(f"  📊 DOWNSTREAM-ONLY MODE  (09→19 reports)")
    elif US_SMALLCAP_ONLY and KR_SMALLCAP_ONLY:
        print(f"  🔬 SMALLCAP-ONLY MODE  (US + KR, skipping main pipelines)")
    elif US_SMALLCAP_ONLY:
        print(f"  🔬 US SMALLCAP-ONLY MODE  (skipping KR smallcap + main pipelines)")
    elif KR_SMALLCAP_ONLY:
        print(f"  🔬 KR SMALLCAP-ONLY MODE  (skipping US smallcap + main pipelines)")
    if ANALYZE_ONLY:
        print(f"  ⚡ ANALYZE-ONLY MODE  (no universe fetch, no cache updates)")
        print(f"  ⚡ Scoring runs on existing US_Universe / KR_Universe data")
        print(f"  ⚡ Steps skipped: 01 universe expansion, 05 backtest, 07 smallcap")
    if US_MAIN_ONLY:
        print(f"  🇺🇸 US-ONLY MODE  (US main pipeline only — KR scoring/backtest skipped)")
    if KR_MAIN_ONLY:
        print(f"  🇰🇷 KR-ONLY MODE  (KR main pipeline only — US scoring/backtest skipped)")
    print(f"{'#' * 65}")

    # ── 0. Google Sheets connection + CacheManager ────────────────────────────
    print(f"\n{BAR}")
    print("  STEP 0 : Google Sheets Connection + CacheManager Initialisation")
    print(BAR)
    creds, client, spreadsheet = _connect_sheets()
    cache = CacheManager(spreadsheet, verbose=False)  # silent bulk load
    cache.print_stats()

    if SHARED_PREP_ONLY:
        if not ANALYZE_ONLY:
            print(f"\n{'=' * 65}")
            print("  STEP 1 / SHARED  :  Universe Expansion (US + Korean)")
            print(f"{'=' * 65}")
            shared_ok = run_step(
                US_PIPELINE[0][0], US_PIPELINE[0][1],
                step=1, total=1, label="SHARED",
            )
            if not shared_ok:
                print("\n⚠️  Universe expansion failed. Both core pipelines require this step.")
                sys.exit(1)
            try:
                creds, client, spreadsheet = _connect_sheets()
            except Exception as e:
                print(f"  ⚠️  Auth refresh failed: {e} — using existing session")
            run_earnings_update(cache, spreadsheet)
        else:
            print(f"\n  ⚡ ANALYZE-ONLY: skipping universe expansion and earnings refresh")

        print(f"\n{'#' * 65}")
        print("  MACRO REGIME DETECTION  (VIX / Yield / Trend / Credit)")
        print(f"{'#' * 65}")
        macro_ok = run_pipeline(MACRO_PIPELINE, label="MACRO")
        if not macro_ok:
            print("  ⚠️  Macro regime detection failed — scorers will use default V/Q/M weights")
        cache.print_stats()
        return

    if US_CORE_ONLY:
        print(f"\n{'#' * 65}")
        print("  US CORE PIPELINE  (USD)")
        print(f"{'#' * 65}")
        _us_steps = [
            s for s in US_PIPELINE[1:]
            if not (ANALYZE_ONLY and '05_backtest' in s[0])
        ]
        us_ok = run_pipeline(_us_steps, label="US")
        cache.print_stats()
        if not us_ok:
            sys.exit(1)
        return

    if KR_CORE_ONLY:
        print(f"\n{'#' * 65}")
        print("  KR CORE PIPELINE  (KRW)")
        print(f"{'#' * 65}")
        _kr_steps = [
            s for s in KR_PIPELINE
            if not (ANALYZE_ONLY and '05b_backtest' in s[0])
        ]
        kr_ok = run_pipeline(_kr_steps, label="KR")
        cache.print_stats()
        if not kr_ok:
            sys.exit(1)
        return

    if SMALLCAP_ONLY:
        # ── SmallCap-only mode: skip universe expansion + US/KR pipelines ─────
        us_ok, kr_ok, macro_ok = True, True, True
    else:
        # ── Step 1: Universe expansion (shared – populates both US & KR sheets) ──
        if not ANALYZE_ONLY:
            print(f"\n{'=' * 65}")
            print("  STEP 1 / SHARED  :  Universe Expansion (US + Korean)")
            print(f"{'=' * 65}")
            shared_ok = run_step(
                US_PIPELINE[0][0], US_PIPELINE[0][1],
                step=1, total=1, label="SHARED",
            )
            if not shared_ok:
                print("\n⚠️  Universe expansion failed.  Both pipelines require this step.")
                sys.exit(1)
        else:
            print(f"\n  ⚡ ANALYZE-ONLY: skipping universe expansion "
                  f"(US_Universe / KR_Universe used as-is)")

        # ── 1b. Weekly earnings calendar update (after universe is populated) ──
        # Runs AFTER step 01 so US_Universe / KR_Universe always have tickers.
        # Re-authenticate first — pipeline can run >1hr and service account tokens
        # expire after 60 min; gspread does not auto-refresh after initial auth.
        if not ANALYZE_ONLY:
            try:
                creds, client, spreadsheet = _connect_sheets()
            except Exception as e:
                print(f"  ⚠️  Auth refresh failed: {e} — using existing session")
            run_earnings_update(cache, spreadsheet)
        else:
            print("\n  ⚡ ANALYZE-ONLY: skipping earnings calendar update")

        # ── Macro Regime Detection ────────────────────────────────────────────
        # Runs BEFORE scoring so 02/03 factor scorers read the fresh regime weights.
        print(f"\n{'#' * 65}")
        print("  MACRO REGIME DETECTION  (VIX / Yield / Trend / Credit)")
        print(f"{'#' * 65}")
        macro_ok = run_pipeline(MACRO_PIPELINE, label="MACRO")
        if not macro_ok:
            print("  ⚠️  Macro regime detection failed — scorers will use default V/Q/M weights")

        # ── US Pipeline (steps 2-5) ───────────────────────────────────────────
        print(f"\n{'#' * 65}")
        print("  US PIPELINE  (USD)")
        print(f"{'#' * 65}")
        if KR_MAIN_ONLY:
            print("  ⏭  Skipping US pipeline (--kr-only)")
            us_ok = True
        elif ANALYZE_ONLY:
            _us_steps = [s for s in US_PIPELINE[1:] if '05_backtest' not in s[0]]
            us_ok = run_pipeline(_us_steps, label="US")
        else:
            us_ok = run_pipeline(US_PIPELINE[1:], label="US")

        # ── Korean Pipeline ───────────────────────────────────────────────────
        print(f"\n{'#' * 65}")
        print("  KOREAN PIPELINE  (KRW  –  no USD mix)")
        print(f"{'#' * 65}")
        if US_MAIN_ONLY:
            print("  ⏭  Skipping KR pipeline (--us-only)")
            kr_ok = True
        elif ANALYZE_ONLY:
            _kr_steps = [s for s in KR_PIPELINE if '05b_backtest' not in s[0]]
            kr_ok = run_pipeline(_kr_steps, label="KR")
        else:
            kr_ok = run_pipeline(KR_PIPELINE, label="KR")

    # ── Shadow Portfolio ─────────────────────────────────────────────────────
    # Records current portfolios and evaluates aged snapshots. Non-blocking.
    shadow_ok = run_optional_pipeline(
        SHADOW_PIPELINE, label="SHADOW",
        title="SHADOW PORTFOLIO  (snapshots, evaluation, attribution)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── Portfolio Drift Monitor ───────────────────────────────────────────────
    # Runs after US + KR portfolio optimisers (needs fresh Weight(%) data).
    drift_ok = run_optional_pipeline(
        DRIFT_PIPELINE, label="DRIFT",
        title="PORTFOLIO DRIFT MONITOR  (weight drift since last rebalance)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── Factor Attribution ────────────────────────────────────────────────────
    # Needs fresh Final_Portfolio + Scored_Stocks. Non-blocking.
    attr_ok = run_optional_pipeline(
        ATTRIBUTION_PIPELINE, label="ATTRIB",
        title="FACTOR ATTRIBUTION  (Barra-lite V/Q/M decomposition)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── Factor IC Report ──────────────────────────────────────────────────────
    # Stores score snapshots and evaluates old snapshots against forward returns.
    # First run mostly builds history; future runs produce richer IC diagnostics.
    ic_ok = run_optional_pipeline(
        FACTOR_IC_PIPELINE, label="FACTOR-IC",
        title="FACTOR IC REPORT  (walk-forward score validation)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    quality_ok = run_optional_pipeline(
        SIGNAL_QUALITY_PIPELINE, label="QUALITY",
        title="SIGNAL QUALITY GATES  (IC-driven PASS/WATCH/FAIL)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    policy_ok = run_optional_pipeline(
        FACTOR_POLICY_PIPELINE, label="POLICY",
        title="FACTOR WEIGHT POLICY  (observation-only recommendations)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    policy_bt_ok = run_optional_pipeline(
        FACTOR_POLICY_BACKTEST_PIPELINE, label="POLICY-BT",
        title="FACTOR POLICY BACKTEST  (base vs policy-adjusted composite)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    remediation_ok = run_optional_pipeline(
        FACTOR_REMEDIATION_PIPELINE, label="REMEDIATE",
        title="FACTOR REMEDIATION PLAN  (prioritised weak-factor actions)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── Earnings Surprise Pipeline (PEAD) ────────────────────────────────────
    # Scans US_Universe for recent positive EPS surprises + post-announcement drift.
    earn_ok = run_optional_pipeline(
        EARNINGS_PIPELINE, label="EARNINGS",
        title="EARNINGS SURPRISE PIPELINE  (PEAD — Post-Announcement Drift)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY, skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── Industry Pipeline ─────────────────────────────────────────────────────
    # Reads US_Scored_Stocks (written by step 02/04).
    # Industry_Map cache is refreshed only when entries are > 30 days old.
    # Skipped in ANALYZE_ONLY: calls yfinance for industry labels (external API).
    ind_ok = run_optional_pipeline(
        INDUSTRY_PIPELINE, label="INDUSTRY",
        title="INDUSTRY PIPELINE  (US — Bottom-up Power Ranking)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY,
        skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── KR Order Flow Pipeline ────────────────────────────────────────────────
    # Reads KR_Scored_Stocks; Naver Finance scraping. Non-blocking.
    # Skipped in ANALYZE_ONLY: calls Naver Finance (external scraping).
    flow_ok = run_optional_pipeline(
        KR_FLOW_PIPELINE, label="KR-FLOW",
        title="KR ORDER FLOW  (Naver Finance — Dual Net Buying Scanner)",
        skip=SMALLCAP_ONLY or ANALYZE_ONLY,
        skip_reason="SMALLCAP/ANALYZE-ONLY mode",
    )

    # ── SmallCap Pipeline ─────────────────────────────────────────────────────
    # 07a (US) and 07b (KR) run as separate scripts.
    # --ussmallcap → runs only 07a + backtest
    # --krsmallcap → runs only 07b + backtest
    # (default)    → runs both 07a + 07b + backtest
    print(f"\n{'#' * 65}")
    if FROM_SMALLCAP:
        print("  SMALLCAP PIPELINE  (resume from 07 — 07a US + 07b KR + 08 backtest)")
    elif SMALLCAP_AFTER_US:
        print("  SMALLCAP PIPELINE  (resume after US — 07b KR + 08 US/KR backtest)")
    elif SMALLCAP_BACKTEST_ONLY:
        print("  SMALLCAP PIPELINE  (backtest only — existing US/KR gems)")
    elif SMALLCAP_BACKTEST_TO_END:
        print("  SMALLCAP PIPELINE  (backtest + downstream — existing US/KR gems)")
    elif DOWNSTREAM_ONLY:
        print("  SMALLCAP PIPELINE  (skipped — downstream-only mode)")
    elif US_SMALLCAP_ONLY and not KR_SMALLCAP_ONLY:
        print("  SMALLCAP PIPELINE  (US only — 07a)")
    elif KR_SMALLCAP_ONLY and not US_SMALLCAP_ONLY:
        print("  SMALLCAP PIPELINE  (KR only — 07b)")
    else:
        print("  SMALLCAP PIPELINE  (07a US  +  07b KR)")
    print(f"{'#' * 65}")

    if DOWNSTREAM_ONLY:
        sc_ok = True
        print("  ⏭  Skipping SmallCap pipeline (--downstream-only)")
    elif ANALYZE_ONLY and not SMALLCAP_ONLY:
        sc_ok = True
        print("  ⚡ ANALYZE-ONLY: skipping SmallCap pipeline (requires universe fetch)")
    else:
        # Select which sub-scripts to run based on flags
        if FROM_SMALLCAP:
            sc_steps = SMALLCAP_PIPELINE  # 07a + 07b + 08
        elif SMALLCAP_AFTER_US:
            sc_steps = [SMALLCAP_PIPELINE[1], SMALLCAP_PIPELINE[2]]  # 07b + 08 both markets
        elif SMALLCAP_BACKTEST_ONLY:
            sc_steps = [SMALLCAP_PIPELINE[2]]  # 08 only
        elif SMALLCAP_BACKTEST_TO_END:
            sc_steps = [SMALLCAP_PIPELINE[2]]  # 08, then downstream resume block
        elif US_SMALLCAP_ONLY and not KR_SMALLCAP_ONLY:
            sc_steps = [SMALLCAP_PIPELINE[0], SMALLCAP_PIPELINE[2]]  # 07a + backtest
        elif KR_SMALLCAP_ONLY and not US_SMALLCAP_ONLY:
            sc_steps = [SMALLCAP_PIPELINE[1], SMALLCAP_PIPELINE[2]]  # 07b + backtest
        else:
            sc_steps = SMALLCAP_PIPELINE  # 07a + 07b + backtest
        sc_ok = run_pipeline(sc_steps, label="SMALLCAP")

    if RESUME_DOWNSTREAM_FROM_SMALLCAP:
        print(f"\n{'#' * 65}")
        print("  DOWNSTREAM RESUME STAGES  (09 → 19)")
        print(f"{'#' * 65}")
        ind_ok = run_optional_pipeline(
            INDUSTRY_PIPELINE, label="INDUSTRY",
            title="09 INDUSTRY PIPELINE  (US — Bottom-up Power Ranking)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        earn_ok = run_optional_pipeline(
            EARNINGS_PIPELINE, label="EARNINGS",
            title="10 EARNINGS SURPRISE PIPELINE  (PEAD — Post-Announcement Drift)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        flow_ok = run_optional_pipeline(
            KR_FLOW_PIPELINE, label="KR-FLOW",
            title="11 KR ORDER FLOW  (Naver Finance — Dual Net Buying Scanner)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        drift_ok = run_optional_pipeline(
            DRIFT_PIPELINE, label="DRIFT",
            title="12 PORTFOLIO DRIFT MONITOR  (weight drift since last rebalance)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        attr_ok = run_optional_pipeline(
            ATTRIBUTION_PIPELINE, label="ATTRIB",
            title="13 FACTOR ATTRIBUTION  (Barra-lite V/Q/M decomposition)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        ic_ok = run_optional_pipeline(
            FACTOR_IC_PIPELINE, label="FACTOR-IC",
            title="14 FACTOR IC REPORT  (walk-forward score validation)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        quality_ok = run_optional_pipeline(
            SIGNAL_QUALITY_PIPELINE, label="QUALITY",
            title="15 SIGNAL QUALITY GATES  (IC-driven PASS/WATCH/FAIL)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        policy_ok = run_optional_pipeline(
            FACTOR_POLICY_PIPELINE, label="POLICY",
            title="16 FACTOR WEIGHT POLICY  (observation-only recommendations)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        policy_bt_ok = run_optional_pipeline(
            FACTOR_POLICY_BACKTEST_PIPELINE, label="POLICY-BT",
            title="17 FACTOR POLICY BACKTEST  (base vs policy-adjusted composite)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        remediation_ok = run_optional_pipeline(
            FACTOR_REMEDIATION_PIPELINE, label="REMEDIATE",
            title="18 FACTOR REMEDIATION PLAN  (prioritised weak-factor actions)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )
        shadow_ok = run_optional_pipeline(
            SHADOW_PIPELINE, label="SHADOW",
            title="19 SHADOW PORTFOLIO  (snapshots, evaluation, attribution)",
            skip=ANALYZE_ONLY,
            skip_reason="ANALYZE-ONLY mode",
        )

    detail_cache_ok = run_optional_pipeline(
        DETAIL_CACHE_PIPELINE,
        label="CACHE",
        title="DETAIL CACHE WARMER  (app stock-detail identity + OHLCV)",
        skip=SKIP_DETAIL_CACHE,
        skip_reason="--skip-detail-cache",
    )

    # ── Final summary ─────────────────────────────────────────────────────────
    print(f"\n{'#' * 65}")
    us_status    = "✅  COMPLETE" if us_ok    else "❌  FAILED"
    kr_status    = "✅  COMPLETE" if kr_ok    else "❌  FAILED"
    sc_status    = "✅  COMPLETE" if sc_ok    else "❌  FAILED"
    macro_status = "✅  COMPLETE" if macro_ok else "⚠️   FAILED (scorers used defaults)"
    shadow_status = "✅  COMPLETE" if shadow_ok else "⚠️   FAILED (non-blocking)"
    drift_status = "✅  COMPLETE" if drift_ok else "⚠️   FAILED (non-blocking)"
    attr_status  = "✅  COMPLETE" if attr_ok  else "⚠️   FAILED (non-blocking)"
    ic_status    = "✅  COMPLETE" if ic_ok    else "⚠️   FAILED (non-blocking)"
    quality_status = "✅  COMPLETE" if quality_ok else "⚠️   FAILED (non-blocking)"
    policy_status = "✅  COMPLETE" if policy_ok else "⚠️   FAILED (non-blocking)"
    policy_bt_status = "✅  COMPLETE" if policy_bt_ok else "⚠️   FAILED (non-blocking)"
    ind_status   = "✅  COMPLETE" if ind_ok   else "⚠️   FAILED (non-blocking)"
    flow_status  = "✅  COMPLETE" if flow_ok  else "⚠️   FAILED (non-blocking)"
    earn_status  = "✅  COMPLETE" if earn_ok  else "⚠️   FAILED (non-blocking)"
    cache_status = "✅  COMPLETE" if detail_cache_ok else "⚠️   FAILED (non-blocking)"
    if RESUME_DOWNSTREAM_FROM_SMALLCAP:
        print(f"  Industry Pipeline : {ind_status}")
        print(f"  Earnings (PEAD)   : {earn_status}")
        print(f"  KR Order Flow     : {flow_status}")
        print(f"  Drift Monitor     : {drift_status}")
        print(f"  Factor Attrib.    : {attr_status}")
        print(f"  Factor IC Report  : {ic_status}")
        print(f"  Signal Quality    : {quality_status}")
        print(f"  Factor Policy     : {policy_status}")
        print(f"  Policy Backtest   : {policy_bt_status}")
        print(f"  Remediation       : {'✅  COMPLETE' if remediation_ok else '⚠️   FAILED (non-blocking)'}")
        print(f"  Shadow Portfolio : {shadow_status}")
    elif not SMALLCAP_ONLY:
        print(f"  Macro Regime      : {macro_status}")
        us_label = "US Pipeline (skipped)" if KR_MAIN_ONLY else "US Pipeline      "
        kr_label = "KR Pipeline (skipped)" if US_MAIN_ONLY else "KR Pipeline      "
        print(f"  {us_label} : {us_status}")
        print(f"  {kr_label} : {kr_status}")
        print(f"  Shadow Portfolio : {shadow_status}")
        print(f"  Drift Monitor     : {drift_status}")
        print(f"  Factor Attrib.    : {attr_status}")
        print(f"  Factor IC Report  : {ic_status}")
        print(f"  Signal Quality    : {quality_status}")
        print(f"  Factor Policy     : {policy_status}")
        print(f"  Policy Backtest   : {policy_bt_status}")
        print(f"  Earnings (PEAD)   : {earn_status}")
        print(f"  Industry Pipeline : {ind_status}")
        print(f"  KR Order Flow     : {flow_status}")
    if US_SMALLCAP_ONLY and not KR_SMALLCAP_ONLY:
        print(f"  US SmallCap       : {sc_status}")
    elif KR_SMALLCAP_ONLY and not US_SMALLCAP_ONLY:
        print(f"  KR SmallCap       : {sc_status}")
    else:
        print(f"  SmallCap Pipeline : {sc_status}")
    print(f"  Detail Cache      : {cache_status}")
    print(f"{'#' * 65}")

    # Cache stats + total elapsed time
    cache.print_stats()
    elapsed_total = time.time() - t_start
    print(f"\n  ⏱  Total elapsed: {elapsed_total/60:.1f} min ({elapsed_total:.0f}s)")

    print()
    print("  📊  Google Sheets  →  QuantBridge_Demo_Workbook")
    print(f"  {THIN}")
    print("  Sheet                 Contents")
    print(f"  {THIN}")
    print("  [Cache Layer – NEW]")
    print("  Company_Master        Fundamental cache (all tickers, refreshed ≥90d)")
    print("  Earnings_Calendar     Next earnings dates (refreshed weekly)")
    print(f"  {THIN}")
    print("  [Macro Regime — VIX / Yield / Trend / Credit signals]")
    print("  Macro_Regime          Current regime (RISK_ON/NEUTRAL/RISK_OFF) + V/Q/M weights")
    print(f"  {THIN}")
    print("  [US Pipeline – USD]")
    print("  US_Universe                Raw fundamentals  (S&P500 + NASDAQ-100)")
    print("  US_Scored_Stocks         US stocks: V/Q/M scores + ML_Score + Combined_Score")
    print("  US_Backtest_Results      US backtest performance metrics")
    print("  US_Final_Portfolio       US risk-parity weights  ← START HERE (US)")
    print(f"  {THIN}")
    print("  [Korean Pipeline – KRW, no USD mix]")
    print("  KR_Universe       Raw fundamentals  (KOSPI300 + KOSDAQ200)")
    print("  KR_Scored_Stocks      KR stocks after filter + V/Q/M scores")
    print("  KR_Backtest_Results   KR backtest performance metrics")
    print("  KR_Final_Portfolio    Top 30 KR stocks, risk-parity weighted  ← START HERE (KR)")
    print(f"  {THIN}")
    print("  [Shadow Portfolio — records recommendations and explains realized performance]")
    print("  Shadow_Portfolio_Snapshots            Point-in-time recommended holdings")
    print("  Shadow_Portfolio_Evaluation           1M/3M/6M alpha and hit-rate evaluation")
    print("  Shadow_Portfolio_Attribution          Stock-level contribution analysis")
    print("  Shadow_Portfolio_Sector_Attribution   Sector-level contribution analysis")
    print("  Shadow_Portfolio_Attribution_Summary  Top/worst contributors and score IC")
    print(f"  {THIN}")
    print("  [Portfolio Drift Monitor — reads US/KR Final Portfolio, computes weight drift]")
    print("  Portfolio_Drift_Alert  Per-stock drift table + rebalance recommendations")
    print(f"  {THIN}")
    print("  [Factor Attribution — Barra-lite V/Q/M return decomposition]")
    print("  Factor_Attribution     Value/Quality/Momentum/Residual contributions + per-stock detail")
    print("  Factor_Score_Snapshots Point-in-time factor score history for IC testing")
    print("  Factor_IC_Report       1M/3M/6M Spearman IC + top/bottom spread diagnostics")
    print("  Signal_Quality_Gates   PASS/WATCH/FAIL gates for factor reliability")
    print("  Factor_Weight_Policy   Observation-only factor weight recommendations")
    print("  Factor_Policy_Backtest Base vs policy-adjusted V/Q/M composite diagnostics")
    print(f"  {THIN}")
    print("  [Earnings Surprise Pipeline — PEAD scanner, reads US_Universe + KR_Universe]")
    print("  US_Earnings_Momentum  Top 30 US stocks: recent EPS surprise + drift signal")
    print("  KR_Earnings_Momentum  Top 30 KR stocks: EPS surprise/YoY proxy + drift signal")
    print(f"  {THIN}")
    print("  [Industry Pipeline – reads US_Scored_Stocks, cache refreshed every 30d]")
    print("  Industry_Map          Ticker→industry mapping cache (yfinance granular industry)")
    print("  US_Industry_Ranking   Bottom-up power ranking by Mean Return + Breadth")
    print(f"  {THIN}")
    print("  [KR Order Flow — Naver Finance investor data]")
    print("  KR_Dual_Net_Buyers    KR stocks with consecutive foreign+inst net buying")
    print(f"  {THIN}")
    print("  [SmallCap Pipeline – independent, cached in Company_Master]")
    print("  US_SmallCap_Gems      Top 20 US small-cap gems ($100M~$1B)   ← START HERE (US SmallCap)")
    print("  US_SmallCap_Backtest  US small-cap backtest results")
    print("  KR_SmallCap_Gems      Top 20 KR small-cap gems (1000억~10조) ← START HERE (KR SmallCap)")
    print("  KR_SmallCap_Backtest  KR small-cap backtest results")
    print(f"  {THIN}")
    print()

    # [CHANGE] SmallCap failure is non-blocking — US/KR pipelines are primary
    if not (us_ok and kr_ok):
        sys.exit(1)


if __name__ == "__main__":
    main()
