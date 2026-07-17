package com.qubit.quantbridge

import com.qubit.quantbridge.generated.models.QBEarningsCalendarItemModel
import com.qubit.quantbridge.generated.models.QBEarningsStockModel
import com.qubit.quantbridge.generated.models.QBSignalEventModel
import com.qubit.quantbridge.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

@Singleton
class PulseRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchMacro(): Map<String, String> {
        return api.getMacro().mapValues { (_, value) -> value.displayValue() }
    }

    suspend fun fetchEarnings(market: Market): List<EarningsStock> {
        return api.getEarnings(market.title.lowercase())
            .stocks
            .orEmpty()
            .mapNotNull { it.toDomain(market) }
    }

    suspend fun fetchEarningsCalendar(refresh: Boolean = false): List<EarningsCalendarItem> {
        return api.getEarningsCalendar(refresh = refresh)
            .items
            .orEmpty()
            .mapNotNull { it.toDomain() }
            .sortedWith(
                compareBy<EarningsCalendarItem> { it.nextEarningsDate }
                    .thenByDescending { it.marketCap ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.name }
            )
    }

    suspend fun fetchSignalEvents(): List<SignalEvent> {
        return api.getSignalEvents()
            .items
            .orEmpty()
            .mapNotNull { it.toDomain() }
    }

    private fun QBEarningsStockModel.toDomain(market: Market): EarningsStock? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val rawName = name?.takeIf { it.isNotBlank() } ?: safeTicker
        return EarningsStock(
            rank = rank,
            ticker = safeTicker,
            name = localizedCompanyName(safeTicker, rawName, market.title),
            sector = sector,
            marketCap = marketCap.doubleOrNull(),
            earningsDate = earningsDate,
            daysSince = daysSinceEarnings.doubleOrNull(),
            surprisePct = surprisePct.doubleOrNull(),
            returnSince = returnSince.doubleOrNull(),
            volumeSurge = volumeSurge.doubleOrNull(),
            signalStrength = signalStrength.doubleOrNull()
        )
    }

    private fun QBEarningsCalendarItemModel.toDomain(): EarningsCalendarItem? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeDate = nextEarningsDate?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market ?: marketCurrency(safeTicker, null).let { if (it == "KRW") "KR" else "US" }
        val rawName = name?.takeIf { it.isNotBlank() } ?: safeTicker
        return EarningsCalendarItem(
            ticker = safeTicker,
            name = localizedCompanyName(safeTicker, rawName, safeMarket),
            market = safeMarket,
            sector = sector,
            marketCap = marketCap.doubleOrNull(),
            nextEarningsDate = safeDate,
            daysUntil = daysUntil
        )
    }

    private fun QBSignalEventModel.toDomain(): SignalEvent? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market ?: marketCurrency(safeTicker, null).let { if (it == "KRW") "KR" else "US" }
        return SignalEvent(
            eventId = eventID ?: "${source ?: "event"}:$safeTicker:${kind ?: "unknown"}",
            market = safeMarket,
            ticker = safeTicker,
            name = localizedCompanyName(safeTicker, name?.takeIf { it.isNotBlank() } ?: safeTicker, safeMarket),
            kind = kind ?: "event",
            severity = severity ?: 1,
            title = title ?: "신호",
            detail = detail ?: "",
            metricLabel = metricLabel,
            metricValue = metricValue,
            eventTime = eventTime,
            source = source,
            updatedAt = updatedAt
        )
    }

    private fun JsonElement.displayValue(): String {
        return when (this) {
            is JsonPrimitive -> booleanOrNull?.toString()
                ?: intOrNull?.toString()
                ?: doubleOrNull?.toString()
                ?: contentOrNull.orEmpty()
            else -> toString()
        }
    }

    private fun Double?.doubleOrNull(): Double? {
        return this?.takeIf(Double::isFinite)
    }
}
