import LocalAuthentication
import SwiftUI

struct AccountView: View {
    @EnvironmentObject private var auth: AuthSessionStore
    @EnvironmentObject private var watchlist: WatchlistStore
    @EnvironmentObject private var notifications: NotificationStore
    @EnvironmentObject private var comparison: ComparisonStore
    @StateObject private var headerIndices = MarketIndicesVM()
    @StateObject private var headerMarketIndicators = MarketIndicatorsVM()
    @State private var email = ""
    @State private var password = ""
    @State private var displayName = ""
    @State private var isSignUpMode = false
    @State private var showDeleteConfirm = false
    @State private var passwordVisible = false
    @State private var successMessage: String?
    @State private var showHeaderSearch = false
    @State private var showHeaderMarketIndicators = false
    @State private var investmentProfile = InvestmentProfile.load()
    @State private var showInvestmentProfileSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let user = auth.user {
                        signedInContent(user)
                    } else {
                        signedOutContent
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 16)
            }
            .appTabBarInset()
            .scrollContentBackground(.hidden)
            .appScreenBackground()
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                QubitScreenTopHeader(
                    title: "계정",
                    indices: headerIndices.indices,
                    openSearch: { showHeaderSearch = true },
                    openMarketIndicators: { showHeaderMarketIndicators = true }
                )
            }
            .task {
                await headerIndices.load()
            }
            .task {
                await notifications.refreshAuthorizationStatus()
            }
            .sheet(isPresented: $showHeaderSearch) {
                ExploreView(showsAdvancedModes: false)
                    .environmentObject(watchlist)
                    .environmentObject(comparison)
            }
            .navigationDestination(isPresented: $showHeaderMarketIndicators) {
                MarketIndicatorsScreen(vm: headerMarketIndicators)
            }
            .sheet(isPresented: $showInvestmentProfileSheet) {
                InvestmentProfileSheet(profile: investmentProfile) { updated in
                    investmentProfile = updated.normalized
                    investmentProfile.save()
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
    }

    private func signedInContent(_ user: AuthUser) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            AccountProfileCard(
                user: user,
                watchlistCount: watchlist.items.count,
                syncText: watchlist.syncStatus.message ?? "정상"
            )

            AccountSettingsCard(
                watchlistCount: watchlist.items.count,
                syncText: watchlist.syncStatus.message ?? "정상",
                appVersion: appVersionText()
            )

            InvestmentProfileCard(profile: investmentProfile) {
                showInvestmentProfileSheet = true
            }

            notificationSettingsCard

            AccountSecurityCard()

            AccountManagementCard(
                logout: {
                    clearFeedback()
                    Task {
                        await auth.logout()
                        watchlist.disconnect()
                    }
                },
                deleteAccount: {
                    showDeleteConfirm = true
                }
            )
        }
        .confirmationDialog("계정을 삭제할까요?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("계정과 Watchlist 삭제", role: .destructive) {
                Task {
                    if await auth.deleteAccount() {
                        watchlist.disconnect(clearLocal: true)
                    }
                }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("서버에 저장된 계정 정보와 관심 종목이 삭제됩니다.")
        }
    }

    private var signedOutContent: some View {
        VStack(alignment: .leading, spacing: 26) {
            VStack(alignment: .leading, spacing: 18) {
                Text(isSignUpMode ? "계정 만들기" : "로그인")
                    .font(.system(size: 30, weight: .black))
                    .foregroundStyle(AppTheme.primaryText)
                Text("관심 종목과 설정을 사용자별로 저장합니다.")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)

                Spacer(minLength: 4)

                if isSignUpMode {
                    AccountPillTextField(
                        placeholder: "이름",
                        text: $displayName,
                        keyboardType: .default,
                        textContentType: .name
                    )
                        .onChange(of: displayName) { _, _ in clearFeedback() }
                }

                AccountPillTextField(
                    placeholder: "이메일",
                    text: $email,
                    keyboardType: .emailAddress,
                    textContentType: .emailAddress
                )
                    .onChange(of: email) { _, _ in clearFeedback() }

                PasswordField(
                    password: $password,
                    isVisible: $passwordVisible,
                    textContentType: isSignUpMode ? .newPassword : .password
                )
                .onChange(of: password) { _, _ in clearFeedback() }

                if let validationHint {
                    HStack(spacing: 7) {
                        LucideIconView(icon: .triangleAlert, size: 14)
                            .foregroundStyle(AppTheme.warning)
                        Text(validationHint)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(AppTheme.secondaryText)
                            .lineSpacing(3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }

                if let error = auth.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                if let successMessage {
                    Label(successMessage, systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button {
                    Task { await submit() }
                } label: {
                    if auth.isLoading {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("계정 확인 중")
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        Text(isSignUpMode ? "가입하기" : "로그인")
                            .font(.system(size: 17, weight: .black))
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.plain)
                .foregroundStyle(canSubmit ? Color.white : AppTheme.secondaryText)
                .frame(height: 58)
                .background(
                    Capsule()
                        .fill(canSubmit ? AppTheme.accent : AccountAuthStyle.inputFill)
                )
                .disabled(auth.isLoading || !canSubmit)
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 34, style: .continuous)
                    .fill(AppTheme.card)
                    .shadow(color: AppTheme.accent.opacity(0.10), radius: 18, x: 0, y: 8)
            )

            Button {
                isSignUpMode.toggle()
                clearFeedback()
            } label: {
                Text(isSignUpMode ? "이미 계정이 있어요" : "새 계정 만들기")
                    .font(.system(size: 18, weight: .black))
                    .foregroundStyle(AccountAuthStyle.createText)
                    .frame(maxWidth: .infinity)
                    .frame(height: 64)
                    .background(Capsule().fill(AccountAuthStyle.createFill))
            }
            .buttonStyle(.plain)

            InvestmentProfileCard(profile: investmentProfile) {
                showInvestmentProfileSheet = true
            }

            notificationSettingsCard
        }
    }

    private func clearFeedback() {
        auth.errorMessage = nil
        successMessage = nil
    }

    private var notificationSettingsCard: some View {
        NotificationSettingsCard(
            title: notifications.statusTitle,
            detail: notifications.statusDetail,
            isEnabled: notifications.isEnabled,
            canRequest: notifications.canRequestAuthorization,
            toggle: toggleNotifications
        )
    }

    private func toggleNotifications() {
        if notifications.isEnabled {
            notifications.disable()
        } else {
            Task {
                await notifications.requestAuthorization()
            }
        }
    }

    private var canSubmit: Bool {
        isEmailValid && password.count >= 8 && (!isSignUpMode || !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private var isEmailValid: Bool {
        let clean = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.contains("@") && clean.contains(".") && clean.count >= 5
    }

    private var validationHint: String? {
        let hasStarted = !email.isEmpty || !password.isEmpty || !displayName.isEmpty
        guard hasStarted, !canSubmit else { return nil }
        if isSignUpMode && displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "이름을 입력하면 새 계정을 만들 수 있어요."
        }
        if !email.isEmpty && !isEmailValid {
            return "이메일 형식을 확인해주세요."
        }
        if !password.isEmpty && password.count < 8 {
            return "비밀번호는 8자 이상이어야 해요."
        }
        return nil
    }

    private func submit() async {
        successMessage = nil
        let success: Bool
        if isSignUpMode {
            success = await auth.signUp(email: email, password: password, displayName: displayName)
        } else {
            success = await auth.login(email: email, password: password)
        }
        if success {
            password = ""
            await watchlist.connect(token: auth.token)
            successMessage = "로그인과 Watchlist 동기화가 완료됐습니다."
        }
    }
}

private func appVersionText() -> String {
    let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
    switch (version, build) {
    case let (version?, build?) where !version.isEmpty && !build.isEmpty:
        return "\(version) (\(build))"
    case let (version?, _) where !version.isEmpty:
        return version
    default:
        return "Debug"
    }
}

private struct AccountProfileCard: View {
    let user: AuthUser
    let watchlistCount: Int
    let syncText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Circle()
                    .fill(AppTheme.accent.opacity(0.12))
                    .frame(width: 52, height: 52)
                    .overlay(
                        Text(String(user.displayName.prefix(1)).uppercased())
                            .font(.title3.weight(.black))
                            .foregroundStyle(AppTheme.accent)
                    )

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(user.displayName)
                            .font(.headline.weight(.bold))
                            .foregroundStyle(AppTheme.primaryText)
                        Text("로그인됨")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(AppTheme.accent)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(AppTheme.accent.opacity(0.10), in: Capsule())
                    }
                    Text(user.email)
                        .font(.subheadline)
                        .foregroundStyle(AppTheme.secondaryText)
                        .lineLimit(1)
                }
                Spacer()
            }

            HStack(spacing: 10) {
                AccountMiniMetric(title: "관심", value: "\(watchlistCount)개")
                AccountMiniMetric(title: "동기화", value: syncText)
            }
        }
        .appCard()
    }
}

private struct AccountMiniMetric: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(AppTheme.elevatedCard.opacity(0.74), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct AccountSettingsCard: View {
    let watchlistCount: Int
    let syncText: String
    let appVersion: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("내 설정")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)

            AccountSettingRow(
                icon: .heart,
                title: "관심 종목",
                detail: "홈 브리핑과 관심 탭에 반영됩니다.",
                value: "\(watchlistCount)개"
            )
            AccountSettingRow(
                icon: .layoutDashboard,
                title: "홈 브리핑",
                detail: "관심 종목, 시장 뉴스, 실적 이벤트를 우선 보여줍니다.",
                value: "자동"
            )
            AccountSettingRow(
                icon: .refreshCw,
                title: "데이터 동기화",
                detail: "로그인한 기기에서 관심 종목을 이어서 볼 수 있습니다.",
                value: syncText
            )
            AccountSettingRow(
                icon: .lightbulb,
                title: "앱 정보",
                detail: "현재 설치된 큐빗 버전입니다.",
                value: appVersion
            )
        }
        .appCard()
    }
}

private struct InvestmentProfileCard: View {
    let profile: InvestmentProfile
    let edit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                LucideIconView(icon: .slidersHorizontal, size: 18)
                    .foregroundStyle(AppTheme.accent)
                    .frame(width: 34, height: 34)
                    .background(AppTheme.accent.opacity(0.10), in: Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text("내 투자 기준")
                        .font(.headline.weight(.bold))
                        .foregroundStyle(AppTheme.primaryText)
                    Text(profile.summary)
                        .font(.caption)
                        .foregroundStyle(AppTheme.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 8)

                Button(action: edit) {
                    Text(profile.isConfigured ? "수정" : "진단")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AppTheme.accent)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(AppTheme.accent.opacity(0.10), in: Capsule())
                }
                .buttonStyle(.plain)
            }

            if profile.isConfigured {
                VStack(alignment: .leading, spacing: 8) {
                    InvestmentProfilePill(label: profile.headline, icon: .target)
                    InvestmentProfilePill(label: profile.guardrailSummary, icon: .shieldCheck)
                }
            } else {
                InvestmentProfilePill(label: "계좌 연결 없이도 나만의 판단 기준을 먼저 세웁니다.", icon: .lightbulb)
            }
        }
        .appCard()
    }
}

private struct InvestmentProfileSheet: View {
    let onSave: (InvestmentProfile) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var draft: InvestmentProfile
    @State private var currentStep = InvestmentProfileStep.experience

    private let experienceOptions = ["처음 시작", "기본 분석 가능", "숙련"]
    private let horizonOptions = ["1개월", "3개월", "6개월", "1년+"]
    private let riskOptions = ["보수적", "균형", "성장", "공격적"]
    private let styleOptions = ["성장주", "가치주", "배당", "퀄리티", "모멘텀"]
    private let avoidanceOptions = ["급등락", "적자 지속", "고평가", "높은 부채", "낮은 거래량"]

    init(profile: InvestmentProfile, onSave: @escaping (InvestmentProfile) -> Void) {
        self.onSave = onSave
        _draft = State(initialValue: profile.normalized)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                investmentProfileSheetHeader
                    .padding(.horizontal, 20)
                    .padding(.top, 18)

                InvestmentProfileWizardHeader(
                    step: currentStep,
                    currentIndex: currentStep.rawValue + 1,
                    totalCount: InvestmentProfileStep.allCases.count
                )
                .padding(.horizontal, 20)
                .padding(.top, 18)

                ScrollView {
                    stepContent
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                }
                .scrollIndicators(.hidden)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(.horizontal, 20)
                .padding(.top, 26)

                InvestmentProfileWizardFooter(
                    canGoBack: currentStep != .experience,
                    primaryTitle: currentStep == .summary ? "저장" : "다음",
                    cancel: { dismiss() },
                    goBack: movePrevious,
                    goNext: moveNextOrSave
                )
                .padding(.horizontal, 20)
                .padding(.bottom, 18)
            }
            .appScreenBackground()
            .navigationTitle("내 투자 기준")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
            }
        }
    }

    private var investmentProfileSheetHeader: some View {
        HStack(spacing: 10) {
            LucideIconView(icon: .target, size: 20)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 42, height: 42)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text("내 투자 기준")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text("후보를 보기 전에 내 판단 규칙을 먼저 정합니다.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
            }
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch currentStep {
        case .experience:
            InvestmentProfileOptionList(options: experienceOptions, selected: $draft.experience)
        case .horizon:
            InvestmentProfileOptionList(options: horizonOptions, selected: $draft.horizon)
        case .risk:
            InvestmentProfileOptionList(options: riskOptions, selected: $draft.riskTolerance)
        case .style:
            InvestmentProfileOptionList(options: styleOptions, selected: $draft.style)
        case .avoidances:
            InvestmentProfileMultiOptionList(options: avoidanceOptions, selected: $draft.avoidances)
        case .summary:
            InvestmentProfileSummaryPanel(profile: draft.normalized)
        }
    }

    private func movePrevious() {
        guard let previous = InvestmentProfileStep(rawValue: currentStep.rawValue - 1) else { return }
        currentStep = previous
    }

    private func moveNextOrSave() {
        if currentStep == .summary {
            onSave(draft.normalized)
            dismiss()
            return
        }
        guard let next = InvestmentProfileStep(rawValue: currentStep.rawValue + 1) else { return }
        currentStep = next
    }
}

private enum InvestmentProfileStep: Int, CaseIterable {
    case experience
    case horizon
    case risk
    case style
    case avoidances
    case summary

    var title: String {
        switch self {
        case .experience:
            return "투자 경험은 어느 정도인가요?"
        case .horizon:
            return "얼마 동안 지켜볼 생각인가요?"
        case .risk:
            return "변동성은 어디까지 괜찮나요?"
        case .style:
            return "끌리는 투자 스타일은 무엇인가요?"
        case .avoidances:
            return "피하고 싶은 신호가 있나요?"
        case .summary:
            return "이 기준으로 저장할까요?"
        }
    }

    var subtitle: String {
        switch self {
        case .experience:
            return "설명 깊이와 리스크 문구의 톤을 맞추는 기준입니다."
        case .horizon:
            return "관심 종목을 단기 신호로 볼지, 긴 흐름으로 볼지 나눕니다."
        case .risk:
            return "같은 랭킹이라도 내 기준에 맞는 후보를 더 차분히 보게 합니다."
        case .style:
            return "성장, 가치, 배당처럼 먼저 보고 싶은 관점을 정합니다."
        case .avoidances:
            return "여러 개를 골라도 괜찮습니다."
        case .summary:
            return "저장된 기준은 이 기기에 보관되며 후보를 볼 때 함께 확인할 개인 기준입니다."
        }
    }
}

private struct InvestmentProfileWizardHeader: View {
    let step: InvestmentProfileStep
    let currentIndex: Int
    let totalCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 5) {
                ForEach(0..<totalCount, id: \.self) { index in
                    Capsule()
                        .fill(index < currentIndex ? AppTheme.accent : AppTheme.hairline)
                        .frame(height: 4)
                }
            }
            Text("\(currentIndex) / \(totalCount)")
                .font(.caption2.weight(.bold))
                .foregroundStyle(AppTheme.secondaryText)
            Text(step.title)
                .font(.title2.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            Text(step.subtitle)
                .font(.caption)
                .foregroundStyle(AppTheme.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct InvestmentProfileWizardFooter: View {
    let canGoBack: Bool
    let primaryTitle: String
    let cancel: () -> Void
    let goBack: () -> Void
    let goNext: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button("취소") { cancel() }
                .buttonStyle(.bordered)
            Spacer()
            if canGoBack {
                Button("이전") { goBack() }
                    .buttonStyle(.bordered)
            }
            Button(action: goNext) {
                Text(primaryTitle)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.accent)
        }
    }
}

private struct InvestmentProfilePill: View {
    let label: String
    let icon: LucideIcon

    var body: some View {
        HStack(spacing: 7) {
            LucideIconView(icon: icon, size: 14)
                .foregroundStyle(AppTheme.accent)
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(AppTheme.elevatedCard.opacity(0.74), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct InvestmentProfileOptionList: View {
    let options: [String]
    @Binding var selected: String

    var body: some View {
        VStack(spacing: 10) {
            ForEach(options, id: \.self) { option in
                InvestmentProfileChoiceRow(
                    label: option,
                    selected: selected == option
                ) {
                    selected = selected == option ? "" : option
                }
            }
        }
    }
}

private struct InvestmentProfileMultiOptionList: View {
    let options: [String]
    @Binding var selected: [String]

    var body: some View {
        VStack(spacing: 10) {
            ForEach(options, id: \.self) { option in
                InvestmentProfileChoiceRow(
                    label: option,
                    selected: selected.contains(option)
                ) {
                    if selected.contains(option) {
                        selected.removeAll { $0 == option }
                    } else {
                        selected.append(option)
                    }
                }
            }
        }
    }
}

private struct InvestmentProfileChoiceRow: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(selected ? AppTheme.primaryText : AppTheme.secondaryText)
                .lineLimit(2)
                .minimumScaleFactor(0.88)
                .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                .padding(.horizontal, 16)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(selected ? AppTheme.elevatedCard.opacity(0.72) : AppTheme.card)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(selected ? AppTheme.secondaryText.opacity(0.82) : AppTheme.hairline.opacity(0.72), lineWidth: 1)
                )
        }
        .buttonStyle(InvestmentProfileOptionButtonStyle())
        .padding(.horizontal, 1)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct InvestmentProfileOptionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.975 : 1)
            .animation(.easeOut(duration: configuration.isPressed ? 0.08 : 0.16), value: configuration.isPressed)
    }
}

private struct InvestmentProfileSummaryPanel: View {
    let profile: InvestmentProfile

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            InvestmentProfileSummaryRow(title: "투자 경험", value: display(profile.experience))
            InvestmentProfileSummaryRow(title: "투자 기간", value: display(profile.horizon))
            InvestmentProfileSummaryRow(title: "위험 선호", value: display(profile.riskTolerance))
            InvestmentProfileSummaryRow(title: "선호 스타일", value: display(profile.style))
            InvestmentProfileSummaryRow(
                title: "피하고 싶은 신호",
                value: profile.avoidances.isEmpty ? "선택 안 함" : profile.avoidances.joined(separator: " · ")
            )
        }
        .padding(14)
        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(AppTheme.hairline, lineWidth: 0.6)
        )
    }

    private func display(_ value: String) -> String {
        value.isEmpty ? "선택 안 함" : value
    }
}

private struct InvestmentProfileSummaryRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.secondaryText)
                .frame(width: 92, alignment: .leading)
            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
}

private struct AccountSettingRow: View {
    let icon: LucideIcon
    let title: String
    let detail: String
    let value: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            LucideIconView(icon: icon, size: 17)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 34, height: 34)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Text(value)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.accent)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
    }
}

private struct NotificationSettingsCard: View {
    let title: String
    let detail: String
    let isEnabled: Bool
    let canRequest: Bool
    let toggle: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            LucideIconView(icon: .bell, size: 18)
                .foregroundStyle(isEnabled ? AppTheme.warning : AppTheme.accent)
                .frame(width: 34, height: 34)
                .background((isEnabled ? AppTheme.warning : AppTheme.accent).opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
                Button(isEnabled ? "관심 알림 끄기" : "관심 알림 켜기", action: toggle)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.bordered)
                    .tint(isEnabled ? AppTheme.secondaryText : AppTheme.accent)
                    .disabled(!isEnabled && !canRequest)
                    .padding(.top, 2)
            }
            Spacer(minLength: 0)
        }
        .appCard()
    }
}

private struct AccountSecurityCard: View {
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            LucideIconView(icon: .shieldCheck, size: 18)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 34, height: 34)
                .background(AppTheme.accent.opacity(0.10), in: Circle())

            VStack(alignment: .leading, spacing: 5) {
                Text("기기 보안")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text("화면 잠금과 생체 인증을 켜두면 로그인 상태와 개인 설정을 더 안전하게 보호할 수 있습니다.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .appCard()
    }
}

private struct PasswordField: View {
    @Binding var password: String
    @Binding var isVisible: Bool
    let textContentType: UITextContentType

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if isVisible {
                    TextField("비밀번호", text: $password)
                } else {
                    SecureField("비밀번호", text: $password)
                }
            }
            .textContentType(textContentType)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(AppTheme.primaryText)

            Button {
                isVisible.toggle()
            } label: {
                LucideIconView(icon: .eye, size: 24)
                    .foregroundStyle(AppTheme.secondaryText)
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isVisible ? "비밀번호 숨기기" : "비밀번호 보기")
        }
        .padding(.leading, 22)
        .padding(.trailing, 12)
        .frame(height: 58)
        .background(
            Capsule()
                .fill(AccountAuthStyle.inputFill)
        )
    }
}

private struct AccountPillTextField: View {
    let placeholder: String
    @Binding var text: String
    let keyboardType: UIKeyboardType
    let textContentType: UITextContentType

    var body: some View {
        TextField(placeholder, text: $text)
            .keyboardType(keyboardType)
            .textContentType(textContentType)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(AppTheme.primaryText)
            .padding(.horizontal, 22)
            .frame(height: 58)
            .background(Capsule().fill(AccountAuthStyle.inputFill))
    }
}

private enum AccountAuthStyle {
    static let inputFill = AppTheme.elevatedCard
    static let createFill = Color(red: 0.78, green: 0.92, blue: 1.00)
    static let createText = Color(red: 0.06, green: 0.16, blue: 0.23)
}

private struct DeviceSecurityStatus {
    let title: String
    let detail: String

    static var current: DeviceSecurityStatus {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            let title: String
            switch context.biometryType {
            case .faceID:
                title = "Face ID 사용 가능"
            case .touchID:
                title = "Touch ID 사용 가능"
            default:
                title = "생체 인증 사용 가능"
            }
            return DeviceSecurityStatus(
                title: title,
                detail: "기기 잠금을 사용하면 로그인 상태와 개인 설정을 더 안전하게 보호할 수 있습니다."
            )
        }
        return DeviceSecurityStatus(
            title: "생체 인증 미설정",
            detail: "기기 설정에서 Face ID 또는 Touch ID를 켜면 계정 보호가 더 쉬워집니다."
        )
    }
}

private struct SecurityStatusCard: View {
    let status: DeviceSecurityStatus

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            LucideIconView(icon: .shieldCheck, size: 18)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 34, height: 34)
                .background(AppTheme.accent.opacity(0.10), in: Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text(status.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                Text(status.detail)
                    .font(.caption)
                    .foregroundStyle(AppTheme.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .appCard()
    }
}

private struct AccountManagementCard: View {
    let logout: () -> Void
    let deleteAccount: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("계정 관리")
                .font(.headline.weight(.bold))
                .foregroundStyle(AppTheme.primaryText)

            Button(action: logout) {
                Text("로그아웃")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(AppTheme.elevatedCard, in: Capsule())
            }
            .buttonStyle(.plain)

            Button(role: .destructive, action: deleteAccount) {
                Text("계정 삭제")
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
            }
            .buttonStyle(.bordered)
        }
        .appCard()
    }
}
