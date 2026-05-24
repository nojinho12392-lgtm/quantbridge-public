import Foundation
import SwiftUI

internal struct DailyRoutineItem: Identifiable {
    let id: String
    let title: String
    let detail: String
    let symbol: String
    let color: Color
    let actionTitle: String
    let action: () -> Void
}

internal struct DailyRoutineCard: View {
    let items: [DailyRoutineItem]
    let completedIDs: Set<String>
    let isLoading: Bool
    let toggle: (String) -> Void
    let reset: () -> Void

    private var completedCount: Int {
        items.filter { completedIDs.contains($0.id) }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("오늘의 체크 루틴")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(isLoading ? "데이터 갱신 중에도 루틴은 저장됩니다" : "\(completedCount)/\(items.count) 완료")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                }
                Spacer(minLength: 8)
                Button(action: reset) {
                    Text("초기화")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(AppTheme.elevatedCard, in: Capsule())
                }
                .buttonStyle(.plain)
            }

            ProgressView(value: Double(completedCount), total: Double(max(items.count, 1)))
                .tint(completedCount == items.count ? AppTheme.quality : AppTheme.accent)
                .accessibilityLabel("오늘의 체크 루틴 진행률")

            VStack(spacing: 8) {
                ForEach(items) { item in
                    DailyRoutineRow(
                        item: item,
                        isCompleted: completedIDs.contains(item.id),
                        toggle: { toggle(item.id) },
                        open: item.action
                    )
                }
            }
        }
        .appCard(padding: 14)
    }
}

internal struct DailyRoutineRow: View {
    let item: DailyRoutineItem
    let isCompleted: Bool
    let toggle: () -> Void
    let open: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: toggle) {
                ZStack {
                    Circle()
                        .stroke(isCompleted ? item.color : AppTheme.hairline, lineWidth: 1.5)
                        .frame(width: 24, height: 24)
                    if isCompleted {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(item.color)
                    }
                }
            }
            .buttonStyle(QuantPressButtonStyle(role: .text))
            .accessibilityLabel(isCompleted ? "\(item.title) 완료 취소" : "\(item.title) 완료")

            Image(systemName: item.symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(item.color)
                .frame(width: 26, height: 26)
                .background(item.color.opacity(0.10), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(item.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                Text(item.detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: open) {
                Text(item.actionTitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(item.color)
                    .frame(minWidth: 42)
                    .padding(.vertical, 7)
                    .background(item.color.opacity(0.08), in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
    }
}
