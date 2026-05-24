package com.example.myapplication

import com.example.myapplication.generated.models.QBOpsHealthResponse
import com.example.myapplication.network.QuantApiService
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
        val health = async { safeValue("운영 상태") { api.getOpsHealth().toDomain() } }

        val qualityResult = quality.await()
        val mlBlendResult = mlBlend.await()
        val healthResult = health.await()
        OpsRepositoryResult(
            researchQuality = qualityResult.value,
            mlBlendReport = mlBlendResult.value,
            opsHealth = healthResult.value,
            errors = listOfNotNull(qualityResult.error, mlBlendResult.error, healthResult.error)
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
    val opsHealth: OpsHealth?,
    val errors: List<String>
)

private data class OpsFetchResult<T>(
    val value: T?,
    val error: String?
)
