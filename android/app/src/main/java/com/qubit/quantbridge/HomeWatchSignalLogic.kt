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

internal fun homeWatchSignals(
    app: QuantAppState,
    homeViewModel: HomeViewModel,
    watchItems: List<WatchlistItem>,
    onMarketIndicators: () -> Unit
): List<HomeWatchSignal> {
    if (watchItems.isEmpty()) return emptyList()
    return watchItems.map { item ->
        val keys = watchMatchKeys(item.ticker)
        if (item.isMarketIndicatorWatchItem()) {
            val quote = app.marketIndicators.firstOrNull { normalizedTicker(it.symbol) in keys }
            val change = quote?.changePct
            val changeText = change?.let { pct(it) } ?: "변화 대기"
            HomeWatchSignal(
                id = "indicator-${normalizedTicker(item.ticker)}",
                ticker = item.ticker,
                name = item.name,
                title = "관심 지수",
                detail = quote?.let { "${it.label} ${formatIndexValue(it.value)} · $changeText" } ?: "지수 데이터를 감시 중입니다.",
                metric = changeText,
                tone = if (abs(change ?: 0.0) >= 0.015) DetailTone.Warning else DetailTone.Primary,
                priority = if (abs(change ?: 0.0) >= 0.015) 3 else 1,
                icon = LucideIcon.LineChart,
                isIndicator = true,
                notificationOption = if (abs(change ?: 0.0) >= 0.015) "판단 업데이트" else null,
                notificationTitle = "시장 판단 업데이트가 생겼습니다",
                notificationBody = quote?.let { "${it.label} $changeText · 관심종목 판단 전에 시장 온도를 확인하세요." },
                onOpen = onMarketIndicators
            )
        } else {
            homeCompanyWatchSignal(app, homeViewModel, item, keys)
        }
    }
        .sortedWith(compareByDescending<HomeWatchSignal> { it.priority }.thenBy { it.name })
}

internal fun homeWatchNotificationEvents(
    signals: List<HomeWatchSignal>,
    watchItems: List<WatchlistItem>
): List<QubitNotificationEvent> {
    return signals.mapNotNull { signal ->
        val option = signal.notificationOption ?: return@mapNotNull null
        val item = notificationWatchItem(signal, watchItems) ?: return@mapNotNull null
        if (!wantsWatchAlert(item, option)) return@mapNotNull null
        QubitNotificationEvent(
            id = signal.id,
            title = signal.notificationTitle ?: signal.title,
            body = signal.notificationBody ?: signal.detail,
            daysUntil = signal.notificationDaysUntil
        )
    }
}

internal fun homeDailyNotificationSummary(signals: List<HomeWatchSignal>, watchItems: List<WatchlistItem>): String {
    val primary = signals.firstOrNull { it.priority >= 3 }
    if (primary != null) return "${primary.title}: ${primary.detail}"
    if (watchItems.isEmpty()) return "오늘 볼 후보와 시장 상태를 확인하세요."
    return "관심 항목 ${watchItems.size}개를 기준으로 실적 일정과 변화 신호를 확인하세요."
}

internal fun notificationWatchItem(signal: HomeWatchSignal, watchItems: List<WatchlistItem>): WatchlistItem? {
    val signalKeys = watchMatchKeys(signal.ticker)
    return watchItems.firstOrNull { item ->
        watchMatchKeys(item.ticker).any { key -> key in signalKeys }
    }
}

internal fun wantsWatchAlert(item: WatchlistItem, option: String): Boolean {
    return watchAlertOptionMatches(item.alertOptions, option)
}
