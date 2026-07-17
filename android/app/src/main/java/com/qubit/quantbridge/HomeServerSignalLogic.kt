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
