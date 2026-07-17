@file:Suppress("TooManyFunctions")

package com.qubit.quantbridge

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = USER_PREFERENCES_DATASTORE,
    produceMigrations = { context -> listOf(LegacyUserPreferencesMigration(context)) }
)

data class HomeActionInboxPreferenceState(
    val date: String,
    val completedIds: Set<String>,
    val snoozedIds: Set<String>
)

data class DailyRoutinePreferenceState(
    val date: String,
    val completedIds: Set<String>
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.applicationContext.userPreferencesDataStore

    val watchlist: Flow<List<WatchlistItem>> = data.mapPreferences {
        decodeWatchlistItems(it[UserPreferenceKeys.WATCHLIST].orEmpty())
    }

    val pendingWatchlistOps: Flow<List<PendingWatchlistOperation>> = data.mapPreferences {
        decodePendingWatchlistOps(it[UserPreferenceKeys.WATCHLIST_PENDING].orEmpty())
    }

    val comparisonItems: Flow<List<StockComparisonItem>> = data.mapPreferences {
        decodeComparisonItems(it[UserPreferenceKeys.COMPARISON_ITEMS].orEmpty())
    }

    val investmentProfile: Flow<InvestmentProfile> = data.mapPreferences {
        decodeInvestmentProfile(it[UserPreferenceKeys.INVESTMENT_PROFILE].orEmpty())
    }

    val investmentDecisions: Flow<List<InvestmentDecisionRecord>> = data.mapPreferences {
        decodeInvestmentDecisions(it[UserPreferenceKeys.INVESTMENT_DECISIONS].orEmpty())
    }

    val recentSearchesRaw: Flow<String> = data.mapPreferences {
        it[UserPreferenceKeys.RECENT_SEARCHES].orEmpty()
    }

    suspend fun watchlistSnapshot(): List<WatchlistItem> = watchlist.first()

    suspend fun setWatchlist(items: List<WatchlistItem>) {
        dataStore.edit { prefs ->
            prefs[UserPreferenceKeys.WATCHLIST] = encodeWatchlistItems(items)
        }
    }

    suspend fun pendingWatchlistOpsSnapshot(): List<PendingWatchlistOperation> = pendingWatchlistOps.first()

    suspend fun setPendingWatchlistOps(items: List<PendingWatchlistOperation>) {
        dataStore.edit { prefs ->
            prefs[UserPreferenceKeys.WATCHLIST_PENDING] = encodePendingWatchlistOps(items)
        }
    }

    suspend fun comparisonItemsSnapshot(): List<StockComparisonItem> = comparisonItems.first()

    suspend fun setComparisonItems(items: List<StockComparisonItem>) {
        dataStore.edit { prefs ->
            prefs[UserPreferenceKeys.COMPARISON_ITEMS] = encodeComparisonItems(items.take(MAX_COMPARISON_ITEMS))
        }
    }

    suspend fun investmentProfileSnapshot(): InvestmentProfile = investmentProfile.first()

    suspend fun setInvestmentProfile(profile: InvestmentProfile) {
        val clean = profile.normalized
        dataStore.edit { prefs ->
            if (clean.isConfigured) {
                prefs[UserPreferenceKeys.INVESTMENT_PROFILE] = encodeInvestmentProfile(clean)
            } else {
                prefs.remove(UserPreferenceKeys.INVESTMENT_PROFILE)
            }
        }
    }

    suspend fun investmentDecisionsSnapshot(): List<InvestmentDecisionRecord> = investmentDecisions.first()

    suspend fun setInvestmentDecisions(items: List<InvestmentDecisionRecord>) {
        dataStore.edit { prefs ->
            val clean = items.map { it.normalized }.filter { it.ticker.isNotBlank() }
            if (clean.isEmpty()) {
                prefs.remove(UserPreferenceKeys.INVESTMENT_DECISIONS)
            } else {
                prefs[UserPreferenceKeys.INVESTMENT_DECISIONS] = encodeInvestmentDecisions(clean)
            }
        }
    }

    suspend fun dailyRoutineSnapshot(): DailyRoutinePreferenceState {
        return data.mapPreferences {
            DailyRoutinePreferenceState(
                date = it[UserPreferenceKeys.DAILY_ROUTINE_DATE].orEmpty(),
                completedIds = decodeCsvSet(it[UserPreferenceKeys.DAILY_ROUTINE_COMPLETED].orEmpty())
            )
        }.first()
    }

    suspend fun setDailyRoutine(date: String, completedIds: Set<String>) {
        dataStore.edit { prefs ->
            prefs[UserPreferenceKeys.DAILY_ROUTINE_DATE] = date
            prefs[UserPreferenceKeys.DAILY_ROUTINE_COMPLETED] = encodeCsvSet(completedIds)
        }
    }

    suspend fun homeActionInboxSnapshot(): HomeActionInboxPreferenceState {
        return data.mapPreferences {
            HomeActionInboxPreferenceState(
                date = it[UserPreferenceKeys.HOME_ACTION_INBOX_DATE].orEmpty(),
                completedIds = decodeCsvSet(it[UserPreferenceKeys.HOME_ACTION_INBOX_COMPLETED].orEmpty()),
                snoozedIds = decodeCsvSet(it[UserPreferenceKeys.HOME_ACTION_INBOX_SNOOZED].orEmpty())
            )
        }.first()
    }

    suspend fun setHomeActionInbox(date: String, completedIds: Set<String>, snoozedIds: Set<String>) {
        dataStore.edit { prefs ->
            prefs[UserPreferenceKeys.HOME_ACTION_INBOX_DATE] = date
            prefs[UserPreferenceKeys.HOME_ACTION_INBOX_COMPLETED] = encodeCsvSet(completedIds)
            prefs[UserPreferenceKeys.HOME_ACTION_INBOX_SNOOZED] = encodeCsvSet(snoozedIds)
        }
    }

    suspend fun recentSearchesSnapshot(): String = recentSearchesRaw.first()

    suspend fun setRecentSearchesRaw(value: String) {
        dataStore.edit { prefs ->
            if (value.isBlank()) {
                prefs.remove(UserPreferenceKeys.RECENT_SEARCHES)
            } else {
                prefs[UserPreferenceKeys.RECENT_SEARCHES] = value
            }
        }
    }

    private val data: Flow<Preferences>
        get() = dataStore.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
}

internal fun <T> Flow<Preferences>.mapPreferences(transform: suspend (Preferences) -> T): Flow<T> {
    return map { prefs -> transform(prefs) }
}
