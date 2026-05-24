package com.example.myapplication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException

private const val ACCOUNT_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: AccountRepository
) : ViewModel() {
    var token by mutableStateOf(repository.storedToken())
        private set
    var user by mutableStateOf(if (token != null) repository.storedUser() else null)
        private set
    var loading by mutableStateOf(false)
        private set
    var sessionRestoring by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        if (token != null) restoreSession()
    }

    fun clearError() {
        error = null
    }

    fun reloadStoredSession() {
        token = repository.storedToken()
        user = if (token != null) repository.storedUser() else null
        sessionRestoring = false
    }

    suspend fun login(
        email: String,
        password: String,
        displayName: String?,
        signup: Boolean
    ): AccountSession? {
        loading = true
        sessionRestoring = false
        error = null
        return try {
            val session = withTimeout(ACCOUNT_REQUEST_TIMEOUT_MS) {
                repository.authenticate(email, password, displayName, signup)
            }
            applySession(session)
            session
        } catch (exc: Exception) {
            exc.throwIfCancellation()
            error = accountFailureMessage(if (signup) "가입" else "로그인", exc)
            null
        } finally {
            loading = false
        }
    }

    suspend fun logout() {
        loading = true
        error = null
        try {
            withTimeout(ACCOUNT_REQUEST_TIMEOUT_MS) {
                repository.logout()
            }
        } catch (exc: Exception) {
            exc.throwIfCancellation()
            error = accountFailureMessage("로그아웃", exc)
            repository.clearSession()
        } finally {
            token = null
            user = null
            sessionRestoring = false
            loading = false
        }
    }

    suspend fun deleteAccount(): Boolean {
        loading = true
        error = null
        return try {
            withTimeout(ACCOUNT_REQUEST_TIMEOUT_MS) {
                repository.deleteAccount()
            }
            token = null
            user = null
            sessionRestoring = false
            true
        } catch (exc: Exception) {
            exc.throwIfCancellation()
            error = accountFailureMessage("계정 삭제", exc)
            false
        } finally {
            loading = false
        }
    }

    private fun restoreSession() {
        if (loading || sessionRestoring) return
        sessionRestoring = true
        viewModelScope.launch {
            try {
                val session = withTimeout(ACCOUNT_REQUEST_TIMEOUT_MS) {
                    repository.restoreSession()
                }
                if (session == null) {
                    token = null
                    user = null
                } else {
                    applySession(session)
                }
                error = null
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                repository.clearSession()
                token = null
                user = null
                error = accountFailureMessage("세션 확인", exc)
            } finally {
                sessionRestoring = false
            }
        }
    }

    private fun applySession(session: AccountSession) {
        token = session.token
        user = session.user
        sessionRestoring = false
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun accountFailureMessage(action: String, error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "$action 응답이 지연되고 있습니다."
            is HttpException -> accountHttpFailureMessage(action, error.code())
            else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "${action}에 실패했습니다."
        }
    }

    private fun accountHttpFailureMessage(action: String, code: Int): String {
        return when (code) {
            401 -> when (action) {
                "세션 확인" -> "로그인이 만료되었습니다. 다시 로그인하세요."
                "로그인" -> "등록된 계정이 없거나 이메일/비밀번호가 올바르지 않습니다. 처음이면 새 계정 만들기를 눌러 가입하세요."
                else -> "$action 권한을 확인할 수 없습니다."
            }
            409 -> "이미 가입된 이메일입니다. 로그인으로 전환해 주세요."
            422 -> "이메일과 비밀번호를 다시 확인하세요."
            429 -> "요청이 너무 많습니다. 잠시 후 다시 시도하세요."
            in 500..599 -> "서버가 잠시 불안정합니다. 잠시 후 다시 시도하세요."
            else -> "${action}에 실패했습니다. ($code)"
        }
    }
}
