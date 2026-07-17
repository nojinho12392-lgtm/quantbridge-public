package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

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
