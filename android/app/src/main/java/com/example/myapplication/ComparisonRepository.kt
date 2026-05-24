package com.example.myapplication

import com.example.myapplication.generated.models.QBComparisonRecommendationItemModel
import com.example.myapplication.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComparisonRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchRecommendations(anchor: StockComparisonItem): List<StockComparisonItem> {
        val response = api.getComparisonRecommendations(
            ticker = anchor.ticker,
            market = anchor.market?.takeIf { it.isNotBlank() } ?: "ALL"
        )
        return response.items.orEmpty().mapNotNull { it.toDomain() }
    }

    private fun QBComparisonRecommendationItemModel.toDomain(): StockComparisonItem? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeCurrency = currency?.takeIf { it.isNotBlank() } ?: marketCurrency(safeTicker, market)
        return StockComparisonItem(
            id = "recommendation:${normalizedTicker(safeTicker)}",
            ticker = safeTicker,
            name = displayCompanyName(name?.takeIf { it.isNotBlank() } ?: safeTicker, safeTicker),
            market = market,
            sector = sector,
            currency = safeCurrency,
            source = source ?: "추천",
            scoreValue = scoreValue.finiteOrNull(),
            scoreText = scoreValue?.let { "%.2f".format(it) } ?: "-",
            expectedReturn = expectedReturn.finiteOrNull(),
            revenueGrowth = revGrowth.finiteOrNull(),
            roic = ROIC.finiteOrNull(),
            grossMargin = grossMargin.finiteOrNull(),
            marketCap = marketCap.finiteOrNull(),
            currentPrice = currentPrice.finiteOrNull(),
            return1M = return1M.finiteOrNull(),
            rankChange = rankChange,
            weight = weightPercent.finiteOrNull(),
            fcfMargin = fcFMargin.finiteOrNull(),
            volumeSurge = volumeSurge.finiteOrNull(),
            updatedAt = lastUpdated
        )
    }

    private fun Double?.finiteOrNull(): Double? {
        return this?.takeIf(Double::isFinite)
    }
}
