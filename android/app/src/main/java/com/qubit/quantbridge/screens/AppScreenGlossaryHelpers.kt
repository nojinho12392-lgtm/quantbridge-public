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
