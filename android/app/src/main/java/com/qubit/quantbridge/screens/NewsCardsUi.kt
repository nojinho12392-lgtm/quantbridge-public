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

@Composable
internal fun NewsCard(item: NewsItem, onOpen: () -> Unit) {
    val clickableModifier = if (item.url.isBlank()) {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
    }
    Surface(
        modifier = clickableModifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            NewsImpactHeader(item = item, timeText = newsRelativeTimeText(item))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            NewsRelatedImpactRow(item = item, tickerLimit = 3)
            NewsQubitAnalysisBlock(item = item, maxLines = 3)
            NewsFeedSourceRow(item)
        }
    }
}

@Composable
internal fun NewsImpactFeedCard(
    item: NewsItem,
    featured: Boolean,
    onOpen: () -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    val clickableModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 10.dp)
        .clip(shape)
        .quantClickable(enabled = item.url.isNotBlank(), role = QuantPressRole.Card, onClick = onOpen)

    Surface(
        modifier = clickableModifier,
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            NewsAssetHeader(item)
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 28.sp,
                maxLines = if (featured) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
            NewsRelatedImpactRow(item = item, tickerLimit = 4)
            NewsQubitAnalysisBlock(item = item, maxLines = if (featured) 3 else 2)
            NewsFeedSourceRow(item)
        }
    }
}

@Composable
internal fun NewsAssetHeader(item: NewsItem) {
    val ticker = newsAssetTicker(item)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (ticker == "NEWS") {
            NewsGlobalAssetAvatar()
        } else {
            TickerAvatar(ticker, item.market, size = 50.dp)
        }
        Text(
            newsAssetName(item, ticker),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        NewsImpactSignalPill(item)
    }
}

@Composable
internal fun NewsGlobalAssetAvatar() {
    Surface(
        modifier = Modifier.size(50.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            LucideIconView(
                icon = LucideIcon.Globe2,
                contentDescription = "글로벌 뉴스",
                modifier = Modifier.size(23.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun NewsImpactSignalPill(item: NewsItem) {
    val color = newsSignalColor(item)
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LucideIconView(
            icon = if (item.impactLabel.lowercase(Locale.US) == "negative") LucideIcon.TrendingDown else LucideIcon.TrendingUp,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = color
        )
        Text(
            newsSignalLabel(item),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
internal fun NewsRelatedImpactRow(item: NewsItem, tickerLimit: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            "관련",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        newsRelatedTickers(item).take(tickerLimit).forEach { ticker ->
            Text(
                ticker,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        NewsMovePill(item)
    }
}

@Composable
internal fun NewsFeedSourceRow(item: NewsItem) {
    val source = newsSourceText(item) ?: newsMarketLabel(item.market).ifBlank { "시장" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "출처: $source",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            newsRelativeTimeText(item),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        if (item.url.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.Globe2,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "원문 보기",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}
