package com.qubit.quantbridge

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
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
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
