package com.qubit.quantbridge

import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

fun displayCompanyName(rawName: String?, ticker: String? = null): String {
    val fallback = ticker.orEmpty().trim()
    marketIndicatorDisplayNameOverride(fallback)?.let { return it }
    var text = rawName.orEmpty().trim()
    if (text.isBlank()) return fallback
    marketIndicatorDisplayNameOverride(text)?.let { return it }


    text = text
        .replace("㈜", "")
        .replace("(주)", "")
        .replace("（주）", "")
        .replace("주식회사", "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim(',', '，', '.', '-', ' ')

    val suffixPattern = Regex(
        """(?:[,，]\s*)?(?:the\s+)?(?:incorporated|inc|corporation|corp|company|co|limited|ltd|plc|llc|holdings?|holding|group|n\.v|nv|s\.a|sa|ag|se|lp|l\.p|common stock|ordinary shares|american depositary shares|american depositary receipts|ads|adr|class\s+[a-z])\.?$""",
        RegexOption.IGNORE_CASE
    )

    var previous: String
    do {
        previous = text
        text = text
            .replace(suffixPattern, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(',', '，', '.', '-', ' ')
    } while (text.isNotBlank() && previous != text)

    if (text.startsWith("the ", ignoreCase = true)) {
        text = text.drop(4)
    }
    if (isGenericTickerCompanyName(text, fallback)) {
        return shortTicker(fallback).ifBlank { fallback }
    }
    val resolved = text.ifBlank { rawName ?: fallback }
    return koreanCompanyFallbackName(resolved, fallback) ?: resolved
}

internal fun isGenericTickerCompanyName(name: String, ticker: String): Boolean {
    val cleanName = name.trim()
    if (!cleanName.endsWith("기업")) return false
    val prefix = cleanName
        .removeSuffix("기업")
        .trim()
        .uppercase(Locale.US)
    val symbol = shortTicker(ticker).uppercase(Locale.US)
    val upperTicker = ticker.uppercase(Locale.US)
    val baseSymbol = upperTicker.substringBefore(".")
    return symbol.isNotBlank() && (prefix == symbol || prefix == upperTicker || prefix == baseSymbol)
}

internal fun koreanCompanyFallbackName(name: String, ticker: String): String? {
    val symbol = shortTicker(ticker)
        .replace(".", "-")
        .uppercase(Locale.US)
    if (symbol.isBlank()) return null
    if (!symbol.matches(Regex("""^[A-Z][A-Z0-9-]{0,7}$"""))) return null
    if (containsHangul(name) || isNonCompanyInstrumentName(name)) return null
    return null
}

internal fun isNonCompanyInstrumentName(value: String): Boolean {
    val text = value.lowercase(Locale.US)
    val markers = listOf(
        " etf", " fund", " trust", " shares", " index", " futures", " future",
        " bond", " treasury", " yield", "spdr", "ishares", "vanguard",
        "invesco", "ark ", "kodex", "tiger", "kospi", "kosdaq", "s&p",
        "nasdaq", "gold", "silver", "oil", "copper", "bitcoin", "ethereum"
    )
    return markers.any { text.contains(it) }
}

internal fun marketIndicatorDisplayNameOverride(value: String): String? {
    val compact = value.trim()
        .replace(Regex("""\s+"""), "")
        .uppercase(Locale.US)
    return when (compact) {
        "^KS11", "KOSPI", "KOSPI지수", "코스피" -> "코스피"
        "^KQ11", "KOSDAQ", "KOSDAQ지수", "코스닥" -> "코스닥"
        else -> null
    }
}
