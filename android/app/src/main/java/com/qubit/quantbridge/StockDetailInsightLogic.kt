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
