import XCTest
@testable import Stock_Analysis

@MainActor
final class PulseVMTests: XCTestCase {
    private var calendarQuery: [URLQueryItem] {
        [
            URLQueryItem(name: "market", value: "ALL"),
            URLQueryItem(name: "days", value: "180"),
            URLQueryItem(name: "limit", value: "2000")
        ]
    }

    private var calendarRefreshQuery: [URLQueryItem] {
        calendarQuery + [URLQueryItem(name: "refresh", value: "true")]
    }

    func testRefreshLoadsMacroEarningsAndCalendar() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(["fear_greed": "neutral"], for: ["macro"])
        api.setFetchResponse(try earningsResponse(ticker: "AAPL"), for: ["earnings", "us"])
        api.setFetchResponse(try earningsResponse(ticker: "005930.KS", name: "삼성전자"), for: ["earnings", "kr"])
        api.setFetchResponse(try earningsCalendarResponse(), for: ["calendar", "earnings"], queryItems: calendarQuery)
        let vm = PulseVM(api: api)

        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertEqual(vm.macro["fear_greed"], "neutral")
        XCTAssertEqual(vm.usEarnings.map(\.ticker), ["AAPL"])
        XCTAssertEqual(vm.krEarnings.map(\.ticker), ["005930.KS"])
        XCTAssertEqual(vm.earningsCalendar.map(\.ticker), ["AAPL"])
        XCTAssertNil(vm.warning)
    }

    func testMacroFailureStillShowsEarningsData() async throws {
        let api = MockAPIClient()
        api.setFetchError("macro timeout", for: ["macro"])
        api.setFetchResponse(try earningsResponse(ticker: "AAPL"), for: ["earnings", "us"])
        api.setFetchResponse(try earningsResponse(ticker: "005930.KS", name: "삼성전자"), for: ["earnings", "kr"])
        api.setFetchResponse(try earningsCalendarResponse(), for: ["calendar", "earnings"], queryItems: calendarQuery)
        let vm = PulseVM(api: api)

        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertTrue(vm.macro.isEmpty)
        XCTAssertFalse(vm.usEarnings.isEmpty)
        XCTAssertTrue(vm.warning?.contains("macro timeout") == true)
    }

    func testAllPulseRequestsFailWithoutCache() async {
        let api = MockAPIClient()
        api.setFetchError("macro timeout", for: ["macro"])
        api.setFetchError("US earnings timeout", for: ["earnings", "us"])
        api.setFetchError("KR earnings timeout", for: ["earnings", "kr"])
        api.setFetchError("calendar timeout", for: ["calendar", "earnings"], queryItems: calendarQuery)
        let vm = PulseVM(api: api)

        await vm.refresh()

        assertFailure(vm.state, contains: "macro timeout")
        XCTAssertTrue(vm.macro.isEmpty)
        XCTAssertTrue(vm.usEarnings.isEmpty)
        XCTAssertTrue(vm.krEarnings.isEmpty)
        XCTAssertTrue(vm.earningsCalendar.isEmpty)
    }

    func testEnsureCalendarLoadedRefreshesEmptyCalendarOnly() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try earningsCalendarResponse(ticker: "MSFT", name: "마이크로소프트"), for: ["calendar", "earnings"], queryItems: calendarRefreshQuery)
        let vm = PulseVM(api: api)

        await vm.ensureCalendarLoaded()

        assertSuccess(vm.state)
        XCTAssertEqual(vm.earningsCalendar.map(\.ticker), ["MSFT"])
        XCTAssertEqual(api.fetchCallCount, 1)
    }

    func testEnsureCalendarLoadedSkipsHealthySmallCalendar() async throws {
        let api = MockAPIClient()
        let vm = PulseVM(api: api)
        vm.earningsCalendar = try earningsCalendarResponse().items

        await vm.ensureCalendarLoaded()

        XCTAssertEqual(api.fetchCallCount, 0)
        XCTAssertEqual(vm.earningsCalendar.map(\.ticker), ["AAPL"])
    }

    func testEarningsForMarketReturnsCorrectSide() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(["fear_greed": "neutral"], for: ["macro"])
        api.setFetchResponse(try earningsResponse(ticker: "AAPL"), for: ["earnings", "us"])
        api.setFetchResponse(try earningsResponse(ticker: "005930.KS", name: "삼성전자"), for: ["earnings", "kr"])
        api.setFetchResponse(try earningsCalendarResponse(), for: ["calendar", "earnings"], queryItems: calendarQuery)
        let vm = PulseVM(api: api)

        await vm.refresh()

        XCTAssertEqual(vm.earnings(for: .us).map(\.ticker), ["AAPL"])
        XCTAssertEqual(vm.earnings(for: .kr).map(\.ticker), ["005930.KS"])
    }
}
