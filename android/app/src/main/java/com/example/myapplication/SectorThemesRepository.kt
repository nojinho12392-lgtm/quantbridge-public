package com.example.myapplication

import android.content.Context
import com.example.myapplication.generated.models.QBSectorTheme
import com.example.myapplication.generated.models.QBSectorThemeMember
import com.example.myapplication.network.QuantApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SectorThemesRepository @Inject constructor(
    private val api: QuantApiService,
    @ApplicationContext context: Context
) {
    private val snapshotStore = SectorThemeSnapshotStore(context.applicationContext)

    suspend fun cachedSectorThemesResult(market: String = "ALL"): SectorThemesResult? {
        return snapshotStore.readSummary(market.safeSectorMarket())
    }

    suspend fun fetchSectorThemesResult(
        market: String = "ALL",
        limit: Int = 36,
        members: Int = 12,
        refresh: Boolean = false
    ): SectorThemesResult {
        val safeMarket = market.safeSectorMarket()
        val response = api.getSectorThemes(
            market = safeMarket,
            limit = limit,
            members = members,
            refresh = refresh
        )
        val result = SectorThemesResult(
            items = response.items.orEmpty().map { it.toDomain() },
            market = response.market,
            source = response.source,
            generatedAt = response.generatedAt
        )
        snapshotStore.writeSummary(result)
        return result
    }

    suspend fun cachedSectorThemeDetail(
        label: String,
        market: String = "ALL"
    ): SectorTheme? {
        return snapshotStore.readDetail(label = label, market = market.safeSectorMarket())
    }

    suspend fun fetchSectorThemeDetail(
        label: String,
        market: String = "ALL",
        members: Int = 80,
        refresh: Boolean = false
    ): SectorTheme? {
        return api.getSectorThemeDetail(
            label = label,
            market = market.safeSectorMarket(),
            members = members,
            refresh = refresh
        ).item.toDomain().also { detail ->
            snapshotStore.writeDetail(detail)
        }
    }

    private fun String.safeSectorMarket(): String {
        return uppercase(Locale.US).takeIf { it in setOf("ALL", "US", "KR") } ?: "ALL"
    }

    private fun QBSectorTheme.toDomain(): SectorTheme {
        val domainMembers = members.orEmpty().mapNotNull { it.toDomain() }
        val memberDailyChanges = domainMembers.mapNotNull { it.dailyChangePct?.takeIf(Double::isFinite) }
        val memberReturns = domainMembers.mapNotNull { it.return1M?.takeIf(Double::isFinite) }
        return SectorTheme(
            label = label,
            market = market ?: "ALL",
            memberCount = memberCount ?: domainMembers.size,
            pricedCount = pricedCount ?: memberDailyChanges.size,
            risingCount = risingCount ?: memberDailyChanges.count { it > 0.0 },
            fallingCount = fallingCount ?: memberDailyChanges.count { it < 0.0 },
            avgChangePct = avgChangePct.doubleOrNull() ?: memberDailyChanges.averageOrNull(),
            avgReturn1M = avgReturn1m.doubleOrNull() ?: memberReturns.averageOrNull(),
            leader = leader?.toDomain(),
            members = domainMembers
        )
    }

    private fun QBSectorThemeMember.toDomain(): SectorThemeMember? {
        val safeTicker = ticker?.takeIf { it.isNotBlank() } ?: return null
        val safeMarket = market
        return SectorThemeMember(
            ticker = safeTicker,
            name = displayCompanyName(name?.takeIf { it.isNotBlank() } ?: safeTicker, safeTicker),
            market = safeMarket,
            sector = sector,
            currency = currency ?: marketCurrency(safeTicker, safeMarket),
            source = source,
            marketCap = marketCap.doubleOrNull(),
            currentPrice = currentPrice.doubleOrNull(),
            dailyChangePct = dailyChangePct.doubleOrNull(),
            dailyChangeHorizon = dailyChangeHorizon,
            return1M = return1M.doubleOrNull(),
            scoreValue = scoreValue.doubleOrNull(),
            inPortfolio = inPortfolio ?: false,
            inSmallCap = inSmallCap ?: false
        )
    }

    private fun Double?.doubleOrNull(): Double? {
        return this?.takeIf(Double::isFinite)
    }

    private fun List<Double>.averageOrNull(): Double? {
        val clean = filter(Double::isFinite)
        return clean.takeIf { it.isNotEmpty() }?.average()
    }
}
