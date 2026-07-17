package com.qubit.quantbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

enum class Market(val title: String, val currency: String) {
    US("US", "USD"),
    KR("KR", "KRW")
}

enum class AppTab(val label: String, val icon: LucideIcon) {
    Home("홈", LucideIcon.LayoutDashboard),
    Search("검색", LucideIcon.Search),
    News("뉴스", LucideIcon.Newspaper),
    Etf("ETF", LucideIcon.PieChart),
    Portfolio("분석", LucideIcon.LineChart),
    SmallCap("스몰캡", LucideIcon.Target),
    Pulse("인사이트", LucideIcon.Lightbulb),
    Watch("관심", LucideIcon.Heart),
    Account("계정", LucideIcon.UserRound)
}

enum class DetailTone { Positive, Warning, Negative, Primary, Neutral }

enum class ChartOverlay(val label: String) {
    MA5("MA5"),
    MA20("MA20"),
    MA60("MA60"),
    Bollinger("볼린저"),
    Volume("거래량"),
    RSI("RSI")
}

enum class ChartPeriod(val label: String, val apiValue: String, val maxPoints: Int) {
    OneMonth("1달", "1mo", 24),
    ThreeMonths("3달", "3mo", 72),
    SixMonths("6달", "6mo", 132),
    OneYear("1년", "1y", 252),
    ThreeYears("3년", "3y", 756),
    FiveYears("5년", "5y", 1260)
}

enum class ChartMode(val label: String) {
    Candle("캔들"),
    Line("선")
}
