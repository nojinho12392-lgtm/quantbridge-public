package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

internal const val WATCH_INVESTMENT_THESIS_PREFIX = "qb_thesis_v1:"
internal val watchInvestmentThesisJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

data class AuthUser(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: String
)

data class WatchlistItem(
    val ticker: String,
    val name: String,
    val market: String,
    val currency: String,
    val note: String,
    val addedAt: String,
    val tags: List<String> = emptyList(),
    val memo: String = "",
    val alertOptions: List<String> = emptyList()
)

@Serializable
data class InvestmentProfile(
    val experience: String = "",
    val horizon: String = "",
    val riskTolerance: String = "",
    val style: String = "",
    val avoidances: List<String> = emptyList(),
    val dropResponse: String = "",
    val overheatedResponse: String = ""
) {
    val normalized: InvestmentProfile
        get() = copy(
            experience = experience.trim(),
            horizon = horizon.trim(),
            riskTolerance = riskTolerance.trim(),
            style = style.trim(),
            avoidances = avoidances
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            dropResponse = dropResponse.trim(),
            overheatedResponse = overheatedResponse.trim()
        )

    val isConfigured: Boolean
        get() = experience.isNotBlank() ||
            horizon.isNotBlank() ||
            riskTolerance.isNotBlank() ||
            style.isNotBlank() ||
            avoidances.any { it.isNotBlank() } ||
            dropResponse.isNotBlank() ||
            overheatedResponse.isNotBlank()

    val headline: String
        get() {
            if (!isConfigured) return "아직 미설정"
            val primary = listOf(riskTolerance, horizon, style).firstOrNull { it.isNotBlank() } ?: "맞춤 기준"
            return "$primary 중심"
        }

    val summary: String
        get() {
            val parts = listOf(experience, horizon, riskTolerance, style).filter { it.isNotBlank() }
            if (parts.isEmpty()) return "투자 성향을 저장하면 후보를 내 기준으로 점검할 수 있습니다."
            return parts.take(3).joinToString(" · ")
        }

    val guardrailSummary: String
        get() {
            val clean = avoidances.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (clean.isEmpty()) return "피하고 싶은 신호 없음"
            return clean.take(3).joinToString(" · ")
        }

    val completionPercent: Int
        get() {
            val fields = listOf(
                experience,
                horizon,
                riskTolerance,
                style,
                avoidances.joinToString(),
                dropResponse,
                overheatedResponse
            )
            val completed = fields.count { it.isNotBlank() }
            return ((completed.toFloat() / fields.size.toFloat()) * 100f).toInt().coerceIn(0, 100)
        }

    val operatingStatement: String
        get() {
            if (!isConfigured) return "나는 먼저 기준을 세운 뒤 후보를 확인한다."
            val styleText = style.ifBlank { "내 기준에 맞는" }
            val horizonText = horizon.ifBlank { "정한 기간" }
            val riskText = riskTolerance.ifBlank { "감당 가능한 위험" }
            return "나는 $styleText 후보를 $horizonText 동안 보고, $riskText 범위에서 확인 조건이 맞을 때만 판단한다."
        }

    val consistencyNotes: List<String>
        get() = buildList {
            if (riskTolerance == "보수적" && (style == "모멘텀" || overheatedResponse.contains("모멘텀"))) {
                add("보수적 기준과 모멘텀 선호가 섞여 있어 급등 구간에서는 확인 조건을 더 엄격하게 잡으세요.")
            }
            if (horizon == "1개월" && (style == "배당" || style == "가치주")) {
                add("1개월 관찰과 ${style} 관점은 속도가 다릅니다. 가격보다 재평가 조건을 먼저 보세요.")
            }
            if (riskTolerance == "공격적" && avoidances.any { it.contains("급등락") }) {
                add("공격적 성향이지만 급등락을 피하고 싶다면 진입보다 후보 비교에 더 무게를 두세요.")
            }
            if (dropResponse.contains("분할") && riskTolerance == "보수적") {
                add("하락 시 분할 관찰을 선택했습니다. 손실 한도와 틀렸다고 볼 조건을 함께 적어두세요.")
            }
        }

    val consistencyLabel: String
        get() = if (consistencyNotes.isEmpty()) "일관성 양호" else "점검 필요 ${consistencyNotes.size}"

    val nextReviewText: String
        get() = when (horizon) {
            "1개월" -> "30일 뒤 기준 재점검"
            "3개월" -> "분기 단위 기준 재점검"
            "6개월" -> "반기 단위 기준 재점검"
            "1년+" -> "연 1회 기준 재점검"
            else -> "30일 뒤 기준 재점검"
        }
}

val WatchlistItem.primaryTag: String?
    get() = tags.firstOrNull { it.isNotBlank() }

val WatchlistItem.investmentThesis: WatchInvestmentThesis
    get() = WatchInvestmentThesis.fromMemo(memo)

@Serializable
data class WatchInvestmentThesis(
    val reason: String = "",
    val expectedChange: String = "",
    val checkCondition: String = "",
    val invalidationCondition: String = "",
    val horizon: String = "",
    val reviewStatus: String = "",
    val reviewNote: String = ""
) {
    val normalized: WatchInvestmentThesis
        get() = copy(
            reason = reason.trim(),
            expectedChange = expectedChange.trim(),
            checkCondition = checkCondition.trim(),
            invalidationCondition = invalidationCondition.trim(),
            horizon = horizon.trim(),
            reviewStatus = reviewStatus.trim(),
            reviewNote = reviewNote.trim()
        )

    val isEmpty: Boolean
        get() = listOf(reason, expectedChange, checkCondition, invalidationCondition, horizon, reviewStatus, reviewNote)
            .all { it.trim().isBlank() }

    val memoText: String
        get() {
            val clean = normalized
            if (clean.isEmpty) return ""
            return runCatching {
                WATCH_INVESTMENT_THESIS_PREFIX +
                    watchInvestmentThesisJson.encodeToString(WatchInvestmentThesis.serializer(), clean)
            }.getOrElse { clean.reason }
        }

    val inlineSummary: String?
        get() = when {
            isEmpty -> null
            reason.isNotBlank() -> reason.trim()
            expectedChange.isNotBlank() -> "기대: ${expectedChange.trim()}"
            checkCondition.isNotBlank() -> "확인: ${checkCondition.trim()}"
            invalidationCondition.isNotBlank() -> "틀린 조건: ${invalidationCondition.trim()}"
            horizon.isNotBlank() -> "관찰 기간: ${horizon.trim()}"
            else -> null
        }

    val detailSummary: String
        get() = buildList {
            if (reason.isNotBlank()) add("이유: ${reason.trim()}")
            if (expectedChange.isNotBlank()) add("기대: ${expectedChange.trim()}")
            if (checkCondition.isNotBlank()) add("확인: ${checkCondition.trim()}")
            if (invalidationCondition.isNotBlank()) add("틀린 조건: ${invalidationCondition.trim()}")
            if (horizon.isNotBlank()) add("기간: ${horizon.trim()}")
            reviewSummary?.let { add("복기: $it") }
        }.take(3).joinToString(" · ")

    val reviewLabel: String
        get() = reviewStatus.trim().takeIf { it.isNotBlank() } ?: "복기 대기"

    val reviewPrompt: String
        get() = when (reviewStatus.trim()) {
            "유지" -> "기존 가설을 유지하되 확인 조건이 실제로 맞는지 계속 보세요."
            "수정" -> "틀린 부분을 반영해 기대 변화나 확인 조건을 다시 적으세요."
            "종료" -> "무효 조건이 확인됐거나 우선순위가 낮아졌다면 관심을 정리하세요."
            else -> "다음 확인 때 유지, 수정, 종료 중 하나를 선택하세요."
        }

    val reviewSummary: String?
        get() {
            val status = reviewStatus.trim()
            val note = reviewNote.trim()
            return when {
                status.isNotBlank() && note.isNotBlank() -> "$status · $note"
                status.isNotBlank() -> "$status · $reviewPrompt"
                note.isNotBlank() -> note
                else -> null
            }
        }

    val quality: WatchThesisQuality
        get() {
            val fields = listOf(
                "관심 이유" to reason,
                "기대 변화" to expectedChange,
                "확인 조건" to checkCondition,
                "틀렸다고 볼 조건" to invalidationCondition,
                "관찰 기간" to horizon
            )
            val completed = fields.filter { it.second.trim().isNotBlank() }
            val missing = fields.filter { it.second.trim().isBlank() }.map { it.first }
            val percent = ((completed.size.toFloat() / fields.size.toFloat()) * 100f).toInt().coerceIn(0, 100)
            return WatchThesisQuality(
                percent = percent,
                label = when {
                    percent >= 100 -> "복기 가능"
                    percent >= 80 -> "거의 완성"
                    percent >= 40 -> "가설 작성 중"
                    percent > 0 -> "이유만 있음"
                    else -> "가설 없음"
                },
                missingFields = missing,
                reviewTiming = when (horizon) {
                    "1개월" -> "30일 안에 유지/수정/종료를 선택하세요."
                    "3개월" -> "분기 실적이나 가격 변화 후 복기하세요."
                    "6개월" -> "반기 동안 확인 조건이 맞는지 추적하세요."
                    "1년+" -> "긴 흐름은 분기마다 중간 점검하세요."
                    else -> "관찰 기간을 정하면 다음 복기 타이밍이 선명해집니다."
                }
            )
        }

    val suggestedTags: List<String>
        get() {
            val text = listOf(reason, expectedChange, checkCondition, invalidationCondition)
                .joinToString(" ")
                .lowercase()
            return buildList {
                if (text.contains("실적") || text.contains("매출") || text.contains("마진") || text.contains("margin")) add("실적")
                if (text.contains("저평가") || text.contains("per") || text.contains("pbr") || text.contains("value")) add("저평가")
                if (text.contains("모멘텀") || text.contains("급등") || text.contains("momentum")) add("모멘텀")
                if (text.contains("리스크") || text.contains("부채") || text.contains("하락") || text.contains("drop")) add("리스크")
                if (text.contains("공부") || text.contains("확인") || text.contains("check")) add("공부")
                if (text.contains("매수") || text.contains("후보") || text.contains("watch")) add("매수후보")
            }.distinct()
        }

    companion object {
        fun fromMemo(memo: String): WatchInvestmentThesis {
            val clean = memo.trim()
            if (clean.startsWith(WATCH_INVESTMENT_THESIS_PREFIX)) {
                val payload = clean.removePrefix(WATCH_INVESTMENT_THESIS_PREFIX)
                return runCatching {
                    watchInvestmentThesisJson.decodeFromString(WatchInvestmentThesis.serializer(), payload).normalized
                }.getOrElse { WatchInvestmentThesis(reason = clean) }
            }
            return WatchInvestmentThesis(reason = clean)
        }
    }
}

data class WatchThesisQuality(
    val percent: Int,
    val label: String,
    val missingFields: List<String>,
    val reviewTiming: String
)

@Serializable
data class InvestmentDecisionRecord(
    val ticker: String,
    val name: String,
    val market: String,
    val currency: String,
    val reasons: List<String> = emptyList(),
    val counterEvidence: List<String> = emptyList(),
    val fitLabel: String = "",
    val condition: String = "",
    val status: String = "",
    val reviewTrigger: String = "",
    val note: String = "",
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    val normalized: InvestmentDecisionRecord
        get() = copy(
            ticker = normalizedTicker(ticker),
            name = displayCompanyName(name.ifBlank { ticker }, ticker),
            market = market.trim(),
            currency = currency.trim().ifBlank { marketCurrency(ticker, market) },
            reasons = reasons.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            counterEvidence = counterEvidence.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            fitLabel = fitLabel.trim(),
            condition = condition.trim(),
            status = status.trim().ifBlank { "추가 확인 필요" },
            reviewTrigger = reviewTrigger.trim(),
            note = note.trim(),
            createdAt = createdAt.trim(),
            updatedAt = updatedAt.trim()
        )

    val qualityPercent: Int
        get() {
            val completed = listOf(
                reasons.isNotEmpty(),
                counterEvidence.isNotEmpty(),
                condition.isNotBlank(),
                status.isNotBlank(),
                reviewTrigger.isNotBlank()
            ).count { it }
            return ((completed.toFloat() / 5f) * 100f).toInt().coerceIn(0, 100)
        }

    val qualityLabel: String
        get() = when {
            qualityPercent >= 100 -> "결정서 완성"
            qualityPercent >= 80 -> "검토 가능"
            qualityPercent >= 40 -> "작성 중"
            else -> "초안"
        }

    val headline: String
        get() = status.ifBlank { "추가 확인 필요" }

    val summary: String
        get() = buildList {
            if (reasons.isNotEmpty()) add("이유 ${reasons.take(2).joinToString(" · ")}")
            if (counterEvidence.isNotEmpty()) add("주의 ${counterEvidence.take(2).joinToString(" · ")}")
            if (condition.isNotBlank()) add("조건 $condition")
            if (reviewTrigger.isNotBlank()) add("재검토 $reviewTrigger")
        }.take(3).joinToString(" · ").ifBlank { "투자 이유와 주의 신호를 먼저 정리하세요." }

    val inlineSummary: String
        get() = "$headline · $qualityPercent%"
}

data class StockComparisonItem(
    val id: String,
    val ticker: String,
    val name: String,
    val market: String?,
    val sector: String?,
    val currency: String,
    val source: String,
    val scoreValue: Double?,
    val scoreText: String,
    val expectedReturn: Double?,
    val revenueGrowth: Double?,
    val roic: Double?,
    val grossMargin: Double?,
    val marketCap: Double?,
    val currentPrice: Double?,
    val return1M: Double?,
    val rankChange: Int?,
    val weight: Double?,
    val fcfMargin: Double?,
    val volumeSurge: Double?,
    val updatedAt: String?
)

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
