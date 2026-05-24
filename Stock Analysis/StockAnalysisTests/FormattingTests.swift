import XCTest
@testable import Stock_Analysis

final class FormattingTests: XCTestCase {
    func testPctFormattingCases() {
        let cases: [(Double?, Bool, String)] = [
            (nil, true, "-"),
            (.nan, true, "-"),
            (0, true, "+0.0%"),
            (0.01234, true, "+1.2%"),
            (-0.056, true, "-5.6%"),
            (1.0, true, "+100.0%"),
            (0.05, false, "5.0%"),
            (-0.05, false, "-5.0%")
        ]

        for (value, signed, expected) in cases {
            XCTAssertEqual(pct(value, signed: signed), expected)
        }
    }

    func testScoreFormattingCases() {
        let cases: [(Double?, String)] = [
            (nil, "-"),
            (.nan, "-"),
            (0, "0.000"),
            (12.34567, "12.346"),
            (-1.2, "-1.200")
        ]

        for (value, expected) in cases {
            XCTAssertEqual(score(value), expected)
        }
    }

    func testCapFormattingCases() {
        let cases: [(Double?, String, String)] = [
            (nil, "USD", "-"),
            (.nan, "USD", "-"),
            (0, "USD", "-"),
            (1_240_000_000_000, "USD", "$1.2T"),
            (2_500_000_000, "USD", "$2.5B"),
            (320_000_000, "USD", "$320M"),
            (1_500_000_000_000, "KRW", "1조 5,000억"),
            (100_000_000, "KRW", "1억"),
            (50_000_000, "KRW", "₩50,000,000")
        ]

        for (value, currency, expected) in cases {
            XCTAssertEqual(cap(value, currency: currency), expected)
        }
    }

    func testFmtPxCases() {
        let cases: [(Double, String, String)] = [
            (.nan, "USD", "-"),
            (1_234.56, "USD", "$1234.56"),
            (12.345, "USD", "$12.35"),
            (0.123456, "USD", "$0.1235"),
            (0.8800, "USD", "$0.88"),
            (0.8850, "USD", "$0.885"),
            (0.8000, "USD", "$0.80"),
            (0, "USD", "$0.00"),
            (-1.2, "USD", "$-1.20"),
            (1_234.4, "KRW", "₩1,234"),
            (0, "KRW", "₩0")
        ]

        for (value, currency, expected) in cases {
            XCTAssertEqual(fmtPx(value, currency: currency), expected)
        }
    }

    func testSignedPxCases() {
        let cases: [(Double, String, String)] = [
            (.nan, "USD", "-"),
            (10, "USD", "+$10.00"),
            (-10, "USD", "-$10.00"),
            (0, "USD", "+$0.00"),
            (1_500.4, "USD", "+$1500.40"),
            (0.8800, "USD", "+$0.88"),
            (-0.8850, "USD", "-$0.885"),
            (-0.125, "USD", "-$0.125"),
            (1_234, "KRW", "+₩1,234"),
            (-1_234, "KRW", "-₩1,234"),
            (0, "KRW", "+₩0")
        ]

        for (value, currency, expected) in cases {
            XCTAssertEqual(signedPx(value, currency: currency), expected)
        }
    }

    func testNormalizedRecommendationCases() {
        let nilCases: [String?] = [nil, "", "   ", "none", "null", "nil", "n/a", "na", "-"]
        for value in nilCases {
            XCTAssertNil(normalizedRecommendation(value))
        }

        let valueCases: [(String?, String)] = [
            ("Buy", "Buy"),
            ("Strong Buy", "Strong Buy"),
            (" Hold ", "Hold"),
            ("Underperform", "Underperform")
        ]

        for (value, expected) in valueCases {
            XCTAssertEqual(normalizedRecommendation(value), expected)
        }
    }

    func testDisplayCompanyNameCases() {
        let cases: [(String?, String?, String)] = [
            (nil, nil, ""),
            (nil, "AAPL", "AAPL"),
            ("", "MSFT", "MSFT"),
            ("Apple Inc.", "AAPL", "Apple"),
            ("(주)삼성전자", "005930.KS", "삼성전자"),
            ("KOSPI", nil, "코스피"),
            ("BOTZ Company", "BOTZ", "BOTZ"),
            ("The Coca-Cola Company", "KO", "Coca-Cola"),
            ("Acme Corporation", "ACME", "Acme"),
            ("NVDA 기업", "NVDA", "NVDA")
        ]

        for (rawName, ticker, expected) in cases {
            XCTAssertEqual(displayCompanyName(rawName, ticker: ticker), expected)
        }
    }

    func testTimestampAndFreshnessCases() {
        XCTAssertEqual(formattedUpdateTimestamp(nil), "-")
        XCTAssertEqual(formattedUpdateTimestamp(""), "-")
        XCTAssertEqual(formattedUpdateTimestamp("2026-05-20 12:34"), "2026-05-20 12:34")
        XCTAssertEqual(formattedUpdateTimestamp("reported at 2026/05/20 12:34 KST"), "2026-05-20 12:34")
        XCTAssertEqual(formattedUpdateTimestamp("not a date"), "not a date")

        XCTAssertEqual(DataFreshnessLevel.fresh.label, "최신")
        XCTAssertEqual(DataFreshnessLevel.delayed.detail, "갱신 지연 가능")
        XCTAssertEqual(DataFreshnessLevel.stale.label, "오래됨")
        XCTAssertEqual(DataFreshnessLevel.unknown.detail, "갱신 시각 없음")
        XCTAssertEqual(dataFreshnessLevel(nil), .unknown)
    }

    func testDataSourceLabelCases() {
        let cases: [(String?, String)] = [
            (nil, "출처 미확인"),
            ("", "출처 미확인"),
            ("yfinance", "시장 API"),
            ("storage", "저장 데이터"),
            ("cache", "앱 캐시"),
            ("vendor", "vendor")
        ]

        for (source, expected) in cases {
            XCTAssertEqual(dataSourceLabel(source), expected)
        }
    }

    func testTickerAndKoreanNameHelpers() {
        XCTAssertEqual(shortTicker("005930.KS"), "005930")
        XCTAssertEqual(shortTicker("005930.KQ"), "005930")
        XCTAssertEqual(shortTicker(" AAPL "), "AAPL")
        XCTAssertEqual(krCode(from: "005930.KS"), "005930")
        XCTAssertEqual(krCode(from: "AAPL"), "")

        XCTAssertTrue(isMissingKrName("", ticker: "005930.KS"))
        XCTAssertTrue(isMissingKrName("005930", ticker: "005930.KS"))
        XCTAssertTrue(isGenericTickerCompanyName("AAPL 기업", ticker: "AAPL"))
        XCTAssertFalse(isMissingKrName("삼성전자", ticker: "005930.KS"))

        XCTAssertEqual(resolvedKrCompanyName(ticker: "005930.KS", currentName: "005930"), "005930")
        XCTAssertEqual(resolvedKrCompanyName(ticker: "AAPL", currentName: "Apple"), "Apple")
        XCTAssertEqual(localizedCompanyName(ticker: "AAPL", currentName: "Apple Inc."), "Apple")
        XCTAssertEqual(localizedCompanyName(ticker: "005930.KS", currentName: "005930"), "005930")
        XCTAssertEqual(localizedCompanyName(ticker: "BOTZ", currentName: "BOTZ 기업"), "BOTZ")
    }

    func testMarketIndicatorLogoTextCases() {
        let cases: [(String, String?, String)] = [
            ("^IXIC", nil, "NASDAQ"),
            ("^GSPC", nil, "S&P"),
            ("^KS11", nil, "KOSPI"),
            ("^KQ11", nil, "KOSDAQ"),
            ("GC=F", nil, "GOLD"),
            ("BTC-USD", nil, "BTC"),
            ("ETH-USD", nil, "ETH"),
            ("IRR_GOVT03Y", nil, "KR3Y"),
            ("ABC", "Alpha Beta Capital", "ABC"),
            ("", "코스피 지수", "코스피")
        ]

        for (ticker, name, expected) in cases {
            XCTAssertEqual(marketIndicatorLogoText(ticker: ticker, name: name), expected)
        }
    }

    func testMarketCurrencyAndLogoURLCases() {
        XCTAssertEqual(marketCurrency(for: "AAPL"), "USD")
        XCTAssertEqual(marketCurrency(for: "005930.KS"), "KRW")
        XCTAssertEqual(marketCurrency(for: "005930", market: "KR"), "KRW")
        XCTAssertEqual(marketCurrency(for: "005930", market: "US"), "USD")

        XCTAssertTrue(logoURLs(for: "", currency: "USD").isEmpty)
        XCTAssertEqual(logoURLs(for: "AAPL", currency: "USD").first?.absoluteString, "https://logo.clearbit.com/apple.com")
        XCTAssertEqual(logoURL(for: "005930.KS", currency: "KRW")?.absoluteString, "https://file.alphasquare.co.kr/media/images/stock_logo/kr/005930.png")
        XCTAssertTrue(logoURLs(for: "BRK.B", currency: "USD").contains { $0.absoluteString.contains("BRK-B") })
    }

    func testSearchAndIndustryCases() {
        XCTAssertTrue(textMatches("", ticker: "AAPL", name: "Apple", sector: "Technology"))
        XCTAssertTrue(textMatches("app", ticker: "AAPL", name: "Apple", sector: "Technology"))
        XCTAssertTrue(textMatches("tech", ticker: "AAPL", name: "Apple", sector: "Technology"))
        XCTAssertFalse(textMatches("bank", ticker: "AAPL", name: "Apple", sector: "Technology"))

        XCTAssertEqual(portfolioIndustryLabel(ticker: "NVDA", name: "NVIDIA", sector: "Technology"), "데이터센터")
        XCTAssertEqual(portfolioIndustryLabel(ticker: "000660.KS", name: "SK하이닉스", sector: "반도체"), "HBM")
        XCTAssertEqual(portfolioIndustryLabel(ticker: "AMD", name: "Advanced Micro Devices", sector: nil), "CPU")
        XCTAssertEqual(portfolioIndustryLabel(ticker: "JPM", name: "JPMorgan", sector: nil), "은행")
        XCTAssertEqual(portfolioIndustryLabel(ticker: "UNKNOWN", name: "Unknown", sector: ""), "기타")

        let options = portfolioIndustryOptions(labels: ["HBM", "HBM", "은행", "기타"])
        XCTAssertEqual(options.first?.label, portfolioIndustryAll)
        XCTAssertEqual(options.first?.count, 4)
        XCTAssertTrue(options.contains(PortfolioIndustryOption(label: "HBM", count: 2)))

        XCTAssertTrue(portfolioIndustryTextMatches("HBM", ticker: "000660.KS", name: "SK하이닉스", sector: "반도체"))
        XCTAssertTrue(portfolioIndustryTextMatches("삼성", ticker: "005930.KS", name: "삼성전자", sector: "반도체"))
        XCTAssertFalse(portfolioIndustryTextMatches("바이오", ticker: "AAPL", name: "Apple", sector: "Technology"))
    }
}
