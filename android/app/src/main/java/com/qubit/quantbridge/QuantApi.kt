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

private const val API_CACHE_MS = 5 * 60 * 1000L
private const val API_LAST_SUCCESS_CACHE_MS = 7 * 24 * 60 * 60 * 1000L
private const val MARKET_QUOTE_CACHE_MS = 20 * 1000L
private const val MARKET_HISTORY_CACHE_MS = 30 * 1000L
private const val SIGNAL_EVENTS_CACHE_MS = 60 * 1000L
private const val COMPARISON_RECOMMENDATION_CACHE_MS = 5 * 60 * 1000L
private const val MARKET_LAST_SUCCESS_CACHE_MS = 30 * 60 * 1000L
private const val EMULATOR_LOCAL_BASE_URL = "http://10.0.2.2:8000"

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

private fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { it.throwIfCancellation() }

data class CachedJsonResponse(
    val loadedAt: Long,
    val raw: String
)

private class ApiHttpException(val code: Int, message: String) : Exception(message) {
    val canTryNextBaseUrl: Boolean
        get() = shouldRetryApiHttpStatus(code)
}

internal fun shouldRetryApiHttpStatus(code: Int): Boolean {
    return code == 404 || code == 408 || code >= 500
}

internal fun userFacingApiHttpError(code: Int, raw: String): String {
    val message = runCatching {
        val errorJson = JSONObject(raw.ifBlank { "{}" })
        errorJson.cleanString("detail")
            ?: errorJson.cleanString("error")
            ?: errorJson.cleanString("message")
            ?: errorJson.optJSONArray("detail")?.let(::validationDetailMessage)
    }.getOrNull()

    message?.takeIf { it.isNotBlank() }?.let { return it }
    return when (code) {
        401 -> "이메일 또는 비밀번호가 올바르지 않습니다"
        409 -> "이미 가입된 이메일입니다"
        422 -> "입력값을 다시 확인하세요"
        in 400..499 -> "요청을 처리하지 못했습니다 ($code)"
        else -> "서버 오류 ($code)"
    }
}

private fun validationDetailMessage(details: JSONArray): String? {
    if (details.length() == 0) return null
    val first = details.optJSONObject(0) ?: return null
    val field = first.optJSONArray("loc")
        ?.let { loc ->
            (0 until loc.length())
                .mapNotNull { index -> loc.optString(index).takeIf { it.isNotBlank() && it != "body" } }
                .lastOrNull()
        }
    val message = first.cleanString("msg") ?: return null
    return listOfNotNull(field, message)
        .joinToString(": ")
        .takeIf { it.isNotBlank() }
}

class ApiResponseCache(private val cacheDir: File) {
    private val responseDir = File(cacheDir, "quantbridge_api_responses")

    fun read(key: String, maxAgeMs: Long): CachedJsonResponse? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val separator = text.indexOf('\n')
        if (separator <= 0) return null
        val loadedAt = text.substring(0, separator).toLongOrNull() ?: return null
        if (System.currentTimeMillis() - loadedAt > maxAgeMs) return null
        val raw = text.substring(separator + 1).takeIf { it.isNotBlank() } ?: return null
        return CachedJsonResponse(loadedAt, raw)
    }

    fun write(key: String, raw: String) {
        runCatching {
            responseDir.mkdirs()
            fileFor(key).writeText("${System.currentTimeMillis()}\n$raw")
        }
    }

    private fun fileFor(key: String): File {
        return File(responseDir, "${sha256(key)}.json")
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

private fun emptyMLBlendReport(): MLBlendReport {
    return MLBlendReport(
        status = "UNAVAILABLE",
        generatedAt = null,
        latest = null,
        items = emptyList()
    )
}

private data class EtfPriceMetric(
    val ticker: String,
    val currentPrice: Double?,
    val return1M: Double?
)

class QuantApi(
    private val responseCache: ApiResponseCache? = null,
    private val baseUrls: List<String> = defaultApiBaseUrls(accountScoped = false),
    private val accountBaseUrls: List<String> = defaultApiBaseUrls(accountScoped = true)
) {
    private var preferredBaseUrl: String? = null
    private var preferredAccountBaseUrl: String? = null
    private val requestCache = mutableMapOf<String, CachedJsonResponse>()

    suspend fun fetchPortfolio(market: String): Pair<Map<String, String>, List<PortfolioStock>> {
        val json = request("portfolio/$market")
        return jsonToMap(json.optJSONObject("meta") ?: JSONObject()) to
            parsePortfolio(json.optJSONArray("stocks") ?: JSONArray())
    }

    suspend fun fetchSmallCap(market: String): List<SmallCapStock> {
        return parseSmallCap(request("smallcap/$market").optJSONArray("stocks") ?: JSONArray())
    }

    suspend fun searchUniverse(query: String, limit: Int = 100): List<SearchStock> {
        val path = "search/universe?q=${Uri.encode(query)}&limit=$limit"
        return parseSearchStocks(request(path).optJSONArray("stocks") ?: JSONArray())
    }

    suspend fun fetchScored(market: String): List<ScoredStock> {
        return parseScoredStocks(request("scored/$market?limit=300").optJSONArray("stocks") ?: JSONArray(), market.uppercase(Locale.US))
    }

    suspend fun fetchEarnings(market: String): List<EarningsStock> {
        return parseEarnings(request("earnings/$market").optJSONArray("stocks") ?: JSONArray())
    }

    suspend fun fetchEarningsCalendar(market: String = "ALL", days: Int = 180, refresh: Boolean = false): List<EarningsCalendarItem> {
        val safeMarket = market.uppercase(Locale.US)
        val refreshParam = if (refresh) "&refresh=true" else ""
        return parseEarningsCalendar(
            request("calendar/earnings?market=$safeMarket&days=$days&limit=2000$refreshParam").optJSONArray("items") ?: JSONArray()
        )
    }

    suspend fun fetchSignalEvents(limit: Int = 120): List<SignalEvent> {
        return parseSignalEvents(
            request("signals/events?market=ALL&limit=$limit").optJSONArray("items") ?: JSONArray()
        )
    }

    suspend fun fetchStockPriceMetrics(
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

    suspend fun fetchSectorThemes(
        market: String = "ALL",
        limit: Int = 36,
        members: Int = 120,
        refresh: Boolean = false
    ): List<SectorTheme> {
        return fetchSectorThemesResult(market, limit, members, refresh).items
    }

    suspend fun fetchSectorThemesResult(
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

    suspend fun fetchSectorThemeDetail(
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

    suspend fun fetchEtfs(limit: Int = 500, query: String = ""): List<EtfInsight> {
        return fetchEtfsResult(limit, query).items
    }

    suspend fun fetchEtfsResult(limit: Int = 500, query: String = ""): EtfInsightsResult {
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

    private suspend fun enrichEtfPrices(items: List<EtfInsight>): List<EtfInsight> {
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

    private suspend fun fetchEtfPriceMetrics(items: List<EtfInsight>, market: String): Map<String, EtfPriceMetric> {
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

    suspend fun fetchMacro(): Map<String, String> = jsonToMap(request("macro"))

    suspend fun fetchMarketIndices(): List<MarketIndexQuote> {
        val apiItems = runCatching {
            parseMarketIndices(request("market/indices").optJSONArray("indices") ?: JSONArray())
        }.rethrowCancellation().getOrDefault(emptyList())
        return apiItems.takeIf { it.isNotEmpty() } ?: fetchNaverMarketIndices()
    }

    suspend fun fetchMarketIndicators(
        refresh: Boolean = false,
        category: String = "index_fx"
    ): List<MarketIndicatorQuote> {
        val safeCategory = category.ifBlank { "index_fx" }
        val path = "market/indicators?category=${Uri.encode(safeCategory)}&refresh=$refresh"
        return parseMarketIndicators(request(path).optJSONArray("items") ?: JSONArray())
    }

    suspend fun withDomesticIndicatorOverrides(items: List<MarketIndicatorQuote>): List<MarketIndicatorQuote> =
        mergeDomesticIndicatorOverrides(items)

    suspend fun fetchMarketIndicatorHistory(
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

    suspend fun fetchResearchQuality(): ResearchQuality {
        val json = request("research/factor-quality")
        return ResearchQuality(
            overallStatus = json.cleanString("overall_status") ?: "UNKNOWN",
            warningCount = json.cleanInt("warning_count") ?: 0,
            productionReadyCount = json.cleanInt("production_ready_count") ?: 0,
            proxyEvidenceCount = json.cleanInt("proxy_evidence_count") ?: 0,
            items = parseQualityGates(json.optJSONArray("items") ?: JSONArray())
        )
    }

    suspend fun fetchMLBlendReport(): MLBlendReport {
        val json = requestOptional("research/ml-blend") ?: return emptyMLBlendReport()
        return MLBlendReport(
            status = json.cleanString("status") ?: "UNAVAILABLE",
            generatedAt = json.cleanString("generated_at"),
            latest = json.optJSONObject("latest")?.let { parseMLBlendItem(it) },
            items = parseMLBlendItems(json.optJSONArray("items") ?: JSONArray())
        )
    }

    suspend fun fetchOpsHealth(): OpsHealth {
        val json = request("ops/health")
        return OpsHealth(
            healthy = json.cleanBool("healthy") ?: false,
            status = json.cleanString("status") ?: if (json.cleanBool("healthy") == true) "OK" else "WARN",
            generatedAt = json.cleanString("generated_at") ?: "",
            checks = parseOpsChecks(json.optJSONArray("checks") ?: JSONArray())
        )
    }

    suspend fun fetchAllBacktests(): List<BacktestSummary> {
        return listOfNotNull(
            runCatching { parseBacktestSummary(request("backtest/us").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull(),
            runCatching { parseBacktestSummary(request("backtest/kr").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull(),
            runCatching { parseBacktestSummary(request("smallcap-backtest/us").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull()?.copy(sheet = "US_SmallCap_Backtest"),
            runCatching { parseBacktestSummary(request("smallcap-backtest/kr").optJSONObject("summary") ?: JSONObject()) }.rethrowCancellation().getOrNull()?.copy(sheet = "KR_SmallCap_Backtest")
        )
    }

    suspend fun fetchDriftItems(): List<DriftItem> {
        return parseDriftItems(request("risk/drift").optJSONArray("items") ?: JSONArray())
    }

    suspend fun fetchIndustryItems(): List<IndustryItem> {
        return parseIndustryItems(request("risk/industry?limit=30").optJSONArray("items") ?: JSONArray())
    }

    suspend fun fetchOrderFlowItems(): List<OrderFlowItem> {
        return parseOrderFlowItems(request("risk/order-flow?limit=30").optJSONArray("items") ?: JSONArray())
    }

    suspend fun fetchPortfolioRisk(market: String): PortfolioRiskReport {
        val json = request("risk/portfolio/$market?limit=30")
        return PortfolioRiskReport(
            holdings = parseRiskHoldings(json.optJSONArray("holdings") ?: JSONArray()),
            sectors = parseRiskSectors(json.optJSONArray("sectors") ?: JSONArray())
        )
    }

    suspend fun fetchRebalanceOrders(market: String): List<RebalanceOrder> {
        return parseRebalanceOrders(request("rebalance/$market?limit=50").optJSONArray("orders") ?: JSONArray())
    }

    suspend fun fetchShadowAttribution(market: String = "ALL"): ShadowAttributionReport {
        val json = request("shadow/attribution?market=${Uri.encode(market)}&limit=50")
        return ShadowAttributionReport(
            summaries = parseShadowAttributionSummaries(json.optJSONArray("summary") ?: JSONArray()),
            items = parseShadowAttributionItems(json.optJSONArray("items") ?: JSONArray())
        )
    }

    suspend fun fetchNews(query: String, market: String, limit: Int = 40): Pair<Boolean, List<NewsItem>> {
        val path = "news/issues?q=${Uri.encode(query)}&market=${Uri.encode(market)}&limit=$limit"
        val json = request(path)
        return (json.cleanBool("configured") ?: false) to parseNewsItems(json.optJSONArray("items") ?: JSONArray())
    }

    suspend fun authenticate(email: String, password: String, displayName: String?, signup: Boolean): Pair<String, AuthUser> {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        if (signup) body.put("display_name", displayName?.trim().orEmpty())
        val json = request(if (signup) "auth/signup" else "auth/login", method = "POST", body = body)
        return json.getString("access_token") to parseUser(json.getJSONObject("user"))
    }

    suspend fun me(token: String): AuthUser = parseUser(request("auth/me", token = token).getJSONObject("user"))

    suspend fun logout(token: String) {
        request("auth/logout", method = "POST", token = token)
    }

    suspend fun deleteAccount(token: String) {
        request("auth/me", method = "DELETE", token = token)
    }

    suspend fun fetchWatchlist(token: String): List<WatchlistItem> {
        return parseWatchlist(request("me/watchlist", token = token).optJSONArray("items") ?: JSONArray())
    }

    suspend fun saveWatchlist(item: WatchlistItem, token: String) {
        val body = JSONObject()
            .put("ticker", item.ticker)
            .put("name", item.name)
            .put("market", item.market)
            .put("currency", item.currency)
            .put("note", item.note)
        request("me/watchlist", method = "POST", token = token, body = body)
    }

    suspend fun deleteWatchlist(ticker: String, token: String) {
        request("me/watchlist/${Uri.encode(ticker)}", method = "DELETE", token = token)
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val errors = mutableListOf<String>()
        val cacheable = isCacheable(method, path, body, token)
        val now = System.currentTimeMillis()
        val cacheTtl = cacheTtlMs(path)
        val staleCacheTtl = lastSuccessCacheTtlMs(path)
        var staleCachedRaw: String? = null
        if (cacheable) pruneRequestCache(now)
        val accountScoped = isAccountScopedPath(path)
        val candidates = candidateBaseUrls(accountScoped)
        for (baseUrl in candidates) {
            val cacheKey = "$baseUrl|$path"
            if (cacheable) {
                requestCache[cacheKey]
                    ?.takeIf { now - it.loadedAt < cacheTtl }
                    ?.let {
                        rememberBaseUrl(baseUrl, accountScoped)
                        return@withContext JSONObject(it.raw)
                    }
                responseCache
                    ?.read(cacheKey, cacheTtl)
                    ?.let {
                        requestCache[cacheKey] = it
                        rememberBaseUrl(baseUrl, accountScoped)
                        return@withContext JSONObject(it.raw)
                    }
                if (staleCachedRaw == null) {
                    staleCachedRaw = responseCache?.read(cacheKey, staleCacheTtl)?.raw
                }
            }
            try {
                val response = requestOnce(baseUrl, path, method, body, token)
                rememberBaseUrl(baseUrl, accountScoped)
                if (cacheable) {
                    val raw = response.toString()
                    requestCache[cacheKey] = CachedJsonResponse(System.currentTimeMillis(), raw)
                    responseCache?.write(cacheKey, raw)
                }
                return@withContext response
            } catch (e: ApiHttpException) {
                lastError = e
                errors += "$baseUrl: ${e.message ?: e.javaClass.simpleName}"
                if (cacheable && staleCachedRaw != null && e.canTryNextBaseUrl && isLatencySensitivePath(path)) {
                    return@withContext JSONObject(staleCachedRaw)
                }
                if (!e.canTryNextBaseUrl) {
                    throw Exception(e.message ?: "서버 오류 (${e.code})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                errors += "$baseUrl: ${e.message ?: e.javaClass.simpleName}"
                if (cacheable && staleCachedRaw != null && shouldUseStaleAfterFailure(path, e)) {
                    return@withContext JSONObject(staleCachedRaw)
                }
            }
        }
        if (cacheable && staleCachedRaw != null) {
            return@withContext JSONObject(staleCachedRaw)
        }
        if (errors.isNotEmpty()) {
            throw Exception("서버 연결 실패 (${errors.joinToString(" / ")})")
        }
        throw lastError ?: Exception("서버에 연결하지 못했습니다")
    }

    private fun isAccountScopedPath(path: String): Boolean {
        return path == "auth" ||
            path.startsWith("auth/") ||
            path == "me" ||
            path.startsWith("me/")
    }

    private fun candidateBaseUrls(accountScoped: Boolean): List<String> {
        val preferred = if (accountScoped) preferredAccountBaseUrl else preferredBaseUrl
        val all = if (accountScoped) accountBaseUrls else baseUrls
        return buildList {
            preferred?.let { add(it) }
            addAll(all.filter { it != preferred })
        }.distinct()
    }

    private fun rememberBaseUrl(baseUrl: String, accountScoped: Boolean) {
        if (accountScoped) {
            preferredAccountBaseUrl = baseUrl
        } else {
            preferredBaseUrl = baseUrl
        }
    }

    private suspend fun requestOptional(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            preferredBaseUrl?.let { add(it) }
            addAll(baseUrls.filter { it != preferredBaseUrl })
        }
        for (baseUrl in candidates) {
            try {
                val response = requestOnce(baseUrl, path, "GET", null, null)
                preferredBaseUrl = baseUrl
                return@withContext response
            } catch (e: ApiHttpException) {
                if (e.code == 404) return@withContext null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Optional diagnostics must not block the core app experience.
            }
        }
        null
    }

    private fun isCacheable(method: String, path: String, body: JSONObject?, token: String?): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        if (body != null || token != null) return false
        if (path.contains("refresh=true", ignoreCase = true)) return false
        return true
    }

    private fun cacheTtlMs(path: String): Long {
        return when {
            path.startsWith("market/indicators/history") -> MARKET_HISTORY_CACHE_MS
            path.startsWith("market/indices") -> MARKET_QUOTE_CACHE_MS
            path.startsWith("market/indicators") -> MARKET_QUOTE_CACHE_MS
            path.startsWith("signals/events") -> SIGNAL_EVENTS_CACHE_MS
            path.startsWith("comparison/recommendations") -> COMPARISON_RECOMMENDATION_CACHE_MS
            else -> API_CACHE_MS
        }
    }

    private fun lastSuccessCacheTtlMs(path: String): Long {
        return when {
            path.startsWith("market/indices") -> MARKET_LAST_SUCCESS_CACHE_MS
            path.startsWith("market/indicators") -> MARKET_LAST_SUCCESS_CACHE_MS
            else -> API_LAST_SUCCESS_CACHE_MS
        }
    }

    private fun isLatencySensitivePath(path: String): Boolean {
        return path.startsWith("sectors/themes") ||
            path.startsWith("etfs") ||
            path.startsWith("stock/") ||
            path.startsWith("portfolio/") ||
            path.startsWith("smallcap/") ||
            path.startsWith("calendar/earnings")
    }

    private fun shouldUseStaleAfterFailure(path: String, error: Exception): Boolean {
        if (isLatencySensitivePath(path)) return true
        val message = error.message.orEmpty().lowercase(Locale.US)
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("failed to connect") ||
            message.contains("connection refused")
    }

    private fun pruneRequestCache(now: Long) {
        requestCache.entries.removeAll { now - it.value.loadedAt >= API_CACHE_MS }
    }

    private suspend fun requestAbsolute(urlString: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw Exception("외부 지표 조회 실패 ($code)")
        }
        JSONObject(if (raw.isBlank()) "{}" else raw)
    }

    private suspend fun fetchNaverMarketIndices(): List<MarketIndexQuote> = coroutineScope {
        val urls = listOf(
            "https://polling.finance.naver.com/api/realtime/worldstock/index/.INX,.IXIC",
            "https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI,KOSDAQ"
        )
        val rows = urls
            .map { url ->
                async {
                    val array = requestAbsolute(url).optJSONArray("datas") ?: JSONArray()
                    buildList {
                        for (i in 0 until array.length()) {
                            array.optJSONObject(i)?.let(::add)
                        }
                    }
                }
            }
            .flatMap { it.await() }
        val specs = listOf(
            NaverIndexSpec("^GSPC", "S&P 500", setOf("SPX", ".INX")),
            NaverIndexSpec("^IXIC", "NASDAQ", setOf("IXIC", ".IXIC")),
            NaverIndexSpec("^KS11", "KOSPI", setOf("KOSPI")),
            NaverIndexSpec("^KQ11", "KOSDAQ", setOf("KOSDAQ"))
        )
        specs.mapNotNull { spec ->
            rows.firstOrNull { it.matches(spec) }?.toMarketIndexQuote(spec)
        }
    }

    private suspend fun mergeDomesticIndicatorOverrides(items: List<MarketIndicatorQuote>): List<MarketIndicatorQuote> {
        val domesticSymbols = setOf("^KS11", "^KQ11")
        if (items.none { it.symbol in domesticSymbols }) return items
        val overrides = runCatching {
            fetchNaverMarketIndices()
                .filter { it.symbol in domesticSymbols }
                .associateBy { it.symbol }
        }.rethrowCancellation().getOrDefault(emptyMap())
        if (overrides.isEmpty()) return items
        return items.map { item ->
            val override = overrides[item.symbol] ?: return@map item
            item.copy(
                value = override.value,
                changeAbs = override.changeAbs,
                changePct = override.changePct,
                updatedAt = override.updatedAt
            )
        }
    }

    private fun requestOnce(
        baseUrl: String,
        path: String,
        method: String,
        body: JSONObject?,
        token: String?
    ): JSONObject {
        val url = URL("$baseUrl/$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Qubit-Android")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw ApiHttpException(code, userFacingApiHttpError(code, raw))
        }
        return JSONObject(if (raw.isBlank()) "{}" else raw)
    }
}

private fun defaultApiBaseUrls(accountScoped: Boolean): List<String> = buildList {
    if (accountScoped) add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
    add(BuildConfig.QUANT_API_BASE_URL)
    if (!accountScoped) add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
}.withDebugLocalFallback()

private fun List<String>.withDebugLocalFallback(): List<String> {
    val configured = filter { it.isNotBlank() }.distinct()
    val hasRemoteUrl = configured.any { !isLocalEmulatorUrl(it) }
    return if (BuildConfig.DEBUG && !hasRemoteUrl) {
        (configured + EMULATOR_LOCAL_BASE_URL).distinct()
    } else {
        configured
    }
}

private fun isLocalEmulatorUrl(url: String): Boolean {
    val clean = url.trim()
    return clean.startsWith("http://10.0.2.2") ||
        clean.startsWith("http://localhost") ||
        clean.startsWith("http://127.0.0.1")
}
