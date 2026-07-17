package com.qubit.quantbridge.network

import com.qubit.quantbridge.BlindFinancialQuizResponse
import com.qubit.quantbridge.generated.models.QBAuthResponse
import com.qubit.quantbridge.generated.models.QBBacktestResponse
import com.qubit.quantbridge.generated.models.QBComparisonRecommendationsResponse
import com.qubit.quantbridge.generated.models.QBCurrentUserResponse
import com.qubit.quantbridge.generated.models.QBDriftAlertsResponse
import com.qubit.quantbridge.generated.models.QBEarningsCalendarResponse
import com.qubit.quantbridge.generated.models.QBEarningsResponse
import com.qubit.quantbridge.generated.models.QBEmptyResponse
import com.qubit.quantbridge.generated.models.QBEtfDetailResponse
import com.qubit.quantbridge.generated.models.QBEtfListResponse
import com.qubit.quantbridge.generated.models.QBIndustryRankingResponse
import com.qubit.quantbridge.generated.models.QBMLBlendResponse
import com.qubit.quantbridge.generated.models.QBMarketIndicatorHistoryResponse
import com.qubit.quantbridge.generated.models.QBMarketIndicatorsResponse
import com.qubit.quantbridge.generated.models.QBMarketIndicesResponse
import com.qubit.quantbridge.generated.models.QBNewsIssuesResponse
import com.qubit.quantbridge.generated.models.QBOpsHealthResponse
import com.qubit.quantbridge.generated.models.QBOrderFlowResponse
import com.qubit.quantbridge.generated.models.QBPortfolioPricesResponse
import com.qubit.quantbridge.generated.models.QBPortfolioResponse
import com.qubit.quantbridge.generated.models.QBPortfolioRiskResponse
import com.qubit.quantbridge.generated.models.QBRebalanceResponse
import com.qubit.quantbridge.generated.models.QBResearchQualityResponse
import com.qubit.quantbridge.generated.models.QBScoredResponse
import com.qubit.quantbridge.generated.models.QBSearchUniverseResponse
import com.qubit.quantbridge.generated.models.QBSectorThemeDetailResponse
import com.qubit.quantbridge.generated.models.QBSectorThemesResponse
import com.qubit.quantbridge.generated.models.QBShadowAttributionResponse
import com.qubit.quantbridge.generated.models.QBSignalEventsResponse
import com.qubit.quantbridge.generated.models.QBSmallCapResponse
import com.qubit.quantbridge.generated.models.QBStockDetailResponse
import com.qubit.quantbridge.generated.models.QBWatchlistResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null
)

@Serializable
data class WatchlistRequest(
    val ticker: String,
    val name: String,
    val market: String,
    val currency: String,
    val note: String = ""
)

@Suppress("TooManyFunctions")
interface QuantApiService {
    @GET("training/blind-financial-quiz")
    suspend fun getBlindFinancialQuiz(
        @Query("quiz_id") quizId: String = "",
        @Query("market") market: String = "US",
        @Query("refresh") refresh: Boolean = false
    ): BlindFinancialQuizResponse

    @GET("portfolio/{market}")
    suspend fun getPortfolio(@Path("market") market: String): QBPortfolioResponse

    @GET("smallcap/{market}")
    suspend fun getSmallCap(@Path("market") market: String): QBSmallCapResponse

    @GET("search/universe")
    suspend fun searchUniverse(
        @Query("q") query: String,
        @Query("limit") limit: Int = 100
    ): QBSearchUniverseResponse

    @GET("scored/{market}")
    suspend fun getScored(
        @Path("market") market: String,
        @Query("limit") limit: Int = 300
    ): QBScoredResponse

    @GET("earnings/{market}")
    suspend fun getEarnings(@Path("market") market: String): QBEarningsResponse

    @GET("calendar/earnings")
    suspend fun getEarningsCalendar(
        @Query("market") market: String = "ALL",
        @Query("days") days: Int = 180,
        @Query("limit") limit: Int = 2000,
        @Query("refresh") refresh: Boolean = false
    ): QBEarningsCalendarResponse

    @GET("signals/events")
    suspend fun getSignalEvents(
        @Query("market") market: String = "ALL",
        @Query("tickers") tickers: String = "",
        @Query("kinds") kinds: String = "",
        @Query("limit") limit: Int = 120
    ): QBSignalEventsResponse

    @GET("comparison/recommendations/{ticker}")
    suspend fun getComparisonRecommendations(
        @Path("ticker") ticker: String,
        @Query("market") market: String = "ALL",
        @Query("limit") limit: Int = 12
    ): QBComparisonRecommendationsResponse

    @GET("portfolio/{market}/prices")
    suspend fun getStockPriceMetrics(
        @Path("market") market: String,
        @Query("tickers") tickers: String,
        @Query("limit") limit: Int,
        @Query("refresh") refresh: Boolean = false
    ): QBPortfolioPricesResponse

    @GET("sectors/themes")
    suspend fun getSectorThemes(
        @Query("market") market: String = "ALL",
        @Query("limit") limit: Int = 36,
        @Query("members") members: Int = 12,
        @Query("refresh") refresh: Boolean = false
    ): QBSectorThemesResponse

    @GET("sectors/themes/detail")
    suspend fun getSectorThemeDetail(
        @Query("label") label: String,
        @Query("market") market: String = "ALL",
        @Query("members") members: Int = 80,
        @Query("refresh") refresh: Boolean = false
    ): QBSectorThemeDetailResponse

    @GET("stock/{ticker}")
    suspend fun getStock(
        @Path("ticker") ticker: String,
        @Query("period") period: String = "6mo",
        @Query("refresh") refresh: Boolean = false,
        @Query("profile") profile: Boolean = false,
        @Query("detail_schema") detailSchema: String = "valuation_v1"
    ): QBStockDetailResponse

    @GET("stock/{ticker}")
    suspend fun getStockRaw(
        @Path("ticker") ticker: String,
        @Query("period") period: String = "6mo",
        @Query("refresh") refresh: Boolean = false,
        @Query("profile") profile: Boolean = false,
        @Query("detail_schema") detailSchema: String = "valuation_v1"
    ): JsonObject

    @GET("market/indices")
    suspend fun getMarketIndices(@Query("refresh") refresh: Boolean = false): QBMarketIndicesResponse

    @GET("market/indicators")
    suspend fun getMarketIndicators(
        @Query("category") category: String = "index_fx",
        @Query("refresh") refresh: Boolean = false
    ): QBMarketIndicatorsResponse

    @GET("market/indicators/history")
    suspend fun getMarketIndicatorHistory(
        @Query("symbols") symbols: String = "",
        @Query("period") period: String = "1d",
        @Query("interval") interval: String = "15m",
        @Query("refresh") refresh: Boolean = false
    ): QBMarketIndicatorHistoryResponse

    @GET("news/issues")
    suspend fun getNews(
        @Query("q") query: String = "",
        @Query("market") market: String = "ALL",
        @Query("limit") limit: Int = 40
    ): QBNewsIssuesResponse

    @GET("etfs")
    suspend fun getEtfInsights(
        @Query("market") market: String = "ALL",
        @Query("category") category: String = "ALL",
        @Query("q") query: String = "",
        @Query("limit") limit: Int = 500,
        @Query("refresh") refresh: Boolean = false,
        @Query("schema") schema: String = "etf-daily-v2"
    ): QBEtfListResponse

    @GET("etfs")
    suspend fun getEtfInsightsRaw(
        @Query("market") market: String = "ALL",
        @Query("category") category: String = "ALL",
        @Query("q") query: String = "",
        @Query("limit") limit: Int = 500,
        @Query("refresh") refresh: Boolean = false,
        @Query("schema") schema: String = "etf-daily-v2"
    ): JsonObject

    @GET("etfs/{ticker}")
    suspend fun getEtfDetail(
        @Path("ticker") ticker: String,
        @Query("refresh") refresh: Boolean = false,
        @Query("schema") schema: String = "etf-daily-v2"
    ): QBEtfDetailResponse

    @GET("research/factor-quality")
    suspend fun getResearchQuality(): QBResearchQualityResponse

    @GET("research/factor-quality")
    suspend fun getResearchQualityRaw(): JsonObject

    @GET("research/ml-blend")
    suspend fun getMLBlendReport(): QBMLBlendResponse

    @GET("research/ml-blend")
    suspend fun getMLBlendReportRaw(): JsonObject

    @GET("research/policy-adjusted-ranking")
    suspend fun getPolicyAdjustedRankingRaw(
        @Query("market") market: String,
        @Query("limit") limit: Int = 12
    ): JsonObject

    @GET("ops/health")
    suspend fun getOpsHealth(): QBOpsHealthResponse

    @GET("backtest/{market}")
    suspend fun getBacktests(@Path("market") market: String): QBBacktestResponse

    @GET("backtest/{market}")
    suspend fun getBacktestsRaw(@Path("market") market: String): JsonObject

    @GET("smallcap-backtest/{market}")
    suspend fun getSmallCapBacktests(@Path("market") market: String): QBBacktestResponse

    @GET("smallcap-backtest/{market}")
    suspend fun getSmallCapBacktestsRaw(@Path("market") market: String): JsonObject

    @GET("risk/drift")
    suspend fun getDriftAlerts(): QBDriftAlertsResponse

    @GET("risk/drift")
    suspend fun getDriftAlertsRaw(): JsonObject

    @GET("risk/industry")
    suspend fun getIndustryRanking(@Query("limit") limit: Int = 30): QBIndustryRankingResponse

    @GET("risk/industry")
    suspend fun getIndustryRankingRaw(@Query("limit") limit: Int = 30): JsonObject

    @GET("risk/order-flow")
    suspend fun getOrderFlow(@Query("limit") limit: Int = 30): QBOrderFlowResponse

    @GET("risk/order-flow")
    suspend fun getOrderFlowRaw(@Query("limit") limit: Int = 30): JsonObject

    @GET("risk/portfolio/{market}")
    suspend fun getPortfolioRisk(
        @Path("market") market: String,
        @Query("limit") limit: Int = 30
    ): QBPortfolioRiskResponse

    @GET("risk/portfolio/{market}")
    suspend fun getPortfolioRiskRaw(
        @Path("market") market: String,
        @Query("limit") limit: Int = 30
    ): JsonObject

    @GET("risk/portfolio/{market}")
    suspend fun getRiskHoldings(
        @Path("market") market: String,
        @Query("limit") limit: Int = 30
    ): QBPortfolioRiskResponse

    @GET("risk/portfolio/{market}")
    suspend fun getRiskSectors(
        @Path("market") market: String,
        @Query("limit") limit: Int = 30
    ): QBPortfolioRiskResponse

    @GET("rebalance/{market}")
    suspend fun getRebalanceOrders(
        @Path("market") market: String,
        @Query("limit") limit: Int = 50
    ): QBRebalanceResponse

    @GET("rebalance/{market}")
    suspend fun getRebalanceOrdersRaw(
        @Path("market") market: String,
        @Query("limit") limit: Int = 50
    ): JsonObject

    @GET("shadow/attribution")
    suspend fun getShadowAttribution(
        @Query("market") market: String = "ALL",
        @Query("limit") limit: Int = 50
    ): QBShadowAttributionResponse

    @GET("shadow/attribution")
    suspend fun getShadowAttributionRaw(
        @Query("market") market: String = "ALL",
        @Query("limit") limit: Int = 50
    ): JsonObject

    @GET("macro")
    suspend fun getMacro(): JsonObject

    @POST("auth/login")
    suspend fun login(@Body body: AuthRequest): QBAuthResponse

    @POST("auth/signup")
    suspend fun signup(@Body body: AuthRequest): QBAuthResponse

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String? = null): QBEmptyResponse

    @GET("auth/me")
    suspend fun getMe(@Header("Authorization") authorization: String? = null): QBCurrentUserResponse

    @DELETE("auth/me")
    suspend fun deleteMe(@Header("Authorization") authorization: String? = null): QBEmptyResponse

    @GET("me/watchlist")
    suspend fun getWatchlist(@Header("Authorization") authorization: String? = null): QBWatchlistResponse

    @POST("me/watchlist")
    suspend fun saveWatchlist(
        @Body body: WatchlistRequest,
        @Header("Authorization") authorization: String? = null
    ): QBEmptyResponse

    @DELETE("me/watchlist/{ticker}")
    suspend fun deleteWatchlist(
        @Path("ticker") ticker: String,
        @Header("Authorization") authorization: String? = null
    ): QBEmptyResponse
}
