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

internal const val USER_PREFERENCES_DATASTORE = "quantbridge_user_preferences"
internal const val LEGACY_MAIN_PREFS = "quantbridge"
internal const val LEGACY_ROUTINE_PREFS = "quantbridge_daily_routine"
internal const val LEGACY_ACTION_INBOX_PREFS = "quantbridge_home_action_inbox"
internal const val LEGACY_SEARCH_PREFS = "qubit_search"
internal const val MAX_COMPARISON_ITEMS = 4
internal const val LEGACY_ROUTINE_DATE_KEY = "date"
internal const val LEGACY_ROUTINE_COMPLETED_KEY = "completed_ids"
internal const val LEGACY_ACTION_INBOX_DATE_KEY = "date"
internal const val LEGACY_ACTION_INBOX_COMPLETED_KEY = "completed_ids"
internal const val LEGACY_ACTION_INBOX_SNOOZED_KEY = "snoozed_ids"
internal const val LEGACY_RECENT_SEARCH_KEY = "recent_queries"

internal object UserPreferenceKeys {
    val WATCHLIST = stringPreferencesKey("watchlist")
    val WATCHLIST_PENDING = stringPreferencesKey("watchlist_pending")
    val COMPARISON_ITEMS = stringPreferencesKey("comparison_items")
    val INVESTMENT_PROFILE = stringPreferencesKey("investment_profile")
    val INVESTMENT_DECISIONS = stringPreferencesKey("investment_decisions")
    val DAILY_ROUTINE_DATE = stringPreferencesKey("daily_routine_date")
    val DAILY_ROUTINE_COMPLETED = stringPreferencesKey("daily_routine_completed")
    val HOME_ACTION_INBOX_DATE = stringPreferencesKey("home_action_inbox_date")
    val HOME_ACTION_INBOX_COMPLETED = stringPreferencesKey("home_action_inbox_completed")
    val HOME_ACTION_INBOX_SNOOZED = stringPreferencesKey("home_action_inbox_snoozed")
    val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
}

internal class LegacyUserPreferencesMigration(context: Context) : DataMigration<Preferences> {
    private val appContext = context.applicationContext

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        return needsMainMigration(currentData) ||
            needsRoutineMigration(currentData) ||
            needsActionInboxMigration(currentData) ||
            needsSearchMigration(currentData)
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val mainPrefs = appContext.getSharedPreferences(LEGACY_MAIN_PREFS, Context.MODE_PRIVATE)
        val routinePrefs = appContext.getSharedPreferences(LEGACY_ROUTINE_PREFS, Context.MODE_PRIVATE)
        val actionInboxPrefs = appContext.getSharedPreferences(LEGACY_ACTION_INBOX_PREFS, Context.MODE_PRIVATE)
        val searchPrefs = appContext.getSharedPreferences(LEGACY_SEARCH_PREFS, Context.MODE_PRIVATE)

        mainPrefs.getString(UserPreferenceKeys.WATCHLIST.name, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.WATCHLIST, it)
        }
        mainPrefs.getString(UserPreferenceKeys.WATCHLIST_PENDING.name, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.WATCHLIST_PENDING, it)
        }
        mainPrefs.getString(UserPreferenceKeys.COMPARISON_ITEMS.name, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.COMPARISON_ITEMS, it)
        }
        routinePrefs.getString(LEGACY_ROUTINE_DATE_KEY, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.DAILY_ROUTINE_DATE, it)
        }
        routinePrefs.getString(LEGACY_ROUTINE_COMPLETED_KEY, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.DAILY_ROUTINE_COMPLETED, it)
        }
        actionInboxPrefs.getString(LEGACY_ACTION_INBOX_DATE_KEY, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.HOME_ACTION_INBOX_DATE, it)
        }
        actionInboxPrefs.getString(LEGACY_ACTION_INBOX_COMPLETED_KEY, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.HOME_ACTION_INBOX_COMPLETED, it)
        }
        actionInboxPrefs.getString(LEGACY_ACTION_INBOX_SNOOZED_KEY, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.HOME_ACTION_INBOX_SNOOZED, it)
        }
        searchPrefs.getString(LEGACY_RECENT_SEARCH_KEY, null)?.let {
            mutable.putIfAbsent(UserPreferenceKeys.RECENT_SEARCHES, it)
        }
        return mutable
    }

    override suspend fun cleanUp() {
        appContext.getSharedPreferences(LEGACY_MAIN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(UserPreferenceKeys.WATCHLIST.name)
            .remove(UserPreferenceKeys.WATCHLIST_PENDING.name)
            .remove(UserPreferenceKeys.COMPARISON_ITEMS.name)
            .commit()
        appContext.getSharedPreferences(LEGACY_ROUTINE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        appContext.getSharedPreferences(LEGACY_ACTION_INBOX_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        appContext.getSharedPreferences(LEGACY_SEARCH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun needsMainMigration(currentData: Preferences): Boolean {
        val prefs = appContext.getSharedPreferences(LEGACY_MAIN_PREFS, Context.MODE_PRIVATE)
        return (currentData[UserPreferenceKeys.WATCHLIST] == null && prefs.contains(UserPreferenceKeys.WATCHLIST.name)) ||
            (currentData[UserPreferenceKeys.WATCHLIST_PENDING] == null && prefs.contains(UserPreferenceKeys.WATCHLIST_PENDING.name)) ||
            (currentData[UserPreferenceKeys.COMPARISON_ITEMS] == null && prefs.contains(UserPreferenceKeys.COMPARISON_ITEMS.name))
    }

    private fun needsRoutineMigration(currentData: Preferences): Boolean {
        val prefs = appContext.getSharedPreferences(LEGACY_ROUTINE_PREFS, Context.MODE_PRIVATE)
        return (currentData[UserPreferenceKeys.DAILY_ROUTINE_DATE] == null && prefs.contains(LEGACY_ROUTINE_DATE_KEY)) ||
            (currentData[UserPreferenceKeys.DAILY_ROUTINE_COMPLETED] == null && prefs.contains(LEGACY_ROUTINE_COMPLETED_KEY))
    }

    private fun needsActionInboxMigration(currentData: Preferences): Boolean {
        val prefs = appContext.getSharedPreferences(LEGACY_ACTION_INBOX_PREFS, Context.MODE_PRIVATE)
        return (currentData[UserPreferenceKeys.HOME_ACTION_INBOX_DATE] == null && prefs.contains(LEGACY_ACTION_INBOX_DATE_KEY)) ||
            (currentData[UserPreferenceKeys.HOME_ACTION_INBOX_COMPLETED] == null && prefs.contains(LEGACY_ACTION_INBOX_COMPLETED_KEY)) ||
            (currentData[UserPreferenceKeys.HOME_ACTION_INBOX_SNOOZED] == null && prefs.contains(LEGACY_ACTION_INBOX_SNOOZED_KEY))
    }

    private fun needsSearchMigration(currentData: Preferences): Boolean {
        val prefs = appContext.getSharedPreferences(LEGACY_SEARCH_PREFS, Context.MODE_PRIVATE)
        return currentData[UserPreferenceKeys.RECENT_SEARCHES] == null && prefs.contains(LEGACY_RECENT_SEARCH_KEY)
    }
}

internal fun <T> androidx.datastore.preferences.core.MutablePreferences.putIfAbsent(
    key: Preferences.Key<T>,
    value: T
) {
    if (this[key] == null) this[key] = value
}

internal val userPreferencesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class WatchlistItemDto(
    val ticker: String,
    val name: String,
    val market: String,
    val currency: String,
    val note: String,
    val addedAt: String,
    val tags: List<String> = emptyList(),
    val memo: String = "",
    val alertOptions: List<String> = emptyList()
)

@Serializable
internal data class PendingWatchlistOperationDto(
    val action: String,
    val ticker: String,
    val item: WatchlistItemDto? = null
)

@Serializable
internal data class StockComparisonItemDto(
    val id: String,
    val ticker: String,
    val name: String,
    val market: String? = null,
    val sector: String? = null,
    val currency: String,
    val source: String,
    val scoreValue: Double? = null,
    val scoreText: String,
    val expectedReturn: Double? = null,
    val revenueGrowth: Double? = null,
    val roic: Double? = null,
    val grossMargin: Double? = null,
    val marketCap: Double? = null,
    val currentPrice: Double? = null,
    val return1M: Double? = null,
    val rankChange: Int? = null,
    val weight: Double? = null,
    val fcfMargin: Double? = null,
    val volumeSurge: Double? = null,
    val updatedAt: String? = null
)
