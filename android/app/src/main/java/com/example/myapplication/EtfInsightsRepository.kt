package com.example.myapplication

import com.example.myapplication.network.QuantApiService
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

@Singleton
class EtfInsightsRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchEtfsResult(
        limit: Int = 500,
        query: String = "",
        refresh: Boolean = false
    ): EtfInsightsResult {
        val cleanQuery = query.trim()
        val response = api.getEtfInsightsRaw(
            query = cleanQuery,
            limit = limit,
            refresh = refresh || cleanQuery.isNotBlank()
        )
        val fallbackUpdatedAt = response.qbString("updated_at", "generated_at")
        val source = response.qbString("source") ?: "api"
        val parsed = response.qbObjects("items").mapNotNull { it.toDomain(source, fallbackUpdatedAt) }
        return EtfInsightsResult(
            items = enrichEtfPrices(parsed),
            source = source,
            updatedAt = fallbackUpdatedAt
        )
    }

    private suspend fun enrichEtfPrices(items: List<EtfInsight>): List<EtfInsight> = coroutineScope {
        val missing = items.filter { it.currentPrice == null || it.return1M == null }
        if (missing.isEmpty()) return@coroutineScope items

        val usMetrics = async { fetchEtfPriceMetrics(missing.filter { it.region == "US" }, "US") }
        val krMetrics = async { fetchEtfPriceMetrics(missing.filter { it.region == "KR" }, "KR") }
        val metrics = usMetrics.await() + krMetrics.await()
        if (metrics.isEmpty()) return@coroutineScope items

        items.map { etf ->
            val metric = etf.priceLookupTickers
                .asSequence()
                .map { it.uppercase(Locale.US) }
                .mapNotNull(metrics::get)
                .firstOrNull { it.currentPrice != null || it.return1M != null }
            if (metric == null) {
                etf
            } else {
                etf.copy(
                    currentPrice = etf.currentPrice ?: metric.currentPrice,
                    return1M = etf.return1M ?: metric.return1M,
                    dailyChangePct = etf.dailyChangePct ?: metric.dailyChangePct,
                    dailyPriceChange = etf.dailyPriceChange ?: dailyPriceChangeFromReturn(
                        etf.currentPrice ?: metric.currentPrice,
                        etf.dailyChangePct ?: metric.dailyChangePct
                    ),
                    dailyChangeHorizon = etf.dailyChangeHorizon ?: metric.dailyChangeHorizon,
                    updatedAt = etf.updatedAt ?: metric.updatedAt
                )
            }
        }
    }

    private suspend fun fetchEtfPriceMetrics(
        items: List<EtfInsight>,
        market: String
    ): Map<String, EtfRepositoryPriceMetric> {
        val tickers = items
            .flatMap { it.priceLookupTickers }
            .map { it.uppercase(Locale.US) }
            .distinct()
            .take(100)
        if (tickers.isEmpty()) return emptyMap()

        return runCatching {
            api.getStockPriceMetrics(
                market = market,
                tickers = tickers.joinToString(","),
                limit = tickers.size,
                refresh = false
            ).metrics.orEmpty().mapNotNull { row ->
                val ticker = row.ticker?.uppercase(Locale.US) ?: return@mapNotNull null
                ticker to EtfRepositoryPriceMetric(
                    ticker = ticker,
                    currentPrice = row.currentPrice?.takeIf(Double::isFinite),
                    return1M = row.return1M?.takeIf(Double::isFinite),
                    dailyChangePct = row.dailyChangePct?.takeIf(Double::isFinite),
                    dailyChangeHorizon = row.dailyChangeHorizon,
                    updatedAt = row.priceUpdatedAt
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun JsonObject.toDomain(source: String, fallbackUpdatedAt: String?): EtfInsight? {
        val ticker = qbString("ticker", "Ticker")?.takeIf { it.isNotBlank() } ?: return null
        val fallback = fallbackEtf(ticker)
        val region = qbString("region", "Market", "market") ?: fallback?.region ?: "US"
        val category = qbString("category", "Category") ?: fallback?.category ?: "기타"
        val theme = qbString("theme", "Theme") ?: fallback?.theme ?: category
        val holdings = qbHoldings("holdings", "Holdings", "topHoldings").ifEmpty { fallback?.holdings.orEmpty() }
        val exposures = qbExposures("exposures", "Exposures").ifEmpty { fallback?.exposures.orEmpty() }
        val name = cleanEtfName(qbString("name", "Name") ?: fallback?.name ?: ticker, ticker)
        val currentPrice = qbDouble("currentPrice", "Current_Price", "current_price", "price")
        val return1M = qbDouble("return1M", "Return_1M", "return_1m", "changePct")
        val dailyChangePct = qbDouble("dailyChangePct", "Daily_Change_Pct", "daily_change_pct")
        val dailyPriceChange = qbDouble("dailyPriceChange", "Daily_Price_Change", "daily_price_change")
            ?: dailyPriceChangeFromReturn(currentPrice, dailyChangePct)
        return EtfInsight(
            ticker = ticker,
            name = name,
            region = region,
            category = category,
            theme = theme,
            summary = qbString("summary", "Summary") ?: fallback?.summary ?: "$name 가격과 구성 정보를 확인하세요.",
            expenseRatio = qbString("expenseRatio", "ExpenseRatio") ?: fallback?.expenseRatio ?: "-",
            aum = qbString("aum", "AUM") ?: fallback?.aum ?: "-",
            distribution = qbString("distribution", "Distribution") ?: fallback?.distribution ?: "-",
            outlook = qbString("outlook", "Outlook") ?: fallback?.outlook ?: "가격 흐름과 구성종목 변화를 함께 확인하세요.",
            risk = qbString("risk", "Risk") ?: fallback?.risk ?: "ETF는 추종 지수, 환율, 구성 종목 집중도에 따라 변동될 수 있습니다.",
            holdings = holdings,
            exposures = exposures,
            currentPrice = currentPrice,
            return1M = return1M,
            priceChange = qbDouble("priceChange", "Price_Change", "price_change") ?: priceChangeFromReturn(currentPrice, return1M),
            dailyChangePct = dailyChangePct,
            dailyPriceChange = dailyPriceChange,
            dailyChangeHorizon = qbString("dailyChangeHorizon", "Daily_Change_Horizon", "daily_change_horizon"),
            source = qbString("priceSource", "source", "Source") ?: source,
            updatedAt = qbString("priceUpdatedAt", "updatedAt", "updated_at", "Updated_At") ?: fallbackUpdatedAt
        )
    }

    private fun JsonObject.qbHoldings(vararg keys: String): List<EtfHolding> {
        return qbObjects(*keys).mapNotNull { item ->
            val ticker = item.qbString("ticker", "Ticker") ?: return@mapNotNull null
            EtfHolding(
                ticker = ticker,
                name = cleanEtfName(item.qbString("name", "Name") ?: ticker, ticker),
                weight = item.qbDouble("weight", "Weight") ?: 0.0
            )
        }
    }

    private fun JsonObject.qbExposures(vararg keys: String): List<EtfExposure> {
        return qbObjects(*keys).mapNotNull { item ->
            val label = item.qbString("label", "Label") ?: return@mapNotNull null
            EtfExposure(
                label = label,
                weight = item.qbDouble("weight", "Weight") ?: 0.0
            )
        }
    }

    private fun fallbackEtf(ticker: String): EtfInsight? {
        val key = ticker.uppercase(Locale.US)
        return etfInsightUniverse.firstOrNull {
            it.ticker.uppercase(Locale.US) == key || it.priceTicker.uppercase(Locale.US) == key
        }
    }

    private fun cleanEtfName(rawName: String, ticker: String): String {
        val cleaned = displayCompanyName(rawName, ticker)
        return if (cleaned.endsWith("기업") && cleaned.startsWith(ticker, ignoreCase = true)) {
            ticker
        } else {
            cleaned
        }
    }

    private fun priceChangeFromReturn(price: Double?, change: Double?): Double? {
        val current = price?.takeIf(Double::isFinite) ?: return null
        val pct = change?.takeIf { it.isFinite() && it > -0.999 } ?: return null
        return current - current / (1.0 + pct)
    }

    private fun dailyPriceChangeFromReturn(price: Double?, change: Double?): Double? {
        return priceChangeFromReturn(price, change)
    }
}

private data class EtfRepositoryPriceMetric(
    val ticker: String,
    val currentPrice: Double?,
    val return1M: Double?,
    val dailyChangePct: Double?,
    val dailyChangeHorizon: String?,
    val updatedAt: String?
)
