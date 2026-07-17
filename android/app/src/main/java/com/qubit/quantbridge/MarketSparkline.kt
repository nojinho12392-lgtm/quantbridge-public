package com.qubit.quantbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.qubit.quantbridge.ui.theme.QubitTheme
import com.qubit.quantbridge.ui.theme.QuantBackground
import com.qubit.quantbridge.ui.theme.QuantDarkBackground
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantSurface
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun IndicatorSparkline(
    item: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val samples = remember(item, points) { sparklineSamples(item, points) }
    val values = remember(samples) { samples.map { it.close }.filter { it.isFinite() } }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(item.symbol) {
        while (true) {
            now = Instant.now()
            delay(30_000)
        }
    }
    val showLiveEndpoint = remember(item.symbol, samples, now) {
        shouldShowLiveEndpoint(item, samples, now)
    }
    val endpointPulse by rememberInfiniteTransition(label = "indicatorEndpointPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicatorEndpointHalo"
    )
    Canvas(modifier) {
        val referenceClose = previousClose(item)
        val domainValues = values + listOf(item.value).filter { it.isFinite() }
        val domain = sparklineDomain(domainValues, referenceClose)
        fun yPosition(value: Double): Float {
            val span = max(domain.second - domain.first, 0.0001)
            val usableHeight = max(size.height - 8.dp.toPx(), 1f)
            return size.height - (((value - domain.first) / span).toFloat() * usableHeight) - 4.dp.toPx()
        }
        val baselineY = referenceClose?.let(::yPosition) ?: (size.height * 0.72f)
        drawLine(
            color = color.copy(alpha = 0.14f),
            start = Offset(0f, baselineY),
            end = Offset(size.width, baselineY),
            strokeWidth = 1.dp.toPx()
        )

        if (samples.size == 1) {
            val sample = samples.first()
            val x = sample.progress.coerceIn(0f, 1f) * size.width
            val y = yPosition(sample.close)
            drawCircle(color = color, radius = 2.4.dp.toPx(), center = Offset(x, y))
            return@Canvas
        }

        if (values.size < 2) {
            drawLine(
                color = color.copy(alpha = 0.45f),
                start = Offset(0f, baselineY),
                end = Offset(size.width, baselineY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        val line = Path()
        val fill = Path()
        var lastX = 0f
        var lastPoint: Offset? = null
        val fillAnchorY = sparklineFillAnchorY(
            samples = samples,
            referenceClose = referenceClose,
            baselineY = baselineY,
            chartBottom = size.height
        )

        samples.forEachIndexed { index, sample ->
            val value = sample.close
            val x = sample.progress.coerceIn(0f, 1f) * size.width
            lastX = x
            val y = yPosition(value)
            lastPoint = Offset(x, y)
            if (index == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, fillAnchorY)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(lastX, fillAnchorY)
        fill.close()

        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.14f), color.copy(alpha = 0.01f))
            )
        )
        drawPath(line, color = color, style = Stroke(width = 1.55.dp.toPx(), cap = StrokeCap.Round))
        if (showLiveEndpoint) {
            lastPoint?.let { point ->
                val haloRadius = (5.0f + 3.3f * endpointPulse).dp.toPx()
                val haloAlpha = 0.22f - 0.07f * endpointPulse
                drawCircle(color = color.copy(alpha = haloAlpha), radius = haloRadius, center = point)
                drawCircle(color = color, radius = 3.1.dp.toPx(), center = point)
            }
        }
    }
}

internal data class SparklineSample(
    val close: Double,
    val progress: Float,
    val instant: Instant?
)

internal const val INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT = 48
internal const val INDICATOR_SPARKLINE_MIN_RELATIVE_SPAN = 0.024
internal const val INDICATOR_SPARKLINE_PADDING_RATIO = 0.10

internal fun previousClose(item: MarketIndicatorQuote): Double? {
    val changeAbs = item.changeAbs
    if (item.value.isFinite() && changeAbs != null && changeAbs.isFinite()) {
        return item.value - changeAbs
    }
    val changePct = item.changePct
    if (item.value.isFinite() && changePct != null && changePct.isFinite() && changePct > -0.9999) {
        return item.value / (1.0 + changePct)
    }
    return null
}

internal fun sparklineDomain(values: List<Double>, referenceClose: Double?): Pair<Double, Double> {
    val clean = values.filter { it.isFinite() } + listOfNotNull(referenceClose).filter { it.isFinite() }
    val minValue = clean.minOrNull() ?: return 0.0 to 1.0
    val maxValue = clean.maxOrNull() ?: return 0.0 to 1.0
    val anchor = clean.firstOrNull { it.isFinite() && abs(it) > 0.0001 } ?: max(abs(maxValue), 1.0)
    val minimumSpan = max(abs(anchor) * INDICATOR_SPARKLINE_MIN_RELATIVE_SPAN, 0.0001)
    val spread = max(maxValue - minValue, minimumSpan)
    val midpoint = (minValue + maxValue) / 2.0
    val padding = spread * INDICATOR_SPARKLINE_PADDING_RATIO
    return (midpoint - spread / 2.0 - padding) to (midpoint + spread / 2.0 + padding)
}

internal fun sparklineFillAnchorY(
    samples: List<SparklineSample>,
    referenceClose: Double?,
    baselineY: Float,
    chartBottom: Float
): Float {
    return chartBottom
}

internal data class MarketSession(
    val zone: ZoneId,
    val start: LocalTime,
    val end: LocalTime
)

internal val fallbackSparklineProgress = listOf(0f, 0.18f, 0.33f, 0.48f, 0.62f, 0.78f, 1f)

internal fun sparklineSamples(
    item: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>
): List<SparklineSample> {
    val clean = stableMarketIndicatorPoints(points, item)
    if (clean.isEmpty()) return emptyList()

    val session = marketSessionFor(item)
    if (session != null) {
        val rawSessionSamples = clean.mapNotNull { point ->
            val instant = parseMarketInstant(point.timestamp) ?: return@mapNotNull null
            val progress = sessionProgress(instant, session) ?: return@mapNotNull null
            SparklineSample(
                close = point.close,
                progress = progress,
                instant = instant
            )
        }
        val latestSessionDate = rawSessionSamples
            .mapNotNull { sample -> sample.instant?.atZone(session.zone)?.toLocalDate() }
            .maxOrNull()
        val sessionSamples = rawSessionSamples
            .filter { sample ->
                latestSessionDate == null || sample.instant?.atZone(session.zone)?.toLocalDate() == latestSessionDate
            }
            .sortedBy { it.progress }
        val now = Instant.now()
        if (isMarketSessionOpen(item, now) && latestSessionDate != now.atZone(session.zone).toLocalDate()) {
            fallbackSessionSamples(item, session, now)?.let { return it }
        }
        if (hasUsableTimeline(sessionSamples)) {
            return downsampleSparklineSamples(sessionSamples)
        }
    }

    return indexedSparklineSamples(clean)
}

internal fun fallbackSessionSamples(
    item: MarketIndicatorQuote,
    session: MarketSession,
    now: Instant
): List<SparklineSample>? {
    val points = stableMarketIndicatorPoints(fallbackMarketIndicatorPoints(item), item)
    if (points.isEmpty()) return null

    val interval = marketSessionInterval(now, session)
    if (now.isBefore(interval.first) || now.isAfter(interval.second)) return null
    val progressCap = (sessionProgress(now, session) ?: return null).coerceAtLeast(0.01f)
    val totalMillis = (interval.second.toEpochMilli() - interval.first.toEpochMilli()).coerceAtLeast(1L)
    val denominator = (points.size - 1).coerceAtLeast(1)
    val samples = points.mapIndexed { index, point ->
        val baseProgress = fallbackSparklineProgress.getOrElse(index) {
            index.toFloat() / denominator
        }
        val progress = (baseProgress * progressCap).coerceIn(0f, 1f)
        SparklineSample(
            close = point.close,
            progress = progress,
            instant = interval.first.plusMillis((totalMillis * progress).roundToLong())
        )
    }
    return if (hasUsableTimeline(samples)) downsampleSparklineSamples(samples) else null
}

internal fun indexedSparklineSamples(points: List<MarketIndicatorPoint>): List<SparklineSample> {
    val clean = points.filter { it.close.isFinite() }
    if (clean.size == 1) {
        return listOf(SparklineSample(clean.first().close, 0f, parseMarketInstant(clean.first().timestamp)))
    }
    val samples = clean.mapIndexed { index, point ->
        SparklineSample(
            close = point.close,
            progress = index.toFloat() / (clean.size - 1).coerceAtLeast(1),
            instant = parseMarketInstant(point.timestamp)
        )
    }
    return downsampleSparklineSamples(samples)
}

internal fun downsampleSparklineSamples(samples: List<SparklineSample>): List<SparklineSample> {
    if (samples.size <= INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT || INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT <= 1) {
        return samples
    }

    val lastIndex = samples.lastIndex
    val targetLastIndex = INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT - 1
    val output = ArrayList<SparklineSample>(INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT)

    for (targetIndex in 0..targetLastIndex) {
        val sourceIndex = ((targetIndex.toDouble() * lastIndex.toDouble()) / targetLastIndex.toDouble())
            .toInt()
            .coerceIn(0, lastIndex)
        val sample = samples[sourceIndex]
        if (output.lastOrNull() != sample) {
            output.add(sample)
        }
    }

    val last = samples.last()
    if (output.lastOrNull() != last) {
        output.add(last)
    }
    return output
}

internal fun hasUsableTimeline(samples: List<SparklineSample>): Boolean {
    if (samples.size <= 1) return samples.isNotEmpty()
    val distinctProgressCount = samples
        .map { it.progress }
        .fold(mutableListOf<Float>()) { distinct, progress ->
            if (distinct.none { abs(it - progress) < 0.0001f }) {
                distinct.add(progress)
            }
            distinct
        }
        .size
    return distinctProgressCount >= min(2, samples.size)
}

internal fun marketSessionFor(item: MarketIndicatorQuote): MarketSession? {
    return when (item.symbol.uppercase(Locale.US)) {
        "^IXIC", "NQ=F", "^GSPC", "ES=F", "RTY=F", "^DJI", "^SOX", "^VIX" -> MarketSession(
            zone = ZoneId.of("America/New_York"),
            start = LocalTime.of(9, 30),
            end = LocalTime.of(16, 0)
        )
        "^KS11", "^KQ11" -> MarketSession(
            zone = ZoneId.of("Asia/Seoul"),
            start = LocalTime.of(9, 0),
            end = LocalTime.of(15, 30)
        )
        else -> null
    }
}

internal fun sessionProgress(instant: Instant, session: MarketSession): Float? {
    val (start, end) = marketSessionInterval(instant, session)
    if (instant.isBefore(start) || instant.isAfter(end)) return null
    val totalMillis = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
    val elapsedMillis = instant.toEpochMilli() - start.toEpochMilli()
    return (elapsedMillis.toDouble() / totalMillis.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun marketSessionInterval(instant: Instant, session: MarketSession): Pair<Instant, Instant> {
    val local = instant.atZone(session.zone)
    val start = ZonedDateTime.of(local.toLocalDate(), session.start, session.zone).toInstant()
    val end = ZonedDateTime.of(local.toLocalDate(), session.end, session.zone).toInstant()
    return start to end
}

internal fun shouldShowLiveEndpoint(
    item: MarketIndicatorQuote,
    samples: List<SparklineSample>,
    now: Instant
): Boolean {
    val session = marketSessionFor(item) ?: return false
    val lastInstant = samples.lastOrNull()?.instant ?: return false
    if (!isMarketSessionOpen(item, now)) return false
    if (lastInstant.atZone(session.zone).toLocalDate() != now.atZone(session.zone).toLocalDate()) return false
    val ageMillis = now.toEpochMilli() - lastInstant.toEpochMilli()
    return ageMillis in -60_000L..(2 * 60 * 60 * 1000L)
}

internal fun isMarketSessionOpen(item: MarketIndicatorQuote, now: Instant = Instant.now()): Boolean {
    val session = marketSessionFor(item) ?: return false
    val local = now.atZone(session.zone)
    if (local.dayOfWeek.value !in 1..5) return false
    val start = ZonedDateTime.of(local.toLocalDate(), session.start, session.zone)
    val end = ZonedDateTime.of(local.toLocalDate(), session.end, session.zone)
    return !now.isBefore(start.toInstant()) && !now.isAfter(end.toInstant())
}

fun parseMarketInstant(raw: String): Instant? {
    if (raw.isBlank()) return null
    return try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (_: DateTimeParseException) {
        runCatching { Instant.parse(raw) }.getOrNull()
    }
}
