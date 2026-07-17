package com.qubit.quantbridge

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

fun stableMarketIndicatorHistory(
    previous: Map<String, List<MarketIndicatorPoint>>,
    incoming: List<MarketIndicatorSeries>,
    quotes: List<MarketIndicatorQuote>
): Map<String, List<MarketIndicatorPoint>> {
    val quotesBySymbol = quotes.associateBy { normalizedTicker(it.symbol) }
    val output = linkedMapOf<String, List<MarketIndicatorPoint>>()
    val incomingSymbols = incoming.map { normalizedTicker(it.symbol) }.toSet()

    previous
        .filterKeys { normalizedTicker(it) !in incomingSymbols }
        .forEach { (symbol, points) -> output[symbol] = points }

    incoming.forEach { series ->
        val key = normalizedTicker(series.symbol)
        val quote = quotesBySymbol[key]
        val cleaned = stableMarketIndicatorPoints(series.points, quote)
        val previousPoints = previous[series.symbol] ?: previous[key]
        val selected = if (isUsableIndicatorSeries(cleaned) || previousPoints.isNullOrEmpty()) {
            cleaned
        } else {
            previousPoints
        }
        output[series.symbol] = selected
    }

    return output
}

fun stableMarketIndicatorPoints(
    points: List<MarketIndicatorPoint>,
    quote: MarketIndicatorQuote? = null
): List<MarketIndicatorPoint> {
    val sorted = points
        .filter { it.close.isFinite() && it.close > 0.0 }
        .sortedWith(compareBy<MarketIndicatorPoint> { parseMarketInstant(it.timestamp) ?: java.time.Instant.EPOCH }
            .thenBy { it.timestamp })

    if (sorted.isEmpty()) return emptyList()

    val deduped = linkedMapOf<String, MarketIndicatorPoint>()
    sorted.forEachIndexed { index, point ->
        val key = parseMarketInstant(point.timestamp)?.toEpochMilli()?.toString()
            ?: point.timestamp.ifBlank { "idx-$index" }
        deduped[key] = point
    }

    val clean = deduped.values.toList()
    if (clean.size < 4) return clean

    val median = clean.map { it.close }.medianOrNull() ?: return clean
    if (!median.isFinite() || median <= 0.0) return clean

    val threshold = marketIndicatorOutlierThreshold(quote)
    val filtered = clean.filter { point ->
        abs(point.close / median - 1.0) <= threshold
    }

    return if (filtered.size >= max(2, clean.size / 2)) filtered else clean
}

fun displayMarketIndicatorPoints(
    quote: MarketIndicatorQuote,
    points: List<MarketIndicatorPoint>
): List<MarketIndicatorPoint> {
    val clean = stableMarketIndicatorPoints(points, quote)
    if (isUsableIndicatorSeries(clean)) return clean

    val fallback = fallbackMarketIndicatorPoints(quote)
    return fallback.ifEmpty { clean }
}

fun fallbackMarketIndicatorPoints(quote: MarketIndicatorQuote): List<MarketIndicatorPoint> {
    val current = quote.value
    if (!current.isFinite() || current <= 0.0) return emptyList()

    val previous = when {
        quote.changeAbs?.isFinite() == true && current - quote.changeAbs > 0.0 -> current - quote.changeAbs
        quote.changePct?.isFinite() == true && quote.changePct > -0.95 -> current / (1.0 + quote.changePct)
        else -> current
    }
    if (!previous.isFinite() || previous <= 0.0) return emptyList()

    val delta = current - previous
    val movement = abs(delta)
    val amplitude = if (movement > 0.0) movement * 0.18 else 0.0
    val progress = listOf(0.0, 0.18, 0.33, 0.48, 0.62, 0.78, 1.0)
    val wave = listOf(0.0, -0.22, 0.14, -0.08, 0.18, -0.10, 0.0)

    return progress.mapIndexed { index, step ->
        val close = when (index) {
            0 -> previous
            progress.lastIndex -> current
            else -> (previous + delta * step + wave[index] * amplitude).coerceAtLeast(0.0001)
        }
        MarketIndicatorPoint(
            timestamp = "1970-01-01T00:${index.toString().padStart(2, '0')}:00Z",
            close = close
        )
    }
}

private fun isUsableIndicatorSeries(points: List<MarketIndicatorPoint>): Boolean {
    if (points.size < 2) return false
    val distinctCloseCount = points
        .map { (it.close * 1_000_000.0).roundToLong() }
        .toSet()
        .size
    return distinctCloseCount >= 2
}

private fun marketIndicatorOutlierThreshold(quote: MarketIndicatorQuote?): Double {
    val symbol = quote?.symbol?.let(::normalizedTicker).orEmpty()
    return when {
        symbol == "^VIX" -> 0.90
        quote?.category == "crypto" -> 0.85
        quote?.category == "commodity" -> 0.55
        quote?.category == "bond" -> 0.50
        else -> 0.35
    }
}

private fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}
