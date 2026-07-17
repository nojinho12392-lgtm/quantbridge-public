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
