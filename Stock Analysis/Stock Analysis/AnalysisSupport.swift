import Combine
import Foundation
import SwiftUI
import UserNotifications

struct InvestmentSignal: Identifiable, Hashable {
    let id = UUID()
    let title: String
    let detail: String
    let systemImage: String
    let color: Color
}

struct WatchNotificationEvent: Identifiable, Hashable {
    let id: String
    let title: String
    let body: String
    let daysUntil: Int?
}

struct WatchJudgmentTimelineEvent: Codable, Identifiable, Hashable {
    let id: String
    let title: String
    let body: String
    let recordedAt: Date
    let source: String
}

let watchJudgmentAlertOptions = ["실적 리스크", "가설 흔들림", "점수·과열 동시", "성향 관찰", "판단 업데이트"]

func watchAlertOptionMatches(_ selectedOptions: [String], option: String) -> Bool {
    guard !selectedOptions.isEmpty else { return true }
    let aliases = Set(watchAlertAliases[option] ?? [option])
    return selectedOptions.contains { selected in
        aliases.contains(selected) || selected == option
    }
}

func watchAlertDisplayLabel(_ option: String) -> String {
    for label in watchJudgmentAlertOptions {
        if watchAlertAliases[label, default: [label]].contains(option) {
            return label
        }
    }
    return option
}

private let watchAlertAliases: [String: [String]] = [
    "실적 리스크": ["실적 리스크", "실적 D-3", "실적 D-1"],
    "가설 흔들림": ["가설 흔들림", "투자 가설 흔들림", "우선순위 상승"],
    "점수·과열 동시": ["점수·과열 동시", "가격 급변", "우선순위 상승"],
    "성향 관찰": ["성향 관찰"],
    "판단 업데이트": ["판단 업데이트", "데이터 갱신", "우선순위 상승"]
]

@MainActor
final class NotificationStore: ObservableObject {
    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined
    @Published private(set) var lastScheduleSummary = "알림 대기"
    @Published private(set) var judgmentHistory: [WatchJudgmentTimelineEvent] = []
    @Published var isEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isEnabled, forKey: Self.enabledKey)
            if !isEnabled {
                cancelQubitNotifications()
            }
        }
    }

    private static let enabledKey = "quantbridge.notifications.enabled"
    private static let judgmentHistoryKey = "quantbridge.notifications.judgmentHistory"
    private let center = UNUserNotificationCenter.current()
    private let dailyIdentifier = "quantbridge.daily.briefing"
    private let watchPrefix = "quantbridge.watch."

    init() {
        self.isEnabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
        self.judgmentHistory = Self.loadJudgmentHistory()
    }

    var statusTitle: String {
        switch authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return isEnabled ? "알림 켜짐" : "알림 꺼짐"
        case .denied:
            return "알림 차단됨"
        case .notDetermined:
            return "알림 미설정"
        @unknown default:
            return "알림 상태 확인"
        }
    }

    var statusDetail: String {
        switch authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return isEnabled ? lastScheduleSummary : "관심종목 판단 업데이트와 매일 브리핑을 받을 수 있습니다."
        case .denied:
            return "iOS 설정에서 알림 권한을 허용해야 받을 수 있습니다."
        case .notDetermined:
            return "권한을 허용하면 투자 가설, 실적 리스크, 과열 신호를 판단 알림으로 알려드립니다."
        @unknown default:
            return "시스템 알림 설정을 다시 확인하세요."
        }
    }

    var canRequestAuthorization: Bool {
        authorizationStatus == .notDetermined || authorizationStatus == .authorized || authorizationStatus == .provisional || authorizationStatus == .ephemeral
    }

    func refreshAuthorizationStatus() async {
        let settings = await center.notificationSettings()
        authorizationStatus = settings.authorizationStatus
        if authorizationStatus == .denied {
            isEnabled = false
        }
    }

    func requestAuthorization() async {
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            isEnabled = granted
            await refreshAuthorizationStatus()
        } catch {
            isEnabled = false
            lastScheduleSummary = "알림 권한 요청 실패"
        }
    }

    func disable() {
        isEnabled = false
        lastScheduleSummary = "알림 꺼짐"
    }

    func sync(events: [WatchNotificationEvent], dailySummary: String) async {
        await refreshAuthorizationStatus()
        await cancelWatchNotifications()
        recordJudgmentHistory(events)
        guard isEnabled, authorizationStatus != .denied, authorizationStatus != .notDetermined else {
            lastScheduleSummary = "알림 대기"
            return
        }

        await scheduleDailyBriefing(summary: dailySummary)
        var scheduledCount = 0
        for event in events.prefix(8) {
            if await scheduleWatchEvent(event) {
                scheduledCount += 1
            }
        }
        lastScheduleSummary = scheduledCount > 0
            ? "판단 알림 \(scheduledCount)개와 매일 브리핑 예약"
            : "매일 브리핑 예약"
    }

    private func scheduleDailyBriefing(summary: String) async {
        center.removePendingNotificationRequests(withIdentifiers: [dailyIdentifier])

        let content = UNMutableNotificationContent()
        content.title = "큐빗 오늘의 브리핑"
        content.body = summary
        content.sound = .default

        var components = DateComponents()
        components.hour = 8
        components.minute = 30
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        let request = UNNotificationRequest(identifier: dailyIdentifier, content: content, trigger: trigger)
        try? await center.add(request)
    }

    private func scheduleWatchEvent(_ event: WatchNotificationEvent) async -> Bool {
        let content = UNMutableNotificationContent()
        content.title = event.title
        content.body = event.body
        content.sound = .default

        let trigger: UNNotificationTrigger
        if let daysUntil = event.daysUntil, daysUntil >= 0 {
            trigger = calendarTrigger(daysUntil: daysUntil)
        } else {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: 60 * 30, repeats: false)
        }

        let request = UNNotificationRequest(
            identifier: watchPrefix + event.id,
            content: content,
            trigger: trigger
        )
        do {
            try await center.add(request)
            return true
        } catch {
            return false
        }
    }

    private func calendarTrigger(daysUntil: Int) -> UNNotificationTrigger {
        let calendar = Calendar.current
        let now = Date()
        let dayOffset = max(daysUntil <= 1 ? 0 : daysUntil - 1, 0)
        let targetDay = calendar.date(byAdding: .day, value: dayOffset, to: now) ?? now
        var components = calendar.dateComponents([.year, .month, .day], from: targetDay)
        components.hour = 9
        components.minute = 0

        if let target = calendar.date(from: components), target.timeIntervalSince(now) > 60 * 10 {
            return UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        }
        return UNTimeIntervalNotificationTrigger(timeInterval: 60 * 15, repeats: false)
    }

    private func cancelQubitNotifications() {
        Task { await cancelWatchNotifications(includeDaily: true) }
    }

    private func cancelWatchNotifications(includeDaily: Bool = false) async {
        let pending = await center.pendingNotificationRequests()
        var identifiers = pending
            .map(\.identifier)
            .filter { $0.hasPrefix(watchPrefix) }
        if includeDaily {
            identifiers.append(dailyIdentifier)
        }
        if !identifiers.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: identifiers)
        }
    }

    private func recordJudgmentHistory(_ events: [WatchNotificationEvent]) {
        let timelineEvents = events
            .filter { !$0.title.isEmpty && !$0.body.isEmpty }
            .map {
                WatchJudgmentTimelineEvent(
                    id: $0.id,
                    title: $0.title,
                    body: $0.body,
                    recordedAt: Date(),
                    source: "판단 알림"
                )
            }
        guard !timelineEvents.isEmpty else { return }
        var next = judgmentHistory
        for event in timelineEvents {
            next.removeAll { $0.id == event.id }
            next.insert(event, at: 0)
        }
        judgmentHistory = Array(next.prefix(40))
        Self.saveJudgmentHistory(judgmentHistory)
    }

    private static func loadJudgmentHistory() -> [WatchJudgmentTimelineEvent] {
        guard let data = UserDefaults.standard.data(forKey: judgmentHistoryKey),
              let decoded = try? JSONDecoder().decode([WatchJudgmentTimelineEvent].self, from: data) else {
            return []
        }
        return decoded.sorted { $0.recordedAt > $1.recordedAt }
    }

    private static func saveJudgmentHistory(_ items: [WatchJudgmentTimelineEvent]) {
        guard let data = try? JSONEncoder().encode(Array(items.prefix(40))) else { return }
        UserDefaults.standard.set(data, forKey: judgmentHistoryKey)
    }
}

struct ExposureSlice: Identifiable {
    let id = UUID()
    let label: String
    let value: Double
    let color: Color
}

struct RebalanceAction: Identifiable {
    let id = UUID()
    let ticker: String
    let name: String
    let currentWeight: Double
    let targetWeight: Double

    var delta: Double {
        targetWeight - currentWeight
    }

    var action: String {
        if delta > 0.002 { return "매수" }
        if delta < -0.002 { return "축소" }
        return "유지"
    }

    var color: Color {
        if delta > 0.002 { return AppTheme.positive }
        if delta < -0.002 { return AppTheme.negative }
        return .secondary
    }
}

struct StockComparisonItem: Identifiable, Hashable, Codable {
    let id: String
    let ticker: String
    let name: String
    let market: String?
    let sector: String?
    let currency: String
    let source: String
    let score: Double?
    let expectedReturn: Double?
    let revenueGrowth: Double?
    let roic: Double?
    let grossMargin: Double?
    let marketCap: Double?
    let currentPrice: Double?
    let return1M: Double?
    let rankChange: Int?
    let weight: Double?
    let fcfMargin: Double?
    let volumeSurge: Double?
    let updatedAt: String?

    init(
        ticker: String,
        name: String,
        market: String?,
        sector: String?,
        currency: String,
        source: String,
        score: Double? = nil,
        expectedReturn: Double? = nil,
        revenueGrowth: Double? = nil,
        roic: Double? = nil,
        grossMargin: Double? = nil,
        marketCap: Double? = nil,
        currentPrice: Double? = nil,
        return1M: Double? = nil,
        rankChange: Int? = nil,
        weight: Double? = nil,
        fcfMargin: Double? = nil,
        volumeSurge: Double? = nil,
        updatedAt: String? = nil
    ) {
        let normalized = normalizedTicker(ticker)
        self.id = normalized
        self.ticker = normalized
        self.name = name
        self.market = market
        self.sector = sector
        self.currency = currency
        self.source = source
        self.score = score
        self.expectedReturn = expectedReturn
        self.revenueGrowth = revenueGrowth
        self.roic = roic
        self.grossMargin = grossMargin
        self.marketCap = marketCap
        self.currentPrice = currentPrice
        self.return1M = return1M
        self.rankChange = rankChange
        self.weight = weight
        self.fcfMargin = fcfMargin
        self.volumeSurge = volumeSurge
        self.updatedAt = updatedAt
    }

    init(portfolio stock: PortfolioStock, currency: String) {
        self.init(
            ticker: stock.ticker,
            name: stock.name,
            market: stock.market,
            sector: stock.sector,
            currency: currency,
            source: "Portfolio",
            score: stock.totalScore,
            expectedReturn: stock.expectedReturn,
            revenueGrowth: stock.revGrowth,
            roic: stock.roic,
            grossMargin: stock.grossMargin,
            marketCap: stock.marketCap,
            currentPrice: stock.currentPrice,
            return1M: stock.return1M,
            rankChange: stock.rankChange,
            weight: stock.weight,
            updatedAt: stock.lastUpdated
        )
    }

    init(smallCap stock: SmallCapStock) {
        self.init(
            ticker: stock.ticker,
            name: stock.name,
            market: stock.market,
            sector: "스몰캡",
            currency: marketCurrency(for: stock.ticker, market: stock.market),
            source: "스몰캡",
            score: stock.totalScore,
            revenueGrowth: stock.revGrowth,
            roic: stock.roic,
            grossMargin: stock.grossMargin,
            marketCap: stock.marketCap,
            currentPrice: stock.currentPrice,
            return1M: stock.return1M,
            rankChange: stock.rankChange,
            fcfMargin: stock.fcfMargin,
            volumeSurge: stock.volumeSurge,
            updatedAt: stock.lastUpdated
        )
    }

    init(watchlist item: WatchlistItem) {
        self.init(
            ticker: item.ticker,
            name: item.name,
            market: item.market,
            sector: item.primaryTag,
            currency: item.currency,
            source: "Watch"
        )
    }

    var headlineScoreText: String {
        guard let score, score.isFinite else { return "-" }
        return (source == "SmallCap" || source == "스몰캡") ? String(format: "%.0f점", score) : String(format: "%.3f", score)
    }
}

@MainActor
final class ComparisonStore: ObservableObject {
    @Published private(set) var items: [StockComparisonItem] = []
    @Published var isPresenting = false

    private let key = "quantbridge.comparison.items"
    private let maxCount = 4

    init() {
        load()
    }

    var canCompare: Bool {
        items.count >= 2
    }

    func contains(_ ticker: String) -> Bool {
        let key = normalizedTicker(ticker)
        return items.contains { $0.id == key || normalizedTicker($0.ticker) == key }
    }

    func add(_ item: StockComparisonItem) {
        items.removeAll { $0.id == item.id || normalizedTicker($0.ticker) == normalizedTicker(item.ticker) }
        items.insert(item, at: 0)
        if items.count > maxCount {
            items = Array(items.prefix(maxCount))
        }
        save()
    }

    func remove(_ item: StockComparisonItem) {
        items.removeAll { $0.id == item.id }
        save()
    }

    func replace(with newItems: [StockComparisonItem]) {
        var seen = Set<String>()
        items = newItems.compactMap { item in
            guard !seen.contains(item.id) else { return nil }
            seen.insert(item.id)
            return item
        }.prefix(maxCount).map { $0 }
        save()
    }

    func clear() {
        items = []
        isPresenting = false
        save()
    }

    func present() {
        guard !items.isEmpty else { return }
        isPresenting = true
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([StockComparisonItem].self, from: data) else {
            return
        }
        items = Array(decoded.prefix(maxCount))
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}

struct MovingAveragePoint: Identifiable {
    let id: String
    let date: Date
    let value: Double
}

struct TrendLinePoint: Identifiable {
    let id: String
    let date: Date
    let value: Double
}

struct BollingerPoint: Identifiable {
    let id: String
    let date: Date
    let upper: Double
    let middle: Double
    let lower: Double
}

struct RSIChartPoint: Identifiable {
    let id: String
    let date: Date
    let value: Double
}

struct MACDChartPoint: Identifiable {
    let id: String
    let date: Date
    let macd: Double
    let signal: Double
    let histogram: Double
}

struct RegressionChannelPoint: Identifiable {
    let id: String
    let date: Date
    let trend: Double
    let upper1: Double
    let lower1: Double
    let upper2: Double
    let lower2: Double
}

struct PriceLevel: Identifiable {
    let id = UUID()
    let price: Double
    let strength: Int
    let isResistance: Bool
}

struct WatchlistItem: Codable, Identifiable, Hashable {
    var id: String { ticker }
    let ticker: String
    let name: String
    let market: String
    let currency: String
    let note: String
    let addedAt: Date
    var tags: [String]
    var memo: String
    var alertOptions: [String]

    init(
        ticker: String,
        name: String,
        market: String,
        currency: String,
        note: String,
        addedAt: Date,
        tags: [String] = [],
        memo: String = "",
        alertOptions: [String] = []
    ) {
        self.ticker = ticker
        self.name = displayCompanyName(name, ticker: ticker)
        self.market = market
        self.currency = currency
        self.note = note
        self.addedAt = addedAt
        self.tags = tags
        self.memo = memo
        self.alertOptions = alertOptions
    }

    enum CodingKeys: String, CodingKey {
        case ticker
        case name
        case market
        case currency
        case note
        case addedAt
        case tags
        case memo
        case alertOptions
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ticker = try container.decode(String.self, forKey: .ticker)
        name = displayCompanyName(try container.decode(String.self, forKey: .name), ticker: ticker)
        market = try container.decode(String.self, forKey: .market)
        currency = try container.decode(String.self, forKey: .currency)
        note = try container.decode(String.self, forKey: .note)
        addedAt = try container.decode(Date.self, forKey: .addedAt)
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        memo = try container.decodeIfPresent(String.self, forKey: .memo) ?? ""
        alertOptions = try container.decodeIfPresent([String].self, forKey: .alertOptions) ?? []
    }
}

let watchlistMarketIndicatorNote = "지수"

private let watchlistMarketIndicatorSymbols: Set<String> = [
    "^IXIC", "NQ=F", "^GSPC", "ES=F", "RTY=F", "^DJI", "^SOX", "^VIX",
    "KRW=X", "DX-Y.NYB", "^KS11", "^KQ11",
    "^IRX", "^FVX", "^TNX", "^TYX", "IRR_GOVT03Y", "IRR_CORP03Y",
    "GC=F", "SI=F", "CL=F", "HG=F",
    "BTC-USD", "ETH-USD", "SOL-USD"
]

private let watchlistMarketIndicatorUnambiguousAliases: Set<String> = [
    "KOSPI", "KOSPI지수", "코스피",
    "KOSDAQ", "KOSDAQ지수", "코스닥",
    "NASDAQ", "나스닥", "VIX"
]

extension WatchlistItem {
    var isMarketIndicator: Bool {
        let normalized = normalizedTicker(ticker)
        return note == watchlistMarketIndicatorNote ||
            watchlistMarketIndicatorSymbols.contains(normalized) ||
            watchlistMarketIndicatorUnambiguousAliases.contains(normalized)
    }

    var primaryTag: String {
        tags.first ?? note
    }

    var investmentThesis: WatchInvestmentThesis {
        WatchInvestmentThesis(memo: memo)
    }

    func withMetadata(tags: [String], memo: String, alertOptions: [String]) -> WatchlistItem {
        WatchlistItem(
            ticker: ticker,
            name: name,
            market: market,
            currency: currency,
            note: note,
            addedAt: addedAt,
            tags: tags,
            memo: memo,
            alertOptions: alertOptions
        )
    }
}

struct WatchInvestmentThesis: Codable, Equatable {
    var reason: String
    var expectedChange: String
    var checkCondition: String
    var invalidationCondition: String
    var horizon: String
    var reviewStatus: String
    var reviewNote: String

    private static let memoPrefix = "qb_thesis_v1:"

    init(
        reason: String = "",
        expectedChange: String = "",
        checkCondition: String = "",
        invalidationCondition: String = "",
        horizon: String = "",
        reviewStatus: String = "",
        reviewNote: String = ""
    ) {
        self.reason = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        self.expectedChange = expectedChange.trimmingCharacters(in: .whitespacesAndNewlines)
        self.checkCondition = checkCondition.trimmingCharacters(in: .whitespacesAndNewlines)
        self.invalidationCondition = invalidationCondition.trimmingCharacters(in: .whitespacesAndNewlines)
        self.horizon = horizon.trimmingCharacters(in: .whitespacesAndNewlines)
        self.reviewStatus = reviewStatus.trimmingCharacters(in: .whitespacesAndNewlines)
        self.reviewNote = reviewNote.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    enum CodingKeys: String, CodingKey {
        case reason
        case expectedChange
        case checkCondition
        case invalidationCondition
        case horizon
        case reviewStatus
        case reviewNote
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            reason: try container.decodeIfPresent(String.self, forKey: .reason) ?? "",
            expectedChange: try container.decodeIfPresent(String.self, forKey: .expectedChange) ?? "",
            checkCondition: try container.decodeIfPresent(String.self, forKey: .checkCondition) ?? "",
            invalidationCondition: try container.decodeIfPresent(String.self, forKey: .invalidationCondition) ?? "",
            horizon: try container.decodeIfPresent(String.self, forKey: .horizon) ?? "",
            reviewStatus: try container.decodeIfPresent(String.self, forKey: .reviewStatus) ?? "",
            reviewNote: try container.decodeIfPresent(String.self, forKey: .reviewNote) ?? ""
        )
    }

    init(memo: String) {
        let clean = memo.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.hasPrefix(Self.memoPrefix) {
            let payload = String(clean.dropFirst(Self.memoPrefix.count))
            if let data = payload.data(using: .utf8),
               let decoded = try? JSONDecoder().decode(WatchInvestmentThesis.self, from: data) {
                self = decoded.normalized
                return
            }
        }
        self.init(reason: clean)
    }

    var normalized: WatchInvestmentThesis {
        WatchInvestmentThesis(
            reason: reason,
            expectedChange: expectedChange,
            checkCondition: checkCondition,
            invalidationCondition: invalidationCondition,
            horizon: horizon,
            reviewStatus: reviewStatus,
            reviewNote: reviewNote
        )
    }

    var isEmpty: Bool {
        [reason, expectedChange, checkCondition, invalidationCondition, horizon, reviewStatus, reviewNote].allSatisfy {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    var memoText: String {
        let clean = normalized
        guard !clean.isEmpty else { return "" }
        guard let data = try? JSONEncoder().encode(clean),
              let encoded = String(data: data, encoding: .utf8) else {
            return clean.reason
        }
        return Self.memoPrefix + encoded
    }

    var headline: String? {
        [reason, expectedChange, checkCondition]
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    var inlineSummary: String? {
        guard !isEmpty else { return nil }
        if !reason.isEmpty { return reason }
        if !expectedChange.isEmpty { return "기대: \(expectedChange)" }
        if !checkCondition.isEmpty { return "확인: \(checkCondition)" }
        if !invalidationCondition.isEmpty { return "틀린 조건: \(invalidationCondition)" }
        return horizon.isEmpty ? nil : "관찰 기간: \(horizon)"
    }

    var detailSummary: String {
        var parts: [String] = []
        if !reason.isEmpty { parts.append("이유: \(reason)") }
        if !expectedChange.isEmpty { parts.append("기대: \(expectedChange)") }
        if !checkCondition.isEmpty { parts.append("확인: \(checkCondition)") }
        if !invalidationCondition.isEmpty { parts.append("틀린 조건: \(invalidationCondition)") }
        if !horizon.isEmpty { parts.append("기간: \(horizon)") }
        if let reviewSummary { parts.append("복기: \(reviewSummary)") }
        return parts.prefix(3).joined(separator: " · ")
    }

    var quality: WatchThesisQuality {
        let fields = [
            ("관심 이유", reason),
            ("기대 변화", expectedChange),
            ("확인 조건", checkCondition),
            ("틀렸다고 볼 조건", invalidationCondition),
            ("관찰 기간", horizon)
        ]
        let completed = fields.filter { !$0.1.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let missing = fields.filter { $0.1.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.map { $0.0 }
        let percent = max(0, min(100, Int((Double(completed.count) / Double(fields.count)) * 100)))
        return WatchThesisQuality(
            percent: percent,
            label: {
                if percent >= 100 { return "복기 가능" }
                if percent >= 80 { return "거의 완성" }
                if percent >= 40 { return "가설 작성 중" }
                if percent > 0 { return "이유만 있음" }
                return "가설 없음"
            }(),
            missingFields: missing,
            reviewTiming: {
                switch horizon {
                case "1개월":
                    return "30일 안에 유지/수정/종료를 선택하세요."
                case "3개월":
                    return "분기 실적이나 가격 변화 후 복기하세요."
                case "6개월":
                    return "반기 동안 확인 조건이 맞는지 추적하세요."
                case "1년+":
                    return "긴 흐름은 분기마다 중간 점검하세요."
                default:
                    return "관찰 기간을 정하면 다음 복기 타이밍이 선명해집니다."
                }
            }()
        )
    }

    var reviewPrompt: String {
        switch reviewStatus {
        case "유지":
            return "기존 가설을 유지하되 확인 조건이 실제로 맞는지 계속 보세요."
        case "수정":
            return "틀린 부분을 반영해 기대 변화나 확인 조건을 다시 적으세요."
        case "종료":
            return "무효 조건이 확인됐거나 우선순위가 낮아졌다면 관심을 정리하세요."
        default:
            return "다음 확인 때 유지, 수정, 종료 중 하나를 선택하세요."
        }
    }

    var reviewSummary: String? {
        if !reviewStatus.isEmpty, !reviewNote.isEmpty {
            return "\(reviewStatus) · \(reviewNote)"
        }
        if !reviewStatus.isEmpty {
            return "\(reviewStatus) · \(reviewPrompt)"
        }
        if !reviewNote.isEmpty {
            return reviewNote
        }
        return nil
    }
}

struct WatchThesisQuality: Equatable {
    let percent: Int
    let label: String
    let missingFields: [String]
    let reviewTiming: String
}

struct InvestmentProfile: Codable, Equatable {
    var experience: String
    var horizon: String
    var riskTolerance: String
    var style: String
    var avoidances: [String]

    static let storageKey = "investmentProfile.v1"
    static let empty = InvestmentProfile()

    init(
        experience: String = "",
        horizon: String = "",
        riskTolerance: String = "",
        style: String = "",
        avoidances: [String] = []
    ) {
        self.experience = experience.trimmingCharacters(in: .whitespacesAndNewlines)
        self.horizon = horizon.trimmingCharacters(in: .whitespacesAndNewlines)
        self.riskTolerance = riskTolerance.trimmingCharacters(in: .whitespacesAndNewlines)
        self.style = style.trimmingCharacters(in: .whitespacesAndNewlines)
        var seenAvoidances = Set<String>()
        self.avoidances = avoidances
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .filter { seenAvoidances.insert($0).inserted }
    }

    static func load() -> InvestmentProfile {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode(InvestmentProfile.self, from: data) else {
            return .empty
        }
        return decoded.normalized
    }

    func save() {
        let clean = normalized
        guard let data = try? JSONEncoder().encode(clean) else { return }
        UserDefaults.standard.set(data, forKey: Self.storageKey)
    }

    var normalized: InvestmentProfile {
        InvestmentProfile(
            experience: experience,
            horizon: horizon,
            riskTolerance: riskTolerance,
            style: style,
            avoidances: avoidances
        )
    }

    var isConfigured: Bool {
        !experience.isEmpty || !horizon.isEmpty || !riskTolerance.isEmpty || !style.isEmpty || !avoidances.isEmpty
    }

    var headline: String {
        guard isConfigured else { return "아직 미설정" }
        let primary = [riskTolerance, horizon, style]
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? "맞춤 기준"
        return "\(primary) 중심"
    }

    var summary: String {
        let parts = [experience, horizon, riskTolerance, style]
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        if parts.isEmpty {
            return "투자 성향을 저장하면 후보를 내 기준으로 점검할 수 있습니다."
        }
        return parts.prefix(3).joined(separator: " · ")
    }

    var guardrailSummary: String {
        guard !avoidances.isEmpty else { return "피하고 싶은 신호 없음" }
        return avoidances.prefix(3).joined(separator: " · ")
    }

    var completionPercent: Int {
        let fields = [
            experience,
            horizon,
            riskTolerance,
            style,
            avoidances.joined(separator: ",")
        ]
        let completed = fields.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.count
        return max(0, min(100, Int((Double(completed) / Double(fields.count)) * 100)))
    }

    var operatingStatement: String {
        guard isConfigured else { return "나는 먼저 기준을 세운 뒤 후보를 확인한다." }
        let styleText = style.isEmpty ? "내 기준에 맞는" : style
        let riskText = riskTolerance.isEmpty ? "무리하지 않는" : riskTolerance
        let horizonText = horizon.isEmpty ? "정한 기간" : horizon
        return "나는 \(styleText) 후보를 \(horizonText) 동안 보고, \(riskText) 범위에서 확인 조건이 맞을 때만 판단한다."
    }

    var nextReviewText: String {
        switch horizon {
        case "1개월":
            return "월 1회 기준 재점검"
        case "3개월":
            return "분기 단위 기준 재점검"
        case "6개월":
            return "반기 단위 기준 재점검"
        case "1년+":
            return "연 1회 기준 재점검"
        default:
            return "30일 뒤 기준 재점검"
        }
    }
}

struct PersonalizedStockInterpretation {
    let label: String
    let headline: String
    let detail: String
    let action: String
    let reasons: [String]
    let color: Color

    var decisionLine: String {
        "\(headline). \(action)"
    }
}

func personalizedStockInterpretation(
    profile: InvestmentProfile,
    name: String,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal]
) -> PersonalizedStockInterpretation {
    let joinedText = ([name] + metrics.map { "\($0.label) \($0.value)" } + signals.map { "\($0.title) \($0.detail)" })
        .joined(separator: " ")
        .lowercased()
    let scoreValue = metrics.first { metric in
        metric.label.localizedCaseInsensitiveContains("점수") || metric.label.localizedCaseInsensitiveContains("score")
    }.flatMap { personalizedNumericToken($0.value) }
    return personalizedStockInterpretation(
        profile: profile,
        name: name,
        highScore: personalizedIsHighScore(scoreValue),
        growthGood: (info?.revenueGrowth ?? 0) >= 0.12 || joinedText.contains("성장") || joinedText.contains("growth"),
        valuationBurden: (info?.peRatio ?? 0) >= 35 || (info?.forwardPe ?? 0) >= 35 || joinedText.contains("밸류에이션 부담"),
        highVolatility: (info?.beta ?? 0) >= 1.15 || joinedText.contains("변동성") || joinedText.contains("급등") || joinedText.contains("과열") || personalizedIsPriceNearHigh(info),
        recentSurge: personalizedPercentMagnitude(info?.dailyChangePct) >= 5 || joinedText.contains("급등") || joinedText.contains("과열"),
        complexityHigh: (info?.peRatio ?? 0) >= 45 || (info?.debtToEquity ?? 0) >= 150 || personalizedWarningSignalCount(signals) >= 2,
        fallbackReason: signals.first?.title ?? metrics.first?.label
    )
}

func personalizedStockInterpretation(profile: InvestmentProfile, stock: PortfolioStock) -> PersonalizedStockInterpretation {
    personalizedStockInterpretation(
        profile: profile,
        name: stock.name,
        highScore: personalizedIsHighScore(stock.totalScore),
        growthGood: personalizedPercentMagnitude(stock.revGrowth) >= 12,
        valuationBurden: (stock.expectedReturn ?? 0) < 0,
        highVolatility: personalizedPercentMagnitude(stock.return1M) >= 10,
        recentSurge: personalizedPercentMagnitude(stock.return1M) >= 12 || (stock.rankChange ?? 0) > 0,
        complexityHigh: stock.marketCap == nil || (stock.expectedReturn ?? 0) < 0,
        fallbackReason: stock.sector
    )
}

private func personalizedStockInterpretation(
    profile: InvestmentProfile,
    name: String,
    highScore: Bool,
    growthGood: Bool,
    valuationBurden: Bool,
    highVolatility: Bool,
    recentSurge: Bool,
    complexityHigh: Bool,
    fallbackReason: String?
) -> PersonalizedStockInterpretation {
    if !profile.isConfigured {
        return PersonalizedStockInterpretation(
            label: "기준 설정 필요",
            headline: "아직은 기본 점수 기준",
            detail: "투자 성향을 저장하면 \(name)을 내 기준으로 다시 해석합니다.",
            action: "먼저 투자 성향 진단을 저장해두세요.",
            reasons: [fallbackReason].compactMap { $0 },
            color: AppTheme.secondaryText
        )
    }

    let stable = profile.riskTolerance.contains("보수") || profile.riskTolerance.contains("안정") || profile.riskTolerance.contains("낮")
    let growth = profile.style.contains("성장")
    let value = profile.style.contains("가치")
    let momentum = profile.style.contains("모멘텀") || profile.horizon.contains("1개월")
    let novice = profile.experience.contains("초보") || profile.experience.contains("입문") || profile.experience.contains("처음")
    let reasons = personalizedReasons(
        highScore: highScore,
        growthGood: growthGood,
        valuationBurden: valuationBurden,
        highVolatility: highVolatility,
        recentSurge: recentSurge,
        complexityHigh: complexityHigh,
        fallbackReason: fallbackReason
    )

    if momentum && recentSurge {
        return PersonalizedStockInterpretation(
            label: "\(profile.headline) 기준",
            headline: "추격 매수 주의",
            detail: "최근 급등 신호가 강해 지금은 진입보다 조건 확인이 먼저입니다.",
            action: "가격 조정이나 거래량 진정 후 다시 보세요.",
            reasons: reasons,
            color: AppTheme.warning
        )
    }
    if novice && complexityHigh {
        return PersonalizedStockInterpretation(
            label: "\(profile.headline) 기준",
            headline: "이해 난이도 높음",
            detail: "점수보다 사업 구조와 리스크를 먼저 이해해야 하는 후보입니다.",
            action: "더 단순한 비교 후보를 함께 열어보고 결정하세요.",
            reasons: reasons,
            color: AppTheme.warning
        )
    }
    if stable && highVolatility {
        return PersonalizedStockInterpretation(
            label: "\(profile.headline) 기준",
            headline: "좋지만 비중 제한",
            detail: "점수는 괜찮아도 변동성이 커서 안정형에게는 부담이 될 수 있습니다.",
            action: "관심 등록 후 작은 비중 또는 재검토 조건으로 관리하세요.",
            reasons: reasons,
            color: AppTheme.warning
        )
    }
    if growth && valuationBurden {
        return PersonalizedStockInterpretation(
            label: "\(profile.headline) 기준",
            headline: "성장성은 확인, 가격 부담",
            detail: "실적 모멘텀은 좋지만 밸류에이션 부담을 같이 봐야 합니다.",
            action: "성장률이 유지되는지와 가격 조정 여부를 같이 확인하세요.",
            reasons: reasons,
            color: AppTheme.accent
        )
    }
    if value && !valuationBurden && highScore {
        return PersonalizedStockInterpretation(
            label: "\(profile.headline) 기준",
            headline: "가치 기준 관심 후보",
            detail: "점수와 가격 부담이 크게 충돌하지 않아 비교 후보로 둘 만합니다.",
            action: "동종 업계 2~3개와 밸류에이션을 비교하세요.",
            reasons: reasons,
            color: AppTheme.quality
        )
    }
    if highScore {
        return PersonalizedStockInterpretation(
            label: "\(profile.headline) 기준",
            headline: "내 기준 관심 후보",
            detail: "좋은 종목인지보다 내 기준에 맞는지 확인할 근거가 있습니다.",
            action: "주의 신호와 다시 볼 조건을 투자 결정서에 남기세요.",
            reasons: reasons,
            color: AppTheme.accent
        )
    }
    return PersonalizedStockInterpretation(
        label: "\(profile.headline) 기준",
        headline: "관찰 우선",
        detail: "현재는 강한 확신보다 비교와 조건 확인이 더 어울립니다.",
        action: "관심 등록 후 다음 실적이나 가격 조건에서 다시 판단하세요.",
        reasons: reasons,
        color: AppTheme.secondaryText
    )
}

private func personalizedReasons(
    highScore: Bool,
    growthGood: Bool,
    valuationBurden: Bool,
    highVolatility: Bool,
    recentSurge: Bool,
    complexityHigh: Bool,
    fallbackReason: String?
) -> [String] {
    var values: [String] = []
    if highScore { values.append("점수 우수") }
    if growthGood { values.append("성장 근거") }
    if valuationBurden { values.append("가격 부담") }
    if highVolatility { values.append("변동성") }
    if recentSurge { values.append("최근 급등") }
    if complexityHigh { values.append("해석 난이도") }
    if values.isEmpty, let fallbackReason { values.append(fallbackReason) }
    return Array(values.prefix(3))
}

private func personalizedWarningSignalCount(_ signals: [InvestmentSignal]) -> Int {
    signals.filter { signal in
        let text = "\(signal.title) \(signal.detail)"
        return text.contains("주의") || text.contains("부담") || text.contains("위험") || text.localizedCaseInsensitiveContains("risk")
    }.count
}

private func personalizedNumericToken(_ value: String) -> Double? {
    let pattern = #"[-+]?\d+(?:\.\d+)?"#
    guard let range = value.range(of: pattern, options: .regularExpression) else { return nil }
    return Double(String(value[range]))
}

private func personalizedIsHighScore(_ value: Double?) -> Bool {
    guard let value, value.isFinite else { return false }
    return abs(value) <= 1 ? value >= 0.70 : value >= 70
}

private func personalizedPercentMagnitude(_ value: Double?) -> Double {
    guard let value, value.isFinite else { return 0 }
    let magnitude = abs(value)
    return magnitude <= 1 ? magnitude * 100 : magnitude
}

private func personalizedIsPriceNearHigh(_ info: StockInfo?) -> Bool {
    guard let current = info?.currentPrice,
          let low = info?.week52Low,
          let high = info?.week52High,
          current.isFinite,
          low.isFinite,
          high.isFinite,
          high > low else {
        return false
    }
    return abs((high - current) / (high - low)) < 0.15
}

struct CoverageCurationReason: Identifiable {
    var id: String { title }
    let title: String
    let detail: String
    let status: String
    let color: Color
}

struct CoverageCurationInsight {
    let headline: String
    let summary: String
    let reasons: [CoverageCurationReason]

    var inlineLine: String {
        let values = reasons.prefix(2).map(\.title).joined(separator: " · ")
        return values.isEmpty ? "선별 이유 확인 중" : "선별 이유 · \(values)"
    }
}

func coverageCurationInsight(
    name: String,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal]
) -> CoverageCurationInsight {
    let hasQuality = info != nil ||
        metrics.contains { $0.value.hasCoverageValue } ||
        !signals.isEmpty
    let hasTracking = info?.currentPrice != nil ||
        metrics.contains { $0.label.coverageMatches("현재가", "수익", "가격", "업데이트") && $0.value.hasCoverageValue }
    let hasFinancials = [
        info?.totalRevenue,
        info?.revenueGrowth,
        info?.grossMargin,
        info?.operatingMargin,
        info?.peRatio,
        info?.forwardPe
    ].contains { $0 != nil } ||
        metrics.contains { $0.label.coverageMatches("매출", "ROIC", "마진", "PER", "점수", "성장") && $0.value.hasCoverageValue }
    let hasEvents = signals.contains { signal in
        signal.title.coverageMatches("실적", "이벤트", "가설", "과열", "점수") ||
            signal.detail.coverageMatches("실적", "이벤트", "가설", "과열", "점수")
    }
    let understandable = info?.description?.hasCoverageValue == true ||
        info?.sector?.hasCoverageValue == true ||
        info?.industry?.hasCoverageValue == true ||
        metrics.contains { $0.label.coverageMatches("섹터", "시장", "산업") && $0.value.hasCoverageValue }

    return CoverageCurationInsight(
        headline: "분석 가능한 기업만 깊게 봅니다",
        summary: "모든 종목을 얕게 보여주지 않습니다. 이 기업은 데이터 품질과 추적 가능성을 통과한 커버리지 후보입니다.",
        reasons: [
            CoverageCurationReason(
                title: "데이터 품질 충분",
                detail: hasQuality ? "점수, 가격, 상세 데이터 중 판단에 쓸 근거가 확보되어 있습니다." : "상세 데이터가 도착하는 즉시 품질 기준을 다시 확인합니다.",
                status: hasQuality ? "확인" : "확인 중",
                color: hasQuality ? .green : AppTheme.secondaryText
            ),
            CoverageCurationReason(
                title: "재무/가격/이벤트 추적 가능",
                detail: coverageTrackingDetail(hasFinancials: hasFinancials, hasTracking: hasTracking, hasEvents: hasEvents),
                status: hasFinancials || hasTracking || hasEvents ? "추적 가능" : "확인 중",
                color: hasFinancials && hasTracking ? .green : AppTheme.accent
            ),
            CoverageCurationReason(
                title: "개인투자자가 이해 가능한 사업",
                detail: understandable ? "섹터, 산업, 사업 설명을 기준으로 비교 가능한 후보로 남겼습니다." : "사업 설명이 부족하면 우선순위를 낮추고 추가 확인 대상으로 둡니다.",
                status: understandable ? "해석 가능" : "보강 필요",
                color: understandable ? .green : AppTheme.warning
            ),
            CoverageCurationReason(
                title: "과도한 테마성 제외",
                detail: "단순 테마 노출보다 데이터로 다시 확인할 수 있는 후보를 우선합니다.",
                status: "큐레이션 기준",
                color: AppTheme.accent
            )
        ]
    )
}

func coverageCurationInsight(stock: PortfolioStock) -> CoverageCurationInsight {
    let hasScore = stock.totalScore != nil
    let hasFinancials = [stock.roic, stock.revGrowth, stock.grossMargin, stock.expectedReturn].contains { $0 != nil }
    let hasTracking = stock.currentPrice != nil || stock.return1M != nil || stock.lastUpdated?.hasCoverageValue == true
    return coverageCurationInsight(
        name: stock.name,
        hasQuality: hasScore || hasFinancials,
        hasFinancials: hasFinancials,
        hasTracking: hasTracking,
        understandable: stock.sector?.hasCoverageValue == true || stock.market?.hasCoverageValue == true
    )
}

func coverageCurationInsight(stock: SmallCapStock) -> CoverageCurationInsight {
    let hasScore = stock.totalScore != nil
    let hasFinancials = [stock.roic, stock.revGrowth, stock.revAccel, stock.grossMargin, stock.fcfMargin].contains { $0 != nil }
    let hasTracking = stock.currentPrice != nil || stock.return1M != nil || stock.volumeSurge != nil || stock.lastUpdated?.hasCoverageValue == true
    return coverageCurationInsight(
        name: stock.name,
        hasQuality: hasScore || hasFinancials,
        hasFinancials: hasFinancials,
        hasTracking: hasTracking,
        understandable: stock.market?.hasCoverageValue == true
    )
}

private func coverageCurationInsight(
    name: String,
    hasQuality: Bool,
    hasFinancials: Bool,
    hasTracking: Bool,
    understandable: Bool
) -> CoverageCurationInsight {
    CoverageCurationInsight(
        headline: "분석 가능한 기업만 깊게 봅니다",
        summary: "모든 종목을 얕게 보여주지 않습니다. 이 후보는 큐빗이 판단 가능한 범위 안에서 선별한 기업입니다.",
        reasons: [
            CoverageCurationReason(
                title: "데이터 품질 충분",
                detail: hasQuality ? "스코어와 핵심 지표가 있어 랭킹보다 깊은 해석이 가능합니다." : "지표가 부족하면 상세 판단보다 관찰 단계로 둡니다.",
                status: hasQuality ? "확인" : "관찰",
                color: hasQuality ? .green : AppTheme.secondaryText
            ),
            CoverageCurationReason(
                title: "재무/가격/이벤트 추적 가능",
                detail: coverageTrackingDetail(hasFinancials: hasFinancials, hasTracking: hasTracking, hasEvents: false),
                status: hasFinancials || hasTracking ? "추적 가능" : "보강 필요",
                color: hasFinancials && hasTracking ? .green : AppTheme.accent
            ),
            CoverageCurationReason(
                title: "개인투자자가 이해 가능한 사업",
                detail: understandable ? "시장과 업종 기준으로 비교 가능한 후보입니다." : "사업 해석 근거가 부족하면 깊은 분석 대상으로 두지 않습니다.",
                status: understandable ? "해석 가능" : "확인 필요",
                color: understandable ? .green : AppTheme.warning
            ),
            CoverageCurationReason(
                title: "과도한 테마성 제외",
                detail: "테마만으로 오른 종목보다 데이터로 검증 가능한 후보를 우선합니다.",
                status: "큐레이션 기준",
                color: AppTheme.accent
            )
        ]
    )
}

private func coverageTrackingDetail(hasFinancials: Bool, hasTracking: Bool, hasEvents: Bool) -> String {
    switch (hasFinancials, hasTracking, hasEvents) {
    case (true, true, _):
        return "재무 지표와 가격 흐름을 함께 추적할 수 있어 판단 업데이트가 가능합니다."
    case (true, false, true):
        return "재무 지표와 이벤트 신호가 있어 다음 판단 조건을 세울 수 있습니다."
    case (false, true, true):
        return "가격 흐름과 이벤트 신호를 기준으로 관찰 상태를 업데이트합니다."
    case (true, false, false):
        return "재무 지표가 있어 비교 기준을 세울 수 있습니다."
    case (false, true, false):
        return "가격과 업데이트 흐름을 추적할 수 있습니다."
    default:
        return "추적 가능한 핵심 지표가 충분한지 확인 중입니다."
    }
}

private extension String {
    var hasCoverageValue: Bool {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return !clean.isEmpty && clean != "-" && clean != "N/A"
    }

    func coverageMatches(_ tokens: String...) -> Bool {
        tokens.contains { localizedCaseInsensitiveContains($0) }
    }
}

struct InvestmentDecisionRecord: Codable, Identifiable, Equatable {
    var id: String { normalizedTicker(ticker) }
    var ticker: String
    var name: String
    var market: String
    var currency: String
    var reasons: [String]
    var counterEvidence: [String]
    var fitLabel: String
    var condition: String
    var status: String
    var reviewTrigger: String
    var note: String
    var createdAt: Date
    var updatedAt: Date

    init(
        ticker: String,
        name: String,
        market: String,
        currency: String,
        reasons: [String] = [],
        counterEvidence: [String] = [],
        fitLabel: String = "",
        condition: String = "",
        status: String = "추가 확인 필요",
        reviewTrigger: String = "",
        note: String = "",
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.ticker = normalizedTicker(ticker)
        self.name = displayCompanyName(name.isEmpty ? ticker : name, ticker: ticker)
        self.market = market.trimmingCharacters(in: .whitespacesAndNewlines)
        self.currency = currency.trimmingCharacters(in: .whitespacesAndNewlines)
        self.reasons = Self.clean(reasons)
        self.counterEvidence = Self.clean(counterEvidence)
        self.fitLabel = fitLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        self.condition = condition.trimmingCharacters(in: .whitespacesAndNewlines)
        self.status = status.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "추가 확인 필요" : status.trimmingCharacters(in: .whitespacesAndNewlines)
        self.reviewTrigger = reviewTrigger.trimmingCharacters(in: .whitespacesAndNewlines)
        self.note = note.trimmingCharacters(in: .whitespacesAndNewlines)
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var qualityPercent: Int {
        let completed = [
            !reasons.isEmpty,
            !counterEvidence.isEmpty,
            !condition.isEmpty,
            !status.isEmpty,
            !reviewTrigger.isEmpty
        ].filter { $0 }.count
        return max(0, min(100, Int((Double(completed) / 5.0) * 100.0)))
    }

    var qualityLabel: String {
        switch qualityPercent {
        case 100...:
            return "결정서 완성"
        case 80...:
            return "검토 가능"
        case 40...:
            return "작성 중"
        default:
            return "초안"
        }
    }

    var headline: String {
        status.isEmpty ? "추가 확인 필요" : status
    }

    var inlineSummary: String {
        "\(headline) · \(qualityPercent)%"
    }

    var summary: String {
        var parts: [String] = []
        if !reasons.isEmpty {
            parts.append("이유 \(reasons.prefix(2).joined(separator: " · "))")
        }
        if !counterEvidence.isEmpty {
            parts.append("반대 \(counterEvidence.prefix(2).joined(separator: " · "))")
        }
        if !condition.isEmpty {
            parts.append("조건 \(condition)")
        }
        if !reviewTrigger.isEmpty {
            parts.append("재검토 \(reviewTrigger)")
        }
        return parts.prefix(3).joined(separator: " · ").isEmpty ? "투자 이유와 주의 신호를 먼저 정리하세요." : parts.prefix(3).joined(separator: " · ")
    }

    private static func clean(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .filter { seen.insert($0).inserted }
    }
}

@MainActor
final class InvestmentDecisionStore: ObservableObject {
    @Published private(set) var records: [String: InvestmentDecisionRecord] = [:]

    private let key = "investmentDecisionRecords.v1"

    init() {
        load()
    }

    func record(for ticker: String) -> InvestmentDecisionRecord? {
        records[normalizedTicker(ticker)]
    }

    func save(_ record: InvestmentDecisionRecord) {
        var next = record
        let key = normalizedTicker(record.ticker)
        next.ticker = key
        next.createdAt = records[key]?.createdAt ?? record.createdAt
        next.updatedAt = Date()
        records[key] = next
        persist()
    }

    func delete(_ ticker: String) {
        records.removeValue(forKey: normalizedTicker(ticker))
        persist()
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([InvestmentDecisionRecord].self, from: data) else {
            records = [:]
            return
        }
        records = Dictionary(uniqueKeysWithValues: decoded.map { (normalizedTicker($0.ticker), $0) })
    }

    private func persist() {
        let values = records.values.sorted { $0.updatedAt > $1.updatedAt }
        guard let data = try? JSONEncoder().encode(values) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}

func canonicalMarketIndicatorSymbol(_ value: String) -> String {
    let normalized = normalizedTicker(value)
    let compact = normalized
        .replacingOccurrences(of: " ", with: "")
        .replacingOccurrences(of: "_", with: "")
        .replacingOccurrences(of: "-", with: "")
        .replacingOccurrences(of: ".", with: "")
    switch compact {
    case "^KS11", "KOSPI", "KOSPI지수", "코스피":
        return "^KS11"
    case "^KQ11", "KOSDAQ", "KOSDAQ지수", "코스닥":
        return "^KQ11"
    case "^IXIC", "IXIC", "NASDAQ", "나스닥":
        return "^IXIC"
    case "^GSPC", "GSPC", "SP500", "S&P500", "SNP500", "에스앤피500":
        return "^GSPC"
    case "^DJI", "DJI", "DOW", "DOWJONES", "다우", "다우존스":
        return "^DJI"
    case "^SOX", "SOX", "필라델피아반도체":
        return "^SOX"
    case "^VIX", "VIX":
        return "^VIX"
    case "DXY", "DXYNYB", "DXYNY", "DOLLARINDEX", "달러인덱스":
        return "DX-Y.NYB"
    case "USDKRW", "KRWX", "원달러", "달러원":
        return "KRW=X"
    default:
        return normalized
    }
}

func canonicalMarketIndicatorSymbol(for item: WatchlistItem) -> String {
    let tickerCanonical = canonicalMarketIndicatorSymbol(item.ticker)
    if tickerCanonical != normalizedTicker(item.ticker) { return tickerCanonical }
    let nameCanonical = canonicalMarketIndicatorSymbol(item.name)
    return nameCanonical != normalizedTicker(item.name) ? nameCanonical : tickerCanonical
}

enum WatchlistSyncStatus: Equatable {
    case idle
    case syncing(Int)
    case synced(Int)
    case failed(String)

    var message: String? {
        switch self {
        case .idle:
            return nil
        case .syncing(let count):
            return "\(count)개 동기화 중"
        case .synced(let count):
            return "\(count)개 동기화 완료"
        case .failed(let message):
            return message
        }
    }

    var isSyncing: Bool {
        if case .syncing = self { return true }
        return false
    }

    var isSynced: Bool {
        if case .synced = self { return true }
        return false
    }

    var hasIssue: Bool {
        if case .failed = self { return true }
        return false
    }
}

private struct PendingWatchlistOperation: Codable, Hashable {
    enum Kind: String, Codable {
        case save
        case delete
    }

    let kind: Kind
    let item: WatchlistItem?
    let ticker: String
}

@MainActor
final class WatchlistStore: ObservableObject {
    @Published private(set) var items: [WatchlistItem] = []
    @Published private(set) var syncStatus: WatchlistSyncStatus = .idle

    private let key = "stock_analysis_watchlist"
    private let pendingKey = "stock_analysis_watchlist_pending"
    private var authToken: String?
    private var pendingOperations: [PendingWatchlistOperation] = []
    private var isSyncing = false

    var pendingOperationCount: Int {
        pendingOperations.count
    }

    private static let serverDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter
    }()

    init() {
        load()
        loadPendingOperations()
    }

    func contains(_ ticker: String) -> Bool {
        let normalized = normalizedTicker(ticker)
        return items.contains { normalizedTicker($0.ticker) == normalized }
    }

    func toggle(_ item: WatchlistItem) {
        let normalizedItem = normalized(item)
        if contains(normalizedItem.ticker) {
            remove(normalizedItem.ticker)
        } else {
            items.removeAll { normalizedTicker($0.ticker) == normalizedItem.ticker }
            items.insert(normalizedItem, at: 0)
            save()
            if let authToken {
                enqueue(.init(kind: .save, item: normalizedItem, ticker: normalizedItem.ticker))
                Task { await syncPendingOperations(token: authToken) }
            }
        }
    }

    func remove(_ ticker: String) {
        let normalized = normalizedTicker(ticker)
        items.removeAll { normalizedTicker($0.ticker) == normalized }
        save()
        if let authToken {
            enqueue(.init(kind: .delete, item: nil, ticker: normalized))
            Task { await syncPendingOperations(token: authToken) }
        }
    }

    func item(for ticker: String) -> WatchlistItem? {
        let normalized = normalizedTicker(ticker)
        return items.first { normalizedTicker($0.ticker) == normalized }
    }

    func updateMetadata(ticker: String, tags: [String], memo: String, alertOptions: [String]) {
        let normalized = normalizedTicker(ticker)
        guard let index = items.firstIndex(where: { normalizedTicker($0.ticker) == normalized }) else { return }
        let updated = items[index].withMetadata(
            tags: tags,
            memo: memo.trimmingCharacters(in: .whitespacesAndNewlines),
            alertOptions: alertOptions
        )
        items[index] = updated
        save()
        if let authToken {
            enqueue(.init(kind: .save, item: updated, ticker: updated.ticker))
            Task { await syncPendingOperations(token: authToken) }
        }
    }

    func connect(token: String?) async {
        authToken = token
        guard let token else { return }

        syncStatus = .syncing(items.count + pendingOperations.count)
        await syncPendingOperations(token: token)
        let localItems = items
        for item in localItems {
            if !(await saveRemote(item, token: token)) {
                enqueue(.init(kind: .save, item: item, ticker: item.ticker))
            }
        }
        await loadRemote(token: token)
        await syncPendingOperations(token: token)
        if pendingOperations.isEmpty, !syncStatus.hasIssue {
            syncStatus = .synced(items.count)
        }
    }

    func disconnect(clearLocal: Bool = false) {
        authToken = nil
        syncStatus = .idle
        guard clearLocal else { return }
        items = []
        pendingOperations = []
        save()
        savePendingOperations()
    }

    func retrySync() {
        guard let authToken else { return }
        Task { await syncPendingOperations(token: authToken) }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([WatchlistItem].self, from: data) else {
            items = []
            return
        }
        items = decoded
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private func loadPendingOperations() {
        guard let data = UserDefaults.standard.data(forKey: pendingKey),
              let decoded = try? JSONDecoder().decode([PendingWatchlistOperation].self, from: data) else {
            pendingOperations = []
            return
        }
        pendingOperations = decoded
        if !decoded.isEmpty {
            syncStatus = .failed("\(decoded.count)개 동기화 대기")
        }
    }

    private func savePendingOperations() {
        guard let data = try? JSONEncoder().encode(pendingOperations) else { return }
        UserDefaults.standard.set(data, forKey: pendingKey)
    }

    private func loadRemote(token: String) async {
        do {
            let response: WatchlistResponse = try await APIClient.shared.authenticatedFetch(
                ["me", "watchlist"],
                token: token
            )
            let remoteItems = response.items.map { item in
                WatchlistItem(
                    ticker: normalizedTicker(item.ticker),
                    name: item.name,
                    market: item.market,
                    currency: item.currency,
                    note: item.note,
                    addedAt: Self.serverDateFormatter.date(from: item.addedAt) ?? Date()
                )
            }
            items = merged(local: items, remote: remoteItems)
            save()
        } catch {
            syncStatus = .failed("Watchlist 불러오기 실패: \(error.localizedDescription)")
        }
    }

    private func saveRemote(_ item: WatchlistItem, token: String) async -> Bool {
        do {
            let _: EmptyResponse = try await APIClient.shared.authenticatedSend(
                ["me", "watchlist"],
                token: token,
                body: WatchlistRequest(
                    ticker: item.ticker,
                    name: item.name,
                    market: item.market,
                    currency: item.currency,
                    note: item.note
                )
            )
            return true
        } catch {
            syncStatus = .failed("Watchlist 저장 실패: \(error.localizedDescription)")
            return false
        }
    }

    private func deleteRemote(_ ticker: String, token: String) async -> Bool {
        do {
            let _: EmptyResponse = try await APIClient.shared.authenticatedEmpty(
                ["me", "watchlist", ticker],
                method: "DELETE",
                token: token
            )
            return true
        } catch {
            syncStatus = .failed("Watchlist 삭제 실패: \(error.localizedDescription)")
            return false
        }
    }

    private func enqueue(_ operation: PendingWatchlistOperation) {
        pendingOperations.removeAll { pending in
            pending.ticker == operation.ticker || pending.item?.ticker == operation.ticker
        }
        pendingOperations.append(operation)
        savePendingOperations()
        syncStatus = .failed("\(pendingOperations.count)개 동기화 대기")
    }

    private func syncPendingOperations(token: String) async {
        guard !isSyncing, !pendingOperations.isEmpty else { return }
        isSyncing = true
        syncStatus = .syncing(pendingOperations.count)
        defer { isSyncing = false }

        var remaining: [PendingWatchlistOperation] = []
        for operation in pendingOperations {
            let succeeded: Bool
            switch operation.kind {
            case .save:
                if let item = operation.item {
                    succeeded = await saveRemote(item, token: token)
                } else {
                    succeeded = true
                }
            case .delete:
                succeeded = await deleteRemote(operation.ticker, token: token)
            }
            if !succeeded {
                remaining.append(operation)
            }
        }

        pendingOperations = remaining
        savePendingOperations()
        syncStatus = remaining.isEmpty ? .synced(items.count) : .failed("\(remaining.count)개 동기화 대기")
    }

    private func normalized(_ item: WatchlistItem) -> WatchlistItem {
        WatchlistItem(
            ticker: normalizedTicker(item.ticker),
            name: displayCompanyName(item.name, ticker: item.ticker),
            market: item.market,
            currency: item.currency,
            note: item.note,
            addedAt: item.addedAt,
            tags: item.tags,
            memo: item.memo,
            alertOptions: item.alertOptions
        )
    }

    private func merged(local: [WatchlistItem], remote: [WatchlistItem]) -> [WatchlistItem] {
        var seen = Set<String>()
        var mergedItems: [WatchlistItem] = []
        for item in local + remote {
            let normalized = normalized(item)
            guard !seen.contains(normalized.ticker) else { continue }
            seen.insert(normalized.ticker)
            mergedItems.append(normalized)
        }
        return mergedItems.sorted { $0.addedAt > $1.addedAt }
    }
}

func watchlistItem(ticker: String, name: String, market: String?, currency: String, note: String) -> WatchlistItem {
    WatchlistItem(
        ticker: normalizedTicker(ticker),
        name: displayCompanyName(name, ticker: ticker),
        market: market ?? (currency == "KRW" ? "KR" : "US"),
        currency: currency,
        note: note,
        addedAt: Date()
    )
}

func normalizedTicker(_ ticker: String) -> String {
    ticker.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
}

func portfolioSignals(_ stock: PortfolioStock) -> [InvestmentSignal] {
    var signals: [InvestmentSignal] = []

    if let score = stock.totalScore, score >= 0.7 {
        signals.append(.init(
            title: "상위 팩터 점수",
            detail: "종합 점수가 높아 현재 분석 후보군에서 우선순위가 높습니다.",
            systemImage: "heart.circle.fill",
            color: .blue
        ))
    }
    if let roic = stock.roic, roic >= 0.15 {
        signals.append(.init(
            title: "높은 ROIC",
            detail: "자본 효율성이 좋아 퀄리티 팩터에 긍정적입니다.",
            systemImage: "checkmark.seal.fill",
            color: .green
        ))
    }
    if let growth = stock.revGrowth, growth >= 0.15 {
        signals.append(.init(
            title: "매출 성장",
            detail: "매출 성장률이 높아 성장 모멘텀이 확인됩니다.",
            systemImage: "chart.line.uptrend.xyaxis.circle.fill",
            color: AppTheme.positive
        ))
    }
    if let expected = stock.expectedReturn, expected < 0 {
        signals.append(.init(
            title: "기대수익률 주의",
            detail: "모델 기대수익률이 음수라 진입 타이밍을 확인해야 합니다.",
            systemImage: "exclamationmark.triangle.fill",
            color: .orange
        ))
    }

    return fallbackSignals(signals, name: stock.name)
}

func smallCapSignals(_ stock: SmallCapStock) -> [InvestmentSignal] {
    var signals: [InvestmentSignal] = []

    if let score = stock.totalScore, score >= 70 {
        signals.append(.init(
            title: "스몰캡 상위 점수",
            detail: "소형주 스캐너 기준으로 종합 매력이 높습니다.",
            systemImage: "sparkles",
            color: .orange
        ))
    }
    if let accel = stock.revAccel, accel > 0 {
        signals.append(.init(
            title: "성장 가속",
            detail: "매출 성장의 가속 신호가 있어 추가 관찰 가치가 있습니다.",
            systemImage: "speedometer",
            color: AppTheme.positive
        ))
    }
    if let volume = stock.volumeSurge, volume >= 1.5 {
        signals.append(.init(
            title: "거래량 증가",
            detail: "평소보다 거래량이 커져 시장 관심이 붙고 있습니다.",
            systemImage: "waveform.path.ecg",
            color: .purple
        ))
    }
    if let debt = stock.debtEbitda, debt > 4 {
        signals.append(.init(
            title: "부채 부담",
            detail: "Debt/EBITDA가 높아 재무 리스크 확인이 필요합니다.",
            systemImage: "exclamationmark.triangle.fill",
            color: .orange
        ))
    }

    return fallbackSignals(signals, name: stock.name)
}

func earningsSignals(_ stock: EarningsStock) -> [InvestmentSignal] {
    var signals: [InvestmentSignal] = []

    if let surprise = stock.surprisePct, surprise > 0 {
        signals.append(.init(
            title: "실적 서프라이즈",
            detail: "예상보다 좋은 실적이 확인되어 단기 모멘텀에 긍정적입니다.",
            systemImage: "bolt.circle.fill",
            color: AppTheme.positive
        ))
    }
    if let returnSince = stock.returnSince, returnSince > 0 {
        signals.append(.init(
            title: "발표 후 주가 반응",
            detail: "실적 발표 이후 수익률이 플러스로 유지되고 있습니다.",
            systemImage: "arrow.up.right.circle.fill",
            color: AppTheme.positive
        ))
    }
    if let signal = stock.signalStrength, signal >= 1 {
        signals.append(.init(
            title: "강한 시그널",
            detail: "서프라이즈와 가격 반응이 함께 나타난 후보입니다.",
            systemImage: "antenna.radiowaves.left.and.right.circle.fill",
            color: .purple
        ))
    }
    if let days = stock.daysSince, days <= 7 {
        signals.append(.init(
            title: "최근 이벤트",
            detail: "실적 발표가 최근에 발생해 정보 반영 과정을 볼 만합니다.",
            systemImage: "clock.badge.checkmark",
            color: .blue
        ))
    }

    return fallbackSignals(signals, name: stock.name)
}

func watchlistSignals(_ item: WatchlistItem) -> [InvestmentSignal] {
    let thesis = item.investmentThesis
    guard !thesis.isEmpty else {
        return [.init(
            title: "관심 종목",
            detail: "Watchlist에 저장한 종목입니다. 가격, 52주 범위, 기업 정보를 확인하세요.",
            systemImage: "heart.circle.fill",
            color: .yellow
        )]
    }
    return [.init(
        title: "투자 가설",
        detail: thesis.detailSummary,
        systemImage: "lightbulb.fill",
        color: AppTheme.accent
    )]
}

private func fallbackSignals(_ signals: [InvestmentSignal], name: String) -> [InvestmentSignal] {
    if signals.isEmpty {
        return [.init(
            title: "추가 확인 필요",
            detail: "\(name)의 핵심 팩터가 중립적입니다. 차트와 상세 지표를 함께 확인하세요.",
            systemImage: "doc.text.magnifyingglass",
            color: .secondary
        )]
    }
    return Array(signals.prefix(4))
}

func sectorExposure(_ stocks: [PortfolioStock]) -> [ExposureSlice] {
    let grouped = Dictionary(grouping: stocks, by: { stock in
        let sector = stock.sector?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return sector.isEmpty ? "Unknown" : sector
    })

    return grouped
        .map { sector, stocks in
            ExposureSlice(
                label: sector,
                value: stocks.reduce(0) { $0 + max($1.weight ?? 0, 0) },
                color: exposureColor(for: sector)
            )
        }
        .sorted { $0.value > $1.value }
}

func factorExposure(_ stocks: [PortfolioStock]) -> [ExposureSlice] {
    let quality = stocks.compactMap(\.roic).average
    let growth = stocks.compactMap(\.revGrowth).average
    let scoreValue = stocks.compactMap(\.totalScore).average

    return [
        ExposureSlice(label: "Quality", value: max(quality, 0), color: .green),
        ExposureSlice(label: "Growth", value: max(growth, 0), color: .blue),
        ExposureSlice(label: "Score", value: max(scoreValue, 0), color: .purple)
    ]
}

func rebalanceActions(_ stocks: [PortfolioStock]) -> [RebalanceAction] {
    let eligible = stocks.filter { ($0.weight ?? 0) > 0 }
    guard !eligible.isEmpty else { return [] }

    let scoreSum = eligible.reduce(0) { $0 + max($1.totalScore ?? 0.01, 0.01) }
    return eligible
        .map { stock in
            let target = max(stock.totalScore ?? 0.01, 0.01) / scoreSum
            return RebalanceAction(
                ticker: stock.ticker,
                name: stock.name,
                currentWeight: stock.weight ?? 0,
                targetWeight: target
            )
        }
        .sorted { abs($0.delta) > abs($1.delta) }
}

func dataFreshnessText(meta: [String: String]) -> String {
    if let generated = meta["Generated"], !generated.isEmpty {
        return generated
    }
    if let updated = meta["Last_Updated"], !updated.isEmpty {
        return updated
    }
    return "업데이트 정보 없음"
}

private func exposureColor(for label: String) -> Color {
    let palette: [Color] = [.blue, .green, .orange, .purple, .cyan, .pink, .indigo, .teal]
    let index = abs(label.hashValue) % palette.count
    return palette[index]
}

private extension Array where Element == Double {
    var average: Double {
        guard !isEmpty else { return 0 }
        return reduce(0, +) / Double(count)
    }
}
