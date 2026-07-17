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
