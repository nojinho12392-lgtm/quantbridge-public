package com.qubit.quantbridge

import com.qubit.quantbridge.generated.models.QBScoredStockModel
import com.qubit.quantbridge.generated.models.QBSearchStockModel
import com.qubit.quantbridge.network.QuantApiService
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun searchUniverse(query: String, limit: Int = 100): List<SearchStock> {
        return api.searchUniverse(query = query.trim(), limit = limit)
            .stocks
            .orEmpty()
            .mapNotNull { it.toDomain() }
    }

    suspend fun fetchScored(market: Market, limit: Int = 300): List<ScoredStock> {
        val defaultMarket = market.title.uppercase(Locale.US)
        return api.getScored(market = market.title.lowercase(Locale.US), limit = limit)
            .stocks
            .orEmpty()
            .mapNotNull { it.toDomain(defaultMarket) }
    }

    private fun QBSearchStockModel.toDomain(): SearchStock? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market
        val safeCurrency = currency ?: marketCurrency(safeTicker, safeMarket)
        val safeName = name?.takeIf { it.isNotBlank() } ?: safeTicker
        return SearchStock(
            rank = rank,
            ticker = safeTicker,
            name = displayCompanyName(safeName, safeTicker),
            market = safeMarket,
            sector = sector,
            marketCap = marketCap.doubleOrNull(),
            inPortfolio = inPortfolio ?: false,
            inSmallCap = inSmallCap ?: false,
            currency = safeCurrency
        )
    }

    private fun QBScoredStockModel.toDomain(defaultMarket: String): ScoredStock? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market ?: defaultMarket
        val safeName = name?.takeIf { it.isNotBlank() } ?: safeTicker
        return ScoredStock(
            rank = rank,
            ticker = safeTicker,
            name = displayCompanyName(safeName, safeTicker),
            market = safeMarket,
            sector = sector,
            marketCap = marketCap.doubleOrNull(),
            valueScore = valueScore.doubleOrNull(),
            qualityScore = qualityScore.doubleOrNull(),
            momentumScore = momentumScore.doubleOrNull(),
            totalScore = totalScore.doubleOrNull(),
            finalScore = finalScore.doubleOrNull(),
            scoreNeutral = scoreNeutral.doubleOrNull(),
            mlScore = mlScore.doubleOrNull(),
            combinedScore = combinedScore.doubleOrNull(),
            roic = ROIC.doubleOrNull(),
            revGrowth = revGrowth.doubleOrNull(),
            grossMargin = grossMargin.doubleOrNull(),
            fcfMargin = fcFMargin.doubleOrNull(),
            debtEbitda = debtEBITDA.doubleOrNull(),
            peg = PEG.doubleOrNull()
        )
    }

    private fun Double?.doubleOrNull(): Double? {
        return this?.takeIf(Double::isFinite)
    }
}
