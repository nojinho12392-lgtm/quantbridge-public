import Foundation
import SwiftUI

func pct(_ value: Double?, signed: Bool = true) -> String {
    guard let value, value.isFinite else { return "-" }
    return String(format: signed ? "%+.1f%%" : "%.1f%%", value * 100)
}

func score(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    return String(format: "%.3f", value)
}

func cap(_ value: Double?, currency: String = "USD") -> String {
    guard let value, value.isFinite, value > 0 else { return "-" }
    if currency == "KRW" {
        let eok = Int(value / 1e8)
        let jo = eok / 10_000
        let rem = eok % 10_000
        if jo > 0 && rem > 0 { return "\(jo)조 \(groupedInteger(rem))억" }
        if jo > 0 { return "\(jo)조" }
        if rem > 0 { return "\(groupedInteger(rem))억" }
        return "₩\(groupedInteger(value))"
    }
    if value >= 1e12 { return String(format: "$%.1fT", value / 1e12) }
    if value >= 1e9 { return String(format: "$%.1fB", value / 1e9) }
    return String(format: "$%.0fM", value / 1e6)
}

func fmtPx(_ value: Double, currency: String = "USD") -> String {
    guard value.isFinite else { return "-" }
    if currency == "KRW" { return "₩\(groupedInteger(value))" }
    if abs(value) >= 1 { return "$\(fixedDecimal(value, minFractionDigits: 2, maxFractionDigits: 2))" }
    return "$\(fixedDecimal(value, minFractionDigits: 2, maxFractionDigits: 4))"
}

func signedPx(_ value: Double, currency: String = "USD") -> String {
    guard value.isFinite else { return "-" }
    let sign = value >= 0 ? "+" : "-"
    let absolute = abs(value)
    if currency == "KRW" { return "\(sign)₩\(groupedInteger(absolute))" }
    if absolute >= 1 { return sign + String(format: "$%.2f", absolute) }
    return sign + "$\(fixedDecimal(absolute, minFractionDigits: 2, maxFractionDigits: 4))"
}

private func fixedDecimal(_ value: Double, minFractionDigits: Int, maxFractionDigits: Int) -> String {
    var text = String(format: "%.\(maxFractionDigits)f", value)
    guard let decimalIndex = text.firstIndex(of: ".") else { return text }
    while text.distance(from: text.index(after: decimalIndex), to: text.endIndex) > minFractionDigits,
          text.last == "0" {
        text.removeLast()
    }
    return text
}

func normalizedRecommendation(_ value: String?) -> String? {
    guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines),
          !text.isEmpty else {
        return nil
    }
    let normalized = text.lowercased()
    if ["none", "null", "nil", "n/a", "na", "-"].contains(normalized) {
        return nil
    }
    return text
}

func formattedUpdateTimestamp(_ rawValue: String?) -> String {
    guard let rawValue else { return "-" }
    let clean = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return "-" }

    if let date = UpdateTimestampParser.date(from: clean) {
        return UpdateTimestampParser.outputFormatter.string(from: date)
    }
    if let extracted = UpdateTimestampParser.extractedDateTime(from: clean) {
        return extracted
    }
    return clean
}

func parsedUpdateTimestamp(_ rawValue: String?) -> Date? {
    guard let rawValue else { return nil }
    let clean = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return nil }
    return UpdateTimestampParser.date(from: clean)
}

enum DataFreshnessLevel {
    case fresh
    case delayed
    case stale
    case unknown

    var label: String {
        switch self {
        case .fresh: return "최신"
        case .delayed: return "지연"
        case .stale: return "오래됨"
        case .unknown: return "확인 필요"
        }
    }

    var detail: String {
        switch self {
        case .fresh: return "최근 데이터"
        case .delayed: return "갱신 지연 가능"
        case .stale: return "재확인 필요"
        case .unknown: return "갱신 시각 없음"
        }
    }

    var color: Color {
        switch self {
        case .fresh: return .green
        case .delayed: return .orange
        case .stale: return .red
        case .unknown: return AppTheme.secondaryText
        }
    }
}

func dataFreshnessLevel(_ rawValue: String?) -> DataFreshnessLevel {
    guard let rawValue,
          let date = UpdateTimestampParser.date(from: rawValue) else {
        return .unknown
    }
    let age = Date().timeIntervalSince(date)
    if age <= 36 * 60 * 60 { return .fresh }
    if age <= 96 * 60 * 60 { return .delayed }
    return .stale
}

func dataSourceLabel(_ source: String?) -> String {
    let clean = source?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    switch clean {
    case "yfinance":
        return "시장 API"
    case "storage":
        return "저장 데이터"
    case "storage_snapshot":
        return "부분 데이터"
    case "cache":
        return "앱 캐시"
    case "":
        return "출처 미확인"
    default:
        return source ?? "출처 미확인"
    }
}

func displayCompanyName(_ rawName: String?, ticker: String? = nil) -> String {
    let fallback = ticker?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if let override = marketIndicatorDisplayNameOverride(fallback) {
        return override
    }
    var text = rawName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    guard !text.isEmpty else { return fallback }
    if let override = marketIndicatorDisplayNameOverride(text) {
        return override
    }


    text = text
        .replacingOccurrences(of: "㈜", with: "")
        .replacingOccurrences(of: "(주)", with: "")
        .replacingOccurrences(of: "（주）", with: "")
        .replacingOccurrences(of: "주식회사", with: "")
        .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .trimmingCharacters(in: CharacterSet(charactersIn: ",，.- "))

    let suffixPattern = #"(?i)(?:[,，]\s*)?(?:the\s+)?(?:incorporated|inc|corporation|corp|company|co|limited|ltd|plc|llc|holdings?|holding|group|n\.v|nv|s\.a|sa|ag|se|lp|l\.p|common stock|ordinary shares|american depositary shares|american depositary receipts|ads|adr|class\s+[a-z])\.?$"#
    var didTrim = true
    while didTrim {
        let previous = text
        if let range = text.range(of: suffixPattern, options: .regularExpression) {
            text.removeSubrange(range)
            text = text
                .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: ",，.- "))
        }
        didTrim = previous != text && !text.isEmpty
    }

    if text.lowercased().hasPrefix("the ") {
        text = String(text.dropFirst(4))
    }
    if isGenericTickerCompanyName(text, ticker: fallback) {
        let symbol = shortTicker(fallback)
        return symbol.isEmpty ? fallback : symbol
    }
    let resolved = text.isEmpty ? (rawName ?? fallback) : text
    if let fallbackName = koreanCompanyFallbackName(resolved, ticker: fallback) {
        return fallbackName
    }
    return resolved
}

private func koreanCompanyFallbackName(_ name: String, ticker: String) -> String? {
    let symbol = shortTicker(ticker)
        .replacingOccurrences(of: ".", with: "-")
        .uppercased()
    guard !symbol.isEmpty,
          symbol.range(of: #"^[A-Z][A-Z0-9-]{0,7}$"#, options: .regularExpression) != nil,
          !containsHangul(name),
          !isNonCompanyInstrumentName(name) else {
        return nil
    }
    return nil
}

private func isNonCompanyInstrumentName(_ value: String) -> Bool {
    let text = value.lowercased()
    let markers = [
        " etf", " fund", " trust", " shares", " index", " futures", " future",
        " bond", " treasury", " yield", "spdr", "ishares", "vanguard",
        "invesco", "ark ", "kodex", "tiger", "kospi", "kosdaq", "s&p",
        "nasdaq", "gold", "silver", "oil", "copper", "bitcoin", "ethereum"
    ]
    return markers.contains { text.contains($0) }
}

private func marketIndicatorDisplayNameOverride(_ value: String) -> String? {
    let compact = value
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: #"\s+"#, with: "", options: .regularExpression)
        .uppercased()
    switch compact {
    case "^KS11", "KOSPI", "KOSPI지수", "코스피":
        return "코스피"
    case "^KQ11", "KOSDAQ", "KOSDAQ지수", "코스닥":
        return "코스닥"
    default:
        return nil
    }
}

func marketIndicatorLogoText(ticker: String, name: String? = nil) -> String {
    let symbol = normalizedTicker(ticker)
    switch symbol {
    case "^IXIC", "NQ=F":
        return "NASDAQ"
    case "^GSPC", "ES=F":
        return "S&P"
    case "RTY=F":
        return "RUSSELL"
    case "^DJI":
        return "DOW"
    case "^SOX":
        return "SOX"
    case "^VIX":
        return "VIX"
    case "^KS11":
        return "KOSPI"
    case "^KQ11":
        return "KOSDAQ"
    case "KRW=X":
        return "USD/KRW"
    case "DX-Y.NYB":
        return "DXY"
    case "^IRX":
        return "US3M"
    case "^FVX":
        return "US5Y"
    case "^TNX":
        return "US10Y"
    case "^TYX":
        return "US30Y"
    case "IRR_GOVT03Y":
        return "KR3Y"
    case "IRR_CORP03Y":
        return "KRCR"
    case "GC=F":
        return "GOLD"
    case "SI=F":
        return "SILVER"
    case "CL=F":
        return "OIL"
    case "HG=F":
        return "COPPER"
    case "BTC-USD":
        return "BTC"
    case "ETH-USD":
        return "ETH"
    case "SOL-USD":
        return "SOL"
    default:
        let cleanedName = name?
            .replacingOccurrences(of: "지수", with: "")
            .replacingOccurrences(of: "선물", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let cleanedName, !cleanedName.isEmpty {
            let latin = cleanedName
                .split(separator: " ")
                .compactMap { word -> String? in
                    guard let first = word.unicodeScalars.first, CharacterSet.alphanumerics.contains(first) else { return nil }
                    return String(word.prefix(1))
                }
                .joined()
                .uppercased()
            if latin.count >= 2 { return String(latin.prefix(6)) }
            return String(cleanedName.prefix(6))
        }
        return String(shortTicker(ticker).uppercased().prefix(6))
    }
}

private enum UpdateTimestampParser {
    static let outputFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        return formatter
    }()

    private static let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let isoFormatterNoFraction: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    private static let localDateTimeFormatters: [DateFormatter] = [
        makeFormatter("yyyy-MM-dd HH:mm:ss"),
        makeFormatter("yyyy-MM-dd HH:mm"),
        makeFormatter("yyyy-MM-dd")
    ]

    static func date(from value: String) -> Date? {
        let candidates = normalizedCandidates(from: value)
        for candidate in candidates {
            if let date = isoFormatter.date(from: candidate) ?? isoFormatterNoFraction.date(from: candidate) {
                return date
            }
        }
        for formatter in localDateTimeFormatters {
            if let date = formatter.date(from: value.replacingOccurrences(of: "T", with: " ")) {
                return date
            }
        }
        return nil
    }

    static func extractedDateTime(from value: String) -> String? {
        let pattern = #"(\d{4}[-/.]\d{2}[-/.]\d{2})[T\s]+(\d{2}:\d{2})"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              let dateRange = Range(match.range(at: 1), in: value),
              let timeRange = Range(match.range(at: 2), in: value) else {
            return nil
        }
        return "\(value[dateRange].replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ".", with: "-")) \(value[timeRange])"
    }

    private static func normalizedCandidates(from value: String) -> [String] {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        var candidates = [trimmed]
        if trimmed.contains("T"), !hasExplicitTimezone(trimmed) {
            candidates.append("\(trimmed)Z")
        }
        return candidates
    }

    private static func hasExplicitTimezone(_ value: String) -> Bool {
        value.hasSuffix("Z") || value.range(of: #"[+-]\d{2}:?\d{2}$"#, options: .regularExpression) != nil
    }

    private static func makeFormatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateFormat = format
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        return formatter
    }
}

private func groupedInteger(_ value: Double) -> String {
    Int(value.rounded()).formatted(.number.grouping(.automatic))
}

private func groupedInteger(_ value: Int) -> String {
    value.formatted(.number.grouping(.automatic))
}

func regimeColor(_ regime: String) -> Color {
    switch regime {
    case "RISK_ON":
        return AppTheme.positive
    case "RISK_OFF":
        return AppTheme.negative
    default:
        return .yellow
    }
}

func shortTicker(_ ticker: String) -> String {
    let trimmed = ticker.trimmingCharacters(in: .whitespacesAndNewlines)
    let uppercased = trimmed.uppercased()
    if uppercased.hasSuffix(".KS") || uppercased.hasSuffix(".KQ") {
        return String(trimmed.dropLast(3))
    }
    return trimmed
}

func krCode(from value: String) -> String {
    let pattern = #"\d{6}"#
    let range = value.range(of: pattern, options: .regularExpression)
    return range.map { String(value[$0]) } ?? ""
}

func isMissingKrName(_ name: String, ticker: String) -> Bool {
    let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
    let cleanTicker = ticker.trimmingCharacters(in: .whitespacesAndNewlines)
    let code = krCode(from: cleanTicker)
    if cleanName.isEmpty { return true }
    if !cleanTicker.isEmpty && cleanName.caseInsensitiveCompare(cleanTicker) == .orderedSame { return true }
    if isGenericTickerCompanyName(cleanName, ticker: cleanTicker) { return true }
    if !code.isEmpty && cleanName == code { return true }
    return cleanName.range(of: #"^\d{6}(\.(KS|KQ))?$"#, options: [.regularExpression, .caseInsensitive]) != nil
}

func isGenericTickerCompanyName(_ name: String, ticker: String) -> Bool {
    let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard cleanName.hasSuffix("기업") else { return false }
    let prefix = cleanName
        .replacingOccurrences(of: "기업", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .uppercased()
    let symbol = shortTicker(ticker).uppercased()
    let upperTicker = ticker.uppercased()
    let baseSymbol = upperTicker.split(separator: ".").first.map(String.init) ?? upperTicker
    return !symbol.isEmpty && (prefix == symbol || prefix == upperTicker || prefix == baseSymbol)
}

func resolvedKrCompanyName(ticker: String, currentName: String) -> String {
    currentName
}

func localizedCompanyName(ticker: String, currentName: String, market: String? = nil) -> String {
    let cleanName = currentName.trimmingCharacters(in: .whitespacesAndNewlines)
    let genericTickerName = isGenericTickerCompanyName(cleanName, ticker: ticker)
    if containsHangul(cleanName), !genericTickerName {
        return cleanName
    }
    if genericTickerName {
        let symbol = shortTicker(ticker).uppercased()
        return symbol.isEmpty ? cleanName : symbol
    }
    let resolved = cleanEnglishCompanyName(cleanName.isEmpty ? shortTicker(ticker) : cleanName)
    return koreanCompanyFallbackName(resolved, ticker: ticker) ?? resolved
}

private func containsHangul(_ value: String) -> Bool {
    value.range(of: #"[가-힣]"#, options: .regularExpression) != nil
}

private func cleanEnglishCompanyName(_ value: String) -> String {
    var text = value.trimmingCharacters(in: .whitespacesAndNewlines)
    let suffixes = [
        " Inc.", " Inc", " Incorporated", " Corporation", " Corp.", " Corp",
        " Company", " Co.", " Co", " Ltd.", " Ltd", " Limited", " plc", " PLC",
        ", Inc.", ", Inc", ", Ltd.", ", Ltd", " (The)", ", Inc. (The)"
    ]
    for suffix in suffixes where text.localizedCaseInsensitiveContains(suffix) {
        if text.lowercased().hasSuffix(suffix.lowercased()) {
            text = String(text.dropLast(suffix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }
    return text.isEmpty ? value : text
}

func marketCurrency(for ticker: String, market: String? = nil) -> String {
    if market == "KR" || ticker.hasSuffix(".KS") || ticker.hasSuffix(".KQ") {
        return "KRW"
    }
    return "USD"
}

func logoURLs(for ticker: String, currency: String) -> [URL] {
    let symbol = ticker.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !symbol.isEmpty else { return [] }

    if currency == "KRW" {
        let code = shortTicker(symbol).uppercased()
        return uniqueURLs([
            krLogoOverrideURL(for: code),
            URL(string: "https://file.alphasquare.co.kr/media/images/stock_logo/kr/\(code).png"),
            URL(string: "https://static.toss.im/png-icons/securities/icn-sec-fill-\(code).png"),
        ])
    }

    let variants = usLogoSymbols(from: symbol)
    return uniqueURLs(variants.flatMap { variant in
        [
            usLogoDomain(for: variant).flatMap { URL(string: "https://logo.clearbit.com/\($0)") },
            URL(string: "https://financialmodelingprep.com/image-stock/\(variant).png"),
            URL(string: "https://companiesmarketcap.com/img/company-logos/256/\(variant).webp"),
            URL(string: "https://eodhd.com/img/logos/US/\(variant).png"),
            URL(string: "https://assets.parqet.com/logos/symbol/\(variant)"),
        ]
    })
}

private func usLogoDomain(for symbol: String) -> String? {
    [
        "AAPL": "apple.com",
        "MSFT": "microsoft.com",
        "NVDA": "nvidia.com",
        "GOOGL": "abc.xyz",
        "GOOG": "abc.xyz",
        "AMZN": "amazon.com",
        "META": "meta.com",
        "TSLA": "tesla.com",
        "HD": "homedepot.com",
        "LOW": "lowes.com",
        "TGT": "target.com",
        "KEYS": "keysight.com",
        "ADI": "analog.com",
        "HAS": "hasbro.com",
        "INTU": "intuit.com",
        "NDSN": "nordson.com",
        "TJX": "tjx.com",
        "WSM": "williams-sonoma.com",
        "CPRT": "copart.com",
        "DE": "deere.com",
        "DECK": "deckers.com",
        "RL": "ralphlauren.com",
        "ROST": "rossstores.com",
        "TTWO": "take2games.com",
        "WDAY": "workday.com"
    ][symbol.uppercased()]
}

func logoURL(for ticker: String, currency: String) -> URL? {
    logoURLs(for: ticker, currency: currency).first
}

private func krLogoOverrideURL(for code: String) -> URL? {
    switch code {
    case "064400":
        return URL(string: "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png")
    default:
        return nil
    }
}

private func usLogoSymbols(from ticker: String) -> [String] {
    let symbol = shortTicker(ticker).uppercased()
    guard !symbol.isEmpty else { return [] }

    var variants = [symbol]
    if symbol.contains(".") {
        variants.append(symbol.replacingOccurrences(of: ".", with: "-"))
    }
    if symbol.contains("-") {
        variants.append(symbol.replacingOccurrences(of: "-", with: "."))
    }
    return uniqueStrings(variants)
}

private func uniqueURLs(_ urls: [URL?]) -> [URL] {
    var seen = Set<String>()
    return urls.compactMap { url in
        guard let url else { return nil }
        let key = url.absoluteString
        guard !seen.contains(key) else { return nil }
        seen.insert(key)
        return url
    }
}

private func uniqueStrings(_ values: [String]) -> [String] {
    var seen = Set<String>()
    return values.filter { value in
        guard !seen.contains(value) else { return false }
        seen.insert(value)
        return true
    }
}

func textMatches(_ query: String, ticker: String, name: String, sector: String? = nil) -> Bool {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return true }
    let lower = trimmed.lowercased()
    return ticker.lowercased().contains(lower)
        || name.lowercased().contains(lower)
        || (sector?.lowercased().contains(lower) ?? false)
}

let portfolioIndustryAll = "전체"

struct PortfolioIndustryOption: Identifiable, Hashable {
    let label: String
    let count: Int
    var id: String { label }
}

private struct PortfolioIndustryRule {
    let label: String
    let tickers: Set<String>
    let terms: [String]
}

let portfolioIndustryOrder = [
    "데이터센터",
    "HBM",
    "CPU",
    "반도체 장비",
    "은행",
    "자동차",
    "배터리",
    "클라우드/SW",
    "헬스케어",
    "에너지",
    "소비/리테일",
    "산업재",
    "기타"
]

private let portfolioIndustryRules: [PortfolioIndustryRule] = [
    PortfolioIndustryRule(label: "HBM", tickers: ["000660", "005930", "MU"], terms: ["hbm", "high bandwidth memory", "메모리", "dram", "낸드"]),
    PortfolioIndustryRule(label: "CPU", tickers: ["AMD", "INTC", "ARM", "QCOM"], terms: ["cpu", "processor", "프로세서", "x86", "arm"]),
    PortfolioIndustryRule(label: "데이터센터", tickers: ["NVDA", "AVGO", "ANET", "VRT", "SMCI", "DELL", "MSFT", "GOOGL", "GOOG", "AMZN", "META", "ORCL"], terms: ["data center", "datacenter", "데이터센터", "ai server", "ai 서버", "cloud infrastructure", "클라우드 인프라"]),
    PortfolioIndustryRule(label: "반도체 장비", tickers: ["ASML", "AMAT", "LRCX", "KLAC", "TER", "ONTO"], terms: ["semiconductor equipment", "반도체 장비", "lithography", "노광", "wafer", "웨이퍼"]),
    PortfolioIndustryRule(label: "은행", tickers: ["JPM", "BAC", "WFC", "C", "GS", "MS", "USB", "PNC", "105560", "055550", "086790", "316140"], terms: ["bank", "은행", "금융지주", "brokerage", "증권"]),
    PortfolioIndustryRule(label: "자동차", tickers: ["TSLA", "GM", "F", "TM", "RIVN", "005380", "000270", "012330"], terms: ["auto", "automotive", "vehicle", "ev", "자동차", "전기차", "모빌리티", "완성차"]),
    PortfolioIndustryRule(label: "배터리", tickers: ["373220", "006400", "051910", "096770"], terms: ["battery", "배터리", "2차전지", "양극재", "음극재"]),
    PortfolioIndustryRule(label: "클라우드/SW", tickers: ["CRM", "ADBE", "NOW", "SNOW", "WDAY", "INTU", "PANW", "CRWD", "ZS"], terms: ["software", "cloud", "saas", "소프트웨어", "클라우드", "보안"]),
    PortfolioIndustryRule(label: "헬스케어", tickers: ["LLY", "NVO", "UNH", "JNJ", "MRK", "PFE", "ABBV", "TMO", "DHR", "068270", "207940"], terms: ["health", "biotech", "pharma", "drug", "헬스케어", "바이오", "제약", "의료"]),
    PortfolioIndustryRule(label: "에너지", tickers: ["XOM", "CVX", "COP", "SLB", "EOG"], terms: ["energy", "oil", "gas", "에너지", "원유", "가스", "정유"]),
    PortfolioIndustryRule(label: "소비/리테일", tickers: ["COST", "WMT", "HD", "LOW", "TGT", "TJX", "MCD", "NKE", "SBUX", "PG", "KO", "PEP"], terms: ["retail", "consumer", "restaurant", "apparel", "소비", "리테일", "유통", "의류", "음식료"]),
    PortfolioIndustryRule(label: "산업재", tickers: ["GE", "DE", "CAT", "HON", "BA", "RTX", "LMT", "329180", "034020"], terms: ["industrial", "aerospace", "defense", "machinery", "산업재", "조선", "기계", "방산", "항공"])
]

func portfolioIndustryLabel(ticker: String, name: String, sector: String? = nil) -> String {
    let symbol = shortTicker(ticker).uppercased()
    let code = krCode(from: ticker)
    let tickerKeys = Set([symbol, code].filter { !$0.isEmpty })
    let rawText = [ticker, name, sector ?? ""].joined(separator: " ").lowercased()
    let compactText = rawText.replacingOccurrences(of: #"[\s/_\-.]+"#, with: "", options: .regularExpression)

    for rule in portfolioIndustryRules {
        if !tickerKeys.isDisjoint(with: rule.tickers) { return rule.label }
        if rule.terms.contains(where: { term in
            let lower = term.lowercased()
            let compactTerm = lower.replacingOccurrences(of: #"[\s/_\-.]+"#, with: "", options: .regularExpression)
            return rawText.contains(lower) || compactText.contains(compactTerm)
        }) {
            return rule.label
        }
    }

    let cleanSector = sector?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if cleanSector.localizedCaseInsensitiveContains("technology") || cleanSector.contains("기술") || cleanSector.contains("반도체") { return "기술" }
    if cleanSector.localizedCaseInsensitiveContains("financial") || cleanSector.contains("금융") { return "은행" }
    if cleanSector.localizedCaseInsensitiveContains("health") || cleanSector.contains("바이오") || cleanSector.contains("제약") { return "헬스케어" }
    if cleanSector.localizedCaseInsensitiveContains("energy") || cleanSector.contains("에너지") { return "에너지" }
    if cleanSector.localizedCaseInsensitiveContains("consumer") || cleanSector.contains("소비") { return "소비/리테일" }
    if cleanSector.localizedCaseInsensitiveContains("industrial") || cleanSector.contains("산업") { return "산업재" }
    if cleanSector.localizedCaseInsensitiveContains("communication") || cleanSector.contains("통신") { return "통신" }
    if cleanSector.localizedCaseInsensitiveContains("materials") || cleanSector.contains("소재") { return "소재" }
    if cleanSector.localizedCaseInsensitiveContains("utilities") || cleanSector.contains("유틸리티") { return "유틸리티" }
    if cleanSector.localizedCaseInsensitiveContains("real estate") || cleanSector.contains("부동산") { return "부동산" }
    return cleanSector.isEmpty ? "기타" : cleanSector
}

func portfolioIndustryOptions(labels: [String]) -> [PortfolioIndustryOption] {
    guard !labels.isEmpty else {
        return [PortfolioIndustryOption(label: portfolioIndustryAll, count: 0)]
    }
    let counts = Dictionary(grouping: labels.map { $0.isEmpty ? "기타" : $0 }, by: { $0 })
        .mapValues(\.count)
    let ordered = portfolioIndustryOrder.filter { counts[$0] != nil }
        + counts.keys.filter { !portfolioIndustryOrder.contains($0) }.sorted()
    return [PortfolioIndustryOption(label: portfolioIndustryAll, count: labels.count)]
        + ordered.map { PortfolioIndustryOption(label: $0, count: counts[$0] ?? 0) }
}

func portfolioIndustryTextMatches(_ query: String, ticker: String, name: String, sector: String? = nil) -> Bool {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return true }
    if textMatches(trimmed, ticker: ticker, name: name, sector: sector) { return true }
    return portfolioIndustryLabel(ticker: ticker, name: name, sector: sector)
        .localizedCaseInsensitiveContains(trimmed)
}
