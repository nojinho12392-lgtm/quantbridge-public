package com.example.myapplication

import com.example.myapplication.generated.models.QBSmallCapStockModel
import com.example.myapplication.network.QuantApiService
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmallCapRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchSmallCap(market: Market): List<SmallCapStock> {
        return api.getSmallCap(market.title.lowercase(Locale.US))
            .stocks
            .orEmpty()
            .mapNotNull { it.toDomain() }
            .map { it.normalizeKrIdentity() }
    }

    private fun QBSmallCapStockModel.toDomain(): SmallCapStock? {
        val safeRank = rank ?: return null
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market
        return SmallCapStock(
            rank = safeRank,
            previousRank = previousRank,
            rankChange = rankChange,
            rankStatus = rankStatus,
            ticker = safeTicker,
            name = displayCompanyName(name?.takeIf { it.isNotBlank() } ?: safeTicker, safeTicker),
            market = safeMarket,
            marketCap = marketCap.doubleOrNull(),
            currentPrice = currentPrice.doubleOrNull(),
            return1M = return1M.doubleOrNull(),
            roic = ROIC.doubleOrNull(),
            revGrowth = revGrowth.doubleOrNull(),
            revAccel = revAccel.doubleOrNull(),
            grossMargin = grossMargin.doubleOrNull(),
            fcfMargin = fcFMargin.doubleOrNull(),
            debtEbitda = debtEBITDA.doubleOrNull(),
            volumeSurge = volumeSurge.doubleOrNull(),
            smallCapBonus = smallCapBonus.doubleOrNull(),
            totalScore = totalScore.doubleOrNull(),
            lastUpdated = lastUpdated,
            source = source,
            generatedAt = generatedAt
        )
    }

    private fun SmallCapStock.normalizeKrIdentity(): SmallCapStock {
        if (!isKoreanTicker(ticker, market)) return this
        val code = krCode(ticker.ifBlank { name })
        val normalizedName = if (isMissingKrName(name, ticker)) {
            code.ifBlank { ticker }
        } else {
            name.trim()
        }
        val normalizedTicker = when {
            hasKrSuffix(ticker) -> ticker.trim().uppercase(Locale.US)
            code.isNotBlank() -> code
            else -> ticker.trim()
        }
        return copy(ticker = normalizedTicker, name = normalizedName)
    }

    private fun Double?.doubleOrNull(): Double? {
        return this?.takeIf(Double::isFinite)
    }
}
