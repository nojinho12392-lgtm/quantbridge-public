package com.qubit.quantbridge

import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

fun marketIndicatorLogoText(ticker: String, name: String? = null): String {
    return when (normalizedTicker(ticker)) {
        "^IXIC", "NQ=F" -> "NASDAQ"
        "^GSPC", "ES=F" -> "S&P"
        "RTY=F" -> "RUSSELL"
        "^DJI" -> "DOW"
        "^SOX" -> "SOX"
        "^VIX" -> "VIX"
        "^KS11" -> "KOSPI"
        "^KQ11" -> "KOSDAQ"
        "KRW=X" -> "USD/KRW"
        "DX-Y.NYB" -> "DXY"
        "^IRX" -> "US3M"
        "^FVX" -> "US5Y"
        "^TNX" -> "US10Y"
        "^TYX" -> "US30Y"
        "IRR_GOVT03Y" -> "KR3Y"
        "IRR_CORP03Y" -> "KRCR"
        "GC=F" -> "GOLD"
        "SI=F" -> "SILVER"
        "CL=F" -> "OIL"
        "HG=F" -> "COPPER"
        "BTC-USD" -> "BTC"
        "ETH-USD" -> "ETH"
        "SOL-USD" -> "SOL"
        else -> {
            val cleaned = name.orEmpty()
                .replace("지수", "")
                .replace("선물", "")
                .trim()
            if (cleaned.isNotBlank()) {
                val acronym = cleaned
                    .split(Regex("""\s+"""))
                    .mapNotNull { word -> word.firstOrNull()?.takeIf { it.isLetterOrDigit() }?.uppercaseChar()?.toString() }
                    .joinToString("")
                if (acronym.length >= 2) acronym.take(6) else cleaned.take(6)
            } else {
                shortTicker(ticker).uppercase(Locale.US).take(6)
            }
        }
    }
}

internal fun normalizedTimestampCandidates(value: String): List<String> {
    return buildList {
        add(value)
        if ("T" in value && !hasExplicitTimezone(value)) {
            add("${value}Z")
        }
    }
}

internal fun hasExplicitTimezone(value: String): Boolean {
    return value.endsWith("Z", ignoreCase = true) ||
        Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(value)
}

internal fun extractDateTimeText(value: String): String? {
    val match = Regex("""(\d{4}[-/.]\d{2}[-/.]\d{2})[T\s]+(\d{2}:\d{2})""").find(value)
        ?: return null
    val date = match.groupValues[1].replace('/', '-').replace('.', '-')
    val time = match.groupValues[2]
    return "$date $time"
}

fun groupedInteger(value: Long): String {
    return "%,d".format(value)
}

fun signedPx(value: Double, currency: String = "USD"): String {
    val sign = if (value >= 0.0) "+" else "-"
    return sign + fmtPx(abs(value), currency)
}

fun normalizedRecommendation(value: String?): String? {
    val clean = value.orEmpty().trim()
    if (clean.isBlank()) return null
    val normalized = clean.lowercase(Locale.US)
    if (normalized in setOf("none", "null", "nil", "n/a", "na", "-")) return null
    return clean
}
