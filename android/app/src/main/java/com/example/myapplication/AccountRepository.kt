package com.example.myapplication

import com.example.myapplication.generated.models.QBAuthUser
import com.example.myapplication.network.AuthRequest
import com.example.myapplication.network.QuantApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val api: QuantApiService,
    private val tokenStore: SecureTokenStore
) {
    fun storedToken(): String? = tokenStore.loadToken()

    fun storedUser(): AuthUser? = tokenStore.loadUser()

    suspend fun authenticate(
        email: String,
        password: String,
        displayName: String?,
        signup: Boolean
    ): AccountSession {
        val request = AuthRequest(
            email = email.trim(),
            password = password,
            displayName = displayName?.trim()?.takeIf { it.isNotBlank() }
        )
        val response = if (signup) api.signup(request) else api.login(request)
        val session = AccountSession(
            token = response.accessToken,
            user = response.user.toDomain()
        )
        saveSession(session)
        return session
    }

    suspend fun restoreSession(): AccountSession? {
        val token = storedToken() ?: return null
        val user = api.getMe().user.toDomain()
        val session = AccountSession(token = token, user = user)
        saveSession(session)
        return session
    }

    suspend fun logout() {
        runCatching { api.logout() }
        tokenStore.clearSession()
    }

    suspend fun deleteAccount() {
        api.deleteMe()
        tokenStore.clearSession()
    }

    fun clearSession() {
        tokenStore.clearSession()
    }

    fun saveSession(session: AccountSession) {
        tokenStore.saveToken(session.token)
        tokenStore.saveUser(session.user)
    }

    private fun QBAuthUser.toDomain(): AuthUser {
        return AuthUser(
            id = id,
            email = email,
            displayName = displayName.orEmpty().ifBlank { email.substringBefore("@") },
            createdAt = createdAt.orEmpty()
        )
    }
}

data class AccountSession(
    val token: String,
    val user: AuthUser
)
