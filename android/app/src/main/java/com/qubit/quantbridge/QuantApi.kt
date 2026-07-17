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

class QuantApi(
    internal val responseCache: ApiResponseCache? = null,
    internal val baseUrls: List<String> = defaultApiBaseUrls(accountScoped = false),
    internal val accountBaseUrls: List<String> = defaultApiBaseUrls(accountScoped = true)
) {
    internal var preferredBaseUrl: String? = null
    internal var preferredAccountBaseUrl: String? = null
    internal val requestCache = mutableMapOf<String, CachedJsonResponse>()

    internal suspend fun request(
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

    internal fun isAccountScopedPath(path: String): Boolean {
        return path == "auth" ||
            path.startsWith("auth/") ||
            path == "me" ||
            path.startsWith("me/")
    }

    internal fun candidateBaseUrls(accountScoped: Boolean): List<String> {
        val preferred = if (accountScoped) preferredAccountBaseUrl else preferredBaseUrl
        val all = if (accountScoped) accountBaseUrls else baseUrls
        return buildList {
            preferred?.let { add(it) }
            addAll(all.filter { it != preferred })
        }.distinct()
    }

    internal fun rememberBaseUrl(baseUrl: String, accountScoped: Boolean) {
        if (accountScoped) {
            preferredAccountBaseUrl = baseUrl
        } else {
            preferredBaseUrl = baseUrl
        }
    }

    internal suspend fun requestOptional(path: String): JSONObject? = withContext(Dispatchers.IO) {
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

    internal fun isCacheable(method: String, path: String, body: JSONObject?, token: String?): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        if (body != null || token != null) return false
        if (path.contains("refresh=true", ignoreCase = true)) return false
        return true
    }

    internal fun cacheTtlMs(path: String): Long {
        return when {
            path.startsWith("market/indicators/history") -> MARKET_HISTORY_CACHE_MS
            path.startsWith("market/indices") -> MARKET_QUOTE_CACHE_MS
            path.startsWith("market/indicators") -> MARKET_QUOTE_CACHE_MS
            path.startsWith("signals/events") -> SIGNAL_EVENTS_CACHE_MS
            path.startsWith("comparison/recommendations") -> COMPARISON_RECOMMENDATION_CACHE_MS
            else -> API_CACHE_MS
        }
    }

    internal fun lastSuccessCacheTtlMs(path: String): Long {
        return when {
            path.startsWith("market/indices") -> MARKET_LAST_SUCCESS_CACHE_MS
            path.startsWith("market/indicators") -> MARKET_LAST_SUCCESS_CACHE_MS
            else -> API_LAST_SUCCESS_CACHE_MS
        }
    }

    internal fun isLatencySensitivePath(path: String): Boolean {
        return path.startsWith("sectors/themes") ||
            path.startsWith("etfs") ||
            path.startsWith("stock/") ||
            path.startsWith("portfolio/") ||
            path.startsWith("smallcap/") ||
            path.startsWith("calendar/earnings")
    }

    internal fun shouldUseStaleAfterFailure(path: String, error: Exception): Boolean {
        if (isLatencySensitivePath(path)) return true
        val message = error.message.orEmpty().lowercase(Locale.US)
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("failed to connect") ||
            message.contains("connection refused")
    }

    internal fun pruneRequestCache(now: Long) {
        requestCache.entries.removeAll { now - it.value.loadedAt >= API_CACHE_MS }
    }

    internal suspend fun requestAbsolute(urlString: String): JSONObject = withContext(Dispatchers.IO) {
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

    internal suspend fun fetchNaverMarketIndices(): List<MarketIndexQuote> = coroutineScope {
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

    internal suspend fun mergeDomesticIndicatorOverrides(items: List<MarketIndicatorQuote>): List<MarketIndicatorQuote> {
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

    internal fun requestOnce(
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
