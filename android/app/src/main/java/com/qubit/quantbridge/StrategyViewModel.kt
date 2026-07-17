package com.qubit.quantbridge

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

private const val STRATEGY_REQUEST_TIMEOUT_MS = 15_000L

@HiltViewModel
class StrategyViewModel @Inject constructor(
    private val repository: StrategyRepository
) : ViewModel() {
    var backtests by mutableStateOf<List<BacktestSummary>>(emptyList())
        private set
    var driftItems by mutableStateOf<List<DriftItem>>(emptyList())
        private set
    var industryItems by mutableStateOf<List<IndustryItem>>(emptyList())
        private set
    var orderFlowItems by mutableStateOf<List<OrderFlowItem>>(emptyList())
        private set
    var riskHoldings by mutableStateOf<List<RiskHolding>>(emptyList())
        private set
    var riskSectors by mutableStateOf<List<RiskSector>>(emptyList())
        private set
    var rebalanceOrders by mutableStateOf<List<RebalanceOrder>>(emptyList())
        private set
    var shadowSummaries by mutableStateOf<List<ShadowAttributionSummary>>(emptyList())
        private set
    var shadowItems by mutableStateOf<List<ShadowAttributionItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun loadStrategy(force: Boolean = false) {
        if (!force && loaded) return
        if (loading) return

        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withTimeout(STRATEGY_REQUEST_TIMEOUT_MS) {
                    repository.fetchStrategy()
                }
                applyResult(result)
                loaded = true
                error = result.errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            } catch (exc: Exception) {
                exc.throwIfCancellation()
                error = strategyFailureSummary(exc)
            } finally {
                loading = false
            }
        }
    }

    fun hasData(): Boolean {
        return backtests.isNotEmpty() ||
            driftItems.isNotEmpty() ||
            industryItems.isNotEmpty() ||
            orderFlowItems.isNotEmpty() ||
            riskHoldings.isNotEmpty() ||
            riskSectors.isNotEmpty() ||
            rebalanceOrders.isNotEmpty() ||
            shadowSummaries.isNotEmpty() ||
            shadowItems.isNotEmpty()
    }

    private fun applyResult(result: StrategyRepositoryResult) {
        backtests = result.backtests
        driftItems = result.driftItems
        industryItems = result.industryItems
        orderFlowItems = result.orderFlowItems
        riskHoldings = result.riskHoldings
        riskSectors = result.riskSectors
        rebalanceOrders = result.rebalanceOrders
        shadowSummaries = result.shadowSummaries
        shadowItems = result.shadowItems
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun strategyFailureSummary(error: Throwable): String {
        return when (error) {
            is TimeoutCancellationException -> "전략 데이터 응답이 지연되고 있습니다. 마지막 정상 데이터를 표시합니다."
            else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "전략 데이터를 불러오지 못했습니다."
        }
    }
}
