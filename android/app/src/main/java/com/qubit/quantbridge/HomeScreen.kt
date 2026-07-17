package com.qubit.quantbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: QuantAppState,
    onMarketIndicators: () -> Unit = {},
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var selectedArticle by remember { mutableStateOf<NewsItem?>(null) }
    val profile = app.investmentProfile
    val topPortfolio = remember(homeViewModel.usPortfolio, homeViewModel.krPortfolio, profile) {
        (homeViewModel.usPortfolio + homeViewModel.krPortfolio)
            .sortedByDescending { candidateActionPriority(it, profile) }
            .take(3)
    }
    val topSmall = remember(homeViewModel.usSmallCap, homeViewModel.krSmallCap, profile) {
        (homeViewModel.usSmallCap + homeViewModel.krSmallCap)
            .sortedByDescending { smallCapActionPriority(it, profile) }
            .take(3)
    }
    val topNews = remember(homeViewModel.newsItems) { homeViewModel.newsItems.take(3) }
    val upcomingEarnings = remember(homeViewModel.earningsCalendar) {
        homeViewModel.earningsCalendar
            .filter { (it.daysUntil ?: 0) >= 0 }
            .sortedBy { it.nextEarningsDate }
            .take(3)
    }
    val dashboardLoading = homeViewModel.loading || app.initialDashboardRetrying
    val retryHome = { homeViewModel.refreshHome(force = true) }
    val actionInboxState = rememberHomeActionInboxState(context)
    val homeActionItems = remember(topPortfolio, topSmall, upcomingEarnings, topNews, app.watchlist.size) {
        homeActionInboxItems(
            app = app,
            topPortfolio = topPortfolio.firstOrNull(),
            topSmallCap = topSmall.firstOrNull(),
            nextEarnings = upcomingEarnings.firstOrNull(),
            topNews = topNews.firstOrNull(),
            onOpenNews = { selectedArticle = it }
        )
    }
    val homeDecisionItems = remember(
        homeActionItems,
        homeViewModel.macro,
        homeViewModel.signalEvents,
        app.marketIndices
    ) {
        homeDecisionItems(
            app = app,
            homeViewModel = homeViewModel,
            actionItems = homeActionItems,
            onMarketIndicators = onMarketIndicators
        )
    }
    val watchItems = app.watchlist.toList()
    val watchPriceKey = watchItems.joinToString("|") { "${it.market}:${it.ticker}" }
    LaunchedEffect(watchPriceKey) {
        if (watchPriceKey.isBlank()) return@LaunchedEffect
        delay(650)
        runCatching { app.refreshWatchPriceMetrics(force = true, automatic = true) }
    }
    LaunchedEffect("home-watch-price-auto", watchPriceKey) {
        if (watchPriceKey.isBlank()) return@LaunchedEffect
        while (true) {
            delay(HOME_PRICE_AUTO_REFRESH_MS)
            runCatching { app.refreshWatchPriceMetrics(force = true, automatic = true) }
        }
    }
    LaunchedEffect("home-candidate-price-auto") {
        while (true) {
            delay(HOME_CANDIDATE_PRICE_AUTO_REFRESH_MS)
            homeViewModel.refreshPortfolioPrices(automatic = true)
        }
    }
    val watchCompanyCount = watchItems.count { !it.isMarketIndicatorWatchItem() }
    val watchIndicatorCount = watchItems.size - watchCompanyCount
    var secondaryHomeReady by remember { mutableStateOf(false) }
    val watchSignals = remember(
        secondaryHomeReady,
        watchItems,
        app.watchPriceMetrics,
        app.investmentProfile,
        homeViewModel.signalEvents,
        homeViewModel.usPortfolio,
        homeViewModel.krPortfolio,
        homeViewModel.usSmallCap,
        homeViewModel.krSmallCap,
        homeViewModel.usEarnings,
        homeViewModel.krEarnings,
        homeViewModel.earningsCalendar
    ) {
        if (secondaryHomeReady) {
            homeWatchSignals(app, homeViewModel, watchItems, onMarketIndicators)
        } else {
            emptyList()
        }
    }
    val appContext = context.applicationContext
    var notificationsEnabled by remember(appContext) {
        mutableStateOf(QubitNotificationScheduler.isEnabled(appContext))
    }
    var notificationsAllowed by remember(appContext) {
        mutableStateOf(QubitNotificationScheduler.canPostNotifications(appContext))
    }
    var notificationTitle by remember(appContext) {
        mutableStateOf(QubitNotificationScheduler.statusTitle(appContext))
    }
    var notificationDetail by remember(appContext) {
        mutableStateOf(QubitNotificationScheduler.statusDetail(appContext))
    }
    fun refreshNotificationStatus() {
        notificationsEnabled = QubitNotificationScheduler.isEnabled(appContext)
        notificationsAllowed = QubitNotificationScheduler.canPostNotifications(appContext)
        notificationTitle = QubitNotificationScheduler.statusTitle(appContext)
        notificationDetail = QubitNotificationScheduler.statusDetail(appContext)
    }
    val notificationEvents = remember(secondaryHomeReady, watchSignals, watchItems) {
        if (secondaryHomeReady) {
            homeWatchNotificationEvents(watchSignals, watchItems)
        } else {
            emptyList()
        }
    }
    val notificationEventKey = notificationEvents.joinToString("|") {
        "${it.id}:${it.daysUntil ?: -1}:${it.title}:${it.body}"
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || QubitNotificationScheduler.canPostNotifications(appContext)) {
            QubitNotificationScheduler.setEnabled(appContext, true)
        }
        refreshNotificationStatus()
    }
    val enableNotifications = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            QubitNotificationScheduler.setEnabled(appContext, true)
            refreshNotificationStatus()
        }
    }
    val disableNotifications = {
        QubitNotificationScheduler.setEnabled(appContext, false)
        refreshNotificationStatus()
    }
    val openNotificationSettings = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    }

    LaunchedEffect(Unit) {
        delay(250)
        homeViewModel.refreshHome()
    }
    LaunchedEffect(Unit) {
        delay(500)
        secondaryHomeReady = true
    }
    LaunchedEffect(notificationsEnabled, notificationsAllowed, notificationEventKey, watchItems.size) {
        delay(1_000)
        if (!secondaryHomeReady || (!notificationsEnabled && notificationEvents.isEmpty())) return@LaunchedEffect
        QubitNotificationScheduler.sync(
            appContext,
            notificationEvents,
            homeDailyNotificationSummary(watchSignals, watchItems)
        )
        refreshNotificationStatus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 26.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            HomeTemplateIntro()
        }
        item {
            HomeTemplateDecisionStack(
                items = homeDecisionItems.take(3),
                profile = app.investmentProfile,
                isLoading = dashboardLoading,
                onRefresh = retryHome
            )
        }
        if (!homeViewModel.hasDashboardData && !homeViewModel.loading && !app.initialDashboardRetrying) {
            item {
                HomeInitialDataRecoveryCard(
                    message = homeViewModel.error ?: "첫 로딩에서 표시할 데이터가 아직 없습니다.",
                    onRetry = retryHome
                )
            }
        }
        item {
            HomeTemplateWatchSection(
                app = app,
                homeViewModel = homeViewModel,
                signals = watchSignals,
                watchItems = watchItems,
                notificationTitle = notificationTitle,
                notificationDetail = notificationDetail,
                notificationsEnabled = notificationsEnabled,
                notificationsAllowed = notificationsAllowed,
                onEnableNotifications = enableNotifications,
                onDisableNotifications = disableNotifications,
                onOpenNotificationSettings = openNotificationSettings,
                isLoading = dashboardLoading || !secondaryHomeReady,
                onMore = { app.selectedTab = AppTab.Watch },
                onRetry = retryHome
            )
        }
        item {
            HomePersonalLensCard(
                profile = app.investmentProfile,
                onOpenProfile = { app.selectedTab = AppTab.Account }
            )
        }
        item {
            HomeTemplateCandidateList(
                stocks = topPortfolio,
                profile = app.investmentProfile,
                isLoading = dashboardLoading,
                onMore = { app.selectedTab = AppTab.Portfolio },
                onFilter = { app.selectedTab = AppTab.Portfolio },
                onOpen = { stock ->
                    val currency = marketCurrency(stock.ticker, stock.market)
                    app.selectedDetail = portfolioDetail(stock, currency)
                },
                onRetry = retryHome
            )
        }
        item {
            HomeCoverageCurationCard(
                coveredCount = homeViewModel.usPortfolio.size + homeViewModel.krPortfolio.size + homeViewModel.usSmallCap.size + homeViewModel.krSmallCap.size,
                watchCompanyCount = watchCompanyCount,
                onOpen = { app.selectedTab = AppTab.Search }
            )
        }
    }
    selectedArticle?.let { item ->
        InAppNewsBrowserDialog(
            item = item,
            onDismiss = { selectedArticle = null }
        )
    }
}
