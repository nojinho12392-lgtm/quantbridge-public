package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun DetailHeroCard(request: DetailRequest, detail: StockDetail?, error: String?) {
    val loaded = detail?.prices?.isNotEmpty() == true || detail?.info != null
    val statusText = when {
        error != null -> "일부 실패"
        loaded -> "상세 로드됨"
        else -> "기본 지표"
    }
    val statusTone = when {
        error != null -> DetailTone.Warning
        loaded -> DetailTone.Positive
        else -> DetailTone.Neutral
    }
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TickerAvatar(request.ticker, request.market)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    request.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${request.ticker} · ${request.currency}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            StatusPill(statusText, statusTone)
        }
        detail?.updatedAt?.takeIf { it.isNotBlank() }?.let {
            Text("업데이트 ${formattedUpdateTimestamp(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DetailSummaryCard(
    info: StockInfo?,
    updatedAt: String?,
    currency: String,
    isEtf: Boolean = false,
    onTermClick: (String) -> Unit = {}
) {
    val current = info?.currentPrice
    val prev = info?.prevClose
    val low = info?.week52Low
    val high = info?.week52High
    val changePct = if (current != null && prev != null && prev != 0.0) current / prev - 1.0 else null
    val changeDetail = if (current != null && prev != null) signedPx(current - prev, currency) else "전일 종가 없음"
    val rangePosition = if (current != null && low != null && high != null && high > low) {
        ((current - low) / (high - low)).coerceIn(0.0, 1.0)
    } else {
        null
    }
    val valuation = when {
        info?.forwardPe != null -> Triple("밸류에이션", "%.1fx".format(info.forwardPe), "Forward PER")
        info?.peRatio != null -> Triple("밸류에이션", "%.1fx".format(info.peRatio), "Trailing PER")
        info?.priceToBook != null -> Triple("밸류에이션", "%.1fx".format(info.priceToBook), "PBR")
        else -> Triple("밸류에이션", "-", stockValuationUnavailableReason(info))
    }
    val metrics = buildList {
        add(Triple("당일 흐름", changePct?.let { pct(it) } ?: "-", changeDetail))
        add(Triple("52주 위치", rangePosition?.let { "%.0f%%".format(it * 100) } ?: "-", rangePosition?.let { if (it >= 0.75) "고점권" else if (it <= 0.25) "저점권" else "중간권" } ?: "범위 데이터 없음"))
        if (!isEtf) add(valuation)
        add(Triple("업데이트", formattedUpdateTimestamp(updatedAt), "상세 데이터 기준"))
    }
    CardBlock {
        Text("핵심 요약", fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { metric ->
                        DetailSummaryTile(
                            label = metric.first,
                            value = metric.second,
                            detail = metric.third,
                            modifier = Modifier.weight(1f),
                            valueColor = when (metric.first) {
                                "당일 흐름" -> changePct?.let { marketMoveColor(it) }
                                else -> null
                            },
                            tone = when (metric.first) {
                                "당일 흐름" -> changePct?.let { if (it >= 0.0) DetailTone.Positive else DetailTone.Negative } ?: DetailTone.Neutral
                                "52주 위치" -> rangePosition?.let { if (it >= 0.75) DetailTone.Warning else DetailTone.Neutral } ?: DetailTone.Neutral
                                else -> DetailTone.Neutral
                            },
                            onTermClick = onTermClick
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun DetailSummaryTile(
    label: String,
    value: String,
    detail: String,
    tone: DetailTone,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    onTermClick: (String) -> Unit = {}
) {
    val termKey = glossaryKeyForLabel(detail) ?: glossaryKeyForLabel(label)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TermLabel(label = label, termKey = termKey, onTermClick = onTermClick)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = valueColor ?: toneColor(tone), maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun TermLabel(label: String, termKey: String?, onTermClick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (termKey != null) {
            LucideIconView(
                icon = LucideIcon.Lightbulb,
                contentDescription = "$label 설명",
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { onTermClick(termKey) },
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GlossaryCard(keys: List<String>, onTermClick: (String) -> Unit) {
    if (keys.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Text(
                "용어 설명",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "눌러서 자세히",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            keys.mapNotNull { glossaryInfo(it) }.forEach { info ->
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .height(38.dp)
                        .clickable { onTermClick(info.title) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            info.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DataTrustCard(source: String?, updatedAt: String?) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "데이터 신뢰도",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
        }
        InfoLine("출처", dataSourceLabel(source))
        InfoLine("업데이트", formattedUpdateTimestamp(updatedAt))
    }
}

@Composable
internal fun StatusPill(text: String, tone: DetailTone) {
    val color = toneColor(tone)
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun DataFreshnessBadge(level: DataFreshnessLevel, compact: Boolean = false) {
    val color = when (level) {
        DataFreshnessLevel.Fresh -> QuantGreen
        DataFreshnessLevel.Delayed -> QuantWarning
        DataFreshnessLevel.Stale -> QuantDanger
        DataFreshnessLevel.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(
                if (compact) level.label else "${level.label} · ${level.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun DetailErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        CardBlock {
            Text("상세 데이터를 불러오지 못했습니다", fontWeight = FontWeight.Bold)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("다시 시도")
            }
        }
    }
}

@Composable
internal fun StockDetailSkeleton(request: DetailRequest) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CardBlock {
                Text(request.name, fontWeight = FontWeight.Bold)
                SkeletonLine(width = 150.dp, height = 30.dp)
                SkeletonLine(width = 220.dp, height = 16.dp)
            }
        }
        item {
            CardBlock {
                Text("차트", fontWeight = FontWeight.Bold)
                SkeletonLine(width = 120.dp, height = 18.dp)
                SkeletonLine(height = 260.dp)
                SkeletonLine(width = 260.dp, height = 34.dp)
            }
        }
        item {
            CardBlock {
                Text("핵심 지표", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SkeletonLine(height = 72.dp, modifier = Modifier.weight(1f))
                    SkeletonLine(height = 72.dp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun SkeletonLine(
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null
) {
    val sizedModifier = if (width == null) modifier.fillMaxWidth() else modifier.width(width)
    Box(
        sizedModifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
    )
}
