package com.qubit.quantbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun rememberDailyRoutineState(context: Context): DailyRoutineState {
    val todayKey = LocalDate.now().toString()
    val scope = rememberCoroutineScope()
    val repository = remember(context) { UserPreferencesRepository(context.applicationContext) }
    var savedDate by remember { mutableStateOf("") }
    var completedCsv by remember { mutableStateOf("") }

    LaunchedEffect(todayKey) {
        val snapshot = repository.dailyRoutineSnapshot()
        if (snapshot.date != todayKey) {
            savedDate = todayKey
            completedCsv = ""
            repository.setDailyRoutine(todayKey, emptySet())
        } else {
            savedDate = snapshot.date
            completedCsv = encodeCsvSet(snapshot.completedIds)
        }
    }

    val completedIds = if (savedDate == todayKey) {
        completedCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    } else {
        emptySet()
    }

    fun save(ids: Set<String>) {
        val nextCsv = ids.sorted().joinToString(",")
        savedDate = todayKey
        completedCsv = nextCsv
        scope.launch {
            repository.setDailyRoutine(todayKey, ids)
        }
    }

    return DailyRoutineState(
        completedIds = completedIds,
        toggle = { id ->
            val next = completedIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            save(next)
        },
        reset = { save(emptySet()) }
    )
}

@Composable
internal fun TodayDecisionPanel(
    items: List<HomeActionItem>,
    completedIds: Set<String>,
    snoozedIds: Set<String>,
    latestUpdatedAt: String?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onComplete: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onReset: () -> Unit
) {
    val activeItems = items.filterNot { it.id in completedIds || it.id in snoozedIds }.take(3)
    val handledCount = items.count { it.id in completedIds || it.id in snoozedIds }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
            )
            Column(
                modifier = Modifier
                    .padding(15.dp)
                    .animateContentSize(animationSpec = tween(220)),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "오늘 볼 것 3개",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    QuantIconActionButton(
                        icon = LucideIcon.RefreshCw,
                        contentDescription = if (isLoading) "홈 갱신 중" else "홈 새로고침",
                        onClick = onRefresh
                    )
                }

                if (activeItems.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(QuantGreen.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            LucideIconView(
                                icon = LucideIcon.ShieldCheck,
                                contentDescription = null,
                                tint = QuantGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            "큰 변화가 없으면 후보와 관심 항목을 계속 감시합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    AnimatedContent(
                        targetState = activeItems.joinToString("|") { it.id },
                        transitionSpec = {
                            (fadeIn(tween(170)) + slideInVertically(tween(220)) { it / 5 }) togetherWith
                                (fadeOut(tween(130)) + slideOutVertically(tween(180)) { -it / 5 })
                        },
                        label = "todayDecisionItems"
                    ) { targetItemIds ->
                        val targetItemIdSet = remember(targetItemIds) {
                            targetItemIds.split("|").filter { it.isNotBlank() }.toSet()
                        }
                        val visibleItems = activeItems.filter { it.id in targetItemIdSet }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            visibleItems.forEachIndexed { index, item ->
                                TodayDecisionRow(
                                    item = item,
                                    number = index + 1,
                                    onOpenAndComplete = {
                                        item.onOpen()
                                        onComplete(item.id)
                                    },
                                    onComplete = { onComplete(item.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TodayDecisionRow(
    item: HomeActionItem,
    number: Int,
    onOpenAndComplete: () -> Unit,
    onComplete: () -> Unit
) {
    val tint = homeActionToneColor(item.tone)
    val metrics = decisionMetricTokens(item.detail)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .quantClickable(role = QuantPressRole.Row, onClick = onOpenAndComplete)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            LucideIconView(
                icon = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                item.detail,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                metrics.forEach { metric ->
                    DecisionMetricChip(value = metric, tint = tint)
                }
                Spacer(Modifier.weight(1f))
                DecisionMetricChip(value = item.actionLabel, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .quantClickable(role = QuantPressRole.Icon, onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            LucideIconView(
                icon = LucideIcon.Square,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

internal fun decisionMetricTokens(detail: String): List<String> {
    return detail
        .split("·", "|", ",")
        .map { it.trim() }
        .filter { token ->
            token.any { it.isDigit() } ||
                token.contains("%") ||
                token.contains("#") ||
                token.uppercase(Locale.US).contains("D-") ||
                token.uppercase(Locale.US).contains("D+")
        }
        .take(2)
}

@Composable
internal fun homeActionToneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun DailyRoutineCard(
    items: List<HomeRoutineItem>,
    completedIds: Set<String>,
    isLoading: Boolean,
    onToggle: (String) -> Unit,
    onReset: () -> Unit
) {
    val completedCount = items.count { completedIds.contains(it.id) }
    val progress = if (items.isEmpty()) 0f else (completedCount.toFloat() / items.size).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "오늘의 체크 루틴",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isLoading) "데이터 갱신 중에도 루틴은 저장됩니다" else "${completedCount}/${items.size} 완료",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "초기화",
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .quantClickable(role = QuantPressRole.Text, onClick = onReset)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(if (completedCount == items.size) QuantGreen else MaterialTheme.colorScheme.primary)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    DailyRoutineRow(
                        item = item,
                        isCompleted = completedIds.contains(item.id),
                        onToggle = { onToggle(item.id) },
                        onOpen = item.onOpen
                    )
                }
            }
        }
    }
}

@Composable
internal fun DailyRoutineRow(
    item: HomeRoutineItem,
    isCompleted: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isCompleted) item.tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                .quantClickable(role = QuantPressRole.Icon, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isCompleted) 10.dp else 18.dp)
                    .clip(CircleShape)
                    .background(if (isCompleted) item.tint else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(item.tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(item.tint)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            item.actionLabel,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(item.tint.copy(alpha = 0.08f))
                .quantClickable(role = QuantPressRole.Text, onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = item.tint,
            maxLines = 1
        )
    }
}
