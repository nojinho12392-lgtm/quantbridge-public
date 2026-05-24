import Foundation
import SwiftUI

internal struct HomeEarningsCalendarCard: View {
    let item: EarningsCalendarItem
    let isWatched: Bool
    let toggleWatch: () -> Void
    let open: () -> Void

    private var currency: String {
        marketCurrency(for: item.ticker, market: item.market)
    }

    private var displayName: String {
        localizedCompanyName(ticker: item.ticker, currentName: item.name, market: item.market)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 9) {
                CompanyLogoView(ticker: item.ticker, currency: currency, size: 36)
                VStack(alignment: .leading, spacing: 3) {
                    Text(displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                    Text(
                        homeSubtitle(
                            market: item.market,
                            text: item.sector.map { portfolioIndustryLabel(ticker: item.ticker, name: item.name, sector: $0) } ?? "Earnings Calendar"
                        )
                    )
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Button(action: toggleWatch) {
                    Image(systemName: isWatched ? "heart.fill" : "heart")
                        .font(.system(size: 18, weight: .semibold))
                }
                .buttonStyle(QuantIconButtonStyle(tint: isWatched ? .yellow : AppTheme.secondaryText))
                .accessibilityLabel(isWatched ? "관심 해제" : "관심 추가")
            }

            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("예정일")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                    Text(homeCompactDateText(item.nextEarningsDate))
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                        .monospacedDigit()
                }
                Spacer()
                Text(homeEarningsCalendarDayText(item.daysUntil))
                    .font(.headline.weight(.bold))
                    .foregroundStyle((item.daysUntil ?? 99) <= 7 ? AppTheme.warning : AppTheme.accent)
                    .monospacedDigit()
            }

            Text("발표 전 변동성과 포지션 크기를 같이 확인하세요.")
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(2)
        }
        .frame(width: 258, alignment: .leading)
        .frame(minHeight: 156, alignment: .leading)
        .appCard(padding: 12)
        .contentShape(Rectangle())
        .onTapGesture(perform: open)
    }
}

internal struct HomeCandidateFallbackCard: View {
    let title: String
    let message: String
    let isLoading: Bool
    let retry: () -> Void

    var body: some View {
        if isLoading {
            SkeletonLoadingCard(titleWidth: 118, lineCount: 2)
                .frame(width: 258, alignment: .leading)
                .frame(minHeight: 132, alignment: .leading)
        } else {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                LucideIconView(icon: .refreshCw, size: 15)
                    .foregroundStyle(AppTheme.accent)

                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
            }

            Text(message)
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 0)

            Button(action: retry) {
                HStack(spacing: 6) {
                    LucideIconView(icon: .refreshCw, size: 13)
                    Text("다시 불러오기")
                }
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(QuantSecondaryButtonStyle())
        }
        .frame(width: 258, alignment: .leading)
        .frame(minHeight: 132, alignment: .leading)
        .appCard(padding: 12)
        }
    }
}
