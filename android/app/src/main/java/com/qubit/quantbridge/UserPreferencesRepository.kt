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

private const val USER_PREFERENCES_DATASTORE = "quantbridge_user_preferences"
private const val LEGACY_MAIN_PREFS = "quantbridge"
private const val LEGACY_ROUTINE_PREFS = "quantbridge_daily_routine"
private const val LEGACY_ACTION_INBOX_PREFS = "quantbridge_home_action_inbox"
private const val LEGACY_SEARCH_PREFS = "qubit_search"
private const val MAX_COMPARISON_ITEMS = 4
private const val LEGACY_ROUTINE_DATE_KEY = "date"
private const val LEGACY_ROUTINE_COMPLETED_KEY = "completed_ids"
private const val LEGACY_ACTION_INBOX_DATE_KEY = "date"
private const val LEGACY_ACTION_INBOX_COMPLETED_KEY = "completed_ids"
private const val LEGACY_ACTION_INBOX_SNOOZED_KEY = "snoozed_ids"
private const val LEGACY_RECENT_SEARCH_KEY = "recent_queries"

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

private fun <T> Flow<Preferences>.mapPreferences(transform: suspend (Preferences) -> T): Flow<T> {
    return map { prefs -> transform(prefs) }
}

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

private fun <T> androidx.datastore.preferences.core.MutablePreferences.putIfAbsent(
    key: Preferences.Key<T>,
    value: T
) {
    if (this[key] == null) this[key] = value
}

private val userPreferencesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class WatchlistItemDto(
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
private data class PendingWatchlistOperationDto(
    val action: String,
    val ticker: String,
    val item: WatchlistItemDto? = null
)

@Serializable
private data class StockComparisonItemDto(
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

internal fun encodeWatchlistItems(items: List<WatchlistItem>): String {
    return userPreferencesJson.encodeToString(
        ListSerializer(WatchlistItemDto.serializer()),
        items.map { it.toDto() }
    )
}

internal fun decodeWatchlistItems(raw: String): List<WatchlistItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        userPreferencesJson
            .decodeFromString(ListSerializer(WatchlistItemDto.serializer()), raw)
            .map { it.toModel() }
    }.getOrDefault(emptyList())
}

internal fun encodePendingWatchlistOps(items: List<PendingWatchlistOperation>): String {
    return userPreferencesJson.encodeToString(
        ListSerializer(PendingWatchlistOperationDto.serializer()),
        items.map { it.toDto() }
    )
}

internal fun decodePendingWatchlistOps(raw: String): List<PendingWatchlistOperation> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        userPreferencesJson
            .decodeFromString(ListSerializer(PendingWatchlistOperationDto.serializer()), raw)
            .mapNotNull { it.toModel() }
    }.getOrDefault(emptyList())
}

internal fun encodeComparisonItems(items: List<StockComparisonItem>): String {
    return userPreferencesJson.encodeToString(
        ListSerializer(StockComparisonItemDto.serializer()),
        items.map { it.toDto() }
    )
}

internal fun decodeComparisonItems(raw: String): List<StockComparisonItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        userPreferencesJson
            .decodeFromString(ListSerializer(StockComparisonItemDto.serializer()), raw)
            .map { it.toModel() }
            .take(MAX_COMPARISON_ITEMS)
    }.getOrDefault(emptyList())
}

internal fun encodeInvestmentProfile(profile: InvestmentProfile): String {
    return userPreferencesJson.encodeToString(InvestmentProfile.serializer(), profile.normalized)
}

internal fun decodeInvestmentProfile(raw: String): InvestmentProfile {
    if (raw.isBlank()) return InvestmentProfile()
    return runCatching {
        userPreferencesJson
            .decodeFromString(InvestmentProfile.serializer(), raw)
            .normalized
    }.getOrDefault(InvestmentProfile())
}

internal fun encodeInvestmentDecisions(items: List<InvestmentDecisionRecord>): String {
    return userPreferencesJson.encodeToString(
        ListSerializer(InvestmentDecisionRecord.serializer()),
        items.map { it.normalized }
    )
}

internal fun decodeInvestmentDecisions(raw: String): List<InvestmentDecisionRecord> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        userPreferencesJson
            .decodeFromString(ListSerializer(InvestmentDecisionRecord.serializer()), raw)
            .map { it.normalized }
            .filter { it.ticker.isNotBlank() }
    }.getOrDefault(emptyList())
}

internal fun encodeCsvSet(values: Set<String>): String {
    return values.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")
}

internal fun decodeCsvSet(raw: String): Set<String> {
    return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

private fun WatchlistItem.toDto(): WatchlistItemDto {
    return WatchlistItemDto(
        ticker = ticker,
        name = name,
        market = market,
        currency = currency,
        note = note,
        addedAt = addedAt,
        tags = tags,
        memo = memo,
        alertOptions = alertOptions
    )
}

private fun WatchlistItemDto.toModel(): WatchlistItem {
    return normalizeWatchlistItem(
        WatchlistItem(
            ticker = ticker,
            name = name,
            market = market,
            currency = currency,
            note = note,
            addedAt = addedAt,
            tags = tags,
            memo = memo,
            alertOptions = alertOptions
        )
    )
}

private fun PendingWatchlistOperation.toDto(): PendingWatchlistOperationDto {
    return PendingWatchlistOperationDto(
        action = action,
        ticker = ticker,
        item = item?.toDto()
    )
}

private fun PendingWatchlistOperationDto.toModel(): PendingWatchlistOperation? {
    val cleanAction = action.trim().lowercase()
    val cleanTicker = normalizedTicker(ticker)
    if (cleanAction.isBlank() || cleanTicker.isBlank()) return null
    return PendingWatchlistOperation(
        action = cleanAction,
        ticker = cleanTicker,
        item = item?.toModel()
    )
}

private fun StockComparisonItem.toDto(): StockComparisonItemDto {
    return StockComparisonItemDto(
        id = id,
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = currency,
        source = source,
        scoreValue = scoreValue,
        scoreText = scoreText,
        expectedReturn = expectedReturn,
        revenueGrowth = revenueGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = currentPrice,
        return1M = return1M,
        rankChange = rankChange,
        weight = weight,
        fcfMargin = fcfMargin,
        volumeSurge = volumeSurge,
        updatedAt = updatedAt
    )
}

private fun StockComparisonItemDto.toModel(): StockComparisonItem {
    return StockComparisonItem(
        id = id,
        ticker = normalizedTicker(ticker),
        name = name.ifBlank { ticker },
        market = market,
        sector = sector,
        currency = currency.ifBlank { marketCurrency(ticker, market) },
        source = source,
        scoreValue = scoreValue,
        scoreText = scoreText,
        expectedReturn = expectedReturn,
        revenueGrowth = revenueGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = currentPrice,
        return1M = return1M,
        rankChange = rankChange,
        weight = weight,
        fcfMargin = fcfMargin,
        volumeSurge = volumeSurge,
        updatedAt = updatedAt
    )
}
