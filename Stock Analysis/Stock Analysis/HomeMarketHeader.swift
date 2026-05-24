import Combine
import SwiftUI
import UIKit

private struct MarketIndicesResponse: Decodable {
    let indices: [MarketIndexQuote]
}

struct MarketIndexQuote: Decodable, Identifiable, Hashable {
    var id: String { symbol }
    let symbol: String
    let label: String
    let value: Double
    let changeAbs: Double
    let changePct: Double
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case symbol
        case label
        case value
        case changeAbs = "change_abs"
        case changePct = "change_pct"
        case updatedAt = "updated_at"
    }
}

private let topHeaderIndexOrder = ["^GSPC", "^IXIC", "^KS11", "^KQ11"]
private let topHeaderIndexLabels = [
    "^GSPC": "S&P 500",
    "^IXIC": "NASDAQ",
    "^KS11": "KOSPI",
    "^KQ11": "KOSDAQ"
]

func topHeaderMarketIndices(from items: [MarketIndicatorQuote]) -> [MarketIndexQuote] {
    var bySymbol: [String: MarketIndicatorQuote] = [:]
    for item in items {
        bySymbol[normalizedTicker(item.symbol)] = item
    }
    return topHeaderIndexOrder.compactMap { symbol in
        guard let item = bySymbol[normalizedTicker(symbol)],
              let changePct = item.changePct else {
            return nil
        }
        return MarketIndexQuote(
            symbol: item.symbol,
            label: topHeaderIndexLabels[symbol] ?? item.label,
            value: item.value,
            changeAbs: item.changeAbs ?? 0,
            changePct: changePct,
            updatedAt: item.updatedAt ?? ""
        )
    }
}

@MainActor
final class MarketIndicesVM: ObservableObject {
    @Published var indices: [MarketIndexQuote] = []
    @Published var isLoading = false
    @Published var error: String?

    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            let response: MarketIndicesResponse = try await api.fetch(["market", "indices"])
            if !response.indices.isEmpty {
                indices = response.indices
                return
            }
        } catch {
            self.error = error.localizedDescription
        }
        indices = (try? await NaverMarketIndexFallback.load()) ?? []
    }
}

struct HomeTopHeader: View {
    let indices: [MarketIndexQuote]
    let openSearch: () -> Void
    let openMarketIndicators: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                QubitBrandMark()
                Text("큐빗")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.90)
                    .allowsTightening(true)
            }
            .frame(width: 76, alignment: .leading)
            Button(action: openMarketIndicators) {
                MarketIndexTicker(indices: indices)
            }
            .buttonStyle(QuantPressButtonStyle(role: .row))
            .layoutPriority(2)
            .accessibilityLabel("주요 지수 열기")
            Button(action: openSearch) {
                LucideIconView(icon: .search, size: 20)
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(QuantPressButtonStyle(role: .icon))
            .accessibilityLabel("검색")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 2)
    }
}

struct QubitScreenTopHeader: View {
    let title: String
    let indices: [MarketIndexQuote]
    let openSearch: () -> Void
    let openMarketIndicators: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 25, weight: .black))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
                .frame(width: 104, alignment: .leading)

            Button(action: openMarketIndicators) {
                MarketIndexTicker(indices: indices)
            }
            .buttonStyle(QuantPressButtonStyle(role: .row))
            .layoutPriority(2)
            .accessibilityLabel("주요 지수 열기")

            Button(action: openSearch) {
                LucideIconView(icon: .search, size: 21)
                    .foregroundStyle(AppTheme.primaryText)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(QuantPressButtonStyle(role: .icon))
            .accessibilityLabel("검색")
        }
        .frame(maxWidth: .infinity, minHeight: 46, alignment: .leading)
        .padding(.horizontal)
        .padding(.top, 2)
        .padding(.bottom, 6)
        .background(AppTheme.background)
    }
}

private struct QubitBrandMark: View {
    private let markColor = Color(red: 0.88, green: 0.23, blue: 0.64)

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0.03, to: 0.96)
                .stroke(
                    markColor,
                    style: StrokeStyle(lineWidth: 3.4, lineCap: .round, lineJoin: .round)
                )
                .rotationEffect(.degrees(18))
                .frame(width: 20, height: 20)

            Path { path in
                path.move(to: CGPoint(x: 16.7, y: 16.7))
                path.addLine(to: CGPoint(x: 22.1, y: 22.1))
            }
            .stroke(markColor, style: StrokeStyle(lineWidth: 3.4, lineCap: .round, lineJoin: .round))

            Path { path in
                path.move(to: CGPoint(x: 8.5, y: 12.4))
                path.addCurve(
                    to: CGPoint(x: 15.5, y: 12.4),
                    control1: CGPoint(x: 8.5, y: 8.8),
                    control2: CGPoint(x: 15.5, y: 8.8)
                )
            }
            .stroke(markColor, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
        }
        .frame(width: 24, height: 24)
        .accessibilityHidden(true)
    }
}

private struct MarketIndexTicker: View {
    let indices: [MarketIndexQuote]
    @State private var currentIndex = 0

    private var current: MarketIndexQuote? {
        guard !indices.isEmpty else { return nil }
        return indices[min(currentIndex, indices.count - 1)]
    }

    var body: some View {
        Group {
            if let current {
                ViewThatFits(in: .horizontal) {
                    tickerRow(current, includeValue: true)
                    tickerRow(current, includeValue: false)
                    Text(pct(current.changePct))
                        .foregroundStyle(current.changePct >= 0 ? AppTheme.positive : AppTheme.negative)
                        .font(.footnote.weight(.semibold))
                        .monospacedDigit()
                }
                .lineLimit(1)
                .transition(.opacity)
            } else {
                Text("S&P 500 대기중")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppTheme.tertiaryText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(AppTheme.elevatedCard)
                .overlay(Capsule().stroke(AppTheme.hairline.opacity(0.35), lineWidth: 0.5))
        )
        .task(id: indices.map(\.id).joined(separator: "|")) {
            currentIndex = 0
            guard indices.count > 1 else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 0.22)) {
                    currentIndex = (currentIndex + 1) % indices.count
                }
            }
        }
    }

    private func tickerRow(_ current: MarketIndexQuote, includeValue: Bool) -> some View {
        HStack(spacing: 4) {
            LucideIconView(icon: .activity, size: 13)
                .foregroundStyle(AppTheme.secondaryText)
            Text(current.label)
                .foregroundStyle(AppTheme.secondaryText)
            if includeValue {
                Text(String(format: "%.2f", current.value))
                    .monospacedDigit()
                    .layoutPriority(-1)
            }
            Text(pct(current.changePct))
                .foregroundStyle(current.changePct >= 0 ? AppTheme.positive : AppTheme.negative)
                .monospacedDigit()
        }
        .font(.footnote.weight(.semibold))
    }
}

enum NaverMarketIndexFallback {
    private struct Spec {
        let outputSymbol: String
        let label: String
        let lookupKeys: Set<String>
    }

    private static let specs = [
        Spec(outputSymbol: "^GSPC", label: "S&P 500", lookupKeys: ["SPX", ".INX"]),
        Spec(outputSymbol: "^IXIC", label: "NASDAQ", lookupKeys: ["IXIC", ".IXIC"]),
        Spec(outputSymbol: "^KS11", label: "KOSPI", lookupKeys: ["KOSPI"]),
        Spec(outputSymbol: "^KQ11", label: "KOSDAQ", lookupKeys: ["KOSDAQ"])
    ]

    static func load() async throws -> [MarketIndexQuote] {
        async let worldRows = rows(from: "https://polling.finance.naver.com/api/realtime/worldstock/index/.INX,.IXIC")
        async let domesticRows = rows(from: "https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI,KOSDAQ")
        let rows = try await worldRows + domesticRows
        return specs.compactMap { spec in
            guard let row = rows.first(where: { matches($0, spec: spec) }) else {
                return nil
            }
            return quote(from: row, spec: spec)
        }
    }

    private static func rows(from urlString: String) async throws -> [[String: Any]] {
        guard let url = URL(string: urlString) else { return [] }
        let (data, _) = try await URLSession.shared.data(from: url)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let datas = json["datas"] as? [[String: Any]] else {
            return []
        }
        return datas
    }

    private static func matches(_ row: [String: Any], spec: Spec) -> Bool {
        let keys = ["symbolCode", "reutersCode", "itemCode"].compactMap { row[$0] as? String }
        return keys.contains { spec.lookupKeys.contains($0) }
    }

    private static func quote(from row: [String: Any], spec: Spec) -> MarketIndexQuote? {
        guard let value = number(row["closePriceRaw"] ?? row["closePrice"]),
              let changePct = number(row["fluctuationsRatioRaw"] ?? row["fluctuationsRatio"]) else {
            return nil
        }
        return MarketIndexQuote(
            symbol: spec.outputSymbol,
            label: spec.label,
            value: value,
            changeAbs: number(row["compareToPreviousClosePriceRaw"] ?? row["compareToPreviousClosePrice"]) ?? 0,
            changePct: changePct / 100,
            updatedAt: row["localTradedAt"] as? String ?? ""
        )
    }

    private static func number(_ value: Any?) -> Double? {
        if let value = value as? Double { return value }
        if let value = value as? Int { return Double(value) }
        guard let raw = value as? String else { return nil }
        return Double(raw.replacingOccurrences(of: ",", with: ""))
    }
}
