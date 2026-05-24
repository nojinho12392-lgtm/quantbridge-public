package com.example.myapplication

import com.example.myapplication.generated.models.QBMarketIndicatorPoint
import com.example.myapplication.generated.models.QBMarketIndicatorQuote
import com.example.myapplication.generated.models.QBMarketIndicatorSeries
import com.example.myapplication.generated.models.QBMarketIndexQuote
import com.example.myapplication.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketIndicatorsRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchMarketIndices(refresh: Boolean = false): MarketIndicesResult {
        val response = api.getMarketIndices(refresh = refresh)
        val fallbackUpdatedAt = response.updatedAt.orEmpty()
        return MarketIndicesResult(
            indices = response.indices.orEmpty().map { it.toDomain(fallbackUpdatedAt) },
            updatedAt = response.updatedAt,
            source = response.source
        )
    }

    suspend fun fetchMarketIndicators(
        category: String = "index_fx",
        refresh: Boolean = false
    ): MarketIndicatorsResult {
        val safeCategory = category.ifBlank { "index_fx" }
        val response = api.getMarketIndicators(category = safeCategory, refresh = refresh)
        return MarketIndicatorsResult(
            items = response.items.orEmpty().mapNotNull { it.toDomain(safeCategory, response.updatedAt) },
            updatedAt = response.updatedAt,
            source = response.source
        )
    }

    suspend fun fetchMarketIndicatorHistory(
        refresh: Boolean = false,
        symbols: List<String> = emptyList()
    ): MarketIndicatorHistoryResult {
        val response = api.getMarketIndicatorHistory(
            symbols = symbols
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(","),
            period = "1d",
            interval = "15m",
            refresh = refresh
        )
        return MarketIndicatorHistoryResult(
            series = response.series.orEmpty().map { it.toDomain() },
            updatedAt = response.updatedAt,
            source = response.source
        )
    }

    private fun QBMarketIndexQuote.toDomain(fallbackUpdatedAt: String): MarketIndexQuote {
        return MarketIndexQuote(
            symbol = symbol,
            label = label,
            value = value,
            changeAbs = changeAbs?.takeIf(Double::isFinite) ?: 0.0,
            changePct = changePct?.takeIf(Double::isFinite) ?: 0.0,
            updatedAt = updatedAt ?: fallbackUpdatedAt
        )
    }

    private fun QBMarketIndicatorQuote.toDomain(
        fallbackCategory: String,
        fallbackUpdatedAt: String?
    ): MarketIndicatorQuote? {
        val cleanValue = value?.takeIf(Double::isFinite) ?: return null
        return MarketIndicatorQuote(
            symbol = symbol,
            label = label,
            category = category ?: fallbackCategory,
            region = region ?: "global",
            value = cleanValue,
            changeAbs = changeAbs?.takeIf(Double::isFinite),
            changePct = changePct?.takeIf(Double::isFinite),
            updatedAt = updatedAt ?: fallbackUpdatedAt
        )
    }

    private fun QBMarketIndicatorSeries.toDomain(): MarketIndicatorSeries {
        return MarketIndicatorSeries(
            symbol = symbol,
            points = points.orEmpty().mapNotNull { it.toDomainOrNull() }
        )
    }

    private fun QBMarketIndicatorPoint.toDomainOrNull(): MarketIndicatorPoint? {
        if (!close.isFinite()) return null
        return MarketIndicatorPoint(timestamp = timestamp, close = close)
    }
}

data class MarketIndicesResult(
    val indices: List<MarketIndexQuote>,
    val updatedAt: String?,
    val source: String?
)

data class MarketIndicatorsResult(
    val items: List<MarketIndicatorQuote>,
    val updatedAt: String?,
    val source: String?
)

data class MarketIndicatorHistoryResult(
    val series: List<MarketIndicatorSeries>,
    val updatedAt: String?,
    val source: String?
)
