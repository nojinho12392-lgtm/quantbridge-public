from __future__ import annotations

import streamlit as st
import pandas as pd
import plotly.graph_objects as go
from datetime import datetime
import numpy as np

from data_loader import (
    _logo_url, _fmt_krw, render_portfolio_table, render_smallcap_table,
    fetch_stock_detail, fetch_earnings_data, load_scored_stocks,
    load_portfolio_sheet, load_drift_alert, load_earnings_momentum, load_kr_order_flow,
)
from ui_components import PLOTLY_LAYOUT, MA_LINES


# ── Risk Parity explanation ────────────────────────────────────────────────────
_RISK_PARITY_EXPLANATION = {
    "concept": (
        "**Risk Parity(리스크 패리티)** 는 각 종목이 포트폴리오 전체 리스크에 "
        "**동등하게 기여**하도록 비중을 배분하는 최적화 전략입니다. "
        "시가총액 비중(MV)이나 동일 비중(EW)과 달리, "
        "변동성이 낮은 종목에는 더 많은 비중을, 변동성이 높은 종목에는 더 적은 비중을 배분합니다."
    ),
    "why": [
        ("분산 효과 극대화", "단일 종목·섹터의 변동성이 포트폴리오 전체를 지배하지 않도록 방지"),
        ("하락장 방어",      "고변동성 종목의 비중을 자동으로 줄여 드로우다운을 억제"),
        ("일관된 리스크 예산", "시장 상황과 무관하게 각 종목의 리스크 기여도를 균등하게 유지"),
    ],
    "math": (
        "목표: 모든 종목 i에 대해  **w_i × (Σw)_i = 1/N × σ_p²** 가 되도록 비중 w를 최적화  \n"
        "제약: Σwᵢ = 1,  wᵢ ∈ [1%, 15%]  \n"
        "풀이: SLSQP (Sequential Least Squares Programming)"
    ),
}

_STRATEGY_DETAIL = {
    "Risk Parity + Factor/ML Scoring": {
        "description": "S&P 500 + NASDAQ-100 유니버스에서 Factor Score와 ML Score를 결합해 상위 30종목을 선정한 뒤, Risk-Parity로 비중을 최적화합니다.",
        "steps": [
            ("① Universe", "S&P 500 + NASDAQ-100 전 종목"),
            ("② Factor Scoring", "Value(40%) · Quality(30%) · Momentum(30%) 팩터 점수 산출"),
            ("③ ML Model", "RandomForest로 3개월 선행 수익률 예측 → ML_Score 생성"),
            ("④ Combined Score", "0.5 × ML_Score + 0.5 × Factor_Score → 상위 30종목 선정"),
            ("⑤ Optimisation", "SLSQP 최적화 · 최소 1% · 최대 15% 비중 제한"),
        ],
        "factors": {
            "Value (40%)":    "PEG↓  EV/EBITDA↓  PSR↓",
            "Quality (30%)":  "ROIC↑  FCF/NI↑  ROE↑  InterestCoverage↑",
            "Momentum (30%)": "Mom_12M–1M↑  Mom_3M↑  EPS_Growth↑",
        }
    },
    "Risk Parity + Factor Scoring": {
        "description": "KOSPI300 + KOSDAQ200 유니버스에서 Factor Score 상위 30종목을 선정한 뒤, Risk-Parity로 비중을 최적화합니다.",
        "steps": [
            ("① Universe", "KOSPI300 + KOSDAQ200 전 종목"),
            ("② Factor Scoring", "Value(40%) · Quality(35%) · Momentum(25%) 팩터 점수 산출"),
            ("③ Top 30 선정", "Total_Score 기준 상위 30종목 선정"),
            ("④ Optimisation", "SLSQP 최적화 · 최소 1% · 최대 15% 비중 제한"),
        ],
        "factors": {
            "Value (40%)":    "PER↓  PBR↓",
            "Quality (35%)":  "ROE↑  OperatingMargin↑  DebtToEquity↓",
            "Momentum (25%)": "Mom_12M–1M↑  Mom_3M↑",
        }
    },
}

# ── Factor Score Radar Chart ───────────────────────────────────────────────────
def _render_factor_radar(ticker: str, scored_df: pd.DataFrame):
    """
    Hexagon radar chart with 6 axes:
      Value / Quality / Momentum  — normalised from score columns (0→100)
      ROIC / Growth / FCF         — percentile rank within the universe (0→100)
    Silently returns if ticker not found.
    """
    if scored_df.empty:
        return
    row = scored_df[scored_df['Ticker'] == ticker]
    if row.empty:
        return
    row = row.iloc[0]

    def _norm(col, max_w):
        """Score column → 0-100 by dividing by its theoretical maximum weight."""
        v = row.get(col)
        try:
            return round(min(float(v) / max_w * 100, 100), 1)
        except (TypeError, ValueError, ZeroDivisionError):
            return 0.0

    def _pct_rank(col, inverse=False):
        """Percentile rank of this ticker within the universe → 0-100.
        inverse=True means lower values are better (e.g. Debt/EBITDA)."""
        try:
            series = pd.to_numeric(scored_df[col], errors='coerce').dropna()
            if series.empty:
                return 0.0
            v   = float(row.get(col) or 0)
            pct = (series < v).sum() / len(series) * 100
            return round(100 - pct if inverse else pct, 1)
        except Exception:
            return 0.0

    # ── 6 axes ────────────────────────────────────────────────────────────────
    categories = ['Value', 'Quality', 'Momentum', 'ROIC', 'Growth', 'FCF']
    labels_kr  = ['밸류', '퀄리티', '모멘텀', 'ROIC', '성장성', '현금창출']
    values = [
        _norm('Value_Score',    0.40),   # Value    — score normalised by max weight
        _norm('Quality_Score',  0.30),   # Quality
        _norm('Momentum_Score', 0.30),   # Momentum
        _pct_rank('ROIC'),               # ROIC     — percentile in universe
        _pct_rank('RevGrowth'),          # Growth
        _pct_rank('FCF_Margin'),         # FCF Margin
    ]

    # Close the polygon
    r_vals = values + [values[0]]
    theta  = labels_kr + [labels_kr[0]]

    # Colour the fill green if Total_Score is above median, else blue
    try:
        median_score = pd.to_numeric(
            scored_df['Total_Score'], errors='coerce'
        ).median()
        above_median = float(row.get('Total_Score') or 0) >= median_score
    except Exception:
        above_median = True
    fill_color = 'rgba(74,222,128,0.15)'  if above_median else 'rgba(99,179,237,0.12)'
    line_color = '#4ade80'                if above_median else '#63b3ed'

    fig = go.Figure(go.Scatterpolar(
        r=r_vals,
        theta=theta,
        fill='toself',
        fillcolor=fill_color,
        line=dict(color=line_color, width=2),
        marker=dict(size=5, color=line_color),
        hovertemplate='<b>%{theta}</b>: %{r:.1f} / 100<extra></extra>',
    ))
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'height': 300,
        'polar': dict(
            bgcolor='#0d1424',
            radialaxis=dict(
                range=[0, 100],
                tickvals=[25, 50, 75, 100],
                tickfont=dict(size=7, color='#4b5563'),
                gridcolor='#1e293b',
                linecolor='#1e293b',
            ),
            angularaxis=dict(
                tickfont=dict(size=11, color='#94a3b8'),
                gridcolor='#1e293b',
                linecolor='#1e293b',
            ),
        ),
        'title': dict(text='Factor Radar (6축)', font=dict(color='#e2e8f0', size=13)),
        'margin': dict(l=44, r=44, t=44, b=16),
    })

    rank     = row.get('Rank')
    rank_str = f"Rank #{int(rank)}" if pd.notna(rank) else ""
    st.plotly_chart(fig, width="stretch")
    # Caption: show all 6 axis values
    st.caption(
        f"밸류 {values[0]:.0f}  ·  퀄리티 {values[1]:.0f}  ·  모멘텀 {values[2]:.0f}"
        f"  ·  ROIC {values[3]:.0f}  ·  성장성 {values[4]:.0f}  ·  현금창출 {values[5]:.0f}"
        + (f"  ·  {rank_str}" if rank_str else "")
    )


# ── Generic Fallback Radar (SmallCap / Universe tickers not in scored_df) ──────
def _render_generic_radar(row_dict: dict, ref_df: pd.DataFrame, title: str = "Factor Radar"):
    """
    6-axis hexagon radar built from raw metric columns.
    Each value is percentile-ranked within ref_df when the column is present,
    otherwise falls back to heuristic absolute scaling.

    Axes:
      수익성   — ROIC or ROE
      성장성   — RevGrowth or RevenueGrowth
      현금창출 — FCF_Margin or OperatingMargin
      밸류에이션— PEG or PBR  (inverse: lower = better)
      마진     — GrossMargin
      재무건전성— Debt_EBITDA or DebtToEquity (inverse: lower = better)
    """
    def _get(*keys):
        for k in keys:
            v = row_dict.get(k)
            if v not in (None, '', 'nan'):
                try:
                    f = float(v)
                    if not pd.isna(f):
                        return f
                except (TypeError, ValueError):
                    pass
        return None

    def _pct(val, cols, inverse=False, hmax=None):
        if val is None:
            return 0.0
        if ref_df is not None:
            for col in cols:
                if col in ref_df.columns:
                    series = pd.to_numeric(ref_df[col], errors='coerce').dropna()
                    if not series.empty:
                        pct = (series < val).sum() / len(series) * 100
                        return round(100 - pct if inverse else pct, 1)
        # Heuristic absolute scaling
        if hmax and hmax > 0:
            raw = min(max(val / hmax, 0.0), 1.0) * 100
            return round(100 - raw if inverse else raw, 1)
        return 0.0

    roic     = _get('ROIC', 'ROE')
    growth   = _get('RevGrowth', 'RevenueGrowth')
    fcf      = _get('FCF_Margin', 'OperatingMargin')
    peg      = _get('PEG', 'PBR')
    margin   = _get('GrossMargin')
    leverage = _get('Debt_EBITDA', 'DebtToEquity')

    labels = ['수익성', '성장성', '현금창출', '밸류에이션', '마진', '재무건전성']
    values = [
        _pct(roic,     ['ROIC',       'ROE'],              inverse=False, hmax=0.40),
        _pct(growth,   ['RevGrowth',  'RevenueGrowth'],    inverse=False, hmax=0.50),
        _pct(fcf,      ['FCF_Margin', 'OperatingMargin'],  inverse=False, hmax=0.35),
        _pct(peg,      ['PEG',        'PBR'],              inverse=True,  hmax=5.0),
        _pct(margin,   ['GrossMargin'],                    inverse=False, hmax=0.80),
        _pct(leverage, ['Debt_EBITDA','DebtToEquity'],     inverse=True,  hmax=5.0),
    ]

    r_vals = values + [values[0]]
    theta  = labels  + [labels[0]]

    avg = sum(v for v in values if v > 0) / max(sum(1 for v in values if v > 0), 1)
    fill_color = 'rgba(74,222,128,0.15)' if avg >= 50 else 'rgba(99,179,237,0.12)'
    line_color = '#4ade80'               if avg >= 50 else '#63b3ed'

    fig = go.Figure(go.Scatterpolar(
        r=r_vals, theta=theta,
        fill='toself',
        fillcolor=fill_color,
        line=dict(color=line_color, width=2),
        marker=dict(size=5, color=line_color),
        hovertemplate='<b>%{theta}</b>: %{r:.1f} / 100<extra></extra>',
    ))
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'height': 300,
        'polar': dict(
            bgcolor='#0d1424',
            radialaxis=dict(
                range=[0, 100], tickvals=[25, 50, 75, 100],
                tickfont=dict(size=7, color='#4b5563'),
                gridcolor='#1e293b', linecolor='#1e293b',
            ),
            angularaxis=dict(
                tickfont=dict(size=11, color='#94a3b8'),
                gridcolor='#1e293b', linecolor='#1e293b',
            ),
        ),
        'title': dict(text=f'{title} (6축)', font=dict(color='#e2e8f0', size=13)),
        'margin': dict(l=44, r=44, t=44, b=16),
    })
    st.plotly_chart(fig, width="stretch")
    st.caption(
        f"수익성 {values[0]:.0f}  ·  성장성 {values[1]:.0f}  ·  현금창출 {values[2]:.0f}"
        f"  ·  밸류 {values[3]:.0f}  ·  마진 {values[4]:.0f}  ·  재무건전성 {values[5]:.0f}"
    )


# ── Sector Average Comparison Bar Chart ───────────────────────────────────────
def _render_vs_sector(ticker: str, scored_df: pd.DataFrame):
    """
    Grouped bar chart comparing ticker's ROIC / GrossMargin / FCF_Margin /
    RevGrowth against sector peers. Silently returns if data is insufficient.
    """
    if scored_df.empty:
        return
    row = scored_df[scored_df['Ticker'] == ticker]
    if row.empty:
        return
    row    = row.iloc[0]
    sector = str(row.get('Sector', '')).strip()
    if not sector or sector == 'nan':
        return

    sector_df = scored_df[scored_df['Sector'] == sector]
    if len(sector_df) < 3:
        return

    metrics       = ['ROIC', 'GrossMargin', 'FCF_Margin', 'RevGrowth']
    metric_labels = ['ROIC', 'Gross Margin', 'FCF Margin', 'Rev Growth']

    stock_vals, sector_vals = [], []
    valid_labels = []

    for m, lbl in zip(metrics, metric_labels):
        try:
            sv = float(row.get(m) or 0)
            sm = pd.to_numeric(sector_df[m], errors='coerce').mean()
            if pd.notna(sm):
                stock_vals.append(round(sv * 100, 2))
                sector_vals.append(round(sm * 100, 2))
                valid_labels.append(lbl)
        except Exception:
            continue

    if not valid_labels:
        return

    fig = go.Figure()
    fig.add_trace(go.Bar(
        name=ticker,
        x=valid_labels,
        y=stock_vals,
        marker_color='#63b3ed',
        text=[f"{v:.1f}%" for v in stock_vals],
        textposition='outside',
        textfont=dict(size=10, color='#94a3b8'),
    ))
    fig.add_trace(go.Bar(
        name=f'{sector[:20]} avg',
        x=valid_labels,
        y=sector_vals,
        marker_color='#374151',
        text=[f"{v:.1f}%" for v in sector_vals],
        textposition='outside',
        textfont=dict(size=10, color='#64748b'),
    ))
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'barmode': 'group',
        'height': 280,
        'title': dict(text=f'vs Sector Average ({sector[:25]})',
                      font=dict(color='#e2e8f0', size=13)),
        'yaxis': dict(
            ticksuffix='%',
            tickfont=dict(color='#64748b'),
            gridcolor='#1f2937',
        ),
        'xaxis': dict(tickfont=dict(color='#94a3b8')),
        'legend': dict(font=dict(color='#94a3b8', size=10),
                       bgcolor='rgba(0,0,0,0)'),
        'margin': dict(l=16, r=16, t=44, b=16),
    })
    st.plotly_chart(fig, width="stretch")


# ══════════════════════════════════════════════════════════════════════════════
# Quant Analysis Helpers  (used by show_stock_dialog 🔬 tab)
# ══════════════════════════════════════════════════════════════════════════════

def _first_float(*values):
    """Return the first valid float from the given values, or None."""
    for v in values:
        if v is None:
            continue
        if isinstance(v, str) and v.strip() in ('', 'nan', 'None', 'N/A'):
            continue
        try:
            f = float(v)
            if not pd.isna(f):
                return f
        except (TypeError, ValueError):
            continue
    return None


def _score_grade(percentile: float) -> tuple:
    """Map rank percentile → (letter, color)."""
    if percentile <= 5:   return 'A+', '#4ade80'
    if percentile <= 15:  return 'A',  '#22d3ee'
    if percentile <= 25:  return 'B+', '#60a5fa'
    if percentile <= 40:  return 'B',  '#a78bfa'
    if percentile <= 60:  return 'C',  '#fbbf24'
    if percentile <= 80:  return 'D',  '#fb923c'
    return 'F', '#f87171'


def _render_score_card(ticker: str, scored_df: pd.DataFrame):
    """Quant score summary: grade badge + rank + factor breakdown."""
    if scored_df.empty or ticker not in scored_df['Ticker'].values:
        st.caption("스코어링 유니버스에 포함되지 않은 종목입니다.")
        return

    row = scored_df[scored_df['Ticker'] == ticker].iloc[0]
    total = len(scored_df)
    rank = row.get('Rank')

    if not pd.notna(rank):
        return
    rank = int(rank)
    pct = rank / total * 100
    grade, grade_color = _score_grade(pct)

    # Score values
    total_s = _first_float(row.get('Total_Score'))
    value_s = _first_float(row.get('Value_Score'))
    qual_s  = _first_float(row.get('Quality_Score'))
    mom_s   = _first_float(row.get('Momentum_Score'))

    # Best composite score (ML/Combined/Final/Total)
    for sc_col, sc_label in [('Combined_Score', 'Combined'),
                              ('Final_Score', 'Final'),
                              ('ML_Score', 'ML')]:
        sc_v = _first_float(row.get(sc_col))
        if sc_v is not None:
            composite_val, composite_label = sc_v, sc_label
            break
    else:
        composite_val, composite_label = total_s, 'Total'

    # Build score pills
    def _pill(label, val):
        if val is None:
            return ''
        return (
            f'<div style="background:#0d1424;border-radius:8px;padding:6px 14px;'
            f'text-align:center;min-width:70px;">'
            f'<div style="color:#64748b;font-size:0.7em;">{label}</div>'
            f'<div style="color:#e2e8f0;font-weight:600;font-size:0.95em;">{val:.4f}</div>'
            f'</div>'
        )

    pills_html = ''.join(filter(None, [
        _pill('Value', value_s),
        _pill('Quality', qual_s),
        _pill('Momentum', mom_s),
        _pill(composite_label, composite_val) if composite_label != 'Total' else '',
    ]))

    st.markdown(f'''
    <div style="background:#111827;border-radius:12px;padding:16px;margin-bottom:8px;">
      <div style="display:flex;align-items:center;gap:14px;margin-bottom:12px;">
        <div style="background:{grade_color}22;border:2px solid {grade_color};border-radius:10px;
             padding:4px 14px;font-size:1.4em;font-weight:bold;color:{grade_color};
             min-width:48px;text-align:center;">{grade}</div>
        <div>
          <span style="color:#e2e8f0;font-size:1.1em;font-weight:600;">
            Rank #{rank} / {total}</span>
          <span style="color:#64748b;margin-left:8px;">Top {pct:.1f}%</span>
        </div>
        <div style="margin-left:auto;color:#64748b;font-size:0.8em;">
          Total Score: <span style="color:#e2e8f0;font-weight:600;">
          {total_s:.4f}</span>
        </div>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap;">{pills_html}</div>
    </div>
    ''', unsafe_allow_html=True)


def _render_signal_alerts(ticker: str, market: str):
    """Conditional badges for portfolio / PEAD / order flow membership."""
    badges = []

    # Portfolio membership + weight + drift
    port_sheet = 'US_Final_Portfolio' if market == 'US' else 'KR_Final_Portfolio'
    try:
        _, port_df = load_portfolio_sheet(port_sheet)
        if not port_df.empty and ticker in port_df['Ticker'].values:
            prow = port_df[port_df['Ticker'] == ticker].iloc[0]
            w = _first_float(prow.get('Weight(%)'))
            er = _first_float(prow.get('Expected_Return'))
            parts = ['📈 포트폴리오 보유']
            if w is not None:
                parts.append(f'비중 {w*100:.1f}%')
            if er is not None:
                parts.append(f'기대수익 {er*100:.1f}%')

            # Drift check
            try:
                _, drift_df = load_drift_alert()
                if not drift_df.empty and ticker in drift_df['Ticker'].values:
                    drow = drift_df[drift_df['Ticker'] == ticker].iloc[0]
                    status = str(drow.get('Status', '')).strip()
                    drift_abs = _first_float(drow.get('Drift_Abs'))
                    if drift_abs is not None:
                        status_icon = {'OK': '🟢', 'WATCH': '🟡', 'REBALANCE': '🔴'}.get(status, '⚪')
                        parts.append(f'Drift {drift_abs*100:+.1f}%p {status_icon}')
            except Exception:
                pass

            badges.append(('📈', '#4ade80', ' · '.join(parts)))
    except Exception:
        pass

    # PEAD earnings signal
    try:
        earn_df = load_earnings_momentum(market)
        if not earn_df.empty and ticker in earn_df['Ticker'].values:
            erow = earn_df[earn_df['Ticker'] == ticker].iloc[0]
            surp = _first_float(erow.get('Surprise_Pct'))
            sig  = _first_float(erow.get('Signal_Strength'))
            days = _first_float(erow.get('Days_Since_Earnings'))
            ret  = _first_float(erow.get('Return_Since'))
            parts = ['⚡ PEAD 시그널']
            if surp is not None:
                parts.append(f'서프라이즈 {surp*100:+.1f}%')
            if sig is not None:
                parts.append(f'Signal {sig:.2f}')
            if days is not None:
                parts.append(f'{int(days)}일 전')
            if ret is not None:
                parts.append(f'이후 수익 {ret*100:+.1f}%')
            badges.append(('⚡', '#fbbf24', ' · '.join(parts)))
    except Exception:
        pass

    # KR Order Flow
    if market == 'KR':
        try:
            flow_df = load_kr_order_flow()
            if not flow_df.empty and ticker in flow_df['Ticker'].values:
                frow = flow_df[flow_df['Ticker'] == ticker].iloc[0]
                days = _first_float(frow.get('Consecutive_Days'))
                parts = ['🏦 외국인+기관 순매수']
                if days is not None:
                    parts.append(f'{int(days)}일 연속')
                badges.append(('🏦', '#a78bfa', ' · '.join(parts)))
        except Exception:
            pass

    if not badges:
        return

    html_parts = []
    for icon, color, text in badges:
        html_parts.append(
            f'<div style="background:{color}15;border:1px solid {color}44;'
            f'border-radius:20px;padding:5px 14px;font-size:0.85em;color:{color};">'
            f'{text}</div>'
        )
    st.markdown(
        f'<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px;">'
        + ''.join(html_parts) + '</div>',
        unsafe_allow_html=True,
    )


def _render_returns_row(hist: pd.DataFrame, currency: str):
    """Period returns (1W–1Y) + volatility + max drawdown."""
    if hist.empty or 'Close' not in hist.columns:
        return
    close = hist['Close'].dropna()
    if len(close) < 5:
        return

    now_price = float(close.iloc[-1])

    periods = [('1W', 5), ('1M', 21), ('3M', 63), ('6M', 126), ('YTD', 'ytd'), ('1Y', 252)]
    cols = st.columns(len(periods))
    for (label, days), col in zip(periods, cols):
        if days == 'ytd':
            year_idx = close.index[close.index.year == close.index[-1].year]
            ret = (now_price / float(close.loc[year_idx[0]]) - 1) if len(year_idx) > 0 else None
        else:
            ret = (now_price / float(close.iloc[-(days + 1)]) - 1) if len(close) > days else None
        with col:
            if ret is not None:
                st.metric(label, f"{ret:+.1%}",
                          delta=None, delta_color="off")
            else:
                st.metric(label, "—")

    # Volatility + MaxDD
    daily_ret = close.pct_change().dropna()
    vol = float(daily_ret.std() * np.sqrt(252)) if len(daily_ret) > 5 else None
    close_6m = close.iloc[-min(126, len(close)):]
    peak = close_6m.cummax()
    dd = (close_6m - peak) / peak
    max_dd = float(dd.min()) if len(dd) > 0 else None

    c1, c2, _ = st.columns(3)
    with c1:
        st.metric("연간 변동성", f"{vol:.1%}" if vol else "—")
    with c2:
        st.metric("6M 최대낙폭", f"{max_dd:.1%}" if max_dd else "—")


def _render_fundamentals_table(ticker: str, scored_df: pd.DataFrame,
                                info: dict, row_data: dict, market: str):
    """Two-column fundamental metrics grid from best available source."""
    # Get scored row if available
    scored_row = None
    if not scored_df.empty and ticker in scored_df['Ticker'].values:
        scored_row = scored_df[scored_df['Ticker'] == ticker].iloc[0]

    rd = row_data or {}

    def _g(scored_key, *info_keys):
        """Get value: scored_df → row_data → yfinance info."""
        sources = []
        if scored_row is not None:
            sources.append(scored_row.get(scored_key))
        sources.append(rd.get(scored_key))
        for k in info_keys:
            sources.append(info.get(k))
        return _first_float(*sources)

    metrics = {
        'PER':       _g('PER', 'trailingPE'),
        'PBR':       _g('PBR', 'priceToBook'),
        'PEG':       _g('PEG', 'pegRatio'),
        'DivYield':  _g('DivYield', 'dividendYield'),
        'ROE':       _g('ROE', 'returnOnEquity'),
        'ROIC':      _g('ROIC'),
        'GrossMargin':     _g('GrossMargin', 'grossMargins'),
        'OperatingMargin': _g('OperatingMargin', 'operatingMargins'),
        'FCF_Margin':      _g('FCF_Margin'),
        'RevGrowth':       _g('RevGrowth', 'RevenueGrowth', 'revenueGrowth'),
        'DebtToEquity':    _g('DebtToEquity', 'debtToEquity'),
        'Debt_EBITDA':     _g('Debt_EBITDA'),
    }

    def _fmt(key, val):
        if val is None:
            return '—'
        if key in ('PER',):
            return f'{val:.1f}'
        if key in ('PBR', 'PEG', 'Debt_EBITDA'):
            return f'{val:.2f}x' if key == 'Debt_EBITDA' else f'{val:.2f}'
        if key == 'DebtToEquity':
            return f'{val:.1f}%'
        # Ratio/pct fields — stored as decimal (0.15 → 15.0%)
        if abs(val) < 5:  # likely decimal ratio
            return f'{val * 100:.1f}%'
        return f'{val:.1f}%'

    def _color(key, val):
        if val is None:
            return '#64748b'
        # Green = good, Red = bad
        if key in ('ROIC', 'ROE', 'GrossMargin', 'OperatingMargin', 'FCF_Margin', 'RevGrowth'):
            v = val * 100 if abs(val) < 5 else val
            return '#4ade80' if v > 10 else '#fbbf24' if v > 0 else '#f87171'
        if key in ('DebtToEquity',):
            return '#4ade80' if val < 50 else '#fbbf24' if val < 100 else '#f87171'
        if key in ('Debt_EBITDA',):
            return '#4ade80' if val < 2 else '#fbbf24' if val < 4 else '#f87171'
        if key in ('PER',):
            return '#4ade80' if 5 < val < 25 else '#fbbf24' if val < 40 else '#f87171'
        return '#e2e8f0'

    def _row(label, key):
        v = metrics[key]
        c = _color(key, v)
        return (f'<tr><td style="color:#94a3b8;padding:4px 8px;">{label}</td>'
                f'<td style="color:{c};font-weight:600;padding:4px 8px;text-align:right;">'
                f'{_fmt(key, v)}</td></tr>')

    left_rows = [
        ('PER', 'PER'), ('PBR', 'PBR'), ('PEG', 'PEG'),
        ('배당수익률', 'DivYield'), ('매출성장률', 'RevGrowth'),
    ]
    right_rows = [
        ('ROIC', 'ROIC'), ('ROE', 'ROE'), ('매출총이익률', 'GrossMargin'),
        ('영업이익률', 'OperatingMargin'), ('FCF 마진', 'FCF_Margin'),
        ('D/E', 'DebtToEquity'), ('Debt/EBITDA', 'Debt_EBITDA'),
    ]

    tbl_style = ('style="width:100%;border-collapse:collapse;font-size:0.9em;"')
    hdr_style = ('style="color:#64748b;font-size:0.75em;text-transform:uppercase;'
                 'padding:4px 8px;border-bottom:1px solid #1e293b;"')
    col1, col2 = st.columns(2)
    with col1:
        st.markdown(
            f'<table {tbl_style}>'
            f'<tr><th {hdr_style}>Valuation & Growth</th><th {hdr_style}></th></tr>'
            + ''.join(_row(l, k) for l, k in left_rows)
            + '</table>',
            unsafe_allow_html=True,
        )
    with col2:
        st.markdown(
            f'<table {tbl_style}>'
            f'<tr><th {hdr_style}>Quality & Risk</th><th {hdr_style}></th></tr>'
            + ''.join(_row(l, k) for l, k in right_rows)
            + '</table>',
            unsafe_allow_html=True,
        )


# ── Dialogs ────────────────────────────────────────────────────────────────────
@st.dialog("📊 Company Detail", width="large")
def show_stock_dialog(ticker: str, name: str, currency: str, row_data: dict = None):
    logo = _logo_url(ticker, currency)
    market = 'KR' if currency == 'KRW' else 'US'

    # ── Header
    c_logo, c_title = st.columns([1, 7])
    with c_logo:
        st.markdown(
            f'<img src="{logo}" style="width:56px;height:56px;object-fit:contain;'
            f'border-radius:12px;background:#0a0f1e;" '
            f'onerror="this.style.display=\'none\'">',
            unsafe_allow_html=True,
        )
    with c_title:
        st.markdown(f"### {name}")
        yf_url = f"https://finance.yahoo.com/quote/{ticker}"
        st.caption(f"`{ticker}` · [Yahoo Finance ↗]({yf_url})")

    with st.spinner("데이터 불러오는 중..."):
        hist, info, err = fetch_stock_detail(ticker)
        annual_rev, quarterly_rev, eps_df = fetch_earnings_data(ticker)

    if err:
        st.warning(f"⚠️ {err}")

    if hist.empty and not info:
        st.info("데이터를 불러올 수 없습니다. Yahoo Finance에서 직접 확인해 주세요.")
        st.markdown(f"👉 [Yahoo Finance에서 {ticker} 보기]({yf_url})")
        return

    # ── Key metrics (always visible above tabs)
    price  = info.get('currentPrice') or info.get('regularMarketPrice') or info.get('previousClose')
    prev   = info.get('previousClose')
    chg    = ((price - prev) / prev) if price and prev and prev != 0 else None
    pe     = info.get('trailingPE')
    hi52   = info.get('fiftyTwoWeekHigh')
    lo52   = info.get('fiftyTwoWeekLow')
    volume = info.get('volume') or info.get('regularMarketVolume')

    def _fmt_price(v):
        if v is None:
            return "—"
        return f"${v:,.2f}" if currency == 'USD' else f"₩{v:,.0f}"

    c1, c2, c3, c4, c5 = st.columns(5)
    with c1:
        st.metric("현재가", _fmt_price(price),
                  f"{chg:+.2%}" if chg is not None else None,
                  delta_color="normal")
    with c2:
        st.metric("P/E", f"{pe:.1f}" if isinstance(pe, (int, float)) else "—")
    with c3:
        st.metric("52W High", _fmt_price(hi52))
    with c4:
        st.metric("52W Low",  _fmt_price(lo52))
    with c5:
        st.metric("거래량", f"{int(volume):,}" if volume else "—")

    # ── Load scored data (shared across tabs)
    scored_df = load_scored_stocks(market)

    # ══════════════════════════════════════════════════════════════════════════
    # TABS
    # ══════════════════════════════════════════════════════════════════════════
    tab_chart, tab_quant, tab_info = st.tabs(["📈 차트", "🔬 퀀트 분석", "ℹ️ 기업 정보"])

    # ── Tab 1: Chart ──────────────────────────────────────────────────────────
    with tab_chart:
        ctrl_left, ctrl_right = st.columns([3, 1])
        with ctrl_left:
            period_map = {'1M': 21, '3M': 63, '6M': 126, '1Y': 252, '2Y': 504}
            period_sel = st.radio(
                "기간", list(period_map.keys()),
                index=2, horizontal=True, label_visibility='collapsed',
            )
        with ctrl_right:
            show_trend = st.toggle("📐 추세선", value=False)
            show_macd  = st.toggle("📊 MACD",  value=False)
        n_days = min(period_map[period_sel], len(hist))
        _render_candlestick(hist, currency, n_days=n_days, show_trend=show_trend)

        if show_macd and not hist.empty:
            _render_macd_chart(hist, n_days, currency)

        if not scored_df.empty and ticker in scored_df['Ticker'].values:
            col_radar, col_sector = st.columns(2)
            with col_radar:
                _render_factor_radar(ticker, scored_df)
            with col_sector:
                _render_vs_sector(ticker, scored_df)
        elif row_data:
            col_radar, _ = st.columns(2)
            with col_radar:
                _render_generic_radar(row_data, ref_df=scored_df)

    # ── Tab 2: Quant Analysis ─────────────────────────────────────────────────
    with tab_quant:
        # Score card
        _render_score_card(ticker, scored_df)

        # Signal alerts (portfolio / PEAD / order flow)
        _render_signal_alerts(ticker, market)

        # Analyst consensus
        st.markdown(
            '<p style="color:#94a3b8;font-size:0.8em;text-transform:uppercase;'
            'margin:12px 0 4px;">애널리스트 컨센서스</p>',
            unsafe_allow_html=True,
        )
        _render_analyst_consensus(info, currency)

        # Period returns
        st.markdown(
            '<p style="color:#94a3b8;font-size:0.8em;text-transform:uppercase;'
            'margin:12px 0 4px;">수익률</p>',
            unsafe_allow_html=True,
        )
        _render_returns_row(hist, currency)

        # Fundamentals grid
        st.markdown(
            '<p style="color:#94a3b8;font-size:0.8em;text-transform:uppercase;'
            'margin:16px 0 4px;">펀더멘탈</p>',
            unsafe_allow_html=True,
        )
        _render_fundamentals_table(ticker, scored_df, info, row_data, market)

        # Quarterly earnings trend
        st.markdown(
            '<p style="color:#94a3b8;font-size:0.8em;text-transform:uppercase;'
            'margin:16px 0 4px;">분기 실적 트렌드</p>',
            unsafe_allow_html=True,
        )
        _render_earnings_trend(annual_rev, quarterly_rev, eps_df, currency)

    # ── Tab 3: Company Info ───────────────────────────────────────────────────
    with tab_info:
        desc      = info.get('longBusinessSummary', '')
        sector    = info.get('sector', '')
        industry  = info.get('industry', '')
        employees = info.get('fullTimeEmployees')
        website   = info.get('website', '')

        if sector or industry or employees:
            tags = []
            if sector:    tags.append(f"🏢 **{sector}**")
            if industry:  tags.append(f"⚙️ {industry}")
            if employees: tags.append(f"👥 {int(employees):,}명")
            st.caption("  ·  ".join(tags))

        if desc:
            with st.expander("기업 설명", expanded=True):
                st.write(desc)

        if website:
            st.markdown(f"🌐 [{website}]({website})")

        # Additional yfinance metrics
        extra_metrics = []
        for key, label in [
            ('marketCap', '시가총액'), ('enterpriseValue', 'EV'),
            ('forwardPE', 'Forward P/E'), ('priceToSalesTrailing12Months', 'P/S'),
            ('enterpriseToEbitda', 'EV/EBITDA'), ('beta', 'Beta'),
            ('profitMargins', '순이익률'), ('revenuePerShare', '주당매출'),
        ]:
            v = info.get(key)
            if v is not None:
                if key in ('marketCap', 'enterpriseValue'):
                    if v >= 1e12:
                        extra_metrics.append((label, f"${v/1e12:.1f}T" if currency == 'USD'
                                              else f"₩{v/1e12:.1f}조"))
                    elif v >= 1e9:
                        extra_metrics.append((label, f"${v/1e9:.1f}B" if currency == 'USD'
                                              else f"₩{v/1e9:.0f}억"))
                elif key in ('profitMargins',):
                    extra_metrics.append((label, f"{v*100:.1f}%"))
                elif key == 'beta':
                    extra_metrics.append((label, f"{v:.2f}"))
                else:
                    extra_metrics.append((label, f"{v:.2f}"))

        if extra_metrics:
            st.divider()
            st.caption("추가 지표 (Yahoo Finance)")
            mid = (len(extra_metrics) + 1) // 2
            ec1, ec2 = st.columns(2)
            with ec1:
                for label, val in extra_metrics[:mid]:
                    st.markdown(f"**{label}:** {val}")
            with ec2:
                for label, val in extra_metrics[mid:]:
                    st.markdown(f"**{label}:** {val}")


def _render_macd_chart(hist: pd.DataFrame, n_days: int, currency: str):
    """Standalone MACD (12, 26, 9) chart with histogram."""
    from plotly.subplots import make_subplots

    close = hist['Close'].dropna()
    if len(close) < 30:
        st.caption("MACD 계산을 위한 데이터가 부족합니다.")
        return

    ema12 = close.ewm(span=12, adjust=False).mean()
    ema26 = close.ewm(span=26, adjust=False).mean()
    macd_line  = ema12 - ema26
    signal_line = macd_line.ewm(span=9, adjust=False).mean()
    histogram   = macd_line - signal_line

    macd_s  = macd_line.iloc[-n_days:]
    signal_s = signal_line.iloc[-n_days:]
    hist_s   = histogram.iloc[-n_days:]

    dates     = [str(d)[:10] for d in macd_s.index]
    x_pos     = list(range(len(macd_s)))
    tick_step = max(1, len(macd_s) // 8)
    tickvals  = list(range(0, len(macd_s), tick_step))
    ticktext  = [dates[i] for i in tickvals]

    hist_colors = ['#4ade80' if v >= 0 else '#f87171' for v in hist_s.values]

    fig = make_subplots(rows=2, cols=1, shared_xaxes=True,
                        row_heights=[0.55, 0.45], vertical_spacing=0.04)

    fig.add_trace(go.Scatter(
        x=x_pos, y=macd_s.values, mode='lines', name='MACD',
        line=dict(color='#60a5fa', width=1.8),
        customdata=dates,
        hovertemplate='%{customdata}<br>MACD: %{y:.4f}<extra></extra>',
    ), row=1, col=1)
    fig.add_trace(go.Scatter(
        x=x_pos, y=signal_s.values, mode='lines', name='Signal(9)',
        line=dict(color='#f87171', width=1.4, dash='dash'),
        customdata=dates,
        hovertemplate='%{customdata}<br>Signal: %{y:.4f}<extra></extra>',
    ), row=1, col=1)
    fig.add_hline(y=0, line=dict(color='#374151', width=1), row=1, col=1)

    fig.add_trace(go.Bar(
        x=x_pos, y=hist_s.values,
        marker_color=hist_colors, name='히스토그램', showlegend=False,
        customdata=dates,
        hovertemplate='%{customdata}<br>Hist: %{y:.4f}<extra></extra>',
    ), row=2, col=1)
    fig.add_hline(y=0, line=dict(color='#374151', width=1), row=2, col=1)

    shared_xaxis = dict(
        tickfont=dict(color='#64748b'), gridcolor='#1f2937',
        showgrid=False, rangeslider=dict(visible=False),
        type='category', tickmode='array',
        tickvals=tickvals, ticktext=ticktext, tickangle=-30,
    )
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'height': 260,
        'margin': dict(l=0, r=16, t=36, b=0),
        'title': dict(text="MACD (12, 26, 9)  —  파랑: MACD선  /  빨강: Signal선  /  막대: 히스토그램",
                      font=dict(color='#e2e8f0', size=12)),
        'hovermode': 'x unified',
        'legend': dict(orientation='h', yanchor='bottom', y=1.02, xanchor='right', x=1,
                       font=dict(color='#94a3b8', size=11)),
    })
    fig.update_xaxes(**shared_xaxis)
    fig.update_yaxes(tickfont=dict(color='#64748b'), gridcolor='#1f2937', showgrid=True)
    st.plotly_chart(fig, width="stretch")


def _render_earnings_trend(
    annual_rev: pd.Series, quarterly_rev: pd.Series,
    eps_df: pd.DataFrame, currency: str,
):
    """연간 매출 + 분기 매출 + 분기 EPS 실적 차트 (Toss 스타일)."""
    has_ann = not annual_rev.empty
    has_rev = not quarterly_rev.empty
    has_eps = not eps_df.empty

    if not has_ann and not has_rev and not has_eps:
        st.caption("실적 데이터를 가져올 수 없습니다. (커버리지 없음)")
        return

    _CHART_LAYOUT = {
        **PLOTLY_LAYOUT,
        'margin':     dict(l=4, r=4, t=44, b=4),
        'plot_bgcolor':  '#0a0f1e',
        'paper_bgcolor': '#0a0f1e',
        'xaxis': dict(
            showgrid=False, zeroline=False,
            tickfont=dict(color='#64748b', size=9), tickangle=-20,
        ),
        'yaxis': dict(
            showgrid=True, gridcolor='#1a2235', zeroline=False,
            tickfont=dict(color='#475569', size=9),
        ),
        'showlegend': False,
    }

    def _rev_scale(vals, currency):
        if currency == 'KRW':
            return [v / 1e8 for v in vals], "억원"
        t = max(abs(v) for v in vals) if vals else 1
        if t >= 1e9:
            return [v / 1e9 for v in vals], "$B"
        return [v / 1e6 for v in vals], "$M"

    def _rev_label(val, unit):
        if unit == "억원":
            if abs(val) >= 10000:
                return f"{val/10000:.1f}조"
            return f"{val:.0f}억"
        if unit == "$B":
            return f"${val:.1f}B"
        return f"${val:.0f}M"

    # ── ① 연간 매출 (전체 너비) ──────────────────────────────────────────────
    if has_ann:
        ann = annual_rev.sort_index()
        dates_ann = [str(d)[:4] for d in ann.index]   # YYYY
        raw_vals  = [float(v) for v in ann.values]
        ann_vals, unit = _rev_scale(raw_vals, currency)

        bar_colors_ann = []
        text_ann = []
        for i, v in enumerate(ann_vals):
            if i > 0 and ann_vals[i - 1] > 0:
                grew = v >= ann_vals[i - 1]
                bar_colors_ann.append('#34d399' if grew else '#f87171')
                yoy = (v / ann_vals[i - 1] - 1) * 100
                text_ann.append(f"{yoy:+.0f}%")
            else:
                bar_colors_ann.append('#60a5fa')
                text_ann.append(_rev_label(v, unit))

        hover_ann = [_rev_label(v, unit) for v in ann_vals]
        fig_ann = go.Figure(go.Bar(
            x=dates_ann, y=ann_vals,
            marker_color=bar_colors_ann,
            marker_line_width=0,
            text=text_ann,
            textposition='outside',
            textfont=dict(color='#94a3b8', size=10, family='monospace'),
            customdata=hover_ann,
            hovertemplate='%{x}년<br>매출: %{customdata}<extra></extra>',
        ))
        fig_ann.update_layout(**{
            **_CHART_LAYOUT,
            'height': 220,
            'title': dict(
                text=f"연간 매출 ({unit})",
                font=dict(color='#e2e8f0', size=12, family='sans-serif'),
                x=0, xanchor='left',
            ),
        })
        fig_ann.update_yaxes(visible=False)
        st.plotly_chart(fig_ann, width="stretch")

    # ── ② 분기 매출 + ③ 분기 EPS (2열) ─────────────────────────────────────
    col_q, col_eps = st.columns(2)

    with col_q:
        if has_rev:
            rev = quarterly_rev.iloc[-8:].sort_index()
            dates_rev = [str(d)[:7] for d in rev.index]
            raw_rev   = [float(v) for v in rev.values]
            rev_vals, unit_q = _rev_scale(raw_rev, currency)

            bar_colors_q = []
            text_q = []
            for i, v in enumerate(rev_vals):
                if i >= 4 and rev_vals[i - 4] > 0:
                    grew = v >= rev_vals[i - 4]
                    bar_colors_q.append('#34d399' if grew else '#f87171')
                    yoy = (v / rev_vals[i - 4] - 1) * 100
                    text_q.append(f"{yoy:+.0f}%")
                else:
                    bar_colors_q.append('#60a5fa')
                    text_q.append("")

            hover_q = [_rev_label(v, unit_q) for v in rev_vals]
            fig_q = go.Figure(go.Bar(
                x=dates_rev, y=rev_vals,
                marker_color=bar_colors_q,
                marker_line_width=0,
                text=text_q,
                textposition='outside',
                textfont=dict(color='#94a3b8', size=9, family='monospace'),
                customdata=hover_q,
                hovertemplate='%{x}<br>매출: %{customdata}<extra></extra>',
            ))
            fig_q.update_layout(**{
                **_CHART_LAYOUT,
                'height': 210,
                'title': dict(
                    text=f"분기 매출 ({unit_q})  ·  막대 위 = YoY",
                    font=dict(color='#e2e8f0', size=11),
                    x=0, xanchor='left',
                ),
            })
            fig_q.update_yaxes(visible=False)
            st.plotly_chart(fig_q, width="stretch")
        else:
            st.caption("분기 매출 데이터 없음")

    with col_eps:
        if has_eps:
            eps_sorted  = eps_df.sort_index().tail(8)
            dates_eps   = [str(d)[:7] for d in eps_sorted.index]
            actuals     = eps_sorted.get('Reported EPS',
                                         pd.Series([None]*len(eps_sorted))).tolist()
            estimates   = eps_sorted.get('EPS Estimate',
                                         pd.Series([None]*len(eps_sorted))).tolist()

            beat_colors = []
            for a, e in zip(actuals, estimates):
                if a is None or (isinstance(a, float) and pd.isna(a)):
                    beat_colors.append('#475569')   # future / no data
                elif e is None or (isinstance(e, float) and pd.isna(e)):
                    beat_colors.append('#60a5fa')
                else:
                    beat_colors.append('#34d399' if float(a) >= float(e) else '#f87171')

            safe_act = [float(v) if v is not None and not (
                isinstance(v, float) and pd.isna(v)) else None for v in actuals]
            safe_est = [float(v) if v is not None and not (
                isinstance(v, float) and pd.isna(v)) else None for v in estimates]

            act_text = [f"{v:.2f}" if v is not None else "" for v in safe_act]

            fig_eps = go.Figure()
            fig_eps.add_trace(go.Bar(
                name='실제 EPS',
                x=dates_eps, y=safe_act,
                marker_color=beat_colors,
                marker_line_width=0,
                text=act_text,
                textposition='outside',
                textfont=dict(color='#94a3b8', size=9, family='monospace'),
                hovertemplate='%{x}<br>실제 EPS: %{y:.3f}<extra></extra>',
            ))
            fig_eps.add_trace(go.Scatter(
                name='컨센서스',
                x=dates_eps, y=safe_est,
                mode='markers',
                marker=dict(color='#fbbf24', size=8, symbol='diamond',
                            line=dict(color='#fbbf24', width=1)),
                hovertemplate='%{x}<br>컨센서스: %{y:.3f}<extra></extra>',
            ))
            fig_eps.update_layout(**{
                **_CHART_LAYOUT,
                'height': 210,
                'showlegend': True,
                'legend': dict(
                    orientation='h', yanchor='bottom', y=1.02,
                    xanchor='right', x=1,
                    font=dict(color='#94a3b8', size=10),
                    bgcolor='rgba(0,0,0,0)',
                ),
                'title': dict(
                    text="분기 EPS  ·  초록=Beat  빨강=Miss  노랑◆=컨센서스",
                    font=dict(color='#e2e8f0', size=11),
                    x=0, xanchor='left',
                ),
                'barmode': 'overlay',
            })
            fig_eps.update_yaxes(visible=False)
            st.plotly_chart(fig_eps, width="stretch")
        else:
            st.caption("EPS 컨센서스 데이터 없음")


def _render_analyst_consensus(info: dict, currency: str):
    """목표주가 범위 + 컨센서스 게이지."""
    current   = _first_float(info.get('currentPrice'), info.get('regularMarketPrice'),
                             info.get('previousClose'))
    t_mean    = _first_float(info.get('targetMeanPrice'))
    t_high    = _first_float(info.get('targetHighPrice'))
    t_low     = _first_float(info.get('targetLowPrice'))
    n_analysts = info.get('numberOfAnalystOpinions')
    rec_mean  = _first_float(info.get('recommendationMean'))   # 1=강매수 ~ 5=강매도
    rec_key   = str(info.get('recommendationKey', '')).strip()

    if t_mean is None and not rec_key:
        st.caption("애널리스트 커버리지 없음")
        return

    prefix = "$" if currency == 'USD' else "₩"
    fmt    = ',.2f' if currency == 'USD' else ',.0f'

    col1, col2 = st.columns(2)

    # ── 목표주가 범위 ──────────────────────────────────────────────────────────
    with col1:
        if t_mean is not None and current is not None:
            upside = (t_mean / current - 1) * 100
            upside_color = '#4ade80' if upside >= 0 else '#f87171'

            # KPI 행
            k1, k2, k3 = st.columns(3)
            k1.metric("목표주가 (평균)", f"{prefix}{t_mean:{fmt}}")
            k2.metric("현재가 대비",
                      f"{upside:+.1f}%",
                      delta=None)
            if n_analysts:
                k3.metric("커버 애널리스트", f"{int(n_analysts)}명")

            # Bullet-chart: 현재가 위치
            if t_low and t_high and t_high > t_low:
                lo_ext = t_low  * 0.92
                hi_ext = t_high * 1.08

                fig_bullet = go.Figure()
                # 배경 구간 (저 → 컨센서스 → 고)
                for x0, x1, fc in [
                    (lo_ext, t_low,  'rgba(248,113,113,0.15)'),
                    (t_low,  t_mean, 'rgba(251,191,36,0.10)'),
                    (t_mean, t_high, 'rgba(74,222,128,0.10)'),
                    (t_high, hi_ext, 'rgba(74,222,128,0.20)'),
                ]:
                    fig_bullet.add_shape(type='rect', x0=x0, x1=x1, y0=0.2, y1=0.8,
                                         fillcolor=fc, line_width=0)

                # 목표주가 평균 세로선
                fig_bullet.add_shape(type='line',
                                     x0=t_mean, x1=t_mean, y0=0.05, y1=0.95,
                                     line=dict(color='#fbbf24', width=2, dash='dash'))
                # 현재가 마커
                fig_bullet.add_trace(go.Scatter(
                    x=[current], y=[0.5], mode='markers',
                    marker=dict(color='#60a5fa', size=14, symbol='triangle-up',
                                line=dict(color='#ffffff', width=1)),
                    name='현재가',
                    hovertemplate=f'현재가: {prefix}{current:{fmt}}<extra></extra>',
                ))
                fig_bullet.update_layout(**{
                    **PLOTLY_LAYOUT,
                    'height': 120,
                    'margin': dict(l=0, r=0, t=8, b=24),
                    'showlegend': False,
                    'xaxis': dict(
                        range=[lo_ext, hi_ext],
                        tickfont=dict(color='#64748b', size=9),
                        tickprefix=prefix,
                        gridcolor='#1f2937',
                        tickformat=',.0f' if currency == 'KRW' else ',.2f',
                    ),
                    'yaxis': dict(visible=False, range=[0, 1]),
                    'annotations': [
                        dict(x=t_low,  y=0,  yref='paper', text=f'Low<br>{prefix}{t_low:{fmt}}',
                             showarrow=False, font=dict(color='#f87171', size=9), yanchor='top'),
                        dict(x=t_mean, y=0,  yref='paper', text=f'Avg<br>{prefix}{t_mean:{fmt}}',
                             showarrow=False, font=dict(color='#fbbf24', size=9), yanchor='top'),
                        dict(x=t_high, y=0,  yref='paper', text=f'High<br>{prefix}{t_high:{fmt}}',
                             showarrow=False, font=dict(color='#4ade80', size=9), yanchor='top'),
                    ],
                })
                st.plotly_chart(fig_bullet, width="stretch")

    # ── 컨센서스 게이지 ────────────────────────────────────────────────────────
    with col2:
        if rec_mean is not None:
            # 1=강매수, 2=매수, 3=보유, 4=매도, 5=강매도
            thresholds = [
                (1.5, '강매수', '#22c55e'),
                (2.5, '매수',   '#4ade80'),
                (3.5, '보유',   '#fbbf24'),
                (4.5, '매도',   '#f87171'),
                (5.1, '강매도', '#dc2626'),
            ]
            label, color = '보유', '#fbbf24'
            for thr, lbl, clr in thresholds:
                if rec_mean < thr:
                    label, color = lbl, clr
                    break

            fig_gauge = go.Figure(go.Indicator(
                mode="gauge+number",
                value=rec_mean,
                gauge={
                    'axis': {
                        'range': [1, 5],
                        'tickvals': [1, 2, 3, 4, 5],
                        'ticktext': ['강매수', '매수', '보유', '매도', '강매도'],
                        'tickfont': dict(color='#64748b', size=9),
                    },
                    'bar':       {'color': color, 'thickness': 0.28},
                    'bgcolor':   '#111827',
                    'borderwidth': 0,
                    'steps': [
                        {'range': [1, 2], 'color': 'rgba(34,197,94,0.20)'},
                        {'range': [2, 3], 'color': 'rgba(74,222,128,0.10)'},
                        {'range': [3, 4], 'color': 'rgba(251,191,36,0.10)'},
                        {'range': [4, 5], 'color': 'rgba(248,113,113,0.20)'},
                    ],
                },
                title={'text': f"컨센서스: <b>{label}</b>",
                       'font': {'color': color, 'size': 13}},
                number={'font': {'color': color, 'size': 24}, 'valueformat': '.2f'},
            ))
            fig_gauge.update_layout(**{
                **PLOTLY_LAYOUT,
                'height': 210,
                'margin': dict(l=0, r=0, t=40, b=0),
            })
            st.plotly_chart(fig_gauge, width="stretch")
        elif rec_key:
            # rec_mean 없을 때 텍스트로라도 표시
            key_map = {
                'strongbuy': ('강매수', '#22c55e'), 'buy': ('매수', '#4ade80'),
                'hold': ('보유', '#fbbf24'), 'sell': ('매도', '#f87171'),
                'strongsell': ('강매도', '#dc2626'),
            }
            lbl, clr = key_map.get(rec_key.lower().replace(' ', ''), (rec_key, '#94a3b8'))
            st.markdown(
                f'<div style="text-align:center;padding:32px 0;">'
                f'<div style="font-size:2rem;font-weight:800;color:{clr};">{lbl}</div>'
                f'<div style="color:#64748b;font-size:0.8em;margin-top:4px;">애널리스트 컨센서스</div>'
                f'</div>',
                unsafe_allow_html=True,
            )


def _calc_rsi(close: pd.Series, period: int = 14) -> pd.Series:
    delta = close.diff()
    gain = delta.clip(lower=0).rolling(period).mean()
    loss = (-delta).clip(lower=0).rolling(period).mean()
    rs = gain / loss.replace(0, np.nan)
    return 100 - (100 / (1 + rs))


def _calc_regression_channel(close: pd.Series):
    """
    OLS linear regression on close prices.
    Returns dict with trend, upper_1s, lower_1s, upper_2s, lower_2s arrays,
    slope_pct_per_month (float), and r2 (float).
    """
    n = len(close)
    x = np.arange(n, dtype=float)
    y = close.values.astype(float)
    valid = ~np.isnan(y)
    if valid.sum() < 5:
        return None

    coeffs = np.polyfit(x[valid], y[valid], 1)
    trend  = np.polyval(coeffs, x)

    residuals = y[valid] - trend[valid]
    std = residuals.std()

    # R²
    ss_res = (residuals ** 2).sum()
    ss_tot = ((y[valid] - y[valid].mean()) ** 2).sum()
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else 0.0

    # Monthly slope as % of starting price
    start_price = trend[0] if trend[0] != 0 else 1.0
    slope_pct_per_month = coeffs[0] * 21 / start_price * 100

    return {
        'trend':    trend,
        'upper_1s': trend + std,
        'lower_1s': trend - std,
        'upper_2s': trend + 2 * std,
        'lower_2s': trend - 2 * std,
        'slope_pct_per_month': slope_pct_per_month,
        'r2': r2,
    }


def _calc_support_resistance(high: pd.Series, low: pd.Series, n_levels: int = 3):
    """
    Detect support/resistance via local extrema, then cluster within 2% band.
    Returns (support_levels, resistance_levels) — each a list of (price, strength) tuples.
    """
    try:
        from scipy.signal import argrelextrema
    except ImportError:
        return [], []

    order = max(3, len(high) // 20)  # adaptive window
    h = high.values.astype(float)
    l = low.values.astype(float)

    res_idx = argrelextrema(h, np.greater, order=order)[0]
    sup_idx = argrelextrema(l, np.less,    order=order)[0]

    def _cluster(prices, pct=0.02):
        if len(prices) == 0:
            return []
        prices = sorted(prices)
        clusters = []
        group = [prices[0]]
        for p in prices[1:]:
            if p <= group[0] * (1 + pct):
                group.append(p)
            else:
                clusters.append(group)
                group = [p]
        clusters.append(group)
        # Return (median_price, count) sorted by count desc
        result = sorted(
            [(np.median(g), len(g)) for g in clusters],
            key=lambda x: x[1], reverse=True,
        )
        return result[:n_levels]

    resistance = _cluster(h[res_idx].tolist())
    support    = _cluster(l[sup_idx].tolist())
    return support, resistance


def _render_candlestick(hist: pd.DataFrame, currency: str, n_days: int = 126,
                        show_trend: bool = False):
    """Candlestick + Bollinger Bands / Volume / RSI subplots. Optional trend overlay."""
    from plotly.subplots import make_subplots

    has_ohlc = all(c in hist.columns for c in ['Open', 'High', 'Low', 'Close'])
    if not (not hist.empty and has_ohlc):
        if not hist.empty:
            st.info("OHLC 데이터를 인식할 수 없습니다.")
        return

    full = hist[['Open', 'High', 'Low', 'Close']].copy()
    has_vol = 'Volume' in hist.columns and hist['Volume'].notna().any()
    if has_vol:
        full['Volume'] = hist['Volume']

    # Bollinger Bands & RSI computed on full history for accuracy, then sliced
    bb_mid   = full['Close'].rolling(20).mean()
    bb_std   = full['Close'].rolling(20).std()
    bb_upper = bb_mid + 2 * bb_std
    bb_lower = bb_mid - 2 * bb_std
    rsi_full = _calc_rsi(full['Close'], 14)

    ma_map = {p: full['Close'].rolling(p, min_periods=1).mean() for p, _ in MA_LINES}

    # Slice to requested window
    data     = full.iloc[-n_days:]
    bb_mid   = bb_mid.iloc[-n_days:]
    bb_upper = bb_upper.iloc[-n_days:]
    bb_lower = bb_lower.iloc[-n_days:]
    rsi_s    = rsi_full.iloc[-n_days:]
    ma_map   = {p: s.iloc[-n_days:] for p, s in ma_map.items()}

    close  = data['Close']
    prefix = "$" if currency == 'USD' else "₩"
    fmt    = ',.2f' if currency == 'USD' else ',.0f'
    up     = close.iloc[-1] >= close.iloc[0]
    color  = '#4ade80' if up else '#f87171'

    x_pos     = list(range(len(data)))
    dates     = [str(d)[:10] for d in data.index]
    tick_step = max(1, len(data) // 8)
    tickvals  = list(range(0, len(data), tick_step))
    ticktext  = [dates[i] for i in tickvals]

    row_heights = [0.55, 0.20, 0.25] if has_vol else [0.65, 0, 0.35]
    n_rows = 3 if has_vol else 2

    if has_vol:
        fig = make_subplots(
            rows=3, cols=1, shared_xaxes=True,
            row_heights=[0.55, 0.20, 0.25],
            vertical_spacing=0.025,
        )
    else:
        fig = make_subplots(
            rows=2, cols=1, shared_xaxes=True,
            row_heights=[0.65, 0.35],
            vertical_spacing=0.03,
        )

    # ── Row 1: Candlestick ──────────────────────────────────────────────────
    fig.add_trace(go.Candlestick(
        x=x_pos,
        open=data['Open'], high=data['High'],
        low=data['Low'],   close=data['Close'],
        increasing=dict(line=dict(color='#4ade80', width=1), fillcolor='#4ade80'),
        decreasing=dict(line=dict(color='#f87171', width=1), fillcolor='#f87171'),
        name='캔들', showlegend=False,
        customdata=dates,
        hovertemplate=(
            '<b>%{customdata}</b><br>'
            f'시가 {prefix}%{{open:{fmt}}}<br>'
            f'고가 {prefix}%{{high:{fmt}}}<br>'
            f'저가 {prefix}%{{low:{fmt}}}<br>'
            f'종가 {prefix}%{{close:{fmt}}}<extra></extra>'
        ),
    ), row=1, col=1)

    # Bollinger Bands
    fig.add_trace(go.Scatter(
        x=x_pos, y=bb_upper.values, mode='lines', name='BB상단',
        line=dict(color='rgba(148,163,184,0.35)', width=1, dash='dot'),
        customdata=dates,
        hovertemplate='%{customdata}<br>BB상단: ' + prefix + '%{y:' + fmt + '}<extra></extra>',
    ), row=1, col=1)
    fig.add_trace(go.Scatter(
        x=x_pos, y=bb_lower.values, mode='lines', name='BB하단',
        line=dict(color='rgba(148,163,184,0.35)', width=1, dash='dot'),
        fill='tonexty', fillcolor='rgba(148,163,184,0.05)',
        customdata=dates,
        hovertemplate='%{customdata}<br>BB하단: ' + prefix + '%{y:' + fmt + '}<extra></extra>',
    ), row=1, col=1)
    fig.add_trace(go.Scatter(
        x=x_pos, y=bb_mid.values, mode='lines', name='BB중심(MA20)',
        line=dict(color='rgba(148,163,184,0.5)', width=1),
        customdata=dates,
        hovertemplate='%{customdata}<br>BB중심: ' + prefix + '%{y:' + fmt + '}<extra></extra>',
    ), row=1, col=1)

    # MA lines
    for period, ma_color in MA_LINES:
        if period == 20:
            continue  # BB 중심선(MA20)과 중복 생략
        ma_s = ma_map[period]
        fig.add_trace(go.Scatter(
            x=x_pos, y=ma_s.values, mode='lines', name=f'MA{period}',
            line=dict(color=ma_color, width=1.5),
            customdata=dates,
            hovertemplate='%{customdata}<br>MA' + str(period) + ': ' + prefix + '%{y:' + fmt + '}<extra></extra>',
        ), row=1, col=1)

    # Current price annotation
    fig.add_annotation(
        x=x_pos[-1], y=float(close.iloc[-1]),
        text=f" {prefix}{close.iloc[-1]:{fmt}}",
        showarrow=False, font=dict(color=color, size=11),
        xanchor='left', yanchor='middle',
        xref='x', yref='y',
    )

    # ── Trend overlay (optional) ────────────────────────────────────────────
    if show_trend:
        reg = _calc_regression_channel(close)
        if reg:
            slope = reg['slope_pct_per_month']
            r2    = reg['r2']
            trend_color = '#4ade80' if slope >= 0 else '#f87171'
            sign  = '+' if slope >= 0 else ''

            # ±2σ outer channel (very faint fill)
            fig.add_trace(go.Scatter(
                x=x_pos, y=reg['upper_2s'], mode='lines', name='추세 +2σ',
                line=dict(color='rgba(251,191,36,0.2)', width=1, dash='dot'),
                showlegend=False, hoverinfo='skip',
            ), row=1, col=1)
            fig.add_trace(go.Scatter(
                x=x_pos, y=reg['lower_2s'], mode='lines', name='추세 -2σ',
                line=dict(color='rgba(251,191,36,0.2)', width=1, dash='dot'),
                fill='tonexty', fillcolor='rgba(251,191,36,0.04)',
                showlegend=False, hoverinfo='skip',
            ), row=1, col=1)

            # ±1σ inner channel
            fig.add_trace(go.Scatter(
                x=x_pos, y=reg['upper_1s'], mode='lines', name='추세 +1σ',
                line=dict(color='rgba(251,191,36,0.45)', width=1, dash='dash'),
                showlegend=False, hoverinfo='skip',
            ), row=1, col=1)
            fig.add_trace(go.Scatter(
                x=x_pos, y=reg['lower_1s'], mode='lines', name='추세 -1σ',
                line=dict(color='rgba(251,191,36,0.45)', width=1, dash='dash'),
                fill='tonexty', fillcolor='rgba(251,191,36,0.07)',
                showlegend=False, hoverinfo='skip',
            ), row=1, col=1)

            # Center regression line
            fig.add_trace(go.Scatter(
                x=x_pos, y=reg['trend'], mode='lines', name='회귀 추세선',
                line=dict(color=trend_color, width=2, dash='dash'),
                customdata=dates,
                hovertemplate='%{customdata}<br>추세: ' + prefix + '%{y:' + fmt + '}<extra></extra>',
            ), row=1, col=1)

            # Slope annotation (top-left of chart)
            fig.add_annotation(
                x=0.01, y=0.97, xref='paper', yref='paper',
                text=f"📐 월 {sign}{slope:.1f}%  R²={r2:.2f}",
                showarrow=False,
                font=dict(color=trend_color, size=11),
                bgcolor='rgba(8,13,24,0.75)',
                bordercolor=trend_color, borderwidth=1,
                xanchor='left', yanchor='top',
            )

        # Support / Resistance horizontal lines
        support_levels, resistance_levels = _calc_support_resistance(
            data['High'], data['Low']
        )
        for price, strength in resistance_levels:
            fig.add_hline(
                y=price,
                line=dict(color='rgba(248,113,113,0.6)', width=1.2, dash='dot'),
                row=1, col=1,
            )
            fig.add_annotation(
                x=1.0, y=price, xref='paper', yref='y',
                text=f" R {prefix}{price:{fmt}}",
                showarrow=False, font=dict(color='#f87171', size=9),
                xanchor='left', yanchor='middle',
            )
        for price, strength in support_levels:
            fig.add_hline(
                y=price,
                line=dict(color='rgba(74,222,128,0.6)', width=1.2, dash='dot'),
                row=1, col=1,
            )
            fig.add_annotation(
                x=1.0, y=price, xref='paper', yref='y',
                text=f" S {prefix}{price:{fmt}}",
                showarrow=False, font=dict(color='#4ade80', size=9),
                xanchor='left', yanchor='middle',
            )

    # ── Row 2: Volume ───────────────────────────────────────────────────────
    if has_vol:
        vol_series = data['Volume'].fillna(0)
        vol_colors = [
            '#4ade80' if float(c) >= float(o) else '#f87171'
            for c, o in zip(data['Close'], data['Open'])
        ]
        fig.add_trace(go.Bar(
            x=x_pos, y=vol_series.values,
            marker_color=vol_colors, name='거래량', showlegend=False,
            customdata=dates,
            hovertemplate='%{customdata}<br>거래량: %{y:,.0f}<extra></extra>',
        ), row=2, col=1)

    # ── Row 3: RSI ─────────────────────────────────────────────────────────
    rsi_row = 3 if has_vol else 2
    rsi_vals = rsi_s.values

    # Overbought / oversold fill bands
    fig.add_hrect(y0=70, y1=100, line_width=0,
                  fillcolor='rgba(248,113,113,0.08)', row=rsi_row, col=1)
    fig.add_hrect(y0=0,  y1=30, line_width=0,
                  fillcolor='rgba(74,222,128,0.08)', row=rsi_row, col=1)

    rsi_colors = [
        '#f87171' if v >= 70 else ('#4ade80' if v <= 30 else '#94a3b8')
        for v in rsi_vals
    ]
    fig.add_trace(go.Scatter(
        x=x_pos, y=rsi_vals, mode='lines', name='RSI(14)',
        line=dict(color='#a78bfa', width=1.8),
        customdata=dates,
        hovertemplate='%{customdata}<br>RSI: %{y:.1f}<extra></extra>',
    ), row=rsi_row, col=1)

    for level, lc in [(70, '#f87171'), (50, '#374151'), (30, '#4ade80')]:
        fig.add_hline(y=level, line=dict(color=lc, width=1, dash='dot'),
                      row=rsi_row, col=1)

    # Latest RSI badge
    latest_rsi = float(rsi_s.dropna().iloc[-1]) if not rsi_s.dropna().empty else None
    if latest_rsi is not None:
        rsi_label_color = '#f87171' if latest_rsi >= 70 else ('#4ade80' if latest_rsi <= 30 else '#a78bfa')
        fig.add_annotation(
            x=x_pos[-1], y=latest_rsi,
            text=f" {latest_rsi:.1f}",
            showarrow=False, font=dict(color=rsi_label_color, size=10),
            xanchor='left', yanchor='middle',
            xref=f'x{rsi_row}' if rsi_row > 1 else 'x',
            yref=f'y{rsi_row}' if rsi_row > 1 else 'y',
        )

    # ── Layout ──────────────────────────────────────────────────────────────
    shared_xaxis = dict(
        tickfont=dict(color='#64748b'), gridcolor='#1f2937',
        showgrid=False, rangeslider=dict(visible=False),
        type='category', tickmode='array',
        tickvals=tickvals, ticktext=ticktext, tickangle=-30,
    )
    total_height = 560 if has_vol else 480

    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'height':    total_height,
        'margin':    dict(l=0, r=64, t=44, b=0),
        'hovermode': 'x unified',
        'showlegend': True,
        'legend': dict(
            orientation='h', yanchor='bottom', y=1.02, xanchor='right', x=1,
            font=dict(color='#94a3b8', size=11),
        ),
        'title': dict(text="캔들차트 + 볼린저밴드 / 거래량 / RSI(14)",
                      font=dict(color='#e2e8f0', size=13)),
    })
    fig.update_xaxes(**shared_xaxis)
    fig.update_yaxes(
        tickfont=dict(color='#64748b'), gridcolor='#1f2937',
        tickprefix=prefix, showgrid=True, row=1, col=1,
    )
    if has_vol:
        fig.update_yaxes(
            tickfont=dict(color='#64748b'), gridcolor='#1f2937',
            title_text='거래량', title_font=dict(size=10, color='#64748b'),
            showgrid=False, row=2, col=1,
        )
    fig.update_yaxes(
        tickfont=dict(color='#64748b'), gridcolor='#1f2937',
        title_text='RSI', title_font=dict(size=10, color='#64748b'),
        range=[0, 100], showgrid=True, row=rsi_row, col=1,
    )
    st.plotly_chart(fig, width="stretch")


@st.dialog("⚡ 투자 전략 상세", width="large")
def show_strategy_dialog(strategy_raw: str):
    detail = _STRATEGY_DETAIL.get(strategy_raw, {})

    st.markdown(f"### {strategy_raw}")
    if detail.get("description"):
        st.caption(detail["description"])

    # ── Risk Parity 개념 설명 ──────────────────────────────────────────────────
    rp = _RISK_PARITY_EXPLANATION
    st.divider()
    st.markdown("**📘 Risk Parity란?**")
    st.markdown(rp["concept"])

    c_why = st.columns(3)
    for col, (title, body) in zip(c_why, rp["why"]):
        with col:
            st.markdown(f"""
            <div class="stat-item">
                <div class="stat-label">{title}</div>
                <div class="stat-value" style="font-size:0.82rem;color:#94a3b8;
                     font-weight:500;line-height:1.4;">{body}</div>
            </div>""", unsafe_allow_html=True)

    with st.expander("📐 수식 & 최적화 방법", expanded=False):
        st.markdown(rp["math"])

    # ── Pipeline Steps ─────────────────────────────────────────────────────────
    if detail.get("steps"):
        st.divider()
        st.markdown("**📋 Pipeline Steps**")
        for step, desc in detail["steps"]:
            st.markdown(f"**{step}** — {desc}")

    # ── Factor Weights ─────────────────────────────────────────────────────────
    if detail.get("factors"):
        st.divider()
        st.markdown("**⚖️ Factor Weights**")
        cols = st.columns(len(detail["factors"]))
        for col, (fname, fdesc) in zip(cols, detail["factors"].items()):
            with col:
                st.markdown(f"""
                <div class="stat-item highlight">
                    <div class="stat-label">{fname}</div>
                    <div class="stat-value" style="font-size:0.82rem;font-weight:600;
                         color:#94a3b8;">{fdesc}</div>
                </div>""", unsafe_allow_html=True)

    if not detail:
        st.info("전략 설명이 없습니다.")
