import Foundation
import Security

enum KeychainStore {
    private static let service = "com.jinho.stock-analysis.auth"
    private static let tokenAccount = "quantbridge_access_token"
    private static let cachedUserAccount = "quantbridge_cached_user"
    private static let accessibility = kSecAttrAccessibleWhenUnlockedThisDeviceOnly

    @discardableResult
    static func saveToken(_ token: String) -> Bool {
        saveData(Data(token.utf8), account: tokenAccount)
    }

    static func loadToken() -> String? {
        guard let data = loadData(account: tokenAccount) else {
            return nil
        }
        let token = String(data: data, encoding: .utf8)
        hardenExistingItem(account: tokenAccount)
        return token
    }

    static func clearToken() {
        clearData(account: tokenAccount)
    }

    @discardableResult
    static func saveCachedUser(_ data: Data) -> Bool {
        saveData(data, account: cachedUserAccount)
    }

    static func loadCachedUser() -> Data? {
        let data = loadData(account: cachedUserAccount)
        if data != nil {
            hardenExistingItem(account: cachedUserAccount)
        }
        return data
    }

    static func clearCachedUser() {
        clearData(account: cachedUserAccount)
    }

    @discardableResult
    private static func saveData(_ data: Data, account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: accessibility
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return true
        }

        if updateStatus == errSecItemNotFound {
            return SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
        }

        SecItemDelete(query as CFDictionary)
        return SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
    }

    private static func loadData(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data else {
            return nil
        }
        return data
    }

    private static func clearData(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func hardenExistingItem(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecAttrAccessible as String: accessibility
        ]
        SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    }
}
