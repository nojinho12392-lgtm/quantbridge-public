package com.qubit.quantbridge

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal suspend fun QuantApi.fetchResearchQuality(): ResearchQuality {
    val json = request("research/factor-quality")
    return ResearchQuality(
        overallStatus = json.cleanString("overall_status") ?: "UNKNOWN",
        warningCount = json.cleanInt("warning_count") ?: 0,
        productionReadyCount = json.cleanInt("production_ready_count") ?: 0,
        proxyEvidenceCount = json.cleanInt("proxy_evidence_count") ?: 0,
        items = parseQualityGates(json.optJSONArray("items") ?: JSONArray())
    )
}


internal suspend fun QuantApi.fetchMLBlendReport(): MLBlendReport {
    val json = requestOptional("research/ml-blend") ?: return emptyMLBlendReport()
    return MLBlendReport(
        status = json.cleanString("status") ?: "UNAVAILABLE",
        generatedAt = json.cleanString("generated_at"),
        latest = json.optJSONObject("latest")?.let { parseMLBlendItem(it) },
        items = parseMLBlendItems(json.optJSONArray("items") ?: JSONArray())
    )
}


internal suspend fun QuantApi.fetchOpsHealth(): OpsHealth {
    val json = request("ops/health")
    return OpsHealth(
        healthy = json.cleanBool("healthy") ?: false,
        status = json.cleanString("status") ?: if (json.cleanBool("healthy") == true) "OK" else "WARN",
        generatedAt = json.cleanString("generated_at") ?: "",
        checks = parseOpsChecks(json.optJSONArray("checks") ?: JSONArray())
    )
}


internal suspend fun QuantApi.fetchAllBacktests(): List<BacktestSummary> {
    return listOfNotNull(
        runCatching { parseBacktestSummary(request("backtest/us").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull(),
        runCatching { parseBacktestSummary(request("backtest/kr").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull(),
        runCatching { parseBacktestSummary(request("smallcap-backtest/us").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull()?.copy(sheet = "US_SmallCap_Backtest"),
        runCatching { parseBacktestSummary(request("smallcap-backtest/kr").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull()?.copy(sheet = "KR_SmallCap_Backtest")
    )
}


internal suspend fun QuantApi.fetchDriftItems(): List<DriftItem> {
    return parseDriftItems(request("risk/drift").optJSONArray("items") ?: JSONArray())
}


internal suspend fun QuantApi.fetchIndustryItems(): List<IndustryItem> {
    return parseIndustryItems(request("risk/industry?limit=30").optJSONArray("items") ?: JSONArray())
}


internal suspend fun QuantApi.fetchOrderFlowItems(): List<OrderFlowItem> {
    return parseOrderFlowItems(request("risk/order-flow?limit=30").optJSONArray("items") ?: JSONArray())
}


internal suspend fun QuantApi.fetchPortfolioRisk(market: String): PortfolioRiskReport {
    val json = request("risk/portfolio/$market?limit=30")
    return PortfolioRiskReport(
        holdings = parseRiskHoldings(json.optJSONArray("holdings") ?: JSONArray()),
        sectors = parseRiskSectors(json.optJSONArray("sectors") ?: JSONArray())
    )
}


internal suspend fun QuantApi.fetchRebalanceOrders(market: String): List<RebalanceOrder> {
    return parseRebalanceOrders(request("rebalance/$market?limit=50").optJSONArray("orders") ?: JSONArray())
}


internal suspend fun QuantApi.fetchShadowAttribution(market: String = "ALL"): ShadowAttributionReport {
    val json = request("shadow/attribution?market=${Uri.encode(market)}&limit=50")
    return ShadowAttributionReport(
        summaries = parseShadowAttributionSummaries(json.optJSONArray("summary") ?: JSONArray()),
        items = parseShadowAttributionItems(json.optJSONArray("items") ?: JSONArray())
    )
}
