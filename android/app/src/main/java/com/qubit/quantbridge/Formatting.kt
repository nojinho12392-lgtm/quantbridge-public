package com.qubit.quantbridge

import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

fun krCode(value: String?): String {
    return Regex("""\d{6}""").find(value.orEmpty().trim())?.value.orEmpty()
}

fun hasKrSuffix(ticker: String?): Boolean {
    return ticker.orEmpty().trim().uppercase(Locale.US).matches(Regex("""\d{6}\.(KS|KQ)"""))
}

fun isMissingKrName(name: String?, ticker: String?): Boolean {
    val cleanName = name.orEmpty().trim()
    val cleanTicker = ticker.orEmpty().trim()
    val code = krCode(cleanTicker)
    if (cleanName.isBlank()) return true
    if (cleanTicker.isNotBlank() && cleanName.equals(cleanTicker, ignoreCase = true)) return true
    if (code.isNotBlank() && cleanName == code) return true
    return cleanName.uppercase(Locale.US).matches(Regex("""\d{6}(\.(KS|KQ))?"""))
}

@Suppress("UnusedParameter")
fun resolvedKrCompanyName(ticker: String, currentName: String): String {
    return currentName
}

@Suppress("ReturnCount", "UnusedParameter")
fun localizedCompanyName(ticker: String, currentName: String, market: String? = null): String {
    val cleanName = currentName.trim()
    val genericTickerName = isGenericTickerCompanyName(cleanName, ticker)
    if (containsHangul(cleanName) && !genericTickerName) return cleanName
    if (genericTickerName) {
        val symbol = shortTicker(ticker).uppercase(Locale.US)
        return symbol.ifBlank { cleanName }
    }
    val resolved = cleanEnglishCompanyName(if (cleanName.isBlank()) shortTicker(ticker) else cleanName)
    return koreanCompanyFallbackName(resolved, ticker) ?: resolved
}

internal fun cleanEnglishCompanyName(value: String): String {
    var text = value.trim()
    val suffixes = listOf(
        " Inc.", " Inc", " Incorporated", " Corporation", " Corp.", " Corp",
        " Company", " Co.", " Co", " Ltd.", " Ltd", " Limited", " plc", " PLC",
        ", Inc.", ", Inc", ", Ltd.", ", Ltd", " (The)", ", Inc. (The)"
    )
    suffixes.forEach { suffix ->
        if (text.endsWith(suffix, ignoreCase = true)) {
            text = text.dropLast(suffix.length).trim()
        }
    }
    return text.ifBlank { value }
}

fun pct(value: Double?, signed: Boolean = true): String {
    if (value == null || !value.isFinite()) return "-"
    return if (signed) "%+.1f%%".format(value * 100) else "%.1f%%".format(value * 100)
}

fun num(value: Double?): String {
    if (value == null || !value.isFinite()) return "-"
    return if (abs(value) >= 10.0) "%.1f".format(value) else "%.3f".format(value)
}

fun indicatorValueText(value: Double): String {
    if (!value.isFinite()) return "-"
    return when {
        abs(value) >= 100.0 -> "%.2f".format(value)
        abs(value) >= 1.0 -> "%.3f".format(value)
        else -> "%.4g".format(value)
    }
}

fun signedNumber(value: Double?): String {
    if (value == null || !value.isFinite()) return "-"
    return if (abs(value) >= 100.0) "%+.2f".format(value) else "%+.3g".format(value)
}

fun compactNumber(value: Double?): String {
    if (value == null || !value.isFinite()) return "-"
    val sign = if (value < 0) "-" else ""
    val absValue = abs(value)
    return when {
        absValue >= 1e12 -> "$sign%.1f조".format(absValue / 1e12)
        absValue >= 1e8 -> "$sign%.1f억".format(absValue / 1e8)
        absValue >= 1e6 -> "$sign%.1fM".format(absValue / 1e6)
        else -> "$sign%.0f".format(absValue)
    }
}

fun horizonLabel(value: Double?): String {
    if (value == null || !value.isFinite()) return "기간 -"
    return "${value.toInt()}거래일"
}

fun score(value: Double?): String {
    if (value == null || !value.isFinite()) return "-"
    return "%.3f".format(value)
}

fun cap(value: Double?, currency: String = "USD"): String {
    if (value == null || value <= 0.0 || !value.isFinite()) return "-"
    if (currency == "KRW") {
        val eok = (value / 1e8).toLong()
        val jo = eok / 10_000
        val rem = eok % 10_000
        return when {
            jo > 0 && rem > 0 -> "${groupedInteger(jo)}조 ${groupedInteger(rem)}억"
            jo > 0 -> "${groupedInteger(jo)}조"
            else -> "${groupedInteger(rem)}억"
        }
    }
    return when {
        value >= 1e12 -> "$%.1fT".format(value / 1e12)
        value >= 1e9 -> "$%.1fB".format(value / 1e9)
        else -> "$%.0fM".format(value / 1e6)
    }
}

fun fmtPx(value: Double, currency: String = "USD"): String {
    if (!value.isFinite()) return "-"
    return if (currency == "KRW") {
        "₩${groupedInteger(value.toLong())}"
    } else {
        when {
            abs(value) >= 1 -> "$${fixedDecimal(value, minFractionDigits = 2, maxFractionDigits = 2)}"
            else -> "$${fixedDecimal(value, minFractionDigits = 2, maxFractionDigits = 4)}"
        }
    }
}

internal fun fixedDecimal(value: Double, minFractionDigits: Int, maxFractionDigits: Int): String {
    var text = "%.${maxFractionDigits}f".format(Locale.US, value)
    val decimalIndex = text.indexOf('.')
    if (decimalIndex < 0) return text
    while (text.length - decimalIndex - 1 > minFractionDigits && text.endsWith("0")) {
        text = text.dropLast(1)
    }
    return text
}
