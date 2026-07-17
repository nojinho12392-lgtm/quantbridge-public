package com.qubit.quantbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val repository: WatchlistRepository
) : ViewModel() {
    var watchlist by mutableStateOf<List<WatchlistItem>>(emptyList())
        private set
    var pendingOperations by mutableStateOf<List<PendingWatchlistOperation>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val pendingCount: Int
        get() = pendingOperations.size

    init {
        observeLocalWatchlist()
        observePendingOperations()
    }

    fun isWatched(ticker: String): Boolean {
        val key = normalizedTicker(ticker)
        return watchlist.any { normalizedTicker(it.ticker) == key }
    }

    fun watchlistItem(ticker: String): WatchlistItem? {
        val key = normalizedTicker(ticker)
        return watchlist.firstOrNull { normalizedTicker(it.ticker) == key }
    }

    fun localSyncStatusFallback(): WatchlistSyncStatus {
        return if (pendingOperations.isEmpty()) {
            WatchlistSyncStatus.Idle
        } else {
            WatchlistSyncStatus.Failed("동기화 대기 ${pendingOperations.size}건")
        }
    }

    private fun observeLocalWatchlist() {
        viewModelScope.launch {
            repository.watchlist
                .catch { exc ->
                    exc.throwIfCancellation()
                    error = exc.localizedMessage ?: "관심 목록을 불러오지 못했습니다."
                    loading = false
                }
                .collect { items ->
                    watchlist = items.map(::normalizeWatchlistItem)
                    error = null
                    loading = false
                }
        }
    }

    private fun observePendingOperations() {
        viewModelScope.launch {
            repository.pendingOperations
                .catch { exc ->
                    exc.throwIfCancellation()
                    error = exc.localizedMessage ?: "관심 동기화 상태를 불러오지 못했습니다."
                }
                .collect { pendingOperations = it }
        }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }
}
