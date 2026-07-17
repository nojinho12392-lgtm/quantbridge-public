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
