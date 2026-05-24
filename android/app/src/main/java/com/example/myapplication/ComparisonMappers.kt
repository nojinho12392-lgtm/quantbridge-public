package com.example.myapplication

fun PortfolioStock.toComparisonItem(currency: String = marketCurrency(ticker, market)): StockComparisonItem {
    return StockComparisonItem(
        id = "portfolio-${normalizedTicker(ticker)}",
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = currency,
        source = "Portfolio",
        scoreValue = totalScore,
        scoreText = score(totalScore),
        expectedReturn = expectedReturn,
        revenueGrowth = revGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = currentPrice,
        return1M = return1M,
        rankChange = rankChange,
        weight = weight,
        fcfMargin = null,
        volumeSurge = null,
        updatedAt = lastUpdated
    )
}

fun SmallCapStock.toComparisonItem(): StockComparisonItem {
    return StockComparisonItem(
        id = "smallcap-${normalizedTicker(ticker)}",
        ticker = ticker,
        name = name,
        market = market,
        sector = "스몰캡",
        currency = marketCurrency(ticker, market),
        source = "스몰캡",
        scoreValue = totalScore,
        scoreText = totalScore?.let { "%.0f점".format(it) } ?: "-",
        expectedReturn = null,
        revenueGrowth = revGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = currentPrice,
        return1M = return1M,
        rankChange = rankChange,
        weight = null,
        fcfMargin = fcfMargin,
        volumeSurge = volumeSurge,
        updatedAt = lastUpdated
    )
}

fun WatchlistItem.toComparisonItem(): StockComparisonItem {
    return StockComparisonItem(
        id = "watch-${normalizedTicker(ticker)}",
        ticker = ticker,
        name = name,
        market = market,
        sector = primaryTag ?: note,
        currency = currency,
        source = "Watch",
        scoreValue = null,
        scoreText = "-",
        expectedReturn = null,
        revenueGrowth = null,
        roic = null,
        grossMargin = null,
        marketCap = null,
        currentPrice = null,
        return1M = null,
        rankChange = null,
        weight = null,
        fcfMargin = null,
        volumeSurge = null,
        updatedAt = addedAt
    )
}

fun SearchStock.toComparisonItem(): StockComparisonItem {
    return StockComparisonItem(
        id = "search-${normalizedTicker(ticker)}",
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = currency,
        source = "Search",
        scoreValue = null,
        scoreText = "-",
        expectedReturn = null,
        revenueGrowth = null,
        roic = null,
        grossMargin = null,
        marketCap = marketCap,
        currentPrice = null,
        return1M = null,
        rankChange = null,
        weight = null,
        fcfMargin = null,
        volumeSurge = null,
        updatedAt = null
    )
}

fun ScoredStock.toComparisonItem(): StockComparisonItem {
    val headline = bestScoreValue()
    return StockComparisonItem(
        id = "score-${normalizedTicker(ticker)}",
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = marketCurrency(ticker, market),
        source = "Score",
        scoreValue = headline,
        scoreText = headline?.let { "%.3f".format(it) } ?: "-",
        expectedReturn = null,
        revenueGrowth = revGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = null,
        return1M = null,
        rankChange = null,
        weight = null,
        fcfMargin = fcfMargin,
        volumeSurge = null,
        updatedAt = null
    )
}

fun DetailRequest.toComparisonItem(detail: StockDetail?): StockComparisonItem {
    val info = detail?.info
    val scoreValue = factors.maxOfOrNull { it.value }
    return StockComparisonItem(
        id = "detail-${normalizedTicker(ticker)}",
        ticker = ticker,
        name = name,
        market = market,
        sector = info?.sector ?: sections.firstOrNull()?.title,
        currency = currency,
        source = "Detail",
        scoreValue = scoreValue,
        scoreText = scoreValue?.let { "%.2f".format(it) } ?: "-",
        expectedReturn = null,
        revenueGrowth = info?.revenueGrowth,
        roic = info?.returnOnEquity,
        grossMargin = info?.grossMargin,
        marketCap = info?.marketCap,
        currentPrice = info?.currentPrice,
        return1M = null,
        rankChange = null,
        weight = null,
        fcfMargin = null,
        volumeSurge = null,
        updatedAt = detail?.updatedAt
    )
}

private fun ScoredStock.bestScoreValue(): Double? {
    return listOfNotNull(combinedScore, finalScore, totalScore, scoreNeutral, mlScore).firstOrNull()
}
