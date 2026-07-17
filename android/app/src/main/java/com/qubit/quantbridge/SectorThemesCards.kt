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
@Suppress("FunctionNaming")
internal fun SectorThemesHeader(count: Int, summary: String, source: String?, updatedAt: String?) {
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
internal fun SectorSectionTitle(title: String, trailing: String) {
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
internal fun SectorThemeGridRow(
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
internal fun SectorThemeGridCard(
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
internal fun SectorTinyStat(label: String, value: String) {
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
internal fun SectorThemeTopCard(theme: SectorTheme, onOpen: () -> Unit) {
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
internal fun SectorThemeDecisionNote(theme: SectorTheme, tone: Color) {
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
internal fun SectorThemeListRow(theme: SectorTheme, onOpen: () -> Unit) {
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
internal fun SectorMemberLogoStack(members: List<SectorThemeMember>) {
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
