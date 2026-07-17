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
internal fun WatchRow(
    item: WatchlistItem,
    insight: WatchCompanyInsight,
    decision: InvestmentDecisionRecord?,
    indicatorQuote: MarketIndicatorQuote? = null,
    onOpen: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onCompare: (() -> Unit)?,
    onDelete: () -> Unit
) {
    val isIndicator = item.isMarketIndicatorWatchItem()
    val priceText = watchRowPriceText(item, insight, indicatorQuote)
    val moveText = watchRowMoveText(insight, indicatorQuote)
    val categoryText = watchRowCategoryText(item, insight, indicatorQuote)
    val moveColor = watchRowMoveColor(moveText)
    val rowModifier = if (onOpen != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen)
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
    }

    Surface(
        modifier = rowModifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isIndicator) {
                MarketIndicatorLogoView(
                    ticker = item.ticker,
                    name = item.name,
                    size = 44.dp,
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                TickerAvatar(item.ticker, item.market)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        shortTicker(item.ticker),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Text(
                    categoryText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                WatchInsightLine(insight)
                WatchMetadataInlineSummary(item)
                InvestmentDecisionInlineSummary(decision)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimatedPriceText(
                    text = priceText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                moveText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = moveColor,
                        maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onCompare != null) {
                        val compareLabel = "${item.name} 비교 목록에 추가"
                        IconButton(
                            onClick = onCompare,
                            modifier = Modifier
                                .size(30.dp)
                                .clearAndSetSemantics {
                                    role = Role.Button
                                    contentDescription = compareLabel
                                    onClick(label = compareLabel) {
                                        onCompare()
                                        true
                                    }
                                }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (onEdit != null) {
                        val editLabel = "${item.name} 관심 설정"
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .size(30.dp)
                                .clearAndSetSemantics {
                                    role = Role.Button
                                    contentDescription = editLabel
                                    onClick(label = editLabel) {
                                        onEdit()
                                        true
                                    }
                                }
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    val deleteLabel = if (isIndicator) "${item.name} 관심 지수 삭제" else "${item.name} 관심 종목 삭제"
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(30.dp)
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = deleteLabel
                                onClick(label = deleteLabel) {
                                    onDelete()
                                    true
                                }
                            }
                    ) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = QuantFavorite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun watchRowPriceText(
    item: WatchlistItem,
    insight: WatchCompanyInsight,
    indicatorQuote: MarketIndicatorQuote?
): String {
    if (item.isMarketIndicatorWatchItem()) {
        val value = indicatorQuote?.value
        return if (value?.isFinite() == true) indicatorValueText(value) else "-"
    }
    return insight.details.firstOrNull { it.startsWith("가격 ") }
        ?.removePrefix("가격 ")
        ?: "-"
}

internal fun watchRowMoveText(
    insight: WatchCompanyInsight,
    indicatorQuote: MarketIndicatorQuote?
): String? {
    indicatorQuote?.changePct?.takeIf { it.isFinite() }?.let { return pct(it) }
    insight.details.firstOrNull { it.startsWith("하루 ") }?.let { return it.removePrefix("하루 ") }
    return insight.details.firstOrNull { it.startsWith("1개월 ") }?.removePrefix("1개월 ")
}

internal fun watchRowCategoryText(
    item: WatchlistItem,
    insight: WatchCompanyInsight,
    indicatorQuote: MarketIndicatorQuote?
): String {
    if (item.isMarketIndicatorWatchItem()) {
        return indicatorQuote?.let { watchIndicatorCategory(item) } ?: item.market
    }
    val detail = insight.details.firstOrNull {
        !it.startsWith("가격 ") &&
            !it.startsWith("하루 ") &&
            !it.startsWith("1개월 ") &&
            !it.startsWith("시총 ") &&
            !it.startsWith("실적 ")
    }
    return detail ?: item.note.ifBlank { item.market }
}

@Composable
internal fun watchRowMoveColor(value: String?): androidx.compose.ui.graphics.Color {
    return when {
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        value.trim().startsWith("-") -> QuantNegative
        value.trim().startsWith("+") -> QuantPositive
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchDetailFacts(details: List<String>) {
    if (details.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        details.forEach { detail ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    detail,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun WatchInsightLine(insight: WatchCompanyInsight) {
    val color = watchInsightColor(insight)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.size(7.dp),
            shape = RoundedCornerShape(99.dp),
            color = color,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {}
        Text(
            insight.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
        Text(
            insight.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun WatchCard(content: @Composable ColumnScope.() -> Unit) {
    QuantCard(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
