package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal val DetailPriceMetricLabels = setOf("현재가", "최근가", "가격", "Price", "Last Price")
internal val DetailTodayMetricLabels = setOf("오늘", "전장", "당일 흐름", "하루 변동률", "일간", "일일", "1D", "Today", "Daily")

internal fun detailMetricLabelMatches(label: String, candidates: Set<String>): Boolean {
    val clean = label.trim().lowercase(Locale.US)
    return candidates.any { candidate ->
        val target = candidate.trim().lowercase(Locale.US)
        clean == target || clean.contains(target)
    }
}

internal fun DetailRequest.reconciledWithDetail(detail: StockDetail?): DetailRequest {
    val info = detail?.info ?: return this
    val current = info.currentPrice?.takeIf { it.isFinite() } ?: return this
    val changePct = detailDailyChangePct(info, current)
    val dailyChangeLabel = info.dailyChangeHorizon?.trim()?.takeIf { it.isNotEmpty() } ?: "오늘"
    val priceMetric = DetailMetric("현재가", fmtPx(current, currency), DetailTone.Primary)
    val todayMetric = changePct?.let { DetailMetric(dailyChangeLabel, pct(it), returnTone(it)) }
    var hasPriceMetric = false
    var hasTodayMetric = false

    val reconciledSections = sections.map { section ->
        section.copy(
            metrics = section.metrics.map { metric ->
                when {
                    detailMetricLabelMatches(metric.label, DetailPriceMetricLabels) -> {
                        hasPriceMetric = true
                        priceMetric.copy(label = metric.label)
                    }
                    detailMetricLabelMatches(metric.label, DetailTodayMetricLabels) -> {
                        hasTodayMetric = true
                        if (todayMetric != null) {
                            val label = if (todayMetric.label == "오늘") metric.label else todayMetric.label
                            todayMetric.copy(label = label)
                        } else {
                            metric.copy(value = "-", tone = DetailTone.Neutral)
                        }
                    }
                    else -> metric
                }
            }
        )
    }.toMutableList()

    val leadingMetrics = buildList {
        if (!hasPriceMetric) add(priceMetric)
        if (!hasTodayMetric && todayMetric != null) add(todayMetric)
    }
    if (leadingMetrics.isNotEmpty()) {
        if (reconciledSections.isEmpty()) {
            reconciledSections += DetailSection("시세", leadingMetrics)
        } else {
            val first = reconciledSections.first()
            reconciledSections[0] = first.copy(metrics = leadingMetrics + first.metrics)
        }
    }

    val reconciledSignals = signals.map { signal ->
        if (signal.title == "확인할 숫자") {
            if (todayMetric != null) {
                signal.copy(
                    detail = "상세 시세 기준 당일 흐름 ${todayMetric.value}, 1개월 흐름은 차트와 같은 테마 기업으로 비교하세요.",
                    tone = todayMetric.tone
                )
            } else {
                signal.copy(detail = "상세 시세 기준 당일 흐름과 1개월 흐름을 같은 테마 기업과 비교하세요.")
            }
        } else {
            signal
        }
    }

    return copy(sections = reconciledSections, signals = reconciledSignals)
}

internal fun detailDailyChangePct(info: StockInfo, current: Double): Double? {
    info.dailyChangePct?.takeIf { it.isFinite() }?.let { return it }
    return info.prevClose
        ?.takeIf { it.isFinite() && it != 0.0 }
        ?.let { current / it - 1.0 }
}

internal fun detailDailyChangeAmount(info: StockInfo, current: Double, changePct: Double?): Double? {
    info.prevClose?.takeIf { it.isFinite() }?.let { return current - it }
    val safeChange = changePct?.takeIf { it.isFinite() && it > -1.0 } ?: return null
    val previous = current / (1.0 + safeChange)
    return current - previous
}
