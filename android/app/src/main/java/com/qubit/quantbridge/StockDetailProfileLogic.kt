package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.abs

internal fun detailDecisionPills(request: DetailRequest, detail: StockDetail?): List<DecisionPillModel> {
    val info = detail?.info
    val metrics = request.sections.flatMap { it.metrics }
    return buildList {
        val current = info?.currentPrice
        val prev = info?.prevClose
        if (current != null && prev != null && prev != 0.0) {
            val changePct = info.dailyChangePct?.takeIf { it.isFinite() } ?: (current / prev - 1.0)
            val label = info.dailyChangeHorizon?.trim()?.takeIf { it.isNotEmpty() } ?: "당일 흐름"
            add(
                DecisionPillModel(
                    label,
                    pct(changePct),
                    signedPx(current - prev, request.currency),
                    if (changePct >= 0.0) DetailTone.Positive else DetailTone.Negative
                )
            )
        }
        metrics.firstOrNull { isScoreMetric(it.label) && it.value != "-" }?.let {
            add(DecisionPillModel("모델 점수", it.value, it.label, it.tone))
        }
        if (request.isEtfDetail()) {
            eventMetricValue(request, "총보수")?.let {
                add(DecisionPillModel("총보수", it, "ETF 비용", DetailTone.Primary))
            }
            eventMetricValue(request, "AUM", "운용규모")?.let {
                add(DecisionPillModel("AUM", it, "운용 규모", DetailTone.Neutral))
            }
            return@buildList
        }
        metrics.firstOrNull { normalizedLabel(it.label).contains("성장") || normalizedLabel(it.label).contains("growth") }?.let {
            add(DecisionPillModel("성장성", it.value, it.label, it.tone))
        }
        metrics.firstOrNull { normalizedLabel(it.label).contains("roic") || normalizedLabel(it.label).contains("roe") }?.let {
            add(DecisionPillModel("수익성", it.value, it.label, it.tone))
        }
        val pe = info?.forwardPe ?: info?.peRatio
        if (pe != null) {
            add(DecisionPillModel("PER", "%.1fx".format(pe), if (info?.forwardPe == null) "Trailing" else "Forward", DetailTone.Neutral))
        }
    }.take(4)
}

internal fun decisionSummaryText(request: DetailRequest, detail: StockDetail?): String {
    request.signals.firstOrNull()?.let { return "${it.title}: ${it.detail}" }
    if (request.isEtfDetail()) {
        eventMetricValue(request, "테마", "유형")?.let {
            return "$it ETF · 가격 차트, 구성 비중, 총보수와 AUM을 중심으로 확인하세요."
        }
        return "ETF는 기업 재무보다 추종 지수, 구성 종목, 비용, 가격 흐름을 중심으로 판단합니다."
    }
    val scoreMetric = request.sections.flatMap { it.metrics }.firstOrNull { isScoreMetric(it.label) && it.value != "-" }
    if (scoreMetric != null) {
        return "${scoreMetric.label} ${scoreMetric.value}를 기준으로 후보군 내 상대 매력을 먼저 확인하고, 차트와 밸류에이션으로 진입 타이밍을 보완합니다."
    }
    if (detail?.info?.currentPrice != null) {
        return "현재가, 52주 위치, 밸류에이션을 함께 확인해 가격 위치와 기본 체력을 점검합니다."
    }
    return "상세 데이터가 도착하면 가격, 밸류에이션, 성장성, 리스크를 순서대로 정리합니다."
}

internal fun investmentProfileFitSummary(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem?
): InvestmentProfileFitSummary {
    val allText = detailProfileEvidenceText(request, detail)
    val matchedStyle = profile.style.isBlank() || profileStyleMatches(profile.style, allText)
    val conflicts = profile.avoidances.filter { profileAvoidanceMatches(it, allText) }
    val hasWarning = allText.contains("주의") ||
        allText.contains("부담") ||
        allText.contains("risk") ||
        request.signals.any { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }
    val thesis = watchItem?.investmentThesis
    val thesisQuality = thesis?.quality
    val score = (
        42 +
            if (matchedStyle) 18 else -6 +
            if (conflicts.isEmpty()) 16 else -14 +
            if (!hasWarning) 10 else -6 +
            if (profile.horizon.isNotBlank()) 6 else 0 +
            when {
                thesisQuality == null -> 0
                thesisQuality.percent >= 80 -> 12
                thesisQuality.percent > 0 -> 5
                else -> -4
            } +
            if (profile.dropResponse.isNotBlank() && profile.overheatedResponse.isNotBlank()) 6 else 0
        ).coerceIn(0, 100)
    val tone = when {
        score >= 72 -> QuantGreen
        score >= 48 -> Color(0xFF2563EB)
        else -> QuantWarning
    }
    val positives = buildList {
        if (matchedStyle && profile.style.isNotBlank()) add("${profile.style} 관점과 맞는 근거가 있습니다.")
        if (!hasWarning) add("핵심 신호에서 즉시 멈출 경고는 적습니다.")
        if (profile.horizon.isNotBlank()) add("${profile.horizon} 관찰 기준으로 복기할 수 있습니다.")
        if ((thesisQuality?.percent ?: 0) >= 80) add("관심 가설이 충분히 채워져 복기 기준이 선명합니다.")
    }.ifEmpty { listOf("확정 근거보다 후보 비교와 확인 조건이 먼저입니다.") }
    val cautions = buildList {
        if (!matchedStyle && profile.style.isNotBlank()) add("${profile.style} 관점의 직접 근거는 약합니다.")
        if (hasWarning) add("주의 신호가 있어 진입보다 조건 확인이 먼저입니다.")
        if (conflicts.isNotEmpty()) add("${conflicts.take(2).joinToString(" · ")} 회피 조건과 충돌합니다.")
        if (thesis == null || thesis.isEmpty) add("관심 가설이 연결되지 않아 복기 기준이 비어 있습니다.")
        else if (thesisQuality != null && thesisQuality.percent < 80) add("가설 완성도가 ${thesisQuality.percent}%라 빠진 항목을 채워야 합니다.")
    }.ifEmpty { listOf("뚜렷한 주의 신호는 아직 적지만 가격과 실적 이벤트는 계속 확인하세요.") }
    return InvestmentProfileFitSummary(
        score = score,
        label = when {
            score >= 72 -> "내 기준과 잘 맞습니다"
            score >= 48 -> "조건부로 더 볼 만합니다"
            else -> "회피 조건을 먼저 확인하세요"
        },
        tone = tone,
        positiveReasons = positives,
        cautionReasons = cautions,
        checklist = listOf(
            FitChecklistItem("확인 조건", thesis?.checkCondition?.isNotBlank() == true),
            FitChecklistItem("무효 조건", thesis?.invalidationCondition?.isNotBlank() == true),
            FitChecklistItem("관찰 기간", profile.horizon.isNotBlank() || thesis?.horizon?.isNotBlank() == true),
            FitChecklistItem("주의 신호", cautions.isNotEmpty())
        ),
        thesisLine = thesis?.inlineSummary,
        invalidationLine = thesis?.invalidationCondition?.takeIf { it.isNotBlank() }
    )
}

internal fun investmentProfileFitRows(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?
): List<InvestmentProfileFitModel> {
    if (!profile.isConfigured) return emptyList()
    val metricText = request.sections
        .flatMap { it.metrics }
        .joinToString(" ") { "${it.label} ${it.value}" }
    val signalText = request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    val infoText = listOfNotNull(
        detail?.info?.forwardPe?.let { "per $it" },
        detail?.info?.peRatio?.let { "per $it" },
        detail?.info?.debtToEquity?.let { "debt $it" },
        detail?.info?.revenueGrowth?.let { "growth $it" },
        detail?.info?.profitMargin?.let { "margin $it" }
    ).joinToString(" ")
    val allText = "$metricText $signalText $infoText".lowercase(Locale.US)

    return buildList {
        if (profile.riskTolerance.isNotBlank()) {
            val hasWarning = allText.contains("주의") ||
                allText.contains("부담") ||
                allText.contains("risk") ||
                request.signals.any { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }
            add(
                InvestmentProfileFitModel(
                    title = "${profile.riskTolerance} 기준",
                    detail = if (hasWarning) {
                        "주의 신호가 있어 진입보다 확인 조건을 먼저 잡는 편이 맞습니다."
                    } else {
                        "현재 핵심 신호에는 큰 경고가 적어 다음 확인 지표로 넘어갈 수 있습니다."
                    },
                    icon = if (hasWarning) LucideIcon.TriangleAlert else LucideIcon.ShieldCheck,
                    color = if (hasWarning) QuantWarning else QuantGreen
                )
            )
        }
        if (profile.style.isNotBlank()) {
            val matched = profileStyleMatches(profile.style, allText)
            add(
                InvestmentProfileFitModel(
                    title = "${profile.style} 관점",
                    detail = if (matched) {
                        "선호 스타일과 맞는 근거가 일부 보입니다. 반대 지표까지 같이 비교하세요."
                    } else {
                        "선호 스타일과 직접 맞는 근거가 약합니다. 점수보다 핵심 지표를 먼저 확인하세요."
                    },
                    icon = if (matched) LucideIcon.Target else LucideIcon.SlidersHorizontal,
                    color = if (matched) Color(0xFF2563EB) else Color(0xFF64748B)
                )
            )
        }
        if (profile.avoidances.isNotEmpty()) {
            val conflicts = profile.avoidances.filter { profileAvoidanceMatches(it, allText) }
            add(
                InvestmentProfileFitModel(
                    title = "회피 신호",
                    detail = if (conflicts.isEmpty()) {
                        "설정한 회피 조건과 직접 충돌하는 신호는 아직 뚜렷하지 않습니다."
                    } else {
                        "${conflicts.take(2).joinToString(", ")} 조건이 보입니다. 투자 가설의 무효 조건으로 남겨두세요."
                    },
                    icon = if (conflicts.isEmpty()) LucideIcon.ShieldCheck else LucideIcon.TriangleAlert,
                    color = if (conflicts.isEmpty()) QuantGreen else QuantWarning
                )
            )
        }
        if (profile.horizon.isNotBlank()) {
            add(
                InvestmentProfileFitModel(
                    title = "${profile.horizon} 관찰",
                    detail = if (profile.horizon.contains("1개월")) {
                        "짧은 기간이면 가격 위치와 실적 이벤트를 먼저 확인하세요."
                    } else {
                        "긴 기간이면 성장률, 마진, 재무 리스크가 함께 유지되는지 보세요."
                    },
                    icon = LucideIcon.CalendarClock,
                    color = Color(0xFF2563EB)
                )
            )
        }
    }.take(3)
}

internal fun detailProfileEvidenceText(request: DetailRequest, detail: StockDetail?): String {
    val metricText = request.sections
        .flatMap { it.metrics }
        .joinToString(" ") { "${it.label} ${it.value}" }
    val signalText = request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    val infoText = listOfNotNull(
        detail?.info?.forwardPe?.let { "per $it" },
        detail?.info?.peRatio?.let { "per $it" },
        detail?.info?.debtToEquity?.let { "debt $it" },
        detail?.info?.revenueGrowth?.let { "growth $it" },
        detail?.info?.profitMargin?.let { "margin $it" }
    ).joinToString(" ")
    return "$metricText $signalText $infoText".lowercase(Locale.US)
}

internal fun profileStyleMatches(style: String, text: String): Boolean {
    return when {
        style.contains("성장") -> text.contains("성장") || text.contains("growth") || text.contains("매출")
        style.contains("가치") -> text.contains("value") || text.contains("저평가") || text.contains("per") || text.contains("pbr")
        style.contains("배당") -> text.contains("배당") || text.contains("dividend")
        style.contains("퀄리티") -> text.contains("roic") || text.contains("roe") || text.contains("마진") || text.contains("quality")
        style.contains("모멘텀") -> text.contains("모멘텀") || text.contains("거래량") || text.contains("surge") || text.contains("momentum")
        else -> false
    }
}

internal fun profileAvoidanceMatches(avoidance: String, text: String): Boolean {
    return when {
        avoidance.contains("급등락") -> text.contains("급변") || text.contains("거래량") || text.contains("vol") || text.contains("momentum")
        avoidance.contains("적자") -> text.contains("적자") || text.contains("음수") || text.contains("negative")
        avoidance.contains("고평가") -> text.contains("고평가") || text.contains("per") || text.contains("밸류")
        avoidance.contains("부채") -> text.contains("부채") || text.contains("debt")
        avoidance.contains("거래량") -> text.contains("거래량") || text.contains("volume")
        else -> false
    }
}
