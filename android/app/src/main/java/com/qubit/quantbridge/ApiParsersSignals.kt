package com.qubit.quantbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun parseSmallCap(array: JSONArray): List<SmallCapStock> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val rank = o.cleanInt("Rank") ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            val market = o.cleanString("Market")
            add(
                SmallCapStock(
                    rank = rank,
                    previousRank = o.cleanFirstInt("Previous_Rank", "previous_rank"),
                    rankChange = o.cleanFirstInt("Rank_Change", "rank_change"),
                    rankStatus = o.cleanString("Rank_Status") ?: o.cleanString("rank_status"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    market = market,
                    marketCap = o.cleanDouble("MarketCap"),
                    currentPrice = o.cleanFirstDouble("Current_Price", "current_price", "Price", "Last_Price", "Close", "End_Price"),
                    return1M = o.cleanFirstDouble("Return_1M", "return_1m", "1M_Return", "Return_1m", "One_Month_Return", "Mom_1M"),
                    roic = o.cleanDouble("ROIC"),
                    revGrowth = o.cleanDouble("RevGrowth"),
                    revAccel = o.cleanDouble("Rev_Accel"),
                    grossMargin = o.cleanDouble("GrossMargin"),
                    fcfMargin = o.cleanDouble("FCF_Margin"),
                    debtEbitda = o.cleanDouble("Debt_EBITDA"),
                    volumeSurge = o.cleanDouble("Volume_Surge"),
                    smallCapBonus = o.cleanDouble("SmallCap_Bonus"),
                    totalScore = o.cleanDouble("Total_Score"),
                    lastUpdated = o.cleanString("Last_Updated") ?: o.cleanString("updated_at") ?: o.cleanString("generated_at"),
                    source = o.cleanString("Source") ?: o.cleanString("source"),
                    generatedAt = o.cleanString("generated_at") ?: o.cleanString("Generated_At")
                )
            )
        }
    }.map { normalizeKrSmallCapIdentity(it) }
}

fun parseEarnings(array: JSONArray): List<EarningsStock> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                EarningsStock(
                    rank = o.cleanInt("Rank"),
                    ticker = o.cleanString("Ticker") ?: continue,
                    name = o.cleanString("Name") ?: o.cleanString("Ticker") ?: "-",
                    sector = o.cleanString("Sector"),
                    marketCap = o.cleanDouble("MarketCap"),
                    earningsDate = o.cleanString("Earnings_Date"),
                    daysSince = o.cleanDouble("Days_Since_Earnings"),
                    surprisePct = o.cleanDouble("Surprise_Pct"),
                    returnSince = o.cleanDouble("Return_Since"),
                    volumeSurge = o.cleanDouble("Volume_Surge"),
                    signalStrength = o.cleanDouble("Signal_Strength")
                )
            )
        }
    }
}

fun parseEarningsCalendar(array: JSONArray): List<EarningsCalendarItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            val date = o.cleanString("Next_Earnings_Date") ?: continue
            val market = o.cleanString("Market") ?: marketCurrency(ticker, null).let { if (it == "KRW") "KR" else "US" }
            val rawName = o.cleanString("Name") ?: ticker
            add(
                EarningsCalendarItem(
                    ticker = ticker,
                    name = localizedCompanyName(ticker, rawName, market),
                    market = market,
                    sector = o.cleanString("Sector"),
                    marketCap = o.cleanDouble("MarketCap"),
                    nextEarningsDate = date,
                    daysUntil = o.cleanInt("Days_Until")
                )
            )
        }
    }
}

fun parseSignalEvents(array: JSONArray): List<SignalEvent> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            val eventId = o.cleanString("Event_ID") ?: "${o.cleanString("Source") ?: "event"}:$ticker:${o.cleanString("Kind") ?: i}"
            add(
                SignalEvent(
                    eventId = eventId,
                    market = o.cleanString("Market") ?: marketCurrency(ticker, null).let { if (it == "KRW") "KR" else "US" },
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    kind = o.cleanString("Kind") ?: "event",
                    severity = o.cleanInt("Severity") ?: 1,
                    title = o.cleanString("Title") ?: "신호",
                    detail = o.cleanString("Detail") ?: "",
                    metricLabel = o.cleanString("Metric_Label"),
                    metricValue = o.cleanString("Metric_Value"),
                    eventTime = o.cleanString("Event_Time"),
                    source = o.cleanString("Source"),
                    updatedAt = o.cleanString("Updated_At")
                )
            )
        }
    }
}

fun parseSectorThemes(array: JSONArray): List<SectorTheme> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val label = o.cleanString("label") ?: continue
            val members = parseSectorThemeMembers(o.optJSONArray("members") ?: JSONArray())
            val memberDailyChanges = members.mapNotNull { it.dailyChangePct?.takeIf(Double::isFinite) }
            val memberReturns = members.mapNotNull { it.return1M?.takeIf(Double::isFinite) }
            val risingCount = o.cleanFirstInt("rising_count", "risingCount", "up_count", "upCount")
                ?: memberDailyChanges.count { it > 0.0 }
            val fallingCount = o.cleanFirstInt("falling_count", "fallingCount", "down_count", "downCount")
                ?: memberDailyChanges.count { it < 0.0 }
            add(
                SectorTheme(
                    label = label,
                    market = o.cleanString("market") ?: "ALL",
                    memberCount = o.cleanFirstInt("member_count", "memberCount") ?: members.size,
                    pricedCount = o.cleanFirstInt("priced_count", "pricedCount") ?: memberDailyChanges.size,
                    risingCount = risingCount,
                    fallingCount = fallingCount,
                    avgChangePct = o.cleanFirstDouble(
                        "avg_change_pct",
                        "avgChangePct",
                        "weighted_change_pct",
                        "weightedChangePct",
                        "change_pct",
                        "changePct",
                        "daily_change_pct",
                        "dailyChangePct"
                    ) ?: memberDailyChanges.averageOrNull(),
                    avgReturn1M = o.cleanFirstDouble(
                        "avg_return_1m",
                        "avgReturn1M",
                        "return_1m",
                        "return1M",
                        "one_month_return",
                        "oneMonthReturn"
                    ) ?: memberReturns.averageOrNull(),
                    leader = o.optJSONObject("leader")?.let(::parseSectorThemeMember),
                    members = members
                )
            )
        }
    }
}

internal fun parseSectorThemeMembers(array: JSONArray): List<SectorThemeMember> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            parseSectorThemeMember(o)?.let(::add)
        }
    }
}

internal fun parseSectorThemeMember(o: JSONObject): SectorThemeMember? {
    val ticker = o.cleanString("Ticker") ?: o.cleanString("ticker") ?: return null
    val market = o.cleanString("Market") ?: o.cleanString("market")
    return SectorThemeMember(
        ticker = ticker,
        name = displayCompanyName(o.cleanString("Name") ?: o.cleanString("name") ?: ticker, ticker),
        market = market,
        sector = o.cleanString("Sector") ?: o.cleanString("sector"),
        currency = o.cleanString("Currency") ?: o.cleanString("currency") ?: marketCurrency(ticker, market),
        source = o.cleanString("Source") ?: o.cleanString("source"),
        marketCap = o.cleanFirstDouble("MarketCap", "market_cap", "marketCap"),
        currentPrice = o.cleanFirstDouble("Current_Price", "current_price", "currentPrice"),
        dailyChangePct = o.cleanFirstDouble("Daily_Change_Pct", "daily_change_pct", "dailyChangePct", "change_pct", "changePct"),
        dailyChangeHorizon = o.cleanString("Daily_Change_Horizon") ?: o.cleanString("daily_change_horizon"),
        return1M = o.cleanFirstDouble("Return_1M", "return_1m", "return1M", "avg_return_1m"),
        scoreValue = o.cleanFirstDouble("Score_Value", "score_value", "scoreValue"),
        inPortfolio = o.cleanBool("In_Portfolio") ?: o.cleanBool("in_portfolio") ?: false,
        inSmallCap = o.cleanBool("In_SmallCap") ?: o.cleanBool("in_small_cap") ?: false
    )
}

internal fun normalizeKrSmallCapIdentity(stock: SmallCapStock): SmallCapStock {
    if (!isKoreanTicker(stock.ticker, stock.market)) return stock
    val code = krCode(stock.ticker.ifBlank { stock.name })
    val normalizedName = if (isMissingKrName(stock.name, stock.ticker)) {
        code.ifBlank { stock.ticker }
    } else {
        stock.name.trim()
    }
    val normalizedTicker = when {
        hasKrSuffix(stock.ticker) -> stock.ticker.trim().uppercase(Locale.US)
        code.isNotBlank() -> code
        else -> stock.ticker.trim()
    }
    return stock.copy(ticker = normalizedTicker, name = normalizedName)
}
