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
internal fun DiagnosticInfoDialog(
    info: DiagnosticInfo,
    onDismiss: () -> Unit
) {
    val accent = diagnosticAccent(info)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(accent.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    LucideIconView(
                        icon = diagnosticIcon(info),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = accent
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(info.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        color = accent.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            info.status,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                ) {
                    Text(
                        info.summary,
                        modifier = Modifier.padding(15.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    info.details.forEachIndexed { index, detail ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 62.dp)
                                    .padding(13.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(accent.copy(alpha = 0.10f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (index + 1).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    detail,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = accent.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LucideIconView(
                            icon = LucideIcon.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = accent
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "판단 포인트",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                diagnosticActionHint(info),
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

@Composable
internal fun diagnosticAccent(info: DiagnosticInfo): Color {
    val glossaryTone = glossaryTone(info.status)
    return toneColor(glossaryTone ?: statusTone(info.status))
}

internal fun diagnosticIcon(info: DiagnosticInfo): LucideIcon {
    return when (info.status) {
        "밸류에이션", "모델 전망", "스코어링", "리서치 검증" -> LucideIcon.Target
        "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀" -> LucideIcon.TrendingUp
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> LucideIcon.TriangleAlert
        "수급", "분석", "포트폴리오", "기업 규모" -> LucideIcon.LineChart
        else -> LucideIcon.Lightbulb
    }
}

internal fun diagnosticActionHint(info: DiagnosticInfo): String {
    return when (info.status) {
        "밸류에이션" -> "같은 업종 평균, 성장률, 마진을 함께 보세요. 숫자가 낮아도 이익이 꺾이면 싸다고 보기 어렵습니다."
        "수익성", "퀄리티" -> "높은 값이 유지되는지, 부채나 일회성 이익으로 만들어진 값은 아닌지 같이 확인하세요."
        "성장성" -> "성장률만 보지 말고 마진과 현금흐름이 같이 좋아지는지 확인하면 판단이 더 안전합니다."
        "현금흐름" -> "회계상 이익보다 실제 남는 현금에 가깝기 때문에 배당, 자사주, 재투자 여력을 볼 때 유용합니다."
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> "높은 값은 주가 흔들림이나 재무 부담을 키울 수 있습니다. 수익 신호가 좋아도 비중 판단에 반영하세요."
        "모델 전망", "AI 보정", "스코어링", "리서치 검증" -> "단독 매수 신호가 아니라 종목 간 우선순위를 정하는 보조 신호로 읽는 것이 좋습니다."
        "수급", "실적 모멘텀" -> "가격 반응과 거래량이 같은 방향인지 확인하세요. 이미 반영된 뉴스일 수도 있습니다."
        "기업 규모" -> "대형주는 안정성, 소형주는 성장성과 변동성을 같이 봐야 합니다. 같은 규모군 안에서 비교하면 더 정확합니다."
        else -> "${info.title}은 단독으로 결론을 내기보다 가격, 성장, 리스크 지표와 함께 비교해 보세요."
    }
}

internal fun glossaryTone(status: String): DetailTone? {
    return when (status) {
        "밸류에이션", "모델 전망", "AI 보정", "스코어링", "리서치 검증" -> DetailTone.Primary
        "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀" -> DetailTone.Positive
        "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도" -> DetailTone.Warning
        "수급", "분석", "포트폴리오", "기업 규모" -> DetailTone.Primary
        else -> null
    }
}
