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

@Composable
internal fun watchInsightColor(insight: WatchCompanyInsight): androidx.compose.ui.graphics.Color {
    return when (insight.tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun watchComparator(
    sort: WatchSortOption,
    selectedTab: WatchAssetTab,
    companyInsights: Map<String, WatchCompanyInsight>,
    indicatorQuotes: List<MarketIndicatorQuote>
): Comparator<WatchlistItem> {
    return Comparator { left, right ->
        when (sort) {
            WatchSortOption.Signal -> {
                val leftSignal = watchSignalScore(left, companyInsights, indicatorQuotes)
                val rightSignal = watchSignalScore(right, companyInsights, indicatorQuotes)
                compareSignalValues(rightSignal, leftSignal).ifZero {
                    right.addedAt.compareTo(left.addedAt).ifZero {
                        left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
                    }
                }
            }
            WatchSortOption.Added -> right.addedAt.compareTo(left.addedAt)
            WatchSortOption.Name -> left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
            WatchSortOption.Market -> left.market.compareTo(right.market).ifZero {
                left.name.lowercase(Locale.getDefault()).compareTo(right.name.lowercase(Locale.getDefault()))
            }
        }
    }
}

internal fun watchJudgmentTimelineItems(
    context: Context,
    priorityItems: List<WatchPriorityItem>
): List<WatchJudgmentTimelineItem> {
    val history = QubitNotificationScheduler.history(context)
        .take(6)
        .map {
            WatchJudgmentTimelineItem(
                id = it.id,
                title = it.title,
                detail = it.body,
                source = it.source,
                recordedAtMillis = it.recordedAtMillis,
                color = Color(0xFF2563EB)
            )
        }
    if (history.isNotEmpty()) return history
    return priorityItems.take(4).map { priority ->
        WatchJudgmentTimelineItem(
            id = "current-${priority.item.ticker}-${priority.insight.title}",
            title = priority.insight.title,
            detail = priority.insight.detail,
            source = "현재 신호",
            recordedAtMillis = null,
            color = watchInsightTimelineColor(priority.insight)
        )
    }
}

internal fun watchInsightTimelineColor(insight: WatchCompanyInsight): Color {
    return when (insight.tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> Color(0xFF2563EB)
        DetailTone.Neutral -> Color(0xFF64748B)
    }
}

internal fun watchSignalScore(
    item: WatchlistItem,
    companyInsights: Map<String, WatchCompanyInsight>,
    indicatorQuotes: List<MarketIndicatorQuote>
): Double {
    return if (item.isMarketIndicatorWatchItem()) {
        kotlin.math.abs(marketIndicatorQuoteFor(item, indicatorQuotes).changePct ?: 0.0)
    } else {
        (companyInsights[normalizedTicker(item.ticker)] ?: item.defaultCompanyInsight()).priority.toDouble()
    }
}

internal fun compareSignalValues(left: Double, right: Double): Int {
    return when {
        left < right -> -1
        left > right -> 1
        else -> 0
    }
}

private inline fun Int.ifZero(block: () -> Int): Int {
    return if (this == 0) block() else this
}

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

internal fun watchInsightMetrics(
    portfolio: PortfolioStock?,
    smallCap: SmallCapStock?,
    earnings: EarningsStock?,
    calendar: EarningsCalendarItem?
): List<String> {
    return buildList {
        if (portfolio?.expectedReturn?.isFinite() == true) {
            add("기대 ${pct(portfolio.expectedReturn)}")
        } else if (portfolio?.totalScore?.isFinite() == true) {
            add("점수 ${"%.0f".format(portfolio.totalScore)}")
        }
        if (smallCap?.totalScore?.isFinite() == true) {
            add("스몰캡 ${"%.0f".format(smallCap.totalScore)}")
        }
        if (earnings?.signalStrength?.isFinite() == true) {
            add("Signal ${"%.2f".format(earnings.signalStrength)}")
        }
        if (calendar?.daysUntil != null) {
            add(watchEarningsDayText(calendar.daysUntil))
        }
    }.take(3)
}

internal fun watchInsightDetails(
    portfolio: PortfolioStock?,
    smallCap: SmallCapStock?,
    earnings: EarningsStock?,
    calendar: EarningsCalendarItem?,
    priceMetric: StockPriceMetric?,
    item: WatchlistItem
): List<String> {
    val currency = portfolio?.let { marketCurrency(it.ticker, it.market) }
        ?: smallCap?.let { marketCurrency(it.ticker, it.market) }
        ?: item.currency
    return buildList {
        val price = listOf(portfolio?.currentPrice, smallCap?.currentPrice, priceMetric?.currentPrice)
            .firstOrNull { it?.isFinite() == true }
        if (price?.isFinite() == true) add("가격 ${fmtPx(price, currency)}")
        val dailyChange = priceMetric?.dailyChangePct
        if (dailyChange?.isFinite() == true) {
            add("하루 ${pct(dailyChange)}")
        } else {
            val return1M = listOf(portfolio?.return1M, smallCap?.return1M, priceMetric?.return1M)
                .firstOrNull { it?.isFinite() == true }
            if (return1M?.isFinite() == true) add("1개월 ${pct(return1M)}")
        }
        val marketCap = portfolio?.marketCap ?: smallCap?.marketCap ?: earnings?.marketCap ?: calendar?.marketCap
        if (marketCap?.isFinite() == true) add("시총 ${cap(marketCap, currency)}")
        val sector = portfolio?.sector ?: earnings?.sector ?: calendar?.sector
        if (!sector.isNullOrBlank()) add(portfolioIndustryLabel(item.ticker, item.name, sector))
        val earningsDate = calendar?.nextEarningsDate ?: earnings?.earningsDate
        if (!earningsDate.isNullOrBlank()) add("실적 ${compactDateText(earningsDate)}")
        if (isEmpty()) addAll(listOf(item.market, item.note).filter { it.isNotBlank() })
    }.take(4)
}

internal fun watchEarningsDayText(days: Int?): String {
    return when {
        days == null -> "D-?"
        days == 0 -> "D-Day"
        days > 0 -> "D-$days"
        else -> "D+${kotlin.math.abs(days)}"
    }
}

internal fun WatchlistItem.detailRequest(): DetailRequest {
    return DetailRequest(
        ticker = ticker,
        name = name,
        currency = currency,
        market = market,
        sections = listOf(
            DetailSection(
                "관심 종목",
                listOf(
                    DetailMetric("분류", note),
                    DetailMetric("시장", market),
                    DetailMetric("통화", currency),
                    DetailMetric("저장일", addedLabel(addedAt))
                )
            )
        ),
        signals = listOf(
            watchlistSignal(this)
        ),
        factors = emptyList()
    )
}

internal fun watchlistSignal(item: WatchlistItem): DetailSignal {
    val thesis = item.investmentThesis
    return if (thesis.isEmpty) {
        DetailSignal(
            "관심 종목",
            "관심 목록에 저장한 종목입니다. 가격, 52주 범위, 기업 정보를 확인하세요.",
            DetailTone.Primary
        )
    } else {
        DetailSignal("투자 가설", thesis.detailSummary, DetailTone.Primary)
    }
}

internal fun addedLabel(value: String): String {
    return value.take(10).ifBlank { "방금" }
}
