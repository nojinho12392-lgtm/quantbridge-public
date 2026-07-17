package com.qubit.quantbridge

import com.qubit.quantbridge.generated.models.QBPortfolioStockModel
import com.qubit.quantbridge.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Singleton
class PortfolioRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchPortfolio(market: Market): PortfolioRepositoryResult {
        val response = api.getPortfolio(market.title.lowercase())
        return PortfolioRepositoryResult(
            meta = response.meta.orEmpty().mapValues { (_, value) ->
                when (value) {
                    is JsonPrimitive -> value.contentOrNull.orEmpty()
                    else -> value.toString()
                }
            },
            stocks = response.stocks.orEmpty().mapNotNull { it.toDomain() }
        )
    }

    private fun QBPortfolioStockModel.toDomain(): PortfolioStock? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market
        return PortfolioStock(
            rank = rank,
            previousRank = previousRank,
            rankChange = rankChange,
            rankStatus = rankStatus,
            ticker = safeTicker,
            name = displayCompanyName(name?.takeIf { it.isNotBlank() } ?: safeTicker, safeTicker),
            market = safeMarket,
            sector = sector,
            marketCap = marketCap.doubleOrNull(),
            weight = weightPercent.doubleOrNull(),
            currentPrice = currentPrice.doubleOrNull(),
            return1M = return1M.doubleOrNull() ?: mom1M.doubleOrNull(),
            totalScore = totalScore.doubleOrNull(),
            roic = ROIC.doubleOrNull(),
            revGrowth = revGrowth.doubleOrNull(),
            grossMargin = grossMargin.doubleOrNull(),
            expectedReturn = expectedReturn.doubleOrNull(),
            lastUpdated = lastUpdated,
            source = source,
            generatedAt = generatedAt
        )
    }

    private fun Double?.doubleOrNull(): Double? {
        return this?.takeIf(Double::isFinite)
    }
}

data class PortfolioRepositoryResult(
    val meta: Map<String, String>,
    val stocks: List<PortfolioStock>
)
