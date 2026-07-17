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
internal fun DetailTopDecisionCard(
    request: DetailRequest,
    detail: StockDetail?,
    error: String?,
    watched: Boolean,
    comparisonSelected: Boolean,
    onWatch: () -> Unit,
    onMemo: () -> Unit,
    onCompare: () -> Unit
) {
    val conclusion = detailConclusion(request, detail)
    val info = detail?.info
    val topMetrics = request.sections
        .flatMap { it.metrics }
        .filter { it.value.isNotBlank() && it.value != "-" }
        .take(2)
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TickerAvatar(request.ticker, request.market)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    request.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${request.ticker} · ${if (request.currency == "KRW") "한국" else "미국"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            DetailPriceMini(request, info, detail?.source, detail?.updatedAt, error)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(toneColor(conclusion.tone).copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = detailConclusionIcon(conclusion),
                    contentDescription = null,
                    tint = toneColor(conclusion.tone),
                    modifier = Modifier.size(15.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conclusion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = toneColor(conclusion.tone))
                Text(conclusion.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(conclusion.badge, conclusion.tone)
        }
        if (topMetrics.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                topMetrics.forEach { metric ->
                    CompactMetricTile(
                        label = metric.label,
                        value = metric.value,
                        modifier = Modifier.weight(1f),
                        color = toneColor(metric.tone)
                    )
                }
                if (topMetrics.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        val watchActionLabel = if (watched) "${request.name} 관심 종목 제거" else "${request.name} 관심 종목 추가"
        val compareActionLabel = if (comparisonSelected) "${request.name} 비교 목록에 추가됨" else "${request.name} 비교 목록에 추가"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onWatch,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = watchActionLabel
                        onClick(label = watchActionLabel) {
                            onWatch()
                            true
                        }
                    },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(if (watched) "관심중" else "관심")
            }
            QuantActionButton(
                label = "비교",
                onClick = onCompare,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = compareActionLabel
                        onClick(label = compareActionLabel) {
                            onCompare()
                            true
                        }
                    },
                complete = comparisonSelected
            ) {
                LucideIconView(
                    icon = if (comparisonSelected) LucideIcon.Check else LucideIcon.GitCompare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("비교", maxLines = 1)
            }
            QuantIconActionButton(
                icon = LucideIcon.SlidersHorizontal,
                contentDescription = "${request.name} 관심 설정",
                onClick = onMemo,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DataFreshnessBadge(source = detail?.source, updatedAt = detail?.updatedAt, compact = true)
    }
}
