package com.qubit.quantbridge

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun portfolioKpis(stock: PortfolioStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.weight?.let { "비중" to pct(it, signed = false) },
        stock.expectedReturn?.let { "기대" to pct(it) },
        stock.revGrowth?.let { "성장" to pct(it) }
    )
}

internal fun smallCapKpis(stock: SmallCapStock): List<Pair<String, String>> {
    return listOfNotNull(
        stock.revGrowth?.let { "성장" to pct(it) },
        stock.fcfMargin?.let { "FCF" to pct(it, signed = false) },
        stock.volumeSurge?.let { "거래량" to "x%.1f".format(it) }
    )
}

internal fun bestScoredValue(stock: ScoredStock): Double? {
    return stock.combinedScore ?: stock.finalScore ?: stock.totalScore ?: stock.scoreNeutral
}

internal fun holdingDetail(holding: DetailHolding): DetailRequest {
    val ticker = fallbackHoldingTicker(holding)
    val market = holdingMarket(ticker, holding.name)
    val currency = marketCurrency(ticker, market)
    val displayName = holding.name.ifBlank { ticker }
    return DetailRequest(
        ticker = ticker,
        name = displayName,
        currency = currency,
        market = market,
        sections = listOf(
            DetailSection(
                "ETF 구성종목",
                listOf(
                    DetailMetric("ETF 내 비중", pct(holding.weight, signed = false), DetailTone.Primary),
                    DetailMetric("시장", market)
                )
            )
        ),
        signals = listOf(
            DetailSignal(
                "ETF 구성종목",
                "$displayName 종목이 이 ETF에 ${pct(holding.weight, signed = false)} 비중으로 포함되어 있습니다.",
                DetailTone.Primary
            )
        ),
        factors = emptyList()
    )
}

internal fun scoredDetail(stock: ScoredStock): DetailRequest {
    val currency = marketCurrency(stock.ticker, stock.market)
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "팩터 점수",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("최종 점수", bestScoredValue(stock)?.let { "%.3f".format(it) } ?: "-", scoreTone(bestScoredValue(stock), 0.7, 0.45)),
                    DetailMetric("AI 보정", num(stock.mlScore), scoreTone(stock.mlScore, 0.7, 0.45)),
                    DetailMetric("Value", num(stock.valueScore), scoreTone(stock.valueScore, 0.7, 0.45)),
                    DetailMetric("Quality", num(stock.qualityScore), scoreTone(stock.qualityScore, 0.7, 0.45)),
                    DetailMetric("Momentum", num(stock.momentumScore), scoreTone(stock.momentumScore, 0.7, 0.45)),
                    DetailMetric("중립화 점수", num(stock.scoreNeutral)),
                    DetailMetric("기업품질", num(stock.businessQualityScore), scoreTone(stock.businessQualityScore, 0.7, 0.45)),
                    DetailMetric("투자가능", num(stock.investabilityScore), scoreTone(stock.investabilityScore, 0.7, 0.45)),
                    DetailMetric("품질분류", cleanQualityText(stock.qualityCategory) ?: "-"),
                    DetailMetric("데이터 신뢰도", num(stock.qualityDataConfidence), scoreTone(stock.qualityDataConfidence, 0.8, 0.5))
                )
            ),
            DetailSection(
                "기초 지표",
                listOf(
                    DetailMetric("ROIC", pct(stock.roic, signed = false), ratioTone(stock.roic, 0.15, 0.05)),
                    DetailMetric("매출성장", pct(stock.revGrowth), ratioTone(stock.revGrowth, 0.15, 0.0)),
                    DetailMetric("매출총이익률", pct(stock.grossMargin, signed = false), ratioTone(stock.grossMargin, 0.40, 0.20)),
                    DetailMetric("FCF 마진", pct(stock.fcfMargin, signed = false), ratioTone(stock.fcfMargin, 0.10, 0.0)),
                    DetailMetric("Debt/EBITDA", stock.debtEbitda?.let { "%.2fx".format(it) } ?: "-", inverseTone(stock.debtEbitda, 2.0, 4.0)),
                    DetailMetric("PEG", stock.peg?.let { "%.2f".format(it) } ?: "-")
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: if (currency == "KRW") "KR" else "US"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.valueScore ?: 0.0) >= 0.7) DetailSignal("가치 팩터 우수", "Value 점수가 높아 저평가 매력이 상대적으로 큽니다.", DetailTone.Positive) else null,
            if ((stock.qualityScore ?: 0.0) >= 0.7) DetailSignal("퀄리티 팩터 우수", "수익성과 재무 품질이 상대적으로 강합니다.", DetailTone.Positive) else null,
            if ((stock.momentumScore ?: 0.0) >= 0.7) DetailSignal("모멘텀 팩터 우수", "가격 또는 이익 모멘텀 신호가 강합니다.", DetailTone.Primary) else null,
            if ((stock.mlScore ?: 0.0) >= 0.7) DetailSignal("AI 보정 우호", "예측 보정 점수가 같은 유니버스 안에서 상위권입니다.", DetailTone.Primary) else null,
            if ((stock.investabilityScore ?: 0.0) >= 0.7) DetailSignal("투자가능성 우수", "기업 품질, 밸류에이션, 타이밍을 함께 반영한 전문가형 품질 점수가 높습니다.", DetailTone.Positive) else null,
            cleanQualityText(stock.qualityRedFlags)?.let { DetailSignal("품질 플래그 확인", it, DetailTone.Warning) }
        ).ifEmpty {
            listOf(DetailSignal("팩터 점수 확인", "${stock.name}의 V/Q/M 팩터를 함께 비교해보세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("Value", scaleScore(stock.valueScore, 1.0)),
            FactorScore("Quality", scaleScore(stock.qualityScore, 1.0)),
            FactorScore("Momentum", scaleScore(stock.momentumScore, 1.0)),
            FactorScore("기업품질", scaleScore(stock.businessQualityScore, 1.0)),
            FactorScore("투자가능", scaleScore(stock.investabilityScore, 1.0)),
            FactorScore("ML", scaleScore(stock.mlScore, 1.0)),
            FactorScore("ROIC", scaleRatio(stock.roic, 0.35)),
            FactorScore("성장", scaleSignedRatio(stock.revGrowth, 0.50)),
            FactorScore("마진", scaleRatio(stock.grossMargin, 0.80))
        )
    )
}

internal fun cleanQualityText(value: String?): String? {
    val text = value.orEmpty().trim()
    return text.takeIf { it.isNotEmpty() && it != "-" }
}

fun portfolioDetail(stock: PortfolioStock, currency: String): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "퀀트 점수",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("종합 점수", score(stock.totalScore), scoreTone(stock.totalScore, 0.7, 0.45)),
                    DetailMetric("비중", pct(stock.weight, signed = false), DetailTone.Primary),
                    DetailMetric("기대수익률", pct(stock.expectedReturn), returnTone(stock.expectedReturn))
                )
            ),
            DetailSection(
                "퀄리티 / 성장",
                listOf(
                    DetailMetric("ROIC", pct(stock.roic, signed = false), ratioTone(stock.roic, 0.15, 0.05)),
                    DetailMetric("매출성장", pct(stock.revGrowth), ratioTone(stock.revGrowth, 0.15, 0.0)),
                    DetailMetric("매출총이익률", pct(stock.grossMargin, signed = false), ratioTone(stock.grossMargin, 0.40, 0.20)),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시장", stock.market ?: if (currency == "KRW") "KR" else "US"),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-"),
                    DetailMetric("업데이트", formattedUpdateTimestamp(stock.lastUpdated))
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.totalScore ?: 0.0) >= 0.7) DetailSignal("상위 팩터 점수", "종합 점수가 높아 현재 분석 후보군에서 우선순위가 높습니다.", DetailTone.Primary) else null,
            if ((stock.roic ?: 0.0) >= 0.15) DetailSignal("높은 ROIC", "자본 효율성이 좋아 퀄리티 팩터에 긍정적입니다.", DetailTone.Positive) else null,
            if ((stock.revGrowth ?: 0.0) >= 0.15) DetailSignal("매출 성장", "매출 성장률이 높아 성장 모멘텀이 확인됩니다.", DetailTone.Positive) else null,
            if ((stock.expectedReturn ?: 0.0) < 0.0) DetailSignal("기대수익률 주의", "모델 기대수익률이 음수라 진입 타이밍을 확인해야 합니다.", DetailTone.Warning) else null
        ).ifEmpty {
            listOf(DetailSignal("추가 확인 필요", "${stock.name}의 핵심 팩터가 중립적입니다. 차트와 상세 지표를 함께 확인하세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("점수", scaleScore(stock.totalScore, 1.0)),
            FactorScore("ROIC", scaleRatio(stock.roic, 0.35)),
            FactorScore("성장", scaleRatio(stock.revGrowth, 0.50)),
            FactorScore("마진", scaleRatio(stock.grossMargin, 0.80)),
            FactorScore("기대수익", scaleSignedRatio(stock.expectedReturn, 0.25)),
            FactorScore("비중", scaleRatio(stock.weight, 0.15))
        )
    )
}

fun smallCapDetail(stock: SmallCapStock, currency: String): DetailRequest {
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = stock.market,
        sections = listOf(
            DetailSection(
                "스몰캡 점수",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("종합 점수", stock.totalScore?.let { "%.0f".format(it) } ?: "-", scoreTone(stock.totalScore, 70.0, 45.0)),
                    DetailMetric("소형주 보너스", stock.smallCapBonus?.let { "%.1f".format(it) } ?: "-"),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            ),
            DetailSection(
                "성장 / 수익성",
                listOf(
                    DetailMetric("매출성장", pct(stock.revGrowth), ratioTone(stock.revGrowth, 0.20, 0.0)),
                    DetailMetric("성장가속", pct(stock.revAccel), ratioTone(stock.revAccel, 0.0, -0.05)),
                    DetailMetric("ROIC", pct(stock.roic, signed = false), ratioTone(stock.roic, 0.15, 0.05)),
                    DetailMetric("매출총이익률", pct(stock.grossMargin, signed = false), ratioTone(stock.grossMargin, 0.40, 0.20))
                )
            ),
            DetailSection(
                "현금흐름 / 리스크",
                listOf(
                    DetailMetric("FCF 마진", pct(stock.fcfMargin, signed = false), ratioTone(stock.fcfMargin, 0.10, 0.0)),
                    DetailMetric("Debt/EBITDA", stock.debtEbitda?.let { "%.2fx".format(it) } ?: "-", inverseTone(stock.debtEbitda, 2.0, 4.0)),
                    DetailMetric("거래량 서지", stock.volumeSurge?.let { "x%.1f".format(it) } ?: "-", scoreTone(stock.volumeSurge, 1.5, 1.0)),
                    DetailMetric("업데이트", formattedUpdateTimestamp(stock.lastUpdated))
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.totalScore ?: 0.0) >= 70.0) DetailSignal("SmallCap 상위 점수", "소형주 스캐너 기준으로 종합 매력이 높습니다.", DetailTone.Primary) else null,
            if ((stock.revAccel ?: 0.0) > 0.0) DetailSignal("성장 가속", "매출 성장의 가속 신호가 있어 추가 관찰 가치가 있습니다.", DetailTone.Positive) else null,
            if ((stock.volumeSurge ?: 0.0) >= 1.5) DetailSignal("거래량 증가", "평소보다 거래량이 커져 시장 관심이 붙고 있습니다.", DetailTone.Primary) else null,
            if ((stock.debtEbitda ?: 0.0) > 4.0) DetailSignal("부채 부담", "Debt/EBITDA가 높아 재무 리스크 확인이 필요합니다.", DetailTone.Warning) else null
        ).ifEmpty {
            listOf(DetailSignal("추가 확인 필요", "${stock.name}의 핵심 팩터가 중립적입니다. 차트와 상세 지표를 함께 확인하세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("수익성", scaleRatio(stock.roic, 0.35)),
            FactorScore("성장성", scaleRatio(stock.revGrowth, 0.60)),
            FactorScore("성장가속", scaleSignedRatio(stock.revAccel, 0.25)),
            FactorScore("현금창출", scaleRatio(stock.fcfMargin, 0.30)),
            FactorScore("마진", scaleRatio(stock.grossMargin, 0.80)),
            FactorScore("재무건전성", scaleInverse(stock.debtEbitda, 5.0))
        )
    )
}

fun earningsDetail(stock: EarningsStock): DetailRequest {
    val currency = marketCurrency(stock.ticker, null)
    val market = if (currency == "KRW") "KR" else "US"
    return DetailRequest(
        ticker = stock.ticker,
        name = stock.name,
        currency = currency,
        market = market,
        sections = listOf(
            DetailSection(
                "실적 모멘텀",
                listOf(
                    DetailMetric("랭킹", stock.rank?.toString() ?: "-"),
                    DetailMetric("시그널", stock.signalStrength?.let { "%.2f".format(it) } ?: "-", scoreTone(stock.signalStrength, 1.0, 0.0)),
                    DetailMetric("EPS 서프라이즈", pct(stock.surprisePct), returnTone(stock.surprisePct)),
                    DetailMetric("발표 후 수익", pct(stock.returnSince), returnTone(stock.returnSince))
                )
            ),
            DetailSection(
                "이벤트 정보",
                listOf(
                    DetailMetric("발표일", stock.earningsDate ?: "-"),
                    DetailMetric("경과일", stock.daysSince?.let { "${it.toInt()}일" } ?: "-", inverseTone(stock.daysSince, 7.0, 30.0)),
                    DetailMetric("거래량 서지", stock.volumeSurge?.let { "x%.1f".format(it) } ?: "-", scoreTone(stock.volumeSurge, 1.5, 1.0)),
                    DetailMetric("시가총액", cap(stock.marketCap, currency))
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시장", market),
                    DetailMetric("섹터", stock.sector?.let { portfolioIndustryLabel(stock.ticker, stock.name, it) } ?: "-")
                )
            )
        ),
        signals = listOfNotNull(
            if ((stock.surprisePct ?: 0.0) > 0.0) DetailSignal("실적 서프라이즈", "예상보다 좋은 실적이 확인되어 단기 모멘텀에 긍정적입니다.", DetailTone.Positive) else null,
            if ((stock.returnSince ?: 0.0) > 0.0) DetailSignal("발표 후 주가 반응", "실적 발표 이후 수익률이 플러스로 유지되고 있습니다.", DetailTone.Positive) else null,
            if ((stock.signalStrength ?: 0.0) >= 1.0) DetailSignal("강한 시그널", "서프라이즈와 가격 반응이 함께 나타난 후보입니다.", DetailTone.Primary) else null,
            if ((stock.daysSince ?: 999.0) <= 7.0) DetailSignal("최근 이벤트", "실적 발표가 최근에 발생해 정보 반영 과정을 볼 만합니다.", DetailTone.Primary) else null
        ).ifEmpty {
            listOf(DetailSignal("추가 확인 필요", "${stock.name}의 실적 이벤트 신호가 중립적입니다. 가격 반응과 상세 지표를 함께 확인하세요.", DetailTone.Neutral))
        },
        factors = listOf(
            FactorScore("시그널", scaleRatio(stock.signalStrength, 2.0)),
            FactorScore("서프라이즈", scaleSignedRatio(stock.surprisePct, 0.25)),
            FactorScore("수익반응", scaleSignedRatio(stock.returnSince, 0.25)),
            FactorScore("신선도", scaleInverse(stock.daysSince, 45.0)),
            FactorScore("거래량", scaleRatio(stock.volumeSurge, 3.0)),
            FactorScore("규모", scaleRatio(stock.marketCap?.let { min(it / 1e11, 1.0) }, 1.0))
        )
    )
}

fun earningsCalendarDetail(item: EarningsCalendarItem): DetailRequest {
    val currency = marketCurrency(item.ticker, item.market)
    return DetailRequest(
        ticker = item.ticker,
        name = item.name,
        currency = currency,
        market = item.market,
        sections = listOf(
            DetailSection(
                "어닝 캘린더",
                listOf(
                    DetailMetric("예정일", formatEarningsCalendarDate(item.nextEarningsDate), DetailTone.Primary),
                    DetailMetric("남은 기간", daysUntilText(item.daysUntil), if ((item.daysUntil ?: 99) <= 7) DetailTone.Warning else DetailTone.Neutral),
                    DetailMetric("시가총액", earningsCalendarValueText(item)),
                    DetailMetric("시장", item.market)
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("티커", item.ticker),
                    DetailMetric("섹터", portfolioIndustryLabel(item.ticker, item.name, item.sector))
                )
            )
        ),
        signals = listOf(
            DetailSignal(
                "실적 발표 예정",
                "${item.name}의 다음 실적 발표가 ${formatEarningsCalendarDate(item.nextEarningsDate)}에 예정되어 있습니다.",
                DetailTone.Primary
            ),
            DetailSignal(
                "체크 포인트",
                "발표 전에는 컨센서스, 가이던스, 최근 가격 반응을 함께 확인하는 구간입니다.",
                DetailTone.Neutral
            )
        ),
        factors = listOf(
            FactorScore("근접도", scaleInverse(item.daysUntil?.toDouble(), 7.0)),
            FactorScore("규모", scaleRatio(item.marketCap?.let { min(it / 1e11, 1.0) }, 1.0))
        )
    )
}

fun watchItem(ticker: String, name: String, market: String?, currency: String, note: String): WatchlistItem {
    return WatchlistItem(
        ticker = ticker,
        name = name,
        market = market ?: if (currency == "KRW") "KR" else "US",
        currency = currency,
        note = note,
        addedAt = ""
    )
}
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
internal fun toneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
}

internal fun marketMoveColor(value: Double): Color {
    return if (value >= 0.0) QuantPositive else QuantNegative
}

internal fun ratioTone(value: Double?, good: Double, caution: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v >= good -> DetailTone.Positive
        v >= caution -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

fun returnTone(value: Double?): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v > 0.0 -> DetailTone.Positive
        v < 0.0 -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

internal fun scoreTone(value: Double?, good: Double, caution: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v >= good -> DetailTone.Positive
        v >= caution -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

internal fun inverseTone(value: Double?, goodMax: Double, cautionMax: Double): DetailTone {
    val v = value ?: return DetailTone.Neutral
    if (!v.isFinite()) return DetailTone.Neutral
    return when {
        v <= goodMax -> DetailTone.Positive
        v <= cautionMax -> DetailTone.Warning
        else -> DetailTone.Negative
    }
}

internal fun researchQualityDiagnosticInfo(quality: ResearchQuality?): DiagnosticInfo {
    return DiagnosticInfo(
        title = "리서치 품질",
        status = quality?.overallStatus ?: "-",
        summary = "팩터 신호가 실제 투자 엔진에 들어갈 만큼 검증됐는지 요약합니다.",
        details = listOf(
            "Status는 전체 factor quality gate 결과입니다. FAIL은 하나 이상의 핵심 신호가 기준을 통과하지 못했다는 뜻입니다.",
            "경고는 IC, 양수 IC 비율, 샘플 수, 프록시 사용 같은 조건에서 주의가 필요한 항목 수입니다.",
            "운영 가능은 실제 스코어링과 리밸런싱에 넣어도 된다고 판정된 팩터 수입니다.",
            "Proxy는 실제 원천 데이터 대신 대체 데이터로 계산한 근거 수입니다. 값이 높을수록 해석 보수성이 필요합니다."
        )
    )
}

internal fun mlBlendDiagnosticInfo(report: MLBlendReport?): DiagnosticInfo {
    val latest = report?.latest
    return DiagnosticInfo(
        title = "AI 보정",
        status = report?.status ?: "-",
        summary = "AI 보정 점수를 기본 점수에 얼마나 반영했는지와 그 근거를 보여줍니다.",
        details = listOf(
            "AI 비중은 최근 예측력에 따라 자동으로 낮아지거나 높아집니다. 예측력이 약하거나 음수이면 영향은 제한됩니다.",
            "기본 점수 비중은 기존 퀄리티, 밸류, 모멘텀 중심 점수의 비중입니다. 현재 기준 컬럼은 ${latest?.factorScoreColumn ?: "-"}입니다.",
            "Rank IC는 예측 순위와 이후 수익률 순위의 관계입니다. 양수이고 충분히 커야 독립적인 예측력으로 볼 수 있습니다.",
            "독립성은 AI 보정 점수와 기본 점수의 상관입니다. 너무 높으면 기존 점수를 반복할 가능성이 큽니다.",
            "Top5는 현재 블렌딩 기준 상위 후보입니다. 종목 선택은 리스크와 리밸런싱 결과까지 함께 확인해야 합니다."
        )
    )
}

internal fun policyAdjustedRankingDiagnosticInfo(rankings: List<PolicyAdjustedRanking>): DiagnosticInfo {
    val markets = rankings.joinToString(" / ") { it.market }.ifBlank { "-" }
    val rows = rankings.sumOf { it.items.size }
    val summaries = rankings.mapNotNull { it.summary }
    val ready = summaries.count { it.productionReady == true }
    return DiagnosticInfo(
        title = "정책 섀도 랭킹",
        status = markets,
        summary = "팩터 정책을 실제 점수 테이블에 적용하기 전에 순위가 어떻게 바뀌는지 확인합니다.",
        details = listOf(
            "현재 표시된 종목 수는 ${rows}개이며, 시장별 상위 상승/하락 종목을 분리해서 보여줍니다.",
            "Ready 시장은 ${ready}/${summaries.size}개입니다. Hold는 운영 점수에 바로 반영하지 않고 관찰해야 한다는 뜻입니다.",
            "Rank Change가 양수이면 정책 적용 후 순위가 올라간 종목이고, 음수이면 내려간 종목입니다.",
            "이 랭킹은 shadow 결과라서 기존 추천 순위는 바꾸지 않습니다. 검증이 쌓이면 운영 정책으로 승격할 후보입니다."
        )
    )
}

internal fun opsHealthDiagnosticInfo(ops: OpsHealth?): DiagnosticInfo {
    return DiagnosticInfo(
        title = "운영 상태",
        status = ops?.status ?: "-",
        summary = "자동 실행에 필요한 API, 데이터 freshness, 산출물 생성 상태를 점검한 결과입니다.",
        details = listOf(
            "Status가 OK이면 핵심 체크가 통과했고, DEGRADED이면 일부 데이터나 산출물이 늦거나 불완전하다는 뜻입니다.",
            "체크 수는 API 응답, 파일 생성, 최신 날짜, 데이터 품질 같은 운영 점검 항목의 개수입니다.",
            "생성 시간은 서버가 이 진단 결과를 만든 시각입니다. 오래된 시간이면 앱보다 파이프라인 실행 상태를 먼저 확인해야 합니다.",
            "운영 상태가 나빠도 앱이 열릴 수는 있지만, 스코어와 백테스트 해석은 보수적으로 봐야 합니다."
        )
    )
}

internal fun detailGlossaryKeys(request: DetailRequest, detail: StockDetail?): List<String> {
    val labels = buildList {
        addAll(request.sections.flatMap { section -> section.metrics.map { it.label } })
        addAll(request.factors.map { it.label })
        if (request.isEtfDetail()) {
            addAll(listOf("MDD", "리스크 기여도"))
            return@buildList
        }
        detail?.info?.let { info ->
            if (info.peRatio != null) add("PER")
            if (info.forwardPe != null) add("Forward PER")
            if (info.priceToBook != null) add("P/B")
            if (info.priceToSales != null) add("P/S")
            if (info.beta != null) add("베타")
            if (info.returnOnEquity != null) add("ROE")
            if (info.freeCashflow != null) add("FCF")
            if (info.debtToEquity != null) add("Debt/Equity")
        }
        addAll(listOf("PER", "ROIC", "FCF", "MDD", "리스크 기여도", "AI 보정"))
    }
    return labels.mapNotNull { glossaryKeyForLabel(it) }.distinct().take(10)
}

internal fun glossaryInfo(rawKey: String): DiagnosticInfo? {
    return when (glossaryKeyForLabel(rawKey)) {
        "per" -> DiagnosticInfo(
            "PER",
            "밸류에이션",
            "주가가 순이익 대비 얼마나 비싼지 보는 대표 지표입니다.",
            listOf(
                "PER = 시가총액 / 순이익입니다. 같은 이익을 내는 기업이라면 PER이 낮을수록 가격 부담이 낮다고 해석합니다.",
                "Trailing PER은 이미 발표된 이익 기준이고, Forward PER은 앞으로 예상되는 이익 기준입니다.",
                "낮은 PER이 항상 좋은 것은 아닙니다. 이익 감소, 경기 민감도, 회계상 일회성 이익 때문에 낮아 보일 수 있습니다."
            )
        )
        "pbr" -> DiagnosticInfo(
            "PBR",
            "밸류에이션",
            "주가가 장부상 순자산 대비 몇 배에 거래되는지 보는 지표입니다.",
            listOf(
                "PBR = 시가총액 / 자기자본입니다. 자산 가치가 중요한 금융, 지주, 전통 제조업에서 특히 자주 봅니다.",
                "PBR이 낮아도 ROE가 낮으면 자본을 효율적으로 쓰지 못한다는 뜻일 수 있습니다.",
                "앱에서는 P/B와 PBR을 같은 의미로 사용합니다."
            )
        )
        "ps" -> DiagnosticInfo(
            "P/S",
            "밸류에이션",
            "주가가 매출 대비 얼마나 비싼지 보는 지표입니다.",
            listOf(
                "P/S = 시가총액 / 매출입니다. 아직 이익이 작거나 변동성이 큰 성장 기업을 볼 때 보조 지표로 씁니다.",
                "매출은 커도 마진이 낮으면 주주에게 남는 이익이 적을 수 있어 수익성 지표와 함께 봐야 합니다."
            )
        )
        "roe" -> DiagnosticInfo(
            "ROE",
            "수익성",
            "자기자본으로 얼마나 많은 순이익을 만들었는지 보여줍니다.",
            listOf(
                "ROE = 순이익 / 자기자본입니다. 높을수록 주주 자본을 효율적으로 쓴다고 볼 수 있습니다.",
                "다만 부채를 크게 쓰면 ROE가 높아질 수 있으니 Debt/Equity와 함께 확인해야 합니다."
            )
        )
        "roic" -> DiagnosticInfo(
            "ROIC",
            "퀄리티",
            "사업에 투입한 자본 대비 영업이익 창출력이 얼마나 좋은지 보는 지표입니다.",
            listOf(
                "ROIC가 높으면 같은 돈을 넣어도 더 많은 영업성과를 만드는 기업일 가능성이 큽니다.",
                "큐빗에서는 퀄리티 팩터의 핵심 지표 중 하나로 봅니다.",
                "업종별 자본 구조가 달라서 같은 업종 안에서 비교하는 것이 더 안전합니다."
            )
        )
        "fcf" -> DiagnosticInfo(
            "FCF",
            "현금흐름",
            "기업이 영업과 투자를 거친 뒤 실제로 남기는 자유현금흐름입니다.",
            listOf(
                "FCF는 배당, 자사주, 부채 상환, 재투자에 쓸 수 있는 현금 여력을 보여줍니다.",
                "회계상 이익은 좋아도 FCF가 계속 약하면 이익의 질을 보수적으로 봐야 합니다.",
                "FCF 마진은 매출 대비 자유현금흐름 비율입니다."
            )
        )
        "debtEquity" -> DiagnosticInfo(
            "Debt/Equity",
            "재무 리스크",
            "자기자본 대비 부채 부담이 어느 정도인지 보는 지표입니다.",
            listOf(
                "값이 높을수록 레버리지 부담이 크고 금리, 경기 둔화에 민감할 수 있습니다.",
                "업종별 정상 범위가 크게 다르므로 금융, 유틸리티, 제조업을 같은 기준으로 비교하면 위험합니다."
            )
        )
        "debtEbitda" -> DiagnosticInfo(
            "Debt/EBITDA",
            "재무 리스크",
            "영업현금 창출력 대비 부채가 얼마나 무거운지 보는 지표입니다.",
            listOf(
                "대략 몇 년치 EBITDA로 부채를 갚을 수 있는지 보는 감각에 가깝습니다.",
                "값이 높을수록 재무 부담이 크고 리밸런싱이나 스몰캡 판단에서 주의 신호로 봅니다."
            )
        )
        "ebitda" -> DiagnosticInfo(
            "EBITDA",
            "수익성",
            "이자, 세금, 감가상각 전 이익으로 영업 체력의 거친 근사치입니다.",
            listOf(
                "설비투자와 감가상각 영향이 큰 기업을 비교할 때 보조적으로 씁니다.",
                "실제 현금흐름과 같지는 않으므로 FCF와 같이 확인하는 편이 좋습니다."
            )
        )
        "growth" -> DiagnosticInfo(
            "매출 성장",
            "성장성",
            "최근 매출이 이전 기간 대비 얼마나 늘었는지 보여줍니다.",
            listOf(
                "양수 성장은 제품 수요나 시장 점유율 확대를 시사할 수 있습니다.",
                "성장률만 높고 마진이 낮으면 수익성 없는 성장일 수 있어 마진과 함께 봐야 합니다."
            )
        )
        "grossMargin" -> DiagnosticInfo(
            "매출총이익률",
            "수익성",
            "매출에서 원가를 뺀 뒤 남는 비율입니다.",
            listOf(
                "높을수록 가격 결정력, 원가 통제력, 제품 경쟁력이 좋을 가능성이 있습니다.",
                "업종 차이가 매우 커서 같은 산업 안에서 비교하는 것이 중요합니다."
            )
        )
        "operatingMargin" -> DiagnosticInfo(
            "영업이익률",
            "수익성",
            "본업에서 매출 대비 얼마나 이익을 남기는지 보여줍니다.",
            listOf(
                "영업이익률이 높고 안정적이면 사업 모델의 질이 좋다고 볼 수 있습니다.",
                "일회성 비용이나 경기 사이클 때문에 단기적으로 흔들릴 수 있습니다."
            )
        )
        "beta" -> DiagnosticInfo(
            "베타",
            "시장 민감도",
            "종목이 시장 전체 움직임에 얼마나 민감한지 나타냅니다.",
            listOf(
                "베타가 1보다 크면 시장보다 더 크게 움직이는 경향이 있고, 1보다 작으면 상대적으로 방어적입니다.",
                "과거 가격으로 계산한 값이라 미래 변동성을 보장하지는 않습니다."
            )
        )
        "marketCap" -> DiagnosticInfo(
            "시가총액",
            "기업 규모",
            "주식시장이 평가하는 기업 전체 가치입니다.",
            listOf(
                "시가총액 = 주가 × 발행주식수입니다.",
                "대형주는 안정성과 유동성이 좋고, 소형주는 성장 여지와 변동성이 함께 커지는 경우가 많습니다."
            )
        )
        "expectedReturn" -> DiagnosticInfo(
            "기대수익률",
            "모델 전망",
            "현재 팩터와 과거 학습 결과를 바탕으로 모델이 추정한 기대 수익 신호입니다.",
            listOf(
                "실제 확정 수익률이 아니라 종목 간 우선순위를 정하기 위한 모델 출력입니다.",
                "리스크, 거래비용, 리밸런싱 제약과 함께 봐야 하며 단독 매수 신호로 쓰면 안 됩니다."
            )
        )
        "weight" -> DiagnosticInfo(
            "비중",
            "분석",
            "모델 분석에서 해당 종목에 배분된 기준 비중입니다.",
            listOf(
                "비중이 높을수록 수익과 손실에 미치는 영향이 커집니다.",
                "좋은 종목이어도 리스크 기여도가 너무 크면 비중을 낮추는 판단이 필요할 수 있습니다."
            )
        )
        "volatility" -> DiagnosticInfo(
            "연간 변동성",
            "리스크",
            "일별 수익률의 흔들림을 연율화한 위험 지표입니다.",
            listOf(
                "값이 높을수록 가격이 크게 출렁이는 종목입니다.",
                "수익률이 높아도 변동성이 지나치게 크면 분석 기준 안정성을 해칠 수 있습니다."
            )
        )
        "mdd" -> DiagnosticInfo(
            "MDD",
            "리스크",
            "고점에서 저점까지 가장 크게 빠진 낙폭입니다.",
            listOf(
                "Maximum Drawdown의 약자로, 손실을 견뎌야 하는 최대 구간을 보여줍니다.",
                "수익률이 좋아도 MDD가 크면 실제 보유 난이도는 높을 수 있습니다."
            )
        )
        "riskContribution" -> DiagnosticInfo(
            "리스크 기여도",
            "분석 리스크",
            "해당 종목이나 섹터가 모델 기준 전체 변동성에 얼마나 기여하는지 보여줍니다.",
            listOf(
                "비중이 작아도 변동성과 상관관계가 높으면 리스크 기여도는 커질 수 있습니다.",
                "분석 결과를 단순 비중이 아니라 실제 위험 기준으로 점검할 때 중요합니다."
            )
        )
        "rankIc" -> DiagnosticInfo(
            "Rank IC",
            "리서치 검증",
            "모델 순위와 이후 수익률 순위가 얼마나 같은 방향으로 움직였는지 보는 검증 지표입니다.",
            listOf(
                "양수이면 점수가 높은 종목이 이후에도 상대적으로 좋은 성과를 냈다는 뜻입니다.",
                "샘플 수가 적거나 기간이 짧으면 우연일 수 있어 품질 게이트와 함께 봐야 합니다."
            )
        )
        "mlScore" -> DiagnosticInfo(
            "AI 보정",
            "AI 보정",
            "예측 모델이 종목의 상대 매력을 0~1 범위로 평가한 보정 점수입니다.",
            listOf(
                "높을수록 모델이 같은 유니버스 안에서 더 우호적으로 본 후보입니다.",
                "AI 보정은 기존 Value, Quality, Momentum 점수를 보완하는 역할이며 Rank IC가 약하면 영향력이 줄어듭니다."
            )
        )
        "factorScore" -> DiagnosticInfo(
            "팩터 점수",
            "스코어링",
            "Value, Quality, Momentum 같은 투자 팩터를 정규화해 종목 간 비교가 가능하게 만든 점수입니다.",
            listOf(
                "값이 높을수록 해당 팩터 관점에서 상대적으로 매력적이라는 뜻입니다.",
                "팩터 하나만 높다고 충분하지 않고, 여러 팩터의 균형과 리스크를 함께 봐야 합니다."
            )
        )
        "epsSurprise" -> DiagnosticInfo(
            "EPS 서프라이즈",
            "실적 모멘텀",
            "실제 주당순이익이 시장 예상치를 얼마나 웃돌았는지 보는 지표입니다.",
            listOf(
                "양수이면 예상보다 실적이 좋았다는 뜻이고 단기 가격 반응의 원인이 될 수 있습니다.",
                "이미 주가에 반영됐을 수 있으므로 발표 후 수익률과 거래량을 같이 봐야 합니다."
            )
        )
        "volumeSurge" -> DiagnosticInfo(
            "거래량 서지",
            "수급",
            "평소 대비 거래량이 얼마나 늘었는지 보여주는 관심도 지표입니다.",
            listOf(
                "거래량 증가는 정보 반영이나 기관/외국인 수급 변화 가능성을 시사합니다.",
                "가격 상승 없이 거래량만 늘면 매물 출회일 수도 있어 방향성을 함께 확인해야 합니다."
            )
        )
        else -> null
    }
}

internal fun glossaryKeyForLabel(label: String?): String? {
    val raw = label.orEmpty().trim()
    if (raw.isBlank()) return null
    val lower = raw.lowercase(Locale.US)
    val compact = lower.replace(" ", "").replace("_", "").replace("-", "")
    return when {
        "forwardper" in compact -> "per"
        "trailingper" in compact -> "per"
        compact == "per" -> "per"
        compact == "pbr" || compact == "p/b" || compact == "pb" -> "pbr"
        compact == "ps" || compact == "p/s" -> "ps"
        compact == "roe" -> "roe"
        compact == "roic" -> "roic"
        compact == "fcf" || "fcf마진" in compact -> "fcf"
        compact == "debt/equity" || "debtequity" in compact -> "debtEquity"
        compact == "debt/ebitda" || "debtebitda" in compact -> "debtEbitda"
        "ebitda" in compact -> "ebitda"
        "매출성장" in compact || "성장가속" in compact -> "growth"
        "매출총이익률" in compact || "grossmargin" in compact -> "grossMargin"
        "영업이익률" in compact || "operatingmargin" in compact -> "operatingMargin"
        "베타" in compact || compact == "beta" -> "beta"
        "시가총액" in compact || "marketcap" in compact -> "marketCap"
        "기대수익" in compact || "expectedreturn" in compact -> "expectedReturn"
        compact == "비중" || "portfolioweight" in compact -> "weight"
        "변동성" in compact || "volatility" in compact -> "volatility"
        "최대낙폭" in compact || compact == "mdd" -> "mdd"
        "리스크기여" in compact || "riskcontribution" in compact -> "riskContribution"
        compact == "ic" || "rankic" in compact || "scorereturnic" in compact -> "rankIc"
        "ml점수" in compact || "ai보정" in compact || compact == "ml" || "mlscore" in compact -> "mlScore"
        compact == "value" || compact == "quality" || compact == "momentum" || "최종점수" in compact || "종합점수" in compact -> "factorScore"
        "eps" in compact || "서프라이즈" in compact -> "epsSurprise"
        "거래량서지" in compact || "volumesurge" in compact -> "volumeSurge"
        else -> null
    }
}

internal fun statusTone(status: String): DetailTone {
    return when (status.uppercase(Locale.US)) {
        "OK", "PASS", "SUCCESS", "HEALTHY", "IMPROVED", "ML_STRONG" -> DetailTone.Positive
        "ML_BASE" -> DetailTone.Primary
        "WARN", "WATCH", "DEGRADED", "INSUFFICIENT", "STALE", "UNKNOWN", "UNAVAILABLE", "REVIEW", "HOLD", "ML_OFF", "ML_WEAK" -> DetailTone.Warning
        "FAIL", "FAILED", "ERROR", "WORSE" -> DetailTone.Negative
        else -> DetailTone.Neutral
    }
}

internal fun scaleRatio(value: Double?, maxValue: Double): Double {
    val v = value ?: return 0.0
    if (!v.isFinite() || maxValue <= 0.0 || !maxValue.isFinite()) return 0.0
    return (v / maxValue * 100.0).coerceIn(0.0, 100.0)
}

internal fun scaleSignedRatio(value: Double?, positiveMax: Double): Double {
    val v = value ?: return 50.0
    if (!v.isFinite() || positiveMax <= 0.0 || !positiveMax.isFinite()) return 50.0
    return (50.0 + (v / positiveMax * 50.0)).coerceIn(0.0, 100.0)
}

internal fun scaleScore(value: Double?, maxValue: Double): Double = scaleRatio(value, maxValue)

internal fun scaleInverse(value: Double?, badAt: Double): Double {
    val v = value ?: return 50.0
    if (!v.isFinite() || badAt <= 0.0 || !badAt.isFinite()) return 50.0
    return (100.0 - (v / badAt * 100.0)).coerceIn(0.0, 100.0)
}

internal fun hasCompanyProfile(info: StockInfo): Boolean {
    return !info.industry.isNullOrBlank() ||
        profileLocation(info) != null ||
        info.employees != null ||
        !info.website.isNullOrBlank()
}

internal fun hasMarketInfo(info: StockInfo): Boolean {
    return info.currentPrice != null ||
        info.prevClose != null ||
        info.week52High != null ||
        info.week52Low != null ||
        info.marketCap != null ||
        info.peRatio != null ||
        info.forwardPe != null ||
        info.priceToSales != null ||
        info.priceToBook != null ||
        info.beta != null ||
        info.targetMeanPrice != null ||
        normalizedRecommendation(info.recommendation) != null
}

internal fun hasFinancialSnapshot(info: StockInfo): Boolean {
    return info.totalRevenue != null ||
        info.revenueGrowth != null ||
        info.grossMargin != null ||
        info.operatingMargin != null ||
        info.profitMargin != null ||
        info.ebitdaMargin != null ||
        info.ebitda != null ||
        info.freeCashflow != null ||
        info.totalDebt != null ||
        info.debtToEquity != null ||
        info.returnOnEquity != null
}

internal fun profileLocation(info: StockInfo): String? {
    return listOfNotNull(
        info.city?.takeIf { it.isNotBlank() },
        info.country?.takeIf { it.isNotBlank() }
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

internal fun returnMetrics(points: List<PricePoint>, currency: String): List<DetailMetric> {
    if (points.size < 2) return emptyList()
    val periodMetrics = listOf(
        "1W" to 5,
        "1M" to 21,
        "3M" to 63,
        "6M" to 126,
        "1Y" to 252
    ).map { (label, days) ->
        val ret = periodReturn(points, days)
        DetailMetric(label, pct(ret), returnTone(ret))
    }
    val returns = points.zipWithNext { prev, next ->
        if (prev.close == 0.0) null else (next.close / prev.close) - 1.0
    }.filterNotNull()
    val vol = if (returns.size > 5) {
        val avg = returns.average()
        sqrt(returns.sumOf { (it - avg) * (it - avg) } / returns.size) * sqrt(252.0)
    } else null
    val maxDd = maxDrawdown(points.takeLast(min(points.size, 126)))
    return periodMetrics + listOf(
        DetailMetric("연간 변동성", pct(vol, signed = false), inverseTone(vol, 0.25, 0.45)),
        DetailMetric("6M 최대낙폭", pct(maxDd), inverseTone(maxDd?.let { -it }, 0.15, 0.30)),
        DetailMetric("최근가", fmtPx(points.last().close, currency), DetailTone.Primary)
    )
}

internal fun periodReturn(points: List<PricePoint>, days: Int): Double? {
    if (points.size < 2) return null
    val offset = min(days, points.lastIndex)
    val base = points[points.lastIndex - offset].close
    if (base == 0.0) return null
    return (points.last().close / base) - 1.0
}

internal fun maxDrawdown(points: List<PricePoint>): Double? {
    if (points.size < 2) return null
    var peak = points.first().close
    var maxDrawdown = 0.0
    points.forEach { point ->
        peak = max(peak, point.close)
        if (peak > 0.0) {
            maxDrawdown = min(maxDrawdown, (point.close - peak) / peak)
        }
    }
    return maxDrawdown
}

internal fun rebalanceTradeText(order: RebalanceOrder): String {
    return "변화 ${pct(order.deltaWeight)} · 거래 ${compactNumber(order.executableTradeValue)}"
}

internal fun backtestTitle(summary: BacktestSummary): String {
    return when {
        summary.sheet.contains("SmallCap", ignoreCase = true) -> "${summary.market} 스몰캡"
        else -> "${summary.market} 분석"
    }
}

internal fun newsMarketLabel(market: String): String {
    return when (market.uppercase(Locale.US)) {
        "US" -> "미국"
        "KR" -> "국내"
        "GLOBAL" -> "글로벌"
        else -> market
    }
}

internal fun freshness(meta: Map<String, String>): String {
    return meta["Generated"] ?: meta["Last_Updated"] ?: "-"
}

internal fun portfolioMetaHeadline(meta: Map<String, String>): String {
    return firstPortfolioMetaValue(
        meta,
        "Expected_Return",
        "Ann. Return (hist. est.)",
        "Ann. Return"
    )?.let { formatMetaPercent(it, signed = true) }
        ?: freshness(meta)
}

internal fun portfolioMetaSubtitle(meta: Map<String, String>): String {
    val cashWeight = firstPortfolioMetaValue(meta, "Cash_Weight", "Cash Weight")
        ?.let { formatMetaPercent(it, signed = false) }
        ?: "데이터 없음"
    val generated = firstPortfolioMetaValue(meta, "Generated", "Generated_At", "Last_Updated")
        ?: "데이터 없음"
    return "현금 비중 $cashWeight · 생성 시각 $generated"
}

internal fun firstPortfolioMetaValue(meta: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}

internal fun formatMetaPercent(value: String, signed: Boolean): String {
    if (value.contains("%")) return value
    val number = value.replace(",", "").toDoubleOrNull() ?: return value
    return pct(number, signed = signed)
}

@Composable
internal fun regimeColor(regime: String): Color {
    return when (normalizeRegime(regime)) {
        "RISK_ON" -> QuantPositive
        "RISK_OFF" -> QuantNegative
        else -> MaterialTheme.colorScheme.primary
    }
}

fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch { block() }
}
