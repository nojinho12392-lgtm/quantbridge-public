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

internal fun suggestedDecisionReasons(request: DetailRequest, detail: StockDetail?): List<String> {
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

internal fun suggestedDecisionCounterEvidence(
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

internal fun defaultCounterEvidenceOptions(): List<String> {
    return listOf("내 성향보다 변동성이 큼", "실적 전 불확실성", "단기 과열 가능성", "데이터 근거 부족", "비교 후보 없이 단독 판단 위험")
}

internal fun decisionFitLabel(profile: InvestmentProfile, detail: StockDetail?, request: DetailRequest): String {
    if (!profile.isConfigured) return "투자 성향을 저장하면 내 기준 적합도를 더 정확히 볼 수 있습니다."
    return personalizedStockInterpretation(profile, request, detail).decisionLine
}

internal fun suggestedDecisionCondition(request: DetailRequest, detail: StockDetail?): String {
    val info = detail?.info
    return when {
        request.signals.any { it.title.contains("실적") || it.detail.contains("실적") } -> "실적 발표 후 매출 성장과 마진이 같이 유지되는지 확인"
        (info?.revenueGrowth ?: 0.0) < 0.0 -> "매출 성장률이 회복되는지 확인"
        isPriceNearHigh(info) -> "가격이 조정된 뒤에도 점수와 거래량이 유지되는지 확인"
        else -> "비슷한 후보와 비교한 뒤 점수, 리스크, 가격 위치가 모두 납득될 때 재검토"
    }
}

internal fun isPriceNearHigh(info: StockInfo?): Boolean {
    val current = info?.currentPrice ?: return false
    val low = info.week52Low ?: return false
    val high = info.week52High ?: return false
    if (!current.isFinite() || !low.isFinite() || !high.isFinite() || high <= low) return false
    return abs((high - current) / (high - low)) < 0.15
}
