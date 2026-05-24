package com.example.myapplication.network

import com.example.myapplication.ApiResponseCache
import com.example.myapplication.CachedJsonResponse
import java.io.IOException
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private const val API_CACHE_MS = 5 * 60 * 1000L
private const val API_LAST_SUCCESS_CACHE_MS = 7 * 24 * 60 * 60 * 1000L
private const val MARKET_QUOTE_CACHE_MS = 20 * 1000L
private const val MARKET_HISTORY_CACHE_MS = 30 * 1000L
private const val SIGNAL_EVENTS_CACHE_MS = 60 * 1000L
private const val COMPARISON_RECOMMENDATION_CACHE_MS = 5 * 60 * 1000L
private const val MARKET_LAST_SUCCESS_CACHE_MS = 30 * 60 * 1000L
private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TIMEOUT = 408
private const val HTTP_SERVER_ERROR_MIN = 500

fun interface AuthTokenProvider {
    fun token(): String?
}

internal class TokenAuthenticator(
    private val tokenProvider: AuthTokenProvider
) : Interceptor {
    @Suppress("ReturnCount")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        if (!isAccountScopedPath(request.url.encodedPath)) return chain.proceed(request)
        val token = tokenProvider.token()?.trim().orEmpty()
        if (token.isBlank()) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }
}

internal class MultiBaseUrlInterceptor(
    baseUrls: List<String>,
    accountBaseUrls: List<String>
) : Interceptor {
    private val baseUrls = baseUrls.mapNotNull(::toHttpUrl).distinct()
    private val accountBaseUrls = accountBaseUrls.mapNotNull(::toHttpUrl).distinct()
    @Volatile private var preferredBaseUrl: HttpUrl? = null
    @Volatile private var preferredAccountBaseUrl: HttpUrl? = null

    @Suppress("ReturnCount")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val accountScoped = isAccountScopedPath(request.url.encodedPath)
        val candidates = candidateBaseUrls(accountScoped)
        if (candidates.isEmpty()) return chain.proceed(request)

        var lastException: IOException? = null
        for (baseUrl in candidates) {
            val routedRequest = request.newBuilder()
                .url(request.url.withBaseUrl(baseUrl))
                .build()
            try {
                val response = chain.proceed(routedRequest)
                if (!shouldRetryApiHttpStatus(response.code)) {
                    rememberBaseUrl(baseUrl, accountScoped)
                    return response
                }
                if (baseUrl == candidates.last()) {
                    rememberBaseUrl(baseUrl, accountScoped)
                    return response
                }
                response.close()
            } catch (error: IOException) {
                lastException = error
            }
        }
        throw lastException ?: IOException("No Quant API base URL was reachable")
    }

    private fun candidateBaseUrls(accountScoped: Boolean): List<HttpUrl> {
        val preferred = if (accountScoped) preferredAccountBaseUrl else preferredBaseUrl
        val all = if (accountScoped) accountBaseUrls else baseUrls
        return buildList {
            preferred?.let { add(it) }
            addAll(all.filter { it != preferred })
        }.distinct()
    }

    private fun rememberBaseUrl(baseUrl: HttpUrl, accountScoped: Boolean) {
        if (accountScoped) {
            preferredAccountBaseUrl = baseUrl
        } else {
            preferredBaseUrl = baseUrl
        }
    }
}

internal class LegacyApiResponseCacheInterceptor(
    private val responseCache: ApiResponseCache
) : Interceptor {
    private val mediaType = "application/json".toMediaType()

    @Suppress("ReturnCount")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isCacheable(request)) return chain.proceed(request)

        val cacheKey = legacyCacheKey(request.url)
        responseCache.read(cacheKey, cacheTtlMs(request.url.encodedPath))?.let {
            return cachedResponse(request, it, "OK (disk cache)")
        }

        try {
            val response = chain.proceed(request)
            if (!response.isSuccessful) return response
            val raw = response.body?.string().orEmpty()
            if (raw.isNotBlank()) responseCache.write(cacheKey, raw)
            return response.newBuilder()
                .body(raw.toResponseBody(mediaType))
                .build()
        } catch (error: IOException) {
            val stale = responseCache.read(cacheKey, lastSuccessCacheTtlMs(request.url.encodedPath))
            if (stale != null) return cachedResponse(request, stale, "OK (stale disk cache)")
            throw error
        }
    }

    @Suppress("ReturnCount")
    private fun isCacheable(request: Request): Boolean {
        if (request.method != "GET") return false
        if (request.header("Authorization") != null) return false
        if (request.url.queryParameter("refresh") == "true") return false
        return true
    }

    private fun cachedResponse(
        request: Request,
        cached: CachedJsonResponse,
        message: String
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message(message)
            .header("Content-Type", "application/json")
            .header("X-QuantBridge-Cache", message)
            .body(cached.raw.toResponseBody(mediaType))
            .build()
    }

    private fun cacheTtlMs(path: String): Long {
        return when {
            path.contains("/market/indicators/history") -> MARKET_HISTORY_CACHE_MS
            path.contains("/market/indices") -> MARKET_QUOTE_CACHE_MS
            path.contains("/market/indicators") -> MARKET_QUOTE_CACHE_MS
            path.contains("/signals/events") -> SIGNAL_EVENTS_CACHE_MS
            path.contains("/comparison/recommendations") -> COMPARISON_RECOMMENDATION_CACHE_MS
            else -> API_CACHE_MS
        }
    }

    private fun lastSuccessCacheTtlMs(path: String): Long {
        return when {
            path.contains("/market/indices") -> MARKET_LAST_SUCCESS_CACHE_MS
            path.contains("/market/indicators") -> MARKET_LAST_SUCCESS_CACHE_MS
            else -> API_LAST_SUCCESS_CACHE_MS
        }
    }
}

private fun HttpUrl.withBaseUrl(baseUrl: HttpUrl): HttpUrl {
    return newBuilder()
        .scheme(baseUrl.scheme)
        .host(baseUrl.host)
        .port(baseUrl.port)
        .build()
}

private fun toHttpUrl(value: String): HttpUrl? {
    val normalized = if (value.endsWith("/")) value else "$value/"
    return normalized.toHttpUrlOrNull()
}

private fun legacyCacheKey(url: HttpUrl): String {
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    val portPart = if (url.port == defaultPort) "" else ":${url.port}"
    val baseUrl = "${url.scheme}://${url.host}$portPart"
    val path = url.encodedPath.trimStart('/') + url.encodedQuery?.let { "?$it" }.orEmpty()
    return "$baseUrl|$path"
}

private fun isAccountScopedPath(encodedPath: String): Boolean {
    val path = encodedPath.trim('/').lowercase(Locale.US)
    return path == "auth" ||
        path.startsWith("auth/") ||
        path == "me" ||
        path.startsWith("me/")
}

private fun shouldRetryApiHttpStatus(code: Int): Boolean {
    return code == HTTP_NOT_FOUND || code == HTTP_TIMEOUT || code >= HTTP_SERVER_ERROR_MIN
}
