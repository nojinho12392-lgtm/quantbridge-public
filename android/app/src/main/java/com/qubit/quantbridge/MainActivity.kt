package com.qubit.quantbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.qubit.quantbridge.ui.theme.QubitTheme
import com.qubit.quantbridge.ui.theme.QuantBackground
import com.qubit.quantbridge.ui.theme.QuantDarkBackground
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantSurface
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = QuantBackground.toArgb(),
                darkScrim = QuantDarkBackground.toArgb()
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = QuantSurface.toArgb(),
                darkScrim = QuantDarkBackground.toArgb()
            )
        )
        setContent {
            QubitTheme {
                QubitScrollEffects {
                    QubitApp()
                }
            }
        }
    }
}

@Composable
private fun QubitScrollEffects(content: @Composable () -> Unit) {
    val overscrollFactory = rememberPlatformOverscrollFactory(
        glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    )

    CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory) {
        content()
    }
}

private suspend fun PagerState.navigateToMainPage(page: Int) {
    if (page == currentPage) return
    if (abs(page - currentPage) <= 1) {
        animateScrollToPage(
            page = page,
            animationSpec = tween(durationMillis = QUANT_ROUTE_ENTER_MS, easing = QuantRouteEasing)
        )
    } else {
        scrollToPage(page)
    }
}

private val QuantRouteEasing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)
private const val QUANT_ROUTE_ENTER_MS = 260
private const val QUANT_ROUTE_EXIT_MS = 210
private const val QUANT_ROUTE_FADE_MS = 160
private const val DETAIL_PRICE_AUTO_REFRESH_MS = 300_000L

private enum class RootSurfaceType {
    Main,
    MarketIndicators
}

private data class RootSurfaceState(
    val type: RootSurfaceType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QubitApp() {
    val context = LocalContext.current
    val app = remember { QuantAppState(context.applicationContext) }
    val accountViewModel: AccountViewModel = hiltViewModel()
    val stockDetailViewModel: StockDetailViewModel = hiltViewModel()
    val comparisonViewModel: ComparisonViewModel = hiltViewModel()
    val newsViewModel: NewsViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val comparisonSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mainTabs = remember {
        listOf(AppTab.Home, AppTab.Portfolio, AppTab.Pulse, AppTab.Watch, AppTab.Account)
    }
    val auxiliaryTabs = remember {
        listOf(AppTab.Search, AppTab.News, AppTab.SmallCap, AppTab.Etf)
    }
    val isNewsTab = auxiliaryTabs.any { it == AppTab.News && app.selectedTab == it }
    val isSmallCapTab = auxiliaryTabs.any { it == AppTab.SmallCap && app.selectedTab == it }
    val pagerState = rememberPagerState(
        initialPage = mainTabs.indexOf(app.selectedTab).coerceAtLeast(0),
        pageCount = { mainTabs.size }
    )

    LaunchedEffect(Unit) { app.bootstrap() }

    LaunchedEffect(pagerState.settledPage) {
        val tab = mainTabs[pagerState.settledPage]
        if (
            app.selectedDetail == null &&
            !auxiliaryTabs.contains(app.selectedTab) &&
            app.selectedTab != tab
        ) {
            app.selectedTab = tab
        }
    }

    LaunchedEffect(app.selectedTab) {
        val targetPage = mainTabs.indexOf(app.selectedTab)
        if (targetPage >= 0 && targetPage != pagerState.currentPage) {
            pagerState.navigateToMainPage(targetPage)
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var routeDirection by remember { mutableStateOf(1) }
    var showMarketIndicators by remember { mutableStateOf(false) }
    var showStartupBranding by remember { mutableStateOf(true) }
    var detailReturnTab by remember { mutableStateOf<AppTab?>(null) }
    var detailReturnPage by remember { mutableStateOf(pagerState.currentPage) }
    var wasDetailVisible by remember { mutableStateOf(false) }
    val selectedDetail = app.selectedDetail
    val closeDetail: () -> Unit = {
        routeDirection = -1
        if (app.detailBackStack.isNotEmpty()) {
            app.selectedDetail = app.detailBackStack.removeAt(app.detailBackStack.lastIndex)
        } else {
            val returnTab = detailReturnTab
            val returnPage = detailReturnPage
            app.selectedDetail = null
            if (returnTab != null) {
                app.selectedTab = returnTab
                val targetPage = mainTabs.indexOf(returnTab)
                if (targetPage >= 0 && targetPage != pagerState.currentPage) {
                    scope.launch { pagerState.navigateToMainPage(targetPage) }
                } else if (returnTab in auxiliaryTabs && returnPage != pagerState.currentPage) {
                    scope.launch { pagerState.navigateToMainPage(returnPage.coerceIn(mainTabs.indices)) }
                }
            }
        }
    }
    val openNestedDetail: (DetailRequest) -> Unit = { next ->
        routeDirection = 1
        selectedDetail?.let { app.detailBackStack.add(it) }
        app.selectedDetail = next
    }

    LaunchedEffect(Unit) {
        delay(1_050)
        showStartupBranding = false
    }

    LaunchedEffect(selectedDetail) {
        if (selectedDetail != null && !wasDetailVisible) {
            detailReturnTab = app.selectedTab
            detailReturnPage = pagerState.currentPage
            wasDetailVisible = true
        } else if (selectedDetail == null) {
            wasDetailVisible = false
            detailReturnTab = null
            detailReturnPage = pagerState.currentPage
        }
    }

    LaunchedEffect(selectedDetail == null) {
        if (selectedDetail == null) {
            app.detailBackStack.clear()
        }
    }

    LaunchedEffect(selectedDetail?.ticker) {
        stockDetailViewModel.resetPeriod()
    }

    LaunchedEffect(selectedDetail?.ticker, stockDetailViewModel.period) {
        selectedDetail?.let { stockDetailViewModel.load(it) } ?: stockDetailViewModel.clear()
    }
    LaunchedEffect("detail-price-auto", selectedDetail?.ticker, stockDetailViewModel.period) {
        val request = selectedDetail ?: return@LaunchedEffect
        while (true) {
            delay(DETAIL_PRICE_AUTO_REFRESH_MS)
            if (app.selectedDetail?.ticker == request.ticker) {
                stockDetailViewModel.refreshCurrent(request)
            }
        }
    }

    val rootSurface = when {
        showMarketIndicators -> RootSurfaceState(RootSurfaceType.MarketIndicators)
        else -> RootSurfaceState(RootSurfaceType.Main)
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = rootSurface,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forwardRoute = targetState.type != RootSurfaceType.Main
                val direction = if (forwardRoute) 1 else -1
                val enter = slideInHorizontally(
                    animationSpec = tween(durationMillis = QUANT_ROUTE_ENTER_MS, easing = QuantRouteEasing),
                    initialOffsetX = { fullWidth -> if (direction > 0) fullWidth / 7 else -fullWidth / 18 }
                ) +
                    fadeIn(animationSpec = tween(durationMillis = QUANT_ROUTE_FADE_MS, easing = QuantRouteEasing)) +
                    scaleIn(initialScale = 0.992f, animationSpec = tween(durationMillis = QUANT_ROUTE_ENTER_MS, easing = QuantRouteEasing))
                val exit = slideOutHorizontally(
                    animationSpec = tween(durationMillis = QUANT_ROUTE_EXIT_MS, easing = QuantRouteEasing),
                    targetOffsetX = { fullWidth -> if (direction > 0) -fullWidth / 18 else fullWidth / 7 }
                ) + fadeOut(animationSpec = tween(durationMillis = 130, easing = QuantRouteEasing)) +
                    scaleOut(targetScale = 0.992f, animationSpec = tween(durationMillis = QUANT_ROUTE_EXIT_MS, easing = QuantRouteEasing))
                enter.togetherWith(exit)
            },
            label = "root-surface-transition"
        ) { surface ->
            when (surface.type) {
                RootSurfaceType.MarketIndicators -> {
                BackHandler {
                    showMarketIndicators = false
                }
                MarketIndicatorsScreen(
                    app = app,
                    onBack = { showMarketIndicators = false }
                )
            }

                RootSurfaceType.Main -> {
                BackHandler(enabled = auxiliaryTabs.contains(app.selectedTab)) {
                    app.selectedTab = mainTabs[pagerState.currentPage]
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        CompactAppBar(
                            title = when (app.selectedTab) {
                                AppTab.Home -> "큐빗"
                                else -> app.selectedTab.label
                            },
                            indices = app.marketIndices,
                            showSearchAction = true,
                            onSearch = { app.selectedTab = AppTab.Search },
                            onMarketIndicators = { showMarketIndicators = true }
                        )
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
                        val contentRoute = if (auxiliaryTabs.contains(app.selectedTab)) app.selectedTab else AppTab.Home
                        AnimatedContent(
                            targetState = contentRoute,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                val enter = slideInHorizontally(
                                    animationSpec = tween(durationMillis = 220, easing = QuantRouteEasing),
                                    initialOffsetX = { fullWidth -> direction * fullWidth / 10 }
                                ) + fadeIn(animationSpec = tween(durationMillis = QUANT_ROUTE_FADE_MS, easing = QuantRouteEasing))
                                val exit = slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 180, easing = QuantRouteEasing),
                                    targetOffsetX = { fullWidth -> -direction * fullWidth / 16 }
                                ) + fadeOut(animationSpec = tween(durationMillis = 120, easing = QuantRouteEasing))
                                enter.togetherWith(exit)
                            },
                            label = "main-content-route-transition"
                        ) { route ->
                            when (route) {
                                AppTab.Search -> SearchScreen(app, showAdvancedModes = false)
                                AppTab.News -> NewsScreen(app)
                                AppTab.SmallCap -> SmallCapScreen(app)
                                AppTab.Etf -> EtfInsightsScreen(app)
                                else -> {
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                        beyondViewportPageCount = 0,
                                        key = { page -> mainTabs[page].name }
                                    ) { page ->
                                        when (mainTabs[page]) {
                                            AppTab.Home -> HomeScreen(app, onMarketIndicators = { showMarketIndicators = true })
                                            AppTab.Search -> SearchScreen(app, showAdvancedModes = false)
                                            AppTab.News -> NewsScreen(app)
                                            AppTab.Etf -> EtfInsightsScreen(app)
                                            AppTab.Portfolio -> PortfolioScreen(app)
                                            AppTab.SmallCap -> SmallCapScreen(app)
                                            AppTab.Pulse -> PulseScreen(app)
                                            AppTab.Watch -> WatchScreen(app)
                                            AppTab.Account -> AccountScreen(
                                                app = app,
                                                onDelete = { showDeleteDialog = true },
                                                accountViewModel = accountViewModel
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if ((app.homeLoading || app.initialDashboardRetrying) && !app.dashboardDataReady) {
                            LoadingSurface(
                                message = if (app.initialDashboardRetrying) "초기 데이터 재확인 중" else "앱 데이터 로딩 중",
                                detail = if (app.initialDashboardRetrying) {
                                    "첫 응답이 비어 있어 한 번 더 불러오고 있습니다."
                                } else {
                                    "분석 후보 데이터를 확인하고 있습니다."
                                }
                            )
                        }
                        app.error?.takeIf {
                            app.selectedTab == AppTab.Home &&
                                !app.dashboardDataReady &&
                                !app.homeLoading &&
                                !app.initialDashboardRetrying
                        }?.let {
                            ErrorBanner(
                                it,
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = FLOATING_NAV_CONTENT_INSET)
                            )
                        }
                        FloatingBottomNav(
                            tabs = mainTabs,
                            selectedTab = app.selectedTab,
                            onTabSelected = { tab ->
                                val targetPage = mainTabs.indexOf(tab)
                                if (targetPage >= 0) {
                                    app.selectedTab = tab
                                    scope.launch { pagerState.navigateToMainPage(targetPage) }
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

        selectedDetail?.let { currentDetail ->
            Dialog(
                onDismissRequest = closeDetail,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                AnimatedContent(
                    targetState = currentDetail,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val forwardRoute = routeDirection >= 0
                        val direction = if (forwardRoute) 1 else -1
                        val enter = slideInHorizontally(
                            animationSpec = tween(durationMillis = QUANT_ROUTE_ENTER_MS, easing = QuantRouteEasing),
                            initialOffsetX = { fullWidth -> if (direction > 0) fullWidth / 7 else -fullWidth / 18 }
                        ) +
                            fadeIn(animationSpec = tween(durationMillis = QUANT_ROUTE_FADE_MS, easing = QuantRouteEasing)) +
                            scaleIn(initialScale = 0.992f, animationSpec = tween(durationMillis = QUANT_ROUTE_ENTER_MS, easing = QuantRouteEasing))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(durationMillis = QUANT_ROUTE_EXIT_MS, easing = QuantRouteEasing),
                            targetOffsetX = { fullWidth -> if (direction > 0) -fullWidth / 18 else fullWidth / 7 }
                        ) + fadeOut(animationSpec = tween(durationMillis = 130, easing = QuantRouteEasing)) +
                            scaleOut(targetScale = 0.992f, animationSpec = tween(durationMillis = QUANT_ROUTE_EXIT_MS, easing = QuantRouteEasing))
                        enter.togetherWith(exit)
                    },
                    label = "detail-overlay-transition"
                ) { detailRequest ->
                BackHandler {
                    closeDetail()
                }
                StockDetailScreen(
                    app = app,
                    request = detailRequest,
                    detail = stockDetailViewModel.detail,
                    loading = stockDetailViewModel.loading,
                    error = stockDetailViewModel.error,
                    period = stockDetailViewModel.period,
                    availablePeriods = stockDetailViewModel.availablePeriods,
                    onPeriodChange = { stockDetailViewModel.updatePeriod(it) },
                    onRetry = { stockDetailViewModel.load(detailRequest, force = true) },
                    onBack = closeDetail,
                    onOpenDetail = openNestedDetail,
                    comparisonViewModel = comparisonViewModel
                )
            }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("계정을 삭제할까요?") },
            text = { Text("서버에 저장된 계정 정보와 관심 종목이 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launchSafely {
                        if (accountViewModel.deleteAccount()) {
                            app.clearAccountSession(clearWatchlist = true)
                        }
                    }
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("취소") }
            }
        )
    }

    if (comparisonViewModel.showSheet) {
        ModalBottomSheet(
            onDismissRequest = { comparisonViewModel.closeSheet() },
            sheetState = comparisonSheetState
        ) {
            StockComparisonSheet(
                items = comparisonViewModel.items,
                newsItems = newsViewModel.items.ifEmpty { app.newsItems }
            )
        }
    }

    AnimatedVisibility(
        visible = showStartupBranding,
        enter = fadeIn(animationSpec = tween(160)),
        exit = fadeOut(animationSpec = tween(220))
    ) {
        QubitStartupSplash()
    }
}

@Composable
private fun QubitStartupSplash() {
    var showSignature by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(260)
        showSignature = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.quantbridge_splash_mark),
            contentDescription = "큐빗",
            modifier = Modifier.size(112.dp),
            contentScale = ContentScale.Fit
        )
        AnimatedVisibility(
            visible = showSignature,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 86.dp),
            enter = fadeIn(animationSpec = tween(220)) +
                slideInVertically(animationSpec = tween(260), initialOffsetY = { it / 3 })
        ) {
            Text(
                "made by Jinho",
                color = Color(0xFF5C6B73),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FloatingBottomNav(
    tabs: List<AppTab>,
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
                        Color.Transparent
                    )
                )
            )
            .padding(top = 10.dp, bottom = 6.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.90f)
                .height(61.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(50),
            shadowElevation = 12.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = selectedTab == tab
                    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(28.dp))
                            .quantClickable(role = QuantPressRole.Icon) { onTabSelected(tab) }
                            .clearAndSetSemantics {
                                role = Role.Tab
                                contentDescription = "${tab.label} 탭"
                                stateDescription = if (selected) "선택됨" else "선택 안 됨"
                                this.selected = selected
                                onClick(label = "${tab.label} 탭 열기") {
                                    onTabSelected(tab)
                                    true
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        LucideIconView(
                            icon = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(if (selected) 23.dp else 22.dp),
                            tint = tint
                        )
                        Text(
                            tab.label,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAppBar(
    title: String,
    indices: List<MarketIndexQuote>,
    showSearchAction: Boolean,
    onSearch: () -> Unit,
    onMarketIndicators: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(48.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (title == "큐빗") {
                Row(
                    modifier = Modifier.width(86.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.quantbridge_splash_mark),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF21111C),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    title,
                    modifier = Modifier.width(146.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            MarketIndexTicker(
                indices = indices,
                modifier = Modifier
                    .weight(1f)
                    .quantClickable(role = QuantPressRole.Row, onClick = onMarketIndicators)
            )
            if (showSearchAction) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .clip(CircleShape)
                        .quantClickable(role = QuantPressRole.Icon, onClick = onSearch),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = LucideIcon.Search,
                        contentDescription = "검색",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketIndexTicker(
    indices: List<MarketIndexQuote>,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember(indices) { mutableStateOf(0) }
    val current = indices.getOrNull(currentIndex.coerceAtMost((indices.size - 1).coerceAtLeast(0)))

    LaunchedEffect(indices) {
        currentIndex = 0
        if (indices.size <= 1) return@LaunchedEffect
        while (true) {
            delay(2_500)
            currentIndex = (currentIndex + 1) % indices.size
        }
    }

    if (current == null) {
        Text(
            "S&P 500 대기중",
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            textAlign = TextAlign.End
        )
        return
    }

    Box(
        modifier = modifier
            .height(26.dp)
            .clipToBounds(),
        contentAlignment = Alignment.CenterEnd
    ) {
        MarketIndexTickerContent(
            quote = current,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}

@Composable
private fun MarketIndexTickerContent(
    quote: MarketIndexQuote,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            marketTickerName(quote),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(5.dp))
        Text(
            marketTickerValueText(quote.value),
            modifier = Modifier.weight(1f, fill = false),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(5.dp))
        Text(
            pct(quote.changePct),
            color = if (quote.changePct >= 0.0) QuantPositive else QuantNegative,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

private fun marketTickerName(quote: MarketIndexQuote): String = when (quote.symbol) {
    "^IXIC" -> "NASDAQ"
    "^GSPC" -> "S&P 500"
    "^KS11" -> "KOSPI"
    "^KQ11" -> "KOSDAQ"
    else -> quote.label
}

private fun marketTickerValueText(value: Double): String {
    return when {
        !value.isFinite() -> "-"
        abs(value) >= 1_000.0 -> String.format(Locale.US, "%,.2f", value)
        abs(value) >= 100.0 -> String.format(Locale.US, "%.2f", value)
        abs(value) >= 1.0 -> String.format(Locale.US, "%.3f", value)
        else -> String.format(Locale.US, "%.4f", value)
    }
}

private enum class MarketIndicatorCategory(val apiValue: String, val label: String) {
    IndexFx("index_fx", "지수·환율"),
    Bond("bond", "채권"),
    Commodity("commodity", "원자재"),
    Crypto("crypto", "가상자산")
}

private enum class MarketIndicatorRegion(val apiValue: String, val label: String) {
    All("all", "전체"),
    Domestic("domestic", "국내"),
    Overseas("overseas", "해외")
}

@Composable
private fun <T> IndicatorSegmentSwitch(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { item ->
                val isSelected = selected == item
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(item) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        label(item),
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketIndicatorsScreen(
    app: QuantAppState,
    onBack: () -> Unit,
    marketIndicatorsViewModel: MarketIndicatorsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(MarketIndicatorCategory.IndexFx) }
    var region by remember { mutableStateOf(MarketIndicatorRegion.All) }
    val showRegionFilter = category == MarketIndicatorCategory.IndexFx || category == MarketIndicatorCategory.Bond
    val indicatorQuotes = marketIndicatorsViewModel.marketIndicators
    val indicatorHistory = marketIndicatorsViewModel.marketIndicatorHistory

    LaunchedEffect(category) {
        if (indicatorQuotes.none { it.category == category.apiValue } && !marketIndicatorsViewModel.loading) {
            marketIndicatorsViewModel.refreshMarketIndicators(category = category.apiValue)
        }
        if (indicatorQuotes.any { isMarketSessionOpen(it, Instant.now()) }) {
            marketIndicatorsViewModel.refreshMarketIndicators(refresh = true, category = category.apiValue)
        }
    }

    LaunchedEffect(category) {
        while (true) {
            delay(60_000)
            val now = Instant.now()
            if (marketIndicatorsViewModel.marketIndicators.any { isMarketSessionOpen(it, now) }) {
                marketIndicatorsViewModel.refreshMarketIndicators(category = category.apiValue, automatic = true)
            }
        }
    }

    val filtered = remember(indicatorQuotes, category, region) {
        indicatorQuotes.filter { item ->
            item.category == category.apiValue &&
                (!showRegionFilter || region == MarketIndicatorRegion.All || item.region == region.apiValue)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(56.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                    Text(
                        "주요 지수",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(onClick = {
                        marketIndicatorsViewModel.refreshMarketIndicators(refresh = true, category = category.apiValue)
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            IndicatorSegmentSwitch(
                options = MarketIndicatorCategory.entries,
                selected = category,
                label = { it.label },
                onSelect = { category = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            if (showRegionFilter) {
                IndicatorSegmentSwitch(
                    options = MarketIndicatorRegion.entries,
                    selected = region,
                    label = { it.label },
                    onSelect = { region = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        MarketIndicatorEmptyState(
                            loading = marketIndicatorsViewModel.loading,
                            error = marketIndicatorsViewModel.error,
                            category = category,
                            region = if (showRegionFilter) region else null,
                            onRetry = {
                                marketIndicatorsViewModel.refreshMarketIndicators(refresh = true, category = category.apiValue)
                            }
                        )
                    }
                }
                items(filtered, key = { it.symbol }) { item ->
                    MarketIndicatorRow(
                        item = item,
                        points = indicatorHistory[item.symbol].orEmpty(),
                        watched = app.isWatched(item.symbol),
                        onWatchToggle = {
                            scope.launchSafely { app.toggleWatch(marketIndicatorWatchItem(item)) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketIndicatorEmptyState(
    loading: Boolean,
    error: String?,
    category: MarketIndicatorCategory,
    region: MarketIndicatorRegion?,
    onRetry: () -> Unit
) {
    val title = when {
        loading -> "주요 지수 로딩 중"
        error != null -> "지수 데이터를 불러오지 못했습니다"
        else -> "표시할 지수 없음"
    }
    val detail = when {
        loading -> "${category.label} 데이터를 확인하고 있습니다."
        error != null -> error
        region != null -> "${category.label} · ${region.label} 조건에 맞는 지수가 없습니다."
        else -> "${category.label} 조건에 맞는 지수가 없습니다."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.AutoGraph,
                        contentDescription = null,
                        tint = if (error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!loading) {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("새로고침")
                }
            }
        }
    }
}

@Composable
private fun MarketIndicatorRow(
    item: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>,
    watched: Boolean,
    onWatchToggle: () -> Unit
) {
    val positive = (item.changePct ?: 0.0) >= 0.0
    val color = if (positive) QuantPositive else QuantNegative
    val chartPoints = remember(item, points) {
        displayMarketIndicatorPoints(item, points)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IndicatorSparkline(
            item = item,
            points = chartPoints,
            color = color,
            modifier = Modifier.size(width = 86.dp, height = 58.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    indicatorValueText(item.value),
                    color = color,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${signedNumber(item.changeAbs)} (${pct(item.changePct)})",
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .quantClickable(role = QuantPressRole.Icon, onClick = onWatchToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (watched) "관심 지수 삭제" else "관심 지수 추가",
                tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
fun IndicatorSparkline(
    item: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val samples = remember(item, points) { sparklineSamples(item, points) }
    val values = remember(samples) { samples.map { it.close }.filter { it.isFinite() } }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(item.symbol) {
        while (true) {
            now = Instant.now()
            delay(30_000)
        }
    }
    val showLiveEndpoint = remember(item.symbol, samples, now) {
        shouldShowLiveEndpoint(item, samples, now)
    }
    val endpointPulse by rememberInfiniteTransition(label = "indicatorEndpointPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "indicatorEndpointHalo"
    )
    Canvas(modifier) {
        val referenceClose = previousClose(item)
        val domainValues = values + listOf(item.value).filter { it.isFinite() }
        val domain = sparklineDomain(domainValues, referenceClose)
        fun yPosition(value: Double): Float {
            val span = max(domain.second - domain.first, 0.0001)
            val usableHeight = max(size.height - 8.dp.toPx(), 1f)
            return size.height - (((value - domain.first) / span).toFloat() * usableHeight) - 4.dp.toPx()
        }
        val baselineY = referenceClose?.let(::yPosition) ?: (size.height * 0.72f)
        drawLine(
            color = color.copy(alpha = 0.14f),
            start = Offset(0f, baselineY),
            end = Offset(size.width, baselineY),
            strokeWidth = 1.dp.toPx()
        )

        if (samples.size == 1) {
            val sample = samples.first()
            val x = sample.progress.coerceIn(0f, 1f) * size.width
            val y = yPosition(sample.close)
            drawCircle(color = color, radius = 2.4.dp.toPx(), center = Offset(x, y))
            return@Canvas
        }

        if (values.size < 2) {
            drawLine(
                color = color.copy(alpha = 0.45f),
                start = Offset(0f, baselineY),
                end = Offset(size.width, baselineY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        val line = Path()
        val fill = Path()
        var lastX = 0f
        var lastPoint: Offset? = null
        val fillAnchorY = sparklineFillAnchorY(
            samples = samples,
            referenceClose = referenceClose,
            baselineY = baselineY,
            chartBottom = size.height
        )

        samples.forEachIndexed { index, sample ->
            val value = sample.close
            val x = sample.progress.coerceIn(0f, 1f) * size.width
            lastX = x
            val y = yPosition(value)
            lastPoint = Offset(x, y)
            if (index == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, fillAnchorY)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(lastX, fillAnchorY)
        fill.close()

        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.14f), color.copy(alpha = 0.01f))
            )
        )
        drawPath(line, color = color, style = Stroke(width = 1.55.dp.toPx(), cap = StrokeCap.Round))
        if (showLiveEndpoint) {
            lastPoint?.let { point ->
                val haloRadius = (5.0f + 3.3f * endpointPulse).dp.toPx()
                val haloAlpha = 0.22f - 0.07f * endpointPulse
                drawCircle(color = color.copy(alpha = haloAlpha), radius = haloRadius, center = point)
                drawCircle(color = color, radius = 3.1.dp.toPx(), center = point)
            }
        }
    }
}

private data class SparklineSample(
    val close: Double,
    val progress: Float,
    val instant: Instant?
)

private const val INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT = 48
private const val INDICATOR_SPARKLINE_MIN_RELATIVE_SPAN = 0.024
private const val INDICATOR_SPARKLINE_PADDING_RATIO = 0.10

private fun previousClose(item: MarketIndicatorQuote): Double? {
    val changeAbs = item.changeAbs
    if (item.value.isFinite() && changeAbs != null && changeAbs.isFinite()) {
        return item.value - changeAbs
    }
    val changePct = item.changePct
    if (item.value.isFinite() && changePct != null && changePct.isFinite() && changePct > -0.9999) {
        return item.value / (1.0 + changePct)
    }
    return null
}

private fun sparklineDomain(values: List<Double>, referenceClose: Double?): Pair<Double, Double> {
    val clean = values.filter { it.isFinite() } + listOfNotNull(referenceClose).filter { it.isFinite() }
    val minValue = clean.minOrNull() ?: return 0.0 to 1.0
    val maxValue = clean.maxOrNull() ?: return 0.0 to 1.0
    val anchor = clean.firstOrNull { it.isFinite() && abs(it) > 0.0001 } ?: max(abs(maxValue), 1.0)
    val minimumSpan = max(abs(anchor) * INDICATOR_SPARKLINE_MIN_RELATIVE_SPAN, 0.0001)
    val spread = max(maxValue - minValue, minimumSpan)
    val midpoint = (minValue + maxValue) / 2.0
    val padding = spread * INDICATOR_SPARKLINE_PADDING_RATIO
    return (midpoint - spread / 2.0 - padding) to (midpoint + spread / 2.0 + padding)
}

private fun sparklineFillAnchorY(
    samples: List<SparklineSample>,
    referenceClose: Double?,
    baselineY: Float,
    chartBottom: Float
): Float {
    return chartBottom
}

private data class MarketSession(
    val zone: ZoneId,
    val start: LocalTime,
    val end: LocalTime
)

private val fallbackSparklineProgress = listOf(0f, 0.18f, 0.33f, 0.48f, 0.62f, 0.78f, 1f)

private fun sparklineSamples(
    item: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>
): List<SparklineSample> {
    val clean = stableMarketIndicatorPoints(points, item)
    if (clean.isEmpty()) return emptyList()

    val session = marketSessionFor(item)
    if (session != null) {
        val rawSessionSamples = clean.mapNotNull { point ->
            val instant = parseMarketInstant(point.timestamp) ?: return@mapNotNull null
            val progress = sessionProgress(instant, session) ?: return@mapNotNull null
            SparklineSample(
                close = point.close,
                progress = progress,
                instant = instant
            )
        }
        val latestSessionDate = rawSessionSamples
            .mapNotNull { sample -> sample.instant?.atZone(session.zone)?.toLocalDate() }
            .maxOrNull()
        val sessionSamples = rawSessionSamples
            .filter { sample ->
                latestSessionDate == null || sample.instant?.atZone(session.zone)?.toLocalDate() == latestSessionDate
            }
            .sortedBy { it.progress }
        val now = Instant.now()
        if (isMarketSessionOpen(item, now) && latestSessionDate != now.atZone(session.zone).toLocalDate()) {
            fallbackSessionSamples(item, session, now)?.let { return it }
        }
        if (hasUsableTimeline(sessionSamples)) {
            return downsampleSparklineSamples(sessionSamples)
        }
    }

    return indexedSparklineSamples(clean)
}

private fun fallbackSessionSamples(
    item: MarketIndicatorQuote,
    session: MarketSession,
    now: Instant
): List<SparklineSample>? {
    val points = stableMarketIndicatorPoints(fallbackMarketIndicatorPoints(item), item)
    if (points.isEmpty()) return null

    val interval = marketSessionInterval(now, session)
    if (now.isBefore(interval.first) || now.isAfter(interval.second)) return null
    val progressCap = (sessionProgress(now, session) ?: return null).coerceAtLeast(0.01f)
    val totalMillis = (interval.second.toEpochMilli() - interval.first.toEpochMilli()).coerceAtLeast(1L)
    val denominator = (points.size - 1).coerceAtLeast(1)
    val samples = points.mapIndexed { index, point ->
        val baseProgress = fallbackSparklineProgress.getOrElse(index) {
            index.toFloat() / denominator
        }
        val progress = (baseProgress * progressCap).coerceIn(0f, 1f)
        SparklineSample(
            close = point.close,
            progress = progress,
            instant = interval.first.plusMillis((totalMillis * progress).roundToLong())
        )
    }
    return if (hasUsableTimeline(samples)) downsampleSparklineSamples(samples) else null
}

private fun indexedSparklineSamples(points: List<MarketIndicatorPoint>): List<SparklineSample> {
    val clean = points.filter { it.close.isFinite() }
    if (clean.size == 1) {
        return listOf(SparklineSample(clean.first().close, 0f, parseMarketInstant(clean.first().timestamp)))
    }
    val samples = clean.mapIndexed { index, point ->
        SparklineSample(
            close = point.close,
            progress = index.toFloat() / (clean.size - 1).coerceAtLeast(1),
            instant = parseMarketInstant(point.timestamp)
        )
    }
    return downsampleSparklineSamples(samples)
}

private fun downsampleSparklineSamples(samples: List<SparklineSample>): List<SparklineSample> {
    if (samples.size <= INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT || INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT <= 1) {
        return samples
    }

    val lastIndex = samples.lastIndex
    val targetLastIndex = INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT - 1
    val output = ArrayList<SparklineSample>(INDICATOR_SPARKLINE_MAX_SAMPLE_COUNT)

    for (targetIndex in 0..targetLastIndex) {
        val sourceIndex = ((targetIndex.toDouble() * lastIndex.toDouble()) / targetLastIndex.toDouble())
            .toInt()
            .coerceIn(0, lastIndex)
        val sample = samples[sourceIndex]
        if (output.lastOrNull() != sample) {
            output.add(sample)
        }
    }

    val last = samples.last()
    if (output.lastOrNull() != last) {
        output.add(last)
    }
    return output
}

private fun hasUsableTimeline(samples: List<SparklineSample>): Boolean {
    if (samples.size <= 1) return samples.isNotEmpty()
    val distinctProgressCount = samples
        .map { it.progress }
        .fold(mutableListOf<Float>()) { distinct, progress ->
            if (distinct.none { abs(it - progress) < 0.0001f }) {
                distinct.add(progress)
            }
            distinct
        }
        .size
    return distinctProgressCount >= min(2, samples.size)
}

private fun marketSessionFor(item: MarketIndicatorQuote): MarketSession? {
    return when (item.symbol.uppercase(Locale.US)) {
        "^IXIC", "NQ=F", "^GSPC", "ES=F", "RTY=F", "^DJI", "^SOX", "^VIX" -> MarketSession(
            zone = ZoneId.of("America/New_York"),
            start = LocalTime.of(9, 30),
            end = LocalTime.of(16, 0)
        )
        "^KS11", "^KQ11" -> MarketSession(
            zone = ZoneId.of("Asia/Seoul"),
            start = LocalTime.of(9, 0),
            end = LocalTime.of(15, 30)
        )
        else -> null
    }
}

private fun sessionProgress(instant: Instant, session: MarketSession): Float? {
    val (start, end) = marketSessionInterval(instant, session)
    if (instant.isBefore(start) || instant.isAfter(end)) return null
    val totalMillis = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
    val elapsedMillis = instant.toEpochMilli() - start.toEpochMilli()
    return (elapsedMillis.toDouble() / totalMillis.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun marketSessionInterval(instant: Instant, session: MarketSession): Pair<Instant, Instant> {
    val local = instant.atZone(session.zone)
    val start = ZonedDateTime.of(local.toLocalDate(), session.start, session.zone).toInstant()
    val end = ZonedDateTime.of(local.toLocalDate(), session.end, session.zone).toInstant()
    return start to end
}

private fun shouldShowLiveEndpoint(
    item: MarketIndicatorQuote,
    samples: List<SparklineSample>,
    now: Instant
): Boolean {
    val session = marketSessionFor(item) ?: return false
    val lastInstant = samples.lastOrNull()?.instant ?: return false
    if (!isMarketSessionOpen(item, now)) return false
    if (lastInstant.atZone(session.zone).toLocalDate() != now.atZone(session.zone).toLocalDate()) return false
    val ageMillis = now.toEpochMilli() - lastInstant.toEpochMilli()
    return ageMillis in -60_000L..(2 * 60 * 60 * 1000L)
}

private fun isMarketSessionOpen(item: MarketIndicatorQuote, now: Instant = Instant.now()): Boolean {
    val session = marketSessionFor(item) ?: return false
    val local = now.atZone(session.zone)
    if (local.dayOfWeek.value !in 1..5) return false
    val start = ZonedDateTime.of(local.toLocalDate(), session.start, session.zone)
    val end = ZonedDateTime.of(local.toLocalDate(), session.end, session.zone)
    return !now.isBefore(start.toInstant()) && !now.isAfter(end.toInstant())
}

fun parseMarketInstant(raw: String): Instant? {
    if (raw.isBlank()) return null
    return try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (_: DateTimeParseException) {
        runCatching { Instant.parse(raw) }.getOrNull()
    }
}
