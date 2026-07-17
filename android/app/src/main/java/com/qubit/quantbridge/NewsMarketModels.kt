package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

data class NewsItem(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val url: String,
    val imageUrl: String,
    val publishedAt: String,
    val market: String,
    val ticker: String,
    val kind: String,
    val impactLabel: String,
    val impactLabelKo: String,
    val impactScore: Double,
    val impactReason: String,
    val impactScope: String,
    val impactHorizon: String,
    val impactConfidence: String,
    val relatedTickers: List<String>,
    val relatedChangePct: Double?,
    val relatedChangeLabel: String,
    val relatedChangeHorizon: String
)

data class MarketIndexQuote(
    val symbol: String,
    val label: String,
    val value: Double,
    val changeAbs: Double,
    val changePct: Double,
    val updatedAt: String
)

data class MarketIndicatorQuote(
    val symbol: String,
    val label: String,
    val category: String,
    val region: String,
    val value: Double,
    val changeAbs: Double?,
    val changePct: Double?,
    val updatedAt: String?
)

data class MarketIndicatorPoint(
    val timestamp: String,
    val close: Double
)

data class MarketIndicatorSeries(
    val symbol: String,
    val points: List<MarketIndicatorPoint>
)

data class NaverIndexSpec(
    val outputSymbol: String,
    val label: String,
    val lookupKeys: Set<String>
)
