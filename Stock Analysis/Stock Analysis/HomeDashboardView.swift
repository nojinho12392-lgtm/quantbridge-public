import Combine
import Foundation
import SwiftUI
import UIKit

private let dailyRoutineDateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.timeZone = .current
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter
}()

struct HomeDashboardView: View {
    let openSearch: () -> Void
    let openPortfolio: () -> Void
    let openPulse: () -> Void
    let openWatch: () -> Void
    let openAccount: () -> Void

    @StateObject private var usPortfolio = PortfolioVM(market: .us)
    @StateObject private var krPortfolio = PortfolioVM(market: .kr)
    @StateObject private var smallCap = SmallCapVM()
    @StateObject private var pulse = PulseVM()
    @StateObject private var news = NewsVM()
    @StateObject private var marketIndices = MarketIndicesVM()
    @StateObject private var marketIndicators = MarketIndicatorsVM()
    @StateObject private var signalEvents = SignalEventsVM()
    @StateObject private var homeOpsStatus = HomeOpsStatusVM()

    @State private var selectedPortfolio: PortfolioStock?
    @State private var selectedSmallCap: SmallCapStock?
    @State private var selectedEarnings: EarningsStock?
    @State private var selectedCalendarEarnings: EarningsCalendarItem?
    @State private var selectedWatchItem: WatchlistItem?
    @State private var showNews = false
    @State private var showSmallCap = false
    @State private var showMarketIndicators = false
    @State private var selectedNewsArticle: NewsItem?
    @State private var watchPriceMetrics: [String: HomeStockPriceMetric] = [:]
    @State private var watchPriceMetricsKey = ""
    @State private var investmentProfile = InvestmentProfile.load()
    @AppStorage("dailyRoutine.date") private var routineDate = ""
    @AppStorage("dailyRoutine.completedIDs") private var routineCompletedIDs = ""
    @AppStorage("homeActionInbox.date") private var actionInboxDate = ""
    @AppStorage("homeActionInbox.completedIDs") private var actionInboxCompletedIDs = ""
    @AppStorage("homeActionInbox.snoozedIDs") private var actionInboxSnoozedIDs = ""
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var notifications: NotificationStore
    @EnvironmentObject private var decisions: InvestmentDecisionStore

    init(
        openSearch: @escaping () -> Void = {},
        openPortfolio: @escaping () -> Void = {},
        openPulse: @escaping () -> Void = {},
        openWatch: @escaping () -> Void = {},
        openAccount: @escaping () -> Void = {}
    ) {
        self.openSearch = openSearch
        self.openPortfolio = openPortfolio
        self.openPulse = openPulse
        self.openWatch = openWatch
        self.openAccount = openAccount
    }

    private var isLoading: Bool {
        if news.isLoading { return true }
        return [usPortfolio.state, krPortfolio.state, smallCap.state, pulse.state].contains { state in
            if case .loading = state { return true }
            if case .idle = state { return true }
            return false
        }
    }

    private var topPortfolio: [PortfolioStock] {
        (usPortfolio.stocks + krPortfolio.stocks)
            .sorted { candidateActionPriority($0) > candidateActionPriority($1) }
            .prefix(3)
            .map { $0 }
    }

    private var topSmallCaps: [SmallCapStock] {
        (smallCap.usStocks + smallCap.krStocks)
            .sorted { smallCapActionPriority($0) > smallCapActionPriority($1) }
            .prefix(3)
            .map { $0 }
    }

    private var topEarnings: [EarningsStock] {
        (pulse.usEarnings + pulse.krEarnings)
            .sorted { ($0.signalStrength ?? -.infinity) > ($1.signalStrength ?? -.infinity) }
            .prefix(3)
            .map { $0 }
    }

    private var topNews: [NewsItem] {
        Array(news.items.prefix(3))
    }

    private var upcomingEarningsCalendar: [EarningsCalendarItem] {
        pulse.earningsCalendar
            .filter { ($0.daysUntil ?? 0) >= 0 }
            .sorted { left, right in
                left.nextEarningsDate < right.nextEarningsDate
            }
            .prefix(3)
            .map { $0 }
    }

    private var marketBriefingValue: String {
        firstMacroValue(["Regime", "Macro_Regime", "US_Regime", "Market_Regime", "risk_regime"]) ?? "시장 상태 대기"
    }

    private var marketBriefingDetail: String {
        if let risk = firstMacroValue(["Risk_Signal", "Risk_Level", "Risk", "Signal"]) {
            return "위험 신호 \(risk)"
        }
        if !topHeaderIndices.isEmpty {
            let index = topHeaderIndices[0]
            return "\(index.label) \(pct(index.changePct))"
        }
        return isLoading ? "시장 데이터 동기화 중" : "주요 지수와 매크로를 함께 확인하세요"
    }

    private var topHeaderIndices: [MarketIndexQuote] {
        let synced = topHeaderMarketIndices(from: marketIndicators.items)
        return synced.isEmpty ? marketIndices.indices : synced
    }

    private var marketBriefingColor: Color {
        let riskText = firstMacroValue(["Risk_Signal", "Risk_Level", "Risk", "Signal"]) ?? ""
        let text = "\(marketBriefingValue) \(riskText)".uppercased()
        if text.contains("RISK_OFF") || text.contains("BEAR") || text.contains("WARNING") || text.contains("위험") {
            return AppTheme.warning
        }
        if text.contains("RISK_ON") || text.contains("BULL") || text.contains("PASS") || text.contains("정상") {
            return AppTheme.positive
        }
        return AppTheme.accent
    }

    private var latestDataTimestamp: String? {
        var values: [String] = []
        values.append(contentsOf: marketIndices.indices.map(\.updatedAt))
        values.append(contentsOf: marketIndicators.items.compactMap(\.updatedAt))
        values.append(contentsOf: usPortfolio.stocks.compactMap(\.lastUpdated))
        values.append(contentsOf: krPortfolio.stocks.compactMap(\.lastUpdated))
        values.append(contentsOf: smallCap.usStocks.compactMap(\.lastUpdated))
        values.append(contentsOf: smallCap.krStocks.compactMap(\.lastUpdated))
        values.append(contentsOf: news.items.map(\.publishedAt))
        if let generated = portfolioGeneratedAt(usPortfolio.meta) { values.append(generated) }
        if let generated = portfolioGeneratedAt(krPortfolio.meta) { values.append(generated) }
        if let generated = pulse.macro["Generated"] { values.append(generated) }
        return newestTimestamp(values)
    }

    private var dataIssueCount: Int {
        [
            stateHasFailure(usPortfolio.state),
            stateHasFailure(krPortfolio.state),
            stateHasFailure(smallCap.state),
            stateHasFailure(pulse.state),
            !(news.error?.isEmpty ?? true),
            !(marketIndicators.error?.isEmpty ?? true),
            !(usPortfolio.warning?.isEmpty ?? true),
            !(krPortfolio.warning?.isEmpty ?? true),
            !(smallCap.warning?.isEmpty ?? true),
            !(pulse.warning?.isEmpty ?? true)
        ].filter { $0 }.count
    }

    private var loadedSourceCount: Int {
        [
            !usPortfolio.stocks.isEmpty,
            !krPortfolio.stocks.isEmpty,
            !smallCap.usStocks.isEmpty || !smallCap.krStocks.isEmpty,
            !pulse.usEarnings.isEmpty || !pulse.krEarnings.isEmpty,
            !marketIndices.indices.isEmpty,
            !marketIndicators.items.isEmpty,
            !news.items.isEmpty
        ].filter { $0 }.count
    }

    private var indexSourceStatus: HomeSourceStatus {
        homeSourceStatus(
            title: "지수",
            isLoading: marketIndices.isLoading || marketIndicators.isLoading,
            error: marketIndices.error ?? marketIndicators.error,
            count: max(marketIndices.indices.count, marketIndicators.items.count),
            emptyDetail: "지수 대기"
        )
    }

    private var newsSourceStatus: HomeSourceStatus {
        homeSourceStatus(
            title: "뉴스",
            isLoading: news.isLoading,
            error: news.error,
            count: news.items.count,
            emptyDetail: "뉴스 대기"
        )
    }

    private var opsSourceStatus: HomeSourceStatus {
        homeOpsSourceStatus(
            isLoading: homeOpsStatus.isLoading,
            researchQuality: homeOpsStatus.researchQuality,
            opsHealth: homeOpsStatus.opsHealth,
            error: homeOpsStatus.error
        )
    }

    private var todayRoutineKey: String {
        dailyRoutineDateFormatter.string(from: Date())
    }

    private var routineCompletedSet: Set<String> {
        guard routineDate == todayRoutineKey else { return [] }
        return Set(routineCompletedIDs.split(separator: ",").map(String.init))
    }

    private var dailyRoutineItems: [DailyRoutineItem] {
        [
            DailyRoutineItem(
                id: "data",
                title: "데이터 상태",
                detail: dataIssueCount == 0 ? "연결 소스 \(loadedSourceCount)개 정상" : "\(dataIssueCount)개 항목 확인 필요",
                symbol: dataIssueCount == 0 ? "checkmark.seal" : "exclamationmark.triangle",
                color: dataIssueCount == 0 ? AppTheme.quality : AppTheme.warning,
                actionTitle: "갱신",
                action: refresh
            ),
            DailyRoutineItem(
                id: "market",
                title: "시장 흐름",
                detail: "\(marketBriefingValue) · \(marketBriefingDetail)",
                symbol: "chart.line.uptrend.xyaxis",
                color: marketBriefingColor,
                actionTitle: "보기",
                action: { pushRoute { showMarketIndicators = true } }
            ),
            DailyRoutineItem(
                id: "watch",
                title: "관심 변화",
                detail: "기업과 지수의 변화 신호 확인",
                symbol: "heart",
                color: AppTheme.accent,
                actionTitle: "Watch",
                action: openWatch
            ),
            DailyRoutineItem(
                id: "earnings",
                title: "실적 이벤트",
                detail: upcomingEarningsCalendar.first.map { "\($0.name) \(homeEarningsCalendarDayText($0.daysUntil))" } ?? "예정 실적 일정 대기",
                symbol: "calendar",
                color: AppTheme.momentum,
                actionTitle: "일정",
                action: openPulse
            )
        ]
    }

    private var watchedCompanyItems: [WatchlistItem] {
        watchlist.items.filter { !$0.isMarketIndicator }
    }

    private var watchedIndicatorItems: [WatchlistItem] {
        watchlist.items.filter(\.isMarketIndicator)
    }

    private var watchPriceTaskKey: String {
        watchedCompanyItems
            .map { "\($0.market):\($0.ticker)" }
            .sorted()
            .joined(separator: "|")
    }

    private var personalizedSignals: [HomePersonalSignal] {
        var signals = watchedCompanyItems.map { serverEventSignal(for: $0) ?? personalSignal(for: $0) }
        if !watchedIndicatorItems.isEmpty {
            signals.append(indicatorWatchSignal())
        }
        return signals
            .sorted {
                if $0.priority == $1.priority { return $0.title < $1.title }
                return $0.priority > $1.priority
            }
    }

    private var personalizedSignalCount: Int {
        personalizedSignals.filter { $0.priority >= 2 }.count
    }

    private var watchedEarningsCount: Int {
        personalizedSignals.filter { $0.category == .earnings }.count
    }

    private var personalizedNotificationEvents: [WatchNotificationEvent] {
        personalizedSignals.compactMap(\.notificationEvent)
    }

    private var notificationSyncKey: String {
        let events = personalizedNotificationEvents
            .map { "\($0.id):\($0.daysUntil ?? -1)" }
            .joined(separator: "|")
        return "\(notifications.isEnabled):\(watchlist.items.count):\(events):\(latestDataTimestamp ?? "-")"
    }

    private var dailyNotificationSummary: String {
        if let signal = personalizedSignals.first, signal.priority >= 2 {
            return "\(signal.title): \(signal.detail)"
        }
        if watchlist.items.isEmpty {
            return "오늘 볼 후보와 시장 상태를 확인하세요."
        }
        return "관심 항목 \(watchlist.items.count)개를 기준으로 시장과 실적 일정을 확인하세요."
    }

    private var actionInboxCompletedSet: Set<String> {
        guard actionInboxDate == todayRoutineKey else { return [] }
        return Set(actionInboxCompletedIDs.split(separator: ",").map(String.init))
    }

    private var actionInboxSnoozedSet: Set<String> {
        guard actionInboxDate == todayRoutineKey else { return [] }
        return Set(actionInboxSnoozedIDs.split(separator: ",").map(String.init))
    }

    private var todayActionItems: [HomeActionInboxItem] {
        var items: [HomeActionInboxItem] = []

        if let next = upcomingEarningsCalendar.first {
            let name = localizedCompanyName(ticker: next.ticker, currentName: next.name, market: next.market)
            items.append(.init(
                id: "earnings-\(normalizedTicker(next.ticker))-\(next.nextEarningsDate)",
                title: "다가오는 실적 · \(name)",
                detail: "\(homeEarningsCalendarDayText(next.daysUntil)) · \(homeCompactDateText(next.nextEarningsDate)) 발표 예정",
                symbol: "calendar.badge.clock",
                color: AppTheme.momentum,
                actionTitle: "일정",
                priority: earningsActionPriority(next),
                action: openPulse
            ))
        }

        if let candidate = topPortfolio.first {
            let profileNote = candidateProfileNudge(candidate)
            let hasDecision = decisions.record(for: candidate.ticker) != nil
            if !hasDecision {
                items.append(.init(
                    id: "decision-\(normalizedTicker(candidate.ticker))",
                    title: "투자 결정서 작성 대기",
                    detail: "\(candidate.name) · 이유와 주의 신호를 남기면 나중에 판단을 복기할 수 있습니다.",
                    symbol: "square.and.pencil",
                    color: AppTheme.accent,
                    actionTitle: "작성",
                    priority: candidateActionPriority(candidate) + 20,
                    action: { pushRoute { selectedPortfolio = candidate } }
                ))
            }
            items.append(.init(
                id: "candidate-\(normalizedTicker(candidate.ticker))",
                title: investmentProfile.isConfigured ? "내 기준 최우선 후보" : "오늘 볼 후보 · \(candidate.name)",
                detail: [portfolioHomeReason(candidate).detail, profileNote].compactMap { $0 }.joined(separator: " · "),
                symbol: "scope",
                color: AppTheme.accent,
                actionTitle: "후보",
                priority: candidateActionPriority(candidate),
                action: { pushRoute { selectedPortfolio = candidate } }
            ))
        }

        if let small = topSmallCaps.first {
            let profileNote = smallCapProfileNudge(small)
            items.append(.init(
                id: "smallcap-\(normalizedTicker(small.ticker))",
                title: investmentProfile.isConfigured ? "내 기준 스몰캡 비교" : "스몰캡 후보 비교",
                detail: ["\(small.name) · 점수 \(score(small.totalScore)) · 거래량 \(homeMultipleText(small.volumeSurge))", profileNote].compactMap { $0 }.joined(separator: " · "),
                symbol: "scope",
                color: AppTheme.quality,
                actionTitle: "보기",
                priority: smallCapActionPriority(small),
                action: { pushRoute { selectedSmallCap = small } }
            ))
        }

        items.append(contentsOf: homeMistakeCoachActions())

        if let newsItem = topNews.first {
            let detail = newsItem.impactReason.isEmpty ? newsItem.title : newsItem.impactReason
            items.append(.init(
                id: "news-\(newsItem.id)",
                title: "시장 뉴스",
                detail: detail,
                symbol: "newspaper",
                color: AppTheme.accent,
                actionTitle: "뉴스",
                priority: newsActionPriority(newsItem),
                action: { openNews(newsItem) }
            ))
        }

        var seen = Set<String>()
        return items.filter { item in
            guard !seen.contains(item.id) else { return false }
            seen.insert(item.id)
            return true
        }
    }

    private var todayDecisionItems: [HomeActionInboxItem] {
        var items: [HomeActionInboxItem] = [
            .init(
                id: "market-regime",
                title: marketBriefingValue,
                detail: "\(marketBriefingValue) · \(marketBriefingDetail)",
                symbol: "chart.line.uptrend.xyaxis",
                color: marketBriefingColor,
                actionTitle: "지수",
                priority: marketActionPriority,
                action: { pushRoute { showMarketIndicators = true } }
            )
        ]
        items.append(contentsOf: todayActionItems)

        var seen = Set<String>()
        return items.filter { item in
            guard !seen.contains(item.id) else { return false }
            seen.insert(item.id)
            return true
        }
        .sorted {
            if $0.priority == $1.priority { return $0.title < $1.title }
            return $0.priority > $1.priority
        }
    }

    private var marketActionPriority: Double {
        let largestIndexMove = marketIndices.indices
            .compactMap(\.changePct)
            .map(abs)
            .max() ?? 0
        let riskText = "\(marketBriefingValue) \(marketBriefingDetail)".uppercased()
        let riskBonus = riskText.contains("RISK_OFF") || riskText.contains("위험") || riskText.contains("WARNING") ? 24.0 : 0.0
        return 48.0 + min(largestIndexMove * 1_000, 24.0) + riskBonus
    }

    private func actionPriority(for signal: HomePersonalSignal) -> Double {
        var value = 56.0 + Double(signal.priority * 12)
        if signal.category == .earnings { value += 10 }
        if signal.category == .indicator { value += 4 }
        if signal.title.contains("주의") || signal.title.contains("타이밍") || signal.title.contains("급락") { value += 7 }
        return value
    }

    private func earningsActionPriority(_ item: EarningsCalendarItem) -> Double {
        guard let days = item.daysUntil else { return 62 }
        if days <= 0 { return 96 }
        if days <= 3 { return 90 - Double(days) }
        if days <= 7 { return 78 - Double(days) }
        return 58
    }

    private func candidateActionPriority(_ stock: PortfolioStock) -> Double {
        let expected = abs(stock.expectedReturn ?? 0)
        let rankBonus = max(0, 8 - Double(stock.rank ?? 8))
        let cautionBonus = (stock.expectedReturn ?? 0) < 0 ? 18.0 : 0.0
        let scoreBonus = min(max((stock.totalScore ?? 0) - 70, 0) / 4, 10)
        let momentumBonus = min(homePercentMagnitude(stock.return1M) / 2, 10)
        let rankMoveBonus = min(abs(Double(stock.rankChange ?? 0)) * 1.5, 8)
        let freshRankBonus = (stock.rankStatus ?? "").contains("신규") ? 6.0 : 0.0
        return 54.0 + min(expected * 100, 18.0) + rankBonus + cautionBonus + scoreBonus + momentumBonus + rankMoveBonus + freshRankBonus + profileCandidatePriorityBonus(stock)
    }

    private func smallCapActionPriority(_ stock: SmallCapStock) -> Double {
        let scoreBonus = min(max(((stock.totalScore ?? 0) - 60) / 3, 0), 14)
        let volumeBonus = min(max(((stock.volumeSurge ?? 1) - 1) * 10, 0), 16)
        let growthBonus = min(homePercentMagnitude(stock.revGrowth) / 3, 10)
        let rankMoveBonus = min(abs(Double(stock.rankChange ?? 0)) * 1.2, 6)
        return 50.0 + scoreBonus + volumeBonus + growthBonus + rankMoveBonus + profileSmallCapPriorityBonus(stock)
    }

    private func profileCandidatePriorityBonus(_ stock: PortfolioStock) -> Double {
        guard investmentProfile.isConfigured else { return 0 }
        let styleBonus: Double
        if investmentProfile.style.contains("성장"), homePercentMagnitude(stock.revGrowth) >= 12 {
            styleBonus = 9
        } else if investmentProfile.style.contains("가치"), (stock.expectedReturn ?? 0) > 0.08 {
            styleBonus = 8
        } else if investmentProfile.style.contains("퀄리티"), (stock.roic ?? 0) > 0.12 || (stock.grossMargin ?? 0) > 0.35 {
            styleBonus = 8
        } else if investmentProfile.style.contains("모멘텀"), homePercentMagnitude(stock.return1M) >= 6 {
            styleBonus = 7
        } else {
            styleBonus = 0
        }
        let riskBonus = (investmentProfile.riskTolerance.contains("보수") || investmentProfile.riskTolerance.contains("안정")) &&
            ((stock.roic ?? 0) > 0.10 || (stock.grossMargin ?? 0) > 0.30) ? 4.0 : 0.0
        let conflicts = candidateConflictLabels(return1M: stock.return1M, expectedReturn: stock.expectedReturn, debtEbitda: nil, volumeSurge: nil)
        return styleBonus + riskBonus - Double(conflicts.count) * 7
    }

    private func profileSmallCapPriorityBonus(_ stock: SmallCapStock) -> Double {
        guard investmentProfile.isConfigured else { return 0 }
        let styleBonus: Double
        if investmentProfile.style.contains("성장"), homePercentMagnitude(stock.revGrowth) >= 15 {
            styleBonus = 9
        } else if investmentProfile.style.contains("퀄리티"), (stock.roic ?? 0) > 0.12 || (stock.fcfMargin ?? 0) > 0.06 {
            styleBonus = 8
        } else if investmentProfile.style.contains("모멘텀"), (stock.volumeSurge ?? 1) >= 1.8 {
            styleBonus = 7
        } else if investmentProfile.style.contains("가치"), (stock.totalScore ?? 0) >= 70 {
            styleBonus = 5
        } else {
            styleBonus = 0
        }
        let conflicts = candidateConflictLabels(return1M: stock.return1M, expectedReturn: nil, debtEbitda: stock.debtEbitda, volumeSurge: stock.volumeSurge)
        return styleBonus - Double(conflicts.count) * 8
    }

    private func candidateProfileNudge(_ stock: PortfolioStock) -> String? {
        guard investmentProfile.isConfigured else { return nil }
        var positives: [String] = []
        if investmentProfile.style.contains("성장"), homePercentMagnitude(stock.revGrowth) >= 12 { positives.append("성장 기준 부합") }
        if investmentProfile.style.contains("가치"), (stock.expectedReturn ?? 0) > 0.08 { positives.append("상대 저평가 후보") }
        if investmentProfile.style.contains("퀄리티"), (stock.roic ?? 0) > 0.12 || (stock.grossMargin ?? 0) > 0.35 { positives.append("퀄리티 근거") }
        if investmentProfile.style.contains("모멘텀"), homePercentMagnitude(stock.return1M) >= 6 { positives.append("모멘텀 확인") }
        let conflicts = candidateConflictLabels(return1M: stock.return1M, expectedReturn: stock.expectedReturn, debtEbitda: nil, volumeSurge: nil)
        let values = Array(positives.prefix(1)) + conflicts.prefix(1).map { "주의: \($0)" }
        return values.isEmpty ? nil : values.joined(separator: " · ")
    }

    private func smallCapProfileNudge(_ stock: SmallCapStock) -> String? {
        guard investmentProfile.isConfigured else { return nil }
        var positives: [String] = []
        if investmentProfile.style.contains("성장"), homePercentMagnitude(stock.revGrowth) >= 15 { positives.append("성장 기준 부합") }
        if investmentProfile.style.contains("퀄리티"), (stock.roic ?? 0) > 0.12 || (stock.fcfMargin ?? 0) > 0.06 { positives.append("퀄리티 근거") }
        if investmentProfile.style.contains("모멘텀"), (stock.volumeSurge ?? 1) >= 1.8 { positives.append("거래량 확인") }
        let conflicts = candidateConflictLabels(return1M: stock.return1M, expectedReturn: nil, debtEbitda: stock.debtEbitda, volumeSurge: stock.volumeSurge)
        let values = Array(positives.prefix(1)) + conflicts.prefix(1).map { "주의: \($0)" }
        return values.isEmpty ? nil : values.joined(separator: " · ")
    }

    private func candidateConflictLabels(return1M: Double?, expectedReturn: Double?, debtEbitda: Double?, volumeSurge: Double?) -> [String] {
        guard !investmentProfile.avoidances.isEmpty else { return [] }
        let move = homePercentMagnitude(return1M)
        var labels: [String] = []
        if investmentProfile.avoidances.contains(where: { $0.contains("급등락") }), move >= 14 { labels.append("급등락") }
        if investmentProfile.avoidances.contains(where: { $0.contains("고평가") }), (expectedReturn ?? 0) < 0 { labels.append("고평가") }
        if investmentProfile.avoidances.contains(where: { $0.contains("부채") }), (debtEbitda ?? 0) >= 3 { labels.append("부채") }
        if investmentProfile.avoidances.contains(where: { $0.contains("거래량") }), (volumeSurge ?? 1) >= 2.2 { labels.append("거래량 급증") }
        return Array(Set(labels)).sorted()
    }

    private func homeMistakeCoachActions() -> [HomeActionInboxItem] {
        var items: [HomeActionInboxItem] = []
        if let weak = watchedCompanyItems.first(where: { item in
            let thesis = item.investmentThesis
            return thesis.isEmpty || thesis.quality.percent < 80
        }) {
            let thesis = weak.investmentThesis
            items.append(.init(
                id: "coach-thesis-\(normalizedTicker(weak.ticker))",
                title: "가설 미완성 방지",
                detail: thesis.isEmpty ? "\(weak.name) · 관심 이유와 무효 조건을 먼저 남기세요." : "\(weak.name) · \(thesis.quality.missingFields.prefix(2).joined(separator: " · ")) 보강 필요",
                symbol: "lightbulb",
                color: AppTheme.warning,
                actionTitle: "정리",
                priority: 86,
                action: openWatch
            ))
        }
        if watchedCompanyItems.count >= 8 {
            items.append(.init(
                id: "coach-watch-spread",
                title: "관심 분산 경고",
                detail: "관심 기업 \(watchedCompanyItems.count)개입니다. 오늘은 가설 완성도가 낮은 항목부터 줄이세요.",
                symbol: "checkmark.seal",
                color: AppTheme.warning,
                actionTitle: "Watch",
                priority: 80,
                action: openWatch
            ))
        }
        if let stock = topPortfolio.first,
           homePercentMagnitude(stock.return1M) >= 14,
           investmentProfile.avoidances.contains(where: { $0.contains("급등락") }) || investmentProfile.riskTolerance.contains("보수") {
            items.append(.init(
                id: "coach-fomo-\(normalizedTicker(stock.ticker))",
                title: "추격매수 방지",
                detail: "\(stock.name) · 1개월 변동 \(pct(stock.return1M)). 비교 후보와 무효 조건을 먼저 확인하세요.",
                symbol: "chart.line.uptrend.xyaxis",
                color: AppTheme.warning,
                actionTitle: "보기",
                priority: 84,
                action: { pushRoute { selectedPortfolio = stock } }
            ))
        }
        if let stock = topSmallCaps.first,
           (stock.volumeSurge ?? 1) >= 2.2,
           investmentProfile.avoidances.contains(where: { $0.contains("거래량") || $0.contains("급등락") }) {
            items.append(.init(
                id: "coach-volume-\(normalizedTicker(stock.ticker))",
                title: "거래량 과열 점검",
                detail: "\(stock.name) · 거래량 \(homeMultipleText(stock.volumeSurge)). 모멘텀보다 지속 조건을 먼저 보세요.",
                symbol: "exclamationmark.triangle",
                color: AppTheme.warning,
                actionTitle: "보기",
                priority: 83,
                action: { pushRoute { selectedSmallCap = stock } }
            ))
        }
        return items
    }

    private func homePercentMagnitude(_ value: Double?) -> Double {
        guard let value, value.isFinite else { return 0 }
        let magnitude = abs(value)
        return magnitude <= 1 ? magnitude * 100 : magnitude
    }

    private func newsActionPriority(_ item: NewsItem) -> Double {
        let move = abs(item.relatedChangePct ?? 0)
        let impact = abs(item.impactScore)
        let watchBonus = watchedCompanyItems.contains { watch in
            let keys = watchMatchKeys(watch.ticker)
            let relatedKeys = Set(([item.ticker] + item.relatedTickers).flatMap { Array(watchMatchKeys($0)) })
            return !keys.isDisjoint(with: relatedKeys)
        } ? 16.0 : 0.0
        return 52.0 + min(move * 1_000, 24.0) + min(impact * 18.0, 18.0) + watchBonus
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    HomeTopHeader(
                        indices: topHeaderIndices,
                        openSearch: openSearch,
                        openMarketIndicators: { pushRoute { showMarketIndicators = true } }
                    )

                    HomeTemplateIntro()

                    HomeTemplateDecisionStack(
                        items: Array(todayDecisionItems.prefix(3)),
                        profile: investmentProfile,
                        isLoading: isLoading,
                        refresh: refresh
                    )

                    HomeTemplateWatchSection(
                        signals: personalizedSignals,
                        portfolioStocks: usPortfolio.stocks + krPortfolio.stocks,
                        smallCapStocks: smallCap.usStocks + smallCap.krStocks,
                        watchItems: watchlist.items,
                        priceMetrics: watchPriceMetrics,
                        isLoading: isLoading,
                        openWatch: openWatch,
                        openSignal: openPersonalSignal,
                        refresh: refresh
                    )

                    HomePersonalLensCard(
                        profile: investmentProfile,
                        openProfile: openAccount
                    )

                    HomeTemplateCandidateList(
                        stocks: topPortfolio,
                        profile: investmentProfile,
                        isLoading: isLoading,
                        openPortfolio: openPortfolio,
                        refresh: refresh,
                        open: { selectedPortfolio = $0 }
                    )

                    HomeCoverageCurationCard(
                        coveredCount: usPortfolio.stocks.count + krPortfolio.stocks.count + smallCap.usStocks.count + smallCap.krStocks.count,
                        watchCompanyCount: watchedCompanyItems.count,
                        openSearch: openSearch
                    )
                }
                .padding(.horizontal)
                .padding(.vertical, 14)
            }
            .appTabBarInset()
            .scrollContentBackground(.hidden)
            .appScreenBackground()
            .navigationTitle("")
            .toolbar(.hidden, for: .navigationBar)
            .onAppear {
                investmentProfile = InvestmentProfile.load()
            }
            .overlay {
                if isLoading && topPortfolio.isEmpty && topSmallCaps.isEmpty && topEarnings.isEmpty {
                    ProgressView("대시보드 로딩 중")
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                }
            }
            .navigationDestination(isPresented: $showNews) {
                NewsTabView()
            }
            .navigationDestination(isPresented: $showSmallCap) {
                SmallCapTabView()
            }
            .navigationDestination(isPresented: $showMarketIndicators) {
                MarketIndicatorsScreen(vm: marketIndicators)
            }
            .sheet(item: $selectedNewsArticle) { item in
                NewsArticleSheet(item: item)
            }
        }
        .task {
            ensureRoutineDate()
            ensureActionInboxDate()
            await load()
            await homeOpsStatus.load()
            await syncNotifications()
        }
        .task(id: watchPriceTaskKey) {
            await refreshWatchPriceMetrics(force: true)
        }
        .task(id: "home-price-auto-\(watchPriceTaskKey)") {
            guard !watchPriceTaskKey.isEmpty else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.fastPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("home-watch-prices-\(watchPriceTaskKey)", minInterval: 60) else { continue }
                await refreshWatchPriceMetrics(force: true)
            }
        }
        .task(id: "home-candidate-price-auto") {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.standardPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("home-candidates", minInterval: 120) else { continue }
                async let us: Void = usPortfolio.refresh()
                async let kr: Void = krPortfolio.refresh()
                _ = await (us, kr)
            }
        }
        .task(id: notificationSyncKey) {
            await syncNotifications()
        }
        .fullScreenCover(item: $selectedPortfolio) { stock in
            StockDetailSheet(
                ticker: stock.ticker,
                name: stock.name,
                currency: marketCurrency(for: stock.ticker, market: stock.market),
                staticMetrics: portfolioMetrics(stock),
                investmentSignals: portfolioSignals(stock)
            )
        }
        .fullScreenCover(item: $selectedSmallCap) { stock in
            StockDetailSheet(
                ticker: stock.ticker,
                name: stock.name,
                currency: marketCurrency(for: stock.ticker, market: stock.market),
                staticMetrics: smallCapMetrics(stock),
                investmentSignals: smallCapSignals(stock)
            )
        }
        .fullScreenCover(item: $selectedEarnings) { stock in
            StockDetailSheet(
                ticker: stock.ticker,
                name: stock.name,
                currency: marketCurrency(for: stock.ticker),
                staticMetrics: earningsMetrics(stock),
                investmentSignals: earningsSignals(stock)
            )
        }
        .fullScreenCover(item: $selectedCalendarEarnings) { item in
            StockDetailSheet(
                ticker: item.ticker,
                name: item.name,
                currency: marketCurrency(for: item.ticker, market: item.market),
                staticMetrics: earningsCalendarMetrics(item),
                investmentSignals: earningsCalendarSignals(item)
            )
        }
        .fullScreenCover(item: $selectedWatchItem) { item in
            StockDetailSheet(
                ticker: item.ticker,
                name: item.name,
                currency: item.currency,
                staticMetrics: [
                    StaticMetric(label: "분류", value: item.note),
                    StaticMetric(label: "시장", value: item.market)
                ],
                investmentSignals: watchlistSignals(item)
            )
        }
    }

    private func load() async {
        async let indices: Void = marketIndices.load()
        async let indicators: Void = marketIndicators.load(category: "all")
        async let us: Void = usPortfolio.load()
        async let kr: Void = krPortfolio.load()
        async let small: Void = smallCap.load()
        async let pulseLoad: Void = pulse.load()
        async let newsLoad: Void = news.load()
        async let eventLoad: Void = signalEvents.load()
        _ = await (indices, indicators, us, kr, small, pulseLoad, newsLoad, eventLoad)
        await loadMissingHomeSections()
    }

    private func loadMissingHomeSections() async {
        if smallCap.usStocks.isEmpty && smallCap.krStocks.isEmpty {
            await smallCap.refresh()
        }
        if pulse.usEarnings.isEmpty && pulse.krEarnings.isEmpty && pulse.earningsCalendar.isEmpty {
            await pulse.refresh()
        }
        if news.items.isEmpty {
            await news.refresh(query: "", market: .all)
        }
        if signalEvents.items.isEmpty {
            await signalEvents.refresh()
        }
    }

    private func refresh() {
        Task {
            async let indices: Void = marketIndices.load()
            async let indicators: Void = marketIndicators.load(refresh: true, category: "all")
            async let us: Void = usPortfolio.refresh()
            async let kr: Void = krPortfolio.refresh()
            async let small: Void = smallCap.refresh()
            async let pulseLoad: Void = pulse.refresh()
            async let newsLoad: Void = news.refresh(query: "", market: .all)
            async let eventLoad: Void = signalEvents.refresh()
            _ = await (indices, indicators, us, kr, small, pulseLoad, newsLoad, eventLoad)
            await refreshWatchPriceMetrics(force: true)
        }
    }

    private func refreshWatchPriceMetrics(force: Bool = false) async {
        let items = watchedCompanyItems
        let key = watchPriceTaskKey
        if !force, key == watchPriceMetricsKey, !watchPriceMetrics.isEmpty { return }
        watchPriceMetricsKey = key
        guard !items.isEmpty else {
            watchPriceMetrics = [:]
            return
        }

        var next: [String: HomeStockPriceMetric] = [:]
        let groups = Dictionary(grouping: items, by: homeWatchPriceMarket)
        for (market, marketItems) in groups {
            let tickers = Array(Set(marketItems.flatMap { homeWatchPriceLookupTickers($0.ticker, market: market) })).sorted()
            guard !tickers.isEmpty else { continue }
            do {
                let metrics = try await APIClient.shared.fetchHomeStockPriceMetrics(market: market, tickers: tickers, refresh: force)
                for metric in metrics {
                    for key in homeWatchMatchKeys(metric.ticker) {
                        next[key] = metric
                    }
                }
                for item in marketItems {
                    if let metric = homeWatchMatchKeys(item.ticker).compactMap({ next[$0] }).first {
                        for key in homeWatchMatchKeys(item.ticker) {
                            next[key] = metric
                        }
                    }
                }
            } catch {
                continue
            }
        }
        watchPriceMetrics = next
    }

    private func ensureRoutineDate() {
        if routineDate != todayRoutineKey {
            routineDate = todayRoutineKey
            routineCompletedIDs = ""
        }
    }

    private func toggleRoutineItem(_ id: String) {
        ensureRoutineDate()
        var ids = routineCompletedSet
        if ids.contains(id) {
            ids.remove(id)
        } else {
            ids.insert(id)
        }
        routineCompletedIDs = ids.sorted().joined(separator: ",")
    }

    private func resetRoutine() {
        routineDate = todayRoutineKey
        routineCompletedIDs = ""
    }

    private func ensureActionInboxDate() {
        if actionInboxDate != todayRoutineKey {
            actionInboxDate = todayRoutineKey
            actionInboxCompletedIDs = ""
            actionInboxSnoozedIDs = ""
        }
    }

    private func completeActionInboxItem(_ id: String) {
        ensureActionInboxDate()
        var completed = actionInboxCompletedSet
        var snoozed = actionInboxSnoozedSet
        completed.insert(id)
        snoozed.remove(id)
        actionInboxCompletedIDs = completed.sorted().joined(separator: ",")
        actionInboxSnoozedIDs = snoozed.sorted().joined(separator: ",")
    }

    private func snoozeActionInboxItem(_ id: String) {
        ensureActionInboxDate()
        var snoozed = actionInboxSnoozedSet
        var completed = actionInboxCompletedSet
        snoozed.insert(id)
        completed.remove(id)
        actionInboxSnoozedIDs = snoozed.sorted().joined(separator: ",")
        actionInboxCompletedIDs = completed.sorted().joined(separator: ",")
    }

    private func resetActionInbox() {
        actionInboxDate = todayRoutineKey
        actionInboxCompletedIDs = ""
        actionInboxSnoozedIDs = ""
    }

    private func enableNotifications() {
        Task {
            await notifications.requestAuthorization()
            await syncNotifications()
        }
    }

    private func disableNotifications() {
        notifications.disable()
    }

    private func syncNotifications() async {
        await notifications.sync(
            events: personalizedNotificationEvents,
            dailySummary: dailyNotificationSummary
        )
    }

    private func openNews(_ item: NewsItem) {
        guard !item.url.isEmpty else { return }
        pushRoute { selectedNewsArticle = item }
    }

    private func openPersonalSignal(_ signal: HomePersonalSignal) {
        if signal.category == .indicator {
            pushRoute { showMarketIndicators = true }
            return
        }

        let keys = watchMatchKeys(signal.ticker)
        if signal.category == .earnings {
            if let calendar = pulse.earningsCalendar
                .filter({ ($0.daysUntil ?? 0) >= 0 })
                .sorted(by: { $0.nextEarningsDate < $1.nextEarningsDate })
                .first(where: { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }) {
                pushRoute { selectedCalendarEarnings = calendar }
                return
            }
            if let earnings = (pulse.usEarnings + pulse.krEarnings)
                .first(where: { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }) {
                pushRoute { selectedEarnings = earnings }
                return
            }
        }

        if let portfolio = (usPortfolio.stocks + krPortfolio.stocks)
            .first(where: { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }) {
            pushRoute { selectedPortfolio = portfolio }
            return
        }
        if let small = (smallCap.usStocks + smallCap.krStocks)
            .first(where: { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }) {
            pushRoute { selectedSmallCap = small }
            return
        }
        if let earnings = (pulse.usEarnings + pulse.krEarnings)
            .first(where: { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }) {
            pushRoute { selectedEarnings = earnings }
            return
        }
        if let item = watchlist.items.first(where: { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }) {
            pushRoute { selectedWatchItem = item }
            return
        }
        openWatch()
    }

    private func serverEventSignal(for item: WatchlistItem) -> HomePersonalSignal? {
        guard let event = bestServerEvent(for: item) else { return nil }
        let color = serverEventColor(event)
        let category: HomePersonalSignalCategory = event.kind == "earnings_due" ? .earnings : .candidate
        let alertOption = serverJudgmentAlertOption(event.kind)
        return HomePersonalSignal(
            id: "server-\(event.eventID)",
            ticker: event.ticker,
            name: event.name,
            title: event.title,
            detail: event.detail,
            metrics: [event.metricValue, event.metricLabel].compactMap { $0 }.filter { !$0.isEmpty },
            symbol: serverEventSymbol(event),
            color: color,
            priority: max(2, event.severity + 1),
            category: category,
            updatedAt: event.eventTime ?? event.updatedAt,
            notificationEvent: event.severity >= 4 && wantsWatchAlert(item, option: alertOption) ? WatchNotificationEvent(
                id: "server-\(normalizedTicker(event.ticker))-\(event.kind)",
                title: serverJudgmentNotificationTitle(event),
                body: serverJudgmentNotificationBody(event),
                daysUntil: nil
            ) : nil
        )
    }

    private func bestServerEvent(for item: WatchlistItem) -> SignalEvent? {
        let keys = watchMatchKeys(item.ticker)
        return signalEvents.items
            .filter { !keys.isDisjoint(with: watchMatchKeys($0.ticker)) }
            .sorted {
                if $0.severity == $1.severity {
                    return ($0.eventTime ?? "") > ($1.eventTime ?? "")
                }
                return $0.severity > $1.severity
            }
            .first
    }

    private func watchMatchKeys(_ ticker: String) -> Set<String> {
        let normalized = normalizedTicker(ticker)
        var keys: Set<String> = normalized.isEmpty ? [] : [normalized]
        let code = krCode(from: normalized)
        if !code.isEmpty {
            keys.formUnion([code, "\(code).KS", "\(code).KQ"])
        }
        return keys
    }

    private func serverEventColor(_ event: SignalEvent) -> Color {
        switch event.kind {
        case "price_pressure", "price_drop", "rank_down":
            return AppTheme.negative
        case "earnings_due":
            return AppTheme.warning
        case "rank_up", "price_momentum", "price_spike":
            return AppTheme.positive
        default:
            return AppTheme.accent
        }
    }

    private func pushRoute(_ update: () -> Void) {
        withAnimation(QuantMotion.route) {
            update()
        }
    }

    private func serverEventSymbol(_ event: SignalEvent) -> String {
        switch event.kind {
        case "earnings_due":
            return "calendar.badge.clock"
        case "rank_up", "rank_down":
            return "arrow.up.arrow.down"
        case "price_drop", "price_pressure":
            return "exclamationmark.triangle"
        default:
            return "bolt.fill"
        }
    }

    private func personalSignal(for item: WatchlistItem) -> HomePersonalSignal {
        let portfolio = portfolioMatch(for: item)
        let small = smallCapMatch(for: item)
        let earnings = earningsMatch(for: item)
        let calendar = earningsCalendarMatch(for: item)
        let metrics = watchMetaMetrics(item) + personalSignalMetrics(portfolio: portfolio, smallCap: small, earnings: earnings, calendar: calendar)
        let updatedAt = portfolio?.lastUpdated ?? small?.lastUpdated ?? earnings?.earningsDate ?? calendar?.nextEarningsDate
        let thesis = item.investmentThesis

        if !thesis.isEmpty, thesis.quality.percent < 80 {
            let missing = thesis.quality.missingFields.prefix(2).joined(separator: " · ")
            return HomePersonalSignal(
                id: "thesis-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: "가설 보강",
                detail: "\(item.name) · \(missing) 보강 후 관찰을 이어가세요.",
                metrics: metrics + ["가설 \(thesis.quality.percent)%"],
                symbol: "lightbulb",
                color: AppTheme.warning,
                priority: 4,
                category: .candidate,
                updatedAt: updatedAt,
                notificationEvent: wantsWatchAlert(item, option: "가설 흔들림") ? WatchNotificationEvent(
                    id: "thesis-\(normalizedTicker(item.ticker))",
                    title: "이 종목의 투자 가정이 흔들렸습니다",
                    body: "\(item.name) · \(missing)이 비어 있습니다. 판단 전에 가설을 보강하세요.",
                    daysUntil: nil
                ) : nil
            )
        }

        if thesis.isEmpty && item.tags.isEmpty {
            return HomePersonalSignal(
                id: "empty-thesis-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: "관심 이유 없음",
                detail: "\(item.name)을 왜 보는지 먼저 남기면 이후 신호를 더 차분하게 판단할 수 있습니다.",
                metrics: ["기록 필요"],
                symbol: "square.and.pencil",
                color: AppTheme.warning,
                priority: 3,
                category: .basic,
                updatedAt: item.addedAt.formatted(date: .abbreviated, time: .omitted),
                notificationEvent: wantsWatchAlert(item, option: "가설 흔들림") ? WatchNotificationEvent(
                    id: "empty-thesis-\(normalizedTicker(item.ticker))",
                    title: "이 종목의 투자 가정이 흔들렸습니다",
                    body: "\(item.name)의 관심 이유와 틀렸다고 볼 조건이 비어 있습니다.",
                    daysUntil: nil
                ) : nil
            )
        }

        if let calendar, let days = calendar.daysUntil, days >= 0, days <= 7 {
            return HomePersonalSignal(
                id: "earnings-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: "실적 임박",
                detail: "\(item.name) \(homeEarningsCalendarDayText(days)) · \(homeCompactDateText(calendar.nextEarningsDate))",
                metrics: metrics,
                symbol: "calendar.badge.clock",
                color: AppTheme.warning,
                priority: 4,
                category: .earnings,
                updatedAt: updatedAt,
                notificationEvent: wantsWatchAlert(item, option: "실적 리스크") ? WatchNotificationEvent(
                    id: "earnings-\(normalizedTicker(item.ticker))",
                    title: "실적 발표 전 확인할 리스크가 생겼습니다",
                    body: "\(item.name) \(homeEarningsCalendarDayText(days)) · 가이던스, 마진, 무효 조건을 먼저 확인하세요.",
                    daysUntil: days
                ) : nil
            )
        }

        if let earnings, let signal = earnings.signalStrength, signal.isFinite, signal >= 1 {
            return HomePersonalSignal(
                id: "momentum-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: "실적 반응",
                detail: "\(item.name) Signal \(String(format: "%.2f", signal)) · 발표 후 \(pct(earnings.returnSince))",
                metrics: metrics,
                symbol: "bolt.fill",
                color: AppTheme.momentum,
                priority: 3,
                category: .earnings,
                updatedAt: updatedAt,
                notificationEvent: wantsWatchAlert(item, option: "점수·과열 동시") ? WatchNotificationEvent(
                    id: "momentum-\(normalizedTicker(item.ticker))",
                    title: "점수는 올랐지만 과열 신호도 같이 커졌습니다",
                    body: "\(item.name) Signal \(String(format: "%.2f", signal)) · 발표 후 \(pct(earnings.returnSince)). 추격보다 주의 신호를 확인하세요.",
                    daysUntil: nil
                ) : nil
            )
        }

        if let portfolio, let expected = portfolio.expectedReturn, expected.isFinite {
            let personal = personalizedStockInterpretation(profile: investmentProfile, stock: portfolio)
            let observationEvent = personal.headline.contains("관찰") && wantsWatchAlert(item, option: "성향 관찰")
            return HomePersonalSignal(
                id: "portfolio-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: expected >= 0 ? "후보 유지" : "타이밍 확인",
                detail: "\(item.name) 기대수익 \(pct(expected)) · 점수 \(score(portfolio.totalScore))",
                metrics: metrics,
                symbol: expected >= 0 ? "scope" : "exclamationmark.triangle",
                color: expected >= 0 ? AppTheme.accent : AppTheme.warning,
                priority: expected >= 0 ? 2 : 3,
                category: .candidate,
                updatedAt: updatedAt,
                notificationEvent: expected < 0 && wantsWatchAlert(item, option: "가설 흔들림") ? WatchNotificationEvent(
                    id: "risk-\(normalizedTicker(item.ticker))",
                    title: "이 종목의 투자 가정이 흔들렸습니다",
                    body: "\(item.name) 기대수익 \(pct(expected)) · 처음 가정과 무효 조건을 다시 확인하세요.",
                    daysUntil: nil
                ) : observationEvent ? WatchNotificationEvent(
                    id: "profile-\(normalizedTicker(item.ticker))",
                    title: "네 성향 기준으로는 아직 관찰 단계입니다",
                    body: "\(item.name) · \(personal.detail) \(personal.action)",
                    daysUntil: nil
                ) : nil
            )
        }

        if let small, let volume = small.volumeSurge, volume.isFinite, volume >= 1.5 {
            return HomePersonalSignal(
                id: "smallcap-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: "거래량 변화",
                detail: "\(item.name) 평소 대비 \(homeMultipleText(volume)) · 스몰캡 \(score(small.totalScore))",
                metrics: metrics,
                symbol: "waveform.path.ecg",
                color: AppTheme.accent,
                priority: 2,
                category: .candidate,
                updatedAt: updatedAt,
                notificationEvent: wantsWatchAlert(item, option: "점수·과열 동시") ? WatchNotificationEvent(
                    id: "volume-\(normalizedTicker(item.ticker))",
                    title: "점수는 올랐지만 과열 신호도 같이 커졌습니다",
                    body: "\(item.name) 거래량 \(homeMultipleText(volume)) · 점수 \(score(small.totalScore)). 지속 조건 전에는 추격을 피하세요.",
                    daysUntil: nil
                ) : nil
            )
        }

        if portfolio != nil || small != nil || earnings != nil || calendar != nil {
            return HomePersonalSignal(
                id: "linked-\(item.ticker)",
                ticker: item.ticker,
                name: item.name,
                title: "데이터 연결",
                detail: "\(item.name)의 후보, 실적, 일정 데이터가 연결됐습니다.",
                metrics: metrics,
                symbol: "checkmark.seal",
                color: AppTheme.quality,
                priority: 1,
                category: .candidate,
                updatedAt: updatedAt,
                notificationEvent: nil
            )
        }

        return HomePersonalSignal(
            id: "watch-\(item.ticker)",
            ticker: item.ticker,
            name: item.name,
            title: "기본 감시",
            detail: "\(item.name)의 가격과 기업 정보를 확인하세요.",
            metrics: [item.market, item.note].filter { !$0.isEmpty },
            symbol: "eye",
            color: AppTheme.secondaryText,
            priority: 0,
            category: .basic,
            updatedAt: item.addedAt.formatted(date: .abbreviated, time: .omitted),
            notificationEvent: nil
        )
    }

    private func watchMetaMetrics(_ item: WatchlistItem) -> [String] {
        var values: [String] = Array(item.tags.prefix(2))
        if let alert = item.alertOptions.first {
            values.append(watchAlertDisplayLabel(alert))
        }
        return Array(values.prefix(3))
    }

    private func wantsWatchAlert(_ item: WatchlistItem, option: String) -> Bool {
        watchAlertOptionMatches(item.alertOptions, option: option)
    }

    private func serverJudgmentNotificationTitle(_ event: SignalEvent) -> String {
        switch event.kind {
        case "earnings_due":
            return "실적 발표 전 확인할 리스크가 생겼습니다"
        case "price_pressure", "price_drop", "rank_down":
            return "이 종목의 투자 가정이 흔들렸습니다"
        case "rank_up", "price_momentum", "price_spike":
            return "점수는 올랐지만 과열 신호도 같이 커졌습니다"
        default:
            return "관심종목 판단 업데이트가 생겼습니다"
        }
    }

    private func serverJudgmentAlertOption(_ kind: String) -> String {
        switch kind {
        case "earnings_due":
            return "실적 리스크"
        case "price_pressure", "price_drop", "rank_down":
            return "가설 흔들림"
        case "rank_up", "price_momentum", "price_spike":
            return "점수·과열 동시"
        default:
            return "판단 업데이트"
        }
    }

    private func serverJudgmentNotificationBody(_ event: SignalEvent) -> String {
        let name = event.name.isEmpty ? event.ticker : event.name
        switch event.kind {
        case "earnings_due":
            return "\(name) · \(event.detail) 발표 전 리스크와 무효 조건을 확인하세요."
        case "price_pressure", "price_drop", "rank_down":
            return "\(name) · \(event.detail) 처음 투자 가정과 다르게 움직이는지 점검하세요."
        case "rank_up", "price_momentum", "price_spike":
            return "\(name) · \(event.detail) 점수 상승과 과열 신호를 함께 확인하세요."
        default:
            return "\(name) · \(event.detail)"
        }
    }

    private func indicatorWatchSignal() -> HomePersonalSignal {
        let strongest = watchedIndicatorItems
            .map { item -> (item: WatchlistItem, quote: MarketIndicatorQuote) in
                (item, indicatorQuote(for: item))
            }
            .sorted { abs($0.quote.changePct ?? 0) > abs($1.quote.changePct ?? 0) }
            .first
        let item = strongest?.item
        let quote = strongest?.quote
        let changeText = quote?.changePct.map { pct($0) } ?? "변화 대기"
        return HomePersonalSignal(
            id: "indicator-watch",
            ticker: item?.ticker ?? "INDICATORS",
            name: item?.name ?? "관심 지수",
            title: "관심 지수",
            detail: item.map { "\($0.name) \(changeText)" } ?? "지수 \(watchedIndicatorItems.count)개 감시 중",
            metrics: ["지수 \(watchedIndicatorItems.count)", changeText],
            symbol: "chart.line.uptrend.xyaxis",
            color: AppTheme.negative,
            priority: abs(quote?.changePct ?? 0) >= 0.015 ? 2 : 1,
            category: .indicator,
            updatedAt: quote?.updatedAt,
            notificationEvent: nil
        )
    }

    private func portfolioMatch(for item: WatchlistItem) -> PortfolioStock? {
        let key = normalizedTicker(item.ticker)
        return (usPortfolio.stocks + krPortfolio.stocks).first { normalizedTicker($0.ticker) == key }
    }

    private func smallCapMatch(for item: WatchlistItem) -> SmallCapStock? {
        let key = normalizedTicker(item.ticker)
        return (smallCap.usStocks + smallCap.krStocks).first { normalizedTicker($0.ticker) == key }
    }

    private func earningsMatch(for item: WatchlistItem) -> EarningsStock? {
        let key = normalizedTicker(item.ticker)
        return (pulse.usEarnings + pulse.krEarnings).first { normalizedTicker($0.ticker) == key }
    }

    private func earningsCalendarMatch(for item: WatchlistItem) -> EarningsCalendarItem? {
        let key = normalizedTicker(item.ticker)
        return pulse.earningsCalendar
            .filter { ($0.daysUntil ?? 0) >= 0 }
            .sorted { $0.nextEarningsDate < $1.nextEarningsDate }
            .first { normalizedTicker($0.ticker) == key }
    }

    private func indicatorQuote(for item: WatchlistItem) -> MarketIndicatorQuote {
        let key = normalizedTicker(item.ticker)
        if let quote = marketIndicators.items.first(where: { normalizedTicker($0.symbol) == key }) {
            return quote
        }
        return MarketIndicatorQuote(
            symbol: item.ticker,
            label: item.name,
            category: "watch",
            region: item.market == "KR" ? "domestic" : "overseas",
            value: .nan,
            changeAbs: nil,
            changePct: nil,
            updatedAt: nil
        )
    }

    private func personalSignalMetrics(
        portfolio: PortfolioStock?,
        smallCap: SmallCapStock?,
        earnings: EarningsStock?,
        calendar: EarningsCalendarItem?
    ) -> [String] {
        var values: [String] = []
        if let expected = portfolio?.expectedReturn, expected.isFinite {
            values.append("기대 \(pct(expected))")
        } else if let score = portfolio?.totalScore, score.isFinite {
            values.append("점수 \(String(format: "%.0f", score))")
        }
        if let smallScore = smallCap?.totalScore, smallScore.isFinite {
            values.append("스몰캡 \(String(format: "%.0f", smallScore))")
        }
        if let signal = earnings?.signalStrength, signal.isFinite {
            values.append("Signal \(String(format: "%.2f", signal))")
        }
        if let days = calendar?.daysUntil {
            values.append(homeEarningsCalendarDayText(days))
        }
        return Array(values.prefix(3))
    }

    private func firstMacroValue(_ keys: [String]) -> String? {
        for key in keys {
            if let value = pulse.macro[key]?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
                return value
            }
        }
        return nil
    }
}
