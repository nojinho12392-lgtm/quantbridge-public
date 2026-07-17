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
internal fun BlindQuizContent(
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
internal fun BlindQuizHeader(quiz: BlindFinancialQuizResponse, revealed: Boolean) {
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
