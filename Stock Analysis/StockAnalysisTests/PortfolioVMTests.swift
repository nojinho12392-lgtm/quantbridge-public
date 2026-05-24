import XCTest
@testable import Stock_Analysis

@MainActor
final class PortfolioVMTests: XCTestCase {
    func testRefreshLoadsPortfolioData() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try portfolioResponse(), for: ["portfolio", "us"])
        let vm = PortfolioVM(market: .us, api: api)

        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertEqual(vm.meta["Source"], "unit-test")
        XCTAssertEqual(vm.meta["Updated"], "2026-05-20")
        XCTAssertEqual(vm.stocks.map(\.ticker), ["AAPL"])
        XCTAssertEqual(vm.stocks.first?.currentPrice, 190.25)
        XCTAssertNil(vm.warning)
        XCTAssertEqual(api.fetchCallCount, 1)
    }

    func testRefreshFailsWhenNoCachedDataExists() async {
        let api = MockAPIClient()
        api.setFetchError("network down", for: ["portfolio", "us"])
        let vm = PortfolioVM(market: .us, api: api)

        await vm.refresh()

        assertFailure(vm.state, contains: "network down")
        XCTAssertTrue(vm.stocks.isEmpty)
        XCTAssertNil(vm.warning)
    }

    func testRefreshKeepsCachedPortfolioAfterFailure() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try portfolioResponse(), for: ["portfolio", "us"])
        let vm = PortfolioVM(market: .us, api: api)
        await vm.refresh()

        api.setFetchError("timeout", for: ["portfolio", "us"])
        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertEqual(vm.stocks.map(\.ticker), ["AAPL"])
        XCTAssertEqual(vm.meta["Source"], "unit-test")
        XCTAssertTrue(vm.warning?.contains("마지막 성공 데이터") == true)
        XCTAssertTrue(vm.warning?.contains("timeout") == true)
    }

    func testLoadRunsOnlyFromIdleState() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try portfolioResponse(), for: ["portfolio", "us"])
        let vm = PortfolioVM(market: .us, api: api)

        await vm.load()
        await vm.load()

        assertSuccess(vm.state)
        XCTAssertEqual(api.fetchCallCount, 1)
    }

    func testRefreshClearsWarningAfterRecovery() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try portfolioResponse(), for: ["portfolio", "us"])
        let vm = PortfolioVM(market: .us, api: api)
        await vm.refresh()

        api.setFetchError("timeout", for: ["portfolio", "us"])
        await vm.refresh()
        api.clearFetchError(for: ["portfolio", "us"])
        api.setFetchResponse(try portfolioResponse(ticker: "MSFT", name: "마이크로소프트"), for: ["portfolio", "us"])
        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertNil(vm.warning)
        XCTAssertEqual(vm.stocks.map(\.ticker), ["MSFT"])
        XCTAssertEqual(api.fetchCallCount, 3)
    }
}
