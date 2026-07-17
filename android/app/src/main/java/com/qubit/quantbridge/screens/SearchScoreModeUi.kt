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

internal fun LazyListScope.searchScoreModeItems(
    scoredRows: List<ScoredStock>,
    showAdvancedModes: Boolean,
    isLoading: Boolean,
    normalizedQuery: String,
    app: QuantAppState,
    scope: CoroutineScope,
    comparisonViewModel: ComparisonViewModel
) {
    item {
        HeaderCard(
            title = if (showAdvancedModes) "Scored Stocks" else "추천 후보",
            value = "${scoredRows.size}개",
            subtitle = if (showAdvancedModes) {
                "Value · Quality · Momentum · Total Score 탐색"
            } else {
                "종합 점수와 성장/수익성 중심으로 후보를 정리합니다."
            },
            trailing = if (isLoading) "동기화" else if (showAdvancedModes) "팩터" else "추천",
            quiet = true
        )
    }
    if (scoredRows.isEmpty()) {
        item {
            if (isLoading) {
                SkeletonLoadingCard(lineCount = 2)
            } else {
                EmptyCard(
                    if (showAdvancedModes) "스코어 데이터 없음" else "추천 데이터 없음",
                    searchEmptyMessage(normalizedQuery),
                    lucideIcon = LucideIcon.Search
                )
            }
        }
    } else {
        itemsIndexed(scoredRows, key = { _, stock -> "${stock.market}:${stock.ticker}" }) { index, stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            StockRow(
                rankLabel = "${index + 1}",
                title = stock.name,
                ticker = stock.ticker,
                market = stock.market,
                subtitle = stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) }
                    ?: cap(stock.marketCap, currency),
                headline = bestScoredValue(stock)?.let { "%.3f".format(it) } ?: "-",
                kpis = scoredKpis(stock),
                watched = app.isWatched(stock.ticker),
                onWatch = { scope.launchSafely { app.toggleWatch(watchItem(stock.ticker, stock.name, stock.market, currency, if (showAdvancedModes) "스코어" else "추천")) } },
                onCompare = {
                    comparisonViewModel.add(stock.toComparisonItem())
                },
                comparisonSelected = comparisonViewModel.contains(stock.toComparisonItem()),
                onOpen = { app.selectedDetail = scoredDetail(stock) }
            )
        }
    }
}
