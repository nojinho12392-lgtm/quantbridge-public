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

internal val WATCH_NAV_CONTENT_INSET = 104.dp
internal const val WATCH_PRICE_AUTO_REFRESH_MS = 180_000L

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
