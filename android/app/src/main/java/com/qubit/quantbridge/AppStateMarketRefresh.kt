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

internal suspend fun QuantAppState.refreshEtfs(force: Boolean = false, automatic: Boolean = false) {
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


internal suspend fun QuantAppState.refreshSectorThemes(
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


internal suspend fun QuantAppState.sectorThemeDetail(theme: SectorTheme, force: Boolean = false): SectorTheme {
    return withTimeout(APP_REQUEST_TIMEOUT_MS) {
        sectorThemesRepository.fetchSectorThemeDetail(
            label = theme.label,
            market = theme.market,
            refresh = force
        )
    } ?: theme
}


internal suspend fun QuantAppState.searchEtfs(query: String): List<EtfInsight> {
    val clean = query.trim()
    if (clean.isBlank()) return emptyList()
    return withTimeout(APP_REQUEST_TIMEOUT_MS) {
        api.fetchEtfs(limit = 80, query = clean)
    }
}


internal suspend fun QuantAppState.refreshWatchPriceMetrics(force: Boolean = false, automatic: Boolean = false) {
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


internal fun QuantAppState.watchPriceMetric(ticker: String): StockPriceMetric? {
    return watchPriceMatchKeys(ticker).firstNotNullOfOrNull { watchPriceMetrics[it] }
}


internal suspend fun QuantAppState.refreshMarketIndicators(refresh: Boolean = false, category: String = "index_fx") {
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


internal fun QuantAppState.syncTopMarketIndicesFromIndicators(category: String) {
    val normalizedCategory = category.lowercase(Locale.US)
    if (normalizedCategory != "all" && normalizedCategory != "index_fx") return
    val next = legacyMarketIndicesFromIndicators(marketIndicators)
    if (next.isNotEmpty()) {
        marketIndices = next
    }
}
