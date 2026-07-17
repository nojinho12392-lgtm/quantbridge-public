package com.qubit.quantbridge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
            InsightSection.Training -> Unit
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
                InsightSection.Training -> BlindFinancialQuizScreen(contentTopPadding = 4.dp)
            }
        }
    }
}

@Composable
internal fun InsightMarketHeader(
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
