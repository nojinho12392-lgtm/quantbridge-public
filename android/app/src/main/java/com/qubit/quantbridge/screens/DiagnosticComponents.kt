package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import java.util.Locale
import kotlin.math.min

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PulseRegimeCard(macro: Map<String, String>, market: Market) {
    val regime = macro["Regime"] ?: "-"
    val score = macro["Regime_Score"] ?: "-"
    val prefix = market.title
    val color = regimeColor(regime)
    val primaryHint = regimeActionHints(regime).firstOrNull()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "오늘 시장 분위기",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        regimeDecisionTitle(regime),
                        style = MaterialTheme.typography.headlineSmall,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        regimeDescription(regime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = color.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
                ) {
                    Text(
                        "판단 강도 $score",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            primaryHint?.let { hint ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = color.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, color.copy(alpha = 0.12f))
                    ) {
                        Text(
                            hint,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    macro["Generated"]?.take(16)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "업데이트 $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactMetricTile("저평가", macro["${prefix}_V_Weight"] ?: "-", Modifier.weight(1f), color = color)
                CompactMetricTile("추세", macro["${prefix}_M_Weight"] ?: "-", Modifier.weight(1f), color = color)
            }
        }
    }
}

@Composable
internal fun MacroSignalTile(reason: RegimeReason, modifier: Modifier = Modifier) {
    val color = toneColor(signalTone(reason.signal))
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.07f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    reason.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    macroSignalBadgeText(reason.signal),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Text(
                macroSignedPercentText(reason.title, reason.value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun macroSignedPercentText(title: String, value: String): AnnotatedString {
    val coloredTitles = setOf("장기 추세", "최근 흐름", "금리 환경", "신용 시장", "신용시장")
    val match = firstSignedPercentMatch(value)
    if (title !in coloredTitles || match == null) {
        return AnnotatedString(value)
    }
    val color = when {
        match.value > 0.0 -> QuantPositive
        match.value < 0.0 -> QuantNegative
        else -> MaterialTheme.colorScheme.onSurface
    }
    return buildAnnotatedString {
        append(value)
        addStyle(SpanStyle(color = color), match.start, match.endExclusive)
    }
}

internal data class SignedPercentMatch(
    val start: Int,
    val endExclusive: Int,
    val value: Double
)

internal fun firstSignedPercentMatch(value: String): SignedPercentMatch? {
    val match = Regex("""[-+]?\d+(?:\.\d+)?\s*%""").find(value) ?: return null
    val percent = match.value.replace("%", "").replace(" ", "").toDoubleOrNull() ?: return null
    return SignedPercentMatch(match.range.first, match.range.last + 1, percent)
}
