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
internal fun BlindFinancialQuizScreen(
    contentTopPadding: Dp = 10.dp,
    contentBottomPadding: Dp = FLOATING_NAV_CONTENT_INSET,
    viewModel: BlindFinancialQuizViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.load() }

    when {
        viewModel.loading && viewModel.quiz == null -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = contentTopPadding,
                    end = 16.dp,
                    bottom = contentBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { SkeletonLoadingCard(lineCount = 5) }
                item { SkeletonLoadingCard(lineCount = 4) }
            }
        }

        viewModel.error != null && viewModel.quiz == null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                EmptyCard(
                    title = "훈련 문제를 불러오지 못했습니다",
                    message = viewModel.error.orEmpty(),
                    lucideIcon = LucideIcon.RefreshCw,
                    actionLabel = "다시 시도",
                    onAction = { viewModel.refresh(force = true) }
                )
            }
        }

        else -> {
            val quiz = viewModel.quiz
            if (quiz == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    EmptyCard(
                        title = "훈련 문제가 없습니다",
                        message = "새로고침하면 다시 확인할 수 있습니다.",
                        lucideIcon = LucideIcon.Lightbulb,
                        actionLabel = "새로고침",
                        onAction = { viewModel.refresh(force = true) }
                    )
                }
            } else {
                BlindQuizContent(
                    quiz = quiz,
                    selectedOptionId = viewModel.selectedOptionId,
                    warning = viewModel.warning,
                    contentTopPadding = contentTopPadding,
                    contentBottomPadding = contentBottomPadding,
                    onSelect = viewModel::selectOption,
                    onRefresh = { viewModel.refresh(force = true) }
                )
            }
        }
    }
}

@Composable
private fun BlindQuizContent(
    quiz: BlindFinancialQuizResponse,
    selectedOptionId: String?,
    warning: String?,
    contentTopPadding: Dp,
    contentBottomPadding: Dp,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentTopPadding,
            end = 16.dp,
            bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            BlindQuizHeader(quiz = quiz, revealed = selectedOptionId != null)
        }

        if (warning != null) {
            item {
                QuantCard(role = QuantCardRole.Status, padding = 12.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        LucideIconView(
                            icon = LucideIcon.TriangleAlert,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = QuantWarning
                        )
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(quiz.options, key = { it.id }) { option ->
                    BlindQuizOptionCard(
                        option = option,
                        selectedOptionId = selectedOptionId,
                        correctOptionId = quiz.correctOptionId,
                        onSelect = { onSelect(option.id) },
                        modifier = Modifier.width(286.dp)
                    )
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = selectedOptionId != null,
                enter = fadeIn(animationSpec = tween(180)) +
                    slideInVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it / 5 }
            ) {
                BlindQuizResultSummary(quiz = quiz, selectedOptionId = selectedOptionId)
            }
        }

        item {
            QuantActionButton(
                label = "다른 문제 풀기",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                complete = selectedOptionId != null
            )
        }
    }
}

@Composable
private fun BlindQuizHeader(quiz: BlindFinancialQuizResponse, revealed: Boolean) {
    QuantCard(role = QuantCardRole.Decision, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                LucideIconView(
                    icon = LucideIcon.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "블라인드 재무제표 퀴즈",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    quiz.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DecisionMetricChip(
                value = if (revealed) "복기" else "문제",
                tint = if (revealed) QuantGreen else MaterialTheme.colorScheme.primary
            )
        }
        Text(
            quiz.prompt,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DecisionMetricChip(value = quiz.market, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            quiz.asOf?.let {
                Text(
                    "기준 $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BlindQuizOptionCard(
    option: BlindQuizOption,
    selectedOptionId: String?,
    correctOptionId: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val revealed = selectedOptionId != null
    val rotation by animateFloatAsState(
        targetValue = if (revealed) 180f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "blind-quiz-card-rotation-${option.id}"
    )
    val density = LocalDensity.current.density

    Box(
        modifier = modifier
            .heightIn(min = 388.dp)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 16f * density
            }
            .quantClickable(enabled = !revealed, role = QuantPressRole.Card, onClick = onSelect)
    ) {
        if (rotation <= 90f) {
            BlindQuizCardFront(option = option)
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) {
                BlindQuizCardBack(
                    option = option,
                    isSelected = selectedOptionId == option.id,
                    isCorrect = correctOptionId == option.id
                )
            }
        }
    }
}

@Composable
private fun BlindQuizCardFront(option: BlindQuizOption) {
    QuantCard(modifier = Modifier.heightIn(min = 388.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    option.blindLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "기업명 비공개",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
            ) {
                LucideIconView(
                    icon = LucideIcon.Eye,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        option.thesis?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier.padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            option.metrics.forEach { metric ->
                Surface(
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            metric.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            metric.value,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = metricToneColor(metric.tone),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 0.dp),
            shape = RoundedCornerShape(QuantControlRadius),
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "${option.blindLabel} 선택",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BlindQuizCardBack(
    option: BlindQuizOption,
    isSelected: Boolean,
    isCorrect: Boolean
) {
    val resultTone = if (isCorrect) QuantGreen else QuantWarning
    QuantCard(
        modifier = Modifier.heightIn(min = 388.dp),
        role = if (isCorrect) QuantCardRole.Decision else QuantCardRole.Information,
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TickerAvatar(ticker = option.company.ticker, market = option.company.market, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    option.company.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${option.company.ticker} · ${option.company.sector ?: option.company.market}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DecisionMetricChip(value = if (isCorrect) "정답" else "상대적으로 낮음", tint = resultTone)
            if (isSelected) {
                DecisionMetricChip(value = "내 선택", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "3년 주가 상승률",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                percentText(option.company.threeYearReturnPct),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = quizReturnColor(option.company.threeYearReturnPct)
            )
        }

        BlindQuizReturnChart(
            points = option.company.pricePoints,
            color = quizReturnColor(option.company.threeYearReturnPct),
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
                .padding(top = 8.dp)
        )

        Spacer(Modifier.weight(1f))

        Text(
            if (isCorrect) {
                "재무 지표의 강점이 실제 가격 모멘텀으로 이어진 케이스입니다."
            } else {
                "싸 보이는 지표보다 성장과 자본효율의 방향이 더 중요했을 수 있습니다."
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BlindQuizReturnChart(
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
private fun BlindQuizResultSummary(
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
private fun metricToneColor(tone: String?): Color {
    return when (tone) {
        "positive" -> QuantGreen
        "negative" -> QuantNegative
        "warning" -> QuantWarning
        else -> MaterialTheme.colorScheme.onSurface
    }
}

private fun quizReturnColor(value: Double?): Color {
    return if ((value ?: 0.0) >= 0.0) QuantPositive else QuantNegative
}

private fun percentText(value: Double?): String {
    return value?.let { String.format(Locale.US, "%+.1f%%", it * 100.0) } ?: "-"
}
