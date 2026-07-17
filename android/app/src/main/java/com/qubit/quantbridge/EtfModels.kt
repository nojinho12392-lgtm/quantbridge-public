package com.qubit.quantbridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.delay

data class EtfHolding(
    val ticker: String,
    val name: String,
    val weight: Double
)

data class EtfExposure(
    val label: String,
    val weight: Double
)

internal data class EtfGuidePoint(
    val title: String,
    val detail: String,
    val icon: LucideIcon
)

data class EtfInsight(
    val ticker: String,
    val name: String,
    val region: String,
    val category: String,
    val theme: String,
    val summary: String,
    val expenseRatio: String,
    val aum: String,
    val distribution: String,
    val outlook: String,
    val risk: String,
    val holdings: List<EtfHolding>,
    val exposures: List<EtfExposure>,
    val currentPrice: Double? = null,
    val return1M: Double? = null,
    val priceChange: Double? = null,
    val dailyChangePct: Double? = null,
    val dailyPriceChange: Double? = null,
    val dailyChangeHorizon: String? = null,
    val source: String? = null,
    val updatedAt: String? = null
) {
    val topHoldingText: String
        get() = holdings.firstOrNull()?.let { "${it.name} ${pct(it.weight, signed = false)}" } ?: "구성 대기"

    val displaySummary: String
        get() = cleanEtfSummary(summary)

    val displayPriceChange: Double?
        get() {
            dailyPriceChange?.takeIf { it.isFinite() }?.let { return it }
            val price = currentPrice?.takeIf { it.isFinite() } ?: return null
            val dailyChange = dailyChangePct?.takeIf { it.isFinite() && it > -0.999 }
            if (dailyChange != null) {
                val basePrice = price / (1.0 + dailyChange)
                return price - basePrice
            }
            priceChange?.takeIf { it.isFinite() }?.let { return it }
            val change = return1M?.takeIf { it.isFinite() && it > -0.999 } ?: return null
            val basePrice = price / (1.0 + change)
            return price - basePrice
        }

    val displayChangePct: Double?
        get() = dailyChangePct?.takeIf { it.isFinite() } ?: return1M?.takeIf { it.isFinite() }
}

data class EtfInsightsResult(
    val items: List<EtfInsight>,
    val source: String?,
    val updatedAt: String?
)

internal enum class EtfSortOption(val title: String) {
    Alphabet("알파벳순"),
    Return1M("수익률순"),
    Ticker("티커순")
}

val EtfInsight.priceTicker: String
    get() = if (region == "KR" && ticker.length == 6 && ticker.all { it.isDigit() }) {
        "$ticker.KS"
    } else {
        ticker
    }

val EtfInsight.priceLookupTickers: List<String>
    get() = if (region == "KR" && ticker.length == 6 && ticker.all { it.isDigit() }) {
        listOf("$ticker.KS", "$ticker.KQ")
    } else {
        listOf(ticker)
    }

fun EtfInsight.detailRequest(): DetailRequest {
    val currency = if (region == "KR") "KRW" else "USD"
    return DetailRequest(
        ticker = priceTicker,
        name = name,
        currency = currency,
        market = region,
        sections = listOf(
            DetailSection(
                "ETF 정보",
                listOf(
                    DetailMetric("유형", category),
                    DetailMetric("테마", theme),
                    DetailMetric("총보수", expenseRatio, DetailTone.Primary),
                    DetailMetric("AUM", aum),
                    DetailMetric("분배", distribution),
                    DetailMetric("Top 구성", topHoldingText, DetailTone.Positive)
                )
            )
        ),
        signals = listOf(
            DetailSignal(
                "ETF 가격 확인",
                "기업 상세와 같은 차트에서 ETF 가격, 기간별 흐름, 변동성을 확인합니다.",
                DetailTone.Primary
            ),
            DetailSignal(theme, outlook, DetailTone.Positive),
            DetailSignal("주의", risk, DetailTone.Warning)
        ),
        factors = emptyList(),
        preferredTab = "chart",
        holdings = holdings.map { DetailHolding(it.ticker, it.name, it.weight) }
    )
}
