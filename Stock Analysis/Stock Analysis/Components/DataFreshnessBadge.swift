import SwiftUI

enum DataFreshnessBadgeTone: Equatable {
    case fresh
    case delayed
    case stale
    case partial
    case unknown
}

struct DataFreshnessPresentation: Equatable {
    let text: String
    let detail: String
    let tone: DataFreshnessBadgeTone
    let systemImage: String?
    let usesDot: Bool
}

func dataFreshnessPresentation(
    source: String?,
    updatedAt: String?,
    now: Date = Date()
) -> DataFreshnessPresentation? {
    let cleanSource = source?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    if cleanSource == "storage_snapshot" {
        return DataFreshnessPresentation(
            text: "부분 데이터",
            detail: "서버 저장 스냅샷 기준",
            tone: .partial,
            systemImage: nil,
            usesDot: true
        )
    }

    guard cleanSource == "storage" else {
        return nil
    }
    guard let date = parsedUpdateTimestamp(updatedAt) else {
        return nil
    }

    let age = max(0, now.timeIntervalSince(date))
    let text = relativeFreshnessText(age: age)
    let tone: DataFreshnessBadgeTone
    if age <= 10 * 60 {
        tone = .fresh
    } else if age <= 60 * 60 {
        tone = .delayed
    } else {
        tone = .stale
    }
    return DataFreshnessPresentation(
        text: text,
        detail: formattedUpdateTimestamp(updatedAt),
        tone: tone,
        systemImage: "clock",
        usesDot: false
    )
}

private func relativeFreshnessText(age: TimeInterval) -> String {
    let minutes = Int(age / 60)
    if minutes < 2 { return "방금 전" }
    if minutes < 60 { return "\(minutes)분 전" }
    let hours = minutes / 60
    if hours < 24 { return "\(hours)시간 전" }
    return "\(max(1, hours / 24))일 전"
}

struct DataFreshnessBadge: View {
    private let presentation: DataFreshnessPresentation?
    private let compact: Bool

    init(source: String?, updatedAt: String?, compact: Bool = true) {
        presentation = dataFreshnessPresentation(source: source, updatedAt: updatedAt)
        self.compact = compact
    }

    init(level: DataFreshnessLevel, compact: Bool = false) {
        presentation = DataFreshnessPresentation(
            text: level.label,
            detail: level.detail,
            tone: DataFreshnessBadgeTone(level: level),
            systemImage: nil,
            usesDot: true
        )
        self.compact = compact
    }

    var body: some View {
        Group {
            if let presentation {
                HStack(spacing: 4) {
                    if presentation.usesDot {
                        Circle()
                            .fill(color(for: presentation.tone))
                            .frame(width: 6, height: 6)
                    } else if let systemImage = presentation.systemImage {
                        Image(systemName: systemImage)
                            .font(.system(size: 11, weight: .semibold))
                    }
                    Text(compact ? presentation.text : "\(presentation.text) · \(presentation.detail)")
                        .font(.system(size: 12, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(Capsule().fill(color(for: presentation.tone).opacity(0.10)))
                .foregroundStyle(color(for: presentation.tone))
                .accessibilityLabel("데이터 상태 \(presentation.text)")
            }
        }
    }

    private func color(for tone: DataFreshnessBadgeTone) -> Color {
        switch tone {
        case .fresh: AppTheme.secondaryText
        case .delayed, .partial: .orange
        case .stale: .red
        case .unknown: AppTheme.secondaryText
        }
    }
}

private extension DataFreshnessBadgeTone {
    init(level: DataFreshnessLevel) {
        switch level {
        case .fresh: self = .fresh
        case .delayed: self = .delayed
        case .stale: self = .stale
        case .unknown: self = .unknown
        }
    }
}
