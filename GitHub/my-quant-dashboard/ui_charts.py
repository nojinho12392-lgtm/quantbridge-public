from __future__ import annotations

import streamlit as st
import pandas as pd
import plotly.graph_objects as go
import numpy as np

from data_loader import _logo_url
from ui_components import PLOTLY_LAYOUT

def _pct(v, decimals=2):
    """Format float as percentage string, return '—' for None/NaN."""
    try:
        return f"{float(v):+.{decimals}%}"
    except (TypeError, ValueError):
        return "—"


def render_attribution_waterfall(summary: dict, market: str = "US"):
    """
    Waterfall chart decomposing portfolio return into V / Q / M / Residual.

    summary dict keys (numeric):
      Portfolio_Return, Value_Contrib, Quality_Contrib, Momentum_Contrib, Residual
    """
    keys   = ['Value_Contrib', 'Quality_Contrib', 'Momentum_Contrib', 'Residual']
    labels = ['Value', 'Quality', 'Momentum', 'Residual']
    colors_pos = '#4ade80'   # green for positive contributions
    colors_neg = '#f87171'   # red   for negative contributions

    total  = summary.get('Portfolio_Return')
    contribs = [summary.get(k) for k in keys]

    valid_contribs = [c for c in contribs if c is not None]
    if not valid_contribs or total is None:
        st.info("귀인분석 데이터가 없습니다.")
        return

    # Build waterfall: starting bar = 0 → running total after each factor
    measures = ['relative'] * len(labels) + ['total']
    x_labels = labels + ['Total Return']
    y_values = contribs + [total]

    # Colour: positive green, negative red, total blue
    bar_colors = []
    for c in contribs:
        if c is None:
            bar_colors.append('#64748b')
        elif c >= 0:
            bar_colors.append(colors_pos)
        else:
            bar_colors.append(colors_neg)
    bar_colors.append('#63b3ed')  # total bar = blue

    text_labels = [_pct(c) for c in contribs] + [_pct(total)]

    fig = go.Figure(go.Waterfall(
        orientation='v',
        measure=measures,
        x=x_labels,
        y=y_values,
        text=text_labels,
        textposition='outside',
        textfont=dict(size=13, color='#e2e8f0'),
        connector=dict(line=dict(color='#374151', width=1.5, dash='dot')),
        increasing=dict(marker=dict(color=colors_pos)),
        decreasing=dict(marker=dict(color=colors_neg)),
        totals=dict(marker=dict(color='#63b3ed')),
        hovertemplate='<b>%{x}</b><br>Contribution: %{y:.4f}<extra></extra>',
    ))

    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'height': 360,
        'title': dict(
            text=f"{market} 수익률 귀인분석 (since last rebalance)",
            font=dict(size=13, color='#94a3b8'),
            x=0.01,
        ),
        'yaxis': dict(
            tickformat='.1%',
            gridcolor='#1e293b',
            zeroline=True,
            zerolinecolor='#374151',
            zerolinewidth=1,
        ),
        'xaxis': dict(tickfont=dict(size=13)),
        'showlegend': False,
        'bargap': 0.45,
    })
    st.plotly_chart(fig, width="stretch")


def render_attribution_kpi(summary: dict, market: str = "US"):
    """Row of KPI metric cards for one market's attribution summary."""
    total    = summary.get('Portfolio_Return')
    contrib_v = summary.get('Value_Contrib')
    contrib_q = summary.get('Quality_Contrib')
    contrib_m = summary.get('Momentum_Contrib')
    residual  = summary.get('Residual')
    r2        = summary.get('R_Squared')
    beta_v    = summary.get('Beta_V')
    beta_q    = summary.get('Beta_Q')
    beta_m    = summary.get('Beta_M')
    period    = f"{summary.get('Period_Start','?')} → {summary.get('Period_End','?')}"
    days      = summary.get('Days', '')

    def _card(label, value, cls=''):
        return f"""<div class="stat-item {cls}">
            <div class="stat-label">{label}</div>
            <div class="stat-value">{value}</div>
        </div>"""

    def _signed_cls(v):
        if v is None:
            return ''
        return 'green' if float(v) >= 0 else 'red'

    st.markdown(f"""
    <div style="font-size:0.78rem;color:#64748b;margin-bottom:10px;">
        📅 {period}  ({days}일)  ·  R² = {f"{r2:.3f}" if r2 is not None else "—"}
        (팩터 모델 설명력)
    </div>
    """, unsafe_allow_html=True)

    cols = st.columns(5)
    cards = [
        ("Total Return",  _pct(total),     _signed_cls(total)),
        ("Value",         _pct(contrib_v), _signed_cls(contrib_v)),
        ("Quality",       _pct(contrib_q), _signed_cls(contrib_q)),
        ("Momentum",      _pct(contrib_m), _signed_cls(contrib_m)),
        ("Residual",      _pct(residual),  _signed_cls(residual)),
    ]
    for col, (label, value, cls) in zip(cols, cards):
        with col:
            st.markdown(_card(label, value, cls), unsafe_allow_html=True)

    # Factor beta row
    beta_cols = st.columns(3)
    beta_cards = [
        (f"β_Value = {f'{beta_v:+.4f}' if beta_v is not None else '—'}",
         "Value 팩터 수익 (단위 노출당)"),
        (f"β_Quality = {f'{beta_q:+.4f}' if beta_q is not None else '—'}",
         "Quality 팩터 수익 (단위 노출당)"),
        (f"β_Momentum = {f'{beta_m:+.4f}' if beta_m is not None else '—'}",
         "Momentum 팩터 수익 (단위 노출당)"),
    ]
    for col, (val_str, desc) in zip(beta_cols, beta_cards):
        with col:
            st.markdown(f"""<div class="stat-item">
                <div class="stat-label">{desc}</div>
                <div class="stat-value" style="font-size:0.95rem;">{val_str}</div>
            </div>""", unsafe_allow_html=True)


def render_attribution_detail(detail_df: pd.DataFrame, market: str = "US"):
    """Scrollable per-stock attribution table filtered by market."""
    df = detail_df[detail_df['Market'] == market].copy() if 'Market' in detail_df.columns else detail_df.copy()
    if df.empty:
        st.info(f"{market} 종목별 귀인 데이터가 없습니다.")
        return

    currency = 'KRW' if market == 'KR' else 'USD'
    df.insert(0, 'Logo', df['Ticker'].apply(lambda t: _logo_url(t, currency)))

    # Format columns for display
    for col in ['Return', 'Predicted_Return', 'Stock_Residual']:
        if col in df.columns:
            df[col] = df[col].apply(lambda x: f"{x:+.2%}" if pd.notna(x) else "—")
    for col in ['V_Exposure', 'Q_Exposure', 'M_Exposure']:
        if col in df.columns:
            df[col] = df[col].apply(lambda x: f"{x:+.2f}" if pd.notna(x) else "—")
    if 'Weight' in df.columns:
        df['Weight'] = df['Weight'].apply(lambda x: f"{x:.2%}" if pd.notna(x) else "—")

    display_cols = ['Logo', 'Ticker', 'Name', 'Sector', 'Weight',
                    'Return', 'V_Exposure', 'Q_Exposure', 'M_Exposure',
                    'Predicted_Return', 'Stock_Residual']
    display_cols = [c for c in display_cols if c in df.columns]

    st.dataframe(
        df[display_cols],
        width="stretch",
        hide_index=True,
        column_config={
            "Logo":             st.column_config.ImageColumn("", width=40),
            "Ticker":           st.column_config.TextColumn("Ticker",      width=90),
            "Name":             st.column_config.TextColumn("Name",        width=165),
            "Sector":           st.column_config.TextColumn("Sector",      width=130),
            "Weight":           st.column_config.TextColumn("Weight",      width=75),
            "Return":           st.column_config.TextColumn("Return",      width=85),
            "V_Exposure":       st.column_config.TextColumn("V-Exposure",  width=90),
            "Q_Exposure":       st.column_config.TextColumn("Q-Exposure",  width=90),
            "M_Exposure":       st.column_config.TextColumn("M-Exposure",  width=90),
            "Predicted_Return": st.column_config.TextColumn("Predicted",   width=90),
            "Stock_Residual":   st.column_config.TextColumn("Residual",    width=85),
        },
    )


# ── Feature 10: Correlation Matrix ────────────────────────────────────────────
def render_diversification_score(corr_df: pd.DataFrame):
    """KPI cards: average correlation, diversification score, rating."""
    if corr_df.empty or len(corr_df) < 2:
        return
    n    = len(corr_df)
    vals = [corr_df.iloc[i, j]
            for i in range(n) for j in range(i + 1, n)
            if pd.notna(corr_df.iloc[i, j])]
    if not vals:
        return
    avg_corr  = sum(vals) / len(vals)
    div_score = 1.0 - avg_corr
    if avg_corr < 0.35:
        rating = "우수 🟢"
    elif avg_corr < 0.55:
        rating = "보통 🟡"
    else:
        rating = "주의 🔴"

    c1, c2, c3, c4 = st.columns(4)
    c1.metric("종목 수",       f"{n}개")
    c2.metric("평균 상관계수", f"{avg_corr:.3f}",
              help="포트폴리오 종목 간 평균 수익률 상관계수 (낮을수록 분산 효과↑)")
    c3.metric("분산화 점수",   f"{div_score:.3f}",
              help="1 − 평균상관계수 (높을수록 분산화 우수)")
    c4.metric("분산화 등급",   rating)


def render_correlation_heatmap(corr_df: pd.DataFrame, names: dict = None):
    """Plotly correlation heatmap using px.imshow()."""
    import plotly.express as px

    if corr_df.empty:
        st.info("상관관계 데이터가 없습니다.")
        return

    # Rename tickers → company names; disambiguate duplicates with "(TICKER)" suffix
    if names:
        seen_names: dict = {}
        label_map: dict  = {}
        for t in corr_df.columns:
            name = names.get(t, t)
            if name in seen_names:
                # Mark both the first occurrence and this one with their ticker
                prev_ticker = seen_names[name]
                label_map[prev_ticker] = f"{name} ({prev_ticker})"
                label_map[t]           = f"{name} ({t})"
            else:
                seen_names[name] = t
                label_map[t]     = name
        display_df = corr_df.rename(index=label_map, columns=label_map)
    else:
        display_df = corr_df

    fig = px.imshow(
        display_df,
        color_continuous_scale="RdBu_r",
        zmin=-1, zmax=1,
        text_auto=".2f",
        aspect="auto",
        labels=dict(color="상관계수"),
    )
    fig.update_traces(textfont=dict(size=8))
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'title': "포트폴리오 종목 간 수익률 상관관계 (최근 1년)",
        'xaxis_title': "",
        'yaxis_title': "",
        'coloraxis_colorbar': dict(title="ρ", thickness=14),
        'height': 620,
        'margin': dict(l=80, r=16, t=50, b=80),
    })
    st.plotly_chart(fig, width="stretch")


# ── Feature 12: US Industry Ranking ──────────────────────────────────────────
def render_industry_ranking(rank_df: pd.DataFrame, portfolio_sectors: set = None):
    """
    Horizontal bar chart of industry strength.
    Green bars = sectors represented in the current portfolio.
    """
    if rank_df.empty:
        st.info("US_Industry_Ranking 데이터가 없습니다.")
        st.caption("💡 `python pipeline/09_industry_ranking.py` 를 먼저 실행하세요.")
        return

    if portfolio_sectors is None:
        portfolio_sectors = set()

    df = rank_df.copy().sort_values("Combined_Rank", ascending=True).head(40)

    max_rank = df["Combined_Rank"].max()
    df["Strength"] = (max_rank - df["Combined_Rank"] + 1).round(1)
    df["in_port"]  = df["Sector"].isin(portfolio_sectors)
    df["color"]    = df["in_port"].apply(lambda x: "#4ade80" if x else "#63b3ed")
    df["label"]    = df.apply(
        lambda r: f"{r['Industry']}  ★" if r["in_port"] else r["Industry"], axis=1
    )

    def _safe_pct(v):
        try:
            return f"{float(v):.1%}"
        except Exception:
            return "—"

    hover_text = df.apply(
        lambda r: (
            f"<b>{r['Industry']}</b><br>"
            f"섹터: {r.get('Sector', '—')}<br>"
            f"Mean Return: {_safe_pct(r['Mean_Return'])}<br>"
            f"Breadth: {_safe_pct(r['Breadth'])}<br>"
            f"Stock Count: {int(r['Stock_Count']) if pd.notna(r.get('Stock_Count')) else '?'}<br>"
            f"Combined Rank: {int(r['Combined_Rank']) if pd.notna(r['Combined_Rank']) else '?'}<br>"
            + ("★ 포트폴리오 섹터" if r["in_port"] else "")
        ),
        axis=1,
    ).tolist()

    fig = go.Figure(go.Bar(
        x=df["Strength"],
        y=df["label"],
        orientation="h",
        marker_color=df["color"].tolist(),
        hovertext=hover_text,
        hoverinfo="text",
    ))
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'title': "US 업종 강도 랭킹  (높을수록 강한 업종 · 🟢 = 포트폴리오 섹터)",
        'xaxis_title': "Strength Score",
        'yaxis_title': "",
        'height': max(420, len(df) * 22),
        'margin': dict(l=220, r=16, t=50, b=16),
        'yaxis': dict(autorange="reversed"),
    })
    st.plotly_chart(fig, width="stretch")


def render_industry_table(rank_df: pd.DataFrame, portfolio_sectors: set = None):
    """Scrollable sortable table of industry ranking data."""
    if rank_df.empty:
        return
    if portfolio_sectors is None:
        portfolio_sectors = set()

    df = rank_df.copy()
    df["Portfolio"] = df["Sector"].apply(lambda s: "★ 포트폴리오" if s in portfolio_sectors else "")

    display_cols = ["Rank", "Industry", "Sector", "Stock_Count",
                    "Mean_Return", "Breadth", "Combined_Rank", "Top_Tickers", "Portfolio"]
    display_cols = [c for c in display_cols if c in df.columns]

    # Format
    df_disp = df[display_cols].copy()
    for col in ["Mean_Return", "Breadth"]:
        if col in df_disp.columns:
            df_disp[col] = df_disp[col].apply(
                lambda x: f"{float(x):.1%}" if pd.notna(x) and x != '' else "—")
    for col in ["Rank", "Stock_Count", "Combined_Rank"]:
        if col in df_disp.columns:
            df_disp[col] = df_disp[col].apply(
                lambda x: str(int(x)) if pd.notna(x) else "—")

    st.dataframe(
        df_disp,
        width="stretch",
        hide_index=True,
        column_config={
            "Rank":          st.column_config.TextColumn("Rank",         width=55),
            "Industry":      st.column_config.TextColumn("Industry",     width=200),
            "Sector":        st.column_config.TextColumn("Sector",       width=140),
            "Stock_Count":   st.column_config.TextColumn("N",            width=45),
            "Mean_Return":   st.column_config.TextColumn("Mean Ret",     width=85),
            "Breadth":       st.column_config.TextColumn("Breadth",      width=80),
            "Combined_Rank": st.column_config.TextColumn("Comb. Rank",   width=85),
            "Top_Tickers":   st.column_config.TextColumn("Top Tickers",  width=180),
            "Portfolio":     st.column_config.TextColumn("Portfolio",    width=110),
        },
    )


# ── Feature 13: KR Order Flow ─────────────────────────────────────────────────
def render_order_flow_bar(df: pd.DataFrame, portfolio_tickers: set = None):
    """
    Bar chart of Foreign + Institutional net buying per stock.
    Green = stock also in KR_Final_Portfolio (dual-confirmation signal).
    """
    if df.empty:
        st.info("KR_Dual_Net_Buyers 데이터가 없습니다.")
        st.caption("💡 `python pipeline/11_kr_order_flow.py` 를 먼저 실행하세요.")
        return

    if portfolio_tickers is None:
        portfolio_tickers = set()

    df = df.copy()
    df["Total_Net_Buy"] = df["Foreign_Net_Buy"].fillna(0) + df["Inst_Net_Buy"].fillna(0)
    df["in_port"]       = df["Ticker"].isin(portfolio_tickers)
    df = df.sort_values("Total_Net_Buy", ascending=False).reset_index(drop=True)

    colors = df["in_port"].apply(lambda x: "#4ade80" if x else "#63b3ed").tolist()

    label_col = "Name" if "Name" in df.columns else "Ticker"
    x_labels  = df.apply(
        lambda r: f"{r[label_col][:12]}  ★" if r["in_port"] else r[label_col][:14],
        axis=1,
    ).tolist()

    fig = go.Figure(go.Bar(
        x=x_labels,
        y=df["Total_Net_Buy"],
        marker_color=colors,
        text=df["Consecutive_Days"].apply(
            lambda d: f"연속 {int(d)}일" if pd.notna(d) else ""
        ),
        textposition="outside",
        hovertemplate=(
            "<b>%{x}</b><br>"
            "총 순매수: %{y:,.0f}<br>"
            "외국인: %{customdata[0]:,.0f}<br>"
            "기관: %{customdata[1]:,.0f}<br>"
            "연속: %{customdata[2]}일<br>"
            "<extra></extra>"
        ),
        customdata=list(zip(
            df["Foreign_Net_Buy"].fillna(0),
            df["Inst_Net_Buy"].fillna(0),
            df["Consecutive_Days"].fillna(0).astype(int),
        )),
    ))
    fig.update_layout(
        **PLOTLY_LAYOUT,
        title="KR 외국인 + 기관 동시 순매수 강도  (🟢 = KR 포트폴리오 종목)",
        xaxis_title="종목",
        yaxis_title="순매수 합계 (주)",
        height=440,
        xaxis=dict(tickangle=-30),
    )
    st.plotly_chart(fig, width="stretch")


def render_order_flow_table(df: pd.DataFrame, portfolio_tickers: set = None):
    """Scrollable KR order flow detail table."""
    if df.empty:
        return
    if portfolio_tickers is None:
        portfolio_tickers = set()

    d = df.copy()
    currency = 'KRW'
    d.insert(0, 'Logo', d['Ticker'].apply(lambda t: _logo_url(t, currency)))
    d["Signal"] = d["Ticker"].apply(
        lambda t: "★ KR 포트폴리오" if t in portfolio_tickers else "—"
    )
    d["Total_Net_Buy"] = d["Foreign_Net_Buy"].fillna(0) + d["Inst_Net_Buy"].fillna(0)

    for col in ["Foreign_Net_Buy", "Inst_Net_Buy", "Total_Net_Buy"]:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{int(x):,}" if pd.notna(x) else "—")
    if "Consecutive_Days" in d.columns:
        d["Consecutive_Days"] = d["Consecutive_Days"].apply(
            lambda x: f"{int(x)}일" if pd.notna(x) else "—"
        )

    display_cols = ["Logo", "Ticker", "Name", "Consecutive_Days",
                    "Foreign_Net_Buy", "Inst_Net_Buy", "Total_Net_Buy",
                    "Signal", "Last_Updated"]
    display_cols = [c for c in display_cols if c in d.columns]

    st.dataframe(
        d[display_cols],
        width="stretch",
        hide_index=True,
        column_config={
            "Logo":            st.column_config.ImageColumn("", width=40),
            "Ticker":          st.column_config.TextColumn("Ticker",       width=95),
            "Name":            st.column_config.TextColumn("종목명",        width=160),
            "Consecutive_Days":st.column_config.TextColumn("연속 순매수",   width=90),
            "Foreign_Net_Buy": st.column_config.TextColumn("외국인 순매수", width=110),
            "Inst_Net_Buy":    st.column_config.TextColumn("기관 순매수",   width=110),
            "Total_Net_Buy":   st.column_config.TextColumn("합계",          width=100),
            "Signal":          st.column_config.TextColumn("포트폴리오",    width=115),
            "Last_Updated":    st.column_config.TextColumn("업데이트",      width=95),
        },
    )


# ── Portfolio Drift Alert ──────────────────────────────────────────────────────

def render_drift_kpi(summary: dict):
    """5-card KPI row for portfolio drift summary."""
    total_drift   = summary.get('Total_Drift')
    n_rebalance   = summary.get('Stocks_Rebalance')
    n_watch       = summary.get('Stocks_Watch')
    n_ok          = summary.get('Stocks_OK')
    days          = summary.get('Days_Since_Rebal')
    recommendation = summary.get('Recommendation', '—')

    drift_str  = f"{total_drift:.1%}" if pd.notna(total_drift) and total_drift is not None else "—"
    days_str   = f"{int(days)}일" if pd.notna(days) and days is not None else "—"
    n_reb_str  = f"{int(n_rebalance)}" if pd.notna(n_rebalance) and n_rebalance is not None else "0"
    n_wat_str  = f"{int(n_watch)}"    if pd.notna(n_watch)    and n_watch    is not None else "0"
    n_ok_str   = f"{int(n_ok)}"      if pd.notna(n_ok)       and n_ok       is not None else "0"

    # Recommendation badge colour
    if 'REBALANCE' in str(recommendation):
        rec_color = "#f87171"   # red
        rec_icon  = "🔴"
    elif 'MONITOR' in str(recommendation):
        rec_color = "#fbbf24"   # yellow
        rec_icon  = "🟡"
    else:
        rec_color = "#4ade80"   # green
        rec_icon  = "🟢"

    drift_color = "#f87171" if (total_drift or 0) > 0.10 else ("#fbbf24" if (total_drift or 0) > 0.05 else "#4ade80")

    st.markdown(f"""
    <div class="stats-grid" style="margin-bottom:8px;">
        <div class="stat-item">
            <div class="stat-label">Total Drift</div>
            <div class="stat-value" style="color:{drift_color};">{drift_str}</div>
            <div class="stat-sub">리밸런싱 비용 추정</div>
        </div>
        <div class="stat-item">
            <div class="stat-label">🔴 리밸런싱 필요</div>
            <div class="stat-value" style="color:#f87171;">{n_reb_str}종목</div>
            <div class="stat-sub">드리프트 &gt; 5%</div>
        </div>
        <div class="stat-item">
            <div class="stat-label">🟡 모니터링</div>
            <div class="stat-value" style="color:#fbbf24;">{n_wat_str}종목</div>
            <div class="stat-sub">드리프트 3–5%</div>
        </div>
        <div class="stat-item">
            <div class="stat-label">🟢 정상</div>
            <div class="stat-value" style="color:#4ade80;">{n_ok_str}종목</div>
            <div class="stat-sub">드리프트 ≤ 3%</div>
        </div>
        <div class="stat-item">
            <div class="stat-label">마지막 리밸런싱</div>
            <div class="stat-value">{days_str}</div>
            <div class="stat-sub">경과</div>
        </div>
    </div>
    <div style="margin:8px 0 16px 0;padding:10px 16px;border-radius:8px;
                background:#0a0f1e;border:1px solid #1f2937;font-size:0.88rem;">
        <span style="color:{rec_color};font-weight:700;">{rec_icon} {recommendation}</span>
    </div>
    """, unsafe_allow_html=True)


def render_drift_chart(drift_df: pd.DataFrame):
    """Grouped bar chart: Target vs Current weight per stock, coloured by Status."""
    if drift_df.empty:
        return

    d = drift_df.copy()
    # Short display label: ticker only
    d['Label'] = d['Ticker'].str.split('.').str[0]

    # Sort by Drift_Abs descending
    if 'Drift_Abs' in d.columns:
        d = d.sort_values('Drift_Abs', ascending=False)

    colors = {
        'REBALANCE': '#f87171',
        'WATCH':     '#fbbf24',
        'OK':        '#4ade80',
    }
    bar_colors = d['Status'].map(colors).fillna('#60a5fa').tolist() if 'Status' in d.columns else '#60a5fa'

    fig = go.Figure()

    fig.add_trace(go.Bar(
        name='목표 비중',
        x=d['Label'],
        y=d['Target_Weight'] * 100 if 'Target_Weight' in d.columns else [],
        marker_color='#60a5fa',
        opacity=0.55,
        text=None,
    ))

    fig.add_trace(go.Bar(
        name='현재 비중',
        x=d['Label'],
        y=d['Current_Weight'] * 100 if 'Current_Weight' in d.columns else [],
        marker_color=bar_colors,
        opacity=0.9,
        text=(d['Drift_Abs'] * 100).apply(lambda x: f"+{x:.1f}%" if pd.notna(x) else ""),
        textposition='outside',
        textfont=dict(size=9),
    ))

    fig.update_layout(
        **{**PLOTLY_LAYOUT, 'margin': dict(l=16, r=16, t=44, b=80)},
        barmode='group',
        title=dict(text="종목별 목표 vs 현재 비중 (%, 색상=드리프트 상태)", font=dict(size=13)),
        yaxis=dict(title="비중 (%)", tickformat=".1f"),
        xaxis=dict(tickangle=-45),
        legend=dict(orientation="h", yanchor="bottom", y=1.01, xanchor="right", x=1),
        height=400,
    )
    st.plotly_chart(fig, width="stretch")


def render_drift_table(drift_df: pd.DataFrame):
    """Scrollable drift detail table with status emoji."""
    if drift_df.empty:
        return

    d = drift_df.copy()

    # Status emoji
    def _status_emoji(s):
        s = str(s).upper()
        if 'REBALANCE' in s:
            return '🔴 REBALANCE'
        if 'WATCH' in s:
            return '🟡 WATCH'
        return '🟢 OK'

    if 'Status' in d.columns:
        d['Status'] = d['Status'].apply(_status_emoji)

    # Determine currency from market column
    currency = 'KRW' if ('Market' in d.columns and d['Market'].iloc[0] == 'KR') else 'USD'
    if 'Ticker' in d.columns:
        d.insert(0, 'Logo', d['Ticker'].apply(lambda t: _logo_url(t, currency)))

    for col in ['Target_Weight', 'Current_Weight', 'Drift_Abs']:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{x:.2%}" if pd.notna(x) else "—")
    if 'Drift_Pct' in d.columns:
        d['Drift_Pct'] = d['Drift_Pct'].apply(lambda x: f"{x:+.1f}%" if pd.notna(x) else "—")
    if 'Return_Since_Rebal' in d.columns:
        d['Return_Since_Rebal'] = d['Return_Since_Rebal'].apply(
            lambda x: f"{x:+.2%}" if pd.notna(x) else "—")
    for col in ['Price_Rebal', 'Price_Current']:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{x:,.2f}" if pd.notna(x) else "—")

    display_cols = ['Logo', 'Ticker', 'Name', 'Sector',
                    'Target_Weight', 'Current_Weight', 'Drift_Abs', 'Drift_Pct',
                    'Return_Since_Rebal', 'Status', 'Last_Rebal']
    display_cols = [c for c in display_cols if c in d.columns]

    st.dataframe(
        d[display_cols],
        width="stretch",
        hide_index=True,
        column_config={
            "Logo":             st.column_config.ImageColumn("",           width=40),
            "Ticker":           st.column_config.TextColumn("Ticker",      width=90),
            "Name":             st.column_config.TextColumn("종목명",       width=170),
            "Sector":           st.column_config.TextColumn("섹터",         width=135),
            "Target_Weight":    st.column_config.TextColumn("목표 비중",    width=85),
            "Current_Weight":   st.column_config.TextColumn("현재 비중",    width=85),
            "Drift_Abs":        st.column_config.TextColumn("드리프트",     width=80),
            "Drift_Pct":        st.column_config.TextColumn("변화율",       width=75),
            "Return_Since_Rebal":st.column_config.TextColumn("리밸 이후 수익", width=105),
            "Status":           st.column_config.TextColumn("상태",         width=110),
            "Last_Rebal":       st.column_config.TextColumn("리밸 날짜",    width=95),
        },
    )


def render_earnings_bar_chart(df: 'pd.DataFrame'):
    """
    Horizontal bar chart for PEAD Earnings Momentum.
    Bar color: green = positive Return_Since, red = negative.
    Text label shows EPS Surprise % and Return Since %.
    Shared by US and KR Earnings PEAD tabs.
    """
    if df.empty:
        return
    top = df.head(20).copy()
    ret_vals   = top['Return_Since'].fillna(0) if 'Return_Since' in top.columns else pd.Series([0] * len(top))
    bar_colors = ['#4ade80' if float(v) >= 0 else '#f87171' for v in ret_vals]
    label_col  = top['Name'] if 'Name' in top.columns and top['Name'].notna().any() else top['Ticker']
    surp_vals  = top['Surprise_Pct'].fillna(0) if 'Surprise_Pct' in top.columns else pd.Series([0] * len(top))
    fig = go.Figure(go.Bar(
        x=top['Signal_Strength'] if 'Signal_Strength' in top.columns else pd.Series([0] * len(top)),
        y=label_col,
        orientation='h',
        marker_color=bar_colors,
        text=[f"  Surp: {s*100:+.1f}%  Ret: {r*100:+.1f}%"
              for s, r in zip(surp_vals, ret_vals)],
        textposition='outside',
        textfont=dict(color='#94a3b8', size=10),
    ))
    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'title': dict(
            text="Signal Strength  (초록 = 발표 후 상승 · 빨강 = 하락)",
            font=dict(color='#e2e8f0', size=13),
        ),
        'xaxis': dict(tickfont=dict(color='#64748b'), gridcolor='#1f2937'),
        'yaxis': dict(tickfont=dict(color='#94a3b8', size=10)),
        'height': max(300, len(top) * 28),
        'margin': dict(l=0, r=160, t=44, b=0),
    })
    st.plotly_chart(fig, width="stretch")


# ── V/Q/M Factor Decomposition ─────────────────────────────────────────────────
def render_factor_decomposition(scored_df: pd.DataFrame, portfolio_tickers: list, market: str = "US"):
    """Stacked horizontal bar: Value / Quality / Momentum per portfolio stock."""
    needed = {'Ticker', 'Value_Score', 'Quality_Score', 'Momentum_Score'}
    if not needed.issubset(scored_df.columns) or not portfolio_tickers:
        st.info("스코어드 종목 데이터를 포트폴리오 종목과 매칭할 수 없습니다.")
        return

    df = scored_df[scored_df['Ticker'].isin(portfolio_tickers)].copy()
    for col in ['Value_Score', 'Quality_Score', 'Momentum_Score']:
        df[col] = pd.to_numeric(df[col], errors='coerce').fillna(0)
    if df.empty:
        st.info("매칭되는 팩터 스코어 데이터가 없습니다.")
        return

    score_col = next((c for c in ('Final_Score', 'Combined_Score', 'Total_Score') if c in df.columns), None)
    if score_col:
        df[score_col] = pd.to_numeric(df[score_col], errors='coerce').fillna(0)
        df = df.sort_values(score_col, ascending=True)
    else:
        df = df.sort_values('Total_Score', ascending=True)

    df['_label'] = df.apply(
        lambda r: f"{r['Ticker']}  {str(r.get('Name', ''))[:14].strip()}", axis=1
    )

    fig = go.Figure()
    for col, name, color in [
        ('Value_Score',    'Value',    '#60a5fa'),
        ('Quality_Score',  'Quality',  '#34d399'),
        ('Momentum_Score', 'Momentum', '#fbbf24'),
    ]:
        fig.add_trace(go.Bar(
            name=name, y=df['_label'], x=df[col],
            orientation='h',
            marker=dict(color=color, opacity=0.85),
            hovertemplate=f"<b>%{{y}}</b><br>{name}: %{{x:.4f}}<extra></extra>",
        ))

    fig.update_layout(**{
        **PLOTLY_LAYOUT,
        'barmode': 'stack',
        'height':  max(280, len(df) * 26 + 60),
        'xaxis':   dict(title='Factor Score (stacked)', gridcolor='#1f2937'),
        'yaxis':   dict(categoryorder='array', categoryarray=list(df['_label']),
                        tickfont=dict(color='#94a3b8', size=10)),
        'legend':  dict(orientation='h', y=1.04, x=0, font=dict(size=11)),
        'margin':  dict(l=170, r=16, t=44, b=16),
    })
    st.plotly_chart(fig, width="stretch")
