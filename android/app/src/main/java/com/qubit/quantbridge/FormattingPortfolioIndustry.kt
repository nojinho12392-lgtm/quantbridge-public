package com.qubit.quantbridge

import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal data class IndustryClusterRule(
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

internal val portfolioIndustryRules = listOf(
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
