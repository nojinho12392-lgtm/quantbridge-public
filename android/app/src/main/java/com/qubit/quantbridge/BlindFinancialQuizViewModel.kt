package com.qubit.quantbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

private const val TRAINING_QUIZ_TIMEOUT_MS = 18_000L
private const val TRAINING_QUIZ_ATTEMPTS = 2

@HiltViewModel
class BlindFinancialQuizViewModel @Inject constructor(
    private val repository: BlindFinancialQuizRepository
) : ViewModel() {
    var quiz by mutableStateOf<BlindFinancialQuizResponse?>(null)
        private set
    var selectedOptionId by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var warning by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (quiz != null || loading) return
        refresh()
    }

    fun refresh(force: Boolean = false) {
        if (loading) return
        loading = true
        error = null
        warning = null
        viewModelScope.launch {
            try {
                retryingApiResult(
                    timeoutMs = TRAINING_QUIZ_TIMEOUT_MS,
                    attempts = TRAINING_QUIZ_ATTEMPTS
                ) {
                    repository.fetchQuiz(refresh = force)
                }.onSuccess { response ->
                    quiz = response
                    selectedOptionId = null
                }.onFailure { exc ->
                    if (quiz == null) {
                        error = quizFailureSummary(exc)
                    } else {
                        warning = "마지막 성공 데이터를 표시 중입니다.\n${quizFailureSummary(exc)}"
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    fun selectOption(optionId: String) {
        if (selectedOptionId == null) {
            selectedOptionId = optionId
        }
    }

    private fun quizFailureSummary(error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "훈련 문제를 불러오는 시간이 길어지고 있습니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "훈련 문제를 불러오지 못했습니다."
        }
    }
}
