package com.qubit.quantbridge

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun scaleRatio(value: Double?, maxValue: Double): Double {
    val v = value ?: return 0.0
    if (!v.isFinite() || maxValue <= 0.0 || !maxValue.isFinite()) return 0.0
    return (v / maxValue * 100.0).coerceIn(0.0, 100.0)
}

internal fun scaleSignedRatio(value: Double?, positiveMax: Double): Double {
    val v = value ?: return 50.0
    if (!v.isFinite() || positiveMax <= 0.0 || !positiveMax.isFinite()) return 50.0
    return (50.0 + (v / positiveMax * 50.0)).coerceIn(0.0, 100.0)
}

internal fun scaleScore(value: Double?, maxValue: Double): Double = scaleRatio(value, maxValue)

internal fun scaleInverse(value: Double?, badAt: Double): Double {
    val v = value ?: return 50.0
    if (!v.isFinite() || badAt <= 0.0 || !badAt.isFinite()) return 50.0
    return (100.0 - (v / badAt * 100.0)).coerceIn(0.0, 100.0)
}

internal fun hasCompanyProfile(info: StockInfo): Boolean {
    return !info.industry.isNullOrBlank() ||
        profileLocation(info) != null ||
        info.employees != null ||
        !info.website.isNullOrBlank()
}

internal fun hasMarketInfo(info: StockInfo): Boolean {
    return info.currentPrice != null ||
        info.prevClose != null ||
        info.week52High != null ||
        info.week52Low != null ||
        info.marketCap != null ||
        info.peRatio != null ||
        info.forwardPe != null ||
        info.priceToSales != null ||
        info.priceToBook != null ||
        info.beta != null ||
        info.targetMeanPrice != null ||
        normalizedRecommendation(info.recommendation) != null
}

internal fun hasFinancialSnapshot(info: StockInfo): Boolean {
    return info.totalRevenue != null ||
        info.revenueGrowth != null ||
        info.grossMargin != null ||
        info.operatingMargin != null ||
        info.profitMargin != null ||
        info.ebitdaMargin != null ||
        info.ebitda != null ||
        info.freeCashflow != null ||
        info.totalDebt != null ||
        info.debtToEquity != null ||
        info.returnOnEquity != null
}

internal fun profileLocation(info: StockInfo): String? {
    return listOfNotNull(
        info.city?.takeIf { it.isNotBlank() },
        info.country?.takeIf { it.isNotBlank() }
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

internal fun returnMetrics(points: List<PricePoint>, currency: String): List<DetailMetric> {
    if (points.size < 2) return emptyList()
    val periodMetrics = listOf(
        "1W" to 5,
        "1M" to 21,
        "3M" to 63,
        "6M" to 126,
        "1Y" to 252
    ).map { (label, days) ->
        val ret = periodReturn(points, days)
        DetailMetric(label, pct(ret), returnTone(ret))
    }
    val returns = points.zipWithNext { prev, next ->
        if (prev.close == 0.0) null else (next.close / prev.close) - 1.0
    }.filterNotNull()
    val vol = if (returns.size > 5) {
        val avg = returns.average()
        sqrt(returns.sumOf { (it - avg) * (it - avg) } / returns.size) * sqrt(252.0)
    } else null
    val maxDd = maxDrawdown(points.takeLast(min(points.size, 126)))
    return periodMetrics + listOf(
        DetailMetric("연간 변동성", pct(vol, signed = false), inverseTone(vol, 0.25, 0.45)),
        DetailMetric("6M 최대낙폭", pct(maxDd), inverseTone(maxDd?.let { -it }, 0.15, 0.30)),
        DetailMetric("최근가", fmtPx(points.last().close, currency), DetailTone.Primary)
    )
}

internal fun periodReturn(points: List<PricePoint>, days: Int): Double? {
    if (points.size < 2) return null
    val offset = min(days, points.lastIndex)
    val base = points[points.lastIndex - offset].close
    if (base == 0.0) return null
    return (points.last().close / base) - 1.0
}

internal fun maxDrawdown(points: List<PricePoint>): Double? {
    if (points.size < 2) return null
    var peak = points.first().close
    var maxDrawdown = 0.0
    points.forEach { point ->
        peak = max(peak, point.close)
        if (peak > 0.0) {
            maxDrawdown = min(maxDrawdown, (point.close - peak) / peak)
        }
    }
    return maxDrawdown
}

internal fun rebalanceTradeText(order: RebalanceOrder): String {
    return "변화 ${pct(order.deltaWeight)} · 거래 ${compactNumber(order.executableTradeValue)}"
}

internal fun backtestTitle(summary: BacktestSummary): String {
    return when {
        summary.sheet.contains("SmallCap", ignoreCase = true) -> "${summary.market} 스몰캡"
        else -> "${summary.market} 분석"
    }
}

internal fun newsMarketLabel(market: String): String {
    return when (market.uppercase(Locale.US)) {
        "US" -> "미국"
        "KR" -> "국내"
        "GLOBAL" -> "글로벌"
        else -> market
    }
}

internal fun freshness(meta: Map<String, String>): String {
    return meta["Generated"] ?: meta["Last_Updated"] ?: "-"
}

internal fun portfolioMetaHeadline(meta: Map<String, String>): String {
    return firstPortfolioMetaValue(
        meta,
        "Expected_Return",
        "Ann. Return (hist. est.)",
        "Ann. Return"
    )?.let { formatMetaPercent(it, signed = true) }
        ?: freshness(meta)
}

internal fun portfolioMetaSubtitle(meta: Map<String, String>): String {
    val cashWeight = firstPortfolioMetaValue(meta, "Cash_Weight", "Cash Weight")
        ?.let { formatMetaPercent(it, signed = false) }
        ?: "데이터 없음"
    val generated = firstPortfolioMetaValue(meta, "Generated", "Generated_At", "Last_Updated")
        ?: "데이터 없음"
    return "현금 비중 $cashWeight · 생성 시각 $generated"
}

internal fun firstPortfolioMetaValue(meta: Map<String, String>, vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.trim()?.takeIf { it.isNotBlank() }
    }
}

internal fun formatMetaPercent(value: String, signed: Boolean): String {
    if (value.contains("%")) return value
    val number = value.replace(",", "").toDoubleOrNull() ?: return value
    return pct(number, signed = signed)
}

internal fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch { block() }
}
