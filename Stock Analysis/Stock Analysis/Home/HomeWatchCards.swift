import Combine
import Foundation
import SwiftUI

internal struct HomeWatchDisplay {
    let name: String
    let sector: String
    let priceText: String
    let changeText: String
    let changeValue: Double?
    let currency: String
    let isIndicator: Bool
}
internal func homeWatchDisplay(
    signal: HomePersonalSignal,
    portfolioStocks: [PortfolioStock],
    smallCapStocks: [SmallCapStock],
    watchItems: [WatchlistItem],
    priceMetrics: [String: HomeStockPriceMetric]
) -> HomeWatchDisplay {
    let keys = homeWatchMatchKeys(signal.ticker)
    let watch = watchItems.first { homeWatchTickerMatches($0.ticker, keys: keys) }
    let portfolio = portfolioStocks.first { homeWatchTickerMatches($0.ticker, keys: keys) }
    let small = smallCapStocks.first { homeWatchTickerMatches($0.ticker, keys: keys) }
    let priceMetric = [signal.ticker, watch?.ticker, portfolio?.ticker, small?.ticker]
        .compactMap { $0 }
        .compactMap { homeWatchPriceMetric($0, priceMetrics: priceMetrics) }
        .first
    let currency = portfolio.map { marketCurrency(for: $0.ticker, market: $0.market) }
        ?? small.map { marketCurrency(for: $0.ticker, market: $0.market) }
        ?? watch?.currency
        ?? marketCurrency(for: signal.ticker, market: watch?.market)
    let price = priceMetric?.currentPrice ?? portfolio?.currentPrice ?? small?.currentPrice
    let indicatorChange = signal.metrics.compactMap(homeWatchPercentValue).first
    let change = signal.category == .indicator ? indicatorChange : priceMetric?.dailyChangePct
    let rawName = portfolio?.name ?? small?.name ?? watch?.name ?? (signal.name.isEmpty ? signal.ticker : signal.name)
    let displayName = localizedCompanyName(ticker: signal.ticker, currentName: rawName, market: watch?.market)
    let rawSector = portfolio?.sector?.nilIfBlank
        ?? small.map { homeSubtitle(market: $0.market, text: "스몰캡") }
        ?? watch?.primaryTag.nilIfBlank
        ?? signal.title
    let sector = portfolio.map { portfolioIndustryLabel(ticker: $0.ticker, name: $0.name, sector: $0.sector) }
        ?? rawSector
    let priceText = price.flatMap { $0.isFinite ? fmtPx($0, currency: currency) : nil }
        ?? signal.metrics.first(where: { !$0.isEmpty })
        ?? "-"
    let changeText = change.flatMap { $0.isFinite ? pct($0) : nil }
        ?? (signal.category == .indicator ? signal.metrics.first(where: { $0.contains("%") }) : nil)
        ?? "-"

    return HomeWatchDisplay(
        name: displayName,
        sector: sector,
        priceText: priceText,
        changeText: changeText,
        changeValue: change?.isFinite == true ? change : nil,
        currency: currency,
        isIndicator: signal.category == .indicator
    )
}

internal func homeWatchMatchKeys(_ ticker: String) -> Set<String> {
    let normalized = normalizedTicker(ticker)
    guard !normalized.isEmpty else { return [] }
    var keys: Set<String> = [normalized]
    let code = krCode(from: normalized)
    if !code.isEmpty {
        keys.formUnion([code, "\(code).KS", "\(code).KQ"])
    }
    return keys
}

internal func homeWatchTickerMatches(_ ticker: String, keys: Set<String>) -> Bool {
    !homeWatchMatchKeys(ticker).isDisjoint(with: keys)
}

internal func homeWatchPercentValue(_ text: String) -> Double? {
    guard text.contains("%") else { return nil }
    let cleaned = text
        .replacingOccurrences(of: "%", with: "")
        .replacingOccurrences(of: "+", with: "")
        .replacingOccurrences(of: ",", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard let value = Double(cleaned), value.isFinite else { return nil }
    return value / 100
}

internal func homeWatchPriceMarket(_ item: WatchlistItem) -> String {
    let market = item.market.uppercased()
    if market == "KR" || item.currency == "KRW" {
        return "KR"
    }
    return "US"
}

internal func homeWatchPriceLookupTickers(_ ticker: String, market: String) -> [String] {
    let normalized = normalizedTicker(ticker)
    let code = krCode(from: normalized)
    if market.uppercased() == "KR", !code.isEmpty {
        return ["\(code).KS", "\(code).KQ", code]
    }
    return normalized.isEmpty ? [] : [normalized]
}

internal func homeWatchPriceMetric(_ ticker: String, priceMetrics: [String: HomeStockPriceMetric]) -> HomeStockPriceMetric? {
    homeWatchMatchKeys(ticker).compactMap { priceMetrics[$0] }.first
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
internal struct HomeTemplateWatchCard: View {
    let signal: HomePersonalSignal
    let display: HomeWatchDisplay
    let open: () -> Void

    private var changeValue: Double { display.changeValue ?? 0.0 }

    private var changeColor: Color {
        if changeValue < 0 { return AppTheme.negative }
        if changeValue > 0 { return AppTheme.positive }
        return signal.color
    }

    var body: some View {
        Button(action: {
            withAnimation(.easeInOut(duration: 0.22)) {
                open()
            }
        }) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    if display.isIndicator {
                        MarketIndicatorLogoView(
                            ticker: signal.ticker,
                            name: display.name.isEmpty ? signal.name : display.name,
                            size: 52,
                            accent: signal.color
                        )
                    } else {
                        CompanyLogoView(ticker: signal.ticker, currency: display.currency, size: 52)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(display.name.isEmpty ? signal.ticker : display.name)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                        Text(display.sector)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 16)

                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 3) {
                        AnimatedPriceText(
                            text: display.priceText,
                            font: .system(size: 21, weight: .black).monospacedDigit(),
                            color: AppTheme.primaryText
                        )
                            .lineLimit(1)
                        Text(display.changeText)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(changeColor)
                            .lineLimit(1)
                    }
                    Spacer()
                    HomeMiniSparkline(changeValue: changeValue, color: changeColor)
                        .frame(width: 62, height: 34)
                }
            }
            .frame(width: 196, height: 106, alignment: .leading)
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(AppTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .stroke(AppTheme.hairline.opacity(0.35), lineWidth: 0.7)
                    )
            )
            .shadow(color: AppTheme.softShadow, radius: 14, y: 4)
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
    }
}

internal struct HomeMiniSparkline: View {
    let changeValue: Double
    let color: Color

    private var points: [(CGFloat, CGFloat)] {
        if changeValue < 0 {
            return [(0.00, 0.22), (0.18, 0.38), (0.36, 0.31), (0.56, 0.58), (0.76, 0.50), (1.00, 0.78)]
        }
        return [(0.00, 0.74), (0.18, 0.58), (0.36, 0.64), (0.56, 0.36), (0.76, 0.43), (1.00, 0.20)]
    }

    var body: some View {
        ZStack {
            Capsule()
                .fill(AppTheme.elevatedCard)
            GeometryReader { proxy in
                Path { path in
                    for (index, point) in points.enumerated() {
                        let x = point.0 * proxy.size.width
                        let y = point.1 * proxy.size.height
                        if index == 0 {
                            path.move(to: CGPoint(x: x, y: y))
                        } else {
                            path.addLine(to: CGPoint(x: x, y: y))
                        }
                    }
                }
                .stroke(color, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 7)
        }
    }
}
internal struct PersonalizedWatchBriefingCard: View {
    let signals: [HomePersonalSignal]
    let watchCount: Int
    let companyCount: Int
    let indicatorCount: Int
    let signalCount: Int
    let earningsCount: Int
    let isLoading: Bool
    let notificationTitle: String
    let notificationDetail: String
    let notificationsEnabled: Bool
    let canRequestNotifications: Bool
    let openWatch: () -> Void
    let openSignal: (HomePersonalSignal) -> Void
    let refresh: () -> Void
    let enableNotifications: () -> Void
    let disableNotifications: () -> Void

    @State private var pageIndex = 0
    private let autoAdvance = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private var signalPages: [[HomePersonalSignal]] {
        guard !signals.isEmpty else { return [] }
        return stride(from: 0, to: signals.count, by: 3).map { index in
            Array(signals[index..<min(index + 3, signals.count)])
        }
    }

    private var carouselPages: [[HomePersonalSignal]] {
        guard signalPages.count > 1, let first = signalPages.first else { return signalPages }
        return signalPages + [first]
    }

    private var activePageIndex: Int {
        guard !signalPages.isEmpty else { return 0 }
        return pageIndex % signalPages.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("관심종목 브리핑")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(headerDetail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Button(action: refresh) {
                    LucideIconView(icon: .refreshCw, size: 16)
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(AppTheme.accent.opacity(0.10), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("관심 브리핑 새로고침")
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                PersonalBriefMetric(label: "관심", value: "\(watchCount)", color: AppTheme.accent)
                PersonalBriefMetric(label: "변화", value: "\(signalCount)", color: signalCount > 0 ? AppTheme.warning : AppTheme.quality)
            }

            if watchCount == 0 {
                PersonalBriefEmptyState(
                    title: "관심 항목을 추가하세요",
                    detail: "기업, 주요 지수, ETF를 하트로 추가하면 홈이 개인 브리핑으로 바뀝니다."
                )
            } else if signals.isEmpty {
                if isLoading {
                    SkeletonLoadingCard(titleWidth: 136, lineCount: 2)
                } else {
                    PersonalBriefEmptyState(
                        title: "큰 변화 없음",
                        detail: "관심 항목 \(watchCount)개를 감시 중입니다."
                    )
                }
            } else if !signalPages.isEmpty {
                TabView(selection: $pageIndex) {
                    ForEach(Array(carouselPages.enumerated()), id: \.offset) { index, page in
                        VStack(spacing: 8) {
                            ForEach(page) { signal in
                                PersonalBriefSignalRow(signal: signal, open: signalOpenAction(signal))
                            }
                            if page.count < 3 {
                                ForEach(0..<(3 - page.count), id: \.self) { _ in
                                    Color.clear.frame(height: 68)
                                }
                            }
                        }
                        .tag(index)
                        .padding(.vertical, 1)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(height: 236)
                .onReceive(autoAdvance) { _ in
                    guard signalPages.count > 1 else { return }
                    withAnimation(.easeInOut(duration: 0.32)) {
                        pageIndex = min(pageIndex + 1, signalPages.count)
                    }
                }
                .onChange(of: signalPages.count) { _, count in
                    if count <= 1 || pageIndex >= count {
                        pageIndex = 0
                    }
                }
                .onChange(of: pageIndex) { _, value in
                    guard signalPages.count > 1, value == signalPages.count else { return }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.34) {
                        var transaction = Transaction()
                        transaction.disablesAnimations = true
                        withTransaction(transaction) {
                            pageIndex = 0
                        }
                    }
                }

                if signalPages.count > 1 {
                    HStack(spacing: 5) {
                        ForEach(0..<signalPages.count, id: \.self) { index in
                            Capsule()
                                .fill(index == activePageIndex ? AppTheme.accent : AppTheme.hairline)
                                .frame(width: index == activePageIndex ? 14 : 5, height: 5)
                                .animation(.easeInOut(duration: 0.2), value: pageIndex)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }

            NotificationInlineControl(
                title: notificationTitle,
                detail: notificationDetail,
                isEnabled: notificationsEnabled,
                canRequest: canRequestNotifications,
                enable: enableNotifications,
                disable: disableNotifications
            )
        }
        .appCard(padding: 14)
    }

    private var headerDetail: String {
        if watchCount == 0 {
            return "관심종목을 추가하면 실적, 후보 신호, 지수 변화를 여기서 먼저 보여줍니다."
        }
        if signalCount > 0 {
            return "관심 기업 \(companyCount)개 중 확인할 변화 \(signalCount)개"
        }
        return "관심 기업 \(companyCount)개와 지수 \(indicatorCount)개 감시 중"
    }

    private func signalOpenAction(_ signal: HomePersonalSignal) -> () -> Void {
        {
            openSignal(signal)
        }
    }
}

internal struct PersonalBriefMetric: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(color)
                .monospacedDigit()
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

internal struct PersonalBriefPriorityRow: View {
    let signal: HomePersonalSignal
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(alignment: .center, spacing: 10) {
                if signal.category == .indicator {
                    MarketIndicatorLogoView(ticker: signal.ticker, name: signal.name, size: 34, accent: signal.color)
                } else {
                    CompanyLogoView(ticker: signal.ticker, currency: marketCurrency(for: signal.ticker), size: 34)
                }
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(signal.title)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(signal.color)
                            .lineLimit(1)
                        Text(signal.name)
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                    }
                    Text(signal.detail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                if let metric = signal.metrics.first {
                    Text(metric)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(signal.color)
                        .lineLimit(1)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(signal.color.opacity(0.10), in: Capsule())
                }
            }
            .padding(10)
            .background(signal.color.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(QuantPressButtonStyle(role: .row))
    }
}

internal struct PersonalBriefSignalRow: View {
    let signal: HomePersonalSignal
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(alignment: .top, spacing: 10) {
                if signal.category == .indicator {
                    MarketIndicatorLogoView(ticker: signal.ticker, name: signal.name, size: 30, accent: signal.color)
                } else {
                    LucideIconView(icon: lucideIcon(forSystemSymbol: signal.symbol), size: 14)
                        .foregroundStyle(signal.color)
                        .frame(width: 28, height: 28)
                        .background(signal.color.opacity(0.10), in: Circle())
                }

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(signal.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(signal.color)
                            .lineLimit(1)
                        Text(signal.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        if let updatedAt = signal.updatedAt {
                            Text(formattedUpdateTimestamp(updatedAt))
                                .font(.system(size: 12))
                                .foregroundStyle(AppTheme.tertiaryText)
                                .lineLimit(1)
                        }
                    }
                    Text(signal.detail)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    if !signal.metrics.isEmpty {
                        HStack(spacing: 6) {
                            ForEach(signal.metrics, id: \.self) { metric in
                                Text(metric)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(AppTheme.secondaryText)
                                    .lineLimit(1)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 4)
                                    .background(AppTheme.elevatedCard, in: Capsule())
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(QuantPressButtonStyle(role: .row))
    }
}

internal struct PersonalBriefEmptyState: View {
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: "heart")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AppTheme.accent)
                .frame(width: 28, height: 28)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
    }
}

internal struct NotificationInlineControl: View {
    let title: String
    let detail: String
    let isEnabled: Bool
    let canRequest: Bool
    let enable: () -> Void
    let disable: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: isEnabled ? "bell.badge.fill" : "bell")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(isEnabled ? AppTheme.warning : AppTheme.secondaryText)
                .frame(width: 28, height: 28)
                .background((isEnabled ? AppTheme.warning : AppTheme.secondaryText).opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            Button(isEnabled ? "끄기" : "켜기") {
                isEnabled ? disable() : enable()
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(.bordered)
            .disabled(!isEnabled && !canRequest)
        }
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
    }
}
