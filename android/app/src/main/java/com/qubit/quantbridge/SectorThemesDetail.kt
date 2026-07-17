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

@Composable
internal fun SectorThemeDetailSheet(
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
internal fun SectorThemeDetailLoadingCard(loading: Boolean, errorMessage: String?) {
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
internal fun SectorThemeDetailHeader(theme: SectorTheme) {
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
internal fun RowScope.SectorMetricPill(title: String, value: String, modifier: Modifier = Modifier) {
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
internal fun SectorLeaderStrip(member: SectorThemeMember, label: String = "주도") {
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
internal fun SectorThemeMemberRow(member: SectorThemeMember, onOpen: () -> Unit) {
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
