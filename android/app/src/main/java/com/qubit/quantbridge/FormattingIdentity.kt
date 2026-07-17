package com.qubit.quantbridge

import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

fun marketCurrency(ticker: String, market: String?): String {
    return if (market == "KR" || ticker.endsWith(".KS") || ticker.endsWith(".KQ")) "KRW" else "USD"
}

fun fallbackHoldingTicker(holding: DetailHolding): String {
    val raw = holding.ticker.trim()
    val code = krCode(raw)
    return when {
        code.isNotBlank() && !hasKrSuffix(raw) -> "$code.KS"
        raw.isNotBlank() -> raw
        else -> holding.name
    }
}

fun holdingMarket(ticker: String, fallbackName: String): String {
    return if (isKoreanTicker(ticker, null) || containsHangul(ticker) || containsHangul(fallbackName)) "KR" else "US"
}

internal fun containsHangul(value: String): Boolean {
    return Regex("""[가-힣]""").containsMatchIn(value)
}

fun shortTicker(ticker: String): String {
    val trimmed = ticker.trim()
    return if (trimmed.endsWith(".KS", ignoreCase = true) || trimmed.endsWith(".KQ", ignoreCase = true)) {
        trimmed.dropLast(3)
    } else {
        trimmed
    }
}

fun companyLogoUrls(ticker: String, market: String?): List<String> {
    val symbol = shortTicker(ticker).uppercase(Locale.US)
    return if (isKoreanTicker(ticker, market)) {
        listOfNotNull(
            krLogoOverrideUrl(symbol),
            "https://file.alphasquare.co.kr/media/images/stock_logo/kr/$symbol.png",
            "https://static.toss.im/png-icons/securities/icn-sec-fill-$symbol.png"
        ).distinct()
    } else {
        usLogoSymbols(symbol).flatMap { variant ->
            listOfNotNull(
                usLogoDomain(variant)?.let { "https://logo.clearbit.com/$it" },
                "https://financialmodelingprep.com/image-stock/$variant.png",
                "https://companiesmarketcap.com/img/company-logos/256/$variant.webp",
                "https://eodhd.com/img/logos/US/$variant.png",
                "https://assets.parqet.com/logos/symbol/$variant"
            )
        }.distinct()
    }
}

fun usLogoDomain(symbol: String): String? {
    return mapOf(
        "AAPL" to "apple.com",
        "MSFT" to "microsoft.com",
        "NVDA" to "nvidia.com",
        "GOOGL" to "abc.xyz",
        "GOOG" to "abc.xyz",
        "AMZN" to "amazon.com",
        "META" to "meta.com",
        "TSLA" to "tesla.com",
        "HD" to "homedepot.com",
        "LOW" to "lowes.com",
        "TGT" to "target.com",
        "KEYS" to "keysight.com",
        "ADI" to "analog.com",
        "HAS" to "hasbro.com",
        "INTU" to "intuit.com",
        "NDSN" to "nordson.com",
        "TJX" to "tjx.com",
        "WSM" to "williams-sonoma.com",
        "CPRT" to "copart.com",
        "DE" to "deere.com",
        "DECK" to "deckers.com",
        "RL" to "ralphlauren.com",
        "ROST" to "rossstores.com",
        "TTWO" to "take2games.com",
        "WDAY" to "workday.com"
    )[symbol.uppercase(Locale.US)]
}

fun krLogoOverrideUrl(symbol: String): String? {
    return when (symbol) {
        "064400" -> "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png"
        else -> null
    }
}

fun usLogoSymbols(symbol: String): List<String> {
    return buildList {
        if (symbol.isNotBlank()) add(symbol)
        if ("." in symbol) add(symbol.replace(".", "-"))
        if ("-" in symbol) add(symbol.replace("-", "."))
    }.distinct()
}

fun isKoreanTicker(ticker: String, market: String?): Boolean {
    val symbol = shortTicker(ticker)
    return market.equals("KR", ignoreCase = true) ||
        ticker.endsWith(".KS", ignoreCase = true) ||
        ticker.endsWith(".KQ", ignoreCase = true) ||
        symbol.matches(Regex("""\d{6}"""))
}

fun matches(query: String, ticker: String, name: String, sector: String?): Boolean {
    val q = query.trim().lowercase(Locale.getDefault())
    if (q.isBlank()) return true
    return ticker.lowercase(Locale.getDefault()).contains(q) ||
        name.lowercase(Locale.getDefault()).contains(q) ||
        (sector?.lowercase(Locale.getDefault())?.contains(q) ?: false)
}
