import SwiftUI

private enum InsightSection: String, CaseIterable, Identifiable, Hashable {
    case earnings = "실적"
    case news = "뉴스"
    case events = "이벤트"
    case training = "훈련"

    var id: String { rawValue }

    var displayTitle: String {
        switch self {
        case .earnings:
            return "실적"
        case .news:
            return "뉴스"
        case .events:
            return "이벤트"
        case .training:
            return "훈련"
        }
    }

    var transitionIndex: Int {
        switch self {
        case .earnings: return 0
        case .news: return 1
        case .events: return 2
        case .training: return 3
        }
    }
}

struct PulseTabView: View {
    @StateObject private var vm = PulseVM()
    @StateObject private var headerIndices = MarketIndicesVM()
    @StateObject private var headerMarketIndicators = MarketIndicatorsVM()
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var comparison: ComparisonStore
    @State private var selectedMarket: Market = .us
    @State private var selectedInsight: InsightSection = .earnings
    @State private var insightTransitionEdge: Edge = .trailing
    @State private var query = ""
    @State private var sort: EarningsSort = .rank
    @State private var selectedEarnings: EarningsStock?
    @State private var selectedCalendarItem: EarningsCalendarItem?
    @State private var showHeaderSearch = false
    @State private var showHeaderMarketIndicators = false

    private var earnings: [EarningsStock] {
        vm.earnings(for: selectedMarket)
    }

    private var calendarItems: [EarningsCalendarItem] {
        vm.earningsCalendar
            .filter { $0.market.uppercased() == selectedMarket.rawValue }
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: $0.sector) }
            .sorted {
                if $0.nextEarningsDate == $1.nextEarningsDate { return $0.name < $1.name }
                return $0.nextEarningsDate < $1.nextEarningsDate
            }
    }

    private var calendarFocusItems: [EarningsCalendarItem] {
        let nearTerm = calendarItems.filter { item in
            guard let days = item.daysUntil else { return false }
            return days >= 0 && days <= 7
        }
        let source = nearTerm.isEmpty ? calendarItems : nearTerm
        return Array(
            source.sorted {
                let lhsDays = $0.daysUntil ?? Int.max
                let rhsDays = $1.daysUntil ?? Int.max
                if lhsDays != rhsDays { return lhsDays < rhsDays }
                let lhsCap = $0.marketCap ?? -Double.infinity
                let rhsCap = $1.marketCap ?? -Double.infinity
                if lhsCap != rhsCap { return lhsCap > rhsCap }
                return $0.name < $1.name
            }
            .prefix(3)
        )
    }

    private var visibleEarnings: [EarningsStock] {
        earnings
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: $0.sector) }
            .sorted(by: sortEarnings)
    }

    private var isLoading: Bool {
        if case .loading = vm.state { return true }
        return false
    }

    private var insightSelection: Binding<InsightSection> {
        Binding(
            get: { selectedInsight },
            set: { newValue in
                guard newValue != selectedInsight else { return }
                insightTransitionEdge = newValue.transitionIndex > selectedInsight.transitionIndex ? .trailing : .leading
                withAnimation(QuantMotion.segment) {
                    selectedInsight = newValue
                }
            }
        )
    }

    private var insightRemovalEdge: Edge {
        insightTransitionEdge == .trailing ? .leading : .trailing
    }

    private var contentBottomInset: CGFloat {
        16
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                QubitScreenTopHeader(
                    title: "인사이트",
                    indices: headerIndices.indices,
                    openSearch: { showHeaderSearch = true },
                    openMarketIndicators: { showHeaderMarketIndicators = true }
                )
                insightHeader
                insightContent
            }
            .ignoresSafeArea(.container, edges: .bottom)
            .toolbar(.hidden, for: .navigationBar)
            .appScreenBackground()
            .overlay(alignment: .top) {
                LoadingOverlay(isVisible: selectedInsight != .news && isLoading && (!earnings.isEmpty || !calendarItems.isEmpty))
            }
        }
        .task { await headerIndices.load() }
        .task { await vm.load() }
        .task(id: selectedInsight) {
            await ensureSelectedInsightLoaded()
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
        .fullScreenCover(item: $selectedCalendarItem) { item in
            StockDetailSheet(
                ticker: item.ticker,
                name: item.name,
                currency: marketCurrency(for: item.ticker, market: item.market),
                staticMetrics: earningsCalendarMetrics(item),
                investmentSignals: earningsCalendarSignals(item)
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

    private var insightHeader: some View {
        VStack(alignment: .leading, spacing: 0) {
            AppSegmentSwitch(options: InsightSection.allCases, selection: insightSelection) { section in
                section.displayTitle
            }
        }
        .padding(.horizontal)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .background(AppTheme.background)
    }

    @ViewBuilder
    private var insightContent: some View {
        ZStack {
            insightPage
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .id(selectedInsight)
                .transition(
                    .asymmetric(
                        insertion: .quantRoute(edge: insightTransitionEdge, distance: 30),
                        removal: .quantRoute(edge: insightRemovalEdge, distance: 22)
                    )
                )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(.container, edges: .bottom)
        .animation(QuantMotion.segment, value: selectedInsight)
    }

    @ViewBuilder
    private var insightPage: some View {
        switch selectedInsight {
        case .earnings:
            pulseContent(showCalendar: false, showMomentum: true)
        case .news:
            NewsTabView(
                navigationTitle: "인사이트",
                navigationDisplayMode: .inline,
                usesInlineSearch: true,
                showsControls: false,
                showsSummary: false,
                usesImpactFeed: true
            )
        case .events:
            pulseContent(showCalendar: true, showMomentum: false)
        case .training:
            BlindFinancialQuizView()
        }
    }

    @ViewBuilder
    private func pulseContent(showCalendar: Bool, showMomentum: Bool) -> some View {
        if isInitialLoading {
            LoadingStateView(
                title: showCalendar ? "이벤트 로딩 중" : "실적 모멘텀 로딩 중",
                detail: "\(selectedMarket.title) \(showCalendar ? "예정 실적" : "실적 모멘텀")을 확인하고 있습니다."
            )
            .frame(maxWidth: .infinity)
            .frame(maxHeight: .infinity)
            .padding(.horizontal, 16)
            .padding(.top, 28)
            .appScreenBackground()
        } else if let error = emptyError {
            ErrView(msg: error, retry: refresh)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .appScreenBackground()
        } else {
            pulseList(showCalendar: showCalendar, showMomentum: showMomentum)
        }
    }

    private var totalCalendarCount: Int {
        vm.earningsCalendar.filter { $0.market.uppercased() == selectedMarket.rawValue }.count
    }

    private var isInitialLoading: Bool {
        if case .idle = vm.state { return true }
        if case .loading = vm.state, earnings.isEmpty, calendarItems.isEmpty { return true }
        return false
    }

    private var emptyError: String? {
        if case .failure(let error) = vm.state, earnings.isEmpty, calendarItems.isEmpty {
            return error
        }
        return nil
    }

    private func pulseList(showCalendar: Bool, showMomentum: Bool) -> some View {
        List {
            insightControls(showCalendar: showCalendar)

            if let warning = vm.warning {
                Section {
                    InlineWarningBanner(msg: warning, retry: refresh)
                        .listRowBackground(AppTheme.card)
                }
            }

            if showCalendar {
                if !calendarFocusItems.isEmpty {
                    Section {
                        EarningsCalendarFocusCard(
                            items: calendarFocusItems,
                            totalCount: totalCalendarCount,
                            open: { selectedCalendarItem = $0 }
                        )
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowBackground(Color.clear)
                    }
                }

                Section("어닝 캘린더 (\(calendarItems.count)개 예정)") {
                    if calendarItems.isEmpty {
                        EmptyMsg(
                            icon: "calendar.badge.clock",
                            msg: "예정 실적 없음",
                            detail: calendarEmptyDetail,
                            actionTitle: "새로고침",
                            action: refresh
                        )
                    } else {
                        EarningsCalendarMonthCard(items: calendarItems) { item in
                            selectedCalendarItem = item
                        }
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowBackground(Color.clear)
                    }
                }
            }

            if showMomentum {
                Section("실적 모멘텀 (\(visibleEarnings.count)/\(earnings.count)개)") {
                    if visibleEarnings.isEmpty {
                        EmptyMsg(
                            icon: earnings.isEmpty ? "arrow.clockwise.circle" : "chart.bar.doc.horizontal",
                            msg: earnings.isEmpty ? "실적 모멘텀 데이터 없음" : "실적 모멘텀 없음",
                            detail: pulseEmptyDetail,
                            actionTitle: earnings.isEmpty ? "새로고침" : nil,
                            action: earnings.isEmpty ? { refresh() } : nil
                        )
                    } else {
                        ForEach(visibleEarnings) { stock in
                            EarningsRow(stock: stock)
                                .onTapGesture { selectedEarnings = stock }
                                .listRowBackground(AppTheme.card)
                        }
                    }
                }
            }

            if !vm.macro.isEmpty {
                Section("시장 배경") {
                    RegimeCard(macro: vm.macro, market: selectedMarket)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .appTabBarInset(contentBottomInset)
        .appScreenBackground()
        .refreshable { await vm.refresh() }
        .ignoresSafeArea(.container, edges: .bottom)
    }

    private func insightControls(showCalendar: Bool) -> some View {
        Section {
            VStack(spacing: 8) {
                MarketPicker(market: $selectedMarket)
                HStack(spacing: 8) {
                    AppSearchField(text: $query, prompt: "티커, 종목명, 섹터 검색")
                    SortMenu(selection: $sort, compact: true)
                }
                SearchStatusLine(
                    query: query,
                    visibleCount: showCalendar ? calendarItems.count : visibleEarnings.count,
                    totalCount: showCalendar ? totalCalendarCount : earnings.count,
                    label: "\(selectedMarket.title) \(showCalendar ? "이벤트" : "실적")",
                    isLoading: isLoading
                )
            }
            .padding(.vertical, 2)
        }
        .listRowBackground(AppTheme.card)
    }

    private var pulseEmptyDetail: String {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return earnings.isEmpty ? "현재 선택한 시장의 실적 이벤트가 비어 있습니다." : "현재 필터와 일치하는 실적 이벤트가 없습니다."
        }
        return "\"\(clean)\"와 일치하는 실적 이벤트가 없습니다."
    }

    private var calendarEmptyDetail: String {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return "\(selectedMarket.title) 예정 실적 데이터가 아직 없습니다."
        }
        return "\"\(clean)\"와 일치하는 예정 실적이 없습니다."
    }

    private func refresh() {
        Task { await vm.refresh() }
    }

    private func ensureSelectedInsightLoaded() async {
        switch selectedInsight {
        case .earnings:
            await vm.load()
        case .news:
            break
        case .events:
            await vm.load()
            await vm.ensureCalendarLoaded()
        case .training:
            break
        }
    }

    private func sortEarnings(_ lhs: EarningsStock, _ rhs: EarningsStock) -> Bool {
        switch sort {
        case .rank:
            return (lhs.rank ?? Int.max) < (rhs.rank ?? Int.max)
        case .signal:
            return (lhs.signalStrength ?? -.infinity) > (rhs.signalStrength ?? -.infinity)
        case .surprise:
            return (lhs.surprisePct ?? -.infinity) > (rhs.surprisePct ?? -.infinity)
        case .returnSince:
            return (lhs.returnSince ?? -.infinity) > (rhs.returnSince ?? -.infinity)
        case .daysSince:
            return (lhs.daysSince ?? .infinity) < (rhs.daysSince ?? .infinity)
        }
    }
}

private struct RegimeCard: View {
    let macro: [String: String]
    let market: Market

    private var regime: String {
        macro["Regime"] ?? "-"
    }

    private var tone: Color {
        regimeColor(normalizedRegime(regime))
    }

    private var signals: [MacroSignal] {
        macroRiskSignals(macro)
    }

    private var actionHints: [String] {
        regimeActionHints(regime)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("오늘 시장 분위기")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                    Text(regimeDecisionTitle(regime))
                        .font(.title3.weight(.bold))
                        .foregroundStyle(tone)
                    Text(regimeSummary(regime))
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                Text(regimeScoreText(macro["Regime_Score"]))
                    .font(.caption.weight(.bold))
                    .foregroundStyle(tone)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(tone.opacity(0.10)))
            }

            RegimeWeightRow(macro: macro, market: market)

            HStack(spacing: 8) {
                if let action = actionHints.first {
                    Text(action)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(tone)
                        .lineLimit(1)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 6)
                        .background(tone.opacity(0.08), in: Capsule())
                }
                Spacer(minLength: 0)
                if let generated = macro["Generated"] {
                    Text(generated)
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.tertiaryText)
                        .lineLimit(1)
                }
            }
        }
        .appCard(padding: 14)
    }
}

private struct RegimeWeightRow: View {
    let macro: [String: String]
    let market: Market

    private var prefix: String {
        market.title
    }

    var body: some View {
        HStack(spacing: 8) {
            MacroMetricTile(label: "저평가", value: macro["\(prefix)_V_Weight"] ?? "-")
            MacroMetricTile(label: "추세", value: macro["\(prefix)_M_Weight"] ?? "-")
        }
    }
}

private struct MacroMetricTile: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(1)
            Text(value)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 9)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(AppTheme.elevatedCard)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(AppTheme.hairline, lineWidth: 0.5)
                )
        )
    }
}

private struct MacroSignal: Identifiable {
    let id: String
    let title: String
    let value: String
    let signal: String
}

private struct MacroSignalTile: View {
    let signal: MacroSignal

    private var tone: Color {
        macroSignalColor(signal.signal)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(signal.title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(macroSignalText(signal.signal))
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(tone)
            }
            macroSignalValueText(title: signal.title, value: signal.value)
                .font(.caption.weight(.medium))
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(tone.opacity(0.07))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(tone.opacity(0.18), lineWidth: 0.5)
                )
        )
    }
}

private func normalizedRegime(_ value: String) -> String {
    let raw = value.trimmingCharacters(in: .whitespacesAndNewlines)
    let compact = raw.uppercased().replacingOccurrences(of: " ", with: "_").replacingOccurrences(of: "-", with: "_")
    if raw.contains("위험선호") { return "RISK_ON" }
    if raw.contains("위험회피") { return "RISK_OFF" }
    if raw.contains("중립") { return "NEUTRAL" }
    if compact == "RISK_ON" || compact == "RISK_OFF" || compact == "NEUTRAL" {
        return compact
    }
    return raw.isEmpty || raw == "-" ? "NEUTRAL" : compact
}

private func regimeSummary(_ value: String) -> String {
    switch normalizedRegime(value) {
    case "RISK_ON":
        return "시장이 주식과 성장주를 받아들이는 분위기입니다. 신규 진입은 가격 추세가 유지되는 종목부터 확인하세요."
    case "RISK_OFF":
        return "시장이 불확실성을 크게 보는 구간입니다. 후보를 보더라도 비중과 손절 기준을 먼저 정리하는 편이 좋습니다."
    default:
        return "상승과 하락 신호가 섞여 있습니다. 실적 일정과 가격 확인을 같이 보며 판단을 미루는 구간입니다."
    }
}

private func regimeDecisionTitle(_ value: String) -> String {
    switch normalizedRegime(value) {
    case "RISK_ON":
        return "위험자산 선호가 살아 있음"
    case "RISK_OFF":
        return "방어적으로 볼 장세"
    default:
        return "방향 확인이 필요한 장세"
    }
}

private func regimeActionHints(_ value: String) -> [String] {
    switch normalizedRegime(value) {
    case "RISK_ON":
        return ["모멘텀 확인", "분할 진입", "과열 체크"]
    case "RISK_OFF":
        return ["현금 비중", "방어주 우선", "손절 기준"]
    default:
        return ["관망 가능", "실적 확인", "지수 방향"]
    }
}

private func regimeScoreText(_ value: String?) -> String {
    guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
        return "판단 강도 -"
    }
    return "판단 강도 \(value)"
}

private func macroRiskSignals(_ macro: [String: String]) -> [MacroSignal] {
    [
        MacroSignal(id: "vix", title: "공포 심리", value: "VIX \(macro["VIX"] ?? "-")", signal: macro["VIX_Signal"] ?? "0"),
        MacroSignal(id: "yield", title: "금리 환경", value: "장단기 금리차 \(macro["Yield_Spread"] ?? "-")", signal: macro["Yield_Signal"] ?? "0"),
        MacroSignal(id: "sp500", title: "장기 추세", value: "S&P 200일선 \(macro["SP500_vs_200MA"] ?? "-")", signal: macro["SP500_Signal"] ?? "0"),
        MacroSignal(id: "credit", title: "신용 시장", value: macro["Credit_Conditions"] ?? "상대흐름 -", signal: macro["Credit_Signal"] ?? "0"),
        MacroSignal(id: "momentum", title: "최근 흐름", value: "S&P 1개월 \(macro["Momentum_1M"] ?? "-")", signal: macro["Momentum_Signal"] ?? "0")
    ]
}

private func macroSignalScore(_ value: String) -> Int {
    Int(Double(value.replacingOccurrences(of: "+", with: "").trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0)
}

private func macroSignalText(_ value: String) -> String {
    switch macroSignalScore(value) {
    case 1:
        return "긍정"
    case -1:
        return "주의"
    default:
        return "중립"
    }
}

private func macroSignalColor(_ value: String) -> Color {
    switch macroSignalScore(value) {
    case 1:
        return AppTheme.quality
    case -1:
        return AppTheme.warning
    default:
        return AppTheme.secondaryText
    }
}

private func macroSignalValueText(title: String, value: String) -> Text {
    let coloredTitles = ["장기 추세", "최근 흐름", "금리 환경", "신용 시장", "신용시장"]
    guard coloredTitles.contains(title),
          let match = firstSignedPercentMatch(in: value) else {
        return Text(value).foregroundColor(AppTheme.primaryText)
    }
    let prefix = String(value[..<match.range.lowerBound])
    let token = String(value[match.range])
    let suffix = String(value[match.range.upperBound...])
    return Text(prefix).foregroundColor(AppTheme.primaryText)
        + Text(token).foregroundColor(macroSignedPercentColor(match.value))
        + Text(suffix).foregroundColor(AppTheme.primaryText)
}

private func macroSignedPercentColor(_ value: Double) -> Color {
    if value > 0 { return AppTheme.positive }
    if value < 0 { return AppTheme.negative }
    return AppTheme.primaryText
}

private func firstSignedPercentMatch(in value: String) -> (range: Range<String.Index>, value: Double)? {
    guard let range = value.range(of: #"[-+]?\d+(?:\.\d+)?\s*%"#, options: .regularExpression) else {
        return nil
    }
    let token = String(value[range])
        .replacingOccurrences(of: "%", with: "")
        .replacingOccurrences(of: " ", with: "")
    guard let value = Double(token) else { return nil }
    return (range, value)
}

private struct EarningsCalendarMonthCard: View {
    let items: [EarningsCalendarItem]
    let onSelect: (EarningsCalendarItem) -> Void

    @State private var visibleMonth = earningsCalendarMonthStart(Date())
    @State private var selectedDateKey: String?

    private var groupedItems: [String: [EarningsCalendarItem]] {
        Dictionary(grouping: items, by: \.nextEarningsDate)
            .mapValues { $0.sorted(by: earningsCalendarMarketCapOrder) }
    }

    private var dateKeys: [String] {
        groupedItems.keys.sorted()
    }

    private var eventKeys: Set<String> {
        Set(dateKeys)
    }

    private var selectedItems: [EarningsCalendarItem] {
        guard let selectedDateKey else { return [] }
        return groupedItems[selectedDateKey] ?? []
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(earningsCalendarMonthTitle(visibleMonth))
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                HStack(spacing: 6) {
                    Button {
                        visibleMonth = earningsCalendarMonth(visibleMonth, adding: -1)
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.caption.weight(.bold))
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(AppTheme.secondaryText)

                    Button {
                        visibleMonth = earningsCalendarMonth(visibleMonth, adding: 1)
                    } label: {
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.bold))
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(AppTheme.secondaryText)
                }
            }

            let cells = earningsCalendarMonthCells(visibleMonth)
            VStack(spacing: 7) {
                HStack(spacing: 4) {
                    ForEach(earningsCalendarWeekdays, id: \.self) { day in
                        Text(day)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.secondaryText)
                            .frame(maxWidth: .infinity)
                    }
                }

                ForEach(0..<((cells.count + 6) / 7), id: \.self) { rowIndex in
                    HStack(spacing: 4) {
                        ForEach(0..<7, id: \.self) { colIndex in
                            let index = rowIndex * 7 + colIndex
                            if index < cells.count {
                                let cell = cells[index]
                                EarningsCalendarDateCell(
                                    cell: cell,
                                    hasEvent: cell.key.map { eventKeys.contains($0) } ?? false,
                                    isSelected: cell.key == selectedDateKey
                                ) { key in
                                    selectedDateKey = key
                                }
                                .frame(maxWidth: .infinity)
                            } else {
                                Color.clear
                                    .frame(minHeight: 42)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                    }
                }
            }

            Divider().opacity(0.45)

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(selectedDateKey.map(earningsCalendarDateTitle) ?? "날짜 선택")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                    Text("\(selectedItems.count)개 기업")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(selectedItems.isEmpty ? AppTheme.secondaryText : AppTheme.positive)
                }

                if selectedItems.isEmpty {
                    Text("선택한 날짜에 예정된 실적 발표가 없습니다.")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 6)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(selectedItems.enumerated()), id: \.offset) { index, item in
                            EarningsCalendarRow(item: item)
                                .onTapGesture { onSelect(item) }
                            if index < selectedItems.count - 1 {
                                Divider().opacity(0.55)
                            }
                        }
                    }
                }
            }
        }
        .appCard(padding: 14)
        .onAppear(perform: syncSelection)
        .onChange(of: dateKeys) { _, _ in
            syncSelection()
        }
    }

    private func syncSelection() {
        guard !dateKeys.isEmpty else {
            selectedDateKey = nil
            visibleMonth = earningsCalendarMonthStart(Date())
            return
        }
        if let selectedDateKey, dateKeys.contains(selectedDateKey) {
            return
        }
        let nextKey = dateKeys.first
        selectedDateKey = nextKey
        if let nextKey, let date = earningsCalendarDate(from: nextKey) {
            visibleMonth = earningsCalendarMonthStart(date)
        }
    }
}

private struct EarningsCalendarDateCell: View {
    let cell: EarningsCalendarCell
    let hasEvent: Bool
    let isSelected: Bool
    let onSelect: (String) -> Void

    var body: some View {
        Group {
            if let day = cell.day, let key = cell.key {
                Button {
                    onSelect(key)
                } label: {
                    VStack(spacing: 4) {
                        Text("\(day)")
                            .font(.caption.weight(isSelected || hasEvent ? .bold : .medium))
                            .foregroundStyle(AppTheme.primaryText)
                            .frame(height: 16)
                        Circle()
                            .fill(hasEvent ? AppTheme.positive : Color.clear)
                            .frame(width: hasEvent ? 5 : 3, height: hasEvent ? 5 : 3)
                    }
                    .frame(maxWidth: .infinity, minHeight: 42)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(isSelected ? AppTheme.elevatedCard : (hasEvent ? AppTheme.elevatedCard.opacity(0.42) : Color.clear))
                            .overlay {
                                if isSelected {
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(AppTheme.hairline.opacity(0.55), lineWidth: 0.6)
                                }
                            }
                    )
                }
                .buttonStyle(.plain)
            } else {
                Color.clear.frame(minHeight: 42)
            }
        }
    }
}

private struct EarningsCalendarRow: View {
    let item: EarningsCalendarItem

    var body: some View {
        HStack(spacing: 10) {
            CompanyLogoView(
                ticker: item.ticker,
                currency: marketCurrency(for: item.ticker, market: item.market),
                size: 38,
                accent: AppTheme.momentum
            )
            VStack(alignment: .leading, spacing: 3) {
                Text(earningsCalendarDisplayName(item))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    TickerBadge(ticker: item.ticker)
                    if let sector = item.sector, !sector.isEmpty {
                        SectorPill(text: portfolioIndustryLabel(ticker: item.ticker, name: item.name, sector: sector))
                    }
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text(daysUntilText(item.daysUntil))
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(earningsCalendarValueText(item))
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 9)
        .contentShape(Rectangle())
    }
}

private struct EarningsCalendarFocusCard: View {
    let items: [EarningsCalendarItem]
    let totalCount: Int
    let open: (EarningsCalendarItem) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    Circle().fill(AppTheme.accent.opacity(0.12))
                    LucideIconView(icon: .calendarClock, size: 18)
                        .foregroundStyle(AppTheme.accent)
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 4) {
                    Text("이번 주 확인할 실적")
                        .font(.headline.weight(.black))
                        .foregroundStyle(AppTheme.primaryText)
                    Text("가까운 일정과 시총이 큰 기업을 먼저 보여줍니다.")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineSpacing(3)
                }
                Spacer()
                Text("전체 \(totalCount)개")
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(AppTheme.accent)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(AppTheme.accent.opacity(0.10), in: Capsule())
            }

            VStack(spacing: 8) {
                ForEach(items) { item in
                    Button {
                        open(item)
                    } label: {
                        HStack(spacing: 10) {
                            CompanyLogoView(
                                ticker: item.ticker,
                                currency: marketCurrency(for: item.ticker, market: item.market),
                                size: 36,
                                accent: AppTheme.accent
                            )
                            VStack(alignment: .leading, spacing: 3) {
                                Text(earningsCalendarDisplayName(item))
                                    .font(.system(size: 15, weight: .black))
                                    .foregroundStyle(AppTheme.primaryText)
                                    .lineLimit(1)
                                Text("\(shortTicker(item.ticker)) · \(earningsCalendarDateTitle(item.nextEarningsDate))")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 8)
                            VStack(alignment: .trailing, spacing: 3) {
                                Text(daysUntilText(item.daysUntil))
                                    .font(.system(size: 13, weight: .black))
                                    .foregroundStyle((item.daysUntil ?? 99) <= 3 ? AppTheme.warning : AppTheme.accent)
                                    .lineLimit(1)
                                Text(earningsCalendarValueText(item))
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(AppTheme.tertiaryText)
                                    .lineLimit(1)
                            }
                        }
                        .padding(10)
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(AppTheme.elevatedCard)
                        )
                    }
                    .buttonStyle(QuantPressButtonStyle(role: .row))
                }
            }
        }
        .appCard(padding: 14)
    }
}

private func earningsCalendarValueText(_ item: EarningsCalendarItem) -> String {
    let currency = marketCurrency(for: item.ticker, market: item.market)
    guard let marketCap = item.marketCap, marketCap.isFinite else {
        return item.market.uppercased() == "KR" ? "국내" : "미국"
    }
    if currency == "KRW", marketCap >= 100_000_000 {
        return cap(marketCap, currency: currency)
    }
    if currency != "KRW", marketCap >= 1_000_000 {
        return cap(marketCap, currency: currency)
    }
    return item.market.uppercased() == "KR" ? "국내" : "미국"
}

private func earningsCalendarMarketCapOrder(_ lhs: EarningsCalendarItem, _ rhs: EarningsCalendarItem) -> Bool {
    let leftCap = lhs.marketCap ?? -Double.greatestFiniteMagnitude
    let rightCap = rhs.marketCap ?? -Double.greatestFiniteMagnitude
    if leftCap != rightCap {
        return leftCap > rightCap
    }
    if lhs.name != rhs.name {
        return lhs.name < rhs.name
    }
    return lhs.ticker < rhs.ticker
}

private func earningsCalendarDisplayName(_ item: EarningsCalendarItem) -> String {
    let localized = localizedCompanyName(ticker: item.ticker, currentName: item.name, market: item.market)
    let genericFallback = "\(shortTicker(item.ticker).uppercased()) 기업"
    if localized == genericFallback {
        return cleanExternalCalendarCompanyName(item.name, fallback: shortTicker(item.ticker))
    }
    return localized
}

private func cleanExternalCalendarCompanyName(_ value: String, fallback: String) -> String {
    var text = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return fallback }
    let suffixes = [
        ", Inc.", ", Inc", " Inc.", " Inc", " Incorporated",
        " Corporation", " Corp.", " Corp", " Company", " Co.",
        " Co", " Ltd.", " Ltd", " Limited", " plc", " PLC"
    ]
    for suffix in suffixes where text.lowercased().hasSuffix(suffix.lowercased()) {
        text = String(text.dropLast(suffix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    return text.isEmpty ? fallback : text
}

private func earningsCalendarDateTitle(_ raw: String) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.dateFormat = "yyyy-MM-dd"
    guard let date = formatter.date(from: raw) else { return raw }
    let out = DateFormatter()
    out.locale = Locale(identifier: "ko_KR")
    out.dateFormat = "M월 d일 (E)"
    return out.string(from: date)
}

private let earningsCalendarWeekdays = ["일", "월", "화", "수", "목", "금", "토"]

private struct EarningsCalendarCell {
    let date: Date?
    let day: Int?
    let key: String?
}

private func earningsCalendarDate(from raw: String) -> Date? {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.calendar = earningsCalendar()
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.date(from: raw)
}

private func earningsCalendarKey(for date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.calendar = earningsCalendar()
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: date)
}

private func earningsCalendarMonthTitle(_ month: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.calendar = earningsCalendar()
    formatter.dateFormat = "yyyy년 M월"
    return formatter.string(from: month)
}

private func earningsCalendarMonth(_ month: Date, adding value: Int) -> Date {
    earningsCalendar().date(byAdding: .month, value: value, to: month).map(earningsCalendarMonthStart) ?? month
}

private func earningsCalendarMonthStart(_ date: Date) -> Date {
    let calendar = earningsCalendar()
    let components = calendar.dateComponents([.year, .month], from: date)
    return calendar.date(from: components) ?? date
}

private func earningsCalendarMonthCells(_ month: Date) -> [EarningsCalendarCell] {
    let calendar = earningsCalendar()
    let firstDay = earningsCalendarMonthStart(month)
    guard let range = calendar.range(of: .day, in: .month, for: firstDay) else { return [] }
    let leadingBlanks = (calendar.component(.weekday, from: firstDay) - calendar.firstWeekday + 7) % 7
    var cells = Array(repeating: EarningsCalendarCell(date: nil, day: nil, key: nil), count: leadingBlanks)
    for day in range {
        guard let date = calendar.date(byAdding: .day, value: day - 1, to: firstDay) else { continue }
        cells.append(EarningsCalendarCell(date: date, day: day, key: earningsCalendarKey(for: date)))
    }
    while cells.count % 7 != 0 {
        cells.append(EarningsCalendarCell(date: nil, day: nil, key: nil))
    }
    return cells
}

private func earningsCalendar() -> Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.locale = Locale(identifier: "ko_KR")
    calendar.timeZone = TimeZone.current
    calendar.firstWeekday = 1
    return calendar
}

private func daysUntilText(_ days: Int?) -> String {
    guard let days else { return "-" }
    if days == 0 { return "오늘" }
    if days > 0 { return "\(days)일 후" }
    return "\(-days)일 전"
}

private struct EarningsRow: View {
    let stock: EarningsStock
    @EnvironmentObject private var watchlist: WatchlistStore

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                CompanyLogoView(ticker: stock.ticker, currency: marketCurrency(for: stock.ticker))
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(stock.name)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                        TickerBadge(ticker: stock.ticker)
                    }
                    if let sector = stock.sector, !sector.isEmpty {
                        SectorPill(text: portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: sector))
                    }
                }
                Spacer()
                if let signal = stock.signalStrength {
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(String(format: "%.2f", signal))
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(AppTheme.momentum)
                        Text("신호")
                            .font(.system(size: 12))
                            .foregroundStyle(AppTheme.tertiaryText)
                    }
                    .frame(width: 58, alignment: .trailing)
                }
                Button(action: toggleWatch) {
                    Image(systemName: watchlist.contains(stock.ticker) ? "heart.fill" : "heart")
                        .foregroundStyle(watchlist.contains(stock.ticker) ? .yellow : .secondary)
                }
                .buttonStyle(.plain)
            }

            HStack(spacing: 14) {
                Kpi(
                    label: "EPS",
                    value: pct(stock.surprisePct),
                    color: (stock.surprisePct ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
                )
                Kpi(
                    label: "수익률",
                    value: pct(stock.returnSince),
                    color: (stock.returnSince ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
                )
                if let daysSince = stock.daysSince {
                    Kpi(label: "경과", value: "\(Int(daysSince))일")
                }
                Spacer()
            }
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    private func toggleWatch() {
        watchlist.toggle(watchlistItem(
            ticker: stock.ticker,
            name: stock.name,
            market: nil,
            currency: marketCurrency(for: stock.ticker),
            note: "Earnings"
        ))
    }
}

func earningsMetrics(_ stock: EarningsStock) -> [StaticMetric] {
    [
        StaticMetric(
            label: "EPS 서프라이즈",
            value: pct(stock.surprisePct),
            color: (stock.surprisePct ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
        ),
        StaticMetric(
            label: "발표 후 수익",
            value: pct(stock.returnSince),
            color: (stock.returnSince ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
        ),
        StaticMetric(
            label: "Signal",
            value: stock.signalStrength.map { String(format: "%.2f", $0) } ?? "-",
            color: .purple
        ),
        StaticMetric(label: "경과일", value: stock.daysSince.map { "\(Int($0))일" } ?? "-")
    ]
}

func earningsCalendarMetrics(_ item: EarningsCalendarItem) -> [StaticMetric] {
    [
        StaticMetric(label: "예정일", value: earningsCalendarDateTitle(item.nextEarningsDate), color: AppTheme.accent),
        StaticMetric(label: "남은 기간", value: daysUntilText(item.daysUntil), color: (item.daysUntil ?? 99) <= 7 ? AppTheme.warning : AppTheme.primaryText),
        StaticMetric(label: "시가총액", value: earningsCalendarValueText(item)),
        StaticMetric(label: "시장", value: item.market)
    ]
}

func earningsCalendarSignals(_ item: EarningsCalendarItem) -> [InvestmentSignal] {
    [
        InvestmentSignal(
            title: "실적 발표 예정",
            detail: "\(earningsCalendarDisplayName(item))의 다음 실적 발표가 \(earningsCalendarDateTitle(item.nextEarningsDate))에 예정되어 있습니다.",
            systemImage: "calendar.badge.clock",
            color: AppTheme.accent
        ),
        InvestmentSignal(
            title: "체크 포인트",
            detail: "발표 전에는 컨센서스, 가이던스, 최근 가격 반응을 함께 확인하는 구간입니다.",
            systemImage: "checklist",
            color: AppTheme.secondaryText
        )
    ]
}
