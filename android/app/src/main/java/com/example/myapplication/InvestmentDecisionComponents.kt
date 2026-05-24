package com.example.myapplication

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
import com.example.myapplication.ui.theme.QuantGreen
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantWarning
import kotlin.math.abs

private val decisionReasonOptions = listOf("장기 성장", "저평가", "실적 개선", "배당/안정성", "단기 모멘텀", "공부 필요")
private val decisionStatusOptions = listOf("관심 유지", "보류", "제외", "추가 확인 필요", "실적 후 재검토", "내 성향과 맞지 않음")
private val decisionReviewOptions = listOf("실적 발표 후", "점수 개선 시", "가격 조정 시", "리스크 완화 시", "비교 후보 확인 후")

private enum class InvestmentDecisionStep(val question: String) {
    Reason("투자 이유가 무엇인가요?"),
    Fit("내 기준으로 어떻게 보나요?"),
    CounterEvidence("주의할 신호는 무엇인가요?"),
    ReviewCondition("다시 볼 조건은?"),
    Status("최종 상태는?")
}

private val investmentDecisionSteps = InvestmentDecisionStep.values().toList()

@Composable
fun InvestmentDecisionCard(
    request: DetailRequest,
    detail: StockDetail?,
    profile: InvestmentProfile,
    record: InvestmentDecisionRecord?,
    onEdit: () -> Unit
) {
    var showDecisionGuide by remember { mutableStateOf(false) }
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.CalendarCheck,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = decisionStatusColor(record?.status)
            )
            Text("내 투자 결정서", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (record == null) {
                IconButton(
                    onClick = { showDecisionGuide = true },
                    modifier = Modifier
                        .size(30.dp)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = "투자 결정서 설명"
                            onClick(label = "투자 결정서 설명 열기") {
                                showDecisionGuide = true
                                true
                            }
                        }
                ) {
                    LucideIconView(
                        icon = LucideIcon.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            record?.let { DecisionQualityPill(it.qualityPercent, it.qualityLabel) }
        }
        if (record == null) {
            Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                LucideIconView(icon = LucideIcon.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("투자 결정서 작성")
            }
        } else {
            Text(
                record.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = decisionStatusColor(record.status),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                record.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            if (record.fitLabel.isNotBlank()) DecisionHintRow("기준 해석", record.fitLabel)
            if (record.note.isNotBlank()) DecisionHintRow("메모", record.note)
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                LucideIconView(icon = LucideIcon.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("결정서 수정")
            }
        }
    }
    if (showDecisionGuide) {
        AlertDialog(
            onDismissRequest = { showDecisionGuide = false },
            confirmButton = {
                TextButton(onClick = { showDecisionGuide = false }) {
                    Text("확인")
                }
            },
            title = { Text("투자 결정서란?") },
            text = {
                Text(
                    "투자 결정서는 매수/매도를 바로 정하는 기능이 아니라, 이 종목을 보는 이유와 주의 신호, 다시 볼 조건을 먼저 남기는 판단 기록입니다. 나중에 감정이 아니라 내가 세운 기준으로 결정을 복기할 수 있게 도와줍니다.",
                    lineHeight = 20.sp
                )
            }
        )
    }
}

@Composable
fun InvestmentDecisionSheet(
    request: DetailRequest,
    detail: StockDetail?,
    profile: InvestmentProfile,
    record: InvestmentDecisionRecord?,
    onSave: (InvestmentDecisionRecord) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val fitInsight = remember(profile, detail, request) { personalizedStockInterpretation(profile, request, detail) }
    val fit = remember(fitInsight, record?.fitLabel) {
        record?.fitLabel?.takeIf { it.isNotBlank() } ?: fitInsight.decisionLine
    }
    val suggestedCounters = remember(profile, detail, request) { suggestedDecisionCounterEvidence(profile, detail, request) }
    val decisionDraftKey = "${normalizedTicker(request.ticker)}:${record?.updatedAt.orEmpty()}"
    var selectedReasons by remember(decisionDraftKey) {
        mutableStateOf(record?.reasons?.toSet() ?: suggestedDecisionReasons(request, detail).take(2).toSet())
    }
    var selectedCounters by remember(decisionDraftKey) {
        mutableStateOf(record?.counterEvidence?.toSet() ?: suggestedCounters.take(2).toSet())
    }
    var status by remember(decisionDraftKey) { mutableStateOf(record?.status ?: "추가 확인 필요") }
    var reviewTrigger by remember(decisionDraftKey) { mutableStateOf(record?.reviewTrigger ?: decisionReviewOptions.first()) }
    var condition by remember(decisionDraftKey) {
        mutableStateOf(record?.condition ?: suggestedDecisionCondition(request, detail))
    }
    var note by remember(decisionDraftKey) { mutableStateOf(record?.note.orEmpty()) }
    var step by remember(decisionDraftKey) { mutableStateOf(InvestmentDecisionStep.Reason) }
    val currentStepIndex = investmentDecisionSteps.indexOf(step)
    val isLastStep = currentStepIndex == investmentDecisionSteps.lastIndex
    val counterOptions = remember(suggestedCounters) { (suggestedCounters + defaultCounterEvidenceOptions()).distinct() }
    val saveDecision = {
        onSave(
            InvestmentDecisionRecord(
                ticker = request.ticker,
                name = request.name,
                market = request.market ?: if (request.currency == "KRW") "KR" else "US",
                currency = request.currency,
                reasons = decisionReasonOptions.filter { it in selectedReasons },
                counterEvidence = counterOptions.filter { it in selectedCounters },
                fitLabel = fit,
                condition = condition,
                status = status,
                reviewTrigger = reviewTrigger,
                note = note,
                createdAt = record?.createdAt.orEmpty()
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TickerAvatar(request.ticker, request.market, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("투자 결정서", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${request.name} · ${request.ticker}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            onDelete?.let {
                TextButton(onClick = it) {
                    Text("삭제", color = QuantNegative)
                }
            }
        }

        DecisionStepProgress(stepIndex = currentStepIndex, total = investmentDecisionSteps.size)

        Text(
            step.question,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (step) {
                InvestmentDecisionStep.Reason -> {
                    DecisionChipGrid(
                        options = decisionReasonOptions,
                        selected = selectedReasons,
                        onToggle = { label -> selectedReasons = toggleSet(selectedReasons, label) }
                    )
                    DecisionSelectionCount("${selectedReasons.size}개 선택됨")
                }

                InvestmentDecisionStep.Fit -> {
                    DecisionFitInsightPanel(
                        profile = profile,
                        insight = fitInsight,
                        conflicts = suggestedCounters.take(3),
                        fitLine = fit
                    )
                }

                InvestmentDecisionStep.CounterEvidence -> {
                    DecisionChipGrid(
                        options = counterOptions,
                        selected = selectedCounters,
                        onToggle = { label -> selectedCounters = toggleSet(selectedCounters, label) }
                    )
                    DecisionSelectionCount("${selectedCounters.size}개 선택됨")
                }

                InvestmentDecisionStep.ReviewCondition -> {
                    OutlinedTextField(
                        value = condition,
                        onValueChange = { condition = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("확인 조건") },
                        placeholder = { Text("예: 실적 발표 후 매출 성장과 마진이 같이 유지되는지") },
                        minLines = 3
                    )
                    DecisionChipGrid(
                        options = decisionReviewOptions,
                        selected = setOf(reviewTrigger).filter { it.isNotBlank() }.toSet(),
                        onToggle = { label -> reviewTrigger = if (reviewTrigger == label) "" else label }
                    )
                }

                InvestmentDecisionStep.Status -> {
                    DecisionChipGrid(
                        options = decisionStatusOptions,
                        selected = setOf(status),
                        onToggle = { label -> status = label }
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("결론 메모") },
                        placeholder = { Text("예: 기대수익은 높지만 실적 전 불확실성이 있어 보류") },
                        minLines = 3
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("취소") }
            Spacer(Modifier.weight(1f))
            if (currentStepIndex > 0) {
                OutlinedButton(
                    onClick = { step = investmentDecisionSteps[currentStepIndex - 1] }
                ) {
                    Text("이전")
                }
            }
            Button(
                onClick = {
                    if (isLastStep) {
                        saveDecision()
                    } else {
                        step = investmentDecisionSteps[currentStepIndex + 1]
                    }
                }
            ) {
                Text(if (isLastStep) "저장" else "다음")
            }
        }
    }
}

@Composable
private fun DecisionStepProgress(stepIndex: Int, total: Int) {
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
private fun DecisionSelectionCount(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun DecisionFitInsightPanel(
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
private fun DecisionFitExplainRow(label: String, value: String) {
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

private fun decisionProfileCriteria(profile: InvestmentProfile): String {
    val clean = profile.normalized
    if (!clean.isConfigured) return "아직 투자 기준 미설정"
    val style = decisionStyleLabel(clean.style)
    val horizon = clean.horizon.ifBlank { "기간 미설정" }
    val volatility = decisionVolatilityLabel(clean.riskTolerance)
    val guardrail = decisionPrimaryGuardrail(clean)
    return listOf(style, horizon, volatility, guardrail).joinToString(" · ")
}

private fun decisionDetectedSignals(insight: PersonalizedStockInterpretation): String {
    return insight.reasons
        .take(4)
        .joinToString(" · ")
        .ifBlank { "뚜렷한 자동 신호 부족" }
}

private fun decisionConflictSignals(values: List<String>): String {
    return values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(3)
        .joinToString(" · ")
        .ifBlank { "현재 기준과 직접 충돌하는 신호는 뚜렷하지 않음" }
}

private fun decisionStyleLabel(style: String): String {
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

private fun decisionVolatilityLabel(riskTolerance: String): String {
    return when {
        riskTolerance.contains("공격") || riskTolerance.contains("성장") -> "변동성 허용"
        riskTolerance.contains("보수") || riskTolerance.contains("안정") || riskTolerance.contains("낮") -> "변동성 제한"
        riskTolerance.isBlank() -> "변동성 기준 미설정"
        else -> "변동성 균형"
    }
}

private fun decisionPrimaryGuardrail(profile: InvestmentProfile): String {
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
private fun DecisionQualityPill(percent: Int, label: String) {
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
private fun DecisionHintRow(label: String, value: String) {
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
private fun DecisionChipGrid(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
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

private fun toggleSet(values: Set<String>, label: String): Set<String> {
    return if (label in values) values - label else values + label
}

@Composable
private fun decisionStatusColor(status: String?): androidx.compose.ui.graphics.Color {
    return when (status) {
        "관심 유지" -> QuantGreen
        "보류", "추가 확인 필요", "실적 후 재검토" -> QuantWarning
        "제외", "내 성향과 맞지 않음" -> QuantNegative
        else -> MaterialTheme.colorScheme.primary
    }
}

private fun suggestedDecisionReasons(request: DetailRequest, detail: StockDetail?): List<String> {
    val text = listOf(
        request.name,
        request.sections.flatMap { it.metrics }.joinToString(" ") { "${it.label} ${it.value}" },
        request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    ).joinToString(" ").lowercase()
    return buildList {
        if (text.contains("성장") || text.contains("growth") || detail?.info?.revenueGrowth?.let { it > 0.10 } == true) add("장기 성장")
        if (text.contains("저평가") || text.contains("per") || text.contains("pbr") || text.contains("value")) add("저평가")
        if (text.contains("실적") || text.contains("earnings") || text.contains("surprise")) add("실적 개선")
        if (text.contains("배당") || text.contains("dividend")) add("배당/안정성")
        if (text.contains("모멘텀") || text.contains("거래량") || text.contains("momentum")) add("단기 모멘텀")
        add("공부 필요")
    }.distinct()
}

private fun suggestedDecisionCounterEvidence(
    profile: InvestmentProfile,
    detail: StockDetail?,
    request: DetailRequest
): List<String> {
    val info = detail?.info
    val signals = request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    return buildList {
        if (profile.riskTolerance == "보수적" && (info?.beta ?: 0.0) > 1.1) add("내 성향보다 변동성이 큼")
        if ((info?.beta ?: 0.0) >= 1.3) add("시장 대비 변동성 높음")
        if ((info?.revenueGrowth ?: 0.0) < 0.0) add("매출 성장 둔화")
        if ((info?.debtToEquity ?: 0.0) > 150.0) add("부채 부담 확인 필요")
        if ((info?.peRatio ?: 0.0) > 45.0) add("밸류에이션 부담")
        if (signals.contains("실적") || signals.contains("earnings", ignoreCase = true)) add("실적 전 불확실성")
        if (signals.contains("급등") || signals.contains("거래량")) add("단기 과열 가능성")
        if (isPriceNearHigh(info)) add("52주 고점 근처")
        add("비교 후보 없이 단독 판단 위험")
    }.distinct()
}

private fun defaultCounterEvidenceOptions(): List<String> {
    return listOf("내 성향보다 변동성이 큼", "실적 전 불확실성", "단기 과열 가능성", "데이터 근거 부족", "비교 후보 없이 단독 판단 위험")
}

private fun decisionFitLabel(profile: InvestmentProfile, detail: StockDetail?, request: DetailRequest): String {
    if (!profile.isConfigured) return "투자 성향을 저장하면 내 기준 적합도를 더 정확히 볼 수 있습니다."
    return personalizedStockInterpretation(profile, request, detail).decisionLine
}

private fun suggestedDecisionCondition(request: DetailRequest, detail: StockDetail?): String {
    val info = detail?.info
    return when {
        request.signals.any { it.title.contains("실적") || it.detail.contains("실적") } -> "실적 발표 후 매출 성장과 마진이 같이 유지되는지 확인"
        (info?.revenueGrowth ?: 0.0) < 0.0 -> "매출 성장률이 회복되는지 확인"
        isPriceNearHigh(info) -> "가격이 조정된 뒤에도 점수와 거래량이 유지되는지 확인"
        else -> "비슷한 후보와 비교한 뒤 점수, 리스크, 가격 위치가 모두 납득될 때 재검토"
    }
}

private fun isPriceNearHigh(info: StockInfo?): Boolean {
    val current = info?.currentPrice ?: return false
    val low = info.week52Low ?: return false
    val high = info.week52High ?: return false
    if (!current.isFinite() || !low.isFinite() || !high.isFinite() || high <= low) return false
    return abs((high - current) / (high - low)) < 0.15
}
