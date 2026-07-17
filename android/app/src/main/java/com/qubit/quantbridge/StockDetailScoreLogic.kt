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
