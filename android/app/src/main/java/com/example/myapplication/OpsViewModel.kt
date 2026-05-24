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

private const val OPS_REQUEST_TIMEOUT_MS = 12_000L

@HiltViewModel
class OpsViewModel @Inject constructor(
    private val repository: OpsRepository
) : ViewModel() {
    var researchQuality by mutableStateOf<ResearchQuality?>(null)
        private set
    var mlBlendReport by mutableStateOf<MLBlendReport?>(null)
        private set
    var opsHealth by mutableStateOf<OpsHealth?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun loadOps(force: Boolean = false) {
        if (!force && loaded) return
        if (loading) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withTimeout(OPS_REQUEST_TIMEOUT_MS) {
                    repository.fetchOps()
                }
                applyResult(result)
                loaded = true
                error = result.errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = opsFailureSummary(exc)
            } finally {
                loading = false
            }
        }
    }

    fun hasData(): Boolean {
        return researchQuality != null || mlBlendReport != null || opsHealth != null
    }

    private fun applyResult(result: OpsRepositoryResult) {
        result.researchQuality?.let { researchQuality = it }
        result.mlBlendReport?.let { mlBlendReport = it }
        result.opsHealth?.let { opsHealth = it }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun opsFailureSummary(error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "진단 데이터 응답이 지연되고 있습니다. 마지막 정상 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "진단 데이터를 불러오지 못했습니다."
        }
    }
}
