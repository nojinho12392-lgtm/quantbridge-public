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
