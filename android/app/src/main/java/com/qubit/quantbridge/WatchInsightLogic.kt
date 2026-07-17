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
internal fun watchInsightColor(insight: WatchCompanyInsight): androidx.compose.ui.graphics.Color {
    return when (insight.tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun watchComparator(
    sort: WatchSortOption,
    selectedTab: WatchAssetTab,
    companyInsights: Map<String, WatchCompanyInsight>,
    indicatorQuotes: List<MarketIndicatorQuote>
): Comparator<WatchlistItem> {
    return Comparator { left, right ->
        when (sort) {
            WatchSortOption.Signal -> {
                val leftSignal = watchSignalScore(left, companyInsights, indicatorQuotes)
                val rightSignal = watchSignalScore(right, companyInsights, indicatorQuotes)
                compareSignalValues(rightSignal, leftSignal).ifZero {
                    right.addedAt.compareTo(left.addedAt).ifZero {
                        left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
                    }
                }
            }
            WatchSortOption.Added -> right.addedAt.compareTo(left.addedAt)
            WatchSortOption.Name -> left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
            WatchSortOption.Market -> left.market.compareTo(right.market).ifZero {
                left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
            }
        }
    }
}

internal fun watchJudgmentTimelineItems(
    context: Context,
    priorityItems: List<WatchPriorityItem>
): List<WatchJudgmentTimelineItem> {
    val history = QubitNotificationScheduler.history(context)
        .take(6)
        .map {
            WatchJudgmentTimelineItem(
                id = it.id,
                title = it.title,
                detail = it.body,
                source = it.source,
                recordedAtMillis = it.recordedAtMillis,
                color = Color(0xFF2563EB)
            )
        }
    if (history.isNotEmpty()) return history
    return priorityItems.take(4).map { priority ->
        WatchJudgmentTimelineItem(
            id = "current-${priority.item.ticker}-${priority.insight.title}",
            title = priority.insight.title,
            detail = priority.insight.detail,
            source = "현재 신호",
            recordedAtMillis = null,
            color = watchInsightTimelineColor(priority.insight)
        )
    }
}

internal fun watchInsightTimelineColor(insight: WatchCompanyInsight): Color {
    return when (insight.tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> Color(0xFF2563EB)
        DetailTone.Neutral -> Color(0xFF64748B)
    }
}

internal fun watchSignalScore(
    item: WatchlistItem,
    companyInsights: Map<String, WatchCompanyInsight>,
    indicatorQuotes: List<MarketIndicatorQuote>
): Double {
    return if (item.isMarketIndicatorWatchItem()) {
        kotlin.math.abs(marketIndicatorQuoteFor(item, indicatorQuotes).changePct ?: 0.0)
    } else {
        (companyInsights[normalizedTicker(item.ticker)] ?: item.defaultCompanyInsight()).priority.toDouble()
    }
}

internal fun compareSignalValues(left: Double, right: Double): Int {
    return when {
        left < right -> -1
        left > right -> 1
        else -> 0
    }
}

internal inline fun Int.ifZero(block: () -> Int): Int {
    return if (this == 0) block() else this
}
