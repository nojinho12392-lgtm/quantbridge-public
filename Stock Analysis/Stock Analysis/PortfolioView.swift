import Combine
import Charts
import SwiftUI

private enum PortfolioMode: String, CaseIterable, Identifiable {
    case core = "일반"
    case smallCap = "스몰캡"

    var id: String { rawValue }
}

private enum AnalysisSection: String, CaseIterable, Identifiable {
    case companies = "기업"
    case sectors = "섹터"
    case etfs = "ETF"

    var id: String { rawValue }
}

struct PortfolioHomeView: View {
    @StateObject private var usVM = PortfolioVM(market: .us)
    @StateObject private var krVM = PortfolioVM(market: .kr)
    @StateObject private var smallCapVM = SmallCapVM()
    @StateObject private var sectorVM = PortfolioSectorThemeVM()
    @StateObject private var headerIndices = MarketIndicesVM()
    @StateObject private var headerMarketIndicators = MarketIndicatorsVM()
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var comparison: ComparisonStore
    @State private var section: AnalysisSection = .companies
    @State private var mode: PortfolioMode = .core
    @State private var selectedMarket: Market = .us
    @State private var query = ""
    @State private var sort: PortfolioSort = .rank
    @State private var smallCapSort: SmallCapSort = .rank
    @State private var selectedPortfolio: PortfolioStock?
    @State private var selectedSmallCap: SmallCapStock?
    @State private var comparisonMode = false
    @State private var selectedComparisonIDs: Set<String> = []
    @State private var showHeaderSearch = false
    @State private var showHeaderMarketIndicators = false
    @State private var investmentProfile = InvestmentProfile.load()

    private var vm: PortfolioVM {
        selectedMarket == .us ? usVM : krVM
    }

    private var smallCapStocks: [SmallCapStock] {
        smallCapVM.stocks(for: selectedMarket)
    }

    private var currency: String {
        selectedMarket.currency
    }

    private var matchingStocks: [PortfolioStock] {
        vm.stocks
            .filter { portfolioIndustryTextMatches(query, ticker: $0.ticker, name: $0.name, sector: $0.sector) }
    }

    private var visibleStocks: [PortfolioStock] {
        matchingStocks
            .sorted(by: sortPortfolio)
    }

    private var matchingSmallCaps: [SmallCapStock] {
        smallCapStocks
            .filter { portfolioIndustryTextMatches(query, ticker: $0.ticker, name: $0.name) }
    }

    private var visibleSmallCaps: [SmallCapStock] {
        matchingSmallCaps
            .sorted(by: sortSmallCap)
    }

    private var isLoading: Bool {
        if mode == .smallCap {
            if case .loading = smallCapVM.state { return true }
        } else if case .loading = vm.state {
            return true
        }
        return false
    }

    private var activeWarning: String? {
        mode == .smallCap ? smallCapVM.warning : vm.warning
    }

    private var comparisonCandidates: [StockComparisonItem] {
        if mode == .smallCap {
            return visibleSmallCaps.map { StockComparisonItem(smallCap: $0) }
        }
        return visibleStocks.map { StockComparisonItem(portfolio: $0, currency: currency) }
    }

    private var selectedComparisonItems: [StockComparisonItem] {
        comparisonCandidates.filter { selectedComparisonIDs.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if section == .etfs {
                    ETFInsightsView()
                } else if section == .sectors {
                    PortfolioSectorThemeView(vm: sectorVM)
                } else {
                    if isInitialLoading {
                        LoadingStateView(
                            title: mode == .smallCap ? "스몰캡 후보 로딩 중" : "분석 데이터 로딩 중",
                            detail: "\(selectedMarket.title) 데이터를 확인하고 있습니다."
                        )
                    } else if let error = emptyError {
                        ErrView(msg: error, retry: refreshSelected)
                    } else {
                        portfolioList
                    }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                VStack(spacing: 8) {
                    QubitScreenTopHeader(
                        title: "분석",
                        indices: headerIndices.indices,
                        openSearch: { showHeaderSearch = true },
                        openMarketIndicators: { showHeaderMarketIndicators = true }
                    )

                    VStack(spacing: 8) {
                        AppSegmentSwitch(options: AnalysisSection.allCases, selection: $section) { section in
                            section.rawValue
                        }

                        if section == .companies {
                            HStack(spacing: 8) {
                                AppSearchField(text: $query, prompt: "티커, 종목명, 섹터 검색")
                                if mode == .smallCap {
                                    SortMenu(selection: $smallCapSort, compact: true)
                                } else {
                                    SortMenu(selection: $sort, compact: true)
                                }
                            }
                            PortfolioFilterChipRow(title: "유형", options: PortfolioMode.allCases, selection: $mode) { mode in
                                mode.rawValue
                            }

                            PortfolioFilterChipRow(title: "시장", options: [Market.us, Market.kr], selection: $selectedMarket) { market in
                                market == .us ? "미국" : "국내"
                            }
                            SearchStatusLine(
                                query: query,
                                visibleCount: mode == .smallCap ? visibleSmallCaps.count : visibleStocks.count,
                                totalCount: mode == .smallCap ? smallCapStocks.count : vm.stocks.count,
                                label: mode == .smallCap ? "\(selectedMarket.title) 스몰캡" : "\(selectedMarket.title) 분석",
                                isLoading: isLoading
                            )
                            PortfolioComparisonBar(
                                isComparing: comparisonMode,
                                selectedCount: selectedComparisonItems.count,
                                totalCount: comparisonCandidates.count,
                                canCompare: selectedComparisonItems.count >= 2,
                                start: startComparison,
                                compare: {
                                    comparison.replace(with: selectedComparisonItems)
                                    comparison.present()
                                },
                                clear: { selectedComparisonIDs.removeAll() },
                                cancel: {
                                    comparisonMode = false
                                    selectedComparisonIDs.removeAll()
                                }
                            )
                        }
                    }
                    .padding(.horizontal)
                }
                .padding(.vertical, 8)
                .background(AppTheme.background)
            }
            .overlay(alignment: .top) {
                LoadingOverlay(isVisible: section == .companies && isLoading && hasVisibleData)
            }
        }
        .onAppear {
            investmentProfile = InvestmentProfile.load()
        }
        .task { await headerIndices.load() }
        .task { await vm.load() }
        .task(id: selectedMarket) { await vm.load() }
        .task { await smallCapVM.load() }
        .task { await sectorVM.load() }
        .task(id: "portfolio-price-auto-\(section.rawValue)-\(mode.rawValue)-\(selectedMarket.rawValue)") {
            guard section == .companies else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.standardPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("portfolio-\(section.rawValue)-\(mode.rawValue)-\(selectedMarket.rawValue)", minInterval: 120) else { continue }
                await refreshActive()
            }
        }
        .onChange(of: section) { _, _ in resetContextFilters() }
        .onChange(of: selectedMarket) { _, _ in resetContextFilters() }
        .onChange(of: mode) { _, _ in resetContextFilters() }
        .fullScreenCover(item: $selectedPortfolio) { stock in
            StockDetailSheet(
                ticker: stock.ticker,
                name: stock.name,
                currency: currency,
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
        .sheet(isPresented: $showHeaderSearch) {
            ExploreView(showsAdvancedModes: false)
                .environmentObject(watchlist)
                .environmentObject(comparison)
        }
        .navigationDestination(isPresented: $showHeaderMarketIndicators) {
            MarketIndicatorsScreen(vm: headerMarketIndicators)
        }
    }

    private var isInitialLoading: Bool {
        if mode == .smallCap {
            if case .idle = smallCapVM.state { return true }
            if case .loading = smallCapVM.state, smallCapStocks.isEmpty { return true }
            return false
        }
        if case .idle = vm.state { return true }
        if case .loading = vm.state, vm.stocks.isEmpty { return true }
        return false
    }

    private var emptyError: String? {
        if mode == .smallCap, case .failure(let error) = smallCapVM.state, smallCapStocks.isEmpty {
            return error
        }
        if mode == .core, case .failure(let error) = vm.state, vm.stocks.isEmpty {
            return error
        }
        return nil
    }

    private var hasVisibleData: Bool {
        mode == .smallCap ? !smallCapStocks.isEmpty : !vm.stocks.isEmpty
    }

    private var portfolioList: some View {
        List {
            if let warning = activeWarning {
                Section {
                    InlineWarningBanner(msg: warning, retry: refreshSelected)
                        .listRowBackground(AppTheme.card)
                }
            }
            if mode == .smallCap {
                smallCapListContent
            } else {
                corePortfolioContent
            }
        }
        .listStyle(.insetGrouped)
        .appTabBarInset()
        .scrollContentBackground(.hidden)
        .background(AppTheme.background.ignoresSafeArea())
        .refreshable { await refreshActive() }
    }

    @ViewBuilder
    private var corePortfolioContent: some View {
        Section {
            if visibleStocks.isEmpty {
                EmptyMsg(
                    icon: vm.stocks.isEmpty ? "arrow.clockwise.circle" : "magnifyingglass",
                    msg: vm.stocks.isEmpty ? "분석 데이터 없음" : "검색 결과 없음",
                    detail: portfolioEmptyDetail(totalCount: vm.stocks.count),
                    actionTitle: vm.stocks.isEmpty ? "새로고침" : nil,
                    action: vm.stocks.isEmpty ? { refreshSelected() } : nil
                )
                .listRowBackground(AppTheme.card)
            } else {
                ForEach(visibleStocks) { stock in
                    let comparisonItem = StockComparisonItem(portfolio: stock, currency: currency)
                    let isSelected = selectedComparisonIDs.contains(comparisonItem.id)
                    PortfolioRow(
                        stock: stock,
                        profile: investmentProfile,
                        currency: currency,
                        currentPrice: stock.currentPrice,
                        return1M: stock.return1M,
                        comparisonMode: comparisonMode,
                        comparisonSelected: isSelected,
                        comparisonDisabled: comparisonMode && !isSelected && selectedComparisonIDs.count >= 4
                    )
                        .onTapGesture {
                            if comparisonMode {
                                toggleComparison(comparisonItem)
                            } else {
                                selectedPortfolio = stock
                            }
                        }
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
            }
        } header: {
            HStack {
                Text("기업 순위")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                Text("1개월 수익률")
                    .font(.subheadline.weight(.regular))
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(width: 88, alignment: .trailing)
                    .padding(.trailing, 8)
            }
            .textCase(nil)
        }
    }

    @ViewBuilder
    private var smallCapListContent: some View {
        Section {
            if visibleSmallCaps.isEmpty {
                EmptyMsg(
                    icon: smallCapStocks.isEmpty ? "arrow.clockwise.circle" : "sparkles",
                    msg: smallCapStocks.isEmpty ? "스몰캡 데이터 없음" : "스몰캡 후보 없음",
                    detail: portfolioEmptyDetail(totalCount: smallCapStocks.count),
                    actionTitle: smallCapStocks.isEmpty ? "새로고침" : nil,
                    action: smallCapStocks.isEmpty ? { refreshSelected() } : nil
                )
                .listRowBackground(AppTheme.card)
            } else {
                ForEach(Array(visibleSmallCaps.enumerated()), id: \.element.id) { index, stock in
                    let comparisonItem = StockComparisonItem(smallCap: stock)
                    let isSelected = selectedComparisonIDs.contains(comparisonItem.id)
                    SmallCapRankingRow(
                        rankLabel: "\(index + 1)",
                        stock: stock,
                        profile: investmentProfile,
                        currentPrice: stock.currentPrice,
                        return1M: stock.return1M,
                        comparisonMode: comparisonMode,
                        comparisonSelected: isSelected,
                        comparisonDisabled: comparisonMode && !isSelected && selectedComparisonIDs.count >= 4
                    )
                        .onTapGesture {
                            if comparisonMode {
                                toggleComparison(comparisonItem)
                            } else {
                                selectedSmallCap = stock
                            }
                        }
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
            }
        } header: {
            HStack {
                Text("기업 순위")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                Text("1개월 수익률")
                    .font(.subheadline.weight(.regular))
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(width: 88, alignment: .trailing)
                    .padding(.trailing, 8)
            }
            .textCase(nil)
        }
    }

    private func startComparison() {
        comparisonMode = true
    }

    private func toggleComparison(_ item: StockComparisonItem) {
        if selectedComparisonIDs.contains(item.id) {
            selectedComparisonIDs.remove(item.id)
            return
        }
        guard selectedComparisonIDs.count < 4 else { return }
        selectedComparisonIDs.insert(item.id)
    }

    private func resetComparison() {
        comparisonMode = false
        selectedComparisonIDs.removeAll()
    }

    private func resetContextFilters() {
        resetComparison()
    }

    private func refreshSelected() {
        Task { await refreshActive() }
    }

    private func refreshActive() async {
        if mode == .smallCap {
            await smallCapVM.refresh()
        } else {
            await vm.refresh()
        }
    }

    private func portfolioEmptyDetail(totalCount: Int) -> String {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return totalCount == 0
                ? "현재 선택한 시장의 데이터가 비어 있습니다. 큐빗은 모든 종목을 얕게 보여주지 않고, 분석 가능한 기업만 깊게 봅니다."
                : "현재 필터와 일치하는 종목이 없습니다. 데이터 품질과 추적 기준을 통과한 후보만 먼저 보여줍니다."
        }
        return "\"\(clean)\"는 아직 큐빗 커버리지 밖일 수 있습니다. 데이터 품질과 추적 기준을 통과한 기업부터 먼저 보여줍니다."
    }

    private func sortPortfolio(_ lhs: PortfolioStock, _ rhs: PortfolioStock) -> Bool {
        switch sort {
        case .rank:
            return (lhs.rank ?? Int.max) < (rhs.rank ?? Int.max)
        case .weight:
            return (lhs.weight ?? -.infinity) > (rhs.weight ?? -.infinity)
        case .score:
            return (lhs.totalScore ?? -.infinity) > (rhs.totalScore ?? -.infinity)
        case .expectedReturn:
            return (lhs.expectedReturn ?? -.infinity) > (rhs.expectedReturn ?? -.infinity)
        case .revenueGrowth:
            return (lhs.revGrowth ?? -.infinity) > (rhs.revGrowth ?? -.infinity)
        }
    }

    private func sortSmallCap(_ lhs: SmallCapStock, _ rhs: SmallCapStock) -> Bool {
        switch smallCapSort {
        case .rank:
            return (lhs.rank ?? Int.max) < (rhs.rank ?? Int.max)
        case .score:
            return (lhs.totalScore ?? -.infinity) > (rhs.totalScore ?? -.infinity)
        case .revenueGrowth:
            return (lhs.revGrowth ?? -.infinity) > (rhs.revGrowth ?? -.infinity)
        case .marketCap:
            return (lhs.marketCap ?? -.infinity) > (rhs.marketCap ?? -.infinity)
        }
    }
}

struct PortfolioFilterChipRow<Option: Hashable>: View {
    let title: String
    let options: [Option]
    @Binding var selection: Option
    let label: (Option) -> String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(options, id: \.self) { option in
                        Button {
                            withAnimation(.easeInOut(duration: 0.18)) {
                                selection = option
                            }
                        } label: {
                            Text(label(option))
                                .font(.caption.weight(.bold))
                                .foregroundStyle(selection == option ? Color.white : AppTheme.secondaryText)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(selection == option ? AppTheme.accent : AppTheme.elevatedCard, in: Capsule())
                        }
                        .buttonStyle(QuantPressButtonStyle(role: .text))
                        .accessibilityLabel("\(label(option)) 선택")
                    }
                }
            }
        }
    }
}



private struct PortfolioSmallCapBanner: View {
    let market: Market
    let visibleCount: Int
    let totalCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                LucideIconView(icon: .gem, size: 14)
                Text("\(market.title) 스몰캡 후보")
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.orange)
            Text("소형주 스캐너 상위 후보를 분석 화면 안에서 함께 봅니다.")
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
            Text("\(visibleCount)/\(totalCount)개")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AppTheme.tertiaryText)
        }
        .padding(.vertical, 2)
    }
}

private struct PortfolioComparisonBar: View {
    let isComparing: Bool
    let selectedCount: Int
    let totalCount: Int
    let canCompare: Bool
    let start: () -> Void
    let compare: () -> Void
    let clear: () -> Void
    let cancel: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            if isComparing {
                Text("비교 \(selectedCount)/4")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.accent)
                    .frame(minWidth: 62, alignment: .leading)
                Button("초기화", action: clear)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .buttonStyle(.plain)
                Spacer(minLength: 8)
                Button(action: cancel) {
                    Text("취소")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(AppTheme.elevatedCard, in: Capsule())
                }
                .buttonStyle(.plain)
                Button(action: compare) {
                    Text("비교 보기")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(canCompare ? Color.white : AppTheme.tertiaryText)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(canCompare ? AppTheme.accent : AppTheme.elevatedCard, in: Capsule())
                }
                .disabled(!canCompare)
                .buttonStyle(.plain)
            } else {
                Button(action: start) {
                    HStack(spacing: 7) {
                        LucideIconView(icon: .gitCompare, size: 14)
                            .foregroundStyle(AppTheme.accent)
                        Text("종목 비교")
                            .font(.caption.weight(.bold))
                        Text(totalCount == 0 ? "데이터 대기" : "2~4개 선택")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.secondaryText)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
                }
                .disabled(totalCount < 2)
                .buttonStyle(.plain)
                .opacity(totalCount < 2 ? 0.45 : 1)
            }
        }
    }
}

struct StockComparisonSheet: View {
    let items: [StockComparisonItem]
    @Environment(\.dismiss) private var dismiss
    @StateObject private var news = NewsVM()

    private var metrics: [ComparisonMetric] {
        [
            ComparisonMetric(label: "현재가", value: { portfolioPriceText($0.currentPrice, currency: $0.currency) }, number: { _ in nil }, higherIsBetter: true),
            ComparisonMetric(label: "1개월", value: { pct($0.return1M) }, number: { $0.return1M }, higherIsBetter: true),
            ComparisonMetric(label: "순위변화", value: { comparisonRankChangeText($0.rankChange) }, number: { $0.rankChange.map(Double.init) }, higherIsBetter: true),
            ComparisonMetric(label: "종합점수", value: { $0.headlineScoreText }, number: { $0.score }, higherIsBetter: true),
            ComparisonMetric(label: "기대수익", value: { pct($0.expectedReturn) }, number: { $0.expectedReturn }, higherIsBetter: true),
            ComparisonMetric(label: "매출성장", value: { pct($0.revenueGrowth) }, number: { $0.revenueGrowth }, higherIsBetter: true),
            ComparisonMetric(label: "ROIC", value: { pct($0.roic, signed: false) }, number: { $0.roic }, higherIsBetter: true),
            ComparisonMetric(label: "마진", value: { pct($0.grossMargin, signed: false) }, number: { $0.grossMargin }, higherIsBetter: true),
            ComparisonMetric(label: "시가총액", value: { cap($0.marketCap, currency: $0.currency) }, number: { $0.marketCap }, higherIsBetter: true),
            ComparisonMetric(label: "비중", value: { pct($0.weight, signed: false) }, number: { $0.weight }, higherIsBetter: true),
            ComparisonMetric(label: "FCF", value: { pct($0.fcfMargin, signed: false) }, number: { $0.fcfMargin }, higherIsBetter: true),
            ComparisonMetric(label: "거래량", value: { volumeText($0.volumeSurge) }, number: { $0.volumeSurge }, higherIsBetter: true),
            ComparisonMetric(label: "리스크", value: { comparisonRiskText($0) }, number: { comparisonRiskScore($0) }, higherIsBetter: false),
            ComparisonMetric(label: "뉴스반응", value: { comparisonNewsText(for: $0, in: news.items) }, number: { comparisonNewsScore(for: $0, in: news.items) }, higherIsBetter: true)
        ]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ComparisonSummaryCard(items: items)
                    ComparisonVerdictCard(items: items)
                    ComparisonMomentumCard(items: items)
                    ComparisonNewsReactionCard(items: items, newsItems: news.items)
                    comparisonTable
                    ComparisonInterpretationCard(items: items)
                }
                .padding()
            }
            .task { await news.load() }
            .appScreenBackground()
            .navigationTitle("종목 비교")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                }
            }
        }
    }

    private var comparisonTable: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("핵심 지표")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)

            ScrollView(.horizontal, showsIndicators: false) {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        Text("지표")
                            .comparisonCell(width: 86, alignment: .leading, isHeader: true)
                        ForEach(items) { item in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(item.name)
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(AppTheme.primaryText)
                                    .lineLimit(1)
                                Text(item.ticker)
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(AppTheme.secondaryText)
                            }
                            .comparisonCell(width: 118, alignment: .leading, isHeader: true)
                        }
                    }

                    ForEach(metrics) { metric in
                        ComparisonMetricRow(metric: metric, items: items)
                    }
                }
                .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(AppTheme.hairline, lineWidth: 0.6)
                )
            }
        }
        .appCard(padding: 14)
    }
}

private struct ComparisonMetric: Identifiable {
    let label: String
    var id: String { label }
    let value: (StockComparisonItem) -> String
    let number: (StockComparisonItem) -> Double?
    let higherIsBetter: Bool

    func bestID(in items: [StockComparisonItem]) -> String? {
        let values = items.compactMap { item -> (String, Double)? in
            guard let value = number(item), value.isFinite else { return nil }
            return (item.id, value)
        }
        guard values.count > 1 else { return nil }
        return higherIsBetter ? values.max(by: { $0.1 < $1.1 })?.0 : values.min(by: { $0.1 < $1.1 })?.0
    }
}

private struct ComparisonMetricRow: View {
    let metric: ComparisonMetric
    let items: [StockComparisonItem]

    var body: some View {
        let bestID = metric.bestID(in: items)
        HStack(spacing: 0) {
            Text(metric.label)
                .comparisonCell(width: 86, alignment: .leading)
                .foregroundStyle(AppTheme.secondaryText)
            ForEach(items) { item in
                Text(metric.value(item))
                    .comparisonCell(width: 118, alignment: .leading)
                    .foregroundStyle(bestID == item.id ? AppTheme.accent : AppTheme.primaryText)
                    .fontWeight(bestID == item.id ? .bold : .semibold)
            }
        }
    }
}

private struct ComparisonSummaryCard: View {
    let items: [StockComparisonItem]

    private var bestScore: StockComparisonItem? {
        bestItem { $0.score }
    }

    private var bestReturn: StockComparisonItem? {
        bestItem { $0.expectedReturn }
    }

    private var bestGrowth: StockComparisonItem? {
        bestItem { $0.revenueGrowth }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("비교 요약")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Text(summaryText)
                .font(.subheadline)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                SummaryChip(title: "점수 우위", value: bestScore?.name ?? "-")
                SummaryChip(title: "기대수익", value: bestReturn?.name ?? "-")
                SummaryChip(title: "성장성", value: bestGrowth?.name ?? "-")
                SummaryChip(title: "비교 개수", value: "\(items.count)개")
            }
        }
        .appCard(padding: 14)
    }

    private var summaryText: String {
        if let bestScore {
            return "\(bestScore.name)이 현재 비교군에서 종합점수가 가장 앞섭니다. 기대수익, 성장성, 수익성 지표가 동시에 비어 있지 않은지 함께 확인하세요."
        }
        return "비교 가능한 점수 데이터가 부족합니다. 성장성, ROIC, 마진처럼 비어 있지 않은 지표를 중심으로 확인하세요."
    }

    private func bestItem(_ value: (StockComparisonItem) -> Double?) -> StockComparisonItem? {
        items.max { left, right in
            (value(left) ?? -.infinity) < (value(right) ?? -.infinity)
        }
    }
}

private struct SummaryChip: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct ComparisonVerdictCard: View {
    let items: [StockComparisonItem]

    private var balanced: StockComparisonItem? {
        bestItem { $0.score }
    }

    private var aggressive: StockComparisonItem? {
        bestItem { average([$0.expectedReturn, $0.revenueGrowth]) }
    }

    private var stable: StockComparisonItem? {
        bestItem { average([$0.roic, $0.grossMargin, $0.fcfMargin]) }
    }

    private var caution: StockComparisonItem? {
        items.first { ($0.expectedReturn ?? 0) < 0 } ?? items.max { missingMetricCount($0) < missingMetricCount($1) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("다음 판단")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Text(verdictText)
                .font(.subheadline)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ComparisonRoleChip(title: "균형형", value: balanced?.name ?? "-", color: AppTheme.accent)
                ComparisonRoleChip(title: "공격형", value: aggressive?.name ?? "-", color: AppTheme.momentum)
                ComparisonRoleChip(title: "안정형", value: stable?.name ?? "-", color: AppTheme.positive)
                ComparisonRoleChip(title: "주의", value: caution?.name ?? "-", color: AppTheme.warning)
            }
        }
        .appCard(padding: 14)
    }

    private var verdictText: String {
        guard let balanced else {
            return "비교 데이터가 부족합니다. 먼저 점수, 성장성, 수익성 중 비어 있는 항목이 적은 종목을 우선 확인하세요."
        }
        if balanced.id == aggressive?.id, balanced.id == stable?.id {
            return "\(balanced.name)이 점수, 성장성, 수익성에서 가장 고르게 앞섭니다. 이 종목을 기준으로 나머지를 반박하는 방식으로 비교하세요."
        }
        let attack = aggressive?.name ?? "-"
        let defend = stable?.name ?? "-"
        return "기준 후보는 \(balanced.name)입니다. 수익률을 더 보려면 \(attack), 안정성을 더 보려면 \(defend)을 별도로 대조하세요."
    }

    private func bestItem(_ value: (StockComparisonItem) -> Double?) -> StockComparisonItem? {
        let candidates = items.compactMap { item -> (StockComparisonItem, Double)? in
            guard let value = value(item), value.isFinite else { return nil }
            return (item, value)
        }
        return candidates.max { $0.1 < $1.1 }?.0
    }

    private func average(_ values: [Double?]) -> Double? {
        let clean = values.compactMap { value -> Double? in
            guard let value, value.isFinite else { return nil }
            return value
        }
        guard !clean.isEmpty else { return nil }
        return clean.reduce(0, +) / Double(clean.count)
    }

    private func missingMetricCount(_ item: StockComparisonItem) -> Int {
        [
            item.score,
            item.expectedReturn,
            item.revenueGrowth,
            item.roic,
            item.grossMargin,
            item.fcfMargin
        ].filter { $0 == nil }.count
    }
}

private struct ComparisonRoleChip: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(color)
            Text(value)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(9)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct ComparisonMomentumCard: View {
    let items: [StockComparisonItem]

    private var chartItems: [StockComparisonItem] {
        items.filter { item in
            guard let value = item.return1M else { return false }
            return value.isFinite
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("단기 흐름")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)

            if chartItems.isEmpty {
                Text("1개월 수익률 데이터가 들어오면 차트 흐름을 같이 비교합니다.")
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.secondaryText)
            } else {
                Chart(chartItems) { item in
                    if let value = item.return1M {
                        BarMark(
                            x: .value("1개월", value * 100),
                            y: .value("종목", item.name)
                        )
                        .foregroundStyle(value >= 0 ? AppTheme.positive : AppTheme.negative)
                        .cornerRadius(4)
                    }
                }
                .frame(height: max(116, CGFloat(chartItems.count) * 40))
                .chartLegend(.hidden)
                .chartXAxis {
                    AxisMarks { axis in
                        AxisGridLine()
                        AxisValueLabel {
                            if let value = axis.as(Double.self) {
                                Text("\(Int(value))%")
                            }
                        }
                    }
                }
                .chartYAxis {
                    AxisMarks { _ in
                        AxisValueLabel()
                    }
                }
            }
        }
        .appCard(padding: 14)
    }
}

private struct ComparisonNewsReactionCard: View {
    let items: [StockComparisonItem]
    let newsItems: [NewsItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("뉴스 반응")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)

            ForEach(items) { item in
                let matchedNews = comparisonNewsItem(for: item, in: newsItems)
                HStack(alignment: .top, spacing: 9) {
                    CompanyLogoView(ticker: item.ticker, currency: item.currency, size: 28)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text(item.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AppTheme.primaryText)
                                .lineLimit(1)
                            Text(comparisonNewsText(for: item, in: newsItems))
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(comparisonNewsColor(for: item, in: newsItems))
                                .monospacedDigit()
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(comparisonNewsColor(for: item, in: newsItems).opacity(0.09), in: Capsule())
                        }

                        Text(matchedNews?.title ?? "관련 뉴스 반응 데이터가 아직 없습니다.")
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(2)
                    }
                    Spacer(minLength: 0)
                }

                if item.id != items.last?.id {
                    Divider()
                }
            }
        }
        .appCard(padding: 14)
    }
}

private struct ComparisonInterpretationCard: View {
    let items: [StockComparisonItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("확인 포인트")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            ForEach(items) { item in
                HStack(alignment: .top, spacing: 9) {
                    CompanyLogoView(ticker: item.ticker, currency: item.currency, size: 28)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                        Text(pointText(item))
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if item.id != items.last?.id {
                    Divider()
                }
            }
        }
        .appCard(padding: 14)
    }

    private func pointText(_ item: StockComparisonItem) -> String {
        if item.expectedReturn == nil && item.fcfMargin == nil {
            return "일부 핵심 지표가 비어 있어 점수와 성장성만으로 판단하지 않도록 주의하세요."
        }
        if let expectedReturn = item.expectedReturn, expectedReturn < 0 {
            return "기대수익이 음수라면 후보 유지보다 관망 사유를 먼저 확인하는 편이 좋습니다."
        }
        if let growth = item.revenueGrowth, growth > 0.15 {
            return "매출 성장성이 강한 편입니다. ROIC와 마진이 같이 받쳐주는지 확인하세요."
        }
        return "점수, 성장성, 수익성 중 최소 두 축이 함께 좋은지 확인하세요."
    }
}

private extension View {
    func comparisonCell(width: CGFloat, alignment: Alignment, isHeader: Bool = false) -> some View {
        frame(width: width, height: isHeader ? 52 : 42, alignment: alignment)
            .padding(.horizontal, 10)
            .background(isHeader ? AppTheme.elevatedCard.opacity(0.72) : Color.clear)
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(AppTheme.hairline)
                    .frame(height: 0.5)
            }
            .font(isHeader ? .caption.weight(.semibold) : .caption)
    }
}

private func volumeText(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    return String(format: "x%.1f", value)
}

private func comparisonRankChangeText(_ value: Int?) -> String {
    guard let value else { return "-" }
    if value > 0 { return "▲\(value)" }
    if value < 0 { return "▼\(abs(value))" }
    return "유지"
}

private func comparisonRiskScore(_ item: StockComparisonItem) -> Double? {
    let values: [Double?] = [
        item.score,
        item.expectedReturn,
        item.revenueGrowth,
        item.roic,
        item.grossMargin,
        item.return1M,
        item.fcfMargin
    ]
    let available = values.compactMap { value -> Double? in
        guard let value, value.isFinite else { return nil }
        return value
    }
    guard !available.isEmpty else { return nil }

    var risk = Double(values.count - available.count) * 5
    if let expectedReturn = item.expectedReturn, expectedReturn < 0 {
        risk += min(abs(expectedReturn) * 100, 20)
    }
    if let return1M = item.return1M, return1M < 0 {
        risk += min(abs(return1M) * 120, 18)
    }
    if let volumeSurge = item.volumeSurge, volumeSurge > 3 {
        risk += 6
    }
    return risk
}

private func comparisonRiskText(_ item: StockComparisonItem) -> String {
    guard let score = comparisonRiskScore(item) else { return "데이터 부족" }
    if score >= 32 { return "높음" }
    if score >= 16 { return "보통" }
    return "낮음"
}

private func comparisonNewsItem(for item: StockComparisonItem, in newsItems: [NewsItem]) -> NewsItem? {
    let itemKeys = comparisonMatchKeys(item.ticker)
    let tickerKey = normalizedTicker(item.ticker)
    return newsItems.compactMap { news -> (NewsItem, Double)? in
        let relatedKeys = Set(([news.ticker] + news.relatedTickers).flatMap { Array(comparisonMatchKeys($0)) })
        let keyScore = itemKeys.isDisjoint(with: relatedKeys) ? 0.0 : 100.0
        let upperTitle = news.title.uppercased()
        let titleScore = upperTitle.contains(tickerKey) || upperTitle.contains(item.name.uppercased()) ? 20.0 : 0.0
        guard keyScore > 0 || titleScore > 0 else { return nil }
        let total = keyScore + titleScore + abs(news.relatedChangePct ?? 0) * 1_000 + abs(news.impactScore) * 6
        return (news, total)
    }
    .max { $0.1 < $1.1 }?
    .0
}

private func comparisonNewsText(for item: StockComparisonItem, in newsItems: [NewsItem]) -> String {
    guard let news = comparisonNewsItem(for: item, in: newsItems) else { return "-" }
    if let change = news.relatedChangePct, change.isFinite {
        return pct(change)
    }
    return news.impactLabelKo.isEmpty ? "중립" : news.impactLabelKo
}

private func comparisonNewsScore(for item: StockComparisonItem, in newsItems: [NewsItem]) -> Double? {
    guard let news = comparisonNewsItem(for: item, in: newsItems) else { return nil }
    if let change = news.relatedChangePct, change.isFinite {
        return change
    }
    let magnitude = max(abs(news.impactScore), 0.01)
    switch news.impactLabel.lowercased() {
    case "positive":
        return magnitude
    case "negative":
        return -magnitude
    default:
        return 0
    }
}

private func comparisonNewsColor(for item: StockComparisonItem, in newsItems: [NewsItem]) -> Color {
    guard let score = comparisonNewsScore(for: item, in: newsItems) else { return AppTheme.secondaryText }
    if score > 0 { return AppTheme.positive }
    if score < 0 { return AppTheme.negative }
    return AppTheme.secondaryText
}

private func comparisonMatchKeys(_ value: String) -> Set<String> {
    let normalized = normalizedTicker(value)
    guard !normalized.isEmpty else { return [] }
    var keys: Set<String> = [normalized]
    if let short = normalized.split(separator: ".").first {
        keys.insert(String(short))
    }
    let code = krCode(from: normalized)
    if !code.isEmpty {
        keys.insert(code)
        keys.insert("\(code).KS")
        keys.insert("\(code).KQ")
    }
    return keys
}

private struct RankMovementBadge: View {
    let change: Int?
    let status: String?

    private var text: String? {
        let normalized = status?.lowercased()
        if normalized == "new" { return "신규" }
        guard let change else { return nil }
        if change > 0 { return "▲\(change)" }
        if change < 0 { return "▼\(abs(change))" }
        return nil
    }

    private var color: Color {
        let normalized = status?.lowercased()
        if normalized == "new" { return AppTheme.accent }
        guard let change else { return AppTheme.secondaryText }
        if change > 0 { return AppTheme.positive }
        if change < 0 { return AppTheme.negative }
        return AppTheme.secondaryText
    }

    var body: some View {
        if let text {
            Text(text)
                .font(.system(size: 9, weight: .bold, design: .rounded))
                .foregroundStyle(color)
                .lineLimit(1)
                .monospacedDigit()
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
                .background(color.opacity(0.10), in: Capsule())
                .accessibilityLabel("순위 변화 \(text)")
        }
    }
}

private struct PortfolioRow: View {
    let stock: PortfolioStock
    let profile: InvestmentProfile
    let currency: String
    let currentPrice: Double?
    let return1M: Double?
    var comparisonMode = false
    var comparisonSelected = false
    var comparisonDisabled = false

    private var personal: PersonalizedStockInterpretation {
        personalizedStockInterpretation(profile: profile, stock: stock)
    }

    private var accessibilitySummary: String {
        [
            "\(stock.rank.map(String.init) ?? "-")위",
            localizedCompanyName(ticker: stock.ticker, currentName: stock.name, market: stock.market),
            "가격 \(portfolioPriceText(currentPrice, currency: currency))",
            "1개월 수익률 \(pct(return1M))",
            "\(personal.label) \(personal.headline)",
            comparisonMode ? (comparisonSelected ? "비교 선택됨" : "비교 선택 가능") : "상세 보기"
        ].joined(separator: ", ")
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            HStack(spacing: 8) {
                VStack(alignment: .trailing, spacing: 3) {
                    Text(stock.rank.map(String.init) ?? "-")
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundStyle(AppTheme.accent)
                        .monospacedDigit()
                    RankMovementBadge(change: stock.rankChange, status: stock.rankStatus)
                }
                .frame(width: 32, alignment: .trailing)

                CompanyLogoView(ticker: stock.ticker, currency: currency, size: 43)

                VStack(alignment: .leading, spacing: 4) {
                    Text(localizedCompanyName(ticker: stock.ticker, currentName: stock.name, market: stock.market))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)

                    HStack(spacing: 6) {
                        SectorPill(text: portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: stock.sector))
                        AnimatedPriceText(
                            text: portfolioPriceText(currentPrice, currency: currency),
                            font: .system(size: 14, weight: .regular, design: .monospaced),
                            color: AppTheme.secondaryText
                        )
                            .lineLimit(1)
                    }
                    Text("\(personal.label) · \(personal.headline)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(personal.color)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 4) {
                HStack(spacing: 8) {
                    Text(pct(return1M))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(portfolioReturnColor(return1M))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .monospacedDigit()

                    if comparisonMode {
                        Image(systemName: comparisonSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(comparisonSelected ? AppTheme.accent : AppTheme.tertiaryText)
                            .opacity(comparisonDisabled ? 0.38 : 1)
                            .accessibilityLabel(comparisonSelected ? "비교 선택됨" : "비교 선택")
                    }
                }

                DataFreshnessBadge(
                    source: stock.source ?? (stock.lastUpdated == nil ? nil : "storage"),
                    updatedAt: stock.lastUpdated ?? stock.generatedAt,
                    compact: true
                )
            }
            .frame(width: comparisonMode ? 116 : 92, alignment: .trailing)
            .padding(.trailing, 4)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(AppTheme.hairline.opacity(0.35), lineWidth: 0.7)
        }
        .contentShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
        .accessibilityAddTraits(.isButton)
    }
}

private struct SmallCapRankingRow: View {
    let rankLabel: String
    let stock: SmallCapStock
    let profile: InvestmentProfile
    let currentPrice: Double?
    let return1M: Double?
    var comparisonMode = false
    var comparisonSelected = false
    var comparisonDisabled = false

    private var currency: String {
        marketCurrency(for: stock.ticker, market: stock.market)
    }

    private var personalLine: String {
        smallCapPersonalLine(stock: stock, profile: profile)
    }

    private var accessibilitySummary: String {
        [
            "\(rankLabel)위",
            stock.name,
            "가격 \(portfolioPriceText(currentPrice, currency: currency))",
            "1개월 수익률 \(pct(return1M))",
            personalLine,
            comparisonMode ? (comparisonSelected ? "비교 선택됨" : "비교 선택 가능") : "상세 보기"
        ].joined(separator: ", ")
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            HStack(spacing: 8) {
                VStack(alignment: .trailing, spacing: 3) {
                    Text(rankLabel)
                        .font(.system(size: 16, weight: .semibold, design: .rounded))
                        .foregroundStyle(AppTheme.accent)
                        .monospacedDigit()
                    RankMovementBadge(change: stock.rankChange, status: stock.rankStatus)
                }
                .frame(width: 32, alignment: .trailing)

                CompanyLogoView(ticker: stock.ticker, currency: currency, size: 43)

                VStack(alignment: .leading, spacing: 4) {
                    Text(stock.name)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)

                    HStack(spacing: 6) {
                        SectorPill(text: stock.market ?? "스몰캡")
                        AnimatedPriceText(
                            text: portfolioPriceText(currentPrice, currency: currency),
                            font: .system(size: 14, weight: .regular, design: .monospaced),
                            color: AppTheme.secondaryText
                        )
                            .lineLimit(1)
                    }
                    Text(personalLine)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 4) {
                HStack(spacing: 8) {
                    Text(pct(return1M))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(portfolioReturnColor(return1M))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .monospacedDigit()

                    if comparisonMode {
                        Image(systemName: comparisonSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(comparisonSelected ? AppTheme.accent : AppTheme.tertiaryText)
                            .opacity(comparisonDisabled ? 0.38 : 1)
                            .accessibilityLabel(comparisonSelected ? "비교 선택됨" : "비교 선택")
                    }
                }

                DataFreshnessBadge(
                    source: stock.source ?? (stock.lastUpdated == nil ? nil : "storage"),
                    updatedAt: stock.lastUpdated ?? stock.generatedAt,
                    compact: true
                )
            }
            .frame(width: comparisonMode ? 116 : 92, alignment: .trailing)
            .padding(.trailing, 4)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(AppTheme.hairline.opacity(0.35), lineWidth: 0.7)
        }
        .contentShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
        .accessibilityAddTraits(.isButton)
    }
}

private func smallCapPersonalLine(stock: SmallCapStock, profile: InvestmentProfile) -> String {
    guard profile.isConfigured else {
        return "기준 설정 필요 · 스몰캡은 점수와 거래량을 함께 비교"
    }
    let headline = profile.headline.isEmpty ? "내 기준" : profile.headline
    let revGrowth = percentMagnitude(stock.revGrowth)
    let return1M = percentMagnitude(stock.return1M)
    let volumeSurge = stock.volumeSurge ?? 1
    if (profile.riskTolerance.contains("안정") || profile.riskTolerance.contains("보수") || profile.riskTolerance.contains("낮")) &&
        (volumeSurge >= 2.0 || return1M >= 12) {
        return "\(headline) 기준 · 변동성 먼저 제한"
    }
    if profile.style.contains("성장") && revGrowth >= 15 {
        return "\(headline) 기준 · 성장 근거 확인"
    }
    if profile.style.contains("퀄리티") && ((stock.roic ?? 0) >= 0.12 || (stock.fcfMargin ?? 0) >= 0.06) {
        return "\(headline) 기준 · 퀄리티 근거 확인"
    }
    if profile.style.contains("모멘텀") && volumeSurge >= 1.8 {
        return "\(headline) 기준 · 과열 여부 확인"
    }
    return "\(headline) 기준 · 비교 후 관찰"
}

private func percentMagnitude(_ value: Double?) -> Double {
    guard let value, value.isFinite else { return 0 }
    let magnitude = abs(value)
    return magnitude <= 1 ? magnitude * 100 : magnitude
}

func portfolioPriceText(_ value: Double?, currency: String) -> String {
    guard let value, value.isFinite else { return "-" }
    if currency == "KRW" {
        return "\(Int(value.rounded()).formatted(.number.grouping(.automatic)))원"
    }
    return fmtPx(value, currency: currency)
}

func portfolioReturnColor(_ value: Double?) -> Color {
    guard let value, value.isFinite else { return AppTheme.secondaryText }
    return value >= 0 ? AppTheme.positive : AppTheme.negative
}

private struct ExposureDashboard: View {
    let stocks: [PortfolioStock]
    let currency: String

    private var sectors: [ExposureSlice] {
        Array(sectorExposure(stocks).prefix(5))
    }

    private var factors: [ExposureSlice] {
        factorExposure(stocks)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Chart(sectors) { slice in
                SectorMark(
                    angle: .value("Weight", slice.value),
                    innerRadius: .ratio(0.58),
                    angularInset: 2
                )
                .foregroundStyle(slice.color)
            }
            .frame(height: 180)
            .chartLegend(.hidden)
            .overlay {
                VStack(spacing: 2) {
                    Text("Sector")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(pct(sectors.first?.value, signed: false))
                        .font(.headline.weight(.bold))
                }
            }

            VStack(spacing: 8) {
                ForEach(sectors) { slice in
                    ExposureRow(slice: slice)
                }
            }

            Divider()

            HStack(spacing: 14) {
                ForEach(factors) { factor in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(factor.label)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(factor.label == "Score" ? score(factor.value) : pct(factor.value, signed: false))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(factor.color)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ExposureRow: View {
    let slice: ExposureSlice

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(slice.color)
                .frame(width: 8, height: 8)
            Text(slice.label)
                .font(.caption)
                .lineLimit(1)
            Spacer()
            Text(pct(slice.value, signed: false))
                .font(.caption.weight(.semibold))
        }
    }
}

private struct RebalanceDashboard: View {
    let actions: [RebalanceAction]

    var body: some View {
        if actions.isEmpty {
            EmptyMsg(icon: "scale.3d", msg: "리밸런싱 데이터 없음")
                .frame(height: 140)
        } else {
            VStack(spacing: 10) {
                ForEach(actions) { action in
                    HStack(spacing: 10) {
                        TickerBadge(ticker: action.ticker)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(action.name)
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                            Text("현재 \(pct(action.currentWeight, signed: false)) → 목표 \(pct(action.targetWeight, signed: false))")
                                .font(.system(size: 12))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(action.action)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(action.color)
                        Text(pct(action.delta))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(action.color)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }
}



func portfolioMetrics(_ stock: PortfolioStock) -> [StaticMetric] {
    [
        StaticMetric(label: "모델 비중", value: pct(stock.weight, signed: false)),
        StaticMetric(label: "Factor Score", value: score(stock.totalScore)),
        StaticMetric(
            label: "ROIC",
            value: pct(stock.roic, signed: false),
            color: (stock.roic ?? 0) > 0.15 ? .green : .primary
        ),
        StaticMetric(
            label: "Revenue 성장",
            value: pct(stock.revGrowth),
            color: (stock.revGrowth ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
        ),
        StaticMetric(label: "Gross Margin", value: pct(stock.grossMargin, signed: false)),
        StaticMetric(
            label: "기대 수익률",
            value: pct(stock.expectedReturn),
            color: (stock.expectedReturn ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
        )
    ]
}
