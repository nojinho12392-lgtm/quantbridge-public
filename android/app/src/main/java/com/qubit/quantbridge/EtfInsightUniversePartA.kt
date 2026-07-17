package com.qubit.quantbridge

internal val etfInsightUniversePartA = listOf(
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
    )
)
