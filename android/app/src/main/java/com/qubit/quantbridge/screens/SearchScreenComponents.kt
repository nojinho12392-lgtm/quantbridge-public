package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.Locale

internal enum class SearchCompanyFilter(val label: String) {
    All("전체"),
    Portfolio("포트"),
    SmallCap("스몰캡"),
    Unwatched("미저장");

    fun matches(stock: SearchStock, watchlist: List<WatchlistItem>): Boolean {
        return when (this) {
            All -> true
            Portfolio -> stock.inPortfolio
            SmallCap -> stock.inSmallCap
            Unwatched -> watchlist.none { normalizedTicker(it.ticker) == normalizedTicker(stock.ticker) }
        }
    }
}

internal enum class SearchResultGroup(val label: String, val icon: LucideIcon, val order: Int) {
    Company("기업", LucideIcon.Building2, 0),
    Etf("ETF", LucideIcon.PieChart, 1),
    Indicator("지수", LucideIcon.LineChart, 2),
    Other("기타", LucideIcon.Search, 3)
}

@Composable
internal fun SearchComparisonDock(
    count: Int,
    onOpen: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LucideIconView(
                icon = LucideIcon.GitCompare,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("비교 $count/4", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("비우기") }
            Button(onClick = onOpen, enabled = count >= 2) { Text("보기") }
        }
    }
}

@Composable
internal fun PolicyAdjustedRankingBlock(ranking: PolicyAdjustedRanking) {
    val summary = ranking.summary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusRow(
            title = "${ranking.market} 정책 조정",
            status = if (summary?.productionReady == true) "Ready" else "Hold",
            subtitle = "상승 ${summary?.positiveMovers ?: 0} · 하락 ${summary?.negativeMovers ?: 0} · 평균 변화 ${num(summary?.meanAbsRankChange)}계단 · ${summary?.multipliers ?: "-"}"
        )
        ranking.topUp.take(3).forEach { item ->
            StatusRow(
                title = "▲ ${item.name}",
                status = signedStep(item.rankChange),
                subtitle = policyAdjustedRankingItemSubtitle(item)
            )
        }
        ranking.topDown.take(3).forEach { item ->
            StatusRow(
                title = "▼ ${item.name}",
                status = signedStep(item.rankChange),
                subtitle = policyAdjustedRankingItemSubtitle(item)
            )
        }
    }
}

internal fun policyRankingHeaderValue(rankings: List<PolicyAdjustedRanking>): String {
    if (rankings.isEmpty()) return "-"
    val readyCount = rankings.count { it.summary?.productionReady == true }
    return if (readyCount == rankings.size) "Ready" else "Hold"
}

internal fun policyRankingHeaderSubtitle(rankings: List<PolicyAdjustedRanking>): String {
    if (rankings.isEmpty()) return "정책 조정 랭킹 대기"
    return rankings.joinToString(" / ") { ranking ->
        val summary = ranking.summary
        val up = summary?.topUpTicker ?: ranking.topUp.firstOrNull()?.ticker ?: "-"
        val down = summary?.topDownTicker ?: ranking.topDown.firstOrNull()?.ticker ?: "-"
        "${ranking.market} $up↑ · $down↓"
    }
}

internal fun policyAdjustedRankingItemSubtitle(item: PolicyAdjustedRankingItem): String {
    return "${item.ticker} · 기준 #${item.baseRank ?: "-"} → 정책 #${item.policyRank ?: "-"} · 점수 ${num(item.policyFinalScore)} · 변화 ${pct(item.scoreChange)}"
}

internal fun signedStep(value: Int?): String {
    return when {
        value == null -> "-"
        value > 0 -> "+$value"
        else -> value.toString()
    }
}

@Composable
internal fun StatusRow(title: String, status: String, subtitle: String) {
    val tone = statusTone(status)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(status.uppercase(Locale.US), color = toneColor(tone), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}
