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

private fun parseEtfHoldings(array: JSONArray): List<EtfHolding> {
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

private fun parseEtfExposures(array: JSONArray): List<EtfExposure> {
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

fun parseQualityGates(array: JSONArray): List<QualityGate> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                QualityGate(
                    market = o.cleanString("Market") ?: "-",
                    factor = o.cleanString("Factor") ?: o.cleanString("Signal") ?: "-",
                    status = o.cleanString("Status") ?: "UNKNOWN",
                    meanIc = o.cleanDouble("Mean_IC"),
                    positiveRate = o.cleanDouble("Positive_IC_Rate"),
                    snapshots = o.cleanDouble("Snapshots"),
                    evidenceSource = o.cleanString("Evidence_Source"),
                    productionReady = o.cleanString("Production_Ready")
                )
            )
        }
    }
}

fun parseMLBlendItems(array: JSONArray): List<MLBlendItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(parseMLBlendItem(o))
        }
    }
}

fun parseMLBlendItem(o: JSONObject): MLBlendItem {
    return MLBlendItem(
        generated = o.cleanString("Generated") ?: "",
        market = o.cleanString("Market") ?: "-",
        model = o.cleanString("Model") ?: "-",
        rankIc = o.cleanDouble("Rank_IC"),
        mlWeight = o.cleanDouble("ML_Weight"),
        factorWeight = o.cleanDouble("Factor_Weight"),
        mlWeightReason = o.cleanString("ML_Weight_Reason"),
        factorScoreColumn = o.cleanString("Factor_Score_Column"),
        mlFactorSpearman = o.cleanDouble("ML_Factor_Spearman"),
        mlFactorPearson = o.cleanDouble("ML_Factor_Pearson"),
        predictedStocks = o.cleanDouble("Predicted_Stocks"),
        top5 = o.cleanString("Top5"),
        notes = o.cleanString("Notes"),
        status = o.cleanString("Status")
    )
}

fun parseOpsChecks(array: JSONArray): List<OpsCheck> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                OpsCheck(
                    name = o.cleanString("name") ?: "-",
                    status = o.cleanString("status") ?: "UNKNOWN",
                    message = o.cleanString("message") ?: ""
                )
            )
        }
    }
}

fun parseBacktestSummary(o: JSONObject): BacktestSummary {
    return BacktestSummary(
        market = o.cleanString("Market") ?: "-",
        sheet = o.cleanString("Sheet") ?: "-",
        periods = o.cleanInt("Periods") ?: 0,
        latestDate = o.cleanString("Latest_Date") ?: "",
        cumulativeReturn = o.cleanDouble("Cumulative_Ret"),
        maxDrawdown = o.cleanDouble("Max_Drawdown"),
        avgReturn = o.cleanDouble("Avg_Return")
    )
}

fun parseDriftItems(array: JSONArray): List<DriftItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                DriftItem(
                    market = o.cleanString("Market") ?: "-",
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    status = o.cleanString("Status") ?: o.cleanString("Recommendation") ?: "UNKNOWN",
                    driftAbs = o.cleanDouble("Drift_Abs"),
                    targetWeight = o.cleanDouble("Target_Weight"),
                    currentWeight = o.cleanDouble("Current_Weight"),
                    returnSinceRebal = o.cleanDouble("Return_Since_Rebal")
                )
            )
        }
    }
}

fun parseIndustryItems(array: JSONArray): List<IndustryItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                IndustryItem(
                    rank = o.cleanInt("Rank"),
                    industry = o.cleanString("Industry") ?: "-",
                    stockCount = o.cleanInt("Stock_Count"),
                    meanReturn = o.cleanDouble("Mean_Return"),
                    breadth = o.cleanDouble("Breadth")
                )
            )
        }
    }
}

fun parseOrderFlowItems(array: JSONArray): List<OrderFlowItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                OrderFlowItem(
                    rank = o.cleanInt("Rank"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    consecutiveDays = o.cleanInt("Consecutive_Days"),
                    foreignNetBuy = o.cleanDouble("Foreign_Net_Buy"),
                    instNetBuy = o.cleanDouble("Inst_Net_Buy")
                )
            )
        }
    }
}

fun parseRiskHoldings(array: JSONArray): List<RiskHolding> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                RiskHolding(
                    market = o.cleanString("Market"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    sector = o.cleanString("Sector"),
                    portfolioWeight = o.cleanDouble("Portfolio_Weight"),
                    assetVol = o.cleanDouble("Asset_Vol"),
                    riskContributionPct = o.cleanDouble("Risk_Contribution_Pct"),
                    weightRiskRatio = o.cleanDouble("Weight_Risk_Ratio")
                )
            )
        }
    }
}

fun parseRiskSectors(array: JSONArray): List<RiskSector> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val sector = o.cleanString("Sector") ?: continue
            add(
                RiskSector(
                    market = o.cleanString("Market"),
                    sector = sector,
                    holdings = o.cleanDouble("Holdings"),
                    sectorWeight = o.cleanDouble("Sector_Weight"),
                    riskContributionPct = o.cleanDouble("Sector_Risk_Contribution_Pct")
                )
            )
        }
    }
}

fun parseRebalanceOrders(array: JSONArray): List<RebalanceOrder> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                RebalanceOrder(
                    market = o.cleanString("Market"),
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    action = o.cleanString("Action") ?: "HOLD",
                    currentWeight = o.cleanDouble("Current_Weight"),
                    targetWeight = o.cleanDouble("Target_Weight"),
                    deltaWeight = o.cleanDouble("Delta_Weight"),
                    executableTradeValue = o.cleanDouble("Executable_Trade_Value"),
                    costEstimate = o.cleanDouble("Cost_Est")
                )
            )
        }
    }
}

fun parseShadowAttributionSummaries(array: JSONArray): List<ShadowAttributionSummary> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(
                ShadowAttributionSummary(
                    market = o.cleanString("Market") ?: "-",
                    horizonTradingDays = o.cleanDouble("Horizon_Trading_Days"),
                    actualReturn = o.cleanDouble("Actual_Return"),
                    benchmarkReturn = o.cleanDouble("Benchmark_Return"),
                    alphaActual = o.cleanDouble("Alpha_Actual"),
                    hitRate = o.cleanDouble("Hit_Rate"),
                    scoreReturnIc = o.cleanDouble("Score_Return_IC")
                )
            )
        }
    }
}

fun parseShadowAttributionItems(array: JSONArray): List<ShadowAttributionItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val ticker = o.cleanString("Ticker") ?: continue
            add(
                ShadowAttributionItem(
                    market = o.cleanString("Market") ?: "-",
                    ticker = ticker,
                    name = o.cleanString("Name") ?: ticker,
                    horizonTradingDays = o.cleanDouble("Horizon_Trading_Days"),
                    weight = o.cleanDouble("Weight"),
                    stockReturn = o.cleanDouble("Stock_Return"),
                    benchmarkReturn = o.cleanDouble("Benchmark_Return"),
                    actualContribution = o.cleanDouble("Actual_Contribution"),
                    excessContribution = o.cleanDouble("Excess_Contribution")
                )
            )
        }
    }
}

fun parseNewsItems(array: JSONArray): List<NewsItem> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val title = o.cleanString("title") ?: continue
            add(
                NewsItem(
                    id = o.cleanString("id") ?: "$i:$title",
                    title = title,
                    summary = o.cleanString("summary") ?: "",
                    source = o.cleanString("source") ?: "-",
                    url = o.cleanString("url") ?: "",
                    imageUrl = firstCleanString(o, "image_url", "urlToImage", "image", "thumbnail", "thumbnail_url") ?: "",
                    publishedAt = o.cleanString("published_at") ?: "",
                    market = o.cleanString("market") ?: "",
                    ticker = o.cleanString("ticker") ?: "",
                    kind = o.cleanString("kind") ?: "news",
                    impactLabel = o.cleanString("impact_label") ?: "neutral",
                    impactLabelKo = o.cleanString("impact_label_ko") ?: impactFallbackLabel(o.cleanString("impact_label")),
                    impactScore = o.cleanDouble("impact_score") ?: 0.0,
                    impactReason = o.cleanString("impact_reason") ?: "",
                    impactScope = o.cleanString("impact_scope") ?: "general",
                    impactHorizon = o.cleanString("impact_horizon") ?: "단기",
                    impactConfidence = o.cleanString("impact_confidence") ?: "low",
                    relatedTickers = parseStringArray(o.optJSONArray("related_tickers")),
                    relatedChangePct = o.cleanDouble("related_change_pct"),
                    relatedChangeLabel = o.cleanString("related_change_label") ?: "",
                    relatedChangeHorizon = o.cleanString("related_change_horizon") ?: ""
                )
            )
        }
    }
}

private fun parseStringArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun firstCleanString(o: JSONObject, vararg keys: String): String? {
    for (key in keys) {
        o.cleanString(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun impactFallbackLabel(label: String?): String {
    return when (label?.lowercase(Locale.US)) {
        "positive" -> "긍정"
        "negative" -> "부정"
        else -> "중립"
    }
}

fun defaultNewsQuery(market: String): String {
    return when (market.uppercase(Locale.US)) {
        "US" -> "뉴욕증시 나스닥 S&P500 미국 주식 엔비디아 테슬라 애플"
        "KR" -> "국내증시 코스피 코스닥 삼성전자 SK하이닉스"
        else -> "증시 주식 실적 반도체 환율"
    }
}

fun List<NewsItem>.filterNewsForMarket(market: String): List<NewsItem> {
    val safeMarket = market.uppercase(Locale.US)
    val articles = filter { item ->
        item.kind.equals("external_news", ignoreCase = true) && item.url.isNotBlank()
    }
    if (safeMarket == "ALL") return articles
    return articles.filter { item -> item.market.equals(safeMarket, ignoreCase = true) }
}

private fun String.matchesNewsMarket(market: String): Boolean {
    val text = lowercase(Locale.US)
    return when (market.uppercase(Locale.US)) {
        "US" -> {
            val excludes = listOf("삼성전자", "sk하이닉스", "코스피", "코스닥", "국내 증시", "한국 증시", "현대차")
            if (excludes.any { text.contains(it.lowercase(Locale.US)) }) return false
            val includes = listOf(
                "미국", "뉴욕증시", "미 증시", "미증시", "나스닥", "s&p", "다우", "월가",
                "엔비디아", "nvidia", "테슬라", "애플", "apple", "마이크로소프트",
                "microsoft", "알파벳", "amazon", "아마존", "연준", "fed"
            )
            includes.any { text.contains(it.lowercase(Locale.US)) }
        }
        "KR" -> {
            val includes = listOf("국내 증시", "한국 증시", "코스피", "코스닥", "삼성전자", "sk하이닉스", "현대차", "외국인", "기관", "한국거래소")
            includes.any { text.contains(it.lowercase(Locale.US)) }
        }
        else -> true
    }
}

fun parseMarketIndices(array: JSONArray): List<MarketIndexQuote> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.cleanString("symbol") ?: continue
            val label = o.cleanString("label") ?: symbol
            val value = o.cleanDouble("value") ?: continue
            val changePct = o.cleanDouble("change_pct") ?: continue
            add(
                MarketIndexQuote(
                    symbol = symbol,
                    label = label,
                    value = value,
                    changeAbs = o.cleanDouble("change_abs") ?: 0.0,
                    changePct = changePct,
                    updatedAt = o.cleanString("updated_at") ?: ""
                )
            )
        }
    }
}

fun MarketIndexQuote.toIndicatorQuote(): MarketIndicatorQuote {
    val region = when (symbol) {
        "^KS11", "^KQ11", "KRW=X" -> "domestic"
        else -> "overseas"
    }
    return MarketIndicatorQuote(
        symbol = symbol,
        label = label,
        category = "index_fx",
        region = region,
        value = value,
        changeAbs = changeAbs,
        changePct = changePct,
        updatedAt = updatedAt
    )
}

fun MarketIndicatorQuote.toMarketIndexQuote(): MarketIndexQuote {
    return MarketIndexQuote(
        symbol = symbol,
        label = label,
        value = value,
        changeAbs = changeAbs ?: 0.0,
        changePct = changePct ?: 0.0,
        updatedAt = updatedAt.orEmpty()
    )
}

fun parseMarketIndicators(array: JSONArray): List<MarketIndicatorQuote> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.cleanString("symbol") ?: continue
            val value = o.cleanDouble("value") ?: continue
            add(
                MarketIndicatorQuote(
                    symbol = symbol,
                    label = o.cleanString("label") ?: symbol,
                    category = o.cleanString("category") ?: "index_fx",
                    region = o.cleanString("region") ?: "global",
                    value = value,
                    changeAbs = o.cleanDouble("change_abs"),
                    changePct = o.cleanDouble("change_pct"),
                    updatedAt = o.cleanString("updated_at")
                )
            )
        }
    }
}

fun parseMarketIndicatorSeries(array: JSONArray): List<MarketIndicatorSeries> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.cleanString("symbol") ?: continue
            val pointsArray = o.optJSONArray("points") ?: JSONArray()
            val points = buildList {
                for (j in 0 until pointsArray.length()) {
                    val p = pointsArray.optJSONObject(j) ?: continue
                    val close = p.cleanDouble("close") ?: continue
                    add(MarketIndicatorPoint(p.cleanString("timestamp") ?: "", close))
                }
            }
            add(MarketIndicatorSeries(symbol, points))
        }
    }
}

fun JSONObject.matches(spec: NaverIndexSpec): Boolean {
    return listOfNotNull(
        cleanString("symbolCode"),
        cleanString("reutersCode"),
        cleanString("itemCode")
    ).any { it in spec.lookupKeys }
}

fun JSONObject.toMarketIndexQuote(spec: NaverIndexSpec): MarketIndexQuote? {
    val value = cleanDouble("closePriceRaw") ?: cleanDouble("closePrice") ?: return null
    val changePct = cleanDouble("fluctuationsRatioRaw") ?: cleanDouble("fluctuationsRatio") ?: return null
    return MarketIndexQuote(
        symbol = spec.outputSymbol,
        label = spec.label,
        value = value,
        changeAbs = cleanDouble("compareToPreviousClosePriceRaw") ?: cleanDouble("compareToPreviousClosePrice") ?: 0.0,
        changePct = changePct / 100.0,
        updatedAt = cleanString("localTradedAt") ?: ""
    )
}

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

private fun parseSectorThemeMembers(array: JSONArray): List<SectorThemeMember> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            parseSectorThemeMember(o)?.let(::add)
        }
    }
}

private fun parseSectorThemeMember(o: JSONObject): SectorThemeMember? {
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

private fun List<Double>.averageOrNull(): Double? {
    val clean = filter(Double::isFinite)
    if (clean.isEmpty()) return null
    return clean.average()
}

fun parseUser(o: JSONObject): AuthUser {
    return AuthUser(
        id = o.cleanString("id") ?: "",
        email = o.cleanString("email") ?: "",
        displayName = o.cleanString("display_name") ?: o.cleanString("displayName") ?: "",
        createdAt = o.cleanString("created_at") ?: ""
    )
}

fun jsonToMap(json: JSONObject): Map<String, String> {
    val map = linkedMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        map[key] = json.cleanString(key).orEmpty()
    }
    return map
}

fun JSONObject.cleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)?.toString()?.trim() ?: return null
    return value.takeUnless { it.isBlank() || it.equals("nan", true) || it.equals("null", true) }
}

fun JSONObject.cleanStringList(vararg keys: String): List<String> {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        val raw = opt(key)
        val values = when (raw) {
            is JSONArray -> buildList {
                for (i in 0 until raw.length()) {
                    raw.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            else -> raw?.toString()
                ?.split(",", "|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
        if (values.isNotEmpty()) return values.distinct()
    }
    return emptyList()
}

fun JSONObject.cleanDouble(key: String): Double? {
    val raw = cleanString(key) ?: return null
    val clean = raw
        .replace(",", "")
        .replace("$", "")
        .replace("₩", "")
        .replace("원", "")
        .trim()
    val value = clean.removeSuffix("%").toDoubleOrNull() ?: return null
    if (value.isNaN() || value.isInfinite()) return null
    if (clean.endsWith("%")) return value / 100.0
    return value
}

fun JSONObject.cleanFirstDouble(vararg keys: String): Double? {
    for (key in keys) {
        cleanDouble(key)?.let { return it }
    }
    return null
}

fun JSONObject.cleanInt(key: String): Int? = cleanDouble(key)?.toInt()

fun JSONObject.cleanFirstInt(vararg keys: String): Int? {
    for (key in keys) {
        cleanInt(key)?.let { return it }
    }
    return null
}

fun JSONObject.cleanBool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        else -> (raw?.toString() ?: return null).trim().lowercase(Locale.US).let {
            when (it) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> null
            }
        }
    }
}

private fun normalizeKrSmallCapIdentity(stock: SmallCapStock): SmallCapStock {
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
