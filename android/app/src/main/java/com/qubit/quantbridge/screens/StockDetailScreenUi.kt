package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StockDetailScreenContent(
    app: QuantAppState,
    request: DetailRequest,
    detail: StockDetail?,
    loading: Boolean,
    error: String?,
    period: ChartPeriod,
    availablePeriods: Set<ChartPeriod>,
    onPeriodChange: (ChartPeriod) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (DetailRequest) -> Unit,
    comparisonViewModel: ComparisonViewModel
) {
    val scope = rememberCoroutineScope()
    var glossaryInfo by remember { mutableStateOf<DiagnosticInfo?>(null) }
    var editingWatchItem by remember(request.ticker) { mutableStateOf<WatchlistItem?>(null) }
    var editingDecision by remember(request.ticker) { mutableStateOf(false) }
    var showComparePicker by remember(request.ticker) { mutableStateOf(false) }
    var resolvingHoldingKey by remember(request.ticker) { mutableStateOf<String?>(null) }
    val isEtfDetail = request.isEtfDetail()
    val displayRequest = remember(request, detail) { request.reconciledWithDetail(detail) }
    var selectedTab by remember(request.ticker, request.preferredTab, isEtfDetail) {
        mutableStateOf(if (isEtfDetail) DetailContentTab.Chart else preferredDetailTab(request.preferredTab))
    }
    val availableTabs = remember(isEtfDetail) {
        if (isEtfDetail) listOf(DetailContentTab.Chart, DetailContentTab.Holdings, DetailContentTab.Overview, DetailContentTab.Data)
        else listOf(DetailContentTab.Overview, DetailContentTab.Chart, DetailContentTab.Financial, DetailContentTab.Data)
    }
    LaunchedEffect(request.ticker, isEtfDetail, selectedTab) {
        if (selectedTab !in availableTabs) {
            selectedTab = if (isEtfDetail) DetailContentTab.Chart else DetailContentTab.Overview
        }
    }
    val comparePickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val investmentDecisionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val openGlossary: (String) -> Unit = { key ->
        glossaryInfo(key)?.let { glossaryInfo = it }
    }
    val watchedItem = app.watchlistItem(request.ticker)
    val decisionRecord = app.investmentDecision(request.ticker)
    val detailWatchItem = remember(request) {
        val isEtfWatch = request.isEtfDetail()
        watchItem(
            ticker = request.ticker,
            name = request.name,
            market = request.market,
            currency = request.currency,
            note = if (isEtfWatch) "ETF" else "상세"
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TickerAvatar(request.ticker, request.market)
                        Column(Modifier.weight(1f)) {
                            Text(request.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(request.ticker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {}
            )
        },
        bottomBar = {}
    ) { padding ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
            if (loading) {
                StockDetailSkeleton(request)
            } else if (detail == null && error != null) {
                DetailErrorState(message = error, onRetry = onRetry)
            } else {
                val pricePoints = detail?.prices.orEmpty()
                val comparisonItem = request.toComparisonItem(detail)
                val comparisonSelected = comparisonViewModel.contains(comparisonItem)
                val comparisonCount = comparisonViewModel.items.size
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        DetailTopDecisionCard(
                            request = displayRequest,
                            detail = detail,
                            error = error,
                            watched = watchedItem != null,
                            comparisonSelected = comparisonSelected,
                            onWatch = {
                                scope.launchSafely {
                                    app.toggleWatch(watchedItem ?: detailWatchItem)
                                }
                            },
                            onMemo = {
                                if (watchedItem == null) {
                                    scope.launchSafely { app.toggleWatch(detailWatchItem) }
                                }
                                editingWatchItem = watchedItem ?: detailWatchItem
                            },
                            onCompare = {
                                showComparePicker = true
                            }
                        )
                    }
                    item {
                        InvestmentDecisionCard(
                            request = displayRequest,
                            detail = detail,
                            profile = app.investmentProfile,
                            record = decisionRecord,
                            onEdit = { editingDecision = true }
                        )
                    }
                    item {
                        DetailTabSelector(selected = selectedTab, tabs = availableTabs, onSelect = { selectedTab = it })
                    }

                    when (selectedTab) {
                        DetailContentTab.Overview -> {
                            item {
                                DetailSummaryCard(
                                    info = detail?.info,
                                    updatedAt = detail?.updatedAt,
                                    currency = displayRequest.currency,
                                    isEtf = isEtfDetail,
                                    onTermClick = openGlossary
                                )
                            }
                            item {
                                GlossaryCard(
                                    keys = detailGlossaryKeys(displayRequest, detail),
                                    onTermClick = openGlossary
                                )
                            }
                            item {
                                DetailComparisonGuardCard(
                                    request = displayRequest,
                                    detail = detail,
                                    comparisonCount = comparisonCount,
                                    comparisonSelected = comparisonSelected,
                                    onCompare = { showComparePicker = true }
                                )
                            }
                            item { DetailDecisionBriefCard(displayRequest, detail) }
                            item { DetailActionPlanCard(displayRequest, detail) }
                            item {
                                DetailMistakeCoachCard(
                                    profile = app.investmentProfile,
                                    request = displayRequest,
                                    detail = detail,
                                    watchItem = watchedItem,
                                    comparisonSelected = comparisonSelected
                                )
                            }
                            item { InvestmentProfileFitCard(app.investmentProfile, displayRequest, detail, watchedItem) }
                            if (!isEtfDetail) {
                                item { EarningsEventPlanCard(displayRequest) }
                            }
                            item { ScoreRationaleCard(displayRequest) }
                            item { DetailDataGapCard(displayRequest, detail?.info) }
                            detail?.info?.let { info ->
                                if (info.currentPrice != null && info.week52Low != null && info.week52High != null) {
                                    item { RangeCard(info, displayRequest.currency) }
                                }
                                if (!isEtfDetail && hasCompanyProfile(info)) {
                                    item { CompanyProfileCard(info) }
                                }
                            }
                            displayRequest.sections.forEach { section ->
                                item { MetricSection(section, openGlossary) }
                            }
                            if (displayRequest.signals.isNotEmpty()) {
                                item { SignalCards(displayRequest.signals) }
                            }
                            if (displayRequest.factors.isNotEmpty()) {
                                item { DetailFactorCoverageCard(displayRequest) }
                                item { FactorRadarCard(displayRequest.factors) }
                            }
                        }
                        DetailContentTab.Chart -> {
                            item {
                                PriceChart(
                                    points = pricePoints,
                                    currency = displayRequest.currency,
                                    period = period,
                                    availablePeriods = availablePeriods,
                                    onPeriodChange = onPeriodChange
                                )
                            }
                            if (pricePoints.isNotEmpty()) {
                                item { ReturnStatsCard(pricePoints, displayRequest.currency, openGlossary) }
                            }
                        }
                        DetailContentTab.Holdings -> {
                            item {
                                DetailHoldingsCard(
                                    holdings = displayRequest.holdings,
                                    resolvingHoldingKey = resolvingHoldingKey,
                                    onHoldingClick = { holding ->
                                        scope.launchSafely {
                                            resolvingHoldingKey = holding.ticker.ifBlank { holding.name }
                                            val resolved = app.resolveHoldingCandidate(holding)
                                            onOpenDetail(resolved?.let(::searchDetail) ?: holdingDetail(holding))
                                            resolvingHoldingKey = null
                                        }
                                    }
                                )
                            }
                        }
                        DetailContentTab.Financial -> {
                            if (!isEtfDetail) {
                                val info = detail?.info
                                if (info != null && hasMarketInfo(info)) {
                                    item { MarketInfoCard(info, displayRequest.currency, openGlossary) }
                                }
                                if (info != null && hasFinancialSnapshot(info)) {
                                    item { FinancialSnapshotCard(info, displayRequest.currency, openGlossary) }
                                }
                                item { DetailDataGapCard(displayRequest, info) }
                                if (info == null || (!hasMarketInfo(info) && !hasFinancialSnapshot(info))) {
                                    item { EmptyCard("재무 데이터 없음", "PER, PBR, 매출, 현금흐름 데이터가 도착하면 이 구역에 표시됩니다.") }
                                }
                            }
                        }
                        DetailContentTab.Data -> {
                            if (detail != null) {
                                item { DataTrustCard(source = detail.source, updatedAt = detail.updatedAt) }
                            } else {
                                item { EmptyCard("데이터 신뢰도 없음", "상세 응답을 불러오면 출처와 업데이트 시각을 표시합니다.") }
                            }
                            detail?.info?.description?.takeIf { it.isNotBlank() }?.let { description ->
                                item {
                                    CardBlock {
                                        Text("기업 정보", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            detail?.error?.takeIf { it.isNotBlank() }?.let { err ->
                                item { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    glossaryInfo?.let { info ->
        DiagnosticInfoDialog(
            info = info,
            onDismiss = { glossaryInfo = null }
        )
    }

    editingWatchItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { editingWatchItem = null }) {
            WatchMetadataSheet(
                item = app.watchlistItem(item.ticker) ?: item,
                onSave = { tags, memo, alerts ->
                    scope.launchSafely {
                        if (!app.isWatched(item.ticker)) {
                            app.toggleWatch(item)
                        }
                        app.updateWatchMetadata(item.ticker, tags, memo, alerts)
                    }
                    editingWatchItem = null
                },
                onDismiss = { editingWatchItem = null }
            )
        }
    }

    if (editingDecision) {
        ModalBottomSheet(
            onDismissRequest = { editingDecision = false },
            sheetState = investmentDecisionSheetState
        ) {
            InvestmentDecisionSheet(
                request = displayRequest,
                detail = detail,
                profile = app.investmentProfile,
                record = decisionRecord,
                onSave = { record ->
                    app.saveInvestmentDecision(record)
                    editingDecision = false
                },
                onDelete = decisionRecord?.let {
                    {
                        app.deleteInvestmentDecision(request.ticker)
                        editingDecision = false
                    }
                },
                onDismiss = { editingDecision = false }
            )
        }
    }

    if (showComparePicker) {
        ModalBottomSheet(
            onDismissRequest = { showComparePicker = false },
            sheetState = comparePickerSheetState
        ) {
            ComparisonTargetPickerSheet(
                app = app,
                anchor = request.toComparisonItem(detail),
                onDismiss = { showComparePicker = false },
                onCompare = {
                    showComparePicker = false
                    comparisonViewModel.openSheet()
                },
                comparisonViewModel = comparisonViewModel
            )
        }
    }
}
