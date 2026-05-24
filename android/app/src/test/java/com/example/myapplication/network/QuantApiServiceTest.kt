package com.example.myapplication.network

import com.example.myapplication.ApiResponseCache
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json

class QuantApiServiceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun quantApiServiceMapsCoreEndpointPaths() = runBlocking {
        val capture = CapturingInterceptor()
        val service = serviceWith(capture)

        service.getPortfolio("us")
        assertEquals("/portfolio/us", capture.lastPath)

        service.getStockPriceMetrics("US", "AAPL,MSFT", 2, refresh = true)
        assertEquals("/portfolio/US/prices?tickers=AAPL%2CMSFT&limit=2&refresh=true", capture.lastPathAndQuery)

        service.searchUniverse("apple", 12)
        assertEquals("/search/universe?q=apple&limit=12", capture.lastPathAndQuery)

        service.getSectorThemeDetail("SMR", "US", members = 30, refresh = true)
        assertEquals("/sectors/themes/detail?label=SMR&market=US&members=30&refresh=true", capture.lastPathAndQuery)

        service.getStock("AAPL", period = "1y", refresh = true, profile = true)
        assertEquals(
            "/stock/AAPL?period=1y&refresh=true&profile=true&detail_schema=valuation_v1",
            capture.lastPathAndQuery
        )

        service.getMarketIndicatorHistory(symbols = "^GSPC,^KS11")
        assertEquals(
            "/market/indicators/history?symbols=%5EGSPC%2C%5EKS11&period=1d&interval=15m&refresh=false",
            capture.lastPathAndQuery
        )

        service.getEtfInsights(query = "xly", refresh = true)
        assertEquals(
            "/etfs?market=ALL&category=ALL&q=xly&limit=500&refresh=true&schema=etf-daily-v2",
            capture.lastPathAndQuery
        )

        service.getWatchlist("Bearer abc")
        assertEquals("/me/watchlist", capture.lastPath)
        assertEquals("Bearer abc", capture.lastRequest?.header("Authorization"))
    }

    @Test
    fun quantApiServiceSerializesAuthAndWatchlistBodies() = runBlocking {
        val capture = CapturingInterceptor()
        val service = serviceWith(capture)

        service.signup(AuthRequest(email = "a@b.com", password = "password123", displayName = "Demo User"))
        assertEquals("/auth/signup", capture.lastPath)
        assertTrue(capture.lastBody.contains("\"display_name\":\"Demo User\""))

        service.saveWatchlist(
            WatchlistRequest(
                ticker = "AAPL",
                name = "애플",
                market = "US",
                currency = "USD",
                note = "Watchlist"
            )
        )
        assertEquals("/me/watchlist", capture.lastPath)
        assertTrue(capture.lastBody.contains("\"ticker\":\"AAPL\""))
    }

    @Test
    fun quantApiServiceDecodesGeneratedNumericDtos() = runBlocking {
        val capture = CapturingInterceptor(
            responseOverride = """
                {
                  "market": "US",
                  "items": [
                    {
                      "label": "SMR",
                      "avg_change_pct": 2.35,
                      "members": [
                        {
                          "Ticker": "CEG",
                          "Name": "콘스텔레이션 에너지",
                          "Daily_Change_Pct": 1.2
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
        val service = serviceWith(capture)

        val response = service.getSectorThemes(market = "US")

        assertEquals("US", response.market)
        assertEquals(2.35, response.items?.firstOrNull()?.avgChangePct ?: 0.0, 0.001)
        assertEquals(1.2, response.items?.firstOrNull()?.members?.firstOrNull()?.dailyChangePct ?: 0.0, 0.001)
    }

    @Test
    fun tokenAuthenticatorAddsBearerTokenOnlyForAccountScopedRoutes() {
        val capture = CapturingInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(TokenAuthenticator(AuthTokenProvider { "token-1" }))
            .addInterceptor(capture)
            .build()

        client.newCall(Request.Builder().url("https://api.test/portfolio/us").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals(null, capture.lastRequest?.header("Authorization"))
        }
        val response = client.newCall(Request.Builder().url("https://api.test/auth/me").build()).execute()

        assertEquals(200, response.code)
        assertEquals("Bearer token-1", response.request.header("Authorization"))
    }

    @Test
    fun multiBaseUrlInterceptorFallsBackAfterRetryableStatus() {
        val terminal = object : Interceptor {
            val urls = mutableListOf<String>()

            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                urls += request.url.toString()
                val code = if (urls.size == 1) 500 else 200
                return jsonResponse(request, code, """{"ok":true}""")
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(
                MultiBaseUrlInterceptor(
                    baseUrls = listOf("https://primary.test", "https://fallback.test"),
                    accountBaseUrls = listOf("https://account.test")
                )
            )
            .addInterceptor(terminal)
            .build()

        val response = client.newCall(Request.Builder().url("https://primary.test/portfolio/us").build()).execute()

        assertEquals(200, response.code)
        assertEquals(
            listOf("https://primary.test/portfolio/us", "https://fallback.test/portfolio/us"),
            terminal.urls
        )
    }

    @Test
    fun legacyApiResponseCacheReturnsStaleDataAfterNetworkFailure() {
        val cache = ApiResponseCache(tempFolder.newFolder("api_cache"))
        val firstClient = OkHttpClient.Builder()
            .addInterceptor(LegacyApiResponseCacheInterceptor(cache))
            .addInterceptor { chain -> jsonResponse(chain.request(), 200, """{"stocks":[{"Ticker":"AAPL"}]}""") }
            .build()

        firstClient.newCall(Request.Builder().url("https://api.test/portfolio/us").build()).execute().use {
            assertEquals(200, it.code)
        }

        val offlineClient = OkHttpClient.Builder()
            .addInterceptor(LegacyApiResponseCacheInterceptor(cache))
            .addInterceptor { throw IOException("offline") }
            .build()

        offlineClient.newCall(Request.Builder().url("https://api.test/portfolio/us").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("OK (disk cache)", it.message)
        }
    }

    @Test
    fun legacyApiResponseCacheReadsExistingLegacyKeys() {
        val cache = ApiResponseCache(tempFolder.newFolder("legacy_api_cache"))
        cache.write("https://api.test|portfolio/us", """{"stocks":[{"Ticker":"MSFT"}]}""")
        val client = OkHttpClient.Builder()
            .addInterceptor(LegacyApiResponseCacheInterceptor(cache))
            .addInterceptor { throw IOException("network should not be used") }
            .build()

        client.newCall(Request.Builder().url("https://api.test/portfolio/us").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("OK (disk cache)", it.message)
        }
    }

    private fun serviceWith(interceptor: CapturingInterceptor): QuantApiService {
        return Retrofit.Builder()
            .baseUrl("https://api.test/")
            .client(OkHttpClient.Builder().addInterceptor(interceptor).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QuantApiService::class.java)
    }

    private class CapturingInterceptor(
        private val responseOverride: String? = null
    ) : Interceptor {
        var lastRequest: Request? = null
        var lastBody: String = ""
        val lastPath: String
            get() = lastRequest?.url?.encodedPath.orEmpty()
        val lastPathAndQuery: String
            get() {
                val url = lastRequest?.url ?: return ""
                return url.encodedPath + url.encodedQuery?.let { "?$it" }.orEmpty()
            }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            lastRequest = request
            lastBody = request.body?.let { body ->
                Buffer().also { body.writeTo(it) }.readUtf8()
            }.orEmpty()
            return jsonResponse(request, 200, responseOverride ?: responseBodyFor(request))
        }

        private fun responseBodyFor(request: Request): String {
            val path = request.url.encodedPath
            return when {
                path.endsWith("/prices") -> """{"market":"US","metrics":[]}"""
                path == "/sectors/themes/detail" -> """{"market":"US","item":{"label":"SMR","members":[]}}"""
                path == "/auth/login" || path == "/auth/signup" -> """
                    {
                      "access_token": "token",
                      "token_type": "bearer",
                      "user": {"id": "user-1", "email": "a@b.com", "display_name": "Demo User"}
                    }
                """.trimIndent()
                else -> """{"ok":true}"""
            }
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

private fun jsonResponse(request: Request, code: Int, raw: String): Response {
    return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(raw.toResponseBody("application/json".toMediaType()))
        .build()
}
