import SwiftUI

struct SmallCapTabView: View {
    @StateObject private var vm = SmallCapVM()
    @State private var selectedMarket: Market = .us
    @State private var query = ""
    @State private var sort: SmallCapSort = .rank
    @State private var selected: SmallCapStock?

    private var stocks: [SmallCapStock] {
        vm.stocks(for: selectedMarket)
    }

    private var visibleStocks: [SmallCapStock] {
        stocks
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name) }
            .sorted(by: sortSmallCap)
    }

    private var isLoading: Bool {
        if case .loading = vm.state { return true }
        return false
    }

    var body: some View {
        NavigationStack {
            Group {
                if isInitialLoading {
                    LoadingStateView(
                        title: "스몰캡 후보 로딩 중",
                        detail: "\(selectedMarket.title) 소형주 데이터를 확인하고 있습니다."
                    )
                } else if let error = emptyError {
                    ErrView(msg: error, retry: refresh)
                } else {
                    list
                }
            }
            .navigationTitle("스몰캡")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    SortMenu(selection: $sort)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: refresh) {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(isLoading)
                }
            }
            .safeAreaInset(edge: .top) {
                VStack(spacing: 6) {
                    MarketPicker(market: $selectedMarket)
                    SearchStatusLine(
                        query: query,
                        visibleCount: visibleStocks.count,
                        totalCount: stocks.count,
                        label: "\(selectedMarket.title) 스몰캡",
                        isLoading: isLoading
                    )
                }
                .padding(.horizontal)
                .padding(.vertical, 6)
                .background(AppTheme.background)
            }
            .overlay(alignment: .top) {
                LoadingOverlay(isVisible: isLoading && !stocks.isEmpty)
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "티커 또는 종목명 검색")
        .task { await vm.load() }
        .fullScreenCover(item: $selected) { stock in
            StockDetailSheet(
                ticker: stock.ticker,
                name: stock.name,
                currency: marketCurrency(for: stock.ticker, market: stock.market),
                staticMetrics: smallCapMetrics(stock),
                investmentSignals: smallCapSignals(stock)
            )
        }
    }

    private var isInitialLoading: Bool {
        if case .idle = vm.state { return true }
        if case .loading = vm.state, stocks.isEmpty { return true }
        return false
    }

    private var emptyError: String? {
        if case .failure(let error) = vm.state, stocks.isEmpty {
            return error
        }
        return nil
    }

    private var list: some View {
        List {
            if let warning = vm.warning {
                Section {
                    InlineWarningBanner(msg: warning, retry: refresh)
                        .listRowBackground(AppTheme.card)
                }
            }

            Section("\(selectedMarket.title) 스몰캡 후보 (\(visibleStocks.count)/\(stocks.count)개)") {
                if visibleStocks.isEmpty {
                    EmptyMsg(
                        icon: stocks.isEmpty ? "arrow.clockwise.circle" : "sparkles",
                        msg: stocks.isEmpty ? "스몰캡 데이터 없음" : "스몰캡 후보 없음",
                        detail: smallCapEmptyDetail,
                        actionTitle: stocks.isEmpty ? "새로고침" : nil,
                        action: stocks.isEmpty ? { refresh() } : nil
                    )
                } else {
                    ForEach(visibleStocks) { stock in
                        SmallCapRow(stock: stock)
                            .onTapGesture { selected = stock }
                            .listRowBackground(AppTheme.card)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .appScreenBackground()
        .refreshable { await vm.refresh() }
    }

    private var smallCapEmptyDetail: String {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return stocks.isEmpty ? "현재 선택한 시장의 스몰캡 데이터가 비어 있습니다." : "현재 필터와 일치하는 후보가 없습니다."
        }
        return "\"\(clean)\"와 일치하는 후보가 없습니다."
    }

    private func refresh() {
        Task { await vm.refresh() }
    }

    private func sortSmallCap(_ lhs: SmallCapStock, _ rhs: SmallCapStock) -> Bool {
        switch sort {
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

struct SmallCapRow: View {
    let stock: SmallCapStock
    var comparisonMode = false
    var comparisonSelected = false
    var comparisonDisabled = false
    @EnvironmentObject private var watchlist: WatchlistStore

    private var currency: String {
        marketCurrency(for: stock.ticker, market: stock.market)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                CompanyLogoView(ticker: stock.ticker, currency: currency)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(stock.name)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                        TickerBadge(ticker: stock.ticker)
                    }
                    Text(cap(stock.marketCap, currency: currency))
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.secondaryText)
                }
                Spacer()
                if let score = stock.totalScore, score.isFinite {
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(String(format: "%.0f", score))
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(AppTheme.warning)
                        Text("점")
                            .font(.system(size: 12))
                            .foregroundStyle(AppTheme.tertiaryText)
                    }
                }
                Button(action: toggleWatch) {
                    Image(systemName: watchlist.contains(stock.ticker) ? "heart.fill" : "heart")
                        .foregroundStyle(watchlist.contains(stock.ticker) ? .yellow : .secondary)
                }
                .buttonStyle(.plain)
                if comparisonMode {
                    Image(systemName: comparisonSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(comparisonSelected ? AppTheme.accent : AppTheme.tertiaryText)
                        .opacity(comparisonDisabled ? 0.38 : 1)
                        .accessibilityLabel(comparisonSelected ? "비교 선택됨" : "비교 선택")
                }
            }

            HStack(spacing: 14) {
                Kpi(
                    label: "Rev",
                    value: pct(stock.revGrowth),
                    color: (stock.revGrowth ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
                )
                Kpi(label: "ROIC", value: pct(stock.roic, signed: false))
                Kpi(label: "Margin", value: pct(stock.grossMargin, signed: false))
                if let volumeSurge = stock.volumeSurge, volumeSurge.isFinite {
                    Kpi(label: "Vol", value: String(format: "x%.1f", volumeSurge))
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
            market: stock.market,
            currency: currency,
            note: "스몰캡"
        ))
    }
}

func smallCapMetrics(_ stock: SmallCapStock) -> [StaticMetric] {
    let currency = marketCurrency(for: stock.ticker, market: stock.market)
    return [
        StaticMetric(
            label: "스몰캡 점수",
            value: stock.totalScore.map { String(format: "%.0f", $0) } ?? "-",
            color: .orange
        ),
        StaticMetric(
            label: "RevGrowth",
            value: pct(stock.revGrowth),
            color: (stock.revGrowth ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
        ),
        StaticMetric(label: "ROIC", value: pct(stock.roic, signed: false)),
        StaticMetric(label: "Gross Margin", value: pct(stock.grossMargin, signed: false)),
        StaticMetric(label: "FCF Margin", value: pct(stock.fcfMargin, signed: false)),
        StaticMetric(label: "시가총액", value: cap(stock.marketCap, currency: currency))
    ]
}
