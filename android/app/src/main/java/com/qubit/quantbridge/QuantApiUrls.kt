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

internal fun defaultApiBaseUrls(accountScoped: Boolean): List<String> = buildList {
    if (accountScoped) add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
    add(BuildConfig.QUANT_API_BASE_URL)
    if (!accountScoped) add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
}.withDebugLocalFallback()

internal fun List<String>.withDebugLocalFallback(): List<String> {
    val configured = filter { it.isNotBlank() }.distinct()
    val hasRemoteUrl = configured.any { !isLocalEmulatorUrl(it) }
    return if (BuildConfig.DEBUG && !hasRemoteUrl) {
        (configured + EMULATOR_LOCAL_BASE_URL).distinct()
    } else {
        configured
    }
}

internal fun isLocalEmulatorUrl(url: String): Boolean {
    val clean = url.trim()
    return clean.startsWith("http://10.0.2.2") ||
        clean.startsWith("http://localhost") ||
        clean.startsWith("http://127.0.0.1")
}
