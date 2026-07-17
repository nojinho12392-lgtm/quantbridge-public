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

@Composable
internal fun EtfDetailScreen(etf: EtfInsight, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ETF 목록으로 돌아가기")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(etf.ticker, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(etf.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { EtfDetailHeader(etf) }
        item { EtfHoldingsSection(etf) }
        item { EtfExposureSection(etf) }
        item { EtfOutlookSection(etf) }
    }
}

@Composable
internal fun EtfDetailHeader(etf: EtfInsight) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EtfAvatar(etf.ticker, etf.name, etf.region, etf.category, etf.theme, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(etf.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${etf.ticker} · ${etf.region} · ${etf.theme}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(etf.displaySummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EtfDetailMetric("총보수", etf.expenseRatio, Modifier.weight(1f))
            EtfDetailMetric("AUM", etf.aum, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EtfDetailMetric("분배", etf.distribution, Modifier.weight(1f))
            EtfDetailMetric("유형", etf.category, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun EtfHoldingsSection(etf: EtfInsight) {
    val maxWeight = etf.holdings.maxOfOrNull { it.weight }?.takeIf { it > 0.0 } ?: 0.01
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.ListOrdered,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("Top 구성종목", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        etf.holdings.forEach { holding ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TickerAvatar(holding.ticker, null, size = 32.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(holding.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(holding.ticker, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(pct(holding.weight, signed = false), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((holding.weight / maxWeight).toFloat().coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
internal fun EtfExposureSection(etf: EtfInsight) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Globe2,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("섹터/지역 노출", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        etf.exposures.forEachIndexed { index, exposure ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(exposure.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(pct(exposure.weight, signed = false), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(exposure.weight.toFloat().coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(exposureColor(index))
                    )
                }
            }
        }
    }
}

@Composable
internal fun EtfOutlookSection(etf: EtfInsight) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Sparkles,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("전망과 리스크", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        EtfOutlookRow(LucideIcon.TrendingUp, "전망", etf.outlook, QuantGreen)
        EtfOutlookRow(LucideIcon.TriangleAlert, "주의", etf.risk, QuantWarning)
    }
}

@Composable
internal fun EtfOutlookRow(icon: LucideIcon, title: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = color.copy(alpha = 0.10f),
            shape = CircleShape
        ) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.padding(5.dp).size(14.dp),
                tint = color
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun EtfDetailMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun EtfMetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun EtfAvatar(ticker: String, name: String, region: String, category: String, theme: String, size: Dp) {
    val background = remember(name, category, theme) { etfAvatarColor(name, category, theme) }
    val foreground = remember(name) { etfAvatarTextColor(name) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        if (region == "KR") {
            val lines = domesticEtfLogoLines(name, theme)
            val textSize = domesticEtfLogoFontSize(size, lines)
            Column(
                modifier = Modifier.padding(horizontal = size * 0.07f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        modifier = Modifier.fillMaxWidth(),
                        color = foreground,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = textSize,
                        lineHeight = (textSize.value + 1f).sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Text(
                ticker.take(if (ticker.length > 4) 3 else 4),
                color = foreground,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}
