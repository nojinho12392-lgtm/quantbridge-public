package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlin.math.abs

@Composable
internal fun DecisionStepProgress(stepIndex: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (index <= stepIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        )
                )
            }
        }
        Text(
            "${stepIndex + 1} / $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun DecisionSelectionCount(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun DecisionFitInsightPanel(
    profile: InvestmentProfile,
    insight: PersonalizedStockInterpretation,
    conflicts: List<String>,
    fitLine: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "내 기준 자동 해석",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "매수/매도 추천이 아니라 저장된 투자 기준과 종목 신호를 대조한 1차 판단입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
            DecisionFitExplainRow("내 기준", decisionProfileCriteria(profile))
            DecisionFitExplainRow("감지된 신호", decisionDetectedSignals(insight))
            DecisionFitExplainRow("충돌 신호", decisionConflictSignals(conflicts))
            DecisionFitExplainRow("결론", fitLine)
        }
    }
}

@Composable
internal fun DecisionFitExplainRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
    }
}

internal fun decisionProfileCriteria(profile: InvestmentProfile): String {
    val clean = profile.normalized
    if (!clean.isConfigured) return "아직 투자 기준 미설정"
    val style = decisionStyleLabel(clean.style)
    val horizon = clean.horizon.ifBlank { "기간 미설정" }
    val volatility = decisionVolatilityLabel(clean.riskTolerance)
    val guardrail = decisionPrimaryGuardrail(clean)
    return listOf(style, horizon, volatility, guardrail).joinToString(" · ")
}

internal fun decisionDetectedSignals(insight: PersonalizedStockInterpretation): String {
    return insight.reasons
        .take(4)
        .joinToString(" · ")
        .ifBlank { "뚜렷한 자동 신호 부족" }
}

internal fun decisionConflictSignals(values: List<String>): String {
    return values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(" · ")
        .ifBlank { "현재 기준과 직접 충돌하는 신호는 뚜렷하지 않음" }
}

internal fun decisionStyleLabel(style: String): String {
    return when {
        style.contains("성장") -> "성장형"
        style.contains("가치") -> "가치형"
        style.contains("배당") -> "배당형"
        style.contains("퀄리티") -> "퀄리티형"
        style.contains("모멘텀") -> "모멘텀형"
        style.isBlank() -> "스타일 미설정"
        else -> "${style}형"
    }
}

internal fun decisionVolatilityLabel(riskTolerance: String): String {
    return when {
        riskTolerance.contains("공격") || riskTolerance.contains("성장") -> "변동성 허용"
        riskTolerance.contains("보수") || riskTolerance.contains("안정") || riskTolerance.contains("낮") -> "변동성 제한"
        riskTolerance.isBlank() -> "변동성 기준 미설정"
        else -> "변동성 균형"
    }
}

internal fun decisionPrimaryGuardrail(profile: InvestmentProfile): String {
    val avoidances = profile.avoidances
    return when {
        avoidances.any { it.contains("고평가") } -> "고점 추격 주의"
        avoidances.any { it.contains("급등락") } -> "급등락 회피"
        avoidances.any { it.contains("부채") } -> "부채 부담 확인"
        avoidances.any { it.contains("적자") } -> "적자 지속 주의"
        avoidances.any { it.contains("거래량") } -> "거래량 부족 주의"
        else -> "고점 추격 주의"
    }
}

@Composable
fun InvestmentDecisionInlineSummary(record: InvestmentDecisionRecord?, modifier: Modifier = Modifier) {
    if (record == null) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(decisionStatusColor(record.status).copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        LucideIconView(
            icon = LucideIcon.CalendarCheck,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = decisionStatusColor(record.status)
        )
        Text(
            record.inlineSummary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = decisionStatusColor(record.status),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DecisionQualityPill(percent: Int, label: String) {
    val color = when {
        percent >= 80 -> QuantGreen
        percent >= 40 -> MaterialTheme.colorScheme.primary
        else -> QuantWarning
    }
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f))
    ) {
        Text(
            "$percent% · $label",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
internal fun DecisionHintRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp
        )
    }
}

@Composable
internal fun DecisionChipGrid(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { label ->
            val isSelected = label in selected
            Surface(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .quantClickable(role = QuantPressRole.Row) { onToggle(label) },
                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
                )
            ) {
                Text(
                    label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                )
            }
        }
    }
}

internal fun toggleSet(values: Set<String>, label: String): Set<String> {
    return if (label in values) values - label else values + label
}

@Composable
internal fun decisionStatusColor(status: String?): androidx.compose.ui.graphics.Color {
    return when (status) {
        "관심 유지" -> QuantGreen
        "보류", "추가 확인 필요", "실적 후 재검토" -> QuantWarning
        "제외", "내 성향과 맞지 않음" -> QuantNegative
        else -> MaterialTheme.colorScheme.primary
    }
}
