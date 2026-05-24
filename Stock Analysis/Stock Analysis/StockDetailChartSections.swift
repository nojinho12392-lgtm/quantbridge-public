import Charts
import Foundation
import SwiftUI

struct VolumeChartView: View {
    let points: [PricePoint]

    var body: some View {
        let maxVolume = max(points.compactMap(\.volume).max() ?? 1, 1)

        VStack(alignment: .leading, spacing: 4) {
            Text("거래량")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AppTheme.secondaryText)

            Chart(Array(points.enumerated()), id: \.element.id) { index, point in
                if let volume = point.volume {
                    BarMark(
                        x: .value("Trading Day", index),
                        y: .value("Volume", volume)
                    )
                    .foregroundStyle((point.close >= point.open ? Color.red : Color.blue).opacity(0.72))
                }
            }
            .chartYScale(domain: 0...maxVolume)
            .chartXScale(domain: -0.5...(Double(max(points.count - 1, 0)) + 0.5))
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .frame(height: 64)
        }
    }
}

struct RSIChartView: View {
    let points: [PricePoint]
    let values: [RSIChartPoint]

    private var visibleValues: [RSIChartPoint] {
        visibleRSI(values)
    }

    private var dateIndex: [Date: Int] {
        Dictionary(uniqueKeysWithValues: points.enumerated().map { index, point in
            (Calendar.current.startOfDay(for: point.date), index)
        })
    }

    var body: some View {
        if !visibleValues.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("RSI(14)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(AppTheme.secondaryText)
                    Spacer()
                    if let latest = visibleValues.last?.value {
                        Text(String(format: "%.1f", latest))
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(latest >= 70 ? .red : latest <= 30 ? .blue : .purple)
                    }
                }

                Chart {
                    RectangleMark(yStart: .value("Oversold", 0), yEnd: .value("Oversold", 30))
                        .foregroundStyle(Color.blue.opacity(0.08))
                    RectangleMark(yStart: .value("Overbought", 70), yEnd: .value("Overbought", 100))
                        .foregroundStyle(Color.red.opacity(0.08))

                    ForEach(visibleValues) { point in
                        if let index = xIndex(for: point.date) {
                            LineMark(
                                x: .value("Trading Day", index),
                                y: .value("RSI", point.value),
                                series: .value("Indicator", "RSI")
                            )
                            .foregroundStyle(.purple)
                            .lineStyle(StrokeStyle(lineWidth: 1.8))
                        }
                    }

                    ForEach([30.0, 50.0, 70.0], id: \.self) { level in
                        RuleMark(y: .value("Level", level))
                            .foregroundStyle(level == 70 ? .red.opacity(0.55) : level == 30 ? .blue.opacity(0.55) : .secondary.opacity(0.35))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    }
                }
                .chartYScale(domain: 0...100)
                .chartXScale(domain: -0.5...(Double(max(points.count - 1, 0)) + 0.5))
                .chartXAxis(.hidden)
                .chartYAxis {
                    AxisMarks(position: .trailing, values: [30, 50, 70]) { value in
                        AxisValueLabel {
                            if let number = value.as(Int.self) {
                                Text("\(number)")
                                    .font(.system(size: 8))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .frame(height: 92)
            }
        }
    }

    private func xIndex(for date: Date) -> Int? {
        dateIndex[Calendar.current.startOfDay(for: date)]
    }

    private func visibleRSI(_ points: [RSIChartPoint]) -> [RSIChartPoint] {
        guard let firstDate = self.points.first?.date,
              let lastDate = self.points.last?.date else {
            return []
        }
        return points.filter { $0.date >= firstDate && $0.date <= lastDate }
    }
}

struct MACDChartView: View {
    let points: [PricePoint]
    let values: [MACDChartPoint]

    private var visibleValues: [MACDChartPoint] {
        visibleMACD(values)
    }

    private var dateIndex: [Date: Int] {
        Dictionary(uniqueKeysWithValues: points.enumerated().map { index, point in
            (Calendar.current.startOfDay(for: point.date), index)
        })
    }

    var body: some View {
        if !visibleValues.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("MACD (12, 26, 9)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(AppTheme.secondaryText)

                Chart {
                    ForEach(visibleValues) { point in
                        if let index = xIndex(for: point.date) {
                            BarMark(
                                x: .value("Trading Day", index),
                                y: .value("Histogram", point.histogram)
                            )
                            .foregroundStyle(point.histogram >= 0 ? Color.red.opacity(0.55) : Color.blue.opacity(0.55))

                            LineMark(
                                x: .value("Trading Day", index),
                                y: .value("MACD", point.macd),
                                series: .value("Indicator", "MACD")
                            )
                            .foregroundStyle(.blue)
                            .lineStyle(StrokeStyle(lineWidth: 1.8))

                            LineMark(
                                x: .value("Trading Day", index),
                                y: .value("Signal", point.signal),
                                series: .value("Indicator", "Signal")
                            )
                            .foregroundStyle(.red)
                            .lineStyle(StrokeStyle(lineWidth: 1.4, dash: [5, 4]))
                        }
                    }

                    RuleMark(y: .value("Zero", 0))
                        .foregroundStyle(.secondary.opacity(0.35))
                }
                .chartXScale(domain: -0.5...(Double(max(points.count - 1, 0)) + 0.5))
                .chartXAxis(.hidden)
                .chartYAxis {
                    AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { value in
                        AxisValueLabel {
                            if let number = value.as(Double.self) {
                                Text(String(format: "%.2f", number))
                                    .font(.system(size: 8))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .frame(height: 120)
            }
        }
    }

    private func xIndex(for date: Date) -> Int? {
        dateIndex[Calendar.current.startOfDay(for: date)]
    }

    private func visibleMACD(_ points: [MACDChartPoint]) -> [MACDChartPoint] {
        guard let firstDate = self.points.first?.date,
              let lastDate = self.points.last?.date else {
            return []
        }
        return points.filter { $0.date >= firstDate && $0.date <= lastDate }
    }
}

struct RangeBarView: View {
    let low: Double
    let high: Double
    let current: Double
    let currency: String

    private var fraction: Double {
        high > low ? min(max((current - low) / (high - low), 0), 1) : 0.5
    }

    var body: some View {
        VStack(spacing: 5) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(LinearGradient(
                            colors: [.blue.opacity(0.5), .yellow.opacity(0.4), .red.opacity(0.5)],
                            startPoint: .leading,
                            endPoint: .trailing
                        ))
                        .frame(height: 6)
                    Circle()
                        .fill(.white)
                        .shadow(color: .black.opacity(0.25), radius: 2, x: 0, y: 1)
                        .frame(width: 13, height: 13)
                        .offset(x: proxy.size.width * fraction - 6.5, y: -3.5)
                }
            }
            .frame(height: 13)

            HStack {
                Text(fmtPx(low, currency: currency))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                Spacer()
                Text("52주 범위")
                    .font(.system(size: 9))
                    .foregroundStyle(.tertiary)
                Spacer()
                Text(fmtPx(high, currency: currency))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
    }
}
