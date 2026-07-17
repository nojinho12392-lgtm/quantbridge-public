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
