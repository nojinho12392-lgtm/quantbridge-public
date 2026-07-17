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

internal suspend fun QuantAppState.toggleWatch(item: WatchlistItem) {
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


internal fun QuantAppState.isWatched(ticker: String): Boolean {
    val key = normalizedTicker(ticker)
    return watchlist.any { normalizedTicker(it.ticker) == key }
}


internal fun QuantAppState.watchlistItem(ticker: String): WatchlistItem? {
    val key = normalizedTicker(ticker)
    return watchlist.firstOrNull { normalizedTicker(it.ticker) == key }
}


internal fun QuantAppState.investmentDecision(ticker: String): InvestmentDecisionRecord? {
    return investmentDecisions[normalizedTicker(ticker)]
}


internal fun QuantAppState.saveInvestmentDecision(record: InvestmentDecisionRecord) {
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


internal fun QuantAppState.deleteInvestmentDecision(ticker: String) {
    val key = normalizedTicker(ticker)
    if (key.isBlank() || key !in investmentDecisions) return
    investmentDecisions = investmentDecisions - key
    saveInvestmentDecisions()
}


internal suspend fun QuantAppState.updateWatchMetadata(
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


internal suspend fun QuantAppState.retryWatchlistSync() {
    connectWatchlist()
}


internal suspend fun QuantAppState.saveRemoteWatchlist(item: WatchlistItem, currentToken: String): Boolean {
    return runCatching {
        api.saveWatchlist(item, currentToken)
    }.onFailure { error ->
        error.throwIfCancellation()
        markWatchlistSyncFailure(error.message ?: "관심 종목 저장 실패")
    }.isSuccess
}


internal suspend fun QuantAppState.deleteRemoteWatchlist(ticker: String, currentToken: String): Boolean {
    return runCatching {
        api.deleteWatchlist(normalizedTicker(ticker), currentToken)
    }.onFailure { error ->
        error.throwIfCancellation()
        markWatchlistSyncFailure(error.message ?: "관심 종목 삭제 실패")
    }.isSuccess
}


internal fun QuantAppState.enqueuePendingWatchlist(operation: PendingWatchlistOperation) {
    pendingWatchlistOps.removeAll { it.ticker == operation.ticker }
    pendingWatchlistOps.add(operation)
    savePendingWatchlistOps()
    watchlistSyncStatus = WatchlistSyncStatus.Failed("동기화 대기 ${pendingWatchlistOps.size}건")
}


internal fun QuantAppState.markWatchlistSyncFailure(message: String) {
    watchlistSyncStatus = WatchlistSyncStatus.Failed(message)
}


internal suspend fun QuantAppState.syncPendingWatchlist(currentToken: String) {
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


internal fun QuantAppState.replaceWatchlist(items: List<WatchlistItem>, persist: Boolean = true) {
    watchlist.clear()
    watchlist.addAll(items.map(::normalizeWatchlistItem))
    if (persist) saveLocalWatchlist()
}


internal suspend fun QuantAppState.loadPendingWatchlistOps() {
    val decoded = userPreferences.pendingWatchlistOpsSnapshot()
    pendingWatchlistOps.clear()
    pendingWatchlistOps.addAll(decoded)
    if (pendingWatchlistOps.isNotEmpty()) {
        watchlistSyncStatus = WatchlistSyncStatus.Failed("동기화 대기 ${pendingWatchlistOps.size}건")
    }
}


internal fun QuantAppState.savePendingWatchlistOps() {
    persistUserPreferences {
        userPreferences.setPendingWatchlistOps(pendingWatchlistOps.toList())
    }
}


internal suspend fun QuantAppState.restoreSession(): Boolean {
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


internal suspend fun QuantAppState.connectWatchlist() {
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


internal suspend fun QuantAppState.loadLocalWatchlist() {
    val decoded = userPreferences.watchlistSnapshot()
    replaceWatchlist(decoded, persist = false)
}
