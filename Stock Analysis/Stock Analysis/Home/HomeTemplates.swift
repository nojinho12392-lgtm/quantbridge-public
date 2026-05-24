import Foundation
import SwiftUI

internal struct HomeTemplateIntro: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("DAILY BRIEF")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AppTheme.accent)
                .padding(.horizontal, 13)
                .padding(.vertical, 6)
                .background(AppTheme.accent.opacity(0.16), in: Capsule())

            Text("오늘의 요약")
                .font(.system(size: 32, weight: .black))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 8)
    }
}

internal struct HomePersonalLensCard: View {
    let profile: InvestmentProfile
    let openProfile: () -> Void

    var body: some View {
        Button(action: openProfile) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 10) {
                    LucideIconView(icon: profile.isConfigured ? .target : .slidersHorizontal, size: 18)
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(AppTheme.accent.opacity(0.10), in: Circle())

                    VStack(alignment: .leading, spacing: 4) {
                        Text(profile.isConfigured ? "내 기준 브리핑" : "내 기준을 먼저 설정해보세요")
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                        Text(profile.isConfigured ? personalLensDetail(profile) : "투자 성향을 저장하면 홈 후보를 내 기준으로 다시 읽을 수 있습니다.")
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    Text(profile.isConfigured ? "수정" : "진단")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.accent)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(AppTheme.accent.opacity(0.10), in: Capsule())
                }

                if profile.isConfigured {
                    Text(profile.operatingStatement)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(spacing: 8) {
                        HomeLensPill(title: "중점", value: personalLensFocus(profile), icon: .target)
                        HomeLensPill(title: "주의", value: profile.guardrailSummary, icon: .shieldCheck)
                    }
                    HStack(spacing: 8) {
                        HomeLensPill(title: "완성도", value: "\(profile.completionPercent)%", icon: .check)
                        HomeLensPill(title: "리마인드", value: profile.nextReviewText, icon: .calendarClock)
                    }
                }
            }
            .appCard()
        }
        .buttonStyle(.plain)
    }
}

private struct HomeLensPill: View {
    let title: String
    let value: String
    let icon: LucideIcon

    var body: some View {
        HStack(spacing: 7) {
            LucideIconView(icon: icon, size: 13)
                .foregroundStyle(AppTheme.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
                Text(value)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(AppTheme.elevatedCard.opacity(0.74), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private func personalLensFocus(_ profile: InvestmentProfile) -> String {
    if !profile.style.isEmpty { return profile.style }
    if !profile.riskTolerance.isEmpty { return profile.riskTolerance }
    if !profile.horizon.isEmpty { return profile.horizon }
    return "맞춤 기준"
}

private func personalLensDetail(_ profile: InvestmentProfile) -> String {
    let focus = personalLensFocus(profile)
    let horizon = profile.horizon.isEmpty ? "관찰 기간" : profile.horizon
    return "\(focus) 관점으로 후보를 보고, \(horizon) 안에 확인할 조건을 먼저 정리하세요."
}

internal struct HomeCoverageCurationCard: View {
    let coveredCount: Int
    let watchCompanyCount: Int
    let openSearch: () -> Void

    var body: some View {
        Button(action: openSearch) {
            HStack(alignment: .center, spacing: 10) {
                LucideIconView(icon: .database, size: 17)
                    .foregroundStyle(AppTheme.info)
                    .frame(width: 32, height: 32)
                    .background(AppTheme.info.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text("분석 커버리지")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text("분석 후보 \(max(coveredCount, 0))개 · 관심 추적 \(max(watchCompanyCount, 0))개")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                }

                Spacer(minLength: 0)

                Text("검색")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.info)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(AppTheme.info.opacity(0.12), in: Capsule())
            }
            .appCard(padding: 12)
        }
        .buttonStyle(.plain)
    }
}

internal struct HomeTemplateDecisionStack: View {
    let items: [HomeActionInboxItem]
    let profile: InvestmentProfile
    let isLoading: Bool
    let refresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HomeTodayCheckHeader(profile: profile)
            if items.isEmpty {
                HomeCandidateFallbackCard(
                    title: isLoading ? "요약 생성 중" : "오늘의 요약 대기",
                    message: isLoading ? "시장과 후보 데이터를 불러와 오늘 볼 항목을 정리하고 있습니다." : "새로고침하면 최신 판단 항목을 다시 계산합니다.",
                    isLoading: isLoading,
                    retry: refresh
                )
            } else {
                ForEach(Array(items.prefix(3)), id: \.id) { item in
                    HomeTemplateDecisionCard(item: item, featured: false)
                }
            }
        }
    }
}

private struct HomeTodayCheckHeader: View {
    let profile: InvestmentProfile

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                LucideIconView(icon: .listOrdered, size: 20)
                    .foregroundStyle(AppTheme.info)
                Text("오늘 확인할 3가지")
                    .font(.system(size: 21, weight: .black))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
                Spacer(minLength: 8)
                Text(profile.isConfigured ? "내 기준 우선" : "기본 우선")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(AppTheme.elevatedCard.opacity(0.72), in: Capsule())
            }
            Text(profile.isConfigured ? "소음 필터: \(personalLensFocus(profile)) 관점과 맞지 않는 단기 급등 신호는 후보 비교 뒤에 보세요." : "투자 기준을 저장하면 단기 뉴스보다 내 판단 기준에 맞는 항목을 먼저 보여줍니다.")
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

internal struct HomeTemplateDecisionCard: View {
    let item: HomeActionInboxItem
    let featured: Bool

    private var metrics: [String] {
        decisionMetricTokens(from: item.detail)
    }

    var body: some View {
        Button(action: {
            withAnimation(.easeInOut(duration: 0.22)) {
                item.action()
            }
        }) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    ZStack {
                        Circle()
                            .fill(featured ? Color.white.opacity(0.20) : item.color.opacity(0.18))
                        LucideIconView(icon: lucideIcon(forSystemSymbol: item.symbol), size: 20)
                            .foregroundStyle(featured ? Color.white : item.color)
                    }
                    .frame(width: 38, height: 38)

                    Spacer()

                    Text(item.actionTitle)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(featured ? Color.white : item.color)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 5)
                        .background((featured ? Color.white.opacity(0.22) : item.color.opacity(0.16)), in: Capsule())
                }

                Spacer(minLength: 12)

                Text(item.title)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(featured ? Color.white : AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)

                Text(item.detail)
                    .font(.system(size: 13))
                    .foregroundStyle(featured ? Color.white.opacity(0.82) : AppTheme.secondaryText)
                    .lineLimit(2)
                    .lineSpacing(3)
                    .padding(.top, 6)

                if !metrics.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(metrics, id: \.self) { metric in
                            Text(metric)
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(featured ? Color.white : item.color)
                                .monospacedDigit()
                                .lineLimit(1)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background((featured ? Color.white.opacity(0.22) : item.color.opacity(0.12)), in: Capsule())
                        }
                    }
                    .padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 148, alignment: .leading)
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(featured ? AppTheme.accent : AppTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .stroke(featured ? AppTheme.accent.opacity(0.24) : AppTheme.hairline.opacity(0.34), lineWidth: 0.7)
                    )
            )
            .shadow(color: featured ? AppTheme.accentShadow : AppTheme.softShadow, radius: featured ? 14 : 8, y: featured ? 6 : 2)
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
    }
}

internal struct HomeTemplateWatchSection: View {
    let signals: [HomePersonalSignal]
    let portfolioStocks: [PortfolioStock]
    let smallCapStocks: [SmallCapStock]
    let watchItems: [WatchlistItem]
    let priceMetrics: [String: HomeStockPriceMetric]
    let isLoading: Bool
    let openWatch: () -> Void
    let openSignal: (HomePersonalSignal) -> Void
    let refresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HomeTemplateSectionHeader(
                title: "관심종목 브리핑",
                icon: .audioWaveform,
                actionTitle: "전체 보기",
                action: openWatch
            )

            if signals.isEmpty {
                HomeCandidateFallbackCard(
                    title: isLoading ? "관심종목 브리핑 생성 중" : "관심종목 없음",
                    message: isLoading ? "관심 기업과 지수의 변화를 정리하고 있습니다." : "관심 화면에서 기업을 추가하면 이곳에 반복 확인 카드가 표시됩니다.",
                    isLoading: isLoading,
                    retry: refresh
                )
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(signals.prefix(8)) { signal in
                            HomeTemplateWatchCard(
                                signal: signal,
                                display: homeWatchDisplay(
                                    signal: signal,
                                    portfolioStocks: portfolioStocks,
                                    smallCapStocks: smallCapStocks,
                                    watchItems: watchItems,
                                    priceMetrics: priceMetrics
                                )
                            ) {
                                openSignal(signal)
                            }
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 3)
                }
            }
        }
    }
}

internal struct HomeTemplateSectionHeader: View {
    let title: String
    let icon: LucideIcon
    let actionTitle: String
    let action: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            LucideIconView(icon: icon, size: 20)
                .foregroundStyle(AppTheme.info)
            Text(title)
                .font(.system(size: 21, weight: .black))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
            Spacer(minLength: 8)
            Button(action: action) {
                Text(actionTitle)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(AppTheme.info)
                .frame(minHeight: 34)
            }
            .buttonStyle(QuantPressButtonStyle(role: .text))
        }
    }
}
internal struct HomeTemplateCandidateList: View {
    let stocks: [PortfolioStock]
    let profile: InvestmentProfile
    let isLoading: Bool
    let openPortfolio: () -> Void
    let refresh: () -> Void
    let open: (PortfolioStock) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HomeTemplateSectionHeader(
                title: "주목해야 할 후보들",
                icon: .target,
                actionTitle: "전체 보기",
                action: openPortfolio
            )

            if stocks.isEmpty {
                HomeCandidateFallbackCard(
                    title: isLoading ? "후보 로딩 중" : "후보 없음",
                    message: isLoading ? "모델 점수와 기대수익 데이터를 불러오고 있습니다." : "후보 데이터가 아직 도착하지 않았습니다.",
                    isLoading: isLoading,
                    retry: refresh
                )
            } else {
                VStack(spacing: 12) {
                    ForEach(stocks.prefix(3)) { stock in
                        HomeTemplateCandidateRow(stock: stock, profile: profile) {
                            open(stock)
                        }
                    }
                }

                Button(action: openPortfolio) {
                    Text("모든후보 보기")
                        .font(.system(size: 14, weight: .black))
                        .foregroundStyle(AppTheme.accent)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(AppTheme.elevatedCard, in: Capsule())
                }
                .buttonStyle(QuantPressButtonStyle(role: .row))
            }
        }
    }
}

internal struct HomeTemplateCandidateRow: View {
    let stock: PortfolioStock
    let profile: InvestmentProfile
    let open: () -> Void

    private var currency: String {
        marketCurrency(for: stock.ticker, market: stock.market)
    }

    private var reason: HomeCardReason {
        portfolioHomeReason(stock)
    }

    private var personal: PersonalizedStockInterpretation {
        personalizedStockInterpretation(profile: profile, stock: stock)
    }

    private var compactReason: String {
        compactPortfolioReason(reason)
    }

    private var displayName: String {
        localizedCompanyName(ticker: stock.ticker, currentName: stock.name, market: stock.market)
    }

    private var sectorLabel: String {
        portfolioIndustryLabel(ticker: stock.ticker, name: stock.name, sector: stock.sector)
    }

    var body: some View {
        Button(action: {
            withAnimation(.easeInOut(duration: 0.22)) {
                open()
            }
        }) {
            HStack(spacing: 12) {
                CompanyLogoView(ticker: stock.ticker, currency: currency, size: 48)

                VStack(alignment: .leading, spacing: 3) {
                    Text(displayName)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                    Text(sectorLabel)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                    Text("\(personal.headline) · \(personal.detail)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }

                Spacer(minLength: 8)

                VStack(alignment: .trailing, spacing: 4) {
                    AnimatedPriceText(
                        text: stock.currentPrice.map { fmtPx($0, currency: currency) } ?? score(stock.totalScore),
                        font: .system(size: 14, weight: .black).monospacedDigit(),
                        color: AppTheme.primaryText
                    )
                    .lineLimit(1)
                    Text(compactReason)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(reason.color)
                        .lineLimit(1)
                        .multilineTextAlignment(.trailing)
                    if let return1M = stock.return1M {
                        Text(pct(return1M))
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(return1M >= 0 ? AppTheme.positive : AppTheme.negative)
                            .monospacedDigit()
                    }
                }
                .frame(width: 104, alignment: .trailing)
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(AppTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .stroke(AppTheme.hairline.opacity(0.34), lineWidth: 0.7)
                    )
            )
            .shadow(color: AppTheme.softShadow, radius: 14, y: 4)
        }
        .buttonStyle(QuantPressButtonStyle(role: .row))
    }
}

internal func compactPortfolioReason(_ reason: HomeCardReason) -> String {
    switch reason.title {
    case "기대수익":
        return "기대수익"
    case "퀄리티":
        return "ROIC 우수"
    case "성장":
        return "성장 확인"
    case "주의":
        return "타이밍 확인"
    case "확인":
        return "차트 확인"
    default:
        return String(reason.title.prefix(7))
    }
}
