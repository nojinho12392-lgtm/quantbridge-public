package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.abs

internal data class DecisionPillModel(
    val title: String,
    val value: String,
    val detail: String,
    val tone: DetailTone
)

internal data class ScoreRationaleRow(
    val title: String,
    val value: String,
    val detail: String,
    val tone: DetailTone
)

internal data class MissingDataReason(
    val title: String,
    val detail: String
)

internal data class DetailActionPlanModel(
    val title: String,
    val detail: String,
    val tone: DetailTone
)

internal data class InvestmentProfileFitModel(
    val title: String,
    val detail: String,
    val icon: LucideIcon,
    val color: Color
)

internal data class InvestmentProfileFitSummary(
    val score: Int,
    val label: String,
    val tone: Color,
    val positiveReasons: List<String>,
    val cautionReasons: List<String>,
    val checklist: List<FitChecklistItem>,
    val thesisLine: String?,
    val invalidationLine: String?
)

internal data class FitChecklistItem(
    val label: String,
    val done: Boolean
)

internal data class DetailGuardrailRow(
    val title: String,
    val detail: String,
    val icon: LucideIcon,
    val color: Color
)

internal data class NoBuyFirstRowModel(
    val label: String,
    val title: String,
    val detail: String,
    val icon: LucideIcon,
    val color: Color
)
