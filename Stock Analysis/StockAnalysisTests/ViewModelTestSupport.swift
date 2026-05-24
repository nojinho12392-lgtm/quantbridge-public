import XCTest
@testable import Stock_Analysis

func decodeFixture<T: Decodable>(
    _ type: T.Type,
    _ json: String,
    file: StaticString = #filePath,
    line: UInt = #line
) throws -> T {
    do {
        return try JSONDecoder().decode(T.self, from: Data(json.utf8))
    } catch {
        XCTFail("Fixture decode failed: \(error)", file: file, line: line)
        throw error
    }
}

func assertSuccess<T>(
    _ state: APIResult<T>,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    if case .success = state {
        return
    }
    XCTFail("Expected success, got \(state)", file: file, line: line)
}

func assertFailure<T>(
    _ state: APIResult<T>,
    contains expected: String? = nil,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    guard case .failure(let message) = state else {
        XCTFail("Expected failure, got \(state)", file: file, line: line)
        return
    }
    if let expected {
        XCTAssertTrue(message.contains(expected), "Expected '\(message)' to contain '\(expected)'", file: file, line: line)
    }
}

func portfolioResponse(ticker: String = "AAPL", name: String = "애플") throws -> PortfolioResponse {
    try decodeFixture(
        PortfolioResponse.self,
        """
        {
          "meta": {"Source": "unit-test", "Updated": "2026-05-20"},
          "stocks": [
            {
              "Rank": 1,
              "Ticker": "\(ticker)",
              "Name": "\(name)",
              "Market": "US",
              "Sector": "Technology",
              "MarketCap": 3000000000000,
              "Current_Price": 190.25,
              "Return_1M": 0.042,
              "Total_Score": 0.91
            }
          ]
        }
        """
    )
}

func smallCapResponse(ticker: String = "SMCI", name: String = "슈퍼마이크로") throws -> SmallCapResponse {
    try decodeFixture(
        SmallCapResponse.self,
        """
        {
          "stocks": [
            {
              "Rank": 1,
              "Ticker": "\(ticker)",
              "Name": "\(name)",
              "Market": "US",
              "MarketCap": 5000000000,
              "Current_Price": 48.5,
              "Return_1M": 0.08,
              "Total_Score": 0.77
            }
          ]
        }
        """
    )
}

func earningsResponse(ticker: String = "AAPL", name: String = "애플") throws -> EarningsResponse {
    try decodeFixture(
        EarningsResponse.self,
        """
        {
          "stocks": [
            {
              "Rank": 1,
              "Ticker": "\(ticker)",
              "Name": "\(name)",
              "Sector": "Technology",
              "MarketCap": 3000000000000,
              "Earnings_Date": "2026-05-01",
              "Surprise_Pct": 0.05,
              "Return_Since": 0.03,
              "Signal_Strength": 0.81
            }
          ]
        }
        """
    )
}

func earningsCalendarResponse(ticker: String = "AAPL", name: String = "애플") throws -> EarningsCalendarResponse {
    try decodeFixture(
        EarningsCalendarResponse.self,
        """
        {
          "items": [
            {
              "Ticker": "\(ticker)",
              "Name": "\(name)",
              "Market": "US",
              "Sector": "Technology",
              "MarketCap": 3000000000000,
              "Next_Earnings_Date": "2026-06-01",
              "Days_Until": 12
            }
          ],
          "generated_at": "2026-05-20T00:00:00Z",
          "source": "unit-test",
          "total": 1
        }
        """
    )
}
