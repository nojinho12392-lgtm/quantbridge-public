import Combine
import Foundation
import SwiftUI

@MainActor
private final class ComparisonTargetPickerVM: ObservableObject {
    @Published var candidates: [StockComparisonItem] = []
    @Published var searchCandidates: [StockComparisonItem] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private var loadedInitial = false

    func loadInitial(anchor: StockComparisonItem) async {
        guard !loadedInitial else { return }
        loadedInitial = true
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        async let recommendations = result {
            try await APIClient.shared.fetch(
                ["comparison", "recommendations", anchor.ticker],
                queryItems: [
                    URLQueryItem(name: "market", value: anchor.market ?? "ALL"),
                    URLQueryItem(name: "limit", value: "12")
                ]
            ) as ComparisonRecommendationsResponse
        }
        async let usPortfolio = result { try await APIClient.shared.fetch(["portfolio", "us"]) as PortfolioResponse }
        async let krPortfolio = result { try await APIClient.shared.fetch(["portfolio", "kr"]) as PortfolioResponse }
        async let usSmallCap = result { try await APIClient.shared.fetch(["smallcap", "us"]) as SmallCapResponse }
        async let krSmallCap = result { try await APIClient.shared.fetch(["smallcap", "kr"]) as SmallCapResponse }
        async let usScored = result { try await APIClient.shared.fetchScored(market: .us, limit: 120) }
        async let krScored = result { try await APIClient.shared.fetchScored(market: .kr, limit: 120) }

        let results = await (recommendations, usPortfolio, krPortfolio, usSmallCap, krSmallCap, usScored, krScored)
        var items: [StockComparisonItem] = []
        var failures: [String] = []

        switch results.0 {
        case .success(let response):
            items.append(contentsOf: response.items.map(recommendedComparisonItem))
        case .failure:
            break
        }
        switch results.1 {
        case .success(let response):
            items.append(contentsOf: response.stocks.map { StockComparisonItem(portfolio: $0, currency: Market.us.currency) })
        case .failure(let error):
            failures.append(error.localizedDescription)
        }
        switch results.2 {
        case .success(let response):
            items.append(contentsOf: response.stocks.map { StockComparisonItem(portfolio: $0, currency: Market.kr.currency) })
        case .failure(let error):
            failures.append(error.localizedDescription)
        }
        switch results.3 {
        case .success(let response):
            items.append(contentsOf: response.stocks.map { StockComparisonItem(smallCap: $0) })
        case .failure(let error):
            failures.append(error.localizedDescription)
        }
        switch results.4 {
        case .success(let response):
            items.append(contentsOf: response.stocks.map { StockComparisonItem(smallCap: $0) })
        case .failure(let error):
            failures.append(error.localizedDescription)
        }
        switch results.5 {
        case .success(let response):
            items.append(contentsOf: response.stocks.map(scoredComparisonItem))
        case .failure(let error):
            failures.append(error.localizedDescription)
        }
        switch results.6 {
        case .success(let response):
            items.append(contentsOf: response.stocks.map(scoredComparisonItem))
        case .failure(let error):
            failures.append(error.localizedDescription)
        }

        candidates = distinctComparisonItems(items)
        if candidates.isEmpty, !failures.isEmpty {
            errorMessage = "비교 후보를 불러오지 못했습니다."
        }
    }

    func search(query: String) async {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 2 else {
            searchCandidates = []
            return
        }

        do {
            let response = try await APIClient.shared.searchUniverse(query: clean, limit: 80)
            searchCandidates = distinctComparisonItems(response.stocks.map(searchComparisonItem))
        } catch {
            searchCandidates = []
        }
    }

    private func result<T>(_ work: @escaping () async throws -> T) async -> Result<T, Error> {
        do {
            return .success(try await work())
        } catch {
            return .failure(error)
        }
    }
}

struct ComparisonTargetPickerSheet: View {
    let anchor: StockComparisonItem
    let onCompare: () -> Void

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var comparison: ComparisonStore
    @EnvironmentObject private var watchlist: WatchlistStore
    @StateObject private var vm = ComparisonTargetPickerVM()
    @State private var query = ""
    @State private var selectedItems: [StockComparisonItem] = []

    private var visibleCandidates: [StockComparisonItem] {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let watchItems = watchlist.items
            .filter { !$0.isMarketIndicator }
            .map { StockComparisonItem(watchlist: $0) }
        let all = distinctComparisonItems(vm.searchCandidates + vm.candidates + watchItems)
            .filter { normalizedTicker($0.ticker) != normalizedTicker(anchor.ticker) }
        let filtered = clean.isEmpty ? all : all.filter { comparisonCandidateMatches($0, query: clean) }
        return filtered
            .sorted { lhs, rhs in
                let left = comparisonCandidatePriority(lhs, anchor: anchor)
                let right = comparisonCandidatePriority(rhs, anchor: anchor)
                if left != right { return left > right }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
            .prefix(30)
            .map { $0 }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ComparisonAnchorCard(anchor: anchor, selectedCount: selectedItems.count)

                    ComparisonSelectedStrip(
                        items: selectedItems,
                        anchor: anchor,
                        remove: removeSelected
                    )

                    AppSearchField(text: $query, prompt: "비교할 기업 검색")

                    candidateSection

                    ComparisonImmediateActionBar(
                        selectedCount: selectedItems.count,
                        reset: { selectedItems = [anchor] },
                        compare: compareNow
                    )
                }
                .padding()
            }
            .appScreenBackground()
            .navigationTitle("비교 대상 선택")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        LucideIconView(icon: .x, size: 18)
                    }
                    .accessibilityLabel("닫기")
                }
            }
        }
        .onAppear(perform: primeSelection)
        .task { await vm.loadInitial(anchor: anchor) }
        .task(id: query) {
            let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard clean.count >= 2 else {
                await vm.search(query: clean)
                return
            }
            try? await Task.sleep(nanoseconds: 350_000_000)
            await vm.search(query: clean)
        }
    }

    private var candidateSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "추천 비교 대상" : "검색 결과")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Spacer()
                if vm.isLoading {
                    ProgressView()
                        .scaleEffect(0.75)
                }
            }

            if let errorMessage = vm.errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(AppTheme.warning)
            }

            if visibleCandidates.isEmpty {
                Text("현재 조건에 맞는 비교 후보가 없습니다.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 8))
            } else {
                VStack(spacing: 0) {
                    ForEach(visibleCandidates) { item in
                        ComparisonCandidateRow(
                            item: item,
                            reason: comparisonCandidateReason(item, anchor: anchor),
                            selected: selectedContains(item),
                            canAdd: selectedItems.count < 4,
                            toggle: { toggle(item) }
                        )
                        if item.id != visibleCandidates.last?.id {
                            Divider()
                                .overlay(AppTheme.hairline)
                        }
                    }
                }
                .appCard(padding: 10)
            }
        }
    }

    private func primeSelection() {
        selectedItems = [anchor]
    }

    private func selectedContains(_ item: StockComparisonItem) -> Bool {
        let key = normalizedTicker(item.ticker)
        return selectedItems.contains { $0.id == item.id || normalizedTicker($0.ticker) == key }
    }

    private func removeSelected(_ item: StockComparisonItem) {
        let key = normalizedTicker(item.ticker)
        guard key != normalizedTicker(anchor.ticker) else { return }
        selectedItems.removeAll { $0.id == item.id || normalizedTicker($0.ticker) == key }
    }

    private func toggle(_ item: StockComparisonItem) {
        if selectedContains(item) {
            removeSelected(item)
            return
        }
        guard selectedItems.count < 4 else { return }
        selectedItems.append(item)
    }

    private func compareNow() {
        comparison.replace(with: selectedItems)
        dismiss()
        onCompare()
    }
}

private struct ComparisonAnchorCard: View {
    let anchor: StockComparisonItem
    let selectedCount: Int

    var body: some View {
        HStack(spacing: 10) {
            CompanyLogoView(ticker: anchor.ticker, currency: anchor.currency, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    LucideIconView(icon: .gitCompare, size: 13)
                        .foregroundStyle(AppTheme.accent)
                    Text(anchor.name)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                }
                Text("\(anchor.ticker) · 기준 종목")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
            }
            Spacer()
            Text("선택 \(selectedCount)/4")
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.accent)
        }
        .padding(12)
        .background(AppTheme.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(AppTheme.accent.opacity(0.18), lineWidth: 1)
        )
    }
}

private struct ComparisonSelectedStrip: View {
    let items: [StockComparisonItem]
    let anchor: StockComparisonItem
    let remove: (StockComparisonItem) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("선택된 비교군")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            ForEach(items) { item in
                let isAnchor = normalizedTicker(item.ticker) == normalizedTicker(anchor.ticker)
                HStack(spacing: 8) {
                    CompanyLogoView(ticker: item.ticker, currency: item.currency, size: 30)
                    Text(item.name)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Spacer()
                    Text(isAnchor ? "기준" : item.source)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                    if !isAnchor {
                        Button {
                            remove(item)
                        } label: {
                            LucideIconView(icon: .x, size: 14)
                                .foregroundStyle(AppTheme.secondaryText)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .appCard(padding: 12)
    }
}

private struct ComparisonImmediateActionBar: View {
    let selectedCount: Int
    let reset: () -> Void
    let compare: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(selectedCount >= 2 ? "선택 완료 \(selectedCount)/4" : "비교 대상 1개 더 선택")
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
            Spacer()
            Button(action: reset) {
                HStack(spacing: 4) {
                    LucideIconView(icon: .refreshCw, size: 12)
                    Text("기준만")
                }
            }
                .font(.caption.weight(.semibold))
                .buttonStyle(.bordered)
                .tint(AppTheme.secondaryText)
            Button(action: compare) {
                HStack(spacing: 4) {
                    LucideIconView(icon: .gitCompare, size: 12)
                    Text("바로 비교하기")
                }
            }
                .font(.caption.weight(.bold))
                .buttonStyle(.borderedProminent)
                .disabled(selectedCount < 2)
        }
        .padding(12)
        .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct ComparisonCandidateRow: View {
    let item: StockComparisonItem
    let reason: String
    let selected: Bool
    let canAdd: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 10) {
                CompanyLogoView(ticker: item.ticker, currency: item.currency, size: 34)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Text([item.ticker, item.sector, item.source].compactMap { $0 }.joined(separator: " · "))
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                    Text(reason)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.accent)
                        .lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(item.headlineScoreText == "-" ? cap(item.marketCap, currency: item.currency) : item.headlineScoreText)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Text(pct(item.revenueGrowth))
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.secondaryText)
                }
                LucideIconView(icon: selected ? .check : .plus, size: 20)
                    .foregroundStyle(selected ? AppTheme.accent : AppTheme.secondaryText.opacity(canAdd ? 1 : 0.35))
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .disabled(!selected && !canAdd)
        .buttonStyle(.plain)
        .opacity(!selected && !canAdd ? 0.45 : 1)
    }
}

private func comparisonCandidateMatches(_ item: StockComparisonItem, query: String) -> Bool {
    let clean = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !clean.isEmpty else { return true }
    return item.ticker.lowercased().contains(clean)
        || item.name.lowercased().contains(clean)
        || (item.sector?.lowercased().contains(clean) ?? false)
}

private func comparisonCandidatePriority(_ item: StockComparisonItem, anchor: StockComparisonItem) -> Int {
    var value = 0
    if item.market?.caseInsensitiveCompare(anchor.market ?? "") == .orderedSame { value += 4 }
    if item.sector?.caseInsensitiveCompare(anchor.sector ?? "") == .orderedSame { value += 7 }
    if similarMarketCap(item.marketCap, anchor.marketCap) { value += 3 }
    if item.source == "Portfolio" { value += 4 }
    if item.source == "Watch" { value += 3 }
    if item.source == "SmallCap" || item.source == "스몰캡" { value += 2 }
    if let score = item.score, score.isFinite { value += min(3, max(1, Int(score.rounded()))) }
    if item.revenueGrowth != nil && anchor.revenueGrowth != nil { value += 2 }
    if item.roic != nil && anchor.roic != nil { value += 1 }
    return value
}

private func comparisonCandidateReason(_ item: StockComparisonItem, anchor: StockComparisonItem) -> String {
    var reasons: [String] = []
    if item.sector?.caseInsensitiveCompare(anchor.sector ?? "") == .orderedSame {
        reasons.append("같은 섹터")
    }
    if item.market?.caseInsensitiveCompare(anchor.market ?? "") == .orderedSame {
        reasons.append("같은 시장")
    }
    if similarMarketCap(item.marketCap, anchor.marketCap) {
        reasons.append("비슷한 규모")
    }
    if item.source == "Portfolio" {
        reasons.append("핵심 후보")
    } else if item.source == "SmallCap" || item.source == "스몰캡" {
        reasons.append("스몰캡 대조")
    } else if item.source == "Watch" {
        reasons.append("관심종목")
    }
    if let growth = item.revenueGrowth, growth.isFinite, growth > 0.12 {
        reasons.append("성장성")
    } else if item.revenueGrowth != nil && anchor.revenueGrowth != nil {
        reasons.append("성장 비교")
    }
    if let roic = item.roic, roic.isFinite, roic > 0.12 {
        reasons.append("퀄리티")
    }
    return reasons.prefix(3).joined(separator: " · ").ifEmpty("\(item.source) 비교")
}

private func similarMarketCap(_ lhs: Double?, _ rhs: Double?) -> Bool {
    guard let lhs, let rhs, lhs.isFinite, rhs.isFinite, lhs > 0, rhs > 0 else { return false }
    return abs(log(lhs) - log(rhs)) < 1.2
}

private func distinctComparisonItems(_ items: [StockComparisonItem]) -> [StockComparisonItem] {
    var seen = Set<String>()
    return items.compactMap { item in
        let key = normalizedTicker(item.ticker)
        guard !seen.contains(key) else { return nil }
        seen.insert(key)
        return item
    }
}

private func searchComparisonItem(_ stock: SearchStock) -> StockComparisonItem {
    StockComparisonItem(
        ticker: stock.ticker,
        name: stock.name,
        market: stock.market,
        sector: stock.sector,
        currency: stock.currency ?? marketCurrency(for: stock.ticker, market: stock.market),
        source: "검색",
        marketCap: stock.marketCap
    )
}

private func scoredComparisonItem(_ stock: ScoredStock) -> StockComparisonItem {
    StockComparisonItem(
        ticker: stock.ticker,
        name: stock.name,
        market: stock.market,
        sector: stock.sector,
        currency: marketCurrency(for: stock.ticker, market: stock.market),
        source: "랭킹",
        score: bestScoredValue(stock),
        revenueGrowth: stock.revGrowth,
        roic: stock.roic,
        grossMargin: stock.grossMargin,
        marketCap: stock.marketCap,
        fcfMargin: stock.fcfMargin
    )
}

private func recommendedComparisonItem(_ item: ComparisonRecommendationItem) -> StockComparisonItem {
    StockComparisonItem(
        ticker: item.ticker,
        name: item.name,
        market: item.market,
        sector: item.sector,
        currency: item.currency ?? marketCurrency(for: item.ticker, market: item.market),
        source: item.source ?? "추천",
        score: item.scoreValue,
        expectedReturn: item.expectedReturn,
        revenueGrowth: item.revenueGrowth,
        roic: item.roic,
        grossMargin: item.grossMargin,
        marketCap: item.marketCap,
        currentPrice: item.currentPrice,
        return1M: item.return1M,
        rankChange: item.rankChange,
        weight: item.weight,
        fcfMargin: item.fcfMargin,
        volumeSurge: item.volumeSurge,
        updatedAt: item.updatedAt
    )
}

private func bestScoredValue(_ stock: ScoredStock) -> Double? {
    [stock.combinedScore, stock.finalScore, stock.totalScore, stock.scoreNeutral, stock.mlScore]
        .compactMap { $0 }
        .first { $0.isFinite }
}

private extension String {
    func ifEmpty(_ fallback: @autoclosure () -> String) -> String {
        isEmpty ? fallback() : self
    }
}
