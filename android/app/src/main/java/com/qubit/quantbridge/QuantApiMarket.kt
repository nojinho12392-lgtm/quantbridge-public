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

internal suspend fun QuantApi.fetchMacro(): Map<String, String> = jsonToMap(request("macro"))


internal suspend fun QuantApi.fetchMarketIndices(): List<MarketIndexQuote> {
    val apiItems = runCatching {
        parseMarketIndices(request("market/indices").optJSONArray("indices") ?: JSONArray())
    }.rethrowCancellation().getOrDefault(emptyList())
    return apiItems.takeIf { it.isNotEmpty() } ?: fetchNaverMarketIndices()
}


internal suspend fun QuantApi.fetchMarketIndicators(
    refresh: Boolean = false,
    category: String = "index_fx"
): List<MarketIndicatorQuote> {
    val safeCategory = category.ifBlank { "index_fx" }
    val path = "market/indicators?category=${Uri.encode(safeCategory)}&refresh=$refresh"
    return parseMarketIndicators(request(path).optJSONArray("items") ?: JSONArray())
}


internal suspend fun QuantApi.withDomesticIndicatorOverrides(items: List<MarketIndicatorQuote>): List<MarketIndicatorQuote> =
    mergeDomesticIndicatorOverrides(items)


internal suspend fun QuantApi.fetchMarketIndicatorHistory(
    refresh: Boolean = false,
    symbols: List<String> = emptyList()
): List<MarketIndicatorSeries> {
    val symbolQuery = symbols
        .filter { it.isNotBlank() }
        .joinToString(",")
        .takeIf { it.isNotBlank() }
        ?.let { "&symbols=${Uri.encode(it)}" }
        .orEmpty()
    val path = "market/indicators/history?period=1d&interval=15m&refresh=$refresh$symbolQuery"
    return parseMarketIndicatorSeries(request(path).optJSONArray("series") ?: JSONArray())
}
