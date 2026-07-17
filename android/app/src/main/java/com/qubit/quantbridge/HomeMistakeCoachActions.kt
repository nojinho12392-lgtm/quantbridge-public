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

internal fun homeMistakeCoachActions(
    app: QuantAppState,
    topPortfolio: PortfolioStock?,
    topSmallCap: SmallCapStock?
): List<HomeActionItem> {
    val companyWatches = app.watchlist.filterNot { it.isMarketIndicatorWatchItem() }
    return buildList {
        val weakThesis = companyWatches.firstOrNull { item ->
            val thesis = item.investmentThesis
            thesis.isEmpty || thesis.quality.percent < 80
        }
        if (weakThesis != null) {
            val thesis = weakThesis.investmentThesis
            add(
                HomeActionItem(
                    id = "coach-thesis-${normalizedTicker(weakThesis.ticker)}",
                    title = "가설 미완성 방지",
                    detail = if (thesis.isEmpty) {
                        "${weakThesis.name} · 관심 이유와 무효 조건을 먼저 남기세요."
                    } else {
                        "${weakThesis.name} · ${thesis.quality.missingFields.take(2).joinToString(" · ")} 보강 필요"
                    },
                    actionLabel = "정리",
                    icon = LucideIcon.Lightbulb,
                    tone = DetailTone.Warning,
                    priority = 86.0,
                    onOpen = { app.selectedTab = AppTab.Watch }
                )
            )
        }
        if (companyWatches.size >= 8) {
            add(
                HomeActionItem(
                    id = "coach-watch-spread",
                    title = "관심 분산 경고",
                    detail = "관심 기업 ${companyWatches.size}개입니다. 오늘은 가설 완성도가 낮은 항목부터 줄이세요.",
                    actionLabel = "Watch",
                    icon = LucideIcon.ShieldCheck,
                    tone = DetailTone.Warning,
                    priority = 80.0,
                    onOpen = { app.selectedTab = AppTab.Watch }
                )
            )
        }
        val fomoPortfolio = topPortfolio?.takeIf { stock ->
            homePercentMagnitude(stock.return1M) >= 14.0 &&
                (app.investmentProfile.avoidances.any { it.contains("급등락") } || app.investmentProfile.riskTolerance.contains("낮"))
        }
        fomoPortfolio?.let { stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            add(
                HomeActionItem(
                    id = "coach-fomo-${normalizedTicker(stock.ticker)}",
                    title = "추격매수 방지",
                    detail = "${stock.name} · 1개월 변동 ${pct(stock.return1M)}. 비교 후보와 무효 조건을 먼저 확인하세요.",
                    actionLabel = "보기",
                    icon = LucideIcon.TrendingUp,
                    tone = DetailTone.Warning,
                    priority = 84.0,
                    onOpen = { app.selectedDetail = portfolioDetail(stock, currency) }
                )
            )
        }
        val volatileSmallCap = topSmallCap?.takeIf { stock ->
            (stock.volumeSurge ?: 1.0) >= 2.2 && app.investmentProfile.avoidances.any { it.contains("거래량") || it.contains("급등락") }
        }
        volatileSmallCap?.let { stock ->
            val currency = marketCurrency(stock.ticker, stock.market)
            add(
                HomeActionItem(
                    id = "coach-volume-${normalizedTicker(stock.ticker)}",
                    title = "거래량 과열 점검",
                    detail = "${stock.name} · 거래량 ${multipleText(stock.volumeSurge)}. 모멘텀보다 지속 조건을 먼저 보세요.",
                    actionLabel = "보기",
                    icon = LucideIcon.TriangleAlert,
                    tone = DetailTone.Warning,
                    priority = 83.0,
                    onOpen = { app.selectedDetail = smallCapDetail(stock, currency) }
                )
            )
        }
    }
}

internal fun homePercentMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}

internal fun newsActionPriority(app: QuantAppState, item: NewsItem): Double {
    val move = abs(item.relatedChangePct ?: 0.0)
    val impact = abs(item.impactScore)
    val relatedKeys = (listOf(item.ticker) + item.relatedTickers)
        .flatMap { watchMatchKeys(it) }
        .toSet()
    val watchBonus = if (app.watchlist.any { watchMatchKeys(it.ticker).any { key -> key in relatedKeys } }) 16.0 else 0.0
    return 52.0 + min(24.0, move * 1_000.0) + min(18.0, impact * 18.0) + watchBonus
}
