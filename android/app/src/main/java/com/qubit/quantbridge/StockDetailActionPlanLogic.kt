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

internal fun detailActionPlanRows(request: DetailRequest, detail: StockDetail?): List<DetailActionPlanModel> {
    val info = detail?.info
    val metrics = request.sections.flatMap { it.metrics }
    if (request.isEtfDetail()) {
        return buildList {
            request.signals.firstOrNull()?.let { signal ->
                add(
                    DetailActionPlanModel(
                        "ETF 성격 확인",
                        "${signal.title}을 기준으로 추종 대상과 사용 목적을 먼저 확인하세요.",
                        signal.tone
                    )
                )
            }
            val current = info?.currentPrice
            val low = info?.week52Low
            val high = info?.week52High
            if (current != null && low != null && high != null && high > low) {
                val position = ((current - low) / (high - low)).coerceIn(0.0, 1.0)
                add(
                    DetailActionPlanModel(
                        when {
                            position >= 0.75 -> "고점권 매수 주의"
                            position <= 0.25 -> "저점권 반등 확인"
                            else -> "가격 위치 확인"
                        },
                        "현재가는 52주 범위의 ${"%.0f".format(position * 100)}% 지점입니다. 추종 지수 흐름과 함께 보세요.",
                        if (position >= 0.75) DetailTone.Warning else DetailTone.Primary
                    )
                )
            } else {
                add(
                    DetailActionPlanModel(
                        "차트 기간 비교",
                        "1개월부터 5년까지 수익률과 변동성을 바꿔 보며 진입 구간을 판단하세요.",
                        DetailTone.Primary
                    )
                )
            }
            eventMetricValue(request, "총보수")?.let { fee ->
                add(
                    DetailActionPlanModel(
                        "비용 비교",
                        "총보수 $fee 를 같은 지수 또는 같은 테마 ETF와 비교하세요.",
                        DetailTone.Positive
                    )
                )
            }
        }.take(3)
    }
    return buildList {
        request.signals.firstOrNull()?.let { signal ->
            add(
                DetailActionPlanModel(
                    "핵심 신호 먼저 확인",
                    "${signal.title}을 기준으로 핵심 신호와 가격 위치를 함께 확인하세요.",
                    signal.tone
                )
            )
        } ?: metrics.firstOrNull { isScoreMetric(it.label) && it.value != "-" }?.let { metric ->
            add(
                DetailActionPlanModel(
                    "모델 점수 확인",
                    "${metric.label} ${metric.value}가 어떤 팩터에서 나온 값인지 아래 근거를 먼저 확인하세요.",
                    metric.tone
                )
            )
        }

        val current = info?.currentPrice
        val low = info?.week52Low
        val high = info?.week52High
        if (current != null && low != null && high != null && high > low) {
            val position = ((current - low) / (high - low)).coerceIn(0.0, 1.0)
            add(
                DetailActionPlanModel(
                    when {
                        position >= 0.75 -> "고점권 진입 타이밍 주의"
                        position <= 0.25 -> "저점권 반등 조건 확인"
                        else -> "가격 위치 중립"
                    },
                    "현재가는 52주 범위의 ${"%.0f".format(position * 100)}% 지점입니다. 차트에서 추세와 지지선을 같이 보세요.",
                    if (position >= 0.75) DetailTone.Warning else DetailTone.Primary
                )
            )
        } else if (current != null) {
            add(
                DetailActionPlanModel(
                    "가격 기준 보강 필요",
                    "현재가는 있으나 52주 범위가 부족합니다. 차트 기간을 바꿔 가격 위치를 직접 확인하세요.",
                    DetailTone.Neutral
                )
            )
        }

        val pe = info?.forwardPe ?: info?.peRatio
        if (pe != null) {
            add(
                DetailActionPlanModel(
                    if (pe >= 35.0) "밸류에이션 부담 확인" else "밸류에이션 비교",
                    "PER ${"%.1f".format(pe)}x입니다. 성장률과 마진이 이 배수를 정당화하는지 비교하세요.",
                    if (pe >= 35.0) DetailTone.Warning else DetailTone.Neutral
                )
            )
        } else {
            add(
                DetailActionPlanModel(
                    "PER 공백 확인",
                    stockValuationUnavailableReason(info),
                    DetailTone.Neutral
                )
            )
        }

        if (isEmpty()) {
            add(
                DetailActionPlanModel(
                    "상세 데이터 대기",
                    "가격, 팩터, 실적 데이터가 도착하면 확인 순서를 자동으로 정리합니다.",
                    DetailTone.Neutral
                )
            )
        }
    }.take(3)
}

internal fun earningsEventPlanRows(request: DetailRequest): List<DetailActionPlanModel> {
    if (request.isEtfDetail()) return emptyList()
    if (!isEarningsEventRequest(request)) return emptyList()
    val daysText = eventMetricValue(request, "남은 기간", "경과일")
    val dateText = eventMetricValue(request, "예정일", "발표일")
    val preEvent = eventMetricValue(request, "예정일") != null ||
        daysText?.contains("후") == true ||
        daysText?.contains("오늘") == true

    return buildList {
        if (preEvent) {
            add(
                DetailActionPlanModel(
                    "발표 전",
                    "${daysText ?: "예정일"} ${dateText ?: ""} 기준으로 포지션 크기, 변동성, 컨센서스 변화를 먼저 확인하세요.",
                    DetailTone.Warning
                )
            )
            add(
                DetailActionPlanModel(
                    "발표 직후",
                    "EPS보다 매출, 마진, 가이던스, 거래량 반응을 함께 보고 하루짜리 급등락과 추세 변화를 분리하세요.",
                    DetailTone.Primary
                )
            )
        } else {
            add(
                DetailActionPlanModel(
                    "발표 후 추적",
                    "${daysText ?: "발표 후"} 구간입니다. 실적 서프라이즈가 가격과 거래량에 계속 반영되는지 확인하세요.",
                    DetailTone.Primary
                )
            )
        }
        add(
            DetailActionPlanModel(
                "다음 판단",
                "실적 이벤트 전후에는 기존 모델 점수와 가격 반응이 같은 방향인지 확인한 뒤 관심 유지 여부를 정하세요.",
                DetailTone.Neutral
            )
        )
    }
}

internal fun isEarningsEventRequest(request: DetailRequest): Boolean {
    val labels = request.sections.flatMap { it.metrics }.map { normalizedLabel(it.label) }
    val signalText = request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    return labels.any { it in setOf("예정일", "남은기간", "발표일", "경과일", "eps") } ||
        signalText.contains("실적") ||
        signalText.contains("earnings", ignoreCase = true)
}

internal fun eventMetricValue(request: DetailRequest, vararg labels: String): String? {
    val normalized = labels.map(::normalizedLabel).toSet()
    return request.sections
        .flatMap { it.metrics }
        .firstOrNull { metric ->
            normalizedLabel(metric.label) in normalized &&
                metric.value.isNotBlank() &&
                metric.value != "-"
        }
        ?.value
}
