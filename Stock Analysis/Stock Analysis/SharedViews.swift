import SwiftUI
import UIKit

enum AppTheme {
    static let background = adaptive(light: UIColor(hex: 0xF7F8FB), dark: UIColor(hex: 0x10151D))
    static let card = adaptive(light: .white, dark: UIColor(hex: 0x1A222D))
    static let elevatedCard = adaptive(light: UIColor(hex: 0xF0F3F8), dark: UIColor(hex: 0x222C38))
    static let hairline = adaptive(light: UIColor(hex: 0xD7DEE8), dark: UIColor(hex: 0x344152))
    static let primaryText = adaptive(light: UIColor(hex: 0x172033), dark: UIColor(hex: 0xF6F8FB))
    static let secondaryText = adaptive(light: UIColor(hex: 0x536171), dark: UIColor(hex: 0xCBD5E1))
    static let tertiaryText = adaptive(light: UIColor(hex: 0x8491A3), dark: UIColor(hex: 0x94A3B8))

    static let accent = adaptive(light: UIColor(hex: 0x2563EB), dark: UIColor(hex: 0x70A0FF))
    static let positive = adaptive(light: UIColor(hex: 0xD92D20), dark: UIColor(hex: 0xFF6B61))
    static let negative = adaptive(light: UIColor(hex: 0x2563EB), dark: UIColor(hex: 0x70A0FF))
    static let quality = adaptive(light: UIColor(hex: 0x0E7A47), dark: UIColor(hex: 0x58C98D))
    static let warning = adaptive(light: UIColor(hex: 0xB54708), dark: UIColor(hex: 0xF5A24A))
    static let info = adaptive(light: UIColor(hex: 0x0096CC), dark: UIColor(hex: 0x80D0F0))
    static let momentum = adaptive(light: UIColor(hex: 0x7C52AA), dark: UIColor(hex: 0xC8A8E8))

    static let cardRadius: CGFloat = 28
    static let controlRadius: CGFloat = 20
    static let minTouchSize: CGFloat = 40
    static let floatingTabBarInset: CGFloat = 88
    static let softShadow = Color.black.opacity(0.035)
    static let accentShadow = accent.opacity(0.11)

    private static func adaptive(light: UIColor, dark: UIColor) -> Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? dark : light
        })
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        let red = CGFloat((hex >> 16) & 0xFF) / 255
        let green = CGFloat((hex >> 8) & 0xFF) / 255
        let blue = CGFloat(hex & 0xFF) / 255
        self.init(red: red, green: green, blue: blue, alpha: 1)
    }
}

enum AppCardRole: Equatable {
    case decision
    case information
    case status
}

struct AppCardModifier: ViewModifier {
    var padding: CGFloat = 14
    var role: AppCardRole = .information

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cardRadius)
                    .fill(backgroundColor)
                    .overlay(alignment: .leading) {
                        if role == .decision {
                            RoundedRectangle(cornerRadius: AppTheme.cardRadius)
                                .fill(AppTheme.accent)
                                .frame(width: 3)
                        }
                    }
                    .overlay(
                        RoundedRectangle(cornerRadius: AppTheme.cardRadius)
                            .stroke(borderColor, lineWidth: role == .decision ? 0.7 : 0.5)
                    )
            )
            .shadow(
                color: role == .decision ? AppTheme.accentShadow : AppTheme.softShadow,
                radius: role == .decision ? 18 : 14,
                x: 0,
                y: role == .decision ? 8 : 4
            )
    }

    private var backgroundColor: Color {
        switch role {
        case .decision, .information:
            return AppTheme.card
        case .status:
            return AppTheme.elevatedCard.opacity(0.72)
        }
    }

    private var borderColor: Color {
        switch role {
        case .decision:
            return AppTheme.accent.opacity(0.16)
        case .information:
            return AppTheme.hairline.opacity(0.72)
        case .status:
            return AppTheme.hairline.opacity(0.58)
        }
    }
}

extension View {
    func appCard(padding: CGFloat = 14, role: AppCardRole = .information) -> some View {
        modifier(AppCardModifier(padding: padding, role: role))
    }

    func appScreenBackground() -> some View {
        background(AppTheme.background.ignoresSafeArea())
    }

    /// iOS 26+ floating tab bar can overlap scrollable content; reserve extra space for readability.
    func appTabBarInset(_ height: CGFloat = AppTheme.floatingTabBarInset) -> some View {
        safeAreaInset(edge: .bottom) {
            Color.clear.frame(height: height)
        }
    }
}

enum QuantPressRole {
    case card
    case row
    case icon
    case text
}

struct QuantPressButtonStyle: ButtonStyle {
    var role: QuantPressRole = .row

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .opacity(configuration.isPressed ? pressedOpacity : 1)
            .animation(pressAnimation(isPressed: configuration.isPressed), value: configuration.isPressed)
    }

    private var pressedScale: CGFloat {
        switch role {
        case .card:
            return 0.991
        case .row:
            return 0.985
        case .icon, .text:
            return 0.976
        }
    }

    private var pressedOpacity: Double {
        switch role {
        case .card:
            return 0.964
        case .row:
            return 0.94
        case .icon, .text:
            return 0.916
        }
    }

    private func pressAnimation(isPressed: Bool) -> Animation {
        .easeOut(duration: isPressed ? 0.09 : 0.14)
    }
}

enum QuantMotion {
    static let route = Animation.timingCurve(0.20, 0.00, 0.00, 1.00, duration: 0.26)
    static let routeExit = Animation.timingCurve(0.32, 0.00, 0.67, 0.00, duration: 0.20)
    static let segment = Animation.timingCurve(0.20, 0.00, 0.00, 1.00, duration: 0.24)
}

enum QuantRefreshInterval {
    static let fastPrices: UInt64 = 180_000_000_000
    static let standardPrices: UInt64 = 300_000_000_000
}

actor QuantRefreshGate {
    static let shared = QuantRefreshGate()

    private var stamps: [String: Date] = [:]

    func shouldRun(_ key: String, minInterval: TimeInterval = 60) -> Bool {
        let now = Date()
        if let last = stamps[key], now.timeIntervalSince(last) < minInterval {
            return false
        }
        stamps[key] = now
        return true
    }
}

struct AnimatedPriceText: View {
    let text: String
    var font: Font = .body.monospacedDigit()
    var color: Color = AppTheme.primaryText
    var spacing: CGFloat = 0

    var body: some View {
        HStack(spacing: spacing) {
            ForEach(Array(text.enumerated()), id: \.offset) { index, character in
                Text(String(character))
                    .font(font)
                    .foregroundStyle(color)
                    .monospacedDigit()
                    .id("\(index)-\(character)")
                    .transition(.asymmetric(
                        insertion: .offset(y: 7).combined(with: .opacity),
                        removal: .offset(y: -7).combined(with: .opacity)
                    ))
            }
        }
        .fixedSize(horizontal: true, vertical: false)
        .animation(.easeOut(duration: 0.20), value: text)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
    }
}

private struct QuantRouteTransitionModifier: ViewModifier {
    let xOffset: CGFloat
    let opacity: Double
    let scale: CGFloat

    func body(content: Content) -> some View {
        content
            .offset(x: xOffset)
            .opacity(opacity)
            .scaleEffect(scale)
    }
}

extension AnyTransition {
    static func quantRoute(edge: Edge, distance: CGFloat = 34) -> AnyTransition {
        let xOffset: CGFloat
        switch edge {
        case .leading:
            xOffset = -distance
        case .trailing:
            xOffset = distance
        default:
            xOffset = 0
        }

        return .modifier(
            active: QuantRouteTransitionModifier(xOffset: xOffset, opacity: 0, scale: 0.992),
            identity: QuantRouteTransitionModifier(xOffset: 0, opacity: 1, scale: 1)
        )
    }
}

struct QuantPrimaryButtonStyle: ButtonStyle {
    var isComplete = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Color.white)
            .lineLimit(1)
            .minimumScaleFactor(0.82)
            .frame(minHeight: 36)
            .padding(.horizontal, 12)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.controlRadius)
                    .fill((isComplete ? AppTheme.quality : AppTheme.accent).opacity(configuration.isPressed ? 0.84 : 1))
            )
            .shadow(color: AppTheme.accentShadow, radius: configuration.isPressed ? 8 : 14, x: 0, y: configuration.isPressed ? 3 : 6)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: configuration.isPressed ? 0.09 : 0.14), value: configuration.isPressed)
    }
}

struct QuantSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(AppTheme.accent)
            .lineLimit(1)
            .minimumScaleFactor(0.82)
            .frame(minHeight: 36)
            .padding(.horizontal, 12)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.controlRadius)
                    .fill(configuration.isPressed ? AppTheme.accent.opacity(0.10) : AppTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: AppTheme.controlRadius)
                            .stroke(AppTheme.hairline, lineWidth: 0.7)
                        )
            )
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .opacity(configuration.isPressed ? 0.94 : 1)
            .animation(.easeOut(duration: configuration.isPressed ? 0.09 : 0.14), value: configuration.isPressed)
    }
}

struct QuantIconButtonStyle: ButtonStyle {
    var tint: Color = AppTheme.accent

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(tint)
            .frame(width: AppTheme.minTouchSize, height: AppTheme.minTouchSize)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.controlRadius)
                    .fill(tint.opacity(configuration.isPressed ? 0.16 : 0.10))
            )
            .scaleEffect(configuration.isPressed ? 0.976 : 1)
            .animation(.easeOut(duration: configuration.isPressed ? 0.08 : 0.14), value: configuration.isPressed)
    }
}

struct TickerBadge: View {
    let ticker: String
    var accent: Color = AppTheme.accent

    var body: some View {
        Text(shortTicker(ticker))
            .font(.system(size: 12, weight: .bold, design: .monospaced))
            .foregroundStyle(accent)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                Capsule()
                    .fill(accent.opacity(0.1))
                    .overlay(
                        Capsule()
                            .stroke(accent.opacity(0.18), lineWidth: 0.5)
                    )
            )
            .accessibilityLabel("Ticker \(ticker)")
    }
}

struct CompanyLogoView: View {
    let ticker: String
    let currency: String
    var size: CGFloat = 34
    var accent: Color = AppTheme.accent
    @State private var logoIndex = 0

    private var logoCandidates: [URL] {
        logoURLs(for: ticker, currency: currency)
    }

    private var cacheKey: String {
        "\(currency):\(shortTicker(ticker).uppercased())"
    }

    private var currentLogo: (index: Int, url: URL)? {
        CompanyLogoMemoryCache.preferredURL(for: cacheKey, in: logoCandidates)
            ?? CompanyLogoMemoryCache.firstAvailableURL(in: logoCandidates, from: logoIndex)
    }

    private var localLogo: LocalCompanyLogo? {
        localCompanyLogo(for: ticker)
    }

    var body: some View {
        Group {
            if let localLogo {
                localLogoView(localLogo)
            } else if let logo = currentLogo {
                AsyncImage(url: logo.url) { phase in
                    switch phase {
                    case .empty:
                        loadingLogo
                    case .success(let image):
                        logoContainer {
                            image
                                .resizable()
                                .scaledToFit()
                        }
                        .task(id: logo.url.absoluteString) {
                            CompanyLogoMemoryCache.markSuccess(logo.url, for: cacheKey)
                        }
                    case .failure:
                        nextLogoOrFallback(failedIndex: logo.index, failedURL: logo.url)
                    @unknown default:
                        nextLogoOrFallback(failedIndex: logo.index, failedURL: logo.url)
                    }
                }
            } else {
                fallback
            }
        }
        .frame(width: size, height: size)
        .onChange(of: ticker) { _, _ in logoIndex = 0 }
        .onChange(of: currency) { _, _ in logoIndex = 0 }
        .accessibilityLabel("\(shortTicker(ticker)) 로고")
    }

    private var loadingLogo: some View {
        logoContainer {
            ProgressView()
                .scaleEffect(0.65)
        }
    }

    private func nextLogoOrFallback(failedIndex: Int, failedURL: URL) -> some View {
        loadingLogo
            .task(id: "\(cacheKey):\(failedURL.absoluteString)") {
                CompanyLogoMemoryCache.markFailure(failedURL)
                logoIndex = CompanyLogoMemoryCache.nextAvailableIndex(in: logoCandidates, after: failedIndex)
            }
    }

    private func logoContainer<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        circularLogoContainer(
            background: Color(uiColor: .systemBackground),
            border: AppTheme.hairline.opacity(0.36),
            paddingFraction: 0.04,
            content: content
        )
    }

    private func localLogoView(_ logo: LocalCompanyLogo) -> some View {
        circularLogoContainer(
            background: logo.background,
            border: AppTheme.hairline.opacity(0.42),
            paddingFraction: logo.paddingFraction
        ) {
            Image(logo.assetName)
                .resizable()
                .scaledToFit()
        }
    }

    private var fallback: some View {
        circularLogoContainer(
            background: accent.opacity(0.10),
            border: accent.opacity(0.18),
            paddingFraction: 0
        ) {
            Text(String(shortTicker(ticker).prefix(2)).uppercased())
                .font(.system(size: max(10, size * 0.32), weight: .bold, design: .rounded))
                .foregroundStyle(accent)
        }
    }

    private func circularLogoContainer<Content: View>(
        background: Color,
        border: Color,
        paddingFraction: CGFloat,
        @ViewBuilder content: () -> Content
    ) -> some View {
        let contentSize = max(0, size * (1 - paddingFraction * 2))
        return ZStack {
            Circle()
                .fill(background)
            content()
                .frame(width: contentSize, height: contentSize)
                .clipShape(Circle())
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .contentShape(Circle())
        .overlay(
            Circle()
                .stroke(border, lineWidth: 0.5)
        )
    }
}

private struct LocalCompanyLogo {
    let assetName: String
    let background: Color
    let paddingFraction: CGFloat
}

private func localCompanyLogo(for ticker: String) -> LocalCompanyLogo? {
    switch shortTicker(ticker).uppercased() {
    case "EPSN":
        return LocalCompanyLogo(assetName: "CompanyLogoEPSN", background: .black, paddingFraction: 0.02)
    case "RIGL":
        return LocalCompanyLogo(assetName: "CompanyLogoRIGL", background: .white, paddingFraction: 0.02)
    case "UNH":
        return LocalCompanyLogo(assetName: "CompanyLogoUNH", background: .white, paddingFraction: 0.06)
    case "MRVL":
        return LocalCompanyLogo(assetName: "CompanyLogoMRVL", background: .black, paddingFraction: 0.04)
    case "SNDK":
        return LocalCompanyLogo(assetName: "CompanyLogoSNDK", background: .black, paddingFraction: 0.02)
    default:
        return nil
    }
}

private enum CompanyLogoMemoryCache {
    private static var preferredURLs: [String: URL] = [:]
    private static var failedURLs = Set<String>()

    static func preferredURL(for key: String, in candidates: [URL]) -> (index: Int, url: URL)? {
        guard let preferred = preferredURLs[key],
              let index = candidates.firstIndex(of: preferred) else {
            return nil
        }
        return (index, preferred)
    }

    static func firstAvailableURL(in candidates: [URL], from startIndex: Int) -> (index: Int, url: URL)? {
        guard !candidates.isEmpty else { return nil }
        let start = min(max(startIndex, 0), candidates.count)
        for index in start..<candidates.count where !failedURLs.contains(candidates[index].absoluteString) {
            return (index, candidates[index])
        }
        return nil
    }

    static func nextAvailableIndex(in candidates: [URL], after failedIndex: Int) -> Int {
        let next = failedIndex + 1
        guard next < candidates.count else { return candidates.count }
        return firstAvailableURL(in: candidates, from: next)?.index ?? candidates.count
    }

    static func markSuccess(_ url: URL, for key: String) {
        preferredURLs[key] = url
    }

    static func markFailure(_ url: URL) {
        failedURLs.insert(url.absoluteString)
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

struct SectorPill: View {
    let text: String

    var body: some View {
        Text(shortText)
            .font(.system(size: 12, weight: .medium))
            .lineLimit(1)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(AppTheme.secondaryText.opacity(0.1)))
            .foregroundStyle(AppTheme.secondaryText)
            .accessibilityLabel(text)
    }

    private var shortText: String {
        text.count > 14 ? String(text.prefix(14)) + "..." : text
    }
}

struct GlossaryTerm: Identifiable, Hashable {
    let id: String
    let title: String
    let category: String
    let summary: String
    let details: [String]

    var icon: LucideIcon {
        switch category {
        case "밸류에이션", "모델 전망", "AI 보정", "스코어링", "리서치 검증":
            return .target
        case "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀":
            return .trendingUp
        case "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도":
            return .triangleAlert
        case "수급", "분석", "포트폴리오", "기업 규모":
            return .lineChart
        default:
            return .lightbulb
        }
    }

    var actionHint: String {
        switch category {
        case "밸류에이션":
            return "같은 업종 평균, 성장률, 마진을 함께 보세요. 숫자가 낮아도 이익이 꺾이면 싸다고 보기 어렵습니다."
        case "수익성", "퀄리티":
            return "높은 값이 유지되는지, 부채나 일회성 이익으로 만들어진 값은 아닌지 같이 확인하세요."
        case "성장성":
            return "성장률만 보지 말고 마진과 현금흐름이 같이 좋아지는지 확인하면 판단이 더 안전합니다."
        case "현금흐름":
            return "회계상 이익보다 실제 남는 현금에 가깝기 때문에 배당, 자사주, 재투자 여력을 볼 때 유용합니다."
        case "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도":
            return "높은 값은 주가 흔들림이나 재무 부담을 키울 수 있습니다. 수익 신호가 좋아도 비중 판단에 반영하세요."
        case "모델 전망", "AI 보정", "스코어링", "리서치 검증":
            return "단독 매수 신호가 아니라 종목 간 우선순위를 정하는 보조 신호로 읽는 것이 좋습니다."
        case "수급", "실적 모멘텀":
            return "가격 반응과 거래량이 같은 방향인지 확인하세요. 이미 반영된 뉴스일 수도 있습니다."
        case "기업 규모":
            return "대형주는 안정성, 소형주는 성장성과 변동성을 같이 봐야 합니다. 같은 규모군 안에서 비교하면 더 정확합니다."
        default:
            return "\(title)은 단독으로 결론을 내기보다 가격, 성장, 리스크 지표와 함께 비교해 보세요."
        }
    }
}

func glossaryTerm(for rawLabel: String?) -> GlossaryTerm? {
    guard let key = glossaryKey(for: rawLabel) else { return nil }
    switch key {
    case "per":
        return GlossaryTerm(
            id: key,
            title: "PER",
            category: "밸류에이션",
            summary: "주가가 순이익 대비 얼마나 비싼지 보는 대표 지표입니다.",
            details: [
                "PER = 시가총액 / 순이익입니다. 같은 이익을 내는 기업이라면 PER이 낮을수록 가격 부담이 낮다고 해석합니다.",
                "Trailing PER은 이미 발표된 이익 기준이고, Forward PER은 앞으로 예상되는 이익 기준입니다.",
                "낮은 PER이 항상 좋은 것은 아닙니다. 이익 감소, 경기 민감도, 회계상 일회성 이익 때문에 낮아 보일 수 있습니다."
            ]
        )
    case "pbr":
        return GlossaryTerm(id: key, title: "PBR", category: "밸류에이션", summary: "주가가 장부상 순자산 대비 몇 배에 거래되는지 보는 지표입니다.", details: [
            "PBR = 시가총액 / 자기자본입니다. 자산 가치가 중요한 금융, 지주, 전통 제조업에서 특히 자주 봅니다.",
            "PBR이 낮아도 ROE가 낮으면 자본을 효율적으로 쓰지 못한다는 뜻일 수 있습니다.",
            "앱에서는 P/B와 PBR을 같은 의미로 사용합니다."
        ])
    case "ps":
        return GlossaryTerm(id: key, title: "P/S", category: "밸류에이션", summary: "주가가 매출 대비 얼마나 비싼지 보는 지표입니다.", details: [
            "P/S = 시가총액 / 매출입니다. 아직 이익이 작거나 변동성이 큰 성장 기업을 볼 때 보조 지표로 씁니다.",
            "매출은 커도 마진이 낮으면 주주에게 남는 이익이 적을 수 있어 수익성 지표와 함께 봐야 합니다."
        ])
    case "roe":
        return GlossaryTerm(id: key, title: "ROE", category: "수익성", summary: "자기자본으로 얼마나 많은 순이익을 만들었는지 보여줍니다.", details: [
            "ROE = 순이익 / 자기자본입니다. 높을수록 주주 자본을 효율적으로 쓴다고 볼 수 있습니다.",
            "다만 부채를 크게 쓰면 ROE가 높아질 수 있으니 Debt/Equity와 함께 확인해야 합니다."
        ])
    case "roic":
        return GlossaryTerm(id: key, title: "ROIC", category: "퀄리티", summary: "사업에 투입한 자본 대비 영업이익 창출력이 얼마나 좋은지 보는 지표입니다.", details: [
            "ROIC가 높으면 같은 돈을 넣어도 더 많은 영업성과를 만드는 기업일 가능성이 큽니다.",
            "큐빗에서는 퀄리티 팩터의 핵심 지표 중 하나로 봅니다.",
            "업종별 자본 구조가 달라서 같은 업종 안에서 비교하는 것이 더 안전합니다."
        ])
    case "fcf":
        return GlossaryTerm(id: key, title: "FCF", category: "현금흐름", summary: "기업이 영업과 투자를 거친 뒤 실제로 남기는 자유현금흐름입니다.", details: [
            "FCF는 배당, 자사주, 부채 상환, 재투자에 쓸 수 있는 현금 여력을 보여줍니다.",
            "회계상 이익은 좋아도 FCF가 계속 약하면 이익의 질을 보수적으로 봐야 합니다.",
            "FCF 마진은 매출 대비 자유현금흐름 비율입니다."
        ])
    case "debtEquity":
        return GlossaryTerm(id: key, title: "Debt/Equity", category: "재무 리스크", summary: "자기자본 대비 부채 부담이 어느 정도인지 보는 지표입니다.", details: [
            "값이 높을수록 레버리지 부담이 크고 금리, 경기 둔화에 민감할 수 있습니다.",
            "업종별 정상 범위가 크게 다르므로 금융, 유틸리티, 제조업을 같은 기준으로 비교하면 위험합니다."
        ])
    case "debtEbitda":
        return GlossaryTerm(id: key, title: "Debt/EBITDA", category: "재무 리스크", summary: "영업현금 창출력 대비 부채가 얼마나 무거운지 보는 지표입니다.", details: [
            "대략 몇 년치 EBITDA로 부채를 갚을 수 있는지 보는 감각에 가깝습니다.",
            "값이 높을수록 재무 부담이 크고 리밸런싱이나 스몰캡 판단에서 주의 신호로 봅니다."
        ])
    case "ebitda":
        return GlossaryTerm(id: key, title: "EBITDA", category: "수익성", summary: "이자, 세금, 감가상각 전 이익으로 영업 체력의 거친 근사치입니다.", details: [
            "설비투자와 감가상각 영향이 큰 기업을 비교할 때 보조적으로 씁니다.",
            "실제 현금흐름과 같지는 않으므로 FCF와 같이 확인하는 편이 좋습니다."
        ])
    case "growth":
        return GlossaryTerm(id: key, title: "매출 성장", category: "성장성", summary: "최근 매출이 이전 기간 대비 얼마나 늘었는지 보여줍니다.", details: [
            "양수 성장은 제품 수요나 시장 점유율 확대를 시사할 수 있습니다.",
            "성장률만 높고 마진이 낮으면 수익성 없는 성장일 수 있어 마진과 함께 봐야 합니다."
        ])
    case "grossMargin":
        return GlossaryTerm(id: key, title: "매출총이익률", category: "수익성", summary: "매출에서 원가를 뺀 뒤 남는 비율입니다.", details: [
            "높을수록 가격 결정력, 원가 통제력, 제품 경쟁력이 좋을 가능성이 있습니다.",
            "업종 차이가 매우 커서 같은 산업 안에서 비교하는 것이 중요합니다."
        ])
    case "operatingMargin":
        return GlossaryTerm(id: key, title: "영업이익률", category: "수익성", summary: "본업에서 매출 대비 얼마나 이익을 남기는지 보여줍니다.", details: [
            "영업이익률이 높고 안정적이면 사업 모델의 질이 좋다고 볼 수 있습니다.",
            "일회성 비용이나 경기 사이클 때문에 단기적으로 흔들릴 수 있습니다."
        ])
    case "beta":
        return GlossaryTerm(id: key, title: "베타", category: "시장 민감도", summary: "종목이 시장 전체 움직임에 얼마나 민감한지 나타냅니다.", details: [
            "베타가 1보다 크면 시장보다 더 크게 움직이는 경향이 있고, 1보다 작으면 상대적으로 방어적입니다.",
            "과거 가격으로 계산한 값이라 미래 변동성을 보장하지는 않습니다."
        ])
    case "marketCap":
        return GlossaryTerm(id: key, title: "시가총액", category: "기업 규모", summary: "주식시장이 평가하는 기업 전체 가치입니다.", details: [
            "시가총액 = 주가 × 발행주식수입니다.",
            "대형주는 안정성과 유동성이 좋고, 소형주는 성장 여지와 변동성이 함께 커지는 경우가 많습니다."
        ])
    case "expectedReturn":
        return GlossaryTerm(id: key, title: "기대수익률", category: "모델 전망", summary: "현재 팩터와 과거 학습 결과를 바탕으로 모델이 추정한 기대 수익 신호입니다.", details: [
            "실제 확정 수익률이 아니라 종목 간 우선순위를 정하기 위한 모델 출력입니다.",
            "리스크, 거래비용, 리밸런싱 제약과 함께 봐야 하며 단독 매수 신호로 쓰면 안 됩니다."
        ])
    case "weight":
        return GlossaryTerm(id: key, title: "비중", category: "분석", summary: "모델 분석에서 해당 종목에 배분된 기준 비중입니다.", details: [
            "비중이 높을수록 수익과 손실에 미치는 영향이 커집니다.",
            "좋은 종목이어도 리스크 기여도가 너무 크면 비중을 낮추는 판단이 필요할 수 있습니다."
        ])
    case "volatility":
        return GlossaryTerm(id: key, title: "연간 변동성", category: "리스크", summary: "일별 수익률의 흔들림을 연율화한 위험 지표입니다.", details: [
            "값이 높을수록 가격이 크게 출렁이는 종목입니다.",
            "수익률이 높아도 변동성이 지나치게 크면 분석 기준 안정성을 해칠 수 있습니다."
        ])
    case "mdd":
        return GlossaryTerm(id: key, title: "MDD", category: "리스크", summary: "고점에서 저점까지 가장 크게 빠진 낙폭입니다.", details: [
            "Maximum Drawdown의 약자로, 손실을 견뎌야 하는 최대 구간을 보여줍니다.",
            "수익률이 좋아도 MDD가 크면 실제 보유 난이도는 높을 수 있습니다."
        ])
    case "riskContribution":
        return GlossaryTerm(id: key, title: "리스크 기여도", category: "분석 리스크", summary: "해당 종목이나 섹터가 모델 기준 전체 변동성에 얼마나 기여하는지 보여줍니다.", details: [
            "비중이 작아도 변동성과 상관관계가 높으면 리스크 기여도는 커질 수 있습니다.",
            "분석 결과를 단순 비중이 아니라 실제 위험 기준으로 점검할 때 중요합니다."
        ])
    case "rankIc":
        return GlossaryTerm(id: key, title: "Rank IC", category: "리서치 검증", summary: "모델 순위와 이후 수익률 순위가 얼마나 같은 방향으로 움직였는지 보는 검증 지표입니다.", details: [
            "양수이면 점수가 높은 종목이 이후에도 상대적으로 좋은 성과를 냈다는 뜻입니다.",
            "샘플 수가 적거나 기간이 짧으면 우연일 수 있어 품질 게이트와 함께 봐야 합니다."
        ])
    case "mlScore":
        return GlossaryTerm(id: key, title: "AI 보정", category: "AI 보정", summary: "예측 모델이 종목의 상대 매력을 0~1 범위로 평가한 보정 점수입니다.", details: [
            "높을수록 모델이 같은 유니버스 안에서 더 우호적으로 본 후보입니다.",
            "AI 보정은 기존 Value, Quality, Momentum 점수를 보완하는 역할이며 Rank IC가 약하면 영향력이 줄어듭니다."
        ])
    case "factorScore":
        return GlossaryTerm(id: key, title: "팩터 점수", category: "스코어링", summary: "Value, Quality, Momentum 같은 투자 팩터를 정규화해 종목 간 비교가 가능하게 만든 점수입니다.", details: [
            "값이 높을수록 해당 팩터 관점에서 상대적으로 매력적이라는 뜻입니다.",
            "팩터 하나만 높다고 충분하지 않고, 여러 팩터의 균형과 리스크를 함께 봐야 합니다."
        ])
    case "epsSurprise":
        return GlossaryTerm(id: key, title: "EPS 서프라이즈", category: "실적 모멘텀", summary: "실제 주당순이익이 시장 예상치를 얼마나 웃돌았는지 보는 지표입니다.", details: [
            "양수이면 예상보다 실적이 좋았다는 뜻이고 단기 가격 반응의 원인이 될 수 있습니다.",
            "이미 주가에 반영됐을 수 있으므로 발표 후 수익률과 거래량을 같이 봐야 합니다."
        ])
    case "volumeSurge":
        return GlossaryTerm(id: key, title: "거래량 서지", category: "수급", summary: "평소 대비 거래량이 얼마나 늘었는지 보여주는 관심도 지표입니다.", details: [
            "거래량 증가는 정보 반영이나 기관/외국인 수급 변화 가능성을 시사합니다.",
            "가격 상승 없이 거래량만 늘면 매물 출회일 수도 있어 방향성을 함께 확인해야 합니다."
        ])
    default:
        return nil
    }
}

func glossaryTerms(for labels: [String]) -> [GlossaryTerm] {
    var seen = Set<String>()
    return labels.compactMap { label in
        guard let term = glossaryTerm(for: label), !seen.contains(term.id) else { return nil }
        seen.insert(term.id)
        return term
    }
}

private func glossaryKey(for rawLabel: String?) -> String? {
    guard let raw = rawLabel?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
    let lower = raw.lowercased()
    let compact = lower
        .replacingOccurrences(of: " ", with: "")
        .replacingOccurrences(of: "_", with: "")
        .replacingOccurrences(of: "-", with: "")

    if compact.contains("forwardper") || compact.contains("trailingper") || compact == "per" { return "per" }
    if compact == "pbr" || compact == "p/b" || compact == "pb" { return "pbr" }
    if compact == "ps" || compact == "p/s" { return "ps" }
    if compact == "roe" { return "roe" }
    if compact == "roic" { return "roic" }
    if compact == "fcf" || compact.contains("fcf마진") { return "fcf" }
    if compact == "debt/equity" || compact.contains("debtequity") { return "debtEquity" }
    if compact == "debt/ebitda" || compact.contains("debtebitda") { return "debtEbitda" }
    if compact.contains("ebitda") { return "ebitda" }
    if compact.contains("매출성장") || compact.contains("성장가속") { return "growth" }
    if compact.contains("매출총이익률") || compact.contains("grossmargin") { return "grossMargin" }
    if compact.contains("영업이익률") || compact.contains("operatingmargin") { return "operatingMargin" }
    if compact.contains("베타") || compact == "beta" { return "beta" }
    if compact.contains("시가총액") || compact.contains("marketcap") { return "marketCap" }
    if compact.contains("기대수익") || compact.contains("expectedreturn") { return "expectedReturn" }
    if compact == "비중" || compact.contains("portfolioweight") { return "weight" }
    if compact.contains("변동성") || compact.contains("volatility") { return "volatility" }
    if compact.contains("최대낙폭") || compact == "mdd" { return "mdd" }
    if compact.contains("리스크기여") || compact.contains("riskcontribution") { return "riskContribution" }
    if compact == "ic" || compact.contains("rankic") || compact.contains("scorereturnic") { return "rankIc" }
    if compact.contains("ml점수") || compact.contains("ai보정") || compact == "ml" || compact.contains("mlscore") { return "mlScore" }
    if ["value", "quality", "momentum"].contains(compact) || compact.contains("최종점수") || compact.contains("종합점수") { return "factorScore" }
    if compact.contains("eps") || compact.contains("서프라이즈") { return "epsSurprise" }
    if compact.contains("거래량서지") || compact.contains("volumesurge") { return "volumeSurge" }
    return nil
}

struct GlossaryInfoButton: View {
    let label: String
    let onSelect: (GlossaryTerm) -> Void

    var body: some View {
        if let term = glossaryTerm(for: label) {
            Button {
                onSelect(term)
            } label: {
                LucideIconView(icon: .lightbulb, size: 15)
                    .foregroundStyle(AppTheme.accent)
                    .frame(width: 22, height: 22)
                    .background(AppTheme.accent.opacity(0.08), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(term.title) 설명")
        }
    }
}

struct GlossaryChipStrip: View {
    let terms: [GlossaryTerm]
    let onSelect: (GlossaryTerm) -> Void
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
    private var visibleTerms: [GlossaryTerm] { Array(terms.prefix(9)) }

    var body: some View {
        if !visibleTerms.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    LucideIconView(icon: .lightbulb, size: 17)
                        .foregroundStyle(AppTheme.accent)
                    Text("용어 설명")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer()
                    Text("눌러서 자세히")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                }

                LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                    ForEach(visibleTerms) { term in
                        Button {
                            onSelect(term)
                        } label: {
                            Text(term.title)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(AppTheme.accent)
                                .lineLimit(1)
                                .minimumScaleFactor(0.72)
                                .frame(maxWidth: .infinity)
                                .padding(.horizontal, 6)
                                .frame(height: 38)
                                .background(
                                    Capsule()
                                        .fill(AppTheme.accent.opacity(0.09))
                                        .overlay(
                                            Capsule()
                                                .stroke(AppTheme.accent.opacity(0.16), lineWidth: 0.6)
                                        )
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .appCard(padding: 12)
            .padding(.horizontal)
        }
    }
}

struct GlossaryTermDialog: View {
    let term: GlossaryTerm
    let onDismiss: () -> Void

    var body: some View {
        let accent = glossaryAccent(for: term.category)
        ZStack {
            Color.black.opacity(0.28)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)

            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .center, spacing: 12) {
                    LucideIconView(icon: term.icon, size: 22)
                        .foregroundStyle(accent)
                        .frame(width: 44, height: 44)
                        .background(accent.opacity(0.11), in: Circle())

                    VStack(alignment: .leading, spacing: 4) {
                        Text(term.title)
                            .font(.title2.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                        Text(term.category)
                            .font(.footnote.weight(.bold))
                            .foregroundStyle(accent)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(accent.opacity(0.10), in: Capsule())
                    }
                    Spacer()
                    Button("닫기", action: onDismiss)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent)
                        .buttonStyle(.plain)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(
                            Capsule()
                                .fill(accent.opacity(0.08))
                        )
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(term.summary)
                            .font(.system(size: 17, weight: .semibold))
                            .lineSpacing(3)
                            .foregroundStyle(AppTheme.primaryText)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(
                                RoundedRectangle(cornerRadius: 20, style: .continuous)
                                    .fill(AppTheme.elevatedCard)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                                            .stroke(AppTheme.hairline, lineWidth: 0.5)
                                    )
                            )

                        ForEach(Array(term.details.enumerated()), id: \.offset) { index, detail in
                            HStack(alignment: .top, spacing: 10) {
                                Text("\(index + 1)")
                                    .font(.caption.weight(.bold))
                                    .foregroundStyle(accent)
                                    .frame(width: 24, height: 24)
                                    .background(accent.opacity(0.10), in: Circle())
                                Text(detail)
                                    .font(.system(size: 15, weight: .regular))
                                    .lineSpacing(3)
                                    .foregroundStyle(AppTheme.primaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(13)
                            .frame(maxWidth: .infinity, minHeight: 62, alignment: .leading)
                            .background(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .fill(AppTheme.card)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                                            .stroke(AppTheme.hairline, lineWidth: 0.5)
                                    )
                            )
                        }

                        HStack(alignment: .top, spacing: 10) {
                            LucideIconView(icon: .lightbulb, size: 18)
                                .foregroundStyle(accent)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("판단 포인트")
                                    .font(.subheadline.weight(.bold))
                                    .foregroundStyle(accent)
                                Text(term.actionHint)
                                    .font(.system(size: 15, weight: .regular))
                                    .lineSpacing(3)
                                    .foregroundStyle(AppTheme.primaryText)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .fill(accent.opacity(0.08))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                                        .stroke(accent.opacity(0.15), lineWidth: 0.6)
                                )
                        )
                    }
                }
                .frame(maxHeight: 420)
            }
            .padding(18)
            .frame(maxWidth: 368)
            .background(
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .fill(AppTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .stroke(AppTheme.hairline, lineWidth: 0.6)
                    )
            )
            .shadow(color: .black.opacity(0.18), radius: 22, x: 0, y: 14)
            .padding(.horizontal, 22)
        }
    }
}

private func glossaryAccent(for category: String) -> Color {
    switch category {
    case "수익성", "퀄리티", "성장성", "현금흐름", "실적 모멘텀":
        return AppTheme.positive
    case "재무 리스크", "리스크", "분석 리스크", "포트폴리오 리스크", "시장 민감도":
        return AppTheme.warning
    case "수급", "분석", "포트폴리오", "기업 규모":
        return AppTheme.info
    default:
        return AppTheme.accent
    }
}

struct Kpi: View {
    let label: String
    let value: String
    var color: Color = .primary

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(AppTheme.tertiaryText)
            Text(value)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }
}

struct EmptyMsg: View {
    let icon: String
    let msg: String
    var detail: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            LucideIconView(icon: lucideIcon(forSystemSymbol: icon), size: 38)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 58, height: 58)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
            Text(msg)
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .multilineTextAlignment(.center)
            if let resolvedDetail {
                Text(resolvedDetail)
                    .font(.subheadline)
                    .foregroundStyle(AppTheme.secondaryText)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .appCard(padding: 22, role: .status)
    }

    private var resolvedDetail: String? {
        detail ?? "필터를 바꾸거나 새로고침하면 다시 확인할 수 있습니다."
    }
}

struct MarketIndicatorLogoView: View {
    let ticker: String
    let name: String
    var size: CGFloat = 34
    var accent: Color = AppTheme.accent

    private var label: String {
        marketIndicatorLogoText(ticker: ticker, name: name)
    }

    private var fontSize: CGFloat {
        switch label.count {
        case 0...3: return size * 0.34
        case 4...5: return size * 0.25
        default: return size * 0.19
        }
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(accent.opacity(0.11))
                .overlay(
                    Circle()
                        .stroke(accent.opacity(0.18), lineWidth: 0.5)
                )
            Text(label)
                .font(.system(size: fontSize, weight: .bold, design: .rounded))
                .foregroundStyle(accent)
                .lineLimit(1)
                .minimumScaleFactor(0.58)
                .padding(.horizontal, size * 0.09)
        }
        .frame(width: size, height: size)
        .accessibilityLabel("\(label) 지수 로고")
    }
}

struct LoadingStateView: View {
    let title: String
    var detail: String?

    var body: some View {
        VStack(spacing: 16) {
            SkeletonLoadingCard()
                .frame(maxWidth: 360)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
            Text(detail ?? "필요한 데이터를 불러오고 있습니다. 잠시 뒤 화면이 자동으로 갱신됩니다.")
                .font(.footnote)
                .foregroundStyle(AppTheme.secondaryText)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }
}

struct ErrView: View {
    let msg: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            LucideIconView(icon: .triangleAlert, size: 36)
                .foregroundStyle(AppTheme.warning)
                .frame(width: 58, height: 58)
                .background(AppTheme.warning.opacity(0.10), in: Circle())
            Text("데이터를 불러오지 못했어요")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
            Text(msg)
                .font(.subheadline)
                .foregroundStyle(AppTheme.secondaryText)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
            Button("다시 시도", action: retry)
                .buttonStyle(.bordered)
                .tint(AppTheme.accent)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .appCard(padding: 22, role: .status)
    }
}

struct InlineWarningBanner: View {
    let msg: String
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            LucideIconView(icon: .triangleAlert, size: 18)
                .foregroundStyle(.orange)
            Text(msg)
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: retry) {
                LucideIconView(icon: .refreshCw, size: 15)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("다시 시도")
        }
        .padding(.vertical, 4)
    }
}

struct MarketPicker: View {
    @Binding var market: Market

    var body: some View {
        AppSegmentSwitch(options: [Market.us, Market.kr], selection: $market) { market in
            market.title
        }
    }
}

struct AppSegmentSwitch<Option: Hashable>: View {
    let options: [Option]
    @Binding var selection: Option
    let title: (Option) -> String

    var body: some View {
        HStack(spacing: 8) {
            ForEach(options, id: \.self) { option in
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        selection = option
                    }
                } label: {
                    Text(title(option))
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(selection == option ? Color.white : AppTheme.secondaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(
                            Capsule()
                                .fill(selection == option ? AppTheme.accent : Color.clear)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title(option)) 선택")
            }
        }
        .padding(5)
        .background(
            Capsule()
                .fill(AppTheme.elevatedCard.opacity(0.72))
        )
    }
}

struct SortMenu<Option>: View where Option: CaseIterable & Identifiable & RawRepresentable, Option.ID == String, Option.RawValue == String {
    @Binding var selection: Option
    var compact = false
    @State private var isPresented = false

    var body: some View {
        Button {
            isPresented = true
        } label: {
            sortMenuLabel
        }
        .buttonStyle(.plain)
        .accessibilityLabel("정렬")
        .popover(isPresented: $isPresented) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    LucideIconView(icon: .slidersHorizontal, size: 16)
                        .foregroundStyle(AppTheme.accent)
                    Text("정렬 기준")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 2)
                .padding(.bottom, 2)

                ForEach(Array(Option.allCases)) { option in
                    let selected = option.id == selection.id
                    Button {
                        selection = option
                        isPresented = false
                    } label: {
                        HStack(spacing: 10) {
                            LucideIconView(icon: sortMenuIcon(for: option.rawValue), size: 16)
                                .foregroundStyle(selected ? AppTheme.accent : AppTheme.secondaryText)
                            Text(option.rawValue)
                                .font(.system(size: 15, weight: selected ? .bold : .semibold))
                                .foregroundStyle(selected ? AppTheme.accent : AppTheme.primaryText)
                                .lineLimit(1)
                                .minimumScaleFactor(0.78)
                            Spacer(minLength: 8)
                            if selected {
                                LucideIconView(icon: .check, size: 16)
                                    .foregroundStyle(AppTheme.accent)
                            }
                        }
                        .padding(.horizontal, 12)
                        .frame(height: 44)
                        .background(
                            RoundedRectangle(cornerRadius: 17, style: .continuous)
                                .fill(selected ? AppTheme.accent.opacity(0.10) : Color.clear)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(option.rawValue) 선택")
                }
            }
            .padding(10)
            .frame(width: compact ? 214 : 230)
            .background(AppTheme.card)
        }
    }

    @ViewBuilder
    private var sortMenuLabel: some View {
        if compact {
            LucideIconView(icon: .slidersHorizontal, size: 16)
                .foregroundStyle(isPresented ? AppTheme.accent : AppTheme.secondaryText)
                .frame(width: 42, height: 42)
                .background(
                    Circle()
                        .fill(isPresented ? AppTheme.accent.opacity(0.10) : AppTheme.elevatedCard)
                        .overlay(
                            Circle()
                                .stroke(AppTheme.hairline, lineWidth: 0.6)
                        )
                )
        } else {
            HStack(spacing: 7) {
                LucideIconView(icon: .slidersHorizontal, size: 15)
                Text("정렬")
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(AppTheme.accent)
            .padding(.horizontal, 12)
            .frame(minHeight: 38)
            .background(
                Capsule()
                    .fill(AppTheme.elevatedCard)
                    .overlay(
                        Capsule()
                            .stroke(AppTheme.hairline, lineWidth: 0.6)
                    )
            )
        }
    }
}

private func sortMenuIcon(for option: String) -> LucideIcon {
    if option.contains("수익") || option.contains("상승") || option.contains("변동") {
        return .trendingUp
    }
    if option.contains("알파벳") || option.contains("이름") || option.contains("순") {
        return .listOrdered
    }
    if option.contains("가격") || option.contains("시총") || option.contains("규모") {
        return .lineChart
    }
    if option.contains("점수") || option.contains("랭킹") || option.contains("순위") {
        return .target
    }
    if option.contains("날짜") || option.contains("최근") {
        return .calendarClock
    }
    return .slidersHorizontal
}

struct AppSearchField: View {
    @Binding var text: String
    let prompt: String
    var onSubmit: () -> Void = {}
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 8) {
            LucideIconView(icon: .search, size: 14)
                .foregroundStyle(AppTheme.secondaryText)
            TextField(prompt, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.subheadline)
                .submitLabel(.search)
                .focused($isFocused)
                .onSubmit(onSubmit)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    LucideIconView(icon: .x, size: 16)
                        .foregroundStyle(AppTheme.tertiaryText)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("검색어 지우기")
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 44)
        .background(
            Capsule()
                .fill(AppTheme.elevatedCard.opacity(isFocused ? 1 : 0.76))
                .overlay(
                    Capsule()
                        .stroke(isFocused ? AppTheme.accent.opacity(0.26) : AppTheme.hairline.opacity(0.42), lineWidth: 0.7)
                )
        )
    }
}

struct SearchStatusLine: View {
    let query: String
    let visibleCount: Int
    let totalCount: Int
    var label: String = "종목"
    var isLoading = false

    private var statusText: String {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if isLoading {
            return "\(label) 동기화 중"
        }
        if clean.isEmpty {
            return "\(label) 전체 \(totalCount)개"
        }
        return "\"\(clean)\" \(visibleCount)/\(totalCount)개"
    }

    var body: some View {
        HStack(spacing: 6) {
            LucideIconView(icon: isLoading ? .refreshCw : .slidersHorizontal, size: 12)
            Text(statusText)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
        }
        .font(.caption)
        .foregroundStyle(AppTheme.secondaryText)
        .padding(.horizontal, 2)
    }
}

struct WatchMetadataSheet: View {
    let item: WatchlistItem
    let onSave: (WatchlistItem) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedTags: Set<String>
    @State private var selectedAlerts: Set<String>
    @State private var thesis: WatchInvestmentThesis

    private let tagOptions = ["실적", "저평가", "모멘텀", "리스크", "공부", "매수후보"]
    private let alertOptions = watchJudgmentAlertOptions
    private let horizonOptions = ["1개월", "3개월", "6개월", "1년+"]
    private let reviewOptions = ["유지", "수정", "종료"]

    init(item: WatchlistItem, onSave: @escaping (WatchlistItem) -> Void) {
        self.item = item
        self.onSave = onSave
        _selectedTags = State(initialValue: Set(item.tags))
        _selectedAlerts = State(initialValue: Set(item.alertOptions))
        _thesis = State(initialValue: item.investmentThesis)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 10) {
                        CompanyLogoView(ticker: item.ticker, currency: item.currency)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.name)
                                .font(.headline.weight(.bold))
                                .foregroundStyle(AppTheme.primaryText)
                                .lineLimit(1)
                            Text("\(item.ticker) · \(item.market)")
                                .font(.caption)
                                .foregroundStyle(AppTheme.secondaryText)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listRowBackground(AppTheme.card)

                Section("관심 이유") {
                    WatchMetadataChipGrid(options: tagOptions, selected: $selectedTags)
                }
                .listRowBackground(AppTheme.card)

                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        WatchThesisQualityPanel(quality: thesis.quality)
                        Text("관심 등록의 이유와 틀렸다고 볼 조건을 남겨두면 나중에 신호를 더 차분하게 점검할 수 있습니다.")
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)

                        WatchThesisTextField(
                            title: "관심 이유",
                            placeholder: "예: AI 서버 수요가 계속 늘어날 것",
                            text: $thesis.reason
                        )
                        WatchThesisTextField(
                            title: "기대하는 변화",
                            placeholder: "예: 다음 분기 매출 성장률 유지",
                            text: $thesis.expectedChange
                        )
                        WatchThesisTextField(
                            title: "확인할 조건",
                            placeholder: "예: 마진과 가이던스가 같이 개선되는지",
                            text: $thesis.checkCondition
                        )
                        WatchThesisTextField(
                            title: "틀렸다고 볼 조건",
                            placeholder: "예: 매출 둔화와 마진 하락이 동시에 발생",
                            text: $thesis.invalidationCondition
                        )
                        WatchMetadataSingleChipGrid(
                            title: "관찰 기간",
                            options: horizonOptions,
                            selected: $thesis.horizon
                        )
                    }
                    .padding(.vertical, 2)
                } header: {
                    Text("투자 가설")
                } footer: {
                    Text("매수 추천이 아니라 내 판단 기준을 기록하는 용도입니다.")
                }
                .listRowBackground(AppTheme.card)

                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("처음 생각이 맞았는지 정기적으로 유지, 수정, 종료 중 하나로 정리하세요.")
                            .font(.caption)
                            .foregroundStyle(AppTheme.secondaryText)
                            .fixedSize(horizontal: false, vertical: true)

                        VStack(alignment: .leading, spacing: 4) {
                            Label(thesis.quality.reviewTiming, systemImage: "calendar.badge.clock")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(AppTheme.accent)
                            Text(thesis.reviewPrompt)
                                .font(.caption)
                                .foregroundStyle(AppTheme.secondaryText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(11)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(AppTheme.accent.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))

                        WatchMetadataSingleChipGrid(
                            title: "복기 상태",
                            options: reviewOptions,
                            selected: $thesis.reviewStatus
                        )
                        WatchThesisTextField(
                            title: "복기 메모",
                            placeholder: "예: 가설은 유지하되 실적 발표 전까지 신규 판단 보류",
                            text: $thesis.reviewNote
                        )
                    }
                    .padding(.vertical, 2)
                } header: {
                    Text("복기 루프")
                }
                .listRowBackground(AppTheme.card)

                Section("알림 조건") {
                    WatchMetadataChipGrid(options: alertOptions, selected: $selectedAlerts)
                    Text("가격 도달보다 투자 가설, 실적 리스크, 과열 신호, 내 성향 기준 변화가 생겼을 때 판단 알림으로 연결됩니다.")
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .listRowBackground(AppTheme.card)

            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .appScreenBackground()
            .navigationTitle("관심 설정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("저장") {
                        onSave(
                            item.withMetadata(
                                tags: tagOptions.filter { selectedTags.contains($0) },
                                memo: thesis.memoText,
                                alertOptions: alertOptions.filter { selectedAlerts.contains($0) }
                            )
                        )
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }
}

private struct WatchThesisQualityPanel: View {
    let quality: WatchThesisQuality

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                LucideIconView(icon: quality.percent >= 80 ? .check : .lightbulb, size: 14)
                    .foregroundStyle(quality.percent >= 80 ? AppTheme.quality : AppTheme.warning)
                Text("가설 완성도 \(quality.percent)% · \(quality.label)")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(quality.percent >= 80 ? AppTheme.quality : AppTheme.warning)
                Spacer(minLength: 0)
            }
            if !quality.missingFields.isEmpty {
                Text("빠진 항목: \(quality.missingFields.prefix(2).joined(separator: " · "))")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(11)
        .background((quality.percent >= 80 ? AppTheme.quality : AppTheme.warning).opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct WatchThesisTextField: View {
    let title: String
    let placeholder: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            TextField(placeholder, text: $text, axis: .vertical)
                .font(.subheadline)
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(2...4)
                .padding(10)
                .background(AppTheme.elevatedCard, in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(AppTheme.hairline, lineWidth: 0.6)
                )
        }
    }
}

private struct WatchMetadataChipGrid: View {
    let options: [String]
    @Binding var selected: Set<String>

    private let columns = [GridItem(.adaptive(minimum: 92), spacing: 8)]

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(options, id: \.self) { option in
                Button {
                    if selected.contains(option) {
                        selected.remove(option)
                    } else {
                        selected.insert(option)
                    }
                } label: {
                    HStack(spacing: 6) {
                        LucideIconView(icon: selected.contains(option) ? .check : .square, size: 13)
                        Text(option)
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                    }
                    .foregroundStyle(selected.contains(option) ? AppTheme.primaryText : AppTheme.secondaryText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(selected.contains(option) ? AppTheme.elevatedCard : Color.clear)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(selected.contains(option) ? AppTheme.accent.opacity(0.28) : AppTheme.hairline, lineWidth: 0.6)
                            )
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct WatchMetadataSingleChipGrid: View {
    let title: String
    let options: [String]
    @Binding var selected: String

    private let columns = [GridItem(.adaptive(minimum: 82), spacing: 8)]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                ForEach(options, id: \.self) { option in
                    let isSelected = selected == option
                    Button {
                        selected = isSelected ? "" : option
                    } label: {
                        HStack(spacing: 6) {
                            LucideIconView(icon: isSelected ? .check : .square, size: 13)
                            Text(option)
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                        }
                        .foregroundStyle(isSelected ? AppTheme.primaryText : AppTheme.secondaryText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(isSelected ? AppTheme.elevatedCard : Color.clear)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(isSelected ? AppTheme.accent.opacity(0.28) : AppTheme.hairline, lineWidth: 0.6)
                                )
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private let investmentDecisionReasonOptions = ["장기 성장", "저평가", "실적 개선", "배당/안정성", "단기 모멘텀", "공부 필요"]
private let investmentDecisionStatusOptions = ["관심 유지", "보류", "제외", "추가 확인 필요", "실적 후 재검토", "내 성향과 맞지 않음"]
private let investmentDecisionReviewOptions = ["실적 발표 후", "점수 개선 시", "가격 조정 시", "리스크 완화 시", "비교 후보 확인 후"]

private enum InvestmentDecisionStep: Int, CaseIterable {
    case reason
    case fit
    case counterEvidence
    case reviewCondition
    case status

    var question: String {
        switch self {
        case .reason:
            return "투자 이유가 무엇인가요?"
        case .fit:
            return "내 기준으로 어떻게 보나요?"
        case .counterEvidence:
            return "주의할 신호는 무엇인가요?"
        case .reviewCondition:
            return "다시 볼 조건은?"
        case .status:
            return "최종 상태는?"
        }
    }
}

struct InvestmentDecisionSummarySection: View {
    let ticker: String
    let name: String
    let market: String
    let currency: String
    let profile: InvestmentProfile
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let record: InvestmentDecisionRecord?
    let edit: () -> Void
    @State private var showDecisionGuide = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                LucideIconView(icon: .calendarCheck, size: 17)
                    .foregroundStyle(investmentDecisionStatusColor(record?.status))
                Text("내 투자 결정서")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                if record == nil {
                    Button {
                        showDecisionGuide = true
                    } label: {
                        LucideIconView(icon: .lightbulb, size: 17)
                            .foregroundStyle(AppTheme.accent)
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("투자 결정서 설명")
                }
                Spacer()
                if let record {
                    InvestmentDecisionQualityPill(percent: record.qualityPercent, label: record.qualityLabel)
                }
            }

            if let record {
                Text(record.headline)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(investmentDecisionStatusColor(record.status))
                    .lineLimit(1)
                Text(record.summary)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                if !record.fitLabel.isEmpty {
                    InvestmentDecisionHintRow(label: "기준 해석", value: record.fitLabel)
                }
                if !record.note.isEmpty {
                    InvestmentDecisionHintRow(label: "메모", value: record.note)
                }
                Button(action: edit) {
                    Label("결정서 수정", systemImage: "square.and.pencil")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            } else {
                Button(action: edit) {
                    Label("투자 결정서 작성", systemImage: "square.and.pencil")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppTheme.accent)
            }
        }
        .appCard(padding: 14)
        .padding(.horizontal)
        .alert("투자 결정서란?", isPresented: $showDecisionGuide) {
            Button("확인", role: .cancel) {}
        } message: {
            Text("투자 결정서는 매수/매도를 바로 정하는 기능이 아니라, 이 종목을 보는 이유와 주의 신호, 다시 볼 조건을 먼저 남기는 판단 기록입니다. 나중에 감정이 아니라 내가 세운 기준으로 결정을 복기할 수 있게 도와줍니다.")
        }
    }
}

struct InvestmentDecisionSheet: View {
    let ticker: String
    let name: String
    let market: String
    let currency: String
    let profile: InvestmentProfile
    let info: StockInfo?
    let staticMetrics: [StaticMetric]
    let signals: [InvestmentSignal]
    let record: InvestmentDecisionRecord?
    let onSave: (InvestmentDecisionRecord) -> Void
    let onDelete: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var selectedReasons: Set<String>
    @State private var selectedCounters: Set<String>
    @State private var status: String
    @State private var reviewTrigger: String
    @State private var condition: String
    @State private var note: String
    @State private var step: InvestmentDecisionStep = .reason

    private var fitLabel: String {
        if let record, !record.fitLabel.isEmpty { return record.fitLabel }
        return fitInsight.decisionLine
    }

    private var fitInsight: PersonalizedStockInterpretation {
        personalizedStockInterpretation(
            profile: profile,
            name: info?.name ?? name,
            info: info,
            metrics: staticMetrics,
            signals: signals
        )
    }

    private var counterOptions: [String] {
        orderedUnique(suggestedDecisionCounterEvidence(profile: profile, info: info, metrics: staticMetrics, signals: signals) + defaultDecisionCounterEvidenceOptions())
    }

    private var fitConflictSignals: [String] {
        Array(suggestedDecisionCounterEvidence(profile: profile, info: info, metrics: staticMetrics, signals: signals).prefix(3))
    }

    private var stepIndex: Int {
        InvestmentDecisionStep.allCases.firstIndex(of: step) ?? 0
    }

    private var isLastStep: Bool {
        stepIndex == InvestmentDecisionStep.allCases.count - 1
    }

    init(
        ticker: String,
        name: String,
        market: String,
        currency: String,
        profile: InvestmentProfile,
        info: StockInfo?,
        staticMetrics: [StaticMetric],
        signals: [InvestmentSignal],
        record: InvestmentDecisionRecord?,
        onSave: @escaping (InvestmentDecisionRecord) -> Void,
        onDelete: (() -> Void)? = nil
    ) {
        self.ticker = ticker
        self.name = name
        self.market = market
        self.currency = currency
        self.profile = profile
        self.info = info
        self.staticMetrics = staticMetrics
        self.signals = signals
        self.record = record
        self.onSave = onSave
        self.onDelete = onDelete
        let reasons = record?.reasons ?? Array(suggestedDecisionReasons(metrics: staticMetrics, signals: signals, info: info).prefix(2))
        let counters = record?.counterEvidence ?? Array(suggestedDecisionCounterEvidence(profile: profile, info: info, metrics: staticMetrics, signals: signals).prefix(2))
        _selectedReasons = State(initialValue: Set(reasons))
        _selectedCounters = State(initialValue: Set(counters))
        _status = State(initialValue: record?.status ?? "추가 확인 필요")
        _reviewTrigger = State(initialValue: record?.reviewTrigger ?? investmentDecisionReviewOptions[0])
        _condition = State(initialValue: record?.condition ?? suggestedDecisionCondition(info: info, metrics: staticMetrics, signals: signals))
        _note = State(initialValue: record?.note ?? "")
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                headerView
                progressView
                Text(step.question)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .fixedSize(horizontal: false, vertical: true)
                ScrollView {
                    stepContent
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                }
                .scrollIndicators(.hidden)
                Spacer(minLength: 0)
                footerView
            }
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 16)
            .appScreenBackground()
            .navigationTitle("투자 결정서")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
                if onDelete != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(role: .destructive) {
                            onDelete?()
                            dismiss()
                        } label: {
                            Image(systemName: "trash")
                        }
                    }
                }
            }
        }
    }

    private var headerView: some View {
        HStack(spacing: 10) {
            CompanyLogoView(ticker: ticker, currency: currency)
            VStack(alignment: .leading, spacing: 3) {
                Text(name)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(1)
                Text("\(ticker) · \(market)")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
            }
            Spacer()
        }
    }

    private var progressView: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 5) {
                ForEach(0..<InvestmentDecisionStep.allCases.count, id: \.self) { index in
                    Capsule()
                        .fill(index <= stepIndex ? AppTheme.accent : AppTheme.hairline)
                        .frame(height: 4)
                }
            }
            Text("\(stepIndex + 1) / \(InvestmentDecisionStep.allCases.count)")
                .font(.caption2.weight(.bold))
                .foregroundStyle(AppTheme.secondaryText)
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case .reason:
            VStack(alignment: .leading, spacing: 10) {
                InvestmentDecisionMultiOptionList(options: investmentDecisionReasonOptions, selected: $selectedReasons)
                selectionCount("\(selectedReasons.count)개 선택됨")
            }
        case .fit:
            InvestmentDecisionFitInsightPanel(
                profile: profile,
                insight: fitInsight,
                conflicts: fitConflictSignals,
                fitLine: fitLabel
            )
        case .counterEvidence:
            VStack(alignment: .leading, spacing: 10) {
                InvestmentDecisionMultiOptionList(options: counterOptions, selected: $selectedCounters)
                selectionCount("\(selectedCounters.count)개 선택됨")
            }
        case .reviewCondition:
            VStack(alignment: .leading, spacing: 14) {
                WatchThesisTextField(
                    title: "확인 조건",
                    placeholder: "예: 실적 발표 후 매출 성장과 마진이 같이 유지되는지",
                    text: $condition
                )
                InvestmentDecisionSingleOptionList(
                    options: investmentDecisionReviewOptions,
                    selected: $reviewTrigger
                )
            }
        case .status:
            VStack(alignment: .leading, spacing: 14) {
                InvestmentDecisionSingleOptionList(
                    options: investmentDecisionStatusOptions,
                    selected: $status
                )
                WatchThesisTextField(
                    title: "결론 메모",
                    placeholder: "예: 기대수익은 높지만 실적 전 불확실성이 있어 보류",
                    text: $note
                )
            }
        }
    }

    private var footerView: some View {
        HStack(spacing: 8) {
            Button("취소") { dismiss() }
                .buttonStyle(.bordered)
            Spacer()
            if stepIndex > 0 {
                Button("이전") { moveStep(by: -1) }
                    .buttonStyle(.bordered)
            }
            Button(isLastStep ? "저장" : "다음") {
                if isLastStep {
                    saveAndDismiss()
                } else {
                    moveStep(by: 1)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.accent)
        }
    }

    private func selectionCount(_ value: String) -> some View {
        Text(value)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(AppTheme.secondaryText)
    }

    private func moveStep(by offset: Int) {
        let nextIndex = max(0, min(stepIndex + offset, InvestmentDecisionStep.allCases.count - 1))
        step = InvestmentDecisionStep.allCases[nextIndex]
    }

    private func saveAndDismiss() {
        onSave(
            InvestmentDecisionRecord(
                ticker: ticker,
                name: name,
                market: market,
                currency: currency,
                reasons: investmentDecisionReasonOptions.filter { selectedReasons.contains($0) },
                counterEvidence: counterOptions.filter { selectedCounters.contains($0) },
                fitLabel: fitLabel,
                condition: condition,
                status: status,
                reviewTrigger: reviewTrigger,
                note: note,
                createdAt: record?.createdAt ?? Date()
            )
        )
        dismiss()
    }
}

private struct InvestmentDecisionFitInsightPanel: View {
    let profile: InvestmentProfile
    let insight: PersonalizedStockInterpretation
    let conflicts: [String]
    let fitLine: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text("내 기준 자동 해석")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.accent)
                Text("매수/매도 추천이 아니라 저장된 투자 기준과 종목 신호를 대조한 1차 판단입니다.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            InvestmentDecisionFitExplainRow(label: "내 기준", value: decisionProfileCriteria(profile))
            InvestmentDecisionFitExplainRow(label: "감지된 신호", value: decisionDetectedSignals(insight))
            InvestmentDecisionFitExplainRow(label: "충돌 신호", value: decisionConflictSignals(conflicts))
            InvestmentDecisionFitExplainRow(label: "결론", value: fitLine)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(AppTheme.hairline.opacity(0.72), lineWidth: 1)
        )
    }
}

private struct InvestmentDecisionFitExplainRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(label)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.secondaryText)
                .frame(maxWidth: 82, alignment: .leading)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private func decisionProfileCriteria(_ profile: InvestmentProfile) -> String {
    let clean = profile.normalized
    guard clean.isConfigured else { return "아직 투자 기준 미설정" }
    let style = decisionStyleLabel(clean.style)
    let horizon = clean.horizon.isEmpty ? "기간 미설정" : clean.horizon
    let volatility = decisionVolatilityLabel(clean.riskTolerance)
    let guardrail = decisionPrimaryGuardrail(clean)
    return [style, horizon, volatility, guardrail].joined(separator: " · ")
}

private func decisionDetectedSignals(_ insight: PersonalizedStockInterpretation) -> String {
    let values = Array(insight.reasons.prefix(4))
    return values.isEmpty ? "뚜렷한 자동 신호 부족" : values.joined(separator: " · ")
}

private func decisionConflictSignals(_ values: [String]) -> String {
    let clean = Array(values.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.prefix(3))
    return clean.isEmpty ? "현재 기준과 직접 충돌하는 신호는 뚜렷하지 않음" : clean.joined(separator: " · ")
}

private func decisionStyleLabel(_ style: String) -> String {
    if style.localizedCaseInsensitiveContains("성장") { return "성장형" }
    if style.localizedCaseInsensitiveContains("가치") { return "가치형" }
    if style.localizedCaseInsensitiveContains("배당") { return "배당형" }
    if style.localizedCaseInsensitiveContains("퀄리티") { return "퀄리티형" }
    if style.localizedCaseInsensitiveContains("모멘텀") { return "모멘텀형" }
    return style.isEmpty ? "스타일 미설정" : "\(style)형"
}

private func decisionVolatilityLabel(_ riskTolerance: String) -> String {
    if riskTolerance.localizedCaseInsensitiveContains("공격") || riskTolerance.localizedCaseInsensitiveContains("성장") {
        return "변동성 허용"
    }
    if riskTolerance.localizedCaseInsensitiveContains("보수") || riskTolerance.localizedCaseInsensitiveContains("안정") || riskTolerance.localizedCaseInsensitiveContains("낮") {
        return "변동성 제한"
    }
    if riskTolerance.isEmpty {
        return "변동성 기준 미설정"
    }
    return "변동성 균형"
}

private func decisionPrimaryGuardrail(_ profile: InvestmentProfile) -> String {
    let avoidances = profile.avoidances
    if avoidances.contains(where: { $0.localizedCaseInsensitiveContains("고평가") }) {
        return "고점 추격 주의"
    }
    if avoidances.contains(where: { $0.localizedCaseInsensitiveContains("급등락") }) {
        return "급등락 회피"
    }
    if avoidances.contains(where: { $0.localizedCaseInsensitiveContains("부채") }) {
        return "부채 부담 확인"
    }
    if avoidances.contains(where: { $0.localizedCaseInsensitiveContains("적자") }) {
        return "적자 지속 주의"
    }
    if avoidances.contains(where: { $0.localizedCaseInsensitiveContains("거래량") }) {
        return "거래량 부족 주의"
    }
    return "고점 추격 주의"
}

private struct InvestmentDecisionMultiOptionList: View {
    let options: [String]
    @Binding var selected: Set<String>

    var body: some View {
        VStack(spacing: 10) {
            ForEach(options, id: \.self) { option in
                InvestmentDecisionOptionRow(title: option, isSelected: selected.contains(option)) {
                    if selected.contains(option) {
                        selected.remove(option)
                    } else {
                        selected.insert(option)
                    }
                }
            }
        }
    }
}

private struct InvestmentDecisionSingleOptionList: View {
    let options: [String]
    @Binding var selected: String

    var body: some View {
        VStack(spacing: 10) {
            ForEach(options, id: \.self) { option in
                InvestmentDecisionOptionRow(title: option, isSelected: selected == option) {
                    selected = selected == option ? "" : option
                }
            }
        }
    }
}

private struct InvestmentDecisionOptionRow: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    private var borderColor: Color {
        isSelected ? AppTheme.secondaryText.opacity(0.82) : AppTheme.hairline.opacity(0.72)
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(isSelected ? AppTheme.primaryText : AppTheme.secondaryText)
                .lineLimit(2)
                .minimumScaleFactor(0.88)
                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                .padding(.horizontal, 16)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(isSelected ? AppTheme.elevatedCard.opacity(0.72) : AppTheme.card)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(borderColor, lineWidth: 1)
                )
        }
        .buttonStyle(InvestmentDecisionOptionButtonStyle())
        .padding(.horizontal, 1)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct InvestmentDecisionOptionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.975 : 1)
            .animation(.easeOut(duration: configuration.isPressed ? 0.08 : 0.16), value: configuration.isPressed)
    }
}

struct InvestmentDecisionInlineSummary: View {
    let record: InvestmentDecisionRecord?

    var body: some View {
        if let record {
            Label(record.inlineSummary, systemImage: "checklist")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(investmentDecisionStatusColor(record.status))
                .lineLimit(1)
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(investmentDecisionStatusColor(record.status).opacity(0.10), in: Capsule())
        }
    }
}

private struct InvestmentDecisionQualityPill: View {
    let percent: Int
    let label: String

    private var color: Color {
        if percent >= 80 { return AppTheme.quality }
        if percent >= 40 { return AppTheme.accent }
        return AppTheme.warning
    }

    var body: some View {
        Text("\(percent)% · \(label)")
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(color.opacity(0.10), in: Capsule())
    }
}

private struct InvestmentDecisionHintRow: View {
    let label: String
    let value: String

    var body: some View {
        if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            HStack(alignment: .top, spacing: 8) {
                Circle()
                    .fill(AppTheme.accent.opacity(0.55))
                    .frame(width: 7, height: 7)
                    .padding(.top, 5)
                Text(label)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(width: 64, alignment: .leading)
                Text(value)
                    .font(.caption)
                    .foregroundStyle(AppTheme.primaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private func investmentDecisionStatusColor(_ status: String?) -> Color {
    switch status {
    case "관심 유지":
        return AppTheme.quality
    case "보류", "추가 확인 필요", "실적 후 재검토":
        return AppTheme.warning
    case "제외", "내 성향과 맞지 않음":
        return AppTheme.negative
    default:
        return AppTheme.accent
    }
}

private func suggestedDecisionReasons(metrics: [StaticMetric], signals: [InvestmentSignal], info: StockInfo?) -> [String] {
    let text = (metrics.map { "\($0.label) \($0.value)" } + signals.map { "\($0.title) \($0.detail)" }).joined(separator: " ").lowercased()
    var result: [String] = []
    if text.contains("성장") || text.contains("growth") || (info?.revenueGrowth ?? 0) > 0.10 { result.append("장기 성장") }
    if text.contains("저평가") || text.contains("per") || text.contains("pbr") || text.contains("value") { result.append("저평가") }
    if text.contains("실적") || text.contains("earnings") || text.contains("surprise") { result.append("실적 개선") }
    if text.contains("배당") || text.contains("dividend") { result.append("배당/안정성") }
    if text.contains("모멘텀") || text.contains("거래량") || text.contains("momentum") { result.append("단기 모멘텀") }
    result.append("공부 필요")
    return orderedUnique(result)
}

private func suggestedDecisionCounterEvidence(
    profile: InvestmentProfile,
    info: StockInfo?,
    metrics: [StaticMetric],
    signals: [InvestmentSignal]
) -> [String] {
    let text = (metrics.map { "\($0.label) \($0.value)" } + signals.map { "\($0.title) \($0.detail)" }).joined(separator: " ")
    var result: [String] = []
    if profile.riskTolerance == "보수적", (info?.beta ?? 0) > 1.1 { result.append("내 성향보다 변동성이 큼") }
    if (info?.beta ?? 0) >= 1.3 { result.append("시장 대비 변동성 높음") }
    if (info?.revenueGrowth ?? 0) < 0 { result.append("매출 성장 둔화") }
    if (info?.debtToEquity ?? 0) > 150 { result.append("부채 부담 확인 필요") }
    if (info?.peRatio ?? 0) > 45 { result.append("밸류에이션 부담") }
    if text.localizedCaseInsensitiveContains("실적") || text.localizedCaseInsensitiveContains("earnings") { result.append("실적 전 불확실성") }
    if text.localizedCaseInsensitiveContains("급등") || text.localizedCaseInsensitiveContains("거래량") { result.append("단기 과열 가능성") }
    if isPriceNearHigh(info) { result.append("52주 고점 근처") }
    result.append("비교 후보 없이 단독 판단 위험")
    return orderedUnique(result)
}

private func defaultDecisionCounterEvidenceOptions() -> [String] {
    ["내 성향보다 변동성이 큼", "실적 전 불확실성", "단기 과열 가능성", "데이터 근거 부족", "비교 후보 없이 단독 판단 위험"]
}

private func decisionFitLabel(profile: InvestmentProfile, info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal]) -> String {
    if !profile.isConfigured {
        return "투자 성향을 저장하면 내 기준 적합도를 더 정확히 볼 수 있습니다."
    }
    return personalizedStockInterpretation(
        profile: profile,
        name: info?.name ?? "이 종목",
        info: info,
        metrics: metrics,
        signals: signals
    ).decisionLine
}

private func suggestedDecisionCondition(info: StockInfo?, metrics: [StaticMetric], signals: [InvestmentSignal]) -> String {
    let text = (metrics.map { "\($0.label) \($0.value)" } + signals.map { "\($0.title) \($0.detail)" }).joined(separator: " ")
    if text.localizedCaseInsensitiveContains("실적") || text.localizedCaseInsensitiveContains("earnings") {
        return "실적 발표 후 매출 성장과 마진이 같이 유지되는지 확인"
    }
    if (info?.revenueGrowth ?? 0) < 0 {
        return "매출 성장률이 회복되는지 확인"
    }
    if isPriceNearHigh(info) {
        return "가격이 조정된 뒤에도 점수와 거래량이 유지되는지 확인"
    }
    return "비슷한 후보와 비교한 뒤 점수, 리스크, 가격 위치가 모두 납득될 때 재검토"
}

private func isPriceNearHigh(_ info: StockInfo?) -> Bool {
    guard let current = info?.currentPrice,
          let low = info?.week52Low,
          let high = info?.week52High,
          current.isFinite,
          low.isFinite,
          high.isFinite,
          high > low else {
        return false
    }
    return abs((high - current) / (high - low)) < 0.15
}

private func orderedUnique(_ values: [String]) -> [String] {
    var seen = Set<String>()
    return values.filter { seen.insert($0).inserted }
}

struct LoadingOverlay: View {
    let isVisible: Bool

    var body: some View {
        if isVisible {
            RefreshingStatusPill()
                .padding(.top, 8)
        }
    }
}

struct RefreshingStatusPill: View {
    var label = "갱신 중"

    var body: some View {
        HStack(spacing: 7) {
            ProgressView()
                .controlSize(.small)
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
    }
}

struct SkeletonLoadingCard: View {
    var titleWidth: CGFloat = 132
    var lineCount = 3

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                SkeletonBlock(width: 36, height: 36, cornerRadius: 18)
                VStack(alignment: .leading, spacing: 7) {
                    SkeletonBlock(width: titleWidth, height: 14)
                    SkeletonBlock(width: titleWidth * 0.72, height: 10)
                }
            }

            ForEach(0..<lineCount, id: \.self) { index in
                SkeletonBlock(width: index == lineCount - 1 ? 160 : nil, height: 12)
            }
        }
        .padding(14)
        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(AppTheme.hairline, lineWidth: 0.5)
        )
    }
}

struct SkeletonBlock: View {
    var width: CGFloat?
    let height: CGFloat
    var cornerRadius: CGFloat = 5

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(AppTheme.elevatedCard)
            .frame(width: width, height: height)
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(
                        LinearGradient(
                            colors: [
                                Color.white.opacity(0.02),
                                Color.white.opacity(0.20),
                                Color.white.opacity(0.02)
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
            )
    }
}
