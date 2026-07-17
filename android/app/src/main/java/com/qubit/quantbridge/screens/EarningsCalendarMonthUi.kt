package com.qubit.quantbridge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Composable
internal fun EarningsCalendarMonthCard(
    items: List<EarningsCalendarItem>,
    onOpen: (EarningsCalendarItem) -> Unit
) {
    val grouped = remember(items) {
        items.mapNotNull { item ->
            parseEarningsCalendarDate(item.nextEarningsDate)?.let { date -> date to item }
        }
            .groupBy({ it.first }, { it.second })
            .mapValues { entry ->
                entry.value.sortedWith(
                    compareByDescending<EarningsCalendarItem> { it.marketCap ?: Double.NEGATIVE_INFINITY }
                        .thenBy { it.name }
                        .thenBy { it.ticker }
                )
            }
    }
    val eventDates = remember(grouped) { grouped.keys.sorted() }
    var visibleMonth by remember {
        mutableStateOf((eventDates.firstOrNull() ?: LocalDate.now()).withDayOfMonth(1))
    }
    var selectedDate by remember { mutableStateOf(eventDates.firstOrNull()) }

    LaunchedEffect(eventDates) {
        if (eventDates.isEmpty()) {
            selectedDate = null
            visibleMonth = LocalDate.now().withDayOfMonth(1)
        } else if (selectedDate == null || !eventDates.contains(selectedDate)) {
            selectedDate = eventDates.first()
            visibleMonth = eventDates.first().withDayOfMonth(1)
        }
    }

    val selectedItems = selectedDate?.let { grouped[it] }.orEmpty()
    val cells = remember(visibleMonth) { earningsCalendarMonthCells(visibleMonth) }

    CardBlock(useBorder = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatEarningsCalendarMonth(visibleMonth),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { visibleMonth = visibleMonth.minusMonths(1).withDayOfMonth(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 달", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { visibleMonth = visibleMonth.plusMonths(1).withDayOfMonth(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 달", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                Text(
                    day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { date ->
                        EarningsCalendarDateCell(
                            date = date,
                            hasEvent = date != null && grouped.containsKey(date),
                            selected = date != null && date == selectedDate,
                            onSelect = { selectedDate = it }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selectedDate?.let { formatEarningsCalendarDate(it.toString()) } ?: "날짜 선택",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${selectedItems.size}개 기업",
                style = MaterialTheme.typography.labelMedium,
                color = if (selectedItems.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else QuantPositive,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (selectedItems.isEmpty()) {
            Text(
                "선택한 날짜에 예정된 실적 발표가 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                selectedItems.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                    EarningsCalendarRow(item = item, onOpen = { onOpen(item) })
                }
            }
        }
    }
}

@Composable
internal fun RowScope.EarningsCalendarDateCell(
    date: LocalDate?,
    hasEvent: Boolean,
    selected: Boolean,
    onSelect: (LocalDate) -> Unit
) {
    if (date == null) {
        Spacer(Modifier.weight(1f).height(36.dp))
        return
    }

    Surface(
        modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect(date) },
        shape = RoundedCornerShape(12.dp),
        color = when {
            selected -> MaterialTheme.colorScheme.surface
            hasEvent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            else -> Color.Transparent
        },
        border = if (selected) BorderStroke(0.6.dp, QuantLine) else null
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (hasEvent || selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .size(if (hasEvent) 5.dp else 5.dp)
                    .background(if (hasEvent) QuantPositive else Color.Transparent, CircleShape)
            )
        }
    }
}

@Composable
internal fun EarningsCalendarRow(
    item: EarningsCalendarItem,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TickerAvatar(item.ticker, item.market, size = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                localizedCompanyName(item.ticker, item.name, item.market),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(
                    item.ticker,
                    portfolioIndustryLabel(item.ticker, item.name, item.sector)
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(daysUntilText(item.daysUntil), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(earningsCalendarValueText(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
