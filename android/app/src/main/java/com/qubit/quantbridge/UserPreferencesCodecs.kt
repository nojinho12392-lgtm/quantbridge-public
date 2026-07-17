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

internal fun WatchlistItem.toDto(): WatchlistItemDto {
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

internal fun WatchlistItemDto.toModel(): WatchlistItem {
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

internal fun PendingWatchlistOperation.toDto(): PendingWatchlistOperationDto {
    return PendingWatchlistOperationDto(
        action = action,
        ticker = ticker,
        item = item?.toDto()
    )
}

internal fun PendingWatchlistOperationDto.toModel(): PendingWatchlistOperation? {
    val cleanAction = action.trim().lowercase()
    val cleanTicker = normalizedTicker(ticker)
    if (cleanAction.isBlank() || cleanTicker.isBlank()) return null
    return PendingWatchlistOperation(
        action = cleanAction,
        ticker = cleanTicker,
        item = item?.toModel()
    )
}

internal fun StockComparisonItem.toDto(): StockComparisonItemDto {
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

internal fun StockComparisonItemDto.toModel(): StockComparisonItem {
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
