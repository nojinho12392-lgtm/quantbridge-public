package com.example.myapplication

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
import com.example.myapplication.ui.theme.QuantBlue
import com.example.myapplication.ui.theme.QuantDanger
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

private const val ANALYSIS_PRICE_AUTO_REFRESH_MS = 300_000L
private val PortfolioListRowMinHeight = 70.dp
private val PortfolioListLogoSize = 43.dp
private val PortfolioListVerticalPadding = 11.dp
private val PortfolioListNamePriceGap = 5.dp
private val PortfolioListUsNamePriceGap = 8.dp
val FLOATING_NAV_CONTENT_INSET = 104.dp

internal data class DiagnosticInfo(
    val title: String,
    val status: String,
    val summary: String,
    val details: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
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
                    PortfolioRankingSectionTitle()
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
                    PortfolioRankingSectionTitle()
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



private enum class InsightSection(val label: String) {
    Earnings("실적"),
    News("뉴스"),
    Events("이벤트")
}

private val InsightSection.transitionIndex: Int
    get() = when (this) {
        InsightSection.Earnings -> 0
        InsightSection.News -> 1
        InsightSection.Events -> 2
    }

@Composable
internal fun InsightScreenContent(
    app: QuantAppState,
    pulseViewModel: PulseViewModel,
    newsViewModel: NewsViewModel
) {
    var section by remember { mutableStateOf(InsightSection.Earnings) }

    LaunchedEffect(section) {
        when (section) {
            InsightSection.Earnings -> {
                if (!pulseViewModel.loading && pulseViewModel.usEarnings.isEmpty() && pulseViewModel.krEarnings.isEmpty()) {
                    pulseViewModel.refreshPulse()
                }
            }
            InsightSection.News -> newsViewModel.ensureNewsLoaded("ALL")
            InsightSection.Events -> pulseViewModel.ensureEarningsCalendarLoaded()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        InsightMarketHeader(
            selected = section,
            onSelected = { section = it }
        )

        AnimatedContent(
            targetState = section,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val direction = if (targetState.transitionIndex > initialState.transitionIndex) 1 else -1
                (
                    slideInHorizontally(
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> fullWidth * direction }
                    ) + fadeIn(animationSpec = tween(durationMillis = 160))
                ).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                        targetOffsetX = { fullWidth -> -fullWidth * direction }
                    ) + fadeOut(animationSpec = tween(durationMillis = 140))
                )
            },
            label = "InsightSectionTransition"
        ) { targetSection ->
            when (targetSection) {
                InsightSection.Earnings -> PulseScreenContent(
                    app = app,
                    pulseViewModel = pulseViewModel,
                    showCalendar = false,
                    showMomentum = true,
                    contentTopPadding = 4.dp
                )
                InsightSection.News -> NewsScreen(
                    app = app,
                    contentTopPadding = 10.dp,
                    showControls = false,
                    showSummary = false,
                    useImpactFeed = true,
                    newsViewModel = newsViewModel
                )
                InsightSection.Events -> PulseScreenContent(
                    app = app,
                    pulseViewModel = pulseViewModel,
                    showCalendar = true,
                    showMomentum = false,
                    contentTopPadding = 4.dp
                )
            }
        }
    }
}

@Composable
private fun InsightMarketHeader(
    selected: InsightSection,
    onSelected: (InsightSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp)
    ) {
        SoftSegmentSwitch(
            options = InsightSection.entries.map { it.label },
            selected = selected.label,
            onSelect = { label ->
                InsightSection.entries.firstOrNull { it.label == label }?.let(onSelected)
            }
        )
    }
}

@Composable
internal fun PulseScreenContent(
    app: QuantAppState,
    pulseViewModel: PulseViewModel,
    showCalendar: Boolean = true,
    showMomentum: Boolean = true,
    contentTopPadding: Dp = 10.dp,
    contentBottomPadding: Dp = FLOATING_NAV_CONTENT_INSET
) {
    val scope = rememberCoroutineScope()
    var market by remember { mutableStateOf(Market.US) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("랭킹") }
    val earnings = pulseViewModel.earningsFor(market)
    val calendarItems = remember(pulseViewModel.earningsCalendar, market, query) {
        pulseViewModel.earningsCalendar
            .filter { it.market.equals(market.title, ignoreCase = true) }
            .filter { matches(query, it.ticker, it.name, it.sector) }
            .sortedWith(compareBy<EarningsCalendarItem> { it.nextEarningsDate }.thenBy { it.name })
    }
    val calendarFocusItems = remember(calendarItems) {
        val nearTerm = calendarItems.filter { item ->
            val days = item.daysUntil
            days != null && days >= 0 && days <= 7
        }
        val source = if (nearTerm.isEmpty()) calendarItems else nearTerm
        source.sortedWith(
            compareBy<EarningsCalendarItem> { it.daysUntil ?: Int.MAX_VALUE }
                .thenByDescending { it.marketCap ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.name }
        ).take(3)
    }
    val visible = remember(earnings, query, sort) {
        earnings
            .filter { matches(query, it.ticker, it.name, it.sector) }
            .sortedWith(compareByFor(sort) { stock: EarningsStock ->
                when (sort) {
                    "시그널" -> stock.signalStrength
                    "서프라이즈" -> stock.surprisePct
                    "수익률" -> stock.returnSince
                    "최근순" -> stock.daysSince?.let { -it }
                    else -> stock.rank?.toDouble()
                }
            })
    }

    LaunchedEffect(showCalendar, showMomentum) {
        val needsCalendar = showCalendar && pulseViewModel.earningsCalendar.isEmpty()
        val needsMomentum = showMomentum && pulseViewModel.usEarnings.isEmpty() && pulseViewModel.krEarnings.isEmpty()
        val needsMacro = pulseViewModel.macro.isEmpty()
        if (!pulseViewModel.loading && (needsCalendar || needsMomentum || needsMacro)) {
            pulseViewModel.refreshPulse()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentTopPadding,
            end = 16.dp,
            bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            MarketToolbar(
                market = market,
                onMarket = { market = it },
                query = query,
                onQuery = { query = it },
                sort = sort,
                sortOptions = listOf("랭킹", "시그널", "서프라이즈", "수익률", "최근순"),
                onSort = { sort = it }
            )
        }
        if (showCalendar) {
            if (calendarFocusItems.isNotEmpty()) {
                item {
                    EarningsCalendarFocusCard(
                        items = calendarFocusItems,
                        totalCount = calendarItems.size,
                        onOpen = { app.selectedDetail = earningsCalendarDetail(it) }
                    )
                }
            }
            item { SectionTitle("어닝 캘린더", "${calendarItems.size}개 예정") }
            if (calendarItems.isEmpty()) {
                item {
                    if (pulseViewModel.loading) {
                        SkeletonLoadingCard(lineCount = 2)
                    } else {
                        EmptyCard(
                            title = "예정 실적 없음",
                            message = listEmptyMessage(
                                query = query,
                                emptyDataMessage = "${market.title} 예정 실적 데이터가 아직 없습니다.",
                                filteredMessage = "현재 검색 조건과 일치하는 예정 실적이 없습니다."
                            ),
                            lucideIcon = LucideIcon.RefreshCw,
                            actionLabel = "새로고침",
                            onAction = { pulseViewModel.refreshPulse(force = true) }
                        )
                    }
                }
            } else {
                item {
                    EarningsCalendarMonthCard(
                        items = calendarItems,
                        onOpen = { app.selectedDetail = earningsCalendarDetail(it) }
                    )
                }
            }
        }

        if (showMomentum) {
            item { SectionTitle("실적 모멘텀", "${visible.size}/${earnings.size}") }
            if (visible.isEmpty()) {
                item {
                    if (pulseViewModel.loading) {
                        SkeletonLoadingCard(lineCount = 2)
                    } else {
                        EmptyCard(
                            title = if (earnings.isEmpty()) "실적 모멘텀 데이터 없음" else "검색 결과 없음",
                            message = listEmptyMessage(
                                query = query,
                                emptyDataMessage = "${market.title} 실적 이벤트 데이터가 비어 있습니다.",
                                filteredMessage = "현재 검색 조건과 일치하는 실적 이벤트가 없습니다."
                            ),
                            lucideIcon = if (earnings.isEmpty()) LucideIcon.RefreshCw else LucideIcon.Search,
                            actionLabel = if (earnings.isEmpty()) "새로고침" else null,
                            onAction = if (earnings.isEmpty()) {
                                { pulseViewModel.refreshPulse(force = true) }
                            } else {
                                null
                            }
                        )
                    }
                }
            } else {
                itemsIndexed(visible, key = { _, stock -> stock.ticker }) { index, stock ->
                    StockRow(
                        rankLabel = "${index + 1}",
                        title = stock.name,
                        ticker = stock.ticker,
                        market = market.title,
                        subtitle = portfolioIndustryLabel(stock.ticker, stock.name, stock.sector),
                        headline = stock.signalStrength?.let { "%.2f".format(it) } ?: "-",
                        kpis = listOf(
                            "EPS" to pct(stock.surprisePct),
                            "수익률" to pct(stock.returnSince),
                            "경과" to (stock.daysSince?.let { "${it.toInt()}일" } ?: "-")
                        ),
                        watched = app.isWatched(stock.ticker),
                        onWatch = { scope.launchSafely { app.toggleWatch(watchItem(stock.ticker, stock.name, null, marketCurrency(stock.ticker, null), "실적")) } },
                        onOpen = { app.selectedDetail = earningsDetail(stock) }
                    )
                }
            }
        }
        if (pulseViewModel.macro.isNotEmpty()) {
            item { SectionTitle("시장 배경", "판단 근거") }
            item {
                PulseRegimeCard(macro = pulseViewModel.macro, market = market)
            }
        }
    }
}

@Composable
private fun EarningsCalendarFocusCard(
    items: List<EarningsCalendarItem>,
    totalCount: Int,
    onOpen: (EarningsCalendarItem) -> Unit
) {
    CardBlock(useBorder = false) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.CalendarClock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("이번 주 확인할 실적", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    "가까운 일정과 시총이 큰 기업을 먼저 보여줍니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            Text(
                "전체 ${totalCount}개",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                        .quantClickable(role = QuantPressRole.Row, onClick = { onOpen(item) })
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TickerAvatar(item.ticker, item.market, size = 36.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            localizedCompanyName(item.ticker, item.name, item.market),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${shortTicker(item.ticker)} · ${formatEarningsCalendarDate(item.nextEarningsDate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            daysUntilText(item.daysUntil),
                            color = if ((item.daysUntil ?: 99) <= 3) QuantWarning else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(
                            earningsCalendarValueText(item),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EarningsCalendarMonthCard(
    items: List<EarningsCalendarItem>,
    onOpen: (EarningsCalendarItem) -> Unit
) {
    val grouped = remember(items) {
        items.mapNotNull { item ->
            parseEarningsCalendarDate(item.nextEarningsDate)?.let { date -> date to item }
        }
            .groupBy({ it.first }, { it.second })
            .mapValues { entry ->
                entry.value.sortedWith(
                    compareByDescending<EarningsCalendarItem> { it.marketCap ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.name }
                        .thenBy { it.ticker }
                )
            }
    }
    val eventDates = remember(grouped) { grouped.keys.sorted() }
    var visibleMonth by remember {
        mutableStateOf((eventDates.firstOrNull() ?: LocalDate.now()).withDayOfMonth(1))
    }
    var selectedDate by remember { mutableStateOf(eventDates.firstOrNull()) }

    LaunchedEffect(eventDates) {
        if (eventDates.isEmpty()) {
            selectedDate = null
            visibleMonth = LocalDate.now().withDayOfMonth(1)
        } else if (selectedDate == null || !eventDates.contains(selectedDate)) {
            selectedDate = eventDates.first()
            visibleMonth = eventDates.first().withDayOfMonth(1)
        }
    }

    val selectedItems = selectedDate?.let { grouped[it] }.orEmpty()
    val cells = remember(visibleMonth) { earningsCalendarMonthCells(visibleMonth) }

    CardBlock(useBorder = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatEarningsCalendarMonth(visibleMonth),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { visibleMonth = visibleMonth.minusMonths(1).withDayOfMonth(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 달", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { visibleMonth = visibleMonth.plusMonths(1).withDayOfMonth(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                Text(
                    day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { date ->
                        EarningsCalendarDateCell(
                            date = date,
                            hasEvent = date != null && grouped.containsKey(date),
                            selected = date != null && date == selectedDate,
                            onSelect = { selectedDate = it }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selectedDate?.let { formatEarningsCalendarDate(it.toString()) } ?: "날짜 선택",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${selectedItems.size}개 기업",
                style = MaterialTheme.typography.labelMedium,
                color = if (selectedItems.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else QuantPositive,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (selectedItems.isEmpty()) {
            Text(
                "선택한 날짜에 예정된 실적 발표가 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                selectedItems.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                    EarningsCalendarRow(item = item, onOpen = { onOpen(item) })
                }
            }
        }
    }
}

@Composable
private fun RowScope.EarningsCalendarDateCell(
    date: LocalDate?,
    hasEvent: Boolean,
    selected: Boolean,
    onSelect: (LocalDate) -> Unit
) {
    if (date == null) {
        Spacer(Modifier.weight(1f).height(36.dp))
        return
    }

    Surface(
        modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect(date) },
        shape = RoundedCornerShape(12.dp),
        color = when {
            selected -> MaterialTheme.colorScheme.surface
            hasEvent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            else -> Color.Transparent
        },
        border = if (selected) BorderStroke(0.6.dp, QuantLine) else null
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (hasEvent || selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .size(if (hasEvent) 5.dp else 5.dp)
                    .background(if (hasEvent) QuantPositive else Color.Transparent, CircleShape)
            )
        }
    }
}

@Composable
private fun EarningsCalendarRow(
    item: EarningsCalendarItem,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TickerAvatar(item.ticker, item.market, size = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                localizedCompanyName(item.ticker, item.name, item.market),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(
                    item.ticker,
                    portfolioIndustryLabel(item.ticker, item.name, item.sector)
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(daysUntilText(item.daysUntil), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(earningsCalendarValueText(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun earningsCalendarValueText(item: EarningsCalendarItem): String {
    val currency = marketCurrency(item.ticker, item.market)
    val marketCap = item.marketCap
    if (marketCap != null && marketCap.isFinite()) {
        if (currency == "KRW" && marketCap >= 100_000_000.0) {
            return cap(marketCap, currency)
        }
        if (currency != "KRW" && marketCap >= 1_000_000.0) {
            return cap(marketCap, currency)
        }
    }
    return if (item.market.equals("KR", ignoreCase = true)) "국내" else "미국"
}

private fun formatEarningsCalendarDate(date: String): String {
    return try {
        val parsed = LocalDate.parse(date)
        val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")
        "${parsed.monthValue}월 ${parsed.dayOfMonth}일 (${weekdays[parsed.dayOfWeek.value - 1]})"
    } catch (_: DateTimeParseException) {
        date
    }
}

private fun parseEarningsCalendarDate(date: String): LocalDate? {
    return try {
        LocalDate.parse(date)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun formatEarningsCalendarMonth(date: LocalDate): String {
    return "${date.year}년 ${date.monthValue}월"
}

private fun earningsCalendarMonthCells(month: LocalDate): List<LocalDate?> {
    val firstDay = month.withDayOfMonth(1)
    val leadingBlanks = firstDay.dayOfWeek.value % 7
    val cells = mutableListOf<LocalDate?>()
    repeat(leadingBlanks) { cells += null }
    for (day in 1..firstDay.lengthOfMonth()) {
        cells += firstDay.withDayOfMonth(day)
    }
    while (cells.size % 7 != 0) {
        cells += null
    }
    return cells
}

private fun daysUntilText(days: Int?): String {
    return when {
        days == null -> "-"
        days == 0 -> "오늘"
        days > 0 -> "${days}일 후"
        else -> "${-days}일 전"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    app: QuantAppState,
    onDelete: () -> Unit,
    accountViewModel: AccountViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showInvestmentProfileSheet by remember { mutableStateOf(false) }
    val investmentProfileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val currentUser = accountViewModel.user ?: app.user
        val accountLoading = accountViewModel.loading || app.accountLoading
        val accountError = accountViewModel.error ?: app.error
        if (currentUser != null) {
            item {
                AccountProfileCard(
                    user = currentUser,
                    watchlistCount = app.watchlist.size,
                    syncText = app.watchlistSyncStatus.messageText ?: "정상"
                )
            }
            item {
                AccountSettingsCard(
                    watchlistCount = app.watchlist.size,
                    syncText = app.watchlistSyncStatus.messageText ?: "정상",
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
            }
            item {
                InvestmentProfileCard(
                    profile = app.investmentProfile,
                    onEdit = { showInvestmentProfileSheet = true }
                )
            }
            item {
                AccountNotificationCard()
            }
            item {
                AccountSecurityCard()
            }
            item {
                AccountManagementCard(
                    onLogout = {
                        successMessage = null
                        app.error = null
                        accountViewModel.clearError()
                        scope.launchSafely {
                            accountViewModel.logout()
                            app.clearAccountSession(clearWatchlist = false)
                        }
                    },
                    onDelete = onDelete
                )
            }
        } else if (accountViewModel.sessionRestoring || app.accountSessionRestoring || accountViewModel.token != null || app.token != null) {
            item {
                AccountSessionCheckingCard()
            }
            item {
                InvestmentProfileCard(
                    profile = app.investmentProfile,
                    onEdit = { showInvestmentProfileSheet = true }
                )
            }
        } else {
            item {
                AccountAuthCard(
                    signup = signup,
                    name = name,
                    onNameChange = {
                        name = it
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    },
                    email = email,
                    onEmailChange = {
                        email = it
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    },
                    password = password,
                    onPasswordChange = {
                        password = it
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    },
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    errorMessage = accountError,
                    successMessage = successMessage,
                    loading = accountLoading,
                    canSubmit = !accountLoading && email.contains("@") && password.length >= 8 && (!signup || name.isNotBlank()),
                    onSubmit = {
                        scope.launchSafely {
                            val session = accountViewModel.login(email, password, name, signup)
                            if (session != null) {
                                app.adoptAccountSession(session)
                                password = ""
                                successMessage = "로그인과 Watchlist 동기화가 완료됐습니다."
                            }
                        }
                    }
                )
            }
            item {
                AccountCreateButton(
                    text = if (signup) "이미 계정이 있어요" else "새 계정 만들기",
                    onClick = {
                        signup = !signup
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    }
                )
            }
            item {
                InvestmentProfileCard(
                    profile = app.investmentProfile,
                    onEdit = { showInvestmentProfileSheet = true }
                )
            }
        }
    }

    if (showInvestmentProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInvestmentProfileSheet = false },
            sheetState = investmentProfileSheetState
        ) {
            InvestmentProfileSheet(
                profile = app.investmentProfile,
                onSave = {
                    app.updateInvestmentProfile(it)
                    showInvestmentProfileSheet = false
                },
                onDismiss = { showInvestmentProfileSheet = false }
            )
        }
    }
}

@Composable
private fun AccountSessionCheckingCard() {
    CardBlock {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "로그인 상태 확인 중",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "저장된 계정을 불러오는 동안 입력창을 잠시 숨깁니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun AccountProfileCard(user: AuthUser, watchlistCount: Int, syncText: String) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    user.displayName.take(1).uppercase(Locale.getDefault()),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "로그인됨",
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountMiniMetric("관심", "${watchlistCount}개", Modifier.weight(1f))
            AccountMiniMetric("동기화", syncText, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AccountMiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccountSettingsCard(watchlistCount: Int, syncText: String, appVersion: String) {
    CardBlock {
        Text("내 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AccountSettingRow(
            icon = LucideIcon.Heart,
            title = "관심 종목",
            detail = "홈 브리핑과 관심 탭에 반영됩니다.",
            value = "${watchlistCount}개"
        )
        AccountSettingRow(
            icon = LucideIcon.LayoutDashboard,
            title = "홈 브리핑",
            detail = "관심 종목, 시장 뉴스, 실적 이벤트를 우선 보여줍니다.",
            value = "자동"
        )
        AccountSettingRow(
            icon = LucideIcon.RefreshCw,
            title = "데이터 동기화",
            detail = "로그인한 기기에서 관심 종목을 이어서 볼 수 있습니다.",
            value = syncText
        )
        AccountSettingRow(
            icon = LucideIcon.Lightbulb,
            title = "앱 정보",
            detail = "현재 설치된 큐빗 버전입니다.",
            value = appVersion
        )
    }
}

@Composable
private fun InvestmentProfileCard(profile: InvestmentProfile, onEdit: () -> Unit) {
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.SlidersHorizontal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("내 투자 기준", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    profile.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            TextButton(onClick = onEdit) {
                LucideIconView(
                    icon = LucideIcon.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (profile.isConfigured) "수정" else "진단")
            }
        }

        if (profile.isConfigured) {
            Text(
                profile.operatingStatement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InvestmentProfileMetricPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                InvestmentProfileMetricPill(profile.consistencyLabel, profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
            }
            InvestmentProfilePill(profile.headline, LucideIcon.Target)
            InvestmentProfilePill(profile.guardrailSummary, LucideIcon.ShieldCheck)
            profile.consistencyNotes.firstOrNull()?.let {
                InvestmentProfileNotice(it)
            }
        } else {
            InvestmentProfilePill("계좌 연결 없이도 나만의 판단 기준을 먼저 세웁니다.", LucideIcon.Lightbulb)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvestmentProfileSheet(
    profile: InvestmentProfile,
    onSave: (InvestmentProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(profile) { mutableStateOf(profile.normalized) }
    var currentStep by remember(profile) { mutableStateOf(InvestmentProfileStep.Experience) }
    val experienceOptions = listOf("처음 시작", "기본 분석 가능", "숙련")
    val horizonOptions = listOf("1개월", "3개월", "6개월", "1년+")
    val riskOptions = listOf("보수적", "균형", "성장", "공격적")
    val styleOptions = listOf("성장주", "가치주", "배당", "퀄리티", "모멘텀")
    val avoidanceOptions = listOf("급등락", "적자 지속", "고평가", "높은 부채", "낮은 거래량")
    val dropResponseOptions = listOf("가설부터 재검토", "확인 조건까지 보류", "분할 관찰", "손실 한도 도달 시 종료")
    val overheatedResponseOptions = listOf("비교 후보 먼저 보기", "가격 안정 후 보기", "소액 관심만 유지", "모멘텀 근거 확인")
    val steps = InvestmentProfileStep.values().toList()
    val currentIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Target,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("내 투자 기준", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "후보를 보기 전에 내 판단 규칙을 먼저 정합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        InvestmentProfileWizardHeader(
            step = currentStep,
            currentIndex = currentIndex + 1,
            totalCount = steps.size
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (currentStep) {
                InvestmentProfileStep.Experience -> InvestmentProfileOptionList(
                    options = experienceOptions,
                    selected = draft.experience,
                    onSelect = { draft = draft.copy(experience = if (draft.experience == it) "" else it) }
                )
                InvestmentProfileStep.Horizon -> InvestmentProfileOptionList(
                    options = horizonOptions,
                    selected = draft.horizon,
                    onSelect = { draft = draft.copy(horizon = if (draft.horizon == it) "" else it) }
                )
                InvestmentProfileStep.Risk -> InvestmentProfileOptionList(
                    options = riskOptions,
                    selected = draft.riskTolerance,
                    onSelect = { draft = draft.copy(riskTolerance = if (draft.riskTolerance == it) "" else it) }
                )
                InvestmentProfileStep.Style -> InvestmentProfileOptionList(
                    options = styleOptions,
                    selected = draft.style,
                    onSelect = { draft = draft.copy(style = if (draft.style == it) "" else it) }
                )
                InvestmentProfileStep.Avoidances -> InvestmentProfileMultiOptionList(
                    options = avoidanceOptions,
                    selected = draft.avoidances,
                    onSelect = { label ->
                        draft = if (label in draft.avoidances) {
                            draft.copy(avoidances = draft.avoidances - label)
                        } else {
                            draft.copy(avoidances = draft.avoidances + label)
                        }
                    }
                )
                InvestmentProfileStep.DropScenario -> InvestmentProfileOptionList(
                    options = dropResponseOptions,
                    selected = draft.dropResponse,
                    onSelect = { draft = draft.copy(dropResponse = if (draft.dropResponse == it) "" else it) }
                )
                InvestmentProfileStep.HeatScenario -> InvestmentProfileOptionList(
                    options = overheatedResponseOptions,
                    selected = draft.overheatedResponse,
                    onSelect = { draft = draft.copy(overheatedResponse = if (draft.overheatedResponse == it) "" else it) }
                )
                InvestmentProfileStep.Summary -> InvestmentProfileSummaryPanel(draft.normalized)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
            Spacer(Modifier.weight(1f))
            if (currentStep != InvestmentProfileStep.Experience) {
                OutlinedButton(
                    onClick = {
                        steps.getOrNull(currentIndex - 1)?.let { currentStep = it }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("이전")
                }
            }
            Button(
                onClick = {
                    if (currentStep == InvestmentProfileStep.Summary) {
                        onSave(draft.normalized)
                    } else {
                        steps.getOrNull(currentIndex + 1)?.let { currentStep = it }
                    }
                },
                modifier = Modifier.height(52.dp)
            ) {
                Text(if (currentStep == InvestmentProfileStep.Summary) "저장" else "다음")
            }
        }
    }
}

private enum class InvestmentProfileStep(
    val title: String,
    val subtitle: String
) {
    Experience(
        "투자 경험은 어느 정도인가요?",
        "설명 깊이와 리스크 문구의 톤을 맞추는 기준입니다."
    ),
    Horizon(
        "얼마 동안 지켜볼 생각인가요?",
        "관심 종목을 단기 신호로 볼지, 긴 흐름으로 볼지 나눕니다."
    ),
    Risk(
        "변동성은 어디까지 괜찮나요?",
        "같은 랭킹이라도 내 기준에 맞는 후보를 더 차분히 보게 합니다."
    ),
    Style(
        "끌리는 투자 스타일은 무엇인가요?",
        "성장, 가치, 배당처럼 먼저 보고 싶은 관점을 정합니다."
    ),
    Avoidances(
        "피하고 싶은 신호가 있나요?",
        "여러 개를 골라도 괜찮습니다."
    ),
    DropScenario(
        "20% 하락하면 어떻게 할까요?",
        "흔들릴 때 미리 정한 행동 기준이 있어야 가설을 차분하게 복기할 수 있습니다."
    ),
    HeatScenario(
        "좋아 보이지만 너무 올랐다면?",
        "기회처럼 보이는 순간에도 비교와 확인 조건을 먼저 둘지 정합니다."
    ),
    Summary(
        "이 기준으로 저장할까요?",
        "저장된 기준은 이 기기에 보관되며 후보를 볼 때 함께 확인할 개인 기준입니다."
    )
}

@Composable
private fun InvestmentProfileWizardHeader(
    step: InvestmentProfileStep,
    currentIndex: Int,
    totalCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(totalCount) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (index < currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        )
                )
            }
        }
        Text(
            "$currentIndex / $totalCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
        Text(step.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
    }
}

@Composable
private fun InvestmentProfilePill(label: String, icon: LucideIcon) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InvestmentProfileMetricPill(title: String, value: String, icon: LucideIcon, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InvestmentProfileNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(QuantWarning.copy(alpha = 0.09f))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        LucideIconView(
            icon = LucideIcon.TriangleAlert,
            contentDescription = null,
            tint = QuantWarning,
            modifier = Modifier.padding(top = 2.dp).size(14.dp)
        )
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun InvestmentProfileOptionList(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            InvestmentProfileChoiceRow(
                label = label,
                selected = selected == label,
                onClick = { onSelect(label) }
            )
        }
    }
}

@Composable
private fun InvestmentProfileMultiOptionList(
    options: List<String>,
    selected: List<String>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            InvestmentProfileChoiceRow(
                label = label,
                selected = label in selected,
                onClick = { onSelect(label) }
            )
        }
    }
}

@Composable
private fun InvestmentProfileChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .quantClickable(role = QuantPressRole.Row, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
        )
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InvestmentProfileSummaryPanel(profile: InvestmentProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                profile.operatingStatement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InvestmentProfileMetricPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                InvestmentProfileMetricPill(profile.consistencyLabel, profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
            }
            InvestmentProfileSummaryRow("투자 경험", displayProfileValue(profile.experience))
            InvestmentProfileSummaryRow("투자 기간", displayProfileValue(profile.horizon))
            InvestmentProfileSummaryRow("위험 선호", displayProfileValue(profile.riskTolerance))
            InvestmentProfileSummaryRow("선호 스타일", displayProfileValue(profile.style))
            InvestmentProfileSummaryRow(
                "피하고 싶은 신호",
                if (profile.avoidances.isEmpty()) "선택 안 함" else profile.avoidances.joinToString(" · ")
            )
            InvestmentProfileSummaryRow("하락 시 행동", displayProfileValue(profile.dropResponse))
            InvestmentProfileSummaryRow("과열 시 행동", displayProfileValue(profile.overheatedResponse))
            profile.consistencyNotes.firstOrNull()?.let {
                InvestmentProfileNotice(it)
            }
        }
    }
}

@Composable
private fun InvestmentProfileSummaryRow(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(
            title,
            modifier = Modifier.width(92.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            lineHeight = 19.sp
        )
    }
}

private fun displayProfileValue(value: String): String {
    return value.ifBlank { "선택 안 함" }
}

@Composable
private fun AccountSettingRow(icon: LucideIcon, title: String, detail: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp
            )
        }
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccountNotificationCard() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var title by remember(appContext) { mutableStateOf(QubitNotificationScheduler.statusTitle(appContext)) }
    var detail by remember(appContext) { mutableStateOf(QubitNotificationScheduler.statusDetail(appContext)) }
    var enabled by remember(appContext) { mutableStateOf(QubitNotificationScheduler.isEnabled(appContext)) }
    var allowed by remember(appContext) { mutableStateOf(QubitNotificationScheduler.canPostNotifications(appContext)) }

    fun refreshNotificationStatus() {
        title = QubitNotificationScheduler.statusTitle(appContext)
        detail = QubitNotificationScheduler.statusDetail(appContext)
        enabled = QubitNotificationScheduler.isEnabled(appContext)
        allowed = QubitNotificationScheduler.canPostNotifications(appContext)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || QubitNotificationScheduler.canPostNotifications(appContext)) {
            QubitNotificationScheduler.setEnabled(appContext, true)
        }
        refreshNotificationStatus()
    }

    fun enableNotifications() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            QubitNotificationScheduler.setEnabled(appContext, true)
            refreshNotificationStatus()
        }
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    }

    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Bell,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = when {
                            enabled && !allowed -> ::openNotificationSettings
                            enabled -> {
                                {
                                    QubitNotificationScheduler.setEnabled(appContext, false)
                                    refreshNotificationStatus()
                                }
                            }
                            else -> ::enableNotifications
                        },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(
                            when {
                                enabled && !allowed -> "설정 열기"
                                enabled -> "알림 끄기"
                                else -> "알림 켜기"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (enabled && allowed) {
                        TextButton(onClick = ::openNotificationSettings, modifier = Modifier.height(38.dp)) {
                            Text("설정", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSecurityCard() {
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.ShieldCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("기기 보안", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "화면 잠금과 생체 인증을 켜두면 로그인 상태와 개인 설정을 더 안전하게 보호할 수 있습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun AccountManagementCard(onLogout: () -> Unit, onDelete: () -> Unit) {
    CardBlock {
        Text("계정 관리", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text("로그아웃", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("계정 삭제", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AccountAuthCard(
    signup: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    errorMessage: String?,
    successMessage: String?,
    loading: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (signup) "계정 만들기" else "로그인",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "관심 종목과 설정을 사용자별로 저장합니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }

            Spacer(Modifier.height(2.dp))

            if (signup) {
                AccountPillTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = "이름",
                    keyboardType = KeyboardType.Text
                )
            }
            AccountPillTextField(
                value = email,
                onValueChange = onEmailChange,
                placeholder = "이메일",
                keyboardType = KeyboardType.Email
            )
            AccountPillTextField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = "비밀번호",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(
                        onClick = onTogglePassword,
                        modifier = Modifier.size(42.dp)
                    ) {
                        LucideIconView(
                            icon = LucideIcon.Eye,
                            contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 보기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            successMessage?.let { message ->
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LucideIconView(LucideIcon.Check, contentDescription = null, tint = QuantGreen, modifier = Modifier.size(18.dp))
                    Text(message, color = QuantGreen, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = Color(0xFFF0E7F0),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("계정 확인 중", fontWeight = FontWeight.ExtraBold)
                } else {
                    Text(
                        if (signup) "가입하기" else "로그인",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(999.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    )
    val fieldBackground = if (isFocused) Color(0xFFF7EFF7) else Color(0xFFF3EAF3)
    val fieldBorder = if (isFocused) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        Color.Transparent
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done
            ),
            interactionSource = interactionSource,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 22.dp, end = if (trailing == null) 22.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF9B88A0),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        innerTextField()
                    }
                    trailing?.invoke()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(shape)
                .background(fieldBackground, shape)
                .border(width = 1.dp, color = fieldBorder, shape = shape)
        )
    }
}

@Composable
private fun AccountCreateButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(999.dp))
            .quantClickable(role = QuantPressRole.Row, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFFC8ECFF),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF102A3A)
            )
        }
    }
}

private fun matchesPortfolioIndustryQuery(query: String, ticker: String, name: String, sector: String?): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return true
    if (matches(cleanQuery, ticker, name, sector)) return true
    return portfolioIndustryLabel(ticker, name, sector)
        .lowercase(Locale.getDefault())
        .contains(cleanQuery.lowercase(Locale.getDefault()))
}

@Composable
private fun PortfolioSearchSortToolbar(
    query: String,
    onQuery: (String) -> Unit,
    sort: String,
    sortOptions: List<String>,
    onSort: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BorderlessSearchField(
            query = query,
            onQuery = onQuery,
            placeholder = "티커, 종목명, 섹터 검색",
            modifier = Modifier.weight(1f)
        )
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
                        contentDescription = "정렬",
                        modifier = Modifier.size(19.dp),
                        tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(210.dp),
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
                    sortOptions.forEach { option ->
                        SortOptionMenuRow(
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
internal fun MarketToolbar(
    market: Market,
    onMarket: (Market) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    sort: String,
    sortOptions: List<String>,
    onSort: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnalysisFilterChipRow(
            title = "시장",
            values = Market.entries.map { marketDisplayLabel(it) },
            selected = marketDisplayLabel(market),
            onSelect = { selectedTitle ->
                Market.entries.firstOrNull { marketDisplayLabel(it) == selectedTitle }?.let(onMarket)
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BorderlessSearchField(
                query = query,
                onQuery = onQuery,
                placeholder = "티커, 종목명, 섹터 검색",
                modifier = Modifier.weight(1f)
            )
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
                            contentDescription = "정렬",
                            modifier = Modifier.size(19.dp),
                            tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(210.dp),
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
                        sortOptions.forEach { option ->
                            SortOptionMenuRow(
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
}

@Composable
fun AnalysisFilterChipRow(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values, key = { it }) { value ->
                Surface(
                    modifier = Modifier
                        .clip(CircleShape)
                        .quantClickable(role = QuantPressRole.Text) { onSelect(value) },
                    color = if (selected == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) {
                    Text(
                        value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (selected == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SortOptionMenuRow(option: String, selected: Boolean, onClick: () -> Unit) {
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
                icon = sortOptionIcon(option),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tint
            )
            Text(
                option,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
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

private fun sortOptionIcon(option: String): LucideIcon {
    return when {
        "수익" in option || "상승" in option || "변동" in option -> LucideIcon.TrendingUp
        "알파벳" in option || "이름" in option || "순" in option -> LucideIcon.ListOrdered
        "가격" in option || "시총" in option || "규모" in option -> LucideIcon.LineChart
        "점수" in option || "랭킹" in option || "순위" in option -> LucideIcon.Target
        "날짜" in option || "최근" in option -> LucideIcon.CalendarClock
        else -> LucideIcon.SlidersHorizontal
    }
}

private fun marketDisplayLabel(market: Market): String {
    return newsMarketLabel(market.title).ifBlank { market.title }
}

private fun shortPortfolioDateLabel(value: String): String {
    val clean = value.trim()
    val date = Regex("""(\d{4})[-/.](\d{2})[-/.](\d{2})""").find(clean)
    return if (date != null) {
        "${date.groupValues[2]}/${date.groupValues[3]}"
    } else {
        clean.take(8)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PulseRegimeCard(macro: Map<String, String>, market: Market) {
    val regime = macro["Regime"] ?: "-"
    val score = macro["Regime_Score"] ?: "-"
    val prefix = market.title
    val color = regimeColor(regime)
    val primaryHint = regimeActionHints(regime).firstOrNull()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "오늘 시장 분위기",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        regimeDecisionTitle(regime),
                        style = MaterialTheme.typography.headlineSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        regimeDescription(regime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
                ) {
                    Text(
                        "판단 강도 $score",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            primaryHint?.let { hint ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = color.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, color.copy(alpha = 0.12f))
                    ) {
                        Text(
                            hint,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    macro["Generated"]?.take(16)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "업데이트 $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactMetricTile("저평가", macro["${prefix}_V_Weight"] ?: "-", Modifier.weight(1f), color = color)
                CompactMetricTile("추세", macro["${prefix}_M_Weight"] ?: "-", Modifier.weight(1f), color = color)
            }
        }
    }
}

@Composable
private fun MacroSignalTile(reason: RegimeReason, modifier: Modifier = Modifier) {
    val color = toneColor(signalTone(reason.signal))
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    reason.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    macroSignalBadgeText(reason.signal),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Text(
                macroSignedPercentText(reason.title, reason.value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun macroSignedPercentText(title: String, value: String): AnnotatedString {
    val coloredTitles = setOf("장기 추세", "최근 흐름", "금리 환경", "신용 시장", "신용시장")
    val match = firstSignedPercentMatch(value)
    if (title !in coloredTitles || match == null) {
        return AnnotatedString(value)
    }
    val color = when {
        match.value > 0.0 -> QuantPositive
        match.value < 0.0 -> QuantNegative
        else -> MaterialTheme.colorScheme.onSurface
    }
    return buildAnnotatedString {
        append(value)
        addStyle(SpanStyle(color = color), match.start, match.endExclusive)
    }
}

private data class SignedPercentMatch(
    val start: Int,
    val endExclusive: Int,
    val value: Double
)

private fun firstSignedPercentMatch(value: String): SignedPercentMatch? {
    val match = Regex("""[-+]?\d+(?:\.\d+)?\s*%""").find(value) ?: return null
    val percent = match.value.replace("%", "").replace(" ", "").toDoubleOrNull() ?: return null
    return SignedPercentMatch(match.range.first, match.range.last + 1, percent)
}

@Composable
internal fun HeaderCard(
    title: String,
    value: String,
    subtitle: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
    quiet: Boolean = false
) {
    val shape = RoundedCornerShape(24.dp)
    val cardModifier = if (onClick == null) {
        Modifier.fillMaxWidth()
    } else {
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .quantClickable(role = QuantPressRole.Card, onClick = onClick)
    }
    Surface(
        modifier = cardModifier,
        color = if (quiet) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = shape,
        border = BorderStroke(1.dp, if (quiet) MaterialTheme.colorScheme.outline.copy(alpha = 0.28f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = regimeColor(value))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticHeaderCard(
    title: String,
    value: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val trailingTone = statusTone(trailing)
    val trailingColor = if (trailingTone == DetailTone.Neutral) labelColor else toneColor(trailingTone)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .quantClickable(role = QuantPressRole.Card, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = labelColor)
                Text(
                    value.uppercase(Locale.US),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = toneColor(statusTone(value))
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = trailingColor)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = bodyColor.copy(alpha = 0.55f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticInfoDialog(
    info: DiagnosticInfo,
    onDismiss: () -> Unit
) {
    val accent = diagnosticAccent(info)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(accent.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = diagnosticIcon(info),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = accent
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(info.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        color = accent.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            info.status,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                ) {
                    Text(
                        info.summary,
                        modifier = Modifier.padding(15.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    info.details.forEachIndexed { index, detail ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 62.dp)
                                    .padding(13.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(accent.copy(alpha = 0.10f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (index + 1).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    detail,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = accent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LucideIconView(
                            icon = LucideIcon.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = accent
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "판단 포인트",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                diagnosticActionHint(info),
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

@Composable
private fun diagnosticAccent(info: DiagnosticInfo): Color {
    val glossaryTone = glossaryTone(info.status)
    return toneColor(glossaryTone ?: statusTone(info.status))
}

private fun diagnosticIcon(info: DiagnosticInfo): LucideIcon {
    return when (info.status) {
        "밸류에이션", "모델 전망", "스코어링", "리서치 검증" -> LucideIcon.Target
        "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀" -> LucideIcon.TrendingUp
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> LucideIcon.TriangleAlert
        "수급", "분석", "포트폴리오", "기업 규모" -> LucideIcon.LineChart
        else -> LucideIcon.Lightbulb
    }
}

private fun diagnosticActionHint(info: DiagnosticInfo): String {
    return when (info.status) {
        "밸류에이션" -> "같은 업종 평균, 성장률, 마진을 함께 보세요. 숫자가 낮아도 이익이 꺾이면 싸다고 보기 어렵습니다."
        "수익성", "퀄리티" -> "높은 값이 유지되는지, 부채나 일회성 이익으로 만들어진 값은 아닌지 같이 확인하세요."
        "성장성" -> "성장률만 보지 말고 마진과 현금흐름이 같이 좋아지는지 확인하면 판단이 더 안전합니다."
        "현금흐름" -> "회계상 이익보다 실제 남는 현금에 가깝기 때문에 배당, 자사주, 재투자 여력을 볼 때 유용합니다."
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> "높은 값은 주가 흔들림이나 재무 부담을 키울 수 있습니다. 수익 신호가 좋아도 비중 판단에 반영하세요."
        "모델 전망", "AI 보정", "스코어링", "리서치 검증" -> "단독 매수 신호가 아니라 종목 간 우선순위를 정하는 보조 신호로 읽는 것이 좋습니다."
        "수급", "실적 모멘텀" -> "가격 반응과 거래량이 같은 방향인지 확인하세요. 이미 반영된 뉴스일 수도 있습니다."
        "기업 규모" -> "대형주는 안정성, 소형주는 성장성과 변동성을 같이 봐야 합니다. 같은 규모군 안에서 비교하면 더 정확합니다."
        else -> "${info.title}은 단독으로 결론을 내기보다 가격, 성장, 리스크 지표와 함께 비교해 보세요."
    }
}

private fun glossaryTone(status: String): DetailTone? {
    return when (status) {
        "밸류에이션", "모델 전망", "AI 보정", "스코어링", "리서치 검증" -> DetailTone.Primary
        "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀" -> DetailTone.Positive
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> DetailTone.Warning
        "수급", "분석", "포트폴리오", "기업 규모" -> DetailTone.Primary
        else -> null
    }
}

@Composable
private fun RegimeExplanationSheet(
    macro: Map<String, String>,
    usMeta: Map<String, String>,
    krMeta: Map<String, String>
) {
    val regime = macro["Regime"] ?: usMeta["Regime"] ?: krMeta["Regime"] ?: "NEUTRAL"
    val score = macro["Regime_Score"] ?: "-"
    val reasons = regimeReasons(macro)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("시장 흐름 판단", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    regimeTitle(regime),
                    style = MaterialTheme.typography.headlineSmall,
                    color = regimeColor(regime),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    regimeDescription(regime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("판단 방식", fontWeight = FontWeight.Bold)
                    Text(
                        "5개 시장 신호가 각각 +1, 0, -1점을 만들고 합산 점수가 +2 이상이면 위험선호, -2 이하면 위험회피, 그 사이는 중립으로 봅니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("현재 합산 점수: $score", color = regimeColor(regime), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(reasons) { reason ->
            RegimeReasonRow(reason)
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("모델에 반영되는 방식", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        factorWeightText(macro),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RegimeReasonRow(reason: RegimeReason) {
    val tone = signalTone(reason.signal)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = toneColor(tone).copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, toneColor(tone).copy(alpha = 0.14f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reason.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(signalText(reason.signal), color = toneColor(tone), fontWeight = FontWeight.Bold)
            }
            Text(reason.value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(reason.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PortfolioRankingSectionTitle(title: String = "기업 순위") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "1개월 수익률",
            modifier = Modifier
                .width(88.dp)
                .padding(end = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

@Composable
private fun PortfolioRankingRow(
    rankLabel: String,
    stock: PortfolioStock,
    profile: InvestmentProfile,
    currency: String,
    currentPrice: Double?,
    return1M: Double?,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onOpen: () -> Unit
) {
    val personal = remember(profile, stock) { personalizedStockInterpretation(profile, stock) }
    PortfolioCompanyRow(
        rankLabel = rankLabel,
        rankChange = stock.rankChange,
        rankStatus = stock.rankStatus,
        ticker = stock.ticker,
        market = stock.market,
        name = stock.name,
        sectorLabel = portfolioIndustryLabel(stock.ticker, stock.name, stock.sector),
        personalLine = "${personal.label} · ${personal.headline}",
        priceText = portfolioPriceText(currentPrice, currency),
        return1M = return1M,
        source = stock.source ?: stock.lastUpdated?.let { "storage" },
        updatedAt = stock.lastUpdated ?: stock.generatedAt,
        comparisonMode = comparisonMode,
        comparisonSelected = comparisonSelected,
        comparisonDisabled = comparisonDisabled,
        onOpen = onOpen
    )
}

@Composable
private fun RankMovementBadge(change: Int?, status: String?) {
    val normalized = status?.lowercase(Locale.US)
    val text = when {
        normalized == "new" -> "신규"
        change == null -> null
        change > 0 -> "▲$change"
        change < 0 -> "▼${abs(change)}"
        else -> null
    } ?: return
    val color = when {
        normalized == "new" -> MaterialTheme.colorScheme.primary
        change != null && change > 0 -> QuantPositive
        change != null && change < 0 -> QuantNegative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

@Composable
internal fun SmallCapRankingRow(
    rankLabel: String,
    stock: SmallCapStock,
    profile: InvestmentProfile,
    currentPrice: Double?,
    return1M: Double?,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onOpen: () -> Unit
) {
    val currency = marketCurrency(stock.ticker, stock.market)
    val personalLine = remember(profile, stock) { smallCapPersonalLine(stock, profile) }
    PortfolioCompanyRow(
        rankLabel = rankLabel,
        rankChange = stock.rankChange,
        rankStatus = stock.rankStatus,
        ticker = stock.ticker,
        market = stock.market,
        name = stock.name,
        sectorLabel = stock.market ?: "스몰캡",
        personalLine = personalLine,
        priceText = portfolioPriceText(currentPrice, currency),
        return1M = return1M,
        source = stock.source ?: stock.lastUpdated?.let { "storage" },
        updatedAt = stock.lastUpdated ?: stock.generatedAt,
        comparisonMode = comparisonMode,
        comparisonSelected = comparisonSelected,
        comparisonDisabled = comparisonDisabled,
        onOpen = onOpen
    )
}

private fun smallCapPersonalLine(stock: SmallCapStock, profile: InvestmentProfile): String {
    if (!profile.isConfigured) return "기준 설정 필요 · 스몰캡은 점수와 거래량을 함께 비교"
    val headline = profile.headline.ifBlank { "내 기준" }
    val revGrowth = listPercentMagnitude(stock.revGrowth)
    val return1M = listPercentMagnitude(stock.return1M)
    val volumeSurge = stock.volumeSurge ?: 1.0
    return when {
        (profile.riskTolerance.contains("안정") || profile.riskTolerance.contains("보수") || profile.riskTolerance.contains("낮")) &&
            (volumeSurge >= 2.0 || return1M >= 12.0) -> "$headline 기준 · 변동성 먼저 제한"
        profile.style.contains("성장") && revGrowth >= 15.0 -> "$headline 기준 · 성장 근거 확인"
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) >= 0.12 || (stock.fcfMargin ?: 0.0) >= 0.06) -> "$headline 기준 · 퀄리티 근거 확인"
        profile.style.contains("모멘텀") && volumeSurge >= 1.8 -> "$headline 기준 · 과열 여부 확인"
        else -> "$headline 기준 · 비교 후 관찰"
    }
}

private fun listPercentMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
private fun PortfolioCompanyRow(
    rankLabel: String,
    rankChange: Int?,
    rankStatus: String?,
    ticker: String,
    market: String?,
    name: String,
    sectorLabel: String,
    personalLine: String? = null,
    priceText: String,
    return1M: Double?,
    source: String?,
    updatedAt: String?,
    comparisonMode: Boolean,
    comparisonSelected: Boolean,
    comparisonDisabled: Boolean,
    onOpen: () -> Unit
) {
    val namePriceGap = if (isKoreanTicker(ticker, market)) {
        PortfolioListNamePriceGap
    } else {
        PortfolioListUsNamePriceGap
    }
    val rowShape = RoundedCornerShape(24.dp)
    val accessibilitySummary = remember(
        rankLabel,
        name,
        priceText,
        return1M,
        personalLine,
        comparisonMode,
        comparisonSelected
    ) {
        listOf(
            "${rankLabel}위",
            name,
            "가격 $priceText",
            "1개월 수익률 ${pct(return1M)}",
            personalLine.orEmpty(),
            if (comparisonMode) {
                if (comparisonSelected) "비교 선택됨" else "비교 선택 가능"
            } else {
                "상세 보기"
            }
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = PortfolioListRowMinHeight)
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface)
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibilitySummary
                onClick(label = "상세 보기") {
                    onOpen()
                    true
                }
            }
            .padding(horizontal = 12.dp, vertical = PortfolioListVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.width(32.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                rankLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            RankMovementBadge(rankChange, rankStatus)
        }

        TickerAvatar(ticker, market, size = PortfolioListLogoSize)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(namePriceGap)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PortfolioSectorChip(sectorLabel)
                AnimatedPriceText(
                    text = priceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            personalLine?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier.width(if (comparisonMode) 104.dp else 84.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    pct(return1M),
                    style = MaterialTheme.typography.titleMedium,
                    color = return1M?.takeIf { it.isFinite() }?.let { marketMoveColor(it) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
                if (comparisonMode) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = if (comparisonSelected) "비교 선택됨" else "비교 선택",
                        tint = if (comparisonSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (comparisonDisabled) 0.34f else 0.58f)
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
        }
    }
}

@Composable
private fun PortfolioSectorChip(label: String) {
    Text(
        label.take(14).ifBlank { "분류 없음" },
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

fun portfolioPriceText(value: Double?, currency: String): String {
    if (value == null || !value.isFinite()) return "-"
    return if (currency == "KRW") {
        "${groupedInteger(value.roundToLong())}원"
    } else {
        fmtPx(value, currency)
    }
}

@Composable
internal fun StockRow(
    rankLabel: String? = null,
    title: String,
    ticker: String,
    market: String?,
    subtitle: String,
    headline: String,
    kpis: List<Pair<String, String>>,
    watched: Boolean,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onCompare: (() -> Unit)? = null,
    onWatch: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = onOpen)
            .clip(RoundedCornerShape(24.dp)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rankLabel?.let { RankBadge(it) }
                TickerAvatar(ticker, market)
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$ticker · $subtitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    headline,
                    modifier = Modifier.width(62.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
                FavoriteButton(watched, onWatch)
                if (onCompare != null) {
                    val compareLabel = if (comparisonSelected) "$title 비교 목록에 추가됨" else "$title 비교 목록에 추가"
                    IconButton(
                        onClick = onCompare,
                        modifier = Modifier
                            .size(34.dp)
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = compareLabel
                                onClick(label = compareLabel) {
                                    onCompare()
                                    true
                                }
                            }
                    ) {
                        Icon(
                            imageVector = if (comparisonSelected) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.CompareArrows,
                            contentDescription = null,
                            tint = if (comparisonSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (comparisonMode) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = if (comparisonSelected) "비교 선택됨" else "비교 선택",
                        tint = if (comparisonSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (comparisonDisabled) 0.34f else 0.58f)
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            if (kpis.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.width(if (rankLabel == null) 0.dp else 34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        kpis.forEach { (label, value) -> Kpi(label, value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankBadge(label: String) {
    Text(
        label,
        modifier = Modifier.width(24.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

private enum class DetailContentTab(val label: String, val key: String) {
    Overview("요약", "overview"),
    Chart("차트", "chart"),
    Holdings("구성종목", "holdings"),
    Financial("재무", "financial"),
    Data("데이터", "data")
}

private fun preferredDetailTab(key: String): DetailContentTab {
    return DetailContentTab.entries.firstOrNull { it.key == key.lowercase() } ?: DetailContentTab.Overview
}

@Composable
private fun DetailTabSelector(
    selected: DetailContentTab,
    tabs: List<DetailContentTab> = DetailContentTab.entries.toList(),
    onSelect: (DetailContentTab) -> Unit
) {
    SoftSegmentSwitch(
        options = tabs.map { it.label },
        selected = selected.label,
        onSelect = { label ->
            tabs.firstOrNull { it.label == label }?.let(onSelect)
        }
    )
}

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
private fun DetailTopDecisionCard(
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

private val DetailPriceMetricLabels = setOf("현재가", "최근가", "가격", "Price", "Last Price")
private val DetailTodayMetricLabels = setOf("오늘", "전장", "당일 흐름", "하루 변동률", "일간", "일일", "1D", "Today", "Daily")

private fun detailMetricLabelMatches(label: String, candidates: Set<String>): Boolean {
    val clean = label.trim().lowercase(Locale.US)
    return candidates.any { candidate ->
        val target = candidate.trim().lowercase(Locale.US)
        clean == target || clean.contains(target)
    }
}

private fun DetailRequest.reconciledWithDetail(detail: StockDetail?): DetailRequest {
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

private fun detailDailyChangePct(info: StockInfo, current: Double): Double? {
    info.dailyChangePct?.takeIf { it.isFinite() }?.let { return it }
    return info.prevClose
        ?.takeIf { it.isFinite() && it != 0.0 }
        ?.let { current / it - 1.0 }
}

private fun detailDailyChangeAmount(info: StockInfo, current: Double, changePct: Double?): Double? {
    info.prevClose?.takeIf { it.isFinite() }?.let { return current - it }
    val safeChange = changePct?.takeIf { it.isFinite() && it > -1.0 } ?: return null
    val previous = current / (1.0 + safeChange)
    return current - previous
}

@Composable
private fun DetailHoldingsCard(
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
private fun DetailHoldingDonutChart(
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
private fun DetailHoldingLegendRow(
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
private fun DetailHoldingLegendRow(
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

private val DetailHoldingPalette = listOf(
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

private val DetailHoldingOtherColor = Color(0xFFD2D8E0)

private fun holdingMarket(ticker: String): String? {
    return when {
        ticker.endsWith(".KS", ignoreCase = true) || ticker.endsWith(".KQ", ignoreCase = true) -> "KR"
        ticker.all { it.isLetter() } -> "US"
        else -> null
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun DetailPriceMini(
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
private fun DetailConclusionActionBar(
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

private data class DetailConclusion(
    val title: String,
    val detail: String,
    val badge: String,
    val tone: DetailTone
)

private fun detailConclusionIcon(conclusion: DetailConclusion): LucideIcon {
    return when {
        conclusion.badge == "ETF" -> LucideIcon.PieChart
        conclusion.tone == DetailTone.Positive -> LucideIcon.TrendingUp
        conclusion.tone == DetailTone.Negative -> LucideIcon.TriangleAlert
        conclusion.tone == DetailTone.Warning -> LucideIcon.TriangleAlert
        conclusion.tone == DetailTone.Primary -> LucideIcon.Target
        else -> LucideIcon.Activity
    }
}

private fun detailConclusion(request: DetailRequest, detail: StockDetail?): DetailConclusion {
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
private fun DetailHeroCard(request: DetailRequest, detail: StockDetail?, error: String?) {
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
private fun DetailSummaryCard(
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
private fun DetailSummaryTile(
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
private fun TermLabel(label: String, termKey: String?, onTermClick: (String) -> Unit) {
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
private fun GlossaryCard(keys: List<String>, onTermClick: (String) -> Unit) {
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
private fun DataTrustCard(source: String?, updatedAt: String?) {
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
private fun StatusPill(text: String, tone: DetailTone) {
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
private fun DetailErrorState(message: String, onRetry: () -> Unit) {
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
private fun StockDetailSkeleton(request: DetailRequest) {
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
private fun SkeletonLine(
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
private fun PriceHeaderCard(request: DetailRequest, info: StockInfo?) {
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
private fun RangeCard(info: StockInfo, currency: String) {
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
private fun CompanyProfileCard(info: StockInfo) {
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
private fun MarketInfoCard(info: StockInfo, currency: String, onTermClick: (String) -> Unit = {}) {
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
private fun FinancialSnapshotCard(info: StockInfo, currency: String, onTermClick: (String) -> Unit = {}) {
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
private fun ReturnStatsCard(points: List<PricePoint>, currency: String, onTermClick: (String) -> Unit = {}) {
    val metrics = remember(points, currency) { returnMetrics(points, currency) }
    if (metrics.isEmpty()) return
    MetricSection(DetailSection("기간 수익률 / 리스크", metrics), onTermClick)
}

@Composable
private fun FactorRadarCard(factors: List<FactorScore>) {
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
private fun SignalCards(signals: List<DetailSignal>) {
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
private fun MetricSection(section: DetailSection, onTermClick: (String) -> Unit = {}) {
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
private fun MetricTile(metric: DetailMetric, modifier: Modifier = Modifier, onTermClick: (String) -> Unit = {}) {
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
private fun PriceChart(
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

private fun chartVisiblePoints(points: List<PricePoint>, period: ChartPeriod): List<PricePoint> {
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

private fun aggregateChartPointChunks(points: List<PricePoint>, chunkSize: Int): List<PricePoint> {
    if (chunkSize <= 1 || points.isEmpty()) return points
    return points.chunked(chunkSize).mapNotNull(::aggregateChartBucket)
}

private fun aggregateChartPoints(
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

private fun aggregateChartBucket(bucket: List<PricePoint>): PricePoint? {
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

private fun weeklyChartBucket(point: PricePoint): String {
    val date = parseChartDate(point.date) ?: return point.date
    return date.chartWeekStart().toString()
}

private fun threeWeekChartBucket(point: PricePoint, firstWeekStart: LocalDate): String {
    val date = parseChartDate(point.date) ?: return point.date
    val weekOffset = ChronoUnit.WEEKS.between(firstWeekStart, date.chartWeekStart()).coerceAtLeast(0L)
    return "W3-${weekOffset / 3L}"
}

private fun monthlyChartBucket(point: PricePoint): String {
    val date = parseChartDate(point.date) ?: return point.date
    return date.withDayOfMonth(1).toString()
}

private fun parseChartDate(value: String): LocalDate? {
    return try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun LocalDate.chartWeekStart(): LocalDate {
    return minusDays((dayOfWeek.value - 1).toLong())
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChartRangeSummaryRow(
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
private fun ChartSummaryPill(label: String, value: String) {
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
private fun PeriodModeSelector(
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
private fun PeriodChip(
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
private fun ChartModeGlyph(mode: ChartMode, color: Color) {
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
private fun IndicatorLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun movingAverage(points: List<PricePoint>, window: Int): List<Double?> {
    if (window <= 0) return List(points.size) { null }
    var sum = 0.0
    return points.indices.map { index ->
        sum += points[index].close
        if (index >= window) sum -= points[index - window].close
        val count = min(index + 1, window)
        sum / count
    }
}

private fun bollingerBands(points: List<PricePoint>, window: Int): List<Pair<Double, Double>?> {
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

private fun rsiSeries(points: List<PricePoint>, window: Int): List<Double?> {
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

private fun shortChartDate(date: String): String {
    val parts = date.split("-")
    return if (parts.size >= 3) "${parts[1]}/${parts[2]}" else date
}

@Composable
internal fun CardBlock(
    modifier: Modifier = Modifier,
    useBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    QuantCard(
        modifier = modifier,
        role = if (useBorder) QuantCardRole.Information else QuantCardRole.Status,
        padding = 16.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun TickerAvatar(ticker: String, market: String?, size: Dp = 34.dp) {
    val symbol = remember(ticker) { shortTicker(ticker).uppercase(Locale.US) }
    val localLogo = remember(symbol) { localCompanyLogo(symbol) }
    val logoKey = remember(ticker, market) { "${market.orEmpty()}:$symbol" }
    val logoUrls = remember(ticker, market) {
        CompanyLogoMemoryCache.candidates(logoKey, companyLogoUrls(ticker, market))
    }
    var logoIndex by remember(logoUrls) { mutableStateOf(0) }
    val logoUrl = logoUrls.getOrNull(logoIndex)
    val shape = CircleShape
    Box(
        Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (localLogo != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = localLogo.background,
                shape = shape,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))
            ) {
                Image(
                    painter = painterResource(localLogo.resId),
                    contentDescription = "$ticker logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(size * localLogo.paddingFraction)
                        .clip(shape),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (logoUrl == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        shortTicker(ticker).take(2),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shape = shape
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = "$ticker logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(size * 0.04f)
                        .clip(shape),
                    contentScale = ContentScale.Fit,
                    onSuccess = {
                        CompanyLogoMemoryCache.markSuccess(logoKey, logoUrl)
                    },
                    onError = {
                        CompanyLogoMemoryCache.markFailure(logoUrl)
                        logoIndex += 1
                    }
                )
            }
        }
    }
}

@Composable
fun MarketIndicatorLogoView(
    ticker: String,
    name: String,
    size: Dp = 34.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    val label = remember(ticker, name) { marketIndicatorLogoText(ticker, name) }
    val fontSize = when (label.length) {
        in 0..3 -> 12.sp
        in 4..5 -> 9.sp
        else -> 7.sp
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = tint.copy(alpha = 0.11f),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 3.dp),
                color = tint,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

private data class LocalCompanyLogo(
    val resId: Int,
    val background: Color,
    val paddingFraction: Float = 0.08f
)

private fun localCompanyLogo(symbol: String): LocalCompanyLogo? {
    return null
}

private object CompanyLogoMemoryCache {
    private val preferredUrls = mutableMapOf<String, String>()
    private val failedUrls = mutableSetOf<String>()

    fun candidates(key: String, urls: List<String>): List<String> {
        preferredUrls[key]?.let { preferred ->
            if (preferred in urls) return listOf(preferred)
        }
        return urls.filterNot { it in failedUrls }
    }

    fun markSuccess(key: String, url: String) {
        preferredUrls[key] = url
    }

    fun markFailure(url: String) {
        failedUrls += url
    }
}

@Composable
private fun FavoriteButton(watched: Boolean, onClick: () -> Unit) {
    val tint = if (watched) QuantFavorite else QuantMuted
    val favoriteLabel = if (watched) "관심 종목 제거" else "관심 종목 추가"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (watched) QuantFavorite.copy(alpha = 0.14f) else Color.Transparent)
            .quantClickable(role = QuantPressRole.Icon, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = favoriteLabel
                onClick(label = favoriteLabel) {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (watched) 27.dp else 25.dp)
        )
    }
}

@Composable
private fun Kpi(label: String, value: String) {
    val valueColor = when {
        value.startsWith("+") -> QuantPositive
        value.startsWith("-") -> QuantNegative
        value.startsWith("x") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
}

@Composable
internal fun SectionTitle(title: String, count: String) {
    Row(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (count.isNotBlank()) Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyCard(
    title: String,
    message: String,
    icon: ImageVector? = null,
    lucideIcon: LucideIcon? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val resolvedMessage = if (message.isBlank()) {
        "필터를 바꾸거나 새로고침하면 다시 확인할 수 있습니다."
    } else {
        message
    }
    CardBlock {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lucideIcon != null || icon != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = CircleShape
                ) {
                    if (lucideIcon != null) {
                        LucideIconView(
                            icon = lucideIcon,
                            contentDescription = null,
                            modifier = Modifier.padding(11.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.padding(11.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    resolvedMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
                if (actionLabel != null && onAction != null) {
                    OutlinedButton(
                        onClick = onAction,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        LucideIconView(
                            icon = LucideIcon.RefreshCw,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

internal fun listEmptyMessage(
    query: String,
    emptyDataMessage: String,
    filteredMessage: String
): String {
    val clean = query.trim()
    return if (clean.isBlank()) {
        "$emptyDataMessage 큐빗은 모든 종목을 얕게 보여주지 않고, 분석 가능한 기업만 깊게 봅니다. 새로고침 후에도 계속 비어 있으면 서버 데이터 생성을 확인해주세요."
    } else {
        "\"$clean\"는 아직 큐빗 커버리지 밖일 수 있습니다. 데이터 품질과 추적 기준을 통과한 기업만 먼저 보여줍니다. $filteredMessage"
    }
}

@Composable
private fun WatchlistSyncBanner(
    message: String,
    syncing: Boolean,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        color = if (syncing) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.74f)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (syncing) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (syncing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
            )
            if (!syncing) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("재시도")
                }
            }
        }
    }
}

@Composable
fun LoadingSurface(message: String, detail: String? = null) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.82f),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        message,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .padding(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            message,
            Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun <T> compareByFor(sort: String, selector: (T) -> Double?): Comparator<T> {
    return Comparator { left, right ->
        val l = selector(left)
        val r = selector(right)
        if (sort == "Rank" || sort == "랭킹") {
            (l ?: Double.POSITIVE_INFINITY).compareTo(r ?: Double.POSITIVE_INFINITY)
        } else {
            (r ?: Double.NEGATIVE_INFINITY).compareTo(l ?: Double.NEGATIVE_INFINITY)
        }
    }
}

private fun portfolioKpis(stock: PortfolioStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.weight?.let { "비중" to pct(it, signed = false) },
        stock.expectedReturn?.let { "기대" to pct(it) },
        stock.revGrowth?.let { "성장" to pct(it) }
    )
}

private fun smallCapKpis(stock: SmallCapStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.revGrowth?.let { "성장" to pct(it) },
        stock.fcfMargin?.let { "FCF" to pct(it, signed = false) },
        stock.volumeSurge?.let { "거래량" to "x%.1f".format(it) }
    )
}

internal fun bestScoredValue(stock: ScoredStock): Double? {
    return stock.combinedScore ?: stock.finalScore ?: stock.totalScore ?: stock.scoreNeutral
}

private fun holdingDetail(holding: DetailHolding): DetailRequest {
    val ticker = fallbackHoldingTicker(holding)
    val market = holdingMarket(ticker, holding.name)
    val currency = marketCurrency(ticker, market)
    val displayName = holding.name.ifBlank { ticker }
    return DetailRequest(
        ticker = ticker,
        name = displayName,
        currency = currency,
        market = market,
        sections = listOf(
            DetailSection(
                "ETF 구성종목",
                listOf(
                    DetailMetric("ETF 내 비중", pct(holding.weight, signed = false), DetailTone.Primary),
                    DetailMetric("시장", market)
                )
            )
        ),
        signals = listOf(
            DetailSignal(
                "ETF 구성종목",
                "$displayName 종목이 이 ETF에 ${pct(holding.weight, signed = false)} 비중으로 포함되어 있습니다.",
                DetailTone.Primary
            )
        ),
        factors = emptyList()
    )
}

internal fun scoredDetail(stock: ScoredStock): DetailRequest {
    val currency = marketCurrency(stock.ticker, stock.market)
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "팩터 점수",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("최종 점수", bestScoredValue(stock)?.let { "%.3f".format(it) } ?: "-", scoreTone(bestScoredValue(stock), 0.7, 0.45)),
                    DetailMetric("AI 보정", num(stock.mlScore), scoreTone(stock.mlScore, 0.7, 0.45)),
                    DetailMetric("Value", num(stock.valueScore), scoreTone(stock.valueScore, 0.7, 0.45)),
                    DetailMetric("Quality", num(stock.qualityScore), scoreTone(stock.qualityScore, 0.7, 0.45)),
                    DetailMetric("Momentum", num(stock.momentumScore), scoreTone(stock.momentumScore, 0.7, 0.45)),
                    DetailMetric("중립화 점수", num(stock.scoreNeutral))
                )
            ),
            DetailSection(
                "기초 지표",
                listOf(
                    DetailMetric("ROIC", pct(stock.roic, signed = false), ratioTone(stock.roic, 0.15, 0.05)),
                    DetailMetric("매출성장", pct(stock.revGrowth), ratioTone(stock.revGrowth, 0.15, 0.0)),
                    DetailMetric("매출총이익률", pct(stock.grossMargin, signed = false), ratioTone(stock.grossMargin, 0.40, 0.20)),
                    DetailMetric("FCF 마진", pct(stock.fcfMargin, signed = false), ratioTone(stock.fcfMargin, 0.10, 0.0)),
                    DetailMetric("Debt/EBITDA", stock.debtEbitda?.let { "%.2fx".format(it) } ?: "-", inverseTone(stock.debtEbitda, 2.0, 4.0)),
                    DetailMetric("PEG", stock.peg?.let { "%.2f".format(it) } ?: "-")
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: if (currency == "KRW") "KR" else "US"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.valueScore ?: 0.0) >= 0.7) DetailSignal("가치 팩터 우수", "Value 점수가 높아 저평가 매력이 상대적으로 큽니다.", DetailTone.Positive) else null,
            if ((stock.qualityScore ?: 0.0) >= 0.7) DetailSignal("퀄리티 팩터 우수", "수익성과 재무 품질이 상대적으로 강합니다.", DetailTone.Positive) else null,
            if ((stock.momentumScore ?: 0.0) >= 0.7) DetailSignal("모멘텀 팩터 우수", "가격 또는 이익 모멘텀 신호가 강합니다.", DetailTone.Primary) else null,
            if ((stock.mlScore ?: 0.0) >= 0.7) DetailSignal("AI 보정 우호", "예측 보정 점수가 같은 유니버스 안에서 상위권입니다.", DetailTone.Primary) else null
        ).ifEmpty {
            listOf(DetailSignal("팩터 점수 확인", "${stock.name}의 V/Q/M 팩터를 함께 비교해보세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("Value", scaleScore(stock.valueScore, 1.0)),
            FactorScore("Quality", scaleScore(stock.qualityScore, 1.0)),
            FactorScore("Momentum", scaleScore(stock.momentumScore, 1.0)),
            FactorScore("ML", scaleScore(stock.mlScore, 1.0)),
            FactorScore("ROIC", scaleRatio(stock.roic, 0.35)),
            FactorScore("성장", scaleSignedRatio(stock.revGrowth, 0.50)),
            FactorScore("마진", scaleRatio(stock.grossMargin, 0.80))
        )
    )
}

fun portfolioDetail(stock: PortfolioStock, currency: String): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "퀀트 점수",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("종합 점수", score(stock.totalScore), scoreTone(stock.totalScore, 0.7, 0.45)),
                    DetailMetric("비중", pct(stock.weight, signed = false), DetailTone.Primary),
                    DetailMetric("기대수익률", pct(stock.expectedReturn), returnTone(stock.expectedReturn))
                )
            ),
            DetailSection(
                "퀄리티 / 성장",
                listOf(
                    DetailMetric("ROIC", pct(stock.roic, signed = false), ratioTone(stock.roic, 0.15, 0.05)),
                    DetailMetric("매출성장", pct(stock.revGrowth), ratioTone(stock.revGrowth, 0.15, 0.0)),
                    DetailMetric("매출총이익률", pct(stock.grossMargin, signed = false), ratioTone(stock.grossMargin, 0.40, 0.20)),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: if (currency == "KRW") "KR" else "US"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("업데이트", formattedUpdateTimestamp(stock.lastUpdated))
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.totalScore ?: 0.0) >= 0.7) DetailSignal("상위 팩터 점수", "종합 점수가 높아 현재 분석 후보군에서 우선순위가 높습니다.", DetailTone.Primary) else null,
            if ((stock.roic ?: 0.0) >= 0.15) DetailSignal("높은 ROIC", "자본 효율성이 좋아 퀄리티 팩터에 긍정적입니다.", DetailTone.Positive) else null,
            if ((stock.revGrowth ?: 0.0) >= 0.15) DetailSignal("매출 성장", "매출 성장률이 높아 성장 모멘텀이 확인됩니다.", DetailTone.Positive) else null,
            if ((stock.expectedReturn ?: 0.0) < 0.0) DetailSignal("기대수익률 주의", "모델 기대수익률이 음수라 진입 타이밍을 확인해야 합니다.", DetailTone.Warning) else null
        ).ifEmpty {
            listOf(DetailSignal("추가 확인 필요", "${stock.name}의 핵심 팩터가 중립적입니다. 차트와 상세 지표를 함께 확인하세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("점수", scaleScore(stock.totalScore, 1.0)),
            FactorScore("ROIC", scaleRatio(stock.roic, 0.35)),
            FactorScore("성장", scaleRatio(stock.revGrowth, 0.50)),
            FactorScore("마진", scaleRatio(stock.grossMargin, 0.80)),
            FactorScore("기대수익", scaleSignedRatio(stock.expectedReturn, 0.25)),
            FactorScore("비중", scaleRatio(stock.weight, 0.15))
        )
    )
}

fun smallCapDetail(stock: SmallCapStock, currency: String): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "스몰캡 점수",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("종합 점수", stock.totalScore?.let { "%.0f".format(it) } ?: "-", scoreTone(stock.totalScore, 70.0, 45.0)),
                    DetailMetric("소형주 보너스", stock.smallCapBonus?.let { "%.1f".format(it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            ),
            DetailSection(
                "성장 / 수익성",
                listOf(
                    DetailMetric("매출성장", pct(stock.revGrowth), ratioTone(stock.revGrowth, 0.20, 0.0)),
                    DetailMetric("성장가속", pct(stock.revAccel), ratioTone(stock.revAccel, 0.0, -0.05)),
                    DetailMetric("ROIC", pct(stock.roic, signed = false), ratioTone(stock.roic, 0.15, 0.05)),
                    DetailMetric("매출총이익률", pct(stock.grossMargin, signed = false), ratioTone(stock.grossMargin, 0.40, 0.20))
                )
            ),
            DetailSection(
                "현금흐름 / 리스크",
                listOf(
                    DetailMetric("FCF 마진", pct(stock.fcfMargin, signed = false), ratioTone(stock.fcfMargin, 0.10, 0.0)),
                    DetailMetric("Debt/EBITDA", stock.debtEbitda?.let { "%.2fx".format(it) } ?: "-", inverseTone(stock.debtEbitda, 2.0, 4.0)),
                    DetailMetric("거래량 서지", stock.volumeSurge?.let { "x%.1f".format(it) } ?: "-", scoreTone(stock.volumeSurge, 1.5, 1.0)),
                    DetailMetric("업데이트", formattedUpdateTimestamp(stock.lastUpdated))
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.totalScore ?: 0.0) >= 70.0) DetailSignal("SmallCap 상위 점수", "소형주 스캐너 기준으로 종합 매력이 높습니다.", DetailTone.Primary) else null,
            if ((stock.revAccel ?: 0.0) > 0.0) DetailSignal("성장 가속", "매출 성장의 가속 신호가 있어 추가 관찰 가치가 있습니다.", DetailTone.Positive) else null,
            if ((stock.volumeSurge ?: 0.0) >= 1.5) DetailSignal("거래량 증가", "평소보다 거래량이 커져 시장 관심이 붙고 있습니다.", DetailTone.Primary) else null,
            if ((stock.debtEbitda ?: 0.0) > 4.0) DetailSignal("부채 부담", "Debt/EBITDA가 높아 재무 리스크 확인이 필요합니다.", DetailTone.Warning) else null
        ).ifEmpty {
            listOf(DetailSignal("추가 확인 필요", "${stock.name}의 핵심 팩터가 중립적입니다. 차트와 상세 지표를 함께 확인하세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("수익성", scaleRatio(stock.roic, 0.35)),
            FactorScore("성장성", scaleRatio(stock.revGrowth, 0.60)),
            FactorScore("성장가속", scaleSignedRatio(stock.revAccel, 0.25)),
            FactorScore("현금창출", scaleRatio(stock.fcfMargin, 0.30)),
            FactorScore("마진", scaleRatio(stock.grossMargin, 0.80)),
            FactorScore("재무건전성", scaleInverse(stock.debtEbitda, 5.0))
        )
    )
}

fun earningsDetail(stock: EarningsStock): DetailRequest {
    val currency = marketCurrency(stock.ticker, null)
    val market = if (currency == "KRW") "KR" else "US"
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = market,
        sections = listOf(
            DetailSection(
                "실적 모멘텀",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("시그널", stock.signalStrength?.let { "%.2f".format(it) } ?: "-", scoreTone(stock.signalStrength, 1.0, 0.0)),
                    DetailMetric("EPS 서프라이즈", pct(stock.surprisePct), returnTone(stock.surprisePct)),
                    DetailMetric("발표 후 수익", pct(stock.returnSince), returnTone(stock.returnSince))
                )
            ),
            DetailSection(
                "이벤트 정보",
                listOf(
                    DetailMetric("발표일", stock.earningsDate ?: "-"),
                    DetailMetric("경과일", stock.daysSince?.let { "${it.toInt()}일" } ?: "-", inverseTone(stock.daysSince, 7.0, 30.0)),
                    DetailMetric("거래량 서지", stock.volumeSurge?.let { "x%.1f".format(it) } ?: "-", scoreTone(stock.volumeSurge, 1.5, 1.0)),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시장", market),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-")
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.surprisePct ?: 0.0) > 0.0) DetailSignal("실적 서프라이즈", "예상보다 좋은 실적이 확인되어 단기 모멘텀에 긍정적입니다.", DetailTone.Positive) else null,
            if ((stock.returnSince ?: 0.0) > 0.0) DetailSignal("발표 후 주가 반응", "실적 발표 이후 수익률이 플러스로 유지되고 있습니다.", DetailTone.Positive) else null,
            if ((stock.signalStrength ?: 0.0) >= 1.0) DetailSignal("강한 시그널", "서프라이즈와 가격 반응이 함께 나타난 후보입니다.", DetailTone.Primary) else null,
            if ((stock.daysSince ?: 999.0) <= 7.0) DetailSignal("최근 이벤트", "실적 발표가 최근에 발생해 정보 반영 과정을 볼 만합니다.", DetailTone.Primary) else null
        ).ifEmpty {
            listOf(DetailSignal("추가 확인 필요", "${stock.name}의 실적 이벤트 신호가 중립적입니다. 가격 반응과 상세 지표를 함께 확인하세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("시그널", scaleRatio(stock.signalStrength, 2.0)),
            FactorScore("서프라이즈", scaleSignedRatio(stock.surprisePct, 0.25)),
            FactorScore("수익반응", scaleSignedRatio(stock.returnSince, 0.25)),
            FactorScore("신선도", scaleInverse(stock.daysSince, 45.0)),
            FactorScore("거래량", scaleRatio(stock.volumeSurge, 3.0)),
            FactorScore("규모", scaleRatio(stock.marketCap?.let { min(it / 1e11, 1.0) }, 1.0))
        )
    )
}

fun earningsCalendarDetail(item: EarningsCalendarItem): DetailRequest {
    val currency = marketCurrency(item.ticker, item.market)
    return DetailRequest(
        ticker = item.ticker,
        name = item.name,
        currency = currency,
        market = item.market,
        sections = listOf(
            DetailSection(
                "어닝 캘린더",
                listOf(
                    DetailMetric("예정일", formatEarningsCalendarDate(item.nextEarningsDate), DetailTone.Primary),
                    DetailMetric("남은 기간", daysUntilText(item.daysUntil), if ((item.daysUntil ?: 99) <= 7) DetailTone.Warning else DetailTone.Neutral),
                    DetailMetric("시가총액", earningsCalendarValueText(item)),
                    DetailMetric("시장", item.market)
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("티커", item.ticker),
                    DetailMetric("섹터", portfolioIndustryLabel(item.ticker, item.name, item.sector))
                )
            )
        ),
        signals = listOf(
            DetailSignal(
                "실적 발표 예정",
                "${item.name}의 다음 실적 발표가 ${formatEarningsCalendarDate(item.nextEarningsDate)}에 예정되어 있습니다.",
                DetailTone.Primary
            ),
            DetailSignal(
                "체크 포인트",
                "발표 전에는 컨센서스, 가이던스, 최근 가격 반응을 함께 확인하는 구간입니다.",
                DetailTone.Neutral
            )
        ),
        factors = listOf(
            FactorScore("근접도", scaleInverse(item.daysUntil?.toDouble(), 7.0)),
            FactorScore("규모", scaleRatio(item.marketCap?.let { min(it / 1e11, 1.0) }, 1.0))
        )
    )
}

fun watchItem(ticker: String, name: String, market: String?, currency: String, note: String): WatchlistItem {
    return WatchlistItem(
        ticker = ticker,
        name = name,
        market = market ?: if (currency == "KRW") "KR" else "US",
        currency = currency,
        note = note,
        addedAt = ""
    )
}
private fun normalizeRegime(value: String?): String {
    val raw = value.orEmpty().trim()
    val compact = raw.uppercase(Locale.US).replace(" ", "_").replace("-", "_")
    return when {
        raw.contains("위험선호") -> "RISK_ON"
        raw.contains("위험회피") -> "RISK_OFF"
        raw.contains("중립") -> "NEUTRAL"
        compact == "RISK_ON" -> "RISK_ON"
        compact == "RISK_OFF" -> "RISK_OFF"
        compact == "NEUTRAL" -> "NEUTRAL"
        else -> raw.uppercase(Locale.US).ifBlank { "NEUTRAL" }
    }
}

private fun displayRegime(value: String?): String {
    val raw = value.orEmpty().trim()
    if (raw.isBlank() || raw == "-") return raw.ifBlank { "-" }
    return when (normalizeRegime(raw)) {
        "RISK_ON" -> "위험선호"
        "RISK_OFF" -> "위험회피"
        "NEUTRAL" -> "중립"
        else -> if (raw.contains("_")) {
            raw.replace("_", " ").lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        } else {
            raw
        }
    }
}

private fun regimeTitle(regime: String): String {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> "위험선호 장세"
        "RISK_OFF" -> "위험회피 장세"
        else -> "중립 장세"
    }
}

private fun regimeDescription(regime: String): String {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> "시장이 주식과 성장주를 받아들이는 분위기입니다. 신규 진입은 가격 추세가 유지되는 종목부터 확인하세요."
        "RISK_OFF" -> "시장이 불확실성을 크게 보는 구간입니다. 후보를 보더라도 비중과 손절 기준을 먼저 정리하는 편이 좋습니다."
        else -> "상승과 하락 신호가 섞여 있습니다. 실적 일정과 가격 확인을 같이 보며 판단을 미루는 구간입니다."
    }
}

private fun regimeDecisionTitle(regime: String): String {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> "위험자산 선호가 살아 있음"
        "RISK_OFF" -> "방어적으로 볼 장세"
        else -> "방향 확인이 필요한 장세"
    }
}

private fun regimeActionHints(regime: String): List<String> {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> listOf("모멘텀 확인", "분할 진입", "과열 체크")
        "RISK_OFF" -> listOf("현금 비중", "방어주 우선", "손절 기준")
        else -> listOf("관망 가능", "실적 확인", "지수 방향")
    }
}

private fun signalInt(value: String?): Int {
    return value.orEmpty().replace("+", "").trim().toDoubleOrNull()?.toInt() ?: 0
}

private fun signalTone(signal: String): DetailTone {
    val score = signalInt(signal)
    return when {
        score > 0 -> DetailTone.Positive
        score < 0 -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

private fun signalText(signal: String): String {
    return when (signalInt(signal)) {
        1 -> "+1 긍정"
        -1 -> "-1 부정"
        else -> "0 중립"
    }
}

private fun macroSignalBadgeText(signal: String): String {
    return when (signalInt(signal)) {
        1 -> "긍정"
        -1 -> "주의"
        else -> "중립"
    }
}

private fun regimeReasons(macro: Map<String, String>): List<RegimeReason> {
    val vixSignal = macro["VIX_Signal"].orEmpty()
    val yieldSignal = macro["Yield_Signal"].orEmpty()
    val spSignal = macro["SP500_Signal"].orEmpty()
    val creditSignal = macro["Credit_Signal"].orEmpty()
    val momentumSignal = macro["Momentum_Signal"].orEmpty()
    return listOf(
        RegimeReason(
            title = "공포 심리",
            value = "VIX ${macro["VIX"] ?: "-"}",
            signal = vixSignal,
            explanation = when (signalInt(vixSignal)) {
                1 -> "VIX가 20 아래면 시장 공포가 낮은 편이라 위험자산 선호에 긍정적으로 봅니다."
                -1 -> "VIX가 25 이상이면 시장 공포가 커진 구간이라 위험회피 신호로 봅니다."
                else -> "VIX가 중간 구간이라 강한 위험선호나 위험회피 어느 쪽으로도 보지 않습니다."
            }
        ),
        RegimeReason(
            title = "금리 환경",
            value = "10년-3개월 금리차 ${macro["Yield_Spread"] ?: "-"}",
            signal = yieldSignal,
            explanation = when (signalInt(yieldSignal)) {
                1 -> "장단기 금리차가 충분히 양수면 경기 확장 기대가 살아있다고 보고 긍정 신호를 줍니다."
                -1 -> "장단기 금리차가 역전되면 경기 둔화 우려가 커졌다고 보고 부정 신호를 줍니다."
                else -> "금리차가 애매한 구간이라 방향성 판단에는 중립으로 둡니다."
            }
        ),
        RegimeReason(
            title = "장기 추세",
            value = "200일선 대비 ${macro["SP500_vs_200MA"] ?: "-"}",
            signal = spSignal,
            explanation = when (signalInt(spSignal)) {
                1 -> "S&P 500이 200일 이동평균보다 3% 이상 위에 있으면 큰 추세가 살아있다고 봅니다."
                -1 -> "S&P 500이 200일 이동평균보다 3% 이상 아래면 하락 추세 위험을 크게 봅니다."
                else -> "지수가 장기 추세선 근처에 있어 추세 신호는 중립으로 둡니다."
            }
        ),
        RegimeReason(
            title = "신용 시장",
            value = macro["Credit_Conditions"] ?: "HYG-IEF 20일 상대수익 -",
            signal = creditSignal,
            explanation = when (signalInt(creditSignal)) {
                1 -> "하이일드 채권이 중기국채보다 강하면 신용위험을 감수하려는 수요가 있다고 봅니다."
                -1 -> "하이일드 채권이 국채보다 약하면 신용 스프레드 확대, 즉 위험회피 신호로 봅니다."
                else -> "신용시장 상대 흐름이 크지 않아 중립으로 처리합니다."
            }
        ),
        RegimeReason(
            title = "최근 흐름",
            value = "S&P 500 1개월 ${macro["Momentum_1M"] ?: "-"}",
            signal = momentumSignal,
            explanation = when (signalInt(momentumSignal)) {
                1 -> "최근 1개월 수익률이 +3%를 넘으면 단기 매수세가 강하다고 보고 긍정 신호를 줍니다."
                -1 -> "최근 1개월 수익률이 -3%보다 낮으면 단기 하락 압력이 크다고 봅니다."
                else -> "최근 수익률이 큰 방향성을 보이지 않아 중립으로 둡니다."
            }
        )
    )
}

private fun factorWeightText(macro: Map<String, String>): String {
    val regime = normalizeRegime(macro["Regime"])
    val note = when (regime) {
        "RISK_ON" -> "현재는 모멘텀 팩터를 더 크게 보고, 가치와 퀄리티 비중은 조금 낮춥니다."
        "RISK_OFF" -> "현재는 방어력을 위해 가치와 퀄리티 팩터를 더 크게 보고, 모멘텀 비중은 낮춥니다."
        else -> "현재는 기본 배분에 가깝게 가치, 퀄리티, 모멘텀을 균형 있게 봅니다."
    }
    val usV = macro["US_V_Weight"] ?: "-"
    val usQ = macro["US_Q_Weight"] ?: "-"
    val usM = macro["US_M_Weight"] ?: "-"
    val krV = macro["KR_V_Weight"] ?: "-"
    val krQ = macro["KR_Q_Weight"] ?: "-"
    val krM = macro["KR_M_Weight"] ?: "-"
    return "$note\nUS: 가치 $usV · 퀄리티 $usQ · 모멘텀 $usM\nKR: 가치 $krV · 퀄리티 $krQ · 모멘텀 $krM"
}

@Composable
internal fun toneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
}

internal fun marketMoveColor(value: Double): Color {
    return if (value >= 0.0) QuantPositive else QuantNegative
}

private fun ratioTone(value: Double?, good: Double, caution: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v >= good -> DetailTone.Positive
        v >= caution -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

fun returnTone(value: Double?): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v > 0.0 -> DetailTone.Positive
        v < 0.0 -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

private fun scoreTone(value: Double?, good: Double, caution: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v >= good -> DetailTone.Positive
        v >= caution -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

private fun inverseTone(value: Double?, goodMax: Double, cautionMax: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v <= goodMax -> DetailTone.Positive
        v <= cautionMax -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

internal fun researchQualityDiagnosticInfo(quality: ResearchQuality?): DiagnosticInfo {
    return DiagnosticInfo(
        title = "리서치 품질",
        status = quality?.overallStatus ?: "-",
        summary = "팩터 신호가 실제 투자 엔진에 들어갈 만큼 검증됐는지 요약합니다.",
        details = listOf(
            "Status는 전체 factor quality gate 결과입니다. FAIL은 하나 이상의 핵심 신호가 기준을 통과하지 못했다는 뜻입니다.",
            "경고는 IC, 양수 IC 비율, 샘플 수, 프록시 사용 같은 조건에서 주의가 필요한 항목 수입니다.",
            "운영 가능은 실제 스코어링과 리밸런싱에 넣어도 된다고 판정된 팩터 수입니다.",
            "Proxy는 실제 원천 데이터 대신 대체 데이터로 계산한 근거 수입니다. 값이 높을수록 해석 보수성이 필요합니다."
        )
    )
}

internal fun mlBlendDiagnosticInfo(report: MLBlendReport?): DiagnosticInfo {
    val latest = report?.latest
    return DiagnosticInfo(
        title = "AI 보정",
        status = report?.status ?: "-",
        summary = "AI 보정 점수를 기본 점수에 얼마나 반영했는지와 그 근거를 보여줍니다.",
        details = listOf(
            "AI 비중은 최근 예측력에 따라 자동으로 낮아지거나 높아집니다. 예측력이 약하거나 음수이면 영향은 제한됩니다.",
            "기본 점수 비중은 기존 퀄리티, 밸류, 모멘텀 중심 점수의 비중입니다. 현재 기준 컬럼은 ${latest?.factorScoreColumn ?: "-"}입니다.",
            "Rank IC는 예측 순위와 이후 수익률 순위의 관계입니다. 양수이고 충분히 커야 독립적인 예측력으로 볼 수 있습니다.",
            "독립성은 AI 보정 점수와 기본 점수의 상관입니다. 너무 높으면 기존 점수를 반복할 가능성이 큽니다.",
            "Top5는 현재 블렌딩 기준 상위 후보입니다. 종목 선택은 리스크와 리밸런싱 결과까지 함께 확인해야 합니다."
        )
    )
}

internal fun opsHealthDiagnosticInfo(ops: OpsHealth?): DiagnosticInfo {
    return DiagnosticInfo(
        title = "운영 상태",
        status = ops?.status ?: "-",
        summary = "자동 실행에 필요한 API, 데이터 freshness, 산출물 생성 상태를 점검한 결과입니다.",
        details = listOf(
            "Status가 OK이면 핵심 체크가 통과했고, DEGRADED이면 일부 데이터나 산출물이 늦거나 불완전하다는 뜻입니다.",
            "체크 수는 API 응답, 파일 생성, 최신 날짜, 데이터 품질 같은 운영 점검 항목의 개수입니다.",
            "생성 시간은 서버가 이 진단 결과를 만든 시각입니다. 오래된 시간이면 앱보다 파이프라인 실행 상태를 먼저 확인해야 합니다.",
            "운영 상태가 나빠도 앱이 열릴 수는 있지만, 스코어와 백테스트 해석은 보수적으로 봐야 합니다."
        )
    )
}

private fun detailGlossaryKeys(request: DetailRequest, detail: StockDetail?): List<String> {
    val labels = buildList {
        addAll(request.sections.flatMap { section -> section.metrics.map { it.label } })
        addAll(request.factors.map { it.label })
        if (request.isEtfDetail()) {
            addAll(listOf("MDD", "리스크 기여도"))
            return@buildList
        }
        detail?.info?.let { info ->
            if (info.peRatio != null) add("PER")
            if (info.forwardPe != null) add("Forward PER")
            if (info.priceToBook != null) add("P/B")
            if (info.priceToSales != null) add("P/S")
            if (info.beta != null) add("베타")
            if (info.returnOnEquity != null) add("ROE")
            if (info.freeCashflow != null) add("FCF")
            if (info.debtToEquity != null) add("Debt/Equity")
        }
        addAll(listOf("PER", "ROIC", "FCF", "MDD", "리스크 기여도", "AI 보정"))
    }
    return labels.mapNotNull { glossaryKeyForLabel(it) }.distinct().take(10)
}

private fun glossaryInfo(rawKey: String): DiagnosticInfo? {
    return when (glossaryKeyForLabel(rawKey)) {
        "per" -> DiagnosticInfo(
            "PER",
            "밸류에이션",
            "주가가 순이익 대비 얼마나 비싼지 보는 대표 지표입니다.",
            listOf(
                "PER = 시가총액 / 순이익입니다. 같은 이익을 내는 기업이라면 PER이 낮을수록 가격 부담이 낮다고 해석합니다.",
                "Trailing PER은 이미 발표된 이익 기준이고, Forward PER은 앞으로 예상되는 이익 기준입니다.",
                "낮은 PER이 항상 좋은 것은 아닙니다. 이익 감소, 경기 민감도, 회계상 일회성 이익 때문에 낮아 보일 수 있습니다."
            )
        )
        "pbr" -> DiagnosticInfo(
            "PBR",
            "밸류에이션",
            "주가가 장부상 순자산 대비 몇 배에 거래되는지 보는 지표입니다.",
            listOf(
                "PBR = 시가총액 / 자기자본입니다. 자산 가치가 중요한 금융, 지주, 전통 제조업에서 특히 자주 봅니다.",
                "PBR이 낮아도 ROE가 낮으면 자본을 효율적으로 쓰지 못한다는 뜻일 수 있습니다.",
                "앱에서는 P/B와 PBR을 같은 의미로 사용합니다."
            )
        )
        "ps" -> DiagnosticInfo(
            "P/S",
            "밸류에이션",
            "주가가 매출 대비 얼마나 비싼지 보는 지표입니다.",
            listOf(
                "P/S = 시가총액 / 매출입니다. 아직 이익이 작거나 변동성이 큰 성장 기업을 볼 때 보조 지표로 씁니다.",
                "매출은 커도 마진이 낮으면 주주에게 남는 이익이 적을 수 있어 수익성 지표와 함께 봐야 합니다."
            )
        )
        "roe" -> DiagnosticInfo(
            "ROE",
            "수익성",
            "자기자본으로 얼마나 많은 순이익을 만들었는지 보여줍니다.",
            listOf(
                "ROE = 순이익 / 자기자본입니다. 높을수록 주주 자본을 효율적으로 쓴다고 볼 수 있습니다.",
                "다만 부채를 크게 쓰면 ROE가 높아질 수 있으니 Debt/Equity와 함께 확인해야 합니다."
            )
        )
        "roic" -> DiagnosticInfo(
            "ROIC",
            "퀄리티",
            "사업에 투입한 자본 대비 영업이익 창출력이 얼마나 좋은지 보는 지표입니다.",
            listOf(
                "ROIC가 높으면 같은 돈을 넣어도 더 많은 영업성과를 만드는 기업일 가능성이 큽니다.",
                "큐빗에서는 퀄리티 팩터의 핵심 지표 중 하나로 봅니다.",
                "업종별 자본 구조가 달라서 같은 업종 안에서 비교하는 것이 더 안전합니다."
            )
        )
        "fcf" -> DiagnosticInfo(
            "FCF",
            "현금흐름",
            "기업이 영업과 투자를 거친 뒤 실제로 남기는 자유현금흐름입니다.",
            listOf(
                "FCF는 배당, 자사주, 부채 상환, 재투자에 쓸 수 있는 현금 여력을 보여줍니다.",
                "회계상 이익은 좋아도 FCF가 계속 약하면 이익의 질을 보수적으로 봐야 합니다.",
                "FCF 마진은 매출 대비 자유현금흐름 비율입니다."
            )
        )
        "debtEquity" -> DiagnosticInfo(
            "Debt/Equity",
            "재무 리스크",
            "자기자본 대비 부채 부담이 어느 정도인지 보는 지표입니다.",
            listOf(
                "값이 높을수록 레버리지 부담이 크고 금리, 경기 둔화에 민감할 수 있습니다.",
                "업종별 정상 범위가 크게 다르므로 금융, 유틸리티, 제조업을 같은 기준으로 비교하면 위험합니다."
            )
        )
        "debtEbitda" -> DiagnosticInfo(
            "Debt/EBITDA",
            "재무 리스크",
            "영업현금 창출력 대비 부채가 얼마나 무거운지 보는 지표입니다.",
            listOf(
                "대략 몇 년치 EBITDA로 부채를 갚을 수 있는지 보는 감각에 가깝습니다.",
                "값이 높을수록 재무 부담이 크고 리밸런싱이나 스몰캡 판단에서 주의 신호로 봅니다."
            )
        )
        "ebitda" -> DiagnosticInfo(
            "EBITDA",
            "수익성",
            "이자, 세금, 감가상각 전 이익으로 영업 체력의 거친 근사치입니다.",
            listOf(
                "설비투자와 감가상각 영향이 큰 기업을 비교할 때 보조적으로 씁니다.",
                "실제 현금흐름과 같지는 않으므로 FCF와 같이 확인하는 편이 좋습니다."
            )
        )
        "growth" -> DiagnosticInfo(
            "매출 성장",
            "성장성",
            "최근 매출이 이전 기간 대비 얼마나 늘었는지 보여줍니다.",
            listOf(
                "양수 성장은 제품 수요나 시장 점유율 확대를 시사할 수 있습니다.",
                "성장률만 높고 마진이 낮으면 수익성 없는 성장일 수 있어 마진과 함께 봐야 합니다."
            )
        )
        "grossMargin" -> DiagnosticInfo(
            "매출총이익률",
            "수익성",
            "매출에서 원가를 뺀 뒤 남는 비율입니다.",
            listOf(
                "높을수록 가격 결정력, 원가 통제력, 제품 경쟁력이 좋을 가능성이 있습니다.",
                "업종 차이가 매우 커서 같은 산업 안에서 비교하는 것이 중요합니다."
            )
        )
        "operatingMargin" -> DiagnosticInfo(
            "영업이익률",
            "수익성",
            "본업에서 매출 대비 얼마나 이익을 남기는지 보여줍니다.",
            listOf(
                "영업이익률이 높고 안정적이면 사업 모델의 질이 좋다고 볼 수 있습니다.",
                "일회성 비용이나 경기 사이클 때문에 단기적으로 흔들릴 수 있습니다."
            )
        )
        "beta" -> DiagnosticInfo(
            "베타",
            "시장 민감도",
            "종목이 시장 전체 움직임에 얼마나 민감한지 나타냅니다.",
            listOf(
                "베타가 1보다 크면 시장보다 더 크게 움직이는 경향이 있고, 1보다 작으면 상대적으로 방어적입니다.",
                "과거 가격으로 계산한 값이라 미래 변동성을 보장하지는 않습니다."
            )
        )
        "marketCap" -> DiagnosticInfo(
            "시가총액",
            "기업 규모",
            "주식시장이 평가하는 기업 전체 가치입니다.",
            listOf(
                "시가총액 = 주가 × 발행주식수입니다.",
                "대형주는 안정성과 유동성이 좋고, 소형주는 성장 여지와 변동성이 함께 커지는 경우가 많습니다."
            )
        )
        "expectedReturn" -> DiagnosticInfo(
            "기대수익률",
            "모델 전망",
            "현재 팩터와 과거 학습 결과를 바탕으로 모델이 추정한 기대 수익 신호입니다.",
            listOf(
                "실제 확정 수익률이 아니라 종목 간 우선순위를 정하기 위한 모델 출력입니다.",
                "리스크, 거래비용, 리밸런싱 제약과 함께 봐야 하며 단독 매수 신호로 쓰면 안 됩니다."
            )
        )
        "weight" -> DiagnosticInfo(
            "비중",
            "분석",
            "모델 분석에서 해당 종목에 배분된 기준 비중입니다.",
            listOf(
                "비중이 높을수록 수익과 손실에 미치는 영향이 커집니다.",
                "좋은 종목이어도 리스크 기여도가 너무 크면 비중을 낮추는 판단이 필요할 수 있습니다."
            )
        )
        "volatility" -> DiagnosticInfo(
            "연간 변동성",
            "리스크",
            "일별 수익률의 흔들림을 연율화한 위험 지표입니다.",
            listOf(
                "값이 높을수록 가격이 크게 출렁이는 종목입니다.",
                "수익률이 높아도 변동성이 지나치게 크면 분석 기준 안정성을 해칠 수 있습니다."
            )
        )
        "mdd" -> DiagnosticInfo(
            "MDD",
            "리스크",
            "고점에서 저점까지 가장 크게 빠진 낙폭입니다.",
            listOf(
                "Maximum Drawdown의 약자로, 손실을 견뎌야 하는 최대 구간을 보여줍니다.",
                "수익률이 좋아도 MDD가 크면 실제 보유 난이도는 높을 수 있습니다."
            )
        )
        "riskContribution" -> DiagnosticInfo(
            "리스크 기여도",
            "분석 리스크",
            "해당 종목이나 섹터가 모델 기준 전체 변동성에 얼마나 기여하는지 보여줍니다.",
            listOf(
                "비중이 작아도 변동성과 상관관계가 높으면 리스크 기여도는 커질 수 있습니다.",
                "분석 결과를 단순 비중이 아니라 실제 위험 기준으로 점검할 때 중요합니다."
            )
        )
        "rankIc" -> DiagnosticInfo(
            "Rank IC",
            "리서치 검증",
            "모델 순위와 이후 수익률 순위가 얼마나 같은 방향으로 움직였는지 보는 검증 지표입니다.",
            listOf(
                "양수이면 점수가 높은 종목이 이후에도 상대적으로 좋은 성과를 냈다는 뜻입니다.",
                "샘플 수가 적거나 기간이 짧으면 우연일 수 있어 품질 게이트와 함께 봐야 합니다."
            )
        )
        "mlScore" -> DiagnosticInfo(
            "AI 보정",
            "AI 보정",
            "예측 모델이 종목의 상대 매력을 0~1 범위로 평가한 보정 점수입니다.",
            listOf(
                "높을수록 모델이 같은 유니버스 안에서 더 우호적으로 본 후보입니다.",
                "AI 보정은 기존 Value, Quality, Momentum 점수를 보완하는 역할이며 Rank IC가 약하면 영향력이 줄어듭니다."
            )
        )
        "factorScore" -> DiagnosticInfo(
            "팩터 점수",
            "스코어링",
            "Value, Quality, Momentum 같은 투자 팩터를 정규화해 종목 간 비교가 가능하게 만든 점수입니다.",
            listOf(
                "값이 높을수록 해당 팩터 관점에서 상대적으로 매력적이라는 뜻입니다.",
                "팩터 하나만 높다고 충분하지 않고, 여러 팩터의 균형과 리스크를 함께 봐야 합니다."
            )
        )
        "epsSurprise" -> DiagnosticInfo(
            "EPS 서프라이즈",
            "실적 모멘텀",
            "실제 주당순이익이 시장 예상치를 얼마나 웃돌았는지 보는 지표입니다.",
            listOf(
                "양수이면 예상보다 실적이 좋았다는 뜻이고 단기 가격 반응의 원인이 될 수 있습니다.",
                "이미 주가에 반영됐을 수 있으므로 발표 후 수익률과 거래량을 같이 봐야 합니다."
            )
        )
        "volumeSurge" -> DiagnosticInfo(
            "거래량 서지",
            "수급",
            "평소 대비 거래량이 얼마나 늘었는지 보여주는 관심도 지표입니다.",
            listOf(
                "거래량 증가는 정보 반영이나 기관/외국인 수급 변화 가능성을 시사합니다.",
                "가격 상승 없이 거래량만 늘면 매물 출회일 수도 있어 방향성을 함께 확인해야 합니다."
            )
        )
        else -> null
    }
}

private fun glossaryKeyForLabel(label: String?): String? {
    val raw = label.orEmpty().trim()
    if (raw.isBlank()) return null
    val lower = raw.lowercase(Locale.US)
    val compact = lower.replace(" ", "").replace("_", "").replace("-", "")
    return when {
        "forwardper" in compact -> "per"
        "trailingper" in compact -> "per"
        compact == "per" -> "per"
        compact == "pbr" || compact == "p/b" || compact == "pb" -> "pbr"
        compact == "ps" || compact == "p/s" -> "ps"
        compact == "roe" -> "roe"
        compact == "roic" -> "roic"
        compact == "fcf" || "fcf마진" in compact -> "fcf"
        compact == "debt/equity" || "debtequity" in compact -> "debtEquity"
        compact == "debt/ebitda" || "debtebitda" in compact -> "debtEbitda"
        "ebitda" in compact -> "ebitda"
        "매출성장" in compact || "성장가속" in compact -> "growth"
        "매출총이익률" in compact || "grossmargin" in compact -> "grossMargin"
        "영업이익률" in compact || "operatingmargin" in compact -> "operatingMargin"
        "베타" in compact || compact == "beta" -> "beta"
        "시가총액" in compact || "marketcap" in compact -> "marketCap"
        "기대수익" in compact || "expectedreturn" in compact -> "expectedReturn"
        compact == "비중" || "portfolioweight" in compact -> "weight"
        "변동성" in compact || "volatility" in compact -> "volatility"
        "최대낙폭" in compact || compact == "mdd" -> "mdd"
        "리스크기여" in compact || "riskcontribution" in compact -> "riskContribution"
        compact == "ic" || "rankic" in compact || "scorereturnic" in compact -> "rankIc"
        "ml점수" in compact || "ai보정" in compact || compact == "ml" || "mlscore" in compact -> "mlScore"
        compact == "value" || compact == "quality" || compact == "momentum" || "최종점수" in compact || "종합점수" in compact -> "factorScore"
        "eps" in compact || "서프라이즈" in compact -> "epsSurprise"
        "거래량서지" in compact || "volumesurge" in compact -> "volumeSurge"
        else -> null
    }
}

internal fun statusTone(status: String): DetailTone {
    return when (status.uppercase(Locale.US)) {
        "OK", "PASS", "SUCCESS", "HEALTHY", "IMPROVED", "ML_STRONG" -> DetailTone.Positive
        "ML_BASE" -> DetailTone.Primary
        "WARN", "WATCH", "DEGRADED", "INSUFFICIENT", "STALE", "UNKNOWN", "UNAVAILABLE", "REVIEW", "HOLD", "ML_OFF", "ML_WEAK" -> DetailTone.Warning
        "FAIL", "FAILED", "ERROR", "WORSE" -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

private fun scaleRatio(value: Double?, maxValue: Double): Double {
    val v = value ?: return 0.0
    if (!v.isFinite() || maxValue <= 0.0 || !maxValue.isFinite()) return 0.0
    return (v / maxValue * 100.0).coerceIn(0.0, 100.0)
}

private fun scaleSignedRatio(value: Double?, positiveMax: Double): Double {
    val v = value ?: return 50.0
    if (!v.isFinite() || positiveMax <= 0.0 || !positiveMax.isFinite()) return 50.0
    return (50.0 + (v / positiveMax * 50.0)).coerceIn(0.0, 100.0)
}

private fun scaleScore(value: Double?, maxValue: Double): Double = scaleRatio(value, maxValue)

private fun scaleInverse(value: Double?, badAt: Double): Double {
    val v = value ?: return 50.0
    if (!v.isFinite() || badAt <= 0.0 || !badAt.isFinite()) return 50.0
    return (100.0 - (v / badAt * 100.0)).coerceIn(0.0, 100.0)
}

private fun hasCompanyProfile(info: StockInfo): Boolean {
    return !info.industry.isNullOrBlank() ||
        profileLocation(info) != null ||
        info.employees != null ||
        !info.website.isNullOrBlank()
}

private fun hasMarketInfo(info: StockInfo): Boolean {
    return info.currentPrice != null ||
        info.prevClose != null ||
        info.week52High != null ||
        info.week52Low != null ||
        info.marketCap != null ||
        info.peRatio != null ||
        info.forwardPe != null ||
        info.priceToSales != null ||
        info.priceToBook != null ||
        info.beta != null ||
        info.targetMeanPrice != null ||
        normalizedRecommendation(info.recommendation) != null
}

private fun hasFinancialSnapshot(info: StockInfo): Boolean {
    return info.totalRevenue != null ||
        info.revenueGrowth != null ||
        info.grossMargin != null ||
        info.operatingMargin != null ||
        info.profitMargin != null ||
        info.ebitdaMargin != null ||
        info.ebitda != null ||
        info.freeCashflow != null ||
        info.totalDebt != null ||
        info.debtToEquity != null ||
        info.returnOnEquity != null
}

private fun profileLocation(info: StockInfo): String? {
    return listOfNotNull(
        info.city?.takeIf { it.isNotBlank() },
        info.country?.takeIf { it.isNotBlank() }
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun returnMetrics(points: List<PricePoint>, currency: String): List<DetailMetric> {
    if (points.size < 2) return emptyList()
    val periodMetrics = listOf(
        "1W" to 5,
        "1M" to 21,
        "3M" to 63,
        "6M" to 126,
        "1Y" to 252
    ).map { (label, days) ->
        val ret = periodReturn(points, days)
        DetailMetric(label, pct(ret), returnTone(ret))
    }
    val returns = points.zipWithNext { prev, next ->
        if (prev.close == 0.0) null else (next.close / prev.close) - 1.0
    }.filterNotNull()
    val vol = if (returns.size > 5) {
        val avg = returns.average()
        sqrt(returns.sumOf { (it - avg) * (it - avg) } / returns.size) * sqrt(252.0)
    } else null
    val maxDd = maxDrawdown(points.takeLast(min(points.size, 126)))
    return periodMetrics + listOf(
        DetailMetric("연간 변동성", pct(vol, signed = false), inverseTone(vol, 0.25, 0.45)),
        DetailMetric("6M 최대낙폭", pct(maxDd), inverseTone(maxDd?.let { -it }, 0.15, 0.30)),
        DetailMetric("최근가", fmtPx(points.last().close, currency), DetailTone.Primary)
    )
}

private fun periodReturn(points: List<PricePoint>, days: Int): Double? {
    if (points.size < 2) return null
    val offset = min(days, points.lastIndex)
    val base = points[points.lastIndex - offset].close
    if (base == 0.0) return null
    return (points.last().close / base) - 1.0
}

private fun maxDrawdown(points: List<PricePoint>): Double? {
    if (points.size < 2) return null
    var peak = points.first().close
    var maxDrawdown = 0.0
    points.forEach { point ->
        peak = max(peak, point.close)
        if (peak > 0.0) {
            maxDrawdown = min(maxDrawdown, (point.close - peak) / peak)
        }
    }
    return maxDrawdown
}

internal fun rebalanceTradeText(order: RebalanceOrder): String {
    return "변화 ${pct(order.deltaWeight)} · 거래 ${compactNumber(order.executableTradeValue)}"
}

internal fun backtestTitle(summary: BacktestSummary): String {
    return when {
        summary.sheet.contains("SmallCap", ignoreCase = true) -> "${summary.market} 스몰캡"
        else -> "${summary.market} 분석"
    }
}

internal fun newsMarketLabel(market: String): String {
    return when (market.uppercase(Locale.US)) {
        "US" -> "미국"
        "KR" -> "국내"
        "GLOBAL" -> "글로벌"
        else -> market
    }
}

private fun freshness(meta: Map<String, String>): String {
    return meta["Generated"] ?: meta["Last_Updated"] ?: "-"
}

private fun portfolioMetaHeadline(meta: Map<String, String>): String {
    return firstPortfolioMetaValue(
        meta,
        "Expected_Return",
        "Ann. Return (hist. est.)",
        "Ann. Return"
    )?.let { formatMetaPercent(it, signed = true) }
        ?: freshness(meta)
}

private fun portfolioMetaSubtitle(meta: Map<String, String>): String {
    val cashWeight = firstPortfolioMetaValue(meta, "Cash_Weight", "Cash Weight")
        ?.let { formatMetaPercent(it, signed = false) }
        ?: "데이터 없음"
    val generated = firstPortfolioMetaValue(meta, "Generated", "Generated_At", "Last_Updated")
        ?: "데이터 없음"
    return "현금 비중 $cashWeight · 생성 시각 $generated"
}

private fun firstPortfolioMetaValue(meta: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}

private fun formatMetaPercent(value: String, signed: Boolean): String {
    if (value.contains("%")) return value
    val number = value.replace(",", "").toDoubleOrNull() ?: return value
    return pct(number, signed = signed)
}

@Composable
private fun regimeColor(regime: String): Color {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> QuantPositive
        "RISK_OFF" -> QuantNegative
        else -> MaterialTheme.colorScheme.primary
    }
}

fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch { block() }
}
