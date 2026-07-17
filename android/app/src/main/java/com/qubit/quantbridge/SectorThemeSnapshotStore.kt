package com.qubit.quantbridge

import android.content.Context
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val SECTOR_THEME_CACHE_DIR = "sector_theme_snapshots"

internal class SectorThemeSnapshotStore(context: Context) {
    private val directory = File(context.cacheDir, SECTOR_THEME_CACHE_DIR)

    suspend fun readSummary(market: String): SectorThemesResult? {
        return withContext(Dispatchers.IO) {
            readResult(summaryFile(market))
        }
    }

    suspend fun writeSummary(result: SectorThemesResult) {
        withContext(Dispatchers.IO) {
            writeResult(summaryFile(result.market), result)
        }
    }

    suspend fun readDetail(label: String, market: String): SectorTheme? {
        return withContext(Dispatchers.IO) {
            readTheme(detailFile(label, market))?.toModel()
        }
    }

    suspend fun writeDetail(theme: SectorTheme) {
        withContext(Dispatchers.IO) {
            writeTheme(detailFile(theme.label, theme.market), theme.toDto())
        }
    }

    private fun readResult(file: File): SectorThemesResult? {
        if (!file.exists()) return null
        return runCatching {
            sectorThemeSnapshotJson
                .decodeFromString(SectorThemesResultDto.serializer(), file.readText())
                .toModel()
                .takeIf { it.items.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeResult(file: File, result: SectorThemesResult) {
        if (result.items.isEmpty()) return
        runCatching {
            directory.mkdirs()
            file.writeText(sectorThemeSnapshotJson.encodeToString(SectorThemesResultDto.serializer(), result.toDto()))
        }
    }

    private fun readTheme(file: File): SectorThemeDto? {
        if (!file.exists()) return null
        return runCatching {
            sectorThemeSnapshotJson
                .decodeFromString(SectorThemeDto.serializer(), file.readText())
                .takeIf { it.members.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeTheme(file: File, theme: SectorThemeDto) {
        if (theme.members.isEmpty()) return
        runCatching {
            directory.mkdirs()
            file.writeText(sectorThemeSnapshotJson.encodeToString(SectorThemeDto.serializer(), theme))
        }
    }

    private fun summaryFile(market: String): File {
        return File(directory, "summary_${market.safeCacheSegment()}.json")
    }

    private fun detailFile(label: String, market: String): File {
        return File(directory, "detail_${market.safeCacheSegment()}_${label.safeCacheSegment()}.json")
    }
}

@Serializable
private data class SectorThemesResultDto(
    val items: List<SectorThemeDto>,
    val market: String,
    val source: String? = null,
    val generatedAt: String? = null
)

@Serializable
private data class SectorThemeDto(
    val label: String,
    val market: String,
    val memberCount: Int,
    val pricedCount: Int,
    val risingCount: Int,
    val fallingCount: Int,
    val avgChangePct: Double? = null,
    val avgReturn1M: Double? = null,
    val leader: SectorThemeMemberDto? = null,
    val members: List<SectorThemeMemberDto>
)

@Serializable
private data class SectorThemeMemberDto(
    val ticker: String,
    val name: String,
    val market: String? = null,
    val sector: String? = null,
    val currency: String? = null,
    val source: String? = null,
    val marketCap: Double? = null,
    val currentPrice: Double? = null,
    val dailyChangePct: Double? = null,
    val dailyChangeHorizon: String? = null,
    val return1M: Double? = null,
    val scoreValue: Double? = null,
    val inPortfolio: Boolean = false,
    val inSmallCap: Boolean = false
)

private val sectorThemeSnapshotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private fun SectorThemesResult.toDto(): SectorThemesResultDto {
    return SectorThemesResultDto(
        items = items.map { it.toDto() },
        market = market,
        source = source,
        generatedAt = generatedAt
    )
}

private fun SectorThemesResultDto.toModel(): SectorThemesResult {
    return SectorThemesResult(
        items = items.map { it.toModel() },
        market = market,
        source = source,
        generatedAt = generatedAt
    )
}

private fun SectorTheme.toDto(): SectorThemeDto {
    return SectorThemeDto(
        label = label,
        market = market,
        memberCount = memberCount,
        pricedCount = pricedCount,
        risingCount = risingCount,
        fallingCount = fallingCount,
        avgChangePct = avgChangePct?.takeIf(Double::isFinite),
        avgReturn1M = avgReturn1M?.takeIf(Double::isFinite),
        leader = leader?.toDto(),
        members = members.map { it.toDto() }
    )
}

private fun SectorThemeDto.toModel(): SectorTheme {
    return SectorTheme(
        label = label,
        market = market,
        memberCount = memberCount,
        pricedCount = pricedCount,
        risingCount = risingCount,
        fallingCount = fallingCount,
        avgChangePct = avgChangePct?.takeIf(Double::isFinite),
        avgReturn1M = avgReturn1M?.takeIf(Double::isFinite),
        leader = leader?.toModel(),
        members = members.map { it.toModel() }
    )
}

private fun SectorThemeMember.toDto(): SectorThemeMemberDto {
    return SectorThemeMemberDto(
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = currency,
        source = source,
        marketCap = marketCap?.takeIf(Double::isFinite),
        currentPrice = currentPrice?.takeIf(Double::isFinite),
        dailyChangePct = dailyChangePct?.takeIf(Double::isFinite),
        dailyChangeHorizon = dailyChangeHorizon,
        return1M = return1M?.takeIf(Double::isFinite),
        scoreValue = scoreValue?.takeIf(Double::isFinite),
        inPortfolio = inPortfolio,
        inSmallCap = inSmallCap
    )
}

private fun SectorThemeMemberDto.toModel(): SectorThemeMember {
    return SectorThemeMember(
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = currency,
        source = source,
        marketCap = marketCap?.takeIf(Double::isFinite),
        currentPrice = currentPrice?.takeIf(Double::isFinite),
        dailyChangePct = dailyChangePct?.takeIf(Double::isFinite),
        dailyChangeHorizon = dailyChangeHorizon,
        return1M = return1M?.takeIf(Double::isFinite),
        scoreValue = scoreValue?.takeIf(Double::isFinite),
        inPortfolio = inPortfolio,
        inSmallCap = inSmallCap
    )
}

private fun String.safeCacheSegment(): String {
    return trim()
        .ifBlank { "all" }
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9가-힣._-]+"), "_")
        .take(80)
}
