package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

data class RegimeReason(
    val title: String,
    val value: String,
    val signal: String,
    val explanation: String
)

data class ResearchQuality(
    val overallStatus: String,
    val warningCount: Int,
    val productionReadyCount: Int,
    val proxyEvidenceCount: Int,
    val items: List<QualityGate>
)

data class QualityGate(
    val market: String,
    val factor: String,
    val status: String,
    val meanIc: Double?,
    val positiveRate: Double?,
    val snapshots: Double?,
    val evidenceSource: String?,
    val productionReady: String?
)

data class MLBlendReport(
    val status: String,
    val generatedAt: String?,
    val latest: MLBlendItem?,
    val items: List<MLBlendItem>
)

data class MLBlendItem(
    val generated: String,
    val market: String,
    val model: String,
    val rankIc: Double?,
    val mlWeight: Double?,
    val factorWeight: Double?,
    val mlWeightReason: String?,
    val factorScoreColumn: String?,
    val mlFactorSpearman: Double?,
    val mlFactorPearson: Double?,
    val predictedStocks: Double?,
    val top5: String?,
    val notes: String?,
    val status: String?
)

data class PolicyAdjustedRanking(
    val market: String,
    val summary: PolicyAdjustedRankingSummary?,
    val items: List<PolicyAdjustedRankingItem>,
    val topUp: List<PolicyAdjustedRankingItem>,
    val topDown: List<PolicyAdjustedRankingItem>,
    val mode: String?
)

data class PolicyAdjustedRankingSummary(
    val generated: String?,
    val market: String,
    val policyMode: String?,
    val rows: Int,
    val positiveMovers: Int,
    val negativeMovers: Int,
    val unchanged: Int,
    val meanAbsRankChange: Double?,
    val topUpTicker: String?,
    val topUpName: String?,
    val topUpRankChange: Int?,
    val topDownTicker: String?,
    val topDownName: String?,
    val topDownRankChange: Int?,
    val topBaseTicker: String?,
    val topPolicyTicker: String?,
    val multipliers: String?,
    val evidenceSource: String?,
    val productionReady: Boolean?,
    val note: String?
)

data class PolicyAdjustedRankingItem(
    val policyRank: Int?,
    val baseRank: Int?,
    val rankChange: Int?,
    val ticker: String,
    val name: String,
    val market: String,
    val sector: String?,
    val policyFinalScore: Double?,
    val baseFinalScore: Double?,
    val scoreChange: Double?,
    val valueMultiplier: Double?,
    val qualityMultiplier: Double?,
    val momentumMultiplier: Double?,
    val policyMode: String?,
    val evidenceSource: String?,
    val productionReady: Boolean?,
    val actions: String?,
    val qualityDataConfidence: Double?,
    val generated: String?
)

data class OpsHealth(
    val healthy: Boolean,
    val status: String,
    val generatedAt: String,
    val checks: List<OpsCheck>
)

data class OpsCheck(
    val name: String,
    val status: String,
    val message: String
)

data class BacktestSummary(
    val market: String,
    val sheet: String,
    val periods: Int,
    val latestDate: String,
    val cumulativeReturn: Double?,
    val maxDrawdown: Double?,
    val avgReturn: Double?
)

data class DriftItem(
    val market: String,
    val ticker: String,
    val name: String,
    val status: String,
    val driftAbs: Double?,
    val targetWeight: Double?,
    val currentWeight: Double?,
    val returnSinceRebal: Double?
)

data class IndustryItem(
    val rank: Int?,
    val industry: String,
    val stockCount: Int?,
    val meanReturn: Double?,
    val breadth: Double?
)

data class OrderFlowItem(
    val rank: Int?,
    val ticker: String,
    val name: String,
    val consecutiveDays: Int?,
    val foreignNetBuy: Double?,
    val instNetBuy: Double?
)

data class PortfolioRiskReport(
    val holdings: List<RiskHolding>,
    val sectors: List<RiskSector>
)

data class RiskHolding(
    val market: String?,
    val ticker: String,
    val name: String,
    val sector: String?,
    val portfolioWeight: Double?,
    val assetVol: Double?,
    val riskContributionPct: Double?,
    val weightRiskRatio: Double?
)

data class RiskSector(
    val market: String?,
    val sector: String,
    val holdings: Double?,
    val sectorWeight: Double?,
    val riskContributionPct: Double?
)

data class RebalanceOrder(
    val market: String?,
    val ticker: String,
    val name: String,
    val action: String,
    val currentWeight: Double?,
    val targetWeight: Double?,
    val deltaWeight: Double?,
    val executableTradeValue: Double?,
    val costEstimate: Double?
)

data class ShadowAttributionReport(
    val summaries: List<ShadowAttributionSummary>,
    val items: List<ShadowAttributionItem>
)

data class ShadowAttributionSummary(
    val market: String,
    val horizonTradingDays: Double?,
    val actualReturn: Double?,
    val benchmarkReturn: Double?,
    val alphaActual: Double?,
    val hitRate: Double?,
    val scoreReturnIc: Double?
)

data class ShadowAttributionItem(
    val market: String,
    val ticker: String,
    val name: String,
    val horizonTradingDays: Double?,
    val weight: Double?,
    val stockReturn: Double?,
    val benchmarkReturn: Double?,
    val actualContribution: Double?,
    val excessContribution: Double?
)
