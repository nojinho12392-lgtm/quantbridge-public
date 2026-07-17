package com.qubit.quantbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun HomeTemplateWatchSection(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    signals: List<HomeWatchSignal>,
    watchItems: List<WatchlistItem>,
    notificationTitle: String,
    notificationDetail: String,
    notificationsEnabled: Boolean,
    notificationsAllowed: Boolean,
    onEnableNotifications: () -> Unit,
    onDisableNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    isLoading: Boolean,
    onMore: () -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeTemplateSectionHeader(
            title = "관심종목 브리핑",
            icon = LucideIcon.AudioWaveform,
            actionLabel = "전체 보기",
            onAction = onMore
        )
        HomeNotificationControl(
            title = notificationTitle,
            detail = notificationDetail,
            isEnabled = notificationsEnabled,
            isAllowed = notificationsAllowed,
            onEnable = onEnableNotifications,
            onDisable = onDisableNotifications,
            onOpenSettings = onOpenNotificationSettings
        )
        if (signals.isEmpty()) {
            HomeSectionFallbackCard(
                title = if (isLoading) "관심종목 브리핑 생성 중" else "관심종목 없음",
                message = if (isLoading) "관심 기업과 지수의 변화를 정리하고 있습니다." else "관심 화면에서 기업을 추가하면 이곳에 반복 확인 카드가 표시됩니다.",
                isLoading = isLoading,
                onRetry = onRetry
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                signals.take(8).forEach { signal ->
                    item(key = signal.id) {
                        HomeTemplateWatchCard(
                            signal = signal,
                            display = homeWatchDisplay(app, homeViewModel, signal, watchItems)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeNotificationControl(
    title: String,
    detail: String,
    isEnabled: Boolean,
    isAllowed: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Bell,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val actionText = when {
                isEnabled && !isAllowed -> "설정"
                isEnabled -> "끄기"
                else -> "켜기"
            }
            val actionAccessibilityLabel = when {
                isEnabled && !isAllowed -> "판단 알림 설정 열기"
                isEnabled -> "판단 알림 끄기"
                else -> "판단 알림 켜기"
            }
            OutlinedButton(
                onClick = when {
                    isEnabled && !isAllowed -> onOpenSettings
                    isEnabled -> onDisable
                    else -> onEnable
                },
                modifier = Modifier
                    .height(38.dp)
                    .clearAndSetSemantics {
                        role = Role.Button
                        contentDescription = actionAccessibilityLabel
                        onClick(label = actionAccessibilityLabel) {
                            when {
                                isEnabled && !isAllowed -> onOpenSettings()
                                isEnabled -> onDisable()
                                else -> onEnable()
                            }
                            true
                        }
                    },
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)
            ) {
                Text(actionText, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun HomeTemplateSectionHeader(
    title: String,
    icon: LucideIcon,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            actionLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .quantClickable(role = QuantPressRole.Text, onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1
        )
    }
}

@Composable
internal fun HomeTemplateWatchCard(signal: HomeWatchSignal, display: HomeWatchDisplay) {
    val changeValue = display.changeValue ?: 0.0
    val tint = when {
        changeValue < 0.0 -> QuantNegative
        changeValue > 0.0 -> QuantPositive
        else -> homeActionToneColor(signal.tone)
    }
    Card(
        modifier = Modifier
            .width(228.dp)
            .height(138.dp)
            .quantClickable(role = QuantPressRole.Card, onClick = signal.onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp, pressedElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (signal.isIndicator) {
                    MarketIndicatorLogoView(
                        ticker = signal.ticker,
                        name = display.name.ifBlank { signal.name.ifBlank { signal.ticker } },
                        size = 52.dp,
                        tint = homeActionToneColor(signal.tone)
                    )
                } else {
                    TickerAvatar(signal.ticker, display.market, size = 52.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        display.name.ifBlank { signal.ticker },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        display.sector,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    AnimatedPriceText(
                        text = display.priceText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        display.changeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HomeMiniSparkline(
                    changeValue = changeValue,
                    tint = tint,
                    modifier = Modifier
                        .width(62.dp)
                        .height(34.dp)
                )
            }
        }
    }
}

@Composable
internal fun HomeMiniSparkline(
    changeValue: Double,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val negative = changeValue < 0.0
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        val points = if (negative) {
            listOf(0.00f to 0.22f, 0.18f to 0.38f, 0.36f to 0.31f, 0.56f to 0.58f, 0.76f to 0.50f, 1.00f to 0.78f)
        } else {
            listOf(0.00f to 0.74f, 0.18f to 0.58f, 0.36f to 0.64f, 0.56f to 0.36f, 0.76f to 0.43f, 1.00f to 0.20f)
        }
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val px = x * size.width
            val py = y * size.height
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
internal fun HomeTemplateCandidateList(
    stocks: List<PortfolioStock>,
    profile: InvestmentProfile,
    isLoading: Boolean,
    onMore: () -> Unit,
    onFilter: () -> Unit,
    onOpen: (PortfolioStock) -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeTemplateSectionHeader(
            title = "주목해야 할 후보들",
            icon = LucideIcon.Target,
            actionLabel = "전체 보기",
            onAction = onMore
        )
        if (stocks.isEmpty()) {
            HomeSectionFallbackCard(
                title = if (isLoading) "후보 로딩 중" else "후보 없음",
                message = if (isLoading) "모델 점수와 기대수익 데이터를 불러오고 있습니다." else "후보 데이터가 아직 도착하지 않았습니다.",
                isLoading = isLoading,
                onRetry = onRetry
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                stocks.take(3).forEach { stock ->
                    HomeTemplateCandidateRow(stock = stock, profile = profile, onOpen = { onOpen(stock) })
                }
            }
            Text(
                "모든후보 보기",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f))
                    .quantClickable(role = QuantPressRole.Row, onClick = onMore)
                    .padding(vertical = 14.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun HomeTemplateCandidateRow(stock: PortfolioStock, profile: InvestmentProfile, onOpen: () -> Unit) {
    val currency = marketCurrency(stock.ticker, stock.market)
    val reason = portfolioHomeReason(stock)
    val compactReason = compactPortfolioReason(reason)
    val personal = remember(profile, stock) { personalizedStockInterpretation(profile, stock) }
    val tint = homeActionToneColor(reason.tone)
    val change = stock.return1M
    val changeColor = when {
        change == null || !change.isFinite() -> MaterialTheme.colorScheme.onSurfaceVariant
        change >= 0.0 -> QuantPositive
        else -> QuantNegative
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp, pressedElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TickerAvatar(stock.ticker, stock.market, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stock.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) }
                        ?: homeSubtitle(stock.market, "후보"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${personal.headline} · ${personal.detail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.width(102.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AnimatedPriceText(
                    text = stock.currentPrice?.let { fmtPx(it, currency) } ?: score(stock.totalScore),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    compactReason,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                change?.takeIf { it.isFinite() }?.let {
                    Text(
                        pct(it),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = changeColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

internal fun compactPortfolioReason(reason: HomeCardReason): String {
    return when (reason.title) {
        "기대수익" -> "기대수익"
        "퀄리티" -> "ROIC 우수"
        "성장" -> "성장 확인"
        "주의" -> "타이밍 확인"
        "확인" -> "차트 확인"
        else -> reason.title.take(7)
    }
}

internal const val HOME_PRICE_AUTO_REFRESH_MS = 180_000L
internal const val HOME_CANDIDATE_PRICE_AUTO_REFRESH_MS = 300_000L
