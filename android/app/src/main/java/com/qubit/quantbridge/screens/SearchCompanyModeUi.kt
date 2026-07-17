package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListScope
import kotlinx.coroutines.CoroutineScope

internal fun LazyListScope.searchCompanyModeItems(
    companyRows: List<SearchStock>,
    groupedCompanyRows: List<Pair<SearchResultGroup, List<SearchStock>>>,
    companyFilter: SearchCompanyFilter,
    isLoading: Boolean,
    normalizedQuery: String,
    query: String,
    app: QuantAppState,
    scope: CoroutineScope,
    comparisonViewModel: ComparisonViewModel,
    onRecordRecentSearch: (String) -> Unit
) {
    item {
        HeaderCard(
            title = "전체 기업 검색",
            value = "${companyRows.size}개",
            subtitle = "${companyFilter.label} · US + KR 유니버스 · 분석 상위군/스몰캡 포함 여부 표시",
            trailing = if (isLoading) "동기화" else "검색",
            quiet = true
        )
    }
    if (companyRows.isEmpty()) {
        item {
            if (isLoading) {
                SkeletonLoadingCard(lineCount = 2)
            } else {
                EmptyCard(
                    "검색 결과 없음",
                    searchEmptyMessage(normalizedQuery),
                    lucideIcon = LucideIcon.Search
                )
            }
        }
    } else {
        groupedCompanyRows.forEach { (group, stocks) ->
            item(key = "search-group-${group.label}") {
                SearchGroupHeader(group = group, count = stocks.size)
            }
            itemsIndexed(stocks, key = { _, stock -> "${group.label}-${stock.ticker}" }) { _, stock ->
                val globalIndex = companyRows.indexOfFirst { it.ticker == stock.ticker && it.market == stock.market }.let { if (it >= 0) it + 1 else 1 }
                StockRow(
                    rankLabel = "$globalIndex",
                    title = stock.name,
                    ticker = stock.ticker,
                    market = stock.market,
                    subtitle = stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) }
                        ?: cap(stock.marketCap, stock.currency),
                    headline = stock.market ?: stock.currency,
                    kpis = listOf(
                        "시총" to cap(stock.marketCap, stock.currency),
                        "상태" to searchStatus(stock)
                    ),
                    watched = app.isWatched(stock.ticker),
                    onWatch = { scope.launchSafely { app.toggleWatch(watchItem(stock.ticker, stock.name, stock.market, stock.currency, "검색")) } },
                    onCompare = {
                        comparisonViewModel.add(stock.toComparisonItem())
                    },
                    comparisonSelected = comparisonViewModel.contains(stock.toComparisonItem()),
                    onOpen = {
                        onRecordRecentSearch(query)
                        app.selectedDetail = searchDetail(stock)
                    }
                )
            }
        }
    }
}
