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

internal const val API_CACHE_MS = 5 * 60 * 1000L
internal const val API_LAST_SUCCESS_CACHE_MS = 7 * 24 * 60 * 60 * 1000L
internal const val MARKET_QUOTE_CACHE_MS = 20 * 1000L
internal const val MARKET_HISTORY_CACHE_MS = 30 * 1000L
internal const val SIGNAL_EVENTS_CACHE_MS = 60 * 1000L
internal const val COMPARISON_RECOMMENDATION_CACHE_MS = 5 * 60 * 1000L
internal const val MARKET_LAST_SUCCESS_CACHE_MS = 30 * 60 * 1000L
internal const val EMULATOR_LOCAL_BASE_URL = "http://10.0.2.2:8000"


data class CachedJsonResponse(
    val loadedAt: Long,
    val raw: String
)

internal class ApiHttpException(val code: Int, message: String) : Exception(message) {
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

internal fun validationDetailMessage(details: JSONArray): String? {
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

internal fun emptyMLBlendReport(): MLBlendReport {
    return MLBlendReport(
        status = "UNAVAILABLE",
        generatedAt = null,
        latest = null,
        items = emptyList()
    )
}

internal data class EtfPriceMetric(
    val ticker: String,
    val currentPrice: Double?,
    val return1M: Double?
)
