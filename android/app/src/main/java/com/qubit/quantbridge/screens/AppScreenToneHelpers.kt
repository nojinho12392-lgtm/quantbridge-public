package com.qubit.quantbridge

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Composable
internal fun toneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
}

internal fun marketMoveColor(value: Double): Color {
    return if (value >= 0.0) QuantPositive else QuantNegative
}

internal fun ratioTone(value: Double?, good: Double, caution: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v >= good -> DetailTone.Positive
        v >= caution -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

fun returnTone(value: Double?): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v > 0.0 -> DetailTone.Positive
        v < 0.0 -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

internal fun scoreTone(value: Double?, good: Double, caution: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v >= good -> DetailTone.Positive
        v >= caution -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

internal fun inverseTone(value: Double?, goodMax: Double, cautionMax: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v <= goodMax -> DetailTone.Positive
        v <= cautionMax -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

internal fun statusTone(status: String): DetailTone {
    return when (status.uppercase(Locale.US)) {
        "OK", "PASS", "SUCCESS", "HEALTHY", "IMPROVED", "ML_STRONG" -> DetailTone.Positive
        "ML_BASE" -> DetailTone.Primary
        "WARN", "WATCH", "DEGRADED", "INSUFFICIENT", "STALE", "UNKNOWN", "UNAVAILABLE", "REVIEW", "HOLD", "ML_OFF", "ML_WEAK" -> DetailTone.Warning
        "FAIL", "FAILED", "ERROR", "WORSE" -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}
