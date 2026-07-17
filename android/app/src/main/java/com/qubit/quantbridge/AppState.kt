package com.qubit.quantbridge

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qubit.quantbridge.network.AuthTokenProvider
import com.qubit.quantbridge.network.HttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class QuantAppState(context: Context) {
    internal val userPreferences = UserPreferencesRepository(context.applicationContext)
    internal val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val tokenStore = SecureTokenStore(context)
    internal val apiResponseCache = ApiResponseCache(context.applicationContext.cacheDir)
    internal val api = QuantApi(responseCache = apiResponseCache)
    internal val sectorThemesRepository = SectorThemesRepository(
        api = HttpClientFactory.create(
            context = context.applicationContext,
            tokenProvider = AuthTokenProvider { tokenStore.loadToken() },
            responseCache = apiResponseCache
        ),
        context = context.applicationContext
    )
    internal val pendingWatchlistOps = mutableListOf<PendingWatchlistOperation>()
    internal val automaticRefreshStamps = mutableMapOf<String, Long>()

    var selectedTab by mutableStateOf(AppTab.Home)
    var analysisSection by mutableStateOf("기업")
    var selectedSectorTheme by mutableStateOf<SectorTheme?>(null)
    var homeLoading by mutableStateOf(false)
    var portfolioLoading by mutableStateOf(false)
    var smallCapLoading by mutableStateOf(false)
    var pulseLoading by mutableStateOf(false)
    var accountLoading by mutableStateOf(false)
    internal val initialToken = tokenStore.loadToken()
    internal val initialUser = if (initialToken != null) tokenStore.loadUser() else null
    var initialDashboardRetrying by mutableStateOf(false)
    val loading: Boolean
        get() = homeLoading ||
            portfolioLoading ||
            smallCapLoading ||
            pulseLoading ||
            accountLoading ||
            newsLoading ||
            exploreLoading ||
            sectorThemesLoading ||
            marketIndicatorLoading
    var error by mutableStateOf<String?>(null)
    var token by mutableStateOf(initialToken)
    var user by mutableStateOf<AuthUser?>(initialUser)
    var accountSessionRestoring by mutableStateOf(initialToken != null && initialUser == null)
    var investmentProfile by mutableStateOf(InvestmentProfile())

    var usMeta by mutableStateOf<Map<String, String>>(emptyMap())
    var krMeta by mutableStateOf<Map<String, String>>(emptyMap())
    var macro by mutableStateOf<Map<String, String>>(emptyMap())
    var usPortfolio by mutableStateOf<List<PortfolioStock>>(emptyList())
    var krPortfolio by mutableStateOf<List<PortfolioStock>>(emptyList())
    var usSmallCap by mutableStateOf<List<SmallCapStock>>(emptyList())
    var krSmallCap by mutableStateOf<List<SmallCapStock>>(emptyList())
    var searchResults by mutableStateOf<List<SearchStock>>(emptyList())
    var usScored by mutableStateOf<List<ScoredStock>>(emptyList())
    var krScored by mutableStateOf<List<ScoredStock>>(emptyList())
    var usEarnings by mutableStateOf<List<EarningsStock>>(emptyList())
    var krEarnings by mutableStateOf<List<EarningsStock>>(emptyList())
    var earningsCalendar by mutableStateOf<List<EarningsCalendarItem>>(emptyList())
    var signalEvents by mutableStateOf<List<SignalEvent>>(emptyList())
    var researchQuality by mutableStateOf<ResearchQuality?>(null)
    var mlBlendReport by mutableStateOf<MLBlendReport?>(null)
    var opsHealth by mutableStateOf<OpsHealth?>(null)
    var backtests by mutableStateOf<List<BacktestSummary>>(emptyList())
    var driftItems by mutableStateOf<List<DriftItem>>(emptyList())
    var industryItems by mutableStateOf<List<IndustryItem>>(emptyList())
    var orderFlowItems by mutableStateOf<List<OrderFlowItem>>(emptyList())
    var riskHoldings by mutableStateOf<List<RiskHolding>>(emptyList())
    var riskSectors by mutableStateOf<List<RiskSector>>(emptyList())
    var rebalanceOrders by mutableStateOf<List<RebalanceOrder>>(emptyList())
    var shadowSummaries by mutableStateOf<List<ShadowAttributionSummary>>(emptyList())
    var shadowItems by mutableStateOf<List<ShadowAttributionItem>>(emptyList())
    var newsItems by mutableStateOf<List<NewsItem>>(emptyList())
    var marketIndices by mutableStateOf<List<MarketIndexQuote>>(emptyList())
    var marketIndicators by mutableStateOf<List<MarketIndicatorQuote>>(emptyList())
    var marketIndicatorHistory by mutableStateOf<Map<String, List<MarketIndicatorPoint>>>(emptyMap())
    var etfInsights by mutableStateOf(etfInsightUniverse)
    var etfInsightsLoading by mutableStateOf(false)
    var etfInsightsError by mutableStateOf<String?>(null)
    var etfInsightsSource by mutableStateOf("fallback")
    var etfInsightsUpdatedAt by mutableStateOf<String?>(null)
    var sectorThemes by mutableStateOf<List<SectorTheme>>(emptyList())
    var sectorThemesLoading by mutableStateOf(false)
    var sectorThemesError by mutableStateOf<String?>(null)
    var sectorThemesMarket by mutableStateOf("ALL")
    var sectorThemesSource by mutableStateOf<String?>(null)
    var sectorThemesGeneratedAt by mutableStateOf<String?>(null)
    var marketIndicatorLoading by mutableStateOf(false)
    var marketIndicatorError by mutableStateOf<String?>(null)
    var newsConfigured by mutableStateOf(false)
    var newsLoading by mutableStateOf(false)
    var newsError by mutableStateOf<String?>(null)
    var exploreLoading by mutableStateOf(false)
    var exploreError by mutableStateOf<String?>(null)
    var loadedExploreModes by mutableStateOf<Set<String>>(emptySet())
    var loadingExploreModes by mutableStateOf<Set<String>>(emptySet())
    var exploreErrors by mutableStateOf<Map<String, String>>(emptyMap())
    var selectedDetail by mutableStateOf<DetailRequest?>(null)
    val detailBackStack = mutableStateListOf<DetailRequest>()
    var watchlistSyncStatus by mutableStateOf<WatchlistSyncStatus>(WatchlistSyncStatus.Idle)
    val watchlist = mutableStateListOf<WatchlistItem>()
    var investmentDecisions by mutableStateOf<Map<String, InvestmentDecisionRecord>>(emptyMap())
    var watchPriceMetrics by mutableStateOf<Map<String, StockPriceMetric>>(emptyMap())
    internal var watchPriceMetricsKey = ""

    val pendingWatchlistCount: Int
        get() = pendingWatchlistOps.size

    val dashboardDataReady: Boolean
        get() = hasDashboardData()

    suspend fun bootstrap() {
        loadInvestmentProfile()
        loadInvestmentDecisions()
        loadLocalWatchlist()
        loadPendingWatchlistOps()
        supervisorScope {
            launch {
                refreshAppShellData()
            }
            launch {
                val invalidatedStoredSession = restoreSession()
                if (invalidatedStoredSession) {
                    disconnectAccountSession()
                } else {
                    connectWatchlist()
                }
            }
        }
    }

    internal suspend fun refreshAppShellData() {
        val failures = mutableListOf<String>()

        suspend fun <T> attempt(block: suspend () -> T): Result<T> {
            return runCatching { withTimeout(APP_REQUEST_TIMEOUT_MS) { block() } }.rethrowCancellation()
        }

        fun <T> commit(label: String, result: Result<T>, onSuccess: (T) -> Unit) {
            result
                .onSuccess(onSuccess)
                .onFailure { failures += loadFailureSummary(label, it) }
        }

        coroutineScope {
            launch {
                commit("주요 지수", attempt { api.fetchMarketIndices() }) { marketIndices = it }
            }
            launch {
                runCatching { withTimeout(APP_REQUEST_TIMEOUT_MS) { refreshMarketIndicators(category = "all") } }
                    .rethrowCancellation()
                    .onFailure { failures += loadFailureSummary("주요 지표", it) }
            }
        }

        if (failures.isNotEmpty() && marketIndices.isEmpty() && marketIndicators.isEmpty()) {
            error = fullFailureMessage(failures)
        }
    }

    internal fun hasDashboardData(): Boolean {
        return usPortfolio.isNotEmpty() ||
            krPortfolio.isNotEmpty() ||
            usSmallCap.isNotEmpty() ||
            krSmallCap.isNotEmpty() ||
            usEarnings.isNotEmpty() ||
            krEarnings.isNotEmpty() ||
            earningsCalendar.isNotEmpty() ||
            signalEvents.isNotEmpty() ||
            newsItems.isNotEmpty() ||
            marketIndices.isNotEmpty() ||
            marketIndicators.isNotEmpty()
    }

    suspend fun refreshAll() {
        homeLoading = true
        error = null
        val failures = mutableListOf<String>()

        suspend fun <T> attempt(block: suspend () -> T): Result<T> {
            return runCatching { withTimeout(APP_REQUEST_TIMEOUT_MS) { block() } }.rethrowCancellation()
        }

        fun <T> commit(label: String, result: Result<T>, onSuccess: (T) -> Unit) {
            result
                .onSuccess(onSuccess)
                .onFailure { failures += loadFailureSummary(label, it) }
        }

        try {
            coroutineScope {
                launch {
                    commit("US 분석", attempt { api.fetchPortfolio("us") }) {
                        usMeta = it.first
                        usPortfolio = it.second
                    }
                }
                launch {
                    commit("KR 분석", attempt { api.fetchPortfolio("kr") }) {
                        krMeta = it.first
                        krPortfolio = it.second
                    }
                }
                launch {
                    commit("US SmallCap", attempt { api.fetchSmallCap("us") }) { usSmallCap = it }
                }
                launch {
                    commit("KR SmallCap", attempt { api.fetchSmallCap("kr") }) { krSmallCap = it }
                }
                launch {
                    commit("US Earnings", attempt { api.fetchEarnings("us") }) {
                        usEarnings = resolveEarningsNames(it, Market.US)
                    }
                }
                launch {
                    commit("KR Earnings", attempt { api.fetchEarnings("kr") }) {
                        krEarnings = resolveEarningsNames(it, Market.KR)
                    }
                }
                launch {
                    commit("Earnings Calendar", attempt { api.fetchEarningsCalendar() }) {
                        earningsCalendar = it
                    }
                }
                launch {
                    commit("이벤트", attempt { api.fetchSignalEvents() }) {
                        signalEvents = it
                    }
                }
                launch {
                    commit("주요 지수", attempt { api.fetchMarketIndices() }) { marketIndices = it }
                }
                launch {
                    commit("매크로", attempt { api.fetchMacro() }) { macro = it }
                }
                launch {
                    commit("뉴스", attempt { api.fetchNews(defaultNewsQuery("ALL"), "ALL") }) {
                        newsConfigured = it.first
                        newsItems = it.second.filterNewsForMarket("ALL")
                    }
                }
            }
            runCatching { withTimeout(APP_REQUEST_TIMEOUT_MS) { refreshMarketIndicators(category = "all") } }
                .rethrowCancellation()
                .onFailure { failures += loadFailureSummary("주요 지표", it) }
            if (failures.isNotEmpty()) {
                error = if (hasDashboardData()) null else fullFailureMessage(failures)
            }
        } finally {
            homeLoading = false
        }
    }

    internal fun shouldRunAutomaticRefresh(key: String, minIntervalMs: Long = 60_000L): Boolean {
        val now = System.currentTimeMillis()
        val last = automaticRefreshStamps[key] ?: 0L
        if (now - last < minIntervalMs) return false
        automaticRefreshStamps[key] = now
        return true
    }

}
