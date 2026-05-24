package com.example.myapplication

import com.example.myapplication.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

@Singleton
class StockDetailRepository @Inject constructor(
    private val api: QuantApiService
) {
    suspend fun fetchStock(
        ticker: String,
        period: ChartPeriod = ChartPeriod.SixMonths,
        refresh: Boolean = false,
        profile: Boolean = false
    ): StockDetail {
        return api.getStockRaw(
            ticker = ticker,
            period = period.apiValue,
            refresh = refresh,
            profile = profile
        ).toDomain(ticker, period)
    }

    private fun JsonObject.toDomain(ticker: String, loadedPeriod: ChartPeriod): StockDetail {
        return StockDetail(
            prices = qbObjects("prices").mapNotNull { it.toPricePoint() },
            info = qbObject("info").toStockInfo(ticker),
            source = qbString("source"),
            updatedAt = qbString("updated_at", "updatedAt"),
            error = qbString("error"),
            loadedPeriod = loadedPeriod
        )
    }

    private fun JsonObject.toPricePoint(): PricePoint? {
        val date = qbString("date", "Date", "timestamp")
        val safeClose = qbDouble("close", "Close")
        if (date == null || safeClose == null) return null
        val safeOpen = qbDouble("open", "Open") ?: safeClose
        val highCandidate = qbDouble("high", "High") ?: maxOf(safeOpen, safeClose)
        val lowCandidate = qbDouble("low", "Low") ?: minOf(safeOpen, safeClose)
        return PricePoint(
            date = date,
            open = safeOpen,
            high = maxOf(highCandidate, safeOpen, safeClose),
            low = minOf(lowCandidate, safeOpen, safeClose),
            close = safeClose,
            volume = qbDouble("volume", "Volume")
        )
    }

    private fun JsonObject?.toStockInfo(ticker: String): StockInfo {
        val source = this
        return StockInfo(
            name = displayCompanyName(source?.qbString("name", "Name"), ticker),
            currentPrice = source?.qbDouble("current_price", "currentPrice"),
            prevClose = source?.qbDouble("prev_close", "prevClose"),
            dailyChangePct = source?.qbDouble("daily_change_pct", "dailyChangePct"),
            dailyChangeHorizon = source?.qbString("daily_change_horizon", "dailyChangeHorizon"),
            week52High = source?.qbDouble("week52_high", "week52High"),
            week52Low = source?.qbDouble("week52_low", "week52Low"),
            marketCap = source?.qbDouble("market_cap", "marketCap"),
            peRatio = source?.qbDouble("pe_ratio", "peRatio"),
            forwardPe = source?.qbDouble("forward_pe", "forwardPe"),
            priceToSales = source?.qbDouble("price_to_sales", "priceToSales"),
            priceToBook = source?.qbDouble("price_to_book", "priceToBook"),
            beta = source?.qbDouble("beta"),
            sector = source?.qbString("sector", "Sector"),
            industry = source?.qbString("industry", "Industry"),
            country = source?.qbString("country", "Country"),
            city = source?.qbString("city", "City"),
            exchange = source?.qbString("exchange", "Exchange"),
            website = source?.qbString("website", "Website"),
            employees = source?.qbInt("employees", "Employees"),
            totalRevenue = source?.qbDouble("total_revenue", "totalRevenue"),
            revenueGrowth = source?.qbDouble("revenue_growth", "revenueGrowth"),
            grossMargin = source?.qbDouble("gross_margin", "grossMargin"),
            operatingMargin = source?.qbDouble("operating_margin", "operatingMargin"),
            profitMargin = source?.qbDouble("profit_margin", "profitMargin"),
            ebitdaMargin = source?.qbDouble("ebitda_margin", "ebitdaMargin"),
            ebitda = source?.qbDouble("ebitda"),
            freeCashflow = source?.qbDouble("free_cashflow", "freeCashflow"),
            totalDebt = source?.qbDouble("total_debt", "totalDebt"),
            debtToEquity = source?.qbDouble("debt_to_equity", "debtToEquity"),
            returnOnEquity = source?.qbDouble("return_on_equity", "returnOnEquity"),
            targetMeanPrice = source?.qbDouble("target_mean_price", "targetMeanPrice"),
            recommendation = source?.qbString("recommendation"),
            description = source?.qbString("description")
        )
    }
}
