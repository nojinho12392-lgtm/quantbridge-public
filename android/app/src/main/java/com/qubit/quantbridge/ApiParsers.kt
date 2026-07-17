package com.qubit.quantbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun parseSearchStocks(array: JSONArray): List<SearchStock> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            val market = o.cleanString("Market")
            val currency = o.cleanString("Currency") ?: marketCurrency(ticker, market)
            add(
                SearchStock(
                    rank = o.cleanInt("Rank"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    market = market,
                    sector = o.cleanString("Sector"),
                    marketCap = o.cleanDouble("MarketCap"),
                    inPortfolio = o.cleanBool("In_Portfolio") ?: false,
                    inSmallCap = o.cleanBool("In_SmallCap") ?: false,
                    currency = currency
                )
            )
        }
    }
}

fun parseEtfInsights(array: JSONArray): List<EtfInsight> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("ticker") ?: o.cleanString("Ticker") ?: continue
            add(
                EtfInsight(
                    ticker = ticker,
                    name = o.cleanString("name") ?: o.cleanString("Name") ?: ticker,
                    region = o.cleanString("region") ?: o.cleanString("Market") ?: "US",
                    category = o.cleanString("category") ?: o.cleanString("Category") ?: "기타",
                    theme = o.cleanString("theme") ?: o.cleanString("Theme") ?: "",
                    summary = o.cleanString("summary") ?: o.cleanString("Summary") ?: "",
                    expenseRatio = o.cleanString("expenseRatio") ?: o.cleanString("ExpenseRatio") ?: "",
                    aum = o.cleanString("aum") ?: o.cleanString("AUM") ?: "",
                    distribution = o.cleanString("distribution") ?: o.cleanString("Distribution") ?: "",
                    outlook = o.cleanString("outlook") ?: o.cleanString("Outlook") ?: "",
                    risk = o.cleanString("risk") ?: o.cleanString("Risk") ?: "",
                    holdings = parseEtfHoldings(o.optJSONArray("holdings") ?: o.optJSONArray("Holdings") ?: JSONArray()),
                    exposures = parseEtfExposures(o.optJSONArray("exposures") ?: o.optJSONArray("Exposures") ?: JSONArray()),
                    currentPrice = o.cleanDouble("currentPrice") ?: o.cleanDouble("Current_Price") ?: o.cleanDouble("current_price"),
                    return1M = o.cleanDouble("return1M") ?: o.cleanDouble("Return_1M") ?: o.cleanDouble("return_1m") ?: o.cleanDouble("1M_Return") ?: o.cleanDouble("Mom_1M"),
                    priceChange = o.cleanDouble("priceChange") ?: o.cleanDouble("Price_Change") ?: o.cleanDouble("price_change"),
                    source = o.cleanString("source") ?: o.cleanString("Source"),
                    updatedAt = o.cleanString("updated_at") ?: o.cleanString("Updated_At")
                )
            )
        }
    }
}

internal fun parseEtfHoldings(array: JSONArray): List<EtfHolding> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("ticker") ?: o.cleanString("Ticker") ?: continue
            add(
                EtfHolding(
                    ticker = ticker,
                    name = o.cleanString("name") ?: o.cleanString("Name") ?: ticker,
                    weight = o.cleanDouble("weight") ?: o.cleanDouble("Weight") ?: 0.0
                )
            )
        }
    }
}

internal fun parseEtfExposures(array: JSONArray): List<EtfExposure> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val label = o.cleanString("label") ?: o.cleanString("Label") ?: continue
            add(
                EtfExposure(
                    label = label,
                    weight = o.cleanDouble("weight") ?: o.cleanDouble("Weight") ?: 0.0
                )
            )
        }
    }
}

fun parseScoredStocks(array: JSONArray, defaultMarket: String): List<ScoredStock> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                ScoredStock(
                    rank = o.cleanInt("Rank"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    market = o.cleanString("Market") ?: defaultMarket,
                    sector = o.cleanString("Sector"),
                    marketCap = o.cleanDouble("MarketCap"),
                    valueScore = o.cleanDouble("Value_Score"),
                    qualityScore = o.cleanDouble("Quality_Score"),
                    momentumScore = o.cleanDouble("Momentum_Score"),
                    totalScore = o.cleanDouble("Total_Score"),
                    finalScore = o.cleanDouble("Final_Score"),
                    scoreNeutral = o.cleanDouble("Score_Neutral"),
                    mlScore = o.cleanDouble("ML_Score"),
                    combinedScore = o.cleanDouble("Combined_Score"),
                    roic = o.cleanDouble("ROIC"),
                    revGrowth = o.cleanDouble("RevGrowth"),
                    grossMargin = o.cleanDouble("GrossMargin"),
                    fcfMargin = o.cleanDouble("FCF_Margin"),
                    debtEbitda = o.cleanDouble("Debt_EBITDA"),
                    peg = o.cleanDouble("PEG"),
                    businessQualityScore = o.cleanDouble("Business_Quality_Score"),
                    investabilityScore = o.cleanDouble("Investability_Score"),
                    qualityDataConfidence = o.cleanDouble("Quality_Data_Confidence"),
                    qualityRedFlags = o.cleanString("Quality_Red_Flags"),
                    qualityCategory = o.cleanString("Quality_Category")
                )
            )
        }
    }
}
