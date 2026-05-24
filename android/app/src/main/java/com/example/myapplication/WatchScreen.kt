package com.example.myapplication

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
import com.example.myapplication.ui.theme.QuantFavorite
import com.example.myapplication.ui.theme.QuantGreen
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantPositive
import com.example.myapplication.ui.theme.QuantWarning
import java.util.Locale
import kotlinx.coroutines.delay

private val WATCH_NAV_CONTENT_INSET = 104.dp
private const val WATCH_PRICE_AUTO_REFRESH_MS = 180_000L

private fun WatchlistSyncStatus.visibleMessageText(): String? {
    return messageText?.takeUnless { message ->
        message.contains("rememberCoroutineScope", ignoreCase = true) ||
            message.contains("left the composition", ignoreCase = true)
    }
}

private enum class WatchMarketFilter(val title: String) {
    All("ALL"),
    US("US"),
    KR("KR");

    fun matches(item: WatchlistItem): Boolean {
        return when (this) {
            All -> true
            US -> item.market.equals("US", ignoreCase = true)
            KR -> item.market.equals("KR", ignoreCase = true) || item.currency == "KRW"
        }
    }
}

private enum class WatchSortOption(val title: String) {
    Signal("이슈순"),
    Added("최근 추가순"),
    Name("이름순"),
    Market("시장순")
}

private enum class WatchAssetTab(val title: String) {
    All("전체"),
    Companies("기업"),
    Indicators("지수"),
    Etfs("ETF")
}

private val WatchlistEtfTickers = setOf(
    "QQQ", "SPY", "VOO", "VTI", "DIA", "IWM", "SCHD", "SMH", "SOXX", "XLK",
    "XLF", "XLV", "VNQ", "TLT", "GLD", "ARKK", "069500", "360750", "379800",
    "305720", "305540", "453850"
)

private fun WatchlistItem.isEtfWatchItem(): Boolean {
    if (isMarketIndicatorWatchItem()) return false
    return note.contains("ETF", ignoreCase = true) ||
        name.contains("ETF", ignoreCase = true) ||
        normalizedTicker(ticker) in WatchlistEtfTickers
}

private fun WatchAssetTab.sectionTitle(): String {
    return when (this) {
        WatchAssetTab.All -> "관심 목록"
        WatchAssetTab.Companies -> "관심 기업"
        WatchAssetTab.Indicators -> "관심 지수"
        WatchAssetTab.Etfs -> "관심 ETF"
    }
}

private fun WatchAssetTab.emptyTitle(): String {
    return when (this) {
        WatchAssetTab.All -> "관심 항목 없음"
        WatchAssetTab.Companies -> "관심 기업 없음"
        WatchAssetTab.Indicators -> "관심 지수 없음"
        WatchAssetTab.Etfs -> "관심 ETF 없음"
    }
}

private fun WatchAssetTab.emptyMessage(): String {
    return when (this) {
        WatchAssetTab.All -> "기업, 지수, ETF를 관심에 추가하면 이곳에서 한 번에 확인할 수 있어요."
        WatchAssetTab.Companies -> "분석, 뉴스, 기업 상세 화면의 하트로 추가해보세요."
        WatchAssetTab.Indicators -> "주요 지수 화면에서 하트를 누르면 이곳에 모입니다."
        WatchAssetTab.Etfs -> "ETF 화면이나 ETF 상세에서 관심에 추가해보세요."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    app: QuantAppState,
    marketIndicatorsViewModel: MarketIndicatorsViewModel = hiltViewModel(),
    watchlistViewModel: WatchlistViewModel = hiltViewModel(),
    comparisonViewModel: ComparisonViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    var selectedTab by remember { mutableStateOf(WatchAssetTab.Companies) }
    var marketFilter by remember { mutableStateOf(WatchMarketFilter.All) }
    var sort by remember { mutableStateOf(WatchSortOption.Signal) }
    var recentlyDeleted by remember { mutableStateOf<WatchlistItem?>(null) }
    var editingItem by remember { mutableStateOf<WatchlistItem?>(null) }
    val watchItems = watchlistViewModel.watchlist
    val syncStatus = remember(app.watchlistSyncStatus, watchlistViewModel.pendingCount) {
        if (app.watchlistSyncStatus is WatchlistSyncStatus.Idle && watchlistViewModel.pendingCount > 0) {
            watchlistViewModel.localSyncStatusFallback()
        } else {
            app.watchlistSyncStatus
        }
    }
    val companyCount = watchItems.count { !it.isMarketIndicatorWatchItem() && !it.isEtfWatchItem() }
    val indicatorCount = watchItems.count { it.isMarketIndicatorWatchItem() }
    val etfCount = watchItems.count { it.isEtfWatchItem() }
    val nonIndicatorCount = companyCount + etfCount
    val watchPriceKey = remember(watchItems) {
        watchItems
            .filter { !it.isMarketIndicatorWatchItem() }
            .map { "${it.market}:${it.ticker}" }
            .sorted()
            .joinToString("|")
    }
    val companyInsights = remember(
        watchItems,
        app.watchPriceMetrics,
        app.usPortfolio,
        app.krPortfolio,
        app.usSmallCap,
        app.krSmallCap,
        app.usEarnings,
        app.krEarnings,
        app.earningsCalendar
    ) {
        watchItems
            .filter { !it.isMarketIndicatorWatchItem() }
            .associate { item -> normalizedTicker(item.ticker) to app.companyInsightFor(item) }
    }
    val prioritySourceItems = remember(watchItems, selectedTab) {
        watchItems.filter { item ->
            when (selectedTab) {
                WatchAssetTab.All -> !item.isMarketIndicatorWatchItem()
                WatchAssetTab.Companies -> !item.isMarketIndicatorWatchItem() && !item.isEtfWatchItem()
                WatchAssetTab.Indicators -> false
                WatchAssetTab.Etfs -> item.isEtfWatchItem()
            }
        }
    }
    val watchPriorityItems = remember(prioritySourceItems, companyInsights) {
        prioritySourceItems
            .map { item ->
                WatchPriorityItem(
                    item = item,
                    insight = companyInsights[normalizedTicker(item.ticker)] ?: item.defaultCompanyInsight()
                )
            }
            .filter { it.insight.priority >= 1 }
            .sortedWith(
                compareByDescending<WatchPriorityItem> { it.insight.priority }
                    .thenByDescending { it.item.addedAt }
            )
    }
    val judgmentTimelineItems = remember(appContext, watchPriorityItems) {
        watchJudgmentTimelineItems(appContext, watchPriorityItems)
    }
    val activeItems = remember(watchItems, selectedTab) {
        watchItems.filter { item ->
            when (selectedTab) {
                WatchAssetTab.All -> true
                WatchAssetTab.Companies -> !item.isMarketIndicatorWatchItem() && !item.isEtfWatchItem()
                WatchAssetTab.Indicators -> item.isMarketIndicatorWatchItem()
                WatchAssetTab.Etfs -> item.isEtfWatchItem()
            }
        }
    }
    val indicatorQuotes = marketIndicatorsViewModel.marketIndicators
    val indicatorHistory = marketIndicatorsViewModel.marketIndicatorHistory
    val items = remember(activeItems, selectedTab, marketFilter, sort, companyInsights, indicatorQuotes) {
        activeItems
            .filter { selectedTab == WatchAssetTab.Indicators || selectedTab == WatchAssetTab.All || marketFilter.matches(it) }
            .sortedWith(watchComparator(sort, selectedTab, companyInsights, indicatorQuotes))
    }
    val indicatorRows = remember(items, selectedTab) {
        if (selectedTab == WatchAssetTab.Indicators) items.chunked(2) else emptyList()
    }

    LaunchedEffect(selectedTab, indicatorCount) {
        if (
            (selectedTab == WatchAssetTab.Indicators || selectedTab == WatchAssetTab.All) &&
            indicatorCount > 0 &&
            indicatorQuotes.isEmpty() &&
            !marketIndicatorsViewModel.loading
        ) {
            marketIndicatorsViewModel.refreshMarketIndicators(category = "all")
        }
    }

    LaunchedEffect(watchPriceKey) {
        if (watchPriceKey.isNotBlank()) {
            runCatching { app.refreshWatchPriceMetrics(force = true, automatic = true) }
        }
    }
    LaunchedEffect("watch-price-auto", watchPriceKey) {
        if (watchPriceKey.isBlank()) return@LaunchedEffect
        while (true) {
            delay(WATCH_PRICE_AUTO_REFRESH_MS)
            runCatching { app.refreshWatchPriceMetrics(force = true, automatic = true) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 10.dp,
            end = 16.dp,
            bottom = WATCH_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            WatchControls(
                selectedTab = selectedTab,
                onSelectedTab = { selectedTab = it },
                totalCount = watchItems.size,
                companyCount = companyCount,
                indicatorCount = indicatorCount,
                etfCount = etfCount
            )
        }

        if (selectedTab != WatchAssetTab.Indicators && nonIndicatorCount > 0) {
            item {
                WatchPriorityQueueCard(
                    items = watchPriorityItems,
                    itemCount = prioritySourceItems.size,
                    loading = app.homeLoading,
                    onOpen = { item -> app.selectedDetail = item.detailRequest() },
                    onRefresh = { scope.launchSafely { app.refreshAll() } }
                )
            }
            if (judgmentTimelineItems.isNotEmpty()) {
                item {
                    WatchJudgmentTimelineCard(items = judgmentTimelineItems)
                }
            }
        }

        syncStatus.visibleMessageText()?.let { message ->
            item {
                WatchSyncBanner(
                    message = message,
                    status = syncStatus,
                    onRetry = { scope.launchSafely { app.retryWatchlistSync() } }
                )
            }
        }

        recentlyDeleted?.let { deleted ->
            item {
                WatchUndoBanner(
                    item = deleted,
                    onUndo = {
                        scope.launchSafely { app.toggleWatch(deleted) }
                        recentlyDeleted = null
                    },
                    onDismiss = { recentlyDeleted = null }
                )
            }
        }

        item {
            WatchSectionHeader(
                selectedTab.sectionTitle(),
                "${items.size}/${activeItems.size}개",
                sort = sort,
                onSort = { sort = it },
                marketFilter = if (selectedTab == WatchAssetTab.Companies || selectedTab == WatchAssetTab.Etfs) marketFilter else null,
                onMarketFilter = if (selectedTab == WatchAssetTab.Companies || selectedTab == WatchAssetTab.Etfs) {
                    { filter -> marketFilter = filter }
                } else {
                    null
                }
            )
        }

        if (watchItems.isEmpty()) {
            item { WatchEmptyCard("관심 항목 없음", "기업과 주요 지수의 하트로 추가하면 이곳에서 따로 관리할 수 있어요.") }
        } else if (activeItems.isEmpty()) {
            item {
                WatchEmptyCard(selectedTab.emptyTitle(), selectedTab.emptyMessage())
            }
        } else if (items.isEmpty()) {
            item { WatchEmptyCard("조건에 맞는 종목 없음", "시장 필터를 바꿔보세요.") }
        } else if (selectedTab == WatchAssetTab.Indicators) {
            if (
                marketIndicatorsViewModel.loading ||
                marketIndicatorsViewModel.error != null ||
                indicatorQuotes.isEmpty()
            ) {
                item {
                    WatchIndicatorDataStatusCard(
                        loading = marketIndicatorsViewModel.loading,
                        error = marketIndicatorsViewModel.error,
                        hasData = indicatorQuotes.isNotEmpty(),
                        onRetry = { marketIndicatorsViewModel.refreshMarketIndicators(refresh = true, category = "all") }
                    )
                }
            }
            items(indicatorRows, key = { row -> row.joinToString("|") { it.ticker } }) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { item ->
                        WatchIndicatorGraphCard(
                            item = item,
                            quote = marketIndicatorQuoteFor(item, indicatorQuotes),
                            points = marketIndicatorPointsFor(item, indicatorHistory),
                            onDelete = {
                                recentlyDeleted = item
                                scope.launchSafely { app.toggleWatch(item) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            items(items, key = { it.ticker }) { item ->
                WatchRow(
                    item = item,
                    insight = companyInsights[normalizedTicker(item.ticker)] ?: item.defaultCompanyInsight(),
                    decision = app.investmentDecision(item.ticker),
                    indicatorQuote = if (item.isMarketIndicatorWatchItem()) marketIndicatorQuoteFor(item, indicatorQuotes) else null,
                    onOpen = if (item.isMarketIndicatorWatchItem()) null else {
                        { app.selectedDetail = item.detailRequest() }
                    },
                    onEdit = if (item.isMarketIndicatorWatchItem()) null else {
                        { editingItem = item }
                    },
                    onCompare = if (item.isMarketIndicatorWatchItem()) null else {
                        {
                            comparisonViewModel.add(item.toComparisonItem())
                            comparisonViewModel.openSheet()
                        }
                    },
                    onDelete = {
                        recentlyDeleted = item
                        scope.launchSafely { app.toggleWatch(item) }
                    }
                )
            }
        }
    }

    editingItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { editingItem = null }) {
            WatchMetadataSheet(
                item = watchlistViewModel.watchlistItem(item.ticker) ?: item,
                onSave = { tags, memo, alerts ->
                    scope.launchSafely {
                        app.updateWatchMetadata(item.ticker, tags, memo, alerts)
                    }
                    editingItem = null
                },
                onDismiss = { editingItem = null }
            )
        }
    }
}

@Composable
private fun WatchControls(
    selectedTab: WatchAssetTab,
    onSelectedTab: (WatchAssetTab) -> Unit,
    totalCount: Int,
    companyCount: Int,
    indicatorCount: Int,
    etfCount: Int
) {
    WatchCard {
        WatchAssetSwitch(
            selectedTab = selectedTab,
            onSelectedTab = onSelectedTab,
            totalCount = totalCount,
            companyCount = companyCount,
            indicatorCount = indicatorCount,
            etfCount = etfCount
        )
    }
}

@Composable
private fun WatchAssetSwitch(
    selectedTab: WatchAssetTab,
    onSelectedTab: (WatchAssetTab) -> Unit,
    totalCount: Int,
    companyCount: Int,
    indicatorCount: Int,
    etfCount: Int
) {
    val labels = remember(totalCount, companyCount, indicatorCount, etfCount) {
        WatchAssetTab.entries.associateWith { tab ->
            val count = when (tab) {
                WatchAssetTab.All -> totalCount
                WatchAssetTab.Companies -> companyCount
                WatchAssetTab.Indicators -> indicatorCount
                WatchAssetTab.Etfs -> etfCount
            }
            "${tab.title} $count"
        }
    }
    QuantSlidingSegmentSwitch(
        options = WatchAssetTab.entries.mapNotNull { labels[it] },
        selected = labels[selectedTab] ?: selectedTab.title,
        onSelect = { label ->
            labels.entries.firstOrNull { it.value == label }?.key?.let(onSelectedTab)
        },
        shape = RoundedCornerShape(999.dp)
    )
}

@Composable
private fun WatchAssetSwitchButton(
    tab: WatchAssetTab,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                tab.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class WatchPriorityItem(
    val item: WatchlistItem,
    val insight: WatchCompanyInsight
)

private data class WatchJudgmentTimelineItem(
    val id: String,
    val title: String,
    val detail: String,
    val source: String,
    val recordedAtMillis: Long?,
    val color: Color
)

@Composable
private fun WatchPriorityQueueCard(
    items: List<WatchPriorityItem>,
    itemCount: Int,
    loading: Boolean,
    onOpen: (WatchlistItem) -> Unit,
    onRefresh: () -> Unit
) {
    val visibleItems = items.take(3)
    val urgentCount = items.count { it.insight.priority >= 3 }
    val summary = when {
        visibleItems.isEmpty() && loading -> "관심 항목의 후보, 실적, 가격 데이터를 맞추고 있습니다."
        visibleItems.isEmpty() -> "큰 변화가 없으면 감시만 유지합니다."
        urgentCount > 0 -> "지금 확인할 신호 ${urgentCount}개"
        else -> "가격, 실적, 후보 신호가 큰 항목부터 정렬했습니다."
    }

    WatchCard {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "오늘 확인할 관심 3개",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(38.dp)) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    LucideIconView(
                        icon = LucideIcon.RefreshCw,
                        contentDescription = "오늘 확인할 관심 새로고침",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (visibleItems.isEmpty()) {
            WatchPriorityEmptyState(itemCount = itemCount, loading = loading)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleItems.forEach { priorityItem ->
                    WatchPriorityRow(
                        priorityItem = priorityItem,
                        onOpen = { onOpen(priorityItem.item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchJudgmentTimelineCard(items: List<WatchJudgmentTimelineItem>) {
    WatchCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.Bell,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "판단 업데이트 타임라인",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${items.size}개",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.take(6).forEach { item ->
                WatchJudgmentTimelineRow(item)
            }
        }
    }
}

@Composable
private fun WatchJudgmentTimelineRow(item: WatchJudgmentTimelineItem) {
    val timeText = item.recordedAtMillis?.let {
        DateUtils.getRelativeTimeSpanString(
            it,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    } ?: "현재"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.title}. ${item.detail}. $timeText"
            }
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(item.color)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.source,
                style = MaterialTheme.typography.labelSmall,
                color = item.color,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun WatchPriorityRow(
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
private fun WatchPriorityLeadingLogo(
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
private fun WatchPriorityEmptyState(
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

private fun watchPriorityIcon(priority: Int): LucideIcon {
    return when {
        priority >= 4 -> LucideIcon.CalendarClock
        priority >= 3 -> LucideIcon.Zap
        priority >= 2 -> LucideIcon.Target
        else -> LucideIcon.Database
    }
}

private fun watchPriorityLabel(priority: Int): String {
    return when {
        priority >= 4 -> "긴급"
        priority >= 3 -> "확인"
        priority >= 2 -> "관찰"
        else -> "연결"
    }
}

@Composable
private fun WatchRow(
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

private fun watchRowPriceText(
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

private fun watchRowMoveText(
    insight: WatchCompanyInsight,
    indicatorQuote: MarketIndicatorQuote?
): String? {
    indicatorQuote?.changePct?.takeIf { it.isFinite() }?.let { return pct(it) }
    insight.details.firstOrNull { it.startsWith("하루 ") }?.let { return it.removePrefix("하루 ") }
    return insight.details.firstOrNull { it.startsWith("1개월 ") }?.removePrefix("1개월 ")
}

private fun watchRowCategoryText(
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
private fun watchRowMoveColor(value: String?): androidx.compose.ui.graphics.Color {
    return when {
        value == null -> MaterialTheme.colorScheme.onSurfaceVariant
        value.trim().startsWith("-") -> QuantNegative
        value.trim().startsWith("+") -> QuantPositive
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchDetailFacts(details: List<String>) {
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
private fun WatchInsightLine(insight: WatchCompanyInsight) {
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
private fun WatchIndicatorAvatar() {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            LucideIconView(
                icon = LucideIcon.LineChart,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun WatchIndicatorGraphCard(
    item: WatchlistItem,
    quote: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if ((quote.changePct ?: 0.0) >= 0.0) QuantPositive else QuantNegative
    val chartPoints = remember(quote, points) {
        displayMarketIndicatorPoints(quote, points)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MarketIndicatorLogoView(item.ticker, item.name, size = 32.dp, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        watchIndicatorValueLine(quote),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "관심 지수 삭제",
                        tint = QuantFavorite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (chartPoints.isEmpty()) {
                WatchIndicatorGraphPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                )
            } else {
                IndicatorSparkline(
                    item = quote,
                    points = chartPoints,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                )
            }

            Text(
                watchIndicatorDescription(item, quote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (quote.value.isFinite()) {
                Text(
                    "갱신 ${formattedUpdateTimestamp(quote.updatedAt)}",
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
private fun WatchIndicatorGraphPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "그래프 대기",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WatchCard(content: @Composable ColumnScope.() -> Unit) {
    QuantCard(padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private fun marketIndicatorQuoteFor(
    item: WatchlistItem,
    indicatorQuotes: List<MarketIndicatorQuote>
): MarketIndicatorQuote {
    val key = canonicalMarketIndicatorSymbol(item)
    return indicatorQuotes.firstOrNull { canonicalMarketIndicatorSymbol(it.symbol) == key }
        ?: MarketIndicatorQuote(
            symbol = key.ifBlank { item.ticker },
            label = item.name,
            category = watchIndicatorCategory(item),
            region = watchIndicatorRegion(item, key),
            value = Double.NaN,
            changeAbs = null,
            changePct = null,
            updatedAt = null
        )
}

private fun marketIndicatorPointsFor(
    item: WatchlistItem,
    indicatorHistory: Map<String, List<MarketIndicatorPoint>>
): List<MarketIndicatorPoint> {
    val key = canonicalMarketIndicatorSymbol(item)
    return indicatorHistory[key]
        ?: indicatorHistory[item.ticker]
        ?: indicatorHistory.entries.firstOrNull { canonicalMarketIndicatorSymbol(it.key) == key }?.value
        ?: emptyList()
}

private fun watchIndicatorRegion(item: WatchlistItem, symbol: String): String {
    return when {
        symbol in setOf("^KS11", "^KQ11", "KRW=X", "IRR_GOVT03Y", "IRR_CORP03Y") -> "domestic"
        item.market == "KR" -> "domestic"
        else -> "overseas"
    }
}

private fun watchIndicatorCategory(item: WatchlistItem): String {
    val symbol = canonicalMarketIndicatorSymbol(item)
    if (symbol in setOf("GC=F", "SI=F", "CL=F", "HG=F")) return "commodity"
    if (symbol in setOf("BTC-USD", "ETH-USD", "SOL-USD")) return "crypto"
    if (symbol in setOf("^IRX", "^FVX", "^TNX", "^TYX", "IRR_GOVT03Y", "IRR_CORP03Y")) return "bond"
    return when (item.market) {
        "원자재" -> "commodity"
        "가상자산" -> "crypto"
        else -> "index_fx"
    }
}

private fun watchIndicatorDescription(item: WatchlistItem, quote: MarketIndicatorQuote): String {
    return when (canonicalMarketIndicatorSymbol(item)) {
        "^IXIC" -> "미국 기술주 중심 나스닥 종합지수"
        "NQ=F" -> "나스닥 100 지수의 선물 가격"
        "^GSPC" -> "미국 대형주 500개 대표 지수"
        "ES=F" -> "S&P 500 지수의 선물 가격"
        "RTY=F" -> "미국 중소형주 러셀 2000 선물"
        "^DJI" -> "미국 대표 우량주 다우존스 지수"
        "^SOX" -> "미국 반도체 업종 대표 지수"
        "^VIX" -> "S&P 500 옵션 기반 변동성 지수"
        "^KS11" -> "한국 유가증권시장 대표 지수"
        "^KQ11" -> "한국 코스닥시장 대표 지수"
        "KRW=X" -> "원/달러 환율 흐름"
        "DX-Y.NYB" -> "주요 통화 대비 달러 가치 지수"
        "^IRX", "^FVX", "^TNX", "^TYX" -> "미국 국채 금리 지표"
        "IRR_GOVT03Y" -> "한국 국고채 3년 금리 지표"
        "IRR_CORP03Y" -> "한국 회사채 3년 금리 지표"
        "GC=F", "SI=F", "CL=F", "HG=F" -> "${item.name} 원자재 선물 가격"
        "BTC-USD", "ETH-USD", "SOL-USD" -> "${item.name} 달러 기준 가상자산 가격"
        else -> when (quote.category) {
            "crypto" -> "${item.name} 가상자산 가격"
            "commodity" -> "${item.name} 원자재 가격"
            "bond" -> "${item.name} 금리 지표"
            else -> "${item.market} 시장 지수"
        }
    }
}

private fun watchIndicatorValueLine(quote: MarketIndicatorQuote): String {
    if (!quote.value.isFinite()) return "데이터 대기"
    return "${indicatorValueText(quote.value)} · ${pct(quote.changePct)}"
}

@Composable
private fun WatchSectionHeader(
    title: String,
    count: String,
    sort: WatchSortOption,
    onSort: (WatchSortOption) -> Unit,
    marketFilter: WatchMarketFilter? = null,
    onMarketFilter: ((WatchMarketFilter) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (marketFilter != null && onMarketFilter != null) {
            Spacer(Modifier.width(8.dp))
            CompactMarketFilter(selected = marketFilter, onSelected = onMarketFilter)
        }
        Spacer(Modifier.weight(1f))
        Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(
                shape = CircleShape,
                color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .quantClickable(role = QuantPressRole.Icon) { expanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = LucideIcon.SlidersHorizontal,
                        contentDescription = "정렬: ${sort.title}",
                        modifier = Modifier.size(19.dp),
                        tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(206.dp),
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "정렬 기준",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    WatchSortOption.entries.forEach { option ->
                        WatchSortMenuRow(
                            option = option,
                            selected = option == sort,
                            onClick = {
                                onSort(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchSortMenuRow(option: WatchSortOption, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LucideIconView(
                icon = watchSortIcon(option),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tint
            )
            Text(
                option.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (selected) {
                LucideIconView(
                    icon = LucideIcon.Check,
                    contentDescription = "선택됨",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun watchSortIcon(option: WatchSortOption): LucideIcon {
    return when (option) {
        WatchSortOption.Signal -> LucideIcon.Target
        WatchSortOption.Added -> LucideIcon.CalendarClock
        WatchSortOption.Name -> LucideIcon.ListOrdered
        WatchSortOption.Market -> LucideIcon.Globe2
    }
}

@Composable
private fun CompactMarketFilter(
    selected: WatchMarketFilter,
    onSelected: (WatchMarketFilter) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WatchMarketFilter.entries.forEach { filter ->
                val isSelected = selected == filter
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelected(filter) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        filter.title,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchEmptyCard(title: String, message: String) {
    WatchCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WatchIndicatorDataStatusCard(
    loading: Boolean,
    error: String?,
    hasData: Boolean,
    onRetry: () -> Unit
) {
    val title = when {
        loading -> "관심 지수 데이터 동기화 중"
        error != null -> "지수 데이터를 불러오지 못했습니다"
        !hasData -> "지수 데이터 대기 중"
        else -> "지수 데이터 확인 필요"
    }
    val detail = when {
        loading -> "그래프와 현재가를 최신 지표로 맞추고 있습니다."
        error != null -> error
        else -> "관심 지수는 저장되어 있지만 그래프와 현재가 데이터가 아직 도착하지 않았습니다."
    }
    WatchCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.AutoGraph,
                    contentDescription = null,
                    tint = if (error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!loading) {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("새로고침")
                }
            }
        }
    }
}

@Composable
private fun WatchUndoBanner(item: WatchlistItem, onUndo: () -> Unit, onDismiss: () -> Unit) {
    WatchCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.name} 삭제됨", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onUndo) { Text("되돌리기") }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "삭제 알림 닫기") }
        }
    }
}

@Composable
private fun WatchSyncBanner(message: String, status: WatchlistSyncStatus, onRetry: () -> Unit) {
    val syncing = status is WatchlistSyncStatus.Syncing
    val synced = status is WatchlistSyncStatus.Synced
    val tone = when {
        syncing -> MaterialTheme.colorScheme.primary
        synced -> QuantGreen
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (syncing || synced) {
            tone.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.74f)
        },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (synced) {
                LucideIconView(
                    icon = LucideIcon.Check,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                LucideIconView(
                    icon = LucideIcon.RefreshCw,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = if (synced) tone else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (!syncing && !synced) {
                OutlinedButton(onClick = onRetry) { Text("재시도") }
            }
        }
    }
}

private data class WatchCompanyInsight(
    val title: String,
    val detail: String,
    val metrics: List<String>,
    val details: List<String>,
    val tone: DetailTone,
    val priority: Int,
    val updatedAt: String?,
    val linked: Boolean,
    val hasUpcomingEarnings: Boolean
)

@Composable
private fun watchInsightColor(insight: WatchCompanyInsight): androidx.compose.ui.graphics.Color {
    return when (insight.tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun watchComparator(
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

private fun watchJudgmentTimelineItems(
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

private fun watchInsightTimelineColor(insight: WatchCompanyInsight): Color {
    return when (insight.tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> Color(0xFF2563EB)
        DetailTone.Neutral -> Color(0xFF64748B)
    }
}

private fun watchSignalScore(
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

private fun compareSignalValues(left: Double, right: Double): Int {
    return when {
        left < right -> -1
        left > right -> 1
        else -> 0
    }
}

private inline fun Int.ifZero(block: () -> Int): Int {
    return if (this == 0) block() else this
}

private fun QuantAppState.companyInsightFor(item: WatchlistItem): WatchCompanyInsight {
    val portfolio = portfolioMatch(item)
    val smallCap = smallCapMatch(item)
    val earnings = earningsMatch(item)
    val calendar = earningsCalendarMatch(item)
    val priceMetric = watchPriceMetric(item.ticker)
    val metrics = (watchMetadataMetrics(item) + watchInsightMetrics(portfolio, smallCap, earnings, calendar)).take(3)
    val details = watchInsightDetails(portfolio, smallCap, earnings, calendar, priceMetric, item)
    val updatedAt = portfolio?.lastUpdated
        ?: smallCap?.lastUpdated
        ?: earnings?.earningsDate
        ?: calendar?.nextEarningsDate
        ?: priceMetric?.updatedAt
    val upcoming = (calendar?.daysUntil ?: 999) in 0..14

    if (calendar != null && (calendar.daysUntil ?: 999) in 0..7) {
        return WatchCompanyInsight(
            title = "실적 임박",
            detail = "${watchEarningsDayText(calendar.daysUntil)} · ${compactDateText(calendar.nextEarningsDate)} 발표 예정",
            metrics = metrics,
            details = details,
            tone = DetailTone.Warning,
            priority = 4,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (earnings?.signalStrength?.isFinite() == true && earnings.signalStrength >= 1.0) {
        return WatchCompanyInsight(
            title = "실적 반응",
            detail = "Signal ${"%.2f".format(earnings.signalStrength)} · 발표 후 수익률 ${pct(earnings.returnSince)}",
            metrics = metrics,
            details = details,
            tone = DetailTone.Primary,
            priority = 3,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (portfolio?.expectedReturn?.isFinite() == true) {
        val expected = portfolio.expectedReturn
        return WatchCompanyInsight(
            title = if (expected >= 0.0) "후보 유지" else "타이밍 확인",
            detail = "기대수익 ${pct(expected)} · 점수 ${score(portfolio.totalScore)}",
            metrics = metrics,
            details = details,
            tone = if (expected >= 0.0) DetailTone.Primary else DetailTone.Warning,
            priority = if (expected >= 0.0) 2 else 3,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (smallCap?.volumeSurge?.isFinite() == true && smallCap.volumeSurge >= 1.5) {
        return WatchCompanyInsight(
            title = "거래량 변화",
            detail = "평소 대비 ${multipleText(smallCap.volumeSurge)} · 스몰캡 점수 ${score(smallCap.totalScore)}",
            metrics = metrics,
            details = details,
            tone = DetailTone.Primary,
            priority = 2,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (portfolio != null || smallCap != null || earnings != null || calendar != null) {
        return WatchCompanyInsight(
            title = "데이터 연결",
            detail = "후보, 실적, 일정 데이터 중 일부가 연결되어 있습니다.",
            metrics = metrics,
            details = details,
            tone = DetailTone.Positive,
            priority = 1,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    val hasPrice = priceMetric?.currentPrice?.isFinite() == true
    return WatchCompanyInsight(
        title = if (item.isEtfWatchItem()) "ETF 감시" else "기본 감시",
        detail = if (hasPrice) "가격 데이터를 연결해 계속 감시 중입니다." else "상세에서 가격과 기업 정보를 확인하세요.",
        metrics = (watchMetadataMetrics(item) + listOf(item.market, item.note)).filter { it.isNotBlank() }.take(3),
        details = details,
        tone = DetailTone.Neutral,
        priority = 0,
        updatedAt = updatedAt ?: item.addedAt.ifBlank { null },
        linked = hasPrice,
        hasUpcomingEarnings = false
    )
}

private fun WatchlistItem.defaultCompanyInsight(): WatchCompanyInsight {
    return WatchCompanyInsight(
        title = "기본 감시",
        detail = "상세에서 가격과 기업 정보를 확인하세요.",
        metrics = (watchMetadataMetrics(this) + listOf(market, note)).filter { it.isNotBlank() }.take(3),
        details = listOf(market, note).filter { it.isNotBlank() }.take(2),
        tone = DetailTone.Neutral,
        priority = 0,
        updatedAt = addedAt.ifBlank { null },
        linked = false,
        hasUpcomingEarnings = false
    )
}

private fun watchMetadataMetrics(item: WatchlistItem): List<String> {
    return buildList {
        item.primaryTag?.let { add(it) }
        item.alertOptions.firstOrNull()?.let { add(watchAlertDisplayLabel(it)) }
    }
}

private fun QuantAppState.portfolioMatch(item: WatchlistItem): PortfolioStock? {
    val key = normalizedTicker(item.ticker)
    return (usPortfolio + krPortfolio).firstOrNull { normalizedTicker(it.ticker) == key }
}

private fun QuantAppState.smallCapMatch(item: WatchlistItem): SmallCapStock? {
    val key = normalizedTicker(item.ticker)
    return (usSmallCap + krSmallCap).firstOrNull { normalizedTicker(it.ticker) == key }
}

private fun QuantAppState.earningsMatch(item: WatchlistItem): EarningsStock? {
    val key = normalizedTicker(item.ticker)
    return (usEarnings + krEarnings).firstOrNull { normalizedTicker(it.ticker) == key }
}

private fun QuantAppState.earningsCalendarMatch(item: WatchlistItem): EarningsCalendarItem? {
    val key = normalizedTicker(item.ticker)
    return earningsCalendar
        .filter { (it.daysUntil ?: 0) >= 0 }
        .sortedBy { it.nextEarningsDate }
        .firstOrNull { normalizedTicker(it.ticker) == key }
}

private fun watchInsightMetrics(
    portfolio: PortfolioStock?,
    smallCap: SmallCapStock?,
    earnings: EarningsStock?,
    calendar: EarningsCalendarItem?
): List<String> {
    return buildList {
        if (portfolio?.expectedReturn?.isFinite() == true) {
            add("기대 ${pct(portfolio.expectedReturn)}")
        } else if (portfolio?.totalScore?.isFinite() == true) {
            add("점수 ${"%.0f".format(portfolio.totalScore)}")
        }
        if (smallCap?.totalScore?.isFinite() == true) {
            add("스몰캡 ${"%.0f".format(smallCap.totalScore)}")
        }
        if (earnings?.signalStrength?.isFinite() == true) {
            add("Signal ${"%.2f".format(earnings.signalStrength)}")
        }
        if (calendar?.daysUntil != null) {
            add(watchEarningsDayText(calendar.daysUntil))
        }
    }.take(3)
}

private fun watchInsightDetails(
    portfolio: PortfolioStock?,
    smallCap: SmallCapStock?,
    earnings: EarningsStock?,
    calendar: EarningsCalendarItem?,
    priceMetric: StockPriceMetric?,
    item: WatchlistItem
): List<String> {
    val currency = portfolio?.let { marketCurrency(it.ticker, it.market) }
        ?: smallCap?.let { marketCurrency(it.ticker, it.market) }
        ?: item.currency
    return buildList {
        val price = listOf(portfolio?.currentPrice, smallCap?.currentPrice, priceMetric?.currentPrice)
            .firstOrNull { it?.isFinite() == true }
        if (price?.isFinite() == true) add("가격 ${fmtPx(price, currency)}")
        val dailyChange = priceMetric?.dailyChangePct
        if (dailyChange?.isFinite() == true) {
            add("하루 ${pct(dailyChange)}")
        } else {
            val return1M = listOf(portfolio?.return1M, smallCap?.return1M, priceMetric?.return1M)
                .firstOrNull { it?.isFinite() == true }
            if (return1M?.isFinite() == true) add("1개월 ${pct(return1M)}")
        }
        val marketCap = portfolio?.marketCap ?: smallCap?.marketCap ?: earnings?.marketCap ?: calendar?.marketCap
        if (marketCap?.isFinite() == true) add("시총 ${cap(marketCap, currency)}")
        val sector = portfolio?.sector ?: earnings?.sector ?: calendar?.sector
        if (!sector.isNullOrBlank()) add(portfolioIndustryLabel(item.ticker, item.name, sector))
        val earningsDate = calendar?.nextEarningsDate ?: earnings?.earningsDate
        if (!earningsDate.isNullOrBlank()) add("실적 ${compactDateText(earningsDate)}")
        if (isEmpty()) addAll(listOf(item.market, item.note).filter { it.isNotBlank() })
    }.take(4)
}

private fun watchEarningsDayText(days: Int?): String {
    return when {
        days == null -> "D-?"
        days == 0 -> "D-Day"
        days > 0 -> "D-$days"
        else -> "D+${kotlin.math.abs(days)}"
    }
}

private fun WatchlistItem.detailRequest(): DetailRequest {
    return DetailRequest(
        ticker = ticker,
        name = name,
        currency = currency,
        market = market,
        sections = listOf(
            DetailSection(
                "관심 종목",
                listOf(
                    DetailMetric("분류", note),
                    DetailMetric("시장", market),
                    DetailMetric("통화", currency),
                    DetailMetric("저장일", addedLabel(addedAt))
                )
            )
        ),
        signals = listOf(
            watchlistSignal(this)
        ),
        factors = emptyList()
    )
}

private fun watchlistSignal(item: WatchlistItem): DetailSignal {
    val thesis = item.investmentThesis
    return if (thesis.isEmpty) {
        DetailSignal(
            "관심 종목",
            "관심 목록에 저장한 종목입니다. 가격, 52주 범위, 기업 정보를 확인하세요.",
            DetailTone.Primary
        )
    } else {
        DetailSignal("투자 가설", thesis.detailSummary, DetailTone.Primary)
    }
}

private fun addedLabel(value: String): String {
    return value.take(10).ifBlank { "방금" }
}
