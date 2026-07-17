package com.qubit.quantbridge

import com.qubit.quantbridge.generated.models.QBOpsHealthResponse
import com.qubit.quantbridge.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

@Singleton
class OpsRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchOps(): OpsRepositoryResult = coroutineScope {
        val quality = async { safeValue("리서치 품질") { parseResearchQuality(api.getResearchQualityRaw()) } }
        val mlBlend = async { safeValue("AI 보정") { parseMLBlend(api.getMLBlendReportRaw()) } }
        val usPolicy = async {
            safeValue("US 정책 섀도 랭킹") {
                parsePolicyAdjustedRanking(api.getPolicyAdjustedRankingRaw("US", 12), "US")
            }
        }
        val krPolicy = async {
            safeValue("KR 정책 섀도 랭킹") {
                parsePolicyAdjustedRanking(api.getPolicyAdjustedRankingRaw("KR", 12), "KR")
            }
        }
        val health = async { safeValue("운영 상태") { api.getOpsHealth().toDomain() } }

        val qualityResult = quality.await()
        val mlBlendResult = mlBlend.await()
        val usPolicyResult = usPolicy.await()
        val krPolicyResult = krPolicy.await()
        val healthResult = health.await()
        OpsRepositoryResult(
            researchQuality = qualityResult.value,
            mlBlendReport = mlBlendResult.value,
            policyAdjustedRankings = listOfNotNull(usPolicyResult.value, krPolicyResult.value),
            opsHealth = healthResult.value,
            errors = listOfNotNull(
                qualityResult.error,
                mlBlendResult.error,
                usPolicyResult.error,
                krPolicyResult.error,
                healthResult.error
            )
        )
    }

    private fun parseResearchQuality(response: JsonObject): ResearchQuality {
        return ResearchQuality(
            overallStatus = response.qbString("overall_status", "Overall_Status") ?: "UNKNOWN",
            warningCount = response.qbInt("warning_count", "Warning_Count") ?: 0,
            productionReadyCount = response.qbInt("production_ready_count", "Production_Ready_Count") ?: 0,
            proxyEvidenceCount = response.qbInt("proxy_evidence_count", "Proxy_Evidence_Count") ?: 0,
            items = response.qbObjects("items").map { item ->
                QualityGate(
                    market = item.qbString("Market", "market") ?: "-",
                    factor = item.qbString("Factor", "Signal", "factor", "signal") ?: "-",
                    status = item.qbString("Status", "status") ?: "UNKNOWN",
                    meanIc = item.qbDouble("Mean_IC", "mean_ic"),
                    positiveRate = item.qbDouble("Positive_IC_Rate", "positive_ic_rate"),
                    snapshots = item.qbDouble("Snapshots", "snapshots"),
                    evidenceSource = item.qbString("Evidence_Source", "evidence_source"),
                    productionReady = item.qbString("Production_Ready", "production_ready")
                )
            }
        )
    }

    private fun parseMLBlend(response: JsonObject): MLBlendReport {
        val items = response.qbObjects("items").map { it.toMLBlendItem() }
        val latest = response.qbObject("latest")?.toMLBlendItem() ?: items.firstOrNull()
        return MLBlendReport(
            status = response.qbString("status", "Status") ?: "UNAVAILABLE",
            generatedAt = response.qbString("generated_at", "Generated_At", "Generated"),
            latest = latest,
            items = items
        )
    }

    private fun JsonObject.toMLBlendItem(): MLBlendItem {
        return MLBlendItem(
            generated = qbString("Generated", "generated") ?: "",
            market = qbString("Market", "market") ?: "-",
            model = qbString("Model", "model") ?: "-",
            rankIc = qbDouble("Rank_IC", "rank_ic"),
            mlWeight = qbDouble("ML_Weight", "ml_weight"),
            factorWeight = qbDouble("Factor_Weight", "factor_weight"),
            mlWeightReason = qbString("ML_Weight_Reason", "ml_weight_reason"),
            factorScoreColumn = qbString("Factor_Score_Column", "factor_score_column"),
            mlFactorSpearman = qbDouble("ML_Factor_Spearman", "ml_factor_spearman"),
            mlFactorPearson = qbDouble("ML_Factor_Pearson", "ml_factor_pearson"),
            predictedStocks = qbDouble("Predicted_Stocks", "predicted_stocks"),
            top5 = qbString("Top5", "top5"),
            notes = qbString("Notes", "notes"),
            status = qbString("Status", "status")
        )
    }

    private fun parsePolicyAdjustedRanking(response: JsonObject, fallbackMarket: String): PolicyAdjustedRanking {
        val items = response.qbObjects("items").mapNotNull { it.toPolicyAdjustedRankingItem(fallbackMarket) }
        return PolicyAdjustedRanking(
            market = response.qbString("market", "Market") ?: fallbackMarket,
            summary = response.qbObject("summary")?.toPolicyAdjustedRankingSummary(fallbackMarket),
            items = items,
            topUp = response.qbObjects("top_up", "topUp").mapNotNull { it.toPolicyAdjustedRankingItem(fallbackMarket) },
            topDown = response.qbObjects("top_down", "topDown").mapNotNull { it.toPolicyAdjustedRankingItem(fallbackMarket) },
            mode = response.qbString("mode", "Policy_Mode") ?: items.firstOrNull()?.policyMode
        )
    }

    private fun JsonObject.toPolicyAdjustedRankingSummary(fallbackMarket: String): PolicyAdjustedRankingSummary {
        return PolicyAdjustedRankingSummary(
            generated = qbString("Generated", "generated"),
            market = qbString("Market", "market") ?: fallbackMarket,
            policyMode = qbString("Policy_Mode", "policy_mode"),
            rows = qbInt("Rows", "rows") ?: 0,
            positiveMovers = qbInt("Positive_Movers", "positive_movers") ?: 0,
            negativeMovers = qbInt("Negative_Movers", "negative_movers") ?: 0,
            unchanged = qbInt("Unchanged", "unchanged") ?: 0,
            meanAbsRankChange = qbDouble("Mean_Abs_Rank_Change", "mean_abs_rank_change"),
            topUpTicker = qbString("Top_Up_Ticker", "top_up_ticker"),
            topUpName = qbString("Top_Up_Name", "top_up_name"),
            topUpRankChange = qbInt("Top_Up_Rank_Change", "top_up_rank_change"),
            topDownTicker = qbString("Top_Down_Ticker", "top_down_ticker"),
            topDownName = qbString("Top_Down_Name", "top_down_name"),
            topDownRankChange = qbInt("Top_Down_Rank_Change", "top_down_rank_change"),
            topBaseTicker = qbString("Top_Base_Ticker", "top_base_ticker"),
            topPolicyTicker = qbString("Top_Policy_Ticker", "top_policy_ticker"),
            multipliers = qbString("Multipliers", "multipliers"),
            evidenceSource = qbString("Evidence_Source", "evidence_source"),
            productionReady = qbBool("Production_Ready", "production_ready"),
            note = qbString("Note", "note")
        )
    }

    private fun JsonObject.toPolicyAdjustedRankingItem(fallbackMarket: String): PolicyAdjustedRankingItem? {
        val ticker = qbString("Ticker", "ticker") ?: return null
        return PolicyAdjustedRankingItem(
            policyRank = qbInt("Policy_Rank", "policy_rank"),
            baseRank = qbInt("Base_Rank", "base_rank"),
            rankChange = qbInt("Rank_Change", "rank_change"),
            ticker = ticker,
            name = qbString("Name", "name") ?: ticker,
            market = qbString("Market", "market") ?: fallbackMarket,
            sector = qbString("Sector", "sector"),
            policyFinalScore = qbDouble("Policy_Final_Score", "policy_final_score"),
            baseFinalScore = qbDouble("Base_Final_Score", "base_final_score"),
            scoreChange = qbDouble("Score_Change", "score_change"),
            valueMultiplier = qbDouble("Value_Multiplier", "value_multiplier"),
            qualityMultiplier = qbDouble("Quality_Multiplier", "quality_multiplier"),
            momentumMultiplier = qbDouble("Momentum_Multiplier", "momentum_multiplier"),
            policyMode = qbString("Policy_Mode", "policy_mode"),
            evidenceSource = qbString("Policy_Evidence_Source", "policy_evidence_source"),
            productionReady = qbBool("Policy_Production_Ready", "policy_production_ready"),
            actions = qbString("Policy_Actions", "policy_actions"),
            qualityDataConfidence = qbDouble("Quality_Data_Confidence", "quality_data_confidence"),
            generated = qbString("Generated", "generated")
        )
    }

    private fun QBOpsHealthResponse.toDomain(): OpsHealth {
        return OpsHealth(
            healthy = healthy,
            status = status,
            generatedAt = generatedAt.orEmpty(),
            checks = checks.orEmpty().map {
                OpsCheck(
                    name = it.name,
                    status = it.status,
                    message = it.message.orEmpty()
                )
            }
        )
    }

    private suspend fun <T> safeValue(
        label: String,
        block: suspend () -> T
    ): OpsFetchResult<T> {
        return runCatching { block() }
            .fold(
                onSuccess = { OpsFetchResult(value = it, error = null) },
                onFailure = { OpsFetchResult(value = null, error = "$label: ${it.readableMessage()}") }
            )
    }

    private fun Throwable.readableMessage(): String {
        return localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }
}

data class OpsRepositoryResult(
    val researchQuality: ResearchQuality?,
    val mlBlendReport: MLBlendReport?,
    val policyAdjustedRankings: List<PolicyAdjustedRanking>,
    val opsHealth: OpsHealth?,
    val errors: List<String>
)

private data class OpsFetchResult<T>(
    val value: T?,
    val error: String?
)
