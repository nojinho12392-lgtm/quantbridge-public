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

internal val decisionReasonOptions = listOf("장기 성장", "저평가", "실적 개선", "배당/안정성", "단기 모멘텀", "공부 필요")
internal val decisionStatusOptions = listOf("관심 유지", "보류", "제외", "추가 확인 필요", "실적 후 재검토", "내 성향과 맞지 않음")
internal val decisionReviewOptions = listOf("실적 발표 후", "점수 개선 시", "가격 조정 시", "리스크 완화 시", "비교 후보 확인 후")

internal enum class InvestmentDecisionStep(val question: String) {
    Reason("투자 이유가 무엇인가요?"),
    Fit("내 기준으로 어떻게 보나요?"),
    CounterEvidence("주의할 신호는 무엇인가요?"),
    ReviewCondition("다시 볼 조건은?"),
    Status("최종 상태는?")
}

internal val investmentDecisionSteps = InvestmentDecisionStep.values().toList()

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
