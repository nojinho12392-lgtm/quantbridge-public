import Foundation

protocol APIClientProtocol {
    func fetch<T: Decodable>(
        _ pathComponents: [String],
        queryItems: [URLQueryItem]
    ) async throws -> T

    func authenticatedFetch<T: Decodable>(
        _ pathComponents: [String],
        token: String,
        queryItems: [URLQueryItem]
    ) async throws -> T

    func send<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        method: String,
        body: Body
    ) async throws -> T

    func authenticatedSend<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        method: String,
        token: String,
        body: Body
    ) async throws -> T

    func authenticatedEmpty<T: Decodable>(
        _ pathComponents: [String],
        method: String,
        token: String
    ) async throws -> T
}

extension APIClientProtocol {
    func fetch<T: Decodable>(_ pathComponents: [String]) async throws -> T {
        try await fetch(pathComponents, queryItems: [])
    }

    func send<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        body: Body
    ) async throws -> T {
        try await send(pathComponents, method: "POST", body: body)
    }

    func authenticatedSend<Body: Encodable, T: Decodable>(
        _ pathComponents: [String],
        token: String,
        body: Body
    ) async throws -> T {
        try await authenticatedSend(pathComponents, method: "POST", token: token, body: body)
    }

    func authenticatedEmpty<T: Decodable>(
        _ pathComponents: [String],
        token: String
    ) async throws -> T {
        try await authenticatedEmpty(pathComponents, method: "POST", token: token)
    }
}
