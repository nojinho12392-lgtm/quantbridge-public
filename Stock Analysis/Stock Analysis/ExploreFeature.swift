import Combine
import SwiftUI

enum MarketScope: String, CaseIterable, Identifiable, Hashable {
    case all = "ALL"
    case us = "US"
    case kr = "KR"

    var id: String { rawValue }

    func accepts(_ market: String?) -> Bool {
        self == .all || market?.uppercased() == rawValue
    }
}

enum ExploreMode: String, CaseIterable, Identifiable, Hashable {
    case companies = "탐색"
    case scores = "랭킹"
    case strategy = "전략"
    case diagnostics = "품질"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .companies:
            return "기업 탐색"
        case .scores:
            return "추천 후보"
        case .strategy:
            return "개발자 전략 점검"
        case .diagnostics:
            return "추천 품질 점검"
        }
    }

    var subtitle: String {
        switch self {
        case .companies:
            return "기업명이나 티커로 바로 찾고 상세 화면으로 이동합니다."
        case .scores:
            return "종합 점수와 성장, 수익성을 기준으로 볼 만한 후보를 정리합니다."
        case .strategy:
            return "백테스트, 리스크, 리밸런싱, 섀도우 성과를 운영 관점에서 확인합니다."
        case .diagnostics:
            return "리서치 품질, AI 보정, 정책 섀도 랭킹, API 상태를 함께 확인합니다."
        }
    }

    var systemImage: String {
        switch self {
        case .companies:
            return "magnifyingglass"
        case .scores:
            return "chart.bar.doc.horizontal"
        case .strategy:
            return "point.3.connected.trianglepath.dotted"
        case .diagnostics:
            return "checkmark.seal"
        }
    }
}

struct SearchResponse: Decodable {
    let stocks: [SearchStock]
}

struct SearchStock: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(ticker)" }
    let rank: Int?
    let ticker: String
    let name: String
    let market: String?
    let sector: String?
    let marketCap: Double?
    let inPortfolio: Bool
    let inSmallCap: Bool
    let currency: String?

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case marketCap = "MarketCap"
        case inPortfolio = "In_Portfolio"
        case inSmallCap = "In_SmallCap"
        case currency = "Currency"
    }
}

struct ScoredResponse: Decodable {
    let stocks: [ScoredStock]
}

struct ScoredStock: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(ticker)" }
    let rank: Int?
    let ticker: String
    let name: String
    let market: String?
    let sector: String?
    let marketCap: Double?
    let valueScore: Double?
    let qualityScore: Double?
    let momentumScore: Double?
    let totalScore: Double?
    let finalScore: Double?
    let scoreNeutral: Double?
    let mlScore: Double?
    let combinedScore: Double?
    let businessQualityScore: Double?
    let investabilityScore: Double?
    let qualityDataConfidence: Double?
    let qualityRedFlags: String?
    let qualityCategory: String?
    let roic: Double?
    let revGrowth: Double?
    let grossMargin: Double?
    let fcfMargin: Double?
    let debtEbitda: Double?
    let peg: Double?

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case marketCap = "MarketCap"
        case valueScore = "Value_Score"
        case qualityScore = "Quality_Score"
        case momentumScore = "Momentum_Score"
        case totalScore = "Total_Score"
        case finalScore = "Final_Score"
        case scoreNeutral = "Score_Neutral"
        case mlScore = "ML_Score"
        case combinedScore = "Combined_Score"
        case businessQualityScore = "Business_Quality_Score"
        case investabilityScore = "Investability_Score"
        case qualityDataConfidence = "Quality_Data_Confidence"
        case qualityRedFlags = "Quality_Red_Flags"
        case qualityCategory = "Quality_Category"
        case roic = "ROIC"
        case revGrowth = "RevGrowth"
        case grossMargin = "GrossMargin"
        case fcfMargin = "FCF_Margin"
        case debtEbitda = "Debt_EBITDA"
        case peg = "PEG"
    }
}

struct ResearchQuality: Decodable {
    let overallStatus: String
    let warningCount: Int
    let productionReadyCount: Int
    let proxyEvidenceCount: Int
    let items: [QualityGate]

    enum CodingKeys: String, CodingKey {
        case overallStatus = "overall_status"
        case warningCount = "warning_count"
        case productionReadyCount = "production_ready_count"
        case proxyEvidenceCount = "proxy_evidence_count"
        case items
    }
}

struct QualityGate: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(factor)" }
    let market: String
    let factor: String
    let status: String
    let meanIc: Double?
    let positiveRate: Double?
    let snapshots: Double?
    let evidenceSource: String?
    let productionReady: String?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case factor = "Factor"
        case status = "Status"
        case meanIc = "Mean_IC"
        case positiveRate = "Positive_IC_Rate"
        case snapshots = "Snapshots"
        case evidenceSource = "Evidence_Source"
        case productionReady = "Production_Ready"
    }
}

struct MLBlendReport: Decodable {
    let status: String
    let generatedAt: String?
    let latest: MLBlendItem?
    let items: [MLBlendItem]

    enum CodingKeys: String, CodingKey {
        case status
        case generatedAt = "generated_at"
        case latest
        case items
    }
}

struct MLBlendItem: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(generated)" }
    let generated: String
    let market: String
    let model: String
    let rankIc: Double?
    let mlWeight: Double?
    let factorWeight: Double?
    let mlWeightReason: String?
    let factorScoreColumn: String?
    let mlFactorSpearman: Double?
    let mlFactorPearson: Double?
    let predictedStocks: Double?
    let top5: String?
    let notes: String?
    let status: String?

    enum CodingKeys: String, CodingKey {
        case generated = "Generated"
        case market = "Market"
        case model = "Model"
        case rankIc = "Rank_IC"
        case mlWeight = "ML_Weight"
        case factorWeight = "Factor_Weight"
        case mlWeightReason = "ML_Weight_Reason"
        case factorScoreColumn = "Factor_Score_Column"
        case mlFactorSpearman = "ML_Factor_Spearman"
        case mlFactorPearson = "ML_Factor_Pearson"
        case predictedStocks = "Predicted_Stocks"
        case top5 = "Top5"
        case notes = "Notes"
        case status = "Status"
    }
}

struct PolicyAdjustedRankingResponse: Decodable {
    let market: String?
    let summary: PolicyAdjustedRankingSummary?
    let items: [PolicyAdjustedRankingItem]
    let topUp: [PolicyAdjustedRankingItem]
    let topDown: [PolicyAdjustedRankingItem]
    let mode: String?

    enum CodingKeys: String, CodingKey {
        case market
        case summary
        case items
        case topUp = "top_up"
        case topDown = "top_down"
        case mode
    }
}

struct PolicyAdjustedRankingSummary: Decodable, Hashable {
    let generated: String?
    let market: String
    let policyMode: String?
    let rows: Int?
    let positiveMovers: Int?
    let negativeMovers: Int?
    let unchanged: Int?
    let meanAbsRankChange: Double?
    let topUpTicker: String?
    let topUpName: String?
    let topUpRankChange: Int?
    let topDownTicker: String?
    let topDownName: String?
    let topDownRankChange: Int?
    let topBaseTicker: String?
    let topPolicyTicker: String?
    let multipliers: String?
    let evidenceSource: String?
    let productionReady: Bool?
    let note: String?

    enum CodingKeys: String, CodingKey {
        case generated = "Generated"
        case market = "Market"
        case policyMode = "Policy_Mode"
        case rows = "Rows"
        case positiveMovers = "Positive_Movers"
        case negativeMovers = "Negative_Movers"
        case unchanged = "Unchanged"
        case meanAbsRankChange = "Mean_Abs_Rank_Change"
        case topUpTicker = "Top_Up_Ticker"
        case topUpName = "Top_Up_Name"
        case topUpRankChange = "Top_Up_Rank_Change"
        case topDownTicker = "Top_Down_Ticker"
        case topDownName = "Top_Down_Name"
        case topDownRankChange = "Top_Down_Rank_Change"
        case topBaseTicker = "Top_Base_Ticker"
        case topPolicyTicker = "Top_Policy_Ticker"
        case multipliers = "Multipliers"
        case evidenceSource = "Evidence_Source"
        case productionReady = "Production_Ready"
        case note = "Note"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        generated = try container.decodeLossyStringIfPresent(.generated)
        market = try container.decodeLossyStringIfPresent(.market) ?? "-"
        policyMode = try container.decodeLossyStringIfPresent(.policyMode)
        rows = try container.decodeLossyIntIfPresent(.rows)
        positiveMovers = try container.decodeLossyIntIfPresent(.positiveMovers)
        negativeMovers = try container.decodeLossyIntIfPresent(.negativeMovers)
        unchanged = try container.decodeLossyIntIfPresent(.unchanged)
        meanAbsRankChange = try container.decodeLossyDoubleIfPresent(.meanAbsRankChange)
        topUpTicker = try container.decodeLossyStringIfPresent(.topUpTicker)
        topUpName = try container.decodeLossyStringIfPresent(.topUpName)
        topUpRankChange = try container.decodeLossyIntIfPresent(.topUpRankChange)
        topDownTicker = try container.decodeLossyStringIfPresent(.topDownTicker)
        topDownName = try container.decodeLossyStringIfPresent(.topDownName)
        topDownRankChange = try container.decodeLossyIntIfPresent(.topDownRankChange)
        topBaseTicker = try container.decodeLossyStringIfPresent(.topBaseTicker)
        topPolicyTicker = try container.decodeLossyStringIfPresent(.topPolicyTicker)
        multipliers = try container.decodeLossyStringIfPresent(.multipliers)
        evidenceSource = try container.decodeLossyStringIfPresent(.evidenceSource)
        productionReady = try container.decodeLossyBoolIfPresent(.productionReady)
        note = try container.decodeLossyStringIfPresent(.note)
    }
}

struct PolicyAdjustedRankingItem: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(ticker):\(policyRank ?? 0):\(rankChange ?? 0)" }
    let policyRank: Int?
    let baseRank: Int?
    let rankChange: Int?
    let ticker: String
    let name: String
    let market: String
    let sector: String?
    let policyFinalScore: Double?
    let baseFinalScore: Double?
    let scoreChange: Double?
    let valueMultiplier: Double?
    let qualityMultiplier: Double?
    let momentumMultiplier: Double?
    let policyMode: String?
    let evidenceSource: String?
    let productionReady: Bool?
    let actions: String?
    let qualityDataConfidence: Double?
    let generated: String?

    enum CodingKeys: String, CodingKey {
        case policyRank = "Policy_Rank"
        case baseRank = "Base_Rank"
        case rankChange = "Rank_Change"
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case policyFinalScore = "Policy_Final_Score"
        case baseFinalScore = "Base_Final_Score"
        case scoreChange = "Score_Change"
        case valueMultiplier = "Value_Multiplier"
        case qualityMultiplier = "Quality_Multiplier"
        case momentumMultiplier = "Momentum_Multiplier"
        case policyMode = "Policy_Mode"
        case evidenceSource = "Policy_Evidence_Source"
        case productionReady = "Policy_Production_Ready"
        case actions = "Policy_Actions"
        case qualityDataConfidence = "Quality_Data_Confidence"
        case generated = "Generated"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        policyRank = try container.decodeLossyIntIfPresent(.policyRank)
        baseRank = try container.decodeLossyIntIfPresent(.baseRank)
        rankChange = try container.decodeLossyIntIfPresent(.rankChange)
        ticker = try container.decodeLossyStringIfPresent(.ticker) ?? "-"
        name = try container.decodeLossyStringIfPresent(.name) ?? ticker
        market = try container.decodeLossyStringIfPresent(.market) ?? "-"
        sector = try container.decodeLossyStringIfPresent(.sector)
        policyFinalScore = try container.decodeLossyDoubleIfPresent(.policyFinalScore)
        baseFinalScore = try container.decodeLossyDoubleIfPresent(.baseFinalScore)
        scoreChange = try container.decodeLossyDoubleIfPresent(.scoreChange)
        valueMultiplier = try container.decodeLossyDoubleIfPresent(.valueMultiplier)
        qualityMultiplier = try container.decodeLossyDoubleIfPresent(.qualityMultiplier)
        momentumMultiplier = try container.decodeLossyDoubleIfPresent(.momentumMultiplier)
        policyMode = try container.decodeLossyStringIfPresent(.policyMode)
        evidenceSource = try container.decodeLossyStringIfPresent(.evidenceSource)
        productionReady = try container.decodeLossyBoolIfPresent(.productionReady)
        actions = try container.decodeLossyStringIfPresent(.actions)
        qualityDataConfidence = try container.decodeLossyDoubleIfPresent(.qualityDataConfidence)
        generated = try container.decodeLossyStringIfPresent(.generated)
    }
}

struct OpsHealth: Decodable {
    let healthy: Bool
    let status: String
    let generatedAt: String
    let checks: [OpsCheck]

    enum CodingKeys: String, CodingKey {
        case healthy
        case status
        case generatedAt = "generated_at"
        case checks
    }
}

struct OpsCheck: Decodable, Identifiable, Hashable {
    var id: String { name }
    let name: String
    let status: String
    let message: String
}

struct BacktestEnvelope: Decodable {
    let summary: BacktestSummary?
}

struct BacktestSummary: Decodable, Identifiable, Hashable {
    var id: String { sheet }
    let market: String
    let sheet: String
    let periods: Int
    let latestDate: String
    let cumulativeReturn: Double?
    let maxDrawdown: Double?
    let avgReturn: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case sheet = "Sheet"
        case periods = "Periods"
        case latestDate = "Latest_Date"
        case cumulativeReturn = "Cumulative_Ret"
        case maxDrawdown = "Max_Drawdown"
        case avgReturn = "Avg_Return"
    }
}

struct DriftResponse: Decodable {
    let items: [DriftItem]

    enum CodingKeys: String, CodingKey {
        case items
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let decoded = try container.decodeIfPresent([DriftItem].self, forKey: .items) ?? []
        items = decoded.filter { !$0.ticker.isEmpty }
    }
}

struct DriftItem: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(ticker):\(status)" }
    let market: String
    let ticker: String
    let name: String
    let status: String
    let driftAbs: Double?
    let targetWeight: Double?
    let currentWeight: Double?
    let returnSinceRebal: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case ticker = "Ticker"
        case name = "Name"
        case status = "Status"
        case recommendation = "Recommendation"
        case driftAbs = "Drift_Abs"
        case targetWeight = "Target_Weight"
        case currentWeight = "Current_Weight"
        case returnSinceRebal = "Return_Since_Rebal"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ticker = try container.decodeIfPresent(String.self, forKey: .ticker) ?? ""
        market = try container.decodeIfPresent(String.self, forKey: .market) ?? "-"
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ticker
        status = try container.decodeIfPresent(String.self, forKey: .status)
            ?? container.decodeIfPresent(String.self, forKey: .recommendation)
            ?? "UNKNOWN"
        driftAbs = try container.decodeIfPresent(Double.self, forKey: .driftAbs)
        targetWeight = try container.decodeIfPresent(Double.self, forKey: .targetWeight)
        currentWeight = try container.decodeIfPresent(Double.self, forKey: .currentWeight)
        returnSinceRebal = try container.decodeIfPresent(Double.self, forKey: .returnSinceRebal)
    }
}

struct IndustryResponse: Decodable {
    let items: [IndustryItem]
}

struct IndustryItem: Decodable, Identifiable, Hashable {
    var id: String { "\(rank ?? 0):\(industry)" }
    let rank: Int?
    let industry: String
    let stockCount: Int?
    let meanReturn: Double?
    let breadth: Double?

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case industry = "Industry"
        case stockCount = "Stock_Count"
        case meanReturn = "Mean_Return"
        case breadth = "Breadth"
    }
}

struct OrderFlowResponse: Decodable {
    let items: [OrderFlowItem]
}

struct OrderFlowItem: Decodable, Identifiable, Hashable {
    var id: String { ticker }
    let rank: Int?
    let ticker: String
    let name: String
    let consecutiveDays: Int?
    let foreignNetBuy: Double?
    let instNetBuy: Double?

    enum CodingKeys: String, CodingKey {
        case rank = "Rank"
        case ticker = "Ticker"
        case name = "Name"
        case consecutiveDays = "Consecutive_Days"
        case foreignNetBuy = "Foreign_Net_Buy"
        case instNetBuy = "Inst_Net_Buy"
    }
}

struct PortfolioRiskResponse: Decodable {
    let market: String
    let summary: [RiskMetric]
    let holdings: [RiskHolding]
    let sectors: [RiskSector]
}

struct RiskMetric: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(metric)" }
    let market: String?
    let metric: String
    let value: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case metric = "Metric"
        case value = "Value"
    }
}

struct RiskHolding: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(ticker)" }
    let market: String?
    let ticker: String
    let name: String
    let sector: String?
    let portfolioWeight: Double?
    let assetVol: Double?
    let riskContributionPct: Double?
    let weightRiskRatio: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case ticker = "Ticker"
        case name = "Name"
        case sector = "Sector"
        case portfolioWeight = "Portfolio_Weight"
        case assetVol = "Asset_Vol"
        case riskContributionPct = "Risk_Contribution_Pct"
        case weightRiskRatio = "Weight_Risk_Ratio"
    }
}

struct RiskSector: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(sector)" }
    let market: String?
    let sector: String
    let holdings: Double?
    let sectorWeight: Double?
    let riskContributionPct: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case sector = "Sector"
        case holdings = "Holdings"
        case sectorWeight = "Sector_Weight"
        case riskContributionPct = "Sector_Risk_Contribution_Pct"
    }
}

struct RebalanceResponse: Decodable {
    let market: String
    let summary: [RebalanceMetric]
    let orders: [RebalanceOrder]
}

struct RebalanceMetric: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(metric)" }
    let market: String?
    let metric: String
    let value: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case metric = "Metric"
        case value = "Value"
    }
}

struct RebalanceOrder: Decodable, Identifiable, Hashable {
    var id: String { "\(market ?? "-"):\(ticker):\(action)" }
    let market: String?
    let ticker: String
    let name: String
    let action: String
    let currentWeight: Double?
    let targetWeight: Double?
    let deltaWeight: Double?
    let executableTradeValue: Double?
    let costEstimate: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case ticker = "Ticker"
        case name = "Name"
        case action = "Action"
        case currentWeight = "Current_Weight"
        case targetWeight = "Target_Weight"
        case deltaWeight = "Delta_Weight"
        case executableTradeValue = "Executable_Trade_Value"
        case costEstimate = "Cost_Est"
    }
}

struct ShadowAttributionResponse: Decodable {
    let market: String
    let summary: [ShadowAttributionSummary]
    let items: [ShadowAttributionItem]
    let sectors: [ShadowSectorAttribution]
}

struct ShadowAttributionSummary: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(horizonTradingDays ?? 0)" }
    let market: String
    let horizonTradingDays: Double?
    let actualReturn: Double?
    let benchmarkReturn: Double?
    let alphaActual: Double?
    let hitRate: Double?
    let scoreReturnIc: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case horizonTradingDays = "Horizon_Trading_Days"
        case actualReturn = "Actual_Return"
        case benchmarkReturn = "Benchmark_Return"
        case alphaActual = "Alpha_Actual"
        case hitRate = "Hit_Rate"
        case scoreReturnIc = "Score_Return_IC"
    }
}

struct ShadowAttributionItem: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(ticker):\(horizonTradingDays ?? 0)" }
    let market: String
    let ticker: String
    let name: String
    let horizonTradingDays: Double?
    let weight: Double?
    let stockReturn: Double?
    let benchmarkReturn: Double?
    let actualContribution: Double?
    let excessContribution: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case ticker = "Ticker"
        case name = "Name"
        case horizonTradingDays = "Horizon_Trading_Days"
        case weight = "Weight"
        case stockReturn = "Stock_Return"
        case benchmarkReturn = "Benchmark_Return"
        case actualContribution = "Actual_Contribution"
        case excessContribution = "Excess_Contribution"
    }
}

struct ShadowSectorAttribution: Decodable, Identifiable, Hashable {
    var id: String { "\(market):\(sector):\(horizonTradingDays ?? 0)" }
    let market: String
    let sector: String
    let horizonTradingDays: Double?
    let sectorWeight: Double?
    let actualContribution: Double?
    let excessContribution: Double?

    enum CodingKeys: String, CodingKey {
        case market = "Market"
        case sector = "Sector"
        case horizonTradingDays = "Horizon_Trading_Days"
        case sectorWeight = "Sector_Weight"
        case actualContribution = "Actual_Contribution"
        case excessContribution = "Excess_Contribution"
    }
}

struct StockDetailSelection: Identifiable {
    let id = UUID()
    let ticker: String
    let name: String
    let currency: String
    let metrics: [StaticMetric]
    let signals: [InvestmentSignal]
}

private struct DiagnosticInfo: Identifiable {
    let id: String
    let title: String
    let status: String
    let summary: String
    let details: [String]
}

private extension KeyedDecodingContainer {
    func decodeLossyStringIfPresent(_ key: Key) throws -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key), value.isFinite {
            return String(value)
        }
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value ? "true" : "false"
        }
        return nil
    }

    func decodeLossyDoubleIfPresent(_ key: Key) throws -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key), value.isFinite {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Double(value)
        }
        if let raw = try? decodeIfPresent(String.self, forKey: key) {
            let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: ",", with: "")
            return Double(value).flatMap { $0.isFinite ? $0 : nil }
        }
        return nil
    }

    func decodeLossyIntIfPresent(_ key: Key) throws -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value
        }
        if let value = try? decodeLossyDoubleIfPresent(key) {
            return Int(value)
        }
        return nil
    }

    func decodeLossyBoolIfPresent(_ key: Key) throws -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value != 0
        }
        if let raw = try? decodeIfPresent(String.self, forKey: key) {
            switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "1", "yes", "y":
                return true
            case "false", "0", "no", "n":
                return false
            default:
                return nil
            }
        }
        return nil
    }
}

extension APIClientProtocol {
    func searchUniverse(query: String, limit: Int = 100) async throws -> SearchResponse {
        try await fetch(
            ["search", "universe"],
            queryItems: [
                URLQueryItem(name: "q", value: query),
                URLQueryItem(name: "limit", value: "\(limit)")
            ]
        )
    }

    func fetchScored(market: Market, limit: Int = 300) async throws -> ScoredResponse {
        try await fetch(
            ["scored", market.rawValue.lowercased()],
            queryItems: [URLQueryItem(name: "limit", value: "\(limit)")]
        )
    }

    func fetchResearchQuality() async throws -> ResearchQuality {
        try await fetch(["research", "factor-quality"])
    }

    func fetchMLBlendReport() async throws -> MLBlendReport {
        try await fetch(["research", "ml-blend"])
    }

    func fetchPolicyAdjustedRanking(market: Market, limit: Int = 12) async throws -> PolicyAdjustedRankingResponse {
        try await fetch(
            ["research", "policy-adjusted-ranking"],
            queryItems: [
                URLQueryItem(name: "market", value: market.rawValue),
                URLQueryItem(name: "limit", value: "\(limit)")
            ]
        )
    }

    func fetchOpsHealth() async throws -> OpsHealth {
        try await fetch(["ops", "health"])
    }

    func fetchBacktest(market: Market, smallCap: Bool = false) async throws -> BacktestEnvelope {
        try await fetch([smallCap ? "smallcap-backtest" : "backtest", market.rawValue.lowercased()])
    }

    func fetchDriftItems() async throws -> DriftResponse {
        try await fetch(["risk", "drift"])
    }

    func fetchIndustryItems(limit: Int = 30) async throws -> IndustryResponse {
        try await fetch(["risk", "industry"], queryItems: [URLQueryItem(name: "limit", value: "\(limit)")])
    }

    func fetchOrderFlowItems(limit: Int = 30) async throws -> OrderFlowResponse {
        try await fetch(["risk", "order-flow"], queryItems: [URLQueryItem(name: "limit", value: "\(limit)")])
    }

    func fetchPortfolioRisk(market: Market, limit: Int = 30) async throws -> PortfolioRiskResponse {
        try await fetch(
            ["risk", "portfolio", market.rawValue.lowercased()],
            queryItems: [URLQueryItem(name: "limit", value: "\(limit)")]
        )
    }

    func fetchRebalanceReport(market: Market, limit: Int = 50) async throws -> RebalanceResponse {
        try await fetch(
            ["rebalance", market.rawValue.lowercased()],
            queryItems: [URLQueryItem(name: "limit", value: "\(limit)")]
        )
    }

    func fetchShadowAttribution(market: String = "ALL", limit: Int = 50) async throws -> ShadowAttributionResponse {
        try await fetch(
            ["shadow", "attribution"],
            queryItems: [
                URLQueryItem(name: "market", value: market),
                URLQueryItem(name: "limit", value: "\(limit)")
            ]
        )
    }
}

private func resultOf<T>(_ operation: @escaping () async throws -> T) async -> Result<T, Error> {
    do {
        return .success(try await operation())
    } catch {
        return .failure(error)
    }
}

@MainActor
final class ExploreVM: ObservableObject {
    @Published var companies: [SearchStock] = []
    @Published var usScored: [ScoredStock] = []
    @Published var krScored: [ScoredStock] = []
    @Published var researchQuality: ResearchQuality?
    @Published var mlBlendReport: MLBlendReport?
    @Published var policyAdjustedRankings: [PolicyAdjustedRankingResponse] = []
    @Published var opsHealth: OpsHealth?
    @Published var backtests: [BacktestSummary] = []
    @Published var driftItems: [DriftItem] = []
    @Published var industryItems: [IndustryItem] = []
    @Published var orderFlowItems: [OrderFlowItem] = []
    @Published var riskHoldings: [RiskHolding] = []
    @Published var riskSectors: [RiskSector] = []
    @Published var rebalanceOrders: [RebalanceOrder] = []
    @Published var shadowSummaries: [ShadowAttributionSummary] = []
    @Published var shadowItems: [ShadowAttributionItem] = []
    @Published private var loadingModes: Set<ExploreMode> = []
    @Published private var modeErrors: [ExploreMode: String] = [:]

    private var loadedModes: Set<ExploreMode> = []
    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    var isLoading: Bool {
        !loadingModes.isEmpty
    }

    func load() async {
        await load(mode: .companies, query: "")
    }

    func load(mode: ExploreMode, query: String) async {
        guard !loadedModes.contains(mode) else { return }
        await refresh(mode: mode, query: query)
    }

    func refresh(query: String) async {
        await refresh(mode: .companies, query: query)
    }

    func refresh(mode: ExploreMode, query: String) async {
        guard beginLoading(mode) else { return }

        switch mode {
        case .companies:
            do {
                let response = try await api.searchUniverse(query: query)
                try Task.checkCancellation()
                companies = response.stocks
                finishLoading(mode)
            } catch is CancellationError {
                finishLoading(mode, markLoaded: loadedModes.contains(mode))
            } catch {
                finishLoading(mode, error: error.localizedDescription, markLoaded: false)
            }
        case .scores:
            do {
                async let usScores = api.fetchScored(market: .us)
                async let krScores = api.fetchScored(market: .kr)
                usScored = try await usScores.stocks
                krScored = try await krScores.stocks
                finishLoading(mode)
            } catch {
                finishLoading(mode, error: error.localizedDescription, markLoaded: false)
            }
        case .strategy:
            let failures = await loadStrategy()
            finishLoading(mode, error: failures.isEmpty ? nil : failures.joined(separator: "\n"), markLoaded: true)
        case .diagnostics:
            let failures = await loadDiagnostics()
            finishLoading(mode, error: failures.isEmpty ? nil : failures.joined(separator: "\n"), markLoaded: true)
        }
    }

    func searchCompanies(query: String) async {
        await refresh(mode: .companies, query: query)
    }

    func isLoading(_ mode: ExploreMode) -> Bool {
        loadingModes.contains(mode)
    }

    func error(for mode: ExploreMode) -> String? {
        modeErrors[mode]
    }

    private func beginLoading(_ mode: ExploreMode) -> Bool {
        guard !loadingModes.contains(mode) else { return false }
        loadingModes.insert(mode)
        modeErrors.removeValue(forKey: mode)
        return true
    }

    private func finishLoading(_ mode: ExploreMode, error: String? = nil, markLoaded: Bool = true) {
        loadingModes.remove(mode)
        if let error, !error.isEmpty {
            modeErrors[mode] = error
        } else {
            modeErrors.removeValue(forKey: mode)
        }
        if markLoaded {
            loadedModes.insert(mode)
        }
    }

    private func loadDiagnostics() async -> [String] {
        async let quality = resultOf { try await self.api.fetchResearchQuality() }
        async let mlBlend = resultOf { try await self.api.fetchMLBlendReport() }
        async let usPolicy = resultOf { try await self.api.fetchPolicyAdjustedRanking(market: .us) }
        async let krPolicy = resultOf { try await self.api.fetchPolicyAdjustedRanking(market: .kr) }
        async let ops = resultOf { try await self.api.fetchOpsHealth() }

        let results = await (quality, mlBlend, usPolicy, krPolicy, ops)
        var failures: [String] = []

        switch results.0 {
        case .success(let value):
            researchQuality = value
        case .failure(let error):
            failures.append("리서치 품질: \(error.localizedDescription)")
        }
        switch results.1 {
        case .success(let value):
            mlBlendReport = value
        case .failure(let error):
            failures.append("AI 보정: \(error.localizedDescription)")
        }
        var policyRankings: [PolicyAdjustedRankingResponse] = []
        switch results.2 {
        case .success(let value):
            policyRankings.append(value)
        case .failure(let error):
            failures.append("US 정책 섀도 랭킹: \(error.localizedDescription)")
        }
        switch results.3 {
        case .success(let value):
            policyRankings.append(value)
        case .failure(let error):
            failures.append("KR 정책 섀도 랭킹: \(error.localizedDescription)")
        }
        if !policyRankings.isEmpty {
            self.policyAdjustedRankings = policyRankings.sorted { ($0.market ?? "") < ($1.market ?? "") }
        }
        switch results.4 {
        case .success(let value):
            opsHealth = value
        case .failure(let error):
            failures.append("운영 상태: \(error.localizedDescription)")
        }
        return failures
    }

    private func loadStrategy() async -> [String] {
        async let backtestResult = loadBacktests()
        async let drift = resultOf { try await self.api.fetchDriftItems() }
        async let industries = resultOf { try await self.api.fetchIndustryItems() }
        async let orderFlow = resultOf { try await self.api.fetchOrderFlowItems() }
        async let usRisk = resultOf { try await self.api.fetchPortfolioRisk(market: .us) }
        async let krRisk = resultOf { try await self.api.fetchPortfolioRisk(market: .kr) }
        async let usRebalance = resultOf { try await self.api.fetchRebalanceReport(market: .us) }
        async let krRebalance = resultOf { try await self.api.fetchRebalanceReport(market: .kr) }
        async let shadow = resultOf { try await self.api.fetchShadowAttribution() }

        let loadedBacktests = await backtestResult
        let results = await (drift, industries, orderFlow, usRisk, krRisk, usRebalance, krRebalance, shadow)
        var failures = loadedBacktests.failures
        backtests = loadedBacktests.summaries

        switch results.0 {
        case .success(let value):
            driftItems = value.items
        case .failure(let error):
            failures.append("드리프트: \(error.localizedDescription)")
        }
        switch results.1 {
        case .success(let value):
            industryItems = value.items
        case .failure(let error):
            failures.append("업종 랭킹: \(error.localizedDescription)")
        }
        switch results.2 {
        case .success(let value):
            orderFlowItems = value.items
        case .failure(let error):
            failures.append("오더플로우: \(error.localizedDescription)")
        }

        var riskReports: [PortfolioRiskResponse] = []
        switch results.3 {
        case .success(let value):
            riskReports.append(value)
        case .failure(let error):
            failures.append("US 리스크: \(error.localizedDescription)")
        }
        switch results.4 {
        case .success(let value):
            riskReports.append(value)
        case .failure(let error):
            failures.append("KR 리스크: \(error.localizedDescription)")
        }
        riskHoldings = riskReports.flatMap(\.holdings)
        riskSectors = riskReports.flatMap(\.sectors)

        var rebalanceReports: [RebalanceResponse] = []
        switch results.5 {
        case .success(let value):
            rebalanceReports.append(value)
        case .failure(let error):
            failures.append("US 리밸런싱: \(error.localizedDescription)")
        }
        switch results.6 {
        case .success(let value):
            rebalanceReports.append(value)
        case .failure(let error):
            failures.append("KR 리밸런싱: \(error.localizedDescription)")
        }
        rebalanceOrders = rebalanceReports.flatMap(\.orders)

        switch results.7 {
        case .success(let value):
            shadowSummaries = value.summary
            shadowItems = value.items
        case .failure(let error):
            failures.append("섀도우 평가: \(error.localizedDescription)")
        }
        return failures
    }

    private func loadBacktests() async -> (summaries: [BacktestSummary], failures: [String]) {
        async let us = resultOf { try await self.api.fetchBacktest(market: .us).summary }
        async let kr = resultOf { try await self.api.fetchBacktest(market: .kr).summary }
        async let usSmall = resultOf { try await self.api.fetchBacktest(market: .us, smallCap: true).summary }
        async let krSmall = resultOf { try await self.api.fetchBacktest(market: .kr, smallCap: true).summary }

        let results = await [
            ("US 백테스트", us),
            ("KR 백테스트", kr),
            ("US SmallCap 백테스트", usSmall),
            ("KR SmallCap 백테스트", krSmall)
        ]
        var summaries: [BacktestSummary] = []
        var failures: [String] = []
        for (label, result) in results {
            switch result {
            case .success(let summary):
                if let summary {
                    summaries.append(summary)
                }
            case .failure(let error):
                failures.append("\(label): \(error.localizedDescription)")
            }
        }
        return (summaries, failures)
    }
}

struct ExploreView: View {
    let showsAdvancedModes: Bool

    @StateObject private var vm = ExploreVM()
    @State private var mode: ExploreMode = .companies
    @State private var market: MarketScope = .all
    @State private var companyFilter: SearchCompanyFilter = .all
    @State private var query = ""
    @State private var lastAutoSearchQuery = ""
    @State private var selectedDetail: StockDetailSelection?
    @State private var selectedDiagnosticInfo: DiagnosticInfo?
    @State private var showComparison = false
    @AppStorage("qubit_recent_search_queries") private var recentSearchRaw = ""
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var comparison: ComparisonStore
    @Environment(\.dismiss) private var dismiss

    init(showsAdvancedModes: Bool = true) {
        self.showsAdvancedModes = showsAdvancedModes
    }

    private var availableModes: [ExploreMode] {
        showsAdvancedModes ? ExploreMode.allCases : [.companies, .scores, .diagnostics]
    }

    private var visibleCompanies: [SearchStock] {
        vm.companies
            .filter { market.accepts($0.market) }
            .filter { companyFilter.accepts($0, watchlist: watchlist) }
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: $0.sector) }
    }

    private var groupedVisibleCompanies: [(SearchResultGroup, [SearchStock])] {
        Dictionary(grouping: visibleCompanies, by: searchResultGroup)
            .sorted {
                if $0.key.sortOrder == $1.key.sortOrder { return $0.key.rawValue < $1.key.rawValue }
                return $0.key.sortOrder < $1.key.sortOrder
            }
    }

    private var recentSearches: [String] {
        recentSearchRaw
            .split(separator: "|")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private var visibleScores: [ScoredStock] {
        (vm.usScored + vm.krScored)
            .filter { market.accepts($0.market) }
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: $0.sector) }
            .sorted { bestScoredValue($0) ?? -.infinity > bestScoredValue($1) ?? -.infinity }
    }

    private var visibleRiskHoldings: [RiskHolding] {
        vm.riskHoldings
            .filter { market.accepts($0.market) }
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: $0.sector) }
    }

    private var visibleRiskSectors: [RiskSector] {
        vm.riskSectors.filter { market.accepts($0.market) }
    }

    private var visibleRebalanceOrders: [RebalanceOrder] {
        vm.rebalanceOrders
            .filter { market.accepts($0.market) }
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: nil) }
    }

    private var visibleShadowSummaries: [ShadowAttributionSummary] {
        vm.shadowSummaries.filter { market.accepts($0.market) }
    }

    private var visibleShadowItems: [ShadowAttributionItem] {
        vm.shadowItems
            .filter { market.accepts($0.market) }
            .filter { textMatches(query, ticker: $0.ticker, name: $0.name, sector: nil) }
    }

    var body: some View {
        NavigationStack {
            List {
                controlsSection

                if let error = vm.error(for: mode) {
                    Section {
                        ErrView(msg: error, retry: refresh)
                            .listRowBackground(AppTheme.card)
                    }
                }

                switch mode {
                case .companies:
                    companiesSection
                case .scores:
                    scoresSection
                case .strategy:
                    strategySections
                case .diagnostics:
                    diagnosticsSections
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .appScreenBackground()
            .navigationTitle(showsAdvancedModes ? "Explore" : "검색")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
            .overlay(alignment: .top) {
                LoadingOverlay(isVisible: vm.isLoading(mode) && hasData(for: mode))
            }
            .refreshable { await vm.refresh(mode: mode, query: query) }
            .safeAreaInset(edge: .bottom) {
                if !comparison.items.isEmpty {
                    SearchComparisonDock(
                        count: comparison.items.count,
                        canCompare: comparison.canCompare,
                        show: { showComparison = true },
                        clear: comparison.clear
                    )
                }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "티커, 기업명, 섹터")
        .onSubmit(of: .search) {
            submitCompanySearch()
        }
        .task { await vm.load(mode: mode, query: query) }
        .task(id: "\(mode.rawValue):\(query)") {
            await debouncedCompanySearch()
        }
        .onChange(of: mode) { _, newMode in
            Task { await vm.load(mode: newMode, query: query) }
        }
        .onChange(of: showsAdvancedModes) { _, _ in
            if !availableModes.contains(mode) {
                mode = .companies
            }
        }
        .fullScreenCover(item: $selectedDetail) { detail in
            StockDetailSheet(
                ticker: detail.ticker,
                name: detail.name,
                currency: detail.currency,
                staticMetrics: detail.metrics,
                investmentSignals: detail.signals
            )
        }
        .sheet(item: $selectedDiagnosticInfo) { info in
            DiagnosticInfoSheet(info: info)
        }
        .sheet(isPresented: $showComparison) {
            StockComparisonSheet(items: comparison.items)
                .presentationDetents([.fraction(0.8), .large])
                .presentationDragIndicator(.visible)
        }
    }

    private func hasData(for mode: ExploreMode) -> Bool {
        switch mode {
        case .companies:
            return !vm.companies.isEmpty
        case .scores:
            return !vm.usScored.isEmpty || !vm.krScored.isEmpty
        case .strategy:
            return !vm.backtests.isEmpty || !vm.riskHoldings.isEmpty || !vm.rebalanceOrders.isEmpty || !vm.shadowItems.isEmpty
        case .diagnostics:
            return vm.researchQuality != nil || vm.mlBlendReport != nil || !vm.policyAdjustedRankings.isEmpty || vm.opsHealth != nil
        }
    }

    private var controlsSection: some View {
        Section {
            ExploreModeHeader(mode: mode)

            AppSegmentSwitch(options: availableModes, selection: $mode) { mode in
                !showsAdvancedModes && mode == .scores ? "추천" : mode.rawValue
            }

            if mode == .companies {
                SearchFilterPicker(selection: $companyFilter)
            }

            AppSegmentSwitch(options: MarketScope.allCases, selection: $market) { scope in
                scope.rawValue
            }

            HStack {
                Button {
                    submitCompanySearch()
                } label: {
                    HStack(spacing: 5) {
                        LucideIconView(icon: .search, size: 14)
                        Text("검색")
                    }
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.secondaryText)

                Button {
                    refresh()
                } label: {
                    HStack(spacing: 5) {
                        LucideIconView(icon: .refreshCw, size: 14)
                        Text("동기화")
                    }
                }
                .buttonStyle(.bordered)
                .tint(AppTheme.secondaryText)
            }

            SearchStatusLine(
                query: query,
                visibleCount: searchVisibleCount,
                totalCount: searchTotalCount,
                label: mode.title,
                isLoading: vm.isLoading(mode)
            )

            if mode == .companies,
               normalizedSearchQuery.isEmpty,
               !recentSearches.isEmpty {
                RecentSearchChips(
                    items: recentSearches,
                    select: { value in
                        query = value
                        submitCompanySearch()
                    },
                    clear: { recentSearchRaw = "" }
                )
            }
        }
        .listRowBackground(AppTheme.card)
    }

    private var companiesSection: some View {
        Section("기업 탐색 (\(visibleCompanies.count)/\(vm.companies.count)개)") {
            if visibleCompanies.isEmpty {
                if vm.isLoading(.companies) {
                    SkeletonLoadingCard(titleWidth: 122, lineCount: 2)
                        .listRowBackground(AppTheme.card)
                } else {
                    EmptyMsg(icon: "magnifyingglass", msg: "검색 결과 없음", detail: searchEmptyDetail)
                }
            } else {
                ForEach(groupedVisibleCompanies, id: \.0) { group, stocks in
                    SearchGroupHeader(group: group, count: stocks.count)
                        .listRowBackground(AppTheme.card)
                    ForEach(Array(stocks.enumerated()), id: \.element.id) { index, stock in
                        SearchCompanyRow(index: index + 1, stock: stock) {
                            recordRecentSearch(query)
                            selectedDetail = searchDetail(stock)
                        }
                        .listRowBackground(AppTheme.card)
                    }
                }
            }
        }
    }

    private var scoresSection: some View {
        Section("\(scoreSectionTitle) (\(visibleScores.count)개)") {
            if visibleScores.isEmpty {
                if vm.isLoading(.scores) {
                    SkeletonLoadingCard(titleWidth: 122, lineCount: 2)
                        .listRowBackground(AppTheme.card)
                } else {
                    EmptyMsg(icon: "chart.bar.xaxis", msg: showsAdvancedModes ? "스코어 데이터 없음" : "추천 데이터 없음", detail: searchEmptyDetail)
                }
            } else {
                ForEach(Array(visibleScores.enumerated()), id: \.element.id) { index, stock in
                    ScoredStockRow(index: index + 1, stock: stock) {
                        selectedDetail = scoredDetail(stock)
                    }
                    .listRowBackground(AppTheme.card)
                }
            }
        }
    }

    private var scoreSectionTitle: String {
        showsAdvancedModes ? "스코어 랭킹" : "추천 후보"
    }

    private var searchVisibleCount: Int {
        switch mode {
        case .companies:
            return visibleCompanies.count
        case .scores:
            return visibleScores.count
        case .strategy:
            return visibleRiskHoldings.count + visibleRiskSectors.count + visibleRebalanceOrders.count + visibleShadowItems.count
        case .diagnostics:
            return [vm.researchQuality != nil, vm.mlBlendReport != nil, !vm.policyAdjustedRankings.isEmpty, vm.opsHealth != nil].filter { $0 }.count
        }
    }

    private var searchTotalCount: Int {
        switch mode {
        case .companies:
            return vm.companies.count
        case .scores:
            return vm.usScored.count + vm.krScored.count
        case .strategy:
            return vm.riskHoldings.count + vm.riskSectors.count + vm.rebalanceOrders.count + vm.shadowItems.count
        case .diagnostics:
            return 4
        }
    }

    private var searchEmptyDetail: String {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty {
            return "현재 선택한 시장과 모드에 일치하는 데이터가 없습니다. 큐빗은 모든 종목을 얕게 보여주지 않고, 분석 가능한 기업만 깊게 봅니다."
        }
        return "\"\(clean)\"는 아직 큐빗 커버리지 밖일 수 있습니다. 데이터 품질과 추적 기준을 통과한 기업부터 먼저 보여줍니다."
    }

    private var strategySections: some View {
        Group {
            Section {
                ExploreSummaryCard(
                    title: "백테스트",
                    value: "\(vm.backtests.count)개",
                    subtitle: "미국/국내 분석 상위군과 스몰캡 백테스트 요약",
                    status: "성과"
                )
                .listRowBackground(AppTheme.card)
            }

            Section("Backtests") {
                if vm.backtests.isEmpty {
                    EmptyMsg(icon: "chart.line.uptrend.xyaxis", msg: "백테스트 없음")
                } else {
                    ForEach(vm.backtests) { item in
                        StatusListRow(
                            title: backtestTitle(item),
                            status: item.market,
                            subtitle: "누적 \(pct(item.cumulativeReturn)) · MDD \(pct(item.maxDrawdown)) · 기간 \(item.periods) · \(item.latestDate.isEmpty ? "-" : item.latestDate)"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Rebalance Drift (\(vm.driftItems.count))") {
                if vm.driftItems.isEmpty {
                    EmptyMsg(icon: "scale.3d", msg: "드리프트 없음")
                } else {
                    ForEach(vm.driftItems.prefix(20)) { item in
                        StatusListRow(
                            title: "\(item.market) \(item.name)",
                            status: item.status,
                            subtitle: "\(item.ticker) · 드리프트 \(pct(item.driftAbs, signed: false)) · 목표 \(pct(item.targetWeight, signed: false)) → 현재 \(pct(item.currentWeight, signed: false))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Portfolio Risk (\(visibleRiskHoldings.count))") {
                if visibleRiskHoldings.isEmpty {
                    EmptyMsg(icon: "chart.pie", msg: "리스크 기여도 없음")
                } else {
                    ForEach(visibleRiskHoldings.prefix(20)) { item in
                        StatusListRow(
                            title: "\(item.market ?? "-") \(item.name)",
                            status: pct(item.riskContributionPct, signed: false),
                            subtitle: "\(item.ticker) · 비중 \(pct(item.portfolioWeight, signed: false)) · 변동성 \(pct(item.assetVol, signed: false)) · W/R \(factorNumber(item.weightRiskRatio))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Sector Risk (\(visibleRiskSectors.count))") {
                if visibleRiskSectors.isEmpty {
                    EmptyMsg(icon: "square.grid.2x2", msg: "섹터 리스크 없음")
                } else {
                    ForEach(visibleRiskSectors.prefix(12)) { item in
                        StatusListRow(
                            title: "\(item.market ?? "-") \(item.sector)",
                            status: pct(item.riskContributionPct, signed: false),
                            subtitle: "비중 \(pct(item.sectorWeight, signed: false)) · 보유 \(item.holdings.map { String(Int($0)) } ?? "0")개"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Rebalance Orders (\(visibleRebalanceOrders.count))") {
                if visibleRebalanceOrders.isEmpty {
                    EmptyMsg(icon: "arrow.triangle.2.circlepath", msg: "실행 주문 없음")
                } else {
                    ForEach(visibleRebalanceOrders.prefix(20)) { item in
                        StatusListRow(
                            title: "\(item.market ?? "-") \(item.name)",
                            status: item.action,
                            subtitle: "\(item.ticker) · \(rebalanceTradeText(item)) · 목표 \(pct(item.targetWeight, signed: false)) · 비용 \(compactNumber(item.costEstimate))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Shadow Attribution (\(visibleShadowSummaries.count))") {
                if visibleShadowSummaries.isEmpty {
                    EmptyMsg(icon: "scope", msg: "섀도우 평가 없음")
                } else {
                    ForEach(visibleShadowSummaries.prefix(6)) { item in
                        StatusListRow(
                            title: "\(item.market) \(horizonLabel(item.horizonTradingDays))",
                            status: pct(item.alphaActual),
                            subtitle: "실제 \(pct(item.actualReturn)) · BM \(pct(item.benchmarkReturn)) · 적중률 \(pct(item.hitRate, signed: false)) · IC \(factorNumber(item.scoreReturnIc))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Shadow Contributors (\(visibleShadowItems.count))") {
                if visibleShadowItems.isEmpty {
                    EmptyMsg(icon: "plus.forwardslash.minus", msg: "종목별 귀속 없음")
                } else {
                    ForEach(visibleShadowItems.prefix(15)) { item in
                        StatusListRow(
                            title: "\(item.market) \(item.name)",
                            status: pct(item.actualContribution),
                            subtitle: "\(item.ticker) · \(horizonLabel(item.horizonTradingDays)) · 수익률 \(pct(item.stockReturn)) · BM \(pct(item.benchmarkReturn)) · 초과 \(pct(item.excessContribution))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("US Industry Ranking (\(vm.industryItems.count))") {
                if vm.industryItems.isEmpty {
                    EmptyMsg(icon: "building.2", msg: "업종 랭킹 없음")
                } else {
                    ForEach(vm.industryItems.prefix(15)) { item in
                        StatusListRow(
                            title: "#\(item.rank.map(String.init) ?? "-") \(item.industry)",
                            status: "Rank",
                            subtitle: "종목 \(item.stockCount ?? 0) · 평균수익 \(pct(item.meanReturn)) · Breadth \(pct(item.breadth, signed: false))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("KR Order Flow (\(vm.orderFlowItems.count))") {
                if vm.orderFlowItems.isEmpty {
                    EmptyMsg(icon: "arrow.left.arrow.right", msg: "오더플로우 없음")
                } else {
                    ForEach(vm.orderFlowItems.prefix(15)) { item in
                        StatusListRow(
                            title: "#\(item.rank.map(String.init) ?? "-") \(item.name)",
                            status: "\(item.consecutiveDays ?? 0)일",
                            subtitle: "\(item.ticker) · 외국인 \(compactNumber(item.foreignNetBuy)) · 기관 \(compactNumber(item.instNetBuy))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }
        }
    }

    private var diagnosticsSections: some View {
        let quality = vm.researchQuality
        let mlBlend = vm.mlBlendReport
        let policyRankings = vm.policyAdjustedRankings
        let ops = vm.opsHealth

        return Group {
            Section {
                Button {
                    selectedDiagnosticInfo = researchQualityDiagnosticInfo(quality)
                } label: {
                    ExploreSummaryCard(
                        title: "리서치 품질",
                        value: quality?.overallStatus ?? "-",
                        subtitle: "경고 \(quality?.warningCount ?? 0) · 운영 가능 \(quality?.productionReadyCount ?? 0) · Proxy \(quality?.proxyEvidenceCount ?? 0)",
                        status: "\(quality?.items.count ?? 0)"
                    )
                }
                .buttonStyle(.plain)
                .listRowBackground(AppTheme.card)
                .accessibilityHint("리서치 품질 설명 열기")

                Button {
                    selectedDiagnosticInfo = mlBlendDiagnosticInfo(mlBlend)
                } label: {
                    ExploreSummaryCard(
                        title: "AI 보정",
                        value: mlBlend?.status ?? "-",
                        subtitle: "AI \(pct(mlBlend?.latest?.mlWeight, signed: false)) · 기본 점수 \(pct(mlBlend?.latest?.factorWeight, signed: false)) · 예측력 \(factorNumber(mlBlend?.latest?.rankIc))",
                        status: mlBlend?.latest?.predictedStocks.map { String(Int($0)) } ?? "\(mlBlend?.items.count ?? 0)"
                    )
                }
                .buttonStyle(.plain)
                .listRowBackground(AppTheme.card)
                .accessibilityHint("AI 보정 설명 열기")

                Button {
                    selectedDiagnosticInfo = policyAdjustedRankingDiagnosticInfo(policyRankings)
                } label: {
                    ExploreSummaryCard(
                        title: "정책 섀도 랭킹",
                        value: policyRankingHeaderValue(policyRankings),
                        subtitle: policyRankingHeaderSubtitle(policyRankings),
                        status: policyRankings.map(\.items.count).reduce(0, +) > 0 ? "\(policyRankings.map(\.items.count).reduce(0, +))" : "-"
                    )
                }
                .buttonStyle(.plain)
                .listRowBackground(AppTheme.card)
                .accessibilityHint("정책 섀도 랭킹 설명 열기")

                Button {
                    selectedDiagnosticInfo = opsHealthDiagnosticInfo(ops)
                } label: {
                    ExploreSummaryCard(
                        title: "운영 상태",
                        value: ops?.status ?? "-",
                        subtitle: "체크 \(ops?.checks.count ?? 0) · 생성 \(ops?.generatedAt.prefix(16) ?? "-")",
                        status: ops?.healthy == true ? "OK" : "확인"
                    )
                }
                .buttonStyle(.plain)
                .listRowBackground(AppTheme.card)
                .accessibilityHint("운영 상태 설명 열기")
            }

            Section("정책 조정 섀도 랭킹 (\(policyRankings.count))") {
                if policyRankings.isEmpty {
                    EmptyMsg(icon: "scope", msg: "정책 섀도 랭킹 없음")
                } else {
                    ForEach(policyRankings, id: \.market) { ranking in
                        StatusListRow(
                            title: "\(ranking.market ?? "-") 정책 조정",
                            status: ranking.summary?.productionReady == true ? "READY" : "HOLD",
                            subtitle: policyRankingSummarySubtitle(ranking)
                        )
                        .listRowBackground(AppTheme.card)

                        ForEach(Array(ranking.topUp.prefix(3))) { item in
                            StatusListRow(
                                title: "상승 \(item.name)",
                                status: signedStep(item.rankChange),
                                subtitle: policyAdjustedRankingItemSubtitle(item)
                            )
                            .listRowBackground(AppTheme.card)
                        }

                        ForEach(Array(ranking.topDown.prefix(3))) { item in
                            StatusListRow(
                                title: "하락 \(item.name)",
                                status: signedStep(item.rankChange),
                                subtitle: policyAdjustedRankingItemSubtitle(item)
                            )
                            .listRowBackground(AppTheme.card)
                        }
                    }
                }
            }

            Section("AI 보정 리포트 (\(mlBlend?.items.count ?? 0))") {
                if mlBlend?.items.isEmpty ?? true {
                    EmptyMsg(icon: "brain.head.profile", msg: "AI 보정 리포트 없음")
                } else {
                    ForEach(Array(mlBlend!.items.prefix(20))) { item in
                        StatusListRow(
                            title: "\(item.market) \(item.model)",
                            status: item.status ?? mlBlend?.status ?? "REVIEW",
                            subtitle: "AI \(pct(item.mlWeight, signed: false)) · 기본 점수 \(pct(item.factorWeight, signed: false)) · 예측력 \(factorNumber(item.rankIc)) · 독립성 \(factorNumber(item.mlFactorSpearman))"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Signal Quality Gates (\(quality?.items.count ?? 0))") {
                if quality?.items.isEmpty ?? true {
                    EmptyMsg(icon: "checkmark.seal", msg: "품질 게이트 없음")
                } else {
                    ForEach(Array(quality!.items.prefix(30).enumerated()), id: \.offset) { _, gate in
                        StatusListRow(
                            title: "\(gate.market) \(gate.factor)",
                            status: gate.status,
                            subtitle: "IC \(factorNumber(gate.meanIc)) · 양수율 \(pct(gate.positiveRate, signed: false)) · 스냅샷 \(gate.snapshots.map { String(Int($0)) } ?? "0")"
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }

            Section("Ops Checks (\(ops?.checks.count ?? 0))") {
                if ops?.checks.isEmpty ?? true {
                    EmptyMsg(icon: "stethoscope", msg: "운영 체크 없음")
                } else {
                    ForEach(ops!.checks) { check in
                        StatusListRow(
                            title: check.name,
                            status: check.status,
                            subtitle: check.message.isEmpty ? "세부 메시지 없음" : check.message
                        )
                        .listRowBackground(AppTheme.card)
                    }
                }
            }
        }
    }

    private func refresh() {
        Task { await vm.refresh(mode: mode, query: query) }
    }

    private var normalizedSearchQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func submitCompanySearch() {
        let searchQuery = normalizedSearchQuery
        lastAutoSearchQuery = searchQuery
        recordRecentSearch(searchQuery)
        Task { await vm.searchCompanies(query: searchQuery) }
    }

    private func recordRecentSearch(_ value: String) {
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 2 else { return }
        recentSearchRaw = ([clean] + recentSearches.filter { $0.caseInsensitiveCompare(clean) != .orderedSame })
            .prefix(8)
            .joined(separator: "|")
    }

    private func debouncedCompanySearch() async {
        guard mode == .companies else { return }
        let searchQuery = normalizedSearchQuery
        guard searchQuery != lastAutoSearchQuery else { return }
        guard searchQuery.isEmpty || searchQuery.count >= 2 else { return }

        do {
            try await Task.sleep(nanoseconds: 400_000_000)
            try Task.checkCancellation()
        } catch {
            return
        }

        lastAutoSearchQuery = searchQuery
        await vm.searchCompanies(query: searchQuery)
    }
}

private struct ExploreModeHeader: View {
    let mode: ExploreMode

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            LucideIconView(icon: lucideIcon(forSystemSymbol: mode.systemImage), size: 16)
                .foregroundStyle(AppTheme.secondaryText)
                .frame(width: 32, height: 32)
                .background(
                    Circle()
                        .fill(AppTheme.elevatedCard)
                        .overlay(
                            Circle()
                                .stroke(AppTheme.hairline, lineWidth: 0.5)
                        )
                )
            VStack(alignment: .leading, spacing: 4) {
                Text(mode.title)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(mode.subtitle)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

private enum SearchCompanyFilter: String, CaseIterable, Identifiable, Hashable {
    case all = "전체"
    case portfolio = "포트"
    case smallCap = "스몰캡"
    case unwatched = "미저장"

    var id: String { rawValue }

    @MainActor
    func accepts(_ stock: SearchStock, watchlist: WatchlistStore) -> Bool {
        switch self {
        case .all:
            return true
        case .portfolio:
            return stock.inPortfolio
        case .smallCap:
            return stock.inSmallCap
        case .unwatched:
            return !watchlist.contains(stock.ticker)
        }
    }
}

private enum SearchResultGroup: String, CaseIterable, Identifiable, Hashable {
    case company = "기업"
    case etf = "ETF"
    case indicator = "지수"
    case other = "기타"

    var id: String { rawValue }

    var sortOrder: Int {
        switch self {
        case .company:
            return 0
        case .etf:
            return 1
        case .indicator:
            return 2
        case .other:
            return 3
        }
    }

    var icon: LucideIcon {
        switch self {
        case .company:
            return .building2
        case .etf:
            return .pieChart
        case .indicator:
            return .lineChart
        case .other:
            return .search
        }
    }
}

private func searchResultGroup(_ stock: SearchStock) -> SearchResultGroup {
    let ticker = stock.ticker.uppercased()
    let sector = stock.sector?.uppercased() ?? ""
    if sector.hasPrefix("ETF") {
        return .etf
    }
    if ticker.hasPrefix("^") || ticker.hasSuffix("=F") || ticker.hasSuffix("=X") {
        return .indicator
    }
    if !sector.isEmpty {
        return .company
    }
    return .other
}

private struct SearchFilterPicker: View {
    @Binding var selection: SearchCompanyFilter

    var body: some View {
        AppSegmentSwitch(options: SearchCompanyFilter.allCases, selection: $selection) { filter in
            filter.rawValue
        }
    }
}

private struct RecentSearchChips: View {
    let items: [String]
    let select: (String) -> Void
    let clear: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                LucideIconView(icon: .calendarClock, size: 14)
                    .foregroundStyle(AppTheme.tertiaryText)
                Text("최근 검색")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
                Spacer(minLength: 8)
                Button("지우기", action: clear)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.tertiaryText)
                    .buttonStyle(.plain)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(items, id: \.self) { item in
                        Button {
                            select(item)
                        } label: {
                            Text(item)
                                .font(.caption.weight(.bold))
                                .foregroundStyle(AppTheme.primaryText)
                                .lineLimit(1)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(AppTheme.elevatedCard, in: Capsule())
                                .overlay(
                                    Capsule()
                                        .stroke(AppTheme.hairline, lineWidth: 0.7)
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 1)
            }
        }
        .padding(.top, 2)
    }
}

private struct SearchGroupHeader: View {
    let group: SearchResultGroup
    let count: Int

    var body: some View {
        HStack(spacing: 9) {
            LucideIconView(icon: group.icon, size: 14)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 28, height: 28)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
            Text(group.rawValue)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Spacer(minLength: 8)
            Text("\(count)개")
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.secondaryText)
                .monospacedDigit()
        }
        .padding(.vertical, 4)
    }
}

private struct SearchComparisonDock: View {
    let count: Int
    let canCompare: Bool
    let show: () -> Void
    let clear: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 5) {
                LucideIconView(icon: .gitCompare, size: 13)
                Text("비교 \(count)/4")
            }
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Spacer(minLength: 8)
            Button("비우기", action: clear)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .buttonStyle(.plain)
            Button("비교 보기", action: show)
                .font(.caption.weight(.bold))
                .buttonStyle(.borderedProminent)
                .disabled(!canCompare)
        }
        .padding(.horizontal)
        .padding(.vertical, 9)
        .background(AppTheme.background)
    }
}

private struct SearchCompanyRow: View {
    let index: Int
    let stock: SearchStock
    let open: () -> Void
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var comparison: ComparisonStore

    private var currency: String {
        stock.currency ?? marketCurrency(for: stock.ticker, market: stock.market)
    }

    var body: some View {
        Button(action: open) {
            HStack(spacing: 10) {
                CompanyLogoView(ticker: stock.ticker, currency: currency, accent: AppTheme.secondaryText)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(stock.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                        TickerBadge(ticker: stock.ticker, accent: AppTheme.secondaryText)
                    }
                    Text(stock.sector.map { portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: $0) } ?? cap(stock.marketCap, currency: currency))
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                    HStack(spacing: 12) {
                        Kpi(label: "시총", value: cap(stock.marketCap, currency: currency))
                        Kpi(label: "상태", value: searchStatus(stock), color: searchStatusColor(stock))
                    }
                }
                Spacer()
                Text("\(index)")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.tertiaryText)
                Button(action: addComparison) {
                    LucideIconView(icon: comparison.contains(stock.ticker) ? .check : .gitCompare, size: 18)
                        .foregroundStyle(comparison.contains(stock.ticker) ? AppTheme.momentum : .secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("비교에 추가")
                Button(action: toggleWatch) {
                    Image(systemName: watchlist.contains(stock.ticker) ? "heart.fill" : "heart")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(watchlist.contains(stock.ticker) ? .yellow : .secondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }

    private func toggleWatch() {
        watchlist.toggle(watchlistItem(
            ticker: stock.ticker,
            name: stock.name,
            market: stock.market,
            currency: currency,
            note: "검색"
        ))
    }

    private func addComparison() {
        comparison.add(StockComparisonItem(search: stock))
    }
}

private struct ScoredStockRow: View {
    let index: Int
    let stock: ScoredStock
    let open: () -> Void
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var comparison: ComparisonStore

    private var currency: String {
        marketCurrency(for: stock.ticker, market: stock.market)
    }

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    CompanyLogoView(ticker: stock.ticker, currency: currency)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(stock.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AppTheme.primaryText)
                                .lineLimit(1)
                            TickerBadge(ticker: stock.ticker)
                        }
                        Text(stock.sector.map { portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: $0) } ?? cap(stock.marketCap, currency: currency))
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(bestScoredValue(stock).map { String(format: "%.3f", $0) } ?? "-")
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.accent)
                        Text("#\(index)")
                            .font(.system(size: 12))
                            .foregroundStyle(AppTheme.tertiaryText)
                    }
                    Button(action: toggleWatch) {
                        Image(systemName: watchlist.contains(stock.ticker) ? "heart.fill" : "heart")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(watchlist.contains(stock.ticker) ? .yellow : .secondary)
                    }
                    .buttonStyle(.plain)
                    Button(action: addComparison) {
                        LucideIconView(icon: comparison.contains(stock.ticker) ? .check : .gitCompare, size: 18)
                            .foregroundStyle(comparison.contains(stock.ticker) ? AppTheme.momentum : .secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("비교에 추가")
                }

                HStack(spacing: 16) {
                    if let valueScore = stock.valueScore {
                        Kpi(label: "V", value: factorNumber(valueScore))
                    }
                    if let qualityScore = stock.qualityScore {
                        Kpi(label: "Q", value: factorNumber(qualityScore))
                    }
                    if let momentumScore = stock.momentumScore {
                        Kpi(label: "M", value: factorNumber(momentumScore))
                    }
                    if let mlScore = stock.mlScore {
                        Kpi(label: "AI", value: factorNumber(mlScore))
                    }
                    if let investabilityScore = stock.investabilityScore {
                        Kpi(label: "투자", value: factorNumber(investabilityScore))
                    }
                    if stock.valueScore == nil && stock.qualityScore == nil && stock.momentumScore == nil {
                        Kpi(label: "ROIC", value: pct(stock.roic, signed: false))
                        Kpi(label: "성장", value: pct(stock.revGrowth))
                        Kpi(label: "마진", value: pct(stock.grossMargin, signed: false))
                    }
                    Spacer()
                }
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }

    private func toggleWatch() {
        watchlist.toggle(watchlistItem(
            ticker: stock.ticker,
            name: stock.name,
            market: stock.market,
            currency: currency,
            note: "스코어"
        ))
    }

    private func addComparison() {
        comparison.add(StockComparisonItem(scored: stock))
    }
}

private struct ExploreSummaryCard: View {
    let title: String
    let value: String
    let subtitle: String
    let status: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                Text(value)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(statusColor(value))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AppTheme.tertiaryText)
                    .lineLimit(2)
            }
            Spacer()
            Text(status)
                .font(.caption.weight(.bold))
                .foregroundStyle(statusColor(status))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule().fill(statusColor(status).opacity(0.12)))
        }
        .padding(.vertical, 4)
    }
}

private struct DiagnosticInfoSheet: View {
    let info: DiagnosticInfo
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ExploreSummaryCard(
                        title: info.title,
                        value: info.status,
                        subtitle: info.summary,
                        status: "설명"
                    )
                    .listRowBackground(AppTheme.card)
                }

                Section("의미") {
                    ForEach(info.details, id: \.self) { detail in
                        Text(detail)
                            .font(.body)
                            .foregroundStyle(AppTheme.primaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .listRowBackground(AppTheme.card)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .appScreenBackground()
            .navigationTitle(info.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") {
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct StatusListRow: View {
    let title: String
    let status: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
            }
            Spacer()
            Text(status.uppercased())
                .font(.caption.weight(.bold))
                .foregroundStyle(statusColor(status))
        }
        .padding(.vertical, 7)
    }
}

private extension StockComparisonItem {
    init(search stock: SearchStock) {
        let currency = stock.currency ?? marketCurrency(for: stock.ticker, market: stock.market)
        self.init(
            ticker: stock.ticker,
            name: stock.name,
            market: stock.market,
            sector: stock.sector,
            currency: currency,
            source: "검색",
            marketCap: stock.marketCap
        )
    }

    init(scored stock: ScoredStock) {
        let currency = marketCurrency(for: stock.ticker, market: stock.market)
        self.init(
            ticker: stock.ticker,
            name: stock.name,
            market: stock.market,
            sector: stock.sector,
            currency: currency,
            source: "랭킹",
            score: bestScoredValue(stock),
            revenueGrowth: stock.revGrowth,
            roic: stock.roic,
            grossMargin: stock.grossMargin,
            marketCap: stock.marketCap,
            fcfMargin: stock.fcfMargin
        )
    }
}

private func searchStatus(_ stock: SearchStock) -> String {
    if stock.inPortfolio && stock.inSmallCap { return "포트+스몰" }
    if stock.inPortfolio { return "포트" }
    if stock.inSmallCap { return "스몰" }
    return "-"
}

private func searchStatusColor(_ stock: SearchStock) -> Color {
    if stock.inPortfolio && stock.inSmallCap { return AppTheme.momentum }
    if stock.inPortfolio { return AppTheme.quality }
    if stock.inSmallCap { return AppTheme.warning }
    return AppTheme.secondaryText
}

private func searchDetail(_ stock: SearchStock) -> StockDetailSelection {
    let currency = stock.currency ?? marketCurrency(for: stock.ticker, market: stock.market)
    let signals: [InvestmentSignal]
    if stock.inPortfolio || stock.inSmallCap {
        signals = [
            stock.inPortfolio ? InvestmentSignal(
                title: "분석 상위군 포함",
                detail: "현재 모델 분석 상위군에 포함된 종목입니다.",
                systemImage: "chart.pie.fill",
                color: AppTheme.quality
            ) : nil,
            stock.inSmallCap ? InvestmentSignal(
                title: "스몰캡 후보",
                detail: "스몰캡 리스트에 포함된 후보입니다.",
                systemImage: "diamond.fill",
                color: AppTheme.warning
            ) : nil
        ].compactMap { $0 }
    } else {
        signals = [.init(
            title: "전체 유니버스 종목",
            detail: "아직 분석 상위군이나 스몰캡 후보는 아니지만, 차트와 기업 정보를 확인할 수 있습니다.",
            systemImage: "doc.text.magnifyingglass",
            color: .secondary
        )]
    }

    return StockDetailSelection(
        ticker: stock.ticker,
        name: stock.name,
        currency: currency,
        metrics: [
            StaticMetric(label: "시장", value: stock.market ?? "-"),
            StaticMetric(label: "섹터", value: stock.sector.map { portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: $0) } ?? "-"),
            StaticMetric(label: "시가총액", value: cap(stock.marketCap, currency: currency)),
            StaticMetric(label: "상태", value: searchStatus(stock), color: searchStatusColor(stock))
        ],
        signals: signals
    )
}

private func scoredDetail(_ stock: ScoredStock) -> StockDetailSelection {
    let currency = marketCurrency(for: stock.ticker, market: stock.market)
    return StockDetailSelection(
        ticker: stock.ticker,
        name: stock.name,
        currency: currency,
        metrics: [
            StaticMetric(label: "랭킹", value: stock.rank.map(String.init) ?? "-"),
            StaticMetric(label: "최종 점수", value: bestScoredValue(stock).map { String(format: "%.3f", $0) } ?? "-", color: scoreColor(bestScoredValue(stock))),
            StaticMetric(label: "AI 보정", value: factorNumber(stock.mlScore), color: scoreColor(stock.mlScore)),
            StaticMetric(label: "Value", value: factorNumber(stock.valueScore), color: scoreColor(stock.valueScore)),
            StaticMetric(label: "Quality", value: factorNumber(stock.qualityScore), color: scoreColor(stock.qualityScore)),
            StaticMetric(label: "Momentum", value: factorNumber(stock.momentumScore), color: scoreColor(stock.momentumScore)),
            StaticMetric(label: "기업품질", value: factorNumber(stock.businessQualityScore), color: scoreColor(stock.businessQualityScore)),
            StaticMetric(label: "투자가능", value: factorNumber(stock.investabilityScore), color: scoreColor(stock.investabilityScore)),
            StaticMetric(label: "품질분류", value: cleanQualityText(stock.qualityCategory) ?? "-"),
            StaticMetric(label: "데이터 신뢰도", value: factorNumber(stock.qualityDataConfidence), color: scoreColor(stock.qualityDataConfidence)),
            StaticMetric(label: "ROIC", value: pct(stock.roic, signed: false)),
            StaticMetric(label: "매출성장", value: pct(stock.revGrowth), color: (stock.revGrowth ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative),
            StaticMetric(label: "시가총액", value: cap(stock.marketCap, currency: currency))
        ],
        signals: scoredSignals(stock)
    )
}

private func scoredSignals(_ stock: ScoredStock) -> [InvestmentSignal] {
    let signals: [InvestmentSignal?] = [
        (stock.valueScore ?? 0) >= 0.7 ? .init(
            title: "가치 팩터 우수",
            detail: "Value 점수가 높아 저평가 매력이 상대적으로 큽니다.",
            systemImage: "tag.circle.fill",
            color: .green
        ) : nil,
        (stock.qualityScore ?? 0) >= 0.7 ? .init(
            title: "퀄리티 팩터 우수",
            detail: "수익성과 재무 품질이 상대적으로 강합니다.",
            systemImage: "checkmark.seal.fill",
            color: .green
        ) : nil,
        (stock.momentumScore ?? 0) >= 0.7 ? .init(
            title: "모멘텀 팩터 우수",
            detail: "가격 또는 이익 모멘텀 신호가 강합니다.",
            systemImage: "bolt.circle.fill",
            color: AppTheme.accent
        ) : nil,
        (stock.mlScore ?? 0) >= 0.7 ? .init(
            title: "AI 보정 우호",
            detail: "랜덤 포레스트 예측이 같은 유니버스 안에서 상위권입니다.",
            systemImage: "brain.head.profile",
            color: AppTheme.accent
        ) : nil,
        (stock.investabilityScore ?? 0) >= 0.7 ? .init(
            title: "투자가능성 우수",
            detail: "기업 품질, 밸류에이션, 타이밍을 함께 반영한 전문가형 품질 점수가 높습니다.",
            systemImage: "checkmark.seal.fill",
            color: AppTheme.quality
        ) : nil,
        cleanQualityText(stock.qualityRedFlags).map { flags in
            .init(
                title: "품질 플래그 확인",
                detail: flags,
                systemImage: "exclamationmark.triangle.fill",
                color: AppTheme.warning
            )
        }
    ]
    let compacted = signals.compactMap { $0 }
    if compacted.isEmpty {
        return [.init(
            title: "팩터 점수 확인",
            detail: "\(stock.name)의 V/Q/M 팩터를 함께 비교해보세요.",
            systemImage: "chart.bar.doc.horizontal",
            color: .secondary
        )]
    }
    return compacted
}

private func cleanQualityText(_ value: String?) -> String? {
    let text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    return text.isEmpty || text == "-" ? nil : text
}

private func bestScoredValue(_ stock: ScoredStock) -> Double? {
    stock.combinedScore ?? stock.finalScore ?? stock.totalScore ?? stock.scoreNeutral
}

private func scoreColor(_ value: Double?) -> Color {
    guard let value else { return .primary }
    if value >= 0.7 { return .green }
    if value < 0.45 { return .orange }
    return AppTheme.accent
}

private func factorNumber(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    return String(format: "%.3f", value)
}

private func compactNumber(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    let absValue = abs(value)
    if absValue >= 1_000_000_000_000 {
        return String(format: "%.1f조", value / 1_000_000_000_000)
    }
    if absValue >= 100_000_000 {
        return String(format: "%.1f억", value / 100_000_000)
    }
    if absValue >= 1_000_000 {
        return String(format: "%.1fM", value / 1_000_000)
    }
    return String(format: "%.0f", value)
}

private func rebalanceTradeText(_ order: RebalanceOrder) -> String {
    let delta = pct(order.deltaWeight, signed: true)
    let trade = compactNumber(order.executableTradeValue)
    return "변화 \(delta) · 거래 \(trade)"
}

private func horizonLabel(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "기간 -" }
    return "\(Int(value))거래일"
}

private func backtestTitle(_ summary: BacktestSummary) -> String {
    if summary.sheet.contains("SmallCap") {
        return "\(summary.market) 스몰캡"
    }
    return "\(summary.market) 분석"
}

private func policyRankingHeaderValue(_ rankings: [PolicyAdjustedRankingResponse]) -> String {
    guard !rankings.isEmpty else { return "-" }
    let readyCount = rankings.filter { $0.summary?.productionReady == true }.count
    return readyCount == rankings.count ? "READY" : "HOLD"
}

private func policyRankingHeaderSubtitle(_ rankings: [PolicyAdjustedRankingResponse]) -> String {
    guard !rankings.isEmpty else { return "정책 조정 랭킹 대기" }
    return rankings.map { ranking in
        let market = ranking.market ?? "-"
        let upwardTicker = ranking.summary?.topUpTicker ?? ranking.topUp.first?.ticker ?? "-"
        let downwardTicker = ranking.summary?.topDownTicker ?? ranking.topDown.first?.ticker ?? "-"
        return "\(market) \(upwardTicker)↑ · \(downwardTicker)↓"
    }.joined(separator: " / ")
}

private func policyRankingSummarySubtitle(_ ranking: PolicyAdjustedRankingResponse) -> String {
    let positiveMovers = ranking.summary?.positiveMovers ?? 0
    let negativeMovers = ranking.summary?.negativeMovers ?? 0
    let meanChange = factorNumber(ranking.summary?.meanAbsRankChange)
    let multipliers = ranking.summary?.multipliers ?? "-"
    return "상승 \(positiveMovers) · 하락 \(negativeMovers) · 평균 변화 \(meanChange)계단 · \(multipliers)"
}

private func policyAdjustedRankingItemSubtitle(_ item: PolicyAdjustedRankingItem) -> String {
    let baseRank = item.baseRank.map(String.init) ?? "-"
    let policyRank = item.policyRank.map(String.init) ?? "-"
    return "\(item.ticker) · 기준 #\(baseRank) → 정책 #\(policyRank) · 점수 \(factorNumber(item.policyFinalScore)) · 변화 \(pct(item.scoreChange))"
}

private func signedStep(_ value: Int?) -> String {
    guard let value else { return "-" }
    return value > 0 ? "+\(value)" : "\(value)"
}

private func researchQualityDiagnosticInfo(_ quality: ResearchQuality?) -> DiagnosticInfo {
    DiagnosticInfo(
        id: "research-quality",
        title: "리서치 품질",
        status: quality?.overallStatus ?? "-",
        summary: "팩터 신호가 실제 투자 엔진에 들어갈 만큼 검증됐는지 요약합니다.",
        details: [
            "Status는 전체 factor quality gate 결과입니다. FAIL은 하나 이상의 핵심 신호가 기준을 통과하지 못했다는 뜻입니다.",
            "경고는 IC, 양수 IC 비율, 샘플 수, 프록시 사용 같은 조건에서 주의가 필요한 항목 수입니다.",
            "운영 가능은 실제 스코어링과 리밸런싱에 넣어도 된다고 판정된 팩터 수입니다.",
            "Proxy는 실제 원천 데이터 대신 대체 데이터로 계산한 근거 수입니다. 값이 높을수록 해석 보수성이 필요합니다."
        ]
    )
}

private func mlBlendDiagnosticInfo(_ report: MLBlendReport?) -> DiagnosticInfo {
    let latest = report?.latest
    return DiagnosticInfo(
        id: "ml-blend",
        title: "AI 보정",
        status: report?.status ?? "-",
        summary: "AI 보정 점수를 기본 점수에 얼마나 반영했는지와 그 근거를 보여줍니다.",
        details: [
            "AI 비중은 최근 예측력에 따라 자동으로 낮아지거나 높아집니다. 예측력이 약하거나 음수이면 영향은 제한됩니다.",
            "기본 점수 비중은 기존 퀄리티, 밸류, 모멘텀 중심 점수의 비중입니다. 현재 기준 컬럼은 \(latest?.factorScoreColumn ?? "-")입니다.",
            "Rank IC는 예측 순위와 이후 수익률 순위의 관계입니다. 양수이고 충분히 커야 독립적인 예측력으로 볼 수 있습니다.",
            "독립성은 AI 보정 점수와 기본 점수의 상관입니다. 너무 높으면 기존 점수를 반복할 가능성이 큽니다.",
            "Top5는 현재 블렌딩 기준 상위 후보입니다. 종목 선택은 리스크와 리밸런싱 결과까지 함께 확인해야 합니다."
        ]
    )
}

private func policyAdjustedRankingDiagnosticInfo(_ rankings: [PolicyAdjustedRankingResponse]) -> DiagnosticInfo {
    let markets = rankings.compactMap(\.market).joined(separator: " / ")
    let rows = rankings.map(\.items.count).reduce(0, +)
    let summaries = rankings.compactMap(\.summary)
    let ready = summaries.filter { $0.productionReady == true }.count
    return DiagnosticInfo(
        id: "policy-adjusted-ranking",
        title: "정책 섀도 랭킹",
        status: markets.isEmpty ? "-" : markets,
        summary: "팩터 정책을 실제 점수 테이블에 적용하기 전에 순위가 어떻게 바뀌는지 확인합니다.",
        details: [
            "현재 표시된 종목 수는 \(rows)개이며, 시장별 상위 상승/하락 종목을 분리해서 보여줍니다.",
            "Ready 시장은 \(ready)/\(summaries.count)개입니다. Hold는 운영 점수에 바로 반영하지 않고 관찰해야 한다는 뜻입니다.",
            "Rank Change가 양수이면 정책 적용 후 순위가 올라간 종목이고, 음수이면 내려간 종목입니다.",
            "이 랭킹은 shadow 결과라서 기존 추천 순위는 바꾸지 않습니다. 검증이 쌓이면 운영 정책으로 승격할 후보입니다."
        ]
    )
}

private func opsHealthDiagnosticInfo(_ ops: OpsHealth?) -> DiagnosticInfo {
    DiagnosticInfo(
        id: "ops-health",
        title: "운영 상태",
        status: ops?.status ?? "-",
        summary: "자동 실행에 필요한 API, 데이터 freshness, 산출물 생성 상태를 점검한 결과입니다.",
        details: [
            "Status가 OK이면 핵심 체크가 통과했고, DEGRADED이면 일부 데이터나 산출물이 늦거나 불완전하다는 뜻입니다.",
            "체크 수는 API 응답, 파일 생성, 최신 날짜, 데이터 품질 같은 운영 점검 항목의 개수입니다.",
            "생성 시간은 서버가 이 진단 결과를 만든 시각입니다. 오래된 시간이면 앱보다 파이프라인 실행 상태를 먼저 확인해야 합니다.",
            "운영 상태가 나빠도 앱이 열릴 수는 있지만, 스코어와 백테스트 해석은 보수적으로 봐야 합니다."
        ]
    )
}

private func statusColor(_ status: String) -> Color {
    let upper = status.uppercased()
    if ["OK", "PASS", "IMPROVED", "ML_STRONG", "READY"].contains(upper) {
        return .green
    }
    if ["FAIL", "FAILED", "WORSE"].contains(upper) {
        return .red
    }
    if ["WATCH", "WARN", "DEGRADED", "INSUFFICIENT", "STALE", "UNAVAILABLE", "REVIEW", "ML_OFF", "ML_WEAK", "HOLD", "확인"].contains(upper) {
        return .orange
    }
    return AppTheme.accent
}
