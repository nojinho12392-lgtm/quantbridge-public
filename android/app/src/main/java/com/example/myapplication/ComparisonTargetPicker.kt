package com.example.myapplication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

private const val COMPARISON_TARGET_SEARCH_DEBOUNCE_MS = 350L
private const val COMPARISON_TARGET_MIN_QUERY_LENGTH = 2

@Composable
fun ComparisonTargetPickerSheet(
    app: QuantAppState,
    anchor: StockComparisonItem,
    onDismiss: () -> Unit,
    onCompare: () -> Unit,
    comparisonViewModel: ComparisonViewModel,
    portfolioViewModel: PortfolioViewModel = hiltViewModel(),
    smallCapViewModel: SmallCapViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    var query by remember(anchor.id) { mutableStateOf("") }
    val selectedItems = comparisonViewModel.items.toList()
    val candidateItems = detailComparisonCandidates(
        app = app,
        anchor = anchor,
        comparisonViewModel = comparisonViewModel,
        portfolioViewModel = portfolioViewModel,
        smallCapViewModel = smallCapViewModel,
        searchViewModel = searchViewModel
    )
    val visibleCandidates = remember(candidateItems, query, selectedItems) {
        val cleanQuery = query.trim()
        candidateItems
            .filter { cleanQuery.isBlank() || matches(cleanQuery, it.ticker, it.name, it.sector) }
            .filterNot { normalizedTicker(it.ticker) == normalizedTicker(anchor.ticker) }
            .take(30)
    }

    LaunchedEffect(anchor.id) {
        comparisonViewModel.replace(listOf(anchor))
        comparisonViewModel.refreshRecommendations(anchor)
    }

    LaunchedEffect(query) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < COMPARISON_TARGET_MIN_QUERY_LENGTH) return@LaunchedEffect
        delay(COMPARISON_TARGET_SEARCH_DEBOUNCE_MS)
        searchViewModel.searchCompanies(cleanQuery)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LucideIconView(
                icon = LucideIcon.GitCompare,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("비교 대상 선택", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("기준 종목은 자동으로 포함됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) {
                LucideIconView(
                    icon = LucideIcon.X,
                    contentDescription = "닫기",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        ComparisonAnchorCard(anchor = anchor, selectedCount = selectedItems.size)

        ComparisonSelectedStrip(
            items = selectedItems,
            anchor = anchor,
            onRemove = { comparisonViewModel.remove(it) }
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("비교할 기업 검색") },
            leadingIcon = {
                LucideIconView(
                    icon = LucideIcon.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (query.isBlank()) "추천 비교 대상" else "검색 결과",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (visibleCandidates.isEmpty()) {
                    Text(
                        "현재 조건에 맞는 비교 후보가 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    visibleCandidates.forEachIndexed { index, item ->
                        ComparisonCandidateRow(
                            item = item,
                            reason = comparisonCandidateReason(item, anchor),
                            selected = comparisonViewModel.contains(item),
                            canAdd = selectedItems.size < 4,
                            onToggle = {
                                if (comparisonViewModel.contains(item)) {
                                    comparisonViewModel.remove(item)
                                } else {
                                    comparisonViewModel.add(item)
                                }
                            }
                        )
                        if (index != visibleCandidates.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        }
                    }
                }
            }
        }

        ComparisonImmediateActionRow(
            selectedCount = selectedItems.size,
            onReset = { comparisonViewModel.replace(listOf(anchor)) },
            onCompare = { onCompare() }
        )
    }
}

@Composable
private fun ComparisonImmediateActionRow(
    selectedCount: Int,
    onReset: () -> Unit,
    onCompare: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (selectedCount >= 2) "선택 완료 $selectedCount/4" else "비교 대상 1개 더 선택",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(onClick = onReset) {
                Text("기준만")
            }
            Button(
                onClick = onCompare,
                enabled = selectedCount >= 2
            ) {
                Text("바로 비교하기")
            }
        }
    }
}

@Composable
private fun ComparisonAnchorCard(anchor: StockComparisonItem, selectedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TickerAvatar(anchor.ticker, anchor.market, size = 38.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(anchor.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${anchor.ticker} · ${anchor.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("선택 $selectedCount/4", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ComparisonSelectedStrip(
    items: List<StockComparisonItem>,
    anchor: StockComparisonItem,
    onRemove: (StockComparisonItem) -> Unit
) {
    if (items.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("선택된 비교군", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            items.forEach { item ->
                val isAnchor = normalizedTicker(item.ticker) == normalizedTicker(anchor.ticker)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TickerAvatar(item.ticker, item.market, size = 30.dp)
                    Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(if (isAnchor) "기준" else item.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!isAnchor) {
                        IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(36.dp)) {
                            LucideIconView(
                                icon = LucideIcon.X,
                                contentDescription = "선택 해제",
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonCandidateRow(
    item: StockComparisonItem,
    reason: String,
    selected: Boolean,
    canAdd: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = selected || canAdd, onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TickerAvatar(item.ticker, item.market, size = 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.ticker, item.sector, item.source).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.scoreText.takeIf { it != "-" } ?: cap(item.marketCap, item.currency), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(pct(item.revenueGrowth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LucideIconView(
            icon = if (selected) LucideIcon.Check else LucideIcon.Plus,
            contentDescription = if (selected) "선택됨" else "선택",
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canAdd) 0.72f else 0.30f),
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun detailComparisonCandidates(
    app: QuantAppState,
    anchor: StockComparisonItem,
    comparisonViewModel: ComparisonViewModel,
    portfolioViewModel: PortfolioViewModel,
    smallCapViewModel: SmallCapViewModel,
    searchViewModel: SearchViewModel
): List<StockComparisonItem> {
    val candidates =
        comparisonViewModel.recommendationsFor(anchor) +
            (portfolioViewModel.usPortfolio + portfolioViewModel.krPortfolio).map { it.toComparisonItem() } +
            (searchViewModel.usScored + searchViewModel.krScored).map { it.toComparisonItem() } +
            (smallCapViewModel.usSmallCap + smallCapViewModel.krSmallCap).map { it.toComparisonItem() } +
            app.watchlist.filterNot { it.isMarketIndicatorWatchItem() }.map { it.toComparisonItem() } +
            searchViewModel.searchResults.map { it.toComparisonItem() }

    return candidates
        .filterNot { normalizedTicker(it.ticker) == normalizedTicker(anchor.ticker) }
        .distinctBy { normalizedTicker(it.ticker) }
        .sortedWith(
            compareByDescending<StockComparisonItem> { comparisonCandidatePriority(it, anchor) }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )
}

private fun comparisonCandidatePriority(item: StockComparisonItem, anchor: StockComparisonItem): Int {
    var score = 0
    if (!item.market.isNullOrBlank() && item.market.equals(anchor.market, ignoreCase = true)) score += 4
    if (!item.sector.isNullOrBlank() && item.sector.equals(anchor.sector, ignoreCase = true)) score += 7
    if (similarMarketCap(item.marketCap, anchor.marketCap)) score += 3
    if (item.source == "Portfolio") score += 4
    if (item.source == "Watch") score += 3
    if (item.source == "SmallCap" || item.source == "스몰캡") score += 2
    item.scoreValue?.takeIf { it.isFinite() }?.let { score += minOf(3, maxOf(1, it.toInt())) }
    if (item.revenueGrowth != null && anchor.revenueGrowth != null) score += 2
    if (item.roic != null && anchor.roic != null) score += 1
    return score
}

private fun comparisonCandidateReason(item: StockComparisonItem, anchor: StockComparisonItem): String {
    val reasons = buildList {
        if (!item.sector.isNullOrBlank() && item.sector.equals(anchor.sector, ignoreCase = true)) add("같은 섹터")
        if (!item.market.isNullOrBlank() && item.market.equals(anchor.market, ignoreCase = true)) add("같은 시장")
        if (similarMarketCap(item.marketCap, anchor.marketCap)) add("비슷한 규모")
        when (item.source) {
            "Portfolio" -> add("핵심 후보")
            "SmallCap", "스몰캡" -> add("스몰캡 대조")
            "Watch" -> add("관심종목")
        }
        val growth = item.revenueGrowth
        if (growth?.isFinite() == true && growth > 0.12) {
            add("성장성")
        } else if (item.revenueGrowth != null && anchor.revenueGrowth != null) {
            add("성장 비교")
        }
        val roic = item.roic
        if (roic?.isFinite() == true && roic > 0.12) add("퀄리티")
    }
    return reasons.take(3).joinToString(" · ").ifBlank { "${item.source} 비교" }
}

private fun similarMarketCap(lhs: Double?, rhs: Double?): Boolean {
    if (lhs == null || rhs == null || !lhs.isFinite() || !rhs.isFinite() || lhs <= 0.0 || rhs <= 0.0) return false
    return abs(ln(lhs) - ln(rhs)) < 1.2
}
