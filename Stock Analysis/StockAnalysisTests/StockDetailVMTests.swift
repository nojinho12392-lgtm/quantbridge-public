import Foundation
import XCTest
@testable import Stock_Analysis

@MainActor
final class StockDetailVMTests: XCTestCase {
    func testCacheActionEmptyRefreshesDefaultFetchPeriod() {
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "6mo"), current: .empty)
        assertRefresh(action, fetchPeriod: "2y")
    }

    func testCacheActionLoadingStillRefreshes() {
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "6mo"), current: .loading(ticker: "AAPL", period: "6mo"))
        assertRefresh(action, fetchPeriod: "2y")
    }

    func testCacheActionDifferentTickerRefreshes() throws {
        let snapshot = try makeSnapshot(ticker: "MSFT", fetchPeriod: "2y")
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "6mo"), current: .loaded(snapshot: snapshot))
        assertRefresh(action, fetchPeriod: "2y")
    }

    func testCacheActionSameTickerUsesCachedSnapshot() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y")
        let action = cacheAction(request: DetailRequest(ticker: "aapl", period: "6mo"), current: .loaded(snapshot: snapshot))
        let cached = try unwrapCached(action)
        XCTAssertEqual(cached.ticker, "AAPL")
        XCTAssertEqual(cached.fetchPeriod, "2y")
    }

    func testCacheActionShorterVisiblePeriodKeepsAllIndicatorHistory() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y", points: makePricePoints(count: 260))
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "1mo"), current: .loaded(snapshot: snapshot))
        let cached = try unwrapCached(action)
        XCTAssertLessThan(cached.visiblePoints.count, cached.allIndicatorPoints.count)
        XCTAssertEqual(cached.allIndicatorPoints.count, 260)
    }

    func testCacheActionForceRefreshKeepsSnapshotVisibleWhileRefreshing() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y")
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "6mo", forceRefresh: true), current: .loaded(snapshot: snapshot))
        let partial = try unwrapPartialRefresh(action)
        XCTAssertEqual(partial.snapshot.ticker, "AAPL")
        XCTAssertEqual(partial.fetchPeriod, "2y")
    }

    func testCacheActionMissingValuationRequestsPartialRefresh() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y", info: makeInfo(valuation: false))
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "6mo"), current: .loaded(snapshot: snapshot))
        let partial = try unwrapPartialRefresh(action)
        XCTAssertEqual(partial.reason, .valuationMissing)
    }

    func testCacheActionStorageSnapshotRequestsPartialRefresh() throws {
        let snapshot = try makeSnapshot(ticker: "CYBR", fetchPeriod: "2y", points: [], source: "storage_snapshot")
        let action = cacheAction(request: DetailRequest(ticker: "CYBR", period: "6mo"), current: .partial(snapshot: snapshot, reason: .storageSnapshotOnly))
        let partial = try unwrapPartialRefresh(action)
        XCTAssertEqual(partial.reason, .storageSnapshotOnly)
    }

    func testCacheActionStoragePartialRequestsPartialRefresh() throws {
        let snapshot = try makeSnapshot(ticker: "CYBR", fetchPeriod: "2y", source: "storage_partial")
        let action = cacheAction(request: DetailRequest(ticker: "CYBR", period: "6mo"), current: .loaded(snapshot: snapshot))
        let partial = try unwrapPartialRefresh(action)
        XCTAssertEqual(partial.reason, .storageSnapshotOnly)
    }

    func testCacheActionLongerPeriodNeedsLongerHistory() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y", points: makePricePoints(count: 260))
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "5y"), current: .loaded(snapshot: snapshot))
        let partial = try unwrapPartialRefresh(action)
        XCTAssertEqual(partial.reason, .insufficientHistory)
        XCTAssertEqual(partial.fetchPeriod, "5y")
    }

    func testCacheActionFailedStateCanReuseLastSnapshot() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y")
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "6mo"), current: .failed(error: "timeout", lastSnapshot: snapshot))
        let cached = try unwrapCached(action)
        XCTAssertEqual(cached.ticker, "AAPL")
    }

    func testCacheActionShortThreeYearHistoryRefreshes() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "3y", points: makePricePoints(count: 120))
        let action = cacheAction(request: DetailRequest(ticker: "AAPL", period: "3y"), current: .loaded(snapshot: snapshot))
        let partial = try unwrapPartialRefresh(action)
        XCTAssertEqual(partial.reason, .insufficientHistory)
    }

    func testCacheActionUnknownPeriodUsesDefaultFetchPeriod() throws {
        let snapshot = try makeSnapshot(ticker: "AAPL", fetchPeriod: "2y")
        let action = cacheAction(request: DetailRequest(ticker: " AAPL ", period: "max"), current: .loaded(snapshot: snapshot))
        let cached = try unwrapCached(action)
        XCTAssertEqual(cached.fetchPeriod, "2y")
    }

    func testLoadStorageResponseBecomesLoaded() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 220)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        guard case .loaded(let snapshot) = vm.state else {
            return XCTFail("Expected loaded, got \(vm.state)")
        }
        XCTAssertEqual(api.fetchCallCount, 1)
        XCTAssertEqual(snapshot.ticker, "AAPL")
        XCTAssertFalse(vm.pricePoints.isEmpty)
    }

    func testLoadStorageSnapshotBecomesPartial() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage_snapshot", prices: []),
            for: ["stock", "CYBR"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "CYBR", period: "6mo")
        guard case .partial(let snapshot, let reason) = vm.state else {
            return XCTFail("Expected partial, got \(vm.state)")
        }
        XCTAssertEqual(reason, .storageSnapshotOnly)
        XCTAssertEqual(snapshot.source, "storage_snapshot")
    }

    func testLoadLiveMissingValuationBecomesPartial() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "live", prices: makePricePoints(count: 220), valuation: false),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        guard case .partial(_, let reason) = vm.state else {
            return XCTFail("Expected valuation partial, got \(vm.state)")
        }
        XCTAssertEqual(reason, .valuationMissing)
    }

    func testLoadEmptyResponseWithoutErrorIsStorageSnapshotPartial() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: nil, prices: []),
            for: ["stock", "EMPTY"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "EMPTY", period: "6mo")
        guard case .partial(let snapshot, let reason) = vm.state else {
            return XCTFail("Expected empty partial, got \(vm.state)")
        }
        XCTAssertEqual(reason, .storageSnapshotOnly)
        XCTAssertTrue(snapshot.visiblePoints.isEmpty)
    }

    func testLoadEmptyResponseWithErrorFailsWithoutSnapshot() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: nil, prices: [], error: "ticker not found"),
            for: ["stock", "NOPE"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "NOPE", period: "6mo")
        guard case .failed(let error, nil) = vm.state else {
            return XCTFail("Expected failed without snapshot, got \(vm.state)")
        }
        XCTAssertTrue(error.contains("ticker not found"))
    }

    func testSecondLoadSamePeriodUsesCacheWithoutNetwork() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 220)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        await vm.load(ticker: "AAPL", period: "6mo")
        XCTAssertEqual(api.fetchCallCount, 1)
        XCTAssertFalse(vm.pricePoints.isEmpty)
    }

    func testChangingToShorterPeriodUsesCachedHistory() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 260)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        let sixMonthCount = vm.pricePoints.count
        await vm.load(ticker: "AAPL", period: "1mo")
        XCTAssertEqual(api.fetchCallCount, 1)
        XCTAssertLessThan(vm.pricePoints.count, sixMonthCount)
    }

    func testLongerPeriodTriggersRefreshWithRefreshFlag() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 260)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 980)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "5y", refresh: true)
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        await vm.load(ticker: "AAPL", period: "5y")
        guard case .loaded(let snapshot) = vm.state else {
            return XCTFail("Expected loaded after longer refresh, got \(vm.state)")
        }
        XCTAssertEqual(api.fetchCallCount, 2)
        XCTAssertEqual(snapshot.fetchPeriod, "5y")
    }

    func testFailureAfterCachedLoadKeepsLastSnapshot() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 220)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        api.setFetchError(
            "timeout",
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y", refresh: true)
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        await vm.load(ticker: "AAPL", period: "6mo", forceRefresh: true)
        guard case .failed(let error, let lastSnapshot?) = vm.state else {
            return XCTFail("Expected stale failed state, got \(vm.state)")
        }
        XCTAssertTrue(error.contains("timeout"))
        XCTAssertFalse(lastSnapshot.visiblePoints.isEmpty)
    }

    func testForceRefreshUsesRefreshFlagAndUpdatesSnapshot() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 220, closeStart: 100)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y")
        )
        api.setFetchResponse(
            try makeResponse(source: "storage", prices: makePricePoints(count: 221, closeStart: 110)),
            for: ["stock", "AAPL"],
            queryItems: detailQuery(period: "2y", refresh: true)
        )
        let vm = StockDetailVM(api: api)
        await vm.load(ticker: "AAPL", period: "6mo")
        await vm.load(ticker: "AAPL", period: "6mo", forceRefresh: true)
        XCTAssertEqual(api.fetchCallCount, 2)
        XCTAssertEqual(vm.indicatorPricePoints.last?.close, 330)
    }
}

private func detailQuery(period: String, refresh: Bool = false) -> [URLQueryItem] {
    var items = [
        URLQueryItem(name: "period", value: period),
        URLQueryItem(name: "profile", value: "false"),
        URLQueryItem(name: "detail_schema", value: "valuation_v1")
    ]
    if refresh {
        items.append(URLQueryItem(name: "refresh", value: "true"))
    }
    return items
}

private func assertRefresh(
    _ action: CacheAction,
    fetchPeriod: String,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    guard case .refresh(let period) = action else {
        return XCTFail("Expected refresh, got \(action)", file: file, line: line)
    }
    XCTAssertEqual(period, fetchPeriod, file: file, line: line)
}

private func unwrapCached(
    _ action: CacheAction,
    file: StaticString = #filePath,
    line: UInt = #line
) throws -> LoadedSnapshot {
    guard case .useCached(let snapshot) = action else {
        XCTFail("Expected cached action, got \(action)", file: file, line: line)
        throw MockAPIClientError.typeMismatch("cacheAction")
    }
    return snapshot
}

private func unwrapPartialRefresh(
    _ action: CacheAction,
    file: StaticString = #filePath,
    line: UInt = #line
) throws -> (snapshot: LoadedSnapshot, reason: PartialReason, fetchPeriod: String) {
    guard case .partialRefresh(let snapshot, let reason, let fetchPeriod) = action else {
        XCTFail("Expected partial refresh, got \(action)", file: file, line: line)
        throw MockAPIClientError.typeMismatch("cacheAction")
    }
    return (snapshot, reason, fetchPeriod)
}

private func makeSnapshot(
    ticker: String,
    fetchPeriod: String,
    points: [PricePoint] = makePricePoints(count: 220),
    source: String? = "storage",
    info: StockInfo? = nil
) throws -> LoadedSnapshot {
    let resolvedInfo: StockInfo
    if let info {
        resolvedInfo = info
    } else {
        resolvedInfo = try makeInfo(valuation: true)
    }
    return stockDetailSnapshot(
        ticker: ticker,
        fetchPeriod: fetchPeriod,
        period: "6mo",
        allIndicatorPoints: points,
        info: resolvedInfo,
        source: source,
        updatedAt: "2026-05-20T00:00:00Z"
    )
}

private func makeResponse(
    source: String?,
    prices: [PricePoint],
    valuation: Bool = true,
    error: String? = nil
) throws -> StockDetailResponse {
    let sourceLine = source.map { #""source": "\#($0)","# } ?? ""
    let errorLine = error.map { #""error": "\#($0)","# } ?? #""error": null,"#
    let priceJSON = prices.map { point in
        """
        {
          "date": "\(point.id)",
          "open": \(point.open),
          "high": \(point.high),
          "low": \(point.low),
          "close": \(point.close),
          "volume": \(point.volume ?? 1000)
        }
        """
    }.joined(separator: ",")
    let valuationFields = valuation ? #""pe_ratio": 25.0, "forward_pe": 23.0, "price_to_book": 6.1,"# : ""
    return try decodeFixture(
        StockDetailResponse.self,
        """
        {
          "prices": [\(priceJSON)],
          "info": {
            "name": "애플",
            "current_price": 190.0,
            "prev_close": 188.0,
            \(valuationFields)
            "market_cap": 3000000000000,
            "sector": "Technology"
          },
          \(errorLine)
          \(sourceLine)
          "updated_at": "2026-05-20T00:00:00Z"
        }
        """
    )
}

private func makeInfo(valuation: Bool) throws -> StockInfo {
    let valuationFields = valuation ? #""pe_ratio": 25.0, "forward_pe": 23.0, "price_to_book": 6.1,"# : ""
    return try JSONDecoder().decode(
        StockInfo.self,
        from: Data(
            """
            {
              "name": "애플",
              "current_price": 190.0,
              "prev_close": 188.0,
              \(valuationFields)
              "market_cap": 3000000000000,
              "sector": "Technology"
            }
            """.utf8
        )
    )
}

private func makePricePoints(count: Int, closeStart: Double = 100) -> [PricePoint] {
    let calendar = Calendar(identifier: .gregorian)
    let start = calendar.date(from: DateComponents(year: 2024, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.locale = Locale(identifier: "en_US_POSIX")
    return (0..<count).compactMap { index in
        guard let date = calendar.date(byAdding: .day, value: index, to: start) else { return nil }
        let close = closeStart + Double(index)
        return PricePoint(
            id: formatter.string(from: date),
            date: date,
            open: close - 0.5,
            high: close + 1,
            low: close - 1,
            close: close,
            volume: 1000 + Double(index)
        )
    }
}
