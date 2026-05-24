import Foundation
@testable import Stock_Analysis

enum MockAPIClientError: LocalizedError {
    case missingResponse(String)
    case configured(String)
    case typeMismatch(String)

    var errorDescription: String? {
        switch self {
        case .missingResponse(let key):
            return "Missing mock response for \(key)"
        case .configured(let message):
            return message
        case .typeMismatch(let key):
            return "Mock response type mismatch for \(key)"
        }
    }
}

final class MockAPIClient: APIClientProtocol {
    var fetchResponses: [String: Any] = [:]
    var authenticatedFetchResponses: [String: Any] = [:]
    var sendResponses: [String: Any] = [:]
    var authenticatedSendResponses: [String: Any] = [:]
    var emptyResponses: [String: Any] = [:]

    var fetchErrors: [String: Error] = [:]
    var authenticatedFetchErrors: [String: Error] = [:]
    var sendErrors: [String: Error] = [:]
    var authenticatedSendErrors: [String: Error] = [:]
    var emptyErrors: [String: Error] = [:]

    private(set) var fetchCallCount = 0
    private(set) var authenticatedFetchCallCount = 0
    private(set) var sendCallCount = 0
    private(set) var authenticatedSendCallCount = 0
    private(set) var emptyCallCount = 0

    static func key(_ pathComponents: [String], queryItems: [URLQueryItem] = []) -> String {
        let path = pathComponents.joined(separator: "/")
        guard !queryItems.isEmpty else { return path }
        let query = queryItems
            .map { "\($0.name)=\($0.value ?? "")" }
            .sorted()
            .joined(separator: "&")
        return "\(path)?\(query)"
    }

    func setFetchResponse<T>(_ response: T, for pathComponents: [String], queryItems: [URLQueryItem] = []) {
        fetchResponses[Self.key(pathComponents, queryItems: queryItems)] = response
    }

    func setFetchError(_ message: String, for pathComponents: [String], queryItems: [URLQueryItem] = []) {
        fetchErrors[Self.key(pathComponents, queryItems: queryItems)] = MockAPIClientError.configured(message)
    }

    func clearFetchError(for pathComponents: [String], queryItems: [URLQueryItem] = []) {
        fetchErrors.removeValue(forKey: Self.key(pathComponents, queryItems: queryItems))
    }

    func fetch<T: Decodable>(
        _ pathComponents: [String],
        queryItems: [URLQueryItem]
    ) async throws -> T {
        fetchCallCount += 1
        return try response(
            from: fetchResponses,
            errors: fetchErrors,
            key: Self.key(pathComponents, queryItems: queryItems)
        )
    }

    func authenticatedFetch<T: Decodable>(
        _ pathComponents: [String],
        token: String,
        queryItems: [URLQueryItem]
    ) async throws -> T {
        authenticatedFetchCallCount += 1
        return try response(
            from: authenticatedFetchResponses,
            errors: authenticatedFetchErrors,
            key: Self.key(pathComponents, queryItems: queryItems)
        )
    }

    func send<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        method: String,
        body: Body
    ) async throws -> T {
        sendCallCount += 1
        return try response(
            from: sendResponses,
            errors: sendErrors,
            key: "\(method.uppercased()) \(Self.key(pathComponents))"
        )
    }

    func authenticatedSend<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        method: String,
        token: String,
        body: Body
    ) async throws -> T {
        authenticatedSendCallCount += 1
        return try response(
            from: authenticatedSendResponses,
            errors: authenticatedSendErrors,
            key: "\(method.uppercased()) \(Self.key(pathComponents))"
        )
    }

    func authenticatedEmpty<T: Decodable>(
        _ pathComponents: [String],
        method: String,
        token: String
    ) async throws -> T {
        emptyCallCount += 1
        return try response(
            from: emptyResponses,
            errors: emptyErrors,
            key: "\(method.uppercased()) \(Self.key(pathComponents))"
        )
    }

    private func response<T: Decodable>(
        from responses: [String: Any],
        errors: [String: Error],
        key: String
    ) throws -> T {
        if let error = errors[key] {
            throw error
        }
        guard let stored = responses[key] else {
            throw MockAPIClientError.missingResponse(key)
        }
        if let typed = stored as? T {
            return typed
        }
        if let data = stored as? Data {
            return try JSONDecoder().decode(T.self, from: data)
        }
        if JSONSerialization.isValidJSONObject(stored) {
            let data = try JSONSerialization.data(withJSONObject: stored)
            return try JSONDecoder().decode(T.self, from: data)
        }
        throw MockAPIClientError.typeMismatch(key)
    }
}
