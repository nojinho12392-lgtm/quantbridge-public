import Foundation

enum StockDetailState {
    case empty
    case loading(ticker: String, period: String)
    case loaded(snapshot: LoadedSnapshot)
    case partial(snapshot: LoadedSnapshot, reason: PartialReason)
    case failed(error: String, lastSnapshot: LoadedSnapshot?)
}

struct LoadedSnapshot {
    let ticker: String
    let fetchPeriod: String
    let visiblePoints: [PricePoint]
    let allIndicatorPoints: [PricePoint]
    let info: StockInfo?
    let source: String?
    let updatedAt: String?

    func visible(for period: String) -> LoadedSnapshot {
        LoadedSnapshot(
            ticker: ticker,
            fetchPeriod: fetchPeriod,
            visiblePoints: stockDetailVisiblePoints(from: allIndicatorPoints, period: period),
            allIndicatorPoints: allIndicatorPoints,
            info: info,
            source: source,
            updatedAt: updatedAt
        )
    }
}

enum PartialReason: Equatable {
    case storageSnapshotOnly
    case insufficientHistory
    case valuationMissing
}

struct DetailRequest {
    let ticker: String
    let period: String
    let forceRefresh: Bool

    init(ticker: String, period: String, forceRefresh: Bool = false) {
        self.ticker = ticker
        self.period = period
        self.forceRefresh = forceRefresh
    }

    var normalizedTicker: String {
        ticker.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    var fetchPeriod: String {
        stockDetailFetchPeriod(for: period)
    }
}

enum CacheAction {
    case useCached(LoadedSnapshot)
    case refresh(fetchPeriod: String)
    case partialRefresh(snapshot: LoadedSnapshot, reason: PartialReason, fetchPeriod: String)
}

func cacheAction(
    request: DetailRequest,
    current: StockDetailState
) -> CacheAction {
    guard let snapshot = current.lastSnapshot,
          snapshot.ticker == request.normalizedTicker else {
        return .refresh(fetchPeriod: request.fetchPeriod)
    }

    let visibleSnapshot = snapshot.visible(for: request.period)
    let partialReason = stockDetailPartialReason(snapshot: visibleSnapshot, requestedFetchPeriod: request.fetchPeriod)

    if request.forceRefresh {
        return .partialRefresh(
            snapshot: visibleSnapshot,
            reason: partialReason ?? .storageSnapshotOnly,
            fetchPeriod: request.fetchPeriod
        )
    }

    if let partialReason {
        return .partialRefresh(snapshot: visibleSnapshot, reason: partialReason, fetchPeriod: request.fetchPeriod)
    }

    return .useCached(visibleSnapshot)
}

func stockDetailFetchPeriod(for period: String) -> String {
    switch period {
    case "3y":
        return "3y"
    case "5y":
        return "5y"
    default:
        return "2y"
    }
}

func stockDetailMinimumSpanDays(for period: String) -> Int? {
    switch period {
    case "1mo":
        return 25
    case "3mo":
        return 75
    case "6mo":
        return 150
    case "1y":
        return 300
    case "2y":
        return 600
    case "3y":
        return 900
    case "5y":
        return 1_500
    default:
        return nil
    }
}

func stockDetailHistorySpanDays(_ points: [PricePoint]) -> Int? {
    guard let firstDate = points.first?.date, let lastDate = points.last?.date else {
        return nil
    }
    return max(0, Calendar(identifier: .gregorian).dateComponents([.day], from: firstDate, to: lastDate).day ?? 0)
}

func stockDetailPeriodIsEnabled(period: String, historyPoints: [PricePoint], fetchedPeriod: String) -> Bool {
    guard let spanDays = stockDetailHistorySpanDays(historyPoints),
          let requiredDays = stockDetailMinimumSpanDays(for: period) else {
        return true
    }
    if spanDays >= requiredDays {
        return true
    }

    guard let fetchedDays = stockDetailMinimumSpanDays(for: fetchedPeriod) else {
        return false
    }
    let fetchedPeriodLooksComplete = spanDays >= fetchedDays
    if fetchedPeriodLooksComplete && stockDetailFetchPeriodRank(period) > stockDetailFetchPeriodRank(fetchedPeriod) {
        return true
    }
    return false
}

func stockDetailFetchPeriodRank(_ period: String?) -> Int {
    switch period {
    case "1y":
        return 1
    case "3y":
        return 3
    case "5y":
        return 5
    case "2y":
        return 2
    default:
        return 0
    }
}

func stockDetailHistoryCoversFetchPeriod(_ points: [PricePoint], fetchPeriod: String) -> Bool {
    guard let firstDate = points.first?.date, let lastDate = points.last?.date else {
        return false
    }

    let minimumPoints: Int
    let minimumDays: Int
    switch fetchPeriod {
    case "3y":
        minimumPoints = 540
        minimumDays = 720
    case "5y":
        minimumPoints = 900
        minimumDays = 1_200
    default:
        minimumPoints = 180
        minimumDays = 240
    }

    if points.count >= minimumPoints {
        return true
    }
    return lastDate.timeIntervalSince(firstDate) >= TimeInterval(minimumDays * 24 * 60 * 60)
}

func stockDetailPreferredHistoryPoints(new: [PricePoint], previous: [PricePoint]?) -> [PricePoint] {
    guard !new.isEmpty else {
        return previous ?? []
    }
    guard let previous, !previous.isEmpty else {
        return new
    }
    let newSpan = stockDetailHistorySpanDays(new) ?? 0
    let previousSpan = stockDetailHistorySpanDays(previous) ?? 0
    return newSpan >= previousSpan ? new : previous
}

func stockDetailVisiblePoints(from points: [PricePoint], period: String) -> [PricePoint] {
    guard let lastDate = points.last?.date else { return [] }

    var calendar = Calendar(identifier: .gregorian)
    calendar.firstWeekday = 2
    let cutoff: Date?
    switch period {
    case "1mo":
        cutoff = calendar.date(byAdding: .month, value: -1, to: lastDate)
    case "3mo":
        cutoff = calendar.date(byAdding: .month, value: -3, to: lastDate)
    case "6mo":
        cutoff = calendar.date(byAdding: .month, value: -6, to: lastDate)
    case "1y":
        cutoff = calendar.date(byAdding: .year, value: -1, to: lastDate)
    case "3y":
        cutoff = calendar.date(byAdding: .year, value: -3, to: lastDate)
    case "5y":
        cutoff = calendar.date(byAdding: .year, value: -5, to: lastDate)
    default:
        cutoff = nil
    }

    guard let cutoff else { return points }
    let filtered = points.filter { $0.date >= cutoff }
    let visible = filtered.isEmpty ? points : filtered
    switch period {
    case "6mo":
        return aggregatePricePointsByTradingDayCount(visible, chunkSize: 2)
    case "1y":
        return aggregatePricePoints(visible, calendar: calendar) { date in
            let components = calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
            return "\(components.yearForWeekOfYear ?? 0)-W\(components.weekOfYear ?? 0)"
        }
    case "3y":
        guard let firstDate = visible.first?.date,
              let firstWeekStart = calendar.dateInterval(of: .weekOfYear, for: firstDate)?.start else {
            return visible
        }
        return aggregatePricePoints(visible, calendar: calendar) { date in
            let weekStart = calendar.dateInterval(of: .weekOfYear, for: date)?.start ?? date
            let weekOffset = calendar.dateComponents([.weekOfYear], from: firstWeekStart, to: weekStart).weekOfYear ?? 0
            return "W3-\(max(0, weekOffset) / 3)"
        }
    case "5y":
        return aggregatePricePoints(visible, calendar: calendar) { date in
            let components = calendar.dateComponents([.year, .month], from: date)
            return "\(components.year ?? 0)-M\(components.month ?? 0)"
        }
    default:
        return visible
    }
}

private func aggregatePricePointsByTradingDayCount(_ points: [PricePoint], chunkSize: Int) -> [PricePoint] {
    guard chunkSize > 1, !points.isEmpty else { return points }
    return stride(from: 0, to: points.count, by: chunkSize).compactMap { start in
        let end = min(start + chunkSize, points.count)
        return aggregatePricePointBucket(Array(points[start..<end]))
    }
}

private func aggregatePricePoints(
    _ points: [PricePoint],
    calendar: Calendar,
    bucketKey: (Date) -> String
) -> [PricePoint] {
    guard !points.isEmpty else { return [] }

    var buckets: [(key: String, points: [PricePoint])] = []
    for point in points {
        let key = bucketKey(calendar.startOfDay(for: point.date))
        if let last = buckets.indices.last, buckets[last].key == key {
            buckets[last].points.append(point)
        } else {
            buckets.append((key, [point]))
        }
    }

    return buckets.compactMap { aggregatePricePointBucket($0.points) }
}

private func aggregatePricePointBucket(_ points: [PricePoint]) -> PricePoint? {
    guard let first = points.first, let last = points.last else {
        return nil
    }
    let volumeValues = points.compactMap(\.volume)
    return PricePoint(
        id: last.id,
        date: last.date,
        open: first.open,
        high: points.map(\.high).max() ?? last.high,
        low: points.map(\.low).min() ?? last.low,
        close: last.close,
        volume: volumeValues.isEmpty ? nil : volumeValues.reduce(0.0, +)
    )
}

func stockDetailSnapshot(
    ticker: String,
    fetchPeriod: String,
    period: String,
    allIndicatorPoints: [PricePoint],
    info: StockInfo?,
    source: String?,
    updatedAt: String?
) -> LoadedSnapshot {
    LoadedSnapshot(
        ticker: ticker.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
        fetchPeriod: fetchPeriod,
        visiblePoints: stockDetailVisiblePoints(from: allIndicatorPoints, period: period),
        allIndicatorPoints: allIndicatorPoints,
        info: info,
        source: source,
        updatedAt: updatedAt
    )
}

private extension StockDetailState {
    var lastSnapshot: LoadedSnapshot? {
        switch self {
        case .empty, .loading:
            return nil
        case .loaded(let snapshot), .partial(let snapshot, _):
            return snapshot
        case .failed(_, let lastSnapshot):
            return lastSnapshot
        }
    }
}

func stockDetailPartialReason(snapshot: LoadedSnapshot, requestedFetchPeriod: String) -> PartialReason? {
    let source = snapshot.source?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    if source == "storage_snapshot" || source == "storage_partial" || snapshot.allIndicatorPoints.isEmpty {
        return .storageSnapshotOnly
    }
    if stockDetailFetchPeriodRank(requestedFetchPeriod) > stockDetailFetchPeriodRank(snapshot.fetchPeriod) ||
        !stockDetailHistoryCoversFetchPeriod(snapshot.allIndicatorPoints, fetchPeriod: requestedFetchPeriod) {
        return .insufficientHistory
    }
    if stockDetailValuationMissing(snapshot.info) {
        return .valuationMissing
    }
    return nil
}

func stockDetailValuationMissing(_ info: StockInfo?) -> Bool {
    info?.forwardPe == nil &&
        info?.peRatio == nil &&
        info?.priceToBook == nil
}
