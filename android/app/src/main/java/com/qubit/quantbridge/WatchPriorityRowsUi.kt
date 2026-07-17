package com.qubit.quantbridge

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun WatchPriorityRow(
    priorityItem: WatchPriorityItem,
    onOpen: () -> Unit
) {
    val item = priorityItem.item
    val insight = priorityItem.insight
    val color = watchInsightColor(insight)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WatchPriorityLeadingLogo(item = item, insight = insight, color = color)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        insight.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        item.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        watchPriorityLabel(insight.priority),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1
                    )
                }
                Text(
                    insight.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                item.investmentThesis.inlineSummary?.let { summary ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        LucideIconView(
                            icon = LucideIcon.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (insight.metrics.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        insight.metrics.take(3).forEach { metric ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                Text(
                                    metric,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
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
internal fun WatchPriorityLeadingLogo(
    item: WatchlistItem,
    insight: WatchCompanyInsight,
    color: androidx.compose.ui.graphics.Color
) {
    Box(modifier = Modifier.size(38.dp)) {
        if (item.isMarketIndicatorWatchItem()) {
            Surface(
                modifier = Modifier
                    .size(34.dp)
                    .align(Alignment.Center),
                shape = CircleShape,
                color = color.copy(alpha = 0.12f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LucideIconView(
                        icon = watchPriorityIcon(insight.priority),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = color
                    )
                }
            }
        } else {
            Box(modifier = Modifier.align(Alignment.Center)) {
                TickerAvatar(
                    ticker = item.ticker,
                    market = item.market,
                    size = 34.dp
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(17.dp)
                .align(Alignment.BottomEnd),
            shape = CircleShape,
            color = color,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                LucideIconView(
                    icon = watchPriorityIcon(insight.priority),
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
internal fun WatchPriorityEmptyState(
    itemCount: Int,
    loading: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = QuantGreen.copy(alpha = 0.12f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        LucideIconView(
                            icon = LucideIcon.Check,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = QuantGreen
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (itemCount == 0) "감시할 관심 항목이 없습니다." else "오늘 즉시 확인할 신호는 없습니다.",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (itemCount == 0) "기업이나 ETF를 관심에 추가하면 우선순위가 자동으로 정렬됩니다." else "가격과 실적 이벤트만 계속 감시합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun watchPriorityIcon(priority: Int): LucideIcon {
    return when {
        priority >= 4 -> LucideIcon.CalendarClock
        priority >= 3 -> LucideIcon.Zap
        priority >= 2 -> LucideIcon.Target
        else -> LucideIcon.Database
    }
}

internal fun watchPriorityLabel(priority: Int): String {
    return when {
        priority >= 4 -> "긴급"
        priority >= 3 -> "확인"
        priority >= 2 -> "관찰"
        else -> "연결"
    }
}
