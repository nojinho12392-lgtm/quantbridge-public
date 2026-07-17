package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun sectorMoveColor(value: Double?): Color {
    val clean = value ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return if (clean >= 0.0) QuantPositive else QuantNegative
}

internal fun sectorThemeMatches(theme: SectorTheme, query: String): Boolean {
    val clean = query.trim().lowercase(Locale.US)
    if (clean.isBlank()) return true
    if (theme.label.lowercase(Locale.US).contains(clean)) return true
    return theme.members.any { member ->
        member.ticker.lowercase(Locale.US).contains(clean) ||
            member.name.lowercase(Locale.US).contains(clean) ||
            (member.sector ?: "").lowercase(Locale.US).contains(clean)
    }
}

internal fun sectorThemeSummary(themes: List<SectorTheme>): String {
    val first = themes.firstOrNull() ?: return "오늘 움직인 투자 테마를 시장별로 확인하세요."
    val second = themes.drop(1).firstOrNull()
    val firstLabel = sectorThemeDisplayLabel(first.label)
    return if (second != null) {
        val secondLabel = sectorThemeDisplayLabel(second.label)
        "${firstLabel}와 ${secondLabel}이 오늘 테마 흐름의 중심입니다."
    } else {
        "${firstLabel}이 오늘 가장 먼저 확인할 테마입니다."
    }
}

internal fun sectorThemeDirectionLabel(theme: SectorTheme): String {
    val value = theme.avgChangePct ?: return "데이터 확인 중"
    if (abs(value) < 0.003) return "혼조"
    return if (value >= 0.0) "상승 주도" else "하락 압력"
}

internal fun sectorThemeDecisionHeadline(theme: SectorTheme): String {
    if (theme.avgChangePct == null) {
        return "${sectorThemeDisplayLabel(theme.label)} 가격 데이터를 확인하는 중입니다."
    }
    val leader = theme.leader?.name ?: sectorThemeDisplayLabel(theme.label)
    return "${pct(theme.avgChangePct)} · ${leader} 중심으로 움직임이 커졌습니다."
}

internal fun sectorThemeNextAction(theme: SectorTheme): String {
    val spread = "상승 ${theme.risingCount} / 하락 ${theme.fallingCount}"
    return if (theme.avgReturn1M != null) {
        "$spread, 1개월 ${pct(theme.avgReturn1M)} 흐름을 함께 비교하세요."
    } else {
        "$spread 분포를 보고 변동 큰 기업부터 확인하세요."
    }
}

internal fun sectorThemeReason(theme: SectorTheme): String {
    val leaderText = theme.leader?.let { "${it.name} ${pct(it.dailyChangePct)}" } ?: "주도 기업 확인 중"
    val direction = sectorThemeDirectionLabel(theme)
    return "$leaderText 이 ${sectorThemeDisplayLabel(theme.label)} 흐름을 이끌고 있습니다. $direction 인지, 상승 ${theme.risingCount}개와 하락 ${theme.fallingCount}개의 폭이 넓어지는지 확인하세요."
}

internal fun sectorThemeIcon(label: String): LucideIcon {
    return when (sectorThemeDisplayLabel(label)) {
        "AI 칩/GPU" -> LucideIcon.Cpu
        "AI 서버/네트워크" -> LucideIcon.Network
        "AI 데이터센터/클라우드" -> LucideIcon.Server
        "AI 소프트웨어" -> LucideIcon.Bot
        "AI 전력/냉각" -> LucideIcon.AirVent
        "SMR" -> LucideIcon.Rocket
        "원자력" -> LucideIcon.Radio
        "HBM" -> LucideIcon.AudioWaveform
        "메모리/낸드" -> LucideIcon.HardDrive
        "파운드리" -> LucideIcon.Factory
        "반도체 설계" -> LucideIcon.CircuitBoard
        "CPU/엣지칩" -> LucideIcon.Microchip
        "반도체 소재" -> LucideIcon.Gem
        "전자/부품", "전기·전자" -> LucideIcon.Cable
        "반도체 장비" -> LucideIcon.MonitorCog
        "반도체 후공정/테스트" -> LucideIcon.Workflow
        "클라우드/SW" -> LucideIcon.Cloud
        "IT 서비스" -> LucideIcon.LineChart
        "사이버보안" -> LucideIcon.ShieldCheck
        "보안/서비스" -> LucideIcon.Eye
        "핀테크/결제" -> LucideIcon.CreditCard
        "은행" -> LucideIcon.Landmark
        "증권/자산운용" -> LucideIcon.BarChart3
        "보험" -> LucideIcon.BadgeDollarSign
        "전기차" -> LucideIcon.Zap
        "자동차" -> LucideIcon.Car
        "자동차 부품" -> LucideIcon.GitCompare
        "배터리" -> LucideIcon.Battery
        "배터리 소재" -> LucideIcon.Beaker
        "조선" -> LucideIcon.Ship
        "방산/항공" -> LucideIcon.Plane
        "기계/로봇" -> LucideIcon.Hammer
        "헬스케어" -> LucideIcon.HeartPulse
        "바이오/제약" -> LucideIcon.Pill
        "의료기기" -> LucideIcon.Stethoscope
        "헬스케어 서비스" -> LucideIcon.Hospital
        "에너지" -> LucideIcon.Fuel
        "정유/화학" -> LucideIcon.FlaskConical
        "전력/유틸리티" -> LucideIcon.Zap
        "클린에너지" -> LucideIcon.Leaf
        "소비/리테일" -> LucideIcon.ShoppingBag
        "이커머스" -> LucideIcon.ShoppingCart
        "음식료/필수소비" -> LucideIcon.Utensils
        "화장품/뷰티" -> LucideIcon.Palette
        "미디어/엔터" -> LucideIcon.Clapperboard
        "게임" -> LucideIcon.Gamepad2
        "여행/레저" -> LucideIcon.Hotel
        "통신" -> LucideIcon.RadioTower
        "리츠/부동산" -> LucideIcon.Building
        "부동산" -> LucideIcon.Building
        "건설/인프라" -> LucideIcon.Warehouse
        "소재/철강" -> LucideIcon.Pickaxe
        "소재" -> LucideIcon.Pickaxe
        "기술" -> LucideIcon.CircuitBoard
        "유틸리티" -> LucideIcon.Zap
        "금속" -> LucideIcon.Gem
        "비금속" -> LucideIcon.CircleArrowDown
        "운송/물류" -> LucideIcon.Truck
        else -> LucideIcon.LayoutDashboard
    }
}

internal fun sectorThemeDisplayLabel(label: String): String {
    val clean = label.trim()
    if (clean.isBlank()) return "기타"
    if (clean.any { it in '\uAC00'..'\uD7A3' }) return clean
    return portfolioIndustryLabel(ticker = "", name = clean, sector = clean)
}

internal fun sectorMemberCurrency(member: SectorThemeMember): String {
    return member.currency ?: marketCurrency(member.ticker, member.market)
}

internal fun sectorDailyChangeLabel(member: SectorThemeMember): String {
    return member.dailyChangeHorizon?.trim()?.takeIf { it.isNotEmpty() } ?: "오늘"
}

internal fun sectorMemberMarketLabel(market: String?): String {
    return when (market?.uppercase(Locale.US)) {
        "KR" -> "국내"
        "US" -> "미국"
        null, "" -> ""
        else -> market
    }
}

internal fun sectorMemberDetail(member: SectorThemeMember): DetailRequest {
    val currency = sectorMemberCurrency(member)
    val sourceDetail = when {
        member.inPortfolio -> "분석 상위 후보에 포함된 기업입니다."
        member.inSmallCap -> "스몰캡 후보군에서 함께 추적되는 기업입니다."
        else -> "섹터 테마 내 비교군으로 포함된 기업입니다."
    }
    return DetailRequest(
        ticker = member.ticker,
        name = member.name,
        currency = currency,
        market = member.market,
        sections = listOf(
            DetailSection(
                "섹터 흐름",
                listOf(
                    DetailMetric("현재가", portfolioPriceText(member.currentPrice, currency)),
                    DetailMetric(sectorDailyChangeLabel(member), pct(member.dailyChangePct), returnTone(member.dailyChangePct)),
                    DetailMetric("1개월", pct(member.return1M), returnTone(member.return1M))
                )
            ),
            DetailSection(
                "기본 정보",
                listOf(
                    DetailMetric("시가총액", cap(member.marketCap, currency)),
                    DetailMetric("점수", score(member.scoreValue))
                )
            )
        ),
        signals = listOf(
            DetailSignal("섹터 내 위치", sourceDetail, DetailTone.Primary),
            DetailSignal("확인할 숫자", "상세 시세 기준 당일 흐름과 1개월 흐름을 같은 테마 기업과 비교하세요.", DetailTone.Primary)
        ),
        factors = emptyList()
    )
}
