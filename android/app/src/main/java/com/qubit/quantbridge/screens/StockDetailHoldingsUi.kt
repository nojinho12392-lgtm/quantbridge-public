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
internal fun DetailHoldingsCard(
    holdings: List<DetailHolding>,
    resolvingHoldingKey: String?,
    onHoldingClick: (DetailHolding) -> Unit
) {
    val visible = holdings.take(10)
    val totalWeight = visible.sumOf { max(it.weight, 0.0) }
    val otherWeight = max(0.0, 1.0 - totalWeight)
    val showOther = otherWeight > 0.0001

    CardBlock {
        Text("보유 비중 Top10", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (visible.isEmpty()) {
            Text(
                "구성 종목 데이터가 도착하면 원형 그래프와 상위 보유비중을 표시합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                if (showOther) {
                    "상위 ${visible.size}개 합산 ${pct(totalWeight, signed = false)} · 기타 ${pct(otherWeight, signed = false)}"
                } else {
                    "상위 ${visible.size}개 기준 · 합산 ${pct(totalWeight, signed = false)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DetailHoldingDonutChart(
                holdings = visible,
                otherWeight = otherWeight,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                visible.forEachIndexed { index, holding ->
                    DetailHoldingLegendRow(
                        holding = holding,
                        color = DetailHoldingPalette[index % DetailHoldingPalette.size],
                        loading = resolvingHoldingKey == holding.ticker || resolvingHoldingKey == holding.name,
                        onClick = { onHoldingClick(holding) }
                    )
                }
                if (showOther) {
                    DetailHoldingLegendRow("기타", otherWeight, DetailHoldingOtherColor)
                }
            }
        }
    }
}

@Composable
internal fun DetailHoldingDonutChart(
    holdings: List<DetailHolding>,
    otherWeight: Double,
    modifier: Modifier = Modifier
) {
    val totalWeight = holdings.sumOf { max(it.weight, 0.0) } + max(otherWeight, 0.0)
    Canvas(modifier = modifier.size(168.dp)) {
        if (totalWeight <= 0.0) return@Canvas
        val strokeWidth = min(size.width, size.height) * 0.20f
        val diameter = min(size.width, size.height) - strokeWidth
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        var startAngle = -90f
        holdings.forEachIndexed { index, holding ->
            val sweep = (max(holding.weight, 0.0) / totalWeight * 360.0).toFloat()
            drawArc(
                color = DetailHoldingPalette[index % DetailHoldingPalette.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            startAngle += sweep
        }
        val otherSweep = (max(otherWeight, 0.0) / totalWeight * 360.0).toFloat()
        if (otherSweep > 0f) {
            drawArc(
                color = DetailHoldingOtherColor,
                startAngle = startAngle,
                sweepAngle = otherSweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
        }
    }
}

@Composable
internal fun DetailHoldingLegendRow(
    holding: DetailHolding,
    color: Color,
    loading: Boolean,
    onClick: () -> Unit
) {
    DetailHoldingLegendRow(
        title = holding.name,
        weight = holding.weight,
        color = color,
        loading = loading,
        clickable = true,
        onClick = onClick
    )
}

@Composable
internal fun DetailHoldingLegendRow(
    title: String,
    weight: Double,
    color: Color,
    loading: Boolean = false,
    clickable: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.quantClickable(role = QuantPressRole.Row, onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            pct(weight, signed = false),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

internal val DetailHoldingPalette = listOf(
    Color(0xFF2F80ED),
    Color(0xFF23A6A6),
    Color(0xFF45CC96),
    Color(0xFFFFD15A),
    Color(0xFFFF980A),
    Color(0xFFE9540A),
    Color(0xFF4CB9D6),
    Color(0xFF1469AD),
    Color(0xFFE54868),
    Color(0xFFAD14D1)
)

internal val DetailHoldingOtherColor = Color(0xFFD2D8E0)

internal fun holdingMarket(ticker: String): String? {
    return when {
        ticker.endsWith(".KS", ignoreCase = true) || ticker.endsWith(".KQ", ignoreCase = true) -> "KR"
        ticker.all { it.isLetter() } -> "US"
        else -> null
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun DetailPriceMini(
    request: DetailRequest,
    info: StockInfo?,
    source: String?,
    updatedAt: String?,
    error: String?
) {
    val px = info?.currentPrice
    if (px == null) {
        Text(
            if (error != null) "시세 지연" else "시세 대기",
            modifier = Modifier.width(86.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        return
    }
    val changePct = detailDailyChangePct(info, px)
    val change = detailDailyChangeAmount(info, px, changePct)
    Column(
        modifier = Modifier.width(112.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            fmtPx(px, request.currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (change != null && changePct != null) {
            Text(
                "${signedPx(change, request.currency)} ${pct(changePct)}",
                color = marketMoveColor(change),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
    }
}
