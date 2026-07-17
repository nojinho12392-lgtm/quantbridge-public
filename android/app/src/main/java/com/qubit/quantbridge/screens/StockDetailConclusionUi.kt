package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun DetailConclusionActionBar(
    request: DetailRequest,
    detail: StockDetail?,
    watched: Boolean,
    comparisonSelected: Boolean,
    onWatch: () -> Unit,
    onMemo: () -> Unit,
    onCompare: () -> Unit
) {
    val conclusion = detailConclusion(request, detail)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = toneColor(conclusion.tone).copy(alpha = 0.08f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, toneColor(conclusion.tone).copy(alpha = 0.22f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(toneColor(conclusion.tone).copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = detailConclusionIcon(conclusion),
                        contentDescription = null,
                        tint = toneColor(conclusion.tone),
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(conclusion.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = toneColor(conclusion.tone))
                    Text(conclusion.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(conclusion.badge, conclusion.tone)
            }
            val watchActionLabel = if (watched) "${request.name} 관심 종목 제거" else "${request.name} 관심 종목 추가"
            val memoActionLabel = "${request.name} 관심 설정"
            val compareActionLabel = if (comparisonSelected) "${request.name} 비교 목록에 추가됨" else "${request.name} 비교 목록에 추가"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onWatch,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = watchActionLabel
                            onClick(label = watchActionLabel) {
                                onWatch()
                                true
                            }
                        },
                    colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                )
            ) {
                    Icon(
                        imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (watched) "관심중" else "관심")
                }
                OutlinedButton(
                    onClick = onMemo,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = memoActionLabel
                            onClick(label = memoActionLabel) {
                                onMemo()
                                true
                            }
                        }
                ) {
                    LucideIconView(icon = LucideIcon.SlidersHorizontal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("설정")
                }
                OutlinedButton(
                    onClick = onCompare,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = compareActionLabel
                            onClick(label = compareActionLabel) {
                                onCompare()
                                true
                            }
                        }
                ) {
                    LucideIconView(icon = if (comparisonSelected) LucideIcon.Check else LucideIcon.GitCompare, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("비교")
                }
            }
        }
    }
}

internal data class DetailConclusion(
    val title: String,
    val detail: String,
    val badge: String,
    val tone: DetailTone
)

internal fun detailConclusionIcon(conclusion: DetailConclusion): LucideIcon {
    return when {
        conclusion.badge == "ETF" -> LucideIcon.PieChart
        conclusion.tone == DetailTone.Positive -> LucideIcon.TrendingUp
        conclusion.tone == DetailTone.Negative -> LucideIcon.TriangleAlert
        conclusion.tone == DetailTone.Warning -> LucideIcon.TriangleAlert
        conclusion.tone == DetailTone.Primary -> LucideIcon.Target
        else -> LucideIcon.Activity
    }
}

internal fun detailConclusion(request: DetailRequest, detail: StockDetail?): DetailConclusion {
    val info = detail?.info
    return when {
        detail?.error?.isNotBlank() == true -> DetailConclusion(
            title = "데이터 확인 필요",
            detail = "상세 데이터 일부가 비어 있습니다. 결론을 확정하기 전에 데이터 탭을 확인하세요.",
            badge = "주의",
            tone = DetailTone.Warning
        )
        request.isEtfDetail() -> DetailConclusion(
            title = if (request.signals.any { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }) "ETF 조건 확인" else "ETF 추적 후보",
            detail = "구성 비중, 총보수, 가격 위치를 같은 지수 또는 같은 테마 ETF와 비교하세요.",
            badge = "ETF",
            tone = if (request.signals.any { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative }) DetailTone.Warning else DetailTone.Primary
        )
        info?.recommendation?.contains("buy", ignoreCase = true) == true -> DetailConclusion(
            title = "긍정 신호 우세",
            detail = "애널리스트 의견과 기본 지표가 우호적입니다. 비교군 대비 성장성과 수익성을 확인하세요.",
            badge = "후보",
            tone = DetailTone.Positive
        )
        request.signals.any { it.tone == DetailTone.Negative } -> DetailConclusion(
            title = "리스크 먼저 점검",
            detail = "부정 신호가 포함되어 있습니다. 포지션을 늘리기 전 리스크 사유를 먼저 확인하세요.",
            badge = "리스크",
            tone = DetailTone.Negative
        )
        request.signals.any { it.tone == DetailTone.Warning } -> DetailConclusion(
            title = "조건부 관찰",
            detail = "좋은 신호와 확인할 신호가 섞여 있습니다. 관심 조건을 정해두면 재방문이 쉬워집니다.",
            badge = "관찰",
            tone = DetailTone.Warning
        )
        else -> DetailConclusion(
            title = "비교 후 판단",
            detail = "단일 화면만으로 결론을 내리기보다 2~4개 후보를 비교해 우선순위를 정하세요.",
            badge = "중립",
            tone = DetailTone.Primary
        )
    }
}
