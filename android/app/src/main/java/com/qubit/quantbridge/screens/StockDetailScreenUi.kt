package com.qubit.quantbridge

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantMuted
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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

@Composable
internal fun DetailTopDecisionCard(
    request: DetailRequest,
    detail: StockDetail?,
    error: String?,
    watched: Boolean,
    comparisonSelected: Boolean,
    onWatch: () -> Unit,
    onMemo: () -> Unit,
    onCompare: () -> Unit
) {
    val conclusion = detailConclusion(request, detail)
    val info = detail?.info
    val topMetrics = request.sections
        .flatMap { it.metrics }
        .filter { it.value.isNotBlank() && it.value != "-" }
        .take(2)
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TickerAvatar(request.ticker, request.market)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    request.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${request.ticker} · ${if (request.currency == "KRW") "한국" else "미국"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            DetailPriceMini(request, info, detail?.source, detail?.updatedAt, error)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(toneColor(conclusion.tone).copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = detailConclusionIcon(conclusion),
                    contentDescription = null,
                    tint = toneColor(conclusion.tone),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conclusion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = toneColor(conclusion.tone))
                Text(conclusion.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(conclusion.badge, conclusion.tone)
        }
        if (topMetrics.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                topMetrics.forEach { metric ->
                    CompactMetricTile(
                        label = metric.label,
                        value = metric.value,
                        modifier = Modifier.weight(1f),
                        color = toneColor(metric.tone)
                    )
                }
                if (topMetrics.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        val watchActionLabel = if (watched) "${request.name} 관심 종목 제거" else "${request.name} 관심 종목 추가"
        val compareActionLabel = if (comparisonSelected) "${request.name} 비교 목록에 추가됨" else "${request.name} 비교 목록에 추가"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onWatch,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = watchActionLabel
                        onClick(label = watchActionLabel) {
                            onWatch()
                            true
                        }
                    },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(if (watched) "관심중" else "관심")
            }
            QuantActionButton(
                label = "비교",
                onClick = onCompare,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = compareActionLabel
                        onClick(label = compareActionLabel) {
                            onCompare()
                            true
                        }
                    },
                complete = comparisonSelected
            ) {
                LucideIconView(
                    icon = if (comparisonSelected) LucideIcon.Check else LucideIcon.GitCompare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("비교", maxLines = 1)
            }
            QuantIconActionButton(
                icon = LucideIcon.SlidersHorizontal,
                contentDescription = "${request.name} 관심 설정",
                onClick = onMemo,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DataFreshnessBadge(source = detail?.source, updatedAt = detail?.updatedAt, compact = true)
    }
}

internal val DetailPriceMetricLabels = setOf("현재가", "최근가", "가격", "Price", "Last Price")
internal val DetailTodayMetricLabels = setOf("오늘", "전장", "당일 흐름", "하루 변동률", "일간", "일일", "1D", "Today", "Daily")

internal fun detailMetricLabelMatches(label: String, candidates: Set<String>): Boolean {
    val clean = label.trim().lowercase(Locale.US)
    return candidates.any { candidate ->
        val target = candidate.trim().lowercase(Locale.US)
        clean == target || clean.contains(target)
    }
}

internal fun DetailRequest.reconciledWithDetail(detail: StockDetail?): DetailRequest {
    val info = detail?.info ?: return this
    val current = info.currentPrice?.takeIf { it.isFinite() } ?: return this
    val changePct = detailDailyChangePct(info, current)
    val dailyChangeLabel = info.dailyChangeHorizon?.trim()?.takeIf { it.isNotEmpty() } ?: "오늘"
    val priceMetric = DetailMetric("현재가", fmtPx(current, currency), DetailTone.Primary)
    val todayMetric = changePct?.let { DetailMetric(dailyChangeLabel, pct(it), returnTone(it)) }
    var hasPriceMetric = false
    var hasTodayMetric = false

    val reconciledSections = sections.map { section ->
        section.copy(
            metrics = section.metrics.map { metric ->
                when {
                    detailMetricLabelMatches(metric.label, DetailPriceMetricLabels) -> {
                        hasPriceMetric = true
                        priceMetric.copy(label = metric.label)
                    }
                    detailMetricLabelMatches(metric.label, DetailTodayMetricLabels) -> {
                        hasTodayMetric = true
                        if (todayMetric != null) {
                            val label = if (todayMetric.label == "오늘") metric.label else todayMetric.label
                            todayMetric.copy(label = label)
                        } else {
                            metric.copy(value = "-", tone = DetailTone.Neutral)
                        }
                    }
                    else -> metric
                }
            }
        )
    }.toMutableList()

    val leadingMetrics = buildList {
        if (!hasPriceMetric) add(priceMetric)
        if (!hasTodayMetric && todayMetric != null) add(todayMetric)
    }
    if (leadingMetrics.isNotEmpty()) {
        if (reconciledSections.isEmpty()) {
            reconciledSections += DetailSection("시세", leadingMetrics)
        } else {
            val first = reconciledSections.first()
            reconciledSections[0] = first.copy(metrics = leadingMetrics + first.metrics)
        }
    }

    val reconciledSignals = signals.map { signal ->
        if (signal.title == "확인할 숫자") {
            if (todayMetric != null) {
                signal.copy(
                    detail = "상세 시세 기준 당일 흐름 ${todayMetric.value}, 1개월 흐름은 차트와 같은 테마 기업으로 비교하세요.",
                    tone = todayMetric.tone
                )
            } else {
                signal.copy(detail = "상세 시세 기준 당일 흐름과 1개월 흐름을 같은 테마 기업과 비교하세요.")
            }
        } else {
            signal
        }
    }

    return copy(sections = reconciledSections, signals = reconciledSignals)
}

internal fun detailDailyChangePct(info: StockInfo, current: Double): Double? {
    info.dailyChangePct?.takeIf { it.isFinite() }?.let { return it }
    return info.prevClose
        ?.takeIf { it.isFinite() && it != 0.0 }
        ?.let { current / it - 1.0 }
}

internal fun detailDailyChangeAmount(info: StockInfo, current: Double, changePct: Double?): Double? {
    info.prevClose?.takeIf { it.isFinite() }?.let { return current - it }
    val safeChange = changePct?.takeIf { it.isFinite() && it > -1.0 } ?: return null
    val previous = current / (1.0 + safeChange)
    return current - previous
}

@Composable
internal fun DetailHoldingsCard(
    holdings: List<DetailHolding>,
    resolvingHoldingKey: String?,
    onHoldingClick: (DetailHolding) -> Unit
) {
    val visible = holdings.take(10)
    val totalWeight = visible.sumOf { max(it.weight, 0.0) }
    val otherWeight = max(0.0, 1.0 - totalWeight)
    val showOther = otherWeight > 0.0001

    CardBlock {
        Text("보유 비중 Top10", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (visible.isEmpty()) {
            Text(
                "구성 종목 데이터가 도착하면 원형 그래프와 상위 보유비중을 표시합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                if (showOther) {
                    "상위 ${visible.size}개 합산 ${pct(totalWeight, signed = false)} · 기타 ${pct(otherWeight, signed = false)}"
                } else {
                    "상위 ${visible.size}개 기준 · 합산 ${pct(totalWeight, signed = false)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DetailHoldingDonutChart(
                holdings = visible,
                otherWeight = otherWeight,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                visible.forEachIndexed { index, holding ->
                    DetailHoldingLegendRow(
                        holding = holding,
                        color = DetailHoldingPalette[index % DetailHoldingPalette.size],
                        loading = resolvingHoldingKey == holding.ticker || resolvingHoldingKey == holding.name,
                        onClick = { onHoldingClick(holding) }
                    )
                }
                if (showOther) {
                    DetailHoldingLegendRow("기타", otherWeight, DetailHoldingOtherColor)
                }
            }
        }
    }
}

@Composable
internal fun DetailHoldingDonutChart(
    holdings: List<DetailHolding>,
    otherWeight: Double,
    modifier: Modifier = Modifier
) {
    val totalWeight = holdings.sumOf { max(it.weight, 0.0) } + max(otherWeight, 0.0)
    Canvas(modifier = modifier.size(168.dp)) {
        if (totalWeight <= 0.0) return@Canvas
        val strokeWidth = min(size.width, size.height) * 0.20f
        val diameter = min(size.width, size.height) - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        var startAngle = -90f
        holdings.forEachIndexed { index, holding ->
            val sweep = (max(holding.weight, 0.0) / totalWeight * 360.0).toFloat()
            drawArc(
                color = DetailHoldingPalette[index % DetailHoldingPalette.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweep
        }
        val otherSweep = (max(otherWeight, 0.0) / totalWeight * 360.0).toFloat()
        if (otherSweep > 0f) {
            drawArc(
                color = DetailHoldingOtherColor,
                startAngle = startAngle,
                sweepAngle = otherSweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
        }
    }
}

@Composable
internal fun DetailHoldingLegendRow(
    holding: DetailHolding,
    color: Color,
    loading: Boolean,
    onClick: () -> Unit
) {
    DetailHoldingLegendRow(
        title = holding.name,
        weight = holding.weight,
        color = color,
        loading = loading,
        clickable = true,
        onClick = onClick
    )
}

@Composable
internal fun DetailHoldingLegendRow(
    title: String,
    weight: Double,
    color: Color,
    loading: Boolean = false,
    clickable: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.quantClickable(role = QuantPressRole.Row, onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            pct(weight, signed = false),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

internal val DetailHoldingPalette = listOf(
    Color(0xFF2F80ED),
    Color(0xFF23A6A6),
    Color(0xFF45CC96),
    Color(0xFFFFD15A),
    Color(0xFFFF980A),
    Color(0xFFE9540A),
    Color(0xFF4CB9D6),
    Color(0xFF1469AD),
    Color(0xFFE54868),
    Color(0xFFAD14D1)
)

internal val DetailHoldingOtherColor = Color(0xFFD2D8E0)

internal fun holdingMarket(ticker: String): String? {
    return when {
        ticker.endsWith(".KS", ignoreCase = true) || ticker.endsWith(".KQ", ignoreCase = true) -> "KR"
        ticker.all { it.isLetter() } -> "US"
        else -> null
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun DetailPriceMini(
    request: DetailRequest,
    info: StockInfo?,
    source: String?,
    updatedAt: String?,
    error: String?
) {
    val px = info?.currentPrice
    if (px == null) {
        Text(
            if (error != null) "시세 지연" else "시세 대기",
            modifier = Modifier.width(86.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        return
    }
    val changePct = detailDailyChangePct(info, px)
    val change = detailDailyChangeAmount(info, px, changePct)
    Column(
        modifier = Modifier.width(112.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            fmtPx(px, request.currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (change != null && changePct != null) {
            Text(
                "${signedPx(change, request.currency)} ${pct(changePct)}",
                color = marketMoveColor(change),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
    }
}

@Composable
internal fun DetailConclusionActionBar(
    request: DetailRequest,
    detail: StockDetail?,
    watched: Boolean,
    comparisonSelected: Boolean,
    onWatch: () -> Unit,
    onMemo: () -> Unit,
    onCompare: () -> Unit
) {
    val conclusion = detailConclusion(request, detail)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = toneColor(conclusion.tone).copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, toneColor(conclusion.tone).copy(alpha = 0.22f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(toneColor(conclusion.tone).copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = detailConclusionIcon(conclusion),
                        contentDescription = null,
                        tint = toneColor(conclusion.tone),
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(conclusion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = toneColor(conclusion.tone))
                    Text(conclusion.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(conclusion.badge, conclusion.tone)
            }
            val watchActionLabel = if (watched) "${request.name} 관심 종목 제거" else "${request.name} 관심 종목 추가"
            val memoActionLabel = "${request.name} 관심 설정"
            val compareActionLabel = if (comparisonSelected) "${request.name} 비교 목록에 추가됨" else "${request.name} 비교 목록에 추가"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onWatch,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = watchActionLabel
                            onClick(label = watchActionLabel) {
                                onWatch()
                                true
                            }
                        },
                    colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                )
            ) {
                    Icon(
                        imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (watched) "관심중" else "관심")
                }
                OutlinedButton(
                    onClick = onMemo,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = memoActionLabel
                            onClick(label = memoActionLabel) {
                                onMemo()
                                true
                            }
                        }
                ) {
                    LucideIconView(icon = LucideIcon.SlidersHorizontal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("설정")
                }
                OutlinedButton(
                    onClick = onCompare,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = compareActionLabel
                            onClick(label = compareActionLabel) {
                                onCompare()
                                true
                            }
                        }
                ) {
                    LucideIconView(icon = if (comparisonSelected) LucideIcon.Check else LucideIcon.GitCompare, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("비교")
                }
            }
        }
    }
}

internal data class DetailConclusion(
    val title: String,
    val detail: String,
    val badge: String,
    val tone: DetailTone
)

internal fun detailConclusionIcon(conclusion: DetailConclusion): LucideIcon {
    return when {
        conclusion.badge == "ETF" -> LucideIcon.PieChart
        conclusion.tone == DetailTone.Positive -> LucideIcon.TrendingUp
        conclusion.tone == DetailTone.Negative -> LucideIcon.TriangleAlert
        conclusion.tone == DetailTone.Warning -> LucideIcon.TriangleAlert
        conclusion.tone == DetailTone.Primary -> LucideIcon.Target
        else -> LucideIcon.Activity
    }
}

internal fun detailConclusion(request: DetailRequest, detail: StockDetail?): DetailConclusion {
    val info = detail?.info
    return when {
        detail?.error?.isNotBlank() == true -> DetailConclusion(
            title = "데이터 확인 필요",
            detail = "상세 데이터 일부가 비어 있습니다. 결론을 확정하기 전에 데이터 탭을 확인하세요.",
            badge = "주의",
            tone = DetailTone.Warning
        )
        request.isEtfDetail() -> DetailConclusion(
            title = if (request.signals.any { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }) "ETF 조건 확인" else "ETF 추적 후보",
            detail = "구성 비중, 총보수, 가격 위치를 같은 지수 또는 같은 테마 ETF와 비교하세요.",
            badge = "ETF",
            tone = if (request.signals.any { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }) DetailTone.Warning else DetailTone.Primary
        )
        info?.recommendation?.contains("buy", ignoreCase = true) == true -> DetailConclusion(
            title = "긍정 신호 우세",
            detail = "애널리스트 의견과 기본 지표가 우호적입니다. 비교군 대비 성장성과 수익성을 확인하세요.",
            badge = "후보",
            tone = DetailTone.Positive
        )
        request.signals.any { it.tone == DetailTone.Negative } -> DetailConclusion(
            title = "리스크 먼저 점검",
            detail = "부정 신호가 포함되어 있습니다. 포지션을 늘리기 전 리스크 사유를 먼저 확인하세요.",
            badge = "리스크",
            tone = DetailTone.Negative
        )
        request.signals.any { it.tone == DetailTone.Warning } -> DetailConclusion(
            title = "조건부 관찰",
            detail = "좋은 신호와 확인할 신호가 섞여 있습니다. 관심 조건을 정해두면 재방문이 쉬워집니다.",
            badge = "관찰",
            tone = DetailTone.Warning
        )
        else -> DetailConclusion(
            title = "비교 후 판단",
            detail = "단일 화면만으로 결론을 내리기보다 2~4개 후보를 비교해 우선순위를 정하세요.",
            badge = "중립",
            tone = DetailTone.Primary
        )
    }
}

@Composable
internal fun DetailHeroCard(request: DetailRequest, detail: StockDetail?, error: String?) {
    val loaded = detail?.prices?.isNotEmpty() == true || detail?.info != null
    val statusText = when {
        error != null -> "일부 실패"
        loaded -> "상세 로드됨"
        else -> "기본 지표"
    }
    val statusTone = when {
        error != null -> DetailTone.Warning
        loaded -> DetailTone.Positive
        else -> DetailTone.Neutral
    }
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TickerAvatar(request.ticker, request.market)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    request.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${request.ticker} · ${request.currency}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            StatusPill(statusText, statusTone)
        }
        detail?.updatedAt?.takeIf { it.isNotBlank() }?.let {
            Text("업데이트 ${formattedUpdateTimestamp(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DetailSummaryCard(
    info: StockInfo?,
    updatedAt: String?,
    currency: String,
    isEtf: Boolean = false,
    onTermClick: (String) -> Unit = {}
) {
    val current = info?.currentPrice
    val prev = info?.prevClose
    val low = info?.week52Low
    val high = info?.week52High
    val changePct = if (current != null && prev != null && prev != 0.0) current / prev - 1.0 else null
    val changeDetail = if (current != null && prev != null) signedPx(current - prev, currency) else "전일 종가 없음"
    val rangePosition = if (current != null && low != null && high != null && high > low) {
        ((current - low) / (high - low)).coerceIn(0.0, 1.0)
    } else {
        null
    }
    val valuation = when {
        info?.forwardPe != null -> Triple("밸류에이션", "%.1fx".format(info.forwardPe), "Forward PER")
        info?.peRatio != null -> Triple("밸류에이션", "%.1fx".format(info.peRatio), "Trailing PER")
        info?.priceToBook != null -> Triple("밸류에이션", "%.1fx".format(info.priceToBook), "PBR")
        else -> Triple("밸류에이션", "-", stockValuationUnavailableReason(info))
    }
    val metrics = buildList {
        add(Triple("당일 흐름", changePct?.let { pct(it) } ?: "-", changeDetail))
        add(Triple("52주 위치", rangePosition?.let { "%.0f%%".format(it * 100) } ?: "-", rangePosition?.let { if (it >= 0.75) "고점권" else if (it <= 0.25) "저점권" else "중간권" } ?: "범위 데이터 없음"))
        if (!isEtf) add(valuation)
        add(Triple("업데이트", formattedUpdateTimestamp(updatedAt), "상세 데이터 기준"))
    }
    CardBlock {
        Text("핵심 요약", fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { metric ->
                        DetailSummaryTile(
                            label = metric.first,
                            value = metric.second,
                            detail = metric.third,
                            modifier = Modifier.weight(1f),
                            valueColor = when (metric.first) {
                                "당일 흐름" -> changePct?.let { marketMoveColor(it) }
                                else -> null
                            },
                            tone = when (metric.first) {
                                "당일 흐름" -> changePct?.let { if (it >= 0.0) DetailTone.Positive else DetailTone.Negative } ?: DetailTone.Neutral
                                "52주 위치" -> rangePosition?.let { if (it >= 0.75) DetailTone.Warning else DetailTone.Neutral } ?: DetailTone.Neutral
                                else -> DetailTone.Neutral
                            },
                            onTermClick = onTermClick
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun DetailSummaryTile(
    label: String,
    value: String,
    detail: String,
    tone: DetailTone,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    onTermClick: (String) -> Unit = {}
) {
    val termKey = glossaryKeyForLabel(detail) ?: glossaryKeyForLabel(label)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TermLabel(label = label, termKey = termKey, onTermClick = onTermClick)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = valueColor ?: toneColor(tone), maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun TermLabel(label: String, termKey: String?, onTermClick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (termKey != null) {
            LucideIconView(
                icon = LucideIcon.Lightbulb,
                contentDescription = "$label 설명",
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { onTermClick(termKey) },
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GlossaryCard(keys: List<String>, onTermClick: (String) -> Unit) {
    if (keys.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Text(
                "용어 설명",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "눌러서 자세히",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            keys.mapNotNull { glossaryInfo(it) }.forEach { info ->
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .height(38.dp)
                        .clickable { onTermClick(info.title) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            info.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DataTrustCard(source: String?, updatedAt: String?) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "데이터 신뢰도",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
        }
        InfoLine("출처", dataSourceLabel(source))
        InfoLine("업데이트", formattedUpdateTimestamp(updatedAt))
    }
}

@Composable
internal fun StatusPill(text: String, tone: DetailTone) {
    val color = toneColor(tone)
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun DataFreshnessBadge(level: DataFreshnessLevel, compact: Boolean = false) {
    val color = when (level) {
        DataFreshnessLevel.Fresh -> QuantGreen
        DataFreshnessLevel.Delayed -> QuantWarning
        DataFreshnessLevel.Stale -> QuantDanger
        DataFreshnessLevel.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(
                if (compact) level.label else "${level.label} · ${level.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun DetailErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        CardBlock {
            Text("상세 데이터를 불러오지 못했습니다", fontWeight = FontWeight.Bold)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("다시 시도")
            }
        }
    }
}

@Composable
internal fun StockDetailSkeleton(request: DetailRequest) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CardBlock {
                Text(request.name, fontWeight = FontWeight.Bold)
                SkeletonLine(width = 150.dp, height = 30.dp)
                SkeletonLine(width = 220.dp, height = 16.dp)
            }
        }
        item {
            CardBlock {
                Text("차트", fontWeight = FontWeight.Bold)
                SkeletonLine(width = 120.dp, height = 18.dp)
                SkeletonLine(height = 260.dp)
                SkeletonLine(width = 260.dp, height = 34.dp)
            }
        }
        item {
            CardBlock {
                Text("핵심 지표", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SkeletonLine(height = 72.dp, modifier = Modifier.weight(1f))
                    SkeletonLine(height = 72.dp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun SkeletonLine(
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null
) {
    val sizedModifier = if (width == null) modifier.fillMaxWidth() else modifier.width(width)
    Box(
        sizedModifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
    )
}

@Composable
internal fun PriceHeaderCard(request: DetailRequest, info: StockInfo?) {
    CardBlock {
        Text("현재가", fontWeight = FontWeight.Bold)
        if (info?.currentPrice == null) {
            Text("가격 데이터 없음", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(
                "현재 상세 응답에 시세 정보가 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            val px = info.currentPrice
            val change = info.prevClose?.let { px - it }
            val changePct = info.prevClose?.takeIf { it != 0.0 }?.let { (px / it) - 1.0 }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedPriceText(
                    text = fmtPx(px, request.currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (change != null && changePct != null) {
                    Text(
                        "${signedPx(change, request.currency)} (${pct(changePct)})",
                        color = marketMoveColor(change),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            info.sector?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun RangeCard(info: StockInfo, currency: String) {
    val low = info.week52Low ?: return
    val high = info.week52High ?: return
    val current = info.currentPrice ?: return
    val position = if (high > low) ((current - low) / (high - low)).coerceIn(0.0, 1.0) else 0.5
    CardBlock {
        Text("52주 범위", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2f
            drawLine(
                color = QuantLine,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = 8f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = QuantNegative,
                radius = 9f,
                center = androidx.compose.ui.geometry.Offset((size.width * position).toFloat(), y)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtPx(low, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtPx(current, currency), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(fmtPx(high, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CompanyProfileCard(info: StockInfo) {
    MetricSection(
        DetailSection(
            "기업 프로필",
            listOfNotNull(
                info.industry?.takeIf { it.isNotBlank() }?.let { DetailMetric("산업", it) },
                profileLocation(info)?.let { DetailMetric("지역", it) },
                info.employees?.let { DetailMetric("직원 수", "%,d".format(it)) },
                info.website?.takeIf { it.isNotBlank() }?.let { DetailMetric("웹사이트", it) }
            )
        )
    )
}

@Composable
internal fun MarketInfoCard(info: StockInfo, currency: String, onTermClick: (String) -> Unit = {}) {
    MetricSection(
        DetailSection(
            "시장 정보",
            listOfNotNull(
                DetailMetric("현재가", info.currentPrice?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("전일 종가", info.prevClose?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("52주 고가", info.week52High?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("52주 저가", info.week52Low?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("시가총액", cap(info.marketCap, currency)),
                info.peRatio?.let { DetailMetric("PER", "%.1f".format(it)) },
                info.forwardPe?.let { DetailMetric("Forward PER", "%.1f".format(it)) },
                info.priceToSales?.let { DetailMetric("P/S", "%.1f".format(it)) },
                info.priceToBook?.let { DetailMetric("P/B", "%.1f".format(it)) },
                info.beta?.let { DetailMetric("베타", "%.2f".format(it)) },
                info.targetMeanPrice?.let { DetailMetric("목표가 평균", fmtPx(it, currency), DetailTone.Primary) },
                normalizedRecommendation(info.recommendation)?.let { DetailMetric("컨센서스", it.replaceFirstChar { c -> c.titlecase(Locale.US) }) }
            )
        ),
        onTermClick
    )
}

@Composable
internal fun FinancialSnapshotCard(info: StockInfo, currency: String, onTermClick: (String) -> Unit = {}) {
    MetricSection(
        DetailSection(
            "재무 스냅샷",
            listOfNotNull(
                info.totalRevenue?.let { DetailMetric("매출", cap(it, currency)) },
                info.revenueGrowth?.let { DetailMetric("매출 성장", pct(it), returnTone(it)) },
                info.grossMargin?.let { DetailMetric("매출총이익률", pct(it, signed = false), ratioTone(it, 0.40, 0.20)) },
                info.operatingMargin?.let { DetailMetric("영업이익률", pct(it, signed = false), ratioTone(it, 0.15, 0.0)) },
                info.profitMargin?.let { DetailMetric("순이익률", pct(it, signed = false), ratioTone(it, 0.10, 0.0)) },
                info.ebitdaMargin?.let { DetailMetric("EBITDA 마진", pct(it, signed = false), ratioTone(it, 0.20, 0.05)) },
                info.ebitda?.let { DetailMetric("EBITDA", cap(it, currency)) },
                info.freeCashflow?.let { DetailMetric("FCF", cap(it, currency)) },
                info.totalDebt?.let { DetailMetric("총부채", cap(it, currency)) },
                info.debtToEquity?.let { DetailMetric("Debt/Equity", "%.1f".format(it), inverseTone(it, 100.0, 200.0)) },
                info.returnOnEquity?.let { DetailMetric("ROE", pct(it, signed = false), ratioTone(it, 0.15, 0.05)) }
            )
        ),
        onTermClick
    )
}

@Composable
internal fun ReturnStatsCard(points: List<PricePoint>, currency: String, onTermClick: (String) -> Unit = {}) {
    val metrics = remember(points, currency) { returnMetrics(points, currency) }
    if (metrics.isEmpty()) return
    MetricSection(DetailSection("기간 수익률 / 리스크", metrics), onTermClick)
}

@Composable
internal fun FactorRadarCard(factors: List<FactorScore>) {
    CardBlock {
        Text("팩터 점수", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(210.dp)) {
            val count = factors.size.coerceAtLeast(3)
            val radius = min(size.width, size.height) * 0.38f
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            for (step in 1..4) {
                val r = radius * step / 4f
                val grid = Path()
                for (i in 0 until count) {
                    val angle = -PI / 2.0 + 2.0 * PI * i / count
                    val x = centerX + (cos(angle) * r).toFloat()
                    val y = centerY + (sin(angle) * r).toFloat()
                    if (i == 0) grid.moveTo(x, y) else grid.lineTo(x, y)
                }
                grid.close()
                drawPath(grid, QuantLine, style = Stroke(width = 1.5f))
            }

            val shape = Path()
            factors.forEachIndexed { index, factor ->
                val r = radius * (factor.value.coerceIn(0.0, 100.0) / 100.0).toFloat()
                val angle = -PI / 2.0 + 2.0 * PI * index / factors.size
                val x = centerX + (cos(angle) * r).toFloat()
                val y = centerY + (sin(angle) * r).toFloat()
                if (index == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
            }
            shape.close()
            drawPath(shape, QuantBlue.copy(alpha = 0.20f))
            drawPath(shape, QuantBlue, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            factors.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { factor ->
                        Text(
                            "${factor.label} ${factor.value.toInt()}",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun SignalCards(signals: List<DetailSignal>) {
    CardBlock {
        Text("투자 근거", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        signals.forEach { signal ->
            Surface(
                color = toneColor(signal.tone).copy(alpha = 0.10f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(signal.title, color = toneColor(signal.tone), fontWeight = FontWeight.SemiBold)
                    Text(signal.detail, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun MetricSection(section: DetailSection, onTermClick: (String) -> Unit = {}) {
    if (section.metrics.isEmpty()) return
    CardBlock {
        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        section.metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { metric ->
                    MetricTile(metric, Modifier.weight(1f), onTermClick)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun MetricTile(metric: DetailMetric, modifier: Modifier = Modifier, onTermClick: (String) -> Unit = {}) {
    val accent = toneColor(metric.tone)
    val termKey = glossaryKeyForLabel(metric.label)
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TermLabel(label = metric.label, termKey = termKey, onTermClick = onTermClick)
            Text(metric.value, style = MaterialTheme.typography.titleSmall, color = toneColor(metric.tone), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PriceChart(
    points: List<PricePoint>,
    currency: String,
    period: ChartPeriod,
    availablePeriods: Set<ChartPeriod>,
    onPeriodChange: (ChartPeriod) -> Unit
) {
    var overlays by remember {
        mutableStateOf(setOf(ChartOverlay.Volume))
    }
    var chartMode by remember { mutableStateOf(ChartMode.Candle) }
    var showIndicatorSheet by remember { mutableStateOf(false) }
    val visible = remember(points, period) { chartVisiblePoints(points, period) }
    CardBlock(useBorder = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("차트", fontWeight = FontWeight.Bold)
            }
            if (visible.size >= 2) {
                val first = visible.first()
                val last = visible.last()
                val returnTone = if (last.close >= first.close) QuantPositive else QuantNegative
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtPx(last.close, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (first.close == 0.0) "-" else pct((last.close / first.close) - 1.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = returnTone,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (visible.size < 2) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("가격 데이터 없음", fontWeight = FontWeight.SemiBold)
                    Text(
                        "현재 기간에 표시할 가격 이력이 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            val ma5 = remember(visible) { movingAverage(visible, 5) }
            val ma20 = remember(visible) { movingAverage(visible, 20) }
            val ma60 = remember(visible) { movingAverage(visible, 60) }
            val bollinger = remember(visible) { bollingerBands(visible, 20) }
            val rsi = remember(visible) { rsiSeries(visible, 14) }
            val first = visible.first().close
            val last = visible.last().close
            val chartTone = if (last >= first) QuantPositive else QuantNegative
            PeriodModeSelector(
                period = period,
                availablePeriods = availablePeriods,
                onPeriodChange = onPeriodChange,
                mode = chartMode,
                tone = chartTone,
                onModeChange = { chartMode = it }
            )
            Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                val indicatorValues = buildList {
                    if (ChartOverlay.MA5 in overlays) addAll(ma5.filterNotNull())
                    if (ChartOverlay.MA20 in overlays) addAll(ma20.filterNotNull())
                    if (ChartOverlay.MA60 in overlays) addAll(ma60.filterNotNull())
                    if (ChartOverlay.Bollinger in overlays) {
                        bollinger.filterNotNull().forEach {
                            add(it.first)
                            add(it.second)
                        }
                    }
                }
                val minPrice = min(visible.minOf { it.low }, indicatorValues.minOrNull() ?: visible.minOf { it.low })
                val maxPrice = max(visible.maxOf { it.high }, indicatorValues.maxOrNull() ?: visible.maxOf { it.high })
                val rawRange = max(0.0001, maxPrice - minPrice)
                val pricePadding = rawRange * 0.03
                val chartMinPrice = minPrice - pricePadding
                val chartMaxPrice = maxPrice + pricePadding
                val range = max(0.0001, chartMaxPrice - chartMinPrice)
                val step = size.width / visible.lastIndex.coerceAtLeast(1).toFloat()
                val candleWidth = max(3f, step * 0.727f)
                val chartTopPad = 34f
                val chartBottomPad = 28f
                val chartHeight = max(1f, size.height - chartTopPad - chartBottomPad)
                fun y(price: Double): Float = chartTopPad + (1f - ((price - chartMinPrice) / range).toFloat()) * chartHeight
                fun chartX(index: Int): Float = if (visible.size <= 1) {
                    size.width / 2f
                } else {
                    val left = candleWidth / 2f
                    val right = size.width - candleWidth / 2f
                    left + (right - left) * index / visible.lastIndex.toFloat()
                }
                fun drawSeries(values: List<Double?>, color: Color, strokeWidth: Float = 3f) {
                    val path = Path()
                    var started = false
                    values.forEachIndexed { index, value ->
                        if (value == null) return@forEachIndexed
                        val x = chartX(index)
                        val yy = y(value)
                        if (!started) {
                            path.moveTo(x, yy)
                            started = true
                        } else {
                            path.lineTo(x, yy)
                        }
                    }
                    if (started) drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
                }

                if (chartMode == ChartMode.Candle) {
                    visible.forEachIndexed { index, point ->
                        val x = chartX(index)
                        val up = point.close >= point.open
                        val color = if (up) QuantPositive else QuantNegative
                        drawLine(color, Offset(x, y(point.high)), Offset(x, y(point.low)), strokeWidth = 2f)
                        val top = min(y(point.open), y(point.close))
                        val bottom = max(y(point.open), y(point.close))
                        val bodyHeight = max(2f, bottom - top)
                        drawRect(
                            color = color,
                            topLeft = Offset(x - candleWidth / 2f, top),
                            size = Size(candleWidth, bodyHeight)
                        )
                    }
                } else {
                    val lineColor = if (last >= first) QuantPositive else QuantNegative
                    drawSeries(visible.map { it.close }, lineColor, strokeWidth = 4f)
                }

                if (ChartOverlay.Bollinger in overlays) {
                    drawSeries(bollinger.map { it?.first }, QuantMuted, strokeWidth = 2f)
                    drawSeries(bollinger.map { it?.second }, QuantMuted, strokeWidth = 2f)
                }
                if (ChartOverlay.MA5 in overlays) drawSeries(ma5, QuantWarning)
                if (ChartOverlay.MA20 in overlays) drawSeries(ma20, QuantGreen)
                if (ChartOverlay.MA60 in overlays) drawSeries(ma60, QuantPurple)

                val highIndex = visible.indices.maxByOrNull { visible[it].high }
                val lowIndex = visible.indices.minByOrNull { visible[it].low }
                val labelColor = QuantMuted
                val markerColor = QuantLine
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = labelColor.toArgb()
                    textSize = 11.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                fun drawExtremaLabel(index: Int, price: Double, prefix: String, isHigh: Boolean) {
                    val pointX = chartX(index)
                    val pointY = y(price)
                    val date = shortChartDate(visible[index].date)
                    val label = "$prefix ${fmtPx(price, currency)} · $date"
                    val width = paint.measureText(label)
                    val textX = pointX.coerceIn(width / 2f + 4f, size.width - width / 2f - 4f)
                    val labelGap = 20f
                    val textY = if (isHigh) {
                        (pointY - labelGap).coerceAtLeast(12.dp.toPx())
                    } else {
                        (pointY + labelGap + 10f).coerceAtMost(size.height - 4f)
                    }
                    drawLine(
                        markerColor,
                        Offset(pointX, pointY),
                        Offset(pointX, if (isHigh) textY + 4f else textY - 16f),
                        strokeWidth = 2f
                    )
                    drawCircle(markerColor, radius = 4f, center = Offset(pointX, pointY))
                    drawContext.canvas.nativeCanvas.drawText(label, textX, textY, paint)
                }
                if (highIndex != null) {
                    drawExtremaLabel(highIndex, visible[highIndex].high, "최고", isHigh = true)
                }
                if (lowIndex != null && lowIndex != highIndex) {
                    drawExtremaLabel(lowIndex, visible[lowIndex].low, "최저", isHigh = false)
                }
            }
            ChartRangeSummaryRow(
                points = visible,
                currency = currency,
                onSettingsClick = { showIndicatorSheet = true }
            )
            if (ChartOverlay.Volume in overlays) {
                IndicatorLabel("거래량")
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val maxVolume = visible.mapNotNull { it.volume }.maxOrNull() ?: 0.0
                    if (maxVolume <= 0.0) return@Canvas
                    val step = size.width / visible.lastIndex.coerceAtLeast(1).toFloat()
                    val barWidth = max(2f, step * 0.734f)
                    fun volumeX(index: Int): Float = if (visible.size <= 1) {
                        size.width / 2f
                    } else {
                        val left = barWidth / 2f
                        val right = size.width - barWidth / 2f
                        left + (right - left) * index / visible.lastIndex.toFloat()
                    }
                    visible.forEachIndexed { index, point ->
                        val volume = point.volume ?: return@forEachIndexed
                        val h = (volume / maxVolume).toFloat() * size.height
                        val color = if (point.close >= point.open) QuantPositive.copy(alpha = 0.40f) else QuantNegative.copy(alpha = 0.40f)
                        drawRect(color, Offset(volumeX(index) - barWidth / 2f, size.height - h), Size(barWidth, h))
                    }
                }
            }
            if (ChartOverlay.RSI in overlays) {
                IndicatorLabel("RSI 14")
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    fun y(value: Double): Float = size.height - (value.coerceIn(0.0, 100.0) / 100.0).toFloat() * size.height
                    drawLine(QuantLine, Offset(0f, y(70.0)), Offset(size.width, y(70.0)), strokeWidth = 1.5f)
                    drawLine(QuantLine, Offset(0f, y(30.0)), Offset(size.width, y(30.0)), strokeWidth = 1.5f)
                    val path = Path()
                    var started = false
                    rsi.forEachIndexed { index, value ->
                        if (value == null) return@forEachIndexed
                        val x = size.width * index / rsi.lastIndex.coerceAtLeast(1).toFloat()
                        val yy = y(value)
                        if (!started) {
                            path.moveTo(x, yy)
                            started = true
                        } else {
                            path.lineTo(x, yy)
                        }
                    }
                    if (started) drawPath(path, QuantPurple, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
            }
        }
    }
    if (showIndicatorSheet) {
        ModalBottomSheet(onDismissRequest = { showIndicatorSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("보조지표", style = MaterialTheme.typography.titleMedium)
                ChartOverlay.entries.forEach { overlay ->
                    val selected = overlay in overlays
                    if (selected) {
                        Button(
                            onClick = { overlays = overlays - overlay },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(overlay.label) }
                    } else {
                        OutlinedButton(
                            onClick = { overlays = overlays + overlay },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(overlay.label) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

internal fun chartVisiblePoints(points: List<PricePoint>, period: ChartPeriod): List<PricePoint> {
    val clean = points.filter { point ->
        point.open.isFinite() &&
            point.high.isFinite() &&
            point.low.isFinite() &&
            point.close.isFinite() &&
            point.high >= point.low &&
            point.close > 0.0
    }
    val visible = clean.takeLast(min(clean.size, period.maxPoints))
    return when (period) {
        ChartPeriod.SixMonths -> aggregateChartPointChunks(visible, 2)
        ChartPeriod.OneYear -> aggregateChartPoints(visible, ::weeklyChartBucket)
        ChartPeriod.ThreeYears -> {
            val firstWeekStart = visible.firstOrNull()?.let { parseChartDate(it.date)?.chartWeekStart() }
            if (firstWeekStart == null) visible else aggregateChartPoints(visible) { threeWeekChartBucket(it, firstWeekStart) }
        }
        ChartPeriod.FiveYears -> aggregateChartPoints(visible, ::monthlyChartBucket)
        else -> visible
    }
}

internal fun aggregateChartPointChunks(points: List<PricePoint>, chunkSize: Int): List<PricePoint> {
    if (chunkSize <= 1 || points.isEmpty()) return points
    return points.chunked(chunkSize).mapNotNull(::aggregateChartBucket)
}

internal fun aggregateChartPoints(
    points: List<PricePoint>,
    bucketKey: (PricePoint) -> String
): List<PricePoint> {
    if (points.isEmpty()) return emptyList()

    val buckets = mutableListOf<MutableList<PricePoint>>()
    var lastKey: String? = null
    points.forEach { point ->
        val key = bucketKey(point)
        if (key == lastKey && buckets.isNotEmpty()) {
            buckets.last().add(point)
        } else {
            buckets.add(mutableListOf(point))
            lastKey = key
        }
    }

    return buckets.mapNotNull(::aggregateChartBucket)
}

internal fun aggregateChartBucket(bucket: List<PricePoint>): PricePoint? {
    val first = bucket.firstOrNull() ?: return null
    val last = bucket.lastOrNull() ?: return null
    val volumes = bucket.mapNotNull { it.volume }
    return PricePoint(
        date = last.date,
        open = first.open,
        high = bucket.maxOf { it.high },
        low = bucket.minOf { it.low },
        close = last.close,
        volume = volumes.takeIf { it.isNotEmpty() }?.sum()
    )
}

internal fun weeklyChartBucket(point: PricePoint): String {
    val date = parseChartDate(point.date) ?: return point.date
    return date.chartWeekStart().toString()
}

internal fun threeWeekChartBucket(point: PricePoint, firstWeekStart: LocalDate): String {
    val date = parseChartDate(point.date) ?: return point.date
    val weekOffset = ChronoUnit.WEEKS.between(firstWeekStart, date.chartWeekStart()).coerceAtLeast(0L)
    return "W3-${weekOffset / 3L}"
}

internal fun monthlyChartBucket(point: PricePoint): String {
    val date = parseChartDate(point.date) ?: return point.date
    return date.withDayOfMonth(1).toString()
}

internal fun parseChartDate(value: String): LocalDate? {
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun LocalDate.chartWeekStart(): LocalDate {
    return minusDays((dayOfWeek.value - 1).toLong())
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChartRangeSummaryRow(
    points: List<PricePoint>,
    currency: String,
    onSettingsClick: () -> Unit
) {
    if (points.size < 2) return
    val last = points.last()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartSummaryPill("고가", fmtPx(points.maxOf { it.high }, currency))
            ChartSummaryPill("저가", fmtPx(points.minOf { it.low }, currency))
            last.volume?.let { ChartSummaryPill("거래량", compactNumber(it)) }
        }
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "보조지표 설정",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ChartSummaryPill(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun PeriodModeSelector(
    period: ChartPeriod,
    availablePeriods: Set<ChartPeriod>,
    onPeriodChange: (ChartPeriod) -> Unit,
    mode: ChartMode,
    tone: Color,
    onModeChange: (ChartMode) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ChartPeriod.entries.forEach { option ->
                val enabled = option in availablePeriods
                PeriodChip(
                    label = option.label,
                    selected = option == period,
                    enabled = enabled,
                    onClick = { if (enabled) onPeriodChange(option) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onModeChange(if (mode == ChartMode.Candle) ChartMode.Line else ChartMode.Candle)
                },
            color = tone.copy(alpha = 0.10f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                ChartModeGlyph(mode, tone)
            }
        }
    }
}

@Composable
internal fun PeriodChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when {
        selected -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }
    Surface(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                if (enabled && !selected) onClick()
            },
        color = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
            enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
internal fun ChartModeGlyph(mode: ChartMode, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        if (mode == ChartMode.Candle) {
            val xs = listOf(size.width * 0.25f, size.width * 0.5f, size.width * 0.75f)
            val bodyHeights = listOf(size.height * 0.36f, size.height * 0.58f, size.height * 0.42f)
            xs.forEachIndexed { index, x ->
                val centerY = size.height * (0.38f + index * 0.08f)
                drawLine(
                    color = color,
                    start = Offset(x, centerY - size.height * 0.32f),
                    end = Offset(x, centerY + size.height * 0.32f),
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round
                )
                drawRect(
                    color = color,
                    topLeft = Offset(x - size.width * 0.075f, centerY - bodyHeights[index] / 2f),
                    size = Size(size.width * 0.15f, bodyHeights[index])
                )
            }
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.70f)
                lineTo(size.width * 0.30f, size.height * 0.46f)
                lineTo(size.width * 0.52f, size.height * 0.57f)
                lineTo(size.width * 0.76f, size.height * 0.24f)
                lineTo(size.width * 0.94f, size.height * 0.36f)
            }
            drawPath(path, color = color, style = Stroke(width = 3.2f, cap = StrokeCap.Round))
            drawCircle(color, radius = 2.8f, center = Offset(size.width * 0.94f, size.height * 0.36f))
        }
    }
}

@Composable
internal fun IndicatorLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun movingAverage(points: List<PricePoint>, window: Int): List<Double?> {
    if (window <= 0) return List(points.size) { null }
    var sum = 0.0
    return points.indices.map { index ->
        sum += points[index].close
        if (index >= window) sum -= points[index - window].close
        val count = min(index + 1, window)
        sum / count
    }
}

internal fun bollingerBands(points: List<PricePoint>, window: Int): List<Pair<Double, Double>?> {
    if (window <= 1) return List(points.size) { null }
    return points.indices.map { index ->
        val start = max(0, index + 1 - window)
        val closes = points.subList(start, index + 1).map { it.close }
        if (closes.size < 2) {
            val close = closes.firstOrNull()
            close?.let { it to it }
        } else {
            val avg = closes.average()
            val std = sqrt(closes.sumOf { (it - avg) * (it - avg) } / closes.size)
            (avg + 2.0 * std) to (avg - 2.0 * std)
        }
    }
}

internal fun rsiSeries(points: List<PricePoint>, window: Int): List<Double?> {
    if (points.isEmpty() || window <= 0) return List(points.size) { null }
    return points.indices.map { index ->
        if (index == 0) {
            50.0
        } else {
            val start = max(0, index + 1 - window)
            val slice = points.subList(start, index + 1)
            var gains = 0.0
            var losses = 0.0
            slice.zipWithNext { prev, next ->
                val diff = next.close - prev.close
                if (diff >= 0) gains += diff else losses -= diff
            }
            when {
                losses == 0.0 && gains == 0.0 -> 50.0
                losses == 0.0 -> 100.0
                else -> {
                    val rs = gains / losses
                    100.0 - (100.0 / (1.0 + rs))
                }
            }
        }
    }
}

internal fun shortChartDate(date: String): String {
    val parts = date.split("-")
    return if (parts.size >= 3) "${parts[1]}/${parts[2]}" else date
}
