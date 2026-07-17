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
internal fun WatchIndicatorAvatar() {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            LucideIconView(
                icon = LucideIcon.LineChart,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun WatchIndicatorGraphCard(
    item: WatchlistItem,
    quote: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if ((quote.changePct ?: 0.0) >= 0.0) QuantPositive else QuantNegative
    val chartPoints = remember(quote, points) {
        displayMarketIndicatorPoints(quote, points)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.50f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MarketIndicatorLogoView(item.ticker, item.name, size = 32.dp, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        watchIndicatorValueLine(quote),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "관심 지수 삭제",
                        tint = QuantFavorite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (chartPoints.isEmpty()) {
                WatchIndicatorGraphPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                )
            } else {
                IndicatorSparkline(
                    item = quote,
                    points = chartPoints,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                )
            }

            Text(
                watchIndicatorDescription(item, quote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (quote.value.isFinite()) {
                Text(
                    "갱신 ${formattedUpdateTimestamp(quote.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun WatchIndicatorGraphPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "그래프 대기",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun marketIndicatorQuoteFor(
    item: WatchlistItem,
    indicatorQuotes: List<MarketIndicatorQuote>
): MarketIndicatorQuote {
    val key = canonicalMarketIndicatorSymbol(item)
    return indicatorQuotes.firstOrNull { canonicalMarketIndicatorSymbol(it.symbol) == key }
        ?: MarketIndicatorQuote(
            symbol = key.ifBlank { item.ticker },
            label = item.name,
            category = watchIndicatorCategory(item),
            region = watchIndicatorRegion(item, key),
            value = Double.NaN,
            changeAbs = null,
            changePct = null,
            updatedAt = null
        )
}

internal fun marketIndicatorPointsFor(
    item: WatchlistItem,
    indicatorHistory: Map<String, List<MarketIndicatorPoint>>
): List<MarketIndicatorPoint> {
    val key = canonicalMarketIndicatorSymbol(item)
    return indicatorHistory[key]
        ?: indicatorHistory[item.ticker]
        ?: indicatorHistory.entries.firstOrNull { canonicalMarketIndicatorSymbol(it.key) == key }?.value
        ?: emptyList()
}

internal fun watchIndicatorRegion(item: WatchlistItem, symbol: String): String {
    return when {
        symbol in setOf("^KS11", "^KQ11", "KRW=X", "IRR_GOVT03Y", "IRR_CORP03Y") -> "domestic"
        item.market == "KR" -> "domestic"
        else -> "overseas"
    }
}

internal fun watchIndicatorCategory(item: WatchlistItem): String {
    val symbol = canonicalMarketIndicatorSymbol(item)
    if (symbol in setOf("GC=F", "SI=F", "CL=F", "HG=F")) return "commodity"
    if (symbol in setOf("BTC-USD", "ETH-USD", "SOL-USD")) return "crypto"
    if (symbol in setOf("^IRX", "^FVX", "^TNX", "^TYX", "IRR_GOVT03Y", "IRR_CORP03Y")) return "bond"
    return when (item.market) {
        "원자재" -> "commodity"
        "가상자산" -> "crypto"
        else -> "index_fx"
    }
}

internal fun watchIndicatorDescription(item: WatchlistItem, quote: MarketIndicatorQuote): String {
    return when (canonicalMarketIndicatorSymbol(item)) {
        "^IXIC" -> "미국 기술주 중심 나스닥 종합지수"
        "NQ=F" -> "나스닥 100 지수의 선물 가격"
        "^GSPC" -> "미국 대형주 500개 대표 지수"
        "ES=F" -> "S&P 500 지수의 선물 가격"
        "RTY=F" -> "미국 중소형주 러셀 2000 선물"
        "^DJI" -> "미국 대표 우량주 다우존스 지수"
        "^SOX" -> "미국 반도체 업종 대표 지수"
        "^VIX" -> "S&P 500 옵션 기반 변동성 지수"
        "^KS11" -> "한국 유가증권시장 대표 지수"
        "^KQ11" -> "한국 코스닥시장 대표 지수"
        "KRW=X" -> "원/달러 환율 흐름"
        "DX-Y.NYB" -> "주요 통화 대비 달러 가치 지수"
        "^IRX", "^FVX", "^TNX", "^TYX" -> "미국 국채 금리 지표"
        "IRR_GOVT03Y" -> "한국 국고채 3년 금리 지표"
        "IRR_CORP03Y" -> "한국 회사채 3년 금리 지표"
        "GC=F", "SI=F", "CL=F", "HG=F" -> "${item.name} 원자재 선물 가격"
        "BTC-USD", "ETH-USD", "SOL-USD" -> "${item.name} 달러 기준 가상자산 가격"
        else -> when (quote.category) {
            "crypto" -> "${item.name} 가상자산 가격"
            "commodity" -> "${item.name} 원자재 가격"
            "bond" -> "${item.name} 금리 지표"
            else -> "${item.market} 시장 지수"
        }
    }
}

internal fun watchIndicatorValueLine(quote: MarketIndicatorQuote): String {
    if (!quote.value.isFinite()) return "데이터 대기"
    return "${indicatorValueText(quote.value)} · ${pct(quote.changePct)}"
}
