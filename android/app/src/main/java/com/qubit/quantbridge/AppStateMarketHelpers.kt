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

internal fun mergeMarketIndicatorQuotes(
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

internal val LEGACY_MARKET_INDEX_ORDER = listOf("^GSPC", "^IXIC", "^KS11", "^KQ11")

internal fun legacyMarketIndicesFromIndicators(items: List<MarketIndicatorQuote>): List<MarketIndexQuote> {
    val bySymbol = items.associateBy { normalizedTicker(it.symbol) }
    return LEGACY_MARKET_INDEX_ORDER.mapNotNull { symbol ->
        bySymbol[normalizedTicker(symbol)]
            ?.takeIf { it.changePct != null }
            ?.toMarketIndexQuote()
    }
}

internal fun domesticIndicatorSymbolsNeedingRefresh(series: List<MarketIndicatorSeries>): List<String> {
    return series
        .filter { it.symbol.uppercase(Locale.US) in DOMESTIC_INTRADAY_INDICATOR_SYMBOLS }
        .filter(::isSparseDomesticIndicatorHistory)
        .map { it.symbol }
}

internal fun isSparseDomesticIndicatorHistory(series: MarketIndicatorSeries): Boolean {
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

internal fun mergeMarketIndicatorSeries(
    existing: List<MarketIndicatorSeries>,
    refreshed: List<MarketIndicatorSeries>
): List<MarketIndicatorSeries> {
    val refreshedBySymbol = refreshed.associateBy { it.symbol }
    val existingSymbols = existing.map { it.symbol }.toSet()
    return existing.map { refreshedBySymbol[it.symbol] ?: it } +
        refreshed.filter { it.symbol !in existingSymbols }
}
