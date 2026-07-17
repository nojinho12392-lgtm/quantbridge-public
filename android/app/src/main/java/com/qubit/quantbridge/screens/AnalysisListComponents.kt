package com.qubit.quantbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantMuted
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PulseRegimeCard(macro: Map<String, String>, market: Market) {
    val regime = macro["Regime"] ?: "-"
    val score = macro["Regime_Score"] ?: "-"
    val prefix = market.title
    val color = regimeColor(regime)
    val primaryHint = regimeActionHints(regime).firstOrNull()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "오늘 시장 분위기",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        regimeDecisionTitle(regime),
                        style = MaterialTheme.typography.headlineSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        regimeDescription(regime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
                ) {
                    Text(
                        "판단 강도 $score",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            primaryHint?.let { hint ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = color.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, color.copy(alpha = 0.12f))
                    ) {
                        Text(
                            hint,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    macro["Generated"]?.take(16)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "업데이트 $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactMetricTile("저평가", macro["${prefix}_V_Weight"] ?: "-", Modifier.weight(1f), color = color)
                CompactMetricTile("추세", macro["${prefix}_M_Weight"] ?: "-", Modifier.weight(1f), color = color)
            }
        }
    }
}

@Composable
internal fun MacroSignalTile(reason: RegimeReason, modifier: Modifier = Modifier) {
    val color = toneColor(signalTone(reason.signal))
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    reason.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    macroSignalBadgeText(reason.signal),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Text(
                macroSignedPercentText(reason.title, reason.value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun macroSignedPercentText(title: String, value: String): AnnotatedString {
    val coloredTitles = setOf("장기 추세", "최근 흐름", "금리 환경", "신용 시장", "신용시장")
    val match = firstSignedPercentMatch(value)
    if (title !in coloredTitles || match == null) {
        return AnnotatedString(value)
    }
    val color = when {
        match.value > 0.0 -> QuantPositive
        match.value < 0.0 -> QuantNegative
        else -> MaterialTheme.colorScheme.onSurface
    }
    return buildAnnotatedString {
        append(value)
        addStyle(SpanStyle(color = color), match.start, match.endExclusive)
    }
}

internal data class SignedPercentMatch(
    val start: Int,
    val endExclusive: Int,
    val value: Double
)

internal fun firstSignedPercentMatch(value: String): SignedPercentMatch? {
    val match = Regex("""[-+]?\d+(?:\.\d+)?\s*%""").find(value) ?: return null
    val percent = match.value.replace("%", "").replace(" ", "").toDoubleOrNull() ?: return null
    return SignedPercentMatch(match.range.first, match.range.last + 1, percent)
}

@Composable
internal fun HeaderCard(
    title: String,
    value: String,
    subtitle: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
    quiet: Boolean = false
) {
    val shape = RoundedCornerShape(24.dp)
    val cardModifier = if (onClick == null) {
        Modifier.fillMaxWidth()
    } else {
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .quantClickable(role = QuantPressRole.Card, onClick = onClick)
    }
    Surface(
        modifier = cardModifier,
        color = if (quiet) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = shape,
        border = BorderStroke(1.dp, if (quiet) MaterialTheme.colorScheme.outline.copy(alpha = 0.28f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = regimeColor(value))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticHeaderCard(
    title: String,
    value: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val trailingTone = statusTone(trailing)
    val trailingColor = if (trailingTone == DetailTone.Neutral) labelColor else toneColor(trailingTone)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .quantClickable(role = QuantPressRole.Card, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = labelColor)
                Text(
                    value.uppercase(Locale.US),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = toneColor(statusTone(value))
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = trailingColor)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = bodyColor.copy(alpha = 0.55f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticInfoDialog(
    info: DiagnosticInfo,
    onDismiss: () -> Unit
) {
    val accent = diagnosticAccent(info)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(accent.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = diagnosticIcon(info),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = accent
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(info.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        color = accent.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            info.status,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                ) {
                    Text(
                        info.summary,
                        modifier = Modifier.padding(15.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    info.details.forEachIndexed { index, detail ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 62.dp)
                                    .padding(13.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(accent.copy(alpha = 0.10f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (index + 1).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    detail,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = accent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LucideIconView(
                            icon = LucideIcon.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = accent
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "판단 포인트",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                diagnosticActionHint(info),
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

@Composable
internal fun diagnosticAccent(info: DiagnosticInfo): Color {
    val glossaryTone = glossaryTone(info.status)
    return toneColor(glossaryTone ?: statusTone(info.status))
}

internal fun diagnosticIcon(info: DiagnosticInfo): LucideIcon {
    return when (info.status) {
        "밸류에이션", "모델 전망", "스코어링", "리서치 검증" -> LucideIcon.Target
        "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀" -> LucideIcon.TrendingUp
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> LucideIcon.TriangleAlert
        "수급", "분석", "포트폴리오", "기업 규모" -> LucideIcon.LineChart
        else -> LucideIcon.Lightbulb
    }
}

internal fun diagnosticActionHint(info: DiagnosticInfo): String {
    return when (info.status) {
        "밸류에이션" -> "같은 업종 평균, 성장률, 마진을 함께 보세요. 숫자가 낮아도 이익이 꺾이면 싸다고 보기 어렵습니다."
        "수익성", "퀄리티" -> "높은 값이 유지되는지, 부채나 일회성 이익으로 만들어진 값은 아닌지 같이 확인하세요."
        "성장성" -> "성장률만 보지 말고 마진과 현금흐름이 같이 좋아지는지 확인하면 판단이 더 안전합니다."
        "현금흐름" -> "회계상 이익보다 실제 남는 현금에 가깝기 때문에 배당, 자사주, 재투자 여력을 볼 때 유용합니다."
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> "높은 값은 주가 흔들림이나 재무 부담을 키울 수 있습니다. 수익 신호가 좋아도 비중 판단에 반영하세요."
        "모델 전망", "AI 보정", "스코어링", "리서치 검증" -> "단독 매수 신호가 아니라 종목 간 우선순위를 정하는 보조 신호로 읽는 것이 좋습니다."
        "수급", "실적 모멘텀" -> "가격 반응과 거래량이 같은 방향인지 확인하세요. 이미 반영된 뉴스일 수도 있습니다."
        "기업 규모" -> "대형주는 안정성, 소형주는 성장성과 변동성을 같이 봐야 합니다. 같은 규모군 안에서 비교하면 더 정확합니다."
        else -> "${info.title}은 단독으로 결론을 내기보다 가격, 성장, 리스크 지표와 함께 비교해 보세요."
    }
}

internal fun glossaryTone(status: String): DetailTone? {
    return when (status) {
        "밸류에이션", "모델 전망", "AI 보정", "스코어링", "리서치 검증" -> DetailTone.Primary
        "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀" -> DetailTone.Positive
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> DetailTone.Warning
        "수급", "분석", "포트폴리오", "기업 규모" -> DetailTone.Primary
        else -> null
    }
}

@Composable
internal fun RegimeExplanationSheet(
    macro: Map<String, String>,
    usMeta: Map<String, String>,
    krMeta: Map<String, String>
) {
    val regime = macro["Regime"] ?: usMeta["Regime"] ?: krMeta["Regime"] ?: "NEUTRAL"
    val score = macro["Regime_Score"] ?: "-"
    val reasons = regimeReasons(macro)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("시장 흐름 판단", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    regimeTitle(regime),
                    style = MaterialTheme.typography.headlineSmall,
                    color = regimeColor(regime),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    regimeDescription(regime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("판단 방식", fontWeight = FontWeight.Bold)
                    Text(
                        "5개 시장 신호가 각각 +1, 0, -1점을 만들고 합산 점수가 +2 이상이면 위험선호, -2 이하면 위험회피, 그 사이는 중립으로 봅니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("현재 합산 점수: $score", color = regimeColor(regime), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        items(reasons) { reason ->
            RegimeReasonRow(reason)
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("모델에 반영되는 방식", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        factorWeightText(macro),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun RegimeReasonRow(reason: RegimeReason) {
    val tone = signalTone(reason.signal)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = toneColor(tone).copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, toneColor(tone).copy(alpha = 0.14f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reason.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(signalText(reason.signal), color = toneColor(tone), fontWeight = FontWeight.Bold)
            }
            Text(reason.value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(reason.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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

@Composable
internal fun PortfolioRankingSectionTitle(title: String = "기업 순위") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "1개월 수익률",
            modifier = Modifier
                .width(88.dp)
                .padding(end = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

@Composable
internal fun PortfolioRankingRow(
    rankLabel: String,
    stock: PortfolioStock,
    profile: InvestmentProfile,
    currency: String,
    currentPrice: Double?,
    return1M: Double?,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onOpen: () -> Unit
) {
    val personal = remember(profile, stock) { personalizedStockInterpretation(profile, stock) }
    PortfolioCompanyRow(
        rankLabel = rankLabel,
        rankChange = stock.rankChange,
        rankStatus = stock.rankStatus,
        ticker = stock.ticker,
        market = stock.market,
        name = stock.name,
        sectorLabel = portfolioIndustryLabel(stock.ticker, stock.name, stock.sector),
        personalLine = "${personal.label} · ${personal.headline}",
        priceText = portfolioPriceText(currentPrice, currency),
        return1M = return1M,
        source = stock.source ?: stock.lastUpdated?.let { "storage" },
        updatedAt = stock.lastUpdated ?: stock.generatedAt,
        comparisonMode = comparisonMode,
        comparisonSelected = comparisonSelected,
        comparisonDisabled = comparisonDisabled,
        onOpen = onOpen
    )
}

@Composable
internal fun RankMovementBadge(change: Int?, status: String?) {
    val normalized = status?.lowercase(Locale.US)
    val text = when {
        normalized == "new" -> "신규"
        change == null -> null
        change > 0 -> "▲$change"
        change < 0 -> "▼${abs(change)}"
        else -> null
    } ?: return
    val color = when {
        normalized == "new" -> MaterialTheme.colorScheme.primary
        change != null && change > 0 -> QuantPositive
        change != null && change < 0 -> QuantNegative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

@Composable
internal fun SmallCapRankingRow(
    rankLabel: String,
    stock: SmallCapStock,
    profile: InvestmentProfile,
    currentPrice: Double?,
    return1M: Double?,
    comparisonMode: Boolean = false,
    comparisonSelected: Boolean = false,
    comparisonDisabled: Boolean = false,
    onOpen: () -> Unit
) {
    val currency = marketCurrency(stock.ticker, stock.market)
    val personalLine = remember(profile, stock) { smallCapPersonalLine(stock, profile) }
    PortfolioCompanyRow(
        rankLabel = rankLabel,
        rankChange = stock.rankChange,
        rankStatus = stock.rankStatus,
        ticker = stock.ticker,
        market = stock.market,
        name = stock.name,
        sectorLabel = stock.market ?: "스몰캡",
        personalLine = personalLine,
        priceText = portfolioPriceText(currentPrice, currency),
        return1M = return1M,
        source = stock.source ?: stock.lastUpdated?.let { "storage" },
        updatedAt = stock.lastUpdated ?: stock.generatedAt,
        comparisonMode = comparisonMode,
        comparisonSelected = comparisonSelected,
        comparisonDisabled = comparisonDisabled,
        onOpen = onOpen
    )
}

internal fun smallCapPersonalLine(stock: SmallCapStock, profile: InvestmentProfile): String {
    if (!profile.isConfigured) return "기준 설정 필요 · 스몰캡은 점수와 거래량을 함께 비교"
    val headline = profile.headline.ifBlank { "내 기준" }
    val revGrowth = listPercentMagnitude(stock.revGrowth)
    val return1M = listPercentMagnitude(stock.return1M)
    val volumeSurge = stock.volumeSurge ?: 1.0
    return when {
        (profile.riskTolerance.contains("안정") || profile.riskTolerance.contains("보수") || profile.riskTolerance.contains("낮")) &&
            (volumeSurge >= 2.0 || return1M >= 12.0) -> "$headline 기준 · 변동성 먼저 제한"
        profile.style.contains("성장") && revGrowth >= 15.0 -> "$headline 기준 · 성장 근거 확인"
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) >= 0.12 || (stock.fcfMargin ?: 0.0) >= 0.06) -> "$headline 기준 · 퀄리티 근거 확인"
        profile.style.contains("모멘텀") && volumeSurge >= 1.8 -> "$headline 기준 · 과열 여부 확인"
        else -> "$headline 기준 · 비교 후 관찰"
    }
}

internal fun listPercentMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}

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

internal enum class DetailContentTab(val label: String, val key: String) {
    Overview("요약", "overview"),
    Chart("차트", "chart"),
    Holdings("구성종목", "holdings"),
    Financial("재무", "financial"),
    Data("데이터", "data")
}

internal fun preferredDetailTab(key: String): DetailContentTab {
    return DetailContentTab.entries.firstOrNull { it.key == key.lowercase() } ?: DetailContentTab.Overview
}

@Composable
internal fun DetailTabSelector(
    selected: DetailContentTab,
    tabs: List<DetailContentTab> = DetailContentTab.entries.toList(),
    onSelect: (DetailContentTab) -> Unit
) {
    SoftSegmentSwitch(
        options = tabs.map { it.label },
        selected = selected.label,
        onSelect = { label ->
            tabs.firstOrNull { it.label == label }?.let(onSelect)
        }
    )
}
