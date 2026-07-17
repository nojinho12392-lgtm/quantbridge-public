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
