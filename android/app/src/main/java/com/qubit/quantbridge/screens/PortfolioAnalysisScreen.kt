package com.qubit.quantbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
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
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun PortfolioScreenContent(
    app: QuantAppState,
    portfolioViewModel: PortfolioViewModel,
    smallCapViewModel: SmallCapViewModel,
    comparisonViewModel: ComparisonViewModel
) {
    val scope = rememberCoroutineScope()
    val analysisSection = app.analysisSection
    var portfolioMode by remember { mutableStateOf("일반") }
    var market by remember { mutableStateOf(Market.US) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("랭킹") }
    var comparisonMode by remember { mutableStateOf(false) }
    var selectedComparisonIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listContentAlpha = remember { Animatable(1f) }
    var listContentPrimed by remember { mutableStateOf(false) }
    val isSmallCap = portfolioMode == "스몰캡"
    val stocks = portfolioViewModel.stocksFor(market)
    val smallCapStocks = smallCapViewModel.stocksFor(market)
    val matchingStocks = remember(stocks, query) {
        stocks
            .filter { matchesPortfolioIndustryQuery(query, it.ticker, it.name, it.sector) }
    }
    val matchingSmallCaps = remember(smallCapStocks, query) {
        smallCapStocks
            .filter { matchesPortfolioIndustryQuery(query, it.ticker, it.name, null) }
    }
    val visible = remember(matchingStocks, sort) {
        matchingStocks
            .sortedWith(compareByFor(sort) { stock: PortfolioStock ->
                when (sort) {
                    "비중" -> stock.weight
                    "점수" -> stock.totalScore
                    "기대수익" -> stock.expectedReturn
                    "매출성장" -> stock.revGrowth
                    else -> stock.rank?.toDouble()
                }
            })
    }
    val visibleSmallCap = remember(matchingSmallCaps, sort) {
        matchingSmallCaps
            .sortedWith(compareByFor(sort) { stock: SmallCapStock ->
                when (sort) {
                    "점수" -> stock.totalScore
                    "매출성장" -> stock.revGrowth
                    "시가총액" -> stock.marketCap
                    else -> stock.rank?.toDouble()
                }
            })
    }
    val rankingFreshness = remember(isSmallCap, stocks, smallCapStocks, visible, visibleSmallCap) {
        if (isSmallCap) {
            smallCapDatasetFreshness(smallCapStocks, visibleSmallCap.size)
        } else {
            portfolioDatasetFreshness(stocks, visible.size)
        }
    }
    val comparisonCandidates = remember(isSmallCap, visible, visibleSmallCap, market) {
        if (isSmallCap) {
            visibleSmallCap.map { it.toCompareStock() }
        } else {
            visible.map { it.toCompareStock(market.currency) }
        }
    }
    val selectedComparisonItems = remember(comparisonCandidates, selectedComparisonIds) {
        comparisonCandidates.filter { selectedComparisonIds.contains(it.id) }
    }

    fun toggleComparison(item: StockComparisonItem) {
        selectedComparisonIds = if (selectedComparisonIds.contains(item.id)) {
            selectedComparisonIds - item.id
        } else if (selectedComparisonIds.size < 4) {
            selectedComparisonIds + item.id
        } else {
            selectedComparisonIds
        }
    }

    LaunchedEffect(analysisSection, portfolioMode, market) {
        comparisonMode = false
        selectedComparisonIds = emptySet()
        if (listContentPrimed) {
            listContentAlpha.snapTo(0.34f)
            listContentAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
        } else {
            listContentPrimed = true
            listContentAlpha.snapTo(1f)
        }
        if (analysisSection == "기업") {
            if (isSmallCap) {
                smallCapViewModel.refreshSmallCap(market)
            } else {
                portfolioViewModel.refreshPortfolio(market)
            }
        }
    }
    LaunchedEffect("analysis-price-auto", analysisSection, portfolioMode, market) {
        if (analysisSection != "기업") return@LaunchedEffect
        while (true) {
            delay(ANALYSIS_PRICE_AUTO_REFRESH_MS)
            if (portfolioMode == "스몰캡") {
                smallCapViewModel.refreshSmallCap(market, automatic = true)
            } else {
                portfolioViewModel.refreshPortfolio(market, automatic = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 10.dp)
        ) {
            SoftSegmentSwitch(
                options = listOf("기업", "섹터", "ETF"),
                selected = analysisSection,
                onSelect = { app.analysisSection = it }
            )
        }

        AnimatedContent(
            targetState = analysisSection,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                    .togetherWith(fadeOut(animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing)))
            },
            label = "analysis-section-transition"
        ) { section ->
            if (section == "ETF") {
                EtfInsightsScreen(app)
            } else if (section == "섹터") {
                SectorThemesScreen(app)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        top = 0.dp,
                        end = 10.dp,
                        bottom = FLOATING_NAV_CONTENT_INSET
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item {
                        PortfolioSearchSortToolbar(
                            query = query,
                            onQuery = { query = it },
                            sort = sort,
                            sortOptions = if (isSmallCap) {
                                listOf("랭킹", "점수", "매출성장", "시가총액")
                            } else {
                                listOf("랭킹", "점수", "비중", "기대수익", "매출성장")
                            },
                            onSort = { sort = it }
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisFilterChipRow(
                            title = "유형",
                            values = listOf("일반", "스몰캡"),
                            selected = portfolioMode,
                            onSelect = {
                                portfolioMode = it
                                sort = "랭킹"
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        AnalysisFilterChipRow(
                            title = "시장",
                            values = Market.entries.map { marketDisplayLabel(it) },
                            selected = marketDisplayLabel(market),
                            onSelect = { selectedTitle ->
                                Market.entries.firstOrNull { marketDisplayLabel(it) == selectedTitle }?.let { market = it }
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        StockComparisonBar(
                            isComparing = comparisonMode,
                            selectedCount = selectedComparisonItems.size,
                            totalCount = comparisonCandidates.size,
                            canCompare = selectedComparisonItems.size >= 2,
                            onStart = {
                                comparisonMode = true
                            },
                            onCompare = {
                                comparisonViewModel.replace(selectedComparisonItems)
                                comparisonViewModel.openSheet()
                            },
                            onClear = { selectedComparisonIds = emptySet() },
                            onCancel = {
                                comparisonMode = false
                                selectedComparisonIds = emptySet()
                            }
                        )
                    }
        if (isSmallCap) {
            item {
                Box(Modifier.alpha(listContentAlpha.value)) {
                    Column {
                        PortfolioRankingSectionTitle()
                        rankingDatasetFreshnessRow(rankingFreshness)
                    }
                }
            }
            if (smallCapViewModel.loading && smallCapStocks.isEmpty()) {
                item {
                    Box(Modifier.alpha(listContentAlpha.value)) {
                        CardBlock {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("스몰캡 데이터 로딩 중", fontWeight = FontWeight.Bold)
                                    Text(
                                        "${market.title} 스몰캡 후보와 가격 지표를 불러오고 있습니다.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (visibleSmallCap.isEmpty()) {
                item {
                    Box(Modifier.alpha(listContentAlpha.value)) {
                        EmptyCard(
                            title = if (smallCapStocks.isEmpty()) "스몰캡 데이터 없음" else "검색 결과 없음",
                            message = listEmptyMessage(
                                query = query,
                                emptyDataMessage = smallCapViewModel.error ?: "${market.title} 스몰캡 후보 데이터가 비어 있습니다.",
                                filteredMessage = "현재 검색 조건과 일치하는 후보가 없습니다."
                            ),
                            lucideIcon = if (smallCapStocks.isEmpty()) LucideIcon.RefreshCw else LucideIcon.Search,
                            actionLabel = if (smallCapStocks.isEmpty()) "새로고침" else null,
                            onAction = if (smallCapStocks.isEmpty()) {
                                { smallCapViewModel.refreshSmallCap(market, force = true) }
                            } else {
                                null
                            }
                        )
                    }
                }
            } else {
                itemsIndexed(visibleSmallCap, key = { _, stock -> stock.ticker }) { index, stock ->
                    val compareItem = stock.toCompareStock()
                    val compareSelected = selectedComparisonIds.contains(compareItem.id)
                    Box(Modifier.alpha(listContentAlpha.value)) {
                        SmallCapRankingRow(
                            rankLabel = "${index + 1}",
                            stock = stock,
                            profile = app.investmentProfile,
                            currentPrice = stock.currentPrice,
                            return1M = stock.return1M,
                            comparisonMode = comparisonMode,
                            comparisonSelected = compareSelected,
                            comparisonDisabled = comparisonMode && !compareSelected && selectedComparisonIds.size >= 4,
                            onOpen = {
                                if (comparisonMode) {
                                    toggleComparison(compareItem)
                                } else {
                                    app.selectedDetail = smallCapDetail(stock, marketCurrency(stock.ticker, stock.market))
                                }
                            }
                        )
                    }
                }
            }
        } else {
            item {
                Box(Modifier.alpha(listContentAlpha.value)) {
                    Column {
                        PortfolioRankingSectionTitle()
                        rankingDatasetFreshnessRow(rankingFreshness)
                    }
                }
            }
            if (portfolioViewModel.loading && stocks.isEmpty()) {
                item {
                    Box(Modifier.alpha(listContentAlpha.value)) {
                        CardBlock {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("분석 데이터 로딩 중", fontWeight = FontWeight.Bold)
                                    Text(
                                        "${market.title} 기업 순위와 가격 지표를 불러오고 있습니다.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (visible.isEmpty()) {
                item {
                    Box(Modifier.alpha(listContentAlpha.value)) {
                        EmptyCard(
                            title = if (stocks.isEmpty()) "분석 데이터 없음" else "검색 결과 없음",
                            message = listEmptyMessage(
                                query = query,
                                emptyDataMessage = portfolioViewModel.error ?: "${market.title} 분석 후보 데이터가 비어 있습니다.",
                                filteredMessage = "현재 검색 조건과 일치하는 기업이 없습니다."
                            ),
                            lucideIcon = if (stocks.isEmpty()) LucideIcon.RefreshCw else LucideIcon.Search,
                            actionLabel = if (stocks.isEmpty()) "새로고침" else null,
                            onAction = if (stocks.isEmpty()) {
                                { portfolioViewModel.refreshPortfolio(market, force = true) }
                            } else {
                                null
                            }
                        )
                    }
                }
            } else {
                itemsIndexed(visible, key = { _, stock -> stock.ticker }) { index, stock ->
                    val currency = market.currency
                    val compareItem = stock.toCompareStock(currency)
                    val compareSelected = selectedComparisonIds.contains(compareItem.id)
                    Box(Modifier.alpha(listContentAlpha.value)) {
                        PortfolioRankingRow(
                            rankLabel = "${index + 1}",
                            stock = stock,
                            profile = app.investmentProfile,
                            currency = currency,
                            currentPrice = stock.currentPrice,
                            return1M = stock.return1M,
                            comparisonMode = comparisonMode,
                            comparisonSelected = compareSelected,
                            comparisonDisabled = comparisonMode && !compareSelected && selectedComparisonIds.size >= 4,
                            onOpen = {
                                if (comparisonMode) {
                                    toggleComparison(compareItem)
                                } else {
                                    app.selectedDetail = portfolioDetail(stock, currency)
                                }
                            }
                        )
                    }
                }
            }
        }
                }
            }
    }
    }
}



internal enum class InsightSection(val label: String) {
    Earnings("실적"),
    News("뉴스"),
    Events("이벤트"),
    Training("훈련")
}

internal val InsightSection.transitionIndex: Int
    get() = when (this) {
        InsightSection.Earnings -> 0
        InsightSection.News -> 1
        InsightSection.Events -> 2
        InsightSection.Training -> 3
    }
