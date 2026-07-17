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
