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

internal suspend fun QuantApi.fetchPortfolio(market: String): Pair<Map<String, String>, List<PortfolioStock>> {
    val json = request("portfolio/$market")
    return jsonToMap(json.optJSONObject("meta") ?: JSONObject()) to
        parsePortfolio(json.optJSONArray("stocks") ?: JSONArray())
}


internal suspend fun QuantApi.fetchSmallCap(market: String): List<SmallCapStock> {
    return parseSmallCap(request("smallcap/$market").optJSONArray("stocks") ?: JSONArray())
}


internal suspend fun QuantApi.searchUniverse(query: String, limit: Int = 100): List<SearchStock> {
    val path = "search/universe?q=${Uri.encode(query)}&limit=$limit"
    return parseSearchStocks(request(path).optJSONArray("stocks") ?: JSONArray())
}


internal suspend fun QuantApi.fetchScored(market: String): List<ScoredStock> {
    return parseScoredStocks(request("scored/$market?limit=300").optJSONArray("stocks") ?: JSONArray(), market.uppercase(Locale.US))
}


internal suspend fun QuantApi.fetchEarnings(market: String): List<EarningsStock> {
    return parseEarnings(request("earnings/$market").optJSONArray("stocks") ?: JSONArray())
}


internal suspend fun QuantApi.fetchEarningsCalendar(market: String = "ALL", days: Int = 180, refresh: Boolean = false): List<EarningsCalendarItem> {
    val safeMarket = market.uppercase(Locale.US)
    val refreshParam = if (refresh) "&refresh=true" else ""
    return parseEarningsCalendar(
        request("calendar/earnings?market=$safeMarket&days=$days&limit=2000$refreshParam").optJSONArray("items") ?: JSONArray()
    )
}


internal suspend fun QuantApi.fetchSignalEvents(limit: Int = 120): List<SignalEvent> {
    return parseSignalEvents(
        request("signals/events?market=ALL&limit=$limit").optJSONArray("items") ?: JSONArray()
    )
}


internal suspend fun QuantApi.fetchStockPriceMetrics(
    market: String,
    tickers: List<String>,
    refresh: Boolean = false
): List<StockPriceMetric> {
    val safeMarket = market.uppercase(Locale.US)
    val cleanTickers = tickers
        .map { it.trim().uppercase(Locale.US) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(100)
    if (cleanTickers.isEmpty()) return emptyList()
    val path = "portfolio/$safeMarket/prices?tickers=${Uri.encode(cleanTickers.joinToString(","))}&limit=${cleanTickers.size}&refresh=$refresh"
    return parseStockPriceMetrics(request(path).optJSONArray("metrics") ?: JSONArray())
}


internal suspend fun QuantApi.fetchSectorThemes(
    market: String = "ALL",
    limit: Int = 36,
    members: Int = 120,
    refresh: Boolean = false
): List<SectorTheme> {
    return fetchSectorThemesResult(market, limit, members, refresh).items
}


internal suspend fun QuantApi.fetchSectorThemesResult(
    market: String = "ALL",
    limit: Int = 36,
    members: Int = 120,
    refresh: Boolean = false
): SectorThemesResult {
    val safeMarket = market.uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
    val path = "sectors/themes/summary?market=${Uri.encode(safeMarket)}&limit=$limit&schema=sector-move-v2&refresh=$refresh"
    val json = request(path)
    return SectorThemesResult(
        items = parseSectorThemes(json.optJSONArray("items") ?: JSONArray()),
        market = json.cleanString("market") ?: safeMarket,
        source = json.cleanString("source"),
        generatedAt = json.cleanString("generated_at") ?: json.cleanString("updated_at")
    )
}


internal suspend fun QuantApi.fetchSectorThemeDetail(
    label: String,
    market: String = "ALL",
    members: Int = 200,
    refresh: Boolean = false
): SectorTheme? {
    val safeMarket = market.uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
    val path = "sectors/themes/detail?market=${Uri.encode(safeMarket)}&label=${Uri.encode(label)}&members=$members&schema=sector-detail-v1&refresh=$refresh"
    val item = request(path).optJSONObject("item") ?: return null
    return parseSectorThemes(JSONArray().put(item)).firstOrNull()
}


internal suspend fun QuantApi.fetchEtfs(limit: Int = 500, query: String = ""): List<EtfInsight> {
    return fetchEtfsResult(limit, query).items
}


internal suspend fun QuantApi.fetchEtfsResult(limit: Int = 500, query: String = ""): EtfInsightsResult {
    val q = query.trim()
    val queryPart = if (q.isNotBlank()) "&q=${Uri.encode(q)}&refresh=true" else ""
    val json = request("etfs?limit=$limit$queryPart")
    val items = parseEtfInsights(json.optJSONArray("items") ?: JSONArray())
    return EtfInsightsResult(
        items = enrichEtfPrices(items),
        source = json.cleanString("source"),
        updatedAt = json.cleanString("updated_at") ?: json.cleanString("generated_at")
    )
}


internal suspend fun QuantApi.enrichEtfPrices(items: List<EtfInsight>): List<EtfInsight> {
    val missing = items.filter { it.currentPrice == null || it.return1M == null }
    if (missing.isEmpty()) return items

    val metrics = mutableMapOf<String, EtfPriceMetric>()
    metrics += fetchEtfPriceMetrics(missing.filter { it.region == "US" }, "US")
    metrics += fetchEtfPriceMetrics(missing.filter { it.region == "KR" }, "KR")
    if (metrics.isEmpty()) return items

    return items.map { etf ->
        val metric = etf.priceLookupTickers
            .asSequence()
            .mapNotNull { metrics[it.uppercase(Locale.US)] }
            .firstOrNull { it.currentPrice != null || it.return1M != null }
        if (metric == null) {
            etf
        } else {
            etf.copy(
                currentPrice = etf.currentPrice ?: metric.currentPrice,
                return1M = etf.return1M ?: metric.return1M
            )
        }
    }
}


internal suspend fun QuantApi.fetchEtfPriceMetrics(items: List<EtfInsight>, market: String): Map<String, EtfPriceMetric> {
    if (items.isEmpty()) return emptyMap()
    val tickers = items
        .flatMap { it.priceLookupTickers }
        .map { it.uppercase(Locale.US) }
        .distinct()
        .take(100)
    if (tickers.isEmpty()) return emptyMap()

    return runCatching {
        val path = "portfolio/$market/prices?tickers=${Uri.encode(tickers.joinToString(","))}&limit=${tickers.size}&refresh=true"
        val metrics = request(path).optJSONArray("metrics") ?: JSONArray()
        buildMap {
            for (index in 0 until metrics.length()) {
                val row = metrics.optJSONObject(index) ?: continue
                val ticker = row.cleanString("Ticker")?.uppercase(Locale.US) ?: continue
                put(
                    ticker,
                    EtfPriceMetric(
                        ticker = ticker,
                        currentPrice = row.cleanDouble("Current_Price"),
                        return1M = row.cleanDouble("Return_1M")
                    )
                )
            }
        }
    }.rethrowCancellation().getOrDefault(emptyMap())
}
