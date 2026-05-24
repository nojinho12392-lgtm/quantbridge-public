package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.example.myapplication.ui.theme.QuantBlue
import com.example.myapplication.ui.theme.QuantFavorite
import com.example.myapplication.ui.theme.QuantGreen
import com.example.myapplication.ui.theme.QuantLine
import com.example.myapplication.ui.theme.QuantMuted
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantPositive
import com.example.myapplication.ui.theme.QuantPurple
import com.example.myapplication.ui.theme.QuantWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

private const val SEARCH_DEBOUNCE_MS = 400L
private const val SEARCH_MIN_QUERY_LENGTH = 2
private enum class SearchCompanyFilter(val label: String) {
    All("전체"),
    Portfolio("포트"),
    SmallCap("스몰캡"),
    Unwatched("미저장");

    fun matches(stock: SearchStock, watchlist: List<WatchlistItem>): Boolean {
        return when (this) {
            All -> true
            Portfolio -> stock.inPortfolio
            SmallCap -> stock.inSmallCap
            Unwatched -> watchlist.none { normalizedTicker(it.ticker) == normalizedTicker(stock.ticker) }
        }
    }
}

private enum class SearchResultGroup(val label: String, val icon: LucideIcon, val order: Int) {
    Company("기업", LucideIcon.Building2, 0),
    Etf("ETF", LucideIcon.PieChart, 1),
    Indicator("지수", LucideIcon.LineChart, 2),
    Other("기타", LucideIcon.Search, 3)
}

@Composable
fun SearchScreen(
    app: QuantAppState,
    showAdvancedModes: Boolean = true,
    searchViewModel: SearchViewModel = hiltViewModel(),
    strategyViewModel: StrategyViewModel = hiltViewModel(),
    opsViewModel: OpsViewModel = hiltViewModel(),
    comparisonViewModel: ComparisonViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesRepository = remember(context) { UserPreferencesRepository(context.applicationContext) }
    val scoreModeLabel = if (showAdvancedModes) "스코어" else "추천"
    val modeOptions = remember(showAdvancedModes) {
        if (showAdvancedModes) listOf("기업", "스코어", "전략", "진단") else listOf("기업", "추천")
    }
    var mode by remember(showAdvancedModes) { mutableStateOf("기업") }
    var marketFilter by remember { mutableStateOf("ALL") }
    var companyFilter by remember { mutableStateOf(SearchCompanyFilter.All) }
    var query by remember { mutableStateOf("") }
    var lastAutoSearchQuery by remember { mutableStateOf("") }
    var recentSearchRaw by remember { mutableStateOf("") }
    var diagnosticInfo by remember { mutableStateOf<DiagnosticInfo?>(null) }
    val normalizedQuery = query.trim()
    val recentSearches = remember(recentSearchRaw) { parseRecentSearches(recentSearchRaw) }
    val watchSnapshot = app.watchlist.toList()
    fun canonicalMode(label: String): String = if (label == scoreModeLabel) "스코어" else label
    val activeMode = canonicalMode(mode)
    fun recordRecentSearch(value: String) {
        val next = updatedRecentSearchRaw(recentSearches, value)
        if (next == recentSearchRaw) return
        recentSearchRaw = next
        scope.launch { preferencesRepository.setRecentSearchesRaw(next) }
    }
    fun clearRecentSearches() {
        recentSearchRaw = ""
        scope.launch { preferencesRepository.setRecentSearchesRaw("") }
    }
    fun submitCompanySearch() {
        lastAutoSearchQuery = normalizedQuery
        recordRecentSearch(normalizedQuery)
        searchViewModel.searchCompanies(normalizedQuery)
    }

    LaunchedEffect(activeMode) {
        when (activeMode) {
            "기업", "스코어" -> searchViewModel.loadExplore(activeMode, query)
            "전략" -> strategyViewModel.loadStrategy()
            "진단" -> opsViewModel.loadOps()
            else -> app.loadExplore(activeMode, query)
        }
    }

    LaunchedEffect(preferencesRepository) {
        recentSearchRaw = preferencesRepository.recentSearchesSnapshot()
    }

    LaunchedEffect(activeMode, normalizedQuery) {
        if (activeMode != "기업") return@LaunchedEffect
        if (normalizedQuery == lastAutoSearchQuery) return@LaunchedEffect
        if (normalizedQuery.isNotEmpty() && normalizedQuery.length < SEARCH_MIN_QUERY_LENGTH) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        lastAutoSearchQuery = normalizedQuery
        searchViewModel.searchCompanies(normalizedQuery)
    }

    LaunchedEffect(modeOptions) {
        if (mode !in modeOptions) mode = "기업"
    }

    val companyRows = remember(searchViewModel.searchResults, marketFilter, companyFilter, query, watchSnapshot) {
        searchViewModel.searchResults
            .filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
            .filter { companyFilter.matches(it, watchSnapshot) }
            .filter { matches(query, it.ticker, it.name, it.sector) }
    }
    val groupedCompanyRows = remember(companyRows) {
        companyRows
            .groupBy { searchResultGroup(it) }
            .toList()
            .sortedWith(compareBy<Pair<SearchResultGroup, List<SearchStock>>> { it.first.order }.thenBy { it.first.label })
    }

    val scoredRows = remember(searchViewModel.usScored, searchViewModel.krScored, marketFilter, query) {
        (searchViewModel.usScored + searchViewModel.krScored)
            .filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
            .filter { matches(query, it.ticker, it.name, it.sector) }
            .sortedWith(compareByDescending<ScoredStock> { bestScoredValue(it) ?: Double.NEGATIVE_INFINITY })
    }

    val riskHoldingRows = remember(strategyViewModel.riskHoldings, marketFilter, query) {
        strategyViewModel.riskHoldings
            .filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
            .filter { matches(query, it.ticker, it.name, it.sector) }
    }

    val riskSectorRows = remember(strategyViewModel.riskSectors, marketFilter) {
        strategyViewModel.riskSectors.filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
    }

    val rebalanceRows = remember(strategyViewModel.rebalanceOrders, marketFilter, query) {
        strategyViewModel.rebalanceOrders
            .filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
            .filter { matches(query, it.ticker, it.name, null) }
    }

    val shadowSummaryRows = remember(strategyViewModel.shadowSummaries, marketFilter) {
        strategyViewModel.shadowSummaries.filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
    }

    val shadowItemRows = remember(strategyViewModel.shadowItems, marketFilter, query) {
        strategyViewModel.shadowItems
            .filter { marketFilter == "ALL" || it.market.equals(marketFilter, ignoreCase = true) }
            .filter { matches(query, it.ticker, it.name, null) }
    }
    val activeVisibleCount = when (activeMode) {
        "기업" -> companyRows.size
        "스코어" -> scoredRows.size
        "전략" -> riskHoldingRows.size + riskSectorRows.size + rebalanceRows.size + shadowItemRows.size
        else -> listOf(opsViewModel.researchQuality, opsViewModel.mlBlendReport, opsViewModel.opsHealth).count { it != null }
    }
    val activeTotalCount = when (activeMode) {
        "기업" -> searchViewModel.searchResults.size
        "스코어" -> searchViewModel.usScored.size + searchViewModel.krScored.size
        "전략" -> strategyViewModel.riskHoldings.size +
            strategyViewModel.riskSectors.size +
            strategyViewModel.rebalanceOrders.size +
            strategyViewModel.shadowItems.size
        else -> 3
    }
    val canSubmitSearch = activeMode != "기업" || normalizedQuery.isEmpty() || normalizedQuery.length >= SEARCH_MIN_QUERY_LENGTH

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 10.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftSegmentSwitch(
                    options = modeOptions,
                    selected = mode,
                    onSelect = { mode = it }
                )
                SoftSegmentSwitch(
                    options = listOf("ALL", "US", "KR"),
                    selected = marketFilter,
                    onSelect = { marketFilter = it }
                )
                if (activeMode == "기업") {
                    SoftSegmentSwitch(
                        options = SearchCompanyFilter.entries.map { it.label },
                        selected = companyFilter.label,
                        onSelect = { label ->
                            companyFilter = SearchCompanyFilter.entries.first { it.label == label }
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BorderlessSearchField(
                        query = query,
                        onQuery = { query = it },
                        placeholder = "AAPL, 삼성, NVDA, 현대차...",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        submitCompanySearch()
                    }, enabled = canSubmitSearch) {
                        LucideIconView(
                            icon = LucideIcon.Search,
                            contentDescription = "검색",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                SearchStatusLine(
                    query = normalizedQuery,
                    visibleCount = activeVisibleCount,
                    totalCount = activeTotalCount,
                    label = mode,
                    loading = when (activeMode) {
                        "기업", "스코어" -> searchViewModel.isExploreLoading(activeMode)
                        "전략" -> strategyViewModel.loading
                        "진단" -> opsViewModel.loading
                        else -> app.isExploreLoading(activeMode)
                    }
                )
                if (activeMode == "기업" && normalizedQuery.isBlank() && recentSearches.isNotEmpty()) {
                    RecentSearchChips(
                        items = recentSearches,
                        onSelect = { value ->
                            query = value
                            lastAutoSearchQuery = value.trim()
                            recordRecentSearch(value)
                            searchViewModel.searchCompanies(value.trim())
                        },
                        onClear = { clearRecentSearches() }
                    )
                }
                val exploreError = when (activeMode) {
                    "기업", "스코어" -> searchViewModel.exploreErrorFor(activeMode)
                    "전략" -> strategyViewModel.error
                    "진단" -> opsViewModel.error
                    else -> app.exploreErrorFor(activeMode)
                }
                exploreError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (comparisonViewModel.items.isNotEmpty()) {
                    SearchComparisonDock(
                        count = comparisonViewModel.items.size,
                        onOpen = { comparisonViewModel.openSheet() },
                        onClear = { comparisonViewModel.clear() }
                    )
                }
            }
        }

        if (activeMode == "기업") {
            item {
                HeaderCard(
                    title = "전체 기업 검색",
                    value = "${companyRows.size}개",
                    subtitle = "${companyFilter.label} · US + KR 유니버스 · 분석 상위군/스몰캡 포함 여부 표시",
                    trailing = if (searchViewModel.isExploreLoading("기업")) "동기화" else "검색",
                    quiet = true
                )
            }
            if (companyRows.isEmpty()) {
                item {
                    if (searchViewModel.isExploreLoading("기업")) {
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
                                recordRecentSearch(query)
                                app.selectedDetail = searchDetail(stock)
                            }
                        )
                    }
                }
            }
        } else if (activeMode == "스코어") {
            item {
                HeaderCard(
                    title = if (showAdvancedModes) "Scored Stocks" else "추천 후보",
                    value = "${scoredRows.size}개",
                    subtitle = if (showAdvancedModes) {
                        "Value · Quality · Momentum · Total Score 탐색"
                    } else {
                        "종합 점수와 성장/수익성 중심으로 후보를 정리합니다."
                    },
                    trailing = if (searchViewModel.isExploreLoading("스코어")) "동기화" else if (showAdvancedModes) "팩터" else "추천",
                    quiet = true
                )
            }
            if (scoredRows.isEmpty()) {
                item {
                    if (searchViewModel.isExploreLoading("스코어")) {
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
        } else if (mode == "전략") {
            item {
                HeaderCard(
                    title = "백테스트",
                    value = "${strategyViewModel.backtests.size}개",
                    subtitle = "미국/국내 분석 상위군과 스몰캡 백테스트 요약",
                    trailing = if (strategyViewModel.loading) "동기화" else "성과"
                )
            }
            if (strategyViewModel.backtests.isEmpty()) {
                item { EmptyCard("백테스트 없음", "백테스트 시트 또는 저장소 데이터가 아직 없습니다.") }
            } else {
                items(strategyViewModel.backtests, key = { it.sheet }) { summary ->
                    StatusRow(
                        title = backtestTitle(summary),
                        status = summary.market,
                        subtitle = "누적 ${pct(summary.cumulativeReturn)} · MDD ${pct(summary.maxDrawdown)} · 기간 ${summary.periods} · ${summary.latestDate.ifBlank { "-" }}"
                    )
                }
            }

            item { SectionTitle("Rebalance Drift", "${strategyViewModel.driftItems.size}") }
            if (strategyViewModel.driftItems.isEmpty()) {
                item { EmptyCard("드리프트 없음", "Portfolio_Drift_Alert 데이터가 아직 없습니다.") }
            } else {
                items(strategyViewModel.driftItems.take(20), key = { "${it.market}:${it.ticker}" }) { item ->
                    StatusRow(
                        title = "${item.market} ${item.name}",
                        status = item.status,
                        subtitle = "${item.ticker} · 드리프트 ${pct(item.driftAbs, signed = false)} · 목표 ${pct(item.targetWeight, signed = false)} → 현재 ${pct(item.currentWeight, signed = false)}"
                    )
                }
            }

            item { SectionTitle("Portfolio Risk", "${riskHoldingRows.size}") }
            if (riskHoldingRows.isEmpty()) {
                item { EmptyCard("리스크 기여도 없음", "Final_Portfolio_Risk 데이터가 아직 없습니다.") }
            } else {
                items(riskHoldingRows.take(20), key = { "${it.market}:${it.ticker}" }) { item ->
                    StatusRow(
                        title = "${item.market ?: "-"} ${item.name}",
                        status = pct(item.riskContributionPct, signed = false),
                        subtitle = "${item.ticker} · 비중 ${pct(item.portfolioWeight, signed = false)} · 변동성 ${pct(item.assetVol, signed = false)} · W/R ${num(item.weightRiskRatio)}"
                    )
                }
            }

            item { SectionTitle("Sector Risk", "${riskSectorRows.size}") }
            if (riskSectorRows.isEmpty()) {
                item { EmptyCard("섹터 리스크 없음", "섹터별 리스크 기여도 데이터가 아직 없습니다.") }
            } else {
                items(riskSectorRows.take(12), key = { "${it.market}:${it.sector}" }) { item ->
                    StatusRow(
                        title = "${item.market ?: "-"} ${item.sector}",
                        status = pct(item.riskContributionPct, signed = false),
                        subtitle = "비중 ${pct(item.sectorWeight, signed = false)} · 보유 ${item.holdings?.toInt() ?: 0}개"
                    )
                }
            }

            item { SectionTitle("Rebalance Orders", "${rebalanceRows.size}") }
            if (rebalanceRows.isEmpty()) {
                item { EmptyCard("실행 주문 없음", "리밸런싱 주문 데이터가 아직 없습니다.") }
            } else {
                items(rebalanceRows.take(20), key = { "${it.market}:${it.ticker}:${it.action}" }) { item ->
                    StatusRow(
                        title = "${item.market ?: "-"} ${item.name}",
                        status = item.action,
                        subtitle = "${item.ticker} · ${rebalanceTradeText(item)} · 목표 ${pct(item.targetWeight, signed = false)} · 비용 ${compactNumber(item.costEstimate)}"
                    )
                }
            }

            item { SectionTitle("Shadow Attribution", "${shadowSummaryRows.size}") }
            if (shadowSummaryRows.isEmpty()) {
                item { EmptyCard("섀도우 평가 없음", "Shadow_Portfolio_Attribution 데이터가 아직 없습니다.") }
            } else {
                items(shadowSummaryRows.take(6), key = { "${it.market}:${it.horizonTradingDays}" }) { item ->
                    StatusRow(
                        title = "${item.market} ${horizonLabel(item.horizonTradingDays)}",
                        status = pct(item.alphaActual),
                        subtitle = "실제 ${pct(item.actualReturn)} · BM ${pct(item.benchmarkReturn)} · 적중률 ${pct(item.hitRate, signed = false)} · IC ${num(item.scoreReturnIc)}"
                    )
                }
            }

            item { SectionTitle("Shadow Contributors", "${shadowItemRows.size}") }
            if (shadowItemRows.isEmpty()) {
                item { EmptyCard("종목별 귀속 없음", "종목별 섀도우 성과 귀속 데이터가 아직 없습니다.") }
            } else {
                items(shadowItemRows.take(15), key = { "${it.market}:${it.ticker}:${it.horizonTradingDays}" }) { item ->
                    StatusRow(
                        title = "${item.market} ${item.name}",
                        status = pct(item.actualContribution),
                        subtitle = "${item.ticker} · ${horizonLabel(item.horizonTradingDays)} · 수익률 ${pct(item.stockReturn)} · BM ${pct(item.benchmarkReturn)} · 초과 ${pct(item.excessContribution)}"
                    )
                }
            }

            item { SectionTitle("US Industry Ranking", "${strategyViewModel.industryItems.size}") }
            if (strategyViewModel.industryItems.isEmpty()) {
                item { EmptyCard("업종 랭킹 없음", "US_Industry_Ranking 데이터가 아직 없습니다.") }
            } else {
                items(strategyViewModel.industryItems.take(15), key = { "${it.rank}:${it.industry}" }) { item ->
                    StatusRow(
                        title = "#${item.rank ?: "-"} ${item.industry}",
                        status = "Rank",
                        subtitle = "종목 ${item.stockCount ?: 0} · 평균수익 ${pct(item.meanReturn)} · Breadth ${pct(item.breadth, signed = false)}"
                    )
                }
            }

            item { SectionTitle("KR Order Flow", "${strategyViewModel.orderFlowItems.size}") }
            if (strategyViewModel.orderFlowItems.isEmpty()) {
                item { EmptyCard("오더플로우 없음", "KR_Dual_Net_Buyers 데이터가 아직 없습니다.") }
            } else {
                items(strategyViewModel.orderFlowItems.take(15), key = { it.ticker }) { item ->
                    StatusRow(
                        title = "#${item.rank ?: "-"} ${item.name}",
                        status = "${item.consecutiveDays ?: 0}일",
                        subtitle = "${item.ticker} · 외국인 ${compactNumber(item.foreignNetBuy)} · 기관 ${compactNumber(item.instNetBuy)}"
                    )
                }
            }
        } else {
            val quality = opsViewModel.researchQuality
            val mlBlend = opsViewModel.mlBlendReport
            val ops = opsViewModel.opsHealth
            item {
                DiagnosticHeaderCard(
                    title = "리서치 품질",
                    value = quality?.overallStatus ?: "-",
                    subtitle = "경고 ${quality?.warningCount ?: 0} · 운영 가능 ${quality?.productionReadyCount ?: 0} · Proxy ${quality?.proxyEvidenceCount ?: 0}",
                    trailing = "${quality?.items?.size ?: 0}",
                    onClick = { diagnosticInfo = researchQualityDiagnosticInfo(quality) }
                )
            }
            item {
                DiagnosticHeaderCard(
                    title = "AI 보정",
                    value = mlBlend?.status ?: "-",
                    subtitle = "AI ${pct(mlBlend?.latest?.mlWeight, signed = false)} · 기본 점수 ${pct(mlBlend?.latest?.factorWeight, signed = false)} · 예측력 ${num(mlBlend?.latest?.rankIc)}",
                    trailing = mlBlend?.latest?.predictedStocks?.toInt()?.toString() ?: "${mlBlend?.items?.size ?: 0}",
                    onClick = { diagnosticInfo = mlBlendDiagnosticInfo(mlBlend) }
                )
            }
            item {
                DiagnosticHeaderCard(
                    title = "운영 상태",
                    value = ops?.status ?: "-",
                    subtitle = "체크 ${ops?.checks?.size ?: 0} · 생성 ${ops?.generatedAt?.take(16) ?: "-"}",
                    trailing = if (ops?.healthy == true) "OK" else "확인",
                    onClick = { diagnosticInfo = opsHealthDiagnosticInfo(ops) }
                )
            }
            item { SectionTitle("AI 보정 리포트", "${mlBlend?.items?.size ?: 0}") }
            if (mlBlend?.items.isNullOrEmpty()) {
                item { EmptyCard("AI 보정 리포트 없음", "AI 보정 데이터가 아직 없습니다.") }
            } else {
                itemsIndexed(
                    mlBlend!!.items.take(20),
                    key = { index, item -> "${item.market}:${item.generated}:$index" }
                ) { _, item ->
                    StatusRow(
                        title = "${item.market} ${item.model}",
                        status = item.status ?: mlBlend!!.status,
                        subtitle = "AI ${pct(item.mlWeight, signed = false)} · 기본 점수 ${pct(item.factorWeight, signed = false)} · 예측력 ${num(item.rankIc)} · 독립성 ${num(item.mlFactorSpearman)}"
                    )
                }
            }
            item { SectionTitle("Signal Quality Gates", "${quality?.items?.size ?: 0}") }
            if (quality?.items.isNullOrEmpty()) {
                item { EmptyCard("품질 게이트 없음", "research-quality 실행 결과가 아직 없습니다.") }
            } else {
                itemsIndexed(
                    quality!!.items.take(30),
                    key = { index, gate -> "${gate.market}:${gate.factor}:$index" }
                ) { _, gate ->
                    StatusRow(
                        title = "${gate.market} ${gate.factor}",
                        status = gate.status,
                        subtitle = "IC ${num(gate.meanIc)} · 양수율 ${pct(gate.positiveRate, signed = false)} · 스냅샷 ${gate.snapshots?.toInt() ?: 0}"
                    )
                }
            }
            item { SectionTitle("Ops Checks", "${ops?.checks?.size ?: 0}") }
            if (ops?.checks.isNullOrEmpty()) {
                item { EmptyCard("운영 체크 없음", "API 운영 상태를 불러오지 못했습니다.") }
            } else {
                items(ops!!.checks, key = { it.name }) { check ->
                    StatusRow(check.name, check.status, check.message.ifBlank { "세부 메시지 없음" })
                }
            }
        }
    }

    diagnosticInfo?.let { info ->
        DiagnosticInfoDialog(
            info = info,
            onDismiss = { diagnosticInfo = null }
        )
    }
}

@Composable
private fun SearchComparisonDock(
    count: Int,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LucideIconView(
                icon = LucideIcon.GitCompare,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("비교 $count/4", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("비우기") }
            Button(onClick = onOpen, enabled = count >= 2) { Text("보기") }
        }
    }
}

@Composable
private fun StatusRow(title: String, status: String, subtitle: String) {
    val tone = statusTone(status)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(status.uppercase(Locale.US), color = toneColor(tone), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RecentSearchChips(
    items: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LucideIconView(
                icon = LucideIcon.CalendarClock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "최근 검색",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "지우기",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .quantClickable(role = QuantPressRole.Text, onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = items, key = { it }) { item ->
                Surface(
                    modifier = Modifier.quantClickable(role = QuantPressRole.Text, onClick = { onSelect(item) }),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 1.dp
                ) {
                    Text(
                        item,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchGroupHeader(group: SearchResultGroup, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = CircleShape
        ) {
            LucideIconView(
                icon = group.icon,
                contentDescription = null,
                modifier = Modifier.padding(7.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            group.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "${count}개",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchStatusLine(
    query: String,
    visibleCount: Int,
    totalCount: Int,
    label: String,
    loading: Boolean
) {
    val text = when {
        loading -> "$label 동기화 중"
        query.isBlank() -> "$label 전체 ${totalCount}개"
        else -> "\"$query\" ${visibleCount}/${totalCount}개"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LucideIconView(
            icon = if (loading) LucideIcon.RefreshCw else LucideIcon.SlidersHorizontal,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun parseRecentSearches(raw: String): List<String> {
    return raw
        .split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun updatedRecentSearchRaw(current: List<String>, value: String): String {
    val clean = value.trim()
    if (clean.length < SEARCH_MIN_QUERY_LENGTH) return current.joinToString("|")
    return (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) })
        .take(8)
        .joinToString("|")
}

private fun searchResultGroup(stock: SearchStock): SearchResultGroup {
    val ticker = stock.ticker.uppercase(Locale.US)
    val sector = stock.sector.orEmpty().uppercase(Locale.US)
    return when {
        sector.startsWith("ETF") -> SearchResultGroup.Etf
        ticker.startsWith("^") || ticker.endsWith("=F") || ticker.endsWith("=X") -> SearchResultGroup.Indicator
        sector.isNotBlank() -> SearchResultGroup.Company
        else -> SearchResultGroup.Other
    }
}

private fun searchEmptyMessage(query: String): String {
    return if (query.isBlank()) {
        "현재 선택한 시장과 모드에 일치하는 데이터가 없습니다. 큐빗은 모든 종목을 얕게 보여주지 않고, 분석 가능한 기업만 깊게 봅니다."
    } else {
        "\"$query\"는 아직 큐빗 커버리지 밖일 수 있습니다. 데이터 품질과 추적 기준을 통과한 기업부터 먼저 보여줍니다."
    }
}

private fun searchStatus(stock: SearchStock): String {
    return when {
        stock.inPortfolio && stock.inSmallCap -> "포트+스몰"
        stock.inPortfolio -> "포트"
        stock.inSmallCap -> "스몰"
        else -> "-"
    }
}

private fun scoredKpis(stock: ScoredStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.valueScore?.let { "V" to num(it) },
        stock.qualityScore?.let { "Q" to num(it) },
        stock.momentumScore?.let { "M" to num(it) },
        stock.mlScore?.let { "ML" to num(it) }
    ).ifEmpty {
        listOfNotNull(
            stock.roic?.let { "ROIC" to pct(it, signed = false) },
            stock.revGrowth?.let { "성장" to pct(it) },
            stock.grossMargin?.let { "마진" to pct(it, signed = false) }
        )
    }
}

internal fun searchDetail(stock: SearchStock): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = stock.currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "검색 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: "-"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, stock.currency)),
                    DetailMetric("상태", searchStatus(stock), if (stock.inPortfolio || stock.inSmallCap) DetailTone.Primary else DetailTone.Neutral)
                )
            )
        ),
        signals = listOfNotNull(
            if (stock.inPortfolio) DetailSignal("분석 상위군 포함", "현재 모델 분석 상위군에 포함된 종목입니다.", DetailTone.Primary) else null,
            if (stock.inSmallCap) DetailSignal("스몰캡 후보", "스몰캡 리스트에 포함된 후보입니다.", DetailTone.Positive) else null
        ).ifEmpty {
            listOf(DetailSignal("전체 유니버스 종목", "아직 분석 상위군이나 스몰캡 후보는 아니지만, 차트와 기업 정보를 확인할 수 있습니다.", DetailTone.Neutral))
        },
        factors = emptyList()
    )
}
