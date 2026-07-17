from __future__ import annotations

import streamlit as st
import pandas as pd
import plotly.graph_objects as go
from datetime import datetime
from pathlib import Path
import math
import html
import json

from data_loader import (
    load_portfolio_sheet, format_portfolio_df, format_smallcap_df,
    render_portfolio_table, render_smallcap_table,
    NASDAQ_TOP20, fetch_marquee_prices,
    load_backtest_sheet, fetch_nasdaq_benchmark, fetch_kospi_benchmark,
    load_search_universe,
    load_scored_stocks,
    load_attribution_sheet,
    load_correlation_data,
    load_industry_ranking,
    load_kr_order_flow,
    load_drift_alert,
    load_earnings_momentum,
    load_macro_regime,
    load_factor_ic_report,
    load_signal_quality_gates,
    load_factor_weight_policy,
    load_factor_policy_backtest,
    load_factor_remediation_plan,
    load_policy_adjusted_rankings,
    load_pipeline_runs,
    load_ops_health,
    load_data_quality,
    enrich_kr_company_identities,
)
from ui_components import (
    PLOTLY_LAYOUT,
    section_header, render_meta_cards,
    render_quick_metrics, render_smallcap_quick_metrics,
    render_charts, render_smallcap_charts,
    render_backtest_kpi, render_backtest_charts,
)
from ui_dialogs import show_stock_dialog, show_strategy_dialog
from ui_charts import (
    render_attribution_waterfall, render_attribution_kpi, render_attribution_detail,
    render_diversification_score, render_correlation_heatmap,
    render_industry_ranking, render_industry_table,
    render_order_flow_bar, render_order_flow_table,
    render_drift_kpi, render_drift_chart, render_drift_table,
    render_earnings_bar_chart,
    render_factor_decomposition,
)

# ── 1. Page Config ─────────────────────────────────────────────────────────────
st.set_page_config(
    page_title="Jino's Quant Dashboard",
    page_icon="📊",
    layout="wide",
    initial_sidebar_state="expanded",
)

# ── 2. Global CSS ──────────────────────────────────────────────────────────────
_css_path = Path(__file__).parent / "style.css"
with open(_css_path) as _f:
    _css = _f.read()
st.markdown(f"<style>{_css}</style>", unsafe_allow_html=True)


# ── 3. Sidebar ─────────────────────────────────────────────────────────────────
with st.sidebar:
    st.markdown("""
    <div style="padding: 8px 0 20px 0;">
        <div style="font-size:1.3rem;font-weight:800;color:#e2e8f0;letter-spacing:-0.5px;">
            📊 Jino Quant
        </div>
        <div style="font-size:0.75rem;color:#4b5563;margin-top:3px;">
            Automated Investment Pipeline
        </div>
    </div>
    """, unsafe_allow_html=True)

    now = datetime.now()
    st.markdown(f"""
    <div class="sidebar-card">
        <div class="sc-label">Last Refreshed</div>
        <div class="sc-value">{now.strftime('%H:%M:%S')}</div>
        <div class="sc-sub">{now.strftime('%Y-%m-%d')}</div>
    </div>
    <div class="sidebar-card">
        <div class="sc-label">Data Source</div>
        <div class="sc-value">Google Sheets</div>
        <div class="sc-sub">Cache: 10 min TTL</div>
    </div>
    <div class="sidebar-card">
        <div class="sc-label">Universe</div>
        <div class="sc-value">US + KR</div>
        <div class="sc-sub">S&P500 · NASDAQ · KOSPI · KOSDAQ</div>
    </div>
    <div class="sidebar-card">
        <div class="sc-label">Strategy</div>
        <div class="sc-value">Risk-Parity</div>
        <div class="sc-sub">ML + Factor Scoring</div>
    </div>
    """, unsafe_allow_html=True)

    try:
        _regime_data = load_macro_regime()
        if _regime_data:
            _regime = _regime_data.get('Regime', 'NEUTRAL')
            _regime_color = {'RISK_ON': '#4ade80', 'RISK_OFF': '#f87171'}.get(_regime, '#facc15')
            _regime_icon  = {'RISK_ON': '🟢', 'RISK_OFF': '🔴'}.get(_regime, '🟡')
            _us_v = _regime_data.get('US_V_Weight', '—')
            _us_q = _regime_data.get('US_Q_Weight', '—')
            _us_m = _regime_data.get('US_M_Weight', '—')
            st.markdown(f"""
            <div class="sidebar-card">
                <div class="sc-label">Macro Regime</div>
                <div class="sc-value" style="color:{_regime_color};">{_regime_icon} {_regime}</div>
                <div class="sc-sub">US  V:{_us_v} · Q:{_us_q} · M:{_us_m}</div>
            </div>
            """, unsafe_allow_html=True)
    except Exception:
        pass

    st.markdown("<div style='margin-top:16px;'></div>", unsafe_allow_html=True)
    if st.button("🔄 Refresh Data", width="stretch"):
        st.cache_data.clear()
        st.rerun()

    st.markdown("""
    <div style="margin-top:24px;padding-top:16px;border-top:1px solid #1f2937;
                font-size:0.7rem;color:#374151;text-align:center;">
        Built with Streamlit · gspread<br>
        © 2026 Jino's Quant Dashboard
    </div>
    """, unsafe_allow_html=True)


# ── 4. Hero ────────────────────────────────────────────────────────────────────
today = datetime.now().strftime("%Y-%m-%d")

_tape_html = ""
try:
    _prices = fetch_marquee_prices()
    _items  = ""
    for _ticker, _name, _price, _pct in _prices:
        _color  = "#4ade80" if _pct >= 0 else "#f87171"
        _arrow  = "▲" if _pct >= 0 else "▼"
        _ps     = f"{_arrow}{abs(_pct)*100:.2f}%"
        _items += (
            f'<a href="?_tick={_ticker}" class="marquee-link">'
            f'<span class="marquee-item">'
            f'<span class="ticker">{_ticker}</span>'
            f'<span class="val" style="color:{_color};">{_ps}</span>'
            f'</span></a>'
        )
    if _items:
        _tape_html = (
            f'<div class="marquee-wrap">'
            f'<div class="marquee-inner">{_items}{_items}</div>'
            f'</div>'
        )
except Exception:
    _tape_html = ""

if 'market' not in st.session_state:
    st.session_state.market = 'US'


def _safe_text(value, fallback="—"):
    text = str(value).strip() if value is not None else ""
    return html.escape(text if text else fallback)


def _fmt_plain_pct(value, digits=1):
    try:
        if value is None or (isinstance(value, float) and math.isnan(value)):
            return "—"
        return f"{float(value) * 100:.{digits}f}%"
    except Exception:
        return "—"


def _notice(kind: str, title: str, body: str):
    st.markdown(
        f"""<div class="system-notice {kind}">
            <div class="notice-title">{_safe_text(title)}</div>
            <div class="notice-body">{_safe_text(body)}</div>
        </div>""",
        unsafe_allow_html=True,
    )


def _render_market_switcher():
    options = ["US", "KR", "SEARCH"]
    labels = {"US": "US Portfolio", "KR": "KR Portfolio", "SEARCH": "Search"}
    current = st.session_state.get("market", "US")
    selected_label = st.radio(
        "Workspace",
        [labels[o] for o in options],
        index=options.index(current) if current in options else 0,
        horizontal=True,
        label_visibility="collapsed",
        key="_market_switcher",
    )
    selected = next(k for k, v in labels.items() if v == selected_label)
    if selected != current:
        st.session_state.market = selected
        st.rerun()


def _score_tone(value):
    try:
        v = float(value)
    except Exception:
        return "neutral"
    if v >= 0.75:
        return "good"
    if v >= 0.55:
        return "watch"
    return "risk"


def _drift_tone(summary):
    rec = str((summary or {}).get("Recommendation", "")).upper()
    try:
        rebal = int(float((summary or {}).get("Stocks_Rebalance") or 0))
        watch = int(float((summary or {}).get("Stocks_Watch") or 0))
    except Exception:
        rebal, watch = 0, 0
    if rebal > 0 or "REBALANCE" in rec:
        return "risk"
    if watch > 0 or "WATCH" in rec:
        return "watch"
    return "good"


def _render_executive_summary():
    market = st.session_state.get("market", "US")
    if market not in ("US", "KR"):
        return

    sheet = "US_Final_Portfolio" if market == "US" else "KR_Final_Portfolio"
    small_sheet = "US_SmallCap_Gems" if market == "US" else "KR_SmallCap_Gems"

    macro = {}
    portfolio = pd.DataFrame()
    smallcap = pd.DataFrame()
    earnings = pd.DataFrame()
    drift_summary = None

    try:
        macro = load_macro_regime()
    except Exception:
        pass
    try:
        _, portfolio = load_portfolio_sheet(sheet)
        if not portfolio.empty:
            portfolio = format_portfolio_df(portfolio)
    except Exception:
        pass
    try:
        _, smallcap = load_portfolio_sheet(small_sheet)
        if not smallcap.empty:
            smallcap = format_smallcap_df(smallcap)
    except Exception:
        pass
    try:
        drift_summaries, _ = load_drift_alert()
        drift_summary = drift_summaries.get(market)
    except Exception:
        pass
    try:
        earnings = load_earnings_momentum(market)
    except Exception:
        pass

    regime = macro.get("Regime", "UNKNOWN") if isinstance(macro, dict) else "UNKNOWN"
    regime_tone = {"RISK_ON": "good", "RISK_OFF": "risk"}.get(str(regime).upper(), "watch")

    top_pick, top_sub, top_score, max_weight = "—", "No portfolio data", None, None
    if not portfolio.empty:
        top = portfolio.iloc[0]
        top_pick = top.get("Name") or top.get("Ticker") or "—"
        top_sub = f"{top.get('Ticker', '—')} · {top.get('Sector', '—')}"
        top_score = top.get("Total_Score")
        max_weight = portfolio["Weight(%)"].max() if "Weight(%)" in portfolio.columns else None

    drift_tone = _drift_tone(drift_summary)
    drift_value = _fmt_plain_pct((drift_summary or {}).get("Total_Drift"), 1)
    drift_sub = (drift_summary or {}).get("Recommendation", "Run drift monitor")

    earn_count = len(earnings) if isinstance(earnings, pd.DataFrame) else 0
    avg_signal = (
        earnings["Signal_Strength"].dropna().mean()
        if isinstance(earnings, pd.DataFrame) and not earnings.empty and "Signal_Strength" in earnings.columns
        else None
    )

    gem_name, gem_score = "—", None
    if isinstance(smallcap, pd.DataFrame) and not smallcap.empty:
        gem = smallcap.iloc[0]
        gem_name = gem.get("Name") or gem.get("Ticker") or "—"
        gem_score = gem.get("Total_Score")

    score_tone = _score_tone(top_score)
    try:
        gem_tone = _score_tone(float(gem_score) / 100.0)
    except Exception:
        gem_tone = "neutral"

    st.markdown(f"""
    <div class="command-center">
        <div class="command-head">
            <div>
                <div class="eyebrow">Decision Summary</div>
                <h2>{market} 투자 지휘실</h2>
            </div>
            <div class="asof">Google Sheets · {today}</div>
        </div>
        <div class="command-grid">
            <div class="command-tile {regime_tone}">
                <div class="tile-label">Macro Regime</div>
                <div class="tile-value">{_safe_text(regime)}</div>
                <div class="tile-sub">Macro_Regime V/Q/M weights</div>
            </div>
            <div class="command-tile {drift_tone}">
                <div class="tile-label">Rebalance State</div>
                <div class="tile-value">{drift_value}</div>
                <div class="tile-sub">{_safe_text(drift_sub)}</div>
            </div>
            <div class="command-tile {score_tone}">
                <div class="tile-label">Top Holding</div>
                <div class="tile-value">{_safe_text(top_pick)}</div>
                <div class="tile-sub">{_safe_text(top_sub)} · max {_fmt_plain_pct(max_weight, 1)}</div>
            </div>
            <div class="command-tile {gem_tone}">
                <div class="tile-label">Small-cap Watch</div>
                <div class="tile-value">{_safe_text(gem_name)}</div>
                <div class="tile-sub">Top gem score {_safe_text(round(float(gem_score), 1) if pd.notna(gem_score) else '—')}</div>
            </div>
            <div class="command-tile neutral">
                <div class="tile-label">Earnings Drift</div>
                <div class="tile-value">{earn_count} candidates</div>
                <div class="tile-sub">Average signal {_safe_text(round(float(avg_signal), 3) if avg_signal is not None and pd.notna(avg_signal) else '—')}</div>
            </div>
        </div>
    </div>
    """, unsafe_allow_html=True)


st.markdown(f"""
<div class="hero">
    <div class="hero-topline">Automated Investment Pipeline</div>
    <h1>Jino's Quant Dashboard</h1>
    <p>US & Korean markets · risk-parity portfolios · factor validation · execution alerts</p>
    <span class="badge">Risk-Parity</span>
    <span class="badge">Factor Scoring</span>
    <span class="badge green">ML Enhanced</span>
    <span class="badge">Updated {today}</span>
    {_tape_html}
</div>
""", unsafe_allow_html=True)

_render_market_switcher()
_render_executive_summary()


# ── 5. Column config helpers ───────────────────────────────────────────────────
def _logo_col():
    return st.column_config.ImageColumn("", width=40)

def _progress_col(label, fmt, max_val):
    return st.column_config.ProgressColumn(label, format=fmt, min_value=0, max_value=max_val)

def _txt(label, width):
    return st.column_config.TextColumn(label, width=width)


def _fmt_market_cap_value(value, currency):
    try:
        x = float(value)
    except Exception:
        return "—"
    if not pd.notna(x) or x <= 0:
        return "—"
    if currency == 'KRW':
        eok = round(x / 1e8)
        jo, rem = divmod(eok, 10000)
        if jo > 0 and rem > 0:
            return f"₩{jo}조 {rem:,}억"
        if jo > 0:
            return f"₩{jo}조"
        return f"₩{rem:,}억"
    return f"${x/1e9:.1f}B" if x >= 1e9 else f"${x/1e6:.0f}M"


def _fmt_pct_cell(value):
    try:
        return f"{float(value):.2%}" if pd.notna(value) else "—"
    except Exception:
        return "—"


def _portfolio_action(row):
    try:
        score = float(row.get('Total_Score'))
    except Exception:
        score = float('nan')
    try:
        weight = float(row.get('Weight(%)'))
    except Exception:
        weight = 0.0
    if pd.notna(score) and score >= 0.75 and weight >= 0.04:
        return "Core"
    if pd.notna(score) and score >= 0.6:
        return "Hold"
    return "Watch"


def _portfolio_risk(row):
    try:
        weight = float(row.get('Weight(%)'))
    except Exception:
        weight = 0.0
    sector = str(row.get('Sector', '')).lower()
    if weight >= 0.10:
        return "Concentration"
    if any(s in sector for s in ["financial", "real estate", "utilities"]):
        return "Balance"
    return "Normal"


def _portfolio_table_view(df, currency):
    view = df.copy()
    if 'Ticker' in view.columns:
        view.insert(0, 'Logo', view['Ticker'].apply(lambda t: company_logo_url_safe(t, currency)))
    view['Action'] = view.apply(_portfolio_action, axis=1)
    view['Risk'] = view.apply(_portfolio_risk, axis=1)
    for col in ['ROIC', 'RevGrowth', 'GrossMargin', 'Expected_Return']:
        if col in view.columns:
            view[col] = view[col].apply(_fmt_pct_cell)
    if 'MarketCap' in view.columns:
        view['MarketCap'] = view['MarketCap'].apply(lambda x: _fmt_market_cap_value(x, currency))
    if 'Rank' in view.columns:
        view['Rank'] = view['Rank'].apply(lambda x: str(x) if str(x).strip() else "—")
    return view


def _fmt_investment_amount(amount, currency):
    try:
        amount = float(amount)
    except Exception:
        return "—"
    if pd.isna(amount):
        return "—"
    if currency == 'KRW':
        if amount >= 1e8:
            eok = amount / 1e8
            return f"₩{eok:,.1f}억" if eok < 100 else f"₩{eok:,.0f}억"
        if amount >= 1e4:
            man = amount / 1e4
            return f"₩{man:,.1f}만" if man < 100 else f"₩{man:,.0f}만"
        return f"₩{amount:,.0f}"
    return f"${amount:,.0f}"


def _smallcap_signal(row):
    try:
        score = float(row.get('Total_Score'))
    except Exception:
        score = 0.0
    try:
        vol = float(row.get('Volume_Surge'))
    except Exception:
        vol = 0.0
    if score >= 90 and vol >= 1.5:
        return "Breakout"
    if score >= 80:
        return "Quality"
    return "Watch"


def _smallcap_table_view(df, currency):
    view = df.copy()
    if 'Ticker' in view.columns:
        view.insert(0, 'Logo', view['Ticker'].apply(lambda t: company_logo_url_safe(t, currency)))
    view['Signal'] = view.apply(_smallcap_signal, axis=1)
    for col in ['ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin']:
        if col in view.columns:
            view[col] = view[col].apply(_fmt_pct_cell)
    for col in ['PEG', 'Debt_EBITDA', 'Volume_Surge']:
        if col in view.columns:
            view[col] = view[col].apply(lambda x: f"{x:.2f}" if pd.notna(x) else "—")
    for col in ['SmallCap_Bonus']:
        if col in view.columns:
            view[col] = view[col].apply(lambda x: f"{x:.1f}" if pd.notna(x) else "—")
    if 'MarketCap' in view.columns:
        view['MarketCap'] = view['MarketCap'].apply(lambda x: _fmt_market_cap_value(x, currency))
    if 'Rank' in view.columns:
        view['Rank'] = view['Rank'].apply(lambda x: str(x) if str(x).strip() else "—")
    return view


def company_logo_url_safe(ticker, currency):
    if currency == 'KRW':
        code = str(ticker).split('.')[0]
        if code == '064400':
            return "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png"
        if code == '267250':
            return f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png"
        return f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png"
    return f"https://financialmodelingprep.com/image-stock/{str(ticker).upper()}.png"


def _render_stock_thesis(row, currency):
    ticker = row.get('Ticker', '—')
    name = row.get('Name', ticker)
    score = row.get('Total_Score', row.get('Signal_Strength', None))
    value = row.get('Value_Score', None)
    quality = row.get('Quality_Score', None)
    momentum = row.get('Momentum_Score', None)
    weight = row.get('Weight(%)', None)
    risk = _portfolio_risk(row) if 'Weight(%)' in row else "Event"
    action = _portfolio_action(row) if 'Weight(%)' in row else _smallcap_signal(row)

    st.markdown(f"""
    <div class="thesis-panel">
        <div class="thesis-title">{_safe_text(name)} <span>{_safe_text(ticker)}</span></div>
        <div class="thesis-grid">
            <div><b>Action</b><span>{_safe_text(action)}</span></div>
            <div><b>Risk</b><span>{_safe_text(risk)}</span></div>
            <div><b>Score</b><span>{_safe_text(round(float(score), 4) if pd.notna(score) else '—')}</span></div>
            <div><b>Weight</b><span>{_fmt_plain_pct(weight, 2)}</span></div>
        </div>
        <div class="thesis-copy">
            Value {_safe_text(round(float(value), 3) if pd.notna(value) else '—')} ·
            Quality {_safe_text(round(float(quality), 3) if pd.notna(quality) else '—')} ·
            Momentum {_safe_text(round(float(momentum), 3) if pd.notna(momentum) else '—')}
        </div>
    </div>
    """, unsafe_allow_html=True)


# ── 6. Shared methodology strings ─────────────────────────────────────────────
_MD_DRIFT = """
**포트폴리오 비중 드리프트 계산**

리밸런싱 이후 주가 변동으로 인한 **자연적 비중 이동**을 추적합니다.

```
w_current(i) = w_target(i) × (1 + r_i) / Σ[w_target(j) × (1 + r_j)]
Drift_Abs(i) = |w_current(i) − w_target(i)|
Total_Drift  = Σ Drift_Abs(i) / 2   (= 완전 리밸런싱 시 편도 턴오버)
```

| 상태 | 기준 | 의미 |
|------|------|------|
| 🔴 REBALANCE | Drift > 5% | 즉시 리밸런싱 권고 |
| 🟡 WATCH | 3% < Drift ≤ 5% | 주시 필요 |
| 🟢 OK | Drift ≤ 3% | 허용 범위 내 |

**리밸런싱 권고 기준**: 3종목 이상 🔴 또는 Total_Drift > 10%
"""

_MD_CORR = """
**분산화 점수 (Diversification Score)**

- **평균 상관계수** = 모든 종목 쌍의 수익률 상관계수 단순 평균 (대각 제외)
- **분산화 점수** = 1 − 평균 상관계수

| 등급 | 평균 상관계수 | 의미 |
|------|-------------|------|
| 우수 🟢 | < 0.35 | 종목 간 독립성 높음 — 분산 효과 탁월 |
| 보통 🟡 | 0.35 – 0.55 | 적정 수준의 분산화 |
| 주의 🔴 | > 0.55 | 종목 간 동조화 강함 — 시장 충격 시 손실 집중 위험 |

리스크 패리티 최적화는 변동성을 균등 분배하지만, **상관관계가 높으면** 개별 종목 분산화 효과가 제한됩니다.
변동성 분산과 함께 이 히트맵을 모니터링해 실질적인 포트폴리오 리스크를 평가하세요.
"""

_MD_ATTRIB = """
**Barra-lite Factor Attribution 방법론**

1. **팩터 노출도 계산** — `Scored_Stocks`에서 Value/Quality/Momentum 점수를
   전체 유니버스 기준으로 Z-score 정규화 → 각 종목의 팩터 노출도 `f_V, f_Q, f_M`

2. **OLS 횡단면 회귀** — 리밸런싱 이후 기간의 종목별 수익률을 팩터 노출도에 회귀:
   ```
   r_i = β_V · f_V(i) + β_Q · f_Q(i) + β_M · f_M(i) + ε_i
   ```
   → `β_V, β_Q, β_M`: 팩터 단위 노출도당 수익 (팩터 수익률)

3. **포트폴리오 팩터 노출도** — 포트폴리오 보유 종목의 가중 평균:
   ```
   h_j = Σ w_i · f_j(i)
   ```

4. **귀인 분해**:
   ```
   Portfolio Return  = h_V × β_V  (Value 기여)
                     + h_Q × β_Q  (Quality 기여)
                     + h_M × β_M  (Momentum 기여)
                     + Residual    (팩터 외 종목 고유 알파)
   ```

**해석 가이드**:
- **R²** — 팩터 3개가 유니버스 수익률 분산의 몇 %를 설명하는지 (높을수록 팩터 설명력 ↑)
- **β > 0** — 해당 팩터가 해당 기간 긍정적 수익 기여
- **Residual > 0** — 팩터 노출도 이상의 초과 수익 (순수 종목 선택 알파)
- **|Exposure| > 1** — 해당 팩터에 유니버스 평균 대비 강하게 노출된 종목
"""


# ═══════════════════════════════════════════════════════════════════════════════
# Tab render functions — one function per tab type, parameterised by market
# ═══════════════════════════════════════════════════════════════════════════════

def _render_portfolio_tab(market: str):
    is_us = market == 'US'
    sheet    = 'US_Final_Portfolio' if is_us else 'KR_Final_Portfolio'
    currency = 'USD' if is_us else 'KRW'
    banner   = (
        'S&P 500 + NASDAQ-100 유니버스에서 <strong>ML + Factor Score</strong> 상위 30종목을 '
        '<strong>Risk-Parity</strong> 방식으로 최적화한 포트폴리오입니다.'
        if is_us else
        'KOSPI300 + KOSDAQ200 유니버스에서 <strong>Factor Score</strong> 상위 30종목을 '
        '<strong>Risk-Parity</strong> 방식으로 최적화한 포트폴리오입니다.'
    )
    lbl = (
        dict(Name='Name', Sector='Sector', MarketCap='Mkt Cap', Weight='Weight',
             Score='Score', RevGrowth='Rev Gwth', GrossMargin='Gross Mgn',
             ExpRet='Exp Ret', Updated='Updated', ticker_w=85, mktcap_w=90, margin_w=90)
        if is_us else
        dict(Name='종목명', Sector='섹터', MarketCap='시가총액', Weight='비중',
             Score='스코어', RevGrowth='매출성장', GrossMargin='매출총이익률',
             ExpRet='기대수익', Updated='업데이트', ticker_w=95, mktcap_w=105, margin_w=100)
    )

    st.markdown(
        f'<div class="info-banner"><span class="bi">ℹ️</span> {banner}</div>',
        unsafe_allow_html=True,
    )
    try:
        with st.spinner("포트폴리오 데이터 로딩 중..."):
            meta, port = load_portfolio_sheet(sheet)
        if port.empty:
            st.warning(f"{sheet} 시트가 비어 있습니다.")
            return
        port = format_portfolio_df(port)
        _scored = load_scored_stocks(market)  # cached; shared by factor chart + explorer

        # ── Summary + charts ──────────────────────────────────────────
        section_header("포트폴리오 요약", "📋")
        render_meta_cards(meta, tab_key=market.lower())
        render_quick_metrics(port, currency=currency)
        section_header("포트폴리오 시각화", "📊")
        render_charts(port, currency=currency)

        # ── V/Q/M 팩터 분해 차트 ──────────────────────────────────────
        section_header("V/Q/M 팩터 분해", "🔬")
        st.caption("각 종목의 Total Score를 Value · Quality · Momentum 기여분으로 분해합니다.")
        if not _scored.empty:
            render_factor_decomposition(_scored, port['Ticker'].tolist(), market=market)
        else:
            st.info("스코어드 종목 데이터가 없습니다. 파이프라인을 먼저 실행하세요.")

        # ── 종목 상세 테이블 ──────────────────────────────────────────
        section_header(f"종목 상세 ({len(port)}개)", "📋")

        # 투자금 계산기
        _inv_col, _mode_col, _summary_col = st.columns([2, 2.2, 2.8])
        with _inv_col:
            _step = 1_000_000.0 if currency == 'KRW' else 10_000.0
            _total_inv = st.number_input(
                "💰 투자금 계산기",
                min_value=0.0, value=0.0, step=_step, format="%.0f",
                key=f"calc_{market}",
                help=f"총 투자 금액 ({currency})을 입력하면 종목별 투자 금액을 자동 계산합니다.",
            )
        _weight_sum = (
            pd.to_numeric(port['Weight(%)'], errors='coerce').fillna(0).sum()
            if 'Weight(%)' in port.columns else 0.0
        )
        with _mode_col:
            _normalize_inv = st.toggle(
                "표시 종목에 전액 배분",
                value=True,
                key=f"calc_normalize_{market}",
                help="켜면 현재 테이블 종목들의 비중을 100%로 환산해 입력 금액 전액을 배분합니다.",
            )

        if _total_inv > 0 and 'Weight(%)' in port.columns and _weight_sum > 0:
            _weights = pd.to_numeric(port['Weight(%)'], errors='coerce').fillna(0)
            _calc_weights = _weights / _weight_sum if _normalize_inv else _weights
            _inv_lbl = '투자금액' if not is_us else 'Invest Amt'
            _calc_weight_lbl = '계산비중(%)' if not is_us else 'Calc Weight(%)'
            port = port.copy()
            port[_calc_weight_lbl] = _calc_weights
            port[_inv_lbl] = (_calc_weights * _total_inv).apply(lambda x: _fmt_investment_amount(x, currency))
            with _summary_col:
                _allocated = float((_calc_weights * _total_inv).sum())
                _cash = max(float(_total_inv) - _allocated, 0.0)
                st.caption(
                    f"배분 {_fmt_investment_amount(_allocated, currency)}"
                    f" · 잔여 {_fmt_investment_amount(_cash, currency)}"
                    f" · 원본비중 합계 {_weight_sum:.1%}"
                )
        else:
            _inv_lbl = None
            _calc_weight_lbl = None

        _hint_col, _dl_col = st.columns([7, 1])
        with _hint_col:
            st.caption("💡 행을 클릭하면 기업 상세 정보(주가·설명)를 볼 수 있습니다.")
        with _dl_col:
            _csv = port.drop(columns=['Logo'], errors='ignore').to_csv(index=False).encode('utf-8-sig')
            st.download_button("⬇ CSV", data=_csv, file_name=f"{sheet}_{today}.csv",
                               mime='text/csv', key=f"dl_{market}_port", width="stretch")

        _col_cfg = {
            "Logo":            _logo_col(),
            "Rank":            _txt("Rank", 55),
            "Ticker":          _txt("Ticker", lbl['ticker_w']),
            "Name":            _txt(lbl['Name'], 175),
            "Action":          _txt("Action", 85),
            "Risk":            _txt("Risk", 110),
            "Sector":          _txt(lbl['Sector'], 135),
            "MarketCap":       _txt(lbl['MarketCap'], lbl['mktcap_w']),
            "Weight(%)":       _progress_col(lbl['Weight'], "%.2f", 0.16),
            "Total_Score":     _progress_col(lbl['Score'], "%.4f", 1.0),
            "ROIC":            _txt("ROIC", 78),
            "RevGrowth":       _txt(lbl['RevGrowth'], 85),
            "GrossMargin":     _txt(lbl['GrossMargin'], lbl['margin_w']),
            "Expected_Return": _txt(lbl['ExpRet'], 85),
            "Last_Updated":    _txt(lbl['Updated'], 95),
        }
        if _inv_lbl:
            _col_cfg[_calc_weight_lbl] = _progress_col(_calc_weight_lbl, "%.2f", 1.0)
            _col_cfg[_inv_lbl] = _txt(_inv_lbl, 105)

        _display_port = _portfolio_table_view(port, currency=currency)
        evt = st.dataframe(
            _display_port,
            width="stretch", hide_index=True,
            on_select="rerun", selection_mode="single-row",
            column_config=_col_cfg,
        )
        if evt.selection.rows:
            row = port.iloc[evt.selection.rows[0]]
            _render_stock_thesis(row, currency)
            st.session_state['_dlg'] = (row['Ticker'], row.get('Name', row['Ticker']), currency, row.to_dict())

        # ── 스코어드 종목 탐색기 ──────────────────────────────────────
        with st.expander(f"🔍 전체 스코어드 종목 탐색기 ({market})", expanded=False):
            if _scored.empty:
                st.info("스코어드 종목 데이터가 없습니다. 파이프라인을 먼저 실행하세요.")
            else:
                _sc_col  = next((c for c in ('Final_Score', 'Combined_Score', 'Total_Score')
                                 if c in _scored.columns), 'Total_Score')
                _sc_num  = pd.to_numeric(_scored[_sc_col], errors='coerce')
                _sc_min  = float(_sc_num.dropna().min())
                _sc_max  = float(_sc_num.dropna().max())

                _ef1, _ef2, _ef3 = st.columns([2, 2, 1])
                with _ef1:
                    _secs = (['전체'] + sorted(_scored['Sector'].dropna().unique().tolist())
                             if 'Sector' in _scored.columns else ['전체'])
                    _sel_sec = st.selectbox("섹터 필터", _secs, key=f"sec_{market}")
                with _ef2:
                    _thr = st.slider("최소 스코어", _sc_min, _sc_max, _sc_min,
                                     max((_sc_max - _sc_min) / 20, 1e-6),
                                     key=f"thr_{market}", format="%.3f")
                with _ef3:
                    st.metric("전체 종목 수", f"{len(_scored)}개")

                _filt = _scored.copy()
                _filt[_sc_col] = pd.to_numeric(_filt[_sc_col], errors='coerce')
                if _sel_sec != '전체' and 'Sector' in _filt.columns:
                    _filt = _filt[_filt['Sector'] == _sel_sec]
                _filt = (_filt[_filt[_sc_col] >= _thr]
                         .sort_values(_sc_col, ascending=False)
                         .reset_index(drop=True))
                _filt['Rank'] = _filt.index + 1

                _dcols = ['Rank', 'Ticker', 'Name', 'Sector',
                          'Value_Score', 'Quality_Score', 'Momentum_Score', _sc_col]
                _dcols = [c for c in _dcols if c in _filt.columns]
                st.caption(f"필터 결과: **{len(_filt)}**개  ·  정렬: {_sc_col} 내림차순")

                _sc_evt = st.dataframe(
                    _filt[_dcols], width="stretch", hide_index=True,
                    on_select="rerun", selection_mode="single-row",
                    key=f"scored_tbl_{market}",
                    column_config={
                        "Rank":           _txt("#", 45),
                        "Ticker":         _txt("Ticker", lbl['ticker_w']),
                        "Name":           _txt(lbl['Name'], 170),
                        "Sector":         _txt(lbl['Sector'], 130),
                        "Value_Score":    _progress_col("Value",    "%.4f", 0.5),
                        "Quality_Score":  _progress_col("Quality",  "%.4f", 0.5),
                        "Momentum_Score": _progress_col("Momentum", "%.4f", 0.5),
                        _sc_col:          _progress_col(lbl['Score'], "%.4f", 1.0),
                    },
                )
                if _sc_evt.selection.rows:
                    _row = _filt.iloc[_sc_evt.selection.rows[0]]
                    st.session_state['_dlg'] = (
                        _row['Ticker'], _row.get('Name', _row['Ticker']), currency, _row.to_dict()
                    )
    except Exception as e:
        st.error(f"{sheet} 로드 에러: {e}")


def _render_smallcap_tab(market: str):
    is_us = market == 'US'
    sheet    = 'US_SmallCap_Gems' if is_us else 'KR_SmallCap_Gems'
    currency = 'USD' if is_us else 'KRW'
    universe = 'Russell 2000 (IWM)' if is_us else 'KOSPI300 + KOSDAQ200'
    mktcap_range = '$100M – $1B' if is_us else '1,000억 – 10조 원'
    banner = (
        'Russell 2000 유니버스에서 <strong>ROIC · 매출성장 · 매출가속 · 내부자보유 · 순현금비율</strong> 기반으로 '
        '선별한 <strong>10-Bagger 소형주 후보</strong>입니다. (시총 $100M–$1B, 금융/보험/리츠 제외)'
        if is_us else
        'KOSPI/KOSDAQ 유니버스에서 <strong>ROIC · 매출성장 · 매출가속 · 내부자보유 · 순현금비율</strong> 기반으로 '
        '선별한 <strong>10-Bagger 소형주 후보</strong>입니다. (시총 1,000억–10조 원, 금융/보험/리츠 제외)'
    )
    lbl = (
        dict(Name='Name', MarketCap='Mkt Cap', RevGrowth='Rev Gwth', RevAccel='Rev Accel',
             GrossMargin='Gross Mgn', FCFMargin='FCF Mgn', DebtEBITDA='D/EBITDA',
             Insider='Insider%', NetCash='Net Cash', VolSurge='Vol Surge',
             Bonus='Bonus', Score='Gem Score', Updated='Updated', ticker_w=85, mktcap_w=90)
        if is_us else
        dict(Name='종목명', MarketCap='시가총액', RevGrowth='매출성장', RevAccel='매출가속',
             GrossMargin='매출총이익률', FCFMargin='FCF 마진', DebtEBITDA='부채/EBITDA',
             Insider='내부자보유', NetCash='순현금비율', VolSurge='거래량 서지',
             Bonus='보너스', Score='Gem 스코어', Updated='업데이트', ticker_w=95, mktcap_w=105)
    )

    st.markdown(
        f'<div class="info-banner"><span class="bi">💎</span> {banner}</div>',
        unsafe_allow_html=True,
    )
    try:
        with st.spinner("소형주 데이터 로딩 중..."):
            meta, df = load_portfolio_sheet(sheet)
        if df.empty:
            st.warning(f"{sheet} 시트가 비어 있습니다.")
            return
        df = format_smallcap_df(df)
        score_top = (
            pd.to_numeric(df['Total_Score'], errors='coerce').max()
            if 'Total_Score' in df.columns else float('nan')
        )
        score_max = (
            max(1.0, math.ceil(float(score_top) / 10.0) * 10.0)
            if pd.notna(score_top) else 1.0
        )

        if not is_us:
            df = enrich_kr_company_identities(df)

        if not meta:
            generated = df['Last_Updated'].dropna().iloc[0] if 'Last_Updated' in df.columns and not df['Last_Updated'].dropna().empty else '—'
            meta = {
                'Strategy':         '10-Bagger SmallCap Scanner',
                'Universe':         universe,
                'Market Cap Range': mktcap_range,
                'Number of Stocks': str(len(df)),
                'Generated':        str(generated),
            }
        section_header("포트폴리오 요약", "📋")
        render_meta_cards(meta, tab_key=f"{market.lower()}_sc")
        render_smallcap_quick_metrics(df, currency=currency)
        section_header("Gem 스코어 시각화", "📊")
        render_smallcap_charts(df, currency=currency)
        section_header(f"종목 상세 ({len(df)}개)", "📋")
        _hint_col2, _dl_col2 = st.columns([7, 1])
        with _hint_col2:
            st.caption("💡 행을 클릭하면 기업 상세 정보(주가·설명)를 볼 수 있습니다.")
        with _dl_col2:
            _csv2 = df.drop(columns=['Logo'], errors='ignore').to_csv(index=False).encode('utf-8-sig')
            st.download_button("⬇ CSV", data=_csv2, file_name=f"{sheet}_{today}.csv",
                               mime='text/csv', key=f"dl_{market}_sc", width="stretch")
        _display_sc = _smallcap_table_view(df, currency=currency)
        evt = st.dataframe(
            _display_sc,
            width="stretch", hide_index=True,
            on_select="rerun", selection_mode="single-row",
            column_config={
                "Logo":           _logo_col(),
                "Rank":           _txt("Rank", 55),
                "Ticker":         _txt("Ticker", lbl['ticker_w']),
                "Name":           _txt(lbl['Name'], 175),
                "Signal":         _txt("Signal", 90),
                "Market":         _txt("Mkt", 60),
                "MarketCap":      _txt(lbl['MarketCap'], lbl['mktcap_w']),
                "ROIC":           _txt("ROIC", 78),
                "RevGrowth":      _txt(lbl['RevGrowth'], 85),
                "Rev_Accel":      _txt(lbl['RevAccel'], 88),
                "GrossMargin":    _txt(lbl['GrossMargin'], 90 if is_us else 100),
                "FCF_Margin":     _txt(lbl['FCFMargin'], 85),
                "Debt_EBITDA":    _txt(lbl['DebtEBITDA'], 88 if is_us else 95),
                "Insider_Pct":    _txt(lbl['Insider'], 82 if is_us else 85),
                "Net_Cash_Ratio": _txt(lbl['NetCash'], 82 if is_us else 85),
                "Volume_Surge":   _txt(lbl['VolSurge'], 85 if is_us else 90),
                "SmallCap_Bonus": _txt(lbl['Bonus'], 70),
                "Total_Score":    _progress_col(lbl['Score'], "%.1f", score_max),
                "Last_Updated":   _txt(lbl['Updated'], 95),
            },
        )
        if evt.selection.rows:
            row = df.iloc[evt.selection.rows[0]]
            _render_stock_thesis(row, currency)
            st.session_state['_dlg'] = (row['Ticker'], row.get('Name', row['Ticker']), currency, row.to_dict())
    except Exception as e:
        st.error(f"{sheet} 로드 에러: {e}")


def _render_backtest_tab(market: str):
    is_us = market == 'US'
    sheet = 'US_Backtest_Results' if is_us else 'KR_Backtest_Results'
    hint  = ('python pipeline/05a_backtest_us.py' if is_us
             else 'python pipeline/05b_backtest_kr.py')
    banner = (
        '<strong>SEC EDGAR XBRL 재무제표</strong>를 활용한 <strong>Point-in-Time 백테스트</strong>입니다. '
        '10분기(~2.5년) 동안 매 7일마다 V/Q/M 팩터 스코어로 상위 30종목을 선정하고 '
        '<strong>Risk-Parity</strong>로 리밸런싱한 실제 수익률을 보여줍니다.'
        if is_us else
        'KR 유니버스에서 <strong>Factor Score</strong> 상위 30종목을 선정하고 '
        '<strong>Risk-Parity</strong>로 리밸런싱한 백테스트 결과입니다. '
        '거래비용(Toss 0.015% 편도)이 반영된 실제 수익률을 보여줍니다.'
    )

    st.markdown(
        f'<div class="info-banner"><span class="bi">📈</span> {banner}</div>',
        unsafe_allow_html=True,
    )
    try:
        with st.spinner("백테스트 데이터 로딩 중..."):
            bt_meta, bt_ret, bt_detail = load_backtest_sheet(sheet)
        if bt_ret.empty:
            st.warning(f"{sheet} 시트가 비어 있거나 아직 파이프라인이 실행되지 않았습니다.")
            st.info(f"💡 `{hint}` 를 먼저 실행하세요.")
            return

        if bt_meta:
            info_items = [f"**{k}:** {v}" for k in ['Strategy', 'Universe', 'Period', 'Fee']
                          if (v := bt_meta.get(k))]
            if info_items:
                st.caption("  ·  ".join(info_items))

        section_header("백테스트 성과 요약", "🏆")
        render_backtest_kpi(bt_meta)

        benchmark_cum = pd.Series(dtype=float)
        if not bt_ret.empty and 'Date' in bt_ret.columns:
            start = bt_ret['Date'].dropna().iloc[0].strftime('%Y-%m-%d')
            end   = bt_ret['Date'].dropna().iloc[-1].strftime('%Y-%m-%d')
            benchmark_cum = (fetch_nasdaq_benchmark(start, end) if is_us
                             else fetch_kospi_benchmark(start, end))

        section_header("수익률 분석", "📊")
        if is_us:
            render_backtest_charts(bt_ret, bt_detail, nasdaq_cum=benchmark_cum)
        else:
            render_backtest_charts(
                bt_ret, bt_detail,
                nasdaq_cum=benchmark_cum,
                benchmark_label='KOSPI (^KS11)',
                benchmark_hover='코스피 수익',
            )
    except Exception as e:
        st.error(f"{sheet} 로드 에러: {e}")


def _render_attribution_tab(market: str):
    is_us = market == 'US'
    flag  = '🇺🇸' if is_us else '🇰🇷'
    refresh_key = 'refresh_attribution' if is_us else 'refresh_attribution_kr'
    universe_label = 'Scored_Stocks' if is_us else 'KR_Scored_Stocks'

    st.markdown(f"""<div class="info-banner">
        <span class="bi">📊</span>
        <strong>Barra-스타일 팩터 귀인분석</strong>입니다.
        마지막 리밸런싱 이후 {flag} 포트폴리오 수익률을
        <strong>Value · Quality · Momentum</strong> 팩터 기여분과
        <strong>종목 고유 잔차(Residual)</strong>로 분해합니다.<br>
        방법론: OLS 횡단면 회귀분석 — {universe_label} 유니버스에서 팩터 수익(β)을 추정하고,
        포트폴리오의 가중 팩터 노출도(h)와 곱하여 기여분을 계산합니다.
    </div>""", unsafe_allow_html=True)

    if st.button("🔄 귀인분석 새로고침", key=refresh_key):
        load_attribution_sheet.clear()

    try:
        with st.spinner("팩터 귀인분석 데이터 로딩 중..."):
            attr_summaries, attr_detail = load_attribution_sheet()
        summaries = [s for s in attr_summaries if s.get('Market') == market]

        if not summaries:
            st.warning(f"Factor_Attribution 시트에 {market} 데이터가 없습니다.")
            st.info("💡 `python pipeline/13_factor_attribution.py` 를 먼저 실행하세요.")
            return

        for summary in summaries:
            n_port = summary.get('N_Portfolio', '?')
            n_univ = summary.get('N_Universe', '?')
            section_header(
                f"{flag}  {market} 포트폴리오 귀인분석  (보유 {n_port}종목 · 유니버스 {n_univ}종목)",
                "📊",
            )
            kpi_col, chart_col = st.columns([1, 1.6])
            with kpi_col:
                render_attribution_kpi(summary, market=market)
            with chart_col:
                render_attribution_waterfall(summary, market=market)

            if not attr_detail.empty:
                detail = (attr_detail[attr_detail['Market'] == market]
                          if 'Market' in attr_detail.columns else attr_detail)
                if not detail.empty:
                    with st.expander(f"📋 {market} 종목별 귀인 상세", expanded=False):
                        st.caption(
                            "**V/Q/M Exposure**: 팩터 점수를 유니버스 전체 기준으로 Z-score 정규화한 값 "
                            "(양수 = 팩터 점수 평균 이상)  ·  "
                            "**Predicted Return**: 팩터 노출도로 설명되는 기대 수익  ·  "
                            "**Residual**: 팩터로 설명되지 않는 종목 고유 알파"
                        )
                        render_attribution_detail(detail, market=market)
            st.divider()

        with st.expander("🔬 방법론 상세", expanded=False):
            st.markdown(_MD_ATTRIB)
    except Exception as e:
        st.error(f"Factor Attribution 로드 에러: {e}")


def _render_correlation_tab(market: str):
    is_us = market == 'US'
    sheet = 'US_Final_Portfolio' if is_us else 'KR_Final_Portfolio'
    flag  = '🇺🇸 US' if is_us else '🇰🇷 KR'

    st.markdown(f"""<div class="info-banner">
        <span class="bi">📉</span>
        <strong>{flag} 포트폴리오 종목</strong> 간 수익률 상관관계 히트맵입니다.
        색이 파란색(음수)에 가까울수록 분산 효과가 크고,
        빨간색(양수)에 가까울수록 동조화 위험이 높습니다.
    </div>""", unsafe_allow_html=True)
    try:
        with st.spinner("1년치 주가 다운로드 중 (최대 30초)..."):
            corr_df, corr_tickers, corr_names = load_correlation_data(sheet)
        if corr_df.empty:
            st.warning(f"{sheet} 데이터가 없거나 가격 다운로드에 실패했습니다.")
            st.info("💡 파이프라인을 실행한 후 다시 시도하세요.")
            return
        section_header("분산화 지표", "📊")
        render_diversification_score(corr_df)
        section_header("수익률 상관관계 히트맵", "🔥")
        st.caption(
            "상관계수 범위: −1 (완전 역상관) → 0 (무상관) → +1 (완전 정상관)  ·  "
            f"데이터: 최근 1년 일별 수익률  ·  {len(corr_tickers)}개 종목"
        )
        render_correlation_heatmap(corr_df, corr_names)
        with st.expander("🔬 분산화 점수 산출 방법", expanded=False):
            st.markdown(_MD_CORR)
    except Exception as e:
        st.error(f"Correlation 로드 에러: {e}")


def _render_drift_tab(market: str):
    is_us = market == 'US'
    refresh_key = 'refresh_drift_us' if is_us else 'refresh_drift_kr'
    section_lbl = 'US 드리프트 요약' if is_us else 'KR 드리프트 요약'

    st.markdown(f"""<div class="info-banner">
        <span class="bi">🔔</span>
        마지막 리밸런싱 이후 <strong>{"US" if is_us else "KR"} 포트폴리오 비중 드리프트</strong>를 모니터링합니다.
        주가 변동으로 인해 목표 비중에서 벗어난 종목을 색상으로 구분해 보여줍니다.
        🔴 REBALANCE(5% 초과) · 🟡 WATCH(3–5%) · 🟢 OK(3% 이하)
    </div>""", unsafe_allow_html=True)

    col_refresh, _ = st.columns([1, 5])
    with col_refresh:
        if st.button("🔄 드리프트 새로고침", key=refresh_key):
            load_drift_alert.clear()

    try:
        drift_summaries, drift_detail = load_drift_alert()
        summary = drift_summaries.get(market)
        if summary is None:
            st.warning(f"{market} 드리프트 데이터가 없습니다.")
            st.info("💡 `python pipeline/12_portfolio_drift_monitor.py` 를 먼저 실행하세요.")
            return

        section_header(section_lbl, "📊")
        render_drift_kpi(summary)

        mkt_drift = (drift_detail[drift_detail['Market'] == market]
                     if not drift_detail.empty and 'Market' in drift_detail.columns
                     else drift_detail)
        if not mkt_drift.empty:
            section_header("종목별 비중 변화", "📈")
            render_drift_chart(mkt_drift)
            section_header("종목별 드리프트 상세", "📋")
            render_drift_table(mkt_drift)

        with st.expander("🔬 드리프트 계산 방법론", expanded=False):
            st.markdown(_MD_DRIFT)
    except Exception as e:
        st.error(f"Drift Alert 로드 에러: {e}")


def _render_factor_ic_tab(market: str):
    st.markdown("""<div class="info-banner">
        <span class="bi">🧪</span>
        <strong>Factor IC 리포트</strong>는 과거 점수 스냅샷이 이후 1M/3M/6M 수익률을
        얼마나 잘 예측했는지 Spearman rank correlation으로 검증합니다.
        첫 실행 직후에는 스냅샷만 쌓이고, 시간이 지나면서 리포트가 자동으로 채워집니다.
    </div>""", unsafe_allow_html=True)

    col_refresh, _ = st.columns([1, 5])
    with col_refresh:
        if st.button("🔄 IC 새로고침", key=f"refresh_ic_{market}"):
            load_factor_ic_report.clear()

    try:
        summary_df, detail_df = load_factor_ic_report()
        if summary_df.empty:
            _notice(
                "info",
                "아직 검증 가능한 IC 표본이 없습니다",
                "Factor_Score_Snapshots는 생성됐지만 1개월 이상 지난 스냅샷이 있어야 1M IC가 계산됩니다.",
            )
            return

        summary = summary_df[summary_df["Market"] == market].copy() if "Market" in summary_df.columns else summary_df
        detail = detail_df[detail_df["Market"] == market].copy() if not detail_df.empty and "Market" in detail_df.columns else detail_df

        if summary.empty:
            _notice("info", f"{market} IC 데이터 없음", "스냅샷이 쌓이면 이 영역이 자동으로 채워집니다.")
            return

        best = summary.sort_values("Mean_IC", ascending=False, na_position="last").iloc[0]
        worst = summary.sort_values("Mean_IC", ascending=True, na_position="last").iloc[0]
        c1, c2, c3, c4 = st.columns(4)
        c1.metric("Best Factor", str(best.get("Factor", "—")).replace("_Score", ""), f"{best.get('Horizon', '')}")
        c2.metric("Mean IC", f"{best.get('Mean_IC', float('nan')):.3f}" if pd.notna(best.get("Mean_IC")) else "—")
        c3.metric("Top-Bottom Spread", _fmt_plain_pct(best.get("Mean_Top_Bottom_Spread"), 2))
        c4.metric("Weakest IC", f"{worst.get('Mean_IC', float('nan')):.3f}" if pd.notna(worst.get("Mean_IC")) else "—")

        section_header("팩터 예측력 요약", "🧪")
        plot_df = summary.dropna(subset=["Mean_IC"]).copy()
        if not plot_df.empty:
            plot_df["Label"] = plot_df["Factor"].str.replace("_Score", "", regex=False) + " · " + plot_df["Horizon"].astype(str)
            colors = ["#34d399" if v >= 0 else "#f87171" for v in plot_df["Mean_IC"]]
            fig = go.Figure(go.Bar(
                x=plot_df["Mean_IC"],
                y=plot_df["Label"],
                orientation="h",
                marker_color=colors,
                text=[f"{v:+.3f}" for v in plot_df["Mean_IC"]],
                textposition="outside",
            ))
            fig.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="Mean Spearman IC by Factor/Horizon", font=dict(color="#e2e8f0", size=13)),
                height=max(330, 26 * len(plot_df)),
                xaxis=dict(zeroline=True, zerolinecolor="#64748b", gridcolor="#1f2937"),
                yaxis=dict(tickfont=dict(size=10, color="#94a3b8")),
            )
            st.plotly_chart(fig, width="stretch")

        st.dataframe(
            summary,
            width="stretch",
            hide_index=True,
            column_config={
                "Factor": _txt("Factor", 130),
                "Horizon": _txt("Horizon", 80),
                "Snapshots": st.column_config.NumberColumn("Snapshots", width=90),
                "Mean_IC": st.column_config.NumberColumn("Mean IC", format="%.4f", width=90),
                "Median_IC": st.column_config.NumberColumn("Median IC", format="%.4f", width=95),
                "Positive_IC_Rate": st.column_config.ProgressColumn("IC > 0", format="%.1f", min_value=0, max_value=1),
                "Mean_Top_Bottom_Spread": st.column_config.NumberColumn("Top-Bottom", format="%.4f", width=105),
                "Mean_Hit_Rate": st.column_config.ProgressColumn("Hit Rate", format="%.1f", min_value=0, max_value=1),
                "Total_Observations": st.column_config.NumberColumn("Obs", width=80),
            },
        )

        if not detail.empty:
            with st.expander("스냅샷별 상세 IC", expanded=False):
                st.dataframe(detail, width="stretch", hide_index=True)
    except Exception as e:
        st.error(f"Factor IC 로드 에러: {e}")


def _status_rank(status: str) -> int:
    order = {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}
    return order.get(str(status or "").upper(), 4)


def _status_tone(status: str) -> str:
    return {
        "PASS": "good",
        "WATCH": "watch",
        "FAIL": "risk",
        "INSUFFICIENT": "neutral",
    }.get(str(status or "").upper(), "neutral")


def _ready_bool(value) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


def _run_scripts(payload) -> list[str]:
    if not isinstance(payload, dict):
        return []
    scripts = []
    for step in payload.get("steps") or []:
        if isinstance(step, str):
            scripts.append(step)
    for result in payload.get("results") or []:
        if isinstance(result, dict) and result.get("script"):
            scripts.append(str(result["script"]))
    return list(dict.fromkeys(scripts))


def _is_research_quality_run(payload) -> bool:
    scripts = set(_run_scripts(payload))
    return {
        "pipeline/14_factor_ic_report.py",
        "pipeline/15_signal_quality_gate.py",
        "pipeline/16_factor_weight_policy.py",
        "pipeline/17_factor_policy_backtest.py",
    }.issubset(scripts)


def _run_elapsed(payload) -> float | None:
    if not isinstance(payload, dict):
        return None
    total = 0.0
    seen = False
    for result in payload.get("results") or []:
        if not isinstance(result, dict):
            continue
        try:
            total += float(result.get("elapsed_sec"))
            seen = True
        except Exception:
            pass
    return total if seen else None


def _fmt_run_time(value):
    if value is None or pd.isna(value):
        return "—"
    ts = pd.Timestamp(value)
    if ts.tzinfo is not None:
        ts = ts.tz_convert("Asia/Seoul")
    return ts.strftime("%m-%d %H:%M")


def _render_research_job_monitor():
    try:
        runs = load_pipeline_runs(limit=30)
    except Exception:
        runs = pd.DataFrame()

    if runs.empty or "payload" not in runs.columns:
        _notice(
            "info",
            "Research job history 없음",
            "PostgreSQL pipeline_runs 기록이 쌓이면 마지막 자동 실행 상태가 여기에 표시됩니다.",
        )
        return

    mask = runs["payload"].apply(_is_research_quality_run)
    research_runs = runs[mask].copy()
    if research_runs.empty:
        _notice(
            "info",
            "Research-quality 실행 기록 없음",
            "`make research-quality` 또는 자동 스케줄이 실행되면 상태가 여기에 표시됩니다.",
        )
        return

    latest = research_runs.iloc[0]
    status_text = str(latest.get("status") or "unknown").upper()
    elapsed = _run_elapsed(latest.get("payload"))
    elapsed_text = f"{elapsed:.1f}s" if elapsed is not None else "—"
    age_hours = None
    try:
        finished_ts = pd.Timestamp(latest.get("finished_at"))
        now_ts = pd.Timestamp.now(tz=finished_ts.tz) if finished_ts.tzinfo else pd.Timestamp.utcnow()
        age_hours = (now_ts - finished_ts).total_seconds() / 3600.0
    except Exception:
        pass
    is_stale = age_hours is not None and age_hours > 84
    tone = "risk" if status_text == "FAILED" or is_stale else "info" if status_text == "SUCCESS" else "warning"
    stale_suffix = f" Age {age_hours:.1f}h." if age_hours is not None else ""
    health_text = "stale" if is_stale else "fresh"
    _notice(
        tone,
        f"Research-quality latest: {status_text} ({health_text})",
        f"Started {_fmt_run_time(latest.get('started_at'))}, finished {_fmt_run_time(latest.get('finished_at'))}, elapsed {elapsed_text}.{stale_suffix}",
    )

    recent = research_runs.head(8).copy()
    recent["started_at"] = recent["started_at"].apply(_fmt_run_time)
    recent["finished_at"] = recent["finished_at"].apply(_fmt_run_time)
    recent["elapsed_sec"] = recent["payload"].apply(_run_elapsed)
    recent["scripts"] = recent["payload"].apply(lambda payload: ", ".join(_run_scripts(payload)))
    st.dataframe(
        recent[["status", "runner", "started_at", "finished_at", "elapsed_sec", "scripts"]],
        width="stretch",
        hide_index=True,
        column_config={
            "status": _txt("Status", 90),
            "runner": _txt("Runner", 80),
            "started_at": _txt("Started", 100),
            "finished_at": _txt("Finished", 100),
            "elapsed_sec": st.column_config.NumberColumn("Elapsed", format="%.1fs", width=85),
            "scripts": _txt("Scripts", 420),
        },
    )


def _render_ops_tab():
    st.markdown("""<div class="info-banner">
        <span class="bi">🩺</span>
        <strong>Operations Health</strong>는 API 준비 상태, 핵심 데이터셋, 리서치 자동 실행,
        저장소 설정을 한 번에 확인하는 4단계 운영 점검판입니다.
    </div>""", unsafe_allow_html=True)

    col_refresh, _ = st.columns([1, 5])
    with col_refresh:
        if st.button("🔄 운영 상태 새로고침", key="refresh_ops_health"):
            load_ops_health.clear()
            load_data_quality.clear()

    payload = load_ops_health()
    status_text = str(payload.get("status") or "UNKNOWN").upper()
    tone = {"OK": "info", "DEGRADED": "warning", "FAIL": "risk", "UNAVAILABLE": "risk"}.get(status_text, "warning")
    base_url = payload.get("_base_url") or payload.get("_url") or "—"
    counts = payload.get("status_counts") or {}
    _notice(
        tone,
        f"운영 상태: {status_text}",
        f"API {base_url} · OK {counts.get('OK', 0)} · WARN {counts.get('WARN', 0)} · FAIL {counts.get('FAIL', 0)}",
    )

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Overall", status_text)
    c2.metric("OK", int(counts.get("OK", 0)))
    c3.metric("WARN", int(counts.get("WARN", 0)))
    c4.metric("FAIL", int(counts.get("FAIL", 0)))

    checks = pd.DataFrame(payload.get("checks") or [])
    if checks.empty:
        _notice("warning", "체크 결과 없음", "API가 운영 상태 상세를 반환하지 않았습니다.")
    else:
        checks["detail"] = checks["detail"].apply(lambda value: json.dumps(value, ensure_ascii=False) if isinstance(value, dict) else str(value))
        st.dataframe(
            checks[["status", "name", "message", "detail"]],
            width="stretch",
            hide_index=True,
            column_config={
                "status": _txt("Status", 80),
                "name": _txt("Check", 180),
                "message": _txt("Message", 360),
                "detail": _txt("Detail", 520),
            },
        )

    with st.expander("운영 헬스체크 JSON", expanded=False):
        st.json(payload)

    quality_payload = load_data_quality()
    quality_status = str(quality_payload.get("status") or "UNKNOWN").upper()
    quality_tone = {"OK": "info", "DEGRADED": "warning", "FAIL": "risk", "UNAVAILABLE": "risk"}.get(quality_status, "warning")
    quality_counts = quality_payload.get("status_counts") or {}
    _notice(
        quality_tone,
        f"데이터 품질: {quality_status}",
        f"OK {quality_counts.get('OK', 0)} · WARN {quality_counts.get('WARN', 0)} · FAIL {quality_counts.get('FAIL', 0)}",
    )

    datasets = pd.DataFrame([
        {
            "status": item.get("status"),
            "dataset": item.get("dataset"),
            "market": item.get("market") or "",
            "rows": item.get("rows", 0),
        }
        for item in quality_payload.get("datasets", [])
    ])
    if not datasets.empty:
        st.dataframe(
            datasets[["status", "dataset", "market", "rows"]],
            width="stretch",
            hide_index=True,
            column_config={
                "status": _txt("Status", 80),
                "dataset": _txt("Dataset", 240),
                "market": _txt("Market", 80),
                "rows": st.column_config.NumberColumn("Rows", width=90),
            },
        )

    quality_issues = []
    for dataset in quality_payload.get("datasets", []):
        for check in dataset.get("checks", []):
            if str(check.get("status") or "").upper() == "OK":
                continue
            quality_issues.append({
                "status": check.get("status"),
                "dataset": dataset.get("dataset"),
                "check": check.get("name"),
                "message": check.get("message"),
            })
    if quality_issues:
        st.dataframe(
            pd.DataFrame(quality_issues),
            width="stretch",
            hide_index=True,
            column_config={
                "status": _txt("Status", 80),
                "dataset": _txt("Dataset", 220),
                "check": _txt("Check", 200),
                "message": _txt("Message", 520),
            },
        )

    with st.expander("데이터 품질 JSON", expanded=False):
        st.json(quality_payload)


def _policy_rank(status: str) -> int:
    order = {"REVIEW": 0, "WATCH": 1, "HOLD": 2, "KEEP": 3}
    return order.get(str(status or "").upper(), 4)


def _render_factor_policy_section(market: str):
    try:
        policy = load_factor_weight_policy()
    except Exception as exc:
        st.error(f"Factor Weight Policy 로드 에러: {exc}")
        return

    if policy.empty:
        _notice(
            "info",
            "Factor_Weight_Policy 데이터가 없습니다",
            "`python pipeline/16_factor_weight_policy.py` 또는 `make factor-policy`를 실행하면 추천 정책이 표시됩니다.",
        )
        return

    if "Market" in policy.columns:
        scoped = policy[policy["Market"].isin([market, "GLOBAL"])].copy()
        if scoped.empty:
            scoped = policy.copy()
    else:
        scoped = policy.copy()

    if scoped.empty:
        _notice("info", f"{market} 정책 추천 없음", "품질 게이트가 쌓이면 자동으로 채워집니다.")
        return

    if "Policy_Status" in scoped.columns:
        scoped["_rank"] = scoped["Policy_Status"].map(_policy_rank)
    else:
        scoped["_rank"] = 4

    counts = scoped.get("Policy_Status", pd.Series(dtype=str)).value_counts().to_dict()
    ready_count = int(scoped.get("Production_Ready", pd.Series(dtype=str)).apply(_ready_bool).sum()) if "Production_Ready" in scoped.columns else 0
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("KEEP", int(counts.get("KEEP", 0)))
    c2.metric("WATCH", int(counts.get("WATCH", 0)))
    c3.metric("REVIEW", int(counts.get("REVIEW", 0)))
    c4.metric("Ready", f"{ready_count}/{len(scoped)}")

    worst = scoped.sort_values("_rank").iloc[0]
    worst_status = str(worst.get("Policy_Status") or "UNKNOWN").upper()
    tone = {"REVIEW": "risk", "WATCH": "warning", "HOLD": "info", "KEEP": "info"}.get(worst_status, "info")
    _notice(
        tone,
        f"가중치 정책 상태: {worst_status}",
        "이 표는 추천/감시용입니다. 실제 스코어러의 V/Q/M 가중치는 자동 변경하지 않습니다.",
    )
    if "Production_Ready" in scoped.columns and ready_count < len(scoped):
        _notice(
            "warning",
            "운영 적용 보류",
            "일부 정책은 PROXY_BACKFILL 기반 진단입니다. LIVE_DAILY 스냅샷이 충분히 쌓이기 전까지는 관찰 전용으로만 봅니다.",
        )

    display = scoped.drop(columns=["_rank"], errors="ignore")
    keep_cols = [
        "Market", "Factor", "Policy_Status", "Current_Action",
        "Adjustment_Bias", "Suggested_Multiplier", "Evidence_Status",
        "Primary_Horizon", "Mean_IC", "Positive_IC_Rate",
        "Snapshots", "Total_Observations", "Live_Snapshots", "Proxy_Snapshots",
        "Proxy_Ratio", "Evidence_Source", "Production_Ready", "Review_Note",
        "Generated",
    ]
    keep_cols = [col for col in keep_cols if col in display.columns]
    st.dataframe(
        display[keep_cols],
        width="stretch",
        hide_index=True,
        column_config={
            "Market": _txt("Market", 70),
            "Factor": _txt("Factor", 130),
            "Policy_Status": _txt("Policy", 95),
            "Current_Action": _txt("Action", 190),
            "Adjustment_Bias": st.column_config.NumberColumn("Bias", format="%.2f", width=75),
            "Suggested_Multiplier": st.column_config.NumberColumn("Suggested Weight", format="%.2f", width=125),
            "Evidence_Status": _txt("Evidence", 110),
            "Primary_Horizon": _txt("Horizon", 80),
            "Mean_IC": st.column_config.NumberColumn("Mean IC", format="%.4f", width=85),
            "Positive_IC_Rate": st.column_config.ProgressColumn("IC > 0", format="%.1f", min_value=0, max_value=1),
            "Snapshots": st.column_config.NumberColumn("Snapshots", width=90),
            "Total_Observations": st.column_config.NumberColumn("Obs", width=80),
            "Live_Snapshots": st.column_config.NumberColumn("Live", width=70),
            "Proxy_Snapshots": st.column_config.NumberColumn("Proxy", width=75),
            "Proxy_Ratio": st.column_config.ProgressColumn("Proxy Ratio", format="%.2f", min_value=0, max_value=1),
            "Evidence_Source": _txt("Source", 105),
            "Production_Ready": _txt("Ready", 80),
            "Review_Note": _txt("Review Note", 330),
            "Generated": _txt("Generated", 120),
        },
    )


def _policy_backtest_rank(status: str) -> int:
    order = {"WORSE": 0, "INSUFFICIENT": 1, "NEUTRAL": 2, "IMPROVED": 3}
    return order.get(str(status or "").upper(), 4)


def _render_factor_policy_backtest_section(market: str):
    try:
        result = load_factor_policy_backtest()
    except Exception as exc:
        st.error(f"Factor Policy Backtest 로드 에러: {exc}")
        return

    if result.empty:
        _notice(
            "info",
            "Factor_Policy_Backtest 데이터가 없습니다",
            "`python pipeline/17_factor_policy_backtest.py` 또는 `make policy-backtest`를 실행하면 비교 결과가 표시됩니다.",
        )
        return

    if "Market" in result.columns:
        scoped = result[result["Market"].isin([market, "GLOBAL"])].copy()
        if scoped.empty:
            scoped = result.copy()
    else:
        scoped = result.copy()

    if scoped.empty:
        _notice("info", f"{market} 정책 백테스트 없음", "aged snapshot이 쌓이면 자동으로 채워집니다.")
        return

    if "Status" in scoped.columns:
        scoped["_rank"] = scoped["Status"].map(_policy_backtest_rank)
    else:
        scoped["_rank"] = 4

    counts = scoped.get("Status", pd.Series(dtype=str)).value_counts().to_dict()
    ready_count = int(scoped.get("Production_Ready", pd.Series(dtype=str)).apply(_ready_bool).sum()) if "Production_Ready" in scoped.columns else 0
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Improved", int(counts.get("IMPROVED", 0)))
    c2.metric("Neutral", int(counts.get("NEUTRAL", 0)))
    c3.metric("Worse", int(counts.get("WORSE", 0)))
    c4.metric("Ready", f"{ready_count}/{len(scoped)}")

    worst = scoped.sort_values("_rank").iloc[0]
    status_text = str(worst.get("Status") or "UNKNOWN").upper()
    tone = {"WORSE": "risk", "INSUFFICIENT": "info", "NEUTRAL": "warning", "IMPROVED": "info"}.get(status_text, "info")
    _notice(
        tone,
        f"정책 백테스트 상태: {status_text}",
        str(worst.get("Reason") or "기존 V/Q/M 합성점수와 정책 조정 합성점수를 비교합니다."),
    )

    display = scoped.drop(columns=["_rank"], errors="ignore")
    keep_cols = [
        "Market", "Horizon", "Status", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source",
        "Production_Ready",
        "Base_Weighted_IC", "Policy_Weighted_IC", "IC_Delta",
        "Base_Top_Bottom_Spread", "Policy_Top_Bottom_Spread", "Spread_Delta",
        "Base_Hit_Rate", "Policy_Hit_Rate", "Turnover_Estimate",
        "Decision", "Generated",
    ]
    keep_cols = [col for col in keep_cols if col in display.columns]
    st.dataframe(
        display[keep_cols],
        width="stretch",
        hide_index=True,
        column_config={
            "Market": _txt("Market", 70),
            "Horizon": _txt("Horizon", 75),
            "Status": _txt("Status", 105),
            "Snapshots": st.column_config.NumberColumn("Snapshots", width=90),
            "Total_Observations": st.column_config.NumberColumn("Obs", width=80),
            "Live_Snapshots": st.column_config.NumberColumn("Live", width=70),
            "Proxy_Snapshots": st.column_config.NumberColumn("Proxy", width=75),
            "Proxy_Ratio": st.column_config.ProgressColumn("Proxy Ratio", format="%.2f", min_value=0, max_value=1),
            "Evidence_Source": _txt("Source", 105),
            "Production_Ready": _txt("Ready", 80),
            "Base_Weighted_IC": st.column_config.NumberColumn("Base IC", format="%.4f", width=90),
            "Policy_Weighted_IC": st.column_config.NumberColumn("Policy IC", format="%.4f", width=95),
            "IC_Delta": st.column_config.NumberColumn("Δ IC", format="%+.4f", width=80),
            "Base_Top_Bottom_Spread": st.column_config.NumberColumn("Base Spread", format="%.4f", width=105),
            "Policy_Top_Bottom_Spread": st.column_config.NumberColumn("Policy Spread", format="%.4f", width=115),
            "Spread_Delta": st.column_config.NumberColumn("Δ Spread", format="%+.4f", width=95),
            "Base_Hit_Rate": st.column_config.ProgressColumn("Base Hit", format="%.1f", min_value=0, max_value=1),
            "Policy_Hit_Rate": st.column_config.ProgressColumn("Policy Hit", format="%.1f", min_value=0, max_value=1),
            "Turnover_Estimate": st.column_config.ProgressColumn("Turnover", format="%.1f", min_value=0, max_value=1),
            "Decision": _txt("Decision", 140),
            "Generated": _txt("Generated", 120),
        },
    )


def _render_policy_adjusted_ranking_section(market: str):
    try:
        ranking, summary = load_policy_adjusted_rankings(market)
    except Exception as exc:
        st.error(f"Policy Adjusted Ranking 로드 에러: {exc}")
        return

    if ranking.empty:
        _notice(
            "info",
            "정책 조정 섀도 랭킹 없음",
            "`make policy-adjusted-rankings`를 실행하면 운영 랭킹을 바꾸지 않고 정책 적용 후보 변화만 따로 볼 수 있습니다.",
        )
        return

    summary_row = summary.iloc[0].to_dict() if not summary.empty else {}
    positive = int(summary_row.get("Positive_Movers") or (ranking["Rank_Change"] > 0).sum())
    negative = int(summary_row.get("Negative_Movers") or (ranking["Rank_Change"] < 0).sum())
    mean_abs = float(summary_row.get("Mean_Abs_Rank_Change") or ranking["Rank_Change"].abs().mean() or 0)
    top_up = str(summary_row.get("Top_Up_Ticker") or "")
    top_down = str(summary_row.get("Top_Down_Ticker") or "")
    evidence = str(summary_row.get("Evidence_Source") or ranking.iloc[0].get("Policy_Evidence_Source") or "").upper()
    ready = _ready_bool(summary_row.get("Production_Ready") or ranking.iloc[0].get("Policy_Production_Ready"))

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("상승 종목", positive)
    c2.metric("하락 종목", negative)
    c3.metric("평균 변화", f"{mean_abs:.1f}계단")
    c4.metric("운영 적용", "Ready" if ready else "Hold")

    _notice(
        "warning" if not ready else "info",
        f"정책 조정 섀도 랭킹 · {evidence or 'UNKNOWN'}",
        "이 표는 운영 랭킹을 바꾸지 않습니다. 프록시 정책을 적용했을 때 어느 종목이 올라가고 내려가는지 확인하는 관찰용 표입니다.",
    )

    if top_up or top_down:
        st.caption(f"가장 크게 상승: {top_up or '-'} · 가장 크게 하락: {top_down or '-'}")

    display = ranking.copy()
    keep_cols = [
        "Policy_Rank", "Base_Rank", "Rank_Change", "Ticker", "Name", "Sector",
        "Policy_Final_Score", "Base_Final_Score", "Score_Change",
        "Policy_Total_Score", "Base_Total_Score",
        "Value_Multiplier", "Quality_Multiplier", "Momentum_Multiplier",
        "Policy_Actions", "Policy_Evidence_Source", "Policy_Production_Ready",
        "Quality_Category", "Quality_Red_Flags", "Generated",
    ]
    keep_cols = [col for col in keep_cols if col in display.columns]
    st.dataframe(
        display[keep_cols].head(40),
        width="stretch",
        hide_index=True,
        column_config={
            "Policy_Rank": st.column_config.NumberColumn("Policy #", width=80),
            "Base_Rank": st.column_config.NumberColumn("Base #", width=75),
            "Rank_Change": st.column_config.NumberColumn("Δ Rank", format="%+d", width=80),
            "Ticker": _txt("Ticker", 100),
            "Name": _txt("Name", 180),
            "Sector": _txt("Sector", 130),
            "Policy_Final_Score": st.column_config.NumberColumn("Policy Score", format="%.4f", width=110),
            "Base_Final_Score": st.column_config.NumberColumn("Base Score", format="%.4f", width=105),
            "Score_Change": st.column_config.NumberColumn("Δ Score", format="%+.4f", width=95),
            "Policy_Total_Score": st.column_config.NumberColumn("Policy Total", format="%.4f", width=105),
            "Base_Total_Score": st.column_config.NumberColumn("Base Total", format="%.4f", width=100),
            "Value_Multiplier": st.column_config.NumberColumn("V x", format="%.2f", width=65),
            "Quality_Multiplier": st.column_config.NumberColumn("Q x", format="%.2f", width=65),
            "Momentum_Multiplier": st.column_config.NumberColumn("M x", format="%.2f", width=65),
            "Policy_Actions": _txt("Policy Actions", 310),
            "Policy_Evidence_Source": _txt("Source", 105),
            "Policy_Production_Ready": _txt("Ready", 80),
            "Quality_Category": _txt("Category", 130),
            "Quality_Red_Flags": _txt("Red Flags", 200),
            "Generated": _txt("Generated", 120),
        },
    )

    movers = display.copy()
    movers["Abs_Rank_Change"] = pd.to_numeric(movers.get("Rank_Change"), errors="coerce").abs()
    movers = movers.sort_values("Abs_Rank_Change", ascending=False, na_position="last").head(12)
    with st.expander("가장 크게 움직인 종목", expanded=False):
        st.dataframe(
            movers[[col for col in keep_cols if col in movers.columns]],
            width="stretch",
            hide_index=True,
        )


def _render_factor_remediation_section(market: str):
    try:
        plan = load_factor_remediation_plan()
    except Exception as exc:
        st.error(f"Factor Remediation Plan 로드 에러: {exc}")
        return

    if plan.empty:
        _notice(
            "info",
            "Factor_Remediation_Plan 데이터가 없습니다",
            "`python pipeline/18_factor_remediation_plan.py` 또는 `make remediation-plan`을 실행하면 우선순위가 표시됩니다.",
        )
        return

    if "Market" in plan.columns:
        scoped = plan[plan["Market"].isin([market, "GLOBAL"])].copy()
        if scoped.empty:
            scoped = plan.copy()
    else:
        scoped = plan.copy()

    if scoped.empty:
        _notice("info", f"{market} 개선 우선순위 없음", "품질 게이트와 정책 결과가 쌓이면 자동으로 채워집니다.")
        return

    if "Priority" in scoped.columns:
        scoped = scoped.sort_values("Priority")

    counts = scoped.get("Severity", pd.Series(dtype=str)).value_counts().to_dict()
    ready_count = int(scoped.get("Production_Ready", pd.Series(dtype=str)).apply(_ready_bool).sum()) if "Production_Ready" in scoped.columns else 0
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("High", int(counts.get("HIGH", 0) + counts.get("OBSERVE_HIGH", 0)))
    c2.metric("Medium", int(counts.get("MEDIUM", 0) + counts.get("OBSERVE_MEDIUM", 0)))
    c3.metric("Low Data", int(counts.get("LOW_DATA", 0)))
    c4.metric("Ready", f"{ready_count}/{len(scoped)}")

    top = scoped.iloc[0]
    severity = str(top.get("Severity") or "UNKNOWN").upper()
    tone = "risk" if "HIGH" in severity else "warning" if "MEDIUM" in severity else "info"
    _notice(
        tone,
        f"개선 1순위: {top.get('Factor', '')} · {severity}",
        str(top.get("Remediation_Action") or "팩터 개선 우선순위를 검토합니다."),
    )

    display = scoped.copy()
    keep_cols = [
        "Priority", "Market", "Factor", "Severity", "Worst_Status",
        "Worst_Horizon", "Mean_IC", "Positive_IC_Rate", "Top_Bottom_Spread",
        "Policy_Status", "Current_Action", "Policy_Backtest_Status", "IC_Delta",
        "Production_Ready", "Evidence_Source", "Root_Cause",
        "Remediation_Action", "Success_Criteria", "Review_Cadence", "Generated",
    ]
    keep_cols = [col for col in keep_cols if col in display.columns]
    st.dataframe(
        display[keep_cols],
        width="stretch",
        hide_index=True,
        column_config={
            "Priority": st.column_config.NumberColumn("#", width=55),
            "Market": _txt("Market", 70),
            "Factor": _txt("Factor", 135),
            "Severity": _txt("Severity", 120),
            "Worst_Status": _txt("Gate", 95),
            "Worst_Horizon": _txt("Horizon", 80),
            "Mean_IC": st.column_config.NumberColumn("Mean IC", format="%.4f", width=85),
            "Positive_IC_Rate": st.column_config.ProgressColumn("IC > 0", format="%.1f", min_value=0, max_value=1),
            "Top_Bottom_Spread": st.column_config.NumberColumn("Spread", format="%.4f", width=90),
            "Policy_Status": _txt("Policy", 95),
            "Current_Action": _txt("Action", 160),
            "Policy_Backtest_Status": _txt("Backtest", 105),
            "IC_Delta": st.column_config.NumberColumn("Δ IC", format="%+.4f", width=80),
            "Production_Ready": _txt("Ready", 80),
            "Evidence_Source": _txt("Source", 105),
            "Root_Cause": _txt("Root Cause", 360),
            "Remediation_Action": _txt("Next Action", 420),
            "Success_Criteria": _txt("Success Criteria", 300),
            "Review_Cadence": _txt("Cadence", 260),
            "Generated": _txt("Generated", 120),
        },
    )


def _render_signal_quality_tab(market: str):
    st.markdown("""<div class="info-banner">
        <span class="bi">🧭</span>
        <strong>Signal Quality Gates</strong>는 Factor IC 리포트를 투자 의사결정용 상태값으로 압축합니다.
        PASS는 유지, WATCH는 관찰, FAIL은 재검토, INSUFFICIENT는 아직 표본 부족을 뜻합니다.
    </div>""", unsafe_allow_html=True)

    col_refresh, _ = st.columns([1, 5])
    with col_refresh:
        if st.button("🔄 품질 게이트 새로고침", key=f"refresh_quality_{market}"):
            load_signal_quality_gates.clear()
            load_factor_weight_policy.clear()
            load_factor_policy_backtest.clear()
            load_factor_remediation_plan.clear()
            load_policy_adjusted_rankings.clear()
            load_pipeline_runs.clear()
            st.rerun()

    try:
        df = load_signal_quality_gates()
        if df.empty:
            _notice(
                "info",
                "Signal_Quality_Gates 데이터가 없습니다",
                "`python pipeline/15_signal_quality_gate.py` 또는 `make quality-gates`를 먼저 실행하세요.",
            )
            return

        if "Market" in df.columns:
            scoped = df[df["Market"].isin([market, "GLOBAL"])].copy()
            if scoped.empty:
                scoped = df.copy()
        else:
            scoped = df.copy()

        if scoped.empty:
            _notice("info", f"{market} 품질 게이트 없음", "IC 표본이 쌓이면 자동으로 채워집니다.")
            return

        scoped["_rank"] = scoped["Status"].map(_status_rank)
        worst_row = scoped.sort_values("_rank").iloc[0]
        overall = str(worst_row.get("Status") or "UNKNOWN").upper()
        status_counts = scoped["Status"].value_counts().to_dict() if "Status" in scoped.columns else {}

        pass_count = int(status_counts.get("PASS", 0))
        watch_count = int(status_counts.get("WATCH", 0))
        fail_count = int(status_counts.get("FAIL", 0))
        insufficient_count = int(status_counts.get("INSUFFICIENT", 0))

        ready_count = int(scoped.get("Production_Ready", pd.Series(dtype=str)).apply(_ready_bool).sum()) if "Production_Ready" in scoped.columns else 0

        c1, c2, c3, c4, c5 = st.columns(5)
        c1.metric("Overall Gate", overall)
        c2.metric("PASS", pass_count)
        c3.metric("WATCH / FAIL", f"{watch_count} / {fail_count}")
        c4.metric("Insufficient", insufficient_count)
        c5.metric("Ready", f"{ready_count}/{len(scoped)}")

        reason = str(worst_row.get("Reason") or "").strip()
        action = str(worst_row.get("Recommended_Action") or "").strip()
        notice_kind = {
            "FAIL": "risk",
            "WATCH": "warning",
            "INSUFFICIENT": "info",
            "PASS": "info",
        }.get(overall, "info")
        _notice(
            notice_kind,
            f"현재 품질 판단: {overall}",
            action or reason or "팩터 품질 게이트를 모니터링 중입니다.",
        )
        if "Production_Ready" in scoped.columns and ready_count < len(scoped):
            _notice(
                "warning",
                "운영 판단은 아직 관찰 단계",
                "현재 품질 게이트에는 백필 기반 표본이 포함되어 있습니다. LIVE_DAILY 표본이 충분히 쌓인 행만 운영 적용 후보로 봅니다.",
            )

        section_header("자동 실행 상태", "🕒")
        _render_research_job_monitor()

        section_header("개선 우선순위", "🛠")
        _render_factor_remediation_section(market)

        section_header("정책 조정 섀도 랭킹", "🧪")
        _render_policy_adjusted_ranking_section(market)

        section_header("팩터 가중치 정책", "⚖")
        _render_factor_policy_section(market)

        section_header("정책 백테스트", "📐")
        _render_factor_policy_backtest_section(market)

        chart_df = scoped.dropna(subset=["Mean_IC"]).copy() if "Mean_IC" in scoped.columns else pd.DataFrame()
        if not chart_df.empty:
            chart_df["Label"] = (
                chart_df["Factor"].astype(str).str.replace("_Score", "", regex=False)
                + " · "
                + chart_df["Horizon"].astype(str)
            )
            colors = {
                "PASS": "#34d399",
                "WATCH": "#fbbf24",
                "FAIL": "#f87171",
                "INSUFFICIENT": "#60a5fa",
            }
            fig = go.Figure(go.Bar(
                x=chart_df["Mean_IC"],
                y=chart_df["Label"],
                orientation="h",
                marker_color=[colors.get(str(s).upper(), "#64748b") for s in chart_df["Status"]],
                text=[f"{v:+.3f}" for v in chart_df["Mean_IC"]],
                textposition="outside",
                customdata=chart_df[["Status", "Positive_IC_Rate", "Mean_Top_Bottom_Spread"]],
                hovertemplate=(
                    "<b>%{y}</b><br>Status: %{customdata[0]}"
                    "<br>Mean IC: %{x:.4f}"
                    "<br>Positive IC Rate: %{customdata[1]:.2f}"
                    "<br>Top-Bottom Spread: %{customdata[2]:.4f}<extra></extra>"
                ),
            ))
            fig.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="Signal Gate Mean IC", font=dict(color="#e2e8f0", size=13)),
                height=max(300, 28 * len(chart_df)),
                xaxis=dict(zeroline=True, zerolinecolor="#64748b", gridcolor="#1f2937"),
                yaxis=dict(tickfont=dict(size=10, color="#94a3b8")),
            )
            st.plotly_chart(fig, width="stretch")

        section_header("품질 게이트 상세", "🧭")
        display = scoped.drop(columns=["_rank"], errors="ignore")
        keep_cols = [
            "Market", "Factor", "Horizon", "Status", "Mean_IC",
            "Positive_IC_Rate", "Mean_Top_Bottom_Spread", "Mean_Hit_Rate",
            "Snapshots", "Total_Observations", "Live_Snapshots", "Proxy_Snapshots",
            "Proxy_Ratio", "Evidence_Source", "Production_Ready", "Reason",
            "Recommended_Action", "Generated",
        ]
        keep_cols = [col for col in keep_cols if col in display.columns]
        st.dataframe(
            display[keep_cols],
            width="stretch",
            hide_index=True,
            column_config={
                "Market": _txt("Market", 70),
                "Factor": _txt("Factor", 130),
                "Horizon": _txt("Horizon", 75),
                "Status": _txt("Status", 105),
                "Mean_IC": st.column_config.NumberColumn("Mean IC", format="%.4f", width=85),
                "Positive_IC_Rate": st.column_config.ProgressColumn("IC > 0", format="%.1f", min_value=0, max_value=1),
                "Mean_Top_Bottom_Spread": st.column_config.NumberColumn("Top-Bottom", format="%.4f", width=105),
                "Mean_Hit_Rate": st.column_config.ProgressColumn("Hit Rate", format="%.1f", min_value=0, max_value=1),
                "Snapshots": st.column_config.NumberColumn("Snapshots", width=90),
                "Total_Observations": st.column_config.NumberColumn("Obs", width=80),
                "Live_Snapshots": st.column_config.NumberColumn("Live", width=70),
                "Proxy_Snapshots": st.column_config.NumberColumn("Proxy", width=75),
                "Proxy_Ratio": st.column_config.ProgressColumn("Proxy Ratio", format="%.2f", min_value=0, max_value=1),
                "Evidence_Source": _txt("Source", 105),
                "Production_Ready": _txt("Ready", 80),
                "Reason": _txt("Reason", 280),
                "Recommended_Action": _txt("Action", 320),
                "Generated": _txt("Generated", 120),
            },
        )

        with st.expander("품질 게이트 기준", expanded=False):
            st.markdown("""
**현재 기준**

- 최소 aged snapshot: `3`
- 최소 forward-return 관측치: `60`
- PASS: `Mean_IC >= 0.03`, `Positive_IC_Rate >= 0.55`, Top-Bottom spread가 음수가 아님
- WATCH: `Mean_IC >= 0.00`, `Positive_IC_Rate >= 0.45`
- FAIL: 충분한 표본이 있는데 IC가 음수이거나 불안정
- INSUFFICIENT: 아직 판단할 만큼 시간이 지나지 않음

이 게이트는 아직 포트폴리오 가중치를 자동 변경하지 않습니다. 먼저 관찰하고, 충분한 IC 기록이 쌓인 뒤 팩터 가중치 조정에 사용합니다.
            """)
    except Exception as e:
        st.error(f"Signal Quality 로드 에러: {e}")


def _render_research_tab(market: str):
    tab_quality, tab_attr, tab_ic = st.tabs(["Signal Quality", "Factor Attribution", "Factor IC"])
    with tab_quality:
        _render_signal_quality_tab(market)
    with tab_attr:
        _render_attribution_tab(market)
    with tab_ic:
        _render_factor_ic_tab(market)


def _render_risk_tab(market: str):
    tab_drift, tab_corr = st.tabs(["Rebalance Drift", "Correlation"])
    with tab_drift:
        _render_drift_tab(market)
    with tab_corr:
        _render_correlation_tab(market)


def _render_earnings_tab(market: str):
    is_us = market == 'US'
    lookback_desc = '최근 21일 내 발표, EPS 서프라이즈 > 5% 기준' if is_us else '최근 45일 내 발표, 서프라이즈 > 5% (또는 YoY 순이익 > 20%) 기준'
    banner = (
        '최근 <strong>EPS 서프라이즈(+5% 이상)</strong> 발표 후 '
        '<strong>Post-Earnings Announcement Drift (PEAD)</strong> 신호가 강한 US 종목입니다. '
        'Signal_Strength = 서프라이즈 크기(50%) + 신선도(30%) + 발표 후 주가 드리프트(20%)'
        if is_us else
        '최근 <strong>EPS 서프라이즈(yfinance) 또는 YoY 순이익 성장(Naver 폴백)</strong> 기반 '
        '<strong>PEAD 신호</strong>가 강한 KR 종목입니다. (최근 45일 내 발표) '
        'Signal_Strength = 서프라이즈 크기(50%) + 신선도(30%) + 발표 후 주가 드리프트(20%)'
    )
    lbl = (
        dict(Name='Name', Sector='Sector', EarningsDate='Earnings Date', DaysAgo='Days Ago',
             Surprise='EPS Surprise', RetSince='Ret Since', VolSurge='Vol Surge',
             ticker_w=85, name_w=175)
        if is_us else
        dict(Name='종목명', Sector='섹터', EarningsDate='실적발표일', DaysAgo='경과일',
             Surprise='EPS 서프라이즈', RetSince='발표후 수익', VolSurge='거래량 서지',
             ticker_w=95, name_w=175)
    )

    st.markdown(
        f'<div class="info-banner"><span class="bi">📰</span> {banner}</div>',
        unsafe_allow_html=True,
    )
    try:
        earn = load_earnings_momentum(market)
        if earn.empty:
            sheet_name = 'US_Earnings_Momentum' if is_us else 'KR_Earnings_Momentum'
            script     = ('pipeline/10a_earnings_surprise_us.py' if is_us
                          else 'pipeline/10b_earnings_surprise_kr.py')
            st.warning(f"{sheet_name} 데이터가 없습니다.")
            st.info(f"💡 `python {script}` 를 먼저 실행하세요.")
            return

        n        = len(earn)
        avg_surp = earn['Surprise_Pct'].dropna().mean()  if 'Surprise_Pct'   in earn.columns else None
        avg_ret  = earn['Return_Since'].dropna().mean()  if 'Return_Since'   in earn.columns else None
        avg_sig  = earn['Signal_Strength'].dropna().mean() if 'Signal_Strength' in earn.columns else None
        updated  = earn['Last_Updated'].dropna().iloc[0] if 'Last_Updated' in earn.columns and not earn['Last_Updated'].dropna().empty else '—'

        c1, c2, c3, c4 = st.columns(4)
        c1.metric("PEAD 후보 종목",    f"{n}개")
        c2.metric("평균 EPS 서프라이즈", f"{avg_surp*100:+.1f}%" if avg_surp is not None else "—")
        c3.metric("발표 후 평균 수익률", f"{avg_ret*100:+.1f}%"  if avg_ret  is not None else "—")
        c4.metric("평균 신호 강도",     f"{avg_sig:.3f}"         if avg_sig  is not None else "—")
        st.caption(f"업데이트: {updated}  ·  {lookback_desc}")

        section_header("종목별 신호 강도", "📊")
        render_earnings_bar_chart(earn)

        section_header(f"종목 상세 ({n}개)", "📋")
        st.caption("💡 행을 클릭하면 기업 상세 정보를 볼 수 있습니다.")

        base_cols = ['Rank', 'Ticker', 'Name', 'Sector', 'Earnings_Date',
                     'Days_Since_Earnings', 'Surprise_Pct', 'Return_Since',
                     'Volume_Surge', 'Signal_Strength']
        if not is_us:
            base_cols.append('Data_Source')
        disp_cols = [c for c in base_cols if c in earn.columns]

        col_cfg = {
            "Rank":                _txt("Rank", 55),
            "Ticker":              _txt("Ticker", lbl['ticker_w']),
            "Name":                _txt(lbl['Name'], lbl['name_w']),
            "Sector":              _txt(lbl['Sector'], 135),
            "Earnings_Date":       _txt(lbl['EarningsDate'], 110),
            "Days_Since_Earnings": _txt(lbl['DaysAgo'], 75 if is_us else 70),
            "Surprise_Pct":        _txt(lbl['Surprise'], 100 if is_us else 105),
            "Return_Since":        _txt(lbl['RetSince'], 85),
            "Volume_Surge":        _txt(lbl['VolSurge'], 85 if is_us else 90),
            "Signal_Strength":     _progress_col("Signal" if is_us else "신호 강도", "%.3f", 1.0),
        }
        if not is_us:
            col_cfg["Data_Source"] = _txt("데이터 소스", 100)

        evt = st.dataframe(
            earn[disp_cols],
            width="stretch", hide_index=True,
            on_select="rerun", selection_mode="single-row",
            column_config=col_cfg,
        )
        if evt.selection.rows:
            row = earn.iloc[evt.selection.rows[0]]
            currency = 'USD' if is_us else 'KRW'
            st.session_state['_dlg'] = (row['Ticker'], row.get('Name', row['Ticker']), currency, row.to_dict())
    except Exception as e:
        st.error(f"{'US' if is_us else 'KR'} Earnings PEAD 로드 에러: {e}")


# ═══════════════════════════════════════════════════════════════════════════════
# 7. Tab layout and dispatch
# ═══════════════════════════════════════════════════════════════════════════════
_MARKET = st.session_state.market

if _MARKET == 'US':
    (tab_port, tab_sc, tab_bt, tab_industry,
     tab_risk, tab_research, tab_earn, tab_ops) = st.tabs([
        "🇺🇸  US Portfolio",
        "🇺🇸  US SmallCap Gems",
        "📈  US Backtest",
        "🏭  US Industry",
        "🛡️  Risk",
        "🧪  Research",
        "📰  Earnings PEAD",
        "🩺  Ops",
    ])
    with tab_port:     _render_portfolio_tab('US')
    with tab_sc:       _render_smallcap_tab('US')
    with tab_bt:       _render_backtest_tab('US')
    with tab_risk:     _render_risk_tab('US')
    with tab_research: _render_research_tab('US')
    with tab_earn:     _render_earnings_tab('US')
    with tab_ops:      _render_ops_tab()

    # US-only: Industry Ranking
    with tab_industry:
        st.markdown("""<div class="info-banner">
            <span class="bi">🏭</span>
            <strong>Bottom-up US 업종 강도 랭킹</strong>입니다.
            개별 종목의 실제 주가 흐름을 집계해 업종별 <strong>평균 수익률</strong>과
            <strong>Breadth(상승 비율)</strong>로 강도를 측정합니다.
            🟢 표시는 현재 포트폴리오 종목이 속한 섹터입니다.
        </div>""", unsafe_allow_html=True)
        try:
            ind_df = load_industry_ranking()
            port_sectors: set = set()
            try:
                _, us_port_raw = load_portfolio_sheet("US_Final_Portfolio")
                if not us_port_raw.empty and 'Sector' in us_port_raw.columns:
                    port_sectors = set(us_port_raw['Sector'].dropna().unique())
            except Exception:
                pass

            if ind_df.empty:
                st.warning("US_Industry_Ranking 데이터가 없습니다.")
                st.info("💡 `python pipeline/09_industry_ranking.py` 를 먼저 실행하세요.")
            else:
                lookback = int(ind_df['Lookback_Days'].dropna().iloc[0]) if 'Lookback_Days' in ind_df.columns and not ind_df['Lookback_Days'].dropna().empty else '?'
                updated  = ind_df['Last_Updated'].dropna().iloc[0] if 'Last_Updated' in ind_df.columns and not ind_df['Last_Updated'].dropna().empty else '—'
                n_ind    = len(ind_df)
                n_port_sector = len(port_sectors & set(ind_df['Sector'].dropna().unique())) if 'Sector' in ind_df.columns else 0
                st.caption(
                    f"업종 수: **{n_ind}**  ·  분석 기간: **{lookback}일**  ·  "
                    f"포트폴리오 섹터 매칭: **{n_port_sector}개**  ·  업데이트: {updated}"
                )
                section_header("업종 강도 차트", "📊")
                render_industry_ranking(ind_df, portfolio_sectors=port_sectors)
                section_header("업종 랭킹 상세", "📋")
                render_industry_table(ind_df, portfolio_sectors=port_sectors)
                with st.expander("🔬 방법론 상세", expanded=False):
                    st.markdown("""
**Bottom-up 업종 강도 산출 방법**

1. **Universe**: `US_Scored_Stocks` — Factor Scorer를 통과한 종목 전체
2. **Industry 레이블**: `yfinance ticker.info['industry']` — 세분화된 업종 분류 (섹터보다 세밀)
3. **Mean Return**: 업종 내 종목들의 평균 수익률 (Lookback 기간)
4. **Breadth**: 양수 수익률 종목 비율 (Win Rate)
5. **Combined Rank** = Mean_Return_Rank + Breadth_Rank (낮을수록 강한 업종)

> 이 접근은 top-down 매크로 콜 대신 **실제 주가 흐름으로부터 업종 강도를 역산**합니다.
                    """)
        except Exception as e:
            st.error(f"US Industry Ranking 로드 에러: {e}")

elif _MARKET == 'KR':
    (tab_port, tab_sc, tab_bt, tab_flow,
     tab_risk, tab_research, tab_earn, tab_ops) = st.tabs([
        "🇰🇷  KR Portfolio",
        "🇰🇷  KR SmallCap Gems",
        "📈  KR Backtest",
        "🇰🇷  KR Order Flow",
        "🛡️  Risk",
        "🧪  Research",
        "📰  Earnings PEAD",
        "🩺  Ops",
    ])
    with tab_port:  _render_portfolio_tab('KR')
    with tab_sc:    _render_smallcap_tab('KR')
    with tab_bt:    _render_backtest_tab('KR')
    with tab_risk:  _render_risk_tab('KR')
    with tab_research: _render_research_tab('KR')
    with tab_earn:  _render_earnings_tab('KR')
    with tab_ops:   _render_ops_tab()

    # KR-only: Order Flow
    with tab_flow:
        st.markdown("""<div class="info-banner">
            <span class="bi">🇰🇷</span>
            <strong>외국인 + 기관 동시 순매수</strong> 종목을 추적합니다.
            두 주체가 동시에 순매수하는 종목은 스마트머니의 집중 신호로 해석됩니다.
            🟢 표시는 <strong>KR 포트폴리오 종목</strong>과 겹치는 이중 확인 신호입니다.
        </div>""", unsafe_allow_html=True)
        try:
            of_df = load_kr_order_flow()
            kr_port_tickers: set = set()
            try:
                _, kr_port_raw = load_portfolio_sheet("KR_Final_Portfolio")
                if not kr_port_raw.empty and 'Ticker' in kr_port_raw.columns:
                    kr_port_tickers = set(kr_port_raw['Ticker'].str.strip())
            except Exception:
                pass

            if of_df.empty:
                st.warning("KR_Dual_Net_Buyers 데이터가 없습니다.")
                st.info("💡 `python pipeline/11_kr_order_flow.py` 를 먼저 실행하세요.")
            else:
                n_total   = len(of_df)
                n_overlap = len(of_df[of_df['Ticker'].isin(kr_port_tickers)])
                updated   = of_df['Last_Updated'].dropna().iloc[0] if 'Last_Updated' in of_df.columns and not of_df['Last_Updated'].dropna().empty else '—'
                min_days  = int(of_df['Consecutive_Days'].dropna().min()) if 'Consecutive_Days' in of_df.columns else '?'

                c1, c2, c3 = st.columns(3)
                c1.metric("감지 종목 수",   f"{n_total}개")
                c2.metric("포트폴리오 겹침", f"{n_overlap}개",
                          help="KR_Final_Portfolio와 동시에 포함된 종목 — 이중 확인 신호")
                c3.metric("최소 연속 일수",  f"{min_days}일",
                          help="외국인 + 기관 동시 순매수가 지속된 최소 기간")

                section_header("순매수 강도 차트", "📊")
                render_order_flow_bar(of_df, portfolio_tickers=kr_port_tickers)
                section_header("종목 상세", "📋")
                render_order_flow_table(of_df, portfolio_tickers=kr_port_tickers)

                with st.expander("🔬 스캐너 방법론", expanded=False):
                    st.markdown("""
**KR 연속 외국인 + 기관 동시 순매수 스캐너**

- **데이터 소스**: Naver Finance 투자자별 동향 (`finance.naver.com/item/frgn.naver`)
- **스캔 기준**: 최근 5영업일 중 **3일 이상 외국인과 기관이 동시 순매수**
- **이중 확인 신호 (🟢)**: KR_Final_Portfolio 종목과 겹치면 Factor Score + Order Flow 양쪽에서 확인된 종목
                    """)
        except Exception as e:
            st.error(f"KR Order Flow 로드 에러: {e}")


# ═══════════════════════════════════════════════════════════════════════════════
# Search — Company Search (top-level, market-agnostic)
# ═══════════════════════════════════════════════════════════════════════════════
if _MARKET == 'SEARCH':
    st.markdown("""<div class="info-banner">
        <span class="bi">🔍</span>
        <strong>US + KR 전체 유니버스</strong>에서 티커 또는 기업명으로 검색합니다.
        행을 클릭하면 주가 차트와 기업 상세 정보를 확인할 수 있습니다.
    </div>""", unsafe_allow_html=True)

    query = st.text_input(
        "Search universe",
        placeholder="Ticker or company name — e.g. AAPL, Samsung, NVDA, 삼성...",
        key="_search_input",
        label_visibility="collapsed",
    )

    _portfolio_set: set = set()
    _gem_set:       set = set()

    def _badge(ticker):
        parts = []
        if ticker in _portfolio_set:
            parts.append("📈 Portfolio")
        if ticker in _gem_set:
            parts.append("💎 Gem")
        return "  ".join(parts) if parts else "—"

    def _search_logo(ticker, market):
        if str(market).upper() == 'KR':
            code = ticker.split('.')[0]
            if code == '064400':
                return "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png"
            if code == '267250':
                return f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png"
            return f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png"
        return f"https://financialmodelingprep.com/image-stock/{ticker.upper()}.png"

    def _fmt_mc(row):
        mc = row.get('MarketCap')
        if not pd.notna(mc) or mc <= 0:
            return "—"
        if str(row.get('Market', '')).upper() == 'KR':
            eok = round(mc / 1e8)
            jo, rem = divmod(eok, 10000)
            if jo > 0 and rem > 0:
                return f"₩{jo}조 {rem:,}억"
            elif jo > 0:
                return f"₩{jo}조"
            return f"₩{rem:,}억"
        return f"${mc / 1e9:.1f}B" if mc >= 1e9 else f"${mc / 1e6:.0f}M"

    def _render_search_table(df, key_suffix):
        df = df.copy()
        df['Status']  = df['Ticker'].apply(_badge)
        df['Logo']    = df.apply(lambda r: _search_logo(r['Ticker'], r.get('Market', '')), axis=1)
        df['Mkt Cap'] = df.apply(_fmt_mc, axis=1)
        display_cols  = ['Rank', 'Logo', 'Ticker', 'Name', 'Sector', 'Mkt Cap', 'Status']
        for col in display_cols:
            if col not in df.columns:
                df[col] = '—'
        evt = st.dataframe(
            df[display_cols],
            width="stretch", hide_index=True,
            on_select="rerun", selection_mode="single-row",
            key=f"_search_tbl_{key_suffix}",
            column_config={
                "Rank":    _txt("#", 40),
                "Logo":    _logo_col(),
                "Ticker":  _txt("Ticker", 90),
                "Name":    _txt("Name", 180),
                "Sector":  _txt("Sector", 130),
                "Mkt Cap": _txt("Mkt Cap", 100),
                "Status":  _txt("Status", 120),
            },
        )
        if evt.selection.rows:
            row = df.iloc[evt.selection.rows[0]]
            cur = 'KRW' if str(row.get('Market', '')).upper() == 'KR' else 'USD'
            st.session_state['_dlg'] = (row['Ticker'], row.get('Name', row['Ticker']), cur, row.to_dict())

    try:
        universe_df, portfolio_set, gem_set = load_search_universe()
        _portfolio_set.update(portfolio_set)
        _gem_set.update(gem_set)

        if universe_df.empty:
            st.warning("Universe data not available. Run the pipeline first.")
        else:
            q = query.strip()
            if q:
                q_up  = q.upper()
                q_low = q.lower()
                tickers = universe_df['Ticker'].str.upper()
                names   = universe_df['Name'].str.lower().fillna('')

                mask_exact    = tickers == q_up
                mask_t_starts = tickers.str.startswith(q_up)
                mask_n_starts = names.str.startswith(q_low)
                mask_t_has    = tickers.str.contains(q_up,  regex=False)
                mask_n_has    = names.str.contains(q_low, regex=False, na=False)

                mask   = mask_exact | mask_t_starts | mask_n_starts | mask_t_has | mask_n_has
                result = universe_df[mask].copy()

                priority = pd.Series(4, index=result.index)
                priority[mask_n_has[mask]]    = 4
                priority[mask_t_has[mask]]    = 3
                priority[mask_n_starts[mask]] = 2
                priority[mask_t_starts[mask]] = 1
                priority[mask_exact[mask]]    = 0
                result['_pri'] = priority
                result = result.sort_values('_pri').drop(columns=['_pri']).reset_index(drop=True)
                result['Rank'] = result.index + 1

                st.caption(f"{len(result)} result(s)")
                _render_search_table(result, key_suffix="search")
            else:
                kr_df = (universe_df[universe_df['Market'].str.upper() == 'KR']
                         .sort_values('MarketCap', ascending=False).head(50).reset_index(drop=True))
                kr_df['Rank'] = kr_df.index + 1
                us_df = (universe_df[universe_df['Market'].str.upper() == 'US']
                         .sort_values('MarketCap', ascending=False).head(50).reset_index(drop=True))
                us_df['Rank'] = us_df.index + 1

                col_kr, col_us = st.columns(2)
                with col_kr:
                    st.markdown("#### 🇰🇷 KR 시총 순위")
                    st.caption(f"{len(kr_df)}개 종목")
                    _render_search_table(kr_df, key_suffix="kr_default")
                with col_us:
                    st.markdown("#### 🇺🇸 US 시총 순위")
                    st.caption(f"{len(us_df)}개 종목")
                    _render_search_table(us_df, key_suffix="us_default")
    except Exception as e:
        st.error(f"Search error: {e}")


# ═══════════════════════════════════════════════════════════════════════════════
# Dialog triggers
# ═══════════════════════════════════════════════════════════════════════════════
_tick_qp = st.query_params.get("_tick", "")
if _tick_qp and "_dlg" not in st.session_state:
    _tick_name = next((n for t, n in NASDAQ_TOP20 if t == _tick_qp), _tick_qp)
    st.session_state["_dlg"] = (_tick_qp, _tick_name, "USD")
    del st.query_params["_tick"]

if '_dlg' in st.session_state:
    _dlg = st.session_state.pop('_dlg')
    ticker, name, currency = _dlg[0], _dlg[1], _dlg[2]
    row_data = _dlg[3] if len(_dlg) > 3 else None
    show_stock_dialog(ticker, name, currency, row_data=row_data)
elif '_strategy_dlg' in st.session_state:
    show_strategy_dialog(st.session_state.pop('_strategy_dlg'))
