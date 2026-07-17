package com.qubit.quantbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantMuted
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CompareMetric(
    val label: String,
    val value: (StockComparisonItem) -> String,
    val number: (StockComparisonItem) -> Double?,
    val higherIsBetter: Boolean = true
)

internal fun PortfolioStock.toCompareStock(currency: String): StockComparisonItem {
    return StockComparisonItem(
        id = "portfolio-$ticker",
        ticker = ticker,
        name = name,
        market = market,
        sector = sector,
        currency = currency,
        source = "Portfolio",
        scoreValue = totalScore,
        scoreText = score(totalScore),
        expectedReturn = expectedReturn,
        revenueGrowth = revGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = currentPrice,
        return1M = return1M,
        rankChange = rankChange,
        weight = weight,
        fcfMargin = null,
        volumeSurge = null,
        updatedAt = lastUpdated
    )
}

internal fun SmallCapStock.toCompareStock(): StockComparisonItem {
    val currency = marketCurrency(ticker, market)
    return StockComparisonItem(
        id = "smallcap-$ticker",
        ticker = ticker,
        name = name,
        market = market,
        sector = "스몰캡",
        currency = currency,
        source = "스몰캡",
        scoreValue = totalScore,
        scoreText = totalScore?.let { "%.0f점".format(it) } ?: "-",
        expectedReturn = null,
        revenueGrowth = revGrowth,
        roic = roic,
        grossMargin = grossMargin,
        marketCap = marketCap,
        currentPrice = currentPrice,
        return1M = return1M,
        rankChange = rankChange,
        weight = null,
        fcfMargin = fcfMargin,
        volumeSurge = volumeSurge,
        updatedAt = lastUpdated
    )
}

@Composable
internal fun StockComparisonBar(
    isComparing: Boolean,
    selectedCount: Int,
    totalCount: Int,
    canCompare: Boolean,
    onStart: () -> Unit,
    onCompare: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit
) {
    if (isComparing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "비교 $selectedCount/4",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(72.dp)
            )
            TextButton(onClick = onClear) {
                Text("초기화", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) {
                Text("취소", style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = onCompare, enabled = canCompare) {
                Text("비교 보기", style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(999.dp))
                .quantClickable(enabled = totalCount >= 2, role = QuantPressRole.Row, onClick = onStart)
                .alpha(if (totalCount >= 2) 1f else 0.45f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            shape = RoundedCornerShape(999.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.GitCompare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text("종목 비교", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (totalCount == 0) "데이터 대기" else "2~4개 선택",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun StockComparisonSheet(items: List<StockComparisonItem>, newsItems: List<NewsItem> = emptyList()) {
    val metrics = remember(newsItems) {
        listOf(
            CompareMetric("현재가", { comparePriceText(it) }, { null }),
            CompareMetric("1개월", { pct(it.return1M) }, { it.return1M }),
            CompareMetric("순위변화", { compareRankChangeText(it.rankChange) }, { it.rankChange?.toDouble() }),
            CompareMetric("종합점수", { it.scoreText }, { it.scoreValue }),
            CompareMetric("기대수익", { pct(it.expectedReturn) }, { it.expectedReturn }),
            CompareMetric("매출성장", { pct(it.revenueGrowth) }, { it.revenueGrowth }),
            CompareMetric("ROIC", { pct(it.roic, signed = false) }, { it.roic }),
            CompareMetric("마진", { pct(it.grossMargin, signed = false) }, { it.grossMargin }),
            CompareMetric("시가총액", { cap(it.marketCap, it.currency) }, { it.marketCap }),
            CompareMetric("비중", { pct(it.weight, signed = false) }, { it.weight }),
            CompareMetric("FCF", { pct(it.fcfMargin, signed = false) }, { it.fcfMargin }),
            CompareMetric("거래량", { compareVolumeText(it.volumeSurge) }, { it.volumeSurge }),
            CompareMetric("리스크", { comparisonRiskText(it) }, { comparisonRiskScore(it) }, higherIsBetter = false),
            CompareMetric("뉴스반응", { comparisonNewsText(it, newsItems) }, { comparisonNewsScore(it, newsItems) })
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("종목 비교", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ComparisonSummaryCard(items)
        ComparisonVerdictCard(items)
        ComparisonMomentumCard(items)
        ComparisonNewsReactionCard(items, newsItems)
        ComparisonTable(items = items, metrics = metrics)
        ComparisonCheckpoints(items)
    }
}

@Composable
internal fun ComparisonSummaryCard(items: List<StockComparisonItem>) {
    val bestScore = items.maxByOrNull { it.scoreValue ?: Double.NEGATIVE_INFINITY }
    val bestReturn = items.maxByOrNull { it.expectedReturn ?: Double.NEGATIVE_INFINITY }
    val bestGrowth = items.maxByOrNull { it.revenueGrowth ?: Double.NEGATIVE_INFINITY }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("비교 요약", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                bestScore?.let { "${it.name}이 현재 비교군에서 종합점수가 가장 앞섭니다. 기대수익, 성장성, 수익성이 함께 받쳐주는지 확인하세요." }
                    ?: "비교 가능한 점수 데이터가 부족합니다. 비어 있지 않은 지표 중심으로 확인하세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryChip("점수 우위", bestScore?.name ?: "-", Modifier.weight(1f))
                SummaryChip("기대수익", bestReturn?.name ?: "-", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryChip("성장성", bestGrowth?.name ?: "-", Modifier.weight(1f))
                SummaryChip("비교 개수", "${items.size}개", Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun SummaryChip(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ComparisonVerdictCard(items: List<StockComparisonItem>) {
    val balanced = bestComparisonItem(items) { it.scoreValue }
    val aggressive = bestComparisonItem(items) { averageComparisonValue(it.expectedReturn, it.revenueGrowth) }
    val stable = bestComparisonItem(items) { averageComparisonValue(it.roic, it.grossMargin, it.fcfMargin) }
    val caution = items.firstOrNull { (it.expectedReturn ?: 0.0) < 0.0 }
        ?: items.maxByOrNull { comparisonMissingMetricCount(it) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("다음 판단", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                comparisonVerdictText(balanced, aggressive, stable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComparisonRoleChip("균형형", balanced?.name ?: "-", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                ComparisonRoleChip("공격형", aggressive?.name ?: "-", QuantWarning, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComparisonRoleChip("안정형", stable?.name ?: "-", QuantPositive, Modifier.weight(1f))
                ComparisonRoleChip("주의", caution?.name ?: "-", QuantNegative, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ComparisonRoleChip(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun comparisonVerdictText(
    balanced: StockComparisonItem?,
    aggressive: StockComparisonItem?,
    stable: StockComparisonItem?
): String {
    if (balanced == null) {
        return "비교 데이터가 부족합니다. 먼저 점수, 성장성, 수익성 중 비어 있는 항목이 적은 종목을 확인하세요."
    }
    if (balanced.id == aggressive?.id && balanced.id == stable?.id) {
        return "${balanced.name}이 점수, 성장성, 수익성에서 가장 고르게 앞섭니다. 이 종목을 기준으로 나머지를 반박하세요."
    }
    return "기준 후보는 ${balanced.name}입니다. 수익률을 더 보려면 ${aggressive?.name ?: "-"}, 안정성을 더 보려면 ${stable?.name ?: "-"}을 대조하세요."
}

internal fun bestComparisonItem(
    items: List<StockComparisonItem>,
    value: (StockComparisonItem) -> Double?
): StockComparisonItem? {
    return items.mapNotNull { item ->
        value(item)?.takeIf { it.isFinite() }?.let { item to it }
    }.maxByOrNull { it.second }?.first
}

internal fun averageComparisonValue(vararg values: Double?): Double? {
    val clean = values.mapNotNull { value -> value?.takeIf { it.isFinite() } }
    if (clean.isEmpty()) return null
    return clean.sum() / clean.size
}

internal fun comparisonMissingMetricCount(item: StockComparisonItem): Int {
    return listOf(
        item.scoreValue,
        item.expectedReturn,
        item.revenueGrowth,
        item.roic,
        item.grossMargin,
        item.fcfMargin
    ).count { it == null }
}

@Composable
internal fun ComparisonMomentumCard(items: List<StockComparisonItem>) {
    val chartItems = items.filter { it.return1M?.isFinite() == true }
    val maxAbsMove = chartItems
        .mapNotNull { it.return1M?.let(::abs) }
        .maxOrNull()
        ?.coerceAtLeast(0.01) ?: 0.01

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("단기 흐름", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (chartItems.isEmpty()) {
                Text(
                    "1개월 수익률 데이터가 들어오면 차트 흐름을 같이 비교합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                chartItems.forEach { item ->
                    val move = item.return1M ?: 0.0
                    val color = if (move >= 0.0) QuantPositive else QuantNegative
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pct(move), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((abs(move) / maxAbsMove).toFloat().coerceIn(0.04f, 1f))
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(color)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ComparisonNewsReactionCard(items: List<StockComparisonItem>, newsItems: List<NewsItem>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("뉴스 반응", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, item ->
                val matchedNews = comparisonNewsItem(item, newsItems)
                val tone = comparisonNewsColor(item, newsItems)
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    TickerAvatar(item.ticker, item.market, size = 28.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Text(
                                comparisonNewsText(item, newsItems),
                                color = tone,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(tone.copy(alpha = 0.09f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            matchedNews?.title ?: "관련 뉴스 반응 데이터가 아직 없습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                }
            }
        }
    }
}

@Composable
internal fun ComparisonTable(items: List<StockComparisonItem>, metrics: List<CompareMetric>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("핵심 지표", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row {
                        ComparisonCell("지표", width = 86.dp, isHeader = true)
                        items.forEach { item ->
                            Column(
                                modifier = Modifier
                                    .width(118.dp)
                                    .height(54.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(item.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.ticker, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                    metrics.forEach { metric ->
                        val bestId = bestMetricId(items, metric)
                        Row {
                            ComparisonCell(metric.label, width = 86.dp)
                            items.forEach { item ->
                                ComparisonCell(
                                    text = metric.value(item),
                                    width = 118.dp,
                                    highlight = bestId == item.id
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ComparisonCell(
    text: String,
    width: Dp,
    isHeader: Boolean = false,
    highlight: Boolean = false
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(if (isHeader) 54.dp else 42.dp)
            .background(if (isHeader) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f) else Color.Transparent)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            color = if (highlight) MaterialTheme.colorScheme.primary else if (isHeader) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (highlight || isHeader) FontWeight.Bold else FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ComparisonCheckpoints(items: List<StockComparisonItem>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("확인 포인트", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    TickerAvatar(item.ticker, item.market, size = 28.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(comparePointText(item), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                }
            }
        }
    }
}

internal fun bestMetricId(items: List<StockComparisonItem>, metric: CompareMetric): String? {
    val values = items.mapNotNull { item ->
        metric.number(item)?.takeIf { it.isFinite() }?.let { item.id to it }
    }
    if (values.size <= 1) return null
    return if (metric.higherIsBetter) values.maxByOrNull { it.second }?.first else values.minByOrNull { it.second }?.first
}

internal fun compareVolumeText(value: Double?): String {
    return value?.takeIf { it.isFinite() }?.let { "x%.1f".format(it) } ?: "-"
}

internal fun comparePriceText(item: StockComparisonItem): String {
    val value = item.currentPrice?.takeIf { it.isFinite() } ?: return "-"
    return fmtPx(value, item.currency)
}

internal fun compareRankChangeText(value: Int?): String {
    return when {
        value == null -> "-"
        value > 0 -> "▲$value"
        value < 0 -> "▼${abs(value)}"
        else -> "유지"
    }
}

internal fun comparisonRiskScore(item: StockComparisonItem): Double? {
    val values = listOf(
        item.scoreValue,
        item.expectedReturn,
        item.revenueGrowth,
        item.roic,
        item.grossMargin,
        item.return1M,
        item.fcfMargin
    )
    val available = values.mapNotNull { it?.takeIf { value -> value.isFinite() } }
    if (available.isEmpty()) return null

    var risk = (values.size - available.size) * 5.0
    item.expectedReturn?.takeIf { it.isFinite() && it < 0.0 }?.let {
        risk += min(20.0, abs(it) * 100.0)
    }
    item.return1M?.takeIf { it.isFinite() && it < 0.0 }?.let {
        risk += min(18.0, abs(it) * 120.0)
    }
    item.volumeSurge?.takeIf { it.isFinite() && it > 3.0 }?.let {
        risk += 6.0
    }
    return risk
}

internal fun comparisonRiskText(item: StockComparisonItem): String {
    val score = comparisonRiskScore(item) ?: return "데이터 부족"
    return when {
        score >= 32.0 -> "높음"
        score >= 16.0 -> "보통"
        else -> "낮음"
    }
}

internal fun comparisonNewsItem(item: StockComparisonItem, newsItems: List<NewsItem>): NewsItem? {
    val itemKeys = comparisonMatchKeys(item.ticker)
    val tickerKey = normalizedTicker(item.ticker)
    return newsItems.mapNotNull { news ->
        val relatedKeys = (listOf(news.ticker) + news.relatedTickers)
            .flatMap { comparisonMatchKeys(it) }
            .toSet()
        val keyScore = if (itemKeys.intersect(relatedKeys).isNotEmpty()) 100.0 else 0.0
        val upperTitle = news.title.uppercase(Locale.US)
        val titleScore = if (upperTitle.contains(tickerKey) || upperTitle.contains(item.name.uppercase(Locale.US))) 20.0 else 0.0
        if (keyScore <= 0.0 && titleScore <= 0.0) return@mapNotNull null
        val total = keyScore + titleScore + abs(news.relatedChangePct ?: 0.0) * 1_000.0 + abs(news.impactScore) * 6.0
        news to total
    }.maxByOrNull { it.second }?.first
}

internal fun comparisonNewsText(item: StockComparisonItem, newsItems: List<NewsItem>): String {
    val news = comparisonNewsItem(item, newsItems) ?: return "-"
    val change = news.relatedChangePct
    if (change != null && change.isFinite()) {
        return pct(change)
    }
    return news.impactLabelKo.ifBlank { newsImpactFallbackLabel(news.impactLabel) }
}

internal fun comparisonNewsScore(item: StockComparisonItem, newsItems: List<NewsItem>): Double? {
    val news = comparisonNewsItem(item, newsItems) ?: return null
    val change = news.relatedChangePct
    if (change != null && change.isFinite()) {
        return change
    }
    val magnitude = max(abs(news.impactScore), 0.01)
    return when (news.impactLabel.lowercase(Locale.US)) {
        "positive" -> magnitude
        "negative" -> -magnitude
        else -> 0.0
    }
}

internal fun comparisonNewsColor(item: StockComparisonItem, newsItems: List<NewsItem>): Color {
    val score = comparisonNewsScore(item, newsItems) ?: return QuantMuted
    return when {
        score > 0.0 -> QuantPositive
        score < 0.0 -> QuantNegative
        else -> QuantMuted
    }
}

internal fun comparisonMatchKeys(value: String): Set<String> {
    val normalized = normalizedTicker(value)
    if (normalized.isBlank()) return emptySet()
    val keys = mutableSetOf(normalized)
    normalized.substringBefore('.').takeIf { it.isNotBlank() }?.let { keys.add(it) }
    val code = krCode(normalized)
    if (code.isNotBlank()) {
        keys.add(code)
        keys.add("$code.KS")
        keys.add("$code.KQ")
    }
    return keys
}

internal fun comparePointText(item: StockComparisonItem): String {
    return when {
        item.expectedReturn == null && item.fcfMargin == null ->
            "일부 핵심 지표가 비어 있어 점수와 성장성만으로 판단하지 않도록 주의하세요."
        (item.expectedReturn ?: 0.0) < 0.0 ->
            "기대수익이 음수라면 후보 유지보다 관망 사유를 먼저 확인하는 편이 좋습니다."
        (item.revenueGrowth ?: 0.0) > 0.15 ->
            "매출 성장성이 강한 편입니다. ROIC와 마진이 같이 받쳐주는지 확인하세요."
        else ->
            "점수, 성장성, 수익성 중 최소 두 축이 함께 좋은지 확인하세요."
    }
}
