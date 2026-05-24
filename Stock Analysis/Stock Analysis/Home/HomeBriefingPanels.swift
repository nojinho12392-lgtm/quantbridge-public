import Foundation
import SwiftUI

internal struct TodayBriefingPanel: View {
    let marketValue: String
    let marketDetail: String
    let marketColor: Color
    let portfolio: PortfolioStock?
    let nextEarnings: EarningsCalendarItem?
    let issueCount: Int
    let loadedSourceCount: Int
    let latestUpdatedAt: String?
    let isLoading: Bool
    let openPortfolio: () -> Void
    let openPulse: () -> Void
    let openMarketIndicators: () -> Void
    let refresh: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("오늘의 투자 브리핑")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text("갱신 \(formattedUpdateTimestamp(latestUpdatedAt))")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                }
                Spacer(minLength: 8)
                Button(action: refresh) {
                    LucideIconView(icon: .refreshCw, size: 17)
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 34, height: 34)
                        .background(AppTheme.accent.opacity(0.10), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("홈 새로고침")
            }

            Button(action: openMarketIndicators) {
                HStack(spacing: 10) {
                    Image(systemName: "chart.line.uptrend.xyaxis")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(marketColor)
                        .frame(width: 34, height: 34)
                        .background(marketColor.opacity(0.12), in: Circle())
                    VStack(alignment: .leading, spacing: 3) {
                        Text("시장 상태")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppTheme.secondaryText)
                        Text(marketValue)
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                        Text(marketDetail)
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.tertiaryText)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                BriefingActionTile(
                    title: "오늘 볼 후보",
                    value: portfolio?.name ?? "후보 대기",
                    detail: portfolio.map { portfolioHomeReason($0).detail } ?? "모델 점수 대기 중",
                    symbol: "scope",
                    color: AppTheme.accent,
                    action: openPortfolio
                )

                BriefingActionTile(
                    title: "다음 실적",
                    value: nextEarnings?.name ?? "일정 대기",
                    detail: homeEarningsCalendarSummary(nextEarnings),
                    symbol: "calendar",
                    color: AppTheme.momentum,
                    action: openPulse
                )

                BriefingActionTile(
                    title: "데이터 상태",
                    value: issueCount == 0 ? "정상" : "확인 \(issueCount)",
                    detail: "연결 소스 \(loadedSourceCount)개",
                    symbol: issueCount == 0 ? "checkmark.seal" : "exclamationmark.triangle",
                    color: issueCount == 0 ? AppTheme.quality : AppTheme.warning,
                    action: refresh
                )

                BriefingActionTile(
                    title: "시장 지표",
                    value: "지수 보기",
                    detail: "개장 흐름과 매크로 확인",
                    symbol: "waveform.path.ecg",
                    color: AppTheme.negative,
                    action: openMarketIndicators
                )
            }
        }
        .appCard(padding: 14)
    }
}

internal struct TodayDecisionPanel: View {
    let items: [HomeActionInboxItem]
    let completedIDs: Set<String>
    let snoozedIDs: Set<String>
    let latestUpdatedAt: String?
    let isLoading: Bool
    let refresh: () -> Void
    let complete: (String) -> Void
    let snooze: (String) -> Void
    let reset: () -> Void

    private var activeItems: [HomeActionInboxItem] {
        Array(items.filter { !completedIDs.contains($0.id) && !snoozedIDs.contains($0.id) }.prefix(3))
    }

    private var transitionKey: String {
        activeItems.map(\.id).joined(separator: "|")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("오늘 볼 것 3개")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                }
                Spacer(minLength: 8)
                Button(action: refresh) {
                    LucideIconView(icon: .refreshCw, size: 17)
                }
                .buttonStyle(QuantIconButtonStyle())
                .accessibilityLabel("홈 새로고침")
            }

            if activeItems.isEmpty {
                HStack(spacing: 10) {
                    LucideIconView(icon: .shieldCheck, size: 16)
                        .foregroundStyle(AppTheme.quality)
                        .frame(width: 30, height: 30)
                        .background(AppTheme.quality.opacity(0.10), in: Circle())
                    Text("큰 변화가 없으면 후보와 관심 항목을 계속 감시합니다.")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(2)
                    Spacer(minLength: 0)
                }
                .padding(10)
                .background(AppTheme.elevatedCard.opacity(0.68), in: RoundedRectangle(cornerRadius: 8))
            } else {
                VStack(spacing: 8) {
                    ForEach(Array(activeItems.enumerated()), id: \.element.id) { index, item in
                        TodayDecisionRow(
                            item: item,
                            number: index + 1,
                            openAndComplete: {
                                item.action()
                                withAnimation(.easeInOut(duration: 0.24)) {
                                    complete(item.id)
                                }
                            },
                            complete: {
                                withAnimation(.easeInOut(duration: 0.24)) {
                                    complete(item.id)
                                }
                            }
                        )
                        .transition(.asymmetric(
                            insertion: .move(edge: .bottom).combined(with: .opacity),
                            removal: .move(edge: .top).combined(with: .opacity)
                        ))
                    }
                }
                .animation(.easeInOut(duration: 0.24), value: transitionKey)
            }
        }
        .appCard(padding: 15, role: .decision)
    }
}

internal struct TodayDecisionRow: View {
    let item: HomeActionInboxItem
    let number: Int
    let openAndComplete: () -> Void
    let complete: () -> Void

    private var metrics: [String] {
        decisionMetricTokens(from: item.detail)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            LucideIconView(icon: lucideIcon(forSystemSymbol: item.symbol), size: 15)
                .foregroundStyle(item.color)
                .frame(width: 32, height: 32)
                .background(item.color.opacity(0.10), in: Circle())

            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text("\(number)")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .monospacedDigit()
                        .foregroundStyle(item.color)
                    Text(item.title)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(item.color)
                        .lineLimit(1)
                }
                Text(item.detail)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 6) {
                    ForEach(metrics, id: \.self) { metric in
                        Text(metric)
                            .font(.system(size: 12, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(item.color)
                            .lineLimit(1)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(item.color.opacity(0.10), in: Capsule())
                    }

                    Spacer(minLength: 4)

                    Text(item.actionTitle)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(AppTheme.accent)
                        .lineLimit(1)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(AppTheme.accent.opacity(0.10), in: Capsule())
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: complete) {
                LucideIconView(icon: .square, size: 21)
            }
            .buttonStyle(QuantIconButtonStyle(tint: AppTheme.secondaryText))
            .accessibilityLabel("\(item.title) 확인 완료")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.68), in: RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture(perform: openAndComplete)
    }
}

internal func decisionMetricTokens(from detail: String) -> [String] {
    let separators = CharacterSet(charactersIn: "·|,")
    let tokens = detail
        .components(separatedBy: separators)
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { token in
            guard !token.isEmpty else { return false }
            return token.rangeOfCharacter(from: .decimalDigits) != nil ||
                token.contains("%") ||
                token.contains("#") ||
                token.uppercased().contains("D-") ||
                token.uppercased().contains("D+")
        }
    return Array(tokens.prefix(2))
}
