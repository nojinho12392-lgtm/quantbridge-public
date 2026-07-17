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

internal suspend fun QuantAppState.login(email: String, password: String, displayName: String?, signup: Boolean): Boolean {
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


internal suspend fun QuantAppState.adoptAccountSession(session: AccountSession) {
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


internal fun QuantAppState.clearAccountSession(clearWatchlist: Boolean = false) {
    clearSessionState(clearWatchlist = clearWatchlist)
}


internal suspend fun QuantAppState.logout() {
    token?.let { runCatching { api.logout(it) }.rethrowCancellation() }
    clearSessionState(clearWatchlist = false)
}


internal suspend fun QuantAppState.deleteAccount(): Boolean {
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


internal fun QuantAppState.clearSessionState(clearWatchlist: Boolean = false) {
    disconnectAccountSession()
    if (clearWatchlist) {
        clearLocalAccountState()
    }
}


internal fun QuantAppState.disconnectAccountSession() {
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


internal fun QuantAppState.clearLocalAccountState() {
    watchlist.clear()
    pendingWatchlistOps.clear()
    saveLocalWatchlist()
    savePendingWatchlistOps()
    watchlistSyncStatus = WatchlistSyncStatus.Idle
}
