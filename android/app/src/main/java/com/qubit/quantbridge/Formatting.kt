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

private fun cleanEnglishCompanyName(value: String): String {
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

private fun fixedDecimal(value: Double, minFractionDigits: Int, maxFractionDigits: Int): String {
    var text = "%.${maxFractionDigits}f".format(Locale.US, value)
    val decimalIndex = text.indexOf('.')
    if (decimalIndex < 0) return text
    while (text.length - decimalIndex - 1 > minFractionDigits && text.endsWith("0")) {
        text = text.dropLast(1)
    }
    return text
}

private val updateDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
private val updateDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

enum class DataFreshnessLevel(val label: String, val detail: String) {
    Fresh("최신", "최근 데이터"),
    Delayed("지연", "갱신 지연 가능"),
    Stale("오래됨", "재확인 필요"),
    Unknown("확인 필요", "갱신 시각 없음")
}

fun formattedUpdateTimestamp(rawValue: String?): String {
    val clean = rawValue.orEmpty().trim()
    if (clean.isBlank()) return "-"

    normalizedTimestampCandidates(clean).forEach { candidate ->
        val formatted = runCatching {
            OffsetDateTime.parse(candidate)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(updateDateTimeFormatter)
        }.getOrNull()
        if (formatted != null) return formatted
    }

    extractDateTimeText(clean)?.let { return it }

    return runCatching {
        LocalDate.parse(clean, updateDateFormatter).format(updateDateFormatter)
    }.getOrElse { clean }
}

fun dataFreshnessLevel(rawValue: String?): DataFreshnessLevel {
    val instant = parsedUpdateInstant(rawValue) ?: return DataFreshnessLevel.Unknown
    val ageHours = (System.currentTimeMillis() - instant.toEpochMilli()) / 3_600_000.0
    return when {
        ageHours <= 36.0 -> DataFreshnessLevel.Fresh
        ageHours <= 96.0 -> DataFreshnessLevel.Delayed
        else -> DataFreshnessLevel.Stale
    }
}

fun parsedUpdateInstant(rawValue: String?): Instant? {
    val clean = rawValue.orEmpty().trim()
    if (clean.isBlank()) return null
    return normalizedTimestampCandidates(clean).firstNotNullOfOrNull { candidate ->
        runCatching { OffsetDateTime.parse(candidate).toInstant() }.getOrNull()
    } ?: runCatching {
        LocalDate.parse(clean.take(10), updateDateFormatter)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }.getOrNull()
}

fun dataSourceLabel(source: String?): String {
    return when (source.orEmpty().trim().lowercase(Locale.US)) {
        "yfinance" -> "시장 API"
        "storage" -> "저장 데이터"
        "storage_snapshot" -> "부분 데이터"
        "cache" -> "앱 캐시"
        "" -> "출처 미확인"
        else -> source.orEmpty()
    }
}

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

private fun isGenericTickerCompanyName(name: String, ticker: String): Boolean {
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

private fun koreanCompanyFallbackName(name: String, ticker: String): String? {
    val symbol = shortTicker(ticker)
        .replace(".", "-")
        .uppercase(Locale.US)
    if (symbol.isBlank()) return null
    if (!symbol.matches(Regex("""^[A-Z][A-Z0-9-]{0,7}$"""))) return null
    if (containsHangul(name) || isNonCompanyInstrumentName(name)) return null
    return null
}

private fun isNonCompanyInstrumentName(value: String): Boolean {
    val text = value.lowercase(Locale.US)
    val markers = listOf(
        " etf", " fund", " trust", " shares", " index", " futures", " future",
        " bond", " treasury", " yield", "spdr", "ishares", "vanguard",
        "invesco", "ark ", "kodex", "tiger", "kospi", "kosdaq", "s&p",
        "nasdaq", "gold", "silver", "oil", "copper", "bitcoin", "ethereum"
    )
    return markers.any { text.contains(it) }
}

private fun marketIndicatorDisplayNameOverride(value: String): String? {
    val compact = value.trim()
        .replace(Regex("""\s+"""), "")
        .uppercase(Locale.US)
    return when (compact) {
        "^KS11", "KOSPI", "KOSPI지수", "코스피" -> "코스피"
        "^KQ11", "KOSDAQ", "KOSDAQ지수", "코스닥" -> "코스닥"
        else -> null
    }
}

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

private fun normalizedTimestampCandidates(value: String): List<String> {
    return buildList {
        add(value)
        if ("T" in value && !hasExplicitTimezone(value)) {
            add("${value}Z")
        }
    }
}

private fun hasExplicitTimezone(value: String): Boolean {
    return value.endsWith("Z", ignoreCase = true) ||
        Regex("""[+-]\d{2}:?\d{2}$""").containsMatchIn(value)
}

private fun extractDateTimeText(value: String): String? {
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

private fun containsHangul(value: String): Boolean {
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

private data class IndustryClusterRule(
    val label: String,
    val tickers: Set<String> = emptySet(),
    val terms: List<String> = emptyList()
)

val portfolioIndustryOrder = listOf(
    "데이터센터",
    "HBM",
    "CPU",
    "반도체 장비",
    "은행",
    "자동차",
    "배터리",
    "클라우드/SW",
    "헬스케어",
    "에너지",
    "소비/리테일",
    "산업재",
    "유틸리티",
    "소재",
    "기술",
    "통신",
    "부동산",
    "기타"
)

private val portfolioIndustryRules = listOf(
    IndustryClusterRule(
        label = "HBM",
        tickers = setOf("000660", "005930", "MU"),
        terms = listOf("hbm", "high bandwidth memory", "메모리", "dram", "낸드")
    ),
    IndustryClusterRule(
        label = "CPU",
        tickers = setOf("AMD", "INTC", "ARM", "QCOM"),
        terms = listOf("cpu", "processor", "프로세서", "x86", "arm")
    ),
    IndustryClusterRule(
        label = "데이터센터",
        tickers = setOf("NVDA", "AVGO", "ANET", "VRT", "SMCI", "DELL", "MSFT", "GOOGL", "GOOG", "AMZN", "META", "ORCL"),
        terms = listOf("data center", "datacenter", "데이터센터", "ai server", "ai 서버", "cloud infrastructure", "클라우드 인프라")
    ),
    IndustryClusterRule(
        label = "반도체 장비",
        tickers = setOf("ASML", "AMAT", "LRCX", "KLAC", "TER", "ONTO"),
        terms = listOf("semiconductor equipment", "반도체 장비", "lithography", "노광", "wafer", "웨이퍼")
    ),
    IndustryClusterRule(
        label = "은행",
        tickers = setOf("JPM", "BAC", "WFC", "C", "GS", "MS", "USB", "PNC", "105560", "055550", "086790", "316140"),
        terms = listOf("bank", "은행", "금융지주", "brokerage", "증권")
    ),
    IndustryClusterRule(
        label = "자동차",
        tickers = setOf("TSLA", "GM", "F", "TM", "RIVN", "005380", "000270", "012330"),
        terms = listOf("auto", "automotive", "vehicle", "ev", "자동차", "전기차", "모빌리티", "완성차")
    ),
    IndustryClusterRule(
        label = "배터리",
        tickers = setOf("373220", "006400", "051910", "096770"),
        terms = listOf("battery", "배터리", "2차전지", "양극재", "음극재")
    ),
    IndustryClusterRule(
        label = "클라우드/SW",
        tickers = setOf("CRM", "ADBE", "NOW", "SNOW", "WDAY", "INTU", "PANW", "CRWD", "ZS"),
        terms = listOf("software", "cloud", "saas", "소프트웨어", "클라우드", "보안")
    ),
    IndustryClusterRule(
        label = "헬스케어",
        tickers = setOf("LLY", "NVO", "UNH", "JNJ", "MRK", "PFE", "ABBV", "TMO", "DHR", "068270", "207940"),
        terms = listOf("health", "biotech", "pharma", "drug", "헬스케어", "바이오", "제약", "의료")
    ),
    IndustryClusterRule(
        label = "에너지",
        tickers = setOf("XOM", "CVX", "COP", "SLB", "EOG"),
        terms = listOf("energy", "oil", "gas", "에너지", "원유", "가스", "정유")
    ),
    IndustryClusterRule(
        label = "소비/리테일",
        tickers = setOf("COST", "WMT", "HD", "LOW", "TGT", "TJX", "MCD", "NKE", "SBUX", "PG", "KO", "PEP"),
        terms = listOf("retail", "consumer", "restaurant", "apparel", "소비", "리테일", "유통", "의류", "음식료")
    ),
    IndustryClusterRule(
        label = "산업재",
        tickers = setOf("GE", "DE", "CAT", "HON", "BA", "RTX", "LMT", "329180", "034020"),
        terms = listOf("industrial", "aerospace", "defense", "machinery", "산업재", "조선", "기계", "방산", "항공")
    )
)

fun portfolioIndustryLabel(ticker: String, name: String, sector: String? = null): String {
    val symbol = shortTicker(ticker).uppercase(Locale.US)
    val code = krCode(ticker)
    val tickerKeys = setOf(symbol, code).filter { it.isNotBlank() }.toSet()
    val rawText = listOf(ticker, name, sector.orEmpty())
        .joinToString(" ")
        .lowercase(Locale.getDefault())
    val compactText = rawText.replace(Regex("""[\s/_\-.]+"""), "")

    portfolioIndustryRules.forEach { rule ->
        if (tickerKeys.any { it in rule.tickers }) return rule.label
        if (rule.terms.any { term ->
                val lower = term.lowercase(Locale.getDefault())
                rawText.contains(lower) || compactText.contains(lower.replace(Regex("""[\s/_\-.]+"""), ""))
            }
        ) {
            return rule.label
        }
    }

    val cleanSector = sector?.trim().orEmpty()
    return when {
        cleanSector.contains("financial", ignoreCase = true) || cleanSector.contains("금융") -> "은행"
        cleanSector.contains("health", ignoreCase = true) || cleanSector.contains("바이오") || cleanSector.contains("제약") -> "헬스케어"
        cleanSector.contains("energy", ignoreCase = true) || cleanSector.contains("에너지") -> "에너지"
        cleanSector.contains("consumer", ignoreCase = true) || cleanSector.contains("소비") -> "소비/리테일"
        cleanSector.contains("industrial", ignoreCase = true) || cleanSector.contains("산업") -> "산업재"
        cleanSector.contains("utilit", ignoreCase = true) || cleanSector.contains("전력") || cleanSector.contains("가스") -> "유틸리티"
        cleanSector.contains("basic material", ignoreCase = true) ||
            cleanSector.contains("materials", ignoreCase = true) ||
            cleanSector.contains("소재") ||
            cleanSector.contains("화학") ||
            cleanSector.contains("금속") -> "소재"
        cleanSector.contains("technology", ignoreCase = true) ||
            cleanSector.contains("tech", ignoreCase = true) ||
            cleanSector.contains("information", ignoreCase = true) ||
            cleanSector.contains("기술") -> "기술"
        cleanSector.contains("communication", ignoreCase = true) ||
            cleanSector.contains("telecom", ignoreCase = true) ||
            cleanSector.contains("통신") ||
            cleanSector.contains("미디어") -> "통신"
        cleanSector.contains("real estate", ignoreCase = true) || cleanSector.contains("부동산") -> "부동산"
        cleanSector.isNotBlank() -> cleanSector
        else -> "기타"
    }
}
