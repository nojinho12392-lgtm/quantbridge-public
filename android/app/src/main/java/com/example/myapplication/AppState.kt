package com.example.myapplication

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.myapplication.network.AuthTokenProvider
import com.example.myapplication.network.HttpClientFactory
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

private const val APP_REQUEST_TIMEOUT_MS = 12_000L
private val DOMESTIC_INTRADAY_INDICATOR_SYMBOLS = setOf("^KS11", "^KQ11")

private fun Throwable.throwIfCancellation() {
    if (this is CancellationException) throw this
}

private fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { it.throwIfCancellation() }

private fun List<SearchStock>.bestHoldingMatch(holding: DetailHolding): SearchStock? {
    val targetTicker = normalizedHoldingText(fallbackHoldingTicker(holding))
    val targetCode = krCode(holding.ticker)
    val targetName = normalizedHoldingText(holding.name)
    return firstOrNull { normalizedHoldingText(it.ticker) == targetTicker }
        ?: firstOrNull { targetCode.isNotBlank() && krCode(it.ticker) == targetCode }
        ?: firstOrNull { normalizedHoldingText(it.name) == targetName }
        ?: firstOrNull()
}

private fun normalizedHoldingText(value: String): String {
    return value.trim().lowercase(Locale.getDefault())
}

sealed class WatchlistSyncStatus {
    data object Idle : WatchlistSyncStatus()
    data class Syncing(val count: Int) : WatchlistSyncStatus()
    data class Synced(val count: Int) : WatchlistSyncStatus()
    data class Failed(val message: String) : WatchlistSyncStatus()

    val messageText: String?
        get() = when (this) {
            Idle -> null
            is Syncing -> if (count > 0) "동기화 중 ${count}건" else "동기화 중"
            is Synced -> "${count}개 동기화 완료"
            is Failed -> message
        }
}

data class PendingWatchlistOperation(
    val action: String,
    val ticker: String,
    val item: WatchlistItem?
)

fun normalizedTicker(ticker: String): String {
    return ticker.trim().uppercase(Locale.US)
}

fun normalizeWatchlistItem(item: WatchlistItem): WatchlistItem {
    val ticker = normalizedTicker(item.ticker)
    val rawName = item.name.trim()
    val currency = item.currency.ifBlank { marketCurrency(ticker, item.market) }
    val market = item.market.ifBlank { if (currency == "KRW") "KR" else "US" }
    return item.copy(
        ticker = ticker,
        name = if (rawName.isBlank()) displayCompanyName(ticker, ticker) else displayCompanyName(rawName, ticker),
        market = market,
        currency = currency,
        note = item.note.ifBlank { "Watchlist" },
        tags = item.tags.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        memo = item.memo.trim(),
        alertOptions = item.alertOptions.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    )
}

private fun watchPriceMarket(item: WatchlistItem): String {
    val market = item.market.uppercase(Locale.US)
    return when {
        market == "KR" || item.currency == "KRW" -> "KR"
        else -> "US"
    }
}

private fun watchPriceLookupTickers(ticker: String, market: String): List<String> {
    val normalized = normalizedTicker(ticker)
    val code = krCode(normalized)
    return if (market.uppercase(Locale.US) == "KR" && code.isNotBlank()) {
        listOf("$code.KS", "$code.KQ", code)
    } else {
        listOf(normalized)
    }
}

private fun watchPriceMatchKeys(ticker: String): Set<String> {
    val normalized = normalizedTicker(ticker)
    if (normalized.isBlank()) return emptySet()
    val keys = linkedSetOf(normalized)
    val code = krCode(normalized)
    if (code.isNotBlank()) {
        keys += code
        keys += "$code.KS"
        keys += "$code.KQ"
    }
    return keys
}

fun mergeWatchlists(local: List<WatchlistItem>, remote: List<WatchlistItem>): List<WatchlistItem> {
    val merged = linkedMapOf<String, WatchlistItem>()
    remote.map(::normalizeWatchlistItem).forEach { merged[it.ticker] = it }
    local.map(::normalizeWatchlistItem).forEach { merged[it.ticker] = it }
    return merged.values.toList()
}

fun watchlistItemJson(item: WatchlistItem): JSONObject {
    return JSONObject()
        .put("ticker", item.ticker)
        .put("name", item.name)
        .put("market", item.market)
        .put("currency", item.currency)
        .put("note", item.note)
        .put("added_at", item.addedAt)
        .put("tags", stringArrayJson(item.tags))
        .put("memo", item.memo)
        .put("alert_options", stringArrayJson(item.alertOptions))
}

private fun stringArrayJson(values: List<String>): JSONArray {
    val array = JSONArray()
    values.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { array.put(it) }
    return array
}

class QuantAppState(context: Context) {
    private val userPreferences = UserPreferencesRepository(context.applicationContext)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenStore = SecureTokenStore(context)
    private val apiResponseCache = ApiResponseCache(context.applicationContext.cacheDir)
    private val api = QuantApi(responseCache = apiResponseCache)
    private val sectorThemesRepository = SectorThemesRepository(
        api = HttpClientFactory.create(
            context = context.applicationContext,
            tokenProvider = AuthTokenProvider { tokenStore.loadToken() },
            responseCache = apiResponseCache
        ),
        context = context.applicationContext
    )
    private val pendingWatchlistOps = mutableListOf<PendingWatchlistOperation>()
    private val automaticRefreshStamps = mutableMapOf<String, Long>()

    var selectedTab by mutableStateOf(AppTab.Home)
    var analysisSection by mutableStateOf("기업")
    var selectedSectorTheme by mutableStateOf<SectorTheme?>(null)
    var homeLoading by mutableStateOf(false)
    var portfolioLoading by mutableStateOf(false)
    var smallCapLoading by mutableStateOf(false)
    var pulseLoading by mutableStateOf(false)
    var accountLoading by mutableStateOf(false)
    private val initialToken = tokenStore.loadToken()
    private val initialUser = if (initialToken != null) tokenStore.loadUser() else null
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
    private var watchPriceMetricsKey = ""

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

    private suspend fun refreshAppShellData() {
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

    private fun hasDashboardData(): Boolean {
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

    private fun shouldRunAutomaticRefresh(key: String, minIntervalMs: Long = 60_000L): Boolean {
        val now = System.currentTimeMillis()
        val last = automaticRefreshStamps[key] ?: 0L
        if (now - last < minIntervalMs) return false
        automaticRefreshStamps[key] = now
        return true
    }

    suspend fun refreshEtfs(force: Boolean = false, automatic: Boolean = false) {
        if (automatic && !shouldRunAutomaticRefresh("etfs", minIntervalMs = 120_000L)) return
        if (etfInsightsLoading) return
        if (!force && etfInsightsSource != "fallback" && etfInsights.isNotEmpty()) return
        etfInsightsLoading = true
        try {
            val result = withTimeout(APP_REQUEST_TIMEOUT_MS) { api.fetchEtfsResult() }
            if (result.items.isNotEmpty()) {
                etfInsights = result.items
                etfInsightsSource = result.source ?: "api"
                etfInsightsUpdatedAt = result.updatedAt
                etfInsightsError = null
            }
        } catch (exc: Exception) {
            exc.throwIfCancellation()
            etfInsightsError = loadFailureSummary("ETF", exc)
            if (etfInsights.isEmpty()) {
                etfInsights = etfInsightUniverse
                etfInsightsSource = "fallback"
            }
        } finally {
            etfInsightsLoading = false
        }
    }

    suspend fun refreshSectorThemes(
        market: String = "ALL",
        force: Boolean = false,
        reloadExisting: Boolean = false,
        automatic: Boolean = false
    ) {
        val safeMarket = market.uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
        if (automatic && !shouldRunAutomaticRefresh("sectors:$safeMarket", minIntervalMs = 120_000L)) return
        if (sectorThemesLoading) return
        if (!force && !reloadExisting && sectorThemesMarket == safeMarket && sectorThemes.isNotEmpty()) return
        sectorThemesLoading = true
        try {
            val result = withTimeout(APP_REQUEST_TIMEOUT_MS) {
                sectorThemesRepository.fetchSectorThemesResult(market = safeMarket, refresh = force)
            }
            sectorThemes = result.items
            sectorThemesMarket = result.market
            sectorThemesSource = result.source
            sectorThemesGeneratedAt = result.generatedAt
            sectorThemesError = null
        } catch (exc: Exception) {
            exc.throwIfCancellation()
            sectorThemesError = loadFailureSummary("섹터", exc)
        } finally {
            sectorThemesLoading = false
        }
    }

    suspend fun sectorThemeDetail(theme: SectorTheme, force: Boolean = false): SectorTheme {
        return withTimeout(APP_REQUEST_TIMEOUT_MS) {
            sectorThemesRepository.fetchSectorThemeDetail(
                label = theme.label,
                market = theme.market,
                refresh = force
            )
        } ?: theme
    }

    suspend fun searchEtfs(query: String): List<EtfInsight> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        return withTimeout(APP_REQUEST_TIMEOUT_MS) {
            api.fetchEtfs(limit = 80, query = clean)
        }
    }

    suspend fun refreshWatchPriceMetrics(force: Boolean = false, automatic: Boolean = false) {
        val companies = watchlist
            .filter { !it.isMarketIndicatorWatchItem() }
            .map(::normalizeWatchlistItem)
        val key = companies
            .map { "${it.market}:${it.ticker}" }
            .sorted()
            .joinToString("|")
        if (automatic && !shouldRunAutomaticRefresh("watch-prices:$key", minIntervalMs = 60_000L)) return
        if (!force && !automatic && key == watchPriceMetricsKey && watchPriceMetrics.isNotEmpty()) return
        watchPriceMetricsKey = key
        if (companies.isEmpty()) {
            watchPriceMetrics = emptyMap()
            return
        }

        val byMarket = companies.groupBy { watchPriceMarket(it) }
        val next = linkedMapOf<String, StockPriceMetric>()
        val refreshPrices = force || automatic
        byMarket.forEach { (market, items) ->
            val requested = items.flatMap { watchPriceLookupTickers(it.ticker, market) }.distinct()
            val metrics = withTimeout(APP_REQUEST_TIMEOUT_MS) {
                api.fetchStockPriceMetrics(market, requested, refresh = refreshPrices)
            }
            metrics.forEach { metric ->
                watchPriceMatchKeys(metric.ticker).forEach { next[it] = metric }
            }
            items.forEach { item ->
                val metric = watchPriceMatchKeys(item.ticker).firstNotNullOfOrNull { next[it] }
                if (metric != null) {
                    watchPriceMatchKeys(item.ticker).forEach { next[it] = metric }
                }
            }
        }
        watchPriceMetrics = next
    }

    fun watchPriceMetric(ticker: String): StockPriceMetric? {
        return watchPriceMatchKeys(ticker).firstNotNullOfOrNull { watchPriceMetrics[it] }
    }

    suspend fun refreshMarketIndicators(refresh: Boolean = false, category: String = "index_fx") {
        marketIndicatorLoading = true
        marketIndicatorError = null
        val normalizedCategory = category.ifBlank { "index_fx" }.lowercase(Locale.US)
        try {
            val current = runCatching { api.fetchMarketIndicators(refresh = refresh, category = normalizedCategory) }
                .rethrowCancellation()
                .getOrElse { currentError ->
                    marketIndices
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.toIndicatorQuote() }
	                        ?: api.fetchMarketIndices().map { it.toIndicatorQuote() }.also {
	                            marketIndicatorError = userFacingLoadFailure("주요 지수", currentError)
	                        }
	                }
            marketIndicators = mergeMarketIndicatorQuotes(marketIndicators, current, normalizedCategory)
            syncTopMarketIndicesFromIndicators(normalizedCategory)
            if (normalizedCategory == "all" || normalizedCategory == "index_fx") {
                withTimeoutOrNull(1_200) {
                    api.withDomesticIndicatorOverrides(current)
                }?.let { updated ->
                    marketIndicators = mergeMarketIndicatorQuotes(marketIndicators, updated, normalizedCategory)
                    syncTopMarketIndicesFromIndicators(normalizedCategory)
                }
            }
            if (current.isEmpty()) return
            val history = runCatching {
                api.fetchMarketIndicatorHistory(refresh, current.map { it.symbol })
            }.rethrowCancellation()
            history.onSuccess { initialSeries ->
                var series = initialSeries
                if (!refresh) {
                    val staleDomesticSymbols = domesticIndicatorSymbolsNeedingRefresh(series)
                    if (staleDomesticSymbols.isNotEmpty()) {
                        runCatching {
                            api.fetchMarketIndicatorHistory(refresh = true, symbols = staleDomesticSymbols)
                        }.rethrowCancellation().onSuccess { refreshed ->
                            series = mergeMarketIndicatorSeries(series, refreshed)
                        }
                    }
                }
                marketIndicatorHistory = stableMarketIndicatorHistory(
                    previous = marketIndicatorHistory,
                    incoming = series,
                    quotes = current
                )
	            }.onFailure { historyError ->
	                if (marketIndicators.isEmpty()) {
	                    marketIndicatorError = userFacingLoadFailure("주요 지표", historyError)
	                }
	            }
	        } catch (e: Exception) {
                e.throwIfCancellation()
	            marketIndicatorError = userFacingLoadFailure("주요 지표", e)
	        } finally {
            marketIndicatorLoading = false
        }
    }

    private fun syncTopMarketIndicesFromIndicators(category: String) {
        val normalizedCategory = category.lowercase(Locale.US)
        if (normalizedCategory != "all" && normalizedCategory != "index_fx") return
        val next = legacyMarketIndicesFromIndicators(marketIndicators)
        if (next.isNotEmpty()) {
            marketIndices = next
        }
    }

private fun mergeMarketIndicatorQuotes(
    existing: List<MarketIndicatorQuote>,
    incoming: List<MarketIndicatorQuote>,
    category: String
): List<MarketIndicatorQuote> {
    val normalizedCategory = category.lowercase(Locale.US)
    if (normalizedCategory == "all") return incoming
    val seen = mutableSetOf<String>()
    return (existing.filter { it.category.lowercase(Locale.US) != normalizedCategory } + incoming)
        .filter { item ->
            val key = normalizedTicker(item.symbol)
            if (key in seen) {
                false
            } else {
                seen += key
                true
            }
        }
}

private val LEGACY_MARKET_INDEX_ORDER = listOf("^GSPC", "^IXIC", "^KS11", "^KQ11")

private fun legacyMarketIndicesFromIndicators(items: List<MarketIndicatorQuote>): List<MarketIndexQuote> {
    val bySymbol = items.associateBy { normalizedTicker(it.symbol) }
    return LEGACY_MARKET_INDEX_ORDER.mapNotNull { symbol ->
        bySymbol[normalizedTicker(symbol)]
            ?.takeIf { it.changePct != null }
            ?.toMarketIndexQuote()
    }
}

private fun domesticIndicatorSymbolsNeedingRefresh(series: List<MarketIndicatorSeries>): List<String> {
    return series
        .filter { it.symbol.uppercase(Locale.US) in DOMESTIC_INTRADAY_INDICATOR_SYMBOLS }
        .filter(::isSparseDomesticIndicatorHistory)
        .map { it.symbol }
}

private fun isSparseDomesticIndicatorHistory(series: MarketIndicatorSeries): Boolean {
    val clean = series.points
        .filter { it.close.isFinite() }
        .sortedBy { parseMarketInstant(it.timestamp) ?: return@sortedBy java.time.Instant.EPOCH }
    if (clean.size < 8) return true

    val distinctCloses = clean
        .map { Math.round(it.close * 1_000_000.0) }
        .toSet()
    if (distinctCloses.size < 3) return true

    val timestamps = clean
        .mapNotNull { parseMarketInstant(it.timestamp) }
        .sorted()
    if (timestamps.size >= 2) {
        val spanMillis = timestamps.last().toEpochMilli() - timestamps.first().toEpochMilli()
        if (spanMillis < 30 * 60 * 1000L) return true
    }

    val seoulZone = ZoneId.of("Asia/Seoul")
    val marketOpen = LocalTime.of(9, 0)
    val marketClose = LocalTime.of(15, 30)
    val inSessionCount = timestamps.count { instant ->
        val localTime = instant.atZone(seoulZone).toLocalTime()
        !localTime.isBefore(marketOpen) && !localTime.isAfter(marketClose)
    }
    if (inSessionCount < minOf(3, clean.size)) return true

    return false
}

private fun mergeMarketIndicatorSeries(
    existing: List<MarketIndicatorSeries>,
    refreshed: List<MarketIndicatorSeries>
): List<MarketIndicatorSeries> {
    val refreshedBySymbol = refreshed.associateBy { it.symbol }
    val existingSymbols = existing.map { it.symbol }.toSet()
    return existing.map { refreshedBySymbol[it.symbol] ?: it } +
        refreshed.filter { it.symbol !in existingSymbols }
}

    suspend fun refreshPortfolio(market: Market, automatic: Boolean = false) {
        if (automatic && !shouldRunAutomaticRefresh("portfolio:${market.title}", minIntervalMs = 120_000L)) return
        portfolioLoading = true
        error = null
        try {
            val response = api.fetchPortfolio(market.title.lowercase(Locale.US))
            if (market == Market.US) {
                usMeta = response.first
                usPortfolio = response.second
            } else {
                krMeta = response.first
                krPortfolio = response.second
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            error = e.message
        } finally {
            portfolioLoading = false
        }
    }

    suspend fun refreshSmallCap(automatic: Boolean = false) {
        if (automatic && !shouldRunAutomaticRefresh("smallcap", minIntervalMs = 120_000L)) return
        smallCapLoading = true
        error = null
        try {
            val failures = mutableListOf<String>()
            runCatching { api.fetchSmallCap("us") }
                .rethrowCancellation()
                .onSuccess { usSmallCap = it }
                .onFailure { failures += loadFailureSummary("US SmallCap", it) }
            runCatching { api.fetchSmallCap("kr") }
                .rethrowCancellation()
                .onSuccess { krSmallCap = it }
                .onFailure { failures += loadFailureSummary("KR SmallCap", it) }
            if (failures.isNotEmpty()) {
                error = if (usSmallCap.isEmpty() && krSmallCap.isEmpty()) {
                    fullFailureMessage(failures)
                } else {
                    partialFailureMessage(failures)
                }
            }
        } finally {
            smallCapLoading = false
        }
    }

    suspend fun refreshPulse() {
        pulseLoading = true
        error = null
        try {
            val failures = mutableListOf<String>()
            runCatching { api.fetchMacro() }
                .rethrowCancellation()
                .onSuccess { macro = it }
                .onFailure { failures += loadFailureSummary("매크로", it) }
            runCatching { api.fetchEarnings("us") }
                .rethrowCancellation()
                .onSuccess { usEarnings = resolveEarningsNames(it, Market.US) }
                .onFailure { failures += loadFailureSummary("US 실적", it) }
            runCatching { api.fetchEarnings("kr") }
                .rethrowCancellation()
                .onSuccess { krEarnings = resolveEarningsNames(it, Market.KR) }
                .onFailure { failures += loadFailureSummary("KR 실적", it) }
            runCatching { api.fetchEarningsCalendar(refresh = false) }
                .rethrowCancellation()
                .onSuccess { earningsCalendar = it }
                .onFailure { failures += loadFailureSummary("실적 캘린더", it) }
            if (failures.isNotEmpty()) {
                error = if (macro.isEmpty() && usEarnings.isEmpty() && krEarnings.isEmpty() && earningsCalendar.isEmpty()) {
                    fullFailureMessage(failures)
                } else {
                    partialFailureMessage(failures)
                }
            }
        } finally {
            pulseLoading = false
        }
    }

    suspend fun ensureEarningsCalendarLoaded() {
        if ((!earningsCalendarNeedsRefresh(earningsCalendar)) || pulseLoading) return
        refreshEarningsCalendarOnly()
    }

    private suspend fun refreshEarningsCalendarOnly() {
        pulseLoading = true
        error = null
        try {
            runCatching { api.fetchEarningsCalendar(refresh = true) }
                .rethrowCancellation()
                .onSuccess {
                    earningsCalendar = it
                    if (it.isNotEmpty()) error = null
                }
                .onFailure {
                    error = if (macro.isEmpty() && usEarnings.isEmpty() && krEarnings.isEmpty()) {
                        loadFailureSummary("실적 캘린더", it)
                    } else {
                        partialFailureMessage(listOf(loadFailureSummary("실적 캘린더", it)))
                    }
                }
        } finally {
            pulseLoading = false
        }
    }

    suspend fun refreshNews(query: String = "", market: String = "ALL") {
        newsLoading = true
        newsError = null
        try {
            val requestQuery = query.trim().ifBlank { defaultNewsQuery(market) }
            val response = api.fetchNews(requestQuery, market)
            newsConfigured = response.first
            newsItems = response.second.filterNewsForMarket(market)
        } catch (e: Exception) {
            e.throwIfCancellation()
            newsError = e.message ?: "뉴스를 불러오지 못했습니다"
        } finally {
            newsLoading = false
        }
    }

    suspend fun ensureNewsLoaded(market: String = "ALL") {
        if (newsLoading) return
        if (newsItems.isNotEmpty() && newsError == null) return
        refreshNews("", market)
    }

    suspend fun refreshExplore(query: String = "") {
        loadExplore("기업", query, force = true)
    }

    suspend fun searchCompanies(query: String) {
        loadExplore("기업", query, force = true)
    }

    suspend fun resolveHoldingCandidate(holding: DetailHolding): SearchStock? {
        val query = holding.name.ifBlank { holding.ticker }.trim()
        if (query.isBlank()) return null
        return runCatching { api.searchUniverse(query, limit = 8) }
            .rethrowCancellation()
            .getOrNull()
            ?.bestHoldingMatch(holding)
    }

    fun isExploreLoading(mode: String): Boolean = mode in loadingExploreModes

    fun exploreErrorFor(mode: String): String? = exploreErrors[mode]

    suspend fun loadExplore(mode: String, query: String = "", force: Boolean = false) {
        if (!force && mode in loadedExploreModes) return
        loadingExploreModes = loadingExploreModes + mode
        exploreLoading = loadingExploreModes.isNotEmpty()
        exploreErrors = exploreErrors - mode
        exploreError = null
        val failures = mutableListOf<String>()

        suspend fun <T> attempt(label: String, block: suspend () -> T): T? {
            return runCatching { block() }.rethrowCancellation().getOrElse { error ->
                failures += loadFailureSummary(label, error)
                null
            }
        }

        try {
            when (mode) {
                "기업" -> {
                    attempt("기업 검색") { api.searchUniverse(query) }?.let { searchResults = it }
                }
                "스코어" -> {
                    attempt("US 스코어") { api.fetchScored("us") }?.let { usScored = it }
                    attempt("KR 스코어") { api.fetchScored("kr") }?.let { krScored = it }
                }
                "전략" -> {
                    attempt("백테스트") { api.fetchAllBacktests() }?.let { backtests = it }
                    attempt("드리프트") { api.fetchDriftItems() }?.let { driftItems = it }
                    attempt("업종 랭킹") { api.fetchIndustryItems() }?.let { industryItems = it }
                    attempt("오더플로우") { api.fetchOrderFlowItems() }?.let { orderFlowItems = it }
                    attempt("전략 리포트") { refreshStrategyReports() }
                }
                "진단" -> {
                    attempt("리서치 품질") { api.fetchResearchQuality() }?.let { researchQuality = it }
                    attempt("ML Overlay") { api.fetchMLBlendReport() }?.let { mlBlendReport = it }
                    attempt("운영 상태") { api.fetchOpsHealth() }?.let { opsHealth = it }
                }
            }
            if (failures.isEmpty() || hasExploreData(mode)) {
                loadedExploreModes = loadedExploreModes + mode
            } else {
                loadedExploreModes = loadedExploreModes - mode
            }
            if (failures.isNotEmpty()) {
                val message = partialFailureMessage(failures)
                exploreErrors = exploreErrors + (mode to message)
                exploreError = message
            }
        } finally {
            loadingExploreModes = loadingExploreModes - mode
            exploreLoading = loadingExploreModes.isNotEmpty()
        }
    }

    private fun hasExploreData(mode: String): Boolean {
        return when (mode) {
            "기업" -> searchResults.isNotEmpty()
            "스코어" -> usScored.isNotEmpty() || krScored.isNotEmpty()
            "전략" -> backtests.isNotEmpty() ||
                driftItems.isNotEmpty() ||
                riskHoldings.isNotEmpty() ||
                rebalanceOrders.isNotEmpty() ||
                shadowItems.isNotEmpty()
            "진단" -> researchQuality != null || mlBlendReport != null || opsHealth != null
            else -> false
        }
    }

    private fun partialFailureMessage(failures: List<String>): String {
        return "일부 데이터 갱신이 지연되어 마지막 정상 데이터를 표시 중입니다."
    }

    private fun fullFailureMessage(failures: List<String>): String {
        val summary = failures.distinct().take(2).joinToString(" · ")
        return if (summary.isBlank()) {
            "데이터를 불러오지 못했습니다. 잠시 후 다시 시도해주세요."
        } else {
            "데이터를 불러오지 못했습니다 · $summary"
        }
    }

    private fun loadFailureSummary(label: String, error: Throwable): String {
        return "$label ${userFacingLoadFailure(label, error).removePrefix("$label ")}"
    }

    private fun userFacingLoadFailure(label: String, error: Throwable): String {
        val message = error.message.orEmpty()
        val timedOut = error is TimeoutCancellationException ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true)
        return if (timedOut) {
            "$label 응답 지연"
        } else {
            "$label 로드 실패"
        }
    }

    private suspend fun refreshStrategyReports() {
        val usRisk = runCatching { api.fetchPortfolioRisk("us") }
            .rethrowCancellation()
            .getOrDefault(PortfolioRiskReport(emptyList(), emptyList()))
        val krRisk = runCatching { api.fetchPortfolioRisk("kr") }
            .rethrowCancellation()
            .getOrDefault(PortfolioRiskReport(emptyList(), emptyList()))
        riskHoldings = usRisk.holdings + krRisk.holdings
        riskSectors = usRisk.sectors + krRisk.sectors
        rebalanceOrders = listOf("us", "kr").flatMap { market ->
            runCatching { api.fetchRebalanceOrders(market) }
                .rethrowCancellation()
                .getOrDefault(emptyList())
        }
        val shadow = runCatching { api.fetchShadowAttribution() }
            .rethrowCancellation()
            .getOrDefault(ShadowAttributionReport(emptyList(), emptyList()))
        shadowSummaries = shadow.summaries
        shadowItems = shadow.items
    }

    private fun resolveEarningsNames(items: List<EarningsStock>, market: Market): List<EarningsStock> {
        val knownNames = knownKrCompanyNames()
        return items.map { stock ->
            val localized = localizedCompanyName(stock.ticker, stock.name, market.title)
            if (market != Market.KR || localized != stock.name) {
                stock.copy(name = localized)
            } else if (isMissingKrName(stock.name, stock.ticker)) {
                val code = krCode(stock.ticker)
                val resolved = knownNames[normalizedTicker(stock.ticker)]
                    ?: knownNames[code]
                    ?: resolvedKrCompanyName(stock.ticker, stock.name)
                stock.copy(name = resolved)
            } else {
                stock
            }
        }
    }

    private fun knownKrCompanyNames(): Map<String, String> {
        val names = linkedMapOf<String, String>()
        fun put(ticker: String, name: String) {
            if (isMissingKrName(name, ticker)) return
            val normalized = normalizedTicker(ticker)
            names[normalized] = name
            krCode(normalized).takeIf { it.isNotBlank() }?.let { names[it] = name }
        }
        krPortfolio.forEach { put(it.ticker, it.name) }
        krSmallCap.forEach { put(it.ticker, it.name) }
        krScored.forEach { put(it.ticker, it.name) }
        searchResults
            .filter { it.market.equals("KR", ignoreCase = true) || isKoreanTicker(it.ticker, it.market) }
            .forEach { put(it.ticker, it.name) }
        return names
    }

    suspend fun login(email: String, password: String, displayName: String?, signup: Boolean): Boolean {
        accountLoading = true
        accountSessionRestoring = false
        error = null
        return try {
            val response = api.authenticate(email, password, displayName, signup)
            token = response.first
            user = response.second
            tokenStore.saveToken(response.first)
            tokenStore.saveUser(response.second)
            connectWatchlist()
            true
        } catch (e: Exception) {
            e.throwIfCancellation()
            error = e.message
            false
        } finally {
            accountLoading = false
        }
    }

    suspend fun adoptAccountSession(session: AccountSession) {
        accountLoading = true
        accountSessionRestoring = false
        error = null
        try {
            token = session.token
            user = session.user
            tokenStore.saveToken(session.token)
            tokenStore.saveUser(session.user)
            connectWatchlist()
        } finally {
            accountLoading = false
        }
    }

    fun clearAccountSession(clearWatchlist: Boolean = false) {
        clearSessionState(clearWatchlist = clearWatchlist)
    }

    suspend fun logout() {
        token?.let { runCatching { api.logout(it) }.rethrowCancellation() }
        clearSessionState(clearWatchlist = false)
    }

    suspend fun deleteAccount(): Boolean {
        val currentToken = token
        if (currentToken == null) {
            error = "로그인이 필요합니다"
            return false
        }

        accountLoading = true
        error = null
        return try {
            api.deleteAccount(currentToken)
            clearSessionState(clearWatchlist = true)
            true
        } catch (e: Exception) {
            e.throwIfCancellation()
            error = e.message ?: "계정 삭제에 실패했습니다"
            false
        } finally {
            accountLoading = false
        }
    }

    private fun clearSessionState(clearWatchlist: Boolean = false) {
        disconnectAccountSession()
        if (clearWatchlist) {
            clearLocalAccountState()
        }
    }

    private fun disconnectAccountSession() {
        token = null
        user = null
        accountSessionRestoring = false
        tokenStore.clearSession()
        watchlistSyncStatus = if (pendingWatchlistOps.isEmpty()) {
            WatchlistSyncStatus.Idle
        } else {
            WatchlistSyncStatus.Failed("로그인 후 동기화 대기 ${pendingWatchlistOps.size}건")
        }
    }

    private fun clearLocalAccountState() {
        watchlist.clear()
        pendingWatchlistOps.clear()
        saveLocalWatchlist()
        savePendingWatchlistOps()
        watchlistSyncStatus = WatchlistSyncStatus.Idle
    }

    suspend fun toggleWatch(item: WatchlistItem) {
        val normalized = normalizeWatchlistItem(item)
        val existing = watchlist.indexOfFirst { normalizedTicker(it.ticker) == normalized.ticker }
        val currentToken = token
        if (existing >= 0) {
            val removed = watchlist.removeAt(existing)
            saveLocalWatchlist()
            if (currentToken != null && !deleteRemoteWatchlist(removed.ticker, currentToken)) {
                enqueuePendingWatchlist(PendingWatchlistOperation("delete", normalizedTicker(removed.ticker), null))
            }
        } else {
            watchlist.add(0, normalized)
            saveLocalWatchlist()
            if (currentToken != null && !saveRemoteWatchlist(normalized, currentToken)) {
                enqueuePendingWatchlist(PendingWatchlistOperation("save", normalized.ticker, normalized))
            }
        }
    }

    fun isWatched(ticker: String): Boolean {
        val key = normalizedTicker(ticker)
        return watchlist.any { normalizedTicker(it.ticker) == key }
    }

    fun watchlistItem(ticker: String): WatchlistItem? {
        val key = normalizedTicker(ticker)
        return watchlist.firstOrNull { normalizedTicker(it.ticker) == key }
    }

    fun investmentDecision(ticker: String): InvestmentDecisionRecord? {
        return investmentDecisions[normalizedTicker(ticker)]
    }

    fun saveInvestmentDecision(record: InvestmentDecisionRecord) {
        val clean = record.normalized
        if (clean.ticker.isBlank()) return
        val now = Instant.now().toString()
        val next = clean.copy(
            createdAt = clean.createdAt.ifBlank { investmentDecision(clean.ticker)?.createdAt.orEmpty().ifBlank { now } },
            updatedAt = now
        )
        investmentDecisions = investmentDecisions + (next.ticker to next)
        saveInvestmentDecisions()
    }

    fun deleteInvestmentDecision(ticker: String) {
        val key = normalizedTicker(ticker)
        if (key.isBlank() || key !in investmentDecisions) return
        investmentDecisions = investmentDecisions - key
        saveInvestmentDecisions()
    }

    suspend fun updateWatchMetadata(
        ticker: String,
        tags: List<String>,
        memo: String,
        alertOptions: List<String>
    ) {
        val key = normalizedTicker(ticker)
        val index = watchlist.indexOfFirst { normalizedTicker(it.ticker) == key }
        if (index < 0) return
        val next = normalizeWatchlistItem(
            watchlist[index].copy(
                tags = tags,
                memo = memo,
                alertOptions = alertOptions
            )
        )
        watchlist[index] = next
        saveLocalWatchlist()
        token?.let { currentToken ->
            if (!saveRemoteWatchlist(next, currentToken)) {
                enqueuePendingWatchlist(PendingWatchlistOperation("save", next.ticker, next))
            }
        }
    }

    suspend fun retryWatchlistSync() {
        connectWatchlist()
    }

    private suspend fun saveRemoteWatchlist(item: WatchlistItem, currentToken: String): Boolean {
        return runCatching {
            api.saveWatchlist(item, currentToken)
        }.onFailure { error ->
            error.throwIfCancellation()
            markWatchlistSyncFailure(error.message ?: "관심 종목 저장 실패")
        }.isSuccess
    }

    private suspend fun deleteRemoteWatchlist(ticker: String, currentToken: String): Boolean {
        return runCatching {
            api.deleteWatchlist(normalizedTicker(ticker), currentToken)
        }.onFailure { error ->
            error.throwIfCancellation()
            markWatchlistSyncFailure(error.message ?: "관심 종목 삭제 실패")
        }.isSuccess
    }

    private fun enqueuePendingWatchlist(operation: PendingWatchlistOperation) {
        pendingWatchlistOps.removeAll { it.ticker == operation.ticker }
        pendingWatchlistOps.add(operation)
        savePendingWatchlistOps()
        watchlistSyncStatus = WatchlistSyncStatus.Failed("동기화 대기 ${pendingWatchlistOps.size}건")
    }

    private fun markWatchlistSyncFailure(message: String) {
        watchlistSyncStatus = WatchlistSyncStatus.Failed(message)
    }

    private suspend fun syncPendingWatchlist(currentToken: String) {
        if (pendingWatchlistOps.isEmpty()) return
        watchlistSyncStatus = WatchlistSyncStatus.Syncing(pendingWatchlistOps.size)
        val remaining = mutableListOf<PendingWatchlistOperation>()
        pendingWatchlistOps.toList().forEach { operation ->
            val success = when (operation.action) {
                "delete" -> deleteRemoteWatchlist(operation.ticker, currentToken)
                else -> operation.item?.let { saveRemoteWatchlist(it, currentToken) } ?: true
            }
            if (!success) remaining += operation
        }
        pendingWatchlistOps.clear()
        pendingWatchlistOps.addAll(remaining)
        savePendingWatchlistOps()
    }

    private fun replaceWatchlist(items: List<WatchlistItem>, persist: Boolean = true) {
        watchlist.clear()
        watchlist.addAll(items.map(::normalizeWatchlistItem))
        if (persist) saveLocalWatchlist()
    }

    private suspend fun loadPendingWatchlistOps() {
        val decoded = userPreferences.pendingWatchlistOpsSnapshot()
        pendingWatchlistOps.clear()
        pendingWatchlistOps.addAll(decoded)
        if (pendingWatchlistOps.isNotEmpty()) {
            watchlistSyncStatus = WatchlistSyncStatus.Failed("동기화 대기 ${pendingWatchlistOps.size}건")
        }
    }

    private fun savePendingWatchlistOps() {
        persistUserPreferences {
            userPreferences.setPendingWatchlistOps(pendingWatchlistOps.toList())
        }
    }

    private suspend fun restoreSession(): Boolean {
        val currentToken = token
        if (currentToken == null) {
            accountSessionRestoring = false
            return false
        }
        if (user == null) {
            accountSessionRestoring = true
            user = tokenStore.loadUser()
        }
        return runCatching {
            user = api.me(currentToken)
            user?.let { tokenStore.saveUser(it) }
        }.fold(
            onSuccess = {
                accountSessionRestoring = false
                false
            },
            onFailure = {
                accountSessionRestoring = false
                token = null
                user = null
                tokenStore.clearSession()
                true
            }
        )
    }

    private suspend fun connectWatchlist() {
        val currentToken = token ?: return
        val localItems = watchlist.toList().map(::normalizeWatchlistItem)
        watchlistSyncStatus = WatchlistSyncStatus.Syncing(localItems.size + pendingWatchlistOps.size)
        syncPendingWatchlist(currentToken)
        for (item in localItems) {
            if (!saveRemoteWatchlist(item, currentToken)) {
                enqueuePendingWatchlist(PendingWatchlistOperation("save", item.ticker, item))
            }
        }
        val remoteSynced = runCatching {
            val remoteItems = api.fetchWatchlist(currentToken)
            replaceWatchlist(mergeWatchlists(localItems, remoteItems))
        }.onFailure { error ->
            error.throwIfCancellation()
            markWatchlistSyncFailure(error.message ?: "관심 종목 동기화 실패")
        }.isSuccess
        watchlistSyncStatus = if (pendingWatchlistOps.isEmpty()) {
            if (remoteSynced) WatchlistSyncStatus.Synced(watchlist.size) else watchlistSyncStatus
        } else {
            WatchlistSyncStatus.Failed("동기화 대기 ${pendingWatchlistOps.size}건")
        }
    }

    private suspend fun loadLocalWatchlist() {
        val decoded = userPreferences.watchlistSnapshot()
        replaceWatchlist(decoded, persist = false)
    }

    private suspend fun loadInvestmentProfile() {
        investmentProfile = userPreferences.investmentProfileSnapshot().normalized
    }

    private suspend fun loadInvestmentDecisions() {
        investmentDecisions = userPreferences.investmentDecisionsSnapshot()
            .associateBy { normalizedTicker(it.ticker) }
    }

    fun updateInvestmentProfile(profile: InvestmentProfile) {
        val clean = profile.normalized
        investmentProfile = clean
        persistUserPreferences {
            userPreferences.setInvestmentProfile(clean)
        }
    }

    private fun saveLocalWatchlist() {
        persistUserPreferences {
            userPreferences.setWatchlist(watchlist.toList().map(::normalizeWatchlistItem))
        }
    }

    private fun saveInvestmentDecisions() {
        persistUserPreferences {
            userPreferences.setInvestmentDecisions(investmentDecisions.values.toList())
        }
    }

    private fun persistUserPreferences(block: suspend () -> Unit) {
        persistenceScope.launch {
            runCatching { block() }
        }
    }

}

private fun earningsCalendarNeedsRefresh(items: List<EarningsCalendarItem>): Boolean {
    if (items.isEmpty()) return true
    if (items.size < 100) return false
    val repeatedBucketCount = items
        .groupBy { it.nextEarningsDate }
        .values
        .mapNotNull { dayItems ->
            val tickers = dayItems
                .map { it.ticker.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            if (tickers.size >= 10) tickers.joinToString("|") else null
        }
        .groupingBy { it }
        .eachCount()
        .values
        .maxOrNull() ?: 0
    return repeatedBucketCount >= 4
}
