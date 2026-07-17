package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

data class PortfolioStock(
    val rank: Int?,
    val previousRank: Int?,
    val rankChange: Int?,
    val rankStatus: String?,
    val ticker: String,
    val name: String,
    val market: String?,
    val sector: String?,
    val marketCap: Double?,
    val weight: Double?,
    val currentPrice: Double?,
    val return1M: Double?,
    val totalScore: Double?,
    val roic: Double?,
    val revGrowth: Double?,
    val grossMargin: Double?,
    val expectedReturn: Double?,
    val lastUpdated: String?,
    val source: String?,
    val generatedAt: String?
)

data class SmallCapStock(
    val rank: Int?,
    val previousRank: Int?,
    val rankChange: Int?,
    val rankStatus: String?,
    val ticker: String,
    val name: String,
    val market: String?,
    val marketCap: Double?,
    val currentPrice: Double?,
    val return1M: Double?,
    val roic: Double?,
    val revGrowth: Double?,
    val revAccel: Double?,
    val grossMargin: Double?,
    val fcfMargin: Double?,
    val debtEbitda: Double?,
    val volumeSurge: Double?,
    val smallCapBonus: Double?,
    val totalScore: Double?,
    val lastUpdated: String?,
    val source: String?,
    val generatedAt: String?
)

data class SearchStock(
    val rank: Int?,
    val ticker: String,
    val name: String,
    val market: String?,
    val sector: String?,
    val marketCap: Double?,
    val inPortfolio: Boolean,
    val inSmallCap: Boolean,
    val currency: String
)

data class StockPriceMetric(
    val ticker: String,
    val currentPrice: Double?,
    val return1M: Double?,
    val dailyChangePct: Double?,
    val dailyChangeHorizon: String?,
    val updatedAt: String?
)

data class SectorTheme(
    val label: String,
    val market: String,
    val memberCount: Int,
    val pricedCount: Int,
    val risingCount: Int,
    val fallingCount: Int,
    val avgChangePct: Double?,
    val avgReturn1M: Double?,
    val leader: SectorThemeMember?,
    val members: List<SectorThemeMember>
)

data class SectorThemesResult(
    val items: List<SectorTheme>,
    val market: String,
    val source: String?,
    val generatedAt: String?
)

data class SectorThemeMember(
    val ticker: String,
    val name: String,
    val market: String?,
    val sector: String?,
    val currency: String?,
    val source: String?,
    val marketCap: Double?,
    val currentPrice: Double?,
    val dailyChangePct: Double?,
    val dailyChangeHorizon: String?,
    val return1M: Double?,
    val scoreValue: Double?,
    val inPortfolio: Boolean,
    val inSmallCap: Boolean
)

data class ScoredStock(
    val rank: Int?,
    val ticker: String,
    val name: String,
    val market: String?,
    val sector: String?,
    val marketCap: Double?,
    val valueScore: Double?,
    val qualityScore: Double?,
    val momentumScore: Double?,
    val totalScore: Double?,
    val finalScore: Double?,
    val scoreNeutral: Double?,
    val mlScore: Double?,
    val combinedScore: Double?,
    val roic: Double?,
    val revGrowth: Double?,
    val grossMargin: Double?,
    val fcfMargin: Double?,
    val debtEbitda: Double?,
    val peg: Double?,
    val businessQualityScore: Double? = null,
    val investabilityScore: Double? = null,
    val qualityDataConfidence: Double? = null,
    val qualityRedFlags: String? = null,
    val qualityCategory: String? = null
)

data class EarningsStock(
    val rank: Int?,
    val ticker: String,
    val name: String,
    val sector: String?,
    val marketCap: Double?,
    val earningsDate: String?,
    val daysSince: Double?,
    val surprisePct: Double?,
    val returnSince: Double?,
    val volumeSurge: Double?,
    val signalStrength: Double?
)

data class EarningsCalendarItem(
    val ticker: String,
    val name: String,
    val market: String,
    val sector: String?,
    val marketCap: Double?,
    val nextEarningsDate: String,
    val daysUntil: Int?
)

data class SignalEvent(
    val eventId: String,
    val market: String,
    val ticker: String,
    val name: String,
    val kind: String,
    val severity: Int,
    val title: String,
    val detail: String,
    val metricLabel: String?,
    val metricValue: String?,
    val eventTime: String?,
    val source: String?,
    val updatedAt: String?
)
