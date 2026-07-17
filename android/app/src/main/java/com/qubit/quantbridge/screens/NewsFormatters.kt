package com.qubit.quantbridge

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal fun newsAssetTicker(item: NewsItem): String {
    return newsRelatedTickers(item).firstOrNull()?.takeIf { it.isNotBlank() }
        ?: item.ticker.takeIf { it.isNotBlank() }
        ?: when (item.market.uppercase(Locale.US)) {
            "US" -> "SPY"
            "KR" -> "KOSPI"
            else -> "NEWS"
        }
}

internal fun newsAssetName(item: NewsItem, ticker: String = newsAssetTicker(item)): String {
    if (ticker == "NEWS") return newsMarketLabel(item.market).ifBlank { "시장" }
    return localizedCompanyName(ticker, ticker, item.market).ifBlank { ticker }
}

internal fun newsSignalLabel(item: NewsItem): String {
    return when (item.impactLabel.lowercase(Locale.US)) {
        "positive" -> "긍정"
        "negative" -> "부정"
        else -> "중립"
    }
}

@Composable
internal fun newsSignalColor(item: NewsItem): Color {
    return when (item.impactLabel.lowercase(Locale.US)) {
        "positive" -> newsImpactColor(item)
        "negative" -> newsImpactColor(item)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun newsRelativeTimeText(item: NewsItem): String {
    val raw = item.publishedAt.trim()
    if (raw.isBlank()) return "방금"
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(raw) }.getOrNull()
    if (instant != null) {
        val minutes = max(0L, Duration.between(instant, Instant.now()).toMinutes())
        return when {
            minutes < 1L -> "방금"
            minutes < 60L -> "${minutes}분 전"
            minutes < 24L * 60L -> "${minutes / 60L}시간 전"
            minutes < 7L * 24L * 60L -> "${minutes / (24L * 60L)}일 전"
            else -> DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
    }
    return formattedUpdateTimestamp(raw)
}

internal fun newsMoveHorizonLabel(value: String): String {
    val clean = value.trim()
    return when (clean) {
        "오늘", "전장" -> " $clean"
        else -> ""
    }
}

@Composable
internal fun newsImpactColor(item: NewsItem): Color {
    return when (item.impactLabel.lowercase(Locale.US)) {
        "positive" -> Color(0xFFD93025)
        "negative" -> Color(0xFF1A73E8)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun newsSourceText(item: NewsItem): String? {
    val source = item.source.trim()
    if (
        source.isBlank() ||
        source == "-" ||
        source.equals("QuantBridge", ignoreCase = true) ||
        source.equals("Qubit", ignoreCase = true) ||
        source.equals("큐빗", ignoreCase = true)
    ) {
        return null
    }
    return source
}

internal fun newsImpactFallbackLabel(label: String): String {
    return when (label.lowercase(Locale.US)) {
        "positive" -> "긍정"
        "negative" -> "부정"
        else -> "중립"
    }
}

internal fun newsRelatedTickers(item: NewsItem): List<String> {
    return item.relatedTickers.ifEmpty {
        if (item.ticker.isBlank()) emptyList() else listOf(item.ticker)
    }
}
