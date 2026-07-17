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

internal const val APP_REQUEST_TIMEOUT_MS = 12_000L
internal val DOMESTIC_INTRADAY_INDICATOR_SYMBOLS = setOf("^KS11", "^KQ11")


internal fun List<SearchStock>.bestHoldingMatch(holding: DetailHolding): SearchStock? {
    val targetTicker = normalizedHoldingText(fallbackHoldingTicker(holding))
    val targetCode = krCode(holding.ticker)
    val targetName = normalizedHoldingText(holding.name)
    return firstOrNull { normalizedHoldingText(it.ticker) == targetTicker }
        ?: firstOrNull { targetCode.isNotBlank() && krCode(it.ticker) == targetCode }
        ?: firstOrNull { normalizedHoldingText(it.name) == targetName }
        ?: firstOrNull()
}

internal fun normalizedHoldingText(value: String): String {
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

internal fun watchPriceMarket(item: WatchlistItem): String {
    val market = item.market.uppercase(Locale.US)
    return when {
        market == "KR" || item.currency == "KRW" -> "KR"
        else -> "US"
    }
}

internal fun watchPriceLookupTickers(ticker: String, market: String): List<String> {
    val normalized = normalizedTicker(ticker)
    val code = krCode(normalized)
    return if (market.uppercase(Locale.US) == "KR" && code.isNotBlank()) {
        listOf("$code.KS", "$code.KQ", code)
    } else {
        listOf(normalized)
    }
}

internal fun watchPriceMatchKeys(ticker: String): Set<String> {
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

internal fun stringArrayJson(values: List<String>): JSONArray {
    val array = JSONArray()
    values.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { array.put(it) }
    return array
}

internal fun earningsCalendarNeedsRefresh(items: List<EarningsCalendarItem>): Boolean {
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
