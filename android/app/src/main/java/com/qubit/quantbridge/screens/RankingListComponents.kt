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

internal data class RankingDatasetFreshness(
    val source: String?,
    val updatedAt: String?,
    val visibleCount: Int,
    val totalCount: Int
)

internal data class RankingUpdateMarker(
    val source: String?,
    val lastUpdated: String?,
    val generatedAt: String?
)

internal fun portfolioDatasetFreshness(
    stocks: List<PortfolioStock>,
    visibleCount: Int
): RankingDatasetFreshness {
    return rankingDatasetFreshness(
        markers = stocks.map {
            RankingUpdateMarker(it.source, it.lastUpdated, it.generatedAt)
        },
        visibleCount = visibleCount,
        totalCount = stocks.size
    )
}

internal fun smallCapDatasetFreshness(
    stocks: List<SmallCapStock>,
    visibleCount: Int
): RankingDatasetFreshness {
    return rankingDatasetFreshness(
        markers = stocks.map {
            RankingUpdateMarker(it.source, it.lastUpdated, it.generatedAt)
        },
        visibleCount = visibleCount,
        totalCount = stocks.size
    )
}

internal fun rankingDatasetFreshness(
    markers: List<RankingUpdateMarker>,
    visibleCount: Int,
    totalCount: Int
): RankingDatasetFreshness {
    val latest = latestRankingUpdate(markers)
    val updatedAt = latest?.updatedAt()
    val source = latest?.source?.trim()?.takeIf { it.isNotBlank() }
        ?: latest?.lastUpdated?.trim()?.takeIf { it.isNotBlank() }?.let { "storage" }
    return RankingDatasetFreshness(
        source = source,
        updatedAt = updatedAt,
        visibleCount = visibleCount,
        totalCount = totalCount
    )
}

internal fun latestRankingUpdate(markers: List<RankingUpdateMarker>): RankingUpdateMarker? {
    return markers.mapNotNull { marker ->
        val updatedAt = marker.updatedAt() ?: return@mapNotNull null
        val epochMillis = parsedUpdateInstant(updatedAt)?.toEpochMilli() ?: Long.MIN_VALUE
        marker to epochMillis
    }.maxByOrNull { it.second }?.first
        ?: markers.firstOrNull { it.updatedAt() != null }
}

internal fun RankingUpdateMarker.updatedAt(): String? {
    return lastUpdated?.trim()?.takeIf { it.isNotBlank() }
        ?: generatedAt?.trim()?.takeIf { it.isNotBlank() }
}

@Composable
internal fun rankingDatasetFreshnessRow(summary: RankingDatasetFreshness) {
    if (summary.totalCount > 0) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.Database,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "데이터 기준",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        "갱신 ${formattedUpdateTimestamp(summary.updatedAt)} · 표시 " +
                            "${summary.visibleCount}/${summary.totalCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (dataFreshnessDisplay(summary.source, summary.updatedAt) != null) {
                    DataFreshnessBadge(
                        source = summary.source,
                        updatedAt = summary.updatedAt,
                        compact = true
                    )
                } else {
                    DataFreshnessBadge(
                        level = dataFreshnessLevel(summary.updatedAt),
                        compact = true
                    )
                }
            }
        }
    }
}
