package com.example.myapplication.network

import android.content.Context
import android.os.Build
import com.example.myapplication.ApiResponseCache
import com.example.myapplication.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

object HttpClientFactory {
    private const val OKHTTP_CACHE_BYTES = 50L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 10L
    private const val CALL_TIMEOUT_SECONDS = 15L

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
        val emulatorLocal = "http://10.0.2.2:8000"
        val isDebugEmulator = BuildConfig.DEBUG && isRunningOnAndroidEmulator()
        if (isDebugEmulator) add(emulatorLocal)
        add(BuildConfig.QUANT_API_BASE_URL)
        if (BuildConfig.DEBUG && !isDebugEmulator) add(emulatorLocal)
        add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
    }.filter { it.isNotBlank() }.distinct()

    private fun defaultAccountBaseUrls(): List<String> = buildList {
        val emulatorLocal = "http://10.0.2.2:8000"
        val isDebugEmulator = BuildConfig.DEBUG && isRunningOnAndroidEmulator()
        if (isDebugEmulator) add(emulatorLocal)
        add(BuildConfig.QUANT_API_FALLBACK_BASE_URL)
        add(BuildConfig.QUANT_API_BASE_URL)
        if (BuildConfig.DEBUG && !isDebugEmulator) add(emulatorLocal)
    }.filter { it.isNotBlank() }.distinct()

    private fun normalizedBaseUrl(raw: String?): String {
        val fallback = BuildConfig.QUANT_API_BASE_URL.ifBlank { "http://10.0.2.2:8000" }
        val base = raw?.takeIf { it.isNotBlank() } ?: fallback
        return if (base.endsWith("/")) base else "$base/"
    }

    private fun isRunningOnAndroidEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        val hardware = Build.HARDWARE.lowercase(Locale.US)
        return fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("sdk") ||
            model.contains("emulator") ||
            product.contains("sdk") ||
            hardware.contains("ranchu") ||
            hardware.contains("goldfish")
    }
}
