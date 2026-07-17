package com.qubit.quantbridge

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.min

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
