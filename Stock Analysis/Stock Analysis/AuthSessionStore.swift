import Combine
import Foundation

enum AuthRestoreOutcome {
    case noStoredSession
    case restored
    case invalidated
}

@MainActor
final class AuthSessionStore: ObservableObject {
    @Published private(set) var user: AuthUser?
    @Published private(set) var token: String?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let legacyCachedUserKey = "stock_analysis_cached_user"

    var isSignedIn: Bool {
        user != nil && token != nil
    }

    init() {
        token = KeychainStore.loadToken()
        user = loadCachedUser()
    }

    func restore() async -> AuthRestoreOutcome {
        guard let token else {
            user = nil
            clearCachedUser()
            return .noStoredSession
        }

        isLoading = true
        defer { isLoading = false }

        do {
            let response: CurrentUserResponse = try await APIClient.shared.authenticatedFetch(["auth", "me"], token: token)
            user = response.user
            cacheUser(response.user)
            return .restored
        } catch {
            self.token = nil
            user = nil
            KeychainStore.clearToken()
            clearCachedUser()
            return .invalidated
        }
    }

    func signUp(email: String, password: String, displayName: String) async -> Bool {
        await authenticate(path: ["auth", "signup"], email: email, password: password, displayName: displayName)
    }

    func login(email: String, password: String) async -> Bool {
        await authenticate(path: ["auth", "login"], email: email, password: password, displayName: nil)
    }

    func logout() async {
        if let token {
            let _: EmptyResponse? = try? await APIClient.shared.authenticatedEmpty(["auth", "logout"], token: token)
        }
        token = nil
        user = nil
        KeychainStore.clearToken()
        clearCachedUser()
    }

    func deleteAccount() async -> Bool {
        guard let token else { return false }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let _: EmptyResponse = try await APIClient.shared.authenticatedEmpty(["auth", "me"], method: "DELETE", token: token)
            self.token = nil
            user = nil
            KeychainStore.clearToken()
            clearCachedUser()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    private func authenticate(
        path: [String],
        email: String,
        password: String,
        displayName: String?
    ) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
            let cleanDisplayName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
            let response: AuthResponse
            if path.last == "signup" {
                response = try await APIClient.shared.send(
                    path,
                    body: SignupRequest(email: cleanEmail, password: password, displayName: cleanDisplayName)
                )
            } else {
                response = try await APIClient.shared.send(
                    path,
                    body: LoginRequest(email: cleanEmail, password: password)
                )
            }
            token = response.accessToken
            user = response.user
            KeychainStore.saveToken(response.accessToken)
            cacheUser(response.user)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    private func cacheUser(_ user: AuthUser) {
        guard let data = try? JSONEncoder().encode(user) else { return }
        KeychainStore.saveCachedUser(data)
        UserDefaults.standard.removeObject(forKey: legacyCachedUserKey)
    }

    private func loadCachedUser() -> AuthUser? {
        if let data = KeychainStore.loadCachedUser() {
            return try? JSONDecoder().decode(AuthUser.self, from: data)
        }
        guard let data = UserDefaults.standard.data(forKey: legacyCachedUserKey),
              let user = try? JSONDecoder().decode(AuthUser.self, from: data) else {
            return nil
        }
        KeychainStore.saveCachedUser(data)
        UserDefaults.standard.removeObject(forKey: legacyCachedUserKey)
        return user
    }

    private func clearCachedUser() {
        KeychainStore.clearCachedUser()
        UserDefaults.standard.removeObject(forKey: legacyCachedUserKey)
    }
}
