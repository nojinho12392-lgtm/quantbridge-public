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

internal const val SEARCH_DEBOUNCE_MS = 400L
internal const val SEARCH_MIN_QUERY_LENGTH = 2

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
        if (showAdvancedModes) listOf("기업", "스코어", "전략", "진단") else listOf("기업", "추천", "품질")
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
    fun canonicalMode(label: String): String = when (label) {
        scoreModeLabel -> "스코어"
        "품질" -> "진단"
        else -> label
    }
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
        else -> listOf(
            opsViewModel.researchQuality != null,
            opsViewModel.mlBlendReport != null,
            opsViewModel.policyAdjustedRankings.isNotEmpty(),
            opsViewModel.opsHealth != null
        ).count { it }
    }
    val activeTotalCount = when (activeMode) {
        "기업" -> searchViewModel.searchResults.size
        "스코어" -> searchViewModel.usScored.size + searchViewModel.krScored.size
        "전략" -> strategyViewModel.riskHoldings.size +
            strategyViewModel.riskSectors.size +
            strategyViewModel.rebalanceOrders.size +
            strategyViewModel.shadowItems.size
        else -> 4
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
            val policyRankings = opsViewModel.policyAdjustedRankings
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
                    title = "정책 섀도 랭킹",
                    value = policyRankingHeaderValue(policyRankings),
                    subtitle = policyRankingHeaderSubtitle(policyRankings),
                    trailing = policyRankings.sumOf { it.items.size }.takeIf { it > 0 }?.toString() ?: "-",
                    onClick = { diagnosticInfo = policyAdjustedRankingDiagnosticInfo(policyRankings) }
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
            item { SectionTitle("정책 조정 섀도 랭킹", "${policyRankings.size}") }
            if (policyRankings.isEmpty()) {
                item { EmptyCard("정책 섀도 랭킹 없음", "정책 조정 랭킹 데이터가 아직 없습니다.") }
            } else {
                items(policyRankings, key = { it.market }) { ranking ->
                    PolicyAdjustedRankingBlock(ranking)
                }
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
