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

@Composable
internal fun EarningsCalendarFocusCard(
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
internal fun EarningsCalendarMonthCard(
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
internal fun RowScope.EarningsCalendarDateCell(
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
internal fun EarningsCalendarRow(
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

internal fun earningsCalendarValueText(item: EarningsCalendarItem): String {
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

internal fun formatEarningsCalendarDate(date: String): String {
    return try {
        val parsed = LocalDate.parse(date)
        val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")
        "${parsed.monthValue}월 ${parsed.dayOfMonth}일 (${weekdays[parsed.dayOfWeek.value - 1]})"
    } catch (_: DateTimeParseException) {
        date
    }
}

internal fun parseEarningsCalendarDate(date: String): LocalDate? {
    return try {
        LocalDate.parse(date)
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun formatEarningsCalendarMonth(date: LocalDate): String {
    return "${date.year}년 ${date.monthValue}월"
}

internal fun earningsCalendarMonthCells(month: LocalDate): List<LocalDate?> {
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

internal fun daysUntilText(days: Int?): String {
    return when {
        days == null -> "-"
        days == 0 -> "오늘"
        days > 0 -> "${days}일 후"
        else -> "${-days}일 전"
    }
}
