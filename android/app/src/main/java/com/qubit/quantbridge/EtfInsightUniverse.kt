package com.qubit.quantbridge

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
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.delay

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
