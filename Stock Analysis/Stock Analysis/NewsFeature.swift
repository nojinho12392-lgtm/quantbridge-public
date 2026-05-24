import Combine
import SwiftUI
import WebKit

struct NewsResponse: Decodable {
    let query: String
    let count: Int
    let configured: Bool
    let items: [NewsItem]
}

struct NewsItem: Decodable, Identifiable, Hashable {
    let id: String
    let title: String
    let summary: String
    let source: String
    let url: String
    let imageUrl: String
    let publishedAt: String
    let market: String
    let ticker: String
    let kind: String
    let impactLabel: String
    let impactLabelKo: String
    let impactScore: Double
    let impactReason: String
    let impactScope: String
    let impactHorizon: String
    let impactConfidence: String
    let relatedTickers: [String]
    let relatedChangePct: Double?
    let relatedChangeLabel: String
    let relatedChangeHorizon: String

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case summary
        case source
        case url
        case imageUrl = "image_url"
        case urlToImage
        case image
        case thumbnail
        case thumbnailUrl = "thumbnail_url"
        case publishedAt = "published_at"
        case market
        case ticker
        case kind
        case impactLabel = "impact_label"
        case impactLabelKo = "impact_label_ko"
        case impactScore = "impact_score"
        case impactReason = "impact_reason"
        case impactScope = "impact_scope"
        case impactHorizon = "impact_horizon"
        case impactConfidence = "impact_confidence"
        case relatedTickers = "related_tickers"
        case relatedChangePct = "related_change_pct"
        case relatedChangeLabel = "related_change_label"
        case relatedChangeHorizon = "related_change_horizon"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        summary = try container.decodeIfPresent(String.self, forKey: .summary) ?? ""
        source = try container.decodeIfPresent(String.self, forKey: .source) ?? "-"
        url = try container.decodeIfPresent(String.self, forKey: .url) ?? ""
        imageUrl = try container.decodeFirstString(for: [.imageUrl, .urlToImage, .image, .thumbnail, .thumbnailUrl]) ?? ""
        publishedAt = try container.decodeIfPresent(String.self, forKey: .publishedAt) ?? ""
        market = try container.decodeIfPresent(String.self, forKey: .market) ?? ""
        ticker = try container.decodeIfPresent(String.self, forKey: .ticker) ?? ""
        kind = try container.decodeIfPresent(String.self, forKey: .kind) ?? "news"
        impactLabel = try container.decodeIfPresent(String.self, forKey: .impactLabel) ?? "neutral"
        impactLabelKo = try container.decodeIfPresent(String.self, forKey: .impactLabelKo) ?? newsImpactFallbackLabel(impactLabel)
        impactScore = try container.decodeIfPresent(Double.self, forKey: .impactScore) ?? 0
        impactReason = try container.decodeIfPresent(String.self, forKey: .impactReason) ?? ""
        impactScope = try container.decodeIfPresent(String.self, forKey: .impactScope) ?? "general"
        impactHorizon = try container.decodeIfPresent(String.self, forKey: .impactHorizon) ?? "단기"
        impactConfidence = try container.decodeIfPresent(String.self, forKey: .impactConfidence) ?? "low"
        relatedTickers = try container.decodeIfPresent([String].self, forKey: .relatedTickers) ?? []
        relatedChangePct = try container.decodeIfPresent(Double.self, forKey: .relatedChangePct)
        relatedChangeLabel = try container.decodeIfPresent(String.self, forKey: .relatedChangeLabel) ?? ""
        relatedChangeHorizon = try container.decodeIfPresent(String.self, forKey: .relatedChangeHorizon) ?? ""
    }
}

private extension KeyedDecodingContainer where K == NewsItem.CodingKeys {
    func decodeFirstString(for keys: [NewsItem.CodingKeys]) throws -> String? {
        for key in keys {
            if let value = try decodeIfPresent(String.self, forKey: key)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !value.isEmpty {
                return value
            }
        }
        return nil
    }
}

extension APIClientProtocol {
    func fetchNews(query: String, market: MarketScope, limit: Int = 40) async throws -> NewsResponse {
        try await fetch(
            ["news", "issues"],
            queryItems: [
                URLQueryItem(name: "q", value: query),
                URLQueryItem(name: "market", value: market.rawValue),
                URLQueryItem(name: "limit", value: "\(limit)")
            ]
        )
    }
}

@MainActor
final class NewsVM: ObservableObject {
    @Published var query = ""
    @Published var configured = false
    @Published var items: [NewsItem] = []
    @Published var isLoading = false
    @Published var error: String?

    private var didLoad = false
    private let api: APIClientProtocol

    init(api: APIClientProtocol = APIClient.shared) {
        self.api = api
    }

    func load() async {
        guard !isLoading else { return }
        guard !didLoad || items.isEmpty || error != nil else { return }
        didLoad = true
        await refresh(query: "", market: .all)
    }

    func refresh(query: String, market: MarketScope) async {
        isLoading = true
        error = nil
        defer { isLoading = false }

        do {
            let requestQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
            let response = try await api.fetchNews(
                query: requestQuery.isEmpty ? market.defaultNewsQuery : requestQuery,
                market: market
            )
            self.query = response.query
            configured = response.configured
            items = response.items.filter { market.acceptsNewsItem($0) }
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct NewsTabView: View {
    let navigationTitle: String
    let navigationDisplayMode: NavigationBarItem.TitleDisplayMode
    let usesInlineSearch: Bool
    let showsControls: Bool
    let showsSummary: Bool
    let usesImpactFeed: Bool
    let bottomInset: CGFloat

    @StateObject private var vm = NewsVM()
    @State private var market: MarketScope = .all
    @State private var query = ""
    @State private var lastAutoSearchKey = ""
    @State private var selectedArticle: NewsItem?

    init(
        navigationTitle: String = "뉴스",
        navigationDisplayMode: NavigationBarItem.TitleDisplayMode = .large,
        usesInlineSearch: Bool = false,
        showsControls: Bool = true,
        showsSummary: Bool = true,
        usesImpactFeed: Bool = true,
        bottomInset: CGFloat = 16
    ) {
        self.navigationTitle = navigationTitle
        self.navigationDisplayMode = navigationDisplayMode
        self.usesInlineSearch = usesInlineSearch
        self.showsControls = showsControls
        self.showsSummary = showsSummary
        self.usesImpactFeed = usesImpactFeed
        self.bottomInset = bottomInset
    }

    var body: some View {
        if usesInlineSearch {
            content
        } else {
            content
                .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "종목명, 지수, 키워드 검색")
        }
    }

    private var content: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                if showsControls {
                    controlsSection
                }

                if let error = vm.error {
                    ErrView(msg: error, retry: refresh)
                        .padding(14)
                        .frame(maxWidth: .infinity)
                        .background(AppTheme.card, in: RoundedRectangle(cornerRadius: AppTheme.cardRadius, style: .continuous))
                }

                if showsSummary {
                    NewsSummaryCard(
                        count: vm.items.count,
                        market: market.rawValue,
                        isLoading: vm.isLoading
                    )
                    .padding(16)
                    .background(AppTheme.card, in: RoundedRectangle(cornerRadius: AppTheme.cardRadius, style: .continuous))
                }

                if vm.items.isEmpty {
                    EmptyMsg(
                        icon: "newspaper",
                        msg: "뉴스 없음",
                        detail: "표시할 외부 기사가 없습니다. 시장 선택이나 검색어를 바꿔보세요.",
                        actionTitle: "새로고침",
                        action: refresh
                    )
                    .padding(18)
                    .frame(maxWidth: .infinity)
                    .background(AppTheme.card, in: RoundedRectangle(cornerRadius: AppTheme.cardRadius, style: .continuous))
                } else {
                    ForEach(Array(vm.items.enumerated()), id: \.element.id) { index, item in
                        if usesImpactFeed {
                            NewsImpactFeedCard(item: item, featured: index == 0) {
                                open(item)
                            }
                        } else {
                            NewsListRow(item: item) {
                                open(item)
                            }
                            .padding(14)
                            .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, usesImpactFeed ? 10 : 12)
            .padding(.bottom, 24)
        }
        .appTabBarInset(bottomInset)
        .appScreenBackground()
        .ignoresSafeArea(.container, edges: .bottom)
        .navigationTitle(navigationTitle)
        .navigationBarTitleDisplayMode(navigationDisplayMode)
        .overlay(alignment: .top) {
            LoadingOverlay(isVisible: vm.isLoading && !vm.items.isEmpty)
        }
        .refreshable { await vm.refresh(query: query, market: market) }
        .onSubmit(of: .search) {
            submitNewsSearch()
        }
        .task { await vm.load() }
        .task(id: "\(market.rawValue):\(query)") {
            await debouncedNewsSearch()
        }
        .sheet(item: $selectedArticle) { item in
            NewsArticleSheet(item: item)
        }
    }

    private var controlsSection: some View {
        VStack(spacing: 8) {
            AppSegmentSwitch(options: MarketScope.allCases, selection: $market) { scope in
                scope.rawValue
            }
            .onChange(of: market) { _, newValue in
                lastAutoSearchKey = searchKey(for: newValue, query: normalizedSearchQuery)
                Task { await vm.refresh(query: query, market: newValue) }
            }

            if usesInlineSearch {
                HStack(spacing: 8) {
                    AppSearchField(text: $query, prompt: "종목명, 지수, 키워드 검색")
                    Button(action: submitNewsSearch) {
                        LucideIconView(icon: .search, size: 15)
                            .frame(width: 36, height: 36)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(AppTheme.accent)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(AppTheme.elevatedCard)
                    )
                    .accessibilityLabel("뉴스 검색")
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(AppTheme.card)
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(AppTheme.hairline.opacity(0.5), lineWidth: 0.6)
                )
        )
    }

    private func refresh() {
        Task { await vm.refresh(query: query, market: market) }
    }

    private var normalizedSearchQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func searchKey(for market: MarketScope, query: String) -> String {
        "\(market.rawValue):\(query)"
    }

    private func submitNewsSearch() {
        let searchQuery = normalizedSearchQuery
        lastAutoSearchKey = searchKey(for: market, query: searchQuery)
        Task { await vm.refresh(query: searchQuery, market: market) }
    }

    private func debouncedNewsSearch() async {
        let searchQuery = normalizedSearchQuery
        let key = searchKey(for: market, query: searchQuery)
        guard key != lastAutoSearchKey else { return }
        guard searchQuery.isEmpty || searchQuery.count >= 2 else { return }

        do {
            try await Task.sleep(nanoseconds: 400_000_000)
            try Task.checkCancellation()
        } catch {
            return
        }

        lastAutoSearchKey = key
        await vm.refresh(query: searchQuery, market: market)
    }

    private func open(_ item: NewsItem) {
        guard !item.url.isEmpty else { return }
        selectedArticle = item
    }
}

struct NewsArticleSheet: View {
    let item: NewsItem
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var showTimeoutHint = false

    private var articleURL: URL? {
        guard !item.url.isEmpty else { return nil }
        return URL(string: item.url)
    }

    private func scheduleTimeoutHint(for url: URL) {
        showTimeoutHint = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 6.5) {
            guard articleURL == url else { return }
            if isLoading && errorMessage == nil {
                showTimeoutHint = true
            }
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let url = articleURL {
                    ZStack {
                        NewsArticleWebView(url: url, isLoading: $isLoading, errorMessage: $errorMessage)
                            .appScreenBackground()

                        if let errorMessage {
                            EmptyMsg(
                                icon: "newspaper",
                                msg: "기사 로딩 실패",
                                detail: errorMessage,
                                actionTitle: "Safari로 열기",
                                action: { openURL(url) }
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .padding()
                            .appScreenBackground()
                        } else if isLoading {
                            VStack(spacing: 10) {
                                ProgressView()
                                Text(showTimeoutHint ? "로딩이 지연되고 있어요" : "기사 불러오는 중")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(AppTheme.secondaryText)
                                if showTimeoutHint {
                                    Button("Safari로 열기") { openURL(url) }
                                        .buttonStyle(.bordered)
                                        .tint(AppTheme.accent)
                                }
                            }
                            .padding(14)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .onAppear { scheduleTimeoutHint(for: url) }
                    .onChange(of: url.absoluteString) { _, _ in scheduleTimeoutHint(for: url) }
                } else {
                    EmptyMsg(
                        icon: "newspaper",
                        msg: "기사 링크 없음",
                        detail: "이 뉴스 항목에는 열 수 있는 원문 링크가 없습니다."
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .appScreenBackground()
                }
            }
            .navigationTitle(newsSourceText(item) ?? "뉴스")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        LucideIconView(icon: .x, size: 18)
                    }
                    .accessibilityLabel("닫기")
                }

                ToolbarItem(placement: .topBarTrailing) {
                    if let url = articleURL {
                        Button {
                            openURL(url)
                        } label: {
                            LucideIconView(icon: .globe2, size: 18)
                        }
                        .accessibilityLabel("Safari로 열기")
                    }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
    }
}

private struct NewsArticleWebView: UIViewRepresentable {
    let url: URL
    @Binding var isLoading: Bool
    @Binding var errorMessage: String?

    final class Coordinator: NSObject, WKNavigationDelegate {
        @Binding var isLoading: Bool
        @Binding var errorMessage: String?

        init(isLoading: Binding<Bool>, errorMessage: Binding<String?>) {
            _isLoading = isLoading
            _errorMessage = errorMessage
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            isLoading = true
            errorMessage = nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoading = false
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            isLoading = false
            errorMessage = error.localizedDescription
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            isLoading = false
            errorMessage = error.localizedDescription
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            isLoading = false
            errorMessage = "웹 콘텐츠 프로세스가 종료되었습니다. 다시 시도해 주세요."
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(isLoading: $isLoading, errorMessage: $errorMessage)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.allowsBackForwardNavigationGestures = true
        view.scrollView.isScrollEnabled = true
        view.scrollView.bounces = true
        view.scrollView.alwaysBounceVertical = true
        view.scrollView.backgroundColor = UIColor.systemBackground
        isLoading = true
        errorMessage = nil
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if uiView.url != url {
            isLoading = true
            errorMessage = nil
            uiView.load(URLRequest(url: url))
        }
    }
}

private struct NewsSummaryCard: View {
    let count: Int
    let market: String
    let isLoading: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            LucideIconView(icon: .newspaper, size: 22)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 42, height: 42)
                .background(Circle().fill(AppTheme.accent.opacity(0.10)))
            VStack(alignment: .leading, spacing: 5) {
                Text("시장 영향 뉴스")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                Text("\(count)개")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.accent)
                Text("원문은 링크로 열고, 큐빗 분석과 관련 가격 반응만 표시합니다.")
                    .font(.caption)
                    .foregroundStyle(AppTheme.tertiaryText)
                    .lineLimit(2)
            }
            Spacer()
            Text(isLoading ? "동기화" : market)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.accent)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule().fill(AppTheme.accent.opacity(0.12)))
        }
        .padding(.vertical, 4)
    }
}

private struct NewsListRow: View {
    let item: NewsItem
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    NewsImpactBadge(item: item)
                    if !item.market.isEmpty {
                        Text(newsMarketLabel(item.market))
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(AppTheme.accent)
                            .lineLimit(1)
                    }
                    NewsMoveBadge(item: item)
                    Spacer()
                    Text(newsRelativeTimeText(item))
                        .font(.system(size: 12))
                        .foregroundStyle(AppTheme.tertiaryText)
                        .lineLimit(1)
                }

                Text(newsDisplayTitle(item))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(2)

                NewsQubitAnalysisBlock(item: item, maxLines: 3)

                HStack(spacing: 8) {
                    NewsRelatedImpactLine(item: item, tickerLimit: 3)
                    Spacer()
                    if !item.url.isEmpty {
                        HStack(spacing: 4) {
                            LucideIconView(icon: .newspaper, size: 11)
                            Text("원문 보기")
                        }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.accent)
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(QuantPressButtonStyle(role: .row))
        .disabled(item.url.isEmpty)
    }
}

private struct NewsImpactFeedCard: View {
    let item: NewsItem
    let featured: Bool
    let open: () -> Void

    private var ticker: String {
        newsAssetTicker(item)
    }

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    if ticker == "NEWS" {
                        NewsGlobalAssetAvatar()
                    } else {
                        CompanyLogoView(
                            ticker: ticker,
                            currency: marketCurrency(for: ticker, market: item.market),
                            size: 50
                        )
                    }
                    Text(newsAssetName(item, ticker: ticker))
                        .font(.system(size: 20, weight: .black))
                        .foregroundStyle(AppTheme.primaryText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                    Spacer(minLength: 8)
                    NewsSignalPill(item: item)
                }

                Text(newsDisplayTitle(item))
                    .font(.system(size: featured ? 22 : 20, weight: .black))
                    .foregroundStyle(AppTheme.primaryText)
                    .lineLimit(featured ? 3 : 2)
                    .lineSpacing(2)
                    .multilineTextAlignment(.leading)

                NewsRelatedImpactLine(item: item, tickerLimit: 4)

                NewsQubitAnalysisBlock(item: item, maxLines: featured ? 3 : 2)

                NewsSourceOpenRow(item: item)
            }
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .fill(AppTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 30, style: .continuous)
                            .stroke(AppTheme.hairline.opacity(0.16), lineWidth: 0.6)
                    )
            )
        }
        .buttonStyle(QuantPressButtonStyle(role: .card))
        .disabled(item.url.isEmpty)
    }
}

private struct NewsRelatedImpactLine: View {
    let item: NewsItem
    let tickerLimit: Int

    var body: some View {
        HStack(spacing: 7) {
            Text("관련")
                .font(.system(size: 12, weight: .black))
                .foregroundStyle(AppTheme.secondaryText)
            ForEach(newsRelatedTickers(item).prefix(tickerLimit), id: \.self) { ticker in
                Text(ticker)
                    .font(.system(size: 12, weight: .black))
                    .foregroundStyle(AppTheme.accent)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(AppTheme.accent.opacity(0.10), in: Capsule())
            }
            NewsMoveBadge(item: item)
            Spacer(minLength: 0)
        }
    }
}

private struct NewsQubitAnalysisBlock: View {
    let item: NewsItem
    let maxLines: Int

    private var analysisText: String {
        item.impactReason.isEmpty ? "큐빗이 관련 종목과 가격 반응을 확인한 뒤 원문 링크 확인이 필요한 뉴스로 분류했습니다." : item.impactReason
    }

    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            LucideIconView(icon: .lineChart, size: 15)
                .foregroundStyle(newsImpactColor(item))
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("큐빗 분석")
                        .font(.system(size: 12, weight: .black))
                        .foregroundStyle(newsImpactColor(item))
                    Text(newsImpactLabel(item))
                        .font(.system(size: 11, weight: .black))
                        .foregroundStyle(newsImpactColor(item))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(newsImpactColor(item).opacity(0.12), in: Capsule())
                }
                Text(analysisText)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)
                    .lineSpacing(3)
                    .lineLimit(maxLines)
                    .multilineTextAlignment(.leading)
            }
        }
        .padding(12)
        .background(AppTheme.elevatedCard.opacity(0.74), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AppTheme.hairline.opacity(0.28), lineWidth: 0.6)
        }
    }
}

private struct NewsSourceOpenRow: View {
    let item: NewsItem

    var body: some View {
        HStack(spacing: 8) {
            Text("출처: \(newsSourceOrMarket(item))")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(AppTheme.info)
                .lineLimit(1)
            Text("•")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(AppTheme.hairline)
            Text(newsRelativeTimeText(item))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AppTheme.info)
                .lineLimit(1)
            Spacer(minLength: 0)
            if !item.url.isEmpty {
                HStack(spacing: 4) {
                    LucideIconView(icon: .globe2, size: 13)
                    Text("원문 보기")
                }
                .font(.system(size: 13, weight: .black))
                .foregroundStyle(AppTheme.accent)
            }
        }
    }
}

private struct NewsGlobalAssetAvatar: View {
    var body: some View {
        Circle()
            .fill(AppTheme.accent.opacity(0.12))
            .overlay(Circle().stroke(AppTheme.accent.opacity(0.18), lineWidth: 0.6))
            .frame(width: 50, height: 50)
            .overlay {
                LucideIconView(icon: .globe2, size: 23)
                    .foregroundStyle(AppTheme.accent)
            }
            .accessibilityLabel("글로벌 뉴스")
    }
}

private struct NewsSignalPill: View {
    let item: NewsItem

    private var color: Color {
        newsSignalColor(item)
    }

    var body: some View {
        HStack(spacing: 6) {
            LucideIconView(icon: item.impactLabel.lowercased() == "negative" ? .trendingDown : .trendingUp, size: 15)
            Text(newsSignalLabel(item))
                .font(.system(size: 14, weight: .black))
                .lineLimit(1)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 13)
        .frame(minHeight: 36)
        .background(Capsule().fill(color.opacity(0.14)))
    }
}

private struct NewsImpactBadge: View {
    let item: NewsItem

    var body: some View {
        Text(newsImpactLabel(item))
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(newsImpactColor(item))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(newsImpactColor(item).opacity(0.12)))
    }
}

private struct NewsMoveBadge: View {
    let item: NewsItem

    var body: some View {
        if let text = newsMoveText(item) {
            HStack(spacing: 3) {
                LucideIconView(icon: newsMoveIcon(item), size: 10)
                Text(text)
            }
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(newsMoveColor(item))
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(newsMoveColor(item).opacity(0.12)))
        }
    }
}

private func newsMoveIcon(_ item: NewsItem) -> LucideIcon {
    (item.relatedChangePct ?? 0) >= 0 ? .trendingUp : .trendingDown
}

private func newsMarketLabel(_ market: String) -> String {
    switch market.uppercased() {
    case "US":
        return "미국"
    case "KR":
        return "국내"
    case "GLOBAL":
        return "글로벌"
    default:
        return market
    }
}

private func newsSourceText(_ item: NewsItem) -> String? {
    let source = item.source.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !source.isEmpty,
          source != "-",
          source.localizedCaseInsensitiveCompare("QuantBridge") != .orderedSame,
          source.localizedCaseInsensitiveCompare("Qubit") != .orderedSame,
          source.localizedCaseInsensitiveCompare("큐빗") != .orderedSame else {
        return nil
    }
    return source
}

private func newsImpactFallbackLabel(_ label: String) -> String {
    switch label.lowercased() {
    case "positive":
        return "긍정"
    case "negative":
        return "부정"
    default:
        return "중립"
    }
}

private func newsImpactLabel(_ item: NewsItem) -> String {
    item.impactLabelKo.isEmpty ? newsImpactFallbackLabel(item.impactLabel) : item.impactLabelKo
}

private func newsImpactColor(_ item: NewsItem) -> Color {
    switch item.impactLabel.lowercased() {
    case "positive":
        return AppTheme.positive
    case "negative":
        return AppTheme.negative
    default:
        return AppTheme.secondaryText
    }
}

private func newsAssetTicker(_ item: NewsItem) -> String {
    if let ticker = newsRelatedTickers(item).first?.trimmingCharacters(in: .whitespacesAndNewlines), !ticker.isEmpty {
        return ticker
    }
    let ticker = item.ticker.trimmingCharacters(in: .whitespacesAndNewlines)
    if !ticker.isEmpty {
        return ticker
    }
    switch item.market.uppercased() {
    case "US":
        return "SPY"
    case "KR":
        return "KOSPI"
    default:
        return "NEWS"
    }
}

private func newsAssetName(_ item: NewsItem, ticker: String) -> String {
    if ticker == "NEWS" {
        return newsMarketLabel(item.market).isEmpty ? "시장" : newsMarketLabel(item.market)
    }
    let name = localizedCompanyName(ticker: ticker, currentName: ticker, market: item.market)
    return name.isEmpty ? ticker : name
}

private func newsDisplayTitle(_ item: NewsItem) -> String {
    let clean = item.title.trimmingCharacters(in: .whitespacesAndNewlines)
    if clean.range(of: #"[가-힣]"#, options: .regularExpression) != nil {
        return clean
    }
    let asset = newsAssetName(item, ticker: newsAssetTicker(item))
    let direction = newsImpactLabel(item)
    switch item.impactScope.lowercased() {
    case "sector":
        return "\(asset) 관련 섹터 \(direction) 이슈"
    case "market":
        let market = newsMarketLabel(item.market).isEmpty ? "시장" : newsMarketLabel(item.market)
        return "\(market) \(direction) 이슈"
    default:
        return "\(asset) \(direction) 이슈"
    }
}

private func newsSignalLabel(_ item: NewsItem) -> String {
    switch item.impactLabel.lowercased() {
    case "positive":
        return "긍정"
    case "negative":
        return "부정"
    default:
        return "중립"
    }
}

private func newsSignalColor(_ item: NewsItem) -> Color {
    switch item.impactLabel.lowercased() {
    case "positive", "negative":
        return newsImpactColor(item)
    default:
        return AppTheme.secondaryText
    }
}

private func newsSourceOrMarket(_ item: NewsItem) -> String {
    newsSourceText(item) ?? (newsMarketLabel(item.market).isEmpty ? "시장" : newsMarketLabel(item.market))
}

private func newsRelativeTimeText(_ item: NewsItem) -> String {
    let raw = item.publishedAt.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !raw.isEmpty else { return "방금" }

    let fractionalFormatter = ISO8601DateFormatter()
    fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let plainFormatter = ISO8601DateFormatter()
    plainFormatter.formatOptions = [.withInternetDateTime]

    guard let date = fractionalFormatter.date(from: raw) ?? plainFormatter.date(from: raw) else {
        let localFormatter = DateFormatter()
        localFormatter.locale = Locale(identifier: "ko_KR")
        localFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        for format in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"] {
            localFormatter.dateFormat = format
            if let date = localFormatter.date(from: raw) {
                return newsRelativeTimeString(from: date)
            }
        }
        return String(raw.prefix(10))
    }

    return newsRelativeTimeString(from: date)
}

private func newsRelativeTimeString(from date: Date) -> String {
    let seconds = max(0, Date().timeIntervalSince(date))
    if seconds < 60 {
        return "방금"
    }
    if seconds < 3_600 {
        return "\(Int(seconds / 60))분 전"
    }
    if seconds < 86_400 {
        return "\(Int(seconds / 3_600))시간 전"
    }
    if seconds < 604_800 {
        return "\(Int(seconds / 86_400))일 전"
    }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "ko_KR")
    formatter.dateFormat = "M월 d일"
    return formatter.string(from: date)
}

private func newsMoveText(_ item: NewsItem) -> String? {
    guard let change = item.relatedChangePct, change.isFinite else { return nil }
    let label = item.relatedChangeLabel.isEmpty ? "관련" : item.relatedChangeLabel
    let horizon = newsMoveHorizonLabel(item.relatedChangeHorizon)
    return "\(label)\(horizon) \(newsSignedPercent(change))"
}

private func newsMoveHorizonLabel(_ value: String) -> String {
    let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return "" }
    if clean == "오늘" || clean == "전장" {
        return " \(clean)"
    }
    return ""
}

private func newsMoveColor(_ item: NewsItem) -> Color {
    guard let change = item.relatedChangePct, change.isFinite else { return AppTheme.secondaryText }
    if change > 0 { return AppTheme.positive }
    if change < 0 { return AppTheme.negative }
    return AppTheme.secondaryText
}

private func newsSignedPercent(_ value: Double) -> String {
    let sign = value > 0 ? "+" : ""
    return "\(sign)\(String(format: "%.1f", value * 100))%"
}

private func newsRelatedTickers(_ item: NewsItem) -> [String] {
    if !item.relatedTickers.isEmpty {
        return item.relatedTickers
    }
    return item.ticker.isEmpty ? [] : [item.ticker]
}

private extension MarketScope {
    var defaultNewsQuery: String {
        switch self {
        case .all:
            return "증시 주식 실적 반도체 환율"
        case .us:
            return "뉴욕증시 나스닥 S&P500 미국 주식 엔비디아 테슬라 애플"
        case .kr:
            return "국내증시 코스피 코스닥 삼성전자 SK하이닉스"
        }
    }

    func acceptsNewsItem(_ item: NewsItem) -> Bool {
        guard item.kind.localizedCaseInsensitiveCompare("external_news") == .orderedSame,
              !item.url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return false }
        if self == .all { return true }
        return item.market.uppercased() == rawValue
    }
}

private extension String {
    func matchesNewsMarket(_ market: MarketScope) -> Bool {
        let text = lowercased()
        switch market {
        case .all:
            return true
        case .us:
            let excludes = ["삼성전자", "sk하이닉스", "코스피", "코스닥", "국내 증시", "한국 증시", "현대차"]
            if excludes.contains(where: { text.contains($0.lowercased()) }) {
                return false
            }
            let includes = ["미국", "뉴욕증시", "미 증시", "미증시", "나스닥", "s&p", "다우", "월가", "엔비디아", "nvidia", "테슬라", "애플", "apple", "마이크로소프트", "microsoft", "알파벳", "amazon", "아마존", "연준", "fed"]
            return includes.contains { text.contains($0.lowercased()) }
        case .kr:
            let includes = ["국내 증시", "한국 증시", "코스피", "코스닥", "삼성전자", "sk하이닉스", "현대차", "외국인", "기관", "한국거래소"]
            return includes.contains { text.contains($0.lowercased()) }
        }
    }
}
