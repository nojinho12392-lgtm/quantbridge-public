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

@Composable
internal fun RecentSearchChips(
    items: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LucideIconView(
                icon = LucideIcon.CalendarClock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "최근 검색",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "지우기",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .quantClickable(role = QuantPressRole.Text, onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = items, key = { it }) { item ->
                Surface(
                    modifier = Modifier.quantClickable(role = QuantPressRole.Text, onClick = { onSelect(item) }),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 1.dp
                ) {
                    Text(
                        item,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchGroupHeader(group: SearchResultGroup, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = CircleShape
        ) {
            LucideIconView(
                icon = group.icon,
                contentDescription = null,
                modifier = Modifier.padding(7.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            group.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "${count}개",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SearchStatusLine(
    query: String,
    visibleCount: Int,
    totalCount: Int,
    label: String,
    loading: Boolean
) {
    val text = when {
        loading -> "$label 동기화 중"
        query.isBlank() -> "$label 전체 ${totalCount}개"
        else -> "\"$query\" ${visibleCount}/${totalCount}개"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LucideIconView(
            icon = if (loading) LucideIcon.RefreshCw else LucideIcon.SlidersHorizontal,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun parseRecentSearches(raw: String): List<String> {
    return raw
        .split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

internal fun updatedRecentSearchRaw(current: List<String>, value: String): String {
    val clean = value.trim()
    if (clean.length < SEARCH_MIN_QUERY_LENGTH) return current.joinToString("|")
    return (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) })
        .take(8)
        .joinToString("|")
}

internal fun searchResultGroup(stock: SearchStock): SearchResultGroup {
    val ticker = stock.ticker.uppercase(Locale.US)
    val sector = stock.sector.orEmpty().uppercase(Locale.US)
    return when {
        sector.startsWith("ETF") -> SearchResultGroup.Etf
        ticker.startsWith("^") || ticker.endsWith("=F") || ticker.endsWith("=X") -> SearchResultGroup.Indicator
        sector.isNotBlank() -> SearchResultGroup.Company
        else -> SearchResultGroup.Other
    }
}

internal fun searchEmptyMessage(query: String): String {
    return if (query.isBlank()) {
        "현재 선택한 시장과 모드에 일치하는 데이터가 없습니다. 큐빗은 모든 종목을 얕게 보여주지 않고, 분석 가능한 기업만 깊게 봅니다."
    } else {
        "\"$query\"는 아직 큐빗 커버리지 밖일 수 있습니다. 데이터 품질과 추적 기준을 통과한 기업부터 먼저 보여줍니다."
    }
}

internal fun searchStatus(stock: SearchStock): String {
    return when {
        stock.inPortfolio && stock.inSmallCap -> "포트+스몰"
        stock.inPortfolio -> "포트"
        stock.inSmallCap -> "스몰"
        else -> "-"
    }
}

internal fun scoredKpis(stock: ScoredStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.valueScore?.let { "V" to num(it) },
        stock.qualityScore?.let { "Q" to num(it) },
        stock.momentumScore?.let { "M" to num(it) },
        stock.mlScore?.let { "ML" to num(it) },
        stock.investabilityScore?.let { "투자" to num(it) }
    ).ifEmpty {
        listOfNotNull(
            stock.roic?.let { "ROIC" to pct(it, signed = false) },
            stock.revGrowth?.let { "성장" to pct(it) },
            stock.grossMargin?.let { "마진" to pct(it, signed = false) }
        )
    }
}

internal fun searchDetail(stock: SearchStock): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = stock.currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "검색 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: "-"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, stock.currency)),
                    DetailMetric("상태", searchStatus(stock), if (stock.inPortfolio || stock.inSmallCap) DetailTone.Primary else DetailTone.Neutral)
                )
            )
        ),
        signals = listOfNotNull(
            if (stock.inPortfolio) DetailSignal("분석 상위군 포함", "현재 모델 분석 상위군에 포함된 종목입니다.", DetailTone.Primary) else null,
            if (stock.inSmallCap) DetailSignal("스몰캡 후보", "스몰캡 리스트에 포함된 후보입니다.", DetailTone.Positive) else null
        ).ifEmpty {
            listOf(DetailSignal("전체 유니버스 종목", "아직 분석 상위군이나 스몰캡 후보는 아니지만, 차트와 기업 정보를 확인할 수 있습니다.", DetailTone.Neutral))
        },
        factors = emptyList()
    )
}
