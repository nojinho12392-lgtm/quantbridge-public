package com.example.myapplication

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    app: QuantAppState,
    request: DetailRequest,
    detail: StockDetail?,
    loading: Boolean,
    error: String?,
    period: ChartPeriod,
    availablePeriods: Set<ChartPeriod>,
    onPeriodChange: (ChartPeriod) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenDetail: (DetailRequest) -> Unit,
    comparisonViewModel: ComparisonViewModel
) {
    StockDetailScreenContent(
        app = app,
        request = request,
        detail = detail,
        loading = loading,
        error = error,
        period = period,
        availablePeriods = availablePeriods,
        onPeriodChange = onPeriodChange,
        onRetry = onRetry,
        onBack = onBack,
        onOpenDetail = onOpenDetail,
        comparisonViewModel = comparisonViewModel
    )
}
