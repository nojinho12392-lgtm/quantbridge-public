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
