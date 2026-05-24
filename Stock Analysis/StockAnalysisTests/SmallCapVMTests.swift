import XCTest
@testable import Stock_Analysis

@MainActor
final class SmallCapVMTests: XCTestCase {
    func testRefreshLoadsBothMarkets() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try smallCapResponse(ticker: "SMCI"), for: ["smallcap", "us"])
        api.setFetchResponse(try smallCapResponse(ticker: "042700.KS", name: "한미반도체"), for: ["smallcap", "kr"])
        let vm = SmallCapVM(api: api)

        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertEqual(vm.usStocks.map(\.ticker), ["SMCI"])
        XCTAssertEqual(vm.krStocks.map(\.ticker), ["042700.KS"])
        XCTAssertNil(vm.warning)
    }

    func testPartialFailureUsesSuccessfulMarket() async throws {
        let api = MockAPIClient()
        api.setFetchError("US timeout", for: ["smallcap", "us"])
        api.setFetchResponse(try smallCapResponse(ticker: "042700.KS", name: "한미반도체"), for: ["smallcap", "kr"])
        let vm = SmallCapVM(api: api)

        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertTrue(vm.usStocks.isEmpty)
        XCTAssertEqual(vm.krStocks.map(\.ticker), ["042700.KS"])
        XCTAssertTrue(vm.warning?.contains("US timeout") == true)
    }

    func testBothMarketsFailWithoutCache() async {
        let api = MockAPIClient()
        api.setFetchError("US timeout", for: ["smallcap", "us"])
        api.setFetchError("KR timeout", for: ["smallcap", "kr"])
        let vm = SmallCapVM(api: api)

        await vm.refresh()

        assertFailure(vm.state, contains: "US timeout")
        XCTAssertTrue(vm.usStocks.isEmpty)
        XCTAssertTrue(vm.krStocks.isEmpty)
    }

    func testRefreshKeepsStaleMarketOnPartialFailure() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try smallCapResponse(ticker: "SMCI"), for: ["smallcap", "us"])
        api.setFetchResponse(try smallCapResponse(ticker: "042700.KS", name: "한미반도체"), for: ["smallcap", "kr"])
        let vm = SmallCapVM(api: api)
        await vm.refresh()

        api.setFetchResponse(try smallCapResponse(ticker: "APP"), for: ["smallcap", "us"])
        api.setFetchError("KR timeout", for: ["smallcap", "kr"])
        await vm.refresh()

        assertSuccess(vm.state)
        XCTAssertEqual(vm.usStocks.map(\.ticker), ["APP"])
        XCTAssertEqual(vm.krStocks.map(\.ticker), ["042700.KS"])
        XCTAssertTrue(vm.warning?.contains("KR timeout") == true)
    }

    func testStocksForMarketReturnsMatchingCollection() async throws {
        let api = MockAPIClient()
        api.setFetchResponse(try smallCapResponse(ticker: "SMCI"), for: ["smallcap", "us"])
        api.setFetchResponse(try smallCapResponse(ticker: "042700.KS", name: "한미반도체"), for: ["smallcap", "kr"])
        let vm = SmallCapVM(api: api)

        await vm.refresh()

        XCTAssertEqual(vm.stocks(for: .us).map(\.ticker), ["SMCI"])
        XCTAssertEqual(vm.stocks(for: .kr).map(\.ticker), ["042700.KS"])
    }
}
