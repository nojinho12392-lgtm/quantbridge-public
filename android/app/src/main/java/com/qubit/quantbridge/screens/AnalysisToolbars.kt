package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.min

internal fun matchesPortfolioIndustryQuery(query: String, ticker: String, name: String, sector: String?): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return true
    if (matches(cleanQuery, ticker, name, sector)) return true
    return portfolioIndustryLabel(ticker, name, sector)
        .lowercase(Locale.getDefault())
        .contains(cleanQuery.lowercase(Locale.getDefault()))
}

@Composable
internal fun PortfolioSearchSortToolbar(
    query: String,
    onQuery: (String) -> Unit,
    sort: String,
    sortOptions: List<String>,
    onSort: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BorderlessSearchField(
            query = query,
            onQuery = onQuery,
            placeholder = "티커, 종목명, 섹터 검색",
            modifier = Modifier.weight(1f)
        )
        Box {
            Surface(
                shape = CircleShape,
                color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .quantClickable(role = QuantPressRole.Icon) { expanded = true },
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = LucideIcon.SlidersHorizontal,
                        contentDescription = "정렬",
                        modifier = Modifier.size(19.dp),
                        tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(210.dp),
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
                    sortOptions.forEach { option ->
                        SortOptionMenuRow(
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
}

@Composable
internal fun MarketToolbar(
    market: Market,
    onMarket: (Market) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    sort: String,
    sortOptions: List<String>,
    onSort: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnalysisFilterChipRow(
            title = "시장",
            values = Market.entries.map { marketDisplayLabel(it) },
            selected = marketDisplayLabel(market),
            onSelect = { selectedTitle ->
                Market.entries.firstOrNull { marketDisplayLabel(it) == selectedTitle }?.let(onMarket)
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BorderlessSearchField(
                query = query,
                onQuery = onQuery,
                placeholder = "티커, 종목명, 섹터 검색",
                modifier = Modifier.weight(1f)
            )
            Box {
                Surface(
                    shape = CircleShape,
                    color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .quantClickable(role = QuantPressRole.Icon) { expanded = true },
                        contentAlignment = Alignment.Center
                    ) {
                        LucideIconView(
                            icon = LucideIcon.SlidersHorizontal,
                            contentDescription = "정렬",
                            modifier = Modifier.size(19.dp),
                            tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(210.dp),
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
                        sortOptions.forEach { option ->
                            SortOptionMenuRow(
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
    }
}

@Composable
fun AnalysisFilterChipRow(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values, key = { it }) { value ->
                Surface(
                    modifier = Modifier
                        .clip(CircleShape)
                        .quantClickable(role = QuantPressRole.Text) { onSelect(value) },
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
internal fun SortOptionMenuRow(option: String, selected: Boolean, onClick: () -> Unit) {
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
                icon = sortOptionIcon(option),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tint
            )
            Text(
                option,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
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

internal fun sortOptionIcon(option: String): LucideIcon {
    return when {
        "수익" in option || "상승" in option || "변동" in option -> LucideIcon.TrendingUp
        "알파벳" in option || "이름" in option || "순" in option -> LucideIcon.ListOrdered
        "가격" in option || "시총" in option || "규모" in option -> LucideIcon.LineChart
        "점수" in option || "랭킹" in option || "순위" in option -> LucideIcon.Target
        "날짜" in option || "최근" in option -> LucideIcon.CalendarClock
        else -> LucideIcon.SlidersHorizontal
    }
}

internal fun marketDisplayLabel(market: Market): String {
    return newsMarketLabel(market.title).ifBlank { market.title }
}

internal fun shortPortfolioDateLabel(value: String): String {
    val clean = value.trim()
    val date = Regex("""(\d{4})[-/.](\d{2})[-/.](\d{2})""").find(clean)
    return if (date != null) {
        "${date.groupValues[2]}/${date.groupValues[3]}"
    } else {
        clean.take(8)
    }
}
