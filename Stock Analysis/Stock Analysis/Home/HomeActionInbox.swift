import Foundation
import SwiftUI

internal struct HomeActionInboxItem: Identifiable {
    let id: String
    let title: String
    let detail: String
    let symbol: String
    let color: Color
    let actionTitle: String
    let priority: Double
    let action: () -> Void
}

internal enum HomePersonalSignalCategory {
    case earnings
    case candidate
    case indicator
    case basic
}

internal struct HomePersonalSignal: Identifiable {
    let id: String
    let ticker: String
    let name: String
    let title: String
    let detail: String
    let metrics: [String]
    let symbol: String
    let color: Color
    let priority: Int
    let category: HomePersonalSignalCategory
    let updatedAt: String?
    let notificationEvent: WatchNotificationEvent?
}
internal struct BriefingActionTile: View {
    let title: String
    let value: String
    let detail: String
    let symbol: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 7) {
                    Image(systemName: symbol)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(color)
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                }
                Text(value)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.74)
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(2)
                    .minimumScaleFactor(0.78)
                    .frame(minHeight: 28, alignment: .topLeading)
            }
            .frame(maxWidth: .infinity, minHeight: 94, alignment: .topLeading)
            .padding(11)
            .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
    }
}
