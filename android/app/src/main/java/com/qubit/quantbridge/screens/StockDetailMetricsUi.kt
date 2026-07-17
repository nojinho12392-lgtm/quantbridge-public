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
internal fun PriceHeaderCard(request: DetailRequest, info: StockInfo?) {
    CardBlock {
        Text("현재가", fontWeight = FontWeight.Bold)
        if (info?.currentPrice == null) {
            Text("가격 데이터 없음", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(
                "현재 상세 응답에 시세 정보가 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            val px = info.currentPrice
            val change = info.prevClose?.let { px - it }
            val changePct = info.prevClose?.takeIf { it != 0.0 }?.let { (px / it) - 1.0 }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedPriceText(
                    text = fmtPx(px, request.currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (change != null && changePct != null) {
                    Text(
                        "${signedPx(change, request.currency)} (${pct(changePct)})",
                        color = marketMoveColor(change),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            info.sector?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun RangeCard(info: StockInfo, currency: String) {
    val low = info.week52Low ?: return
    val high = info.week52High ?: return
    val current = info.currentPrice ?: return
    val position = if (high > low) ((current - low) / (high - low)).coerceIn(0.0, 1.0) else 0.5
    CardBlock {
        Text("52주 범위", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2f
            drawLine(
                color = QuantLine,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = 8f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = QuantNegative,
                radius = 9f,
                center = androidx.compose.ui.geometry.Offset((size.width * position).toFloat(), y)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtPx(low, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtPx(current, currency), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(fmtPx(high, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CompanyProfileCard(info: StockInfo) {
    MetricSection(
        DetailSection(
            "기업 프로필",
            listOfNotNull(
                info.industry?.takeIf { it.isNotBlank() }?.let { DetailMetric("산업", it) },
                profileLocation(info)?.let { DetailMetric("지역", it) },
                info.employees?.let { DetailMetric("직원 수", "%,d".format(it)) },
                info.website?.takeIf { it.isNotBlank() }?.let { DetailMetric("웹사이트", it) }
            )
        )
    )
}

@Composable
internal fun MarketInfoCard(info: StockInfo, currency: String, onTermClick: (String) -> Unit = {}) {
    MetricSection(
        DetailSection(
            "시장 정보",
            listOfNotNull(
                DetailMetric("현재가", info.currentPrice?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("전일 종가", info.prevClose?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("52주 고가", info.week52High?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("52주 저가", info.week52Low?.let { fmtPx(it, currency) } ?: "-"),
                DetailMetric("시가총액", cap(info.marketCap, currency)),
                info.peRatio?.let { DetailMetric("PER", "%.1f".format(it)) },
                info.forwardPe?.let { DetailMetric("Forward PER", "%.1f".format(it)) },
                info.priceToSales?.let { DetailMetric("P/S", "%.1f".format(it)) },
                info.priceToBook?.let { DetailMetric("P/B", "%.1f".format(it)) },
                info.beta?.let { DetailMetric("베타", "%.2f".format(it)) },
                info.targetMeanPrice?.let { DetailMetric("목표가 평균", fmtPx(it, currency), DetailTone.Primary) },
                normalizedRecommendation(info.recommendation)?.let { DetailMetric("컨센서스", it.replaceFirstChar { c -> c.titlecase(Locale.US) }) }
            )
        ),
        onTermClick
    )
}

@Composable
internal fun FinancialSnapshotCard(info: StockInfo, currency: String, onTermClick: (String) -> Unit = {}) {
    MetricSection(
        DetailSection(
            "재무 스냅샷",
            listOfNotNull(
                info.totalRevenue?.let { DetailMetric("매출", cap(it, currency)) },
                info.revenueGrowth?.let { DetailMetric("매출 성장", pct(it), returnTone(it)) },
                info.grossMargin?.let { DetailMetric("매출총이익률", pct(it, signed = false), ratioTone(it, 0.40, 0.20)) },
                info.operatingMargin?.let { DetailMetric("영업이익률", pct(it, signed = false), ratioTone(it, 0.15, 0.0)) },
                info.profitMargin?.let { DetailMetric("순이익률", pct(it, signed = false), ratioTone(it, 0.10, 0.0)) },
                info.ebitdaMargin?.let { DetailMetric("EBITDA 마진", pct(it, signed = false), ratioTone(it, 0.20, 0.05)) },
                info.ebitda?.let { DetailMetric("EBITDA", cap(it, currency)) },
                info.freeCashflow?.let { DetailMetric("FCF", cap(it, currency)) },
                info.totalDebt?.let { DetailMetric("총부채", cap(it, currency)) },
                info.debtToEquity?.let { DetailMetric("Debt/Equity", "%.1f".format(it), inverseTone(it, 100.0, 200.0)) },
                info.returnOnEquity?.let { DetailMetric("ROE", pct(it, signed = false), ratioTone(it, 0.15, 0.05)) }
            )
        ),
        onTermClick
    )
}

@Composable
internal fun ReturnStatsCard(points: List<PricePoint>, currency: String, onTermClick: (String) -> Unit = {}) {
    val metrics = remember(points, currency) { returnMetrics(points, currency) }
    if (metrics.isEmpty()) return
    MetricSection(DetailSection("기간 수익률 / 리스크", metrics), onTermClick)
}

@Composable
internal fun FactorRadarCard(factors: List<FactorScore>) {
    CardBlock {
        Text("팩터 점수", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(210.dp)) {
            val count = factors.size.coerceAtLeast(3)
            val radius = min(size.width, size.height) * 0.38f
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            for (step in 1..4) {
                val r = radius * step / 4f
                val grid = Path()
                for (i in 0 until count) {
                    val angle = -PI / 2.0 + 2.0 * PI * i / count
                    val x = centerX + (cos(angle) * r).toFloat()
                    val y = centerY + (sin(angle) * r).toFloat()
                    if (i == 0) grid.moveTo(x, y) else grid.lineTo(x, y)
                }
                grid.close()
                drawPath(grid, QuantLine, style = Stroke(width = 1.5f))
            }

            val shape = Path()
            factors.forEachIndexed { index, factor ->
                val r = radius * (factor.value.coerceIn(0.0, 100.0) / 100.0).toFloat()
                val angle = -PI / 2.0 + 2.0 * PI * index / factors.size
                val x = centerX + (cos(angle) * r).toFloat()
                val y = centerY + (sin(angle) * r).toFloat()
                if (index == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
            }
            shape.close()
            drawPath(shape, QuantBlue.copy(alpha = 0.20f))
            drawPath(shape, QuantBlue, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            factors.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { factor ->
                        Text(
                            "${factor.label} ${factor.value.toInt()}",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun SignalCards(signals: List<DetailSignal>) {
    CardBlock {
        Text("투자 근거", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        signals.forEach { signal ->
            Surface(
                color = toneColor(signal.tone).copy(alpha = 0.10f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(signal.title, color = toneColor(signal.tone), fontWeight = FontWeight.SemiBold)
                    Text(signal.detail, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun MetricSection(section: DetailSection, onTermClick: (String) -> Unit = {}) {
    if (section.metrics.isEmpty()) return
    CardBlock {
        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        section.metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { metric ->
                    MetricTile(metric, Modifier.weight(1f), onTermClick)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun MetricTile(metric: DetailMetric, modifier: Modifier = Modifier, onTermClick: (String) -> Unit = {}) {
    val accent = toneColor(metric.tone)
    val termKey = glossaryKeyForLabel(metric.label)
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TermLabel(label = metric.label, termKey = termKey, onTermClick = onTermClick)
            Text(metric.value, style = MaterialTheme.typography.titleSmall, color = toneColor(metric.tone), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
