package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning

fun homeSubtitle(market: String?, text: String): String {
    val marketLabel = if (market.equals(Market.KR.title, ignoreCase = true)) "KR" else "US"
    return "$marketLabel · ${text.ifBlank { "후보" }}"
}

fun portfolioHomeMetrics(stock: PortfolioStock, currency: String): List<HomeCardMetric> {
    return listOf(
        HomeCardMetric("Score", score(stock.totalScore)),
        HomeCardMetric("ROIC", pct(stock.roic, signed = false)),
        HomeCardMetric("성장", pct(stock.revGrowth)),
        HomeCardMetric("마진", pct(stock.grossMargin, signed = false)),
        HomeCardMetric("시총", cap(stock.marketCap, currency))
    )
}

fun portfolioHomeReason(stock: PortfolioStock): HomeCardReason {
    val expected = stock.expectedReturn
    val roic = stock.roic
    val growth = stock.revGrowth
    return when {
        expected != null && expected.isFinite() && expected > 0.0 ->
            HomeCardReason("기대수익", "모델 기준 ${pct(expected)} 후보입니다.", DetailTone.Primary)
        roic != null && roic.isFinite() && roic >= 0.15 ->
            HomeCardReason("퀄리티", "ROIC ${pct(roic, signed = false)}로 자본 효율이 좋습니다.", DetailTone.Positive)
        growth != null && growth.isFinite() && growth >= 0.15 ->
            HomeCardReason("성장", "매출 성장 ${pct(growth)}가 확인됩니다.", DetailTone.Positive)
        expected != null && expected.isFinite() && expected < 0.0 ->
            HomeCardReason("주의", "기대수익이 음수라 타이밍 확인이 필요합니다.", DetailTone.Warning)
        else ->
            HomeCardReason("확인", "상세에서 차트와 팩터 균형을 같이 보세요.", DetailTone.Neutral)
    }
}

fun portfolioHomeBasis(stock: PortfolioStock): String {
    val parts = buildList {
        add("기대수익")
        if (stock.totalScore?.isFinite() == true) add("종합점수")
        if (stock.roic?.isFinite() == true) add("ROIC")
        if (stock.revGrowth?.isFinite() == true) add("성장")
        if (stock.grossMargin?.isFinite() == true) add("마진")
    }.take(4)
    return "선정 기준 ${parts.joinToString(" · ")}"
}

fun smallCapHomeMetrics(stock: SmallCapStock, currency: String): List<HomeCardMetric> {
    return listOf(
        HomeCardMetric("시총", cap(stock.marketCap, currency)),
        HomeCardMetric("ROIC", pct(stock.roic, signed = false)),
        HomeCardMetric("성장", pct(stock.revGrowth)),
        HomeCardMetric("FCF", pct(stock.fcfMargin, signed = false)),
        HomeCardMetric("거래량", multipleText(stock.volumeSurge))
    )
}

fun smallCapHomeReason(stock: SmallCapStock): HomeCardReason {
    val score = stock.totalScore
    val accel = stock.revAccel
    val volume = stock.volumeSurge
    val debt = stock.debtEbitda
    return when {
        score != null && score.isFinite() && score >= 70.0 ->
            HomeCardReason("상위점수", "스몰캡 점수 ${"%.0f".format(score)}점으로 선별됐습니다.", DetailTone.Primary)
        accel != null && accel.isFinite() && accel > 0.0 ->
            HomeCardReason("성장가속", "매출 성장 가속 신호가 있습니다.", DetailTone.Positive)
        volume != null && volume.isFinite() && volume >= 1.5 ->
            HomeCardReason("거래량", "평소 대비 ${multipleText(volume)} 거래량입니다.", DetailTone.Primary)
        debt != null && debt.isFinite() && debt > 4.0 ->
            HomeCardReason("주의", "Debt/EBITDA 부담을 먼저 확인하세요.", DetailTone.Warning)
        else ->
            HomeCardReason("확인", "성장성, 현금흐름, 재무 리스크를 같이 보세요.", DetailTone.Neutral)
    }
}

fun smallCapHomeBasis(stock: SmallCapStock): String {
    val parts = buildList {
        add("총점")
        if (stock.marketCap?.isFinite() == true) add("시총")
        if (stock.revAccel?.isFinite() == true) add("성장가속")
        if (stock.fcfMargin?.isFinite() == true) add("FCF")
        if (stock.volumeSurge?.isFinite() == true) add("거래량")
    }.take(4)
    return "선정 기준 ${parts.joinToString(" · ")}"
}

fun earningsHomeMetrics(stock: EarningsStock): List<HomeCardMetric> {
    return listOf(
        HomeCardMetric("EPS", pct(stock.surprisePct)),
        HomeCardMetric("수익", pct(stock.returnSince)),
        HomeCardMetric("경과", daysText(stock.daysSince)),
        HomeCardMetric("거래량", multipleText(stock.volumeSurge)),
        HomeCardMetric("발표", compactDateText(stock.earningsDate))
    )
}

fun earningsHomeReason(stock: EarningsStock): HomeCardReason {
    val signal = stock.signalStrength
    val surprise = stock.surprisePct
    val returnSince = stock.returnSince
    val days = stock.daysSince
    return when {
        signal != null && signal.isFinite() && signal >= 1.0 ->
            HomeCardReason("강한시그널", "서프라이즈와 가격 반응이 함께 나왔습니다.", DetailTone.Primary)
        surprise != null && surprise.isFinite() && surprise > 0.0 ->
            HomeCardReason("서프라이즈", "EPS가 예상보다 ${pct(surprise)} 높았습니다.", DetailTone.Positive)
        returnSince != null && returnSince.isFinite() && returnSince > 0.0 ->
            HomeCardReason("가격반응", "발표 후 수익률 ${pct(returnSince)}입니다.", DetailTone.Positive)
        days != null && days.isFinite() && days <= 7.0 ->
            HomeCardReason("최근이벤트", "발표 후 ${days.toInt()}일째라 반응 확인 구간입니다.", DetailTone.Primary)
        else ->
            HomeCardReason("확인", "EPS, 수익률, 거래량 반응을 함께 보세요.", DetailTone.Neutral)
    }
}

fun earningsHomeBasis(stock: EarningsStock): String {
    val parts = buildList {
        add("EPS")
        if (stock.signalStrength?.isFinite() == true) add("Signal")
        if (stock.returnSince?.isFinite() == true) add("발표 후 수익률")
        if (stock.daysSince?.isFinite() == true) add("경과일")
        if (stock.volumeSurge?.isFinite() == true) add("거래량")
    }.take(4)
    return "선정 기준 ${parts.joinToString(" · ")}"
}

fun compactDateText(value: String?): String {
    val text = formattedUpdateTimestamp(value)
    return if (text == "-") "-" else text.take(10)
}

fun multipleText(value: Double?): String {
    return value?.takeIf { it.isFinite() }?.let { "x%.1f".format(it) } ?: "-"
}

fun daysText(value: Double?): String {
    return value?.takeIf { it.isFinite() }?.let { "${it.toInt()}일" } ?: "-"
}

@Composable
internal fun homeToneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
}
