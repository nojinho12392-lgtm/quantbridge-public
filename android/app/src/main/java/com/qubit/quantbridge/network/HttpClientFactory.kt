package com.qubit.quantbridge.network

import android.content.Context
import com.qubit.quantbridge.ApiResponseCache
import com.qubit.quantbridge.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object HttpClientFactory {
    private const val OKHTTP_CACHE_BYTES = 50L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_SECONDS = 12L
    private const val READ_TIMEOUT_SECONDS = 25L
    private const val CALL_TIMEOUT_SECONDS = 35L
    private const val EMULATOR_LOCAL_BASE_URL = "http://10.0.2.2:8000"

    fun create(
        context: Context,
        tokenProvider: AuthTokenProvider = AuthTokenProvider { null },
        responseCache: ApiResponseCache? = ApiResponseCache(context.cacheDir),
        baseUrls: List<String> = defaultBaseUrls(),
        accountBaseUrls: List<String> = defaultAccountBaseUrls()
    ): QuantApiService {
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl(baseUrls.firstOrNull()))
            .client(
                createOkHttpClient(
                    context = context,
                    tokenProvider = tokenProvider,
                    responseCache = responseCache,
                    baseUrls = baseUrls,
                    accountBaseUrls = accountBaseUrls
                )
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QuantApiService::class.java)
    }

    fun createOkHttpClient(
        context: Context,
        tokenProvider: AuthTokenProvider,
        responseCache: ApiResponseCache?,
        baseUrls: List<String> = defaultBaseUrls(),
        accountBaseUrls: List<String> = defaultAccountBaseUrls()
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "okhttp_cache"), OKHTTP_CACHE_BYTES))
            .addInterceptor(TokenAuthenticator(tokenProvider))
            .addInterceptor(MultiBaseUrlInterceptor(baseUrls, accountBaseUrls))

        // Future migration: use native HTTP cache once the API emits Cache-Control/ETag consistently.
        responseCache?.let { builder.addInterceptor(LegacyApiResponseCacheInterceptor(it)) }

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
        }
        return builder.build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun primaryBaseUrl(): String = normalizedBaseUrl(defaultBaseUrls().firstOrNull())

    private fun defaultBaseUrls(): List<String> = buildList {
        add(BuildConfig.QUANT_API_BASE_URL)
        add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
    }.withDebugLocalFallback()

    private fun defaultAccountBaseUrls(): List<String> = buildList {
        add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
        add(BuildConfig.QUANT_API_BASE_URL)
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

    private fun normalizedBaseUrl(raw: String?): String {
        val fallback = BuildConfig.QUANT_API_BASE_URL.ifBlank { EMULATOR_LOCAL_BASE_URL }
        val base = raw?.takeIf { it.isNotBlank() } ?: fallback
        return if (base.endsWith("/")) base else "$base/"
    }
}
