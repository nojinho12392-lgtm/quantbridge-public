import Foundation
import SwiftUI

internal struct QuickStatsGrid: View {
    let usCount: Int
    let krCount: Int
    let smallCapCount: Int
    let earningsCount: Int
    let isLoading: Bool

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            StatTile(label: "미국 분석", value: valueText(usCount), icon: "chart.pie.fill", color: .blue)
            StatTile(label: "국내 분석", value: valueText(krCount), icon: "chart.pie", color: .green)
            StatTile(label: "스몰캡", value: valueText(smallCapCount), icon: "diamond.fill", color: .orange)
            StatTile(label: "실적", value: valueText(earningsCount), icon: "bolt.fill", color: .purple)
        }
    }

    private func valueText(_ count: Int) -> String {
        isLoading && count == 0 ? "..." : "\(count)"
    }
}

internal struct StatTile: View {
    let label: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundStyle(color)
                .frame(width: 28, height: 28)
                .background(color.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AppTheme.secondaryText)
                Text(value)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
            }
            Spacer()
        }
        .appCard(padding: 12)
    }
}

internal func stateHasFailure(_ state: APIResult<Bool>) -> Bool {
    if case .failure = state { return true }
    return false
}

internal func newestTimestamp(_ values: [String]) -> String? {
    values
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty }
        .max()
}

internal func portfolioGeneratedAt(_ meta: [String: String]) -> String? {
    for key in ["Generated", "Generated_At", "Last_Updated"] {
        if let value = meta[key]?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
            return value
        }
    }
    return nil
}

internal struct InfoChip: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
    }
}
