package com.qubit.quantbridge

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantMuted
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun chartVisiblePoints(points: List<PricePoint>, period: ChartPeriod): List<PricePoint> {
    val clean = points.filter { point ->
        point.open.isFinite() &&
            point.high.isFinite() &&
            point.low.isFinite() &&
            point.close.isFinite() &&
            point.high >= point.low &&
            point.close > 0.0
    }
    val visible = clean.takeLast(min(clean.size, period.maxPoints))
    return when (period) {
        ChartPeriod.SixMonths -> aggregateChartPointChunks(visible, 2)
        ChartPeriod.OneYear -> aggregateChartPoints(visible, ::weeklyChartBucket)
        ChartPeriod.ThreeYears -> {
            val firstWeekStart = visible.firstOrNull()?.let { parseChartDate(it.date)?.chartWeekStart() }
            if (firstWeekStart == null) visible else aggregateChartPoints(visible) { threeWeekChartBucket(it, firstWeekStart) }
        }
        ChartPeriod.FiveYears -> aggregateChartPoints(visible, ::monthlyChartBucket)
        else -> visible
    }
}

internal fun aggregateChartPointChunks(points: List<PricePoint>, chunkSize: Int): List<PricePoint> {
    if (chunkSize <= 1 || points.isEmpty()) return points
    return points.chunked(chunkSize).mapNotNull(::aggregateChartBucket)
}

internal fun aggregateChartPoints(
    points: List<PricePoint>,
    bucketKey: (PricePoint) -> String
): List<PricePoint> {
    if (points.isEmpty()) return emptyList()

    val buckets = mutableListOf<MutableList<PricePoint>>()
    var lastKey: String? = null
    points.forEach { point ->
        val key = bucketKey(point)
        if (key == lastKey && buckets.isNotEmpty()) {
            buckets.last().add(point)
        } else {
            buckets.add(mutableListOf(point))
            lastKey = key
        }
    }

    return buckets.mapNotNull(::aggregateChartBucket)
}

internal fun aggregateChartBucket(bucket: List<PricePoint>): PricePoint? {
    val first = bucket.firstOrNull() ?: return null
    val last = bucket.lastOrNull() ?: return null
    val volumes = bucket.mapNotNull { it.volume }
    return PricePoint(
        date = last.date,
        open = first.open,
        high = bucket.maxOf { it.high },
        low = bucket.minOf { it.low },
        close = last.close,
        volume = volumes.takeIf { it.isNotEmpty() }?.sum()
    )
}

internal fun weeklyChartBucket(point: PricePoint): String {
    val date = parseChartDate(point.date) ?: return point.date
    return date.chartWeekStart().toString()
}

internal fun threeWeekChartBucket(point: PricePoint, firstWeekStart: LocalDate): String {
    val date = parseChartDate(point.date) ?: return point.date
    val weekOffset = ChronoUnit.WEEKS.between(firstWeekStart, date.chartWeekStart()).coerceAtLeast(0L)
    return "W3-${weekOffset / 3L}"
}

internal fun monthlyChartBucket(point: PricePoint): String {
    val date = parseChartDate(point.date) ?: return point.date
    return date.withDayOfMonth(1).toString()
}

internal fun parseChartDate(value: String): LocalDate? {
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun LocalDate.chartWeekStart(): LocalDate {
    return minusDays((dayOfWeek.value - 1).toLong())
}
