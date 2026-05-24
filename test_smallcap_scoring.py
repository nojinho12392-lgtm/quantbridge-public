"""
test_smallcap_scoring.py  —  SmallCap 10-Bagger 점수 기준 단독 테스트
======================================================================
데이터 로드 없이 score_stock() 로직만 실행.
10가지 가상 기업 프로파일로 각 항목별 점수 내역 출력.

Run: python test_smallcap_scoring.py
"""

import math
import unittest

# ── 상수 ──────────────────────────────────────────────────────────────────────
MCAP_MIN_US = 1e8   # $100M
MCAP_MAX_US = 1e9   # $1B

# ── 유틸 ──────────────────────────────────────────────────────────────────────
def is_valid(v):
    return v is not None and not (isinstance(v, float) and math.isnan(v))

def smallcap_bonus(mcap, mcap_min=MCAP_MIN_US, mcap_max=MCAP_MAX_US):
    try:
        if not mcap or mcap <= 0:
            return 0.0
        n = (mcap - mcap_min) / (mcap_max - mcap_min)
        return round((1 - max(0.0, min(1.0, n))) * 15, 2)
    except Exception:
        return 0.0

# ── 점수 계산 (항목별 내역 반환) ──────────────────────────────────────────────
def score_stock_detailed(roic, rev_growth, gross_margin, fcf_margin,
                          debt_ebitda, rev_accel, volume_surge, mcap,
                          mcap_min=MCAP_MIN_US, mcap_max=MCAP_MAX_US):
    """
    (total, bonus, breakdown_dict) 반환.
    breakdown_dict = 각 항목별 획득 점수.
    """
    breakdown = {}
    s = 0

    # ROIC  25pts max / -5 penalty
    pts = 0
    if is_valid(roic):
        if roic > 0.30:    pts = 25
        elif roic > 0.20:  pts = 20
        elif roic > 0.10:  pts = 12
        elif roic > 0:     pts = 4
        elif roic > -0.10: pts = 0
        else:              pts = -5
    breakdown['ROIC (25)'] = pts
    s += pts

    # RevGrowth  30pts max
    pts = 0
    if is_valid(rev_growth):
        if rev_growth > 0.50:   pts = 30
        elif rev_growth > 0.25: pts = 24
        elif rev_growth > 0.10: pts = 15
        elif rev_growth > 0:    pts = 6
    breakdown['RevGrowth (30)'] = pts
    s += pts

    # Rule of 40  15pts max
    pts = 0
    if is_valid(rev_growth) and is_valid(fcf_margin):
        r40 = (rev_growth * 100) + (fcf_margin * 100)
        if r40 >= 60:   pts = 15
        elif r40 >= 40: pts = 10
        elif r40 >= 20: pts = 5
        breakdown['Rule of 40 (15)'] = pts
    else:
        breakdown['Rule of 40 (15)'] = '—'
    s += pts

    # GrossMargin  15pts
    pts = 0
    if is_valid(gross_margin):
        if gross_margin > 0.50:   pts = 15
        elif gross_margin > 0.35: pts = 12
        elif gross_margin > 0.20: pts = 7
        elif gross_margin > 0:    pts = 3
    breakdown['GrossMargin (15)'] = pts
    s += pts

    # FCF_Margin  10pts max
    pts = 0
    if is_valid(fcf_margin):
        if fcf_margin > 0.10:   pts = 10
        elif fcf_margin > 0.05: pts = 7
        elif fcf_margin > 0:    pts = 5
    breakdown['FCF_Margin (10)'] = pts
    s += pts

    # Debt/EBITDA  10pts max / -5 penalty / +5 neutral
    pts = 5  # neutral default
    if is_valid(debt_ebitda) and debt_ebitda > 0:
        if debt_ebitda < 3:   pts = 10
        elif debt_ebitda < 5: pts = 6
        elif debt_ebitda < 8: pts = 0
        else:                 pts = -5
    breakdown['Debt/EBITDA (10)'] = pts
    s += pts

    # Rev_Accel  10pts max
    pts = 0
    if is_valid(rev_accel):
        if rev_accel > 0.05:    pts = 10
        elif rev_accel > 0.01:  pts = 6
        elif rev_accel > -0.05: pts = 3
        breakdown['Rev_Accel (10)'] = pts
    else:
        breakdown['Rev_Accel (10)'] = '—'
    s += pts

    # Volume_Surge  5pts max
    pts = 0
    if is_valid(volume_surge):
        if volume_surge > 3:   pts = 5
        elif volume_surge > 1.5: pts = 3
    breakdown['Vol_Surge (5)'] = pts
    s += pts

    # SmallCap Bonus  15pts
    bonus = smallcap_bonus(mcap, mcap_min, mcap_max)
    breakdown['SmallCap Bonus (15)'] = bonus
    s += bonus

    return round(s, 2), bonus, breakdown


# ── 가상 기업 프로파일 ──────────────────────────────────────────────────────────
# 각 값: (roic, rev_growth, gross_margin, fcf_margin, debt_ebitda, rev_accel, volume_surge, mcap_usd)
PROFILES = {
    # 이름                roic  rev_g  gm    fcf_m  d_ebitda rev_acc  vol_s  mcap($M)
    "🚀 SaaS 하이퍼성장":  (0.15, 0.80, 0.72, -0.05, 1.5,     0.12,    2.5,   2e8),
    "💎 이상적 복합기업":  (0.35, 0.35, 0.55,  0.18, 0.8,     0.08,    3.5,   1.5e8),
    "📈 안정적 성장주":    (0.22, 0.18, 0.42,  0.12, 2.1,     0.03,    1.8,   5e8),
    "🔬 바이오 재투자":    (0.05, 0.60, 0.65, -0.20, 0.5,     0.20,    4.0,   1.2e8),
    "🏭 전통 제조업":      (0.12, 0.08, 0.28,  0.06, 3.8,    -0.02,    1.0,   7e8),
    "💰 현금 창출기업":    (0.28, 0.12, 0.38,  0.22, 1.2,     0.01,    0.8,   9e8),
    "⚡ 고성장 흑자전환":  (0.08, 0.55, 0.60,  0.02, 2.0,     0.15,    5.0,   1.8e8),
    "🏦 부채 과다기업":    (0.10, 0.20, 0.30,  0.03, 9.5,     0.04,    1.2,   4e8),
    "📉 역성장 기업":      (-0.05, -0.10, 0.22, -0.15, 4.0,  -0.08,    0.5,   3e8),
    "🌱 초기 성장주":      (0.00, 1.20, 0.80, -0.35, None,   0.40,    8.0,   1.1e8),
}


# ── 출력 ──────────────────────────────────────────────────────────────────────
def fmt(v, pct=False):
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "—"
    if pct:
        return f"{v*100:+.1f}%"
    return f"{v:.2f}"

COLS = ['ROIC (25)', 'RevGrowth (30)', 'Rule of 40 (15)', 'GrossMargin (15)',
        'FCF_Margin (10)', 'Debt/EBITDA (10)', 'Rev_Accel (10)',
        'Vol_Surge (5)', 'SmallCap Bonus (15)']

COL_W = 16  # 컬럼 너비

def print_results(profiles):
    # 헤더
    name_w = 22
    header = f"{'기업':>{name_w}s} | " + " | ".join(f"{c[:COL_W-1]:>{COL_W-1}s}" for c in COLS) + f" | {'TOTAL':>7s}"
    sep = "-" * len(header)
    print("\n" + "=" * len(header))
    print("  SmallCap 10-Bagger 점수 시뮬레이션  (max ~135 pts)")
    print("=" * len(header))
    print(header)
    print(sep)

    results = []
    for name, (roic, rev_g, gm, fcf, de, ra, vs, mcap) in profiles.items():
        total, bonus, bd = score_stock_detailed(roic, rev_g, gm, fcf, de, ra, vs, mcap)
        results.append((total, name, bd))

    results.sort(reverse=True)

    for total, name, bd in results:
        row_vals = []
        for c in COLS:
            v = bd.get(c, 0)
            if v == '—':
                row_vals.append(f"{'—':>{COL_W-1}s}")
            elif isinstance(v, (int, float)):
                color = '\033[92m' if v > 0 else ('\033[91m' if v < 0 else '\033[90m')
                reset = '\033[0m'
                row_vals.append(f"{color}{v:>{COL_W-1}.1f}{reset}")
            else:
                row_vals.append(f"{str(v):>{COL_W-1}s}")
        total_color = '\033[93m' if total >= 80 else ('\033[92m' if total >= 60 else '\033[0m')
        reset = '\033[0m'
        print(f"{name:>{name_w}s} | " + " | ".join(row_vals) + f" | {total_color}{total:>7.1f}{reset}")

    print(sep)
    print()

    # 요약: 각 항목 평균
    all_totals = [r[0] for r in results]
    print(f"  평균 총점: {sum(all_totals)/len(all_totals):.1f}pts  |  "
          f"최고: {max(all_totals):.1f}pts  |  최저: {min(all_totals):.1f}pts")
    print()

    # 항목별 최고 기여도 분석
    print("  [ 항목별 기여 분석 ]")
    print(f"  {'항목':<22s} {'최고점':>8s}  {'설명'}")
    print(f"  {'-'*60}")
    for c, maxp in [
        ('ROIC (25)',         25), ('RevGrowth (30)',    30),
        ('Rule of 40 (15)',   15), ('GrossMargin (15)',  15),
        ('FCF_Margin (10)',   10), ('Debt/EBITDA (10)',  10),
        ('Rev_Accel (10)',    10), ('Vol_Surge (5)',       5),
        ('SmallCap Bonus (15)', 15),
    ]:
        vals = [r[2].get(c, 0) for r in results if isinstance(r[2].get(c, 0), (int, float))]
        if vals:
            avg = sum(vals) / len(vals)
            utilization = avg / maxp * 100
            bar = '█' * int(utilization / 10) + '░' * (10 - int(utilization / 10))
            print(f"  {c:<22s} {avg:>5.1f}/{maxp}  {bar}  {utilization:.0f}% 활용")
    print()


def print_rule40_matrix():
    """Rule of 40 매트릭스 — RevGrowth × FCF_Margin 조합별 점수 시각화"""
    print("  [ Rule of 40 매트릭스 ] (RevGrowth% + FCF_Margin% = Rule40)")
    print()
    rev_vals  = [-10, 0, 10, 20, 30, 50, 80, 120]   # %
    fcf_vals  = [-30, -15, -5, 0, 5, 10, 20]         # %
    print(f"  {'FCF%→':>8s}", end="")
    for f in fcf_vals:
        print(f"  {f:>+5d}%", end="")
    print()
    print(f"  {'Rev% ↓':>8s}" + "  " + "─" * (8 * len(fcf_vals)))
    for r in rev_vals:
        print(f"  {r:>+7d}%", end="")
        for f in fcf_vals:
            r40 = r + f
            if r40 >= 60:   pts, c = 15, '\033[92m'
            elif r40 >= 40: pts, c = 10, '\033[93m'
            elif r40 >= 20: pts = 5;  c = '\033[0m'
            else:           pts = 0;  c = '\033[90m'
            print(f"  {c}{pts:>5d}pt\033[0m", end="")
        print(f"")
    print()
    print("  \033[92m■\033[0m 15pt (R40≥60)  \033[93m■\033[0m 10pt (R40≥40)  □ 5pt (R40≥20)  \033[90m□\033[0m 0pt (R40<20)")
    print()


def print_rev_accel_examples():
    """Rev_Accel 실제 수치 예시"""
    print("  [ Rev_Accel 점수 기준 ]")
    examples = [
        (0.20,  "매출 가속 +20%p  (분기 YoY 급가속 — 초강력 성장 신호)"),
        (0.08,  "매출 가속 +8%p   (뚜렷한 가속 → 10pt)"),
        (0.03,  "매출 가속 +3%p   (소폭 가속 → 6pt)"),
        (0.00,  "보합 (0%p)       (± 1%p 이내 → 3pt)"),
        (-0.03, "소폭 감속 -3%p   (감속 시작 → 3pt 아직 유지)"),
        (-0.07, "감속 -7%p        (확연한 감속 → 0pt, 경고)"),
        (-0.20, "급감속 -20%p     (성장 둔화 심각 → 0pt)"),
    ]
    for accel, desc in examples:
        if accel > 0.05:    pts = 10; bar = "██████████"
        elif accel > 0.01:  pts = 6;  bar = "██████░░░░"
        elif accel > -0.05: pts = 3;  bar = "███░░░░░░░"
        else:               pts = 0;  bar = "░░░░░░░░░░"
        color = '\033[92m' if pts >= 6 else ('\033[93m' if pts > 0 else '\033[91m')
        print(f"  {accel:>+7.0%}  {bar}  {color}{pts:>2d}pt\033[0m  {desc}")
    print()


class SmallCapScoringTests(unittest.TestCase):
    def test_ideal_profile_scores_above_stable_growth(self):
        ideal_total, _, _ = score_stock_detailed(0.35, 0.35, 0.55, 0.18, 0.8, 0.08, 3.5, 1.5e8)
        stable_total, _, _ = score_stock_detailed(0.22, 0.18, 0.42, 0.12, 2.1, 0.03, 1.8, 5e8)
        self.assertGreater(ideal_total, stable_total)
        self.assertGreaterEqual(ideal_total, 100)

    def test_debt_penalty_reduces_score(self):
        low_debt, _, _ = score_stock_detailed(0.18, 0.30, 0.45, 0.08, 2.0, 0.04, 1.2, 4e8)
        high_debt, _, high_breakdown = score_stock_detailed(0.18, 0.30, 0.45, 0.08, 9.0, 0.04, 1.2, 4e8)
        self.assertLess(high_debt, low_debt)
        self.assertEqual(high_breakdown["Debt/EBITDA (10)"], -5)

    def test_smallcap_bonus_is_bounded(self):
        self.assertEqual(smallcap_bonus(1e8), 15)
        self.assertEqual(smallcap_bonus(1e9), 0)
        self.assertEqual(smallcap_bonus(5.5e8), 7.5)


# ── 실행 ──────────────────────────────────────────────────────────────────────
if __name__ == '__main__':
    print_results(PROFILES)
    print_rule40_matrix()
    print_rev_accel_examples()
