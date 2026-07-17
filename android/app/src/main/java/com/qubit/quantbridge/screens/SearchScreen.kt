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

        when (activeMode) {
            "기업" -> searchCompanyModeItems(
                companyRows = companyRows,
                groupedCompanyRows = groupedCompanyRows,
                companyFilter = companyFilter,
                isLoading = searchViewModel.isExploreLoading("기업"),
                normalizedQuery = normalizedQuery,
                query = query,
                app = app,
                scope = scope,
                comparisonViewModel = comparisonViewModel,
                onRecordRecentSearch = { recordRecentSearch(it) }
            )
            "스코어" -> searchScoreModeItems(
                scoredRows = scoredRows,
                showAdvancedModes = showAdvancedModes,
                isLoading = searchViewModel.isExploreLoading("스코어"),
                normalizedQuery = normalizedQuery,
                app = app,
                scope = scope,
                comparisonViewModel = comparisonViewModel
            )
            "전략" -> searchStrategyModeItems(
                strategyViewModel = strategyViewModel,
                riskHoldingRows = riskHoldingRows,
                riskSectorRows = riskSectorRows,
                rebalanceRows = rebalanceRows,
                shadowSummaryRows = shadowSummaryRows,
                shadowItemRows = shadowItemRows
            )
            else -> searchDiagnosticModeItems(
                opsViewModel = opsViewModel,
                onDiagnosticInfo = { diagnosticInfo = it }
            )
        }
    }

    diagnosticInfo?.let { info ->
        DiagnosticInfoDialog(
            info = info,
            onDismiss = { diagnosticInfo = null }
        )
    }
}
