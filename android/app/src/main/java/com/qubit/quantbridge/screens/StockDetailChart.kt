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
