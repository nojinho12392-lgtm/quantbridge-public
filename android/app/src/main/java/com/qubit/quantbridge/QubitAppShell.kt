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

@Composable
internal fun QubitScrollEffects(content: @Composable () -> Unit) {
    val overscrollFactory = rememberPlatformOverscrollFactory(
        glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    )

    CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory) {
        content()
    }
}

internal suspend fun PagerState.navigateToMainPage(page: Int) {
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

internal val QuantRouteEasing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)
internal const val QUANT_ROUTE_ENTER_MS = 260
internal const val QUANT_ROUTE_EXIT_MS = 210
internal const val QUANT_ROUTE_FADE_MS = 160
internal const val DETAIL_PRICE_AUTO_REFRESH_MS = 300_000L

internal enum class RootSurfaceType {
    Main,
    MarketIndicators
}

internal data class RootSurfaceState(
    val type: RootSurfaceType
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QubitApp() {
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
