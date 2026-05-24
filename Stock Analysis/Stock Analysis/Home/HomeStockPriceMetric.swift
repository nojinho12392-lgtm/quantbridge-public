import Foundation

internal struct HomeStockPriceMetric: Decodable {
    let ticker: String
    let currentPrice: Double?
    let return1M: Double?
    let dailyChangePct: Double?
    let dailyChangeHorizon: String?
    let updatedAt: String?

    private enum CodingKeys: String, CodingKey {
        case ticker = "Ticker"
        case currentPrice = "Current_Price"
        case return1M = "Return_1M"
        case dailyChangePct = "Daily_Change_Pct"
        case dailyChangeHorizon = "Daily_Change_Horizon"
        case updatedAt = "Price_Updated_At"
    }
}

internal struct HomeStockPriceMetricsResponse: Decodable {
    let metrics: [HomeStockPriceMetric]
}

extension APIClient {
    func fetchHomeStockPriceMetrics(market: String, tickers: [String], refresh: Bool = false) async throws -> [HomeStockPriceMetric] {
        let cleanTickers = Array(Set(tickers.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }.filter { !$0.isEmpty })).sorted()
        guard !cleanTickers.isEmpty else { return [] }
        let response: HomeStockPriceMetricsResponse = try await fetch(
            ["portfolio", market.uppercased(), "prices"],
            queryItems: [
                URLQueryItem(name: "tickers", value: cleanTickers.prefix(100).joined(separator: ",")),
                URLQueryItem(name: "limit", value: "\(min(cleanTickers.count, 100))"),
                URLQueryItem(name: "refresh", value: refresh ? "true" : "false")
            ]
        )
        return response.metrics
    }
}
