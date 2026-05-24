import Foundation
import SwiftUI

internal struct CandidateStrip<Content: View>: View {
    let title: String
    var action: (() -> Void)?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    content
                }
                .padding(.vertical, 2)
            }
        }
    }

    @ViewBuilder
    private var header: some View {
        if let action {
            Button(action: action) {
                headerContent(showsChevron: true)
            }
            .buttonStyle(.plain)
            .contentShape(Rectangle())
            .accessibilityLabel("\(title) 더보기")
        } else {
            headerContent(showsChevron: false)
        }
    }

    private func headerContent(showsChevron: Bool) -> some View {
        HStack(alignment: .center) {
            Text(title)
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Spacer(minLength: 8)
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

internal struct CandidateCard: View {
    let ticker: String
    let name: String
    let currency: String
    let subtitle: String
    let rankText: String?
    let headlineLabel: String
    let headlineValue: String
    let headlineColor: Color
    let basisText: String
    let reason: HomeCardReason
    let metrics: [HomeCardMetric]
    let updatedAt: String?
    let isWatched: Bool
    let toggleWatch: () -> Void
    let open: () -> Void

    private var metricPreview: [HomeCardMetric] {
        Array(metrics.prefix(2))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 9) {
                CompanyLogoView(ticker: ticker, currency: currency, size: 36)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                        TickerBadge(ticker: ticker)
                    }
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .trailing, spacing: 4) {
                    if let rankText {
                        Text(rankText)
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(AppTheme.accent)
                            .lineLimit(1)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(AppTheme.accent.opacity(0.10)))
                    }
                    Button(action: toggleWatch) {
                        Image(systemName: isWatched ? "heart.fill" : "heart")
                            .font(.system(size: 18, weight: .semibold))
                    }
                    .buttonStyle(QuantIconButtonStyle(tint: isWatched ? .yellow : AppTheme.secondaryText))
                    .accessibilityLabel(isWatched ? "관심 해제" : "관심 추가")
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(headlineLabel)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(headlineValue)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(headlineColor)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.74)
                }
                HomeReasonPill(reason: reason)
            }

            HomeCardMetricList(metrics: metricPreview)
            HomeCardTrustFooter(updatedAt: updatedAt, metrics: metrics)
        }
        .frame(width: 258, alignment: .leading)
        .appCard(padding: 11)
        .contentShape(Rectangle())
        .onTapGesture(perform: open)
    }
}

internal struct HomeCardReason {
    let title: String
    let detail: String
    let color: Color
}

internal struct HomeCardMetric: Identifiable, Hashable {
    var id: String { label }
    let label: String
    let value: String
}

internal struct HomeReasonPill: View {
    let reason: HomeCardReason

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(reason.color)
                .frame(width: 7, height: 7)
            Text(reason.title)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(reason.color)
                .lineLimit(1)
            Text(reason.detail)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

internal struct HomeCardMetricList: View {
    let metrics: [HomeCardMetric]

    var body: some View {
        HStack(spacing: 8) {
            ForEach(metrics) { metric in
                VStack(alignment: .leading, spacing: 4) {
                    Text(metric.label)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                    Text(metric.value)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                }
                .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

internal struct HomeCardTrustFooter: View {
    let updatedAt: String?
    let metrics: [HomeCardMetric]

    private var missingCount: Int {
        metrics.filter { $0.value == "-" }.count + ((updatedAt?.isEmpty ?? true) ? 1 : 0)
    }

    private var detail: String {
        if missingCount > 0 {
            return "부족 \(missingCount)개"
        }
        return "근거 확인"
    }

    var body: some View {
        HStack(spacing: 6) {
            LucideIconView(icon: .calendarClock, size: 12)
                .foregroundStyle(AppTheme.secondaryText)
            Text(formattedUpdateTimestamp(updatedAt))
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(1)
            Spacer(minLength: 6)
            Text(detail)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(missingCount > 0 ? AppTheme.warning : AppTheme.accent)
                .lineLimit(1)
        }
        .padding(.top, 1)
        .accessibilityLabel("갱신 \(formattedUpdateTimestamp(updatedAt)), \(detail)")
    }
}

internal func homeSubtitle(market: String?, text: String) -> String {
    let marketText = (market == "KR") ? "KR" : "US"
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return "\(marketText) · \(trimmed.isEmpty ? "후보" : trimmed)"
}

internal func portfolioHomeMetrics(_ stock: PortfolioStock, currency: String) -> [HomeCardMetric] {
    [
        HomeCardMetric(label: "Score", value: score(stock.totalScore)),
        HomeCardMetric(label: "ROIC", value: pct(stock.roic, signed: false)),
        HomeCardMetric(label: "성장", value: pct(stock.revGrowth)),
        HomeCardMetric(label: "마진", value: pct(stock.grossMargin, signed: false)),
        HomeCardMetric(label: "시총", value: cap(stock.marketCap, currency: currency))
    ]
}

internal func portfolioHomeReason(_ stock: PortfolioStock) -> HomeCardReason {
    if let expected = stock.expectedReturn, expected.isFinite, expected > 0 {
        return HomeCardReason(title: "기대수익", detail: "모델 기준 \(pct(expected)) 후보입니다.", color: AppTheme.accent)
    }
    if let roic = stock.roic, roic.isFinite, roic >= 0.15 {
        return HomeCardReason(title: "퀄리티", detail: "ROIC \(pct(roic, signed: false))로 자본 효율이 좋습니다.", color: AppTheme.positive)
    }
    if let growth = stock.revGrowth, growth.isFinite, growth >= 0.15 {
        return HomeCardReason(title: "성장", detail: "매출 성장 \(pct(growth))가 확인됩니다.", color: AppTheme.positive)
    }
    if let expected = stock.expectedReturn, expected.isFinite, expected < 0 {
        return HomeCardReason(title: "주의", detail: "기대수익이 음수라 타이밍 확인이 필요합니다.", color: AppTheme.warning)
    }
    return HomeCardReason(title: "확인", detail: "상세에서 차트와 팩터 균형을 같이 보세요.", color: AppTheme.secondaryText)
}

internal func portfolioHomeBasis(_ stock: PortfolioStock) -> String {
    var parts = ["기대수익"]
    if stock.totalScore?.isFinite == true { parts.append("종합점수") }
    if stock.roic?.isFinite == true { parts.append("ROIC") }
    if stock.revGrowth?.isFinite == true { parts.append("성장") }
    if stock.grossMargin?.isFinite == true { parts.append("마진") }
    return "선정 기준 \(parts.prefix(4).joined(separator: " · "))"
}

internal func smallCapHomeMetrics(_ stock: SmallCapStock, currency: String) -> [HomeCardMetric] {
    [
        HomeCardMetric(label: "시총", value: cap(stock.marketCap, currency: currency)),
        HomeCardMetric(label: "ROIC", value: pct(stock.roic, signed: false)),
        HomeCardMetric(label: "성장", value: pct(stock.revGrowth)),
        HomeCardMetric(label: "FCF", value: pct(stock.fcfMargin, signed: false)),
        HomeCardMetric(label: "거래량", value: homeMultipleText(stock.volumeSurge))
    ]
}

internal func smallCapHomeReason(_ stock: SmallCapStock) -> HomeCardReason {
    if let score = stock.totalScore, score.isFinite, score >= 70 {
        return HomeCardReason(title: "상위점수", detail: "스몰캡 점수 \(String(format: "%.0f", score))점으로 선별됐습니다.", color: AppTheme.accent)
    }
    if let accel = stock.revAccel, accel.isFinite, accel > 0 {
        return HomeCardReason(title: "성장가속", detail: "매출 성장 가속 신호가 있습니다.", color: AppTheme.positive)
    }
    if let volume = stock.volumeSurge, volume.isFinite, volume >= 1.5 {
        return HomeCardReason(title: "거래량", detail: "평소 대비 \(homeMultipleText(volume)) 거래량입니다.", color: AppTheme.momentum)
    }
    if let debt = stock.debtEbitda, debt.isFinite, debt > 4 {
        return HomeCardReason(title: "주의", detail: "Debt/EBITDA 부담을 먼저 확인하세요.", color: AppTheme.warning)
    }
    return HomeCardReason(title: "확인", detail: "성장성, 현금흐름, 재무 리스크를 같이 보세요.", color: AppTheme.secondaryText)
}

internal func smallCapHomeBasis(_ stock: SmallCapStock) -> String {
    var parts = ["총점"]
    if stock.marketCap?.isFinite == true { parts.append("시총") }
    if stock.revAccel?.isFinite == true { parts.append("성장가속") }
    if stock.fcfMargin?.isFinite == true { parts.append("FCF") }
    if stock.volumeSurge?.isFinite == true { parts.append("거래량") }
    return "선정 기준 \(parts.prefix(4).joined(separator: " · "))"
}

internal func earningsHomeMetrics(_ stock: EarningsStock) -> [HomeCardMetric] {
    [
        HomeCardMetric(label: "EPS", value: pct(stock.surprisePct)),
        HomeCardMetric(label: "수익", value: pct(stock.returnSince)),
        HomeCardMetric(label: "경과", value: homeDaysText(stock.daysSince)),
        HomeCardMetric(label: "거래량", value: homeMultipleText(stock.volumeSurge)),
        HomeCardMetric(label: "발표", value: homeCompactDateText(stock.earningsDate))
    ]
}

internal func earningsHomeReason(_ stock: EarningsStock) -> HomeCardReason {
    if let signal = stock.signalStrength, signal.isFinite, signal >= 1 {
        return HomeCardReason(title: "강한시그널", detail: "서프라이즈와 가격 반응이 함께 나왔습니다.", color: AppTheme.momentum)
    }
    if let surprise = stock.surprisePct, surprise.isFinite, surprise > 0 {
        return HomeCardReason(title: "서프라이즈", detail: "EPS가 예상보다 \(pct(surprise)) 높았습니다.", color: AppTheme.positive)
    }
    if let returnSince = stock.returnSince, returnSince.isFinite, returnSince > 0 {
        return HomeCardReason(title: "가격반응", detail: "발표 후 수익률 \(pct(returnSince))입니다.", color: AppTheme.positive)
    }
    if let days = stock.daysSince, days.isFinite, days <= 7 {
        return HomeCardReason(title: "최근이벤트", detail: "발표 후 \(Int(days))일째라 반응 확인 구간입니다.", color: AppTheme.accent)
    }
    return HomeCardReason(title: "확인", detail: "EPS, 수익률, 거래량 반응을 함께 보세요.", color: AppTheme.secondaryText)
}

internal func earningsHomeBasis(_ stock: EarningsStock) -> String {
    var parts = ["EPS"]
    if stock.signalStrength?.isFinite == true { parts.append("Signal") }
    if stock.returnSince?.isFinite == true { parts.append("발표 후 수익률") }
    if stock.daysSince?.isFinite == true { parts.append("경과일") }
    if stock.volumeSurge?.isFinite == true { parts.append("거래량") }
    return "선정 기준 \(parts.prefix(4).joined(separator: " · "))"
}

internal func homeCompactDateText(_ value: String?) -> String {
    let text = formattedUpdateTimestamp(value)
    guard text != "-" else { return "-" }
    return String(text.prefix(10))
}

internal func homeEarningsCalendarSummary(_ item: EarningsCalendarItem?) -> String {
    guard let item else { return "예정 실적 일정 대기 중" }
    return "\(homeCompactDateText(item.nextEarningsDate)) · \(homeEarningsCalendarDayText(item.daysUntil))"
}

internal func homeEarningsCalendarDayText(_ days: Int?) -> String {
    guard let days else { return "D-?" }
    if days == 0 { return "D-Day" }
    if days > 0 { return "D-\(days)" }
    return "D+\(abs(days))"
}

internal func homeMultipleText(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    return String(format: "x%.1f", value)
}

internal func homeDaysText(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    return "\(Int(value.rounded()))일"
}
