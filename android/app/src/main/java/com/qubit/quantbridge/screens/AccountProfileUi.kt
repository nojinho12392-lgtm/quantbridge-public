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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlin.math.min

@Composable
internal fun InvestmentProfileCard(profile: InvestmentProfile, onEdit: () -> Unit) {
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.SlidersHorizontal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("내 투자 기준", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    profile.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            TextButton(onClick = onEdit) {
                LucideIconView(
                    icon = LucideIcon.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (profile.isConfigured) "수정" else "진단")
            }
        }

        if (profile.isConfigured) {
            Text(
                profile.operatingStatement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InvestmentProfileMetricPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                InvestmentProfileMetricPill(profile.consistencyLabel, profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
            }
            InvestmentProfilePill(profile.headline, LucideIcon.Target)
            InvestmentProfilePill(profile.guardrailSummary, LucideIcon.ShieldCheck)
            profile.consistencyNotes.firstOrNull()?.let {
                InvestmentProfileNotice(it)
            }
        } else {
            InvestmentProfilePill("계좌 연결 없이도 나만의 판단 기준을 먼저 세웁니다.", LucideIcon.Lightbulb)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InvestmentProfileSheet(
    profile: InvestmentProfile,
    onSave: (InvestmentProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(profile) { mutableStateOf(profile.normalized) }
    var currentStep by remember(profile) { mutableStateOf(InvestmentProfileStep.Experience) }
    val experienceOptions = listOf("처음 시작", "기본 분석 가능", "숙련")
    val horizonOptions = listOf("1개월", "3개월", "6개월", "1년+")
    val riskOptions = listOf("보수적", "균형", "성장", "공격적")
    val styleOptions = listOf("성장주", "가치주", "배당", "퀄리티", "모멘텀")
    val avoidanceOptions = listOf("급등락", "적자 지속", "고평가", "높은 부채", "낮은 거래량")
    val dropResponseOptions = listOf("가설부터 재검토", "확인 조건까지 보류", "분할 관찰", "손실 한도 도달 시 종료")
    val overheatedResponseOptions = listOf("비교 후보 먼저 보기", "가격 안정 후 보기", "소액 관심만 유지", "모멘텀 근거 확인")
    val steps = InvestmentProfileStep.values().toList()
    val currentIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Target,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("내 투자 기준", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "후보를 보기 전에 내 판단 규칙을 먼저 정합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        InvestmentProfileWizardHeader(
            step = currentStep,
            currentIndex = currentIndex + 1,
            totalCount = steps.size
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (currentStep) {
                InvestmentProfileStep.Experience -> InvestmentProfileOptionList(
                    options = experienceOptions,
                    selected = draft.experience,
                    onSelect = { draft = draft.copy(experience = if (draft.experience == it) "" else it) }
                )
                InvestmentProfileStep.Horizon -> InvestmentProfileOptionList(
                    options = horizonOptions,
                    selected = draft.horizon,
                    onSelect = { draft = draft.copy(horizon = if (draft.horizon == it) "" else it) }
                )
                InvestmentProfileStep.Risk -> InvestmentProfileOptionList(
                    options = riskOptions,
                    selected = draft.riskTolerance,
                    onSelect = { draft = draft.copy(riskTolerance = if (draft.riskTolerance == it) "" else it) }
                )
                InvestmentProfileStep.Style -> InvestmentProfileOptionList(
                    options = styleOptions,
                    selected = draft.style,
                    onSelect = { draft = draft.copy(style = if (draft.style == it) "" else it) }
                )
                InvestmentProfileStep.Avoidances -> InvestmentProfileMultiOptionList(
                    options = avoidanceOptions,
                    selected = draft.avoidances,
                    onSelect = { label ->
                        draft = if (label in draft.avoidances) {
                            draft.copy(avoidances = draft.avoidances - label)
                        } else {
                            draft.copy(avoidances = draft.avoidances + label)
                        }
                    }
                )
                InvestmentProfileStep.DropScenario -> InvestmentProfileOptionList(
                    options = dropResponseOptions,
                    selected = draft.dropResponse,
                    onSelect = { draft = draft.copy(dropResponse = if (draft.dropResponse == it) "" else it) }
                )
                InvestmentProfileStep.HeatScenario -> InvestmentProfileOptionList(
                    options = overheatedResponseOptions,
                    selected = draft.overheatedResponse,
                    onSelect = { draft = draft.copy(overheatedResponse = if (draft.overheatedResponse == it) "" else it) }
                )
                InvestmentProfileStep.Summary -> InvestmentProfileSummaryPanel(draft.normalized)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
            Spacer(Modifier.weight(1f))
            if (currentStep != InvestmentProfileStep.Experience) {
                OutlinedButton(
                    onClick = {
                        steps.getOrNull(currentIndex - 1)?.let { currentStep = it }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("이전")
                }
            }
            Button(
                onClick = {
                    if (currentStep == InvestmentProfileStep.Summary) {
                        onSave(draft.normalized)
                    } else {
                        steps.getOrNull(currentIndex + 1)?.let { currentStep = it }
                    }
                },
                modifier = Modifier.height(52.dp)
            ) {
                Text(if (currentStep == InvestmentProfileStep.Summary) "저장" else "다음")
            }
        }
    }
}

internal enum class InvestmentProfileStep(
    val title: String,
    val subtitle: String
) {
    Experience(
        "투자 경험은 어느 정도인가요?",
        "설명 깊이와 리스크 문구의 톤을 맞추는 기준입니다."
    ),
    Horizon(
        "얼마 동안 지켜볼 생각인가요?",
        "관심 종목을 단기 신호로 볼지, 긴 흐름으로 볼지 나눕니다."
    ),
    Risk(
        "변동성은 어디까지 괜찮나요?",
        "같은 랭킹이라도 내 기준에 맞는 후보를 더 차분히 보게 합니다."
    ),
    Style(
        "끌리는 투자 스타일은 무엇인가요?",
        "성장, 가치, 배당처럼 먼저 보고 싶은 관점을 정합니다."
    ),
    Avoidances(
        "피하고 싶은 신호가 있나요?",
        "여러 개를 골라도 괜찮습니다."
    ),
    DropScenario(
        "20% 하락하면 어떻게 할까요?",
        "흔들릴 때 미리 정한 행동 기준이 있어야 가설을 차분하게 복기할 수 있습니다."
    ),
    HeatScenario(
        "좋아 보이지만 너무 올랐다면?",
        "기회처럼 보이는 순간에도 비교와 확인 조건을 먼저 둘지 정합니다."
    ),
    Summary(
        "이 기준으로 저장할까요?",
        "저장된 기준은 이 기기에 보관되며 후보를 볼 때 함께 확인할 개인 기준입니다."
    )
}

@Composable
internal fun InvestmentProfileWizardHeader(
    step: InvestmentProfileStep,
    currentIndex: Int,
    totalCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(totalCount) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (index < currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        )
                )
            }
        }
        Text(
            "$currentIndex / $totalCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
        Text(step.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
    }
}

@Composable
internal fun InvestmentProfilePill(label: String, icon: LucideIcon) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun InvestmentProfileMetricPill(title: String, value: String, icon: LucideIcon, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun InvestmentProfileNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(QuantWarning.copy(alpha = 0.09f))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        LucideIconView(
            icon = LucideIcon.TriangleAlert,
            contentDescription = null,
            tint = QuantWarning,
            modifier = Modifier.padding(top = 2.dp).size(14.dp)
        )
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp
        )
    }
}

@Composable
internal fun InvestmentProfileOptionList(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            InvestmentProfileChoiceRow(
                label = label,
                selected = selected == label,
                onClick = { onSelect(label) }
            )
        }
    }
}

@Composable
internal fun InvestmentProfileMultiOptionList(
    options: List<String>,
    selected: List<String>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            InvestmentProfileChoiceRow(
                label = label,
                selected = label in selected,
                onClick = { onSelect(label) }
            )
        }
    }
}

@Composable
internal fun InvestmentProfileChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .quantClickable(role = QuantPressRole.Row, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
        )
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun InvestmentProfileSummaryPanel(profile: InvestmentProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                profile.operatingStatement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InvestmentProfileMetricPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                InvestmentProfileMetricPill(profile.consistencyLabel, profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
            }
            InvestmentProfileSummaryRow("투자 경험", displayProfileValue(profile.experience))
            InvestmentProfileSummaryRow("투자 기간", displayProfileValue(profile.horizon))
            InvestmentProfileSummaryRow("위험 선호", displayProfileValue(profile.riskTolerance))
            InvestmentProfileSummaryRow("선호 스타일", displayProfileValue(profile.style))
            InvestmentProfileSummaryRow(
                "피하고 싶은 신호",
                if (profile.avoidances.isEmpty()) "선택 안 함" else profile.avoidances.joinToString(" · ")
            )
            InvestmentProfileSummaryRow("하락 시 행동", displayProfileValue(profile.dropResponse))
            InvestmentProfileSummaryRow("과열 시 행동", displayProfileValue(profile.overheatedResponse))
            profile.consistencyNotes.firstOrNull()?.let {
                InvestmentProfileNotice(it)
            }
        }
    }
}

@Composable
internal fun InvestmentProfileSummaryRow(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(
            title,
            modifier = Modifier.width(92.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            lineHeight = 19.sp
        )
    }
}

internal fun displayProfileValue(value: String): String {
    return value.ifBlank { "선택 안 함" }
}
