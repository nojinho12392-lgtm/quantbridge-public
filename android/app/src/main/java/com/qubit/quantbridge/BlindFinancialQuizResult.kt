package com.qubit.quantbridge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun BlindQuizReturnChart(
    points: List<BlindQuizPricePoint>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f))
            .padding(4.dp)
    ) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.returnPct }.filter { it.isFinite() }
        if (values.size < 2) return@Canvas
        val lower = min(values.minOrNull() ?: 0.0, 0.0)
        val upper = max(values.maxOrNull() ?: 0.0, 0.0)
        val span = max(upper - lower, 0.01)
        val horizontalPadding = 4.dp.toPx()
        val verticalPadding = 8.dp.toPx()
        val chartWidth = max(size.width - horizontalPadding * 2, 1f)
        val chartHeight = max(size.height - verticalPadding * 2, 1f)

        fun x(index: Int): Float = horizontalPadding + chartWidth * index / max(points.lastIndex, 1)
        fun y(value: Double): Float {
            val normalized = ((value - lower) / span).toFloat().coerceIn(0f, 1f)
            return size.height - verticalPadding - chartHeight * normalized
        }

        val baselineY = y(0.0)
        drawLine(
            color = color.copy(alpha = 0.14f),
            start = androidx.compose.ui.geometry.Offset(0f, baselineY),
            end = androidx.compose.ui.geometry.Offset(size.width, baselineY),
            strokeWidth = 1.dp.toPx()
        )

        val line = Path()
        val fill = Path()
        points.forEachIndexed { index, item ->
            val px = x(index)
            val py = y(item.returnPct)
            if (index == 0) {
                line.moveTo(px, py)
                fill.moveTo(px, baselineY)
                fill.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                fill.lineTo(px, py)
            }
        }
        val lastX = x(points.lastIndex)
        fill.lineTo(lastX, baselineY)
        fill.close()

        drawPath(fill, color = color.copy(alpha = 0.10f))
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
internal fun BlindQuizResultSummary(
    quiz: BlindFinancialQuizResponse,
    selectedOptionId: String?
) {
    val selectedIsCorrect = selectedOptionId == quiz.correctOptionId
    val correctLabel = remember(quiz) {
        quiz.options.firstOrNull { it.id == quiz.correctOptionId }?.blindLabel ?: quiz.correctOptionId.uppercase(Locale.US)
    }
    QuantCard(role = QuantCardRole.Status, padding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = (if (selectedIsCorrect) QuantGreen else QuantWarning).copy(alpha = 0.10f)
            ) {
                LucideIconView(
                    icon = if (selectedIsCorrect) LucideIcon.ShieldCheck else LucideIcon.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                    tint = if (selectedIsCorrect) QuantGreen else QuantWarning
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (selectedIsCorrect) "좋은 선택입니다" else "복기 포인트",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${quiz.answerRule} 기준 정답은 $correctLabel 입니다. 다음 문제에서는 PER/PBR의 절대값보다 ROE, 성장률, 부채 부담의 조합을 먼저 비교해 보세요.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun metricToneColor(tone: String?): Color {
    return when (tone) {
        "positive" -> QuantGreen
        "negative" -> QuantNegative
        "warning" -> QuantWarning
        else -> MaterialTheme.colorScheme.onSurface
    }
}

internal fun quizReturnColor(value: Double?): Color {
    return if ((value ?: 0.0) >= 0.0) QuantPositive else QuantNegative
}

internal fun percentText(value: Double?): String {
    return value?.let { String.format(Locale.US, "%+.1f%%", it * 100.0) } ?: "-"
}
