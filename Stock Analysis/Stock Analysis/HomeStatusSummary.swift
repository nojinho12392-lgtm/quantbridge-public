import Combine
import Foundation
import SwiftUI

@MainActor
final class HomeOpsStatusVM: ObservableObject {
    @Published var researchQuality: ResearchQuality?
    @Published var opsHealth: OpsHealth?
    @Published var isLoading = false
    @Published var error: String?

    private var didLoad = false
    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        guard !didLoad else { return }
        didLoad = true
        await refresh()
    }

    func refresh() async {
        isLoading = true
        error = nil
        defer { isLoading = false }

        async let quality = homeResult { try await self.api.fetchResearchQuality() }
        async let ops = homeResult { try await self.api.fetchOpsHealth() }
        let results = await (quality, ops)
        var failures: [String] = []

        switch results.0 {
        case .success(let value):
            researchQuality = value
        case .failure(let error):
            failures.append("Signal Quality: \(error.localizedDescription)")
        }

        switch results.1 {
        case .success(let value):
            opsHealth = value
        case .failure(let error):
            failures.append("운영 상태: \(error.localizedDescription)")
        }

        if !failures.isEmpty {
            self.error = failures.joined(separator: "\n")
        }
    }
}

struct HomeDataTrustCard: View {
    let latestUpdatedAt: String?
    let issueCount: Int
    let loadedSourceCount: Int
    let isLoading: Bool
    let researchQuality: ResearchQuality?
    let opsHealth: OpsHealth?
    let opsError: String?

    private var freshness: DataFreshnessLevel {
        dataFreshnessLevel(latestUpdatedAt)
    }

    private var signalIssueCount: Int {
        researchQuality?.items.filter { ["FAIL", "WATCH", "INSUFFICIENT"].contains($0.status.uppercased()) }.count ?? 0
    }

    private var opsIssueCount: Int {
        guard let opsHealth else { return 0 }
        return opsHealth.checks.filter { !["OK", "PASS", "HEALTHY"].contains($0.status.uppercased()) }.count
    }

    private var totalIssueCount: Int {
        issueCount + signalIssueCount + opsIssueCount + ((opsError?.isEmpty ?? true) ? 0 : 1)
    }

    private var accent: Color {
        if isLoading { return AppTheme.accent }
        if totalIssueCount > 0 { return AppTheme.warning }
        return freshness.color
    }

    private var title: String {
        if isLoading { return "데이터 동기화 중" }
        if totalIssueCount > 0 { return "데이터 갱신 필요" }
        if loadedSourceCount > 0 { return "데이터 정상" }
        return "데이터 대기 중"
    }

    private var detail: String {
        let updatedText = formattedUpdateTimestamp(latestUpdatedAt)
        if let researchCheck {
            return "Signal \(researchQuality?.overallStatus ?? "-") · Research \(researchCheck.status) · 갱신 \(updatedText)"
        }
        if let researchQuality {
            return "Signal \(researchQuality.overallStatus) · 경고 \(researchQuality.warningCount) · 갱신 \(updatedText)"
        }
        if totalIssueCount > 0 {
            return "확인 항목 \(totalIssueCount)개 · 최근 갱신 \(updatedText)"
        }
        return "연결 소스 \(loadedSourceCount)개 · 최근 갱신 \(updatedText)"
    }

    private var researchCheck: OpsCheck? {
        opsHealth?.checks.first {
            $0.name.localizedCaseInsensitiveContains("research")
                || $0.name.localizedCaseInsensitiveContains("signal")
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isLoading ? "arrow.triangle.2.circlepath" : "checkmark.seal.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(accent)
                .frame(width: 34, height: 34)
                .background(accent.opacity(0.12), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            Spacer(minLength: 8)

            DataFreshnessBadge(level: freshness, compact: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AppTheme.elevatedCard.opacity(0.58), in: RoundedRectangle(cornerRadius: 8))
    }
}

struct HomeSourceStatusRow: View {
    let indexStatus: HomeSourceStatus
    let newsStatus: HomeSourceStatus
    let opsStatus: HomeSourceStatus

    var body: some View {
        HStack(spacing: 8) {
            HomeSourceChip(status: indexStatus)
            HomeSourceChip(status: newsStatus)
            HomeSourceChip(status: opsStatus)
        }
    }
}

private struct HomeSourceChip: View {
    let status: HomeSourceStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 5) {
                Circle()
                    .fill(status.color)
                    .frame(width: 7, height: 7)
                Text(status.title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            Text(status.value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
            Text(status.detail)
                .font(.system(size: 12))
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(AppTheme.elevatedCard.opacity(0.46), in: RoundedRectangle(cornerRadius: 8))
    }
}

struct HomeSourceStatus {
    let title: String
    let value: String
    let detail: String
    let color: Color
}

func homeSourceStatus(
    title: String,
    isLoading: Bool,
    error: String?,
    count: Int,
    emptyDetail: String
) -> HomeSourceStatus {
    if isLoading {
        return HomeSourceStatus(title: title, value: "동기화", detail: "불러오는 중", color: AppTheme.warning)
    }
    if error != nil, count == 0 {
        return HomeSourceStatus(title: title, value: "지연", detail: "재시도 가능", color: AppTheme.warning)
    }
    if count > 0 {
        return HomeSourceStatus(title: title, value: "\(count)개", detail: "표시 가능", color: .green)
    }
    return HomeSourceStatus(title: title, value: "대기", detail: emptyDetail, color: AppTheme.secondaryText)
}

func homeOpsSourceStatus(
    isLoading: Bool,
    researchQuality: ResearchQuality?,
    opsHealth: OpsHealth?,
    error: String?
) -> HomeSourceStatus {
    let opsIssues = opsHealth?.checks.filter { !["OK", "PASS", "HEALTHY"].contains($0.status.uppercased()) }.count ?? 0
    let signalIssues = researchQuality?.items.filter { ["FAIL", "WATCH", "INSUFFICIENT"].contains($0.status.uppercased()) }.count ?? 0
    if isLoading, researchQuality == nil, opsHealth == nil {
        return HomeSourceStatus(title: "운영", value: "동기화", detail: "상태 확인 중", color: AppTheme.warning)
    }
    if error != nil, researchQuality == nil, opsHealth == nil {
        return HomeSourceStatus(title: "운영", value: "지연", detail: "상태 대기", color: AppTheme.warning)
    }
    if opsIssues + signalIssues > 0 {
        return HomeSourceStatus(title: "운영", value: "확인", detail: "이슈 \(opsIssues + signalIssues)개", color: AppTheme.warning)
    }
    if researchQuality != nil || opsHealth != nil {
        return HomeSourceStatus(title: "운영", value: "정상", detail: "품질 확인", color: .green)
    }
    return HomeSourceStatus(title: "운영", value: "대기", detail: "상태 대기", color: AppTheme.secondaryText)
}

private func homeResult<T>(_ operation: @escaping () async throws -> T) async -> Result<T, Error> {
    do {
        return .success(try await operation())
    } catch {
        return .failure(error)
    }
}
