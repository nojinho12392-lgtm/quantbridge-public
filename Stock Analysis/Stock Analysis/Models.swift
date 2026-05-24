import Foundation
import SwiftUI

struct EmptyResponse: Codable {
    let ok: Bool?
}

struct AuthUser: Codable, Identifiable, Hashable {
    let id: String
    let email: String
    let displayName: String
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case email
        case displayName = "display_name"
        case createdAt = "created_at"
    }
}

struct AuthResponse: Codable {
    let accessToken: String
    let tokenType: String
    let user: AuthUser

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case tokenType = "token_type"
        case user
    }
}

struct CurrentUserResponse: Codable {
    let user: AuthUser
}

struct WatchlistResponse: Codable {
    let items: [RemoteWatchlistItem]
}

struct RemoteWatchlistItem: Codable {
    let ticker: String
    let name: String
    let market: String
    let currency: String
    let note: String
    let addedAt: String

    enum CodingKeys: String, CodingKey {
        case ticker
        case name
        case market
        case currency
        case note
        case addedAt = "added_at"
    }
}

struct PortfolioStock: Codable, Identifiable, Hashable {
    var id: String { ticker }
    let rank: Int?
    let previousRank: Int?
    let rankChange: Int?
    let rankStatus: String?
    let ticker: String
    let name: String
    let market: String?
    let sector: String?
    let marketCap: Double?
    let weight: Double?
    let currentPrice: Double?
    let return1M: Double?
    let totalScore: Double?
    let roic: Double?
    let revGrowth: Double?
    let grossMargin: Double?
    let expectedReturn: Double?
    let lastUpdated: String?
    let source: String?
    let generatedAt: String?

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PortfolioStockKey.self)
        rank = container.decodeInt("Rank")
        previousRank = container.decodeInt("Previous_Rank")
        rankChange = container.decodeInt("Rank_Change")
        rankStatus = container.decodeString("Rank_Status")
        ticker = container.decodeString("Ticker") ?? "-"
        name = displayCompanyName(container.decodeString("Name") ?? ticker, ticker: ticker)
        market = container.decodeString("Market")
        sector = container.decodeString("Sector")
        marketCap = container.decodeDouble("MarketCap")
        weight = container.decodeDouble("Weight(%)")
        currentPrice = container.decodeDouble(["Current_Price", "current_price", "Price", "Last_Price", "Close", "End_Price"])
        return1M = container.decodeDouble(["Return_1M", "return_1m", "1M_Return", "Return_1m", "One_Month_Return", "Mom_1M"])
        totalScore = container.decodeDouble("Total_Score")
        roic = container.decodeDouble("ROIC")
        revGrowth = container.decodeDouble("RevGrowth")
        grossMargin = container.decodeDouble("GrossMargin")
        expectedReturn = container.decodeDouble("Expected_Return")
        lastUpdated = container.decodeString(["Price_Updated_At", "Last_Updated", "updated_at", "generated_at", "Generated_At"])
        source = container.decodeString(["Price_Source", "Source", "source"])
        generatedAt = container.decodeString(["generated_at", "Generated_At"])
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(rank, forKey: .rank)
        try container.encodeIfPresent(previousRank, forKey: .previousRank)
        try container.encodeIfPresent(rankChange, forKey: .rankChange)
        try container.encodeIfPresent(rankStatus, forKey: .rankStatus)
        try container.encode(ticker, forKey: .ticker)
        try container.encode(name, forKey: .name)
        try container.encodeIfPresent(market, forKey: .market)
        try container.encodeIfPresent(sector, forKey: .sector)
        try container.encodeIfPresent(marketCap, forKey: .marketCap)
        try container.encodeIfPresent(weight, forKey: .weight)
        try container.encodeIfPresent(currentPrice, forKey: .currentPrice)
        try container.encodeIfPresent(return1M, forKey: .return1M)
        try container.encodeIfPresent(totalScore, forKey: .totalScore)
        try container.encodeIfPresent(roic, forKey: .roic)
        try container.encodeIfPresent(revGrowth, forKey: .revGrowth)
        try container.encodeIfPresent(grossMargin, forKey: .grossMargin)
        try container.encodeIfPresent(expectedReturn, forKey: .expectedReturn)
        try container.encodeIfPresent(lastUpdated, forKey: .lastUpdated)
        try container.encodeIfPresent(source, forKey: .source)
        try container.encodeIfPresent(generatedAt, forKey: .generatedAt)
    }

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case previousRank = "Previous_Rank"
        case rankChange = "Rank_Change"
        case rankStatus = "Rank_Status"
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case marketCap = "MarketCap"
        case weight = "Weight(%)"
        case currentPrice = "Current_Price"
        case return1M = "Return_1M"
        case totalScore = "Total_Score"
        case roic = "ROIC"
        case revGrowth = "RevGrowth"
        case grossMargin = "GrossMargin"
        case expectedReturn = "Expected_Return"
        case lastUpdated = "Last_Updated"
        case source = "Source"
        case generatedAt = "generated_at"
    }
}

private struct PortfolioStockKey: CodingKey {
    let stringValue: String
    let intValue: Int?

    init?(stringValue: String) {
        self.stringValue = stringValue
        intValue = nil
    }

    init?(intValue: Int) {
        stringValue = "\(intValue)"
        self.intValue = intValue
    }
}

private extension KeyedDecodingContainer where Key == PortfolioStockKey {
    func decodeString(_ key: String) -> String? {
        guard let codingKey = PortfolioStockKey(stringValue: key) else { return nil }
        return try? decodeIfPresent(String.self, forKey: codingKey)
    }

    func decodeString(_ keys: [String]) -> String? {
        for key in keys {
            if let value = decodeString(key)?.trimmingCharacters(in: .whitespacesAndNewlines),
               !value.isEmpty {
                return value
            }
        }
        return nil
    }

    func decodeInt(_ key: String) -> Int? {
        guard let codingKey = PortfolioStockKey(stringValue: key) else { return nil }
        if let value = try? decodeIfPresent(Int.self, forKey: codingKey) {
            return value
        }
        if let text = try? decodeIfPresent(String.self, forKey: codingKey) {
            return Int(Double(text.trimmingCharacters(in: .whitespacesAndNewlines)) ?? .nan)
        }
        return nil
    }

    func decodeDouble(_ key: String) -> Double? {
        decodeDouble([key])
    }

    func decodeDouble(_ keys: [String]) -> Double? {
        for key in keys {
            guard let codingKey = PortfolioStockKey(stringValue: key) else { continue }
            if let value = try? decodeIfPresent(Double.self, forKey: codingKey), value.isFinite {
                return value
            }
            if let intValue = try? decodeIfPresent(Int.self, forKey: codingKey) {
                return Double(intValue)
            }
            if let text = try? decodeIfPresent(String.self, forKey: codingKey),
               let value = portfolioDouble(from: text) {
                return value
            }
        }
        return nil
    }
}

private func portfolioDouble(from text: String) -> Double? {
    let clean = text
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: ",", with: "")
        .replacingOccurrences(of: "$", with: "")
        .replacingOccurrences(of: "₩", with: "")
        .replacingOccurrences(of: "원", with: "")
    guard !clean.isEmpty, clean != "-" else { return nil }
    if clean.hasSuffix("%") {
        return Double(clean.dropLast()).map { $0 / 100 }
    }
    return Double(clean)
}

struct PortfolioResponse: Codable {
    let meta: [String: String]
    let stocks: [PortfolioStock]
}

struct SmallCapStock: Codable, Identifiable, Hashable {
    var id: String { ticker }
    let rank: Int?
    let previousRank: Int?
    let rankChange: Int?
    let rankStatus: String?
    let ticker: String
    let name: String
    let market: String?
    let marketCap: Double?
    let currentPrice: Double?
    let return1M: Double?
    let roic: Double?
    let revGrowth: Double?
    let revAccel: Double?
    let grossMargin: Double?
    let fcfMargin: Double?
    let debtEbitda: Double?
    let volumeSurge: Double?
    let smallCapBonus: Double?
    let totalScore: Double?
    let lastUpdated: String?
    let source: String?
    let generatedAt: String?

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case previousRank = "Previous_Rank"
        case rankChange = "Rank_Change"
        case rankStatus = "Rank_Status"
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case marketCap = "MarketCap"
        case currentPrice = "Current_Price"
        case return1M = "Return_1M"
        case roic = "ROIC"
        case revGrowth = "RevGrowth"
        case revAccel = "Rev_Accel"
        case grossMargin = "GrossMargin"
        case fcfMargin = "FCF_Margin"
        case debtEbitda = "Debt_EBITDA"
        case volumeSurge = "Volume_Surge"
        case smallCapBonus = "SmallCap_Bonus"
        case totalScore = "Total_Score"
        case lastUpdated = "Last_Updated"
        case source = "Source"
        case generatedAt = "generated_at"
    }
}

struct SmallCapResponse: Codable {
    let stocks: [SmallCapStock]
}

struct EarningsStock: Codable, Identifiable, Hashable {
    var id: String { ticker }
    let rank: Int?
    let ticker: String
    let name: String
    let sector: String?
    let marketCap: Double?
    let earningsDate: String?
    let daysSince: Double?
    let surprisePct: Double?
    let returnSince: Double?
    let volumeSurge: Double?
    let signalStrength: Double?

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case ticker = "Ticker"
        case name = "Name"
        case sector = "Sector"
        case marketCap = "MarketCap"
        case earningsDate = "Earnings_Date"
        case daysSince = "Days_Since_Earnings"
        case surprisePct = "Surprise_Pct"
        case returnSince = "Return_Since"
        case volumeSurge = "Volume_Surge"
        case signalStrength = "Signal_Strength"
    }
}

struct EarningsResponse: Codable {
    let stocks: [EarningsStock]
}

struct EarningsCalendarItem: Codable, Identifiable, Hashable {
    var id: String { "\(ticker)-\(nextEarningsDate)" }
    let ticker: String
    let name: String
    let market: String
    let sector: String?
    let marketCap: Double?
    let nextEarningsDate: String
    let daysUntil: Int?

    enum CodingKeys: String, CodingKey {
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case marketCap = "MarketCap"
        case nextEarningsDate = "Next_Earnings_Date"
        case daysUntil = "Days_Until"
    }
}

struct EarningsCalendarResponse: Codable {
    let items: [EarningsCalendarItem]
    let generatedAt: String?
    let source: String?
    let total: Int?

    enum CodingKeys: String, CodingKey {
        case items
        case generatedAt = "generated_at"
        case source
        case total
    }
}

struct SignalEvent: Codable, Identifiable, Hashable {
    var id: String { eventID }
    let eventID: String
    let market: String
    let ticker: String
    let name: String
    let kind: String
    let severity: Int
    let title: String
    let detail: String
    let metricLabel: String?
    let metricValue: String?
    let eventTime: String?
    let source: String?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case eventID = "Event_ID"
        case market = "Market"
        case ticker = "Ticker"
        case name = "Name"
        case kind = "Kind"
        case severity = "Severity"
        case title = "Title"
        case detail = "Detail"
        case metricLabel = "Metric_Label"
        case metricValue = "Metric_Value"
        case eventTime = "Event_Time"
        case source = "Source"
        case updatedAt = "Updated_At"
    }
}

struct SignalEventsResponse: Codable {
    let items: [SignalEvent]
    let generatedAt: String?
    let count: Int?

    enum CodingKeys: String, CodingKey {
        case items
        case generatedAt = "generated_at"
        case count
    }
}

struct ComparisonRecommendationItem: Codable, Hashable {
    let ticker: String
    let name: String
    let market: String?
    let sector: String?
    let currency: String?
    let source: String?
    let scoreValue: Double?
    let expectedReturn: Double?
    let revenueGrowth: Double?
    let roic: Double?
    let grossMargin: Double?
    let marketCap: Double?
    let currentPrice: Double?
    let return1M: Double?
    let rankChange: Int?
    let weight: Double?
    let fcfMargin: Double?
    let volumeSurge: Double?
    let updatedAt: String?
    let reason: String?

    enum CodingKeys: String, CodingKey {
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case currency = "Currency"
        case source = "Source"
        case scoreValue = "Score_Value"
        case expectedReturn = "Expected_Return"
        case revenueGrowth = "RevGrowth"
        case roic = "ROIC"
        case grossMargin = "GrossMargin"
        case marketCap = "MarketCap"
        case currentPrice = "Current_Price"
        case return1M = "Return_1M"
        case rankChange = "Rank_Change"
        case weight = "Weight(%)"
        case fcfMargin = "FCF_Margin"
        case volumeSurge = "Volume_Surge"
        case updatedAt = "Last_Updated"
        case reason = "Recommendation_Reason"
    }
}

struct ComparisonRecommendationsResponse: Codable {
    let items: [ComparisonRecommendationItem]
    let generatedAt: String?
    let count: Int?

    enum CodingKeys: String, CodingKey {
        case items
        case generatedAt = "generated_at"
        case count
    }
}

extension EarningsStock {
    func withResolvedKrName() -> EarningsStock {
        let resolvedName = localizedCompanyName(ticker: ticker, currentName: name)
        guard resolvedName != name else { return self }
        return EarningsStock(
            rank: rank,
            ticker: ticker,
            name: resolvedName,
            sector: sector,
            marketCap: marketCap,
            earningsDate: earningsDate,
            daysSince: daysSince,
            surprisePct: surprisePct,
            returnSince: returnSince,
            volumeSurge: volumeSurge,
            signalStrength: signalStrength
        )
    }
}

extension EarningsCalendarItem {
    func withResolvedKrName() -> EarningsCalendarItem {
        let resolvedName = localizedCompanyName(ticker: ticker, currentName: name, market: market)
        guard resolvedName != name else { return self }
        return EarningsCalendarItem(
            ticker: ticker,
            name: resolvedName,
            market: market,
            sector: sector,
            marketCap: marketCap,
            nextEarningsDate: nextEarningsDate,
            daysUntil: daysUntil
        )
    }
}

struct RawPricePoint: Decodable {
    let date: String
    let open: Double
    let high: Double
    let low: Double
    let close: Double
    let volume: Double?
}

struct StockInfo: Decodable {
    let name: String?
    let currentPrice: Double?
    let prevClose: Double?
    let dailyChangePct: Double?
    let dailyChangeHorizon: String?
    let week52High: Double?
    let week52Low: Double?
    let marketCap: Double?
    let peRatio: Double?
    let forwardPe: Double?
    let priceToSales: Double?
    let priceToBook: Double?
    let beta: Double?
    let sector: String?
    let industry: String?
    let country: String?
    let city: String?
    let exchange: String?
    let website: String?
    let employees: Int?
    let totalRevenue: Double?
    let revenueGrowth: Double?
    let grossMargin: Double?
    let operatingMargin: Double?
    let profitMargin: Double?
    let ebitdaMargin: Double?
    let ebitda: Double?
    let freeCashflow: Double?
    let totalDebt: Double?
    let debtToEquity: Double?
    let returnOnEquity: Double?
    let targetMeanPrice: Double?
    let recommendation: String?
    let description: String?

    enum CodingKeys: String, CodingKey {
        case name
        case currentPrice = "current_price"
        case prevClose = "prev_close"
        case dailyChangePct = "daily_change_pct"
        case dailyChangeHorizon = "daily_change_horizon"
        case week52High = "week52_high"
        case week52Low = "week52_low"
        case marketCap = "market_cap"
        case peRatio = "pe_ratio"
        case forwardPe = "forward_pe"
        case priceToSales = "price_to_sales"
        case priceToBook = "price_to_book"
        case beta
        case sector
        case industry
        case country
        case city
        case exchange
        case website
        case employees
        case totalRevenue = "total_revenue"
        case revenueGrowth = "revenue_growth"
        case grossMargin = "gross_margin"
        case operatingMargin = "operating_margin"
        case profitMargin = "profit_margin"
        case ebitdaMargin = "ebitda_margin"
        case ebitda
        case freeCashflow = "free_cashflow"
        case totalDebt = "total_debt"
        case debtToEquity = "debt_to_equity"
        case returnOnEquity = "return_on_equity"
        case targetMeanPrice = "target_mean_price"
        case recommendation
        case description
    }
}

struct StockDetailResponse: Decodable {
    let prices: [RawPricePoint]
    let info: StockInfo
    let error: String?
    let source: String?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case prices
        case info
        case error
        case source
        case updatedAt = "updated_at"
    }
}

struct PricePoint: Identifiable, Hashable {
    let id: String
    let date: Date
    let open: Double
    let high: Double
    let low: Double
    let close: Double
    let volume: Double?
}

struct StaticMetric: Identifiable {
    let id = UUID()
    let label: String
    let value: String
    var color: Color = .primary
}

enum Market: String, CaseIterable, Identifiable {
    case us = "US"
    case kr = "KR"

    var id: String { rawValue }
    var currency: String { self == .kr ? "KRW" : "USD" }
    var title: String { self == .kr ? "국내" : "미국" }
}

enum PortfolioSort: String, CaseIterable, Identifiable {
    case rank = "Rank"
    case weight = "Weight"
    case score = "Score"
    case expectedReturn = "Exp. Return"
    case revenueGrowth = "Revenue"

    var id: String { rawValue }
}

enum SmallCapSort: String, CaseIterable, Identifiable {
    case rank = "Rank"
    case score = "Score"
    case revenueGrowth = "Revenue"
    case marketCap = "Market Cap"

    var id: String { rawValue }
}

enum EarningsSort: String, CaseIterable, Identifiable {
    case rank = "Rank"
    case signal = "Signal"
    case surprise = "Surprise"
    case returnSince = "Return"
    case daysSince = "Recent"

    var id: String { rawValue }
}
