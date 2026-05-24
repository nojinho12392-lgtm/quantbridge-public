import Charts
import SwiftUI

private enum StockDetailTab: String, CaseIterable, Identifiable, Hashable {
    case overview = "요약"
    case chart = "차트"
    case holdings = "구성종목"
    case financial = "재무"
    case data = "데이터"

    var id: String { rawValue }
}

private struct InvestmentDecisionEditorContext: Identifiable {
    let id = UUID()
    let record: InvestmentDecisionRecord?
}

struct StockDetailSheet: View {
    let ticker: String
    let name: String
    let currency: String
    let staticMetrics: [StaticMetric]
    let investmentSignals: [InvestmentSignal]
    let etfHoldings: [ETFHolding]

    @StateObject private var vm = StockDetailVM()
    @State private var period = "6mo"
    @State private var reloadTrigger = 0
    @State private var selectedIndex: Int?
    @State private var showCloseLine = false
    @State private var showMA5 = true
    @State private var showMA20 = false
    @State private var showMA120 = true
    @State private var showBollinger = true
    @State private var showTrendChannel = false
    @State private var showSupportResistance = false
    @State private var showVolume = true
    @State private var showRSI = true
    @State private var showMACD = false
    @State private var selectedGlossaryTerm: GlossaryTerm?
    @State private var selectedTab: StockDetailTab = .overview
    @State private var editingWatchItem: WatchlistItem?
    @State private var editingDecision: InvestmentDecisionEditorContext?
    @State private var showComparePicker = false
    @State private var showComparisonSheet = false
    @State private var selectedHoldingDetail: StockDetailSelection?
    @State private var resolvingHoldingID: String?
    @State private var investmentProfile = InvestmentProfile.load()
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var comparison: ComparisonStore
    @EnvironmentObject private var decisions: InvestmentDecisionStore

    private let periods = [("1달", "1mo"), ("3달", "3mo"), ("6달", "6mo"), ("1년", "1y"), ("3년", "3y"), ("5년", "5y")]

    private var isETFDetail: Bool {
        !etfHoldings.isEmpty ||
        name.localizedCaseInsensitiveContains("ETF") ||
        ticker.localizedCaseInsensitiveContains("ETF") ||
        investmentSignals.contains { signal in
            signal.title.localizedCaseInsensitiveContains("ETF") ||
            signal.detail.localizedCaseInsensitiveContains("ETF")
        } ||
        staticMetrics.contains { metric in
            ["총보수", "AUM", "운용규모", "운용 규모"].contains(metric.label)
        }
    }

    private var availableTabs: [StockDetailTab] {
        isETFDetail ? [.chart, .holdings, .overview, .data] : [.overview, .chart, .financial, .data]
    }

    private var isInitialDetailLoading: Bool {
        if case .loading = vm.state {
            return vm.pricePoints.isEmpty && vm.info == nil
        }
        return false
    }

    private var blockingErrorMessage: String? {
        if case .failed(let error, nil) = vm.state {
            return error
        }
        return nil
    }

    private var staleErrorMessage: String? {
        if case .failed(let error, .some) = vm.state {
            return error
        }
        return nil
    }

    init(
        ticker: String,
        name: String,
        currency: String,
        staticMetrics: [StaticMetric],
        investmentSignals: [InvestmentSignal] = [],
        etfHoldings: [ETFHolding] = [],
        startsOnChart: Bool = false
    ) {
        self.ticker = ticker
        self.name = name
        self.currency = currency
        self.staticMetrics = staticMetrics
        self.investmentSignals = investmentSignals
        self.etfHoldings = etfHoldings
        let startsAsETF = !etfHoldings.isEmpty ||
            name.localizedCaseInsensitiveContains("ETF") ||
            ticker.localizedCaseInsensitiveContains("ETF") ||
            investmentSignals.contains { signal in
                signal.title.localizedCaseInsensitiveContains("ETF") ||
                signal.detail.localizedCaseInsensitiveContains("ETF")
            } ||
            staticMetrics.contains { metric in
                ["총보수", "AUM", "운용규모", "운용 규모"].contains(metric.label)
            }
        _selectedTab = State(initialValue: startsOnChart || startsAsETF ? .chart : .overview)
    }

    private var selectedPoint: PricePoint? {
        guard let selectedIndex, vm.pricePoints.indices.contains(selectedIndex) else {
            return vm.pricePoints.last
        }
        return vm.pricePoints[selectedIndex]
    }

    private var displayStaticMetrics: [StaticMetric] {
        reconciledStaticMetrics(staticMetrics, info: vm.info, currency: currency)
    }

    private var visibleGlossaryTerms: [GlossaryTerm] {
        var labels = displayStaticMetrics.map(\.label)
        if isETFDetail {
            labels.append(contentsOf: ["MDD", "리스크 기여도"])
            return Array(glossaryTerms(for: labels).prefix(8))
        }
        if vm.info?.peRatio != nil { labels.append("PER") }
        if vm.info?.forwardPe != nil { labels.append("Forward PER") }
        if vm.info?.priceToBook != nil { labels.append("P/B") }
        if vm.info?.priceToSales != nil { labels.append("P/S") }
        if vm.info?.beta != nil { labels.append("베타") }
        if vm.info?.returnOnEquity != nil { labels.append("ROE") }
        if vm.info?.freeCashflow != nil { labels.append("FCF") }
        if vm.info?.debtToEquity != nil { labels.append("Debt/Equity") }
        labels.append(contentsOf: ["PER", "ROIC", "FCF", "MDD", "리스크 기여도", "AI 보정"])
        return Array(glossaryTerms(for: labels).prefix(10))
    }

    var body: some View {
        let displayMetrics = displayStaticMetrics
        let isCompared = comparison.contains(ticker)
        let currentWatchItem = watchlist.item(for: ticker)
        let currentDecision = decisions.record(for: ticker)
        ZStack {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        DetailTopDecisionCard(
                            ticker: ticker,
                            name: name,
                            currency: currency,
                            info: vm.info,
                            isLoading: vm.isLoading,
                            staticMetrics: displayMetrics,
                            signals: investmentSignals,
                            isETF: isETFDetail,
                            source: vm.source,
                            updatedAt: vm.updatedAt,
                            isWatched: watchlist.contains(ticker),
                            isCompared: isCompared,
                            canShowComparison: comparison.canCompare,
                            toggleWatch: toggleWatch,
                            editMemo: openWatchMetadata,
                            compare: addComparisonFromDetail
                        )
                        InvestmentDecisionSummarySection(
                            ticker: ticker,
                            name: name,
                            market: currency == "KRW" ? "KR" : "US",
                            currency: currency,
                            profile: investmentProfile,
                            info: vm.info,
                            staticMetrics: displayMetrics,
                            signals: investmentSignals,
                            record: currentDecision,
                            edit: {
                                editingDecision = InvestmentDecisionEditorContext(record: decisions.record(for: ticker))
                            }
                        )
                        if isInitialDetailLoading {
                            StockDetailLoadingStateView()
                                .padding(.horizontal)
                        } else if let blockingErrorMessage {
                            StockDetailErrorStateView(error: blockingErrorMessage, retry: reloadDetail)
                                .padding(.horizontal)
                        } else {
                            if let reason = vm.partialReason {
                                PartialDataBadge(reason: reason)
                                    .padding(.horizontal)
                            }
                            if let staleErrorMessage {
                                StaleDataBadge(error: staleErrorMessage)
                                    .padding(.horizontal)
                            }
                            if let errorMsg = vm.errorMsg, !vm.isLoading {
                                InlineWarningBanner(msg: errorMsg, retry: reloadDetail)
                                    .appCard(padding: 12)
                                    .padding(.horizontal)
                            }
                            StockDetailTabPicker(selection: $selectedTab, tabs: availableTabs)

                            switch selectedTab {
                            case .overview:
                                DetailSummarySection(
                                    info: vm.info,
                                    updatedAt: vm.updatedAt,
                                    currency: currency,
                                    isETF: isETFDetail,
                                    onTermSelected: { selectedGlossaryTerm = $0 }
                                )
                                GlossaryChipStrip(
                                    terms: visibleGlossaryTerms,
                                    onSelect: { selectedGlossaryTerm = $0 }
                                )
                                DetailComparisonGuardSection(
                                    name: name,
                                    comparisonCount: comparison.items.count,
                                    isCompared: isCompared,
                                    openCompare: addComparisonFromDetail
                                )
                                DetailDecisionBriefSection(
                                    info: vm.info,
                                    staticMetrics: displayMetrics,
                                    signals: investmentSignals,
                                    currency: currency,
                                    updatedAt: vm.updatedAt,
                                    isETF: isETFDetail
                                )
                                DetailJudgementMatrixSection(
                                    info: vm.info,
                                    staticMetrics: displayMetrics,
                                    signals: investmentSignals,
                                    currency: currency,
                                    isETF: isETFDetail
                                )
                                DetailActionPlanSection(
                                    info: vm.info,
                                    staticMetrics: displayMetrics,
                                    signals: investmentSignals,
                                    currency: currency,
                                    isETF: isETFDetail
                                )
                                DetailMistakeCoachSection(
                                    profile: investmentProfile,
                                    name: name,
                                    info: vm.info,
                                    staticMetrics: displayMetrics,
                                    signals: investmentSignals,
                                    watchItem: currentWatchItem,
                                    isCompared: isCompared
                                )
                                InvestmentProfileFitSection(
                                    profile: investmentProfile,
                                    staticMetrics: displayMetrics,
                                    signals: investmentSignals
                                )
                                if !isETFDetail {
                                    EarningsEventPlanSection(
                                        staticMetrics: displayMetrics,
                                        signals: investmentSignals
                                    )
                                }
                                ScoreRationaleSection(
                                    metrics: displayMetrics,
                                    signals: investmentSignals,
                                    onTermSelected: { selectedGlossaryTerm = $0 }
                                )
                                MissingDataNoticeSection(info: vm.info, staticMetrics: displayMetrics, isETF: isETFDetail)
                                RangeSection(info: vm.info, currency: currency)
                                InvestmentRationaleSection(signals: investmentSignals)
                                StaticMetricsSection(metrics: displayMetrics, onTermSelected: { selectedGlossaryTerm = $0 })
                                if !isETFDetail {
                                    CompanyProfileSection(info: vm.info)
                                }
                            case .chart:
                                ChartSection(
                                    periods: periods,
                                    period: $period,
                                    points: vm.pricePoints,
                                    indicators: vm.indicators,
                                    enabledPeriods: vm.enabledChartPeriods,
                                    selectedIndex: $selectedIndex,
                                    selectedPoint: selectedPoint,
                                    isLoading: vm.isLoading,
                                    currency: currency,
                                    showCloseLine: $showCloseLine,
                                    showMA5: $showMA5,
                                    showMA20: $showMA20,
                                    showMA120: $showMA120,
                                    showBollinger: $showBollinger,
                                    showTrendChannel: $showTrendChannel,
                                    showSupportResistance: $showSupportResistance,
                                    showVolume: $showVolume,
                                    showRSI: $showRSI,
                                    showMACD: $showMACD
                                )
                            case .holdings:
                                ETFHoldingBreakdownSection(
                                    holdings: etfHoldings,
                                    resolvingHoldingID: resolvingHoldingID,
                                    onSelectHolding: openHoldingDetail
                                )
                                    .padding(.horizontal)
                            case .financial:
                                if !isETFDetail {
                                    ExtraInfoSection(info: vm.info, currency: currency, onTermSelected: { selectedGlossaryTerm = $0 })
                                    FinancialSnapshotSection(info: vm.info, currency: currency, onTermSelected: { selectedGlossaryTerm = $0 })
                                    MissingDataNoticeSection(info: vm.info, staticMetrics: displayMetrics)
                                }
                            case .data:
                                DataMetaSection(source: vm.source, updatedAt: vm.updatedAt)
                                DescriptionSection(description: vm.info?.description)
                            }
                        }
                    }
                    .padding(.bottom, 32)
                }
                .scrollContentBackground(.hidden)
                .appScreenBackground()
                .navigationTitle(name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: reloadDetail) {
                            Image(systemName: "arrow.clockwise")
                        }
                        .disabled(vm.isLoading)
                        .accessibilityLabel("다시 불러오기")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: toggleWatch) {
                            Image(systemName: watchlist.contains(ticker) ? "heart.fill" : "heart")
                                .foregroundStyle(watchlist.contains(ticker) ? .yellow : .primary)
                        }
                        .accessibilityLabel(watchlist.contains(ticker) ? "관심 종목 제거" : "관심 종목 추가")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("닫기") { dismiss() }
                    }
                }
            }

            if let term = selectedGlossaryTerm {
                GlossaryTermDialog(term: term) {
                    selectedGlossaryTerm = nil
                }
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
                .zIndex(1)
            }
        }
        .animation(.easeOut(duration: 0.16), value: selectedGlossaryTerm)
        .onAppear {
            investmentProfile = InvestmentProfile.load()
        }
        .task(id: "\(period):\(reloadTrigger)") {
            await vm.load(ticker: ticker, period: period, forceRefresh: reloadTrigger > 0)
        }
        .task(id: "detail-price-auto-\(ticker)-\(period)") {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.standardPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("detail-\(ticker)-\(period)", minInterval: 120) else { continue }
                await vm.load(ticker: ticker, period: period, forceRefresh: true)
            }
        }
        .sheet(item: $editingWatchItem) { item in
            WatchMetadataSheet(item: item) { updated in
                watchlist.updateMetadata(
                    ticker: updated.ticker,
                    tags: updated.tags,
                    memo: updated.memo,
                    alertOptions: updated.alertOptions
                )
            }
        }
        .sheet(item: $editingDecision) { context in
            InvestmentDecisionSheet(
                ticker: ticker,
                name: name,
                market: currency == "KRW" ? "KR" : "US",
                currency: currency,
                profile: investmentProfile,
                info: vm.info,
                staticMetrics: displayStaticMetrics,
                signals: investmentSignals,
                record: context.record,
                onSave: { decisions.save($0) },
                onDelete: context.record == nil ? nil : {
                    decisions.delete(ticker)
                }
            )
            .id("\(ticker)-\(context.id)")
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showComparisonSheet) {
            StockComparisonSheet(items: comparison.items)
                .presentationDetents([.fraction(0.8), .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showComparePicker) {
            ComparisonTargetPickerSheet(anchor: detailComparisonItem) {
                showComparePicker = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                    showComparisonSheet = true
                }
            }
            .environmentObject(comparison)
            .environmentObject(watchlist)
            .presentationDetents([.fraction(0.8), .large])
            .presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: $selectedHoldingDetail) { detail in
            StockDetailSheet(
                ticker: detail.ticker,
                name: detail.name,
                currency: detail.currency,
                staticMetrics: detail.metrics,
                investmentSignals: detail.signals
            )
        }
    }

    private func reloadDetail() {
        reloadTrigger += 1
    }

    private func toggleWatch() {
        watchlist.toggle(watchlistItem(
            ticker: ticker,
            name: name,
            market: nil,
            currency: currency,
            note: isETFDetail ? "ETF" : "Detail"
        ))
    }

    private func openWatchMetadata() {
        if let item = watchlist.item(for: ticker) {
            editingWatchItem = item
            return
        }
        let item = watchlistItem(
            ticker: ticker,
            name: name,
            market: nil,
            currency: currency,
            note: isETFDetail ? "ETF" : "Detail"
        )
        watchlist.toggle(item)
        editingWatchItem = item
    }

    private func addComparisonFromDetail() {
        showComparePicker = true
    }

    private func openHoldingDetail(_ holding: ETFHolding) {
        resolvingHoldingID = holding.id
        Task {
            let detail = await holdingDetailSelection(for: holding)
            await MainActor.run {
                guard resolvingHoldingID == holding.id else { return }
                selectedHoldingDetail = detail
                resolvingHoldingID = nil
            }
        }
    }

    private func holdingDetailSelection(for holding: ETFHolding) async -> StockDetailSelection {
        let fallback = fallbackHoldingDetailSelection(for: holding)
        let query = holding.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? holding.ticker : holding.name
        guard !query.isEmpty else { return fallback }
        do {
            let response = try await APIClient.shared.searchUniverse(query: query, limit: 8)
            if let match = bestHoldingSearchMatch(response.stocks, holding: holding) {
                return holdingSearchDetail(match, holding: holding)
            }
        } catch {
            return fallback
        }
        return fallback
    }

    private func fallbackHoldingDetailSelection(for holding: ETFHolding) -> StockDetailSelection {
        let resolvedTicker = fallbackTicker(for: holding)
        let market = holdingMarket(for: resolvedTicker, fallbackName: holding.name)
        let resolvedCurrency = marketCurrency(for: resolvedTicker, market: market)
        return StockDetailSelection(
            ticker: resolvedTicker,
            name: holding.name.isEmpty ? resolvedTicker : holding.name,
            currency: resolvedCurrency,
            metrics: [
                StaticMetric(label: "ETF 내 비중", value: pct(holding.weight, signed: false), color: AppTheme.accent),
                StaticMetric(label: "시장", value: market)
            ],
            signals: [
                InvestmentSignal(
                    title: "ETF 구성종목",
                    detail: "\(name)에 \(pct(holding.weight, signed: false)) 비중으로 포함된 종목입니다.",
                    systemImage: "chart.pie.fill",
                    color: AppTheme.accent
                )
            ]
        )
    }

    private func holdingSearchDetail(_ stock: SearchStock, holding: ETFHolding) -> StockDetailSelection {
        let resolvedCurrency = stock.currency ?? marketCurrency(for: stock.ticker, market: stock.market)
        return StockDetailSelection(
            ticker: stock.ticker,
            name: stock.name,
            currency: resolvedCurrency,
            metrics: [
                StaticMetric(label: "ETF 내 비중", value: pct(holding.weight, signed: false), color: AppTheme.accent),
                StaticMetric(label: "시장", value: stock.market ?? "-"),
                StaticMetric(label: "섹터", value: stock.sector.map { portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: $0) } ?? "-"),
                StaticMetric(label: "시가총액", value: cap(stock.marketCap, currency: resolvedCurrency))
            ],
            signals: [
                InvestmentSignal(
                    title: "ETF 구성종목",
                    detail: "\(name)에 \(pct(holding.weight, signed: false)) 비중으로 포함된 종목입니다.",
                    systemImage: "chart.pie.fill",
                    color: AppTheme.accent
                )
            ]
        )
    }

    private var detailComparisonItem: StockComparisonItem {
        StockComparisonItem(
            ticker: ticker,
            name: name,
            market: currency == "KRW" ? "KR" : "US",
            sector: vm.info?.sector ?? metricValue("섹터"),
            currency: currency,
            source: "Detail",
            expectedReturn: percentMetricValue(["기대수익", "Expected Return", "Exp.Ret"]),
            revenueGrowth: vm.info?.revenueGrowth ?? percentMetricValue(["매출성장", "Rev", "Revenue"]),
            roic: percentMetricValue(["ROIC"]),
            grossMargin: vm.info?.grossMargin ?? percentMetricValue(["마진", "GrossMargin"]),
            marketCap: vm.info?.marketCap,
            currentPrice: vm.info?.currentPrice,
            updatedAt: vm.updatedAt
        )
    }

    private func metricValue(_ label: String) -> String? {
        staticMetrics.first { $0.label == label }?.value
    }

    private func percentMetricValue(_ labels: [String]) -> Double? {
        for label in labels {
            guard let raw = staticMetrics.first(where: { $0.label == label })?.value else { continue }
            let cleaned = raw
                .replacingOccurrences(of: "%", with: "")
                .replacingOccurrences(of: "+", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let value = Double(cleaned), value.isFinite {
                return value / 100
            }
        }
        return nil
    }
}

private struct StockDetailLoadingStateView: View {
    var body: some View {
        VStack(spacing: 10) {
            ProgressView()
                .tint(AppTheme.accent)
            Text("상세 데이터를 불러오는 중입니다")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
            Text("차트, 밸류에이션, 최신 가격을 한 번에 정리하고 있어요.")
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .appCard(padding: 18, role: .status)
    }
}

private struct StockDetailErrorStateView: View {
    let error: String
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                LucideIconView(icon: .triangleAlert, size: 18)
                    .foregroundStyle(AppTheme.warning)
                Text("상세 데이터를 표시할 수 없습니다")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            Text(error)
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: retry) {
                Label("다시 불러오기", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.accent)
        }
        .appCard(padding: 16, role: .status)
    }
}

private struct PartialDataBadge: View {
    let reason: PartialReason

    var body: some View {
        StockDetailStatusBadge(
            icon: "exclamationmark.triangle.fill",
            title: "데이터 부분적",
            message: reason.message,
            tint: reason.tint
        )
    }
}

private struct StaleDataBadge: View {
    let error: String

    var body: some View {
        StockDetailStatusBadge(
            icon: "clock.badge.exclamationmark.fill",
            title: "마지막 성공 데이터 표시 중",
            message: error,
            tint: AppTheme.warning
        )
    }
}

private struct StockDetailStatusBadge: View {
    let icon: String
    let title: String
    let message: String
    let tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 24, height: 24)
                .background(tint.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .appCard(padding: 12, role: .status)
    }
}

private extension PartialReason {
    var message: String {
        switch self {
        case .storageSnapshotOnly:
            return "서버 저장소의 스냅샷만 먼저 표시합니다. 가격 시리즈나 일부 지표가 비어 있을 수 있습니다."
        case .insufficientHistory:
            return "요청한 기간보다 저장된 차트 구간이 짧습니다. 가능한 구간을 먼저 보여주고 최신 데이터를 보강합니다."
        case .valuationMissing:
            return "PER, PBR 같은 밸류에이션 지표가 아직 비어 있습니다. 가격과 기본 정보는 먼저 확인할 수 있습니다."
        }
    }

    var tint: Color {
        switch self {
        case .storageSnapshotOnly:
            return AppTheme.info
        case .insufficientHistory:
            return AppTheme.warning
        case .valuationMissing:
            return AppTheme.momentum
        }
    }
}

private func reconciledStaticMetrics(_ metrics: [StaticMetric], info: StockInfo?, currency: String) -> [StaticMetric] {
    guard let info, let current = info.currentPrice, current.isFinite else {
        return metrics
    }

    var result = metrics
    let priceLabels = ["현재가", "최근가", "가격", "Price", "Last Price"]
    let dayLabels = ["오늘", "전장", "당일 흐름", "하루 변동률", "일간", "일일", "1D", "Today", "Daily"]
    let dayLabel = {
        let value = info.dailyChangeHorizon?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? "오늘" : value
    }()
    let priceMetric = StaticMetric(label: "현재가", value: fmtPx(current, currency: currency), color: AppTheme.primaryText)
    let changePct = info.dailyChangePct ?? info.prevClose
        .flatMap { previous -> Double? in
            guard previous.isFinite, previous != 0 else { return nil }
            return (current - previous) / previous
        }
    let todayMetric = changePct.map {
        StaticMetric(label: dayLabel, value: pct($0), color: $0 >= 0 ? AppTheme.positive : AppTheme.negative)
    }

    func replaceOrInsert(labels: [String], metric: StaticMetric, fallbackIndex: Int) {
        if let index = result.firstIndex(where: { metricLabel($0.label, matchesAnyOf: labels) }) {
            let label = metric.label == "오늘" ? result[index].label : metric.label
            result[index] = StaticMetric(label: label, value: metric.value, color: metric.color)
        } else {
            result.insert(metric, at: min(fallbackIndex, result.count))
        }
    }

    replaceOrInsert(labels: priceLabels, metric: priceMetric, fallbackIndex: 0)
    if let todayMetric {
        replaceOrInsert(labels: dayLabels, metric: todayMetric, fallbackIndex: min(1, result.count))
    } else if let index = result.firstIndex(where: { metricLabel($0.label, matchesAnyOf: dayLabels) }) {
        result[index] = StaticMetric(label: result[index].label, value: "-", color: AppTheme.secondaryText)
    }
    return result
}

private func metricLabel(_ label: String, matchesAnyOf candidates: [String]) -> Bool {
    let normalized = label
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
    return candidates.contains { candidate in
        let clean = candidate.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return normalized == clean || normalized.contains(clean)
    }
}

private func bestHoldingSearchMatch(_ stocks: [SearchStock], holding: ETFHolding) -> SearchStock? {
    let targetTicker = normalizedHoldingText(fallbackTicker(for: holding))
    let targetCode = krCode(from: holding.ticker)
    let targetName = normalizedHoldingText(holding.name)
    return stocks.first { normalizedHoldingText($0.ticker) == targetTicker }
        ?? stocks.first { !targetCode.isEmpty && krCode(from: $0.ticker) == targetCode }
        ?? stocks.first { normalizedHoldingText($0.name) == targetName }
        ?? stocks.first
}

private func fallbackTicker(for holding: ETFHolding) -> String {
    let raw = holding.ticker.trimmingCharacters(in: .whitespacesAndNewlines)
    let code = krCode(from: raw)
    if !code.isEmpty, !raw.localizedCaseInsensitiveContains(".KS"), !raw.localizedCaseInsensitiveContains(".KQ") {
        return "\(code).KS"
    }
    return raw.isEmpty ? holding.name : raw
}

private func holdingMarket(for ticker: String, fallbackName: String) -> String {
    if marketCurrency(for: ticker) == "KRW" || containsHangul(ticker) || containsHangul(fallbackName) {
        return "KR"
    }
    return "US"
}

private func normalizedHoldingText(_ value: String) -> String {
    value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
}

private func containsHangul(_ value: String) -> Bool {
    value.range(of: #"[가-힣]"#, options: .regularExpression) != nil
}

private struct ETFHoldingBreakdownSection: View {
    let holdings: [ETFHolding]
    let resolvingHoldingID: String?
    let onSelectHolding: (ETFHolding) -> Void

    private var visibleHoldings: [ETFHolding] {
        Array(holdings.prefix(10))
    }

    private var visibleWeightTotal: Double {
        visibleHoldings.reduce(0) { $0 + $1.weight }
    }

    private var otherWeight: Double {
        max(0, 1 - visibleWeightTotal)
    }

    private var shouldShowOther: Bool {
        otherWeight > 0.0001
    }

    var body: some View {
        if visibleHoldings.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("보유 비중 Top10")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text("구성 종목 데이터가 도착하면 원형 그래프와 상위 보유비중을 표시합니다.")
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.secondaryText)
            }
            .appCard(padding: 16)
        } else {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("보유 비중 Top10")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(shouldShowOther
                         ? "상위 \(visibleHoldings.count)개 합산 \(pct(visibleWeightTotal, signed: false)) · 기타 \(pct(otherWeight, signed: false))"
                         : "상위 \(visibleHoldings.count)개 기준 · 합산 \(pct(visibleWeightTotal, signed: false))")
                        .font(.subheadline)
                        .foregroundStyle(AppTheme.secondaryText)
                }

                ETFHoldingDonutChart(holdings: visibleHoldings, otherWeight: otherWeight)
                    .frame(maxWidth: .infinity)

                VStack(spacing: 0) {
                    ForEach(Array(visibleHoldings.enumerated()), id: \.element.id) { index, holding in
                        Button {
                            onSelectHolding(holding)
                        } label: {
                            ETFHoldingBreakdownRow(
                                holding: holding,
                                color: ETFHoldingPalette.color(at: index),
                                isLoading: resolvingHoldingID == holding.id
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    if shouldShowOther {
                        ETFHoldingBreakdownRow(
                            title: "기타",
                            weight: otherWeight,
                            color: ETFHoldingPalette.otherColor
                        )
                    }
                }
            }
            .appCard(padding: 16)
        }
    }
}

private struct ETFHoldingDonutChart: View {
    let holdings: [ETFHolding]
    let otherWeight: Double

    private var totalWeight: Double {
        holdings.reduce(max(otherWeight, 0)) { $0 + max($1.weight, 0) }
    }

    var body: some View {
        Canvas { context, size in
            guard totalWeight > 0 else { return }
            let lineWidth = min(size.width, size.height) * 0.20
            let radius = (min(size.width, size.height) - lineWidth) / 2
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            var startAngle = Angle.degrees(-90)

            for (index, holding) in holdings.enumerated() {
                let fraction = max(holding.weight, 0) / totalWeight
                let endAngle = startAngle + .degrees(fraction * 360)
                var path = Path()
                path.addArc(
                    center: center,
                    radius: radius,
                    startAngle: startAngle,
                    endAngle: endAngle,
                    clockwise: false
                )
                context.stroke(
                    path,
                    with: .color(ETFHoldingPalette.color(at: index)),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt)
                )
                startAngle = endAngle
            }
            let remainingWeight = max(otherWeight, 0)
            if remainingWeight > 0 {
                let fraction = remainingWeight / totalWeight
                let endAngle = startAngle + .degrees(fraction * 360)
                var path = Path()
                path.addArc(
                    center: center,
                    radius: radius,
                    startAngle: startAngle,
                    endAngle: endAngle,
                    clockwise: false
                )
                context.stroke(
                    path,
                    with: .color(ETFHoldingPalette.otherColor),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt)
                )
            }
        }
        .frame(width: 168, height: 168)
        .padding(.vertical, 8)
    }
}

private struct ETFHoldingBreakdownRow: View {
    let title: String
    let weight: Double
    let color: Color
    let isLoading: Bool

    init(holding: ETFHolding, color: Color, isLoading: Bool = false) {
        self.title = holding.name
        self.weight = holding.weight
        self.color = color
        self.isLoading = isLoading
    }

    init(title: String, weight: Double, color: Color, isLoading: Bool = false) {
        self.title = title
        self.weight = weight
        self.color = color
        self.isLoading = isLoading
    }

    var body: some View {
        HStack(spacing: 16) {
            RoundedRectangle(cornerRadius: 4)
                .fill(color)
                .frame(width: 16, height: 16)
            Text(title)
                .font(.headline.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
            Spacer(minLength: 12)
            Text(pct(weight, signed: false))
                .font(.headline.weight(.medium))
                .foregroundStyle(AppTheme.secondaryText)
                .monospacedDigit()
                .lineLimit(1)
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            }
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }
}

private enum ETFHoldingPalette {
    static let otherColor = Color(red: 0.82, green: 0.85, blue: 0.89)

    private static let colors: [Color] = [
        Color(red: 0.16, green: 0.47, blue: 0.88),
        Color(red: 0.12, green: 0.65, blue: 0.65),
        Color(red: 0.27, green: 0.80, blue: 0.58),
        Color(red: 1.00, green: 0.82, blue: 0.35),
        Color(red: 1.00, green: 0.59, blue: 0.03),
        Color(red: 0.91, green: 0.33, blue: 0.02),
        Color(red: 0.30, green: 0.73, blue: 0.84),
        Color(red: 0.08, green: 0.40, blue: 0.68),
        Color(red: 0.90, green: 0.27, blue: 0.41),
        Color(red: 0.68, green: 0.08, blue: 0.82)
    ]

    static func color(at index: Int) -> Color {
        colors[index % colors.count]
    }
}

private struct CompanyTitleSection: View {
    let ticker: String
    let name: String
    let currency: String

    var body: some View {
        HStack(spacing: 12) {
            CompanyLogoView(ticker: ticker, currency: currency, size: 46)
            VStack(alignment: .leading, spacing: 5) {
                Text(name)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
                HStack(spacing: 6) {
                    TickerBadge(ticker: ticker)
                    Text(currency == "KRW" ? "Korea" : "US")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(AppTheme.secondaryText)
                }
            }
            Spacer()
        }
        .appCard(padding: 14)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }
}

private struct DetailTopDecisionCard: View {
    let ticker: String
    let name: String
    let currency: String
    let info: StockInfo?
    let isLoading: Bool
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let isETF: Bool
    let source: String?
    let updatedAt: String?
    let isWatched: Bool
    let isCompared: Bool
    let canShowComparison: Bool
    let toggleWatch: () -> Void
    let editMemo: () -> Void
    let compare: () -> Void

    private var conclusion: DetailConclusion {
        detailConclusion(info: info, metrics: staticMetrics, signals: signals, isETF: isETF)
    }

    private var compareTitle: String {
        if isCompared, canShowComparison { return "대상 선택" }
        return isCompared ? "비교중" : "비교"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                CompanyLogoView(ticker: ticker, currency: currency, size: 44)
                VStack(alignment: .leading, spacing: 5) {
                    Text(name)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                    HStack(spacing: 6) {
                        TickerBadge(ticker: ticker)
                        Text(currency == "KRW" ? "한국" : "미국")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(AppTheme.secondaryText)
                    }
                }
                Spacer(minLength: 8)
                priceSummary
            }

            HStack(alignment: .top, spacing: 10) {
                LucideIconView(icon: lucideIcon(forSystemSymbol: conclusion.symbol), size: 15)
                    .foregroundStyle(conclusion.color)
                    .frame(width: 30, height: 30)
                    .background(conclusion.color.opacity(0.10), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text(conclusion.title)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Text(conclusion.detail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                }
            }

            DetailTopMetricStrip(metrics: Array(staticMetrics.prefix(2)))

            HStack(spacing: 8) {
                Button(action: toggleWatch) {
                    HStack(spacing: 6) {
                        Image(systemName: isWatched ? "heart.fill" : "heart")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(isWatched ? .yellow : AppTheme.accent)
                        Text(isWatched ? "관심중" : "관심")
                            .foregroundStyle(isWatched ? .yellow : AppTheme.accent)
                    }
                }
                .buttonStyle(QuantSecondaryButtonStyle())
                .accessibilityLabel(isWatched ? "\(name) 관심 종목 제거" : "\(name) 관심 종목 추가")

                Button(action: compare) {
                    HStack(spacing: 6) {
                        LucideIconView(icon: isCompared ? .check : .gitCompare, size: 14)
                        Text(compareTitle)
                    }
                }
                .buttonStyle(QuantPrimaryButtonStyle(isComplete: isCompared))
                .accessibilityLabel(isCompared ? "\(name) 비교 목록에 추가됨" : "\(name) 비교 목록에 추가")

                Button(action: editMemo) {
                    LucideIconView(icon: .slidersHorizontal, size: 17)
                }
                .buttonStyle(QuantIconButtonStyle(tint: AppTheme.secondaryText))
                .accessibilityLabel("\(name) 관심 설정")

                Spacer(minLength: 0)
            }
            .font(.caption.weight(.semibold))
        }
        .appCard(padding: 14)
        .padding(.horizontal)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var priceSummary: some View {
        if let info, let px = info.currentPrice {
            let changePct = detailDailyChangePct(info: info, current: px)
            let change = detailDailyChangeAmount(info: info, current: px, changePct: changePct)
            let isUp = (change ?? 0) >= 0
            VStack(alignment: .trailing, spacing: 4) {
                AnimatedPriceText(
                    text: fmtPx(px, currency: currency),
                    font: .system(size: 20, weight: .bold, design: .rounded).monospacedDigit(),
                    color: AppTheme.primaryText
                )
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                if let change, let changePct {
                    Text("\(signedPx(change, currency: currency)) \(pct(changePct))")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(isUp ? AppTheme.positive : AppTheme.negative)
                        .monospacedDigit()
                        .lineLimit(1)
                }
                DataFreshnessBadge(source: source, updatedAt: updatedAt, compact: true)
            }
            .frame(width: 116, alignment: .trailing)
        } else if isLoading {
            ProgressView()
                .scaleEffect(0.75)
                .frame(width: 72, alignment: .trailing)
        } else {
            Text("시세 대기")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .frame(width: 72, alignment: .trailing)
        }
    }
}

private struct DetailTopMetricStrip: View {
    let metrics: [StaticMetric]

    var body: some View {
        if !metrics.isEmpty {
            HStack(spacing: 8) {
                ForEach(metrics) { metric in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(metric.label)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                        Text(metric.value)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(metric.color)
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.74)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }
}

private struct PriceHeaderSection: View {
    let info: StockInfo?
    let isLoading: Bool
    let errorMsg: String?
    let currency: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let info, let px = info.currentPrice {
                priceRow(info: info, px: px)
                if let sector = info.sector, !sector.isEmpty {
                    SectorPill(text: portfolioIndustryLabel(ticker: "", name: info.name ?? "", sector: sector))
                }
            } else if isLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("시세 확인 중")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                }
            } else if let errorMsg {
                Text(errorMsg)
                    .font(.caption)
                    .foregroundStyle(.red)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("시세 정보 없음")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text("현재 상세 응답에 가격 정보가 없습니다.")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                }
            }
        }
        .appCard(padding: 14)
        .padding(.horizontal)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private func priceRow(info: StockInfo, px: Double) -> some View {
        let changePct = detailDailyChangePct(info: info, current: px)
        let change = detailDailyChangeAmount(info: info, current: px, changePct: changePct)
        let isUp = (change ?? 0) >= 0

        HStack(alignment: .firstTextBaseline, spacing: 10) {
            AnimatedPriceText(
                text: fmtPx(px, currency: currency),
                font: .system(size: 30, weight: .bold, design: .rounded).monospacedDigit(),
                color: AppTheme.primaryText
            )

            if let change, let changePct {
                Text("\(signedPx(change, currency: currency)) (\(pct(changePct)))")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(isUp ? AppTheme.positive : AppTheme.negative)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Capsule().fill((isUp ? AppTheme.positive : AppTheme.negative).opacity(0.12)))
            }
            Spacer()
        }
    }
}

private func detailDailyChangePct(info: StockInfo, current: Double) -> Double? {
    if let value = info.dailyChangePct, value.isFinite {
        return value
    }
    return info.prevClose.flatMap { previous in
        guard previous.isFinite, previous != 0 else { return nil }
        return (current - previous) / previous
    }
}

private func detailDailyChangeAmount(info: StockInfo, current: Double, changePct: Double?) -> Double? {
    if let previous = info.prevClose, previous.isFinite {
        return current - previous
    }
    if let changePct, changePct.isFinite, changePct > -1 {
        let previous = current / (1 + changePct)
        return current - previous
    }
    return nil
}

private struct DetailConclusionActionBar: View {
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let isWatched: Bool
    let isCompared: Bool
    let canShowComparison: Bool
    let toggleWatch: () -> Void
    let editMemo: () -> Void
    let compare: () -> Void

    private var conclusion: DetailConclusion {
        detailConclusion(info: info, metrics: staticMetrics, signals: signals)
    }

    private var accessibilityName: String {
        let clean = info?.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return clean.isEmpty ? "종목" : clean
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: conclusion.symbol)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(conclusion.color)
                    .frame(width: 30, height: 30)
                    .background(conclusion.color.opacity(0.10), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text(conclusion.title)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Text(conclusion.detail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                Button(action: toggleWatch) {
                    Label(isWatched ? "관심중" : "관심", systemImage: isWatched ? "heart.fill" : "heart")
                }
                .buttonStyle(.bordered)
                .tint(isWatched ? .yellow : AppTheme.secondaryText)
                .accessibilityLabel(isWatched ? "\(accessibilityName) 관심 종목 제거" : "\(accessibilityName) 관심 종목 추가")

                Button(action: editMemo) {
                    Label("설정", systemImage: "slider.horizontal.3")
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.secondaryText)
                .accessibilityLabel("\(accessibilityName) 관심 설정")

                Button(action: compare) {
                    Label(compareTitle, systemImage: isCompared ? "checkmark.rectangle.split.2x1" : "rectangle.split.2x1")
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.momentum)
                .accessibilityLabel(isCompared ? "\(accessibilityName) 비교 목록에 추가됨" : "\(accessibilityName) 비교 목록에 추가")

                Spacer(minLength: 0)
            }
            .font(.caption.weight(.semibold))
        }
        .appCard(padding: 12)
        .padding(.horizontal)
        .padding(.bottom, 4)
    }

    private var compareTitle: String {
        if isCompared, canShowComparison { return "대상 선택" }
        return isCompared ? "비교중" : "비교"
    }
}

private struct DetailConclusion {
    let title: String
    let detail: String
    let symbol: String
    let color: Color
}

private func detailConclusion(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal], isETF: Bool = false) -> DetailConclusion {
    let joined = signals.map { "\($0.title) \($0.detail)" }.joined(separator: " ")
    if isETF {
        if joined.contains("주의") || joined.contains("부담") || joined.contains("리스크") {
            return DetailConclusion(
                title: "주의 조건 먼저 확인",
                detail: "구성 종목 쏠림, 추종 대상, 가격 위치를 먼저 확인하세요.",
                symbol: "exclamationmark.triangle",
                color: AppTheme.warning
            )
        }
        return DetailConclusion(
            title: "ETF 추적 후보",
            detail: "구성 비중, 총보수, 차트 흐름을 다른 ETF와 함께 비교하세요.",
            symbol: "checklist",
            color: AppTheme.quality
        )
    }
    if joined.contains("주의") || joined.contains("부담") || joined.contains("리스크") {
        return DetailConclusion(
            title: "주의 조건 먼저 확인",
            detail: "리스크 또는 밸류 부담 신호가 있습니다. 판단 카드와 차트 위치를 먼저 보세요.",
            symbol: "exclamationmark.triangle",
            color: AppTheme.warning
        )
    }
    if signals.contains(where: { $0.title.contains("실적") || $0.detail.contains("실적") }) {
        return DetailConclusion(
            title: "이벤트 중심 추적",
            detail: "실적 이벤트가 판단의 핵심입니다. 알림 조건을 정해 발표 전후를 비교하세요.",
            symbol: "calendar.badge.clock",
            color: AppTheme.momentum
        )
    }
    if let recommendation = info?.recommendation, !recommendation.isEmpty {
        return DetailConclusion(
            title: "추적 유지",
            detail: "추천/컨센서스 \(recommendation)를 참고하되, 점수와 재무 지표를 함께 확인하세요.",
            symbol: "scope",
            color: AppTheme.accent
        )
    }
    if metrics.isEmpty && info == nil {
        return DetailConclusion(
            title: "데이터 연결 대기",
            detail: "상세 데이터가 도착하면 결론과 행동 버튼이 더 정확해집니다.",
            symbol: "hourglass",
            color: AppTheme.secondaryText
        )
    }
    return DetailConclusion(
        title: "추적 후보",
        detail: "관심 조건과 비교 바구니에 넣어 다른 후보와 함께 판단하세요.",
        symbol: "checklist",
        color: AppTheme.quality
    )
}

private struct StockDetailTabPicker: View {
    @Binding var selection: StockDetailTab
    let tabs: [StockDetailTab]

    var body: some View {
        AppSegmentSwitch(options: tabs, selection: $selection) { tab in
            tab.rawValue
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
    }
}

private struct RangeSection: View {
    let info: StockInfo?
    let currency: String

    var body: some View {
        if let info,
           let low = info.week52Low,
           let high = info.week52High,
           let current = info.currentPrice {
            RangeBarView(low: low, high: high, current: current, currency: currency)
                .padding(.bottom, 10)
        }
    }
}

private struct ChartSection: View {
    let periods: [(String, String)]
    @Binding var period: String
    let points: [PricePoint]
    let indicators: ChartIndicatorSet
    let enabledPeriods: Set<String>
    @Binding var selectedIndex: Int?
    let selectedPoint: PricePoint?
    let isLoading: Bool
    let currency: String
    @Binding var showCloseLine: Bool
    @Binding var showMA5: Bool
    @Binding var showMA20: Bool
    @Binding var showMA120: Bool
    @Binding var showBollinger: Bool
    @Binding var showTrendChannel: Bool
    @Binding var showSupportResistance: Bool
    @Binding var showVolume: Bool
    @Binding var showRSI: Bool
    @Binding var showMACD: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            chartHeader

            DetailChartPeriodSelector(periods: periods, enabledPeriods: enabledPeriods, selection: $period)
                .padding(.horizontal)

            if isLoading && points.isEmpty {
                LoadingStateView(title: "가격 데이터 로딩 중", detail: nil)
                    .frame(height: 180)
                    .frame(maxWidth: .infinity)
            } else if points.isEmpty {
                EmptyMsg(
                    icon: "chart.line.uptrend.xyaxis",
                    msg: "가격 데이터 없음",
                    detail: "현재 기간에 표시할 가격 이력이 없습니다."
                )
                    .frame(height: 200)
                    .frame(maxWidth: .infinity)
            } else {
                CandleChartView(
                    points: points,
                    indicators: indicators,
                    selectedIndex: $selectedIndex,
                    showCloseLine: showCloseLine,
                    showMA5: showMA5,
                    showMA20: showMA20,
                    showMA120: showMA120,
                    showBollinger: showBollinger,
                    showTrendChannel: showTrendChannel,
                    showSupportResistance: showSupportResistance
                )
                    .padding(.horizontal)

                selectedPointRow
                ChartRangeSummary(points: points, currency: currency)
                ChartOverlayControls(
                    showCloseLine: $showCloseLine,
                    showMA5: $showMA5,
                    showMA20: $showMA20,
                    showMA120: $showMA120,
                    showBollinger: $showBollinger,
                    showTrendChannel: $showTrendChannel,
                    showSupportResistance: $showSupportResistance,
                    showVolume: $showVolume,
                    showRSI: $showRSI,
                    showMACD: $showMACD
                )

                if showVolume {
                    VolumeChartView(points: points)
                        .padding(.horizontal)
                }
                if showRSI {
                    RSIChartView(points: points, values: indicators.rsi)
                        .padding(.horizontal)
                }
                if showMACD {
                    MACDChartView(points: points, values: indicators.macd)
                        .padding(.horizontal)
                }
            }
        }
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(AppTheme.card)
        )
        .padding(.horizontal)
        .padding(.bottom, 8)
        .onAppear(perform: normalizeSelectedPeriod)
        .onChange(of: enabledPeriods) { _, _ in
            normalizeSelectedPeriod()
        }
    }

    private var activePoint: PricePoint? {
        selectedPoint ?? points.last
    }

    private var chartHeader: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("가격 차트")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            Spacer(minLength: 8)
            if let point = activePoint {
                VStack(alignment: .trailing, spacing: 2) {
                    Text(fmtPx(point.close, currency: currency))
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(point.id)
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.secondaryText)
                }
            }
        }
        .padding(.horizontal)
    }

    private var selectedPointRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let point = activePoint {
                    Kpi(label: "Open", value: fmtPx(point.open, currency: currency))
                    Kpi(label: "High", value: fmtPx(point.high, currency: currency), color: .red)
                    Kpi(label: "Low", value: fmtPx(point.low, currency: currency), color: .blue)
                    Kpi(label: "Close", value: fmtPx(point.close, currency: currency))
                }
            }
            .padding(.horizontal)
        }
    }

    private func normalizeSelectedPeriod() {
        guard !enabledPeriods.contains(period) else { return }
        if let fallback = periods.reversed().first(where: { enabledPeriods.contains($0.1) })?.1 {
            period = fallback
        }
    }
}

private struct DetailChartPeriodSelector: View {
    let periods: [(String, String)]
    let enabledPeriods: Set<String>
    @Binding var selection: String

    var body: some View {
        HStack(spacing: 5) {
            ForEach(periods, id: \.1) { label, value in
                let isEnabled = enabledPeriods.contains(value)
                Button {
                    if isEnabled {
                        selection = value
                    }
                } label: {
                    Text(label)
                        .font(.caption.weight(selection == value ? .bold : .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity)
                        .frame(height: 30)
                }
                .buttonStyle(.plain)
                .disabled(!isEnabled)
                .foregroundStyle(selection == value ? Color.white : isEnabled ? AppTheme.secondaryText : AppTheme.tertiaryText)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(selection == value ? AppTheme.accent : AppTheme.elevatedCard.opacity(isEnabled ? 1 : 0.45))
                )
                .accessibilityLabel(isEnabled ? "\(label) 차트 보기" : "\(label) 차트 사용 불가")
            }
        }
    }
}

private struct ChartRangeSummary: View {
    let points: [PricePoint]
    let currency: String

    var body: some View {
        if points.count >= 2, let first = points.first, let last = points.last {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ChartSummaryPill(label: "기간", value: "\(first.id) ~ \(last.id)")
                    ChartSummaryPill(label: "수익률", value: pct(first.close == 0 ? nil : last.close / first.close - 1.0))
                    ChartSummaryPill(label: "고가", value: fmtPx(points.map(\.high).max() ?? last.high, currency: currency))
                    ChartSummaryPill(label: "저가", value: fmtPx(points.map(\.low).min() ?? last.low, currency: currency))
                }
                .padding(.horizontal)
            }
        }
    }
}

private struct ChartSummaryPill: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 5) {
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(.secondary.opacity(0.07)))
    }
}

private struct ChartOverlayControls: View {
    @Binding var showCloseLine: Bool
    @Binding var showMA5: Bool
    @Binding var showMA20: Bool
    @Binding var showMA120: Bool
    @Binding var showBollinger: Bool
    @Binding var showTrendChannel: Bool
    @Binding var showSupportResistance: Bool
    @Binding var showVolume: Bool
    @Binding var showRSI: Bool
    @Binding var showMACD: Bool

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                OverlayToggle(title: "Close", color: .primary, isOn: $showCloseLine)
                OverlayToggle(title: "MA5", color: .yellow, isOn: $showMA5)
                OverlayToggle(title: "MA20", color: .blue, isOn: $showMA20)
                OverlayToggle(title: "MA120", color: .indigo, isOn: $showMA120)
                OverlayToggle(title: "BB", color: .secondary, isOn: $showBollinger)
                OverlayToggle(title: "Trend", color: .orange, isOn: $showTrendChannel)
                OverlayToggle(title: "S/R", color: .teal, isOn: $showSupportResistance)
                OverlayToggle(title: "Volume", color: .green, isOn: $showVolume)
                OverlayToggle(title: "RSI", color: AppTheme.momentum, isOn: $showRSI)
                OverlayToggle(title: "MACD", color: .blue, isOn: $showMACD)
            }
            .padding(.horizontal)
            .padding(.vertical, 2)
        }
    }
}

private struct OverlayToggle: View {
    let title: String
    let color: Color
    @Binding var isOn: Bool

    var body: some View {
        Button {
            isOn.toggle()
        } label: {
            HStack(spacing: 5) {
                Circle()
                    .fill(isOn ? color : .secondary.opacity(0.35))
                    .frame(width: 7, height: 7)
                Text(title)
                    .font(.caption.weight(.semibold))
            }
            .foregroundStyle(isOn ? .primary : .secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(isOn ? color.opacity(0.12) : Color.secondary.opacity(0.07))
            )
        }
        .buttonStyle(.plain)
    }
}

private struct ExtraInfoSection: View {
    let info: StockInfo?
    let currency: String
    var onTermSelected: (GlossaryTerm) -> Void = { _ in }

    var body: some View {
        if let info, hasMarketInfo(info) {
            Divider().padding(.horizontal).padding(.vertical, 6)
            VStack(alignment: .leading, spacing: 10) {
                Text("시장 정보")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .padding(.horizontal)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    if let marketCap = info.marketCap {
                        DetailInfoTile(label: "시가총액", value: cap(marketCap, currency: currency), onTermSelected: onTermSelected)
                    }
                    if let pe = info.peRatio {
                        DetailInfoTile(label: "PER", value: String(format: "%.1f", pe), onTermSelected: onTermSelected)
                    }
                    if let forwardPe = info.forwardPe {
                        DetailInfoTile(label: "Forward PER", value: String(format: "%.1f", forwardPe), onTermSelected: onTermSelected)
                    }
                    if let ps = info.priceToSales {
                        DetailInfoTile(label: "P/S", value: String(format: "%.1f", ps), onTermSelected: onTermSelected)
                    }
                    if let pb = info.priceToBook {
                        DetailInfoTile(label: "P/B", value: String(format: "%.1f", pb), onTermSelected: onTermSelected)
                    }
                    if let beta = info.beta {
                        DetailInfoTile(label: "Beta", value: String(format: "%.2f", beta), onTermSelected: onTermSelected)
                    }
                    if let target = info.targetMeanPrice {
                        DetailInfoTile(label: "목표가 평균", value: fmtPx(target, currency: currency))
                    }
                    if let recommendation = normalizedRecommendation(info.recommendation) {
                        DetailInfoTile(label: "컨센서스", value: recommendation.capitalized)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 10)
        }
    }

    private func hasMarketInfo(_ info: StockInfo) -> Bool {
        info.peRatio != nil ||
        info.forwardPe != nil ||
        info.priceToSales != nil ||
        info.priceToBook != nil ||
        info.beta != nil ||
        info.marketCap != nil ||
        info.targetMeanPrice != nil ||
        normalizedRecommendation(info.recommendation) != nil
    }
}

private struct CompanyProfileSection: View {
    let info: StockInfo?

    var body: some View {
        if let info, hasProfile(info) {
            Divider().padding(.horizontal).padding(.vertical, 6)
            VStack(alignment: .leading, spacing: 10) {
                Text("기업 프로필")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .padding(.horizontal)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    if let industry = nonEmpty(info.industry) {
                        DetailInfoTile(label: "산업", value: industry)
                    }
                    if let country = location(info) {
                        DetailInfoTile(label: "지역", value: country)
                    }
                    if let employees = info.employees {
                        DetailInfoTile(label: "직원 수", value: employeeText(employees))
                    }
                    if let website = nonEmpty(info.website) {
                        DetailInfoTile(label: "웹사이트", value: website)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 10)
        }
    }

    private func hasProfile(_ info: StockInfo) -> Bool {
        nonEmpty(info.industry) != nil ||
        location(info) != nil ||
        info.employees != nil ||
        nonEmpty(info.website) != nil
    }

    private func location(_ info: StockInfo) -> String? {
        let parts = [info.city, info.country].compactMap(nonEmpty)
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }
}

private struct FinancialSnapshotSection: View {
    let info: StockInfo?
    let currency: String
    var onTermSelected: (GlossaryTerm) -> Void = { _ in }

    var body: some View {
        if let info, hasFinancials(info) {
            Divider().padding(.horizontal).padding(.vertical, 6)
            VStack(alignment: .leading, spacing: 10) {
                Text("재무 스냅샷")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .padding(.horizontal)
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    if let revenue = info.totalRevenue {
                        DetailInfoTile(label: "매출", value: cap(revenue, currency: currency), onTermSelected: onTermSelected)
                    }
                    if let growth = info.revenueGrowth {
                        DetailInfoTile(label: "매출 성장", value: pct(growth), onTermSelected: onTermSelected)
                    }
                    if let gross = info.grossMargin {
                        DetailInfoTile(label: "매출총이익률", value: pct(gross, signed: false), onTermSelected: onTermSelected)
                    }
                    if let operating = info.operatingMargin {
                        DetailInfoTile(label: "영업이익률", value: pct(operating, signed: false), onTermSelected: onTermSelected)
                    }
                    if let profit = info.profitMargin {
                        DetailInfoTile(label: "순이익률", value: pct(profit, signed: false))
                    }
                    if let ebitdaMargin = info.ebitdaMargin {
                        DetailInfoTile(label: "EBITDA 마진", value: pct(ebitdaMargin, signed: false), onTermSelected: onTermSelected)
                    }
                    if let ebitda = info.ebitda {
                        DetailInfoTile(label: "EBITDA", value: cap(ebitda, currency: currency), onTermSelected: onTermSelected)
                    }
                    if let freeCashflow = info.freeCashflow {
                        DetailInfoTile(label: "FCF", value: cap(freeCashflow, currency: currency), onTermSelected: onTermSelected)
                    }
                    if let debt = info.totalDebt {
                        DetailInfoTile(label: "총부채", value: cap(debt, currency: currency))
                    }
                    if let debtToEquity = info.debtToEquity {
                        DetailInfoTile(label: "Debt/Equity", value: String(format: "%.1f", debtToEquity), onTermSelected: onTermSelected)
                    }
                    if let roe = info.returnOnEquity {
                        DetailInfoTile(label: "ROE", value: pct(roe, signed: false), onTermSelected: onTermSelected)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 10)
        }
    }

    private func hasFinancials(_ info: StockInfo) -> Bool {
        info.totalRevenue != nil ||
        info.revenueGrowth != nil ||
        info.grossMargin != nil ||
        info.operatingMargin != nil ||
        info.profitMargin != nil ||
        info.ebitdaMargin != nil ||
        info.ebitda != nil ||
        info.freeCashflow != nil ||
        info.totalDebt != nil ||
        info.debtToEquity != nil ||
        info.returnOnEquity != nil
    }
}

private struct DetailInfoTile: View {
    let label: String
    let value: String
    var onTermSelected: (GlossaryTerm) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                GlossaryInfoButton(label: label, onSelect: onTermSelected)
            }
            Text(value)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(2)
                .minimumScaleFactor(0.75)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 62, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 8).fill(.secondary.opacity(0.07)))
    }
}

private func nonEmpty(_ value: String?) -> String? {
    guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
        return nil
    }
    return text
}

private func employeeText(_ value: Int) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
}

private struct DataMetaSection: View {
    let source: String?
    let updatedAt: String?

    var body: some View {
        Divider().padding(.horizontal).padding(.vertical, 6)
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text("데이터 신뢰도")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                DataFreshnessBadge(source: source, updatedAt: updatedAt, compact: true)
            }
            HStack(spacing: 12) {
                Label(dataSourceLabel(source), systemImage: "server.rack")
                Label(formattedUpdateTimestamp(updatedAt), systemImage: "clock")
                Spacer()
            }
            .font(.caption)
            .foregroundStyle(AppTheme.secondaryText)
        }
        .appCard(padding: 12)
        .padding(.horizontal)
        .padding(.bottom, 10)
    }
}

private struct DescriptionSection: View {
    let description: String?

    var body: some View {
        if let description, !description.isEmpty {
            Divider().padding(.horizontal).padding(.vertical, 6)
            VStack(alignment: .leading, spacing: 8) {
                Text("기업 소개")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.primaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal)
            .padding(.bottom, 12)
        }
    }
}

private struct CandleChartView: View {
    let points: [PricePoint]
    let indicators: ChartIndicatorSet
    @Binding var selectedIndex: Int?
    let showCloseLine: Bool
    let showMA5: Bool
    let showMA20: Bool
    let showMA120: Bool
    let showBollinger: Bool
    let showTrendChannel: Bool
    let showSupportResistance: Bool

    private var yDomain: ClosedRange<Double> {
        guard !points.isEmpty else { return 0...1 }
        var values = points.flatMap { [$0.low, $0.high] }
        if showBollinger {
            values.append(contentsOf: bollinger.flatMap { [$0.lower, $0.upper] })
        }
        if showTrendChannel {
            values.append(contentsOf: regression.flatMap { [$0.lower2, $0.upper2] })
        }
        let low = values.min() ?? 0
        let high = values.max() ?? 1
        if low == high { return (low * 0.95)...(high * 1.05) }
        let pad = (high - low) * 0.08
        return (low - pad)...(high + pad)
    }

    private var bodyWidth: CGFloat {
        let count = CGFloat(max(points.count, 1))
        return max(1.5, min(10, 340 / count * 0.755))
    }

    private var ma5: [MovingAveragePoint] {
        visibleIndicatorPoints(indicators.ma5)
    }

    private var ma20: [MovingAveragePoint] {
        visibleIndicatorPoints(indicators.ma20)
    }

    private var ma120: [MovingAveragePoint] {
        visibleIndicatorPoints(indicators.ma120)
    }

    private var trend: [TrendLinePoint] {
        trendLine(points: points)
    }

    private var bollinger: [BollingerPoint] {
        visibleBollingerPoints(indicators.bollinger)
    }

    private var regression: [RegressionChannelPoint] {
        regressionChannel(points: points)
    }

    private var levels: [PriceLevel] {
        supportResistance(points: points)
    }

    private var dateIndex: [Date: Int] {
        Dictionary(uniqueKeysWithValues: points.enumerated().map { index, point in
            (Calendar.current.startOfDay(for: point.date), index)
        })
    }

    private var xLabelValues: [Int] {
        guard !points.isEmpty else { return [] }
        let step = max(1, points.count / 5)
        var values = Array(stride(from: 0, to: points.count, by: step))
        if let last = points.indices.last, values.last != last {
            values.append(last)
        }
        return values
    }

    private static let axisDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM/dd"
        return formatter
    }()

    private func xIndex(for date: Date) -> Int? {
        dateIndex[Calendar.current.startOfDay(for: date)]
    }

    private func visibleIndicatorPoints(_ points: [MovingAveragePoint]) -> [MovingAveragePoint] {
        guard let firstDate = self.points.first?.date,
              let lastDate = self.points.last?.date else {
            return []
        }
        return points.filter { $0.date >= firstDate && $0.date <= lastDate }
    }

    private func visibleBollingerPoints(_ points: [BollingerPoint]) -> [BollingerPoint] {
        guard let firstDate = self.points.first?.date,
              let lastDate = self.points.last?.date else {
            return []
        }
        return points.filter { $0.date >= firstDate && $0.date <= lastDate }
    }

    var body: some View {
        let domain = yDomain
        let minBodyHeight = (domain.upperBound - domain.lowerBound) * 0.003

        Chart {
            if showBollinger {
                ForEach(bollinger) { point in
                    if let index = xIndex(for: point.date) {
                        LineMark(
                            x: .value("Trading Day", index),
                            y: .value("BB Upper", point.upper),
                            series: .value("Indicator", "BB Upper")
                        )
                        .foregroundStyle(.secondary.opacity(0.38))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))

                        LineMark(
                            x: .value("Trading Day", index),
                            y: .value("BB Lower", point.lower),
                            series: .value("Indicator", "BB Lower")
                        )
                        .foregroundStyle(.secondary.opacity(0.38))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))

                        LineMark(
                            x: .value("Trading Day", index),
                            y: .value("BB Middle", point.middle),
                            series: .value("Indicator", "BB Middle")
                        )
                        .foregroundStyle(.secondary.opacity(0.62))
                        .lineStyle(StrokeStyle(lineWidth: 1))
                    }
                }
            }

            if showTrendChannel {
                ForEach(regression) { point in
                    if let index = xIndex(for: point.date) {
                        LineMark(x: .value("Trading Day", index), y: .value("+2σ", point.upper2), series: .value("Indicator", "+2σ"))
                            .foregroundStyle(.orange.opacity(0.25))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                        LineMark(x: .value("Trading Day", index), y: .value("-2σ", point.lower2), series: .value("Indicator", "-2σ"))
                            .foregroundStyle(.orange.opacity(0.25))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                        LineMark(x: .value("Trading Day", index), y: .value("+1σ", point.upper1), series: .value("Indicator", "+1σ"))
                            .foregroundStyle(.orange.opacity(0.5))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [5, 4]))
                        LineMark(x: .value("Trading Day", index), y: .value("-1σ", point.lower1), series: .value("Indicator", "-1σ"))
                            .foregroundStyle(.orange.opacity(0.5))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [5, 4]))
                        LineMark(x: .value("Trading Day", index), y: .value("Regression", point.trend), series: .value("Indicator", "Regression"))
                            .foregroundStyle(.orange)
                            .lineStyle(StrokeStyle(lineWidth: 1.8, dash: [7, 4]))
                    }
                }
            }

            ForEach(Array(points.enumerated()), id: \.element.id) { index, point in
                let isUp = point.close >= point.open
                let color = isUp ? Color.red : Color.blue
                let bodyLow = min(point.open, point.close)
                let bodyHigh = max(point.open, point.close)
                let adjustedHigh = bodyHigh <= bodyLow ? bodyLow + minBodyHeight : bodyHigh

                RuleMark(
                    x: .value("Trading Day", index),
                    yStart: .value("Low", point.low),
                    yEnd: .value("High", point.high)
                )
                .lineStyle(StrokeStyle(lineWidth: 1))
                .foregroundStyle(color.opacity(0.85))

                RectangleMark(
                    x: .value("Trading Day", index),
                    yStart: .value("BodyLow", bodyLow),
                    yEnd: .value("BodyHigh", adjustedHigh),
                    width: .fixed(bodyWidth)
                )
                .foregroundStyle(color.opacity(isUp ? 1 : 0.9))
            }

            if showSupportResistance {
                ForEach(levels) { level in
                    RuleMark(y: .value(level.isResistance ? "Resistance" : "Support", level.price))
                        .foregroundStyle((level.isResistance ? Color.red : Color.blue).opacity(0.58))
                        .lineStyle(StrokeStyle(lineWidth: 1.2, dash: [3, 3]))
                        .annotation(position: .trailing, alignment: .center) {
                            Text(level.isResistance ? "R" : "S")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(level.isResistance ? .red : .blue)
                        }
                }
            }

            if showCloseLine {
                ForEach(Array(points.enumerated()), id: \.element.id) { index, point in
                    LineMark(
                        x: .value("Trading Day", index),
                        y: .value("Close", point.close),
                        series: .value("Indicator", "Close")
                    )
                    .foregroundStyle(.primary.opacity(0.55))
                    .lineStyle(StrokeStyle(lineWidth: 1.2))
                }
            }

            if showMA5 {
                ForEach(ma5) { point in
                    if let index = xIndex(for: point.date) {
                        LineMark(
                            x: .value("Trading Day", index),
                            y: .value("MA5", point.value),
                            series: .value("Indicator", "MA5")
                        )
                        .foregroundStyle(.yellow)
                        .lineStyle(StrokeStyle(lineWidth: 1.5))
                    }
                }
            }

            if showMA20 {
                ForEach(ma20) { point in
                    if let index = xIndex(for: point.date) {
                        LineMark(
                            x: .value("Trading Day", index),
                            y: .value("MA20", point.value),
                            series: .value("Indicator", "MA20")
                        )
                        .foregroundStyle(.blue)
                        .lineStyle(StrokeStyle(lineWidth: 1.8))
                    }
                }
            }

            if showMA120 {
                ForEach(ma120) { point in
                    if let index = xIndex(for: point.date) {
                        LineMark(
                            x: .value("Trading Day", index),
                            y: .value("MA120", point.value),
                            series: .value("Indicator", "MA120")
                        )
                        .foregroundStyle(.indigo)
                        .lineStyle(StrokeStyle(lineWidth: 1.5))
                    }
                }
            }

            if let selectedIndex, points.indices.contains(selectedIndex) {
                RuleMark(x: .value("Selected", selectedIndex))
                    .foregroundStyle(.secondary.opacity(0.55))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4]))
            }
        }
        .chartYScale(domain: domain)
        .chartXScale(domain: -0.5...(Double(max(points.count - 1, 0)) + 0.5))
        .chartXSelection(value: $selectedIndex)
        .chartXAxis {
            AxisMarks(values: xLabelValues) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.4, dash: [3]))
                    .foregroundStyle(.secondary.opacity(0.12))
                AxisTick()
                    .foregroundStyle(.secondary.opacity(0.35))
                AxisValueLabel {
                    if let index = value.as(Int.self), points.indices.contains(index) {
                        Text(Self.axisDateFormatter.string(from: points[index].date))
                            .font(.system(size: 9))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) { value in
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [4]))
                    .foregroundStyle(.secondary.opacity(0.2))
                AxisValueLabel {
                    if let number = value.as(Double.self) {
                        Text(number >= 1_000 ? String(format: "%.0f", number) : String(format: "%.2f", number))
                            .font(.system(size: 9))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .frame(height: 220)
    }
}
