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

@Composable
private fun HomeTemplateIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "DAILY BRIEF",
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .padding(horizontal = 13.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Text(
            "오늘의 요약",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}

@Composable
private fun HomePersonalLensCard(profile: InvestmentProfile, onOpenProfile: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenProfile),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = if (profile.isConfigured) LucideIcon.Target else LucideIcon.SlidersHorizontal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (profile.isConfigured) "내 기준 브리핑" else "내 기준을 먼저 설정해보세요",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (profile.isConfigured) personalLensDetail(profile) else "투자 성향을 저장하면 홈 후보를 내 기준으로 다시 읽을 수 있습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp
                    )
                }
                Text(
                    if (profile.isConfigured) "수정" else "진단",
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (profile.isConfigured) {
                Text(
                    profile.operatingStatement,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeLensPill("중점", personalLensFocus(profile), LucideIcon.Target, Modifier.weight(1f))
                    HomeLensPill("주의", profile.guardrailSummary, LucideIcon.ShieldCheck, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeLensPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                    HomeLensPill("리마인드", profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeLensPill(title: String, value: String, icon: LucideIcon, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeCoverageCurationCard(
    coveredCount: Int,
    watchCompanyCount: Int,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .quantClickable(role = QuantPressRole.Card, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Database,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "분석 커버리지",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    "분석 후보 ${coveredCount.coerceAtLeast(0)}개 · 관심 추적 ${watchCompanyCount.coerceAtLeast(0)}개",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "검색",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

private fun personalLensFocus(profile: InvestmentProfile): String {
    return profile.style.ifBlank {
        profile.riskTolerance.ifBlank {
            profile.horizon.ifBlank { "맞춤 기준" }
        }
    }
}

private fun personalLensDetail(profile: InvestmentProfile): String {
    val focus = personalLensFocus(profile)
    val horizon = profile.horizon.ifBlank { "관찰 기간" }
    return "$focus 관점으로 후보를 보고, $horizon 안에 확인할 조건을 먼저 정리하세요."
}

@Composable
private fun HomeTemplateDecisionStack(
    items: List<HomeActionItem>,
    profile: InvestmentProfile,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeTodayCheckHeader(profile)
        if (items.isEmpty()) {
            HomeSectionFallbackCard(
                title = if (isLoading) "요약 생성 중" else "오늘의 요약 대기",
                message = if (isLoading) "시장과 후보 데이터를 불러와 오늘 볼 항목을 정리하고 있습니다." else "새로고침하면 최신 판단 항목을 다시 계산합니다.",
                isLoading = isLoading,
                onRetry = onRefresh
            )
            return@Column
        }
        items.take(3).forEach { item ->
            HomeTemplateDecisionCard(
                item = item,
                featured = false,
                onOpen = item.onOpen
            )
        }
    }
}

@Composable
private fun HomeTodayCheckHeader(profile: InvestmentProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIconView(
                icon = LucideIcon.ListOrdered,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "오늘 확인할 3가지",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (profile.isConfigured) "내 기준 우선" else "기본 우선",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Text(
            if (profile.isConfigured) {
                "소음 필터: ${personalLensFocus(profile)} 관점과 맞지 않는 단기 급등 신호는 후보 비교 뒤에 보세요."
            } else {
                "투자 기준을 저장하면 단기 뉴스보다 내 판단 기준에 맞는 항목을 먼저 보여줍니다."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun HomeTemplateDecisionCard(
    item: HomeActionItem,
    featured: Boolean,
    onOpen: () -> Unit
) {
    val tint = homeActionToneColor(item.tone)
    val background = if (featured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (featured) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (featured) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
    val iconBackground = if (featured) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f) else tint.copy(alpha = 0.18f)
    val iconTint = if (featured) MaterialTheme.colorScheme.onPrimary else tint
    val pillBackground = if (featured) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f) else tint.copy(alpha = 0.16f)
    val pillText = if (featured) MaterialTheme.colorScheme.onPrimary else tint
    val metrics = decisionMetricTokens(item.detail)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (featured) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (featured) 8.dp else 1.dp, pressedElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(iconBackground),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = item.icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    item.actionLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(pillBackground)
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = pillText,
                    maxLines = 1
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (metrics.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        metrics.forEach { metric ->
                            Text(
                                metric,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(pillBackground)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = pillText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTemplateWatchSection(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    signals: List<HomeWatchSignal>,
    watchItems: List<WatchlistItem>,
    notificationTitle: String,
    notificationDetail: String,
    notificationsEnabled: Boolean,
    notificationsAllowed: Boolean,
    onEnableNotifications: () -> Unit,
    onDisableNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    isLoading: Boolean,
    onMore: () -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeTemplateSectionHeader(
            title = "관심종목 브리핑",
            icon = LucideIcon.AudioWaveform,
            actionLabel = "전체 보기",
            onAction = onMore
        )
        HomeNotificationControl(
            title = notificationTitle,
            detail = notificationDetail,
            isEnabled = notificationsEnabled,
            isAllowed = notificationsAllowed,
            onEnable = onEnableNotifications,
            onDisable = onDisableNotifications,
            onOpenSettings = onOpenNotificationSettings
        )
        if (signals.isEmpty()) {
            HomeSectionFallbackCard(
                title = if (isLoading) "관심종목 브리핑 생성 중" else "관심종목 없음",
                message = if (isLoading) "관심 기업과 지수의 변화를 정리하고 있습니다." else "관심 화면에서 기업을 추가하면 이곳에 반복 확인 카드가 표시됩니다.",
                isLoading = isLoading,
                onRetry = onRetry
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                signals.take(8).forEach { signal ->
                    item(key = signal.id) {
                        HomeTemplateWatchCard(
                            signal = signal,
                            display = homeWatchDisplay(app, homeViewModel, signal, watchItems)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeNotificationControl(
    title: String,
    detail: String,
    isEnabled: Boolean,
    isAllowed: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Bell,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val actionText = when {
                isEnabled && !isAllowed -> "설정"
                isEnabled -> "끄기"
                else -> "켜기"
            }
            val actionAccessibilityLabel = when {
                isEnabled && !isAllowed -> "판단 알림 설정 열기"
                isEnabled -> "판단 알림 끄기"
                else -> "판단 알림 켜기"
            }
            OutlinedButton(
                onClick = when {
                    isEnabled && !isAllowed -> onOpenSettings
                    isEnabled -> onDisable
                    else -> onEnable
                },
                modifier = Modifier
                    .height(38.dp)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = actionAccessibilityLabel
                        onClick(label = actionAccessibilityLabel) {
                            when {
                                isEnabled && !isAllowed -> onOpenSettings()
                                isEnabled -> onDisable()
                                else -> onEnable()
                            }
                            true
                        }
                    },
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
            ) {
                Text(actionText, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HomeTemplateSectionHeader(
    title: String,
    icon: LucideIcon,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            actionLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .quantClickable(role = QuantPressRole.Text, onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1
        )
    }
}

@Composable
private fun HomeTemplateWatchCard(signal: HomeWatchSignal, display: HomeWatchDisplay) {
    val changeValue = display.changeValue ?: 0.0
    val tint = when {
        changeValue < 0.0 -> QuantNegative
        changeValue > 0.0 -> QuantPositive
        else -> homeActionToneColor(signal.tone)
    }
    Card(
        modifier = Modifier
            .width(228.dp)
            .height(138.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = signal.onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp, pressedElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (signal.isIndicator) {
                    MarketIndicatorLogoView(
                        ticker = signal.ticker,
                        name = display.name.ifBlank { signal.name.ifBlank { signal.ticker } },
                        size = 52.dp,
                        tint = homeActionToneColor(signal.tone)
                    )
                } else {
                    TickerAvatar(signal.ticker, display.market, size = 52.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        display.name.ifBlank { signal.ticker },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        display.sector,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    AnimatedPriceText(
                        text = display.priceText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        display.changeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HomeMiniSparkline(
                    changeValue = changeValue,
                    tint = tint,
                    modifier = Modifier
                        .width(62.dp)
                        .height(34.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeMiniSparkline(
    changeValue: Double,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val negative = changeValue < 0.0
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        val points = if (negative) {
            listOf(0.00f to 0.22f, 0.18f to 0.38f, 0.36f to 0.31f, 0.56f to 0.58f, 0.76f to 0.50f, 1.00f to 0.78f)
        } else {
            listOf(0.00f to 0.74f, 0.18f to 0.58f, 0.36f to 0.64f, 0.56f to 0.36f, 0.76f to 0.43f, 1.00f to 0.20f)
        }
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val px = x * size.width
            val py = y * size.height
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun HomeTemplateCandidateList(
    stocks: List<PortfolioStock>,
    profile: InvestmentProfile,
    isLoading: Boolean,
    onMore: () -> Unit,
    onFilter: () -> Unit,
    onOpen: (PortfolioStock) -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeTemplateSectionHeader(
            title = "주목해야 할 후보들",
            icon = LucideIcon.Target,
            actionLabel = "전체 보기",
            onAction = onMore
        )
        if (stocks.isEmpty()) {
            HomeSectionFallbackCard(
                title = if (isLoading) "후보 로딩 중" else "후보 없음",
                message = if (isLoading) "모델 점수와 기대수익 데이터를 불러오고 있습니다." else "후보 데이터가 아직 도착하지 않았습니다.",
                isLoading = isLoading,
                onRetry = onRetry
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                stocks.take(3).forEach { stock ->
                    HomeTemplateCandidateRow(stock = stock, profile = profile, onOpen = { onOpen(stock) })
                }
            }
            Text(
                "모든후보 보기",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f))
                    .quantClickable(role = QuantPressRole.Row, onClick = onMore)
                    .padding(vertical = 14.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HomeTemplateCandidateRow(stock: PortfolioStock, profile: InvestmentProfile, onOpen: () -> Unit) {
    val currency = marketCurrency(stock.ticker, stock.market)
    val reason = portfolioHomeReason(stock)
    val compactReason = compactPortfolioReason(reason)
    val personal = remember(profile, stock) { personalizedStockInterpretation(profile, stock) }
    val tint = homeActionToneColor(reason.tone)
    val change = stock.return1M
    val changeColor = when {
        change == null || !change.isFinite() -> MaterialTheme.colorScheme.onSurfaceVariant
        change >= 0.0 -> QuantPositive
        else -> QuantNegative
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp, pressedElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TickerAvatar(stock.ticker, stock.market, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stock.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) }
                        ?: homeSubtitle(stock.market, "후보"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${personal.headline} · ${personal.detail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.width(102.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AnimatedPriceText(
                    text = stock.currentPrice?.let { fmtPx(it, currency) } ?: score(stock.totalScore),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    compactReason,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                change?.takeIf { it.isFinite() }?.let {
                    Text(
                        pct(it),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = changeColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun compactPortfolioReason(reason: HomeCardReason): String {
    return when (reason.title) {
        "기대수익" -> "기대수익"
        "퀄리티" -> "ROIC 우수"
        "성장" -> "성장 확인"
        "주의" -> "타이밍 확인"
        "확인" -> "차트 확인"
        else -> reason.title.take(7)
    }
}

private const val HOME_PRICE_AUTO_REFRESH_MS = 180_000L
private const val HOME_CANDIDATE_PRICE_AUTO_REFRESH_MS = 300_000L

private data class HomeActionInboxState(
    val completedIds: Set<String>,
    val snoozedIds: Set<String>,
    val complete: (String) -> Unit,
    val snooze: (String) -> Unit,
    val reset: () -> Unit
)

private data class HomeActionItem(
    val id: String,
    val title: String,
    val detail: String,
    val actionLabel: String,
    val icon: LucideIcon,
    val tone: DetailTone,
    val priority: Double,
    val onOpen: () -> Unit
)

private data class HomeWatchSignal(
    val id: String,
    val ticker: String,
    val name: String,
    val title: String,
    val detail: String,
    val metric: String,
    val tone: DetailTone,
    val priority: Int,
    val icon: LucideIcon = LucideIcon.Building2,
    val isIndicator: Boolean = false,
    val notificationOption: String? = null,
    val notificationTitle: String? = null,
    val notificationBody: String? = null,
    val notificationDaysUntil: Int? = null,
    val onOpen: () -> Unit
)

private data class HomeWatchDisplay(
    val name: String,
    val sector: String,
    val priceText: String,
    val changeText: String,
    val changeValue: Double?,
    val market: String?
)

private data class DailyRoutineState(
    val completedIds: Set<String>,
    val toggle: (String) -> Unit,
    val reset: () -> Unit
)

@Composable
private fun rememberHomeActionInboxState(context: Context): HomeActionInboxState {
    val todayKey = LocalDate.now().toString()
    val scope = rememberCoroutineScope()
    val repository = remember(context) { UserPreferencesRepository(context.applicationContext) }
    var savedDate by remember { mutableStateOf("") }
    var completedCsv by remember { mutableStateOf("") }
    var snoozedCsv by remember { mutableStateOf("") }

    LaunchedEffect(todayKey) {
        val snapshot = repository.homeActionInboxSnapshot()
        if (snapshot.date != todayKey) {
            savedDate = todayKey
            completedCsv = ""
            snoozedCsv = ""
            repository.setHomeActionInbox(todayKey, emptySet(), emptySet())
        } else {
            savedDate = snapshot.date
            completedCsv = encodeCsvSet(snapshot.completedIds)
            snoozedCsv = encodeCsvSet(snapshot.snoozedIds)
        }
    }

    fun parse(csv: String): Set<String> {
        return csv.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    val completedIds = if (savedDate == todayKey) parse(completedCsv) else emptySet()
    val snoozedIds = if (savedDate == todayKey) parse(snoozedCsv) else emptySet()

    fun save(completed: Set<String>, snoozed: Set<String>) {
        savedDate = todayKey
        completedCsv = completed.sorted().joinToString(",")
        snoozedCsv = snoozed.sorted().joinToString(",")
        scope.launch {
            repository.setHomeActionInbox(todayKey, completed, snoozed)
        }
    }

    return HomeActionInboxState(
        completedIds = completedIds,
        snoozedIds = snoozedIds,
        complete = { id -> save(completedIds + id, snoozedIds - id) },
        snooze = { id -> save(completedIds - id, snoozedIds + id) },
        reset = { save(emptySet(), emptySet()) }
    )
}

private fun homeActionInboxItems(
    app: QuantAppState,
    topPortfolio: PortfolioStock?,
    topSmallCap: SmallCapStock?,
    nextEarnings: EarningsCalendarItem?,
    topNews: NewsItem?,
    onOpenNews: (NewsItem) -> Unit
): List<HomeActionItem> {
    return buildList {
        topPortfolio?.let { stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            val profileNote = candidateProfileNudge(stock, app.investmentProfile)
            if (app.investmentDecision(stock.ticker) == null) {
                add(
                    HomeActionItem(
                        id = "decision-${normalizedTicker(stock.ticker)}",
                        title = "투자 결정서 작성 대기",
                        detail = "${stock.name} · 이유와 주의 신호를 남기면 나중에 판단을 복기할 수 있습니다.",
                        actionLabel = "작성",
                        icon = LucideIcon.Edit,
                        tone = DetailTone.Primary,
                        priority = candidateActionPriority(stock, app.investmentProfile) + 20.0,
                        onOpen = { app.selectedDetail = portfolioDetail(stock, currency) }
                    )
                )
            }
            add(
                HomeActionItem(
                    id = "portfolio-${normalizedTicker(stock.ticker)}",
                    title = if (app.investmentProfile.isConfigured) "내 기준 최우선 후보" else "최우선 후보 점검",
                    detail = listOfNotNull(
                        "${stock.name} · 기대수익 ${pct(stock.expectedReturn)} · 점수 ${score(stock.totalScore)}",
                        profileNote
                    ).joinToString(" · "),
                    actionLabel = "보기",
                    icon = LucideIcon.Target,
                    tone = DetailTone.Primary,
                    priority = candidateActionPriority(stock, app.investmentProfile),
                    onOpen = { app.selectedDetail = portfolioDetail(stock, currency) }
                )
            )
        }
        nextEarnings?.let { item ->
            add(
                HomeActionItem(
                    id = "earnings-${normalizedTicker(item.ticker)}",
                    title = "다가오는 실적 확인",
                    detail = "${item.name} ${earningsCalendarDayText(item.daysUntil)} · ${compactDateText(item.nextEarningsDate)}",
                    actionLabel = "일정",
                    icon = LucideIcon.CalendarClock,
                    tone = DetailTone.Warning,
                    priority = earningsActionPriority(item),
                    onOpen = { app.selectedDetail = earningsCalendarDetail(item) }
                )
            )
        }
        topSmallCap?.let { stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            val profileNote = smallCapProfileNudge(stock, app.investmentProfile)
            add(
                HomeActionItem(
                    id = "smallcap-${normalizedTicker(stock.ticker)}",
                    title = if (app.investmentProfile.isConfigured) "내 기준 스몰캡 비교" else "스몰캡 후보 비교",
                    detail = listOfNotNull(
                        "${stock.name} · 점수 ${stock.totalScore?.let { "%.0f점".format(it) } ?: "-"} · 거래량 ${multipleText(stock.volumeSurge)}",
                        profileNote
                    ).joinToString(" · "),
                    actionLabel = "보기",
                    icon = LucideIcon.Target,
                    tone = DetailTone.Positive,
                    priority = smallCapActionPriority(stock, app.investmentProfile),
                    onOpen = { app.selectedDetail = smallCapDetail(stock, currency) }
                )
            )
        }
        addAll(homeMistakeCoachActions(app, topPortfolio, topSmallCap))
        if (app.watchlist.isNotEmpty()) {
            add(
                HomeActionItem(
                    id = "watch-review",
                    title = "관심종목 조건 정리",
                    detail = "태그와 알림 조건을 정해두면 홈 브리핑이 더 정확해집니다.",
                    actionLabel = "Watch",
                    icon = LucideIcon.Heart,
                    tone = DetailTone.Neutral,
                    priority = 34.0,
                    onOpen = { app.selectedTab = AppTab.Watch }
                )
            )
        }
        topNews?.let { news ->
            add(
                HomeActionItem(
                    id = "news-${news.id}",
                    title = "시장 뉴스 확인",
                    detail = news.title,
                    actionLabel = "뉴스",
                    icon = LucideIcon.Newspaper,
                    tone = DetailTone.Primary,
                    priority = newsActionPriority(app, news),
                    onOpen = { onOpenNews(news) }
                )
            )
        }
    }.distinctBy { it.id }
        .sortedWith(compareByDescending<HomeActionItem> { it.priority }.thenBy { it.title })
        .take(5)
}

private fun homeDecisionItems(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    actionItems: List<HomeActionItem>,
    onMarketIndicators: () -> Unit
): List<HomeActionItem> {
    val marketValue = marketBriefingValue(app, homeViewModel)
    val marketDetail = marketBriefingDetail(app, homeViewModel)
    val marketDecision = HomeActionItem(
        id = "market-regime",
        title = marketValue,
        detail = "$marketValue · $marketDetail",
        actionLabel = "지수",
        icon = LucideIcon.Activity,
        tone = when {
            marketBriefingColor(app, homeViewModel) == QuantWarning -> DetailTone.Warning
            marketBriefingColor(app, homeViewModel) == QuantPositive -> DetailTone.Positive
            else -> DetailTone.Primary
        },
        priority = marketActionPriority(app, homeViewModel),
        onOpen = onMarketIndicators
    )
    return (listOf(marketDecision) + actionItems)
        .distinctBy { it.id }
        .sortedWith(compareByDescending<HomeActionItem> { it.priority }.thenBy { it.title })
}

private fun marketActionPriority(app: QuantAppState, homeViewModel: HomeViewModel): Double {
    val largestIndexMove = app.marketIndices.map { abs(it.changePct) }.maxOrNull() ?: 0.0
    val riskText = "${marketBriefingValue(app, homeViewModel)} ${marketBriefingDetail(app, homeViewModel)}".uppercase(Locale.US)
    val riskBonus = if (riskText.contains("RISK_OFF") || riskText.contains("위험") || riskText.contains("WARNING")) 24.0 else 0.0
    return 48.0 + min(24.0, largestIndexMove * 1_000.0) + riskBonus
}

private fun earningsActionPriority(item: EarningsCalendarItem): Double {
    val days = item.daysUntil ?: return 62.0
    return when {
        days <= 0 -> 96.0
        days <= 3 -> 90.0 - days
        days <= 7 -> 78.0 - days
        else -> 58.0
    }
}

private fun candidateActionPriority(stock: PortfolioStock, profile: InvestmentProfile = InvestmentProfile()): Double {
    val expected = abs(stock.expectedReturn ?: 0.0)
    val rankBonus = max(0.0, 8.0 - (stock.rank ?: 8).toDouble())
    val cautionBonus = if ((stock.expectedReturn ?: 0.0) < 0.0) 18.0 else 0.0
    val scoreBonus = min(10.0, max(0.0, (stock.totalScore ?: 0.0) - 70.0) / 4.0)
    val momentumBonus = min(10.0, homePercentMagnitude(stock.return1M) / 2.0)
    val rankMoveBonus = min(8.0, abs((stock.rankChange ?: 0).toDouble()) * 1.5)
    val freshRankBonus = if ((stock.rankStatus ?: "").contains("신규")) 6.0 else 0.0
    return 54.0 + min(18.0, expected * 100.0) + rankBonus + cautionBonus + scoreBonus + momentumBonus + rankMoveBonus + freshRankBonus +
        profileCandidatePriorityBonus(stock, profile)
}

private fun smallCapActionPriority(stock: SmallCapStock, profile: InvestmentProfile = InvestmentProfile()): Double {
    val scoreBonus = min(14.0, max(0.0, ((stock.totalScore ?: 0.0) - 60.0) / 3.0))
    val volumeBonus = min(16.0, max(0.0, ((stock.volumeSurge ?: 1.0) - 1.0) * 10.0))
    val growthBonus = min(10.0, homePercentMagnitude(stock.revGrowth) / 3.0)
    val rankMoveBonus = min(6.0, abs((stock.rankChange ?: 0).toDouble()) * 1.2)
    return 50.0 + scoreBonus + volumeBonus + growthBonus + rankMoveBonus + profileSmallCapPriorityBonus(stock, profile)
}

private fun profileCandidatePriorityBonus(stock: PortfolioStock, profile: InvestmentProfile): Double {
    if (!profile.isConfigured) return 0.0
    val styleBonus = when {
        profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 12.0 -> 9.0
        profile.style.contains("가치") && (stock.expectedReturn ?: 0.0) > 0.08 -> 8.0
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.grossMargin ?: 0.0) > 0.35) -> 8.0
        profile.style.contains("모멘텀") && homePercentMagnitude(stock.return1M) >= 6.0 -> 7.0
        else -> 0.0
    }
    val riskBonus = if ((profile.riskTolerance.contains("낮") || profile.riskTolerance.contains("안정")) &&
        ((stock.roic ?: 0.0) > 0.10 || (stock.grossMargin ?: 0.0) > 0.30)
    ) 4.0 else 0.0
    val conflicts = candidateConflictLabels(
        profile = profile,
        return1M = stock.return1M,
        expectedReturn = stock.expectedReturn,
        debtEbitda = null,
        volumeSurge = null
    )
    return styleBonus + riskBonus - conflicts.size * 7.0
}

private fun profileSmallCapPriorityBonus(stock: SmallCapStock, profile: InvestmentProfile): Double {
    if (!profile.isConfigured) return 0.0
    val styleBonus = when {
        profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 15.0 -> 9.0
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.fcfMargin ?: 0.0) > 0.06) -> 8.0
        profile.style.contains("모멘텀") && (stock.volumeSurge ?: 1.0) >= 1.8 -> 7.0
        profile.style.contains("가치") && (stock.totalScore ?: 0.0) >= 70.0 -> 5.0
        else -> 0.0
    }
    val conflicts = candidateConflictLabels(
        profile = profile,
        return1M = stock.return1M,
        expectedReturn = null,
        debtEbitda = stock.debtEbitda,
        volumeSurge = stock.volumeSurge
    )
    return styleBonus - conflicts.size * 8.0
}

private fun candidateProfileNudge(stock: PortfolioStock, profile: InvestmentProfile): String? {
    if (!profile.isConfigured) return null
    val positives = buildList {
        if (profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 12.0) add("성장 기준 부합")
        if (profile.style.contains("가치") && (stock.expectedReturn ?: 0.0) > 0.08) add("상대 저평가 후보")
        if (profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.grossMargin ?: 0.0) > 0.35)) add("퀄리티 근거")
        if (profile.style.contains("모멘텀") && homePercentMagnitude(stock.return1M) >= 6.0) add("모멘텀 확인")
    }
    val conflicts = candidateConflictLabels(profile, stock.return1M, stock.expectedReturn, null, null)
    return (positives.take(1) + conflicts.take(1).map { "주의: $it" }).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun smallCapProfileNudge(stock: SmallCapStock, profile: InvestmentProfile): String? {
    if (!profile.isConfigured) return null
    val positives = buildList {
        if (profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 15.0) add("성장 기준 부합")
        if (profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.fcfMargin ?: 0.0) > 0.06)) add("퀄리티 근거")
        if (profile.style.contains("모멘텀") && (stock.volumeSurge ?: 1.0) >= 1.8) add("거래량 확인")
    }
    val conflicts = candidateConflictLabels(profile, stock.return1M, null, stock.debtEbitda, stock.volumeSurge)
    return (positives.take(1) + conflicts.take(1).map { "주의: $it" }).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun candidateConflictLabels(
    profile: InvestmentProfile,
    return1M: Double?,
    expectedReturn: Double?,
    debtEbitda: Double?,
    volumeSurge: Double?
): List<String> {
    if (profile.avoidances.isEmpty()) return emptyList()
    val move = homePercentMagnitude(return1M)
    return buildList {
        if (profile.avoidances.any { it.contains("급등락") } && move >= 14.0) add("급등락")
        if (profile.avoidances.any { it.contains("고평가") } && (expectedReturn ?: 0.0) < 0.0) add("고평가")
        if (profile.avoidances.any { it.contains("부채") } && (debtEbitda ?: 0.0) >= 3.0) add("부채")
        if (profile.avoidances.any { it.contains("거래량") } && (volumeSurge ?: 1.0) >= 2.2) add("거래량 급증")
    }.distinct()
}

private fun homeMistakeCoachActions(
    app: QuantAppState,
    topPortfolio: PortfolioStock?,
    topSmallCap: SmallCapStock?
): List<HomeActionItem> {
    val companyWatches = app.watchlist.filterNot { it.isMarketIndicatorWatchItem() }
    return buildList {
        val weakThesis = companyWatches.firstOrNull { item ->
            val thesis = item.investmentThesis
            thesis.isEmpty || thesis.quality.percent < 80
        }
        if (weakThesis != null) {
            val thesis = weakThesis.investmentThesis
            add(
                HomeActionItem(
                    id = "coach-thesis-${normalizedTicker(weakThesis.ticker)}",
                    title = "가설 미완성 방지",
                    detail = if (thesis.isEmpty) {
                        "${weakThesis.name} · 관심 이유와 무효 조건을 먼저 남기세요."
                    } else {
                        "${weakThesis.name} · ${thesis.quality.missingFields.take(2).joinToString(" · ")} 보강 필요"
                    },
                    actionLabel = "정리",
                    icon = LucideIcon.Lightbulb,
                    tone = DetailTone.Warning,
                    priority = 86.0,
                    onOpen = { app.selectedTab = AppTab.Watch }
                )
            )
        }
        if (companyWatches.size >= 8) {
            add(
                HomeActionItem(
                    id = "coach-watch-spread",
                    title = "관심 분산 경고",
                    detail = "관심 기업 ${companyWatches.size}개입니다. 오늘은 가설 완성도가 낮은 항목부터 줄이세요.",
                    actionLabel = "Watch",
                    icon = LucideIcon.ShieldCheck,
                    tone = DetailTone.Warning,
                    priority = 80.0,
                    onOpen = { app.selectedTab = AppTab.Watch }
                )
            )
        }
        val fomoPortfolio = topPortfolio?.takeIf { stock ->
            homePercentMagnitude(stock.return1M) >= 14.0 &&
                (app.investmentProfile.avoidances.any { it.contains("급등락") } || app.investmentProfile.riskTolerance.contains("낮"))
        }
        fomoPortfolio?.let { stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            add(
                HomeActionItem(
                    id = "coach-fomo-${normalizedTicker(stock.ticker)}",
                    title = "추격매수 방지",
                    detail = "${stock.name} · 1개월 변동 ${pct(stock.return1M)}. 비교 후보와 무효 조건을 먼저 확인하세요.",
                    actionLabel = "보기",
                    icon = LucideIcon.TrendingUp,
                    tone = DetailTone.Warning,
                    priority = 84.0,
                    onOpen = { app.selectedDetail = portfolioDetail(stock, currency) }
                )
            )
        }
        val volatileSmallCap = topSmallCap?.takeIf { stock ->
            (stock.volumeSurge ?: 1.0) >= 2.2 && app.investmentProfile.avoidances.any { it.contains("거래량") || it.contains("급등락") }
        }
        volatileSmallCap?.let { stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            add(
                HomeActionItem(
                    id = "coach-volume-${normalizedTicker(stock.ticker)}",
                    title = "거래량 과열 점검",
                    detail = "${stock.name} · 거래량 ${multipleText(stock.volumeSurge)}. 모멘텀보다 지속 조건을 먼저 보세요.",
                    actionLabel = "보기",
                    icon = LucideIcon.TriangleAlert,
                    tone = DetailTone.Warning,
                    priority = 83.0,
                    onOpen = { app.selectedDetail = smallCapDetail(stock, currency) }
                )
            )
        }
    }
}

private fun homePercentMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}

private fun newsActionPriority(app: QuantAppState, item: NewsItem): Double {
    val move = abs(item.relatedChangePct ?: 0.0)
    val impact = abs(item.impactScore)
    val relatedKeys = (listOf(item.ticker) + item.relatedTickers)
        .flatMap { watchMatchKeys(it) }
        .toSet()
    val watchBonus = if (app.watchlist.any { watchMatchKeys(it.ticker).any { key -> key in relatedKeys } }) 16.0 else 0.0
    return 52.0 + min(24.0, move * 1_000.0) + min(18.0, impact * 18.0) + watchBonus
}

private fun homeWatchSignals(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    watchItems: List<WatchlistItem>,
    onMarketIndicators: () -> Unit
): List<HomeWatchSignal> {
    if (watchItems.isEmpty()) return emptyList()
    return watchItems.map { item ->
        val keys = watchMatchKeys(item.ticker)
        if (item.isMarketIndicatorWatchItem()) {
            val quote = app.marketIndicators.firstOrNull { normalizedTicker(it.symbol) in keys }
            val change = quote?.changePct
            val changeText = change?.let { pct(it) } ?: "변화 대기"
            HomeWatchSignal(
                id = "indicator-${normalizedTicker(item.ticker)}",
                ticker = item.ticker,
                name = item.name,
                title = "관심 지수",
                detail = quote?.let { "${it.label} ${formatIndexValue(it.value)} · $changeText" } ?: "지수 데이터를 감시 중입니다.",
                metric = changeText,
                tone = if (abs(change ?: 0.0) >= 0.015) DetailTone.Warning else DetailTone.Primary,
                priority = if (abs(change ?: 0.0) >= 0.015) 3 else 1,
                icon = LucideIcon.LineChart,
                isIndicator = true,
                notificationOption = if (abs(change ?: 0.0) >= 0.015) "판단 업데이트" else null,
                notificationTitle = "시장 판단 업데이트가 생겼습니다",
                notificationBody = quote?.let { "${it.label} $changeText · 관심종목 판단 전에 시장 온도를 확인하세요." },
                onOpen = onMarketIndicators
            )
        } else {
            homeCompanyWatchSignal(app, homeViewModel, item, keys)
        }
    }
        .sortedWith(compareByDescending<HomeWatchSignal> { it.priority }.thenBy { it.name })
}

private fun homeWatchNotificationEvents(
    signals: List<HomeWatchSignal>,
    watchItems: List<WatchlistItem>
): List<QubitNotificationEvent> {
    return signals.mapNotNull { signal ->
        val option = signal.notificationOption ?: return@mapNotNull null
        val item = notificationWatchItem(signal, watchItems) ?: return@mapNotNull null
        if (!wantsWatchAlert(item, option)) return@mapNotNull null
        QubitNotificationEvent(
            id = signal.id,
            title = signal.notificationTitle ?: signal.title,
            body = signal.notificationBody ?: signal.detail,
            daysUntil = signal.notificationDaysUntil
        )
    }
}

private fun homeDailyNotificationSummary(signals: List<HomeWatchSignal>, watchItems: List<WatchlistItem>): String {
    val primary = signals.firstOrNull { it.priority >= 3 }
    if (primary != null) return "${primary.title}: ${primary.detail}"
    if (watchItems.isEmpty()) return "오늘 볼 후보와 시장 상태를 확인하세요."
    return "관심 항목 ${watchItems.size}개를 기준으로 실적 일정과 변화 신호를 확인하세요."
}

private fun notificationWatchItem(signal: HomeWatchSignal, watchItems: List<WatchlistItem>): WatchlistItem? {
    val signalKeys = watchMatchKeys(signal.ticker)
    return watchItems.firstOrNull { item ->
        watchMatchKeys(item.ticker).any { key -> key in signalKeys }
    }
}

private fun wantsWatchAlert(item: WatchlistItem, option: String): Boolean {
    return watchAlertOptionMatches(item.alertOptions, option)
}

private fun homeCompanyWatchSignal(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    item: WatchlistItem,
    keys: Set<String>
): HomeWatchSignal {
    serverSignalFor(app, homeViewModel, item, keys)?.let { return it }

    val thesis = item.investmentThesis
    if (!thesis.isEmpty && thesis.quality.percent < 80) {
        val missing = thesis.quality.missingFields.take(2).joinToString(" · ")
        return HomeWatchSignal(
            id = "watch-thesis-${normalizedTicker(item.ticker)}",
            ticker = item.ticker,
            name = item.name,
            title = "가설 보강",
            detail = "${item.name} · $missing 보강 후 관찰을 이어가세요.",
            metric = "가설 ${thesis.quality.percent}%",
            tone = DetailTone.Warning,
            priority = 6,
            icon = LucideIcon.Lightbulb,
            notificationOption = "가설 흔들림",
            notificationTitle = "이 종목의 투자 가정이 흔들렸습니다",
            notificationBody = "${item.name} · $missing 항목이 비어 있습니다. 판단 전에 가설을 보강하세요.",
            onOpen = { app.selectedTab = AppTab.Watch }
        )
    }
    if (thesis.isEmpty && item.tags.isEmpty()) {
        return HomeWatchSignal(
            id = "watch-empty-thesis-${normalizedTicker(item.ticker)}",
            ticker = item.ticker,
            name = item.name,
            title = "관심 이유 없음",
            detail = "${item.name}을 왜 보는지 먼저 남기면 이후 신호를 더 차분하게 판단할 수 있습니다.",
            metric = "기록 필요",
            tone = DetailTone.Warning,
            priority = 5,
            icon = LucideIcon.Edit,
            notificationOption = "가설 흔들림",
            notificationTitle = "이 종목의 투자 가정이 흔들렸습니다",
            notificationBody = "${item.name}의 관심 이유와 틀렸다고 볼 조건이 비어 있습니다.",
            onOpen = { app.selectedTab = AppTab.Watch }
        )
    }

    val calendar = homeViewModel.earningsCalendar
        .filter { (it.daysUntil ?: Int.MAX_VALUE) >= 0 }
        .sortedBy { it.nextEarningsDate }
        .firstOrNull { watchTickerMatches(it.ticker, keys) }
    val calendarDays = calendar?.daysUntil
    if (calendar != null && calendarDays != null && calendarDays in 0..7) {
        return HomeWatchSignal(
            id = "watch-calendar-${normalizedTicker(item.ticker)}",
            ticker = item.ticker,
            name = item.name,
            title = "실적 임박",
            detail = "${calendar.name} ${earningsCalendarDayText(calendarDays)} · ${compactDateText(calendar.nextEarningsDate)}",
            metric = earningsCalendarDayText(calendarDays),
            tone = DetailTone.Warning,
            priority = 5,
            icon = LucideIcon.CalendarClock,
            notificationOption = "실적 리스크",
            notificationTitle = "실적 발표 전 확인할 리스크가 생겼습니다",
            notificationBody = "${calendar.name} ${earningsCalendarDayText(calendarDays)} · 가이던스, 마진, 무효 조건을 먼저 확인하세요.",
            notificationDaysUntil = calendarDays,
            onOpen = { app.selectedDetail = earningsCalendarDetail(calendar) }
        )
    }

    val earnings = (homeViewModel.usEarnings + homeViewModel.krEarnings).firstOrNull { watchTickerMatches(it.ticker, keys) }
    val earningsSignal = earnings?.signalStrength
    if (earnings != null && earningsSignal?.isFinite() == true && earningsSignal >= 1.0) {
        return HomeWatchSignal(
            id = "watch-earnings-${normalizedTicker(item.ticker)}",
            ticker = item.ticker,
            name = item.name,
            title = "실적 반응",
            detail = "${earnings.name} Signal ${String.format(Locale.US, "%.2f", earningsSignal)} · 발표 후 ${pct(earnings.returnSince)}",
            metric = "Signal ${String.format(Locale.US, "%.2f", earningsSignal)}",
            tone = DetailTone.Positive,
            priority = 4,
            icon = LucideIcon.Zap,
            notificationOption = "점수·과열 동시",
            notificationTitle = "점수는 올랐지만 과열 신호도 같이 커졌습니다",
            notificationBody = "${earnings.name} Signal ${String.format(Locale.US, "%.2f", earningsSignal)} · 발표 후 ${pct(earnings.returnSince)}. 추격보다 주의 신호를 확인하세요.",
            onOpen = { app.selectedDetail = earningsDetail(earnings) }
        )
    }

    val portfolio = (homeViewModel.usPortfolio + homeViewModel.krPortfolio).firstOrNull { watchTickerMatches(it.ticker, keys) }
    val expectedReturn = portfolio?.expectedReturn
    if (portfolio != null && expectedReturn?.isFinite() == true) {
        val currency = marketCurrency(portfolio.ticker, portfolio.market)
        val negative = expectedReturn < 0
        val personal = personalizedStockInterpretation(app.investmentProfile, portfolio)
        val observation = personal.headline.contains("관찰")
        return HomeWatchSignal(
            id = "watch-portfolio-${normalizedTicker(item.ticker)}",
            ticker = portfolio.ticker,
            name = portfolio.name,
            title = if (negative) "타이밍 확인" else "후보 유지",
            detail = "${portfolio.name} · 기대수익 ${pct(expectedReturn)} · 점수 ${score(portfolio.totalScore)}",
            metric = pct(expectedReturn),
            tone = if (negative) DetailTone.Warning else DetailTone.Primary,
            priority = if (negative) 4 else 3,
            icon = if (negative) LucideIcon.TriangleAlert else LucideIcon.Target,
            notificationOption = when {
                negative -> "가설 흔들림"
                observation -> "성향 관찰"
                else -> null
            },
            notificationTitle = when {
                negative -> "이 종목의 투자 가정이 흔들렸습니다"
                observation -> "네 성향 기준으로는 아직 관찰 단계입니다"
                else -> null
            },
            notificationBody = when {
                negative -> "${portfolio.name} 기대수익 ${pct(expectedReturn)} · 처음 가정과 무효 조건을 다시 확인하세요."
                observation -> "${portfolio.name} · ${personal.detail} ${personal.action}"
                else -> null
            },
            onOpen = { app.selectedDetail = portfolioDetail(portfolio, currency) }
        )
    }

    val small = (homeViewModel.usSmallCap + homeViewModel.krSmallCap).firstOrNull { watchTickerMatches(it.ticker, keys) }
    if (small != null) {
        val currency = marketCurrency(small.ticker, small.market)
        val volume = small.volumeSurge
        return HomeWatchSignal(
            id = "watch-smallcap-${normalizedTicker(item.ticker)}",
            ticker = small.ticker,
            name = small.name,
            title = if ((volume ?: 0.0) >= 1.5) "거래량 변화" else "스몰캡 후보",
            detail = "${small.name} · 점수 ${score(small.totalScore)} · 거래량 ${multipleText(volume)}",
            metric = small.totalScore?.let { String.format(Locale.US, "%.0f점", it) } ?: "-",
            tone = if ((volume ?: 0.0) >= 1.5) DetailTone.Positive else DetailTone.Neutral,
            priority = if ((volume ?: 0.0) >= 1.5) 3 else 2,
            icon = LucideIcon.AudioWaveform,
            notificationOption = if ((volume ?: 0.0) >= 1.5) "점수·과열 동시" else null,
            notificationTitle = if ((volume ?: 0.0) >= 1.5) "점수는 올랐지만 과열 신호도 같이 커졌습니다" else null,
            notificationBody = if ((volume ?: 0.0) >= 1.5) {
                "${small.name} 거래량 ${multipleText(volume)} · 점수 ${score(small.totalScore)}. 지속 조건 전에는 추격을 피하세요."
            } else {
                null
            },
            onOpen = { app.selectedDetail = smallCapDetail(small, currency) }
        )
    }

    return HomeWatchSignal(
        id = "watch-basic-${normalizedTicker(item.ticker)}",
        ticker = item.ticker,
        name = item.name,
        title = "기본 감시",
        detail = "아직 연결된 후보나 실적 신호는 없습니다. Watch에서 태그와 알림 조건을 정리하세요.",
        metric = item.primaryTag ?: item.market,
        tone = DetailTone.Neutral,
        priority = 0,
        icon = LucideIcon.Eye,
        onOpen = { app.selectedTab = AppTab.Watch }
    )
}

private fun homeWatchDisplay(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    signal: HomeWatchSignal,
    watchItems: List<WatchlistItem>
): HomeWatchDisplay {
    val keys = watchMatchKeys(signal.ticker)
    val watch = watchItems.firstOrNull { watchTickerMatches(it.ticker, keys) }
    val portfolio = (homeViewModel.usPortfolio + homeViewModel.krPortfolio).firstOrNull { watchTickerMatches(it.ticker, keys) }
    val small = (homeViewModel.usSmallCap + homeViewModel.krSmallCap).firstOrNull { watchTickerMatches(it.ticker, keys) }
    val indicator = if (signal.isIndicator) {
        app.marketIndicators.firstOrNull { watchTickerMatches(it.symbol, keys) }
    } else {
        null
    }
    val priceMetric = listOfNotNull(signal.ticker, watch?.ticker, portfolio?.ticker, small?.ticker)
        .firstNotNullOfOrNull { app.watchPriceMetric(it) }
    val market = portfolio?.market ?: small?.market ?: watch?.market
    val currency = portfolio?.let { marketCurrency(it.ticker, it.market) }
        ?: small?.let { marketCurrency(it.ticker, it.market) }
        ?: watch?.currency
        ?: marketCurrency(signal.ticker, market)
    val price = priceMetric?.currentPrice ?: portfolio?.currentPrice ?: small?.currentPrice
    val change = if (signal.isIndicator) {
        indicator?.changePct
    } else {
        priceMetric?.dailyChangePct
    }
    val priceText = when {
        price?.isFinite() == true -> fmtPx(price, currency)
        indicator != null -> formatIndexValue(indicator.value)
        signal.metric.isNotBlank() -> signal.metric
        else -> "-"
    }
    val changeText = when {
        change?.isFinite() == true -> pct(change)
        else -> "-"
    }
    val sector = portfolio?.sector?.takeIf { it.isNotBlank() }
        ?: small?.let { homeSubtitle(it.market, "스몰캡") }
        ?: watch?.primaryTag
        ?: watch?.note.takeIf { !it.isNullOrBlank() }
        ?: signal.title
    return HomeWatchDisplay(
        name = portfolio?.name ?: small?.name ?: watch?.name ?: signal.name.ifBlank { signal.ticker },
        sector = sector,
        priceText = priceText,
        changeText = changeText,
        changeValue = change?.takeIf { it.isFinite() },
        market = market
    )
}

private fun watchMatchKeys(ticker: String): Set<String> {
    val normalized = normalizedTicker(ticker)
    if (normalized.isBlank()) return emptySet()
    val keys = mutableSetOf(normalized)
    val code = krCode(normalized)
    if (code.isNotBlank()) {
        keys.add(code)
        keys.add("$code.KS")
        keys.add("$code.KQ")
    }
    return keys
}

private fun watchTickerMatches(ticker: String, keys: Set<String>): Boolean {
    return watchMatchKeys(ticker).any { it in keys }
}

private fun serverSignalFor(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    item: WatchlistItem,
    keys: Set<String>
): HomeWatchSignal? {
    val event = homeViewModel.signalEvents
        .filter { watchTickerMatches(it.ticker, keys) }
        .sortedWith(compareByDescending<SignalEvent> { it.severity }.thenByDescending { it.eventTime ?: it.updatedAt.orEmpty() })
        .firstOrNull()
        ?: return null
    val detail = serverSignalDetailRequest(app, homeViewModel, event, keys)
    return HomeWatchSignal(
        id = "server-${event.eventId}",
        ticker = event.ticker,
        name = event.name.ifBlank { item.name },
        title = event.title,
        detail = event.detail,
        metric = event.metricValue?.takeIf { it.isNotBlank() } ?: event.metricLabel ?: event.market,
        tone = serverSignalTone(event.kind),
        priority = maxOf(2, event.severity + 1),
        icon = serverSignalIcon(event.kind),
        notificationOption = if (event.severity >= 4) serverJudgmentAlertOption(event.kind) else null,
        notificationTitle = if (event.severity >= 4) serverJudgmentNotificationTitle(event) else null,
        notificationBody = if (event.severity >= 4) serverJudgmentNotificationBody(event) else null,
        onOpen = {
            if (detail != null) {
                app.selectedDetail = detail
            } else {
                app.selectedTab = AppTab.Watch
            }
        }
    )
}

private fun serverSignalDetailRequest(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    event: SignalEvent,
    keys: Set<String>
): DetailRequest? {
    if (event.kind == "earnings_due") {
        homeViewModel.earningsCalendar.firstOrNull { watchTickerMatches(it.ticker, keys) }?.let {
            return earningsCalendarDetail(it)
        }
    }
    (homeViewModel.usPortfolio + homeViewModel.krPortfolio).firstOrNull { watchTickerMatches(it.ticker, keys) }?.let {
        return portfolioDetail(it, marketCurrency(it.ticker, it.market))
    }
    (homeViewModel.usSmallCap + homeViewModel.krSmallCap).firstOrNull { watchTickerMatches(it.ticker, keys) }?.let {
        return smallCapDetail(it, marketCurrency(it.ticker, it.market))
    }
    (homeViewModel.usEarnings + homeViewModel.krEarnings).firstOrNull { watchTickerMatches(it.ticker, keys) }?.let {
        return earningsDetail(it)
    }
    return DetailRequest(
        ticker = event.ticker,
        name = event.name,
        currency = marketCurrency(event.ticker, event.market),
        market = event.market,
        sections = listOf(
            DetailSection(
                title = "서버 이벤트",
                metrics = listOfNotNull(
                    DetailMetric("종류", serverSignalKindLabel(event.kind), serverSignalTone(event.kind)),
                    event.metricValue?.let { DetailMetric(event.metricLabel ?: "지표", it, serverSignalTone(event.kind)) },
                    event.eventTime?.let { DetailMetric("발생", formattedUpdateTimestamp(it), DetailTone.Neutral) }
                )
            )
        ),
        signals = listOf(DetailSignal(event.title, event.detail, serverSignalTone(event.kind))),
        factors = emptyList()
    )
}

private fun serverSignalTone(kind: String): DetailTone {
    return when (kind) {
        "price_pressure", "price_drop", "rank_down" -> DetailTone.Negative
        "earnings_due" -> DetailTone.Warning
        "rank_up", "price_momentum", "price_spike" -> DetailTone.Positive
        else -> DetailTone.Primary
    }
}

private fun serverSignalIcon(kind: String): LucideIcon {
    return when (kind) {
        "price_pressure", "price_drop", "rank_down" -> LucideIcon.TriangleAlert
        "earnings_due" -> LucideIcon.CalendarClock
        "rank_up", "price_momentum", "price_spike" -> LucideIcon.TrendingUp
        else -> LucideIcon.Zap
    }
}

private fun serverSignalKindLabel(kind: String): String {
    return when (kind) {
        "price_momentum" -> "1개월 강세"
        "price_pressure" -> "1개월 약세"
        "price_spike" -> "단기 급등"
        "price_drop" -> "단기 급락"
        "rank_up" -> "순위 상승"
        "rank_down" -> "순위 하락"
        "earnings_due" -> "실적 임박"
        else -> "이벤트"
    }
}

private fun serverJudgmentAlertOption(kind: String): String {
    return when (kind) {
        "earnings_due" -> "실적 리스크"
        "price_pressure", "price_drop", "rank_down" -> "가설 흔들림"
        "rank_up", "price_momentum", "price_spike" -> "점수·과열 동시"
        else -> "판단 업데이트"
    }
}

private fun serverJudgmentNotificationTitle(event: SignalEvent): String {
    return when (event.kind) {
        "earnings_due" -> "실적 발표 전 확인할 리스크가 생겼습니다"
        "price_pressure", "price_drop", "rank_down" -> "이 종목의 투자 가정이 흔들렸습니다"
        "rank_up", "price_momentum", "price_spike" -> "점수는 올랐지만 과열 신호도 같이 커졌습니다"
        else -> "관심종목 판단 업데이트가 생겼습니다"
    }
}

private fun serverJudgmentNotificationBody(event: SignalEvent): String {
    val name = event.name.ifBlank { event.ticker }
    return when (event.kind) {
        "earnings_due" -> "$name · ${event.detail} 발표 전 리스크와 무효 조건을 확인하세요."
        "price_pressure", "price_drop", "rank_down" -> "$name · ${event.detail} 처음 투자 가정과 다르게 움직이는지 점검하세요."
        "rank_up", "price_momentum", "price_spike" -> "$name · ${event.detail} 점수 상승과 과열 신호를 함께 확인하세요."
        else -> "$name · ${event.detail}"
    }
}

private fun formatIndexValue(value: Double): String {
    if (!value.isFinite()) return "-"
    return String.format(Locale.US, "%,.2f", value)
}

@Composable
private fun HomeWatchBriefingCard(
    signals: List<HomeWatchSignal>,
    watchCount: Int,
    companyCount: Int,
    indicatorCount: Int,
    isLoading: Boolean
) {
    val signalPages = remember(signals) { signals.chunked(3) }
    val realPageCount = signalPages.size
    val displayPageCount = if (realPageCount > 1) realPageCount + 1 else realPageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { displayPageCount })

    LaunchedEffect(realPageCount) {
        if (realPageCount <= 1) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val next = (pagerState.currentPage + 1).coerceAtMost(realPageCount)
            pagerState.animateScrollToPage(next)
            if (next == realPageCount) {
                delay(320)
                pagerState.scrollToPage(0)
            }
        }
    }

    LaunchedEffect(pagerState, realPageCount) {
        if (realPageCount <= 1) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page == realPageCount) {
                delay(320)
                pagerState.scrollToPage(0)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = QuantFavorite,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(QuantFavorite.copy(alpha = 0.10f))
                        .padding(7.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("관심종목 브리핑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (watchCount == 0) {
                            "관심 기업을 추가하면 홈에서 실적, 후보, 지수 변화를 먼저 보여줍니다."
                        } else {
                            "기업 ${companyCount}개와 지수 ${indicatorCount}개 중 지금 볼 신호 ${signals.count { it.priority >= 2 }}개"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeWatchMetric("관심", watchCount.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                HomeWatchMetric("변화", signals.count { it.priority >= 2 }.toString(), QuantWarning, Modifier.weight(1f))
            }

            if (watchCount == 0) {
                HomeWatchEmptyState()
            } else if (signals.isEmpty() && isLoading) {
                SkeletonLoadingCard(lineCount = 2)
            } else if (signals.isEmpty()) {
                HomeWatchQuietState(watchCount)
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(238.dp),
                    key = { page ->
                        val actualPage = if (realPageCount > 1 && page == realPageCount) 0 else page
                        "$page:${signalPages.getOrNull(actualPage)?.joinToString("|") { it.id } ?: "empty"}"
                    }
                ) { page ->
                    val actualPage = if (realPageCount > 1 && page == realPageCount) 0 else page
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        signalPages.getOrNull(actualPage).orEmpty().forEach { signal ->
                            HomeWatchSignalRow(signal)
                        }
                    }
                }

                if (realPageCount > 1) {
                    val activePage = pagerState.currentPage % realPageCount
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(realPageCount) { index ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(width = if (activePage == index) 14.dp else 5.dp, height = 5.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (activePage == index) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWatchMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeWatchSignalRow(signal: HomeWatchSignal) {
    val color = homeActionToneColor(signal.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .quantClickable(role = QuantPressRole.Row, onClick = signal.onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (signal.isIndicator) {
            MarketIndicatorLogoView(signal.ticker, signal.name, size = 30.dp, tint = color)
        } else {
            TickerAvatar(signal.ticker, null, size = 30.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(signal.title, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(signal.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(signal.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            signal.metric,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun HomeWatchEmptyState() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LucideIconView(
            icon = LucideIcon.Heart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("관심종목을 추가하세요", fontWeight = FontWeight.SemiBold)
            Text("하트로 추가하면 홈이 개인 브리핑처럼 바뀝니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HomeWatchQuietState(watchCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = QuantGreen)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("큰 변화 없음", fontWeight = FontWeight.SemiBold)
            Text("관심 항목 ${watchCount}개를 계속 감시 중입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class HomeRoutineItem(
    val id: String,
    val title: String,
    val detail: String,
    val tint: Color,
    val actionLabel: String,
    val onOpen: () -> Unit
)

@Composable
private fun rememberDailyRoutineState(context: Context): DailyRoutineState {
    val todayKey = LocalDate.now().toString()
    val scope = rememberCoroutineScope()
    val repository = remember(context) { UserPreferencesRepository(context.applicationContext) }
    var savedDate by remember { mutableStateOf("") }
    var completedCsv by remember { mutableStateOf("") }

    LaunchedEffect(todayKey) {
        val snapshot = repository.dailyRoutineSnapshot()
        if (snapshot.date != todayKey) {
            savedDate = todayKey
            completedCsv = ""
            repository.setDailyRoutine(todayKey, emptySet())
        } else {
            savedDate = snapshot.date
            completedCsv = encodeCsvSet(snapshot.completedIds)
        }
    }

    val completedIds = if (savedDate == todayKey) {
        completedCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    } else {
        emptySet()
    }

    fun save(ids: Set<String>) {
        val nextCsv = ids.sorted().joinToString(",")
        savedDate = todayKey
        completedCsv = nextCsv
        scope.launch {
            repository.setDailyRoutine(todayKey, ids)
        }
    }

    return DailyRoutineState(
        completedIds = completedIds,
        toggle = { id ->
            val next = completedIds.toMutableSet()
            if (!next.add(id)) next.remove(id)
            save(next)
        },
        reset = { save(emptySet()) }
    )
}

@Composable
private fun TodayDecisionPanel(
    items: List<HomeActionItem>,
    completedIds: Set<String>,
    snoozedIds: Set<String>,
    latestUpdatedAt: String?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onComplete: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onReset: () -> Unit
) {
    val activeItems = items.filterNot { it.id in completedIds || it.id in snoozedIds }.take(3)
    val handledCount = items.count { it.id in completedIds || it.id in snoozedIds }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
            )
            Column(
                modifier = Modifier
                    .padding(15.dp)
                    .animateContentSize(animationSpec = tween(220)),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "오늘 볼 것 3개",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    QuantIconActionButton(
                        icon = LucideIcon.RefreshCw,
                        contentDescription = if (isLoading) "홈 갱신 중" else "홈 새로고침",
                        onClick = onRefresh
                    )
                }

                if (activeItems.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(QuantGreen.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            LucideIconView(
                                icon = LucideIcon.ShieldCheck,
                                contentDescription = null,
                                tint = QuantGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            "큰 변화가 없으면 후보와 관심 항목을 계속 감시합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    AnimatedContent(
                        targetState = activeItems.joinToString("|") { it.id },
                        transitionSpec = {
                            (fadeIn(tween(170)) + slideInVertically(tween(220)) { it / 5 }) togetherWith
                                (fadeOut(tween(130)) + slideOutVertically(tween(180)) { -it / 5 })
                        },
                        label = "todayDecisionItems"
                    ) { targetItemIds ->
                        val targetItemIdSet = remember(targetItemIds) {
                            targetItemIds.split("|").filter { it.isNotBlank() }.toSet()
                        }
                        val visibleItems = activeItems.filter { it.id in targetItemIdSet }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            visibleItems.forEachIndexed { index, item ->
                                TodayDecisionRow(
                                    item = item,
                                    number = index + 1,
                                    onOpenAndComplete = {
                                        item.onOpen()
                                        onComplete(item.id)
                                    },
                                    onComplete = { onComplete(item.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayDecisionRow(
    item: HomeActionItem,
    number: Int,
    onOpenAndComplete: () -> Unit,
    onComplete: () -> Unit
) {
    val tint = homeActionToneColor(item.tone)
    val metrics = decisionMetricTokens(item.detail)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .quantClickable(role = QuantPressRole.Row, onClick = onOpenAndComplete)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            LucideIconView(
                icon = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                item.detail,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                metrics.forEach { metric ->
                    DecisionMetricChip(value = metric, tint = tint)
                }
                Spacer(Modifier.weight(1f))
                DecisionMetricChip(value = item.actionLabel, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .quantClickable(role = QuantPressRole.Icon, onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            LucideIconView(
                icon = LucideIcon.Square,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private fun decisionMetricTokens(detail: String): List<String> {
    return detail
        .split("·", "|", ",")
        .map { it.trim() }
        .filter { token ->
            token.any { it.isDigit() } ||
                token.contains("%") ||
                token.contains("#") ||
                token.uppercase(Locale.US).contains("D-") ||
                token.uppercase(Locale.US).contains("D+")
        }
        .take(2)
}

@Composable
private fun homeActionToneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun DailyRoutineCard(
    items: List<HomeRoutineItem>,
    completedIds: Set<String>,
    isLoading: Boolean,
    onToggle: (String) -> Unit,
    onReset: () -> Unit
) {
    val completedCount = items.count { completedIds.contains(it.id) }
    val progress = if (items.isEmpty()) 0f else (completedCount.toFloat() / items.size).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "오늘의 체크 루틴",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isLoading) "데이터 갱신 중에도 루틴은 저장됩니다" else "${completedCount}/${items.size} 완료",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "초기화",
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .quantClickable(role = QuantPressRole.Text, onClick = onReset)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(if (completedCount == items.size) QuantGreen else MaterialTheme.colorScheme.primary)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    DailyRoutineRow(
                        item = item,
                        isCompleted = completedIds.contains(item.id),
                        onToggle = { onToggle(item.id) },
                        onOpen = item.onOpen
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyRoutineRow(
    item: HomeRoutineItem,
    isCompleted: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isCompleted) item.tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                .quantClickable(role = QuantPressRole.Icon, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isCompleted) 10.dp else 18.dp)
                    .clip(CircleShape)
                    .background(if (isCompleted) item.tint else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(item.tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(item.tint)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            item.actionLabel,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(item.tint.copy(alpha = 0.08f))
                .quantClickable(role = QuantPressRole.Text, onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = item.tint,
            maxLines = 1
        )
    }
}

@Composable
private fun TodayBriefingPanel(
    app: QuantAppState,
    portfolio: PortfolioStock?,
    nextEarnings: EarningsCalendarItem?,
    isLoading: Boolean,
    onPortfolio: () -> Unit,
    onPulse: () -> Unit,
    onMarket: () -> Unit,
    onRefresh: () -> Unit
) {
    val issueCount = briefingDataIssueCount(app)
    val loadedSourceCount = briefingLoadedSourceCount(app)
    val marketValue = marketBriefingValue(app)
    val marketDetail = marketBriefingDetail(app)
    val marketColor = marketBriefingColor(app)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "오늘의 투자 브리핑",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "갱신 ${formattedUpdateTimestamp(briefingLatestUpdate(app))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "홈 새로고침",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .quantClickable(role = QuantPressRole.Row, onClick = onMarket)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(marketColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(marketColor)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "시장 상태",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        marketValue,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        marketDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "보기",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BriefingActionTile(
                    title = "오늘 볼 후보",
                    value = portfolio?.name ?: "후보 대기",
                    detail = portfolio?.let { portfolioHomeReason(it).detail } ?: "모델 점수 대기 중",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPortfolio,
                    modifier = Modifier.weight(1f)
                )
                BriefingActionTile(
                    title = "다음 실적",
                    value = nextEarnings?.name ?: "일정 대기",
                    detail = earningsCalendarSummary(nextEarnings),
                    tint = QuantPurple,
                    onClick = onPulse,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BriefingActionTile(
                    title = "데이터 상태",
                    value = if (issueCount == 0) "정상" else "확인 $issueCount",
                    detail = "연결 소스 ${loadedSourceCount}개",
                    tint = if (issueCount == 0) QuantGreen else QuantWarning,
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f)
                )
                BriefingActionTile(
                    title = "시장 지표",
                    value = "지수 보기",
                    detail = "개장 흐름과 매크로 확인",
                    tint = QuantNegative,
                    onClick = onMarket,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BriefingActionTile(
    title: String,
    value: String,
    detail: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(94.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.08f))
            .quantClickable(role = QuantPressRole.Row, onClick = onClick)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeEarningsCalendarCard(
    item: EarningsCalendarItem,
    watched: Boolean,
    onWatch: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(288.dp)
            .height(172.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TickerAvatar(item.ticker, item.market, size = 36.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        homeSubtitle(
                            item.market,
                            item.sector?.let { portfolioIndustryLabel(item.ticker, item.name, it) } ?: "어닝 캘린더"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onWatch, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (watched) "관심 해제" else "관심 추가",
                        tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        "예정일",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        compactDateText(item.nextEarningsDate),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
                Text(
                    earningsCalendarDayText(item.daysUntil),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if ((item.daysUntil ?: 99) <= 7) QuantWarning else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Text(
                "발표 전 변동성과 포지션 크기를 같이 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeSectionFallbackCard(
    title: String,
    message: String,
    isLoading: Boolean,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(288.dp)
            .height(156.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("다시 불러오기")
            }
        }
    }
}

@Composable
private fun HomeInitialDataRecoveryCard(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "데이터가 아직 비어 있습니다",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("다시 불러오기")
                }
            }
        }
    }
}

private fun marketBriefingValue(app: QuantAppState): String {
    return displayBriefingRegime(
        firstMacroValue(app.macro, "Regime", "Macro_Regime", "US_Regime", "Market_Regime", "risk_regime")
    ) ?: "시장 상태 대기"
}

private fun marketBriefingDetail(app: QuantAppState): String {
    val risk = firstMacroValue(app.macro, "Risk_Signal", "Risk_Level", "Risk", "Signal")
    if (!risk.isNullOrBlank()) {
        return "위험 신호 ${displayBriefingRegime(risk) ?: risk}"
    }
    val index = app.marketIndices.firstOrNull()
    if (index != null) {
        return "${index.label} ${pct(index.changePct)}"
    }
    return if (app.homeLoading) "시장 데이터 동기화 중" else "주요 지수와 매크로를 함께 확인하세요"
}

private fun marketBriefingColor(app: QuantAppState): Color {
    val risk = firstMacroValue(app.macro, "Risk_Signal", "Risk_Level", "Risk", "Signal").orEmpty()
    val text = "${marketBriefingValue(app)} $risk".uppercase()
    return when {
        text.contains("RISK_OFF") || text.contains("BEAR") || text.contains("WARNING") || text.contains("위험회피") -> QuantWarning
        text.contains("RISK_ON") || text.contains("BULL") || text.contains("PASS") || text.contains("위험선호") -> QuantPositive
        else -> Color.Unspecified
    }.takeUnless { it == Color.Unspecified } ?: Color(0xFF2E63D1)
}

private fun marketBriefingValue(app: QuantAppState, homeViewModel: HomeViewModel): String {
    return displayBriefingRegime(
        firstMacroValue(homeViewModel.macro.ifEmpty { app.macro }, "Regime", "Macro_Regime", "US_Regime", "Market_Regime", "risk_regime")
    ) ?: "시장 상태 대기"
}

private fun marketBriefingDetail(app: QuantAppState, homeViewModel: HomeViewModel): String {
    val macro = homeViewModel.macro.ifEmpty { app.macro }
    val risk = firstMacroValue(macro, "Risk_Signal", "Risk_Level", "Risk", "Signal")
    if (!risk.isNullOrBlank()) {
        return "위험 신호 ${displayBriefingRegime(risk) ?: risk}"
    }
    val index = app.marketIndices.firstOrNull()
    if (index != null) {
        return "${index.label} ${pct(index.changePct)}"
    }
    return if (homeViewModel.loading || app.homeLoading) "시장 데이터 동기화 중" else "주요 지수와 매크로를 함께 확인하세요"
}

private fun marketBriefingColor(app: QuantAppState, homeViewModel: HomeViewModel): Color {
    val macro = homeViewModel.macro.ifEmpty { app.macro }
    val risk = firstMacroValue(macro, "Risk_Signal", "Risk_Level", "Risk", "Signal").orEmpty()
    val text = "${marketBriefingValue(app, homeViewModel)} $risk".uppercase()
    return when {
        text.contains("RISK_OFF") || text.contains("BEAR") || text.contains("WARNING") || text.contains("위험회피") -> QuantWarning
        text.contains("RISK_ON") || text.contains("BULL") || text.contains("PASS") || text.contains("위험선호") -> QuantPositive
        else -> Color.Unspecified
    }.takeUnless { it == Color.Unspecified } ?: Color(0xFF2E63D1)
}

private fun displayBriefingRegime(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank() || raw == "-") return null
    val normalized = raw.uppercase().replace(" ", "_").replace("-", "_")
    return when {
        raw.contains("위험선호") || normalized == "RISK_ON" -> "위험선호"
        raw.contains("위험회피") || normalized == "RISK_OFF" -> "위험회피"
        raw.contains("중립") || normalized == "NEUTRAL" -> "중립"
        else -> raw
    }
}

private fun firstMacroValue(macro: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        macro[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}

private fun earningsCalendarSummary(item: EarningsCalendarItem?): String {
    return item?.let { "${compactDateText(it.nextEarningsDate)} · ${earningsCalendarDayText(it.daysUntil)}" }
        ?: "예정 실적 일정 대기 중"
}

private fun earningsCalendarDayText(days: Int?): String {
    return when {
        days == null -> "D-?"
        days == 0 -> "D-Day"
        days > 0 -> "D-$days"
        else -> "D+${kotlin.math.abs(days)}"
    }
}

private fun briefingLatestUpdate(app: QuantAppState): String? {
    return buildList {
        add(firstMetaValue(app.usMeta, "Generated", "Generated_At", "Last_Updated"))
        add(firstMetaValue(app.krMeta, "Generated", "Generated_At", "Last_Updated"))
        add(app.macro["Generated"])
        add(app.opsHealth?.generatedAt)
        addAll(app.marketIndices.map { it.updatedAt })
        addAll(app.marketIndicators.mapNotNull { it.updatedAt })
        addAll(app.usPortfolio.mapNotNull { it.lastUpdated })
        addAll(app.krPortfolio.mapNotNull { it.lastUpdated })
        addAll(app.usSmallCap.mapNotNull { it.lastUpdated })
        addAll(app.krSmallCap.mapNotNull { it.lastUpdated })
        addAll(app.newsItems.map { it.publishedAt })
        addAll(app.signalEvents.mapNotNull { it.eventTime ?: it.updatedAt })
    }
        .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
        .maxOrNull()
}

private fun briefingDataIssueCount(app: QuantAppState): Int {
    val loadIssues = listOf(app.error, app.marketIndicatorError, app.newsError).count { !it.isNullOrBlank() }
    val signalIssues = app.researchQuality?.items
        ?.count { it.status.uppercase() in setOf("FAIL", "WATCH", "INSUFFICIENT") }
        ?: 0
    val opsIssues = app.opsHealth?.checks
        ?.count { it.status.uppercase() !in setOf("OK", "PASS", "HEALTHY") }
        ?: 0
    return loadIssues + signalIssues + opsIssues
}

private fun briefingLoadedSourceCount(app: QuantAppState): Int {
    return listOf(
        app.usPortfolio.isNotEmpty(),
        app.krPortfolio.isNotEmpty(),
        app.usSmallCap.isNotEmpty() || app.krSmallCap.isNotEmpty(),
        app.usEarnings.isNotEmpty() || app.krEarnings.isNotEmpty() || app.earningsCalendar.isNotEmpty(),
        app.marketIndices.isNotEmpty(),
        app.marketIndicators.isNotEmpty(),
        app.newsItems.isNotEmpty(),
        app.researchQuality != null,
        app.opsHealth != null
    ).count { it }
}

private fun firstMetaValue(meta: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}
