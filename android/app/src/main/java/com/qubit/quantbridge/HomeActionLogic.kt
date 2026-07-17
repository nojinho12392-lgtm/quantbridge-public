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

@Composable
internal fun rememberHomeActionInboxState(context: Context): HomeActionInboxState {
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

internal fun homeActionInboxItems(
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

internal fun homeDecisionItems(
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

internal fun marketActionPriority(app: QuantAppState, homeViewModel: HomeViewModel): Double {
    val largestIndexMove = app.marketIndices.map { abs(it.changePct) }.maxOrNull() ?: 0.0
    val riskText = "${marketBriefingValue(app, homeViewModel)} ${marketBriefingDetail(app, homeViewModel)}".uppercase(Locale.US)
    val riskBonus = if (riskText.contains("RISK_OFF") || riskText.contains("위험") || riskText.contains("WARNING")) 24.0 else 0.0
    return 48.0 + min(24.0, largestIndexMove * 1_000.0) + riskBonus
}

internal fun earningsActionPriority(item: EarningsCalendarItem): Double {
    val days = item.daysUntil ?: return 62.0
    return when {
        days <= 0 -> 96.0
        days <= 3 -> 90.0 - days
        days <= 7 -> 78.0 - days
        else -> 58.0
    }
}

internal fun candidateActionPriority(stock: PortfolioStock, profile: InvestmentProfile = InvestmentProfile()): Double {
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

internal fun smallCapActionPriority(stock: SmallCapStock, profile: InvestmentProfile = InvestmentProfile()): Double {
    val scoreBonus = min(14.0, max(0.0, ((stock.totalScore ?: 0.0) - 60.0) / 3.0))
    val volumeBonus = min(16.0, max(0.0, ((stock.volumeSurge ?: 1.0) - 1.0) * 10.0))
    val growthBonus = min(10.0, homePercentMagnitude(stock.revGrowth) / 3.0)
    val rankMoveBonus = min(6.0, abs((stock.rankChange ?: 0).toDouble()) * 1.2)
    return 50.0 + scoreBonus + volumeBonus + growthBonus + rankMoveBonus + profileSmallCapPriorityBonus(stock, profile)
}

internal fun profileCandidatePriorityBonus(stock: PortfolioStock, profile: InvestmentProfile): Double {
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

internal fun profileSmallCapPriorityBonus(stock: SmallCapStock, profile: InvestmentProfile): Double {
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

internal fun candidateProfileNudge(stock: PortfolioStock, profile: InvestmentProfile): String? {
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

internal fun smallCapProfileNudge(stock: SmallCapStock, profile: InvestmentProfile): String? {
    if (!profile.isConfigured) return null
    val positives = buildList {
        if (profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 15.0) add("성장 기준 부합")
        if (profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.fcfMargin ?: 0.0) > 0.06)) add("퀄리티 근거")
        if (profile.style.contains("모멘텀") && (stock.volumeSurge ?: 1.0) >= 1.8) add("거래량 확인")
    }
    val conflicts = candidateConflictLabels(profile, stock.return1M, null, stock.debtEbitda, stock.volumeSurge)
    return (positives.take(1) + conflicts.take(1).map { "주의: $it" }).joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun candidateConflictLabels(
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

internal fun homeMistakeCoachActions(
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

internal fun homePercentMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}

internal fun newsActionPriority(app: QuantAppState, item: NewsItem): Double {
    val move = abs(item.relatedChangePct ?: 0.0)
    val impact = abs(item.impactScore)
    val relatedKeys = (listOf(item.ticker) + item.relatedTickers)
        .flatMap { watchMatchKeys(it) }
        .toSet()
    val watchBonus = if (app.watchlist.any { watchMatchKeys(it.ticker).any { key -> key in relatedKeys } }) 16.0 else 0.0
    return 52.0 + min(24.0, move * 1_000.0) + min(18.0, impact * 18.0) + watchBonus
}

internal fun homeWatchSignals(
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

internal fun homeWatchNotificationEvents(
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

internal fun homeDailyNotificationSummary(signals: List<HomeWatchSignal>, watchItems: List<WatchlistItem>): String {
    val primary = signals.firstOrNull { it.priority >= 3 }
    if (primary != null) return "${primary.title}: ${primary.detail}"
    if (watchItems.isEmpty()) return "오늘 볼 후보와 시장 상태를 확인하세요."
    return "관심 항목 ${watchItems.size}개를 기준으로 실적 일정과 변화 신호를 확인하세요."
}

internal fun notificationWatchItem(signal: HomeWatchSignal, watchItems: List<WatchlistItem>): WatchlistItem? {
    val signalKeys = watchMatchKeys(signal.ticker)
    return watchItems.firstOrNull { item ->
        watchMatchKeys(item.ticker).any { key -> key in signalKeys }
    }
}

internal fun wantsWatchAlert(item: WatchlistItem, option: String): Boolean {
    return watchAlertOptionMatches(item.alertOptions, option)
}

internal fun homeCompanyWatchSignal(
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

internal fun homeWatchDisplay(
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

internal fun watchMatchKeys(ticker: String): Set<String> {
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

internal fun watchTickerMatches(ticker: String, keys: Set<String>): Boolean {
    return watchMatchKeys(ticker).any { it in keys }
}

internal fun serverSignalFor(
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

internal fun serverSignalDetailRequest(
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

internal fun serverSignalTone(kind: String): DetailTone {
    return when (kind) {
        "price_pressure", "price_drop", "rank_down" -> DetailTone.Negative
        "earnings_due" -> DetailTone.Warning
        "rank_up", "price_momentum", "price_spike" -> DetailTone.Positive
        else -> DetailTone.Primary
    }
}

internal fun serverSignalIcon(kind: String): LucideIcon {
    return when (kind) {
        "price_pressure", "price_drop", "rank_down" -> LucideIcon.TriangleAlert
        "earnings_due" -> LucideIcon.CalendarClock
        "rank_up", "price_momentum", "price_spike" -> LucideIcon.TrendingUp
        else -> LucideIcon.Zap
    }
}

internal fun serverSignalKindLabel(kind: String): String {
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

internal fun serverJudgmentAlertOption(kind: String): String {
    return when (kind) {
        "earnings_due" -> "실적 리스크"
        "price_pressure", "price_drop", "rank_down" -> "가설 흔들림"
        "rank_up", "price_momentum", "price_spike" -> "점수·과열 동시"
        else -> "판단 업데이트"
    }
}

internal fun serverJudgmentNotificationTitle(event: SignalEvent): String {
    return when (event.kind) {
        "earnings_due" -> "실적 발표 전 확인할 리스크가 생겼습니다"
        "price_pressure", "price_drop", "rank_down" -> "이 종목의 투자 가정이 흔들렸습니다"
        "rank_up", "price_momentum", "price_spike" -> "점수는 올랐지만 과열 신호도 같이 커졌습니다"
        else -> "관심종목 판단 업데이트가 생겼습니다"
    }
}

internal fun serverJudgmentNotificationBody(event: SignalEvent): String {
    val name = event.name.ifBlank { event.ticker }
    return when (event.kind) {
        "earnings_due" -> "$name · ${event.detail} 발표 전 리스크와 무효 조건을 확인하세요."
        "price_pressure", "price_drop", "rank_down" -> "$name · ${event.detail} 처음 투자 가정과 다르게 움직이는지 점검하세요."
        "rank_up", "price_momentum", "price_spike" -> "$name · ${event.detail} 점수 상승과 과열 신호를 함께 확인하세요."
        else -> "$name · ${event.detail}"
    }
}

internal fun formatIndexValue(value: Double): String {
    if (!value.isFinite()) return "-"
    return String.format(Locale.US, "%,.2f", value)
}
