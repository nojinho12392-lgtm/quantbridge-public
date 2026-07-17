package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

@Composable
internal fun PortfolioRankingSectionTitle(title: String = "기업 순위") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "1개월 수익률",
            modifier = Modifier
                .width(88.dp)
                .padding(end = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

@Composable
internal fun PortfolioRankingRow(
    rankLabel: String,
    stock: PortfolioStock,
    profile: InvestmentProfile,
    currency: String,
    currentPrice: Double?,
    return1M: Double?,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onOpen: () -> Unit
) {
    val personal = remember(profile, stock) { personalizedStockInterpretation(profile, stock) }
    PortfolioCompanyRow(
        rankLabel = rankLabel,
        rankChange = stock.rankChange,
        rankStatus = stock.rankStatus,
        ticker = stock.ticker,
        market = stock.market,
        name = stock.name,
        sectorLabel = portfolioIndustryLabel(stock.ticker, stock.name, stock.sector),
        personalLine = "${personal.label} · ${personal.headline}",
        priceText = portfolioPriceText(currentPrice, currency),
        return1M = return1M,
        source = stock.source ?: stock.lastUpdated?.let { "storage" },
        updatedAt = stock.lastUpdated ?: stock.generatedAt,
        comparisonMode = comparisonMode,
        comparisonSelected = comparisonSelected,
        comparisonDisabled = comparisonDisabled,
        onOpen = onOpen
    )
}

@Composable
internal fun RankMovementBadge(change: Int?, status: String?) {
    val normalized = status?.lowercase(Locale.US)
    val text = when {
        normalized == "new" -> "신규"
        change == null -> null
        change > 0 -> "▲$change"
        change < 0 -> "▼${abs(change)}"
        else -> null
    } ?: return
    val color = when {
        normalized == "new" -> MaterialTheme.colorScheme.primary
        change != null && change > 0 -> QuantPositive
        change != null && change < 0 -> QuantNegative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

@Composable
internal fun SmallCapRankingRow(
    rankLabel: String,
    stock: SmallCapStock,
    profile: InvestmentProfile,
    currentPrice: Double?,
    return1M: Double?,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onOpen: () -> Unit
) {
    val currency = marketCurrency(stock.ticker, stock.market)
    val personalLine = remember(profile, stock) { smallCapPersonalLine(stock, profile) }
    PortfolioCompanyRow(
        rankLabel = rankLabel,
        rankChange = stock.rankChange,
        rankStatus = stock.rankStatus,
        ticker = stock.ticker,
        market = stock.market,
        name = stock.name,
        sectorLabel = stock.market ?: "스몰캡",
        personalLine = personalLine,
        priceText = portfolioPriceText(currentPrice, currency),
        return1M = return1M,
        source = stock.source ?: stock.lastUpdated?.let { "storage" },
        updatedAt = stock.lastUpdated ?: stock.generatedAt,
        comparisonMode = comparisonMode,
        comparisonSelected = comparisonSelected,
        comparisonDisabled = comparisonDisabled,
        onOpen = onOpen
    )
}

internal fun smallCapPersonalLine(stock: SmallCapStock, profile: InvestmentProfile): String {
    if (!profile.isConfigured) return "기준 설정 필요 · 스몰캡은 점수와 거래량을 함께 비교"
    val headline = profile.headline.ifBlank { "내 기준" }
    val revGrowth = listPercentMagnitude(stock.revGrowth)
    val return1M = listPercentMagnitude(stock.return1M)
    val volumeSurge = stock.volumeSurge ?: 1.0
    return when {
        (profile.riskTolerance.contains("안정") || profile.riskTolerance.contains("보수") || profile.riskTolerance.contains("낮")) &&
            (volumeSurge >= 2.0 || return1M >= 12.0) -> "$headline 기준 · 변동성 먼저 제한"
        profile.style.contains("성장") && revGrowth >= 15.0 -> "$headline 기준 · 성장 근거 확인"
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) >= 0.12 || (stock.fcfMargin ?: 0.0) >= 0.06) -> "$headline 기준 · 퀄리티 근거 확인"
        profile.style.contains("모멘텀") && volumeSurge >= 1.8 -> "$headline 기준 · 과열 여부 확인"
        else -> "$headline 기준 · 비교 후 관찰"
    }
}

internal fun listPercentMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}
