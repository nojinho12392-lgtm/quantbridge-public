package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val MARKET_INDICATORS_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class MarketIndicatorsViewModel @Inject constructor(
    private val repository: MarketIndicatorsRepository
) : ViewModel() {
    var marketIndices by mutableStateOf<List<MarketIndexQuote>>(emptyList())
        private set
    var marketIndicators by mutableStateOf<List<MarketIndicatorQuote>>(emptyList())
        private set
    var marketIndicatorHistory by mutableStateOf<Map<String, List<MarketIndicatorPoint>>>(emptyMap())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var source by mutableStateOf<String?>(null)
        private set
    var updatedAt by mutableStateOf<String?>(null)
        private set

    private val automaticRefreshStamps = mutableMapOf<String, Long>()

    fun refreshMarketIndicators(
        refresh: Boolean = false,
        category: String = "index_fx",
        automatic: Boolean = false
    ) {
        val normalizedCategory = category.ifBlank { "index_fx" }.lowercase(Locale.US)
        if (automatic && !shouldRunAutomaticRefresh("market:$normalizedCategory", 120_000L)) return
        if (loading) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                val current = loadIndicatorQuotes(normalizedCategory, refresh)
                marketIndicators = mergeMarketIndicatorQuotes(marketIndicators, current, normalizedCategory)
                syncTopMarketIndicesFromIndicators(normalizedCategory)
                if (current.isNotEmpty()) {
                    loadIndicatorHistory(current, refresh)
                }
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = marketIndicatorFailureSummary(exc)
            } finally {
                loading = false
            }
        }
    }

    fun ensureCategoryLoaded(category: String = "index_fx") {
        val normalizedCategory = category.ifBlank { "index_fx" }.lowercase(Locale.US)
        if (loading) return
        if (normalizedCategory == "all") {
            if (marketIndicators.isNotEmpty()) return
        } else if (marketIndicators.any { it.category.equals(normalizedCategory, ignoreCase = true) }) {
            return
        }
        refreshMarketIndicators(category = normalizedCategory)
    }

    private suspend fun loadIndicatorQuotes(
        category: String,
        refresh: Boolean
    ): List<MarketIndicatorQuote> {
        val result = runCatching {
            withTimeout(MARKET_INDICATORS_REQUEST_TIMEOUT_MS) {
                repository.fetchMarketIndicators(category = category, refresh = refresh)
            }
        }.onFailure { it.throwIfCancellation() }

        result.onSuccess { response ->
            source = response.source
            updatedAt = response.updatedAt
            if (response.items.isNotEmpty()) return response.items
        }

        val fallback = withTimeout(MARKET_INDICATORS_REQUEST_TIMEOUT_MS) {
            repository.fetchMarketIndices(refresh = refresh)
        }
        marketIndices = fallback.indices
        source = fallback.source
        updatedAt = fallback.updatedAt
        if (fallback.indices.isNotEmpty()) {
            return fallback.indices.map { it.toIndicatorQuote() }
        }

        result.exceptionOrNull()?.let { throw it }
        return emptyList()
    }

    private suspend fun loadIndicatorHistory(
        quotes: List<MarketIndicatorQuote>,
        refresh: Boolean
    ) {
        runCatching {
            withTimeout(MARKET_INDICATORS_REQUEST_TIMEOUT_MS) {
                repository.fetchMarketIndicatorHistory(
                    refresh = refresh,
                    symbols = quotes.map { it.symbol }
                )
            }
        }.onSuccess { result ->
            marketIndicatorHistory = stableMarketIndicatorHistory(
                previous = marketIndicatorHistory,
                incoming = result.series,
                quotes = quotes
            )
            source = result.source ?: source
            updatedAt = result.updatedAt ?: updatedAt
        }.onFailure { exc ->
            exc.throwIfCancellation()
            if (marketIndicators.isEmpty()) error = marketIndicatorFailureSummary(exc)
        }
    }

    private fun syncTopMarketIndicesFromIndicators(category: String) {
        if (category != "all" && category != "index_fx") return
        val next = legacyMarketIndicesFromIndicators(marketIndicators)
        if (next.isNotEmpty()) marketIndices = next
    }

    private fun mergeMarketIndicatorQuotes(
        existing: List<MarketIndicatorQuote>,
        incoming: List<MarketIndicatorQuote>,
        category: String
    ): List<MarketIndicatorQuote> {
        if (category == "all") return incoming
        val seen = mutableSetOf<String>()
        return (existing.filter { !it.category.equals(category, ignoreCase = true) } + incoming)
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

    private fun legacyMarketIndicesFromIndicators(items: List<MarketIndicatorQuote>): List<MarketIndexQuote> {
        val bySymbol = items.associateBy { normalizedTicker(it.symbol) }
        return listOf("^GSPC", "^IXIC", "^KS11", "^KQ11").mapNotNull { symbol ->
            bySymbol[normalizedTicker(symbol)]
                ?.takeIf { it.changePct != null }
                ?.toMarketIndexQuote()
        }
    }

    private fun shouldRunAutomaticRefresh(key: String, minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = automaticRefreshStamps[key] ?: 0L
        if (now - last < minIntervalMs) return false
        automaticRefreshStamps[key] = now
        return true
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun marketIndicatorFailureSummary(error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "시장 지표를 불러오는 시간이 길어지고 있습니다. 마지막 성공 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "시장 지표를 불러오지 못했습니다."
        }
    }
}
