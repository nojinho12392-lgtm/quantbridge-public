import SwiftUI
import UIKit

private enum RootTab: Hashable {
    case home
    case portfolio
    case pulse
    case watch
    case account

    var accessibilityLabel: String {
        switch self {
        case .home: return "홈 탭"
        case .portfolio: return "분석 탭"
        case .pulse: return "인사이트 탭"
        case .watch: return "관심 탭"
        case .account: return "계정 탭"
        }
    }

    var accessibilityIdentifier: String {
        switch self {
        case .home: return "tab_home"
        case .portfolio: return "tab_analysis"
        case .pulse: return "tab_insight"
        case .watch: return "tab_watch"
        case .account: return "tab_account"
        }
    }
}

struct ContentView: View {
    @StateObject private var auth = AuthSessionStore()
    @StateObject private var watchlist = WatchlistStore()
    @StateObject private var notifications = NotificationStore()
    @StateObject private var comparison = ComparisonStore()
    @StateObject private var decisions = InvestmentDecisionStore()
    @State private var selectedTab: RootTab = .home
    @State private var showSearch = false
    @State private var showStartupBranding = true

    private let rootTabs: [RootTab] = [.home, .portfolio, .pulse, .watch, .account]

    private var selectedTabBinding: Binding<RootTab> {
        Binding(
            get: { selectedTab },
            set: { selectTab($0) }
        )
    }

    var body: some View {
        ZStack {
            TabView(selection: selectedTabBinding) {
                HomeDashboardView(
                    openSearch: { presentSearch() },
                    openPortfolio: { selectTab(.portfolio) },
                    openPulse: { selectTab(.pulse) },
                    openWatch: { selectTab(.watch) },
                    openAccount: { selectTab(.account) }
                )
                    .tabItem {
                        Label("홈", systemImage: "square.grid.2x2")
                    }
                    .accessibilityLabel("홈 탭")
                    .accessibilityIdentifier("tab_home")
                    .tag(RootTab.home)

                PortfolioHomeView()
                    .tabItem {
                        Label("분석", systemImage: "chart.xyaxis.line")
                    }
                    .accessibilityLabel("분석 탭")
                    .accessibilityIdentifier("tab_analysis")
                    .tag(RootTab.portfolio)

                PulseTabView()
                    .tabItem {
                        Label("인사이트", systemImage: "lightbulb")
                    }
                    .accessibilityLabel("인사이트 탭")
                    .accessibilityIdentifier("tab_insight")
                    .tag(RootTab.pulse)

                WatchlistView(openSearch: { showSearch = true })
                    .tabItem {
                        Label("관심", systemImage: "heart")
                    }
                    .accessibilityLabel("관심 탭")
                    .accessibilityIdentifier("tab_watch")
                    .tag(RootTab.watch)

                AccountView()
                    .tabItem {
                        Label("계정", systemImage: "person")
                    }
                    .accessibilityLabel("계정 탭")
                    .accessibilityIdentifier("tab_account")
                    .tag(RootTab.account)
            }

            VStack {
                Spacer(minLength: 0)
                AccessibleTabBarOverlay(
                    tabs: rootTabs,
                    selectedTab: selectedTab,
                    selectTab: selectTab
                )
            }
            .ignoresSafeArea(.keyboard)

            if showStartupBranding {
                QubitStartupSplash()
                    .transition(.opacity)
            }
        }
        .environmentObject(auth)
        .environmentObject(watchlist)
        .environmentObject(notifications)
        .environmentObject(comparison)
        .environmentObject(decisions)
        .tint(AppTheme.accent)
        .sheet(isPresented: $showSearch) {
            ExploreView(showsAdvancedModes: false)
                .environmentObject(watchlist)
                .environmentObject(comparison)
                .environmentObject(decisions)
        }
        .sheet(isPresented: $comparison.isPresenting) {
            StockComparisonSheet(items: comparison.items)
                .presentationDetents([.fraction(0.8), .large])
                .presentationDragIndicator(.visible)
        }
        .task {
            let restoreOutcome = await auth.restore()
            if case .invalidated = restoreOutcome {
                watchlist.disconnect()
            }
            await watchlist.connect(token: auth.token)
            await notifications.refreshAuthorizationStatus()
        }
        .task {
            try? await Task.sleep(nanoseconds: 1_050_000_000)
            withAnimation(.easeOut(duration: 0.22)) {
                showStartupBranding = false
            }
        }
    }

    private func selectTab(_ tab: RootTab) {
        guard tab != selectedTab else { return }
        withAnimation(QuantMotion.route) {
            selectedTab = tab
        }
    }

    private func presentSearch() {
        withAnimation(QuantMotion.route) {
            showSearch = true
        }
    }
}

private struct AccessibleTabBarOverlay: View {
    let tabs: [RootTab]
    let selectedTab: RootTab
    let selectTab: (RootTab) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tabs, id: \.self) { tab in
                Button {
                    selectTab(tab)
                } label: {
                    Color.clear
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.accessibilityLabel)
                .accessibilityIdentifier(tab.accessibilityIdentifier)
                .accessibilityValue(selectedTab == tab ? "선택됨" : "선택 안 됨")
                .accessibilityAddTraits(selectedTab == tab ? [.isButton, .isSelected] : .isButton)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(height: 83)
        .background(Color.clear)
    }
}

private struct QubitStartupSplash: View {
    var body: some View {
        ZStack {
            Color.white
                .ignoresSafeArea()

            VStack(spacing: 18) {
                QubitStartupLogo()
                    .frame(width: 108, height: 108)

                Text("made by Jinho")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color(red: 0.36, green: 0.43, blue: 0.48))
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("큐빗 made by Jinho")
        }
    }
}

private struct QubitStartupLogo: View {
    var body: some View {
        if let image = Self.appIconImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(color: Color.black.opacity(0.10), radius: 14, y: 8)
        } else {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color(red: 0.03, green: 0.07, blue: 0.12))
                .overlay {
                    Text("QB")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(AppTheme.accent)
                }
                .shadow(color: Color.black.opacity(0.10), radius: 14, y: 8)
        }
    }

    private static var appIconImage: UIImage? {
        if let image = UIImage(named: "AppIcon") {
            return image
        }
        guard
            let icons = Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any],
            let primary = icons["CFBundlePrimaryIcon"] as? [String: Any],
            let files = primary["CFBundleIconFiles"] as? [String]
        else {
            return nil
        }
        return files.reversed().compactMap { UIImage(named: $0) }.first
    }
}

#Preview {
    ContentView()
}
