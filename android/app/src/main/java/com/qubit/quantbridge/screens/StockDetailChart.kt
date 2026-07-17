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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PriceChart(
    points: List<PricePoint>,
    currency: String,
    period: ChartPeriod,
    availablePeriods: Set<ChartPeriod>,
    onPeriodChange: (ChartPeriod) -> Unit
) {
    var overlays by remember {
        mutableStateOf(setOf(ChartOverlay.Volume))
    }
    var chartMode by remember { mutableStateOf(ChartMode.Candle) }
    var showIndicatorSheet by remember { mutableStateOf(false) }
    val visible = remember(points, period) { chartVisiblePoints(points, period) }
    CardBlock(useBorder = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("차트", fontWeight = FontWeight.Bold)
            }
            if (visible.size >= 2) {
                val first = visible.first()
                val last = visible.last()
                val returnTone = if (last.close >= first.close) QuantPositive else QuantNegative
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtPx(last.close, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (first.close == 0.0) "-" else pct((last.close / first.close) - 1.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = returnTone,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (visible.size < 2) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("가격 데이터 없음", fontWeight = FontWeight.SemiBold)
                    Text(
                        "현재 기간에 표시할 가격 이력이 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val ma5 = remember(visible) { movingAverage(visible, 5) }
            val ma20 = remember(visible) { movingAverage(visible, 20) }
            val ma60 = remember(visible) { movingAverage(visible, 60) }
            val bollinger = remember(visible) { bollingerBands(visible, 20) }
            val rsi = remember(visible) { rsiSeries(visible, 14) }
            val first = visible.first().close
            val last = visible.last().close
            val chartTone = if (last >= first) QuantPositive else QuantNegative
            PeriodModeSelector(
                period = period,
                availablePeriods = availablePeriods,
                onPeriodChange = onPeriodChange,
                mode = chartMode,
                tone = chartTone,
                onModeChange = { chartMode = it }
            )
            Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                val indicatorValues = buildList {
                    if (ChartOverlay.MA5 in overlays) addAll(ma5.filterNotNull())
                    if (ChartOverlay.MA20 in overlays) addAll(ma20.filterNotNull())
                    if (ChartOverlay.MA60 in overlays) addAll(ma60.filterNotNull())
                    if (ChartOverlay.Bollinger in overlays) {
                        bollinger.filterNotNull().forEach {
                            add(it.first)
                            add(it.second)
                        }
                    }
                }
                val minPrice = min(visible.minOf { it.low }, indicatorValues.minOrNull() ?: visible.minOf { it.low })
                val maxPrice = max(visible.maxOf { it.high }, indicatorValues.maxOrNull() ?: visible.maxOf { it.high })
                val rawRange = max(0.0001, maxPrice - minPrice)
                val pricePadding = rawRange * 0.03
                val chartMinPrice = minPrice - pricePadding
                val chartMaxPrice = maxPrice + pricePadding
                val range = max(0.0001, chartMaxPrice - chartMinPrice)
                val step = size.width / visible.lastIndex.coerceAtLeast(1).toFloat()
                val candleWidth = max(3f, step * 0.727f)
                val chartTopPad = 34f
                val chartBottomPad = 28f
                val chartHeight = max(1f, size.height - chartTopPad - chartBottomPad)
                fun y(price: Double): Float = chartTopPad + (1f - ((price - chartMinPrice) / range).toFloat()) * chartHeight
                fun chartX(index: Int): Float = if (visible.size <= 1) {
                    size.width / 2f
                } else {
                    val left = candleWidth / 2f
                    val right = size.width - candleWidth / 2f
                    left + (right - left) * index / visible.lastIndex.toFloat()
                }
                fun drawSeries(values: List<Double?>, color: Color, strokeWidth: Float = 3f) {
                    val path = Path()
                    var started = false
                    values.forEachIndexed { index, value ->
                        if (value == null) return@forEachIndexed
                        val x = chartX(index)
                        val yy = y(value)
                        if (!started) {
                            path.moveTo(x, yy)
                            started = true
                        } else {
                            path.lineTo(x, yy)
                        }
                    }
                    if (started) drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
                }

                if (chartMode == ChartMode.Candle) {
                    visible.forEachIndexed { index, point ->
                        val x = chartX(index)
                        val up = point.close >= point.open
                        val color = if (up) QuantPositive else QuantNegative
                        drawLine(color, Offset(x, y(point.high)), Offset(x, y(point.low)), strokeWidth = 2f)
                        val top = min(y(point.open), y(point.close))
                        val bottom = max(y(point.open), y(point.close))
                        val bodyHeight = max(2f, bottom - top)
                        drawRect(
                            color = color,
                            topLeft = Offset(x - candleWidth / 2f, top),
                            size = Size(candleWidth, bodyHeight)
                        )
                    }
                } else {
                    val lineColor = if (last >= first) QuantPositive else QuantNegative
                    drawSeries(visible.map { it.close }, lineColor, strokeWidth = 4f)
                }

                if (ChartOverlay.Bollinger in overlays) {
                    drawSeries(bollinger.map { it?.first }, QuantMuted, strokeWidth = 2f)
                    drawSeries(bollinger.map { it?.second }, QuantMuted, strokeWidth = 2f)
                }
                if (ChartOverlay.MA5 in overlays) drawSeries(ma5, QuantWarning)
                if (ChartOverlay.MA20 in overlays) drawSeries(ma20, QuantGreen)
                if (ChartOverlay.MA60 in overlays) drawSeries(ma60, QuantPurple)

                val highIndex = visible.indices.maxByOrNull { visible[it].high }
                val lowIndex = visible.indices.minByOrNull { visible[it].low }
                val labelColor = QuantMuted
                val markerColor = QuantLine
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = labelColor.toArgb()
                    textSize = 11.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                fun drawExtremaLabel(index: Int, price: Double, prefix: String, isHigh: Boolean) {
                    val pointX = chartX(index)
                    val pointY = y(price)
                    val date = shortChartDate(visible[index].date)
                    val label = "$prefix ${fmtPx(price, currency)} · $date"
                    val width = paint.measureText(label)
                    val textX = pointX.coerceIn(width / 2f + 4f, size.width - width / 2f - 4f)
                    val labelGap = 20f
                    val textY = if (isHigh) {
                        (pointY - labelGap).coerceAtLeast(12.dp.toPx())
                    } else {
                        (pointY + labelGap + 10f).coerceAtMost(size.height - 4f)
                    }
                    drawLine(
                        markerColor,
                        Offset(pointX, pointY),
                        Offset(pointX, if (isHigh) textY + 4f else textY - 16f),
                        strokeWidth = 2f
                    )
                    drawCircle(markerColor, radius = 4f, center = Offset(pointX, pointY))
                    drawContext.canvas.nativeCanvas.drawText(label, textX, textY, paint)
                }
                if (highIndex != null) {
                    drawExtremaLabel(highIndex, visible[highIndex].high, "최고", isHigh = true)
                }
                if (lowIndex != null && lowIndex != highIndex) {
                    drawExtremaLabel(lowIndex, visible[lowIndex].low, "최저", isHigh = false)
                }
            }
            ChartRangeSummaryRow(
                points = visible,
                currency = currency,
                onSettingsClick = { showIndicatorSheet = true }
            )
            if (ChartOverlay.Volume in overlays) {
                IndicatorLabel("거래량")
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val maxVolume = visible.mapNotNull { it.volume }.maxOrNull() ?: 0.0
                    if (maxVolume <= 0.0) return@Canvas
                    val step = size.width / visible.lastIndex.coerceAtLeast(1).toFloat()
                    val barWidth = max(2f, step * 0.734f)
                    fun volumeX(index: Int): Float = if (visible.size <= 1) {
                        size.width / 2f
                    } else {
                        val left = barWidth / 2f
                        val right = size.width - barWidth / 2f
                        left + (right - left) * index / visible.lastIndex.toFloat()
                    }
                    visible.forEachIndexed { index, point ->
                        val volume = point.volume ?: return@forEachIndexed
                        val h = (volume / maxVolume).toFloat() * size.height
                        val color = if (point.close >= point.open) QuantPositive.copy(alpha = 0.40f) else QuantNegative.copy(alpha = 0.40f)
                        drawRect(color, Offset(volumeX(index) - barWidth / 2f, size.height - h), Size(barWidth, h))
                    }
                }
            }
            if (ChartOverlay.RSI in overlays) {
                IndicatorLabel("RSI 14")
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    fun y(value: Double): Float = size.height - (value.coerceIn(0.0, 100.0) / 100.0).toFloat() * size.height
                    drawLine(QuantLine, Offset(0f, y(70.0)), Offset(size.width, y(70.0)), strokeWidth = 1.5f)
                    drawLine(QuantLine, Offset(0f, y(30.0)), Offset(size.width, y(30.0)), strokeWidth = 1.5f)
                    val path = Path()
                    var started = false
                    rsi.forEachIndexed { index, value ->
                        if (value == null) return@forEachIndexed
                        val x = size.width * index / rsi.lastIndex.coerceAtLeast(1).toFloat()
                        val yy = y(value)
                        if (!started) {
                            path.moveTo(x, yy)
                            started = true
                        } else {
                            path.lineTo(x, yy)
                        }
                    }
                    if (started) drawPath(path, QuantPurple, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
            }
        }
    }
    if (showIndicatorSheet) {
        ModalBottomSheet(onDismissRequest = { showIndicatorSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("보조지표", style = MaterialTheme.typography.titleMedium)
                ChartOverlay.entries.forEach { overlay ->
                    val selected = overlay in overlays
                    if (selected) {
                        Button(
                            onClick = { overlays = overlays - overlay },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(overlay.label) }
                    } else {
                        OutlinedButton(
                            onClick = { overlays = overlays + overlay },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(overlay.label) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

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

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChartRangeSummaryRow(
    points: List<PricePoint>,
    currency: String,
    onSettingsClick: () -> Unit
) {
    if (points.size < 2) return
    val last = points.last()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartSummaryPill("고가", fmtPx(points.maxOf { it.high }, currency))
            ChartSummaryPill("저가", fmtPx(points.minOf { it.low }, currency))
            last.volume?.let { ChartSummaryPill("거래량", compactNumber(it)) }
        }
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "보조지표 설정",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ChartSummaryPill(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PeriodModeSelector(
    period: ChartPeriod,
    availablePeriods: Set<ChartPeriod>,
    onPeriodChange: (ChartPeriod) -> Unit,
    mode: ChartMode,
    tone: Color,
    onModeChange: (ChartMode) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ChartPeriod.entries.forEach { option ->
                val enabled = option in availablePeriods
                PeriodChip(
                    label = option.label,
                    selected = option == period,
                    enabled = enabled,
                    onClick = { if (enabled) onPeriodChange(option) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onModeChange(if (mode == ChartMode.Candle) ChartMode.Line else ChartMode.Candle)
                },
            color = tone.copy(alpha = 0.10f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                ChartModeGlyph(mode, tone)
            }
        }
    }
}

@Composable
internal fun PeriodChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when {
        selected -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }
    Surface(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                if (enabled && !selected) onClick()
            },
        color = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
            enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
internal fun ChartModeGlyph(mode: ChartMode, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        if (mode == ChartMode.Candle) {
            val xs = listOf(size.width * 0.25f, size.width * 0.5f, size.width * 0.75f)
            val bodyHeights = listOf(size.height * 0.36f, size.height * 0.58f, size.height * 0.42f)
            xs.forEachIndexed { index, x ->
                val centerY = size.height * (0.38f + index * 0.08f)
                drawLine(
                    color = color,
                    start = Offset(x, centerY - size.height * 0.32f),
                    end = Offset(x, centerY + size.height * 0.32f),
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round
                )
                drawRect(
                    color = color,
                    topLeft = Offset(x - size.width * 0.075f, centerY - bodyHeights[index] / 2f),
                    size = Size(size.width * 0.15f, bodyHeights[index])
                )
            }
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.70f)
                lineTo(size.width * 0.30f, size.height * 0.46f)
                lineTo(size.width * 0.52f, size.height * 0.57f)
                lineTo(size.width * 0.76f, size.height * 0.24f)
                lineTo(size.width * 0.94f, size.height * 0.36f)
            }
            drawPath(path, color = color, style = Stroke(width = 3.2f, cap = StrokeCap.Round))
            drawCircle(color, radius = 2.8f, center = Offset(size.width * 0.94f, size.height * 0.36f))
        }
    }
}

@Composable
internal fun IndicatorLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun movingAverage(points: List<PricePoint>, window: Int): List<Double?> {
    if (window <= 0) return List(points.size) { null }
    var sum = 0.0
    return points.indices.map { index ->
        sum += points[index].close
        if (index >= window) sum -= points[index - window].close
        val count = min(index + 1, window)
        sum / count
    }
}

internal fun bollingerBands(points: List<PricePoint>, window: Int): List<Pair<Double, Double>?> {
    if (window <= 1) return List(points.size) { null }
    return points.indices.map { index ->
        val start = max(0, index + 1 - window)
        val closes = points.subList(start, index + 1).map { it.close }
        if (closes.size < 2) {
            val close = closes.firstOrNull()
            close?.let { it to it }
        } else {
            val avg = closes.average()
            val std = sqrt(closes.sumOf { (it - avg) * (it - avg) } / closes.size)
            (avg + 2.0 * std) to (avg - 2.0 * std)
        }
    }
}

internal fun rsiSeries(points: List<PricePoint>, window: Int): List<Double?> {
    if (points.isEmpty() || window <= 0) return List(points.size) { null }
    return points.indices.map { index ->
        if (index == 0) {
            50.0
        } else {
            val start = max(0, index + 1 - window)
            val slice = points.subList(start, index + 1)
            var gains = 0.0
            var losses = 0.0
            slice.zipWithNext { prev, next ->
                val diff = next.close - prev.close
                if (diff >= 0) gains += diff else losses -= diff
            }
            when {
                losses == 0.0 && gains == 0.0 -> 50.0
                losses == 0.0 -> 100.0
                else -> {
                    val rs = gains / losses
                    100.0 - (100.0 / (1.0 + rs))
                }
            }
        }
    }
}

internal fun shortChartDate(date: String): String {
    val parts = date.split("-")
    return if (parts.size >= 3) "${parts[1]}/${parts[2]}" else date
}
