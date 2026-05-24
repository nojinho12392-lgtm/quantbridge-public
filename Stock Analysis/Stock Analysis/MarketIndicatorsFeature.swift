import Combine
import Foundation
import SwiftUI

private struct MarketIndicatorResponse: Decodable {
    let items: [MarketIndicatorQuote]
}

private struct MarketIndicatorHistoryResponse: Decodable {
    let series: [MarketIndicatorSeries]
}

struct MarketIndicatorQuote: Decodable, Identifiable, Hashable {
    var id: String { symbol }
    let symbol: String
    let label: String
    let category: String
    let region: String
    let value: Double
    let changeAbs: Double?
    let changePct: Double?
    let updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case symbol
        case label
        case category
        case region
        case value
        case changeAbs = "change_abs"
        case changePct = "change_pct"
        case updatedAt = "updated_at"
    }
}

private struct MarketIndicatorSeries: Decodable, Identifiable, Hashable {
    var id: String { symbol }
    let symbol: String
    let label: String
    let category: String
    let region: String
    let points: [MarketIndicatorPoint]
}

struct MarketIndicatorPoint: Decodable, Hashable {
    let timestamp: String
    let close: Double
}

@MainActor
final class MarketIndicatorsVM: ObservableObject {
    @Published var items: [MarketIndicatorQuote] = []
    @Published var seriesBySymbol: [String: [MarketIndicatorPoint]] = [:]
    @Published var isLoading = false
    @Published var error: String?

    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func hasItems(category: String) -> Bool {
        let normalized = category.lowercased()
        if normalized == "all" {
            return !items.isEmpty
        }
        return items.contains { $0.category.lowercased() == normalized }
    }

    func load(refresh: Bool = false, category: String = "index_fx") async {
        isLoading = true
        error = nil
        defer { isLoading = false }

        let normalizedCategory = category.lowercased()

        do {
            let loadedCurrent: MarketIndicatorResponse = try await api.fetch(
                ["market", "indicators"],
                queryItems: [
                    URLQueryItem(name: "category", value: normalizedCategory),
                    URLQueryItem(name: "refresh", value: refresh ? "true" : "false")
                ]
            )
            let currentItems = loadedCurrent.items
            items = mergedIndicatorItems(existing: items, incoming: currentItems, replacingCategory: normalizedCategory)
            refreshDomesticRealtimeQuotes(for: currentItems, category: normalizedCategory)
            guard !currentItems.isEmpty else { return }

            let loadedHistory = try await fetchHistory(refresh: refresh, symbols: currentItems.map(\.symbol))
            var loadedSeries = loadedHistory.series
            if !refresh {
                let staleDomesticSymbols = domesticIndicatorSymbolsNeedingRefresh(loadedSeries)
                if !staleDomesticSymbols.isEmpty,
                   let refreshed = try? await fetchHistory(refresh: true, symbols: staleDomesticSymbols) {
                    loadedSeries = mergeIndicatorSeries(existing: loadedSeries, refreshed: refreshed.series)
                }
            }
            seriesBySymbol = stableIndicatorSeriesBySymbol(
                previous: seriesBySymbol,
                incoming: loadedSeries,
                quotes: currentItems
            )
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func refreshDomesticRealtimeQuotes(for currentItems: [MarketIndicatorQuote], category: String) {
        guard category == "all" || category == "index_fx" else { return }
        Task { [weak self] in
            guard let self,
                  let naverIndices = try? await NaverMarketIndexFallback.load(),
                  !naverIndices.isEmpty else {
                return
            }
            self.applyDomesticRealtimeQuotes(currentItems: currentItems, naverIndices: naverIndices, category: category)
        }
    }

    private func applyDomesticRealtimeQuotes(
        currentItems: [MarketIndicatorQuote],
        naverIndices: [MarketIndexQuote],
        category: String
    ) {
        let merged = mergeDomesticIndicatorQuotes(currentItems, with: naverIndices)
        items = mergedIndicatorItems(existing: items, incoming: merged, replacingCategory: category)
    }

    private func fetchHistory(refresh: Bool, symbols: [String] = []) async throws -> MarketIndicatorHistoryResponse {
        var queryItems = [
            URLQueryItem(name: "period", value: "1d"),
            URLQueryItem(name: "interval", value: "15m"),
            URLQueryItem(name: "refresh", value: refresh ? "true" : "false")
        ]
        if !symbols.isEmpty {
            queryItems.append(URLQueryItem(name: "symbols", value: symbols.joined(separator: ",")))
        }
        return try await api.fetch(
            ["market", "indicators", "history"],
            queryItems: queryItems
        )
    }
}

private let domesticIntradayIndicatorSymbols: Set<String> = ["^KS11", "^KQ11"]
private let domesticRealtimeIndicatorSymbols: Set<String> = ["^KS11", "^KQ11"]

private func mergedIndicatorItems(
    existing: [MarketIndicatorQuote],
    incoming: [MarketIndicatorQuote],
    replacingCategory category: String
) -> [MarketIndicatorQuote] {
    let normalizedCategory = category.lowercased()
    if normalizedCategory == "all" {
        return incoming
    }

    var seen = Set<String>()
    let retained = existing.filter { item in
        item.category.lowercased() != normalizedCategory
    }
    return (retained + incoming).filter { item in
        let key = normalizedTicker(item.symbol)
        guard !seen.contains(key) else { return false }
        seen.insert(key)
        return true
    }
}

private func mergeDomesticIndicatorQuotes(_ items: [MarketIndicatorQuote], with indices: [MarketIndexQuote]) -> [MarketIndicatorQuote] {
    let overrides = Dictionary(uniqueKeysWithValues: indices
        .filter { domesticRealtimeIndicatorSymbols.contains($0.symbol) }
        .map { ($0.symbol, $0) })
    guard !overrides.isEmpty else { return items }
    return items.map { item in
        guard let override = overrides[item.symbol] else { return item }
        return MarketIndicatorQuote(
            symbol: item.symbol,
            label: item.label,
            category: item.category,
            region: item.region,
            value: override.value,
            changeAbs: override.changeAbs,
            changePct: override.changePct,
            updatedAt: override.updatedAt
        )
    }
}

private func domesticIndicatorSymbolsNeedingRefresh(_ series: [MarketIndicatorSeries]) -> [String] {
    series
        .filter { domesticIntradayIndicatorSymbols.contains($0.symbol.uppercased()) }
        .filter { isSparseDomesticIndicatorHistory($0) }
        .map(\.symbol)
}

private func isSparseDomesticIndicatorHistory(_ series: MarketIndicatorSeries) -> Bool {
    let clean = chronologicallySorted(series.points.filter { $0.close.isFinite })
    if clean.count < 8 { return true }

    let distinctCloses = Set(clean.map { Int(($0.close * 1_000_000).rounded()) })
    if distinctCloses.count < 3 { return true }

    let dates = clean.compactMap { marketDate(from: $0.timestamp) }.sorted()
    if dates.count >= 2, let first = dates.first, let last = dates.last {
        if last.timeIntervalSince(first) < 30 * 60 { return true }
    }

    guard let session = marketSession(forSymbol: series.symbol) else { return false }
    let sessionSamples = clean.compactMap { point -> SparklineSample? in
        guard let date = marketDate(from: point.timestamp) else { return nil }
        guard let progress = sessionProgress(date: date, session: session) else { return nil }
        return SparklineSample(close: point.close, progress: progress, date: date)
    }
    return !hasUsableTimeline(sessionSamples)
}

private func mergeIndicatorSeries(existing: [MarketIndicatorSeries], refreshed: [MarketIndicatorSeries]) -> [MarketIndicatorSeries] {
    let refreshedBySymbol = Dictionary(uniqueKeysWithValues: refreshed.map { ($0.symbol, $0) })
    let merged = existing.map { refreshedBySymbol[$0.symbol] ?? $0 }
    let existingSymbols = Set(existing.map(\.symbol))
    return merged + refreshed.filter { !existingSymbols.contains($0.symbol) }
}

private func stableIndicatorSeriesBySymbol(
    previous: [String: [MarketIndicatorPoint]],
    incoming: [MarketIndicatorSeries],
    quotes: [MarketIndicatorQuote]
) -> [String: [MarketIndicatorPoint]] {
    let quotesBySymbol = Dictionary(uniqueKeysWithValues: quotes.map { (normalizedTicker($0.symbol), $0) })
    let incomingSymbols = Set(incoming.map { normalizedTicker($0.symbol) })
    var output = previous.filter { !incomingSymbols.contains(normalizedTicker($0.key)) }

    for series in incoming {
        let quote = quotesBySymbol[normalizedTicker(series.symbol)]
        let cleaned = stableMarketIndicatorPoints(series.points, quote: quote)
        let previousPoints = previous[series.symbol] ?? previous[normalizedTicker(series.symbol)]
        if isUsableIndicatorSeries(cleaned) || (previousPoints?.isEmpty ?? true) {
            output[series.symbol] = cleaned
        } else {
            output[series.symbol] = previousPoints
        }
    }

    return output
}

private func stableMarketIndicatorPoints(
    _ points: [MarketIndicatorPoint],
    quote: MarketIndicatorQuote? = nil
) -> [MarketIndicatorPoint] {
    let sorted = chronologicallySorted(points.filter { $0.close.isFinite && $0.close > 0 })
    guard !sorted.isEmpty else { return [] }

    var deduped: [String: MarketIndicatorPoint] = [:]
    var order: [String] = []
    for (index, point) in sorted.enumerated() {
        let key = marketDate(from: point.timestamp).map { "\($0.timeIntervalSince1970)" }
            ?? (point.timestamp.isEmpty ? "idx-\(index)" : point.timestamp)
        if deduped[key] == nil {
            order.append(key)
        }
        deduped[key] = point
    }
    let clean = order.compactMap { deduped[$0] }
    guard clean.count >= 4 else { return clean }

    let values = clean.map(\.close).sorted()
    let median = values.count.isMultiple(of: 2)
        ? (values[values.count / 2 - 1] + values[values.count / 2]) / 2
        : values[values.count / 2]
    guard median.isFinite, median > 0 else { return clean }

    let threshold = marketIndicatorOutlierThreshold(quote)
    let filtered = clean.filter { abs($0.close / median - 1) <= threshold }
    return filtered.count >= max(2, clean.count / 2) ? filtered : clean
}

private func isUsableIndicatorSeries(_ points: [MarketIndicatorPoint]) -> Bool {
    guard points.count >= 2 else { return false }
    let distinctCloses = Set(points.map { Int(($0.close * 1_000_000).rounded()) })
    return distinctCloses.count >= 2
}

func displayMarketIndicatorPoints(for quote: MarketIndicatorQuote, points: [MarketIndicatorPoint]) -> [MarketIndicatorPoint] {
    let clean = stableMarketIndicatorPoints(points, quote: quote)
    if isUsableIndicatorSeries(clean) { return clean }

    let fallback = fallbackMarketIndicatorPoints(for: quote)
    return fallback.isEmpty ? clean : fallback
}

func fallbackMarketIndicatorPoints(for quote: MarketIndicatorQuote) -> [MarketIndicatorPoint] {
    let current = quote.value
    guard current.isFinite, current > 0 else { return [] }

    let previous: Double
    if let changeAbs = quote.changeAbs,
       changeAbs.isFinite,
       current - changeAbs > 0 {
        previous = current - changeAbs
    } else if let changePct = quote.changePct,
              changePct.isFinite,
              changePct > -0.95 {
        previous = current / (1 + changePct)
    } else {
        previous = current
    }
    guard previous.isFinite, previous > 0 else { return [] }

    let delta = current - previous
    let movement = abs(delta)
    let amplitude = movement > 0 ? movement * 0.18 : 0
    let sessionProgressCap = currentSessionProgressCap(for: quote)
    let progress: [Double] = [0, 0.18, 0.33, 0.48, 0.62, 0.78, 1]
        .map { step in
            guard let sessionProgressCap else { return step }
            return step * sessionProgressCap
        }
    let wave: [Double] = [0, -0.22, 0.14, -0.08, 0.18, -0.10, 0]

    return progress.enumerated().map { index, step in
        let close: Double
        if index == 0 {
            close = previous
        } else if index == progress.count - 1 {
            close = current
        } else {
            close = max(0.0001, previous + delta * step + wave[index] * amplitude)
        }
        return MarketIndicatorPoint(
            timestamp: fallbackTimestamp(for: quote, progress: progress[index])
                ?? String(format: "1970-01-01T00:%02d:00Z", index),
            close: close
        )
    }
}

private func currentSessionProgressCap(for quote: MarketIndicatorQuote, now: Date = Date()) -> Double? {
    guard
        let session = marketSession(forSymbol: quote.symbol),
        let progress = sessionProgress(date: now, session: session)
    else {
        return nil
    }
    return max(Double(progress), 0.01)
}

private func fallbackTimestamp(for quote: MarketIndicatorQuote, progress: Double, now: Date = Date()) -> String? {
    guard
        let session = marketSession(forSymbol: quote.symbol),
        let interval = sessionInterval(for: now, session: session),
        now >= interval.start,
        now <= interval.end
    else {
        return nil
    }
    let boundedProgress = min(max(progress, 0), 1)
    let timestamp = interval.start.addingTimeInterval(
        interval.end.timeIntervalSince(interval.start) * boundedProgress
    )
    return ISO8601DateFormatter.marketIndicator.string(from: timestamp)
}

private func marketIndicatorOutlierThreshold(_ quote: MarketIndicatorQuote?) -> Double {
    let symbol = normalizedTicker(quote?.symbol ?? "")
    if symbol == "^VIX" { return 0.90 }
    switch quote?.category {
    case "crypto":
        return 0.85
    case "commodity":
        return 0.55
    case "bond":
        return 0.50
    default:
        return 0.35
    }
}

private enum MarketIndicatorCategory: String, CaseIterable, Identifiable, Hashable {
    case indexFx = "index_fx"
    case bond
    case commodity
    case crypto

    var id: String { rawValue }

    var title: String {
        switch self {
        case .indexFx: return "지수·환율"
        case .bond: return "채권"
        case .commodity: return "원자재"
        case .crypto: return "가상자산"
        }
    }
}

private enum MarketIndicatorRegion: String, CaseIterable, Identifiable, Hashable {
    case all
    case domestic
    case overseas

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "전체"
        case .domestic: return "국내"
        case .overseas: return "해외"
        }
    }
}

struct MarketIndicatorsScreen: View {
    @ObservedObject var vm: MarketIndicatorsVM
    @EnvironmentObject private var watchlist: WatchlistStore
    @State private var category: MarketIndicatorCategory = .indexFx
    @State private var region: MarketIndicatorRegion = .all
    @State private var requestedFreshOpenSessionLoad = false

    private var showsRegionFilter: Bool {
        category == .indexFx || category == .bond
    }

    private var filteredItems: [MarketIndicatorQuote] {
        vm.items.filter { item in
            item.category == category.rawValue &&
            (!showsRegionFilter || region == .all || item.region == region.rawValue)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 14) {
                AppSegmentSwitch(options: MarketIndicatorCategory.allCases, selection: $category) { item in
                    item.title
                }
                .padding(.horizontal)
                .padding(.top, 12)

                if showsRegionFilter {
                    AppSegmentSwitch(options: MarketIndicatorRegion.allCases, selection: $region) { item in
                        item.title
                    }
                    .padding(.horizontal)
                }
            }
            .background(AppTheme.background)

            ScrollView {
                LazyVStack(spacing: 4) {
                    if filteredItems.isEmpty {
                        MarketIndicatorEmptyState(
                            isLoading: vm.isLoading,
                            error: vm.error,
                            category: category,
                            region: showsRegionFilter ? region : nil,
                            retry: { Task { await vm.load(refresh: true, category: category.rawValue) } }
                        )
                    } else {
                        ForEach(filteredItems) { item in
                            MarketIndicatorRow(
                                item: item,
                                points: vm.seriesBySymbol[item.symbol] ?? [],
                                isWatched: watchlist.contains(item.symbol),
                                toggleWatch: {
                                    watchlist.toggle(marketIndicatorWatchlistItem(item))
                                }
                            )
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 10)
            }
            .appTabBarInset()
            .refreshable {
                await vm.load(refresh: true, category: category.rawValue)
            }
        }
        .appScreenBackground()
        .navigationTitle("주요 지수")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await vm.load(refresh: true, category: category.rawValue) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(vm.isLoading)
            }
        }
        .task {
            if !vm.hasItems(category: category.rawValue) {
                await vm.load(category: category.rawValue)
            }
            if !requestedFreshOpenSessionLoad, hasOpenMarketSession(vm.items) {
                requestedFreshOpenSessionLoad = true
                await vm.load(refresh: true, category: category.rawValue)
            }
        }
        .onChange(of: category) { _, newCategory in
            if !vm.hasItems(category: newCategory.rawValue) {
                Task { await vm.load(category: newCategory.rawValue) }
            }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                if hasOpenMarketSession(vm.items) {
                    await vm.load(category: category.rawValue)
                }
            }
        }
    }
}

private struct MarketIndicatorEmptyState: View {
    let isLoading: Bool
    let error: String?
    let category: MarketIndicatorCategory
    let region: MarketIndicatorRegion?
    let retry: () -> Void

    private var title: String {
        if isLoading {
            return "주요 지수 로딩 중"
        }
        if error != nil {
            return "지수 데이터를 불러오지 못했습니다"
        }
        return "표시할 지수 없음"
    }

    private var detail: String {
        if isLoading {
            return "\(category.title) 데이터를 확인하고 있습니다."
        }
        if let error {
            return error
        }
        if let region {
            return "\(category.title) · \(region.title) 조건에 맞는 지수가 없습니다."
        }
        return "\(category.title) 조건에 맞는 지수가 없습니다."
    }

    var body: some View {
        EmptyMsg(
            icon: isLoading ? "chart.line.uptrend.xyaxis" : "arrow.clockwise.circle",
            msg: title,
            detail: detail,
            actionTitle: isLoading ? nil : "새로고침",
            action: isLoading ? nil : retry
        )
        .frame(minHeight: 320)
    }
}

private struct MarketIndicatorRow: View {
    let item: MarketIndicatorQuote
    let points: [MarketIndicatorPoint]
    let isWatched: Bool
    let toggleWatch: () -> Void

    private var isPositive: Bool { (item.changePct ?? 0) >= 0 }
    private var moveColor: Color { isPositive ? AppTheme.positive : AppTheme.negative }

    var body: some View {
        let chartPoints = displayMarketIndicatorPoints(for: item, points: points)

        HStack(spacing: 16) {
            IndicatorSparkline(item: item, points: chartPoints, color: moveColor)
                .frame(width: 82, height: 58)

            VStack(alignment: .leading, spacing: 6) {
                Text(item.label)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)

                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(indicatorValueText(item))
                        .font(.title2.weight(.bold))
                        .foregroundStyle(moveColor)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text("\(signedNumber(item.changeAbs)) (\(pct(item.changePct, signed: true)))")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(moveColor)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                }
            }

            Spacer(minLength: 8)

            Button(action: toggleWatch) {
                Image(systemName: isWatched ? "heart.fill" : "heart")
                    .font(.title2)
                    .foregroundStyle(isWatched ? .yellow : AppTheme.tertiaryText.opacity(0.65))
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isWatched ? "\(item.label) 관심 지수 삭제" : "\(item.label) 관심 지수 추가")
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }
}

private func marketIndicatorWatchlistItem(_ item: MarketIndicatorQuote) -> WatchlistItem {
    watchlistItem(
        ticker: item.symbol,
        name: item.label,
        market: marketIndicatorWatchlistMarket(for: item),
        currency: marketIndicatorWatchlistCurrency(for: item),
        note: watchlistMarketIndicatorNote
    )
}

private func marketIndicatorWatchlistMarket(for item: MarketIndicatorQuote) -> String {
    switch item.category {
    case "commodity":
        return "원자재"
    case "crypto":
        return "가상자산"
    case "bond":
        return item.region == "domestic" ? "KR" : "US"
    default:
        if item.region == "domestic" { return "KR" }
        if item.region == "overseas" { return "US" }
        return "GLOBAL"
    }
}

private func marketIndicatorWatchlistCurrency(for item: MarketIndicatorQuote) -> String {
    item.region == "domestic" ? "KRW" : "USD"
}

struct IndicatorSparkline: View {
    let item: MarketIndicatorQuote
    let points: [MarketIndicatorPoint]
    let color: Color

    private var samples: [SparklineSample] {
        sparklineSamples(item: item, points: points)
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1.0 / 18.0)) { timeline in
            Canvas { context, size in
            let samples = samples
            let values = samples.map(\.close).filter { $0.isFinite }
            let showLiveEndpoint = shouldShowLiveEndpoint(item: item, samples: samples, now: timeline.date)
            let referenceClose = previousClose(for: item)
            let domainValues = values + [item.value].filter { $0.isFinite }
            let domain = sparklineDomain(for: domainValues, referenceClose: referenceClose)
            let baselineY = referenceClose.map { yPosition(value: $0, domain: domain, height: size.height) } ?? size.height * 0.72

            if samples.count == 1 {
                let x = min(max(samples[0].progress, 0), 1) * size.width
                drawSparklineBaseline(in: context, size: size, y: baselineY, color: color)
                let y = yPosition(value: samples[0].close, domain: domain, height: size.height)
                context.fill(Path(ellipseIn: CGRect(x: x - 2.4, y: y - 2.4, width: 4.8, height: 4.8)), with: .color(color))
                return
            }

            guard values.count >= 2 else {
                drawSparklineBaseline(in: context, size: size, y: baselineY, color: color)
                var path = Path()
                let y = baselineY
                path.move(to: CGPoint(x: 0, y: y))
                path.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(path, with: .color(color.opacity(0.45)), lineWidth: 1.5)
                return
            }

            var line = Path()
            var fill = Path()
            var lastX: CGFloat = 0
            var lastPoint: CGPoint?
            let fillAnchorY = sparklineFillAnchorY(
                samples: samples,
                referenceClose: referenceClose,
                baselineY: baselineY,
                size: size
            )
            for (idx, sample) in samples.enumerated() {
                let value = sample.close
                let x = min(max(sample.progress, 0), 1) * size.width
                lastX = x
                let y = yPosition(value: value, domain: domain, height: size.height)
                lastPoint = CGPoint(x: x, y: y)
                if idx == 0 {
                    line.move(to: CGPoint(x: x, y: y))
                    fill.move(to: CGPoint(x: x, y: fillAnchorY))
                    fill.addLine(to: CGPoint(x: x, y: y))
                } else {
                    line.addLine(to: CGPoint(x: x, y: y))
                    fill.addLine(to: CGPoint(x: x, y: y))
                }
            }
            fill.addLine(to: CGPoint(x: lastX, y: fillAnchorY))
            fill.closeSubpath()

            drawSparklineBaseline(in: context, size: size, y: baselineY, color: color)
            context.fill(fill, with: .linearGradient(
                Gradient(colors: [color.opacity(0.14), color.opacity(0.01)]),
                startPoint: CGPoint(x: size.width / 2, y: 0),
                endPoint: CGPoint(x: size.width / 2, y: size.height)
            ))
            context.stroke(line, with: .color(color), lineWidth: 1.55)
            if showLiveEndpoint, let lastPoint {
                let haloRadius = liveEndpointHaloRadius(at: timeline.date)
                let haloOpacity = liveEndpointHaloOpacity(at: timeline.date)
                context.fill(
                    Path(ellipseIn: CGRect(
                        x: lastPoint.x - haloRadius,
                        y: lastPoint.y - haloRadius,
                        width: haloRadius * 2,
                        height: haloRadius * 2
                    )),
                    with: .color(color.opacity(haloOpacity))
                )
                context.fill(
                    Path(ellipseIn: CGRect(x: lastPoint.x - 3.1, y: lastPoint.y - 3.1, width: 6.2, height: 6.2)),
                    with: .color(color)
                )
            }
            }
        }
    }
}

private struct SparklineSample: Hashable {
    let close: Double
    let progress: CGFloat
    let date: Date?
}

private let indicatorSparklineMaximumSampleCount = 48
private let indicatorSparklineMinimumRelativeSpan = 0.024
private let indicatorSparklinePaddingRatio = 0.10

private func previousClose(for item: MarketIndicatorQuote) -> Double? {
    guard item.value.isFinite else { return nil }
    if let changeAbs = item.changeAbs, changeAbs.isFinite {
        return item.value - changeAbs
    }
    if let changePct = item.changePct, changePct.isFinite, changePct > -0.9999 {
        return item.value / (1 + changePct)
    }
    return nil
}

private func sparklineDomain(for values: [Double], referenceClose: Double?) -> ClosedRange<Double> {
    let clean = values.filter { $0.isFinite } + [referenceClose].compactMap { $0 }.filter { $0.isFinite }
    guard let minValue = clean.min(), let maxValue = clean.max() else {
        return 0...1
    }
    let anchor = clean.first(where: { $0.isFinite && abs($0) > 0.0001 }) ?? max(abs(maxValue), 1)
    let minimumSpan = max(abs(anchor) * indicatorSparklineMinimumRelativeSpan, 0.0001)
    let spread = max(maxValue - minValue, minimumSpan)
    let midpoint = (minValue + maxValue) / 2
    let padding = spread * indicatorSparklinePaddingRatio
    return (midpoint - spread / 2 - padding)...(midpoint + spread / 2 + padding)
}

private func sparklineFillAnchorY(
    samples: [SparklineSample],
    referenceClose: Double?,
    baselineY: CGFloat,
    size: CGSize
) -> CGFloat {
    size.height
}

private func liveEndpointHaloRadius(at date: Date) -> CGFloat {
    5.0 + 3.3 * liveEndpointPulse(at: date)
}

private func liveEndpointHaloOpacity(at date: Date) -> Double {
    0.22 - 0.07 * Double(liveEndpointPulse(at: date))
}

private func liveEndpointPulse(at date: Date) -> CGFloat {
    let cycle: TimeInterval = 1.15
    let rawPhase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
    return CGFloat(0.5 - 0.5 * cos(rawPhase * 2 * .pi))
}

private func yPosition(value: Double, domain: ClosedRange<Double>, height: CGFloat) -> CGFloat {
    let span = max(domain.upperBound - domain.lowerBound, 0.0001)
    let usableHeight = max(height - 8, 1)
    return height - CGFloat((value - domain.lowerBound) / span) * usableHeight - 4
}

private func drawSparklineBaseline(in context: GraphicsContext, size: CGSize, y: CGFloat, color: Color) {
    var baseline = Path()
    baseline.move(to: CGPoint(x: 0, y: y))
    baseline.addLine(to: CGPoint(x: size.width, y: y))
    context.stroke(baseline, with: .color(color.opacity(0.14)), lineWidth: 1)
}

private func sparklineSamples(item: MarketIndicatorQuote, points: [MarketIndicatorPoint]) -> [SparklineSample] {
    let clean = stableMarketIndicatorPoints(points, quote: item)
    guard !clean.isEmpty else { return [] }

    if let session = marketSession(for: item) {
        let rawSessionSamples = clean.compactMap { point -> SparklineSample? in
            guard let date = marketDate(from: point.timestamp) else { return nil }
            guard let progress = sessionProgress(date: date, session: session) else { return nil }
            return SparklineSample(close: point.close, progress: progress, date: date)
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = session.timeZone
        let latestSessionDay = rawSessionSamples
            .compactMap { sample in sample.date.map { calendar.startOfDay(for: $0) } }
            .max()
        let sessionSamples = rawSessionSamples
            .filter { sample in
                guard let latestSessionDay, let date = sample.date else { return true }
                return calendar.startOfDay(for: date) == latestSessionDay
            }
            .sorted { $0.progress < $1.progress }
        if isMarketSessionOpen(for: item),
           let latestSessionDay,
           !calendar.isDate(latestSessionDay, inSameDayAs: Date()),
           let fallbackSamples = fallbackSessionSamples(for: item) {
            return fallbackSamples
        }
        if hasUsableTimeline(sessionSamples) {
            return downsampleSparklineSamples(sessionSamples)
        }
    }

    return indexedSparklineSamples(clean)
}

private func fallbackSessionSamples(for item: MarketIndicatorQuote) -> [SparklineSample]? {
    let fallbackPoints = stableMarketIndicatorPoints(fallbackMarketIndicatorPoints(for: item), quote: item)
    guard let session = marketSession(for: item), !fallbackPoints.isEmpty else { return nil }

    let samples = fallbackPoints.compactMap { point -> SparklineSample? in
        guard let date = marketDate(from: point.timestamp) else { return nil }
        guard let progress = sessionProgress(date: date, session: session) else { return nil }
        return SparklineSample(close: point.close, progress: progress, date: date)
    }.sorted { $0.progress < $1.progress }

    guard hasUsableTimeline(samples) else { return nil }
    return downsampleSparklineSamples(samples)
}

private func chronologicallySorted(_ points: [MarketIndicatorPoint]) -> [MarketIndicatorPoint] {
    points.sorted {
        (marketDate(from: $0.timestamp) ?? .distantPast) < (marketDate(from: $1.timestamp) ?? .distantPast)
    }
}

private func indexedSparklineSamples(_ points: [MarketIndicatorPoint]) -> [SparklineSample] {
    let clean = points.filter { $0.close.isFinite }
    if clean.count == 1 {
        return [SparklineSample(close: clean[0].close, progress: 0, date: marketDate(from: clean[0].timestamp))]
    }
    let samples = clean.enumerated().map { idx, point in
        SparklineSample(
            close: point.close,
            progress: CGFloat(idx) / CGFloat(max(clean.count - 1, 1)),
            date: marketDate(from: point.timestamp)
        )
    }
    return downsampleSparklineSamples(samples)
}

private func downsampleSparklineSamples(_ samples: [SparklineSample]) -> [SparklineSample] {
    guard samples.count > indicatorSparklineMaximumSampleCount,
          indicatorSparklineMaximumSampleCount > 1
    else {
        return samples
    }

    let lastIndex = samples.count - 1
    let targetLastIndex = indicatorSparklineMaximumSampleCount - 1
    var output: [SparklineSample] = []
    output.reserveCapacity(indicatorSparklineMaximumSampleCount)

    for targetIndex in 0...targetLastIndex {
        let sourceIndex = Int(
            (Double(targetIndex) * Double(lastIndex) / Double(targetLastIndex)).rounded()
        )
        let sample = samples[min(max(sourceIndex, 0), lastIndex)]
        if output.last != sample {
            output.append(sample)
        }
    }

    if output.last != samples.last, let last = samples.last {
        output.append(last)
    }
    return output
}

private func hasUsableTimeline(_ samples: [SparklineSample]) -> Bool {
    guard samples.count > 1 else { return !samples.isEmpty }
    let progressValues = samples.map(\.progress)
    let distinctProgressCount = progressValues.reduce(into: [CGFloat]()) { distinct, progress in
        if !distinct.contains(where: { abs($0 - progress) < 0.0001 }) {
            distinct.append(progress)
        }
    }.count
    return distinctProgressCount >= min(2, samples.count)
}

private struct IndicatorSession {
    let timeZone: TimeZone
    let startHour: Int
    let startMinute: Int
    let endHour: Int
    let endMinute: Int
}

private func marketSession(for item: MarketIndicatorQuote) -> IndicatorSession? {
    marketSession(forSymbol: item.symbol)
}

private func marketSession(forSymbol symbol: String) -> IndicatorSession? {
    switch symbol.uppercased() {
    case "^IXIC", "NQ=F", "^GSPC", "ES=F", "RTY=F", "^DJI", "^SOX", "^VIX":
        return IndicatorSession(
            timeZone: TimeZone(identifier: "America/New_York") ?? .current,
            startHour: 9,
            startMinute: 30,
            endHour: 16,
            endMinute: 0
        )
    case "^KS11", "^KQ11":
        return IndicatorSession(
            timeZone: TimeZone(identifier: "Asia/Seoul") ?? .current,
            startHour: 9,
            startMinute: 0,
            endHour: 15,
            endMinute: 30
        )
    default:
        return nil
    }
}

private func hasOpenMarketSession(_ items: [MarketIndicatorQuote], now: Date = Date()) -> Bool {
    items.contains { isMarketSessionOpen(for: $0, now: now) }
}

private func shouldShowLiveEndpoint(item: MarketIndicatorQuote, samples: [SparklineSample], now: Date) -> Bool {
    guard
        let session = marketSession(for: item),
        isMarketSessionOpen(for: item, now: now),
        let lastDate = samples.last?.date,
        isSameSessionDay(lastDate, now, session: session)
    else {
        return false
    }
    let age = now.timeIntervalSince(lastDate)
    return age >= -60 && age <= 2 * 60 * 60
}

private func isMarketSessionOpen(for item: MarketIndicatorQuote, now: Date = Date()) -> Bool {
    guard let session = marketSession(for: item) else { return false }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = session.timeZone
    let weekday = calendar.component(.weekday, from: now)
    guard (2...6).contains(weekday) else { return false }
    guard let interval = sessionInterval(for: now, session: session) else { return false }
    return now >= interval.start && now <= interval.end
}

private func isSameSessionDay(_ lhs: Date, _ rhs: Date, session: IndicatorSession) -> Bool {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = session.timeZone
    return calendar.isDate(lhs, inSameDayAs: rhs)
}

private func sessionInterval(for date: Date, session: IndicatorSession) -> (start: Date, end: Date)? {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = session.timeZone
    let components = calendar.dateComponents([.year, .month, .day], from: date)
    guard
        let start = calendar.date(from: DateComponents(
            timeZone: session.timeZone,
            year: components.year,
            month: components.month,
            day: components.day,
            hour: session.startHour,
            minute: session.startMinute
        )),
        let end = calendar.date(from: DateComponents(
            timeZone: session.timeZone,
            year: components.year,
            month: components.month,
            day: components.day,
            hour: session.endHour,
            minute: session.endMinute
        ))
    else {
        return nil
    }
    return (start, end)
}

private func marketDate(from raw: String) -> Date? {
    ISO8601DateFormatter.marketIndicator.date(from: raw) ??
        ISO8601DateFormatter.marketIndicatorFractional.date(from: raw)
}

private func sessionProgress(date: Date, session: IndicatorSession) -> CGFloat? {
    guard let interval = sessionInterval(for: date, session: session) else { return nil }
    guard date >= interval.start && date <= interval.end else { return nil }
    let start = interval.start
    let end = interval.end
    let total = max(end.timeIntervalSince(start), 1)
    let elapsed = date.timeIntervalSince(start)
    return min(max(CGFloat(elapsed / total), 0), 1)
}

private extension ISO8601DateFormatter {
    static let marketIndicator: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static let marketIndicatorFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

private func indicatorValueText(_ item: MarketIndicatorQuote) -> String {
    if item.value >= 1_000 {
        return String(format: "%.2f", item.value)
    }
    if item.value >= 100 {
        return String(format: "%.2f", item.value)
    }
    return String(format: "%.4g", item.value)
}

private func signedNumber(_ value: Double?) -> String {
    guard let value, value.isFinite else { return "-" }
    if abs(value) >= 100 {
        return String(format: "%+.2f", value)
    }
    return String(format: "%+.3g", value)
}
