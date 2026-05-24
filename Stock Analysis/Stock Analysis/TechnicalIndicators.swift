import Foundation

struct ChartIndicatorSet {
    let ma5: [MovingAveragePoint]
    let ma20: [MovingAveragePoint]
    let ma120: [MovingAveragePoint]
    let bollinger: [BollingerPoint]
    let rsi: [RSIChartPoint]
    let macd: [MACDChartPoint]

    static let empty = ChartIndicatorSet(
        ma5: [],
        ma20: [],
        ma120: [],
        bollinger: [],
        rsi: [],
        macd: []
    )

    init(points: [PricePoint]) {
        self.ma5 = movingAverage(points: points, window: 5)
        self.ma20 = movingAverage(points: points, window: 20)
        self.ma120 = movingAverage(points: points, window: 120)
        self.bollinger = bollingerBands(points: points)
        self.rsi = calculateRSI(points: points)
        self.macd = calculateMACD(points: points)
    }

    private init(
        ma5: [MovingAveragePoint],
        ma20: [MovingAveragePoint],
        ma120: [MovingAveragePoint],
        bollinger: [BollingerPoint],
        rsi: [RSIChartPoint],
        macd: [MACDChartPoint]
    ) {
        self.ma5 = ma5
        self.ma20 = ma20
        self.ma120 = ma120
        self.bollinger = bollinger
        self.rsi = rsi
        self.macd = macd
    }
}

func movingAverage(points: [PricePoint], window: Int) -> [MovingAveragePoint] {
    guard window > 1, !points.isEmpty else { return [] }

    var sum = 0.0
    return points.indices.map { index in
        sum += points[index].close
        if index >= window {
            sum -= points[index - window].close
        }
        let divisor = Double(min(index + 1, window))
        let point = points[index]
        return MovingAveragePoint(
            id: "\(point.id)-ma\(window)",
            date: point.date,
            value: sum / divisor
        )
    }
}

func trendLine(points: [PricePoint]) -> [TrendLinePoint] {
    guard points.count >= 2 else { return [] }

    let n = Double(points.count)
    let xs = points.indices.map(Double.init)
    let ys = points.map(\.close)
    let sumX = xs.reduce(0, +)
    let sumY = ys.reduce(0, +)
    let sumXY = zip(xs, ys).reduce(0) { $0 + $1.0 * $1.1 }
    let sumXX = xs.reduce(0) { $0 + $1 * $1 }
    let denominator = n * sumXX - sumX * sumX
    guard denominator != 0 else { return [] }

    let slope = (n * sumXY - sumX * sumY) / denominator
    let intercept = (sumY - slope * sumX) / n

    guard let first = points.first, let last = points.last else { return [] }
    return [
        TrendLinePoint(id: "\(first.id)-trend-start", date: first.date, value: intercept),
        TrendLinePoint(id: "\(last.id)-trend-end", date: last.date, value: slope * Double(points.count - 1) + intercept)
    ]
}

func bollingerBands(points: [PricePoint], window: Int = 20, width: Double = 2) -> [BollingerPoint] {
    guard points.count >= window else { return [] }

    return points.indices.compactMap { index in
        guard index >= window - 1 else { return nil }
        let slice = points[(index - window + 1)...index].map(\.close)
        let mean = slice.reduce(0, +) / Double(window)
        let variance = slice.reduce(0) { $0 + pow($1 - mean, 2) } / Double(window)
        let std = sqrt(variance)
        let point = points[index]
        return BollingerPoint(
            id: "\(point.id)-bb",
            date: point.date,
            upper: mean + width * std,
            middle: mean,
            lower: mean - width * std
        )
    }
}

func calculateRSI(points: [PricePoint], period: Int = 14) -> [RSIChartPoint] {
    guard points.count > period else { return [] }

    var result: [RSIChartPoint] = []
    for index in points.indices where index >= period {
        let window = points[(index - period + 1)...index]
        var gains = 0.0
        var losses = 0.0
        for pair in zip(window.dropLast(), window.dropFirst()) {
            let delta = pair.1.close - pair.0.close
            if delta >= 0 {
                gains += delta
            } else {
                losses += -delta
            }
        }
        let avgGain = gains / Double(period)
        let avgLoss = losses / Double(period)
        let value: Double
        if avgLoss == 0 {
            value = 100
        } else {
            let rs = avgGain / avgLoss
            value = 100 - (100 / (1 + rs))
        }
        let point = points[index]
        result.append(RSIChartPoint(id: "\(point.id)-rsi", date: point.date, value: value))
    }
    return result
}

func calculateMACD(points: [PricePoint]) -> [MACDChartPoint] {
    guard points.count >= 30 else { return [] }

    let closes = points.map(\.close)
    let ema12 = ema(values: closes, span: 12)
    let ema26 = ema(values: closes, span: 26)
    let macdLine = zip(ema12, ema26).map(-)
    let signalLine = ema(values: macdLine, span: 9)

    return points.indices.map { index in
        let histogram = macdLine[index] - signalLine[index]
        return MACDChartPoint(
            id: "\(points[index].id)-macd",
            date: points[index].date,
            macd: macdLine[index],
            signal: signalLine[index],
            histogram: histogram
        )
    }
}

func regressionChannel(points: [PricePoint]) -> [RegressionChannelPoint] {
    guard points.count >= 5 else { return [] }

    let n = Double(points.count)
    let xs = points.indices.map(Double.init)
    let ys = points.map(\.close)
    let sumX = xs.reduce(0, +)
    let sumY = ys.reduce(0, +)
    let sumXY = zip(xs, ys).reduce(0) { $0 + $1.0 * $1.1 }
    let sumXX = xs.reduce(0) { $0 + $1 * $1 }
    let denominator = n * sumXX - sumX * sumX
    guard denominator != 0 else { return [] }

    let slope = (n * sumXY - sumX * sumY) / denominator
    let intercept = (sumY - slope * sumX) / n
    let trendValues = xs.map { slope * $0 + intercept }
    let residuals = zip(ys, trendValues).map { $0 - $1 }
    let std = sqrt(residuals.reduce(0) { $0 + $1 * $1 } / n)

    return points.indices.map { index in
        let trend = trendValues[index]
        return RegressionChannelPoint(
            id: "\(points[index].id)-regression",
            date: points[index].date,
            trend: trend,
            upper1: trend + std,
            lower1: trend - std,
            upper2: trend + 2 * std,
            lower2: trend - 2 * std
        )
    }
}

func supportResistance(points: [PricePoint], levelCount: Int = 3) -> [PriceLevel] {
    guard points.count >= 20 else { return [] }

    let order = max(3, points.count / 20)
    var highs: [Double] = []
    var lows: [Double] = []

    for index in order..<(points.count - order) {
        let window = points[(index - order)...(index + order)]
        let high = points[index].high
        let low = points[index].low
        if high >= (window.map(\.high).max() ?? high) {
            highs.append(high)
        }
        if low <= (window.map(\.low).min() ?? low) {
            lows.append(low)
        }
    }

    let resistance = clusteredLevels(highs, levelCount: levelCount).map {
        PriceLevel(price: $0.price, strength: $0.strength, isResistance: true)
    }
    let support = clusteredLevels(lows, levelCount: levelCount).map {
        PriceLevel(price: $0.price, strength: $0.strength, isResistance: false)
    }
    return resistance + support
}

private func ema(values: [Double], span: Int) -> [Double] {
    guard let first = values.first else { return [] }
    let alpha = 2.0 / Double(span + 1)
    var result = [first]
    for value in values.dropFirst() {
        result.append(alpha * value + (1 - alpha) * (result.last ?? value))
    }
    return result
}

private func clusteredLevels(_ prices: [Double], levelCount: Int) -> [(price: Double, strength: Int)] {
    guard !prices.isEmpty else { return [] }
    let sorted = prices.sorted()
    var clusters: [[Double]] = [[sorted[0]]]

    for price in sorted.dropFirst() {
        if let first = clusters.last?.first, price <= first * 1.02 {
            clusters[clusters.count - 1].append(price)
        } else {
            clusters.append([price])
        }
    }

    return clusters
        .map { cluster in
            let sortedCluster = cluster.sorted()
            let median = sortedCluster[sortedCluster.count / 2]
            return (price: median, strength: cluster.count)
        }
        .sorted { $0.strength > $1.strength }
        .prefix(levelCount)
        .map { $0 }
}
