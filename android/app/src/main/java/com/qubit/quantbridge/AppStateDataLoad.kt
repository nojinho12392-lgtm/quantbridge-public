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

internal suspend fun QuantAppState.refreshPortfolio(market: Market, automatic: Boolean = false) {
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


internal suspend fun QuantAppState.refreshSmallCap(automatic: Boolean = false) {
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


internal suspend fun QuantAppState.refreshPulse() {
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


internal suspend fun QuantAppState.ensureEarningsCalendarLoaded() {
    if ((!earningsCalendarNeedsRefresh(earningsCalendar)) || pulseLoading) return
    refreshEarningsCalendarOnly()
}


internal suspend fun QuantAppState.refreshEarningsCalendarOnly() {
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


internal suspend fun QuantAppState.refreshNews(query: String = "", market: String = "ALL") {
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


internal suspend fun QuantAppState.ensureNewsLoaded(market: String = "ALL") {
    if (newsLoading) return
    if (newsItems.isNotEmpty() && newsError == null) return
    refreshNews("", market)
}


internal suspend fun QuantAppState.refreshExplore(query: String = "") {
    loadExplore("기업", query, force = true)
}


internal suspend fun QuantAppState.searchCompanies(query: String) {
    loadExplore("기업", query, force = true)
}


internal suspend fun QuantAppState.resolveHoldingCandidate(holding: DetailHolding): SearchStock? {
    val query = holding.name.ifBlank { holding.ticker }.trim()
    if (query.isBlank()) return null
    return runCatching { api.searchUniverse(query, limit = 8) }
        .rethrowCancellation()
        .getOrNull()
        ?.bestHoldingMatch(holding)
}


internal fun QuantAppState.isExploreLoading(mode: String): Boolean = mode in loadingExploreModes


internal fun QuantAppState.exploreErrorFor(mode: String): String? = exploreErrors[mode]


internal suspend fun QuantAppState.loadExplore(mode: String, query: String = "", force: Boolean = false) {
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


internal fun QuantAppState.hasExploreData(mode: String): Boolean {
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


internal fun QuantAppState.partialFailureMessage(failures: List<String>): String {
    return "일부 데이터 갱신이 지연되어 마지막 정상 데이터를 표시 중입니다."
}


internal fun QuantAppState.fullFailureMessage(failures: List<String>): String {
    val summary = failures.distinct().take(2).joinToString(" · ")
    return if (summary.isBlank()) {
        "데이터를 불러오지 못했습니다. 잠시 후 다시 시도해주세요."
    } else {
        "데이터를 불러오지 못했습니다 · $summary"
    }
}


internal fun QuantAppState.loadFailureSummary(label: String, error: Throwable): String {
    return "$label ${userFacingLoadFailure(label, error).removePrefix("$label ")}"
}


internal fun QuantAppState.userFacingLoadFailure(label: String, error: Throwable): String {
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


internal suspend fun QuantAppState.refreshStrategyReports() {
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


internal fun QuantAppState.resolveEarningsNames(items: List<EarningsStock>, market: Market): List<EarningsStock> {
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


internal fun QuantAppState.knownKrCompanyNames(): Map<String, String> {
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
