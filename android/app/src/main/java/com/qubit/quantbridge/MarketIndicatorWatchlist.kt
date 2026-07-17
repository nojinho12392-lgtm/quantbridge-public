package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

const val MARKET_INDICATOR_WATCHLIST_NOTE = "지수"

internal val MARKET_INDICATOR_WATCHLIST_SYMBOLS = setOf(
    "^IXIC", "NQ=F", "^GSPC", "ES=F", "RTY=F", "^DJI", "^SOX", "^VIX",
    "KRW=X", "DX-Y.NYB", "^KS11", "^KQ11",
    "^IRX", "^FVX", "^TNX", "^TYX", "IRR_GOVT03Y", "IRR_CORP03Y",
    "GC=F", "SI=F", "CL=F", "HG=F",
    "BTC-USD", "ETH-USD", "SOL-USD"
)

internal val MARKET_INDICATOR_UNAMBIGUOUS_ALIASES = setOf(
    "KOSPI", "KOSPI지수", "코스피",
    "KOSDAQ", "KOSDAQ지수", "코스닥",
    "NASDAQ", "나스닥", "VIX"
)

fun WatchlistItem.isMarketIndicatorWatchItem(): Boolean {
    val normalized = normalizedTicker(ticker)
    return note == MARKET_INDICATOR_WATCHLIST_NOTE ||
        normalized in MARKET_INDICATOR_WATCHLIST_SYMBOLS ||
        normalized in MARKET_INDICATOR_UNAMBIGUOUS_ALIASES
}

fun canonicalMarketIndicatorSymbol(value: String): String {
    val normalized = normalizedTicker(value)
    val compact = normalized
        .replace(" ", "")
        .replace("_", "")
        .replace("-", "")
        .replace(".", "")
    return when (compact) {
        "^KS11", "KOSPI", "KOSPI지수", "코스피" -> "^KS11"
        "^KQ11", "KOSDAQ", "KOSDAQ지수", "코스닥" -> "^KQ11"
        "^IXIC", "IXIC", "NASDAQ", "나스닥" -> "^IXIC"
        "^GSPC", "GSPC", "SP500", "S&P500", "SNP500", "에스앤피500" -> "^GSPC"
        "^DJI", "DJI", "DOW", "DOWJONES", "다우", "다우존스" -> "^DJI"
        "^SOX", "SOX", "필라델피아반도체" -> "^SOX"
        "^VIX", "VIX" -> "^VIX"
        "DXY", "DXYNYB", "DXYNY", "DOLLARINDEX", "달러인덱스" -> "DX-Y.NYB"
        "USDKRW", "KRWX", "원달러", "달러원" -> "KRW=X"
        else -> normalized
    }
}

fun canonicalMarketIndicatorSymbol(item: WatchlistItem): String {
    val tickerCanonical = canonicalMarketIndicatorSymbol(item.ticker)
    if (tickerCanonical != normalizedTicker(item.ticker)) return tickerCanonical
    val nameCanonical = canonicalMarketIndicatorSymbol(item.name)
    return if (nameCanonical != normalizedTicker(item.name)) nameCanonical else tickerCanonical
}

fun marketIndicatorWatchItem(item: MarketIndicatorQuote): WatchlistItem {
    return WatchlistItem(
        ticker = item.symbol,
        name = item.label,
        market = marketIndicatorWatchMarket(item),
        currency = if (item.region == "domestic") "KRW" else "USD",
        note = MARKET_INDICATOR_WATCHLIST_NOTE,
        addedAt = ""
    )
}

internal fun marketIndicatorWatchMarket(item: MarketIndicatorQuote): String {
    return when (item.category) {
        "commodity" -> "원자재"
        "crypto" -> "가상자산"
        "bond" -> if (item.region == "domestic") "KR" else "US"
        else -> when (item.region) {
            "domestic" -> "KR"
            "overseas" -> "US"
            else -> "GLOBAL"
        }
    }
}
