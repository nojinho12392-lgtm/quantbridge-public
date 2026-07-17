package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

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
