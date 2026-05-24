import Combine
import Foundation
import SwiftUI

enum SectorMarketScope: String, CaseIterable, Identifiable, Hashable {
    case all = "전체"
    case us = "미국"
    case kr = "국내"

    var id: String { rawValue }
    var apiValue: String {
        switch self {
        case .all: "ALL"
        case .us: "US"
        case .kr: "KR"
        }
    }
}

struct SectorThemesResponse: Codable {
    let market: String
    let generatedAt: String?
    let source: String?
    let count: Int
    let items: [SectorTheme]

    enum CodingKeys: String, CodingKey {
        case market
        case generatedAt = "generated_at"
        case source
        case count
        case items
    }
}

struct SectorThemeDetailResponse: Codable {
    let market: String
    let generatedAt: String?
    let source: String?
    let item: SectorTheme

    enum CodingKeys: String, CodingKey {
        case market
        case generatedAt = "generated_at"
        case source
        case item
    }
}

struct SectorTheme: Codable, Identifiable, Hashable {
    let label: String
    let market: String
    let memberCount: Int
    let pricedCount: Int
    let risingCount: Int
    let fallingCount: Int
    let avgChangePct: Double?
    let avgReturn1M: Double?
    let leader: SectorThemeMember?
    let members: [SectorThemeMember]

    var id: String { "\(market)-\(label)" }

    enum CodingKeys: String, CodingKey {
        case label
        case market
        case memberCount = "member_count"
        case pricedCount = "priced_count"
        case risingCount = "rising_count"
        case fallingCount = "falling_count"
        case avgChangePct = "avg_change_pct"
        case avgReturn1M = "avg_return_1m"
        case leader
        case members
    }
}

struct SectorThemeMember: Codable, Identifiable, Hashable {
    let ticker: String
    let name: String
    let market: String?
    let sector: String?
    let currency: String?
    let source: String?
    let marketCap: Double?
    let currentPrice: Double?
    let dailyChangePct: Double?
    let dailyChangeHorizon: String?
    let return1M: Double?
    let scoreValue: Double?
    let inPortfolio: Bool
    let inSmallCap: Bool

    var id: String { "\(market ?? "")-\(ticker)" }
    var displayName: String { displayCompanyName(name, ticker: ticker) }
    var resolvedCurrency: String { currency ?? marketCurrency(for: ticker, market: market) }
    var marketLabel: String {
        if market?.uppercased() == "KR" { return "국내" }
        if market?.uppercased() == "US" { return "미국" }
        return market ?? "-"
    }
    var dailyChangeLabel: String {
        let value = dailyChangeHorizon?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? "오늘" : value
    }

    enum CodingKeys: String, CodingKey {
        case ticker = "Ticker"
        case name = "Name"
        case market = "Market"
        case sector = "Sector"
        case currency = "Currency"
        case source = "Source"
        case marketCap = "MarketCap"
        case currentPrice = "Current_Price"
        case dailyChangePct = "Daily_Change_Pct"
        case dailyChangeHorizon = "Daily_Change_Horizon"
        case return1M = "Return_1M"
        case scoreValue = "Score_Value"
        case inPortfolio = "In_Portfolio"
        case inSmallCap = "In_SmallCap"
    }
}

@MainActor
final class PortfolioSectorThemeVM: ObservableObject {
    @Published var items: [SectorTheme] = []
    @Published var state: APIResult<Bool> = .idle
    @Published var warning: String?
    @Published var source: String?
    @Published var generatedAt: String?

    private var loadedMarket = ""
    private let api: APIClientProtocol
    private let cache = SectorThemeSnapshotStore.shared

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load(market: String = "ALL") async {
        if case .loading = state { return }
        guard market != loadedMarket || items.isEmpty else { return }
        await fetch(market: market, refresh: false)
    }

    func refresh(market: String = "ALL") async {
        await fetch(market: market, refresh: true)
    }

    func reloadFromSnapshot(market: String = "ALL") async {
        await fetch(market: market, refresh: false)
    }

    private func fetch(market: String, refresh: Bool) async {
        if case .loading = state { return }
        var hasVisibleData = !items.isEmpty && loadedMarket == market
        if !refresh, let cached = await cache.readSummary(market: market) {
            apply(cached)
            hasVisibleData = true
        }
        if !hasVisibleData || refresh {
            state = .loading
        }
        warning = nil
        do {
            let response = try await api.fetchSectorThemes(market: market, refresh: refresh)
            apply(response)
            await cache.writeSummary(response)
        } catch {
            if !hasVisibleData && items.isEmpty {
                state = .failure(error.localizedDescription)
            } else {
                warning = "마지막 성공 데이터를 표시 중입니다.\n\(error.localizedDescription)"
                state = .success(true)
            }
        }
    }

    private func apply(_ response: SectorThemesResponse) {
        items = response.items
        source = response.source
        generatedAt = response.generatedAt
        loadedMarket = response.market
        state = .success(true)
    }
}

private final class SectorThemeSnapshotStore {
    static let shared = SectorThemeSnapshotStore()

    private let directory: URL

    private init() {
        let cacheRoot = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        directory = cacheRoot.appendingPathComponent("sector-theme-snapshots", isDirectory: true)
    }

    func readSummary(market: String) async -> SectorThemesResponse? {
        await read(SectorThemesResponse.self, from: summaryURL(market: market))
    }

    func writeSummary(_ response: SectorThemesResponse) async {
        guard !response.items.isEmpty else { return }
        await write(response, to: summaryURL(market: response.market))
    }

    func readDetail(label: String, market: String) async -> SectorThemeDetailResponse? {
        await read(SectorThemeDetailResponse.self, from: detailURL(label: label, market: market))
    }

    func writeDetail(_ response: SectorThemeDetailResponse, label: String, market: String) async {
        guard !response.item.members.isEmpty else { return }
        await write(response, to: detailURL(label: label, market: market))
    }

    private func read<T: Decodable>(_ type: T.Type, from url: URL) async -> T? {
        await Task.detached(priority: .utility) {
            guard let data = try? Data(contentsOf: url) else { return nil }
            return try? JSONDecoder().decode(type, from: data)
        }.value
    }

    private func write<T: Encodable>(_ value: T, to url: URL) async {
        await Task.detached(priority: .utility) { [directory] in
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let data = try JSONEncoder().encode(value)
                try data.write(to: url, options: [.atomic])
            } catch {
                // Cache writes should never block the screen.
            }
        }.value
    }

    private func summaryURL(market: String) -> URL {
        directory.appendingPathComponent("summary-\(cacheSegment(market)).json")
    }

    private func detailURL(label: String, market: String) -> URL {
        directory.appendingPathComponent("detail-\(cacheSegment(market))-\(cacheSegment(label)).json")
    }
}

struct PortfolioSectorThemeView: View {
    @ObservedObject var vm: PortfolioSectorThemeVM
    @State private var marketScope: SectorMarketScope = .all
    @State private var query = ""
    @State private var selectedTheme: SectorTheme?

    private var isLoading: Bool {
        if case .loading = vm.state { return true }
        return false
    }

    private var errorMessage: String? {
        if case .failure(let message) = vm.state { return message }
        return nil
    }

    private var cleanQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var filteredThemes: [SectorTheme] {
        guard !cleanQuery.isEmpty else { return vm.items }
        return vm.items.filter { sectorThemeMatches($0, query: cleanQuery) }
    }

    private var topThemes: [SectorTheme] {
        Array(filteredThemes.prefix(3))
    }

    private var remainingThemes: [SectorTheme] {
        Array(filteredThemes.dropFirst(3))
    }

    private var themeGridColumns: [GridItem] {
        [
            GridItem(.flexible(), spacing: 10, alignment: .top),
            GridItem(.flexible(), spacing: 10, alignment: .top),
        ]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SectorThemesHeader(
                    count: filteredThemes.count,
                    summary: sectorThemeSummary(topThemes.isEmpty ? vm.items : topThemes),
                    source: vm.source,
                    updatedAt: vm.generatedAt
                )
                PortfolioFilterChipRow(title: "시장", options: SectorMarketScope.allCases, selection: $marketScope) { scope in
                    scope.rawValue
                }
                AppSearchField(text: $query, prompt: "테마, 기업명, 티커 검색")

                if let warning = vm.warning {
                    InlineWarningBanner(msg: warning) {
                        Task { await vm.refresh(market: marketScope.apiValue) }
                    }
                    .appCard(padding: 12)
                }

                if isLoading && vm.items.isEmpty {
                    LoadingStateView(title: "섹터 흐름 로딩 중", detail: "테마별 기업 묶음과 당일 변동률을 계산하고 있습니다.")
                        .frame(maxWidth: .infinity)
                        .appCard(padding: 18)
                } else if let errorMessage, vm.items.isEmpty {
                    ErrView(msg: errorMessage) {
                        Task { await vm.refresh(market: marketScope.apiValue) }
                    }
                    .appCard(padding: 12)
                } else if vm.items.isEmpty {
                    EmptyMsg(
                        icon: "square.grid.2x2",
                        msg: "섹터 데이터 없음",
                        detail: "분석 가능한 기업 묶음이 아직 없습니다.",
                        actionTitle: "새로고침",
                        action: { Task { await vm.refresh(market: marketScope.apiValue) } }
                    )
                    .appCard(padding: 16)
                } else if filteredThemes.isEmpty {
                    EmptyMsg(
                        icon: "magnifyingglass",
                        msg: "검색 결과 없음",
                        detail: "\"\(cleanQuery)\"와 일치하는 테마나 기업이 없습니다.",
                        actionTitle: nil,
                        action: nil
                    )
                    .appCard(padding: 16)
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        SectionTitleRow(title: "오늘 움직인 테마 Top 3", trailing: "\(topThemes.count)개")
                        LazyVGrid(columns: themeGridColumns, spacing: 10) {
                            ForEach(topThemes) { theme in
                                SectorThemeGridCard(theme: theme) {
                                    selectedTheme = theme
                                }
                            }
                        }
                    }

                    if !remainingThemes.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitleRow(title: "전체 테마", trailing: "\(remainingThemes.count)개")
                            LazyVGrid(columns: themeGridColumns, spacing: 10) {
                                ForEach(remainingThemes) { theme in
                                    SectorThemeGridCard(theme: theme) {
                                        selectedTheme = theme
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
        .appTabBarInset()
        .background(AppTheme.background.ignoresSafeArea())
        .refreshable { await vm.refresh(market: marketScope.apiValue) }
        .task(id: marketScope) { await vm.load(market: marketScope.apiValue) }
        .task(id: "sector-price-auto-\(marketScope.rawValue)") {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: QuantRefreshInterval.standardPrices)
                guard !Task.isCancelled else { return }
                guard await QuantRefreshGate.shared.shouldRun("sectors-\(marketScope.rawValue)", minInterval: 120) else { continue }
                await vm.reloadFromSnapshot(market: marketScope.apiValue)
            }
        }
        .sheet(item: $selectedTheme) { theme in
            SectorThemeDetailSheet(theme: theme)
        }
        .overlay(alignment: .top) {
            LoadingOverlay(isVisible: isLoading && !vm.items.isEmpty)
        }
    }
}

private struct SectorThemesHeader: View {
    let count: Int
    let summary: String
    let source: String?
    let updatedAt: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            HStack(alignment: .top, spacing: 11) {
                ZStack {
                    Circle()
                        .fill(AppTheme.accent.opacity(0.10))
                    LucideIconView(icon: .building2, size: 18)
                        .foregroundStyle(AppTheme.accent)
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 4) {
                    Text("오늘의 섹터 흐름")
                        .font(.headline.weight(.black))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(summary)
                        .font(.system(size: 15, weight: .bold))
                        .lineSpacing(4)
                        .foregroundStyle(AppTheme.primaryText)
                }

                Spacer()

                Text("\(count)개")
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(AppTheme.accent)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(AppTheme.accent.opacity(0.10), in: Capsule())
            }

            DataFreshnessBadge(source: source, updatedAt: updatedAt, compact: true)

            Text("상세에서는 구성 기업을 시가총액 순으로 보고, 주도·압박 기업은 당일 변동률로 따로 확인합니다.")
                .font(.system(size: 13))
                .lineSpacing(4)
                .foregroundStyle(AppTheme.secondaryText)
        }
        .appCard(padding: 14)
    }
}

private struct SectionTitleRow: View {
    let title: String
    let trailing: String

    var body: some View {
        HStack {
            Text(title)
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Spacer()
            Text(trailing)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.secondaryText)
                .monospacedDigit()
        }
        .padding(.top, 4)
    }
}

private struct SectorThemeGridCard: View {
    let theme: SectorTheme
    let open: () -> Void

    private var tone: Color {
        portfolioReturnColor(theme.avgChangePct)
    }

    private var displayLabel: String {
        sectorThemeDisplayLabel(theme.label)
    }

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(tone.opacity(0.11))
                    LucideIconView(icon: sectorThemeIcon(theme.label), size: 19)
                        .foregroundStyle(tone)
                }
                .frame(width: 40, height: 40)

                Spacer(minLength: 0)

                Text(displayLabel)
                    .font(.system(size: 16, weight: .black))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)

                Text(pct(theme.avgChangePct))
                    .font(.system(size: 20, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(tone)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
            .appCard(padding: 12)
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
    }
}

private struct SectorTinyStat: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 3) {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.system(size: 11, weight: .black, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(AppTheme.primaryText)
        }
        .lineLimit(1)
    }
}

private struct SectorThemeTopCard: View {
    let theme: SectorTheme
    let open: () -> Void

    private var tone: Color {
        portfolioReturnColor(theme.avgChangePct)
    }

    private var displayLabel: String {
        sectorThemeDisplayLabel(theme.label)
    }

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(tone.opacity(0.11))
                        LucideIconView(icon: sectorThemeIcon(theme.label), size: 21)
                            .foregroundStyle(tone)
                    }
                    .frame(width: 44, height: 44)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(displayLabel)
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                        Text(sectorThemeDirectionLabel(theme))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(tone)
                    }

                    Spacer(minLength: 8)

                    VStack(alignment: .trailing, spacing: 3) {
                        Text(pct(theme.avgChangePct))
                            .font(.system(size: 22, weight: .black, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(tone)
                        Text("시총가중")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.tertiaryText)
                    }
                }

                HStack(spacing: 8) {
                    SectorMetricPill(title: "상승", value: "\(theme.risingCount)")
                    SectorMetricPill(title: "하락", value: "\(theme.fallingCount)")
                    SectorMetricPill(title: "1개월", value: pct(theme.avgReturn1M))
                }

                if let leader = theme.leader {
                    SectorLeaderStrip(member: leader, label: "주도")
                }

                SectorThemeDecisionNote(theme: theme, tone: tone)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .appCard(padding: 14)
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
    }
}

private struct SectorThemeDecisionNote: View {
    let theme: SectorTheme
    let tone: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 8) {
                LucideIconView(icon: .lightbulb, size: 15)
                    .foregroundStyle(tone)
                    .padding(.top, 1)
                Text(sectorThemeDecisionHeadline(theme))
                    .font(.system(size: 14, weight: .bold))
                    .lineSpacing(3)
                    .foregroundStyle(AppTheme.primaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(alignment: .top, spacing: 8) {
                Text("다음")
                    .font(.system(size: 11, weight: .black))
                    .foregroundStyle(tone)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(tone.opacity(0.10), in: Capsule())
                Text(sectorThemeNextAction(theme))
                    .font(.system(size: 13, weight: .semibold))
                    .lineSpacing(3)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(11)
        .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AppTheme.hairline.opacity(0.28), lineWidth: 0.7)
        }
    }
}

private struct SectorThemeListRow: View {
    let theme: SectorTheme
    let open: () -> Void

    private var tone: Color {
        portfolioReturnColor(theme.avgChangePct)
    }

    private var displayLabel: String {
        sectorThemeDisplayLabel(theme.label)
    }

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(tone.opacity(0.10))
                    LucideIconView(icon: sectorThemeIcon(theme.label), size: 18)
                        .foregroundStyle(tone)
                }
                .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 5) {
                    Text(displayLabel)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    HStack(spacing: 7) {
                        SectorMemberLogoStack(members: Array(theme.members.prefix(3)))
                        Text("\(theme.memberCount)개 · 상승 \(theme.risingCount) · 하락 \(theme.fallingCount)")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 8)

                VStack(alignment: .trailing, spacing: 3) {
                    Text(pct(theme.avgChangePct))
                        .font(.system(size: 16, weight: .black, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(tone)
                    Text(sectorThemeDirectionLabel(theme))
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(AppTheme.tertiaryText)
                        .lineLimit(1)
                }
            }
            .padding(14)
            .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(AppTheme.hairline.opacity(0.36), lineWidth: 0.7)
            }
            .shadow(color: AppTheme.softShadow, radius: 12, y: 3)
        }
        .buttonStyle(QuantPressButtonStyle(role: .row))
    }
}

private struct SectorMemberLogoStack: View {
    let members: [SectorThemeMember]

    var body: some View {
        HStack(spacing: -7) {
            ForEach(members) { member in
                CompanyLogoView(ticker: member.ticker, currency: member.resolvedCurrency, size: 22)
                    .overlay(Circle().stroke(AppTheme.card, lineWidth: 1.5))
            }
        }
        .frame(width: members.isEmpty ? 0 : CGFloat(22 + max(0, members.count - 1) * 15), alignment: .leading)
    }
}

private struct SectorMetricPill: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.system(size: 13, weight: .black, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(AppTheme.primaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(AppTheme.elevatedCard.opacity(0.68), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(AppTheme.hairline.opacity(0.28), lineWidth: 0.6)
        }
    }
}

private struct SectorLeaderStrip: View {
    let member: SectorThemeMember
    var label = "주도"

    private var tone: Color {
        portfolioReturnColor(member.dailyChangePct)
    }

    var body: some View {
        HStack(spacing: 9) {
            ZStack {
                Circle()
                    .fill(tone.opacity(0.10))
                LucideIconView(icon: (member.dailyChangePct ?? 0) >= 0 ? .trendingUp : .trendingDown, size: 14)
                    .foregroundStyle(tone)
            }
            .frame(width: 28, height: 28)

            Text(label)
                .font(.system(size: 12, weight: .black))
                .foregroundStyle(tone)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(tone.opacity(0.10), in: Capsule())
            Text(member.displayName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
            Spacer(minLength: 6)
            Text(pct(member.dailyChangePct))
                .font(.caption.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(tone)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(tone.opacity(0.16), lineWidth: 0.8)
        }
    }
}

private struct SectorThemeMemberRow: View {
    let member: SectorThemeMember

    var body: some View {
        HStack(spacing: 10) {
            CompanyLogoView(ticker: member.ticker, currency: member.resolvedCurrency, size: 36)
            VStack(alignment: .leading, spacing: 3) {
                Text(member.displayName)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                Text(member.ticker)
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 3) {
                AnimatedPriceText(
                    text: portfolioPriceText(member.currentPrice, currency: member.resolvedCurrency),
                    font: .subheadline.monospacedDigit().weight(.bold),
                    color: AppTheme.primaryText
                )
                .lineLimit(1)
                Text(pct(member.dailyChangePct))
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(portfolioReturnColor(member.dailyChangePct))
            }
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 2)
        .contentShape(Rectangle())
    }
}

private struct SectorThemeDetailSheet: View {
    let theme: SectorTheme
    @Environment(\.dismiss) private var dismiss
    @State private var selectedMember: SectorThemeMember?
    @State private var loadedTheme: SectorTheme?
    @State private var loadError: String?
    @State private var loadingDetail = false

    private var activeTheme: SectorTheme {
        loadedTheme ?? theme
    }

    private var topGainers: [SectorThemeMember] {
        activeTheme.members
            .filter { ($0.dailyChangePct ?? 0) > 0 }
            .sorted { ($0.dailyChangePct ?? -.infinity) > ($1.dailyChangePct ?? -.infinity) }
    }

    private var topLosers: [SectorThemeMember] {
        activeTheme.members
            .filter { ($0.dailyChangePct ?? 0) < 0 }
            .sorted { ($0.dailyChangePct ?? .infinity) < ($1.dailyChangePct ?? .infinity) }
    }

    private var canShowMemberSections: Bool {
        loadedTheme != nil || !loadingDetail
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    detailHeader

                    HStack(spacing: 8) {
                        SectorMetricPill(title: "상승", value: "\(activeTheme.risingCount)")
                        SectorMetricPill(title: "하락", value: "\(activeTheme.fallingCount)")
                        SectorMetricPill(title: "1개월", value: pct(activeTheme.avgReturn1M))
                    }

                    if loadingDetail && loadedTheme == nil {
                        LoadingStateView(title: "구성 기업 불러오는 중", detail: "목록은 가볍게 열고, 상세 기업은 지금 가져오고 있습니다.")
                            .frame(maxWidth: .infinity)
                            .appCard(padding: 16)
                    } else if let loadError {
                        InlineWarningBanner(msg: loadError) {
                            Task { await loadDetail(refresh: true) }
                        }
                        .appCard(padding: 12)
                    }

                    if canShowMemberSections && (!topGainers.isEmpty || !topLosers.isEmpty) {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitleRow(title: "주도 / 압박 기업", trailing: "")
                            if let gain = topGainers.first {
                                Button {
                                    selectedMember = gain
                                } label: {
                                    SectorLeaderStrip(member: gain, label: "상승 주도")
                                }
                                .buttonStyle(QuantPressButtonStyle(role: .row))
                            }
                            if let loss = topLosers.first {
                                Button {
                                    selectedMember = loss
                                } label: {
                                    SectorLeaderStrip(member: loss, label: "하락 압박")
                                }
                                .buttonStyle(QuantPressButtonStyle(role: .row))
                            }
                        }
                    }

                    if canShowMemberSections {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitleRow(title: "구성 기업", trailing: "\(activeTheme.members.count)개")
                            VStack(spacing: 0) {
                                ForEach(activeTheme.members) { member in
                                    Button {
                                        selectedMember = member
                                    } label: {
                                        SectorThemeMemberRow(member: member)
                                    }
                                    .buttonStyle(QuantPressButtonStyle(role: .row))

                                    if member.id != activeTheme.members.last?.id {
                                        Divider()
                                            .padding(.leading, 48)
                                    }
                                }
                            }
                            .appCard(padding: 12)
                        }
                    }
                }
                .padding()
            }
            .appScreenBackground()
            .navigationTitle(sectorThemeDisplayLabel(activeTheme.label))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                }
            }
            .task(id: theme.id) {
                await loadDetail(refresh: true)
            }
            .fullScreenCover(item: $selectedMember) { member in
                StockDetailSheet(
                    ticker: member.ticker,
                    name: member.displayName,
                    currency: member.resolvedCurrency,
                    staticMetrics: sectorMemberMetrics(member),
                    investmentSignals: sectorMemberSignals(member)
                )
            }
        }
    }

    @MainActor
    private func loadDetail(refresh: Bool) async {
        if loadingDetail { return }
        loadingDetail = true
        loadError = nil
        var hasVisibleData = !activeTheme.members.isEmpty
        do {
            if !refresh, let cached = await SectorThemeSnapshotStore.shared.readDetail(label: theme.label, market: theme.market) {
                loadedTheme = cached.item
                hasVisibleData = true
                loadingDetail = false
            }
            let response = try await APIClient.shared.fetchSectorThemeDetail(
                label: theme.label,
                market: theme.market,
                refresh: refresh
            )
            loadedTheme = response.item
            await SectorThemeSnapshotStore.shared.writeDetail(response, label: theme.label, market: theme.market)
        } catch {
            if !hasVisibleData {
                loadError = "구성 기업을 불러오지 못했습니다.\n\(error.localizedDescription)"
            }
        }
        loadingDetail = false
    }

    private var detailHeader: some View {
        let theme = activeTheme
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(portfolioReturnColor(theme.avgChangePct).opacity(0.11))
                    LucideIconView(icon: sectorThemeIcon(theme.label), size: 22)
                        .foregroundStyle(portfolioReturnColor(theme.avgChangePct))
                }
                .frame(width: 46, height: 46)

                VStack(alignment: .leading, spacing: 5) {
                    Text(sectorThemeDisplayLabel(theme.label))
                        .font(.title2.weight(.black))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(sectorThemeDirectionLabel(theme))
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(portfolioReturnColor(theme.avgChangePct))
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 3) {
                    Text(pct(theme.avgChangePct))
                        .font(.system(size: 24, weight: .black, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(portfolioReturnColor(theme.avgChangePct))
                    Text("시총가중")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.tertiaryText)
                }
            }

            Text(sectorThemeReason(theme))
                .font(.system(size: 14))
                .lineSpacing(4)
                .foregroundStyle(AppTheme.secondaryText)

            HStack(spacing: 8) {
                SectorMemberLogoStack(members: Array(theme.members.prefix(4)))
                Text("\(theme.memberCount)개 기업 · 가격 확인 \(theme.pricedCount)개")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.secondaryText)
            }
        }
        .appCard(padding: 14)
    }
}

private func sectorThemeMatches(_ theme: SectorTheme, query: String) -> Bool {
    let clean = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !clean.isEmpty else { return true }
    if theme.label.lowercased().contains(clean) { return true }
    return theme.members.contains { member in
        member.ticker.lowercased().contains(clean)
            || member.displayName.lowercased().contains(clean)
            || (member.sector ?? "").lowercased().contains(clean)
    }
}

private func sectorThemeSummary(_ themes: [SectorTheme]) -> String {
    guard let first = themes.first else {
        return "오늘 움직인 투자 테마를 시장별로 확인하세요."
    }
    let firstLabel = sectorThemeDisplayLabel(first.label)
    let second = themes.dropFirst().first
    if let second {
        return "\(firstLabel)와 \(sectorThemeDisplayLabel(second.label))이 오늘 테마 흐름의 중심입니다."
    }
    return "\(firstLabel)이 오늘 가장 먼저 확인할 테마입니다."
}

private func sectorThemeDirectionLabel(_ theme: SectorTheme) -> String {
    guard let value = theme.avgChangePct else { return "데이터 확인 중" }
    if abs(value) < 0.003 { return "혼조" }
    return value >= 0 ? "상승 주도" : "하락 압력"
}

private func sectorThemeDecisionHeadline(_ theme: SectorTheme) -> String {
    guard theme.avgChangePct != nil else {
        return "\(sectorThemeDisplayLabel(theme.label)) 가격 데이터를 확인하는 중입니다."
    }
    let leader = theme.leader?.displayName ?? sectorThemeDisplayLabel(theme.label)
    return "\(pct(theme.avgChangePct)) · \(leader) 중심으로 움직임이 커졌습니다."
}

private func sectorThemeNextAction(_ theme: SectorTheme) -> String {
    let spread = "상승 \(theme.risingCount) / 하락 \(theme.fallingCount)"
    if theme.avgReturn1M != nil {
        return "\(spread), 1개월 \(pct(theme.avgReturn1M)) 흐름을 함께 비교하세요."
    }
    return "\(spread) 분포를 보고 변동 큰 기업부터 확인하세요."
}

private func sectorThemeReason(_ theme: SectorTheme) -> String {
    let leaderText = theme.leader.map { "\($0.displayName) \(pct($0.dailyChangePct))" } ?? "주도 기업 확인 중"
    let direction = sectorThemeDirectionLabel(theme)
    return "\(leaderText)이 \(sectorThemeDisplayLabel(theme.label)) 흐름을 이끌고 있습니다. \(direction)인지, 상승 \(theme.risingCount)개와 하락 \(theme.fallingCount)개의 폭이 넓어지는지 확인하세요."
}

private func sectorThemeIcon(_ label: String) -> LucideIcon {
    switch sectorThemeDisplayLabel(label) {
    case "AI 칩/GPU": return .cpu
    case "AI 서버/네트워크": return .network
    case "AI 데이터센터/클라우드": return .server
    case "AI 소프트웨어": return .bot
    case "AI 전력/냉각": return .airVent
    case "SMR": return .rocket
    case "원자력": return .radio
    case "HBM": return .audioWaveform
    case "메모리/낸드": return .hardDrive
    case "파운드리": return .factory
    case "반도체 설계": return .circuitBoard
    case "CPU/엣지칩": return .microchip
    case "반도체 소재": return .gem
    case "전자/부품", "전기·전자": return .cable
    case "반도체 장비": return .monitorCog
    case "반도체 후공정/테스트": return .workflow
    case "클라우드/SW": return .cloud
    case "IT 서비스": return .lineChart
    case "사이버보안": return .shieldCheck
    case "보안/서비스": return .eye
    case "핀테크/결제": return .creditCard
    case "은행": return .landmark
    case "증권/자산운용": return .barChart3
    case "보험": return .badgeDollarSign
    case "전기차": return .zap
    case "자동차": return .car
    case "자동차 부품": return .gitCompare
    case "배터리": return .battery
    case "배터리 소재": return .beaker
    case "조선": return .ship
    case "방산/항공": return .plane
    case "기계/로봇": return .hammer
    case "헬스케어": return .heartPulse
    case "바이오/제약": return .pill
    case "의료기기": return .stethoscope
    case "헬스케어 서비스": return .hospital
    case "에너지": return .fuel
    case "정유/화학": return .flaskConical
    case "전력/유틸리티": return .zap
    case "클린에너지": return .leaf
    case "소비/리테일": return .shoppingBag
    case "이커머스": return .shoppingCart
    case "음식료/필수소비": return .utensils
    case "화장품/뷰티": return .palette
    case "미디어/엔터": return .clapperboard
    case "게임": return .gamepad2
    case "여행/레저": return .hotel
    case "통신": return .radioTower
    case "리츠/부동산", "부동산": return .building
    case "건설/인프라": return .warehouse
    case "소재/철강", "소재": return .pickaxe
    case "기술": return .circuitBoard
    case "유틸리티": return .zap
    case "금속": return .gem
    case "비금속": return .circleArrowDown
    case "운송/물류": return .truck
    default:
        return .layoutDashboard
    }
}

private func sectorThemeDisplayLabel(_ label: String) -> String {
    let clean = label.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return "기타" }
    if clean.unicodeScalars.contains(where: { (0xAC00...0xD7A3).contains(Int($0.value)) }) {
        return clean
    }
    return portfolioIndustryLabel(ticker: "", name: clean, sector: clean)
}

private func sectorMemberMetrics(_ member: SectorThemeMember) -> [StaticMetric] {
    [
        StaticMetric(label: "현재가", value: portfolioPriceText(member.currentPrice, currency: member.resolvedCurrency)),
        StaticMetric(
            label: member.dailyChangeLabel,
            value: pct(member.dailyChangePct),
            color: (member.dailyChangePct ?? 0) >= 0 ? .red : .blue
        ),
        StaticMetric(
            label: "1개월",
            value: pct(member.return1M),
            color: (member.return1M ?? 0) >= 0 ? .red : .blue
        ),
        StaticMetric(label: "점수", value: score(member.scoreValue)),
        StaticMetric(label: "시가총액", value: cap(member.marketCap, currency: member.resolvedCurrency))
    ]
}

private func sectorMemberSignals(_ member: SectorThemeMember) -> [InvestmentSignal] {
    let sourceText: String
    if member.inPortfolio {
        sourceText = "분석 상위 후보에 포함된 기업입니다."
    } else if member.inSmallCap {
        sourceText = "스몰캡 후보군에서 함께 추적되는 기업입니다."
    } else {
        sourceText = "섹터 테마 내 비교군으로 포함된 기업입니다."
    }

    return [
        InvestmentSignal(
            title: "섹터 내 위치",
            detail: sourceText,
            systemImage: "square.grid.2x2",
            color: AppTheme.accent
        ),
        InvestmentSignal(
            title: "확인할 숫자",
            detail: "상세 시세 기준 당일 흐름과 1개월 흐름을 같은 테마의 다른 기업과 비교하세요.",
            systemImage: "chart.line.uptrend.xyaxis",
            color: AppTheme.accent
        )
    ]
}

private extension APIClientProtocol {
    func fetchSectorThemes(
        market: String = "ALL",
        limit: Int = 36,
        members: Int = 12,
        refresh: Bool = false
    ) async throws -> SectorThemesResponse {
        try await fetch(
            ["sectors", "themes"],
            queryItems: [
                URLQueryItem(name: "market", value: market),
                URLQueryItem(name: "limit", value: "\(limit)"),
                URLQueryItem(name: "members", value: "\(members)"),
                URLQueryItem(name: "schema", value: "sector-ai-audit-v1"),
                URLQueryItem(name: "refresh", value: refresh ? "true" : "false")
            ]
        )
    }

    func fetchSectorThemeDetail(
        label: String,
        market: String = "ALL",
        members: Int = 80,
        refresh: Bool = false
    ) async throws -> SectorThemeDetailResponse {
        try await fetch(
            ["sectors", "themes", "detail"],
            queryItems: [
                URLQueryItem(name: "market", value: market),
                URLQueryItem(name: "label", value: label),
                URLQueryItem(name: "members", value: "\(members)"),
                URLQueryItem(name: "schema", value: "sector-detail-v1"),
                URLQueryItem(name: "refresh", value: refresh ? "true" : "false")
            ]
        )
    }
}

private func cacheSegment(_ raw: String) -> String {
    let allowed = CharacterSet.alphanumerics
        .union(CharacterSet(charactersIn: "._-"))
        .union(CharacterSet(charactersIn: "가나다라마바사아자차카타파하거너더러머버서어저처커터퍼허고노도로모보소오조초코토포호구누두루무부수우주추쿠투푸후그느드르므브스으즈츠크트프흐기니디리미비시이지치키티피히"))
    let cleaned = raw
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
        .unicodeScalars
        .map { allowed.contains($0) ? Character($0) : "_" }
    let value = String(cleaned).trimmingCharacters(in: CharacterSet(charactersIn: "_"))
    return String((value.isEmpty ? "all" : value).prefix(80))
}
