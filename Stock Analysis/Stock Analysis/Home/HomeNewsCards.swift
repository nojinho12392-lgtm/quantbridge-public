import SwiftUI

internal struct HomeNewsCard: View {
    let item: NewsItem
    let open: () -> Void

    private var impactText: String {
        item.impactReason.isEmpty ? item.summary : item.impactReason
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 6) {
                HomeNewsImpactBadge(item: item)
                if !item.market.isEmpty {
                    Text(item.market)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(AppTheme.accent)
                        .lineLimit(1)
                }
                HomeNewsMoveBadge(item: item)
                Spacer()
            }

            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(2)

            if !impactText.isEmpty {
                HStack(alignment: .top, spacing: 7) {
                    LucideIconView(icon: .newspaper, size: 13)
                        .foregroundStyle(homeNewsImpactColor(item))
                        .padding(.top, 2)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("시장 영향")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(homeNewsImpactColor(item))
                        Text(impactText)
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(2)
                    }
                }
            }

            HStack(spacing: 8) {
                Text("관련")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                if !item.ticker.isEmpty {
                    Text(item.ticker)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(AppTheme.accent)
                }
                Text(item.source)
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                Spacer()
                Text(String(item.publishedAt.prefix(10)).isEmpty ? item.market : String(item.publishedAt.prefix(10)))
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.tertiaryText)
                    .lineLimit(1)
            }
        }
        .frame(width: 230, alignment: .leading)
        .appCard(padding: 12)
        .contentShape(Rectangle())
        .onTapGesture(perform: open)
    }
}

internal struct HomeNewsImpactBadge: View {
    let item: NewsItem

    var body: some View {
        Text(homeNewsImpactLabel(item))
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(homeNewsImpactColor(item))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(homeNewsImpactColor(item).opacity(0.12)))
    }
}

internal struct HomeNewsMoveBadge: View {
    let item: NewsItem

    var body: some View {
        if let text = homeNewsMoveText(item) {
            Text(text)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(homeNewsMoveColor(item))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Capsule().fill(homeNewsMoveColor(item).opacity(0.12)))
        }
    }
}

private func homeNewsImpactLabel(_ item: NewsItem) -> String {
    if !item.impactLabelKo.isEmpty {
        return item.impactLabelKo
    }
    switch item.impactLabel.lowercased() {
    case "positive":
        return "긍정"
    case "negative":
        return "부정"
    default:
        return "중립"
    }
}

private func homeNewsImpactColor(_ item: NewsItem) -> Color {
    switch item.impactLabel.lowercased() {
    case "positive":
        return AppTheme.positive
    case "negative":
        return AppTheme.negative
    default:
        return AppTheme.secondaryText
    }
}

private func homeNewsMoveText(_ item: NewsItem) -> String? {
    guard let change = item.relatedChangePct, change.isFinite else { return nil }
    let label = item.relatedChangeLabel.isEmpty ? "관련" : item.relatedChangeLabel
    let horizon = homeNewsMoveHorizonLabel(item.relatedChangeHorizon)
    let sign = change > 0 ? "+" : ""
    return "\(label)\(horizon) \(sign)\(String(format: "%.1f", change * 100))%"
}

private func homeNewsMoveHorizonLabel(_ value: String) -> String {
    let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return "" }
    if clean == "오늘" || clean == "전장" {
        return " \(clean)"
    }
    return ""
}

private func homeNewsMoveColor(_ item: NewsItem) -> Color {
    guard let change = item.relatedChangePct, change.isFinite else { return AppTheme.secondaryText }
    if change > 0 { return AppTheme.positive }
    if change < 0 { return AppTheme.negative }
    return AppTheme.secondaryText
}
