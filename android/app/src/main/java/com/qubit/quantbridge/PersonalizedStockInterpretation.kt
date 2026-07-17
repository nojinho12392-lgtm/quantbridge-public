package com.qubit.quantbridge

import java.util.Locale
import kotlin.math.abs

data class PersonalizedStockInterpretation(
    val label: String,
    val headline: String,
    val detail: String,
    val action: String,
    val reasons: List<String>,
    val tone: DetailTone
) {
    val decisionLine: String
        get() = "$headline. $action"
}

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

fun personalizedStockInterpretation(
    profile: InvestmentProfile,
    request: DetailRequest,
    detail: StockDetail?
): PersonalizedStockInterpretation {
    val metrics = request.sections.flatMap { it.metrics }
    val text = listOf(
        request.name,
        metrics.joinToString(" ") { "${it.label} ${it.value}" },
        request.signals.joinToString(" ") { "${it.title} ${it.detail}" }
    ).joinToString(" ").lowercase(Locale.US)
    val info = detail?.info
    val score = metrics.firstNotNullOfOrNull { metric ->
        if (metric.label.contains("점수") || metric.label.contains("score", ignoreCase = true)) {
            metric.value.numericToken()
        } else {
            null
        }
    }
    return personalizedStockInterpretation(
        profile = profile,
        name = request.name,
        highScore = score.isHighScore(),
        growthGood = (info?.revenueGrowth ?: 0.0) >= 0.12 || text.contains("성장") || text.contains("growth"),
        valuationBurden = (info?.peRatio ?: 0.0) >= 35.0 || (info?.forwardPe ?: 0.0) >= 35.0 || text.contains("밸류에이션 부담"),
        highVolatility = (info?.beta ?: 0.0) >= 1.15 || text.contains("변동성") || text.contains("급등") || text.contains("과열") || personalIsPriceNearHigh(info),
        recentSurge = pctMagnitude(info?.dailyChangePct) >= 5.0 || text.contains("급등") || text.contains("과열"),
        complexityHigh = (info?.peRatio ?: 0.0) >= 45.0 || (info?.debtToEquity ?: 0.0) >= 150.0 || request.signals.count { it.tone == DetailTone.Warning || it.tone == DetailTone.Negative } >= 2,
        fallbackReason = request.signals.firstOrNull()?.title ?: metrics.firstOrNull()?.label
    )
}

fun personalizedStockInterpretation(
    profile: InvestmentProfile,
    stock: PortfolioStock
): PersonalizedStockInterpretation {
    return personalizedStockInterpretation(
        profile = profile,
        name = stock.name,
        highScore = (stock.totalScore ?: 0.0).isHighScore(),
        growthGood = pctMagnitude(stock.revGrowth) >= 12.0,
        valuationBurden = (stock.expectedReturn ?: 0.0) < 0.0,
        highVolatility = pctMagnitude(stock.return1M) >= 10.0,
        recentSurge = pctMagnitude(stock.return1M) >= 12.0 || (stock.rankChange ?: 0) > 0,
        complexityHigh = stock.marketCap == null || (stock.expectedReturn ?: 0.0) < 0.0,
        fallbackReason = stock.sector
    )
}

private fun personalizedStockInterpretation(
    profile: InvestmentProfile,
    name: String,
    highScore: Boolean,
    growthGood: Boolean,
    valuationBurden: Boolean,
    highVolatility: Boolean,
    recentSurge: Boolean,
    complexityHigh: Boolean,
    fallbackReason: String?
): PersonalizedStockInterpretation {
    if (!profile.isConfigured) {
        return PersonalizedStockInterpretation(
            label = "기준 설정 필요",
            headline = "아직은 기본 점수 기준",
            detail = "투자 성향을 저장하면 ${name}을 내 기준으로 다시 해석합니다.",
            action = "먼저 투자 성향 진단을 저장해두세요.",
            reasons = listOfNotNull(fallbackReason),
            tone = DetailTone.Neutral
        )
    }

    val stable = profile.riskTolerance.contains("보수") || profile.riskTolerance.contains("안정") || profile.riskTolerance.contains("낮")
    val growth = profile.style.contains("성장")
    val value = profile.style.contains("가치")
    val momentum = profile.style.contains("모멘텀") || profile.horizon.contains("1개월")
    val novice = profile.experience.contains("초보") || profile.experience.contains("입문") || profile.experience.contains("처음")
    val reasons = buildList {
        if (highScore) add("점수 우수")
        if (growthGood) add("성장 근거")
        if (valuationBurden) add("가격 부담")
        if (highVolatility) add("변동성")
        if (recentSurge) add("최근 급등")
        if (complexityHigh) add("해석 난이도")
    }.ifEmpty { listOfNotNull(fallbackReason).take(1) }

    return when {
        momentum && recentSurge -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "추격 매수 주의",
            detail = "최근 급등 신호가 강해 지금은 진입보다 조건 확인이 먼저입니다.",
            action = "가격 조정이나 거래량 진정 후 다시 보세요.",
            reasons = reasons,
            tone = DetailTone.Warning
        )
        novice && complexityHigh -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "이해 난이도 높음",
            detail = "점수보다 사업 구조와 리스크를 먼저 이해해야 하는 후보입니다.",
            action = "더 단순한 비교 후보를 함께 열어보고 결정하세요.",
            reasons = reasons,
            tone = DetailTone.Warning
        )
        stable && highVolatility -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "좋지만 비중 제한",
            detail = "점수는 괜찮아도 변동성이 커서 안정형에게는 부담이 될 수 있습니다.",
            action = "관심 등록 후 작은 비중 또는 재검토 조건으로 관리하세요.",
            reasons = reasons,
            tone = DetailTone.Warning
        )
        growth && valuationBurden -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "성장성은 확인, 가격 부담",
            detail = "실적 모멘텀은 좋지만 밸류에이션 부담을 같이 봐야 합니다.",
            action = "성장률이 유지되는지와 가격 조정 여부를 같이 확인하세요.",
            reasons = reasons,
            tone = DetailTone.Primary
        )
        value && !valuationBurden && highScore -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "가치 기준 관심 후보",
            detail = "점수와 가격 부담이 크게 충돌하지 않아 비교 후보로 둘 만합니다.",
            action = "동종 업계 2~3개와 밸류에이션을 비교하세요.",
            reasons = reasons,
            tone = DetailTone.Positive
        )
        highScore -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "내 기준 관심 후보",
            detail = "좋은 종목인지보다 내 기준에 맞는지 확인할 근거가 있습니다.",
            action = "주의 신호와 다시 볼 조건을 투자 결정서에 남기세요.",
            reasons = reasons,
            tone = DetailTone.Primary
        )
        else -> PersonalizedStockInterpretation(
            label = "${profile.headline} 기준",
            headline = "관찰 우선",
            detail = "현재는 강한 확신보다 비교와 조건 확인이 더 어울립니다.",
            action = "관심 등록 후 다음 실적이나 가격 조건에서 다시 판단하세요.",
            reasons = reasons,
            tone = DetailTone.Neutral
        )
    }
}

private fun Double?.isHighScore(): Boolean {
    val value = this ?: return false
    return if (abs(value) <= 1.0) value >= 0.70 else value >= 70.0
}

private fun String.numericToken(): Double? {
    val token = Regex("""[-+]?\d+(?:\.\d+)?""").find(this)?.value ?: return null
    return token.toDoubleOrNull()
}

private fun pctMagnitude(value: Double?): Double {
    val raw = value ?: return 0.0
    val magnitude = abs(raw)
    return if (magnitude <= 1.0) magnitude * 100.0 else magnitude
}

private fun personalIsPriceNearHigh(info: StockInfo?): Boolean {
    val current = info?.currentPrice ?: return false
    val low = info.week52Low ?: return false
    val high = info.week52High ?: return false
    if (!current.isFinite() || !low.isFinite() || !high.isFinite() || high <= low) return false
    return abs((high - current) / (high - low)) < 0.15
}

private fun coverageCurationInsight(
    name: String,
    hasQuality: Boolean,
    hasFinancials: Boolean,
    hasTracking: Boolean,
    understandable: Boolean
): CoverageCurationInsight {
    return CoverageCurationInsight(
        headline = "분석 가능한 기업만 깊게 봅니다",
        summary = "모든 종목을 얕게 보여주지 않습니다. 이 후보는 큐빗이 판단 가능한 범위 안에서 선별한 기업입니다.",
        reasons = listOf(
            CoverageCurationReason(
                title = "데이터 품질 충분",
                detail = if (hasQuality) "스코어와 핵심 지표가 있어 랭킹보다 깊은 해석이 가능합니다." else "지표가 부족하면 상세 판단보다 관찰 단계로 둡니다.",
                status = if (hasQuality) "확인" else "관찰",
                tone = if (hasQuality) DetailTone.Positive else DetailTone.Neutral
            ),
            CoverageCurationReason(
                title = "재무/가격/이벤트 추적 가능",
                detail = when {
                    hasFinancials && hasTracking -> "재무 지표와 가격 흐름을 같이 보며 판단 알림으로 이어질 수 있습니다."
                    hasFinancials -> "재무 지표가 있어 비교 기준을 세울 수 있습니다."
                    hasTracking -> "가격과 업데이트 흐름을 추적할 수 있습니다."
                    else -> "추적 가능한 데이터가 부족하면 커버리지 우선순위를 낮춥니다."
                },
                status = if (hasFinancials || hasTracking) "추적 가능" else "보강 필요",
                tone = if (hasFinancials && hasTracking) DetailTone.Positive else DetailTone.Primary
            ),
            CoverageCurationReason(
                title = "개인투자자가 이해 가능한 사업",
                detail = if (understandable) "시장과 업종 기준으로 비교 가능한 후보입니다." else "사업 해석 근거가 부족하면 깊은 분석 대상으로 두지 않습니다.",
                status = if (understandable) "해석 가능" else "확인 필요",
                tone = if (understandable) DetailTone.Positive else DetailTone.Warning
            ),
            CoverageCurationReason(
                title = "과도한 테마성 제외",
                detail = "테마만으로 오른 종목보다 데이터로 검증 가능한 후보를 우선합니다.",
                status = "큐레이션 기준",
                tone = DetailTone.Primary
            )
        )
    )
}

private fun String.hasCoverageValue(): Boolean {
    val clean = trim()
    return clean.isNotBlank() && clean != "-" && clean != "N/A"
}

private fun String.coverageMatches(vararg tokens: String): Boolean {
    return tokens.any { contains(it, ignoreCase = true) }
}
