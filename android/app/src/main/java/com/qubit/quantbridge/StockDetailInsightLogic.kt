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

@Composable
internal fun personalInsightColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Negative -> QuantNegative
        DetailTone.Warning -> QuantWarning
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun detailNoBuyFirstRows(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem?,
    comparisonSelected: Boolean
): List<NoBuyFirstRowModel> {
    val counter = detailCounterEvidenceRows(profile, request, detail, watchItem).first()
    val stillWorth = detailStillWorthReason(request, detail)
    val checkNow = detailOneThingToCheck(request, detail, watchItem, comparisonSelected)
    val personal = personalizedStockInterpretation(profile, request, detail)
    return listOf(
        NoBuyFirstRowModel(
            label = "이 종목을 보면 안 되는 이유",
            title = counter.title,
            detail = counter.detail,
            icon = counter.icon,
            color = counter.color
        ),
        NoBuyFirstRowModel(
            label = "그래도 볼 만한 이유",
            title = stillWorth.title,
            detail = stillWorth.detail,
            icon = stillWorth.icon,
            color = stillWorth.color
        ),
        NoBuyFirstRowModel(
            label = "지금 확인해야 할 한 가지",
            title = checkNow.title,
            detail = checkNow.detail,
            icon = checkNow.icon,
            color = checkNow.color
        ),
        NoBuyFirstRowModel(
            label = "내 성향 기준 결론",
            title = personal.headline,
            detail = personal.action,
            icon = LucideIcon.Target,
            color = personalInsightStaticColor(personal.tone)
        )
    )
}

internal fun detailStillWorthReason(
    request: DetailRequest,
    detail: StockDetail?
): NoBuyFirstRowModel {
    val metrics = request.sections.flatMap { it.metrics }
    request.signals.firstOrNull { it.tone == DetailTone.Positive || it.tone == DetailTone.Primary }?.let { signal ->
        return NoBuyFirstRowModel(
            label = "",
            title = signal.title,
            detail = signal.detail,
            icon = LucideIcon.Lightbulb,
            color = guardrailToneColor(signal.tone)
        )
    }
    metrics.firstOrNull { isScoreMetric(it.label) && it.value != "-" }?.let { metric ->
        return NoBuyFirstRowModel(
            label = "",
            title = "${metric.label} ${metric.value}",
            detail = "점수는 출발점일 뿐입니다. 같은 섹터 후보와 비교할 때만 의미 있게 보세요.",
            icon = LucideIcon.BarChart3,
            color = Color(0xFF2563EB)
        )
    }
    if (request.isEtfDetail()) {
        eventMetricValue(request, "테마", "유형")?.let { theme ->
            return NoBuyFirstRowModel(
                label = "",
                title = "$theme 노출",
                detail = "ETF는 개별 기업보다 추종 대상, 비용, 구성 비중이 내 목적과 맞는지 확인할 수 있습니다.",
                icon = LucideIcon.PieChart,
                color = Color(0xFF2563EB)
            )
        }
    }
    detail?.info?.revenueGrowth?.takeIf { it.isFinite() && it > 0.0 }?.let { growth ->
        return NoBuyFirstRowModel(
            label = "",
            title = "성장률 ${pct(growth)}",
            detail = "성장 근거는 있습니다. 다만 가격 부담과 지속 가능성을 같이 확인해야 합니다.",
            icon = LucideIcon.TrendingUp,
            color = QuantGreen
        )
    }
    return NoBuyFirstRowModel(
        label = "",
        title = "비교 후보로만 유지",
        detail = "단독 결론보다 비교 바구니에 넣고 상대 매력과 주의 신호를 같이 보세요.",
        icon = LucideIcon.GitCompare,
        color = Color(0xFF2563EB)
    )
}

internal fun detailOneThingToCheck(
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem?,
    comparisonSelected: Boolean
): NoBuyFirstRowModel {
    if (!comparisonSelected) {
        return NoBuyFirstRowModel(
            label = "",
            title = "비교 후보 1개 추가",
            detail = "${request.name}만 보고 판단하지 말고 비슷한 후보와 나란히 놓은 뒤 우선순위를 정하세요.",
            icon = LucideIcon.GitCompare,
            color = QuantWarning
        )
    }
    if (watchItem?.investmentThesis?.invalidationCondition.isNullOrBlank()) {
        return NoBuyFirstRowModel(
            label = "",
            title = "무효 조건 적기",
            detail = "무엇이 깨지면 이 종목을 그만 볼지 먼저 정해야 좋은 뉴스만 보는 실수를 줄일 수 있습니다.",
            icon = LucideIcon.Edit,
            color = QuantWarning
        )
    }
    detailActionPlanRows(request, detail).firstOrNull()?.let { row ->
        return NoBuyFirstRowModel(
            label = "",
            title = row.title,
            detail = row.detail,
            icon = LucideIcon.ListOrdered,
            color = guardrailToneColor(row.tone)
        )
    }
    return NoBuyFirstRowModel(
        label = "",
        title = "다음 실적 또는 가격 조건",
        detail = "새 데이터가 나올 때까지 결론을 서두르지 말고 확인 조건이 충족되는지만 추적하세요.",
        icon = LucideIcon.CalendarClock,
        color = Color(0xFF2563EB)
    )
}

internal fun personalInsightStaticColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Negative -> QuantNegative
        DetailTone.Warning -> QuantWarning
        DetailTone.Primary -> Color(0xFF2563EB)
        DetailTone.Neutral -> Color(0xFF64748B)
    }
}

internal fun detailCounterEvidenceRows(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem?
): List<DetailGuardrailRow> {
    val allText = detailProfileEvidenceText(request, detail)
    val thesis = watchItem?.investmentThesis
    val info = detail?.info
    val conflicts = if (profile.isConfigured) profile.avoidances.filter { profileAvoidanceMatches(it, allText) } else emptyList()
    val warningSignals = request.signals.filter { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }
    val pe = info?.forwardPe ?: info?.peRatio
    val dailyChange = info?.dailyChangePct?.takeIf { it.isFinite() }
    return buildList {
        if (thesis?.invalidationCondition.isNullOrBlank()) {
            add(
                DetailGuardrailRow(
                    "무효 조건 없음",
                    "언제 이 생각을 버릴지 정하지 않으면 좋은 뉴스만 보게 됩니다. 관심 가설에 틀렸다고 볼 조건을 먼저 남기세요.",
                    LucideIcon.TriangleAlert,
                    QuantWarning
                )
            )
        } else {
            add(
                DetailGuardrailRow(
                    "무효 조건",
                    thesis.invalidationCondition.trim(),
                    LucideIcon.X,
                    QuantWarning
                )
            )
        }
        if (conflicts.isNotEmpty()) {
            add(
                DetailGuardrailRow(
                    "내 회피 조건 충돌",
                    "${conflicts.take(2).joinToString(" · ")} 신호가 보입니다. 점수보다 회피 기준을 먼저 확인하세요.",
                    LucideIcon.ShieldCheck,
                    QuantWarning
                )
            )
        }
        warningSignals.firstOrNull()?.let { signal ->
            add(
                DetailGuardrailRow(
                    "주의 신호",
                    "${signal.title}: ${signal.detail}",
                    LucideIcon.TriangleAlert,
                    guardrailToneColor(signal.tone)
                )
            )
        }
        if (pe != null && pe > 35.0) {
            add(
                DetailGuardrailRow(
                    "밸류에이션 부담",
                    "PER ${"%.1f".format(pe)}x 구간입니다. 성장률과 마진이 같이 받쳐주는지 비교 후보와 나란히 보세요.",
                    LucideIcon.TrendingUp,
                    QuantWarning
                )
            )
        }
        if (dailyChange != null && abs(dailyChange) >= 0.04) {
            add(
                DetailGuardrailRow(
                    "가격 급변",
                    "최근 변동 ${pct(dailyChange)}입니다. 오늘의 움직임이 가설을 바꾸는지, 단순 소음인지 분리하세요.",
                    if (dailyChange >= 0.0) LucideIcon.TrendingUp else LucideIcon.TrendingDown,
                    if (dailyChange >= 0.0) QuantWarning else QuantNegative
                )
            )
        }
    }.ifEmpty {
        listOf(
            DetailGuardrailRow(
                "뚜렷한 경고는 낮음",
                "그래도 가격 위치, 실적 이벤트, 비교 후보를 확인한 뒤 관심 가설을 유지할지 정하세요.",
                LucideIcon.ShieldCheck,
                QuantGreen
            )
        )
    }.take(4)
}

internal fun detailMistakeCoachRows(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem?,
    comparisonSelected: Boolean
): List<DetailGuardrailRow> {
    val thesis = watchItem?.investmentThesis
    val dailyChange = detail?.info?.dailyChangePct?.takeIf { it.isFinite() }
    return buildList {
        if (!comparisonSelected) {
            add(
                DetailGuardrailRow(
                    "단독 판단 방지",
                    "${request.name}만 보지 말고 2~4개 후보를 비교한 뒤 우선순위를 정하세요.",
                    LucideIcon.GitCompare,
                    QuantWarning
                )
            )
        }
        if (thesis == null || thesis.isEmpty) {
            add(
                DetailGuardrailRow(
                    "관심 이유 비어 있음",
                    "왜 보는지, 무엇이 바뀌면 생각을 고칠지 기록해야 나중에 판단을 복기할 수 있습니다.",
                    LucideIcon.Edit,
                    QuantWarning
                )
            )
        } else if (thesis.quality.percent < 80) {
            add(
                DetailGuardrailRow(
                    "가설 미완성",
                    "${thesis.quality.missingFields.take(2).joinToString(" · ")} 항목을 채우면 홈 브리핑의 우선순위가 더 정확해집니다.",
                    LucideIcon.Lightbulb,
                    QuantWarning
                )
            )
        }
        if (dailyChange != null && dailyChange > 0.04 && (profile.avoidances.any { it.contains("급등락") } || profile.riskTolerance.contains("낮"))) {
            add(
                DetailGuardrailRow(
                    "추격매수 주의",
                    "상승폭 ${pct(dailyChange)}가 내 위험 기준과 충돌할 수 있습니다. 확인 조건 전에는 관찰로 남기세요.",
                    LucideIcon.TrendingUp,
                    QuantWarning
                )
            )
        }
        if (profile.isConfigured && (profile.dropResponse.isBlank() || profile.overheatedResponse.isBlank())) {
            add(
                DetailGuardrailRow(
                    "행동 원칙 보강",
                    "하락장 또는 과열장에서 어떻게 행동할지 성향 진단에 남기면 홈의 소음 필터가 더 선명해집니다.",
                    LucideIcon.SlidersHorizontal,
                    Color(0xFF2563EB)
                )
            )
        }
    }.ifEmpty {
        listOf(
            DetailGuardrailRow(
                "오늘의 원칙",
                "비교 결과, 무효 조건, 관찰 기간이 모두 맞을 때만 다음 행동으로 넘어가세요.",
                LucideIcon.ShieldCheck,
                QuantGreen
            )
        )
    }.take(3)
}

internal fun guardrailToneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Negative -> QuantNegative
        DetailTone.Warning -> QuantWarning
        DetailTone.Primary -> Color(0xFF2563EB)
        DetailTone.Neutral -> Color(0xFF64748B)
    }
}

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

internal fun detailActionPlanRows(request: DetailRequest, detail: StockDetail?): List<DetailActionPlanModel> {
    val info = detail?.info
    val metrics = request.sections.flatMap { it.metrics }
    if (request.isEtfDetail()) {
        return buildList {
            request.signals.firstOrNull()?.let { signal ->
                add(
                    DetailActionPlanModel(
                        "ETF 성격 확인",
                        "${signal.title}을 기준으로 추종 대상과 사용 목적을 먼저 확인하세요.",
                        signal.tone
                    )
                )
            }
            val current = info?.currentPrice
            val low = info?.week52Low
            val high = info?.week52High
            if (current != null && low != null && high != null && high > low) {
                val position = ((current - low) / (high - low)).coerceIn(0.0, 1.0)
                add(
                    DetailActionPlanModel(
                        when {
                            position >= 0.75 -> "고점권 매수 주의"
                            position <= 0.25 -> "저점권 반등 확인"
                            else -> "가격 위치 확인"
                        },
                        "현재가는 52주 범위의 ${"%.0f".format(position * 100)}% 지점입니다. 추종 지수 흐름과 함께 보세요.",
                        if (position >= 0.75) DetailTone.Warning else DetailTone.Primary
                    )
                )
            } else {
                add(
                    DetailActionPlanModel(
                        "차트 기간 비교",
                        "1개월부터 5년까지 수익률과 변동성을 바꿔 보며 진입 구간을 판단하세요.",
                        DetailTone.Primary
                    )
                )
            }
            eventMetricValue(request, "총보수")?.let { fee ->
                add(
                    DetailActionPlanModel(
                        "비용 비교",
                        "총보수 $fee 를 같은 지수 또는 같은 테마 ETF와 비교하세요.",
                        DetailTone.Positive
                    )
                )
            }
        }.take(3)
    }
    return buildList {
        request.signals.firstOrNull()?.let { signal ->
            add(
                DetailActionPlanModel(
                    "핵심 신호 먼저 확인",
                    "${signal.title}을 기준으로 핵심 신호와 가격 위치를 함께 확인하세요.",
                    signal.tone
                )
            )
        } ?: metrics.firstOrNull { isScoreMetric(it.label) && it.value != "-" }?.let { metric ->
            add(
                DetailActionPlanModel(
                    "모델 점수 확인",
                    "${metric.label} ${metric.value}가 어떤 팩터에서 나온 값인지 아래 근거를 먼저 확인하세요.",
                    metric.tone
                )
            )
        }

        val current = info?.currentPrice
        val low = info?.week52Low
        val high = info?.week52High
        if (current != null && low != null && high != null && high > low) {
            val position = ((current - low) / (high - low)).coerceIn(0.0, 1.0)
            add(
                DetailActionPlanModel(
                    when {
                        position >= 0.75 -> "고점권 진입 타이밍 주의"
                        position <= 0.25 -> "저점권 반등 조건 확인"
                        else -> "가격 위치 중립"
                    },
                    "현재가는 52주 범위의 ${"%.0f".format(position * 100)}% 지점입니다. 차트에서 추세와 지지선을 같이 보세요.",
                    if (position >= 0.75) DetailTone.Warning else DetailTone.Primary
                )
            )
        } else if (current != null) {
            add(
                DetailActionPlanModel(
                    "가격 기준 보강 필요",
                    "현재가는 있으나 52주 범위가 부족합니다. 차트 기간을 바꿔 가격 위치를 직접 확인하세요.",
                    DetailTone.Neutral
                )
            )
        }

        val pe = info?.forwardPe ?: info?.peRatio
        if (pe != null) {
            add(
                DetailActionPlanModel(
                    if (pe >= 35.0) "밸류에이션 부담 확인" else "밸류에이션 비교",
                    "PER ${"%.1f".format(pe)}x입니다. 성장률과 마진이 이 배수를 정당화하는지 비교하세요.",
                    if (pe >= 35.0) DetailTone.Warning else DetailTone.Neutral
                )
            )
        } else {
            add(
                DetailActionPlanModel(
                    "PER 공백 확인",
                    stockValuationUnavailableReason(info),
                    DetailTone.Neutral
                )
            )
        }

        if (isEmpty()) {
            add(
                DetailActionPlanModel(
                    "상세 데이터 대기",
                    "가격, 팩터, 실적 데이터가 도착하면 확인 순서를 자동으로 정리합니다.",
                    DetailTone.Neutral
                )
            )
        }
    }.take(3)
}

internal fun earningsEventPlanRows(request: DetailRequest): List<DetailActionPlanModel> {
    if (request.isEtfDetail()) return emptyList()
    if (!isEarningsEventRequest(request)) return emptyList()
    val daysText = eventMetricValue(request, "남은 기간", "경과일")
    val dateText = eventMetricValue(request, "예정일", "발표일")
    val preEvent = eventMetricValue(request, "예정일") != null ||
        daysText?.contains("후") == true ||
        daysText?.contains("오늘") == true

    return buildList {
        if (preEvent) {
            add(
                DetailActionPlanModel(
                    "발표 전",
                    "${daysText ?: "예정일"} ${dateText ?: ""} 기준으로 포지션 크기, 변동성, 컨센서스 변화를 먼저 확인하세요.",
                    DetailTone.Warning
                )
            )
            add(
                DetailActionPlanModel(
                    "발표 직후",
                    "EPS보다 매출, 마진, 가이던스, 거래량 반응을 함께 보고 하루짜리 급등락과 추세 변화를 분리하세요.",
                    DetailTone.Primary
                )
            )
        } else {
            add(
                DetailActionPlanModel(
                    "발표 후 추적",
                    "${daysText ?: "발표 후"} 구간입니다. 실적 서프라이즈가 가격과 거래량에 계속 반영되는지 확인하세요.",
                    DetailTone.Primary
                )
            )
        }
        add(
            DetailActionPlanModel(
                "다음 판단",
                "실적 이벤트 전후에는 기존 모델 점수와 가격 반응이 같은 방향인지 확인한 뒤 관심 유지 여부를 정하세요.",
                DetailTone.Neutral
            )
        )
    }
}

internal fun isEarningsEventRequest(request: DetailRequest): Boolean {
    val labels = request.sections.flatMap { it.metrics }.map { normalizedLabel(it.label) }
    val signalText = request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    return labels.any { it in setOf("예정일", "남은기간", "발표일", "경과일", "eps") } ||
        signalText.contains("실적") ||
        signalText.contains("earnings", ignoreCase = true)
}

internal fun eventMetricValue(request: DetailRequest, vararg labels: String): String? {
    val normalized = labels.map(::normalizedLabel).toSet()
    return request.sections
        .flatMap { it.metrics }
        .firstOrNull { metric ->
            normalizedLabel(metric.label) in normalized &&
                metric.value.isNotBlank() &&
                metric.value != "-"
        }
        ?.value
}

internal fun scoreRationaleRows(request: DetailRequest): List<ScoreRationaleRow> {
    val metricRows = request.sections
        .flatMap { it.metrics }
        .filter { metric ->
            metric.value.isNotBlank() &&
                metric.value != "-" &&
                normalizedLabel(metric.label) !in setOf("시장", "섹터", "분류", "상태") &&
                !normalizedLabel(metric.label).contains("시가총액")
        }
        .take(5)
        .map { metric ->
            ScoreRationaleRow(
                title = metric.label,
                value = metric.value,
                detail = scoreMetricExplanation(metric),
                tone = metric.tone
            )
        }
    if (metricRows.isNotEmpty()) return metricRows
    return request.signals.take(3).map {
        ScoreRationaleRow(it.title, "근거", it.detail, it.tone)
    }
}

internal fun missingStockDataReasons(request: DetailRequest, info: StockInfo?): List<MissingDataReason> {
    val metricLabels = request.sections.flatMap { it.metrics }.map { normalizedLabel(it.label) }
    return buildList {
        if (request.isEtfDetail()) {
            if (info?.currentPrice == null) {
                add(MissingDataReason("가격 데이터 대기", "ETF 현재가가 도착하면 차트와 수익률을 바로 확인할 수 있습니다."))
            }
            if (info?.week52Low == null || info?.week52High == null) {
                add(MissingDataReason("가격 범위 데이터 대기", "52주 범위가 도착하면 고점권인지 저점권인지 표시합니다."))
            }
            return@buildList
        }
        if (info?.peRatio == null && info?.forwardPe == null) {
            add(MissingDataReason("PER 계산 불가", stockValuationUnavailableReason(info)))
        }
        if (info?.priceToBook == null) {
            add(MissingDataReason("PBR 없음", "순자산 또는 주가 기준 데이터가 아직 상세 응답에 포함되지 않았습니다."))
        }
        if (info?.returnOnEquity == null && metricLabels.none { it.contains("roe") || it.contains("roic") }) {
            add(MissingDataReason("수익성 보강 필요", "ROE/ROIC 계열 지표가 부족해 퀄리티 판단은 제한적입니다."))
        }
        if (info?.totalRevenue == null && info?.revenueGrowth == null && metricLabels.none { it.contains("성장") || it.contains("growth") }) {
            add(MissingDataReason("성장 데이터 부족", "매출 또는 성장률이 없어 성장성 판단은 후보 점수/기존 팩터에 의존합니다."))
        }
        if (info?.freeCashflow == null && metricLabels.none { it.contains("fcf") }) {
            add(MissingDataReason("현금흐름 없음", "FCF가 없으면 이익의 현금 전환 품질을 앱 안에서 바로 검증하기 어렵습니다."))
        }
    }
}

internal fun stockValuationUnavailableReason(info: StockInfo?): String {
    if (info == null) return "상세 응답 대기"
    if (info.currentPrice == null) return "시세 데이터 부족"
    if (info.priceToBook != null) return "순이익/EPS 부족"
    if ((info.profitMargin ?: 0.0) <= 0.0 && info.profitMargin != null) return "적자 또는 순이익률 음수"
    return "EPS/순이익 데이터 부족"
}

internal fun scoreMetricExplanation(metric: DetailMetric): String {
    val label = normalizedLabel(metric.label)
    return when {
        label.contains("기대") || label.contains("return") -> "모델 기대수익 또는 발표 후 수익 반응으로 후보 우선순위에 직접 반영됩니다."
        label.contains("score") || label.contains("점수") || label.contains("시그널") -> "여러 팩터를 합산한 상대 점수라 후보군 안에서의 순위를 판단하는 핵심 축입니다."
        label.contains("roic") || label.contains("roe") -> "자본 효율성과 수익성을 보여줘 퀄리티 팩터에 반영됩니다."
        label.contains("growth") || label.contains("성장") -> "매출 또는 이익 성장 흐름이 좋아질수록 성장 팩터에 유리합니다."
        label.contains("margin") || label.contains("마진") -> "마진은 사업의 체력과 가격 결정력을 보는 보조 근거입니다."
        else -> "후보 선정 시 함께 비교하는 보조 지표입니다."
    }
}

internal fun isScoreMetric(label: String): Boolean {
    val normalized = normalizedLabel(label)
    return normalized.contains("score") || normalized.contains("점수") || normalized == "signal" || normalized == "시그널"
}

internal fun normalizedLabel(value: String): String {
    return value
        .trim()
        .lowercase(Locale.US)
        .replace(" ", "")
        .replace("_", "")
        .replace("-", "")
}

internal fun DetailRequest.isEtfDetail(): Boolean {
    val sectionText = sections.joinToString(" ") { section ->
        buildString {
            append(section.title)
            append(' ')
            append(section.metrics.joinToString(" ") { metric -> "${metric.label} ${metric.value}" })
        }
    }
    val signalText = signals.joinToString(" ") { "${it.title} ${it.detail}" }
    return holdings.isNotEmpty() ||
        name.contains("ETF", ignoreCase = true) ||
        ticker.contains("ETF", ignoreCase = true) ||
        sectionText.contains("ETF", ignoreCase = true) ||
        signalText.contains("ETF", ignoreCase = true) ||
        sectionText.contains("총보수") ||
        sectionText.contains("운용 규모") ||
        sectionText.contains("운용규모")
}

@Composable
internal fun detailToneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
}

internal fun actionPlanToneIcon(tone: DetailTone): LucideIcon {
    return when (tone) {
        DetailTone.Positive -> LucideIcon.TrendingUp
        DetailTone.Warning -> LucideIcon.TriangleAlert
        DetailTone.Negative -> LucideIcon.TrendingDown
        DetailTone.Primary -> LucideIcon.Target
        DetailTone.Neutral -> LucideIcon.Activity
    }
}
