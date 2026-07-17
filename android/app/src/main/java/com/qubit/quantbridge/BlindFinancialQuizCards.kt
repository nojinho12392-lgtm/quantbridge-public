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
internal fun BlindQuizOptionCard(
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
internal fun BlindQuizCardFront(option: BlindQuizOption) {
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
internal fun BlindQuizCardBack(
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
