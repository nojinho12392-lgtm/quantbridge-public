package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun PortfolioCompanyRow(
    rankLabel: String,
    rankChange: Int?,
    rankStatus: String?,
    ticker: String,
    market: String?,
    name: String,
    sectorLabel: String,
    personalLine: String? = null,
    priceText: String,
    return1M: Double?,
    source: String?,
    updatedAt: String?,
    comparisonMode: Boolean,
    comparisonSelected: Boolean,
    comparisonDisabled: Boolean,
    onOpen: () -> Unit
) {
    val namePriceGap = if (isKoreanTicker(ticker, market)) {
        PortfolioListNamePriceGap
    } else {
        PortfolioListUsNamePriceGap
    }
    val rowShape = RoundedCornerShape(24.dp)
    val accessibilitySummary = remember(
        rankLabel,
        name,
        priceText,
        return1M,
        personalLine,
        comparisonMode,
        comparisonSelected
    ) {
        listOf(
            "${rankLabel}위",
            name,
            "가격 $priceText",
            "1개월 수익률 ${pct(return1M)}",
            personalLine.orEmpty(),
            if (comparisonMode) {
                if (comparisonSelected) "비교 선택됨" else "비교 선택 가능"
            } else {
                "상세 보기"
            }
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = PortfolioListRowMinHeight)
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surface)
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibilitySummary
                onClick(label = "상세 보기") {
                    onOpen()
                    true
                }
            }
            .padding(horizontal = 12.dp, vertical = PortfolioListVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.width(32.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                rankLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            RankMovementBadge(rankChange, rankStatus)
        }

        TickerAvatar(ticker, market, size = PortfolioListLogoSize)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(namePriceGap)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PortfolioSectorChip(sectorLabel)
                AnimatedPriceText(
                    text = priceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            personalLine?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier.width(if (comparisonMode) 104.dp else 84.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    pct(return1M),
                    style = MaterialTheme.typography.titleMedium,
                    color = return1M?.takeIf { it.isFinite() }?.let { marketMoveColor(it) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
                if (comparisonMode) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = if (comparisonSelected) "비교 선택됨" else "비교 선택",
                        tint = if (comparisonSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (comparisonDisabled) 0.34f else 0.58f)
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
        }
    }
}

@Composable
internal fun PortfolioSectorChip(label: String) {
    Text(
        label.take(14).ifBlank { "분류 없음" },
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

fun portfolioPriceText(value: Double?, currency: String): String {
    if (value == null || !value.isFinite()) return "-"
    return if (currency == "KRW") {
        "${groupedInteger(value.roundToLong())}원"
    } else {
        fmtPx(value, currency)
    }
}

@Composable
internal fun StockRow(
    rankLabel: String? = null,
    title: String,
    ticker: String,
    market: String?,
    subtitle: String,
    headline: String,
    kpis: List<Pair<String, String>>,
    watched: Boolean,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onCompare: (() -> Unit)? = null,
    onWatch: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = onOpen)
            .clip(RoundedCornerShape(24.dp)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rankLabel?.let { RankBadge(it) }
                TickerAvatar(ticker, market)
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$ticker · $subtitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    headline,
                    modifier = Modifier.width(62.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
                FavoriteButton(watched, onWatch)
                if (onCompare != null) {
                    val compareLabel = if (comparisonSelected) "$title 비교 목록에 추가됨" else "$title 비교 목록에 추가"
                    IconButton(
                        onClick = onCompare,
                        modifier = Modifier
                            .size(34.dp)
                            .clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = compareLabel
                                onClick(label = compareLabel) {
                                    onCompare()
                                    true
                                }
                            }
                    ) {
                        Icon(
                            imageVector = if (comparisonSelected) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.CompareArrows,
                            contentDescription = null,
                            tint = if (comparisonSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (comparisonMode) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = if (comparisonSelected) "비교 선택됨" else "비교 선택",
                        tint = if (comparisonSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (comparisonDisabled) 0.34f else 0.58f)
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            if (kpis.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.width(if (rankLabel == null) 0.dp else 34.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        kpis.forEach { (label, value) -> Kpi(label, value) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RankBadge(label: String) {
    Text(
        label,
        modifier = Modifier.width(24.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}
