import Combine
import Foundation

enum APIResult<T> {
    case idle
    case loading
    case success(T)
    case failure(String)
}

private func viewModelResult<T>(_ operation: @escaping () async throws -> T) async -> Result<T, Error> {
    do {
        return .success(try await operation())
    } catch {
        return .failure(error)
    }
}

private func cachedDataWarning(_ message: String) -> String {
    "마지막 성공 데이터를 표시 중입니다.\n\(message)"
}

@MainActor
final class PortfolioVM: ObservableObject {
    let market: Market
    private let api: APIClientProtocol

    @Published var meta: [String: String] = [:]
    @Published var stocks: [PortfolioStock] = []
    @Published var state: APIResult<Bool> = .idle
    @Published var warning: String?

    init(market: Market, api: APIClientProtocol = APIClient.shared) {
        self.market = market
        self.api = api
    }

    func load() async {
        guard case .idle = state else { return }
        await refresh()
    }

    func refresh() async {
        state = .loading
        warning = nil
        do {
            let response: PortfolioResponse = try await api
                .fetch(["portfolio", market.rawValue.lowercased()])
            meta = response.meta
            stocks = response.stocks
            state = .success(true)
        } catch {
            if stocks.isEmpty {
                state = .failure(error.localizedDescription)
            } else {
                warning = cachedDataWarning(error.localizedDescription)
                state = .success(true)
            }
        }
    }
}

@MainActor
final class SmallCapVM: ObservableObject {
    @Published var usStocks: [SmallCapStock] = []
    @Published var krStocks: [SmallCapStock] = []
    @Published var state: APIResult<Bool> = .idle
    @Published var warning: String?

    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        guard case .idle = state else { return }
        await refresh()
    }

    func refresh() async {
        state = .loading
        warning = nil

        async let us = viewModelResult { try await self.api.fetch(["smallcap", "us"]) as SmallCapResponse }
        async let kr = viewModelResult { try await self.api.fetch(["smallcap", "kr"]) as SmallCapResponse }
        let results = await (us, kr)
        var failures: [String] = []

        switch results.0 {
        case .success(let usResponse):
            usStocks = usResponse.stocks
        case .failure(let error):
            failures.append("US: \(error.localizedDescription)")
        }

        switch results.1 {
        case .success(let krResponse):
            krStocks = krResponse.stocks
        case .failure(let error):
            failures.append("KR: \(error.localizedDescription)")
        }

        if failures.isEmpty {
            state = .success(true)
        } else if !usStocks.isEmpty || !krStocks.isEmpty {
            warning = cachedDataWarning(failures.joined(separator: "\n"))
            state = .success(true)
        } else {
            state = .failure(failures.joined(separator: "\n"))
        }
    }

    func stocks(for market: Market) -> [SmallCapStock] {
        market == .us ? usStocks : krStocks
    }
}

@MainActor
final class PulseVM: ObservableObject {
    @Published var macro: [String: String] = [:]
    @Published var usEarnings: [EarningsStock] = []
    @Published var krEarnings: [EarningsStock] = []
    @Published var earningsCalendar: [EarningsCalendarItem] = []
    @Published var state: APIResult<Bool> = .idle
    @Published var warning: String?

    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        guard case .idle = state else { return }
        await refresh()
    }

    func ensureCalendarLoaded() async {
        guard earningsCalendarNeedsRefresh(earningsCalendar) else { return }
        guard !isLoading else { return }
        await refreshCalendarOnly()
    }

    func refresh() async {
        state = .loading
        warning = nil

        async let macroResult = viewModelResult { try await self.api.fetch(["macro"]) as [String: String] }
        async let us = viewModelResult { try await self.api.fetch(["earnings", "us"]) as EarningsResponse }
        async let kr = viewModelResult { try await self.api.fetch(["earnings", "kr"]) as EarningsResponse }
        async let calendar = viewModelResult {
            try await self.api.fetch(
                ["calendar", "earnings"],
                queryItems: [
                    URLQueryItem(name: "market", value: "ALL"),
                    URLQueryItem(name: "days", value: "180"),
                    URLQueryItem(name: "limit", value: "2000")
                ]
            ) as EarningsCalendarResponse
        }
        let results = await (macroResult, us, kr, calendar)
        var failures: [String] = []

        switch results.0 {
        case .success(let macro):
            self.macro = macro
        case .failure(let error):
            failures.append("Macro: \(error.localizedDescription)")
        }

        switch results.1 {
        case .success(let usResponse):
            usEarnings = usResponse.stocks.map { $0.withResolvedKrName() }
        case .failure(let error):
            failures.append("US Earnings: \(error.localizedDescription)")
        }

        switch results.2 {
        case .success(let krResponse):
            krEarnings = krResponse.stocks.map { $0.withResolvedKrName() }
        case .failure(let error):
            failures.append("KR Earnings: \(error.localizedDescription)")
        }

        switch results.3 {
        case .success(let response):
            earningsCalendar = response.items.map { $0.withResolvedKrName() }
        case .failure(let error):
            failures.append("Earnings Calendar: \(error.localizedDescription)")
        }

        if failures.isEmpty {
            state = .success(true)
        } else if !macro.isEmpty || !usEarnings.isEmpty || !krEarnings.isEmpty || !earningsCalendar.isEmpty {
            warning = cachedDataWarning(failures.joined(separator: "\n"))
            state = .success(true)
        } else {
            state = .failure(failures.joined(separator: "\n"))
        }
    }

    func earnings(for market: Market) -> [EarningsStock] {
        market == .us ? usEarnings : krEarnings
    }

    private var isLoading: Bool {
        if case .loading = state { return true }
        return false
    }

    private func refreshCalendarOnly() async {
        state = .loading
        warning = nil
        do {
            let response: EarningsCalendarResponse = try await api.fetch(
                ["calendar", "earnings"],
                queryItems: [
                    URLQueryItem(name: "market", value: "ALL"),
                    URLQueryItem(name: "days", value: "180"),
                    URLQueryItem(name: "limit", value: "2000"),
                    URLQueryItem(name: "refresh", value: "true")
                ]
            )
            earningsCalendar = response.items.map { $0.withResolvedKrName() }
            state = .success(true)
        } catch {
            if macro.isEmpty && usEarnings.isEmpty && krEarnings.isEmpty {
                state = .failure(error.localizedDescription)
            } else {
                warning = cachedDataWarning("Earnings Calendar: \(error.localizedDescription)")
                state = .success(true)
            }
        }
    }
}

private func earningsCalendarNeedsRefresh(_ items: [EarningsCalendarItem]) -> Bool {
    if items.isEmpty { return true }
    if items.count < 100 { return false }
    let signatures = Dictionary(grouping: items, by: \.nextEarningsDate)
        .values
        .compactMap { dayItems -> String? in
            let tickers = Array(Set(dayItems.map { $0.ticker.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }.filter { !$0.isEmpty })).sorted()
            return tickers.count >= 10 ? tickers.joined(separator: "|") : nil
        }
    let repeatedBucketCount = Dictionary(grouping: signatures, by: { $0 })
        .values
        .map(\.count)
        .max() ?? 0
    return repeatedBucketCount >= 4
}

@MainActor
final class SignalEventsVM: ObservableObject {
    @Published var items: [SignalEvent] = []
    @Published var state: APIResult<Bool> = .idle
    @Published var warning: String?

    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        guard case .idle = state else { return }
        await refresh()
    }

    func refresh() async {
        state = .loading
        warning = nil
        do {
            let response: SignalEventsResponse = try await api.fetch(
                ["signals", "events"],
                queryItems: [
                    URLQueryItem(name: "market", value: "ALL"),
                    URLQueryItem(name: "limit", value: "120")
                ]
            )
            items = response.items
            state = .success(true)
        } catch {
            if items.isEmpty {
                state = .failure(error.localizedDescription)
            } else {
                warning = cachedDataWarning(error.localizedDescription)
                state = .success(true)
            }
        }
    }
}

@MainActor
final class StockDetailVM: ObservableObject {
    @Published private(set) var state: StockDetailState = .empty
    @Published private(set) var indicators = ChartIndicatorSet.empty
    @Published private(set) var enabledChartPeriods: Set<String> = ["1mo", "3mo", "6mo", "1y", "3y", "5y"]

    private let api: APIClientProtocol
    private static let allChartPeriodValues = ["1mo", "3mo", "6mo", "1y", "3y", "5y"]

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    var pricePoints: [PricePoint] {
        snapshot?.visiblePoints ?? []
    }

    var indicatorPricePoints: [PricePoint] {
        snapshot?.allIndicatorPoints ?? []
    }

    var info: StockInfo? {
        snapshot?.info
    }

    var source: String? {
        snapshot?.source
    }

    var updatedAt: String? {
        snapshot?.updatedAt
    }

    var isLoading: Bool {
        if case .loading = state { return true }
        return false
    }

    var errorMsg: String? {
        switch state {
        case .empty, .loading, .loaded, .partial:
            return nil
        case .failed(let error, let lastSnapshot):
            return lastSnapshot == nil ? error : nil
        }
    }

    var partialReason: PartialReason? {
        if case .partial(_, let reason) = state { return reason }
        return nil
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    func load(ticker: String, period: String, forceRefresh: Bool = false) async {
        let request = DetailRequest(ticker: ticker, period: period, forceRefresh: forceRefresh)
        if snapshot?.ticker != request.normalizedTicker {
            enabledChartPeriods = Set(Self.allChartPeriodValues)
        }
        switch cacheAction(request: request, current: state) {
        case .useCached(let snapshot):
            state = .loaded(snapshot: snapshot)
            updateIndicators(from: snapshot)
        case .refresh(let fetchPeriod):
            state = .loading(ticker: request.normalizedTicker, period: period)
            await performFetch(request: request, fetchPeriod: fetchPeriod, lastSnapshot: nil)
        case .partialRefresh(let snapshot, let reason, let fetchPeriod):
            state = .partial(snapshot: snapshot, reason: reason)
            updateIndicators(from: snapshot)
            await performFetch(request: request, fetchPeriod: fetchPeriod, lastSnapshot: snapshot)
        }
    }

    private func performFetch(request: DetailRequest, fetchPeriod: String, lastSnapshot: LoadedSnapshot?) async {
        do {
            var queryItems = [
                URLQueryItem(name: "period", value: fetchPeriod),
                URLQueryItem(name: "profile", value: "false"),
                URLQueryItem(name: "detail_schema", value: "valuation_v1")
            ]
            if request.forceRefresh || lastSnapshot != nil {
                queryItems.append(URLQueryItem(name: "refresh", value: "true"))
            }
            let response: StockDetailResponse = try await api.fetch(["stock", request.ticker], queryItems: queryItems)
            try Task.checkCancellation()
            let interpreted = interpret(response, request: request, fetchPeriod: fetchPeriod, lastSnapshot: lastSnapshot)
            state = interpreted
            updateIndicators(from: snapshot(from: interpreted))
        } catch is CancellationError {
            if let lastSnapshot {
                state = .partial(snapshot: lastSnapshot, reason: .storageSnapshotOnly)
                updateIndicators(from: lastSnapshot)
            } else {
                state = .empty
                indicators = .empty
            }
        } catch {
            let fallback = lastSnapshot ?? snapshot?.visible(for: request.period)
            if let fallback {
                state = .failed(error: error.localizedDescription, lastSnapshot: fallback)
                updateIndicators(from: fallback)
            } else {
                state = .failed(error: error.localizedDescription, lastSnapshot: nil)
                indicators = .empty
            }
        }
    }

    private func interpret(
        _ response: StockDetailResponse,
        request: DetailRequest,
        fetchPeriod: String,
        lastSnapshot: LoadedSnapshot?
    ) -> StockDetailState {
        let allPoints = response.prices.compactMap(makePricePoint)
        let previousHistoryPoints = lastSnapshot?.allIndicatorPoints
        let historyPoints = stockDetailPreferredHistoryPoints(new: allPoints, previous: previousHistoryPoints)
        let retainedPreviousHistory = previousHistoryPoints.map { $0 == historyPoints } == true && allPoints != historyPoints
        let historyFetchPeriod = retainedPreviousHistory ? (lastSnapshot?.fetchPeriod ?? fetchPeriod) : fetchPeriod
        let snapshot = stockDetailSnapshot(
            ticker: request.ticker,
            fetchPeriod: historyFetchPeriod,
            period: request.period,
            allIndicatorPoints: historyPoints,
            info: response.info,
            source: response.source,
            updatedAt: response.updatedAt
        )

        if let error = response.error?.trimmingCharacters(in: .whitespacesAndNewlines),
           !error.isEmpty,
           allPoints.isEmpty {
            return .failed(error: error, lastSnapshot: lastSnapshot)
        }

        let source = response.source?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        if source == "storage_snapshot" || source == "storage_partial" {
            return .partial(snapshot: snapshot, reason: .storageSnapshotOnly)
        }

        if let reason = stockDetailPartialReason(snapshot: snapshot, requestedFetchPeriod: fetchPeriod) {
            return .partial(snapshot: snapshot, reason: reason)
        }

        switch source {
        case "storage", "live", "":
            return .loaded(snapshot: snapshot)
        default:
            if allPoints.isEmpty {
                return .partial(snapshot: snapshot, reason: .storageSnapshotOnly)
            }
            return .loaded(snapshot: snapshot)
        }
    }

    private func makePricePoint(_ raw: RawPricePoint) -> PricePoint? {
        guard let date = Self.dateFormatter.date(from: raw.date) else {
            return nil
        }
        return PricePoint(
            id: raw.date,
            date: date,
            open: raw.open,
            high: raw.high,
            low: raw.low,
            close: raw.close,
            volume: raw.volume
        )
    }

    private func updateIndicators(from snapshot: LoadedSnapshot?) {
        guard let snapshot else {
            indicators = .empty
            enabledChartPeriods = Set(Self.allChartPeriodValues)
            return
        }
        indicators = ChartIndicatorSet(points: snapshot.visiblePoints)
        let enabled = Self.allChartPeriodValues.filter {
            stockDetailPeriodIsEnabled(
                period: $0,
                historyPoints: snapshot.allIndicatorPoints,
                fetchedPeriod: snapshot.fetchPeriod
            )
        }
        enabledChartPeriods = Set(enabled.isEmpty ? ["1mo"] : enabled)
    }

    private var snapshot: LoadedSnapshot? {
        snapshot(from: state)
    }

    private func snapshot(from state: StockDetailState) -> LoadedSnapshot? {
        switch state {
        case .empty, .loading:
            return nil
        case .loaded(let snapshot), .partial(let snapshot, _):
            return snapshot
        case .failed(_, let lastSnapshot):
            return lastSnapshot
        }
    }
}
