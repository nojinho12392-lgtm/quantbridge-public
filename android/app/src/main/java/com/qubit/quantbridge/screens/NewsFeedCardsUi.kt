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
fun CompactNewsCard(item: NewsItem, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(8.dp))
            .quantClickable(enabled = item.url.isNotBlank(), role = QuantPressRole.Card, onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsImpactHeader(item = item, timeText = newsRelativeTimeText(item))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            NewsRelatedImpactRow(item = item, tickerLimit = 2)
            NewsQubitAnalysisBlock(item = item, maxLines = 2, compact = true)
            NewsRelatedFooter(item = item, tickerLimit = 2, showOpen = false, compact = true)
        }
    }
}

@Composable
internal fun NewsImpactHeader(item: NewsItem, timeText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        NewsImpactPill(item)
        if (item.market.isNotBlank()) {
            Text(
                newsMarketLabel(item.market),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        NewsMovePill(item)
        Spacer(Modifier.weight(1f))
        Text(
            timeText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun NewsQubitAnalysisBlock(item: NewsItem, maxLines: Int, compact: Boolean = false) {
    val impactText = item.impactReason.ifBlank { "큐빗이 관련 종목과 가격 반응을 확인한 뒤 원문 링크 확인이 필요한 뉴스로 분류했습니다." }
    if (impactText.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            LucideIconView(
                icon = LucideIcon.LineChart,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 13.dp else 15.dp),
                tint = newsImpactColor(item)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "큐빗 분석",
                        color = newsImpactColor(item),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        item.impactLabelKo.ifBlank { newsImpactFallbackLabel(item.impactLabel) },
                        color = newsImpactColor(item),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(newsImpactColor(item).copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
                Text(
                    impactText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun NewsRelatedFooter(item: NewsItem, tickerLimit: Int, showOpen: Boolean, compact: Boolean = false) {
    val source = newsSourceText(item)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "관련",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        newsRelatedTickers(item).take(tickerLimit).forEach { ticker ->
            Text(
                ticker,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        source?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (showOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIconView(
                    icon = LucideIcon.Newspaper,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 11.dp else 12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "원문 보기",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
