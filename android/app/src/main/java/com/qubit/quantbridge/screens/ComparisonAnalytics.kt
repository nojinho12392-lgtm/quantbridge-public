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
