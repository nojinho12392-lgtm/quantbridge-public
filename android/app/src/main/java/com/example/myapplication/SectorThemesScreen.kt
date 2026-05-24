package com.example.myapplication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantPositive
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

private const val SECTOR_PRICE_AUTO_REFRESH_MS = 300_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectorThemesScreen(
    app: QuantAppState,
    viewModel: SectorThemesViewModel = hiltViewModel()
) {
    var marketScope by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    val selectedTheme = viewModel.selectedTheme
    val apiMarket = when (marketScope) {
        "미국" -> "US"
        "국내" -> "KR"
        else -> "ALL"
    }
    val themes = if (viewModel.market == apiMarket) viewModel.themes else emptyList()
    val filteredThemes = remember(themes, query) {
        val clean = query.trim()
        if (clean.isBlank()) themes else themes.filter { sectorThemeMatches(it, clean) }
    }
    val topThemes = remember(filteredThemes) { filteredThemes.take(3) }
    val remainingThemes = remember(filteredThemes) { filteredThemes.drop(3) }

    LaunchedEffect(apiMarket) {
        viewModel.refreshSectorThemes(apiMarket)
    }
    LaunchedEffect(filteredThemes) {
        if (filteredThemes.isNotEmpty()) {
            viewModel.prefetchThemeDetails(filteredThemes.take(8))
        }
    }
    LaunchedEffect("sector-price-auto", apiMarket) {
        while (true) {
            delay(SECTOR_PRICE_AUTO_REFRESH_MS)
            viewModel.refreshSectorThemes(apiMarket, reloadExisting = true, automatic = true)
        }
    }
    fun openTheme(theme: SectorTheme) {
        viewModel.openTheme(theme, force = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 0.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectorThemesHeader(
                count = filteredThemes.size,
                summary = sectorThemeSummary(if (topThemes.isEmpty()) themes else topThemes),
                source = viewModel.source,
                updatedAt = viewModel.generatedAt
            )
        }
        item {
            AnalysisFilterChipRow(
                title = "시장",
                values = listOf("전체", "미국", "국내"),
                selected = marketScope,
                onSelect = { marketScope = it }
            )
        }
        item {
            BorderlessSearchField(
                query = query,
                onQuery = { query = it },
                placeholder = "테마, 기업명, 티커 검색",
                modifier = Modifier.fillMaxWidth()
            )
        }
        viewModel.error?.let { message ->
            item {
                EmptyCard(
                    title = "섹터 데이터를 다시 확인해야 합니다",
                    message = message,
                    lucideIcon = LucideIcon.RefreshCw,
                    actionLabel = "새로고침",
                    onAction = { viewModel.refreshSectorThemes(apiMarket, force = true) }
                )
            }
        }
        if (viewModel.loading && themes.isEmpty()) {
            item {
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("섹터 흐름 로딩 중", fontWeight = FontWeight.Bold)
                            Text("테마별 기업과 당일 변동률을 계산하고 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (themes.isEmpty()) {
            item {
                EmptyCard(
                    title = "섹터 데이터 없음",
                    message = "분석 가능한 기업 묶음이 아직 없습니다.",
                    lucideIcon = LucideIcon.LayoutDashboard,
                    actionLabel = "새로고침",
                    onAction = { viewModel.refreshSectorThemes(apiMarket, force = true) }
                )
            }
        } else if (filteredThemes.isEmpty()) {
            item {
                EmptyCard(
                    title = "검색 결과 없음",
                    message = "\"${query.trim()}\"와 일치하는 테마나 기업이 없습니다.",
                    lucideIcon = LucideIcon.Search
                )
            }
        } else {
            item {
                SectorSectionTitle(title = "오늘 움직인 테마 Top 3", trailing = "${topThemes.size}개")
            }
            items(
                topThemes.chunked(2),
                key = { row -> row.joinToString("|") { "${it.market}-${it.label}-top" } }
            ) { row ->
                SectorThemeGridRow(
                    themes = row,
                    onOpen = { openTheme(it) }
                )
            }
            if (remainingThemes.isNotEmpty()) {
                item {
                    SectorSectionTitle(title = "전체 테마", trailing = "${remainingThemes.size}개")
                }
                items(
                    remainingThemes.chunked(2),
                    key = { row -> row.joinToString("|") { "${it.market}-${it.label}-row" } }
                ) { row ->
                    SectorThemeGridRow(
                        themes = row,
                        onOpen = { openTheme(it) }
                    )
                }
            }
        }
    }

    selectedTheme?.let { theme ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissSelectedTheme() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = app.selectedDetail == null
            )
        ) {
            SectorThemeDetailSheet(
                theme = theme,
                loading = viewModel.selectedThemeLoading,
                errorMessage = viewModel.selectedThemeError,
                onOpen = { member ->
                    app.selectedDetail = sectorMemberDetail(member)
                }
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SectorThemesHeader(count: Int, summary: String, source: String?, updatedAt: String?) {
    QuantCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LucideIconView(
                        icon = LucideIcon.Building2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("오늘의 섹터 흐름", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    summary,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp
                )
                Text(
                    "상세에서는 구성 기업을 시가총액 순으로 보고, 주도·압박 기업은 당일 변동률로 따로 확인합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp
                )
                DataFreshnessBadge(source = source, updatedAt = updatedAt, compact = true)
            }
            Text(
                "${count}개",
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SectorSectionTitle(title: String, trailing: String) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SectorThemeGridRow(
    themes: List<SectorTheme>,
    onOpen: (SectorTheme) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        themes.forEach { theme ->
            SectorThemeGridCard(
                theme = theme,
                modifier = Modifier.weight(1f),
                onOpen = { onOpen(theme) }
            )
        }
        if (themes.size == 1) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectorThemeGridCard(
    theme: SectorTheme,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val tone = sectorMoveColor(theme.avgChangePct)
    val displayLabel = sectorThemeDisplayLabel(theme.label)
    QuantCard(
        modifier = modifier
            .heightIn(min = 104.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = onOpen),
        padding = 12.dp
    ) {
        Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = tone.copy(alpha = 0.11f)) {
            Box(contentAlignment = Alignment.Center) {
                LucideIconView(
                    icon = sectorThemeIcon(theme.label),
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = tone
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            displayLabel,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            pct(theme.avgChangePct),
            color = tone,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectorTinyStat(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
private fun SectorThemeTopCard(theme: SectorTheme, onOpen: () -> Unit) {
    val tone = sectorMoveColor(theme.avgChangePct)
    val displayLabel = sectorThemeDisplayLabel(theme.label)
    QuantCard(
        modifier = Modifier.quantClickable(role = QuantPressRole.Card, onClick = onOpen),
        padding = 14.dp
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = tone.copy(alpha = 0.11f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LucideIconView(
                        icon = sectorThemeIcon(theme.label),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = tone
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    sectorThemeDirectionLabel(theme),
                    color = tone,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    pct(theme.avgChangePct),
                    color = tone,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("시총가중", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectorMetricPill("상승", "${theme.risingCount}", Modifier.weight(1f))
            SectorMetricPill("하락", "${theme.fallingCount}", Modifier.weight(1f))
            SectorMetricPill("1개월", pct(theme.avgReturn1M), Modifier.weight(1f))
        }

        theme.leader?.let { leader ->
            SectorLeaderStrip(leader, label = "주도")
        }

        SectorThemeDecisionNote(theme = theme, tone = tone)
    }
}

@Composable
private fun SectorThemeDecisionNote(theme: SectorTheme, tone: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            LucideIconView(
                icon = LucideIcon.Lightbulb,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(15.dp),
                tint = tone
            )
            Text(
                sectorThemeDecisionHeadline(theme),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = tone.copy(alpha = 0.10f)
            ) {
                Text(
                    "다음",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    color = tone,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(
                sectorThemeNextAction(theme),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SectorThemeListRow(theme: SectorTheme, onOpen: () -> Unit) {
    val tone = sectorMoveColor(theme.avgChangePct)
    val displayLabel = sectorThemeDisplayLabel(theme.label)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = tone.copy(alpha = 0.10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LucideIconView(
                        icon = sectorThemeIcon(theme.label),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = tone
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(displayLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SectorMemberLogoStack(theme.members.take(3))
                    Text(
                        "${theme.memberCount}개 · 상승 ${theme.risingCount} · 하락 ${theme.fallingCount}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    pct(theme.avgChangePct),
                    color = tone,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    sectorThemeDirectionLabel(theme),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SectorMemberLogoStack(members: List<SectorThemeMember>) {
    if (members.isEmpty()) return
    Box(
        modifier = Modifier
            .width((22 + (members.size - 1) * 15).dp)
            .height(22.dp)
    ) {
        members.forEachIndexed { index, member ->
            Box(modifier = Modifier.offset(x = (index * 15).dp)) {
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface)
                ) {
                    TickerAvatar(member.ticker, member.market, size = 22.dp)
                }
            }
        }
    }
}

@Composable
private fun SectorThemeDetailSheet(
    theme: SectorTheme,
    loading: Boolean,
    errorMessage: String?,
    onOpen: (SectorThemeMember) -> Unit
) {
    val topGainer = remember(theme) {
        theme.members
            .filter { (it.dailyChangePct ?: 0.0) > 0.0 }
            .maxByOrNull { it.dailyChangePct ?: Double.NEGATIVE_INFINITY }
    }
    val topLoser = remember(theme) {
        theme.members
            .filter { (it.dailyChangePct ?: 0.0) < 0.0 }
            .minByOrNull { it.dailyChangePct ?: Double.POSITIVE_INFINITY }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectorThemeDetailHeader(theme)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectorMetricPill("상승", "${theme.risingCount}", Modifier.weight(1f))
                SectorMetricPill("하락", "${theme.fallingCount}", Modifier.weight(1f))
                SectorMetricPill("1개월", pct(theme.avgReturn1M), Modifier.weight(1f))
            }
        }
        if (!loading && (topGainer != null || topLoser != null)) {
            item {
                SectorSectionTitle("주도 / 압박 기업", "")
            }
            topGainer?.let { member ->
                item {
                    Box(Modifier.quantClickable(role = QuantPressRole.Row, onClick = { onOpen(member) })) {
                        SectorLeaderStrip(member, label = "상승 주도")
                    }
                }
            }
            topLoser?.let { member ->
                item {
                    Box(Modifier.quantClickable(role = QuantPressRole.Row, onClick = { onOpen(member) })) {
                        SectorLeaderStrip(member, label = "하락 압박")
                    }
                }
            }
        }
        if (loading || errorMessage != null) {
            item {
                SectorThemeDetailLoadingCard(
                    loading = loading,
                    errorMessage = errorMessage
                )
            }
        }
        if (!loading && theme.members.isNotEmpty()) {
            item {
                SectorSectionTitle("구성 기업", "${theme.members.size}개")
            }
            item {
                QuantCard(padding = 12.dp) {
                    theme.members.forEachIndexed { index, member ->
                        SectorThemeMemberRow(member = member, onOpen = { onOpen(member) })
                        if (index != theme.members.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 48.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            )
                        }
                    }
                }
            }
        } else if (!loading && errorMessage == null) {
            item {
                SectorThemeDetailLoadingCard(
                    loading = loading,
                    errorMessage = errorMessage
                )
            }
        }
    }
}

@Composable
private fun SectorThemeDetailLoadingCard(loading: Boolean, errorMessage: String?) {
    QuantCard(padding = 14.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                LucideIconView(
                    icon = if (errorMessage == null) LucideIcon.Search else LucideIcon.RefreshCw,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (loading) "구성 기업 불러오는 중" else "구성 기업 준비 중",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    errorMessage ?: "테마 화면은 바로 열고, 상세 기업 목록만 이어서 표시합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectorThemeDetailHeader(theme: SectorTheme) {
    val tone = sectorMoveColor(theme.avgChangePct)
    val displayLabel = sectorThemeDisplayLabel(theme.label)
    QuantCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(modifier = Modifier.size(46.dp), shape = CircleShape, color = tone.copy(alpha = 0.11f)) {
                Box(contentAlignment = Alignment.Center) {
                    LucideIconView(
                        icon = sectorThemeIcon(theme.label),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = tone
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(displayLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(sectorThemeDirectionLabel(theme), color = tone, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(pct(theme.avgChangePct), color = tone, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("시총가중", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            sectorThemeReason(theme),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectorMemberLogoStack(theme.members.take(4))
            Text(
                "${theme.memberCount}개 기업 · 가격 확인 ${theme.pricedCount}개",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun RowScope.SectorMetricPill(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectorLeaderStrip(member: SectorThemeMember, label: String = "주도") {
    val tone = sectorMoveColor(member.dailyChangePct)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, tone.copy(alpha = 0.16f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(modifier = Modifier.size(28.dp), shape = CircleShape, color = tone.copy(alpha = 0.10f)) {
                Box(contentAlignment = Alignment.Center) {
                    LucideIconView(
                        icon = if ((member.dailyChangePct ?: 0.0) >= 0.0) LucideIcon.TrendingUp else LucideIcon.TrendingDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = tone
                    )
                }
            }
            Surface(shape = CircleShape, color = tone.copy(alpha = 0.10f)) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = tone,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(member.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pct(member.dailyChangePct), color = tone, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectorThemeMemberRow(member: SectorThemeMember, onOpen: () -> Unit) {
    val currency = sectorMemberCurrency(member)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TickerAvatar(member.ticker, member.market, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(member.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                member.ticker,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AnimatedPriceText(
                text = portfolioPriceText(member.currentPrice, currency),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                pct(member.dailyChangePct),
                color = sectorMoveColor(member.dailyChangePct),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun sectorMoveColor(value: Double?): Color {
    val clean = value ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return if (clean >= 0.0) QuantPositive else QuantNegative
}

private fun sectorThemeMatches(theme: SectorTheme, query: String): Boolean {
    val clean = query.trim().lowercase(Locale.US)
    if (clean.isBlank()) return true
    if (theme.label.lowercase(Locale.US).contains(clean)) return true
    return theme.members.any { member ->
        member.ticker.lowercase(Locale.US).contains(clean) ||
            member.name.lowercase(Locale.US).contains(clean) ||
            (member.sector ?: "").lowercase(Locale.US).contains(clean)
    }
}

private fun sectorThemeSummary(themes: List<SectorTheme>): String {
    val first = themes.firstOrNull() ?: return "오늘 움직인 투자 테마를 시장별로 확인하세요."
    val second = themes.drop(1).firstOrNull()
    val firstLabel = sectorThemeDisplayLabel(first.label)
    return if (second != null) {
        val secondLabel = sectorThemeDisplayLabel(second.label)
        "${firstLabel}와 ${secondLabel}이 오늘 테마 흐름의 중심입니다."
    } else {
        "${firstLabel}이 오늘 가장 먼저 확인할 테마입니다."
    }
}

private fun sectorThemeDirectionLabel(theme: SectorTheme): String {
    val value = theme.avgChangePct ?: return "데이터 확인 중"
    if (abs(value) < 0.003) return "혼조"
    return if (value >= 0.0) "상승 주도" else "하락 압력"
}

private fun sectorThemeDecisionHeadline(theme: SectorTheme): String {
    if (theme.avgChangePct == null) {
        return "${sectorThemeDisplayLabel(theme.label)} 가격 데이터를 확인하는 중입니다."
    }
    val leader = theme.leader?.name ?: sectorThemeDisplayLabel(theme.label)
    return "${pct(theme.avgChangePct)} · ${leader} 중심으로 움직임이 커졌습니다."
}

private fun sectorThemeNextAction(theme: SectorTheme): String {
    val spread = "상승 ${theme.risingCount} / 하락 ${theme.fallingCount}"
    return if (theme.avgReturn1M != null) {
        "$spread, 1개월 ${pct(theme.avgReturn1M)} 흐름을 함께 비교하세요."
    } else {
        "$spread 분포를 보고 변동 큰 기업부터 확인하세요."
    }
}

private fun sectorThemeReason(theme: SectorTheme): String {
    val leaderText = theme.leader?.let { "${it.name} ${pct(it.dailyChangePct)}" } ?: "주도 기업 확인 중"
    val direction = sectorThemeDirectionLabel(theme)
    return "$leaderText 이 ${sectorThemeDisplayLabel(theme.label)} 흐름을 이끌고 있습니다. $direction 인지, 상승 ${theme.risingCount}개와 하락 ${theme.fallingCount}개의 폭이 넓어지는지 확인하세요."
}

private fun sectorThemeIcon(label: String): LucideIcon {
    return when (sectorThemeDisplayLabel(label)) {
        "AI 칩/GPU" -> LucideIcon.Cpu
        "AI 서버/네트워크" -> LucideIcon.Network
        "AI 데이터센터/클라우드" -> LucideIcon.Server
        "AI 소프트웨어" -> LucideIcon.Bot
        "AI 전력/냉각" -> LucideIcon.AirVent
        "SMR" -> LucideIcon.Rocket
        "원자력" -> LucideIcon.Radio
        "HBM" -> LucideIcon.AudioWaveform
        "메모리/낸드" -> LucideIcon.HardDrive
        "파운드리" -> LucideIcon.Factory
        "반도체 설계" -> LucideIcon.CircuitBoard
        "CPU/엣지칩" -> LucideIcon.Microchip
        "반도체 소재" -> LucideIcon.Gem
        "전자/부품", "전기·전자" -> LucideIcon.Cable
        "반도체 장비" -> LucideIcon.MonitorCog
        "반도체 후공정/테스트" -> LucideIcon.Workflow
        "클라우드/SW" -> LucideIcon.Cloud
        "IT 서비스" -> LucideIcon.LineChart
        "사이버보안" -> LucideIcon.ShieldCheck
        "보안/서비스" -> LucideIcon.Eye
        "핀테크/결제" -> LucideIcon.CreditCard
        "은행" -> LucideIcon.Landmark
        "증권/자산운용" -> LucideIcon.BarChart3
        "보험" -> LucideIcon.BadgeDollarSign
        "전기차" -> LucideIcon.Zap
        "자동차" -> LucideIcon.Car
        "자동차 부품" -> LucideIcon.GitCompare
        "배터리" -> LucideIcon.Battery
        "배터리 소재" -> LucideIcon.Beaker
        "조선" -> LucideIcon.Ship
        "방산/항공" -> LucideIcon.Plane
        "기계/로봇" -> LucideIcon.Hammer
        "헬스케어" -> LucideIcon.HeartPulse
        "바이오/제약" -> LucideIcon.Pill
        "의료기기" -> LucideIcon.Stethoscope
        "헬스케어 서비스" -> LucideIcon.Hospital
        "에너지" -> LucideIcon.Fuel
        "정유/화학" -> LucideIcon.FlaskConical
        "전력/유틸리티" -> LucideIcon.Zap
        "클린에너지" -> LucideIcon.Leaf
        "소비/리테일" -> LucideIcon.ShoppingBag
        "이커머스" -> LucideIcon.ShoppingCart
        "음식료/필수소비" -> LucideIcon.Utensils
        "화장품/뷰티" -> LucideIcon.Palette
        "미디어/엔터" -> LucideIcon.Clapperboard
        "게임" -> LucideIcon.Gamepad2
        "여행/레저" -> LucideIcon.Hotel
        "통신" -> LucideIcon.RadioTower
        "리츠/부동산" -> LucideIcon.Building
        "부동산" -> LucideIcon.Building
        "건설/인프라" -> LucideIcon.Warehouse
        "소재/철강" -> LucideIcon.Pickaxe
        "소재" -> LucideIcon.Pickaxe
        "기술" -> LucideIcon.CircuitBoard
        "유틸리티" -> LucideIcon.Zap
        "금속" -> LucideIcon.Gem
        "비금속" -> LucideIcon.CircleArrowDown
        "운송/물류" -> LucideIcon.Truck
        else -> LucideIcon.LayoutDashboard
    }
}

private fun sectorThemeDisplayLabel(label: String): String {
    val clean = label.trim()
    if (clean.isBlank()) return "기타"
    if (clean.any { it in '\uAC00'..'\uD7A3' }) return clean
    return portfolioIndustryLabel(ticker = "", name = clean, sector = clean)
}

private fun sectorMemberCurrency(member: SectorThemeMember): String {
    return member.currency ?: marketCurrency(member.ticker, member.market)
}

private fun sectorDailyChangeLabel(member: SectorThemeMember): String {
    return member.dailyChangeHorizon?.trim()?.takeIf { it.isNotEmpty() } ?: "오늘"
}

private fun sectorMemberMarketLabel(market: String?): String {
    return when (market?.uppercase(Locale.US)) {
        "KR" -> "국내"
        "US" -> "미국"
        null, "" -> ""
        else -> market
    }
}

private fun sectorMemberDetail(member: SectorThemeMember): DetailRequest {
    val currency = sectorMemberCurrency(member)
    val sourceDetail = when {
        member.inPortfolio -> "분석 상위 후보에 포함된 기업입니다."
        member.inSmallCap -> "스몰캡 후보군에서 함께 추적되는 기업입니다."
        else -> "섹터 테마 내 비교군으로 포함된 기업입니다."
    }
    return DetailRequest(
        ticker = member.ticker,
        name = member.name,
        currency = currency,
        market = member.market,
        sections = listOf(
            DetailSection(
                "섹터 흐름",
                listOf(
                    DetailMetric("현재가", portfolioPriceText(member.currentPrice, currency)),
                    DetailMetric(sectorDailyChangeLabel(member), pct(member.dailyChangePct), returnTone(member.dailyChangePct)),
                    DetailMetric("1개월", pct(member.return1M), returnTone(member.return1M))
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시가총액", cap(member.marketCap, currency)),
                    DetailMetric("점수", score(member.scoreValue))
                )
            )
        ),
        signals = listOf(
            DetailSignal("섹터 내 위치", sourceDetail, DetailTone.Primary),
            DetailSignal("확인할 숫자", "상세 시세 기준 당일 흐름과 1개월 흐름을 같은 테마 기업과 비교하세요.", DetailTone.Primary)
        ),
        factors = emptyList()
    )
}
