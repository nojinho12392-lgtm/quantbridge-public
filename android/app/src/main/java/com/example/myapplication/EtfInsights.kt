package com.example.myapplication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.myapplication.ui.theme.QuantGreen
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantPositive
import com.example.myapplication.ui.theme.QuantPurple
import com.example.myapplication.ui.theme.QuantWarning
import kotlinx.coroutines.delay

private const val ETF_PRICE_AUTO_REFRESH_MS = 300_000L

data class EtfHolding(
    val ticker: String,
    val name: String,
    val weight: Double
)

data class EtfExposure(
    val label: String,
    val weight: Double
)

private data class EtfGuidePoint(
    val title: String,
    val detail: String,
    val icon: LucideIcon
)

data class EtfInsight(
    val ticker: String,
    val name: String,
    val region: String,
    val category: String,
    val theme: String,
    val summary: String,
    val expenseRatio: String,
    val aum: String,
    val distribution: String,
    val outlook: String,
    val risk: String,
    val holdings: List<EtfHolding>,
    val exposures: List<EtfExposure>,
    val currentPrice: Double? = null,
    val return1M: Double? = null,
    val priceChange: Double? = null,
    val dailyChangePct: Double? = null,
    val dailyPriceChange: Double? = null,
    val dailyChangeHorizon: String? = null,
    val source: String? = null,
    val updatedAt: String? = null
) {
    val topHoldingText: String
        get() = holdings.firstOrNull()?.let { "${it.name} ${pct(it.weight, signed = false)}" } ?: "구성 대기"

    val displaySummary: String
        get() = cleanEtfSummary(summary)

    val displayPriceChange: Double?
        get() {
            dailyPriceChange?.takeIf { it.isFinite() }?.let { return it }
            val price = currentPrice?.takeIf { it.isFinite() } ?: return null
            val dailyChange = dailyChangePct?.takeIf { it.isFinite() && it > -0.999 }
            if (dailyChange != null) {
                val basePrice = price / (1.0 + dailyChange)
                return price - basePrice
            }
            priceChange?.takeIf { it.isFinite() }?.let { return it }
            val change = return1M?.takeIf { it.isFinite() && it > -0.999 } ?: return null
            val basePrice = price / (1.0 + change)
            return price - basePrice
        }

    val displayChangePct: Double?
        get() = dailyChangePct?.takeIf { it.isFinite() } ?: return1M?.takeIf { it.isFinite() }
}

data class EtfInsightsResult(
    val items: List<EtfInsight>,
    val source: String?,
    val updatedAt: String?
)

private enum class EtfSortOption(val title: String) {
    Alphabet("알파벳순"),
    Return1M("수익률순"),
    Ticker("티커순")
}

val EtfInsight.priceTicker: String
    get() = if (region == "KR" && ticker.length == 6 && ticker.all { it.isDigit() }) {
        "$ticker.KS"
    } else {
        ticker
    }

val EtfInsight.priceLookupTickers: List<String>
    get() = if (region == "KR" && ticker.length == 6 && ticker.all { it.isDigit() }) {
        listOf("$ticker.KS", "$ticker.KQ")
    } else {
        listOf(ticker)
    }

fun EtfInsight.detailRequest(): DetailRequest {
    val currency = if (region == "KR") "KRW" else "USD"
    return DetailRequest(
        ticker = priceTicker,
        name = name,
        currency = currency,
        market = region,
        sections = listOf(
            DetailSection(
                "ETF 정보",
                listOf(
                    DetailMetric("유형", category),
                    DetailMetric("테마", theme),
                    DetailMetric("총보수", expenseRatio, DetailTone.Primary),
                    DetailMetric("AUM", aum),
                    DetailMetric("분배", distribution),
                    DetailMetric("Top 구성", topHoldingText, DetailTone.Positive)
                )
            )
        ),
        signals = listOf(
            DetailSignal(
                "ETF 가격 확인",
                "기업 상세와 같은 차트에서 ETF 가격, 기간별 흐름, 변동성을 확인합니다.",
                DetailTone.Primary
            ),
            DetailSignal(theme, outlook, DetailTone.Positive),
            DetailSignal("주의", risk, DetailTone.Warning)
        ),
        factors = emptyList(),
        preferredTab = "chart",
        holdings = holdings.map { DetailHolding(it.ticker, it.name, it.weight) }
    )
}

val etfInsightUniverse = listOf(
    EtfInsight(
        ticker = "QQQ",
        name = "Invesco QQQ",
        region = "US",
        category = "성장",
        theme = "나스닥 100 · 빅테크",
        summary = "대형 기술주와 성장주 노출이 강한 대표 ETF입니다.",
        expenseRatio = "0.20%",
        aum = "$280B+",
        distribution = "분기",
        outlook = "AI, 클라우드, 반도체 모멘텀이 유지될 때 강합니다.",
        risk = "상위 빅테크 집중도가 높아 금리 상승과 기술주 조정에 민감합니다.",
        holdings = listOf(
            EtfHolding("NVDA", "NVIDIA", 0.082),
            EtfHolding("MSFT", "Microsoft", 0.078),
            EtfHolding("AAPL", "Apple", 0.073),
            EtfHolding("AMZN", "Amazon", 0.052),
            EtfHolding("AVGO", "Broadcom", 0.047)
        ),
        exposures = listOf(
            EtfExposure("기술", 0.55),
            EtfExposure("커뮤니케이션", 0.16),
            EtfExposure("경기소비재", 0.13),
            EtfExposure("헬스케어", 0.06)
        )
    ),
    EtfInsight(
        ticker = "VOO",
        name = "Vanguard S&P 500",
        region = "US",
        category = "대표지수",
        theme = "미국 대형주",
        summary = "미국 대형주 시장 전체에 가까운 노출을 주는 핵심 지수 ETF입니다.",
        expenseRatio = "0.03%",
        aum = "$1T+",
        distribution = "분기",
        outlook = "미국 이익 사이클과 위험선호가 살아날 때 안정적인 코어 역할을 합니다.",
        risk = "시총가중 구조라 대형 기술주 비중이 높아질수록 QQQ와 중복 노출이 커집니다.",
        holdings = listOf(
            EtfHolding("NVDA", "NVIDIA", 0.073),
            EtfHolding("MSFT", "Microsoft", 0.067),
            EtfHolding("AAPL", "Apple", 0.061),
            EtfHolding("AMZN", "Amazon", 0.038),
            EtfHolding("META", "Meta", 0.029)
        ),
        exposures = listOf(
            EtfExposure("기술", 0.34),
            EtfExposure("금융", 0.13),
            EtfExposure("헬스케어", 0.10),
            EtfExposure("경기소비재", 0.10)
        )
    ),
    EtfInsight(
        ticker = "SCHD",
        name = "Schwab US Dividend Equity",
        region = "US",
        category = "배당",
        theme = "미국 배당성장",
        summary = "배당 지속성과 현금흐름이 좋은 미국 대형주를 중심으로 담는 ETF입니다.",
        expenseRatio = "0.06%",
        aum = "$60B+",
        distribution = "분기",
        outlook = "금리 안정과 배당주 선호가 강해질 때 방어적 코어 역할을 기대할 수 있습니다.",
        risk = "고성장 기술주 랠리 구간에서는 성장주 ETF보다 상대 성과가 약할 수 있습니다.",
        holdings = listOf(
            EtfHolding("HD", "Home Depot", 0.045),
            EtfHolding("ABBV", "AbbVie", 0.043),
            EtfHolding("AMGN", "Amgen", 0.041),
            EtfHolding("KO", "Coca-Cola", 0.039),
            EtfHolding("PEP", "PepsiCo", 0.036)
        ),
        exposures = listOf(
            EtfExposure("산업재", 0.18),
            EtfExposure("헬스케어", 0.17),
            EtfExposure("필수소비재", 0.14),
            EtfExposure("금융", 0.13)
        )
    ),
    EtfInsight(
        ticker = "SOXX",
        name = "iShares Semiconductor",
        region = "US",
        category = "섹터",
        theme = "반도체",
        summary = "반도체 설계, 장비, 파운드리 밸류체인에 집중 투자하는 섹터 ETF입니다.",
        expenseRatio = "0.35%",
        aum = "$10B+",
        distribution = "분기",
        outlook = "AI 설비투자와 메모리 업황 회복 기대가 강할 때 탄력이 큽니다.",
        risk = "업황 사이클, 미국 수출규제, 특정 대형주 비중 변화에 크게 흔들릴 수 있습니다.",
        holdings = listOf(
            EtfHolding("NVDA", "NVIDIA", 0.095),
            EtfHolding("AVGO", "Broadcom", 0.083),
            EtfHolding("AMD", "AMD", 0.067),
            EtfHolding("QCOM", "Qualcomm", 0.061),
            EtfHolding("TXN", "Texas Instruments", 0.047)
        ),
        exposures = listOf(
            EtfExposure("반도체", 0.82),
            EtfExposure("장비", 0.12),
            EtfExposure("현금/기타", 0.06)
        )
    ),
    EtfInsight(
        ticker = "379800",
        name = "KODEX 미국S&P500",
        region = "KR",
        category = "해외지수",
        theme = "국내상장 미국 대형주",
        summary = "국내 계좌로 미국 S&P 500 노출을 얻는 대표적인 국내상장 해외 ETF입니다.",
        expenseRatio = "0.05%대",
        aum = "국내 대형",
        distribution = "상품별 상이",
        outlook = "환율과 미국 대형주 흐름을 동시에 확인해야 합니다.",
        risk = "원달러 환율, 환헤지 여부, 국내장 거래 시간과 미국장 시차를 함께 봐야 합니다.",
        holdings = listOf(
            EtfHolding("NVDA", "NVIDIA", 0.073),
            EtfHolding("MSFT", "Microsoft", 0.067),
            EtfHolding("AAPL", "Apple", 0.061),
            EtfHolding("AMZN", "Amazon", 0.038),
            EtfHolding("META", "Meta", 0.029)
        ),
        exposures = listOf(
            EtfExposure("미국", 0.96),
            EtfExposure("현금/기타", 0.04)
        )
    ),
    EtfInsight(
        ticker = "069500",
        name = "KODEX 200",
        region = "KR",
        category = "대표지수",
        theme = "코스피 200",
        summary = "국내 대형주 시장을 대표하는 코스피200 추종 ETF입니다.",
        expenseRatio = "0.15%대",
        aum = "국내 대형",
        distribution = "분배",
        outlook = "반도체, 자동차, 금융 대형주의 이익 방향성이 핵심입니다.",
        risk = "국내 경기와 수출 사이클, 원화 흐름, 반도체 대형주 집중도에 민감합니다.",
        holdings = listOf(
            EtfHolding("005930.KS", "삼성전자", 0.235),
            EtfHolding("000660.KS", "SK하이닉스", 0.094),
            EtfHolding("373220.KS", "LG에너지솔루션", 0.038),
            EtfHolding("005380.KS", "현대차", 0.031),
            EtfHolding("207940.KS", "삼성바이오로직스", 0.029)
        ),
        exposures = listOf(
            EtfExposure("IT", 0.34),
            EtfExposure("산업재", 0.14),
            EtfExposure("금융", 0.12),
            EtfExposure("헬스케어", 0.09)
        )
    ),
    EtfInsight(
        ticker = "VTI",
        name = "Vanguard Total Stock Market",
        region = "US",
        category = "대표지수",
        theme = "미국 전체시장",
        summary = "대형주부터 중소형주까지 미국 주식시장 전반을 한 번에 담는 ETF입니다.",
        expenseRatio = "0.03%",
        aum = "$400B+",
        distribution = "분기",
        outlook = "미국 시장 전체를 코어로 가져가고 싶을 때 가장 단순한 선택지입니다.",
        risk = "시총가중 구조라 대형 기술주 영향이 여전히 크고, 미국 시장 전체 조정에는 같이 흔들립니다.",
        holdings = listOf(
            EtfHolding("NVDA", "NVIDIA", 0.066),
            EtfHolding("MSFT", "Microsoft", 0.060),
            EtfHolding("AAPL", "Apple", 0.055),
            EtfHolding("AMZN", "Amazon", 0.034),
            EtfHolding("META", "Meta", 0.026)
        ),
        exposures = listOf(
            EtfExposure("대형주", 0.73),
            EtfExposure("중형주", 0.18),
            EtfExposure("소형주", 0.09)
        )
    ),
    EtfInsight(
        ticker = "IWM",
        name = "iShares Russell 2000",
        region = "US",
        category = "소형주",
        theme = "미국 소형주",
        summary = "미국 중소형 기업 사이클에 투자하는 대표 ETF입니다.",
        expenseRatio = "0.19%",
        aum = "$60B+",
        distribution = "분기",
        outlook = "금리 하락, 경기 회복, 내수 민감주 반등 국면에서 탄력이 커질 수 있습니다.",
        risk = "이익 변동성과 부채 부담이 큰 기업 비중이 높아 대형주보다 변동성이 큽니다.",
        holdings = listOf(
            EtfHolding("FTAI", "FTAI Aviation", 0.007),
            EtfHolding("INSM", "Insmed", 0.006),
            EtfHolding("SFM", "Sprouts Farmers Market", 0.005),
            EtfHolding("AIT", "Applied Industrial", 0.005),
            EtfHolding("CRS", "Carpenter Technology", 0.005)
        ),
        exposures = listOf(
            EtfExposure("산업재", 0.18),
            EtfExposure("금융", 0.17),
            EtfExposure("헬스케어", 0.16),
            EtfExposure("경기소비재", 0.11)
        )
    ),
    EtfInsight(
        ticker = "XLK",
        name = "Technology Select Sector SPDR",
        region = "US",
        category = "섹터",
        theme = "미국 기술주",
        summary = "S&P 500 안의 대형 기술주를 집중적으로 담는 섹터 ETF입니다.",
        expenseRatio = "0.09%대",
        aum = "$60B+",
        distribution = "분기",
        outlook = "AI, 소프트웨어, 반도체 사이클이 강할 때 시장 대비 초과 성과를 노릴 수 있습니다.",
        risk = "소수 대형주 집중도가 높아 기술주 밸류에이션 조정에 민감합니다.",
        holdings = listOf(
            EtfHolding("MSFT", "Microsoft", 0.150),
            EtfHolding("AAPL", "Apple", 0.145),
            EtfHolding("NVDA", "NVIDIA", 0.130),
            EtfHolding("AVGO", "Broadcom", 0.055),
            EtfHolding("CRM", "Salesforce", 0.025)
        ),
        exposures = listOf(
            EtfExposure("소프트웨어", 0.36),
            EtfExposure("하드웨어", 0.24),
            EtfExposure("반도체", 0.24),
            EtfExposure("IT서비스", 0.12)
        )
    ),
    EtfInsight(
        ticker = "XLF",
        name = "Financial Select Sector SPDR",
        region = "US",
        category = "섹터",
        theme = "미국 금융",
        summary = "미국 은행, 카드, 보험, 거래소 기업을 담는 금융 섹터 ETF입니다.",
        expenseRatio = "0.09%대",
        aum = "$40B+",
        distribution = "분기",
        outlook = "장단기 금리차 개선과 신용 사이클 안정이 확인될 때 관심도가 높아집니다.",
        risk = "경기 둔화, 부실채권 확대, 규제 이슈에 민감합니다.",
        holdings = listOf(
            EtfHolding("BRK-B", "Berkshire Hathaway", 0.130),
            EtfHolding("JPM", "JPMorgan Chase", 0.095),
            EtfHolding("V", "Visa", 0.055),
            EtfHolding("MA", "Mastercard", 0.045),
            EtfHolding("BAC", "Bank of America", 0.040)
        ),
        exposures = listOf(
            EtfExposure("은행", 0.34),
            EtfExposure("자본시장", 0.24),
            EtfExposure("보험", 0.18),
            EtfExposure("결제", 0.14)
        )
    ),
    EtfInsight(
        ticker = "XLV",
        name = "Health Care Select Sector SPDR",
        region = "US",
        category = "섹터",
        theme = "미국 헬스케어",
        summary = "제약, 의료기기, 보험, 바이오를 담는 방어적 섹터 ETF입니다.",
        expenseRatio = "0.09%대",
        aum = "$35B+",
        distribution = "분기",
        outlook = "경기 민감도가 낮은 실적 안정성을 찾을 때 포트폴리오 완충재가 될 수 있습니다.",
        risk = "약가 규제, 임상 실패, 대형 제약 특허만료 리스크를 함께 봐야 합니다.",
        holdings = listOf(
            EtfHolding("LLY", "Eli Lilly", 0.120),
            EtfHolding("UNH", "UnitedHealth", 0.080),
            EtfHolding("JNJ", "Johnson & Johnson", 0.060),
            EtfHolding("ABBV", "AbbVie", 0.050),
            EtfHolding("MRK", "Merck", 0.045)
        ),
        exposures = listOf(
            EtfExposure("제약", 0.34),
            EtfExposure("의료보험", 0.20),
            EtfExposure("의료기기", 0.18),
            EtfExposure("바이오", 0.13)
        )
    ),
    EtfInsight(
        ticker = "VNQ",
        name = "Vanguard Real Estate",
        region = "US",
        category = "리츠",
        theme = "미국 리츠",
        summary = "미국 상장 리츠와 부동산 운영 기업에 분산 투자하는 ETF입니다.",
        expenseRatio = "0.13%대",
        aum = "$30B+",
        distribution = "분기",
        outlook = "금리 안정과 임대료 회복이 같이 나타날 때 배당형 자산으로 주목받습니다.",
        risk = "금리 상승, 상업용 부동산 공실, 리파이낸싱 부담에 민감합니다.",
        holdings = listOf(
            EtfHolding("PLD", "Prologis", 0.075),
            EtfHolding("AMT", "American Tower", 0.060),
            EtfHolding("EQIX", "Equinix", 0.050),
            EtfHolding("WELL", "Welltower", 0.045),
            EtfHolding("SPG", "Simon Property", 0.035)
        ),
        exposures = listOf(
            EtfExposure("산업/물류", 0.18),
            EtfExposure("데이터센터", 0.15),
            EtfExposure("헬스케어", 0.13),
            EtfExposure("리테일", 0.12)
        )
    ),
    EtfInsight(
        ticker = "TLT",
        name = "iShares 20+ Year Treasury Bond",
        region = "US",
        category = "채권",
        theme = "미국 장기국채",
        summary = "미국 장기 국채 가격에 투자하는 대표적인 듀레이션 ETF입니다.",
        expenseRatio = "0.15%대",
        aum = "$50B+",
        distribution = "월",
        outlook = "경기 둔화와 금리 하락 기대가 강해질 때 방어와 반등을 동시에 노릴 수 있습니다.",
        risk = "장기금리 상승에는 가격 하락폭이 크게 나타날 수 있습니다.",
        holdings = listOf(
            EtfHolding("TLT", "20년 이상 미국 국채", 0.420),
            EtfHolding("UST30", "30년 미국 국채", 0.300),
            EtfHolding("UST20", "20년 미국 국채", 0.220),
            EtfHolding("CASH", "현금성 자산", 0.030)
        ),
        exposures = listOf(
            EtfExposure("장기국채", 0.94),
            EtfExposure("현금/기타", 0.06)
        )
    ),
    EtfInsight(
        ticker = "GLD",
        name = "SPDR Gold Shares",
        region = "US",
        category = "원자재",
        theme = "금",
        summary = "금 가격 노출을 얻기 위한 대표 원자재 ETF입니다.",
        expenseRatio = "0.40%대",
        aum = "$60B+",
        distribution = "없음",
        outlook = "달러 약세, 실질금리 하락, 지정학 리스크가 커질 때 방어 자산으로 쓰입니다.",
        risk = "현금흐름이 없는 자산이라 실질금리 상승과 달러 강세에는 약할 수 있습니다.",
        holdings = listOf(
            EtfHolding("GLD", "금 현물", 0.995),
            EtfHolding("CASH", "현금/기타", 0.005)
        ),
        exposures = listOf(
            EtfExposure("금", 0.995),
            EtfExposure("현금/기타", 0.005)
        )
    ),
    EtfInsight(
        ticker = "ARKK",
        name = "ARK Innovation ETF",
        region = "US",
        category = "테마",
        theme = "혁신성장",
        summary = "AI, 전기차, 바이오, 핀테크 등 고성장 혁신 기업을 담는 테마 ETF입니다.",
        expenseRatio = "0.75%대",
        aum = "$5B+",
        distribution = "연",
        outlook = "성장주 위험선호가 강해질 때 높은 베타로 반등할 수 있습니다.",
        risk = "높은 변동성, 편입종목 교체, 적자 성장주의 금리 민감도를 반드시 봐야 합니다.",
        holdings = listOf(
            EtfHolding("TSLA", "Tesla", 0.110),
            EtfHolding("COIN", "Coinbase", 0.080),
            EtfHolding("ROKU", "Roku", 0.060),
            EtfHolding("SHOP", "Shopify", 0.055),
            EtfHolding("CRSP", "CRISPR Therapeutics", 0.040)
        ),
        exposures = listOf(
            EtfExposure("기술", 0.36),
            EtfExposure("헬스케어", 0.24),
            EtfExposure("경기소비재", 0.18),
            EtfExposure("금융기술", 0.12)
        )
    ),
    EtfInsight(
        ticker = "133690",
        name = "TIGER 미국나스닥100",
        region = "KR",
        category = "해외지수",
        theme = "국내상장 나스닥 100",
        summary = "국내 계좌로 나스닥100 대형 성장주에 접근하는 대표 ETF입니다.",
        expenseRatio = "0.07%대",
        aum = "국내 대형",
        distribution = "상품별 상이",
        outlook = "미국 성장주와 원달러 환율 흐름을 함께 보고 접근하기 좋습니다.",
        risk = "국내장 거래 시간과 미국장 가격 반영 시차, 환율 변동을 같이 확인해야 합니다.",
        holdings = listOf(
            EtfHolding("NVDA", "NVIDIA", 0.082),
            EtfHolding("MSFT", "Microsoft", 0.078),
            EtfHolding("AAPL", "Apple", 0.073),
            EtfHolding("AMZN", "Amazon", 0.052),
            EtfHolding("AVGO", "Broadcom", 0.047)
        ),
        exposures = listOf(
            EtfExposure("미국", 0.96),
            EtfExposure("현금/기타", 0.04)
        )
    ),
    EtfInsight(
        ticker = "305720",
        name = "KODEX 2차전지산업",
        region = "KR",
        category = "테마",
        theme = "국내 2차전지",
        summary = "배터리 셀, 소재, 장비 기업에 집중 투자하는 국내 테마 ETF입니다.",
        expenseRatio = "0.45%대",
        aum = "국내 중대형",
        distribution = "상품별 상이",
        outlook = "전기차 수요 회복과 소재 가격 안정이 확인될 때 반등 탄력이 커질 수 있습니다.",
        risk = "업황 둔화, 가격 경쟁, 특정 대형주 집중도에 따라 변동성이 큽니다.",
        holdings = listOf(
            EtfHolding("373220.KS", "LG에너지솔루션", 0.180),
            EtfHolding("006400.KS", "삼성SDI", 0.120),
            EtfHolding("003670.KS", "포스코퓨처엠", 0.090),
            EtfHolding("247540.KQ", "에코프로비엠", 0.080),
            EtfHolding("005490.KS", "POSCO홀딩스", 0.060)
        ),
        exposures = listOf(
            EtfExposure("셀", 0.32),
            EtfExposure("소재", 0.38),
            EtfExposure("장비", 0.14),
            EtfExposure("기타", 0.16)
        )
    ),
    EtfInsight(
        ticker = "091160",
        name = "KODEX 반도체",
        region = "KR",
        category = "섹터",
        theme = "국내 반도체",
        summary = "국내 반도체 대형주와 장비, 소재 기업을 함께 담는 섹터 ETF입니다.",
        expenseRatio = "0.45%대",
        aum = "국내 중대형",
        distribution = "분배",
        outlook = "메모리 업황 회복과 AI 서버 투자 확대가 핵심 모멘텀입니다.",
        risk = "메모리 가격 사이클, 수출 규제, 대형주 비중 변화에 민감합니다.",
        holdings = listOf(
            EtfHolding("005930.KS", "삼성전자", 0.230),
            EtfHolding("000660.KS", "SK하이닉스", 0.210),
            EtfHolding("403870.KQ", "HPSP", 0.055),
            EtfHolding("058470.KQ", "리노공업", 0.050),
            EtfHolding("000990.KS", "DB하이텍", 0.045)
        ),
        exposures = listOf(
            EtfExposure("메모리", 0.48),
            EtfExposure("장비", 0.24),
            EtfExposure("소재/부품", 0.18),
            EtfExposure("파운드리", 0.10)
        )
    )
)

val featuredEtfInsights: List<EtfInsight>
    get() = etfInsightUniverse.take(4)

@Composable
fun HomeEtfInsightCard(etf: EtfInsight, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .width(258.dp)
            .height(162.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                EtfAvatar(etf.ticker, etf.name, etf.region, etf.category, etf.theme, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(etf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${etf.region} · ${etf.theme}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(etf.displaySummary, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtfMetricPill("보수", etf.expenseRatio, Modifier.weight(1f))
                EtfMetricPill("Top", etf.topHoldingText, Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtfInsightsScreen(
    app: QuantAppState,
    viewModel: EtfInsightsViewModel = hiltViewModel()
) {
    var region by remember { mutableStateOf("전체") }
    var category by remember { mutableStateOf("전체") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(EtfSortOption.Alphabet) }
    var showEtfGuide by remember { mutableStateOf(false) }
    val items = viewModel.items

    if (showEtfGuide) {
        BackHandler { showEtfGuide = false }
        EtfEducationScreen(onBack = { showEtfGuide = false })
        return
    }

    LaunchedEffect(Unit) {
        viewModel.refreshEtfs()
    }
    LaunchedEffect("etf-price-auto") {
        while (true) {
            delay(ETF_PRICE_AUTO_REFRESH_MS)
            viewModel.refreshEtfs(force = true, automatic = true)
        }
    }

    LaunchedEffect(query) {
        val clean = query.trim()
        if (clean.isBlank()) {
            viewModel.clearSearch()
            return@LaunchedEffect
        }
        region = "전체"
        category = "전체"
        delay(260)
        viewModel.searchEtfs(clean)
    }

    val searchSourceItems = remember(items, viewModel.searchItems, query) {
        if (query.trim().isBlank()) {
            items
        } else {
            mergeEtfSearchItems(items, viewModel.searchItems)
        }
    }
    val regions = remember(searchSourceItems) { listOf("전체") + searchSourceItems.map { it.region }.distinct().sorted() }
    val categories = remember(searchSourceItems) { listOf("전체") + searchSourceItems.map { it.category }.distinct().sorted() }
    val filtered = remember(region, category, query, searchSourceItems, sort) {
        val clean = query.trim().lowercase()
        val matches = searchSourceItems.filter { etf ->
            (region == "전체" || etf.region == region) &&
                (category == "전체" || etf.category == category) &&
                (clean.isEmpty() ||
                    etf.ticker.lowercase().contains(clean) ||
                    etf.name.lowercase().contains(clean) ||
                    etf.theme.lowercase().contains(clean))
        }
        sortEtfs(matches, sort)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            EtfHeaderCard(
                filtered.size,
                viewModel.loading || viewModel.searchLoading,
                if (query.trim().isBlank()) viewModel.source else "검색 확장",
                viewModel.updatedAt,
                viewModel.searchError ?: viewModel.error,
                onOpenGuide = { showEtfGuide = true }
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                placeholder = { Text("ETF 티커, 이름, 테마 검색") },
                leadingIcon = {
                    LucideIconView(
                        icon = LucideIcon.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        item { EtfFilterRow("시장", regions, region) { region = it } }
        item { EtfFilterRow("유형", categories, category) { category = it } }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LucideIconView(
                        icon = LucideIcon.PieChart,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("ETF 목록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                EtfSortMenu(sort = sort, onSort = { sort = it })
            }
        }
        if (filtered.isEmpty()) {
            item {
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LucideIconView(
                            icon = LucideIcon.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("ETF 검색 결과 없음", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (viewModel.searchLoading) "검색 범위를 넓혀 확인하고 있습니다." else "다른 티커나 테마로 검색해보세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(filtered, key = { it.ticker }) { etf ->
                EtfInsightRow(etf = etf, onOpen = { app.selectedDetail = etf.detailRequest() })
            }
        }
    }
}

private fun mergeEtfSearchItems(local: List<EtfInsight>, remote: List<EtfInsight>): List<EtfInsight> {
    val merged = LinkedHashMap<String, EtfInsight>()
    local.forEach { merged[it.ticker.uppercase()] = it }
    remote.forEach { merged[it.ticker.uppercase()] = it }
    return merged.values.toList()
}

private fun sortEtfs(items: List<EtfInsight>, sort: EtfSortOption): List<EtfInsight> {
    return when (sort) {
        EtfSortOption.Alphabet -> items.sortedWith(
            compareBy<EtfInsight> { it.name.lowercase() }.thenBy { it.ticker.uppercase() }
        )
        EtfSortOption.Return1M -> items.sortedWith(
            compareByDescending<EtfInsight> { it.return1M ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.name.lowercase() }
                .thenBy { it.ticker.uppercase() }
        )
        EtfSortOption.Ticker -> items.sortedBy { it.ticker.uppercase() }
    }
}

@Composable
private fun EtfSortMenu(sort: EtfSortOption, onSort: (EtfSortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .clip(CircleShape)
                .quantClickable(role = QuantPressRole.Icon) { expanded = true },
            shape = CircleShape,
            color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.SlidersHorizontal,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    sort.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(206.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "정렬 기준",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
                EtfSortOption.entries.forEach { option ->
                    EtfSortMenuRow(
                        option = option,
                        selected = option == sort,
                        onClick = {
                            onSort(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EtfSortMenuRow(option: EtfSortOption, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LucideIconView(
                icon = when (option) {
                    EtfSortOption.Alphabet -> LucideIcon.ListOrdered
                    EtfSortOption.Return1M -> LucideIcon.TrendingUp
                    EtfSortOption.Ticker -> LucideIcon.Target
                },
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tint
            )
            Text(
                option.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (selected) {
                LucideIconView(
                    icon = LucideIcon.Check,
                    contentDescription = "선택됨",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun EtfHeaderCard(
    count: Int,
    loading: Boolean,
    source: String,
    updatedAt: String?,
    error: String?,
    onOpenGuide: () -> Unit
) {
    CardBlock(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .quantClickable(role = QuantPressRole.Card, onClick = onOpenGuide)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.PieChart,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("ETF 인사이트", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = CircleShape
            ) {
                LucideIconView(
                    icon = LucideIcon.Lightbulb,
                    contentDescription = "ETF 설명 열기",
                    modifier = Modifier.padding(7.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text("대표 ETF의 구성종목, 섹터 노출, 전망과 리스크를 한 곳에서 봅니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "눌러서 ETF가 무엇인지 자세히 보기",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        val sourceText = when (source) {
            "api" -> "Azure API"
            "검색 확장" -> "검색 확장"
            "fallback" -> "대표 노출"
            else -> dataSourceLabel(source)
        }
        Text(
            "${count}개 ETF · $sourceText 기준${if (loading) " · 갱신 중" else ""}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        DataFreshnessBadge(
            source = if (source == "api") "storage" else source,
            updatedAt = updatedAt,
            compact = true
        )
        if (!error.isNullOrBlank() && source != "api") {
            Text(error, color = QuantWarning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EtfEducationScreen(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ETF 인사이트로 돌아가기")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("ETF 기본 가이드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("ETF를 볼 때 무엇부터 확인해야 하는지 정리했습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { EtfGuideHeroCard() }
        item {
            EtfGuideSection(
                icon = LucideIcon.PieChart,
                title = "ETF란 무엇인가요?",
                headline = "여러 자산을 한 바구니에 담아 주식처럼 거래하는 펀드입니다.",
                body = "ETF는 Exchange Traded Fund의 약자입니다. 특정 지수, 산업, 테마, 채권, 원자재, 통화 같은 바구니를 따라가도록 설계되고, 일반 주식처럼 장중에 사고팔 수 있습니다.",
                points = listOf(
                    EtfGuidePoint("한 종목으로 분산", "S&P 500 ETF를 사면 개별 500개 기업을 하나씩 고르지 않아도 시장 전체에 가까운 노출을 얻습니다.", LucideIcon.ListOrdered),
                    EtfGuidePoint("거래는 주식처럼", "펀드지만 거래소에 상장되어 있어 가격이 장중에 움직이고, 지정가/시장가 주문이 가능합니다.", LucideIcon.ChartCandlestick),
                    EtfGuidePoint("핵심은 추종 대상", "ETF 이름보다 어떤 지수와 규칙을 따라가는지가 실제 성과와 위험을 결정합니다.", LucideIcon.Target)
                )
            )
        }
        item {
            EtfGuideSection(
                icon = LucideIcon.SlidersHorizontal,
                title = "큐빗에서 ETF를 읽는 순서",
                headline = "가격 차트보다 먼저 ETF의 성격을 파악하면 판단 속도가 빨라집니다.",
                body = "ETF는 기업 실적보다 구성 방식이 중요합니다. 같은 AI ETF라도 반도체 중심인지, 소프트웨어 중심인지, 대형주 집중인지에 따라 완전히 다른 움직임을 보일 수 있습니다.",
                points = listOf(
                    EtfGuidePoint("1. 추종 대상", "지수형, 섹터형, 테마형, 채권형, 원자재형인지 먼저 구분합니다.", LucideIcon.Target),
                    EtfGuidePoint("2. Top10 비중", "상위 종목 비중이 높으면 사실상 몇 개 기업에 집중 투자하는 것과 비슷합니다.", LucideIcon.PieChart),
                    EtfGuidePoint("3. 총보수와 AUM", "보수는 장기 수익률을 갉아먹고, AUM이 너무 작으면 유동성이나 상장 유지 리스크가 커질 수 있습니다.", LucideIcon.Database)
                )
            )
        }
        item { EtfGuideChecklistCard() }
        item {
            EtfGuideSection(
                icon = LucideIcon.TriangleAlert,
                title = "주의해야 할 ETF",
                headline = "ETF라고 해서 모두 분산이 잘 된 것은 아닙니다.",
                body = "레버리지, 인버스, 초협소 테마, 거래량이 작은 ETF는 일반 장기 보유 ETF와 성격이 다릅니다. 특히 레버리지/인버스 ETF는 하루 수익률을 목표로 설계되는 경우가 많아 장기 성과가 직관과 다를 수 있습니다.",
                points = listOf(
                    EtfGuidePoint("레버리지/인버스", "방향을 맞춰도 장기 보유 중 복리 효과와 변동성 때문에 기대와 다른 결과가 나올 수 있습니다.", LucideIcon.Zap),
                    EtfGuidePoint("테마 집중", "AI, 우주, 2차전지처럼 이름은 넓어도 실제 구성은 일부 기업에 크게 쏠릴 수 있습니다.", LucideIcon.TriangleAlert),
                    EtfGuidePoint("환율과 금리", "해외 ETF와 채권 ETF는 주가 외에도 환율, 금리 변화가 성과에 크게 반영됩니다.", LucideIcon.Globe2)
                )
            )
        }
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .quantClickable(role = QuantPressRole.Card, onClick = onBack),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LucideIconView(
                        icon = LucideIcon.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "ETF 목록에서 실제 상품 비교하기",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun EtfGuideHeroCard() {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape) {
                LucideIconView(
                    icon = LucideIcon.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("핵심 결론", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "ETF는 종목 하나가 아니라 투자 규칙을 사는 상품입니다.",
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 30.sp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            "따라서 ETF를 볼 때는 오늘 가격이 올랐는지보다, 어떤 시장을 따라가는지, 무엇을 얼마나 담고 있는지, 비용과 유동성이 적절한지부터 확인하는 것이 좋습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
        )
    }
}

@Composable
private fun EtfGuideSection(
    icon: LucideIcon,
    title: String,
    headline: String,
    body: String,
    points: List<EtfGuidePoint>
) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            headline,
            style = MaterialTheme.typography.titleLarge.copy(lineHeight = 27.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            points.forEach { point ->
                EtfGuidePointRow(point)
            }
        }
    }
}

@Composable
private fun EtfGuidePointRow(point: EtfGuidePoint) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape = CircleShape) {
            LucideIconView(
                icon = point.icon,
                contentDescription = null,
                modifier = Modifier.padding(6.dp).size(15.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(point.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                point.detail,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EtfGuideChecklistCard() {
    val checks = listOf(
        EtfGuidePoint("구성종목", "Top10 비중과 특정 기업 쏠림을 확인합니다.", LucideIcon.ListOrdered),
        EtfGuidePoint("섹터/지역 노출", "내가 생각한 ETF 성격과 실제 노출이 같은지 봅니다.", LucideIcon.Globe2),
        EtfGuidePoint("총보수", "장기 보유라면 낮은 보수가 누적 성과에 유리합니다.", LucideIcon.SlidersHorizontal),
        EtfGuidePoint("가격 흐름", "단기 변동성과 1개월 수익률이 시장 상황과 맞는지 비교합니다.", LucideIcon.LineChart)
    )
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.Check,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = QuantGreen
            )
            Text("ETF 판단 체크리스트", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "목록에서 ETF를 고를 때 아래 4가지만 먼저 확인해도 대부분의 오해를 줄일 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        checks.forEach { point ->
            EtfGuidePointRow(point)
        }
    }
}

@Composable
private fun EtfFilterRow(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values, key = { it }) { value ->
                Surface(
                    modifier = Modifier.clickable { onSelect(value) },
                    color = if (selected == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ) {
                    Text(
                        value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (selected == value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EtfInsightRow(etf: EtfInsight, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EtfAvatar(etf.ticker, etf.name, etf.region, etf.category, etf.theme, size = 42.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(etf.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(etf.ticker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(etf.displaySummary, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    EtfInlineMetric(LucideIcon.Target, "유형", etf.category)
                    EtfInlineMetric(LucideIcon.SlidersHorizontal, "보수", etf.expenseRatio)
                }
            }
            EtfPriceSummary(etf)
        }
    }
}

@Composable
private fun EtfInlineMetric(icon: LucideIcon, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "$label $value",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EtfPriceSummary(etf: EtfInsight) {
    val currency = if (etf.region == "KR") "KRW" else "USD"
    val move = etf.displayPriceChange
    val changePct = etf.displayChangePct
    val moveColor = if ((changePct ?: move ?: 0.0) >= 0.0) QuantPositive else QuantNegative
    Column(
        modifier = Modifier.width(98.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        AnimatedPriceText(
            text = etf.currentPrice?.let { etfPriceText(it, currency) } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        if (move != null && changePct != null) {
            Text(
                "${etfSignedPriceText(move, currency)} (${pct(changePct)})",
                color = moveColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun EtfDetailScreen(etf: EtfInsight, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ETF 목록으로 돌아가기")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(etf.ticker, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(etf.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { EtfDetailHeader(etf) }
        item { EtfHoldingsSection(etf) }
        item { EtfExposureSection(etf) }
        item { EtfOutlookSection(etf) }
    }
}

@Composable
private fun EtfDetailHeader(etf: EtfInsight) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EtfAvatar(etf.ticker, etf.name, etf.region, etf.category, etf.theme, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(etf.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${etf.ticker} · ${etf.region} · ${etf.theme}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(etf.displaySummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EtfDetailMetric("총보수", etf.expenseRatio, Modifier.weight(1f))
            EtfDetailMetric("AUM", etf.aum, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EtfDetailMetric("분배", etf.distribution, Modifier.weight(1f))
            EtfDetailMetric("유형", etf.category, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EtfHoldingsSection(etf: EtfInsight) {
    val maxWeight = etf.holdings.maxOfOrNull { it.weight }?.takeIf { it > 0.0 } ?: 0.01
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.ListOrdered,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("Top 구성종목", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        etf.holdings.forEach { holding ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TickerAvatar(holding.ticker, null, size = 32.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(holding.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(holding.ticker, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(pct(holding.weight, signed = false), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((holding.weight / maxWeight).toFloat().coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
private fun EtfExposureSection(etf: EtfInsight) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Globe2,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("섹터/지역 노출", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        etf.exposures.forEachIndexed { index, exposure ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(exposure.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(pct(exposure.weight, signed = false), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(exposure.weight.toFloat().coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(exposureColor(index))
                    )
                }
            }
        }
    }
}

@Composable
private fun EtfOutlookSection(etf: EtfInsight) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIconView(
                icon = LucideIcon.Sparkles,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("전망과 리스크", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        EtfOutlookRow(LucideIcon.TrendingUp, "전망", etf.outlook, QuantGreen)
        EtfOutlookRow(LucideIcon.TriangleAlert, "주의", etf.risk, QuantWarning)
    }
}

@Composable
private fun EtfOutlookRow(icon: LucideIcon, title: String, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = color.copy(alpha = 0.10f),
            shape = CircleShape
        ) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.padding(5.dp).size(14.dp),
                tint = color
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EtfDetailMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EtfMetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EtfAvatar(ticker: String, name: String, region: String, category: String, theme: String, size: Dp) {
    val background = remember(name, category, theme) { etfAvatarColor(name, category, theme) }
    val foreground = remember(name) { etfAvatarTextColor(name) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        if (region == "KR") {
            val lines = domesticEtfLogoLines(name, theme)
            val textSize = domesticEtfLogoFontSize(size, lines)
            Column(
                modifier = Modifier.padding(horizontal = size * 0.07f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        modifier = Modifier.fillMaxWidth(),
                        color = foreground,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = textSize,
                        lineHeight = (textSize.value + 1f).sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Text(
                ticker.take(if (ticker.length > 4) 3 else 4),
                color = foreground,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

private fun domesticEtfLogoLines(name: String, theme: String): List<String> {
    val label = domesticEtfTheme(name, theme)
    preferredDomesticEtfLogoLines(label)?.let { return it }
    return when (label) {
        "KOSPI 200" -> listOf("코스피", "200")
        "KOSPI 200TR" -> listOf("코스피", "200TR")
        "NASDAQ 100" -> listOf("나스닥", "100")
        "S&P 500" -> listOf("S&P", "500")
        "2차전지" -> listOf("2차", "전지")
        else -> fittedDomesticEtfLogoLines(label)
    }
}

private fun domesticEtfLogoFontSize(size: Dp, lines: List<String>) = run {
    val widest = lines.maxOfOrNull(::domesticEtfLogoWidthScore) ?: 1f
    val ratio = when {
        widest <= 2.4f -> 0.285f
        widest <= 3.2f -> 0.255f
        widest <= 4.05f -> 0.215f
        widest <= 5.0f -> 0.170f
        else -> 0.145f
    }
    (size.value * ratio).coerceAtLeast(6.5f).sp
}

private fun domesticEtfLogoWidthScore(text: String): Float {
    var score = 0f
    text.forEach { char ->
        score += when {
            char in '\uAC00'..'\uD7A3' || char in '\u3130'..'\u318F' -> 1.0f
            char.isDigit() -> 0.56f
            char in 'A'..'Z' || char in 'a'..'z' -> 0.60f
            char == ' ' -> 0.20f
            else -> 0.38f
        }
    }
    return score.coerceAtLeast(1f)
}

private fun preferredDomesticEtfLogoLines(label: String): List<String>? {
    val compact = label.replace(" ", "").uppercase()
    return when {
        "WTI" in compact || "원유" in compact -> listOf("WTI", "원유")
        "달러" in compact && "레버리지" in compact -> listOf("달러", "레버리지")
        "달러" in compact && "인버스" in compact -> listOf("달러", "인버스")
        "코스피200TR" in compact || "KOSPI200TR" in compact -> listOf("코스피", "200TR")
        "코스피200" in compact && "인버스" in compact -> listOf("코스피200", "인버스")
        "코스닥150" in compact && "레버리지" in compact -> listOf("코스닥150", "레버리지")
        "코스닥150" in compact && "인버스" in compact -> listOf("코스닥150", "인버스")
        "코스닥150" in compact -> listOf("코스닥", "150")
        "다우존스30" in compact -> listOf("다우존스", "30")
        "국고채3년" in compact -> listOf("국고채", "3년")
        "빅테크집중" in compact -> listOf("빅테크", "집중")
        "테크TOP10" in compact -> listOf("테크", "TOP10")
        "미디어엔터" in compact -> listOf("미디어", "엔터")
        "BBIG성장" in compact -> listOf("BBIG", "성장")
        "여행레저" in compact -> listOf("여행", "레저")
        else -> null
    }
}

private fun fittedDomesticEtfLogoLines(label: String): List<String> {
    if (label.length <= 4) return listOf(label)
    splitTrailingNumberLogoLabel(label)?.let { return it }
    listOf("레버리지", "인버스", "선물", "채권").forEach { suffix ->
        splitLogoLabel(label, suffix)?.let { return it }
    }
    val midpoint = (label.length / 2).coerceAtLeast(2)
    return listOf(label.take(midpoint), label.drop(midpoint))
}

private fun splitTrailingNumberLogoLabel(label: String): List<String>? {
    val match = Regex("""\d+$""").find(label) ?: return null
    if (match.range.first <= 0) return null
    val prefix = label.substring(0, match.range.first)
    val suffix = match.value
    return if (prefix.isNotBlank() && suffix.isNotBlank()) listOf(prefix, suffix) else null
}

private fun splitLogoLabel(label: String, suffix: String): List<String>? {
    val index = label.indexOf(suffix)
    if (index <= 0) return null
    val prefix = label.substring(0, index)
    val suffixText = label.substring(index)
    return if (prefix.isNotBlank() && suffixText.isNotBlank()) listOf(prefix, suffixText) else null
}

private fun cleanEtfSummary(summary: String): String {
    var clean = summary
    listOf("국내상장 해외 ", "국내상장 ", "미국상장 ", "국내 상장 ", "미국 상장 ").forEach { token ->
        clean = clean.replace(token, "")
    }
    clean = clean
        .replace("대표적인 해외 ETF", "대표 ETF")
        .replace("대표적인 ETF", "대표 ETF")
        .replace("해외 ETF", "ETF")
        .replace("ETF입니다.", "ETF")
        .replace("ETF입니다", "ETF")
    while ("  " in clean) {
        clean = clean.replace("  ", " ")
    }
    return clean.trim()
}

private fun etfPriceText(value: Double, currency: String): String {
    if (!value.isFinite()) return "-"
    return if (currency == "KRW") {
        "${groupedInteger(value.toLong())}원"
    } else {
        fmtPx(value, currency)
    }
}

private fun etfSignedPriceText(value: Double, currency: String): String {
    if (!value.isFinite()) return "-"
    val sign = if (value >= 0.0) "+" else "-"
    val absolute = kotlin.math.abs(value)
    return if (currency == "KRW") {
        "$sign${groupedInteger(absolute.toLong())}원"
    } else {
        signedPx(value, currency)
    }
}

private fun domesticEtfTheme(name: String, theme: String): String {
    val combined = "$name $theme".replace(" ", "").uppercase()
    return when {
        "코스피200TR" in combined || "KODEX200TR" in combined -> "KOSPI 200TR"
        "인버스" in combined && ("코스피200" in combined || "KODEX200" in combined) -> "코스피200인버스"
        "레버리지" in combined && "코스닥150" in combined -> "코스닥150레버리지"
        "NASDAQ100" in combined || "나스닥100" in combined -> "NASDAQ 100"
        "S&P500" in combined -> "S&P 500"
        "2차전지" in combined -> "2차전지"
        "반도체" in combined -> "반도체"
        "KODEX200" in combined || "TIGER200" in combined || "코스피200" in combined || combined.endsWith("200") -> "KOSPI 200"
        else -> theme
            .replace("국내상장", "")
            .replace("국내", "")
            .replace("미국", "")
            .replace("ETF", "")
            .replace(" ", "")
            .ifBlank { "ETF" }
    }
}

private fun etfAvatarColor(name: String, category: String, theme: String): Color {
    return etfIssuerColor(name) ?: etfCategoryColor(category, theme)
}

private fun etfIssuerColor(name: String): Color? {
    val upper = name.uppercase()
    return when {
        "KODEX" in upper -> Color(0xFF0050AB)
        "TIGER" in upper -> Color(0xFFF07800)
        "ACE" in upper -> Color(0xFFD60D19)
        "SOL" in upper -> Color(0xFF0045AB)
        "RISE" in upper || "KBSTAR" in upper -> Color(0xFFFFD100)
        "HANARO" in upper -> Color(0xFF008C4C)
        "ARIRANG" in upper || "PLUS" in upper -> Color(0xFFE84118)
        "KOSEF" in upper -> Color(0xFF074A91)
        "TIMEFOLIO" in upper -> Color(0xFF612AAA)
        "WOORI" in upper -> Color(0xFF005AB8)
        "INVESCO" in upper -> Color(0xFF004580)
        "VANGUARD" in upper -> Color(0xFFAB0A1C)
        "SCHWAB" in upper -> Color(0xFF00648F)
        "ISHARES" in upper || "BLACKROCK" in upper -> Color(0xFF141926)
        "SPDR" in upper -> Color(0xFF8C1428)
        "ARK" in upper -> Color(0xFF0085BF)
        "VANECK" in upper -> Color(0xFF006BB3)
        "GLOBAL X" in upper -> Color(0xFF343A9E)
        "DIREXION" in upper -> Color(0xFFC7141F)
        "PROSHARES" in upper -> Color(0xFF0052A0)
        else -> null
    }
}

private fun etfAvatarTextColor(name: String): Color {
    return Color.White
}

private fun etfCategoryColor(category: String, theme: String): Color {
    if (theme.contains("반도체")) return Color(0xFF7B2398)
    return when (category) {
        "성장" -> Color(0xFF2254C7)
        "대표지수" -> Color(0xFF06696D)
        "소형주" -> Color(0xFFA34C14)
        "배당" -> Color(0xFF167A45)
        "섹터" -> Color(0xFFB43236)
        "리츠" -> Color(0xFF6B401F)
        "채권" -> Color(0xFF2E527F)
        "원자재" -> Color(0xFF946B0D)
        "테마" -> Color(0xFF6E2AA3)
        "해외지수" -> Color(0xFF4545A8)
        else -> Color(0xFF3D4A57)
    }
}

private fun exposureColor(index: Int): Color {
    return when (index % 5) {
        0 -> Color(0xFF2E63D1)
        1 -> QuantGreen
        2 -> QuantPurple
        3 -> QuantWarning
        else -> Color(0xFF007AFF)
    }
}
