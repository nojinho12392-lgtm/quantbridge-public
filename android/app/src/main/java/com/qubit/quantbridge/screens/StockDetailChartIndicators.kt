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
