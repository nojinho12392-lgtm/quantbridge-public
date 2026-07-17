package com.qubit.quantbridge

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlinx.coroutines.delay

internal fun QuantAppState.companyInsightFor(item: WatchlistItem): WatchCompanyInsight {
    val portfolio = portfolioMatch(item)
    val smallCap = smallCapMatch(item)
    val earnings = earningsMatch(item)
    val calendar = earningsCalendarMatch(item)
    val priceMetric = watchPriceMetric(item.ticker)
    val metrics = (watchMetadataMetrics(item) + watchInsightMetrics(portfolio, smallCap, earnings, calendar)).take(3)
    val details = watchInsightDetails(portfolio, smallCap, earnings, calendar, priceMetric, item)
    val updatedAt = portfolio?.lastUpdated
        ?: smallCap?.lastUpdated
        ?: earnings?.earningsDate
        ?: calendar?.nextEarningsDate
        ?: priceMetric?.updatedAt
    val upcoming = (calendar?.daysUntil ?: 999) in 0..14

    if (calendar != null && (calendar.daysUntil ?: 999) in 0..7) {
        return WatchCompanyInsight(
            title = "실적 임박",
            detail = "${watchEarningsDayText(calendar.daysUntil)} · ${compactDateText(calendar.nextEarningsDate)} 발표 예정",
            metrics = metrics,
            details = details,
            tone = DetailTone.Warning,
            priority = 4,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (earnings?.signalStrength?.isFinite() == true && earnings.signalStrength >= 1.0) {
        return WatchCompanyInsight(
            title = "실적 반응",
            detail = "Signal ${"%.2f".format(earnings.signalStrength)} · 발표 후 수익률 ${pct(earnings.returnSince)}",
            metrics = metrics,
            details = details,
            tone = DetailTone.Primary,
            priority = 3,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (portfolio?.expectedReturn?.isFinite() == true) {
        val expected = portfolio.expectedReturn
        return WatchCompanyInsight(
            title = if (expected >= 0.0) "후보 유지" else "타이밍 확인",
            detail = "기대수익 ${pct(expected)} · 점수 ${score(portfolio.totalScore)}",
            metrics = metrics,
            details = details,
            tone = if (expected >= 0.0) DetailTone.Primary else DetailTone.Warning,
            priority = if (expected >= 0.0) 2 else 3,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (smallCap?.volumeSurge?.isFinite() == true && smallCap.volumeSurge >= 1.5) {
        return WatchCompanyInsight(
            title = "거래량 변화",
            detail = "평소 대비 ${multipleText(smallCap.volumeSurge)} · 스몰캡 점수 ${score(smallCap.totalScore)}",
            metrics = metrics,
            details = details,
            tone = DetailTone.Primary,
            priority = 2,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    if (portfolio != null || smallCap != null || earnings != null || calendar != null) {
        return WatchCompanyInsight(
            title = "데이터 연결",
            detail = "후보, 실적, 일정 데이터 중 일부가 연결되어 있습니다.",
            metrics = metrics,
            details = details,
            tone = DetailTone.Positive,
            priority = 1,
            updatedAt = updatedAt,
            linked = true,
            hasUpcomingEarnings = upcoming
        )
    }

    val hasPrice = priceMetric?.currentPrice?.isFinite() == true
    return WatchCompanyInsight(
        title = if (item.isEtfWatchItem()) "ETF 감시" else "기본 감시",
        detail = if (hasPrice) "가격 데이터를 연결해 계속 감시 중입니다." else "상세에서 가격과 기업 정보를 확인하세요.",
        metrics = (watchMetadataMetrics(item) + listOf(item.market, item.note)).filter { it.isNotBlank() }.take(3),
        details = details,
        tone = DetailTone.Neutral,
        priority = 0,
        updatedAt = updatedAt ?: item.addedAt.ifBlank { null },
        linked = hasPrice,
        hasUpcomingEarnings = false
    )
}

internal fun WatchlistItem.defaultCompanyInsight(): WatchCompanyInsight {
    return WatchCompanyInsight(
        title = "기본 감시",
        detail = "상세에서 가격과 기업 정보를 확인하세요.",
        metrics = (watchMetadataMetrics(this) + listOf(market, note)).filter { it.isNotBlank() }.take(3),
        details = listOf(market, note).filter { it.isNotBlank() }.take(2),
        tone = DetailTone.Neutral,
        priority = 0,
        updatedAt = addedAt.ifBlank { null },
        linked = false,
        hasUpcomingEarnings = false
    )
}

internal fun watchMetadataMetrics(item: WatchlistItem): List<String> {
    return buildList {
        item.primaryTag?.let { add(it) }
        item.alertOptions.firstOrNull()?.let { add(watchAlertDisplayLabel(it)) }
    }
}

internal fun QuantAppState.portfolioMatch(item: WatchlistItem): PortfolioStock? {
    val key = normalizedTicker(item.ticker)
    return (usPortfolio + krPortfolio).firstOrNull { normalizedTicker(it.ticker) == key }
}

internal fun QuantAppState.smallCapMatch(item: WatchlistItem): SmallCapStock? {
    val key = normalizedTicker(item.ticker)
    return (usSmallCap + krSmallCap).firstOrNull { normalizedTicker(it.ticker) == key }
}

internal fun QuantAppState.earningsMatch(item: WatchlistItem): EarningsStock? {
    val key = normalizedTicker(item.ticker)
    return (usEarnings + krEarnings).firstOrNull { normalizedTicker(it.ticker) == key }
}

internal fun QuantAppState.earningsCalendarMatch(item: WatchlistItem): EarningsCalendarItem? {
    val key = normalizedTicker(item.ticker)
    return earningsCalendar
        .filter { (it.daysUntil ?: 0) >= 0 }
        .sortedBy { it.nextEarningsDate }
        .firstOrNull { normalizedTicker(it.ticker) == key }
}
