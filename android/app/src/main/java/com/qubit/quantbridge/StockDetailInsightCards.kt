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

@Composable
internal fun DetailDecisionBriefCard(
    request: DetailRequest,
    detail: StockDetail?
) {
    val info = detail?.info
    val pills = remember(request, detail) { detailDecisionPills(request, detail) }
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = if (request.isEtfDetail()) LucideIcon.PieChart else LucideIcon.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("판단 요약", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                formattedUpdateTimestamp(detail?.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            decisionSummaryText(request, detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (pills.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pills.take(2).forEach { pill ->
                    DecisionPill(pill, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pills.drop(2).take(2).forEach { pill ->
                    DecisionPill(pill, Modifier.weight(1f))
                }
                if (pills.drop(2).take(2).size == 1) Spacer(Modifier.weight(1f))
            }
        } else if (info == null) {
            Text(
                if (request.isEtfDetail()) {
                    "상세 데이터가 도착하면 가격 흐름, 구성 비중, 총보수, 운용 규모를 순서대로 정리합니다."
                } else {
                    "상세 데이터가 도착하면 가격, 밸류에이션, 성장성, 리스크를 순서대로 정리합니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DetailActionPlanCard(
    request: DetailRequest,
    detail: StockDetail?
) {
    val rows = remember(request, detail) { detailActionPlanRows(request, detail) }
    if (rows.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.ListOrdered,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("다음 행동", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        rows.forEach { row ->
            ActionPlanRow(row)
        }
    }
}

@Composable
internal fun DetailComparisonGuardCard(
    request: DetailRequest,
    detail: StockDetail?,
    comparisonCount: Int,
    comparisonSelected: Boolean,
    onCompare: () -> Unit
) {
    val ready = comparisonSelected && comparisonCount >= 2
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.GitCompare,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("비교 전 판단 금지", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(
                color = if (ready) QuantGreen.copy(alpha = 0.10f) else QuantWarning.copy(alpha = 0.10f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, if (ready) QuantGreen.copy(alpha = 0.22f) else QuantWarning.copy(alpha = 0.22f))
            ) {
                Text(
                    if (ready) "비교 준비됨" else "비교 필요",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ready) QuantGreen else QuantWarning,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        Text(
            if (ready) {
                "${comparisonCount.coerceAtMost(4)}개 후보가 비교 목록에 있습니다. 점수, 성장성, 가격 위치를 나란히 본 뒤 우선순위를 정하세요."
            } else if (comparisonSelected) {
                "${request.name}은 비교 목록에 담겼습니다. 최소 한 개 후보를 더 담아 상대 매력을 확인하세요."
            } else {
                "${request.name} 하나만 보고 결론내리지 말고, 비슷한 후보와 같이 담아 상대 매력을 먼저 확인하세요."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCompare),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LucideIconView(
                    icon = if (comparisonSelected) LucideIcon.ListOrdered else LucideIcon.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (comparisonSelected) "비교 후보 관리" else "비교 후보에 담기",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${comparisonCount.coerceIn(0, 4)}/4",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun DetailMistakeCoachCard(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem? = null,
    comparisonSelected: Boolean
) {
    val rows = remember(profile, request, detail, watchItem, comparisonSelected) {
        detailMistakeCoachRows(profile, request, detail, watchItem, comparisonSelected)
    }
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.ShieldCheck,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("실수 방지 코치", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        rows.forEach { row ->
            GuardrailRow(row)
        }
    }
}

@Composable
internal fun InvestmentProfileFitCard(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?,
    watchItem: WatchlistItem? = null
) {
    val rows = remember(profile, request, detail) { investmentProfileFitRows(profile, request, detail) }
    val summary = remember(profile, request, detail, watchItem) {
        investmentProfileFitSummary(profile, request, detail, watchItem)
    }
    if (!profile.isConfigured || rows.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Target,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("내 기준 체크", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(
                color = summary.tone.copy(alpha = 0.10f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, summary.tone.copy(alpha = 0.22f))
            ) {
                Text(
                    "적합도 ${summary.score}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = summary.tone,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        Text(
            "${profile.headline} · ${summary.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        FitScoreBar(summary.score, summary.tone)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FitEvidenceBlock("좋은 근거", summary.positiveReasons, QuantGreen, Modifier.weight(1f))
            FitEvidenceBlock("주의 신호", summary.cautionReasons, QuantWarning, Modifier.weight(1f))
        }
        FitChecklist(summary.checklist)
        summary.thesisLine?.let {
            FitLinkedThesis(it, summary.invalidationLine)
        }
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(row.color.copy(alpha = 0.08f))
                    .padding(11.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = row.color.copy(alpha = 0.10f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        LucideIconView(
                            icon = row.icon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = row.color
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
internal fun PersonalizedStockFitCard(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?
) {
    val insight = remember(profile, request, detail) { personalizedStockInterpretation(profile, request, detail) }
    val color = personalInsightColor(insight.tone)
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Target,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = color
            )
            Text("나와의 적합도", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(
                color = color.copy(alpha = 0.10f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, color.copy(alpha = 0.20f))
            ) {
                Text(
                    insight.label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            insight.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            insight.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 20.sp
        )
        Text(
            insight.action,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp
        )
        if (insight.reasons.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                insight.reasons.take(3).forEach { reason ->
                    Text(
                        reason,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun FitScoreBar(score: Int, tone: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(score.coerceIn(0, 100) / 100f)
                .height(6.dp)
                .background(tone)
        )
    }
}

@Composable
internal fun FitEvidenceBlock(title: String, items: List<String>, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.07f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        items.take(2).forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FitChecklist(items: List<FitChecklistItem>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            Surface(
                color = if (item.done) QuantGreen.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, if (item.done) QuantGreen.copy(alpha = 0.22f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    LucideIconView(
                        icon = if (item.done) LucideIcon.Check else LucideIcon.Square,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (item.done) QuantGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(item.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun FitLinkedThesis(thesisLine: String, invalidationLine: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIconView(
                icon = LucideIcon.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text("연결된 투자 가설", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text(thesisLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        invalidationLine?.let {
            Text("무효 조건: $it", style = MaterialTheme.typography.labelSmall, color = QuantWarning, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun GuardrailRow(row: DetailGuardrailRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(row.color.copy(alpha = 0.08f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = row.color.copy(alpha = 0.11f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                LucideIconView(
                    icon = row.icon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = row.color
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                row.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
internal fun EarningsEventPlanCard(request: DetailRequest) {
    val rows = remember(request) { earningsEventPlanRows(request) }
    if (rows.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.CalendarClock,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("어닝 이벤트 플랜", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            eventMetricValue(request, "예정일", "발표일")?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        rows.forEach { row ->
            ActionPlanRow(row)
        }
    }
}

@Composable
internal fun ActionPlanRow(row: DetailActionPlanModel) {
    val rowColor = detailToneColor(row.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = rowColor.copy(alpha = 0.10f),
            shape = CircleShape
        ) {
            LucideIconView(
                icon = actionPlanToneIcon(row.tone),
                contentDescription = null,
                modifier = Modifier.padding(5.dp).size(14.dp),
                tint = rowColor
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                row.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ScoreRationaleCard(request: DetailRequest) {
    val rows = remember(request) { scoreRationaleRows(request) }
    if (rows.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Sparkles,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("점수 산정 근거", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        rows.forEach { row ->
            val rowColor = detailToneColor(row.tone)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LucideIconView(
                    icon = actionPlanToneIcon(row.tone),
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp).size(17.dp),
                    tint = rowColor
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            row.value,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = rowColor,
                            maxLines = 1
                        )
                    }
                    Text(
                        row.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailDataGapCard(
    request: DetailRequest,
    info: StockInfo?
) {
    val reasons = remember(request, info) { missingStockDataReasons(request, info) }
    if (reasons.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.TriangleAlert,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = QuantWarning
            )
            Text("데이터 공백", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(
                color = QuantWarning.copy(alpha = 0.10f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, QuantWarning.copy(alpha = 0.22f))
            ) {
                Text(
                    "${reasons.size}개 확인",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = QuantWarning,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        reasons.take(4).forEach { reason ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                LucideIconView(
                    icon = LucideIcon.TriangleAlert,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 3.dp).size(13.dp),
                    tint = QuantWarning
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(reason.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        reason.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DetailFactorCoverageCard(request: DetailRequest) {
    if (request.factors.isEmpty()) return
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Target,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("팩터 커버리지", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            request.factors.forEach { factor ->
                val tone = when {
                    factor.value >= 70.0 -> QuantGreen
                    factor.value >= 45.0 -> QuantWarning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    color = tone.copy(alpha = 0.09f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, tone.copy(alpha = 0.20f))
                ) {
                    Text(
                        "${factor.label} ${factor.value.toInt()}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = tone,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun DecisionPill(pill: DecisionPillModel, modifier: Modifier = Modifier) {
    val pillColor = detailToneColor(pill.tone)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(pill.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                pill.value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = pillColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pill.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
