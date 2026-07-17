import Combine
import SwiftUI

struct BlindFinancialQuizResponse: Decodable {
    let id: String
    let title: String
    let prompt: String
    let market: String
    let asOf: String?
    let source: String?
    let generatedAt: String?
    let correctOptionId: String
    let answerRule: String
    let options: [BlindQuizOption]

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case prompt
        case market
        case asOf = "as_of"
        case source
        case generatedAt = "generated_at"
        case correctOptionId = "correct_option_id"
        case answerRule = "answer_rule"
        case options
    }
}

struct BlindQuizOption: Decodable, Identifiable, Hashable {
    let id: String
    let blindLabel: String
    let thesis: String?
    let metrics: [BlindQuizMetric]
    let company: BlindQuizCompany

    enum CodingKeys: String, CodingKey {
        case id
        case blindLabel = "blind_label"
        case thesis
        case metrics
        case company
    }
}

struct BlindQuizMetric: Decodable, Identifiable, Hashable {
    var id: String { label }
    let label: String
    let value: String
    let tone: String?
}

struct BlindQuizCompany: Decodable, Hashable {
    let ticker: String
    let name: String
    let market: String
    let currency: String
    let sector: String?
    let logoUrl: String?
    let pricePoints: [BlindQuizPricePoint]
    let threeYearReturnPct: Double?

    enum CodingKeys: String, CodingKey {
        case ticker
        case name
        case market
        case currency
        case sector
        case logoUrl = "logo_url"
        case pricePoints = "price_points"
        case threeYearReturnPct = "three_year_return_pct"
    }
}

struct BlindQuizPricePoint: Decodable, Identifiable, Hashable {
    var id: String { date }
    let date: String
    let returnPct: Double

    enum CodingKeys: String, CodingKey {
        case date
        case returnPct = "return_pct"
    }
}

@MainActor
final class BlindFinancialQuizVM: ObservableObject {
    @Published var quiz: BlindFinancialQuizResponse?
    @Published var state: APIResult<Bool> = .idle
    @Published var selectedOptionID: String?
    @Published var warning: String?

    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        guard case .idle = state else { return }
        await refresh()
    }

    func refresh(force: Bool = false) async {
        state = .loading
        warning = nil
        do {
            let response: BlindFinancialQuizResponse = try await api.fetch(
                ["training", "blind-financial-quiz"],
                queryItems: [
                    URLQueryItem(name: "market", value: "US"),
                    URLQueryItem(name: "refresh", value: force ? "true" : "false"),
                ]
            )
            quiz = response
            selectedOptionID = nil
            state = .success(true)
        } catch {
            if quiz == nil {
                state = .failure(error.localizedDescription)
            } else {
                warning = "마지막 성공 데이터를 표시 중입니다.\n\(error.localizedDescription)"
                state = .success(true)
            }
        }
    }
}

struct BlindFinancialQuizView: View {
    @StateObject private var vm = BlindFinancialQuizVM()

    var body: some View {
        Group {
            if isInitialLoading {
                LoadingStateView(
                    title: "훈련 문제 로딩 중",
                    detail: "기업명을 가리고 재무제표와 이후 주가를 비교하는 문제를 준비하고 있습니다."
                )
            } else if let error = emptyError {
                ErrView(msg: error) {
                    Task { await vm.refresh(force: true) }
                }
                .padding(.horizontal, 16)
                .padding(.top, 18)
            } else if let quiz = vm.quiz {
                quizContent(quiz)
            } else {
                EmptyMsg(
                    icon: "brain.head.profile",
                    msg: "훈련 문제가 없습니다",
                    detail: "새로고침하면 다시 확인할 수 있습니다.",
                    actionTitle: "새로고침",
                    action: { Task { await vm.refresh(force: true) } }
                )
                .padding(.horizontal, 16)
                .padding(.top, 18)
            }
        }
        .appScreenBackground()
        .task { await vm.load() }
    }

    private var isInitialLoading: Bool {
        if case .idle = vm.state { return true }
        if case .loading = vm.state, vm.quiz == nil { return true }
        return false
    }

    private var emptyError: String? {
        if case .failure(let error) = vm.state, vm.quiz == nil {
            return error
        }
        return nil
    }

    private func quizContent(_ quiz: BlindFinancialQuizResponse) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                BlindQuizHeader(quiz: quiz, revealed: vm.selectedOptionID != nil)

                if let warning = vm.warning {
                    InlineWarningBanner(msg: warning) {
                        Task { await vm.refresh(force: true) }
                    }
                }

                optionDeck(quiz)

                if vm.selectedOptionID != nil {
                    BlindQuizResultSummary(quiz: quiz, selectedOptionID: vm.selectedOptionID)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                Button {
                    Task { await vm.refresh(force: true) }
                } label: {
                    Label("다른 문제 풀기", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(QuantSecondaryButtonStyle())
                .padding(.top, 2)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .appTabBarInset(16)
        }
        .refreshable { await vm.refresh(force: true) }
        .animation(QuantMotion.segment, value: vm.selectedOptionID)
    }

    private func optionDeck(_ quiz: BlindFinancialQuizResponse) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 12) {
                ForEach(quiz.options) { option in
                    BlindQuizOptionCard(
                        option: option,
                        selectedOptionID: vm.selectedOptionID,
                        correctOptionID: quiz.correctOptionId,
                        select: { select(option) }
                    )
                    .frame(maxWidth: .infinity)
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(quiz.options) { option in
                        BlindQuizOptionCard(
                            option: option,
                            selectedOptionID: vm.selectedOptionID,
                            correctOptionID: quiz.correctOptionId,
                            select: { select(option) }
                        )
                        .frame(width: 274)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private func select(_ option: BlindQuizOption) {
        guard vm.selectedOptionID == nil else { return }
        withAnimation(.timingCurve(0.20, 0.00, 0.00, 1.00, duration: 0.48)) {
            vm.selectedOptionID = option.id
        }
    }
}

private struct BlindQuizHeader: View {
    let quiz: BlindFinancialQuizResponse
    let revealed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 10) {
                LucideIconView(icon: .sparkles, size: 20)
                    .foregroundStyle(AppTheme.accent)
                    .frame(width: 42, height: 42)
                    .background(AppTheme.accent.opacity(0.10), in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text("블라인드 재무제표 퀴즈")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.accent)
                    Text(quiz.title)
                        .font(.system(size: 22, weight: .black))
                        .foregroundStyle(AppTheme.primaryText)
                }
                Spacer(minLength: 0)
                Text(revealed ? "복기" : "문제")
                    .font(.caption.weight(.black))
                    .foregroundStyle(revealed ? AppTheme.quality : AppTheme.accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background((revealed ? AppTheme.quality : AppTheme.accent).opacity(0.10), in: Capsule())
            }

            Text(quiz.prompt)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 8) {
                TickerBadge(ticker: quiz.market, accent: AppTheme.secondaryText)
                if let asOf = quiz.asOf {
                    Text("기준 \(asOf)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.tertiaryText)
                }
            }
        }
        .appCard(padding: 14, role: .decision)
    }
}

private struct BlindQuizOptionCard: View {
    let option: BlindQuizOption
    let selectedOptionID: String?
    let correctOptionID: String
    let select: () -> Void

    private var isRevealed: Bool { selectedOptionID != nil }
    private var isSelected: Bool { selectedOptionID == option.id }
    private var isCorrect: Bool { correctOptionID == option.id }

    var body: some View {
        let rotation = isRevealed ? 180.0 : 0.0
        Button(action: select) {
            ZStack {
                BlindQuizCardFront(option: option)
                    .opacity(rotation < 90 ? 1 : 0)

                BlindQuizCardBack(option: option, isSelected: isSelected, isCorrect: isCorrect)
                    .rotation3DEffect(.degrees(180), axis: (x: 0, y: 1, z: 0))
                    .opacity(rotation >= 90 ? 1 : 0)
            }
            .rotation3DEffect(.degrees(rotation), axis: (x: 0, y: 1, z: 0), perspective: 0.72)
            .contentShape(RoundedRectangle(cornerRadius: AppTheme.cardRadius, style: .continuous))
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
        .disabled(isRevealed)
        .accessibilityLabel(isRevealed ? "\(option.company.name) 결과 카드" : "\(option.blindLabel) 선택")
    }
}

private struct BlindQuizCardFront: View {
    let option: BlindQuizOption

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(option.blindLabel)
                        .font(.system(size: 20, weight: .black))
                        .foregroundStyle(AppTheme.primaryText)
                    Text("기업명 비공개")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.tertiaryText)
                }
                Spacer()
                LucideIconView(icon: .eye, size: 18)
                    .foregroundStyle(AppTheme.accent)
                    .frame(width: 38, height: 38)
                    .background(AppTheme.accent.opacity(0.09), in: Circle())
            }

            if let thesis = option.thesis {
                Text(thesis)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(spacing: 8) {
                ForEach(option.metrics) { metric in
                    HStack(spacing: 8) {
                        Text(metric.label)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AppTheme.secondaryText)
                        Spacer(minLength: 8)
                        Text(metric.value)
                            .font(.system(size: 15, weight: .black, design: .rounded))
                            .foregroundStyle(metricToneColor(metric.tone))
                            .lineLimit(1)
                            .minimumScaleFactor(0.74)
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 38)
                    .background(AppTheme.elevatedCard.opacity(0.64), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }

            Spacer(minLength: 0)

            Text("\(option.blindLabel) 선택")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(AppTheme.accent, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
        }
        .frame(minHeight: 380, alignment: .top)
        .appCard(padding: 14)
    }
}

private struct BlindQuizCardBack: View {
    let option: BlindQuizOption
    let isSelected: Bool
    let isCorrect: Bool

    var body: some View {
        let resultTone = isCorrect ? AppTheme.quality : AppTheme.warning
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 10) {
                CompanyLogoView(ticker: option.company.ticker, currency: option.company.currency, size: 42)
                VStack(alignment: .leading, spacing: 3) {
                    Text(option.company.name)
                        .font(.system(size: 18, weight: .black))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                    Text("\(option.company.ticker) · \(option.company.sector ?? option.company.market)")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                Text(isCorrect ? "정답" : "상대적으로 낮음")
                    .font(.caption.weight(.black))
                    .foregroundStyle(resultTone)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(resultTone.opacity(0.10), in: Capsule())
                if isSelected {
                    Text("내 선택")
                        .font(.caption.weight(.black))
                        .foregroundStyle(AppTheme.accent)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(AppTheme.accent.opacity(0.10), in: Capsule())
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("3년 주가 상승률")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
                Text(percentText(option.company.threeYearReturnPct))
                    .font(.system(size: 30, weight: .black, design: .rounded))
                    .foregroundStyle(returnTone(option.company.threeYearReturnPct))
                    .monospacedDigit()
            }

            BlindQuizReturnChart(points: option.company.pricePoints, color: returnTone(option.company.threeYearReturnPct))
                .frame(height: 126)
                .padding(.top, 2)

            Spacer(minLength: 0)

            Text(isCorrect ? "재무 지표의 강점이 실제 가격 모멘텀으로 이어진 케이스입니다." : "싸 보이는 지표보다 성장과 자본효율의 방향이 더 중요했을 수 있습니다.")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 380, alignment: .top)
        .appCard(padding: 14, role: isCorrect ? .decision : .information)
    }
}

private struct BlindQuizReturnChart: View {
    let points: [BlindQuizPricePoint]
    let color: Color

    var body: some View {
        Canvas { context, size in
            guard points.count >= 2 else { return }
            let values = points.map(\.returnPct).filter { $0.isFinite }
            guard let minValue = values.min(), let maxValue = values.max() else { return }
            let lower = min(minValue, 0)
            let upper = max(maxValue, 0)
            let span = max(upper - lower, 0.01)
            let horizontalPadding: CGFloat = 4
            let verticalPadding: CGFloat = 8
            let usableWidth = max(size.width - horizontalPadding * 2, 1)
            let usableHeight = max(size.height - verticalPadding * 2, 1)

            func point(at index: Int, value: Double) -> CGPoint {
                let progress = CGFloat(index) / CGFloat(max(points.count - 1, 1))
                let pointX = horizontalPadding + usableWidth * progress
                let yProgress = CGFloat((value - lower) / span)
                return CGPoint(x: pointX, y: size.height - verticalPadding - usableHeight * yProgress)
            }

            let baselineY = point(at: 0, value: 0).y
            var baseline = Path()
            baseline.move(to: CGPoint(x: 0, y: baselineY))
            baseline.addLine(to: CGPoint(x: size.width, y: baselineY))
            context.stroke(baseline, with: .color(color.opacity(0.14)), lineWidth: 1)

            var line = Path()
            var fill = Path()
            for (index, item) in points.enumerated() {
                let chartPoint = point(at: index, value: item.returnPct)
                if index == 0 {
                    line.move(to: chartPoint)
                    fill.move(to: CGPoint(x: chartPoint.x, y: baselineY))
                    fill.addLine(to: chartPoint)
                } else {
                    line.addLine(to: chartPoint)
                    fill.addLine(to: chartPoint)
                }
            }
            if let last = points.indices.last {
                let lastPoint = point(at: last, value: points[last].returnPct)
                fill.addLine(to: CGPoint(x: lastPoint.x, y: baselineY))
                fill.closeSubpath()
            }

            context.fill(
                fill,
                with: .linearGradient(
                    Gradient(colors: [color.opacity(0.14), color.opacity(0.01)]),
                    startPoint: CGPoint(x: size.width / 2, y: 0),
                    endPoint: CGPoint(x: size.width / 2, y: size.height)
                )
            )
            context.stroke(line, with: .color(color), lineWidth: 2)
        }
        .background(AppTheme.elevatedCard.opacity(0.54), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct BlindQuizResultSummary: View {
    let quiz: BlindFinancialQuizResponse
    let selectedOptionID: String?

    private var selectedIsCorrect: Bool {
        selectedOptionID == quiz.correctOptionId
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            LucideIconView(icon: selectedIsCorrect ? .shieldCheck : .lightbulb, size: 20)
                .foregroundStyle(selectedIsCorrect ? AppTheme.quality : AppTheme.warning)
                .frame(width: 40, height: 40)
                .background((selectedIsCorrect ? AppTheme.quality : AppTheme.warning).opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 5) {
                Text(selectedIsCorrect ? "좋은 선택입니다" : "복기 포인트")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text("\(quiz.answerRule)을 기준으로 정답은 \(quiz.correctOptionId.uppercased())입니다. 다음 문제에서는 PER/PBR의 절대값보다 ROE, 성장률, 부채 부담의 조합을 먼저 비교해 보세요.")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .appCard(padding: 14, role: .status)
    }
}

private func metricToneColor(_ tone: String?) -> Color {
    switch tone {
    case "positive":
        return AppTheme.quality
    case "negative":
        return AppTheme.negative
    case "warning":
        return AppTheme.warning
    default:
        return AppTheme.primaryText
    }
}

private func returnTone(_ value: Double?) -> Color {
    guard let value else { return AppTheme.secondaryText }
    return value >= 0 ? AppTheme.positive : AppTheme.negative
}

private func percentText(_ value: Double?) -> String {
    guard let value else { return "-" }
    return String(format: "%+.1f%%", value * 100)
}

#Preview {
    BlindFinancialQuizView()
        .environmentObject(WatchlistStore())
        .environmentObject(ComparisonStore())
}
