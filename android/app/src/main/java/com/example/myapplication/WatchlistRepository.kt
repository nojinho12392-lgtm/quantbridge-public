package com.example.myapplication

import com.example.myapplication.generated.models.QBWatchlistItem
import com.example.myapplication.network.QuantApiService
import com.example.myapplication.network.WatchlistRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class WatchlistRepository @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val api: QuantApiService
) {
    val watchlist: Flow<List<WatchlistItem>> = preferences.watchlist
    val pendingOperations: Flow<List<PendingWatchlistOperation>> = preferences.pendingWatchlistOps

    suspend fun watchlistSnapshot(): List<WatchlistItem> = preferences.watchlistSnapshot()

    suspend fun pendingOperationsSnapshot(): List<PendingWatchlistOperation> = preferences.pendingWatchlistOpsSnapshot()

    suspend fun setWatchlist(items: List<WatchlistItem>) {
        preferences.setWatchlist(items.map(::normalizeWatchlistItem))
    }

    suspend fun setPendingOperations(items: List<PendingWatchlistOperation>) {
        preferences.setPendingWatchlistOps(items)
    }

    suspend fun fetchRemoteWatchlist(): List<WatchlistItem> {
        return api.getWatchlist().items.orEmpty().map { it.toDomain() }
    }

    suspend fun saveRemoteWatchlist(item: WatchlistItem) {
        val normalized = normalizeWatchlistItem(item)
        api.saveWatchlist(
            WatchlistRequest(
                ticker = normalized.ticker,
                name = normalized.name,
                market = normalized.market,
                currency = normalized.currency,
                note = normalized.note
            )
        )
    }

    suspend fun deleteRemoteWatchlist(ticker: String) {
        api.deleteWatchlist(normalizedTicker(ticker))
    }

    private fun QBWatchlistItem.toDomain(): WatchlistItem {
        return normalizeWatchlistItem(
            WatchlistItem(
                ticker = ticker,
                name = name,
                market = market,
                currency = currency,
                note = note.orEmpty(),
                addedAt = addedAt.orEmpty()
            )
        )
    }
}
