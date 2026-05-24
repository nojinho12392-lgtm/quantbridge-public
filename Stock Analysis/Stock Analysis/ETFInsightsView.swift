import Combine
import SwiftUI

struct ETFHolding: Identifiable, Hashable, Codable {
    var id: String { ticker }
    let ticker: String
    let name: String
    let weight: Double
}

struct ETFExposure: Identifiable, Hashable, Codable {
    var id: String { label }
    let label: String
    let weight: Double
}

struct ETFInsight: Identifiable, Hashable, Codable {
    var id: String { ticker }
    let ticker: String
    let name: String
    let region: String
    let category: String
    let theme: String
    let summary: String
    let expenseRatio: String
    let aum: String
    let distribution: String
    let outlook: String
    let risk: String
    let holdings: [ETFHolding]
    let exposures: [ETFExposure]
    var currentPrice: Double? = nil
    var return1M: Double? = nil
    var priceChange: Double? = nil

    var topHoldingText: String {
        holdings.first.map { "\($0.name) \(pct($0.weight, signed: false))" } ?? "구성 대기"
    }

    var displaySummary: String {
        cleanETFSummary(summary)
    }

    var priceTicker: String {
        if region == "KR", ticker.range(of: #"^\d{6}$"#, options: .regularExpression) != nil {
            return "\(ticker).KS"
        }
        return ticker
    }

    var priceLookupTickers: [String] {
        if region == "KR", ticker.range(of: #"^\d{6}$"#, options: .regularExpression) != nil {
            return ["\(ticker).KS", "\(ticker).KQ"]
        }
        return [ticker]
    }

    var detailCurrency: String {
        region == "KR" ? "KRW" : marketCurrency(for: priceTicker, market: region)
    }

    fileprivate func mergingPrice(_ metric: ETFPriceMetric?) -> ETFInsight {
        guard let metric else { return self }
        var copy = self
        copy.currentPrice = currentPrice ?? metric.currentPrice
        copy.return1M = return1M ?? metric.return1M
        return copy
    }

    var displayPriceChange: Double? {
        if let priceChange, priceChange.isFinite {
            return priceChange
        }
        guard let currentPrice, currentPrice.isFinite,
              let return1M, return1M.isFinite,
              return1M > -0.999 else {
            return nil
        }
        let basePrice = currentPrice / (1 + return1M)
        return currentPrice - basePrice
    }

    var detailMetrics: [StaticMetric] {
        [
            StaticMetric(label: "유형", value: category),
            StaticMetric(label: "테마", value: theme),
            StaticMetric(label: "총보수", value: expenseRatio, color: AppTheme.accent),
            StaticMetric(label: "AUM", value: aum),
            StaticMetric(label: "분배", value: distribution),
            StaticMetric(label: "Top 구성", value: topHoldingText, color: AppTheme.quality)
        ]
    }

    var detailSignals: [InvestmentSignal] {
        [
            InvestmentSignal(
                title: "ETF 가격 확인",
                detail: "기업 상세와 같은 차트에서 ETF 가격, 기간별 흐름, 변동성을 확인합니다.",
                systemImage: "chart.line.uptrend.xyaxis",
                color: AppTheme.accent
            ),
            InvestmentSignal(
                title: theme,
                detail: outlook,
                systemImage: "sparkles",
                color: AppTheme.quality
            ),
            InvestmentSignal(
                title: "주의",
                detail: risk,
                systemImage: "exclamationmark.triangle",
                color: AppTheme.warning
            )
        ]
    }
}

private enum ETFSortOption: String, CaseIterable, Identifiable {
    case alphabet = "알파벳순"
    case return1M = "수익률순"
    case ticker = "티커순"

    var id: String { rawValue }
}

let etfInsightUniverse: [ETFInsight] = [
    ETFInsight(
        ticker: "QQQ",
        name: "Invesco QQQ",
        region: "US",
        category: "성장",
        theme: "나스닥 100 · 빅테크",
        summary: "대형 기술주와 성장주 노출이 강한 대표 ETF입니다.",
        expenseRatio: "0.20%",
        aum: "$280B+",
        distribution: "분기",
        outlook: "AI, 클라우드, 반도체 모멘텀이 유지될 때 강합니다.",
        risk: "상위 빅테크 집중도가 높아 금리 상승과 기술주 조정에 민감합니다.",
        holdings: [
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.082),
            .init(ticker: "MSFT", name: "Microsoft", weight: 0.078),
            .init(ticker: "AAPL", name: "Apple", weight: 0.073),
            .init(ticker: "AMZN", name: "Amazon", weight: 0.052),
            .init(ticker: "AVGO", name: "Broadcom", weight: 0.047)
        ],
        exposures: [
            .init(label: "기술", weight: 0.55),
            .init(label: "커뮤니케이션", weight: 0.16),
            .init(label: "경기소비재", weight: 0.13),
            .init(label: "헬스케어", weight: 0.06)
        ]
    ),
    ETFInsight(
        ticker: "VOO",
        name: "Vanguard S&P 500",
        region: "US",
        category: "대표지수",
        theme: "미국 대형주",
        summary: "미국 대형주 시장 전체에 가까운 노출을 주는 핵심 지수 ETF입니다.",
        expenseRatio: "0.03%",
        aum: "$1T+",
        distribution: "분기",
        outlook: "미국 이익 사이클과 위험선호가 살아날 때 안정적인 코어 역할을 합니다.",
        risk: "시총가중 구조라 대형 기술주 비중이 높아질수록 QQQ와 중복 노출이 커집니다.",
        holdings: [
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.073),
            .init(ticker: "MSFT", name: "Microsoft", weight: 0.067),
            .init(ticker: "AAPL", name: "Apple", weight: 0.061),
            .init(ticker: "AMZN", name: "Amazon", weight: 0.038),
            .init(ticker: "META", name: "Meta", weight: 0.029)
        ],
        exposures: [
            .init(label: "기술", weight: 0.34),
            .init(label: "금융", weight: 0.13),
            .init(label: "헬스케어", weight: 0.10),
            .init(label: "경기소비재", weight: 0.10)
        ]
    ),
    ETFInsight(
        ticker: "SCHD",
        name: "Schwab US Dividend Equity",
        region: "US",
        category: "배당",
        theme: "미국 배당성장",
        summary: "배당 지속성과 현금흐름이 좋은 미국 대형주를 중심으로 담는 ETF입니다.",
        expenseRatio: "0.06%",
        aum: "$60B+",
        distribution: "분기",
        outlook: "금리 안정과 배당주 선호가 강해질 때 방어적 코어 역할을 기대할 수 있습니다.",
        risk: "고성장 기술주 랠리 구간에서는 성장주 ETF보다 상대 성과가 약할 수 있습니다.",
        holdings: [
            .init(ticker: "HD", name: "Home Depot", weight: 0.045),
            .init(ticker: "ABBV", name: "AbbVie", weight: 0.043),
            .init(ticker: "AMGN", name: "Amgen", weight: 0.041),
            .init(ticker: "KO", name: "Coca-Cola", weight: 0.039),
            .init(ticker: "PEP", name: "PepsiCo", weight: 0.036)
        ],
        exposures: [
            .init(label: "산업재", weight: 0.18),
            .init(label: "헬스케어", weight: 0.17),
            .init(label: "필수소비재", weight: 0.14),
            .init(label: "금융", weight: 0.13)
        ]
    ),
    ETFInsight(
        ticker: "SOXX",
        name: "iShares Semiconductor",
        region: "US",
        category: "섹터",
        theme: "반도체",
        summary: "반도체 설계, 장비, 파운드리 밸류체인에 집중 투자하는 섹터 ETF입니다.",
        expenseRatio: "0.35%",
        aum: "$10B+",
        distribution: "분기",
        outlook: "AI 설비투자와 메모리 업황 회복 기대가 강할 때 탄력이 큽니다.",
        risk: "업황 사이클, 미국 수출규제, 특정 대형주 비중 변화에 크게 흔들릴 수 있습니다.",
        holdings: [
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.095),
            .init(ticker: "AVGO", name: "Broadcom", weight: 0.083),
            .init(ticker: "AMD", name: "AMD", weight: 0.067),
            .init(ticker: "QCOM", name: "Qualcomm", weight: 0.061),
            .init(ticker: "TXN", name: "Texas Instruments", weight: 0.047)
        ],
        exposures: [
            .init(label: "반도체", weight: 0.82),
            .init(label: "장비", weight: 0.12),
            .init(label: "현금/기타", weight: 0.06)
        ]
    ),
    ETFInsight(
        ticker: "379800",
        name: "KODEX 미국S&P500",
        region: "KR",
        category: "해외지수",
        theme: "국내상장 미국 대형주",
        summary: "국내 계좌로 미국 S&P 500 노출을 얻는 대표적인 국내상장 해외 ETF입니다.",
        expenseRatio: "0.05%대",
        aum: "국내 대형",
        distribution: "상품별 상이",
        outlook: "환율과 미국 대형주 흐름을 동시에 확인해야 합니다.",
        risk: "원달러 환율, 환헤지 여부, 국내장 거래 시간과 미국장 시차를 함께 봐야 합니다.",
        holdings: [
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.073),
            .init(ticker: "MSFT", name: "Microsoft", weight: 0.067),
            .init(ticker: "AAPL", name: "Apple", weight: 0.061),
            .init(ticker: "AMZN", name: "Amazon", weight: 0.038),
            .init(ticker: "META", name: "Meta", weight: 0.029)
        ],
        exposures: [
            .init(label: "미국", weight: 0.96),
            .init(label: "현금/기타", weight: 0.04)
        ]
    ),
    ETFInsight(
        ticker: "069500",
        name: "KODEX 200",
        region: "KR",
        category: "대표지수",
        theme: "코스피 200",
        summary: "국내 대형주 시장을 대표하는 코스피200 추종 ETF입니다.",
        expenseRatio: "0.15%대",
        aum: "국내 대형",
        distribution: "분배",
        outlook: "반도체, 자동차, 금융 대형주의 이익 방향성이 핵심입니다.",
        risk: "국내 경기와 수출 사이클, 원화 흐름, 반도체 대형주 집중도에 민감합니다.",
        holdings: [
            .init(ticker: "005930.KS", name: "삼성전자", weight: 0.235),
            .init(ticker: "000660.KS", name: "SK하이닉스", weight: 0.094),
            .init(ticker: "373220.KS", name: "LG에너지솔루션", weight: 0.038),
            .init(ticker: "005380.KS", name: "현대차", weight: 0.031),
            .init(ticker: "207940.KS", name: "삼성바이오로직스", weight: 0.029)
        ],
        exposures: [
            .init(label: "IT", weight: 0.34),
            .init(label: "산업재", weight: 0.14),
            .init(label: "금융", weight: 0.12),
            .init(label: "헬스케어", weight: 0.09)
        ]
    ),
    ETFInsight(
        ticker: "VTI",
        name: "Vanguard Total Stock Market",
        region: "US",
        category: "대표지수",
        theme: "미국 전체시장",
        summary: "대형주부터 중소형주까지 미국 주식시장 전반을 한 번에 담는 ETF입니다.",
        expenseRatio: "0.03%",
        aum: "$400B+",
        distribution: "분기",
        outlook: "미국 시장 전체를 코어로 가져가고 싶을 때 가장 단순한 선택지입니다.",
        risk: "시총가중 구조라 대형 기술주 영향이 여전히 크고, 미국 시장 전체 조정에는 같이 흔들립니다.",
        holdings: [
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.066),
            .init(ticker: "MSFT", name: "Microsoft", weight: 0.060),
            .init(ticker: "AAPL", name: "Apple", weight: 0.055),
            .init(ticker: "AMZN", name: "Amazon", weight: 0.034),
            .init(ticker: "META", name: "Meta", weight: 0.026)
        ],
        exposures: [
            .init(label: "대형주", weight: 0.73),
            .init(label: "중형주", weight: 0.18),
            .init(label: "소형주", weight: 0.09)
        ]
    ),
    ETFInsight(
        ticker: "IWM",
        name: "iShares Russell 2000",
        region: "US",
        category: "소형주",
        theme: "미국 소형주",
        summary: "미국 중소형 기업 사이클에 투자하는 대표 ETF입니다.",
        expenseRatio: "0.19%",
        aum: "$60B+",
        distribution: "분기",
        outlook: "금리 하락, 경기 회복, 내수 민감주 반등 국면에서 탄력이 커질 수 있습니다.",
        risk: "이익 변동성과 부채 부담이 큰 기업 비중이 높아 대형주보다 변동성이 큽니다.",
        holdings: [
            .init(ticker: "FTAI", name: "FTAI Aviation", weight: 0.007),
            .init(ticker: "INSM", name: "Insmed", weight: 0.006),
            .init(ticker: "SFM", name: "Sprouts Farmers Market", weight: 0.005),
            .init(ticker: "AIT", name: "Applied Industrial", weight: 0.005),
            .init(ticker: "CRS", name: "Carpenter Technology", weight: 0.005)
        ],
        exposures: [
            .init(label: "산업재", weight: 0.18),
            .init(label: "금융", weight: 0.17),
            .init(label: "헬스케어", weight: 0.16),
            .init(label: "경기소비재", weight: 0.11)
        ]
    ),
    ETFInsight(
        ticker: "XLK",
        name: "Technology Select Sector SPDR",
        region: "US",
        category: "섹터",
        theme: "미국 기술주",
        summary: "S&P 500 안의 대형 기술주를 집중적으로 담는 섹터 ETF입니다.",
        expenseRatio: "0.09%대",
        aum: "$60B+",
        distribution: "분기",
        outlook: "AI, 소프트웨어, 반도체 사이클이 강할 때 시장 대비 초과 성과를 노릴 수 있습니다.",
        risk: "소수 대형주 집중도가 높아 기술주 밸류에이션 조정에 민감합니다.",
        holdings: [
            .init(ticker: "MSFT", name: "Microsoft", weight: 0.150),
            .init(ticker: "AAPL", name: "Apple", weight: 0.145),
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.130),
            .init(ticker: "AVGO", name: "Broadcom", weight: 0.055),
            .init(ticker: "CRM", name: "Salesforce", weight: 0.025)
        ],
        exposures: [
            .init(label: "소프트웨어", weight: 0.36),
            .init(label: "하드웨어", weight: 0.24),
            .init(label: "반도체", weight: 0.24),
            .init(label: "IT서비스", weight: 0.12)
        ]
    ),
    ETFInsight(
        ticker: "XLF",
        name: "Financial Select Sector SPDR",
        region: "US",
        category: "섹터",
        theme: "미국 금융",
        summary: "미국 은행, 카드, 보험, 거래소 기업을 담는 금융 섹터 ETF입니다.",
        expenseRatio: "0.09%대",
        aum: "$40B+",
        distribution: "분기",
        outlook: "장단기 금리차 개선과 신용 사이클 안정이 확인될 때 관심도가 높아집니다.",
        risk: "경기 둔화, 부실채권 확대, 규제 이슈에 민감합니다.",
        holdings: [
            .init(ticker: "BRK-B", name: "Berkshire Hathaway", weight: 0.130),
            .init(ticker: "JPM", name: "JPMorgan Chase", weight: 0.095),
            .init(ticker: "V", name: "Visa", weight: 0.055),
            .init(ticker: "MA", name: "Mastercard", weight: 0.045),
            .init(ticker: "BAC", name: "Bank of America", weight: 0.040)
        ],
        exposures: [
            .init(label: "은행", weight: 0.34),
            .init(label: "자본시장", weight: 0.24),
            .init(label: "보험", weight: 0.18),
            .init(label: "결제", weight: 0.14)
        ]
    ),
    ETFInsight(
        ticker: "XLV",
        name: "Health Care Select Sector SPDR",
        region: "US",
        category: "섹터",
        theme: "미국 헬스케어",
        summary: "제약, 의료기기, 보험, 바이오를 담는 방어적 섹터 ETF입니다.",
        expenseRatio: "0.09%대",
        aum: "$35B+",
        distribution: "분기",
        outlook: "경기 민감도가 낮은 실적 안정성을 찾을 때 포트폴리오 완충재가 될 수 있습니다.",
        risk: "약가 규제, 임상 실패, 대형 제약 특허만료 리스크를 함께 봐야 합니다.",
        holdings: [
            .init(ticker: "LLY", name: "Eli Lilly", weight: 0.120),
            .init(ticker: "UNH", name: "UnitedHealth", weight: 0.080),
            .init(ticker: "JNJ", name: "Johnson & Johnson", weight: 0.060),
            .init(ticker: "ABBV", name: "AbbVie", weight: 0.050),
            .init(ticker: "MRK", name: "Merck", weight: 0.045)
        ],
        exposures: [
            .init(label: "제약", weight: 0.34),
            .init(label: "의료보험", weight: 0.20),
            .init(label: "의료기기", weight: 0.18),
            .init(label: "바이오", weight: 0.13)
        ]
    ),
    ETFInsight(
        ticker: "VNQ",
        name: "Vanguard Real Estate",
        region: "US",
        category: "리츠",
        theme: "미국 리츠",
        summary: "미국 상장 리츠와 부동산 운영 기업에 분산 투자하는 ETF입니다.",
        expenseRatio: "0.13%대",
        aum: "$30B+",
        distribution: "분기",
        outlook: "금리 안정과 임대료 회복이 같이 나타날 때 배당형 자산으로 주목받습니다.",
        risk: "금리 상승, 상업용 부동산 공실, 리파이낸싱 부담에 민감합니다.",
        holdings: [
            .init(ticker: "PLD", name: "Prologis", weight: 0.075),
            .init(ticker: "AMT", name: "American Tower", weight: 0.060),
            .init(ticker: "EQIX", name: "Equinix", weight: 0.050),
            .init(ticker: "WELL", name: "Welltower", weight: 0.045),
            .init(ticker: "SPG", name: "Simon Property", weight: 0.035)
        ],
        exposures: [
            .init(label: "산업/물류", weight: 0.18),
            .init(label: "데이터센터", weight: 0.15),
            .init(label: "헬스케어", weight: 0.13),
            .init(label: "리테일", weight: 0.12)
        ]
    ),
    ETFInsight(
        ticker: "TLT",
        name: "iShares 20+ Year Treasury Bond",
        region: "US",
        category: "채권",
        theme: "미국 장기국채",
        summary: "미국 장기 국채 가격에 투자하는 대표적인 듀레이션 ETF입니다.",
        expenseRatio: "0.15%대",
        aum: "$50B+",
        distribution: "월",
        outlook: "경기 둔화와 금리 하락 기대가 강해질 때 방어와 반등을 동시에 노릴 수 있습니다.",
        risk: "장기금리 상승에는 가격 하락폭이 크게 나타날 수 있습니다.",
        holdings: [
            .init(ticker: "TLT", name: "20년 이상 미국 국채", weight: 0.420),
            .init(ticker: "UST30", name: "30년 미국 국채", weight: 0.300),
            .init(ticker: "UST20", name: "20년 미국 국채", weight: 0.220),
            .init(ticker: "CASH", name: "현금성 자산", weight: 0.030)
        ],
        exposures: [
            .init(label: "장기국채", weight: 0.94),
            .init(label: "현금/기타", weight: 0.06)
        ]
    ),
    ETFInsight(
        ticker: "GLD",
        name: "SPDR Gold Shares",
        region: "US",
        category: "원자재",
        theme: "금",
        summary: "금 가격 노출을 얻기 위한 대표 원자재 ETF입니다.",
        expenseRatio: "0.40%대",
        aum: "$60B+",
        distribution: "없음",
        outlook: "달러 약세, 실질금리 하락, 지정학 리스크가 커질 때 방어 자산으로 쓰입니다.",
        risk: "현금흐름이 없는 자산이라 실질금리 상승과 달러 강세에는 약할 수 있습니다.",
        holdings: [
            .init(ticker: "GLD", name: "금 현물", weight: 0.995),
            .init(ticker: "CASH", name: "현금/기타", weight: 0.005)
        ],
        exposures: [
            .init(label: "금", weight: 0.995),
            .init(label: "현금/기타", weight: 0.005)
        ]
    ),
    ETFInsight(
        ticker: "ARKK",
        name: "ARK Innovation ETF",
        region: "US",
        category: "테마",
        theme: "혁신성장",
        summary: "AI, 전기차, 바이오, 핀테크 등 고성장 혁신 기업을 담는 테마 ETF입니다.",
        expenseRatio: "0.75%대",
        aum: "$5B+",
        distribution: "연",
        outlook: "성장주 위험선호가 강해질 때 높은 베타로 반등할 수 있습니다.",
        risk: "높은 변동성, 편입종목 교체, 적자 성장주의 금리 민감도를 반드시 봐야 합니다.",
        holdings: [
            .init(ticker: "TSLA", name: "Tesla", weight: 0.110),
            .init(ticker: "COIN", name: "Coinbase", weight: 0.080),
            .init(ticker: "ROKU", name: "Roku", weight: 0.060),
            .init(ticker: "SHOP", name: "Shopify", weight: 0.055),
            .init(ticker: "CRSP", name: "CRISPR Therapeutics", weight: 0.040)
        ],
        exposures: [
            .init(label: "기술", weight: 0.36),
            .init(label: "헬스케어", weight: 0.24),
            .init(label: "경기소비재", weight: 0.18),
            .init(label: "금융기술", weight: 0.12)
        ]
    ),
    ETFInsight(
        ticker: "133690",
        name: "TIGER 미국나스닥100",
        region: "KR",
        category: "해외지수",
        theme: "국내상장 나스닥 100",
        summary: "국내 계좌로 나스닥100 대형 성장주에 접근하는 대표 ETF입니다.",
        expenseRatio: "0.07%대",
        aum: "국내 대형",
        distribution: "상품별 상이",
        outlook: "미국 성장주와 원달러 환율 흐름을 함께 보고 접근하기 좋습니다.",
        risk: "국내장 거래 시간과 미국장 가격 반영 시차, 환율 변동을 같이 확인해야 합니다.",
        holdings: [
            .init(ticker: "NVDA", name: "NVIDIA", weight: 0.082),
            .init(ticker: "MSFT", name: "Microsoft", weight: 0.078),
            .init(ticker: "AAPL", name: "Apple", weight: 0.073),
            .init(ticker: "AMZN", name: "Amazon", weight: 0.052),
            .init(ticker: "AVGO", name: "Broadcom", weight: 0.047)
        ],
        exposures: [
            .init(label: "미국", weight: 0.96),
            .init(label: "현금/기타", weight: 0.04)
        ]
    ),
    ETFInsight(
        ticker: "305720",
        name: "KODEX 2차전지산업",
        region: "KR",
        category: "테마",
        theme: "국내 2차전지",
        summary: "배터리 셀, 소재, 장비 기업에 집중 투자하는 국내 테마 ETF입니다.",
        expenseRatio: "0.45%대",
        aum: "국내 중대형",
        distribution: "상품별 상이",
        outlook: "전기차 수요 회복과 소재 가격 안정이 확인될 때 반등 탄력이 커질 수 있습니다.",
        risk: "업황 둔화, 가격 경쟁, 특정 대형주 집중도에 따라 변동성이 큽니다.",
        holdings: [
            .init(ticker: "373220.KS", name: "LG에너지솔루션", weight: 0.180),
            .init(ticker: "006400.KS", name: "삼성SDI", weight: 0.120),
            .init(ticker: "003670.KS", name: "포스코퓨처엠", weight: 0.090),
            .init(ticker: "247540.KQ", name: "에코프로비엠", weight: 0.080),
            .init(ticker: "005490.KS", name: "POSCO홀딩스", weight: 0.060)
        ],
        exposures: [
            .init(label: "셀", weight: 0.32),
            .init(label: "소재", weight: 0.38),
            .init(label: "장비", weight: 0.14),
            .init(label: "기타", weight: 0.16)
        ]
    ),
    ETFInsight(
        ticker: "091160",
        name: "KODEX 반도체",
        region: "KR",
        category: "섹터",
        theme: "국내 반도체",
        summary: "국내 반도체 대형주와 장비, 소재 기업을 함께 담는 섹터 ETF입니다.",
        expenseRatio: "0.45%대",
        aum: "국내 중대형",
        distribution: "분배",
        outlook: "메모리 업황 회복과 AI 서버 투자 확대가 핵심 모멘텀입니다.",
        risk: "메모리 가격 사이클, 수출 규제, 대형주 비중 변화에 민감합니다.",
        holdings: [
            .init(ticker: "005930.KS", name: "삼성전자", weight: 0.230),
            .init(ticker: "000660.KS", name: "SK하이닉스", weight: 0.210),
            .init(ticker: "403870.KQ", name: "HPSP", weight: 0.055),
            .init(ticker: "058470.KQ", name: "리노공업", weight: 0.050),
            .init(ticker: "000990.KS", name: "DB하이텍", weight: 0.045)
        ],
        exposures: [
            .init(label: "메모리", weight: 0.48),
            .init(label: "장비", weight: 0.24),
            .init(label: "소재/부품", weight: 0.18),
            .init(label: "파운드리", weight: 0.10)
        ]
    )
]

var featuredETFInsights: [ETFInsight] {
    Array(etfInsightUniverse.prefix(4))
}

struct ETFInsightsResponse: Decodable {
    let items: [ETFInsight]
    let count: Int
    let updatedAt: String?
    let source: String?

    init(items: [ETFInsight], count: Int, updatedAt: String?, source: String?) {
        self.items = items
        self.count = count
        self.updatedAt = updatedAt
        self.source = source
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decode([ETFInsight].self, forKey: .items)
        count = try container.decode(Int.self, forKey: .count)
        updatedAt = try container.decodeIfPresent(String.self, forKey: .updatedAt)
            ?? container.decodeIfPresent(String.self, forKey: .generatedAt)
        source = try container.decodeIfPresent(String.self, forKey: .source)
    }

    enum CodingKeys: String, CodingKey {
        case items
        case count
        case updatedAt = "updated_at"
        case generatedAt = "generated_at"
        case source
    }
}

private struct ETFPriceMetricsResponse: Decodable {
    let metrics: [ETFPriceMetric]
}

fileprivate struct ETFPriceMetric: Decodable {
    let ticker: String
    let currentPrice: Double?
    let return1M: Double?

    enum CodingKeys: String, CodingKey {
        case ticker = "Ticker"
        case currentPrice = "Current_Price"
        case return1M = "Return_1M"
    }
}

extension APIClientProtocol {
    func fetchETFInsightsPayload(limit: Int = 500, query: String = "") async throws -> ETFInsightsResponse {
        var queryItems = [URLQueryItem(name: "limit", value: "\(limit)")]
        let cleanQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanQuery.isEmpty {
            queryItems.append(URLQueryItem(name: "q", value: cleanQuery))
            queryItems.append(URLQueryItem(name: "refresh", value: "true"))
        }
        let response: ETFInsightsResponse = try await fetch(
            ["etfs"],
            queryItems: queryItems
        )
        let enrichedItems = await enrichETFPrices(response.items)
        return ETFInsightsResponse(
            items: enrichedItems,
            count: response.count,
            updatedAt: response.updatedAt,
            source: response.source
        )
    }

    func fetchETFInsights(limit: Int = 500, query: String = "") async throws -> [ETFInsight] {
        let response = try await fetchETFInsightsPayload(limit: limit, query: query)
        return response.items
    }

    private func enrichETFPrices(_ items: [ETFInsight]) async -> [ETFInsight] {
        let missing = items.filter { $0.currentPrice == nil || $0.return1M == nil }
        guard !missing.isEmpty else { return items }

        async let usMetrics = fetchETFPriceMetrics(missing.filter { $0.region == "US" }, market: "US")
        async let krMetrics = fetchETFPriceMetrics(missing.filter { $0.region == "KR" }, market: "KR")
        let (us, kr) = await (usMetrics, krMetrics)
        let metrics = us.merging(kr) { current, _ in current }

        return items.map { etf in
            let metric = etf.priceLookupTickers
                .lazy
                .compactMap { metrics[$0.uppercased()] }
                .first { $0.currentPrice != nil || $0.return1M != nil }
            return etf.mergingPrice(metric)
        }
    }

    private func fetchETFPriceMetrics(_ items: [ETFInsight], market: String) async -> [String: ETFPriceMetric] {
        let tickers = Array(Set(items.flatMap(\.priceLookupTickers).map { $0.uppercased() })).sorted()
        guard !tickers.isEmpty else { return [:] }

        do {
            let response: ETFPriceMetricsResponse = try await fetch(
                ["portfolio", market, "prices"],
                queryItems: [
                    URLQueryItem(name: "tickers", value: tickers.prefix(100).joined(separator: ",")),
                    URLQueryItem(name: "limit", value: "\(min(tickers.count, 100))"),
                    URLQueryItem(name: "refresh", value: "true")
                ]
            )
            return Dictionary(
                uniqueKeysWithValues: response.metrics.map { ($0.ticker.uppercased(), $0) }
            )
        } catch {
            return [:]
        }
    }
}

@MainActor
final class ETFInsightsStore: ObservableObject {
    @Published private(set) var items: [ETFInsight] = etfInsightUniverse
    @Published private(set) var searchItems: [ETFInsight] = []
    @Published private(set) var isSearchLoading = false
    @Published private(set) var searchErrorMessage: String?
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var source = "fallback"
    @Published private(set) var updatedAt: String?
    private var lastSearchQuery = ""
    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    var featured: [ETFInsight] {
        Array(items.prefix(4))
    }

    func load(force: Bool = false) async {
        if isLoading { return }
        if !force && source == "api" { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let response = try await api.fetchETFInsightsPayload()
            if !response.items.isEmpty {
                items = response.items
                source = response.source ?? "api"
                updatedAt = response.updatedAt
                errorMessage = nil
            }
        } catch {
            errorMessage = error.localizedDescription
            if items.isEmpty {
                items = etfInsightUniverse
                source = "fallback"
            }
        }
    }

    func search(_ query: String) async {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else {
            searchItems = []
            searchErrorMessage = nil
            lastSearchQuery = ""
            return
        }
        if clean == lastSearchQuery { return }
        lastSearchQuery = clean
        isSearchLoading = true
        defer { isSearchLoading = false }
        do {
            searchItems = try await api.fetchETFInsights(limit: 80, query: clean)
            searchErrorMessage = nil
        } catch {
            searchItems = []
            searchErrorMessage = error.localizedDescription
        }
    }
}

struct HomeETFInsightCard: View {
    let etf: ETFInsight
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 11) {
                HStack(spacing: 9) {
                    ETFInsightAvatar(
                        text: etf.ticker,
                        name: etf.name,
                        region: etf.region,
                        category: etf.category,
                        theme: etf.theme,
                        size: 36
                    )
                    VStack(alignment: .leading, spacing: 3) {
                        Text(etf.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.76)
                        Text("\(etf.region) · \(etf.theme)")
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                    }
                }

                Text(etf.displaySummary)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 10) {
                    ETFMetricPill(label: "보수", value: etf.expenseRatio)
                    ETFMetricPill(label: "Top", value: etf.topHoldingText)
                }
            }
            .frame(width: 258, alignment: .topLeading)
            .frame(minHeight: 142, alignment: .topLeading)
            .appCard(padding: 12)
        }
        .buttonStyle(.plain)
    }
}

struct ETFInsightsView: View {
    @StateObject private var store = ETFInsightsStore()
    @State private var region = "전체"
    @State private var category = "전체"
    @State private var query = ""
    @State private var sort: ETFSortOption = .alphabet
    @State private var selectedPriceETF: ETFInsight?
    @State private var showETFGuide = false

    private var cleanQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var searchSourceItems: [ETFInsight] {
        guard !cleanQuery.isEmpty else { return store.items }
        var seen = Set<String>()
        var merged: [ETFInsight] = []
        for item in store.items + store.searchItems {
            let key = item.ticker.uppercased()
            if seen.insert(key).inserted {
                merged.append(item)
            }
        }
        return merged
    }

    private var regions: [String] {
        ["전체"] + Array(Set(searchSourceItems.map(\.region))).sorted()
    }

    private var categories: [String] {
        ["전체"] + Array(Set(searchSourceItems.map(\.category))).sorted()
    }

    private var filtered: [ETFInsight] {
        let clean = cleanQuery.lowercased()
        let matches = searchSourceItems.filter { etf in
            (region == "전체" || etf.region == region) &&
                (category == "전체" || etf.category == category) &&
                (clean.isEmpty ||
                    etf.ticker.lowercased().contains(clean) ||
                    etf.name.lowercased().contains(clean) ||
                    etf.theme.lowercased().contains(clean))
        }
        return sortedETFs(matches, by: sort)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                ETFInsightsHeader(
                    count: filtered.count,
                    source: store.source,
                    updatedAt: store.updatedAt,
                    onOpenGuide: { showETFGuide = true }
                )
                AppSearchField(text: $query, prompt: "ETF 티커, 이름, 테마 검색")

                ETFSegmentRow(title: "시장", values: regions, selection: $region)
                ETFSegmentRow(title: "유형", values: categories, selection: $category)

                HStack(spacing: 7) {
                    HStack(spacing: 7) {
                        LucideIconView(icon: .pieChart, size: 17)
                            .foregroundStyle(AppTheme.accent)
                        Text("ETF 목록")
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                    }
                    Spacer()
                    ETFSortMenu(selection: $sort)
                }

                if filtered.isEmpty {
                    EmptyMsg(
                        icon: "magnifyingglass",
                        msg: "ETF 검색 결과 없음",
                        detail: store.isSearchLoading
                            ? "검색 범위를 넓혀 확인하고 있습니다."
                            : (store.searchErrorMessage ?? "다른 티커나 테마로 검색해보세요.")
                    )
                } else {
                    ForEach(filtered) { etf in
                        Button {
                            selectedPriceETF = etf
                        } label: {
                            ETFInsightRow(etf: etf)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding()
        }
        .appTabBarInset()
        .appScreenBackground()
        .navigationTitle("ETF 인사이트")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showETFGuide) {
            ETFEducationView()
        }
        .refreshable {
            await store.load(force: true)
        }
        .task {
            await store.load()
        }
        .task(id: "etf-price-auto") {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.standardPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("etf-insights", minInterval: 120) else { continue }
                await store.load(force: true)
            }
        }
        .task(id: query) {
            let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !clean.isEmpty else {
                await store.search("")
                return
            }
            region = "전체"
            category = "전체"
            try? await Task.sleep(nanoseconds: 260_000_000)
            guard !Task.isCancelled else { return }
            await store.search(clean)
        }
        .fullScreenCover(item: $selectedPriceETF) { etf in
            StockDetailSheet(
                ticker: etf.priceTicker,
                name: etf.name,
                currency: etf.detailCurrency,
                staticMetrics: etf.detailMetrics,
                investmentSignals: etf.detailSignals,
                etfHoldings: etf.holdings,
                startsOnChart: true
            )
        }
    }
}

private func sortedETFs(_ items: [ETFInsight], by sort: ETFSortOption) -> [ETFInsight] {
    switch sort {
    case .alphabet:
        return items.sorted {
            let nameOrder = $0.name.localizedCaseInsensitiveCompare($1.name)
            if nameOrder == .orderedSame {
                return $0.ticker.localizedStandardCompare($1.ticker) == .orderedAscending
            }
            return nameOrder == .orderedAscending
        }
    case .return1M:
        return items.sorted {
            let left = $0.return1M ?? -Double.greatestFiniteMagnitude
            let right = $1.return1M ?? -Double.greatestFiniteMagnitude
            if left == right {
                return $0.ticker.localizedStandardCompare($1.ticker) == .orderedAscending
            }
            return left > right
        }
    case .ticker:
        return items.sorted {
            $0.ticker.localizedStandardCompare($1.ticker) == .orderedAscending
        }
    }
}

private struct ETFSortMenu: View {
    @Binding var selection: ETFSortOption
    @State private var isPresented = false

    var body: some View {
        Button {
            isPresented = true
        } label: {
            HStack(spacing: 5) {
                LucideIconView(icon: .slidersHorizontal, size: 13)
                    .foregroundStyle(AppTheme.accent)
                Text(selection.rawValue)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                Capsule()
                    .fill(AppTheme.elevatedCard)
                    .overlay(
                        Capsule()
                            .stroke(AppTheme.hairline, lineWidth: 0.5)
                    )
            )
        }
        .buttonStyle(.plain)
        .popover(isPresented: $isPresented) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    LucideIconView(icon: .slidersHorizontal, size: 16)
                        .foregroundStyle(AppTheme.accent)
                    Text("정렬 기준")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 2)
                .padding(.bottom, 2)

                ForEach(ETFSortOption.allCases) { option in
                    let selected = selection == option
                    Button {
                        selection = option
                        isPresented = false
                    } label: {
                        HStack(spacing: 10) {
                            LucideIconView(icon: etfSortIcon(for: option), size: 16)
                                .foregroundStyle(selected ? AppTheme.accent : AppTheme.secondaryText)
                            Text(option.rawValue)
                                .font(.system(size: 15, weight: selected ? .bold : .semibold))
                                .foregroundStyle(selected ? AppTheme.accent : AppTheme.primaryText)
                            Spacer(minLength: 8)
                            if selected {
                                LucideIconView(icon: .check, size: 16)
                                    .foregroundStyle(AppTheme.accent)
                            }
                        }
                        .padding(.horizontal, 12)
                        .frame(height: 44)
                        .background(
                            RoundedRectangle(cornerRadius: 17, style: .continuous)
                                .fill(selected ? AppTheme.accent.opacity(0.10) : Color.clear)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(10)
            .frame(width: 214)
            .background(AppTheme.card)
        }
    }
}

private func etfSortIcon(for option: ETFSortOption) -> LucideIcon {
    switch option {
    case .alphabet:
        return .listOrdered
    case .return1M:
        return .trendingUp
    case .ticker:
        return .target
    }
}

private struct ETFInsightsHeader: View {
    let count: Int
    let source: String
    let updatedAt: String?
    let onOpenGuide: () -> Void

    var body: some View {
        Button(action: onOpenGuide) {
            VStack(alignment: .leading, spacing: 9) {
                HStack(spacing: 8) {
                    LucideIconView(icon: .pieChart, size: 22)
                        .foregroundStyle(AppTheme.accent)
                    Text("ETF 인사이트")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                    LucideIconView(icon: .lightbulb, size: 16)
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 30, height: 30)
                        .background(AppTheme.accent.opacity(0.10), in: Circle())
                }
                Text("대표 ETF의 구성종목, 섹터 노출, 전망과 리스크를 한 곳에서 봅니다.")
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                Text("눌러서 ETF가 무엇인지 자세히 보기")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.accent)
                HStack(spacing: 7) {
                    Text("\(count)개 ETF · \(source == "fallback" ? "대표 노출" : "저장 데이터") 기준")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.accent)
                    DataFreshnessBadge(
                        source: source == "api" ? "storage" : source,
                        updatedAt: updatedAt,
                        compact: true
                    )
                }
            }
            .appCard(padding: 14)
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
    }
}

private struct ETFGuidePoint: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let icon: LucideIcon
}

private struct ETFEducationView: View {
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                ETFGuideHeroCard()
                ETFGuideSection(
                    icon: .pieChart,
                    title: "ETF란 무엇인가요?",
                    headline: "여러 자산을 한 바구니에 담아 주식처럼 거래하는 펀드입니다.",
                    bodyText: "ETF는 Exchange Traded Fund의 약자입니다. 특정 지수, 산업, 테마, 채권, 원자재, 통화 같은 바구니를 따라가도록 설계되고, 일반 주식처럼 장중에 사고팔 수 있습니다.",
                    points: [
                        ETFGuidePoint(title: "한 종목으로 분산", detail: "S&P 500 ETF를 사면 개별 500개 기업을 하나씩 고르지 않아도 시장 전체에 가까운 노출을 얻습니다.", icon: .listOrdered),
                        ETFGuidePoint(title: "거래는 주식처럼", detail: "펀드지만 거래소에 상장되어 있어 가격이 장중에 움직이고, 지정가/시장가 주문이 가능합니다.", icon: .chartCandlestick),
                        ETFGuidePoint(title: "핵심은 추종 대상", detail: "ETF 이름보다 어떤 지수와 규칙을 따라가는지가 실제 성과와 위험을 결정합니다.", icon: .target)
                    ]
                )
                ETFGuideSection(
                    icon: .slidersHorizontal,
                    title: "큐빗에서 ETF를 읽는 순서",
                    headline: "가격 차트보다 먼저 ETF의 성격을 파악하면 판단 속도가 빨라집니다.",
                    bodyText: "ETF는 기업 실적보다 구성 방식이 중요합니다. 같은 AI ETF라도 반도체 중심인지, 소프트웨어 중심인지, 대형주 집중인지에 따라 완전히 다른 움직임을 보일 수 있습니다.",
                    points: [
                        ETFGuidePoint(title: "1. 추종 대상", detail: "지수형, 섹터형, 테마형, 채권형, 원자재형인지 먼저 구분합니다.", icon: .target),
                        ETFGuidePoint(title: "2. Top10 비중", detail: "상위 종목 비중이 높으면 사실상 몇 개 기업에 집중 투자하는 것과 비슷합니다.", icon: .pieChart),
                        ETFGuidePoint(title: "3. 총보수와 AUM", detail: "보수는 장기 수익률을 갉아먹고, AUM이 너무 작으면 유동성이나 상장 유지 리스크가 커질 수 있습니다.", icon: .database)
                    ]
                )
                ETFGuideChecklistCard()
                ETFGuideSection(
                    icon: .triangleAlert,
                    title: "주의해야 할 ETF",
                    headline: "ETF라고 해서 모두 분산이 잘 된 것은 아닙니다.",
                    bodyText: "레버리지, 인버스, 초협소 테마, 거래량이 작은 ETF는 일반 장기 보유 ETF와 성격이 다릅니다. 특히 레버리지/인버스 ETF는 하루 수익률을 목표로 설계되는 경우가 많아 장기 성과가 직관과 다를 수 있습니다.",
                    points: [
                        ETFGuidePoint(title: "레버리지/인버스", detail: "방향을 맞춰도 장기 보유 중 복리 효과와 변동성 때문에 기대와 다른 결과가 나올 수 있습니다.", icon: .zap),
                        ETFGuidePoint(title: "테마 집중", detail: "AI, 우주, 2차전지처럼 이름은 넓어도 실제 구성은 일부 기업에 크게 쏠릴 수 있습니다.", icon: .triangleAlert),
                        ETFGuidePoint(title: "환율과 금리", detail: "해외 ETF와 채권 ETF는 주가 외에도 환율, 금리 변화가 성과에 크게 반영됩니다.", icon: .globe2)
                    ]
                )
            }
            .padding()
        }
        .appScreenBackground()
        .navigationTitle("ETF 기본 가이드")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ETFGuideHeroCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                LucideIconView(icon: .lightbulb, size: 24)
                    .foregroundStyle(AppTheme.accent)
                    .frame(width: 48, height: 48)
                    .background(AppTheme.accent.opacity(0.12), in: Circle())
                VStack(alignment: .leading, spacing: 4) {
                    Text("핵심 결론")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.accent)
                    Text("ETF는 종목 하나가 아니라 투자 규칙을 사는 상품입니다.")
                        .font(.title2.weight(.bold))
                        .lineSpacing(2)
                        .foregroundStyle(AppTheme.primaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Text("따라서 ETF를 볼 때는 오늘 가격이 올랐는지보다, 어떤 시장을 따라가는지, 무엇을 얼마나 담고 있는지, 비용과 유동성이 적절한지부터 확인하는 것이 좋습니다.")
                .font(.system(size: 16, weight: .regular))
                .lineSpacing(4)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .appCard(padding: 16, role: .decision)
    }
}

private struct ETFGuideSection: View {
    let icon: LucideIcon
    let title: String
    let headline: String
    let bodyText: String
    let points: [ETFGuidePoint]

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                LucideIconView(icon: icon, size: 18)
                    .foregroundStyle(AppTheme.accent)
                Text(title)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            Text(headline)
                .font(.title3.weight(.bold))
                .lineSpacing(2)
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            Text(bodyText)
                .font(.system(size: 15, weight: .regular))
                .lineSpacing(4)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 10) {
                ForEach(points) { point in
                    ETFGuidePointRow(point: point)
                }
            }
        }
        .appCard(padding: 16)
    }
}

private struct ETFGuidePointRow: View {
    let point: ETFGuidePoint

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            LucideIconView(icon: point.icon, size: 15)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 28, height: 28)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 3) {
                Text(point.title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(point.detail)
                    .font(.system(size: 15, weight: .regular))
                    .lineSpacing(3)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct ETFGuideChecklistCard: View {
    private let checks = [
        ETFGuidePoint(title: "구성종목", detail: "Top10 비중과 특정 기업 쏠림을 확인합니다.", icon: .listOrdered),
        ETFGuidePoint(title: "섹터/지역 노출", detail: "내가 생각한 ETF 성격과 실제 노출이 같은지 봅니다.", icon: .globe2),
        ETFGuidePoint(title: "총보수", detail: "장기 보유라면 낮은 보수가 누적 성과에 유리합니다.", icon: .slidersHorizontal),
        ETFGuidePoint(title: "가격 흐름", detail: "단기 변동성과 1개월 수익률이 시장 상황과 맞는지 비교합니다.", icon: .lineChart)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                LucideIconView(icon: .check, size: 18)
                    .foregroundStyle(AppTheme.quality)
                Text("ETF 판단 체크리스트")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            Text("목록에서 ETF를 고를 때 아래 4가지만 먼저 확인해도 대부분의 오해를 줄일 수 있습니다.")
                .font(.system(size: 15, weight: .regular))
                .lineSpacing(4)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(checks) { point in
                ETFGuidePointRow(point: point)
            }
        }
        .appCard(padding: 16)
    }
}

private struct ETFSegmentRow: View {
    let title: String
    let values: [String]
    @Binding var selection: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(values, id: \.self) { value in
                        Button {
                            selection = value
                        } label: {
                            Text(value)
                                .font(.caption.weight(.bold))
                                .foregroundStyle(selection == value ? Color.white : AppTheme.secondaryText)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(selection == value ? AppTheme.accent : AppTheme.elevatedCard, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

private struct ETFInsightRow: View {
    let etf: ETFInsight

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            ETFInsightAvatar(
                text: etf.ticker,
                name: etf.name,
                region: etf.region,
                category: etf.category,
                theme: etf.theme,
                size: 42
            )
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(etf.name)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Text(etf.ticker)
                        .font(.caption.monospacedDigit().weight(.bold))
                        .foregroundStyle(AppTheme.accent)
                }
                Text(etf.displaySummary)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
                HStack(spacing: 7) {
                    ETFInlineMetric(icon: .target, label: "유형", value: etf.category)
                    ETFInlineMetric(icon: .slidersHorizontal, label: "보수", value: etf.expenseRatio)
                }
                .lineLimit(1)
            }
            Spacer(minLength: 8)
            ETFPriceSummary(etf: etf)
        }
        .appCard(padding: 12)
    }
}

private struct ETFInlineMetric: View {
    let icon: LucideIcon
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 3) {
            LucideIconView(icon: icon, size: 10)
            Text("\(label) \(value)")
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(AppTheme.secondaryText)
        .lineLimit(1)
        .minimumScaleFactor(0.78)
    }
}

private struct ETFPriceSummary: View {
    let etf: ETFInsight

    private var currency: String {
        etf.detailCurrency
    }

    private var moveColor: Color {
        (etf.return1M ?? etf.displayPriceChange ?? 0) >= 0 ? AppTheme.positive : AppTheme.negative
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 3) {
            AnimatedPriceText(
                text: etf.currentPrice.map { etfPriceText($0, currency: currency) } ?? "-",
                font: .subheadline.monospacedDigit().weight(.bold),
                color: AppTheme.primaryText
            )
                .lineLimit(1)
                .minimumScaleFactor(0.72)
            if let move = etf.displayPriceChange, let changePct = etf.return1M {
                Text("\(etfSignedPriceText(move, currency: currency)) (\(pct(changePct)))")
                    .font(.caption.monospacedDigit().weight(.bold))
                    .foregroundStyle(moveColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.70)
            }
        }
        .frame(minWidth: 76, alignment: .trailing)
    }
}

private struct ETFInsightDetailView: View {
    let etf: ETFInsight

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ETFDetailHeader(etf: etf)
                ETFHoldingsSection(etf: etf)
                ETFExposureSection(etf: etf)
                ETFOutlookSection(etf: etf)
            }
            .padding()
        }
        .appScreenBackground()
        .navigationTitle(etf.ticker)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct ETFDetailHeader: View {
    let etf: ETFInsight

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 12) {
                ETFInsightAvatar(
                    text: etf.ticker,
                    name: etf.name,
                    region: etf.region,
                    category: etf.category,
                    theme: etf.theme,
                    size: 52
                )
                VStack(alignment: .leading, spacing: 4) {
                    Text(etf.name)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text("\(etf.ticker) · \(etf.region) · \(etf.theme)")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                }
            }

            Text(etf.displaySummary)
                .font(.subheadline)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ETFDetailMetric(title: "총보수", value: etf.expenseRatio)
                ETFDetailMetric(title: "AUM", value: etf.aum)
                ETFDetailMetric(title: "분배", value: etf.distribution)
                ETFDetailMetric(title: "유형", value: etf.category)
            }
        }
        .appCard(padding: 14)
    }
}

private struct ETFHoldingsSection: View {
    let etf: ETFInsight

    private var maxWeight: Double {
        max(etf.holdings.map(\.weight).max() ?? 0.01, 0.01)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 7) {
                LucideIconView(icon: .listOrdered, size: 17)
                    .foregroundStyle(AppTheme.accent)
                Text("Top 구성종목")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            ForEach(etf.holdings) { holding in
                VStack(alignment: .leading, spacing: 7) {
                    HStack(spacing: 10) {
                        CompanyLogoView(
                            ticker: holding.ticker,
                            currency: marketCurrency(for: holding.ticker),
                            size: 32
                        )
                        VStack(alignment: .leading, spacing: 2) {
                            Text(holding.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AppTheme.primaryText)
                                .lineLimit(1)
                            Text(holding.ticker)
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(AppTheme.secondaryText)
                        }
                        Spacer()
                        Text(pct(holding.weight, signed: false))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(AppTheme.accent)
                            .monospacedDigit()
                    }

                    GeometryReader { proxy in
                        Capsule()
                            .fill(AppTheme.elevatedCard)
                            .overlay(alignment: .leading) {
                                Capsule()
                                    .fill(AppTheme.accent)
                                    .frame(width: max(6, proxy.size.width * holding.weight / maxWeight))
                            }
                    }
                    .frame(height: 6)
                }
                if holding.id != etf.holdings.last?.id {
                    Divider()
                }
            }
        }
        .appCard(padding: 14)
    }
}

private struct ETFExposureSection: View {
    let etf: ETFInsight

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 7) {
                LucideIconView(icon: .globe2, size: 17)
                    .foregroundStyle(AppTheme.accent)
                Text("섹터/지역 노출")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            ForEach(Array(etf.exposures.enumerated()), id: \.element.id) { index, exposure in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(exposure.label)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                        Spacer()
                        Text(pct(exposure.weight, signed: false))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AppTheme.secondaryText)
                    }
                    GeometryReader { proxy in
                        Capsule()
                            .fill(AppTheme.elevatedCard)
                            .overlay(alignment: .leading) {
                                Capsule()
                                    .fill(exposureColor(index))
                                    .frame(width: max(6, proxy.size.width * exposure.weight))
                            }
                    }
                    .frame(height: 8)
                }
            }
        }
        .appCard(padding: 14)
    }
}

private struct ETFOutlookSection: View {
    let etf: ETFInsight

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 7) {
                LucideIconView(icon: .sparkles, size: 17)
                    .foregroundStyle(AppTheme.accent)
                Text("전망과 리스크")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            ETFOutlookRow(icon: .trendingUp, title: "전망", text: etf.outlook, color: AppTheme.quality)
            ETFOutlookRow(icon: .triangleAlert, title: "주의", text: etf.risk, color: AppTheme.warning)
        }
        .appCard(padding: 14)
    }
}

private struct ETFOutlookRow: View {
    let icon: LucideIcon
    let title: String
    let text: String
    let color: Color

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            LucideIconView(icon: icon, size: 14)
                .foregroundStyle(color)
                .frame(width: 22, height: 22)
                .background(color.opacity(0.10), in: Circle())
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(color)
                Text(text)
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct ETFDetailMetric: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct ETFMetricPill: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct ETFInsightAvatar: View {
    let text: String
    let name: String
    let region: String
    let category: String
    let theme: String
    let size: CGFloat

    var body: some View {
        let background = etfAvatarColor(name: name, category: category, theme: theme)
        let foreground = etfAvatarTextColor(name: name)

        Circle()
            .fill(background)
            .frame(width: size, height: size)
            .overlay {
                if region == "KR" {
                    let lines = domesticETFLogoLines(name: name, theme: theme)
                    let fontSize = domesticETFLogoFontSize(size: size, lines: lines)
                    VStack(spacing: -2) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                            Text(line)
                                .font(.system(size: fontSize, weight: .heavy, design: .rounded))
                                .lineLimit(1)
                                .minimumScaleFactor(0.58)
                        }
                    }
                    .foregroundStyle(foreground)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, max(2, size * 0.07))
                } else {
                    Text(shortText)
                        .font(.system(size: max(10, size * 0.25), weight: .bold, design: .rounded))
                        .foregroundStyle(foreground)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
            }
    }

    private var shortText: String {
        text.count > 4 ? String(text.prefix(3)) : text
    }
}

private func domesticETFLogoLines(name: String, theme: String) -> [String] {
    let label = domesticETFTheme(name: name, theme: theme)
    if let preferredLines = preferredDomesticETFLogoLines(label) {
        return preferredLines
    }
    switch label {
    case "KOSPI 200":
        return ["코스피", "200"]
    case "KOSPI 200TR":
        return ["코스피", "200TR"]
    case "NASDAQ 100":
        return ["나스닥", "100"]
    case "S&P 500":
        return ["S&P", "500"]
    case "2차전지":
        return ["2차", "전지"]
    default:
        return fittedDomesticETFLogoLines(label)
    }
}

private func domesticETFLogoFontSize(size: CGFloat, lines: [String]) -> CGFloat {
    let widest = lines.map(domesticETFLogoWidthScore).max() ?? 1
    let ratio: CGFloat
    if widest <= 2.4 {
        ratio = 0.285
    } else if widest <= 3.2 {
        ratio = 0.255
    } else if widest <= 4.05 {
        ratio = 0.215
    } else if widest <= 5.0 {
        ratio = 0.170
    } else {
        ratio = 0.145
    }
    return max(6.5, size * ratio)
}

private func domesticETFLogoWidthScore(_ text: String) -> CGFloat {
    var score: CGFloat = 0
    for scalar in text.unicodeScalars {
        switch scalar.value {
        case 0xAC00...0xD7A3, 0x3130...0x318F:
            score += 1.0
        case 0x0030...0x0039:
            score += 0.56
        case 0x0041...0x005A, 0x0061...0x007A:
            score += 0.60
        case 0x0020:
            score += 0.20
        default:
            score += 0.38
        }
    }
    return max(score, 1)
}

private func preferredDomesticETFLogoLines(_ label: String) -> [String]? {
    let compact = label.replacingOccurrences(of: " ", with: "").uppercased()
    if compact.contains("WTI") || compact.contains("원유") {
        return ["WTI", "원유"]
    }
    if compact.contains("달러"), compact.contains("레버리지") {
        return ["달러", "레버리지"]
    }
    if compact.contains("달러"), compact.contains("인버스") {
        return ["달러", "인버스"]
    }
    if compact.contains("코스피200TR") || compact.contains("KOSPI200TR") {
        return ["코스피", "200TR"]
    }
    if compact.contains("코스피200"), compact.contains("인버스") {
        return ["코스피200", "인버스"]
    }
    if compact.contains("코스닥150"), compact.contains("레버리지") {
        return ["코스닥150", "레버리지"]
    }
    if compact.contains("코스닥150"), compact.contains("인버스") {
        return ["코스닥150", "인버스"]
    }
    if compact.contains("코스닥150") {
        return ["코스닥", "150"]
    }
    if compact.contains("다우존스30") {
        return ["다우존스", "30"]
    }
    if compact.contains("국고채3년") {
        return ["국고채", "3년"]
    }
    if compact.contains("빅테크집중") {
        return ["빅테크", "집중"]
    }
    if compact.contains("테크TOP10") {
        return ["테크", "TOP10"]
    }
    if compact.contains("미디어엔터") {
        return ["미디어", "엔터"]
    }
    if compact.contains("BBIG성장") {
        return ["BBIG", "성장"]
    }
    if compact.contains("여행레저") {
        return ["여행", "레저"]
    }
    return nil
}

private func fittedDomesticETFLogoLines(_ label: String) -> [String] {
    if label.count <= 4 {
        return [label]
    }
    if let digitSplit = splitTrailingNumberLogoLabel(label) {
        return digitSplit
    }
    for term in ["레버리지", "인버스", "선물", "채권"] {
        if let split = splitLogoLabel(label, beforeSuffix: term) {
            return split
        }
    }
    let midpoint = max(2, label.count / 2)
    let splitIndex = label.index(label.startIndex, offsetBy: midpoint)
    return [String(label[..<splitIndex]), String(label[splitIndex...])]
}

private func splitTrailingNumberLogoLabel(_ label: String) -> [String]? {
    guard let range = label.range(of: #"\d+$"#, options: .regularExpression),
          range.lowerBound > label.startIndex else {
        return nil
    }
    let prefix = String(label[..<range.lowerBound])
    let suffix = String(label[range])
    guard !prefix.isEmpty, !suffix.isEmpty else { return nil }
    return [prefix, suffix]
}

private func splitLogoLabel(_ label: String, beforeSuffix suffix: String) -> [String]? {
    guard let range = label.range(of: suffix),
          range.lowerBound > label.startIndex else {
        return nil
    }
    let prefix = String(label[..<range.lowerBound])
    let suffixText = String(label[range.lowerBound...])
    guard !prefix.isEmpty, !suffixText.isEmpty else { return nil }
    return [prefix, suffixText]
}

private func cleanETFSummary(_ summary: String) -> String {
    var clean = summary
    for token in ["국내상장 해외 ", "국내상장 ", "미국상장 ", "국내 상장 ", "미국 상장 "] {
        clean = clean.replacingOccurrences(of: token, with: "")
    }
    clean = clean.replacingOccurrences(of: "대표적인 해외 ETF", with: "대표 ETF")
    clean = clean.replacingOccurrences(of: "대표적인 ETF", with: "대표 ETF")
    clean = clean.replacingOccurrences(of: "해외 ETF", with: "ETF")
    clean = clean.replacingOccurrences(of: "ETF입니다.", with: "ETF")
    clean = clean.replacingOccurrences(of: "ETF입니다", with: "ETF")
    while clean.contains("  ") {
        clean = clean.replacingOccurrences(of: "  ", with: " ")
    }
    return clean.trimmingCharacters(in: .whitespacesAndNewlines)
}

private func etfPriceText(_ value: Double, currency: String) -> String {
    guard value.isFinite else { return "-" }
    if currency == "KRW" {
        return "\(etfGroupedInteger(value))원"
    }
    return fmtPx(value, currency: currency)
}

private func etfSignedPriceText(_ value: Double, currency: String) -> String {
    guard value.isFinite else { return "-" }
    let sign = value >= 0 ? "+" : "-"
    let absolute = abs(value)
    if currency == "KRW" {
        return "\(sign)\(etfGroupedInteger(absolute))원"
    }
    return signedPx(value, currency: currency)
}

private func etfGroupedInteger(_ value: Double) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = 0
    return formatter.string(from: NSNumber(value: value.rounded())) ?? String(format: "%.0f", value)
}

private func domesticETFTheme(name: String, theme: String) -> String {
    let combined = "\(name) \(theme)"
        .replacingOccurrences(of: " ", with: "")
        .uppercased()
    if combined.contains("코스피200TR") || combined.contains("KODEX200TR") {
        return "KOSPI 200TR"
    }
    if combined.contains("인버스"), combined.contains("코스피200") || combined.contains("KODEX200") {
        return "코스피200인버스"
    }
    if combined.contains("레버리지"), combined.contains("코스닥150") {
        return "코스닥150레버리지"
    }
    if combined.contains("NASDAQ100") || combined.contains("나스닥100") {
        return "NASDAQ 100"
    }
    if combined.contains("S&P500") {
        return "S&P 500"
    }
    if combined.contains("2차전지") {
        return "2차전지"
    }
    if combined.contains("반도체") {
        return "반도체"
    }
    if combined.contains("KODEX200") || combined.contains("TIGER200") || combined.contains("코스피200") || combined.hasSuffix("200") {
        return "KOSPI 200"
    }

    var clean = theme
    for token in ["국내상장", "국내", "미국", "ETF"] {
        clean = clean.replacingOccurrences(of: token, with: "")
    }
    clean = clean.replacingOccurrences(of: " ", with: "")
    return clean.isEmpty ? "ETF" : clean
}

private func etfAvatarColor(name: String, category: String, theme: String) -> Color {
    if let issuerColor = etfIssuerColor(name: name) {
        return issuerColor
    }
    return etfCategoryColor(category: category, theme: theme)
}

private func etfIssuerColor(name: String) -> Color? {
    let upper = name.uppercased()
    if upper.contains("KODEX") { return Color(red: 0.00, green: 0.31, blue: 0.67) }
    if upper.contains("TIGER") { return Color(red: 0.94, green: 0.46, blue: 0.00) }
    if upper.contains("ACE") { return Color(red: 0.84, green: 0.05, blue: 0.10) }
    if upper.contains("SOL") { return Color(red: 0.00, green: 0.27, blue: 0.67) }
    if upper.contains("RISE") || upper.contains("KBSTAR") { return Color(red: 1.00, green: 0.82, blue: 0.00) }
    if upper.contains("HANARO") { return Color(red: 0.00, green: 0.55, blue: 0.30) }
    if upper.contains("ARIRANG") || upper.contains("PLUS") { return Color(red: 0.91, green: 0.25, blue: 0.09) }
    if upper.contains("KOSEF") { return Color(red: 0.03, green: 0.29, blue: 0.57) }
    if upper.contains("TIMEFOLIO") { return Color(red: 0.38, green: 0.17, blue: 0.66) }
    if upper.contains("WOORI") { return Color(red: 0.00, green: 0.35, blue: 0.72) }
    if upper.contains("INVESCO") { return Color(red: 0.00, green: 0.27, blue: 0.50) }
    if upper.contains("VANGUARD") { return Color(red: 0.67, green: 0.04, blue: 0.11) }
    if upper.contains("SCHWAB") { return Color(red: 0.00, green: 0.39, blue: 0.56) }
    if upper.contains("ISHARES") || upper.contains("BLACKROCK") { return Color(red: 0.08, green: 0.10, blue: 0.15) }
    if upper.contains("SPDR") { return Color(red: 0.55, green: 0.08, blue: 0.16) }
    if upper.contains("ARK") { return Color(red: 0.00, green: 0.52, blue: 0.75) }
    if upper.contains("VANECK") { return Color(red: 0.00, green: 0.42, blue: 0.70) }
    if upper.contains("GLOBAL X") { return Color(red: 0.20, green: 0.23, blue: 0.62) }
    if upper.contains("DIREXION") { return Color(red: 0.78, green: 0.08, blue: 0.12) }
    if upper.contains("PROSHARES") { return Color(red: 0.00, green: 0.32, blue: 0.63) }
    return nil
}

private func etfAvatarTextColor(name: String) -> Color {
    return .white
}

private func etfCategoryColor(category: String, theme: String) -> Color {
    if theme.contains("반도체") {
        return Color(red: 0.50, green: 0.13, blue: 0.68)
    }
    switch category {
    case "성장":
        return Color(red: 0.13, green: 0.33, blue: 0.78)
    case "대표지수":
        return Color(red: 0.02, green: 0.40, blue: 0.43)
    case "소형주":
        return Color(red: 0.64, green: 0.30, blue: 0.08)
    case "배당":
        return Color(red: 0.08, green: 0.48, blue: 0.27)
    case "섹터":
        return Color(red: 0.72, green: 0.20, blue: 0.22)
    case "리츠":
        return Color(red: 0.42, green: 0.25, blue: 0.12)
    case "채권":
        return Color(red: 0.18, green: 0.32, blue: 0.50)
    case "원자재":
        return Color(red: 0.58, green: 0.42, blue: 0.05)
    case "테마":
        return Color(red: 0.43, green: 0.16, blue: 0.64)
    case "해외지수":
        return Color(red: 0.24, green: 0.27, blue: 0.67)
    default:
        return Color(red: 0.24, green: 0.31, blue: 0.39)
    }
}

private func exposureColor(_ index: Int) -> Color {
    let colors = [AppTheme.accent, AppTheme.quality, AppTheme.momentum, AppTheme.warning, AppTheme.negative]
    return colors[index % colors.count]
}
