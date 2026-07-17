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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchMetadataSheet(
    item: WatchlistItem,
    onSave: (tags: List<String>, memo: String, alertOptions: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTags by remember(item.ticker) { mutableStateOf(item.tags.toSet()) }
    var selectedAlerts by remember(item.ticker) { mutableStateOf(item.alertOptions.toSet()) }
    var thesis by remember(item.ticker, item.memo) { mutableStateOf(item.investmentThesis) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TickerAvatar(item.ticker, item.market, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.ticker} · ${item.market}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        WatchMetadataSection("태그") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                watchTagOptions.forEach { label ->
                    SelectableMetaChip(
                        label = label,
                        selected = label in selectedTags,
                        onClick = {
                            selectedTags = if (label in selectedTags) selectedTags - label else selectedTags + label
                        }
                    )
                }
            }
        }

        WatchMetadataSection("투자 가설") {
            WatchThesisQualityPanel(thesis.quality)
            Text(
                "관심 등록의 이유와 틀렸다고 볼 조건을 남겨두면 나중에 신호를 더 차분하게 점검할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WatchThesisTextField(
                title = "관심 이유",
                placeholder = "예: AI 서버 수요가 계속 늘어날 것",
                value = thesis.reason,
                onValueChange = { thesis = thesis.copy(reason = it) }
            )
            WatchThesisTextField(
                title = "기대하는 변화",
                placeholder = "예: 다음 분기 매출 성장률 유지",
                value = thesis.expectedChange,
                onValueChange = { thesis = thesis.copy(expectedChange = it) }
            )
            WatchThesisTextField(
                title = "확인할 조건",
                placeholder = "예: 마진과 가이던스가 같이 개선되는지",
                value = thesis.checkCondition,
                onValueChange = { thesis = thesis.copy(checkCondition = it) }
            )
            WatchThesisTextField(
                title = "틀렸다고 볼 조건",
                placeholder = "예: 매출 둔화와 마진 하락이 동시에 발생",
                value = thesis.invalidationCondition,
                onValueChange = { thesis = thesis.copy(invalidationCondition = it) }
            )
            Text(
                "관찰 기간",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                watchHorizonOptions.forEach { label ->
                    SelectableMetaChip(
                        label = label,
                        selected = thesis.horizon == label,
                        onClick = {
                            thesis = thesis.copy(horizon = if (thesis.horizon == label) "" else label)
                        }
                    )
                }
            }
            Text(
                "매수 추천이 아니라 내 판단 기준을 기록하는 용도입니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val suggestedTags = thesis.suggestedTags.filterNot { it in selectedTags }
            if (suggestedTags.isNotEmpty()) {
                Text(
                    "추천 태그",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestedTags.forEach { label ->
                        SelectableMetaChip(
                            label = label,
                            selected = false,
                            onClick = { selectedTags = selectedTags + label }
                        )
                    }
                }
            }
        }

        WatchMetadataSection("복기 루프") {
            Text(
                "처음 생각이 맞았는지 정기적으로 유지, 수정, 종료 중 하나로 정리하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                    .padding(11.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.CalendarClock,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        thesis.quality.reviewTiming,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        thesis.reviewPrompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                watchReviewStatusOptions.forEach { label ->
                    SelectableMetaChip(
                        label = label,
                        selected = thesis.reviewStatus == label,
                        onClick = {
                            thesis = thesis.copy(reviewStatus = if (thesis.reviewStatus == label) "" else label)
                        }
                    )
                }
            }
            WatchThesisTextField(
                title = "복기 메모",
                placeholder = "예: 가설은 유지하되 실적 발표 전까지 신규 판단 보류",
                value = thesis.reviewNote,
                onValueChange = { thesis = thesis.copy(reviewNote = it) }
            )
        }

        WatchMetadataSection("알림 조건") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                watchAlertOptions.forEach { label ->
                    SelectableMetaChip(
                        label = label,
                        selected = label in selectedAlerts,
                        onClick = {
                            selectedAlerts = if (label in selectedAlerts) selectedAlerts - label else selectedAlerts + label
                        }
                    )
                }
            }
            Text(
                "가격 도달보다 투자 가설, 실적 리스크, 과열 신호, 내 성향 기준 변화가 생겼을 때 판단 알림으로 연결됩니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("취소") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    onSave(selectedTags.toList(), thesis.memoText, selectedAlerts.toList())
                }
            ) {
                Text("저장")
            }
        }
    }
}

@Composable
internal fun WatchThesisQualityPanel(quality: WatchThesisQuality) {
    val tone = when {
        quality.percent >= 80 -> QuantGreen
        quality.percent >= 40 -> MaterialTheme.colorScheme.primary
        else -> QuantWarning
    }
    Surface(
        color = tone.copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.20f))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LucideIconView(
                    icon = if (quality.percent >= 80) LucideIcon.Check else LucideIcon.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = tone
                )
                Text(
                    "가설 완성도 ${quality.percent}% · ${quality.label}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(quality.percent / 100f)
                        .height(5.dp)
                        .background(tone)
                )
            }
            val missing = quality.missingFields.take(3)
            Text(
                if (missing.isEmpty()) quality.reviewTiming else "빠진 항목: ${missing.joinToString(" · ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WatchThesisTextField(
    title: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            minLines = 1,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun WatchMetadataSection(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun SelectableMetaChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
