package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

data class PricePoint(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double?
)

data class StockInfo(
    val name: String?,
    val currentPrice: Double?,
    val prevClose: Double?,
    val dailyChangePct: Double?,
    val dailyChangeHorizon: String?,
    val week52High: Double?,
    val week52Low: Double?,
    val marketCap: Double?,
    val peRatio: Double?,
    val forwardPe: Double?,
    val priceToSales: Double?,
    val priceToBook: Double?,
    val beta: Double?,
    val sector: String?,
    val industry: String?,
    val country: String?,
    val city: String?,
    val exchange: String?,
    val website: String?,
    val employees: Int?,
    val totalRevenue: Double?,
    val revenueGrowth: Double?,
    val grossMargin: Double?,
    val operatingMargin: Double?,
    val profitMargin: Double?,
    val ebitdaMargin: Double?,
    val ebitda: Double?,
    val freeCashflow: Double?,
    val totalDebt: Double?,
    val debtToEquity: Double?,
    val returnOnEquity: Double?,
    val targetMeanPrice: Double?,
    val recommendation: String?,
    val description: String?
)

data class StockDetail(
    val prices: List<PricePoint>,
    val info: StockInfo,
    val source: String?,
    val updatedAt: String?,
    val error: String?,
    val loadedPeriod: ChartPeriod = ChartPeriod.SixMonths
)

@Serializable
data class BlindFinancialQuizResponse(
    val id: String,
    val title: String,
    val prompt: String,
    val market: String,
    @SerialName("as_of") val asOf: String? = null,
    val source: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("correct_option_id") val correctOptionId: String,
    @SerialName("answer_rule") val answerRule: String,
    val options: List<BlindQuizOption> = emptyList()
)

@Serializable
data class BlindQuizOption(
    val id: String,
    @SerialName("blind_label") val blindLabel: String,
    val thesis: String? = null,
    val metrics: List<BlindQuizMetric> = emptyList(),
    val company: BlindQuizCompany
)

@Serializable
data class BlindQuizMetric(
    val label: String,
    val value: String,
    val tone: String? = null
)

@Serializable
data class BlindQuizCompany(
    val ticker: String,
    val name: String,
    val market: String,
    val currency: String,
    val sector: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("price_points") val pricePoints: List<BlindQuizPricePoint> = emptyList(),
    @SerialName("three_year_return_pct") val threeYearReturnPct: Double? = null
)

@Serializable
data class BlindQuizPricePoint(
    val date: String,
    @SerialName("return_pct") val returnPct: Double
)

data class CachedStockDetail(
    val loadedAt: Long,
    val period: ChartPeriod,
    val detail: StockDetail
)

data class DetailRequest(
    val ticker: String,
    val name: String,
    val currency: String,
    val market: String?,
    val sections: List<DetailSection>,
    val signals: List<DetailSignal>,
    val factors: List<FactorScore>,
    val preferredTab: String = "overview",
    val holdings: List<DetailHolding> = emptyList()
)

data class DetailHolding(
    val ticker: String,
    val name: String,
    val weight: Double
)

data class DetailSection(val title: String, val metrics: List<DetailMetric>)

data class DetailMetric(
    val label: String,
    val value: String,
    val tone: DetailTone = DetailTone.Neutral
)

data class DetailSignal(
    val title: String,
    val detail: String,
    val tone: DetailTone = DetailTone.Neutral
)

data class FactorScore(val label: String, val value: Double)
