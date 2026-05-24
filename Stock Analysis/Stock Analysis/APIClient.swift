import Foundation
import CryptoKit

enum APIEnvironment {
    private static var simulatorLocalhostURL: URL {
        URL(string: "http://localhost:8000") ?? URL(fileURLWithPath: "/")
    }

    private static func configuredURL(forInfoKey key: String) -> URL? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !raw.contains("$(") else {
            return nil
        }
        return URL(string: raw)
    }

    private static var configuredBaseURL: URL? {
        configuredURL(forInfoKey: "APIBaseURL")
    }

    private static var fallbackBaseURL: URL? {
        configuredURL(forInfoKey: "APIFallbackBaseURL")
    }

    static var baseURLs: [URL] {
        var urls: [URL] = []
        #if DEBUG && targetEnvironment(simulator)
        urls.append(simulatorLocalhostURL)
        if let configuredBaseURL {
            urls.append(configuredBaseURL)
        }
        if let fallbackBaseURL {
            urls.append(fallbackBaseURL)
        }
        return unique(urls)
        #else
        if let configuredBaseURL {
            urls.append(configuredBaseURL)
        }
        if let fallbackBaseURL {
            urls.append(fallbackBaseURL)
        }
        return unique(urls)
        #endif
    }

    static var accountBaseURLs: [URL] {
        var urls: [URL] = []
        #if DEBUG && targetEnvironment(simulator)
        urls.append(simulatorLocalhostURL)
        if let fallbackBaseURL {
            urls.append(fallbackBaseURL)
        }
        if let configuredBaseURL {
            urls.append(configuredBaseURL)
        }
        return unique(urls)
        #else
        if let fallbackBaseURL {
            urls.append(fallbackBaseURL)
        }
        if let configuredBaseURL {
            urls.append(configuredBaseURL)
        }
        return unique(urls.isEmpty ? baseURLs : urls)
        #endif
    }

    private static func unique(_ urls: [URL]) -> [URL] {
        var seen = Set<String>()
        return urls.filter { url in
            let key = url.absoluteString
            if seen.contains(key) {
                return false
            }
            seen.insert(key)
            return true
        }
    }

    static var baseURL: URL {
        baseURLs.first ?? fallbackBaseURL ?? simulatorLocalhostURL
    }
}

struct APIErrorBody: Decodable {
    let detail: String?
    let error: String?
    let message: String?

    var userMessage: String? {
        detail ?? error ?? message
    }
}

enum APIError: LocalizedError {
    case badURL
    case http(Int, String?)
    case decoding(Error)
    case network(Error)

    var errorDescription: String? {
        switch self {
        case .badURL:
            return "잘못된 서버 URL"
        case .http(let code, let message):
            if let message, !message.isEmpty {
                if code >= 500 {
                    return "서버 오류 (\(code)): \(message)"
                }
                return message
            }
            switch code {
            case 401:
                return "이메일 또는 비밀번호가 올바르지 않습니다"
            case 409:
                return "이미 가입된 이메일입니다"
            case 422:
                return "입력값을 다시 확인하세요"
            case 400..<500:
                return "요청을 처리하지 못했습니다 (\(code))"
            default:
                return "서버 오류 (\(code))"
            }
        case .decoding(let error):
            return "파싱 실패: \(error.localizedDescription)"
        case .network(let error):
            return "네트워크 오류: \(error.localizedDescription)"
        }
    }
}

actor APIClient: APIClientProtocol {
    static let shared = APIClient()

    private struct CachedResponse {
        let data: Data
        let storedAt: Date
    }

    private struct DiskCachedResponse: Codable {
        let data: Data
        let storedAt: Date
    }

    private let baseURLs: [URL]
    private let accountBaseURLs: [URL]
    private var preferredBaseURL: URL?
    private var preferredAccountBaseURL: URL?
    private var responseCache: [String: CachedResponse] = [:]
    private let decoder: JSONDecoder
    private let session: URLSession
    private let cacheDirectory: URL
    private let defaultCacheTTL: TimeInterval = 300
    private let defaultLastSuccessCacheTTL: TimeInterval = 7 * 24 * 60 * 60
    private let priceListCacheTTL: TimeInterval = 30
    private let priceListLastSuccessCacheTTL: TimeInterval = 30 * 60
    private let marketQuoteCacheTTL: TimeInterval = 20
    private let marketHistoryCacheTTL: TimeInterval = 30
    private let marketLastSuccessCacheTTL: TimeInterval = 30 * 60

    init(
        baseURLs: [URL] = APIEnvironment.baseURLs,
        accountBaseURLs: [URL] = APIEnvironment.accountBaseURLs
    ) {
        self.baseURLs = baseURLs.isEmpty ? [APIEnvironment.baseURL] : baseURLs
        self.accountBaseURLs = accountBaseURLs.isEmpty ? self.baseURLs : accountBaseURLs
        self.decoder = JSONDecoder()
        let rootCacheDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        self.cacheDirectory = rootCacheDirectory.appendingPathComponent("QubitAPIResponses", isDirectory: true)

        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 12
        configuration.timeoutIntervalForResource = 30
        configuration.waitsForConnectivity = true
        self.session = URLSession(configuration: configuration)
    }

    init(baseURL: URL) {
        self.init(baseURLs: [baseURL], accountBaseURLs: [baseURL])
    }

    func fetch<T: Decodable>(
        _ pathComponents: [String],
        queryItems: [URLQueryItem] = []
    ) async throws -> T {
        try await request(pathComponents, queryItems: queryItems)
    }

    func authenticatedFetch<T: Decodable>(
        _ pathComponents: [String],
        token: String,
        queryItems: [URLQueryItem] = []
    ) async throws -> T {
        try await request(pathComponents, queryItems: queryItems, token: token)
    }

    func send<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        method: String = "POST",
        body: Body
    ) async throws -> T {
        let bodyData = try JSONEncoder().encode(body)
        return try await request(pathComponents, method: method, body: bodyData)
    }

    func authenticatedSend<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        method: String = "POST",
        token: String,
        body: Body
    ) async throws -> T {
        let bodyData = try JSONEncoder().encode(body)
        return try await request(pathComponents, method: method, body: bodyData, token: token)
    }

    func authenticatedEmpty<T: Decodable>(
        _ pathComponents: [String],
        method: String = "POST",
        token: String
    ) async throws -> T {
        try await request(pathComponents, method: method, token: token)
    }

    private func request<T: Decodable>(
        _ pathComponents: [String],
        method: String = "GET",
        queryItems: [URLQueryItem] = [],
        body: Data? = nil,
        token: String? = nil
    ) async throws -> T {
        var lastError: Error?
        var staleCacheData: Data?
        let shouldUseCache = isCacheable(method: method, token: token, queryItems: queryItems)
        let accountScoped = isAccountScoped(pathComponents)
        let candidates = candidateBaseURLs(accountScoped: accountScoped)
        for baseURL in candidates {
            guard let url = makeURL(baseURL, pathComponents, queryItems: queryItems) else {
                lastError = APIError.badURL
                continue
            }

            do {
                let cacheKey = url.absoluteString
                let ttl = cacheTTL(for: url)
                let staleTTL = lastSuccessCacheTTL(for: url)
                if shouldUseCache, let cachedData = cachedData(for: cacheKey, ttl: ttl) {
                    let value: T = try decode(cachedData)
                    remember(baseURL, accountScoped: accountScoped)
                    return value
                }
                if shouldUseCache, staleCacheData == nil {
                    staleCacheData = diskCachedData(for: cacheKey, ttl: ttl, lastSuccessTTL: staleTTL, allowExpired: true)
                }

                let data = try await responseData(url: url, method: method, body: body, token: token)
                let value: T = try decode(data)
                if shouldUseCache {
                    storeCachedData(data, for: cacheKey)
                }
                remember(baseURL, accountScoped: accountScoped)
                return value
            } catch let error as APIError {
                lastError = error
                if case .http = error {
                    if shouldUseCache, let staleCacheData {
                        return try decode(staleCacheData)
                    }
                    throw error
                }
            } catch {
                lastError = error
            }
        }

        if shouldUseCache, let staleCacheData {
            return try decode(staleCacheData)
        }
        if let error = lastError as? APIError {
            throw error
        }
        throw lastError.map(APIError.network) ?? APIError.badURL
    }

    private func responseData(
        url: URL,
        method: String,
        body: Data?,
        token: String?
    ) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                let body = try? decoder.decode(APIErrorBody.self, from: data)
                throw APIError.http(http.statusCode, body?.userMessage)
            }
            return data
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.network(error)
        }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do {
            return try decoder.decode(T.self, from: data)
        } catch let error as DecodingError {
            throw APIError.decoding(error)
        } catch {
            throw APIError.network(error)
        }
    }

    private func cachedData(for key: String, ttl: TimeInterval) -> Data? {
        guard let cached = responseCache[key] else {
            return diskCachedData(for: key, ttl: ttl, lastSuccessTTL: ttl, allowExpired: false)
        }
        if Date().timeIntervalSince(cached.storedAt) <= ttl {
            return cached.data
        }
        responseCache.removeValue(forKey: key)
        return diskCachedData(for: key, ttl: ttl, lastSuccessTTL: ttl, allowExpired: false)
    }

    private func storeCachedData(_ data: Data, for key: String) {
        let cached = CachedResponse(data: data, storedAt: Date())
        responseCache[key] = cached
        let diskCached = DiskCachedResponse(data: data, storedAt: cached.storedAt)
        do {
            try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
            let encoded = try JSONEncoder().encode(diskCached)
            try encoded.write(to: diskCacheURL(for: key), options: [.atomic])
        } catch {
            // Disk cache is best-effort; network responses should still be usable.
        }
    }

    private func diskCachedData(
        for key: String,
        ttl: TimeInterval,
        lastSuccessTTL: TimeInterval,
        allowExpired: Bool
    ) -> Data? {
        let fileURL = diskCacheURL(for: key)
        guard let raw = try? Data(contentsOf: fileURL),
              let cached = try? JSONDecoder().decode(DiskCachedResponse.self, from: raw) else {
            return nil
        }
        let age = Date().timeIntervalSince(cached.storedAt)
        guard age <= lastSuccessTTL, allowExpired || age <= ttl else {
            return nil
        }
        responseCache[key] = CachedResponse(data: cached.data, storedAt: cached.storedAt)
        return cached.data
    }

    private func diskCacheURL(for key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return cacheDirectory.appendingPathComponent("\(digest).json")
    }

    private func isCacheable(method: String, token: String?, queryItems: [URLQueryItem]) -> Bool {
        guard method.uppercased() == "GET", token == nil else { return false }
        return !queryItems.contains { item in
            item.name == "refresh" && item.value == "true"
        }
    }

    private func cacheTTL(for url: URL) -> TimeInterval {
        if url.path.hasSuffix("/market/indicators/history") {
            return marketHistoryCacheTTL
        }
        if url.path.hasSuffix("/market/indices") || url.path.hasSuffix("/market/indicators") {
            return marketQuoteCacheTTL
        }
        if isPriceSensitivePath(url.path) {
            return priceListCacheTTL
        }
        return defaultCacheTTL
    }

    private func lastSuccessCacheTTL(for url: URL) -> TimeInterval {
        if url.path.hasSuffix("/market/indices") ||
            url.path.hasSuffix("/market/indicators") ||
            url.path.hasSuffix("/market/indicators/history") {
            return marketLastSuccessCacheTTL
        }
        if isPriceSensitivePath(url.path) {
            return priceListLastSuccessCacheTTL
        }
        return defaultLastSuccessCacheTTL
    }

    private func isPriceSensitivePath(_ path: String) -> Bool {
        path.hasPrefix("/portfolio") ||
            path.hasPrefix("/smallcap") ||
            path.hasPrefix("/scored") ||
            path.hasPrefix("/sectors/themes") ||
            path.hasPrefix("/etfs") ||
            path.hasPrefix("/stock")
    }

    private func isAccountScoped(_ pathComponents: [String]) -> Bool {
        guard let first = pathComponents.first?.lowercased() else { return false }
        return first == "auth" || first == "me"
    }

    private func remember(_ baseURL: URL, accountScoped: Bool) {
        if accountScoped {
            preferredAccountBaseURL = baseURL
        } else {
            preferredBaseURL = baseURL
        }
    }

    private func candidateBaseURLs(accountScoped: Bool) -> [URL] {
        var urls: [URL] = []
        if accountScoped {
            if let preferredAccountBaseURL {
                urls.append(preferredAccountBaseURL)
            }
            urls.append(contentsOf: accountBaseURLs.filter { $0 != preferredAccountBaseURL })
        } else {
            if let preferredBaseURL {
                urls.append(preferredBaseURL)
            }
            urls.append(contentsOf: baseURLs.filter { $0 != preferredBaseURL })
        }
        return urls
    }

    private func makeURL(
        _ baseURL: URL,
        _ pathComponents: [String],
        queryItems: [URLQueryItem]
    ) -> URL? {
        var url = baseURL
        for component in pathComponents where !component.isEmpty {
            url.appendPathComponent(component)
        }

        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        return components.url
    }
}
