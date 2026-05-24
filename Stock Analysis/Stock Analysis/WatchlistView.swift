import Foundation
import SwiftUI

struct WatchlistView: View {
    let openSearch: () -> Void

    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var notifications: NotificationStore
    @EnvironmentObject private var comparison: ComparisonStore
    @EnvironmentObject private var decisions: InvestmentDecisionStore
    @StateObject private var headerIndices = MarketIndicesVM()
    @StateObject private var marketIndicators = MarketIndicatorsVM()
    @StateObject private var usPortfolio = PortfolioVM(market: .us)
    @StateObject private var krPortfolio = PortfolioVM(market: .kr)
    @StateObject private var smallCap = SmallCapVM()
    @StateObject private var pulse = PulseVM()
    @State private var selected: WatchlistItem?
    @State private var editingItem: WatchlistItem?
    @State private var selectedGroup = WatchlistGroup.companies
    @State private var marketFilter = WatchlistMarketFilter.all
    @State private var sort = WatchlistSort.signal
    @State private var recentlyDeleted: WatchlistItem?
    @State private var watchPriceMetrics: [String: WatchlistStockPriceMetric] = [:]
    @State private var watchPriceMetricsKey = ""
    @State private var showHeaderMarketIndicators = false

    init(openSearch: @escaping () -> Void = {}) {
        self.openSearch = openSearch
    }

    private var companyItems: [WatchlistItem] {
        watchlist.items.filter { !$0.isMarketIndicator && !$0.isETFWatchItem }
    }

    private var indicatorItems: [WatchlistItem] {
        watchlist.items.filter(\.isMarketIndicator)
    }

    private var etfItems: [WatchlistItem] {
        watchlist.items.filter(\.isETFWatchItem)
    }

    private var nonIndicatorItems: [WatchlistItem] {
        watchlist.items.filter { !$0.isMarketIndicator }
    }

    private var activeItems: [WatchlistItem] {
        switch selectedGroup {
        case .all:
            return watchlist.items
        case .companies:
            return companyItems
        case .indicators:
            return indicatorItems
        case .etfs:
            return etfItems
        }
    }

    private var filteredItems: [WatchlistItem] {
        let base = activeItems
            .filter { item in
                selectedGroup == .indicators || selectedGroup == .all || marketFilter.matches(item)
            }
        return base.sorted(by: watchSortComparator)
    }

    private var companyMonitorLoading: Bool {
        [usPortfolio.state, krPortfolio.state, smallCap.state, pulse.state].contains { state in
            if case .loading = state { return true }
            if case .idle = state { return true }
            return false
        }
    }

    private var prioritySourceItems: [WatchlistItem] {
        switch selectedGroup {
        case .all:
            return nonIndicatorItems
        case .companies:
            return companyItems
        case .indicators:
            return []
        case .etfs:
            return etfItems
        }
    }

    private var watchPriceItems: [WatchlistItem] {
        watchlist.items.filter { !$0.isMarketIndicator }
    }

    private var watchPriceTaskKey: String {
        watchPriceItems
            .map { "\($0.market):\($0.ticker)" }
            .sorted()
            .joined(separator: "|")
    }

    private var watchPriorityItems: [WatchPriorityItem] {
        prioritySourceItems
            .map { WatchPriorityItem(item: $0, insight: companyInsight(for: $0)) }
            .filter { $0.insight.priority >= 1 }
            .sorted {
                if $0.insight.priority == $1.insight.priority {
                    return $0.item.addedAt > $1.item.addedAt
                }
                return $0.insight.priority > $1.insight.priority
            }
    }

    private var judgmentTimelineItems: [WatchJudgmentTimelineDisplayItem] {
        let history = notifications.judgmentHistory.prefix(6).map {
            WatchJudgmentTimelineDisplayItem(
                id: $0.id,
                title: $0.title,
                detail: $0.body,
                source: $0.source,
                recordedAt: $0.recordedAt,
                color: AppTheme.accent
            )
        }
        if !history.isEmpty { return Array(history) }
        return watchPriorityItems.prefix(4).map { priority in
            WatchJudgmentTimelineDisplayItem(
                id: "current-\(priority.item.ticker)-\(priority.insight.title)",
                title: priority.insight.title,
                detail: priority.insight.detail,
                source: "현재 신호",
                recordedAt: nil,
                color: priority.insight.color
            )
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    WatchlistControls(
                        selectedGroup: $selectedGroup,
                        totalCount: watchlist.items.count,
                        companyCount: companyItems.count,
                        indicatorCount: indicatorItems.count,
                        etfCount: etfItems.count
                    )
                    .listRowBackground(AppTheme.card)
                }

                if selectedGroup != .indicators, !nonIndicatorItems.isEmpty {
                    Section {
                        WatchPriorityQueueCard(
                            items: watchPriorityItems,
                            itemCount: prioritySourceItems.count,
                            isLoading: companyMonitorLoading,
                            open: { selected = $0 },
                            refresh: refreshCompanyMonitorData
                        )
                        .listRowBackground(AppTheme.card)
                    }

                    if !judgmentTimelineItems.isEmpty {
                        Section {
                            WatchJudgmentTimelineCard(items: judgmentTimelineItems)
                                .listRowBackground(AppTheme.card)
                        }
                    }
                }

                if let syncMessage = watchlist.syncStatus.message {
                    Section {
                        WatchlistSyncBanner(
                            message: syncMessage,
                            isSyncing: watchlist.syncStatus.isSyncing,
                            isSynced: watchlist.syncStatus.isSynced,
                            retry: watchlist.retrySync
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }

                if let recentlyDeleted {
                    Section {
                        WatchlistUndoBanner(
                            item: recentlyDeleted,
                            undo: undoDelete,
                            dismiss: { self.recentlyDeleted = nil }
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }

                if watchlist.items.isEmpty {
                    EmptyMsg(
                        icon: "heart",
                        msg: "관심 항목 없음",
                        detail: "기업, 주요 지수, ETF를 하트로 추가하면 이곳에서 따로 관리할 수 있어요.",
                        actionTitle: "관심 추가하기",
                        action: openSearch
                    )
                } else if activeItems.isEmpty {
                    EmptyMsg(
                        icon: selectedGroup.emptyIcon,
                        msg: selectedGroup.emptyTitle,
                        detail: selectedGroup.emptyDetail
                    )
                } else if filteredItems.isEmpty {
                    Section {
                        EmptyMsg(
                            icon: "line.3.horizontal.decrease.circle",
                            msg: "조건에 맞는 종목 없음",
                            detail: "시장 필터를 바꿔보세요."
                        )
                    } header: {
                        WatchlistSectionHeader(
                            title: selectedGroup.sectionTitle,
                            count: "\(filteredItems.count)/\(activeItems.count)개",
                            sort: $sort,
                            marketFilter: selectedGroup == .companies || selectedGroup == .etfs ? $marketFilter : nil
                        )
                    }
                } else if selectedGroup == .indicators {
                    if shouldShowIndicatorDataStatus {
                        Section {
                            WatchIndicatorDataStatusCard(
                                isLoading: marketIndicators.isLoading,
                                error: marketIndicators.error,
                                hasData: !marketIndicators.items.isEmpty,
                                retry: refreshIndicatorData
                            )
                            .listRowBackground(AppTheme.card)
                        }
                    }
                    Section {
                        ForEach(indicatorRows, id: \.id) { row in
                            HStack(spacing: 10) {
                                ForEach(row.items) { item in
                                    WatchIndicatorGraphCard(
                                        item: item,
                                        quote: indicatorQuote(for: item),
                                        points: indicatorPoints(for: item),
                                        delete: { delete(item) }
                                    )
                                    .frame(maxWidth: .infinity)
                                }
                                if row.items.count == 1 {
                                    Color.clear.frame(maxWidth: .infinity)
                                }
                            }
                            .listRowInsets(.init(top: 6, leading: 16, bottom: 6, trailing: 16))
                            .listRowBackground(Color.clear)
                        }
                    } header: {
                        WatchlistSectionHeader(
                            title: selectedGroup.sectionTitle,
                            count: "\(filteredItems.count)/\(activeItems.count)개",
                            sort: $sort
                        )
                    }
                } else {
                    Section {
                        ForEach(filteredItems) { item in
                            WatchlistRow(
                                item: item,
                                insight: companyInsight(for: item),
                                decision: decisions.record(for: item.ticker),
                                indicatorQuote: item.isMarketIndicator ? indicatorQuote(for: item) : nil
                            )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    if !item.isMarketIndicator {
                                        selected = item
                                    }
                                }
                                .listRowBackground(AppTheme.card)
                                .swipeActions {
                                    Button(role: .destructive) {
                                        delete(item)
                                    } label: {
                                        Label("삭제", systemImage: "trash")
                                    }
                                }
                                .swipeActions(edge: .leading) {
                                    if !item.isMarketIndicator {
                                        Button {
                                            editingItem = item
                                        } label: {
                                            Label("설정", systemImage: "slider.horizontal.3")
                                        }
                                        .tint(AppTheme.accent)

                                        Button {
                                            comparison.add(StockComparisonItem(watchlist: item))
                                        } label: {
                                            Label("비교", systemImage: "square.split.2x1")
                                        }
                                        .tint(AppTheme.momentum)
                                    }
                                }
                        }
                    } header: {
                        WatchlistSectionHeader(
                            title: selectedGroup.sectionTitle,
                            count: "\(filteredItems.count)/\(activeItems.count)개",
                            sort: $sort,
                            marketFilter: selectedGroup == .companies || selectedGroup == .etfs ? $marketFilter : nil
                        )
                    }
                }
            }
            .listStyle(.insetGrouped)
            .appTabBarInset()
            .scrollContentBackground(.hidden)
            .appScreenBackground()
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                QubitScreenTopHeader(
                    title: "관심",
                    indices: headerIndices.indices,
                    openSearch: openSearch,
                    openMarketIndicators: { showHeaderMarketIndicators = true }
                )
            }
        }
        .task { await headerIndices.load() }
        .task(id: selectedGroup) {
            if (selectedGroup == .indicators || selectedGroup == .all), marketIndicators.items.isEmpty {
                await marketIndicators.load(category: "all")
            }
            if selectedGroup != .indicators {
                await loadCompanyMonitorData()
            }
        }
        .task(id: watchPriceTaskKey) {
            if !watchPriceTaskKey.isEmpty {
                await refreshWatchPriceMetrics(force: true)
            }
        }
        .task(id: "watch-price-auto-\(watchPriceTaskKey)") {
            guard !watchPriceTaskKey.isEmpty else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.fastPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("watch-prices-\(watchPriceTaskKey)", minInterval: 60) else { continue }
                await refreshWatchPriceMetrics(force: true)
            }
        }
        .task {
            await loadCompanyMonitorData()
        }
        .fullScreenCover(item: $selected) { item in
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
        .sheet(item: $editingItem) { item in
            WatchMetadataSheet(item: item) { updated in
                watchlist.updateMetadata(
                    ticker: updated.ticker,
                    tags: updated.tags,
                    memo: updated.memo,
                    alertOptions: updated.alertOptions
                )
            }
        }
        .navigationDestination(isPresented: $showHeaderMarketIndicators) {
            MarketIndicatorsScreen(vm: marketIndicators)
        }
    }

    private var shouldShowIndicatorDataStatus: Bool {
        selectedGroup == .indicators &&
            !activeItems.isEmpty &&
            (marketIndicators.isLoading || marketIndicators.error != nil || marketIndicators.items.isEmpty)
    }

    private func refreshIndicatorData() {
        Task { await marketIndicators.load(refresh: true, category: "all") }
    }

    private func loadCompanyMonitorData() async {
        async let us: Void = usPortfolio.load()
        async let kr: Void = krPortfolio.load()
        async let small: Void = smallCap.load()
        async let pulseLoad: Void = pulse.load()
        _ = await (us, kr, small, pulseLoad)
    }

    private func refreshCompanyMonitorData() {
        Task {
            async let us: Void = usPortfolio.refresh()
            async let kr: Void = krPortfolio.refresh()
            async let small: Void = smallCap.refresh()
            async let pulseLoad: Void = pulse.refresh()
            async let priceLoad: Void = refreshWatchPriceMetrics(force: true)
            _ = await (us, kr, small, pulseLoad, priceLoad)
        }
    }

    private func refreshWatchPriceMetrics(force: Bool = false) async {
        let items = watchPriceItems
        let key = watchPriceTaskKey
        if !force, key == watchPriceMetricsKey, !watchPriceMetrics.isEmpty { return }
        watchPriceMetricsKey = key
        guard !items.isEmpty else {
            watchPriceMetrics = [:]
            return
        }

        var next: [String: WatchlistStockPriceMetric] = [:]
        let groups = Dictionary(grouping: items, by: watchlistPriceMarket)
        for (market, marketItems) in groups {
            let tickers = Array(Set(marketItems.flatMap { watchlistPriceLookupTickers($0.ticker, market: market) })).sorted()
            guard !tickers.isEmpty else { continue }
            do {
                let metrics = try await APIClient.shared.fetchWatchlistStockPriceMetrics(market: market, tickers: tickers, refresh: force)
                for metric in metrics {
                    for key in watchlistPriceMatchKeys(metric.ticker) {
                        next[key] = metric
                    }
                }
                for item in marketItems {
                    if let metric = watchlistPriceMatchKeys(item.ticker).compactMap({ next[$0] }).first {
                        for key in watchlistPriceMatchKeys(item.ticker) {
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

    private func delete(_ item: WatchlistItem) {
        recentlyDeleted = item
        watchlist.remove(item.ticker)
    }

    private func undoDelete() {
        guard let recentlyDeleted else { return }
        watchlist.toggle(recentlyDeleted)
        self.recentlyDeleted = nil
    }

    private var indicatorRows: [WatchIndicatorRowGroup] {
        stride(from: 0, to: filteredItems.count, by: 2).map { index in
            WatchIndicatorRowGroup(items: Array(filteredItems[index..<min(index + 2, filteredItems.count)]))
        }
    }

    private func indicatorQuote(for item: WatchlistItem) -> MarketIndicatorQuote {
        let key = canonicalMarketIndicatorSymbol(for: item)
        if let quote = marketIndicators.items.first(where: { canonicalMarketIndicatorSymbol($0.symbol) == key }) {
            return quote
        }
        return MarketIndicatorQuote(
            symbol: key.isEmpty ? item.ticker : key,
            label: item.name,
            category: watchIndicatorCategory(item: item),
            region: watchIndicatorRegion(item: item, symbol: key),
            value: .nan,
            changeAbs: nil,
            changePct: nil,
            updatedAt: nil
        )
    }

    private func indicatorPoints(for item: WatchlistItem) -> [MarketIndicatorPoint] {
        let key = canonicalMarketIndicatorSymbol(for: item)
        if let points = marketIndicators.seriesBySymbol[key] { return points }
        if let points = marketIndicators.seriesBySymbol[item.ticker] { return points }
        return marketIndicators.seriesBySymbol.first { canonicalMarketIndicatorSymbol($0.key) == key }?.value ?? []
    }

    private func watchSortComparator(_ lhs: WatchlistItem, _ rhs: WatchlistItem) -> Bool {
        switch sort {
        case .signal:
            let lhsSignal = watchSignalScore(for: lhs)
            let rhsSignal = watchSignalScore(for: rhs)
            if lhsSignal != rhsSignal {
                return lhsSignal > rhsSignal
            }
            return lhs.addedAt > rhs.addedAt
        case .added:
            return lhs.addedAt > rhs.addedAt
        case .name:
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        case .market:
            if lhs.market == rhs.market { return lhs.name < rhs.name }
            return lhs.market < rhs.market
        }
    }

    private func watchSignalScore(for item: WatchlistItem) -> Double {
        if item.isMarketIndicator {
            return abs(indicatorQuote(for: item).changePct ?? 0)
        }
        return Double(companyInsight(for: item).priority)
    }

    private func companyInsight(for item: WatchlistItem) -> WatchCompanyInsight {
        let portfolio = portfolioMatch(for: item)
        let small = smallCapMatch(for: item)
        let earnings = earningsMatch(for: item)
        let calendar = earningsCalendarMatch(for: item)
        let priceMetric = watchlistPriceMetric(item.ticker, priceMetrics: watchPriceMetrics)
        let metrics = companyInsightMetrics(portfolio: portfolio, smallCap: small, earnings: earnings, calendar: calendar)
        let details = companyInsightDetails(
            portfolio: portfolio,
            smallCap: small,
            earnings: earnings,
            calendar: calendar,
            priceMetric: priceMetric,
            item: item
        )
        let updatedAt = portfolio?.lastUpdated ?? small?.lastUpdated ?? earnings?.earningsDate ?? calendar?.nextEarningsDate ?? priceMetric?.updatedAt

        if let calendar, let days = calendar.daysUntil, days >= 0, days <= 7 {
            return WatchCompanyInsight(
                title: "실적 임박",
                detail: "\(earningsCalendarDayText(days)) · \(compactDateText(calendar.nextEarningsDate)) 발표 예정",
                metrics: metrics,
                details: details,
                color: AppTheme.warning,
                systemImage: "calendar.badge.clock",
                priority: 4,
                updatedAt: updatedAt,
                isLinked: true
            )
        }

        if let earnings, let signal = earnings.signalStrength, signal.isFinite, signal >= 1 {
            return WatchCompanyInsight(
                title: "실적 반응",
                detail: "Signal \(String(format: "%.2f", signal)) · 발표 후 수익률 \(pct(earnings.returnSince))",
                metrics: metrics,
                details: details,
                color: AppTheme.momentum,
                systemImage: "bolt.fill",
                priority: 3,
                updatedAt: updatedAt,
                isLinked: true
            )
        }

        if let portfolio, let expected = portfolio.expectedReturn, expected.isFinite {
            return WatchCompanyInsight(
                title: expected >= 0 ? "후보 유지" : "타이밍 확인",
                detail: "기대수익 \(pct(expected)) · 점수 \(score(portfolio.totalScore))",
                metrics: metrics,
                details: details,
                color: expected >= 0 ? AppTheme.accent : AppTheme.warning,
                systemImage: expected >= 0 ? "scope" : "exclamationmark.triangle",
                priority: expected >= 0 ? 2 : 3,
                updatedAt: updatedAt,
                isLinked: true
            )
        }

        if let small, let volume = small.volumeSurge, volume.isFinite, volume >= 1.5 {
            return WatchCompanyInsight(
                title: "거래량 변화",
                detail: "평소 대비 \(multipleText(volume)) · 스몰캡 점수 \(score(small.totalScore))",
                metrics: metrics,
                details: details,
                color: AppTheme.accent,
                systemImage: "waveform.path.ecg",
                priority: 2,
                updatedAt: updatedAt,
                isLinked: true
            )
        }

        if portfolio != nil || small != nil || earnings != nil || calendar != nil {
            return WatchCompanyInsight(
                title: "데이터 연결",
                detail: "후보, 실적, 일정 데이터 중 일부가 연결되어 있습니다.",
                metrics: metrics,
                details: details,
                color: AppTheme.quality,
                systemImage: "checkmark.seal",
                priority: 1,
                updatedAt: updatedAt,
                isLinked: true
            )
        }

        let hasPrice = priceMetric?.currentPrice?.isFinite == true
        return WatchCompanyInsight(
            title: item.isETFWatchItem ? "ETF 감시" : "기본 감시",
            detail: hasPrice ? "가격 데이터를 연결해 계속 감시 중입니다." : "상세에서 가격과 기업 정보를 확인하세요.",
            metrics: [item.market, item.note].filter { !$0.isEmpty },
            details: details,
            color: AppTheme.secondaryText,
            systemImage: "eye",
            priority: 0,
            updatedAt: updatedAt ?? item.addedAt.formatted(date: .abbreviated, time: .omitted),
            isLinked: hasPrice
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

    private func companyInsightMetrics(
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
            values.append(earningsCalendarDayText(days))
        }
        return Array(values.prefix(3))
    }

    private func companyInsightDetails(
        portfolio: PortfolioStock?,
        smallCap: SmallCapStock?,
        earnings: EarningsStock?,
        calendar: EarningsCalendarItem?,
        priceMetric: WatchlistStockPriceMetric?,
        item: WatchlistItem
    ) -> [String] {
        let currency = portfolio.map { marketCurrency(for: $0.ticker, market: $0.market) }
            ?? smallCap.map { marketCurrency(for: $0.ticker, market: $0.market) }
            ?? item.currency
        var values: [String] = []
        if let price = [portfolio?.currentPrice, smallCap?.currentPrice, priceMetric?.currentPrice].compactMap({ $0 }).first(where: { $0.isFinite }) {
            values.append("가격 \(fmtPx(price, currency: currency))")
        }
        if let dailyChange = priceMetric?.dailyChangePct, dailyChange.isFinite {
            values.append("하루 \(pct(dailyChange))")
        } else if let return1M = [portfolio?.return1M, smallCap?.return1M, priceMetric?.return1M].compactMap({ $0 }).first(where: { $0.isFinite }) {
            values.append("1개월 \(pct(return1M))")
        }
        if let marketCap = portfolio?.marketCap ?? smallCap?.marketCap ?? earnings?.marketCap ?? calendar?.marketCap, marketCap.isFinite {
            values.append("시총 \(cap(marketCap, currency: currency))")
        }
        if let sector = portfolio?.sector ?? earnings?.sector ?? calendar?.sector, !sector.isEmpty {
            values.append(portfolioIndustryLabel(ticker: item.ticker, name: item.name, sector: sector))
        }
        if let date = calendar?.nextEarningsDate ?? earnings?.earningsDate {
            values.append("실적 \(compactDateText(date))")
        }
        if values.isEmpty {
            values.append(contentsOf: [item.market, item.note].filter { !$0.isEmpty })
        }
        return Array(values.prefix(4))
    }
}

private struct WatchIndicatorRowGroup: Identifiable {
    let items: [WatchlistItem]

    var id: String {
        items.map(\.ticker).joined(separator: "|")
    }
}

private struct WatchlistStockPriceMetric: Decodable {
    let ticker: String
    let currentPrice: Double?
    let return1M: Double?
    let dailyChangePct: Double?
    let dailyChangeHorizon: String?
    let updatedAt: String?

    private enum CodingKeys: String, CodingKey {
        case ticker = "Ticker"
        case currentPrice = "Current_Price"
        case return1M = "Return_1M"
        case dailyChangePct = "Daily_Change_Pct"
        case dailyChangeHorizon = "Daily_Change_Horizon"
        case updatedAt = "Price_Updated_At"
    }
}

private struct WatchlistStockPriceMetricsResponse: Decodable {
    let metrics: [WatchlistStockPriceMetric]
}

private extension APIClient {
    func fetchWatchlistStockPriceMetrics(market: String, tickers: [String], refresh: Bool = false) async throws -> [WatchlistStockPriceMetric] {
        let cleanTickers = Array(Set(tickers.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }.filter { !$0.isEmpty })).sorted()
        guard !cleanTickers.isEmpty else { return [] }
        let response: WatchlistStockPriceMetricsResponse = try await fetch(
            ["portfolio", market.uppercased(), "prices"],
            queryItems: [
                URLQueryItem(name: "tickers", value: cleanTickers.prefix(100).joined(separator: ",")),
                URLQueryItem(name: "limit", value: "\(min(cleanTickers.count, 100))"),
                URLQueryItem(name: "refresh", value: refresh ? "true" : "false")
            ]
        )
        return response.metrics
    }
}

private func watchlistPriceMarket(_ item: WatchlistItem) -> String {
    let market = item.market.uppercased()
    if market == "KR" || item.currency == "KRW" {
        return "KR"
    }
    return "US"
}

private func watchlistPriceLookupTickers(_ ticker: String, market: String) -> [String] {
    let normalized = normalizedTicker(ticker)
    let code = krCode(from: normalized)
    if market.uppercased() == "KR", !code.isEmpty {
        return ["\(code).KS", "\(code).KQ", code]
    }
    return normalized.isEmpty ? [] : [normalized]
}

private func watchlistPriceMatchKeys(_ ticker: String) -> [String] {
    let normalized = normalizedTicker(ticker)
    guard !normalized.isEmpty else { return [] }
    var keys: [String] = [normalized]
    let code = krCode(from: normalized)
    if !code.isEmpty {
        keys.append(contentsOf: [code, "\(code).KS", "\(code).KQ"])
    }
    return Array(Set(keys))
}

private func watchlistPriceMetric(_ ticker: String, priceMetrics: [String: WatchlistStockPriceMetric]) -> WatchlistStockPriceMetric? {
    watchlistPriceMatchKeys(ticker).compactMap { priceMetrics[$0] }.first
}

private let watchlistETFTickers: Set<String> = [
    "QQQ", "SPY", "VOO", "VTI", "DIA", "IWM", "SCHD", "SMH", "SOXX", "XLK",
    "XLF", "XLV", "VNQ", "TLT", "GLD", "ARKK", "069500", "360750", "379800",
    "305720", "305540", "453850"
]

private extension WatchlistItem {
    var isETFWatchItem: Bool {
        if isMarketIndicator { return false }
        return note.localizedCaseInsensitiveContains("ETF") ||
            name.localizedCaseInsensitiveContains("ETF") ||
            watchlistETFTickers.contains(normalizedTicker(ticker))
    }
}

private enum WatchlistGroup: String, CaseIterable, Identifiable {
    case all = "전체"
    case companies = "기업"
    case indicators = "지수"
    case etfs = "ETF"

    var id: String { rawValue }

    var sectionTitle: String {
        switch self {
        case .all:
            return "관심 목록"
        case .companies:
            return "관심 기업"
        case .indicators:
            return "관심 지수"
        case .etfs:
            return "관심 ETF"
        }
    }

    var emptyIcon: String {
        switch self {
        case .all:
            return "heart"
        case .companies:
            return "heart"
        case .indicators:
            return "heart"
        case .etfs:
            return "chart.pie"
        }
    }

    var emptyTitle: String {
        switch self {
        case .all:
            return "관심 항목 없음"
        case .companies:
            return "관심 기업 없음"
        case .indicators:
            return "관심 지수 없음"
        case .etfs:
            return "관심 ETF 없음"
        }
    }

    var emptyDetail: String {
        switch self {
        case .all:
            return "기업, 지수, ETF를 관심에 추가하면 이곳에서 한 번에 확인할 수 있어요."
        case .companies:
            return "분석, 인사이트, 기업 상세 화면의 하트로 추가해보세요."
        case .indicators:
            return "주요 지수 화면에서 하트를 누르면 이곳에 모입니다."
        case .etfs:
            return "ETF 화면이나 ETF 상세에서 관심에 추가해보세요."
        }
    }
}

private enum WatchlistMarketFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case us = "US"
    case kr = "KR"

    var id: String { rawValue }

    var displayTitle: String {
        switch self {
        case .all:
            return "ALL"
        case .us:
            return "US"
        case .kr:
            return "KR"
        }
    }

    func matches(_ item: WatchlistItem) -> Bool {
        switch self {
        case .all:
            return true
        case .us:
            return item.market == "US"
        case .kr:
            return item.market == "KR" || item.currency == "KRW"
        }
    }
}

private enum WatchlistSort: String, CaseIterable, Identifiable {
    case signal = "이슈순"
    case added = "최근 추가순"
    case name = "이름순"
    case market = "시장순"

    var id: String { rawValue }
}

private struct WatchlistControls: View {
    @Binding var selectedGroup: WatchlistGroup
    let totalCount: Int
    let companyCount: Int
    let indicatorCount: Int
    let etfCount: Int

    var body: some View {
        WatchGroupToggle(
            selection: $selectedGroup,
            totalCount: totalCount,
            companyCount: companyCount,
            indicatorCount: indicatorCount,
            etfCount: etfCount
        )
        .padding(.vertical, 2)
    }
}

private struct WatchGroupToggle: View {
    @Binding var selection: WatchlistGroup
    let totalCount: Int
    let companyCount: Int
    let indicatorCount: Int
    let etfCount: Int

    var body: some View {
        HStack(spacing: 4) {
            ForEach(WatchlistGroup.allCases) { group in
                Button {
                    selection = group
                } label: {
                    HStack(spacing: 6) {
                        Text(group.rawValue)
                            .font(.subheadline.weight(.semibold))
                        Text("\(count(for: group))")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(selection == group ? AppTheme.accent : AppTheme.tertiaryText)
                    }
                    .foregroundStyle(selection == group ? AppTheme.primaryText : AppTheme.secondaryText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(selection == group ? AppTheme.card : Color.clear)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(selection == group ? AppTheme.hairline : Color.clear, lineWidth: 0.5)
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(group.rawValue) \(count(for: group))개 보기")
            }
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(AppTheme.elevatedCard)
        )
    }

    private func count(for group: WatchlistGroup) -> Int {
        switch group {
        case .all:
            return totalCount
        case .companies:
            return companyCount
        case .indicators:
            return indicatorCount
        case .etfs:
            return etfCount
        }
    }
}

private struct WatchlistSectionHeader: View {
    let title: String
    let count: String
    @Binding var sort: WatchlistSort
    let marketFilter: Binding<WatchlistMarketFilter>?

    init(
        title: String,
        count: String,
        sort: Binding<WatchlistSort>,
        marketFilter: Binding<WatchlistMarketFilter>? = nil
    ) {
        self.title = title
        self.count = count
        self._sort = sort
        self.marketFilter = marketFilter
    }

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            if let marketFilter {
                CompactMarketFilter(selection: marketFilter)
            }
            Spacer(minLength: 6)
            Text(count)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            SortMenu(selection: $sort, compact: true)
        }
        .textCase(nil)
        .padding(.top, 2)
    }
}

private struct CompactMarketFilter: View {
    @Binding var selection: WatchlistMarketFilter

    var body: some View {
        HStack(spacing: 2) {
            ForEach(WatchlistMarketFilter.allCases) { filter in
                Button {
                    selection = filter
                } label: {
                    Text(filter.displayTitle)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(selection == filter ? AppTheme.accent : AppTheme.secondaryText)
                        .frame(minWidth: 32)
                        .padding(.vertical, 5)
                        .background(
                            RoundedRectangle(cornerRadius: 6)
                                .fill(selection == filter ? AppTheme.accent.opacity(0.12) : Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(filter.displayTitle) 시장 필터")
            }
        }
        .padding(2)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(AppTheme.elevatedCard)
        )
    }
}

private struct WatchlistUndoBanner: View {
    let item: WatchlistItem
    let undo: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "trash")
                .foregroundStyle(.orange)
            Text("\(item.name) 삭제됨")
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(1)
            Spacer()
            Button("되돌리기", action: undo)
                .font(.caption.weight(.semibold))
                .buttonStyle(.bordered)
            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("삭제 알림 닫기")
        }
        .padding(.vertical, 4)
    }
}

private struct WatchlistSyncBanner: View {
    let message: String
    let isSyncing: Bool
    let isSynced: Bool
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            if isSyncing {
                ProgressView()
                    .scaleEffect(0.8)
            } else if isSynced {
                Image(systemName: "checkmark.icloud")
                    .foregroundStyle(.green)
            } else {
                Image(systemName: "icloud.slash")
                    .foregroundStyle(.orange)
            }
            Text(message)
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
            Spacer()
            if !isSyncing && !isSynced {
                Button("재시도", action: retry)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct WatchIndicatorDataStatusCard: View {
    let isLoading: Bool
    let error: String?
    let hasData: Bool
    let retry: () -> Void

    private var title: String {
        if isLoading { return "관심 지수 데이터 동기화 중" }
        if error != nil { return "지수 데이터를 불러오지 못했습니다" }
        if !hasData { return "지수 데이터 대기 중" }
        return "지수 데이터 확인 필요"
    }

    private var detail: String {
        if isLoading {
            return "그래프와 현재가를 최신 지표로 맞추고 있습니다."
        }
        if let error {
            return error
        }
        return "관심 지수는 저장되어 있지만 그래프와 현재가 데이터가 아직 도착하지 않았습니다."
    }

    var body: some View {
        HStack(spacing: 12) {
            if isLoading {
                ProgressView()
                    .scaleEffect(0.85)
            } else {
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.headline)
                    .foregroundStyle(error == nil ? AppTheme.accent : .orange)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            if !isLoading {
                Button("새로고침", action: retry)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.bordered)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct WatchCompanyInsight {
    let title: String
    let detail: String
    let metrics: [String]
    let details: [String]
    let color: Color
    let systemImage: String
    let priority: Int
    let updatedAt: String?
    let isLinked: Bool
}

private struct WatchPriorityItem: Identifiable {
    let item: WatchlistItem
    let insight: WatchCompanyInsight

    var id: String { item.id }
}

private struct WatchJudgmentTimelineDisplayItem: Identifiable {
    let id: String
    let title: String
    let detail: String
    let source: String
    let recordedAt: Date?
    let color: Color
}

private struct WatchPriorityQueueCard: View {
    let items: [WatchPriorityItem]
    let itemCount: Int
    let isLoading: Bool
    let open: (WatchlistItem) -> Void
    let refresh: () -> Void

    private var visibleItems: [WatchPriorityItem] {
        Array(items.prefix(3))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("오늘 확인할 관심 3개")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(queueSummary)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Button(action: refresh) {
                    Image(systemName: isLoading ? "arrow.triangle.2.circlepath" : "arrow.clockwise")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(AppTheme.accent.opacity(0.10), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("오늘 확인할 관심 새로고침")
            }

            if visibleItems.isEmpty {
                WatchPriorityEmptyState(itemCount: itemCount, isLoading: isLoading)
            } else {
                VStack(spacing: 8) {
                    ForEach(visibleItems) { item in
                        WatchPriorityRow(priorityItem: item) {
                            open(item.item)
                        }
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }

    private var queueSummary: String {
        if visibleItems.isEmpty {
            return isLoading ? "관심 항목의 후보, 실적, 가격 데이터를 맞추고 있습니다." : "큰 변화가 없으면 감시만 유지합니다."
        }
        let urgentCount = items.filter { $0.insight.priority >= 3 }.count
        return urgentCount > 0 ? "지금 확인할 신호 \(urgentCount)개" : "가격, 실적, 후보 신호가 큰 항목부터 정렬했습니다."
    }
}

private struct WatchJudgmentTimelineCard: View {
    let items: [WatchJudgmentTimelineDisplayItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "bell.badge")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(AppTheme.accent)
                    .accessibilityHidden(true)
                Text("판단 업데이트 타임라인")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                Text("\(items.count)개")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
            }

            VStack(spacing: 8) {
                ForEach(items) { item in
                    WatchJudgmentTimelineRow(item: item)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .contain)
    }
}

private struct WatchJudgmentTimelineRow: View {
    let item: WatchJudgmentTimelineDisplayItem

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(item.color)
                .frame(width: 9, height: 9)
                .padding(.top, 7)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(item.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Spacer(minLength: 6)
                    Text(timeText)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(AppTheme.tertiaryText)
                        .lineLimit(1)
                }
                Text(item.detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(item.source)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(item.color)
                    .lineLimit(1)
            }
        }
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.70), in: RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(item.title). \(item.detail). \(timeText)")
    }

    private var timeText: String {
        guard let recordedAt = item.recordedAt else { return "현재" }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: recordedAt, relativeTo: Date())
    }
}

private struct WatchPriorityRow: View {
    let priorityItem: WatchPriorityItem
    let open: () -> Void

    private var item: WatchlistItem { priorityItem.item }
    private var insight: WatchCompanyInsight { priorityItem.insight }

    var body: some View {
        Button(action: open) {
            HStack(alignment: .top, spacing: 10) {
                WatchPriorityLeadingLogo(item: item, insight: insight)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(insight.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(insight.color)
                            .lineLimit(1)
                        Text(item.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                        Spacer(minLength: 6)
                        Text(priorityLabel(insight.priority))
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(insight.color)
                    }
                    Text(insight.detail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    if let thesisSummary = item.investmentThesis.inlineSummary {
                        Label(thesisSummary, systemImage: "lightbulb")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.accent)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if !insight.metrics.isEmpty {
                        HStack(spacing: 5) {
                            ForEach(insight.metrics, id: \.self) { metric in
                                Text(metric)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(AppTheme.primaryText)
                                    .lineLimit(1)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 4)
                                    .background(AppTheme.elevatedCard, in: Capsule())
                            }
                        }
                    }
                }

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.tertiaryText)
                    .padding(.top, 6)
            }
            .padding(10)
            .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }

    private func priorityLabel(_ value: Int) -> String {
        if value >= 4 { return "긴급" }
        if value >= 3 { return "확인" }
        if value >= 2 { return "관찰" }
        return "연결"
    }
}

private struct WatchPriorityLeadingLogo: View {
    let item: WatchlistItem
    let insight: WatchCompanyInsight

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            if item.isMarketIndicator {
                Image(systemName: insight.systemImage)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(insight.color)
                    .frame(width: 34, height: 34)
                    .background(insight.color.opacity(0.10), in: Circle())
            } else {
                CompanyLogoView(ticker: item.ticker, currency: item.currency, size: 34)
            }

            Image(systemName: insight.systemImage)
                .font(.system(size: 8, weight: .heavy))
                .foregroundStyle(.white)
                .frame(width: 16, height: 16)
                .background(insight.color, in: Circle())
                .overlay {
                    Circle()
                        .stroke(AppTheme.elevatedCard, lineWidth: 1.5)
                }
                .offset(x: 2, y: 2)
        }
        .frame(width: 38, height: 38)
    }
}

private struct WatchPriorityEmptyState: View {
    let itemCount: Int
    let isLoading: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            if isLoading {
                ProgressView()
                    .scaleEffect(0.8)
                    .frame(width: 28, height: 28)
            } else {
                Image(systemName: "checkmark.seal")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AppTheme.quality)
                    .frame(width: 28, height: 28)
                    .background(AppTheme.quality.opacity(0.10), in: Circle())
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(isLoading ? "신호 계산 중" : "큰 변화 없음")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(isLoading ? "관심 항목 \(itemCount)개를 최신 데이터와 연결하고 있습니다." : "관심 항목 \(itemCount)개를 계속 감시합니다.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct WatchlistRow: View {
    let item: WatchlistItem
    let insight: WatchCompanyInsight
    let decision: InvestmentDecisionRecord?
    let indicatorQuote: MarketIndicatorQuote?

    init(item: WatchlistItem, insight: WatchCompanyInsight, decision: InvestmentDecisionRecord? = nil, indicatorQuote: MarketIndicatorQuote? = nil) {
        self.item = item
        self.insight = insight
        self.decision = decision
        self.indicatorQuote = indicatorQuote
    }

    private var priceText: String {
        if let indicatorQuote {
            return watchIndicatorValueText(indicatorQuote.value)
        }
        return insight.details.first { $0.hasPrefix("가격 ") }?.replacingOccurrences(of: "가격 ", with: "") ?? "-"
    }

    private var moveText: String? {
        if let change = indicatorQuote?.changePct, change.isFinite {
            return pct(change, signed: true)
        }
        if let daily = insight.details.first(where: { $0.hasPrefix("하루 ") }) {
            return daily.replacingOccurrences(of: "하루 ", with: "")
        }
        return insight.details.first { $0.hasPrefix("1개월 ") }?.replacingOccurrences(of: "1개월 ", with: "")
    }

    private var categoryText: String {
        if item.isMarketIndicator {
            return item.market
        }
        return insight.details.first {
            !$0.hasPrefix("가격 ") &&
                !$0.hasPrefix("하루 ") &&
                !$0.hasPrefix("1개월 ") &&
                !$0.hasPrefix("시총 ") &&
                !$0.hasPrefix("실적 ")
        } ?? (item.note.isEmpty ? item.market : item.note)
    }

    private var moveColor: Color {
        guard let moveText else { return AppTheme.secondaryText }
        if moveText.trimmingCharacters(in: .whitespaces).hasPrefix("-") { return AppTheme.negative }
        if moveText.trimmingCharacters(in: .whitespaces).hasPrefix("+") { return AppTheme.positive }
        return AppTheme.secondaryText
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            if item.isMarketIndicator {
                MarketIndicatorLogoView(ticker: item.ticker, name: item.name, size: 34, accent: AppTheme.accent)
            } else {
                CompanyLogoView(ticker: item.ticker, currency: item.currency)
            }

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(item.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    TickerBadge(ticker: item.ticker)
                }

                Text(categoryText)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)

                HStack(spacing: 5) {
                    Image(systemName: insight.systemImage)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(insight.color)
                    Text(insight.title)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(insight.color)
                        .lineLimit(1)
                    Text(insight.detail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }

                WatchMetadataInlineSummary(item: item)
                InvestmentDecisionInlineSummary(record: decision)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer()

            VStack(alignment: .trailing, spacing: 4) {
                AnimatedPriceText(
                    text: priceText,
                    font: .subheadline.monospacedDigit().weight(.bold),
                    color: AppTheme.primaryText
                )
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                if let moveText {
                    Text(moveText)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(moveColor)
                        .lineLimit(1)
                }
                if !item.isMarketIndicator {
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(AppTheme.tertiaryText)
                        .padding(.top, 2)
                }
            }
            .frame(width: 76, alignment: .trailing)
        }
        .padding(.vertical, 8)
    }
}

private struct WatchInsightDetailGrid: View {
    let details: [String]

    private let columns = [GridItem(.adaptive(minimum: 92), spacing: 6)]

    var body: some View {
        if !details.isEmpty {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
                ForEach(details, id: \.self) { detail in
                    Text(detail)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
                }
            }
        }
    }
}

private struct WatchMetadataInlineSummary: View {
    let item: WatchlistItem

    private var thesis: WatchInvestmentThesis {
        item.investmentThesis
    }

    var body: some View {
        if !item.tags.isEmpty || !item.alertOptions.isEmpty || !thesis.isEmpty {
            VStack(alignment: .leading, spacing: 5) {
                if !thesis.isEmpty {
                    Label("가설 \(thesis.quality.percent)%", systemImage: thesis.quality.percent >= 80 ? "checkmark" : "scope")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(thesis.quality.percent >= 80 ? AppTheme.quality : AppTheme.warning)
                        .lineLimit(1)
                }
                if let summary = thesis.inlineSummary {
                    Label(summary, systemImage: "lightbulb")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.accent)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let reviewSummary = thesis.reviewSummary {
                    Label("복기 \(reviewSummary)", systemImage: "calendar.badge.clock")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.accent)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 5) {
                    ForEach(Array(item.tags.prefix(3)), id: \.self) { tag in
                        Text(tag)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 4)
                            .background(AppTheme.elevatedCard, in: Capsule())
                    }
                    if !item.alertOptions.isEmpty {
                        Label(watchAlertDisplayLabel(item.alertOptions.first ?? "알림"), systemImage: "bell")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.warning)
                            .lineLimit(1)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 4)
                            .background(AppTheme.warning.opacity(0.10), in: Capsule())
                    }
                }
            }
        }
    }
}

private struct WatchIndicatorGraphCard: View {
    let item: WatchlistItem
    let quote: MarketIndicatorQuote
    let points: [MarketIndicatorPoint]
    let delete: () -> Void

    private var moveColor: Color {
        (quote.changePct ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
    }

    var body: some View {
        let chartPoints = displayMarketIndicatorPoints(for: quote, points: points)

        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 6) {
                MarketIndicatorLogoView(ticker: item.ticker, name: item.name, size: 32, accent: AppTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                    Text(watchIndicatorValueLine(quote))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(moveColor)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                        .monospacedDigit()
                }
                Spacer(minLength: 0)
                Button(action: delete) {
                    Image(systemName: "heart.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.yellow)
                        .frame(width: 26, height: 26)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(item.name) 관심 지수 삭제")
            }

            if chartPoints.isEmpty {
                WatchIndicatorGraphPlaceholder()
                    .frame(height: 70)
            } else {
                IndicatorSparkline(item: quote, points: chartPoints, color: moveColor)
                    .frame(height: 70)
            }

            Text(watchIndicatorDescription(item: item, quote: quote))
                .font(.system(size: 12))
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            if quote.value.isFinite {
                Text("갱신 \(formattedUpdateTimestamp(quote.updatedAt))")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                    .monospacedDigit()
            }
        }
        .padding(12)
        .frame(minHeight: 172, alignment: .top)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(AppTheme.card)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(AppTheme.hairline, lineWidth: 1)
        )
    }
}

private struct WatchIndicatorGraphPlaceholder: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 8)
            .fill(AppTheme.elevatedCard)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(AppTheme.hairline, lineWidth: 1)
            )
            .overlay {
                Text("그래프 대기")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
            }
    }
}

private struct WatchlistIndicatorAvatar: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(AppTheme.accent.opacity(0.12))
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.accent)
        }
        .frame(width: 44, height: 44)
    }
}

private func watchIndicatorDescription(item: WatchlistItem, quote: MarketIndicatorQuote) -> String {
    let symbol = canonicalMarketIndicatorSymbol(for: item)
    switch symbol {
    case "^IXIC":
        return "미국 기술주 중심 나스닥 종합지수"
    case "NQ=F":
        return "나스닥 100 지수의 선물 가격"
    case "^GSPC":
        return "미국 대형주 500개 대표 지수"
    case "ES=F":
        return "S&P 500 지수의 선물 가격"
    case "RTY=F":
        return "미국 중소형주 러셀 2000 선물"
    case "^DJI":
        return "미국 대표 우량주 다우존스 지수"
    case "^SOX":
        return "미국 반도체 업종 대표 지수"
    case "^VIX":
        return "S&P 500 옵션 기반 변동성 지수"
    case "^KS11":
        return "한국 유가증권시장 대표 지수"
    case "^KQ11":
        return "한국 코스닥시장 대표 지수"
    case "KRW=X":
        return "원/달러 환율 흐름"
    case "DX-Y.NYB":
        return "주요 통화 대비 달러 가치 지수"
    case "^IRX", "^FVX", "^TNX", "^TYX":
        return "미국 국채 금리 지표"
    case "IRR_GOVT03Y":
        return "한국 국고채 3년 금리 지표"
    case "IRR_CORP03Y":
        return "한국 회사채 3년 금리 지표"
    case "GC=F", "SI=F", "CL=F", "HG=F":
        return "\(item.name) 원자재 선물 가격"
    case "BTC-USD", "ETH-USD", "SOL-USD":
        return "\(item.name) 달러 기준 가상자산 가격"
    default:
        if quote.category == "crypto" { return "\(item.name) 가상자산 가격" }
        if quote.category == "commodity" { return "\(item.name) 원자재 가격" }
        if quote.category == "bond" { return "\(item.name) 금리 지표" }
        return "\(item.market) 시장 지수"
    }
}

private func watchIndicatorCategory(item: WatchlistItem) -> String {
    let symbol = canonicalMarketIndicatorSymbol(for: item)
    if ["GC=F", "SI=F", "CL=F", "HG=F"].contains(symbol) { return "commodity" }
    if ["BTC-USD", "ETH-USD", "SOL-USD"].contains(symbol) { return "crypto" }
    if ["^IRX", "^FVX", "^TNX", "^TYX", "IRR_GOVT03Y", "IRR_CORP03Y"].contains(symbol) { return "bond" }
    switch item.market {
    case "원자재":
        return "commodity"
    case "가상자산":
        return "crypto"
    default:
        return "index_fx"
    }
}

private func watchIndicatorRegion(item: WatchlistItem, symbol: String) -> String {
    if ["^KS11", "^KQ11", "KRW=X", "IRR_GOVT03Y", "IRR_CORP03Y"].contains(symbol) {
        return "domestic"
    }
    return item.market == "KR" ? "domestic" : "overseas"
}

private func watchIndicatorValueText(_ value: Double) -> String {
    guard value.isFinite else { return "-" }
    if abs(value) >= 100 { return String(format: "%.2f", value) }
    if abs(value) >= 1 { return String(format: "%.3f", value) }
    return String(format: "%.4g", value)
}

private func watchIndicatorValueLine(_ quote: MarketIndicatorQuote) -> String {
    guard quote.value.isFinite else { return "데이터 대기" }
    return "\(watchIndicatorValueText(quote.value)) · \(pct(quote.changePct, signed: true))"
}

private func compactDateText(_ value: String?) -> String {
    let text = formattedUpdateTimestamp(value)
    guard text != "-" else { return "-" }
    return String(text.prefix(10))
}

private func earningsCalendarDayText(_ days: Int?) -> String {
    guard let days else { return "D-?" }
    if days == 0 { return "D-Day" }
    if days > 0 { return "D-\(days)" }
    return "D+\(abs(days))"
}

private func multipleText(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    return String(format: "x%.1f", value)
}
