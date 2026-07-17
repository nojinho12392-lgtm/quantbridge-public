package com.qubit.quantbridge

import java.util.Locale
import kotlin.math.abs

data class CoverageCurationReason(
    val title: String,
    val detail: String,
    val status: String,
    val tone: DetailTone = DetailTone.Primary
)

data class CoverageCurationInsight(
    val headline: String,
    val summary: String,
    val reasons: List<CoverageCurationReason>
) {
    val inlineLine: String
        get() = reasons
            .take(2)
            .joinToString(separator = " · ", prefix = "선별 이유 · ") { it.title }
}

fun coverageCurationInsight(
    request: DetailRequest,
    detail: StockDetail?
): CoverageCurationInsight {
    val info = detail?.info
    val metrics = request.sections.flatMap { it.metrics }
    val hasQuality = info != null ||
        metrics.any { it.value.hasCoverageValue() } ||
        detail?.source?.isNotBlank() == true
    val hasTracking = detail?.prices?.isNotEmpty() == true ||
        info?.currentPrice != null ||
        metrics.any { it.label.coverageMatches("현재가", "수익", "가격", "업데이트") && it.value.hasCoverageValue() }
    val hasFinancials = listOf(
        info?.totalRevenue,
        info?.revenueGrowth,
        info?.grossMargin,
        info?.operatingMargin,
        info?.peRatio,
        info?.forwardPe
    ).any { it != null } ||
        metrics.any { it.label.coverageMatches("매출", "ROIC", "마진", "PER", "점수", "성장") && it.value.hasCoverageValue() }
    val understandable = !info?.description.isNullOrBlank() ||
        !info?.sector.isNullOrBlank() ||
        !info?.industry.isNullOrBlank() ||
        request.sections.any { section -> section.metrics.any { it.label.coverageMatches("섹터", "시장", "산업") && it.value.hasCoverageValue() } }

    return CoverageCurationInsight(
        headline = "분석 가능한 기업만 깊게 봅니다",
        summary = "모든 종목을 얕게 보여주지 않습니다. 이 기업은 데이터 품질과 추적 가능성을 통과한 커버리지 후보입니다.",
        reasons = listOf(
            CoverageCurationReason(
                title = "데이터 품질 충분",
                detail = if (hasQuality) "점수, 가격, 상세 데이터 중 판단에 쓸 근거가 확보되어 있습니다." else "상세 데이터가 도착하는 즉시 품질 기준을 다시 확인합니다.",
                status = if (hasQuality) "확인" else "확인 중",
                tone = if (hasQuality) DetailTone.Positive else DetailTone.Neutral
            ),
            CoverageCurationReason(
                title = "재무/가격/이벤트 추적 가능",
                detail = when {
                    hasFinancials && hasTracking -> "재무 지표와 가격 흐름을 함께 추적할 수 있어 판단 업데이트가 가능합니다."
                    hasFinancials -> "재무 지표는 확인되며, 가격 데이터는 상세 로딩 후 보강합니다."
                    hasTracking -> "가격 흐름은 확인되며, 재무 지표는 상세 데이터로 보강합니다."
                    else -> "추적 가능한 핵심 지표가 충분한지 확인 중입니다."
                },
                status = if (hasFinancials || hasTracking) "추적 가능" else "확인 중",
                tone = if (hasFinancials && hasTracking) DetailTone.Positive else DetailTone.Primary
            ),
            CoverageCurationReason(
                title = "개인투자자가 이해 가능한 사업",
                detail = if (understandable) "섹터, 산업, 사업 설명을 기준으로 비교 가능한 후보로 남겼습니다." else "사업 설명이 부족하면 우선순위를 낮추고 추가 확인 대상으로 둡니다.",
                status = if (understandable) "해석 가능" else "보강 필요",
                tone = if (understandable) DetailTone.Positive else DetailTone.Warning
            ),
            CoverageCurationReason(
                title = "과도한 테마성 제외",
                detail = "단순 테마 노출보다 데이터로 다시 확인할 수 있는 후보를 우선합니다.",
                status = "큐레이션 기준",
                tone = DetailTone.Primary
            )
        )
    )
}

fun coverageCurationInsight(stock: PortfolioStock): CoverageCurationInsight {
    val hasScore = stock.totalScore != null
    val hasFinancials = listOf(stock.roic, stock.revGrowth, stock.grossMargin, stock.expectedReturn).any { it != null }
    val hasTracking = stock.currentPrice != null || stock.return1M != null || !stock.lastUpdated.isNullOrBlank()
    return coverageCurationInsight(
        name = stock.name,
        hasQuality = hasScore || hasFinancials,
        hasFinancials = hasFinancials,
        hasTracking = hasTracking,
        understandable = !stock.sector.isNullOrBlank() || !stock.market.isNullOrBlank()
    )
}

fun coverageCurationInsight(stock: SmallCapStock): CoverageCurationInsight {
    val hasScore = stock.totalScore != null
    val hasFinancials = listOf(stock.roic, stock.revGrowth, stock.revAccel, stock.grossMargin, stock.fcfMargin).any { it != null }
    val hasTracking = stock.currentPrice != null || stock.return1M != null || stock.volumeSurge != null || !stock.lastUpdated.isNullOrBlank()
    return coverageCurationInsight(
        name = stock.name,
        hasQuality = hasScore || hasFinancials,
        hasFinancials = hasFinancials,
        hasTracking = hasTracking,
        understandable = !stock.market.isNullOrBlank()
    )
}
