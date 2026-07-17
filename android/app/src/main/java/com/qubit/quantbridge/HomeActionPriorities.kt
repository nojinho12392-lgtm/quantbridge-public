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

internal fun marketActionPriority(app: QuantAppState, homeViewModel: HomeViewModel): Double {
    val largestIndexMove = app.marketIndices.map { abs(it.changePct) }.maxOrNull() ?: 0.0
    val riskText = "${marketBriefingValue(app, homeViewModel)} ${marketBriefingDetail(app, homeViewModel)}".uppercase(Locale.US)
    val riskBonus = if (riskText.contains("RISK_OFF") || riskText.contains("위험") || riskText.contains("WARNING")) 24.0 else 0.0
    return 48.0 + min(24.0, largestIndexMove * 1_000.0) + riskBonus
}

internal fun earningsActionPriority(item: EarningsCalendarItem): Double {
    val days = item.daysUntil ?: return 62.0
    return when {
        days <= 0 -> 96.0
        days <= 3 -> 90.0 - days
        days <= 7 -> 78.0 - days
        else -> 58.0
    }
}

internal fun candidateActionPriority(stock: PortfolioStock, profile: InvestmentProfile = InvestmentProfile()): Double {
    val expected = abs(stock.expectedReturn ?: 0.0)
    val rankBonus = max(0.0, 8.0 - (stock.rank ?: 8).toDouble())
    val cautionBonus = if ((stock.expectedReturn ?: 0.0) < 0.0) 18.0 else 0.0
    val scoreBonus = min(10.0, max(0.0, (stock.totalScore ?: 0.0) - 70.0) / 4.0)
    val momentumBonus = min(10.0, homePercentMagnitude(stock.return1M) / 2.0)
    val rankMoveBonus = min(8.0, abs((stock.rankChange ?: 0).toDouble()) * 1.5)
    val freshRankBonus = if ((stock.rankStatus ?: "").contains("신규")) 6.0 else 0.0
    return 54.0 + min(18.0, expected * 100.0) + rankBonus + cautionBonus + scoreBonus + momentumBonus + rankMoveBonus + freshRankBonus +
        profileCandidatePriorityBonus(stock, profile)
}

internal fun smallCapActionPriority(stock: SmallCapStock, profile: InvestmentProfile = InvestmentProfile()): Double {
    val scoreBonus = min(14.0, max(0.0, ((stock.totalScore ?: 0.0) - 60.0) / 3.0))
    val volumeBonus = min(16.0, max(0.0, ((stock.volumeSurge ?: 1.0) - 1.0) * 10.0))
    val growthBonus = min(10.0, homePercentMagnitude(stock.revGrowth) / 3.0)
    val rankMoveBonus = min(6.0, abs((stock.rankChange ?: 0).toDouble()) * 1.2)
    return 50.0 + scoreBonus + volumeBonus + growthBonus + rankMoveBonus + profileSmallCapPriorityBonus(stock, profile)
}

internal fun profileCandidatePriorityBonus(stock: PortfolioStock, profile: InvestmentProfile): Double {
    if (!profile.isConfigured) return 0.0
    val styleBonus = when {
        profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 12.0 -> 9.0
        profile.style.contains("가치") && (stock.expectedReturn ?: 0.0) > 0.08 -> 8.0
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.grossMargin ?: 0.0) > 0.35) -> 8.0
        profile.style.contains("모멘텀") && homePercentMagnitude(stock.return1M) >= 6.0 -> 7.0
        else -> 0.0
    }
    val riskBonus = if ((profile.riskTolerance.contains("낮") || profile.riskTolerance.contains("안정")) &&
        ((stock.roic ?: 0.0) > 0.10 || (stock.grossMargin ?: 0.0) > 0.30)
    ) 4.0 else 0.0
    val conflicts = candidateConflictLabels(
        profile = profile,
        return1M = stock.return1M,
        expectedReturn = stock.expectedReturn,
        debtEbitda = null,
        volumeSurge = null
    )
    return styleBonus + riskBonus - conflicts.size * 7.0
}

internal fun profileSmallCapPriorityBonus(stock: SmallCapStock, profile: InvestmentProfile): Double {
    if (!profile.isConfigured) return 0.0
    val styleBonus = when {
        profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 15.0 -> 9.0
        profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.fcfMargin ?: 0.0) > 0.06) -> 8.0
        profile.style.contains("모멘텀") && (stock.volumeSurge ?: 1.0) >= 1.8 -> 7.0
        profile.style.contains("가치") && (stock.totalScore ?: 0.0) >= 70.0 -> 5.0
        else -> 0.0
    }
    val conflicts = candidateConflictLabels(
        profile = profile,
        return1M = stock.return1M,
        expectedReturn = null,
        debtEbitda = stock.debtEbitda,
        volumeSurge = stock.volumeSurge
    )
    return styleBonus - conflicts.size * 8.0
}

internal fun candidateProfileNudge(stock: PortfolioStock, profile: InvestmentProfile): String? {
    if (!profile.isConfigured) return null
    val positives = buildList {
        if (profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 12.0) add("성장 기준 부합")
        if (profile.style.contains("가치") && (stock.expectedReturn ?: 0.0) > 0.08) add("상대 저평가 후보")
        if (profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.grossMargin ?: 0.0) > 0.35)) add("퀄리티 근거")
        if (profile.style.contains("모멘텀") && homePercentMagnitude(stock.return1M) >= 6.0) add("모멘텀 확인")
    }
    val conflicts = candidateConflictLabels(profile, stock.return1M, stock.expectedReturn, null, null)
    return (positives.take(1) + conflicts.take(1).map { "주의: $it" }).joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun smallCapProfileNudge(stock: SmallCapStock, profile: InvestmentProfile): String? {
    if (!profile.isConfigured) return null
    val positives = buildList {
        if (profile.style.contains("성장") && homePercentMagnitude(stock.revGrowth) >= 15.0) add("성장 기준 부합")
        if (profile.style.contains("퀄리티") && ((stock.roic ?: 0.0) > 0.12 || (stock.fcfMargin ?: 0.0) > 0.06)) add("퀄리티 근거")
        if (profile.style.contains("모멘텀") && (stock.volumeSurge ?: 1.0) >= 1.8) add("거래량 확인")
    }
    val conflicts = candidateConflictLabels(profile, stock.return1M, null, stock.debtEbitda, stock.volumeSurge)
    return (positives.take(1) + conflicts.take(1).map { "주의: $it" }).joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun candidateConflictLabels(
    profile: InvestmentProfile,
    return1M: Double?,
    expectedReturn: Double?,
    debtEbitda: Double?,
    volumeSurge: Double?
): List<String> {
    if (profile.avoidances.isEmpty()) return emptyList()
    val move = homePercentMagnitude(return1M)
    return buildList {
        if (profile.avoidances.any { it.contains("급등락") } && move >= 14.0) add("급등락")
        if (profile.avoidances.any { it.contains("고평가") } && (expectedReturn ?: 0.0) < 0.0) add("고평가")
        if (profile.avoidances.any { it.contains("부채") } && (debtEbitda ?: 0.0) >= 3.0) add("부채")
        if (profile.avoidances.any { it.contains("거래량") } && (volumeSurge ?: 1.0) >= 2.2) add("거래량 급증")
    }.distinct()
}
