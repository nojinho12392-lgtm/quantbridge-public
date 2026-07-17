package com.qubit.quantbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun parsePortfolio(array: JSONArray): List<PortfolioStock> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                PortfolioStock(
                    rank = o.cleanInt("Rank"),
                    previousRank = o.cleanFirstInt("Previous_Rank", "previous_rank"),
                    rankChange = o.cleanFirstInt("Rank_Change", "rank_change"),
                    rankStatus = o.cleanString("Rank_Status") ?: o.cleanString("rank_status"),
                    ticker = o.cleanString("Ticker") ?: continue,
                    name = o.cleanString("Name") ?: o.cleanString("Ticker") ?: "-",
                    market = o.cleanString("Market"),
                    sector = o.cleanString("Sector"),
                    marketCap = o.cleanDouble("MarketCap"),
                    weight = o.cleanDouble("Weight(%)"),
                    currentPrice = o.cleanFirstDouble("Current_Price", "current_price", "Price", "Last_Price", "Close", "End_Price"),
                    return1M = o.cleanFirstDouble("Return_1M", "return_1m", "1M_Return", "Return_1m", "One_Month_Return", "Mom_1M"),
                    totalScore = o.cleanDouble("Total_Score"),
                    roic = o.cleanDouble("ROIC"),
                    revGrowth = o.cleanDouble("RevGrowth"),
                    grossMargin = o.cleanDouble("GrossMargin"),
                    expectedReturn = o.cleanDouble("Expected_Return"),
                    lastUpdated = o.cleanString("Last_Updated") ?: o.cleanString("updated_at") ?: o.cleanString("generated_at"),
                    source = o.cleanString("Source") ?: o.cleanString("source"),
                    generatedAt = o.cleanString("generated_at") ?: o.cleanString("Generated_At")
                )
            )
        }
    }
}

fun parseWatchlist(array: JSONArray): List<WatchlistItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("ticker") ?: continue
            add(
                WatchlistItem(
                    ticker = ticker,
                    name = o.cleanString("name") ?: ticker,
                    market = o.cleanString("market") ?: "US",
                    currency = o.cleanString("currency") ?: "USD",
                    note = o.cleanString("note") ?: "Watchlist",
                    addedAt = o.cleanString("added_at") ?: o.cleanString("addedAt") ?: "",
                    tags = o.cleanStringList("tags"),
                    memo = o.cleanString("memo") ?: "",
                    alertOptions = o.cleanStringList("alert_options", "alertOptions")
                )
            )
        }
    }
}

fun parseStockPriceMetrics(array: JSONArray): List<StockPriceMetric> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: o.cleanString("ticker") ?: continue
            add(
                StockPriceMetric(
                    ticker = ticker,
                    currentPrice = o.cleanDouble("Current_Price") ?: o.cleanDouble("current_price"),
                    return1M = o.cleanDouble("Return_1M") ?: o.cleanDouble("return_1m"),
                    dailyChangePct = o.cleanDouble("Daily_Change_Pct") ?: o.cleanDouble("daily_change_pct"),
                    dailyChangeHorizon = o.cleanString("Daily_Change_Horizon") ?: o.cleanString("daily_change_horizon"),
                    updatedAt = o.cleanString("Price_Updated_At") ?: o.cleanString("updated_at")
                )
            )
        }
    }
}

fun parseUser(o: JSONObject): AuthUser {
    return AuthUser(
        id = o.cleanString("id") ?: "",
        email = o.cleanString("email") ?: "",
        displayName = o.cleanString("display_name") ?: o.cleanString("displayName") ?: "",
        createdAt = o.cleanString("created_at") ?: ""
    )
}
