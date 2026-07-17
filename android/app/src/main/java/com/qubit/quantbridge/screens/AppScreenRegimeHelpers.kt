package com.qubit.quantbridge

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun normalizeRegime(value: String?): String {
    val raw = value.orEmpty().trim()
    val compact = raw.uppercase(Locale.US).replace(" ", "_").replace("-", "_")
    return when {
        raw.contains("위험선호") -> "RISK_ON"
        raw.contains("위험회피") -> "RISK_OFF"
        raw.contains("중립") -> "NEUTRAL"
        compact == "RISK_ON" -> "RISK_ON"
        compact == "RISK_OFF" -> "RISK_OFF"
        compact == "NEUTRAL" -> "NEUTRAL"
        else -> raw.uppercase(Locale.US).ifBlank { "NEUTRAL" }
    }
}

internal fun displayRegime(value: String?): String {
    val raw = value.orEmpty().trim()
    if (raw.isBlank() || raw == "-") return raw.ifBlank { "-" }
    return when (normalizeRegime(raw)) {
        "RISK_ON" -> "위험선호"
        "RISK_OFF" -> "위험회피"
        "NEUTRAL" -> "중립"
        else -> if (raw.contains("_")) {
            raw.replace("_", " ").lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        } else {
            raw
        }
    }
}

internal fun regimeTitle(regime: String): String {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> "위험선호 장세"
        "RISK_OFF" -> "위험회피 장세"
        else -> "중립 장세"
    }
}

internal fun regimeDescription(regime: String): String {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> "시장이 주식과 성장주를 받아들이는 분위기입니다. 신규 진입은 가격 추세가 유지되는 종목부터 확인하세요."
        "RISK_OFF" -> "시장이 불확실성을 크게 보는 구간입니다. 후보를 보더라도 비중과 손절 기준을 먼저 정리하는 편이 좋습니다."
        else -> "상승과 하락 신호가 섞여 있습니다. 실적 일정과 가격 확인을 같이 보며 판단을 미루는 구간입니다."
    }
}

internal fun regimeDecisionTitle(regime: String): String {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> "위험자산 선호가 살아 있음"
        "RISK_OFF" -> "방어적으로 볼 장세"
        else -> "방향 확인이 필요한 장세"
    }
}

internal fun regimeActionHints(regime: String): List<String> {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> listOf("모멘텀 확인", "분할 진입", "과열 체크")
        "RISK_OFF" -> listOf("현금 비중", "방어주 우선", "손절 기준")
        else -> listOf("관망 가능", "실적 확인", "지수 방향")
    }
}

internal fun signalInt(value: String?): Int {
    return value.orEmpty().replace("+", "").trim().toDoubleOrNull()?.toInt() ?: 0
}

internal fun signalTone(signal: String): DetailTone {
    val score = signalInt(signal)
    return when {
        score > 0 -> DetailTone.Positive
        score < 0 -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

internal fun signalText(signal: String): String {
    return when (signalInt(signal)) {
        1 -> "+1 긍정"
        -1 -> "-1 부정"
        else -> "0 중립"
    }
}

internal fun macroSignalBadgeText(signal: String): String {
    return when (signalInt(signal)) {
        1 -> "긍정"
        -1 -> "주의"
        else -> "중립"
    }
}

internal fun regimeReasons(macro: Map<String, String>): List<RegimeReason> {
    val vixSignal = macro["VIX_Signal"].orEmpty()
    val yieldSignal = macro["Yield_Signal"].orEmpty()
    val spSignal = macro["SP500_Signal"].orEmpty()
    val creditSignal = macro["Credit_Signal"].orEmpty()
    val momentumSignal = macro["Momentum_Signal"].orEmpty()
    return listOf(
        RegimeReason(
            title = "공포 심리",
            value = "VIX ${macro["VIX"] ?: "-"}",
            signal = vixSignal,
            explanation = when (signalInt(vixSignal)) {
                1 -> "VIX가 20 아래면 시장 공포가 낮은 편이라 위험자산 선호에 긍정적으로 봅니다."
                -1 -> "VIX가 25 이상이면 시장 공포가 커진 구간이라 위험회피 신호로 봅니다."
                else -> "VIX가 중간 구간이라 강한 위험선호나 위험회피 어느 쪽으로도 보지 않습니다."
            }
        ),
        RegimeReason(
            title = "금리 환경",
            value = "10년-3개월 금리차 ${macro["Yield_Spread"] ?: "-"}",
            signal = yieldSignal,
            explanation = when (signalInt(yieldSignal)) {
                1 -> "장단기 금리차가 충분히 양수면 경기 확장 기대가 살아있다고 보고 긍정 신호를 줍니다."
                -1 -> "장단기 금리차가 역전되면 경기 둔화 우려가 커졌다고 보고 부정 신호를 줍니다."
                else -> "금리차가 애매한 구간이라 방향성 판단에는 중립으로 둡니다."
            }
        ),
        RegimeReason(
            title = "장기 추세",
            value = "200일선 대비 ${macro["SP500_vs_200MA"] ?: "-"}",
            signal = spSignal,
            explanation = when (signalInt(spSignal)) {
                1 -> "S&P 500이 200일 이동평균보다 3% 이상 위에 있으면 큰 추세가 살아있다고 봅니다."
                -1 -> "S&P 500이 200일 이동평균보다 3% 이상 아래면 하락 추세 위험을 크게 봅니다."
                else -> "지수가 장기 추세선 근처에 있어 추세 신호는 중립으로 둡니다."
            }
        ),
        RegimeReason(
            title = "신용 시장",
            value = macro["Credit_Conditions"] ?: "HYG-IEF 20일 상대수익 -",
            signal = creditSignal,
            explanation = when (signalInt(creditSignal)) {
                1 -> "하이일드 채권이 중기국채보다 강하면 신용위험을 감수하려는 수요가 있다고 봅니다."
                -1 -> "하이일드 채권이 국채보다 약하면 신용 스프레드 확대, 즉 위험회피 신호로 봅니다."
                else -> "신용시장 상대 흐름이 크지 않아 중립으로 처리합니다."
            }
        ),
        RegimeReason(
            title = "최근 흐름",
            value = "S&P 500 1개월 ${macro["Momentum_1M"] ?: "-"}",
            signal = momentumSignal,
            explanation = when (signalInt(momentumSignal)) {
                1 -> "최근 1개월 수익률이 +3%를 넘으면 단기 매수세가 강하다고 보고 긍정 신호를 줍니다."
                -1 -> "최근 1개월 수익률이 -3%보다 낮으면 단기 하락 압력이 크다고 봅니다."
                else -> "최근 수익률이 큰 방향성을 보이지 않아 중립으로 둡니다."
            }
        )
    )
}

internal fun factorWeightText(macro: Map<String, String>): String {
    val regime = normalizeRegime(macro["Regime"])
    val note = when (regime) {
        "RISK_ON" -> "현재는 모멘텀 팩터를 더 크게 보고, 가치와 퀄리티 비중은 조금 낮춥니다."
        "RISK_OFF" -> "현재는 방어력을 위해 가치와 퀄리티 팩터를 더 크게 보고, 모멘텀 비중은 낮춥니다."
        else -> "현재는 기본 배분에 가깝게 가치, 퀄리티, 모멘텀을 균형 있게 봅니다."
    }
    val usV = macro["US_V_Weight"] ?: "-"
    val usQ = macro["US_Q_Weight"] ?: "-"
    val usM = macro["US_M_Weight"] ?: "-"
    val krV = macro["KR_V_Weight"] ?: "-"
    val krQ = macro["KR_Q_Weight"] ?: "-"
    val krM = macro["KR_M_Weight"] ?: "-"
    return "$note\nUS: 가치 $usV · 퀄리티 $usQ · 모멘텀 $usM\nKR: 가치 $krV · 퀄리티 $krQ · 모멘텀 $krM"
}

@Composable
internal fun regimeColor(regime: String): Color {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> QuantPositive
        "RISK_OFF" -> QuantNegative
        else -> MaterialTheme.colorScheme.primary
    }
}
