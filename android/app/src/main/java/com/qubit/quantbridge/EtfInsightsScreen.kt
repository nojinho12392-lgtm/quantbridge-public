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

internal const val ETF_PRICE_AUTO_REFRESH_MS = 300_000L

@Composable
fun HomeEtfInsightCard(etf: EtfInsight, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .width(258.dp)
            .height(162.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                EtfAvatar(etf.ticker, etf.name, etf.region, etf.category, etf.theme, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(etf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${etf.region} · ${etf.theme}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(etf.displaySummary, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtfMetricPill("보수", etf.expenseRatio, Modifier.weight(1f))
                EtfMetricPill("Top", etf.topHoldingText, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtfInsightsScreen(
    app: QuantAppState,
    viewModel: EtfInsightsViewModel = hiltViewModel()
) {
    var region by remember { mutableStateOf("전체") }
    var category by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(EtfSortOption.Alphabet) }
    var showEtfGuide by remember { mutableStateOf(false) }
    val items = viewModel.items

    if (showEtfGuide) {
        BackHandler { showEtfGuide = false }
        EtfEducationScreen(onBack = { showEtfGuide = false })
        return
    }

    LaunchedEffect(Unit) {
        viewModel.refreshEtfs()
    }
    LaunchedEffect("etf-price-auto") {
        while (true) {
            delay(ETF_PRICE_AUTO_REFRESH_MS)
            viewModel.refreshEtfs(force = true, automatic = true)
        }
    }

    LaunchedEffect(query) {
        val clean = query.trim()
        if (clean.isBlank()) {
            viewModel.clearSearch()
            return@LaunchedEffect
        }
        region = "전체"
        category = "전체"
        delay(260)
        viewModel.searchEtfs(clean)
    }

    val searchSourceItems = remember(items, viewModel.searchItems, query) {
        if (query.trim().isBlank()) {
            items
        } else {
            mergeEtfSearchItems(items, viewModel.searchItems)
        }
    }
    val regions = remember(searchSourceItems) { listOf("전체") + searchSourceItems.map { it.region }.distinct().sorted() }
    val categories = remember(searchSourceItems) { listOf("전체") + searchSourceItems.map { it.category }.distinct().sorted() }
    val filtered = remember(region, category, query, searchSourceItems, sort) {
        val clean = query.trim().lowercase()
        val matches = searchSourceItems.filter { etf ->
            (region == "전체" || etf.region == region) &&
                (category == "전체" || etf.category == category) &&
                (clean.isEmpty() ||
                    etf.ticker.lowercase().contains(clean) ||
                    etf.name.lowercase().contains(clean) ||
                    etf.theme.lowercase().contains(clean))
        }
        sortEtfs(matches, sort)
    }

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
            EtfHeaderCard(
                filtered.size,
                viewModel.loading || viewModel.searchLoading,
                if (query.trim().isBlank()) viewModel.source else "검색 확장",
                viewModel.updatedAt,
                viewModel.searchError ?: viewModel.error,
                onOpenGuide = { showEtfGuide = true }
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                placeholder = { Text("ETF 티커, 이름, 테마 검색") },
                leadingIcon = {
                    LucideIconView(
                        icon = LucideIcon.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        item { EtfFilterRow("시장", regions, region) { region = it } }
        item { EtfFilterRow("유형", categories, category) { category = it } }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LucideIconView(
                        icon = LucideIcon.PieChart,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("ETF 목록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                EtfSortMenu(sort = sort, onSort = { sort = it })
            }
        }
        if (filtered.isEmpty()) {
            item {
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LucideIconView(
                            icon = LucideIcon.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("ETF 검색 결과 없음", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (viewModel.searchLoading) "검색 범위를 넓혀 확인하고 있습니다." else "다른 티커나 테마로 검색해보세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(filtered, key = { it.ticker }) { etf ->
                EtfInsightRow(etf = etf, onOpen = { app.selectedDetail = etf.detailRequest() })
            }
        }
    }
}

internal fun mergeEtfSearchItems(local: List<EtfInsight>, remote: List<EtfInsight>): List<EtfInsight> {
    val merged = LinkedHashMap<String, EtfInsight>()
    local.forEach { merged[it.ticker.uppercase()] = it }
    remote.forEach { merged[it.ticker.uppercase()] = it }
    return merged.values.toList()
}

internal fun sortEtfs(items: List<EtfInsight>, sort: EtfSortOption): List<EtfInsight> {
    return when (sort) {
        EtfSortOption.Alphabet -> items.sortedWith(
            compareBy<EtfInsight> { it.name.lowercase() }.thenBy { it.ticker.uppercase() }
        )
        EtfSortOption.Return1M -> items.sortedWith(
            compareByDescending<EtfInsight> { it.return1M ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.name.lowercase() }
                .thenBy { it.ticker.uppercase() }
        )
        EtfSortOption.Ticker -> items.sortedBy { it.ticker.uppercase() }
    }
}

@Composable
internal fun EtfSortMenu(sort: EtfSortOption, onSort: (EtfSortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .clip(CircleShape)
                .quantClickable(role = QuantPressRole.Icon) { expanded = true },
            shape = CircleShape,
            color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.SlidersHorizontal,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    sort.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(206.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "정렬 기준",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
                EtfSortOption.entries.forEach { option ->
                    EtfSortMenuRow(
                        option = option,
                        selected = option == sort,
                        onClick = {
                            onSort(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun EtfSortMenuRow(option: EtfSortOption, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LucideIconView(
                icon = when (option) {
                    EtfSortOption.Alphabet -> LucideIcon.ListOrdered
                    EtfSortOption.Return1M -> LucideIcon.TrendingUp
                    EtfSortOption.Ticker -> LucideIcon.Target
                },
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tint
            )
            Text(
                option.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (selected) {
                LucideIconView(
                    icon = LucideIcon.Check,
                    contentDescription = "선택됨",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun EtfHeaderCard(
    count: Int,
    loading: Boolean,
    source: String,
    updatedAt: String?,
    error: String?,
    onOpenGuide: () -> Unit
) {
    CardBlock(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .quantClickable(role = QuantPressRole.Card, onClick = onOpenGuide)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.PieChart,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("ETF 인사이트", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = CircleShape
            ) {
                LucideIconView(
                    icon = LucideIcon.Lightbulb,
                    contentDescription = "ETF 설명 열기",
                    modifier = Modifier.padding(7.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text("대표 ETF의 구성종목, 섹터 노출, 전망과 리스크를 한 곳에서 봅니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "눌러서 ETF가 무엇인지 자세히 보기",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        val sourceText = when (source) {
            "api" -> "Azure API"
            "검색 확장" -> "검색 확장"
            "fallback" -> "대표 노출"
            else -> dataSourceLabel(source)
        }
        Text(
            "${count}개 ETF · $sourceText 기준${if (loading) " · 갱신 중" else ""}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        DataFreshnessBadge(
            source = if (source == "api") "storage" else source,
            updatedAt = updatedAt,
            compact = true
        )
        if (!error.isNullOrBlank() && source != "api") {
            Text(error, color = QuantWarning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun EtfFilterRow(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values, key = { it }) { value ->
                Surface(
                    modifier = Modifier.clickable { onSelect(value) },
                    color = if (selected == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) {
                    Text(
                        value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (selected == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
internal fun EtfInsightRow(etf: EtfInsight, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EtfAvatar(etf.ticker, etf.name, etf.region, etf.category, etf.theme, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(etf.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(etf.ticker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(etf.displaySummary, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    EtfInlineMetric(LucideIcon.Target, "유형", etf.category)
                    EtfInlineMetric(LucideIcon.SlidersHorizontal, "보수", etf.expenseRatio)
                }
            }
            EtfPriceSummary(etf)
        }
    }
}

@Composable
internal fun EtfInlineMetric(icon: LucideIcon, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "$label $value",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun EtfPriceSummary(etf: EtfInsight) {
    val currency = if (etf.region == "KR") "KRW" else "USD"
    val move = etf.displayPriceChange
    val changePct = etf.displayChangePct
    val moveColor = if ((changePct ?: move ?: 0.0) >= 0.0) QuantPositive else QuantNegative
    Column(
        modifier = Modifier.width(98.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        AnimatedPriceText(
            text = etf.currentPrice?.let { etfPriceText(it, currency) } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        if (move != null && changePct != null) {
            Text(
                "${etfSignedPriceText(move, currency)} (${pct(changePct)})",
                color = moveColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
