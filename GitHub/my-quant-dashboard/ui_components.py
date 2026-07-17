from __future__ import annotations

import streamlit as st
import pandas as pd
import plotly.graph_objects as go

from data_loader import _logo_url


# ── Moving Average Settings ────────────────────────────────────────────────────
# Change the numbers below to customise which MA lines appear on the chart.
# Format: (period, colour)  — add or remove rows freely.
MA_LINES = [
    (5,   '#facc15'),   # MA5   — yellow
    (20,  '#60a5fa'),   # MA20  — blue
    (120, '#f472b6'),   # MA120 — pink
]


# ── Plotly theme ───────────────────────────────────────────────────────────────
PLOTLY_LAYOUT = dict(
    template="plotly_dark",
    plot_bgcolor="#111827",
    paper_bgcolor="#080d18",
    font=dict(family="Inter, sans-serif", color="#64748b"),
    margin=dict(l=16, r=16, t=44, b=16),
    colorway=["#63b3ed","#4ade80","#fbbf24","#f87171","#a78bfa",
               "#34d399","#fb923c","#60a5fa","#e879f9","#2dd4bf"],
)


# ── Meta card config ───────────────────────────────────────────────────────────
_META_EXCLUDE: frozenset = frozenset()

_META_CONFIG = {
    'Strategy':                ('Strategy',        'highlight'),
    'Number of Stocks':        ('# Stocks',        'highlight'),
    'Ann. Volatility (est.)':  ('Ann. Volatility', ''),
    'Ann. Return (hist. est.)':('Ann. Return',      'green'),
    'Sharpe (est.)':           ('Sharpe Ratio',     'green'),
    'Max Weight':              ('Max Weight',       ''),
    'Min Weight':              ('Min Weight',       ''),
    'Generated':               ('Generated',        'yellow'),
}


# ── UI helpers ─────────────────────────────────────────────────────────────────
def section_header(title, icon=""):
    prefix = f"{icon}&nbsp;" if icon else ""
    st.markdown(f"""
    <div class="section-header">
        <h3>{prefix}{title}</h3>
        <div class="section-divider"></div>
    </div>""", unsafe_allow_html=True)


def render_meta_cards(meta: dict, tab_key: str = ""):
    if not meta:
        return

    meta = {k: v for k, v in meta.items()
            if k not in _META_EXCLUDE and not k.startswith('──')}
    if not meta:
        return

    strategy_raw = meta.get('Strategy', '')

    # ── Format numeric values
    formatted = {}
    for key, value in meta.items():
        try:
            f = float(value)
            if key in ('Ann. Volatility (est.)', 'Ann. Return (hist. est.)'):
                formatted[key] = f"{f:.2%}"
            elif key == 'Sharpe (est.)':
                formatted[key] = f"{f:.2f}"
            else:
                formatted[key] = value
        except (ValueError, TypeError):
            formatted[key] = value

    # ── Non-Strategy stat cards as HTML (identical .stat-item blocks)
    items_html = ""
    for key, value in formatted.items():
        if key == 'Strategy':
            continue
        label, css = _META_CONFIG.get(key, (key, ''))
        items_html += f"""
        <div class="stat-item {css}">
            <div class="stat-label">{label}</div>
            <div class="stat-value">{value}</div>
        </div>"""

    # ── Strategy button (styled via globally loaded CSS) + other cards side by side
    col_btn, col_cards = st.columns([1, 3])

    with col_btn:
        btn_label = strategy_raw if strategy_raw else "Strategy"
        if st.button(btn_label, key=f"strat_btn_{tab_key}",
                     width="stretch"):
            st.session_state['_strategy_dlg'] = strategy_raw

    with col_cards:
        st.markdown(f'<div class="stats-grid">{items_html}</div>',
                    unsafe_allow_html=True)


def render_quick_metrics(df, currency='USD'):
    n        = len(df)
    top_row  = df.iloc[0]
    top_name = top_row.get('Name', '—') if 'Name' in df.columns else "—"
    top_tick = top_row.get('Ticker', '')  if 'Ticker' in df.columns else ''
    logo     = _logo_url(top_tick, currency) if top_tick else ''

    max_weight = exp_ret = "—"
    if 'Weight(%)' in df.columns and df['Weight(%)'].notna().any():
        max_weight = f"{df['Weight(%)'].max():.2%}"
    if 'Expected_Return' in df.columns and df['Expected_Return'].notna().any():
        er = df['Expected_Return'].dropna().iloc[0]
        exp_ret = f"{er:.2%}"
        er_class = "green" if er >= 0 else "red"
    else:
        er_class = "green"

    logo_html = (
        f'<img src="{logo}" style="width:44px;height:44px;object-fit:contain;'
        f'border-radius:10px;background:#0a0f1e;flex-shrink:0;" '
        f'onerror="this.style.display=\'none\'">'
    ) if logo else '<div class="qm-icon">🏆</div>'

    st.markdown(f"""
    <div class="quick-metrics">
        <div class="qm-card">
            <div class="qm-icon">📦</div>
            <div>
                <div class="qm-label">Holdings</div>
                <div class="qm-value blue">{n}</div>
                <div class="qm-sub">Stocks</div>
            </div>
        </div>
        <div class="qm-card">
            {logo_html}
            <div>
                <div class="qm-label">Top Pick</div>
                <div class="qm-value" style="font-size:1rem;">{top_name}</div>
                <div class="qm-sub">{top_tick}</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">⚖️</div>
            <div>
                <div class="qm-label">Max Weight</div>
                <div class="qm-value yellow">{max_weight}</div>
                <div class="qm-sub">Risk-Parity</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">📈</div>
            <div>
                <div class="qm-label">Exp. Return</div>
                <div class="qm-value {er_class}">{exp_ret}</div>
                <div class="qm-sub">1-Year Hist.</div>
            </div>
        </div>
    </div>
    """, unsafe_allow_html=True)


def render_smallcap_quick_metrics(df, currency='USD'):
    n         = len(df)
    top_row   = df.iloc[0]
    label_col = 'Name' if (currency == 'KRW' and 'Name' in df.columns) else 'Ticker'
    top_pick  = top_row.get(label_col, '—') if label_col in df.columns else "—"
    top_tick  = top_row.get('Ticker', '')   if 'Ticker' in df.columns else ''
    logo      = _logo_url(top_tick, currency) if top_tick else ''

    avg_score = avg_roic = "—"
    if 'Total_Score' in df.columns and df['Total_Score'].notna().any():
        avg_score = f"{df['Total_Score'].mean():.1f} pts"
    if 'ROIC' in df.columns and df['ROIC'].notna().any():
        avg_roic = f"{df['ROIC'].mean():.1%}"

    logo_html = (
        f'<img src="{logo}" style="width:44px;height:44px;object-fit:contain;'
        f'border-radius:10px;background:#0a0f1e;flex-shrink:0;" '
        f'onerror="this.style.display=\'none\'">'
    ) if logo else '<div class="qm-icon">🥇</div>'

    st.markdown(f"""
    <div class="quick-metrics">
        <div class="qm-card">
            <div class="qm-icon">💎</div>
            <div>
                <div class="qm-label">Gems Found</div>
                <div class="qm-value blue">{n}</div>
                <div class="qm-sub">Top candidates</div>
            </div>
        </div>
        <div class="qm-card">
            {logo_html}
            <div>
                <div class="qm-label">Top Gem</div>
                <div class="qm-value" style="font-size:1rem;">{top_pick}</div>
                <div class="qm-sub">{top_tick}</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">🎯</div>
            <div>
                <div class="qm-label">Avg Gem Score</div>
                <div class="qm-value yellow">{avg_score}</div>
                <div class="qm-sub">Max ~115 pts</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">💰</div>
            <div>
                <div class="qm-label">Avg ROIC</div>
                <div class="qm-value green">{avg_roic}</div>
                <div class="qm-sub">Universe avg.</div>
            </div>
        </div>
    </div>
    """, unsafe_allow_html=True)


def render_charts(df, currency='USD'):
    has_weight = 'Weight(%)' in df.columns and df['Weight(%)'].notna().any()
    has_sector = 'Sector'    in df.columns and df['Sector'].notna().any()
    if not has_weight:
        return

    col_left, col_right = st.columns(2)
    label_col = 'Name' if (currency == 'KRW' and 'Name' in df.columns) else 'Ticker'

    # Left: Top-10 weight bar
    with col_left:
        cols_needed = [c for c in [label_col, 'Weight(%)'] if c in df.columns]
        top10 = df.nlargest(10, 'Weight(%)')[cols_needed].copy()
        top10 = top10.sort_values('Weight(%)')
        fig = go.Figure(go.Bar(
            x=top10['Weight(%)'] * 100,
            y=top10[label_col],
            orientation='h',
            marker=dict(
                color=top10['Weight(%)'],
                colorscale=[[0, '#1e3a5f'], [0.5, '#2563eb'], [1, '#63b3ed']],
                showscale=False,
            ),
            text=[f"{v*100:.1f}%" for v in top10['Weight(%)']],
            textposition='outside',
            textfont=dict(color='#94a3b8', size=11),
        ))
        fig.update_layout(
            **PLOTLY_LAYOUT,
            title=dict(text="Top 10 Holdings by Weight", font=dict(color='#e2e8f0', size=13)),
            xaxis=dict(ticksuffix="%", tickfont=dict(color='#64748b'), gridcolor='#1f2937', showgrid=True),
            yaxis=dict(tickfont=dict(color='#94a3b8', size=10)),
            height=330,
        )
        st.plotly_chart(fig, width="stretch")

    # Right: Sector donut or score histogram
    with col_right:
        if has_sector:
            sec = df.groupby('Sector')['Weight(%)'].sum().reset_index()
            sec = sec[sec['Sector'].str.strip() != ''].sort_values('Weight(%)', ascending=False)
            fig2 = go.Figure(go.Pie(
                labels=sec['Sector'],
                values=sec['Weight(%)'],
                hole=0.56,
                textinfo='label+percent',
                textfont=dict(size=10, color='#e2e8f0'),
                marker=dict(
                    colors=PLOTLY_LAYOUT['colorway'],
                    line=dict(color='#080d18', width=2),
                ),
                hovertemplate='<b>%{label}</b><br>Weight: %{percent}<extra></extra>',
            ))
            fig2.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="Sector Allocation", font=dict(color='#e2e8f0', size=13)),
                showlegend=False, height=330,
            )
            st.plotly_chart(fig2, width="stretch")
        elif 'Total_Score' in df.columns and df['Total_Score'].notna().any():
            fig3 = go.Figure(go.Histogram(
                x=df['Total_Score'].dropna() * 100,
                nbinsx=10, marker_color='#63b3ed', opacity=0.75,
            ))
            fig3.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="Score Distribution", font=dict(color='#e2e8f0', size=13)),
                xaxis=dict(title="Score (%)", tickfont=dict(color='#64748b')),
                yaxis=dict(title="Count", tickfont=dict(color='#64748b'), gridcolor='#1f2937'),
                height=330,
            )
            st.plotly_chart(fig3, width="stretch")


def render_smallcap_charts(df, currency='USD'):
    if 'Total_Score' not in df.columns or not df['Total_Score'].notna().any():
        return

    label_col = 'Name' if (currency == 'KRW' and 'Name' in df.columns) else 'Ticker'
    col_left, col_right = st.columns(2)

    with col_left:
        top10 = df.nlargest(10, 'Total_Score')[[label_col, 'Total_Score']].copy()
        top10 = top10.sort_values('Total_Score')
        fig = go.Figure(go.Bar(
            x=top10['Total_Score'], y=top10[label_col], orientation='h',
            marker=dict(
                color=top10['Total_Score'],
                colorscale=[[0, '#422006'], [0.5, '#b45309'], [1, '#fbbf24']],
                showscale=False,
            ),
            text=[f"{v:.1f}" for v in top10['Total_Score']],
            textposition='outside',
            textfont=dict(color='#94a3b8', size=11),
        ))
        fig.update_layout(
            **PLOTLY_LAYOUT,
            title=dict(text="Top 10 Gems by Score", font=dict(color='#e2e8f0', size=13)),
            xaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937'),
            yaxis=dict(tickfont=dict(color='#94a3b8', size=10)),
            height=340,
        )
        st.plotly_chart(fig, width="stretch")

    with col_right:
        if 'Volume_Surge' in df.columns and df['Volume_Surge'].notna().any():
            vol_df = df[[label_col, 'Volume_Surge']].dropna().nlargest(10, 'Volume_Surge')
            vol_df = vol_df.sort_values('Volume_Surge')
            fig2 = go.Figure(go.Bar(
                x=vol_df['Volume_Surge'], y=vol_df[label_col], orientation='h',
                marker=dict(
                    color=vol_df['Volume_Surge'],
                    colorscale=[[0, '#052e16'], [0.5, '#166534'], [1, '#4ade80']],
                    showscale=False,
                ),
                text=[f"{v:.2f}x" for v in vol_df['Volume_Surge']],
                textposition='outside',
                textfont=dict(color='#94a3b8', size=11),
            ))
            fig2.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="Top 10 Volume Surge (3M avg)", font=dict(color='#e2e8f0', size=13)),
                xaxis=dict(ticksuffix="x", tickfont=dict(color='#64748b'), gridcolor='#1f2937'),
                yaxis=dict(tickfont=dict(color='#94a3b8', size=10)),
                height=340,
            )
            st.plotly_chart(fig2, width="stretch")
        elif 'ROIC' in df.columns and df['ROIC'].notna().any():
            roic_df = df[[label_col, 'ROIC']].dropna().nlargest(10, 'ROIC').sort_values('ROIC')
            fig2 = go.Figure(go.Bar(
                x=roic_df['ROIC'] * 100, y=roic_df[label_col], orientation='h',
                marker_color='#4ade80',
                text=[f"{v*100:.1f}%" for v in roic_df['ROIC']],
                textposition='outside', textfont=dict(color='#94a3b8', size=11),
            ))
            fig2.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="Top 10 ROIC", font=dict(color='#e2e8f0', size=13)),
                xaxis=dict(ticksuffix="%", tickfont=dict(color='#64748b'), gridcolor='#1f2937'),
                yaxis=dict(tickfont=dict(color='#94a3b8', size=10)),
                height=340,
            )
            st.plotly_chart(fig2, width="stretch")


# ── Backtest KPI cards ────────────────────────────────────────────────────────
def render_backtest_kpi(meta: dict):
    """Render the 6 key performance metric cards from the backtest summary."""
    def _pct(key, fallback='—'):
        v = meta.get(key)
        try:
            return f"{float(v):.2%}"
        except Exception:
            return fallback

    def _f2(key, fallback='—'):
        v = meta.get(key)
        try:
            return f"{float(v):.2f}"
        except Exception:
            return fallback

    cagr     = _pct('CAGR')
    tot_ret  = _pct('Total Return')
    sharpe   = _f2('Sharpe Ratio')
    max_dd   = _pct('Max Drawdown')
    win_rate = _pct('Win Rate')
    vol      = _pct('Ann. Volatility')
    periods  = meta.get('Periods', '—')
    generated= meta.get('Generated', '—')

    def _color(key):
        v = meta.get(key)
        try:
            return 'green' if float(v) >= 0 else 'red'
        except Exception:
            return 'blue'

    st.markdown(f"""
    <div class="quick-metrics" style="grid-template-columns: repeat(6,1fr);">
        <div class="qm-card">
            <div class="qm-icon">📈</div>
            <div>
                <div class="qm-label">CAGR</div>
                <div class="qm-value {_color('CAGR')}">{cagr}</div>
                <div class="qm-sub">연환산 수익률</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">💰</div>
            <div>
                <div class="qm-label">Total Return</div>
                <div class="qm-value {_color('Total Return')}">{tot_ret}</div>
                <div class="qm-sub">누적 수익률</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">⚡</div>
            <div>
                <div class="qm-label">Sharpe Ratio</div>
                <div class="qm-value yellow">{sharpe}</div>
                <div class="qm-sub">위험 대비 수익</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">🛡️</div>
            <div>
                <div class="qm-label">Max Drawdown</div>
                <div class="qm-value red">{max_dd}</div>
                <div class="qm-sub">최대 낙폭</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">🎯</div>
            <div>
                <div class="qm-label">Win Rate</div>
                <div class="qm-value blue">{win_rate}</div>
                <div class="qm-sub">승률</div>
            </div>
        </div>
        <div class="qm-card">
            <div class="qm-icon">📅</div>
            <div>
                <div class="qm-label">Periods</div>
                <div class="qm-value blue">{periods}</div>
                <div class="qm-sub">리밸런싱 횟수</div>
            </div>
        </div>
    </div>
    <div style="text-align:right;font-size:0.72rem;color:#374151;margin-top:-10px;margin-bottom:16px;">
        Generated: {generated} &nbsp;|&nbsp; Ann. Volatility: {vol}
    </div>
    """, unsafe_allow_html=True)


def render_backtest_charts(ret_df: 'pd.DataFrame', detail_df: 'pd.DataFrame',
                           nasdaq_cum: 'pd.Series | None' = None,
                           benchmark_label: str = 'NASDAQ Composite (^IXIC)',
                           benchmark_hover: str = '나스닥 수익'):
    """Render cumulative return (vs benchmark), period return bars, loss distribution, detail table."""
    import plotly.graph_objects as go
    import pandas as pd

    if ret_df.empty:
        st.info("수익률 데이터가 없습니다.")
        return

    has_cum = 'Cumulative_Ret' in ret_df.columns and ret_df['Cumulative_Ret'].notna().any()
    has_dd  = 'Drawdown'       in ret_df.columns and ret_df['Drawdown'].notna().any()
    has_ret = 'Net_Return'     in ret_df.columns and ret_df['Net_Return'].notna().any()
    dates   = ret_df['Date'] if 'Date' in ret_df.columns else ret_df.index

    # ── Chart 1: Cumulative return curve + benchmark ──────────────────────────
    if has_cum:
        cum       = ret_df['Cumulative_Ret'].dropna()
        final_val = cum.iloc[-1]
        line_color = '#4ade80' if final_val >= 1.0 else '#f87171'

        fig_cum = go.Figure()

        # ── Strategy line (fill to zero)
        fig_cum.add_trace(go.Scatter(
            x=dates, y=(ret_df['Cumulative_Ret'] - 1) * 100,
            mode='lines',
            line=dict(color=line_color, width=2.5),
            fill='tozeroy',
            fillcolor='rgba(74,222,128,0.06)' if final_val >= 1.0 else 'rgba(248,113,113,0.06)',
            name='My Strategy',
            hovertemplate='<b>%{x|%Y-%m-%d}</b><br>전략 수익: %{y:.2f}%<extra></extra>',
        ))

        # ── Benchmark line
        has_bench = nasdaq_cum is not None and not nasdaq_cum.empty
        if has_bench:
            bench_final = float(nasdaq_cum.iloc[-1])
            fig_cum.add_trace(go.Scatter(
                x=nasdaq_cum.index, y=nasdaq_cum.values,
                mode='lines',
                line=dict(color='#63b3ed', width=1.8, dash='dot'),
                name=benchmark_label,
                hovertemplate=f'<b>%{{x|%Y-%m-%d}}</b><br>{benchmark_hover}: %{{y:.2f}}%<extra></extra>',
            ))
            short_label = benchmark_label.split('(')[0].strip()
            fig_cum.add_annotation(
                x=nasdaq_cum.index[-1], y=bench_final,
                text=f"  {short_label} {bench_final:+.1f}%",
                showarrow=False,
                font=dict(color='#63b3ed', size=11, family='Inter'),
                xanchor='left', yanchor='middle',
            )

        # Baseline
        fig_cum.add_hline(y=0, line=dict(color='#374151', width=1, dash='dot'))

        # Strategy final annotation
        fig_cum.add_annotation(
            x=dates.iloc[-1], y=(final_val - 1) * 100,
            text=f"  전략 {(final_val-1)*100:+.1f}%",
            showarrow=False,
            font=dict(color=line_color, size=12, family='Inter'),
            xanchor='left', yanchor='middle',
        )

        # Alpha annotation (outperformance vs benchmark)
        if has_bench:
            alpha = (final_val - 1) * 100 - bench_final
            alpha_color = '#4ade80' if alpha >= 0 else '#f87171'
            alpha_sign  = '+' if alpha >= 0 else ''
            short_label = benchmark_label.split('(')[0].strip()
            fig_cum.add_annotation(
                x=0.01, y=0.97,
                xref='paper', yref='paper',
                text=f"α (vs {short_label}) {alpha_sign}{alpha:.1f}%p",
                showarrow=False,
                font=dict(color=alpha_color, size=12, family='Inter'),
                bgcolor='rgba(0,0,0,0.4)',
                bordercolor=alpha_color,
                borderwidth=1,
                borderpad=5,
                align='left',
            )

        bench_short = benchmark_label.split('(')[0].strip()
        fig_cum.update_layout(
            **PLOTLY_LAYOUT,
            title=dict(text=f"📈 누적 수익률 — My Strategy vs {bench_short}",
                       font=dict(color='#e2e8f0', size=14)),
            xaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937',
                       showgrid=False),
            yaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937',
                       ticksuffix='%', showgrid=True, zeroline=False),
            height=400,
            showlegend=True,
            legend=dict(
                font=dict(color='#94a3b8', size=11),
                bgcolor='rgba(8,13,24,0.7)',
                bordercolor='#1f2937', borderwidth=1,
                x=0.01, y=0.06,
            ),
        )
        st.plotly_chart(fig_cum, width="stretch")

    # ── Charts 2·3 — two columns: 기간별 수익 | 손해율 분포 ──────────────────────
    col2, col3 = st.columns(2)

    # ── Col 1: 기간별 수익 — per-period return bars (time-series) ─────────────
    with col2:
        if has_ret:
            nr_series = ret_df['Net_Return'].fillna(0) * 100
            bar_colors = [
                '#4ade80' if v >= 0 else '#f87171'
                for v in nr_series
            ]
            fig_bar = go.Figure()
            fig_bar.add_trace(go.Bar(
                x=dates,
                y=nr_series,
                marker_color=bar_colors,
                name='기간 수익률',
                hovertemplate='<b>%{x|%Y-%m-%d}</b><br>수익: %{y:.2f}%<extra></extra>',
            ))
            fig_bar.add_hline(y=0, line=dict(color='#374151', width=1))
            fig_bar.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(text="📊 기간별 수익 (Net Return per Period)",
                           font=dict(color='#e2e8f0', size=13)),
                xaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937',
                           showgrid=False),
                yaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937',
                           ticksuffix='%', showgrid=True, zeroline=False),
                bargap=0.15,
                height=320, showlegend=False,
            )
            st.plotly_chart(fig_bar, width="stretch")

    # ── Col 3: 손해율 분포 — return distribution histogram (gain vs loss) ──────
    with col3:
        if has_ret:
            nr_all = ret_df['Net_Return'].dropna() * 100
            pos    = nr_all[nr_all >= 0]
            neg    = nr_all[nr_all < 0]
            total  = len(nr_all)
            win_n  = len(pos)
            loss_n = len(neg)
            avg_gain = pos.mean() if len(pos) else 0
            avg_loss = neg.mean() if len(neg) else 0

            fig_hist = go.Figure()
            fig_hist.add_trace(go.Histogram(
                x=pos, nbinsx=12,
                marker_color='rgba(74,222,128,0.75)',
                name=f'수익 ({win_n}회)',
                hovertemplate='구간: %{x:.2f}%<br>횟수: %{y}<extra></extra>',
            ))
            fig_hist.add_trace(go.Histogram(
                x=neg, nbinsx=12,
                marker_color='rgba(248,113,113,0.75)',
                name=f'손실 ({loss_n}회)',
                hovertemplate='구간: %{x:.2f}%<br>횟수: %{y}<extra></extra>',
            ))
            # Avg gain / avg loss reference lines
            if len(pos):
                fig_hist.add_vline(x=avg_gain,
                    line=dict(color='#4ade80', width=1.2, dash='dash'))
            if len(neg):
                fig_hist.add_vline(x=avg_loss,
                    line=dict(color='#f87171', width=1.2, dash='dash'))

            win_rate_pct = win_n / total * 100 if total else 0
            fig_hist.update_layout(
                **PLOTLY_LAYOUT,
                title=dict(
                    text=f"🎯 손해율 분포  (승률 {win_rate_pct:.0f}% · 평균수익 {avg_gain:+.2f}% / 평균손실 {avg_loss:+.2f}%)",
                    font=dict(color='#e2e8f0', size=11),
                ),
                barmode='overlay',
                xaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937',
                           ticksuffix='%', showgrid=False),
                yaxis=dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937',
                           title='횟수', showgrid=True),
                height=320,
                legend=dict(font=dict(color='#94a3b8', size=10),
                            bgcolor='rgba(0,0,0,0)', x=0.6, y=0.95),
            )
            st.plotly_chart(fig_hist, width="stretch")

    # ── Period detail table ────────────────────────────────────────────────────
    if not detail_df.empty:
        section_header("기간별 상세 내역", "🗂️")

        disp = detail_df.copy()
        for col in ['Gross_Return', 'Fee', 'Net_Return']:
            if col in disp.columns:
                disp[col] = disp[col].apply(
                    lambda x: f"{x:.2%}" if pd.notna(x) else '—')
        if 'Turnover_Pct' in disp.columns:
            disp['Turnover_Pct'] = disp['Turnover_Pct'].apply(
                lambda x: f"{x:.0%}" if pd.notna(x) else '—')

        st.dataframe(
            disp,
            width="stretch",
            hide_index=True,
            height=320,
        )

