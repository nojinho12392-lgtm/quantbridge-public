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

internal fun marketBriefingValue(app: QuantAppState): String {
    return displayBriefingRegime(
        firstMacroValue(app.macro, "Regime", "Macro_Regime", "US_Regime", "Market_Regime", "risk_regime")
    ) ?: "시장 상태 대기"
}

internal fun marketBriefingDetail(app: QuantAppState): String {
    val risk = firstMacroValue(app.macro, "Risk_Signal", "Risk_Level", "Risk", "Signal")
    if (!risk.isNullOrBlank()) {
        return "위험 신호 ${displayBriefingRegime(risk) ?: risk}"
    }
    val index = app.marketIndices.firstOrNull()
    if (index != null) {
        return "${index.label} ${pct(index.changePct)}"
    }
    return if (app.homeLoading) "시장 데이터 동기화 중" else "주요 지수와 매크로를 함께 확인하세요"
}

internal fun marketBriefingColor(app: QuantAppState): Color {
    val risk = firstMacroValue(app.macro, "Risk_Signal", "Risk_Level", "Risk", "Signal").orEmpty()
    val text = "${marketBriefingValue(app)} $risk".uppercase()
    return when {
        text.contains("RISK_OFF") || text.contains("BEAR") || text.contains("WARNING") || text.contains("위험회피") -> QuantWarning
        text.contains("RISK_ON") || text.contains("BULL") || text.contains("PASS") || text.contains("위험선호") -> QuantPositive
        else -> Color.Unspecified
    }.takeUnless { it == Color.Unspecified } ?: Color(0xFF2E63D1)
}

internal fun marketBriefingValue(app: QuantAppState, homeViewModel: HomeViewModel): String {
    return displayBriefingRegime(
        firstMacroValue(homeViewModel.macro.ifEmpty { app.macro }, "Regime", "Macro_Regime", "US_Regime", "Market_Regime", "risk_regime")
    ) ?: "시장 상태 대기"
}

internal fun marketBriefingDetail(app: QuantAppState, homeViewModel: HomeViewModel): String {
    val macro = homeViewModel.macro.ifEmpty { app.macro }
    val risk = firstMacroValue(macro, "Risk_Signal", "Risk_Level", "Risk", "Signal")
    if (!risk.isNullOrBlank()) {
        return "위험 신호 ${displayBriefingRegime(risk) ?: risk}"
    }
    val index = app.marketIndices.firstOrNull()
    if (index != null) {
        return "${index.label} ${pct(index.changePct)}"
    }
    return if (homeViewModel.loading || app.homeLoading) "시장 데이터 동기화 중" else "주요 지수와 매크로를 함께 확인하세요"
}

internal fun marketBriefingColor(app: QuantAppState, homeViewModel: HomeViewModel): Color {
    val macro = homeViewModel.macro.ifEmpty { app.macro }
    val risk = firstMacroValue(macro, "Risk_Signal", "Risk_Level", "Risk", "Signal").orEmpty()
    val text = "${marketBriefingValue(app, homeViewModel)} $risk".uppercase()
    return when {
        text.contains("RISK_OFF") || text.contains("BEAR") || text.contains("WARNING") || text.contains("위험회피") -> QuantWarning
        text.contains("RISK_ON") || text.contains("BULL") || text.contains("PASS") || text.contains("위험선호") -> QuantPositive
        else -> Color.Unspecified
    }.takeUnless { it == Color.Unspecified } ?: Color(0xFF2E63D1)
}

internal fun displayBriefingRegime(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank() || raw == "-") return null
    val normalized = raw.uppercase().replace(" ", "_").replace("-", "_")
    return when {
        raw.contains("위험선호") || normalized == "RISK_ON" -> "위험선호"
        raw.contains("위험회피") || normalized == "RISK_OFF" -> "위험회피"
        raw.contains("중립") || normalized == "NEUTRAL" -> "중립"
        else -> raw
    }
}

internal fun firstMacroValue(macro: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        macro[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}

internal fun earningsCalendarSummary(item: EarningsCalendarItem?): String {
    return item?.let { "${compactDateText(it.nextEarningsDate)} · ${earningsCalendarDayText(it.daysUntil)}" }
        ?: "예정 실적 일정 대기 중"
}

internal fun earningsCalendarDayText(days: Int?): String {
    return when {
        days == null -> "D-?"
        days == 0 -> "D-Day"
        days > 0 -> "D-$days"
        else -> "D+${kotlin.math.abs(days)}"
    }
}

internal fun briefingLatestUpdate(app: QuantAppState): String? {
    return buildList {
        add(firstMetaValue(app.usMeta, "Generated", "Generated_At", "Last_Updated"))
        add(firstMetaValue(app.krMeta, "Generated", "Generated_At", "Last_Updated"))
        add(app.macro["Generated"])
        add(app.opsHealth?.generatedAt)
        addAll(app.marketIndices.map { it.updatedAt })
        addAll(app.marketIndicators.mapNotNull { it.updatedAt })
        addAll(app.usPortfolio.mapNotNull { it.lastUpdated })
        addAll(app.krPortfolio.mapNotNull { it.lastUpdated })
        addAll(app.usSmallCap.mapNotNull { it.lastUpdated })
        addAll(app.krSmallCap.mapNotNull { it.lastUpdated })
        addAll(app.newsItems.map { it.publishedAt })
        addAll(app.signalEvents.mapNotNull { it.eventTime ?: it.updatedAt })
    }
        .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
        .maxOrNull()
}

internal fun briefingDataIssueCount(app: QuantAppState): Int {
    val loadIssues = listOf(app.error, app.marketIndicatorError, app.newsError).count { !it.isNullOrBlank() }
    val signalIssues = app.researchQuality?.items
        ?.count { it.status.uppercase() in setOf("FAIL", "WATCH", "INSUFFICIENT") }
        ?: 0
    val opsIssues = app.opsHealth?.checks
        ?.count { it.status.uppercase() !in setOf("OK", "PASS", "HEALTHY") }
        ?: 0
    return loadIssues + signalIssues + opsIssues
}

internal fun briefingLoadedSourceCount(app: QuantAppState): Int {
    return listOf(
        app.usPortfolio.isNotEmpty(),
        app.krPortfolio.isNotEmpty(),
        app.usSmallCap.isNotEmpty() || app.krSmallCap.isNotEmpty(),
        app.usEarnings.isNotEmpty() || app.krEarnings.isNotEmpty() || app.earningsCalendar.isNotEmpty(),
        app.marketIndices.isNotEmpty(),
        app.marketIndicators.isNotEmpty(),
        app.newsItems.isNotEmpty(),
        app.researchQuality != null,
        app.opsHealth != null
    ).count { it }
}

internal fun firstMetaValue(meta: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}
