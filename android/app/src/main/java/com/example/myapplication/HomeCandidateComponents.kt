package com.example.myapplication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.QuantBlue
import com.example.myapplication.ui.theme.QuantFavorite
import com.example.myapplication.ui.theme.QuantGreen
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantPositive
import com.example.myapplication.ui.theme.QuantPurple
import com.example.myapplication.ui.theme.QuantWarning

@Composable
fun QuickStatsGrid(
    usCount: Int,
    krCount: Int,
    smallCapCount: Int,
    earningsCount: Int,
    isLoading: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "미국 분석",
                value = quickStatValue(usCount, isLoading),
                icon = LucideIcon.PieChart,
                tint = QuantBlue,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "국내 분석",
                value = quickStatValue(krCount, isLoading),
                icon = LucideIcon.PieChart,
                tint = QuantGreen,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                label = "스몰캡",
                value = quickStatValue(smallCapCount, isLoading),
                icon = LucideIcon.Gem,
                tint = QuantWarning,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "실적",
                value = quickStatValue(earningsCount, isLoading),
                icon = LucideIcon.CalendarClock,
                tint = QuantPurple,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun quickStatValue(count: Int, isLoading: Boolean): String {
    return if (isLoading && count == 0) "..." else count.toString()
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    icon: LucideIcon,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QuantCardRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun CandidateStrip(
    title: String,
    onMore: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val headerModifier = Modifier
        .fillMaxWidth()
        .padding(top = 14.dp, bottom = 8.dp)
        .let { modifier ->
            if (onMore != null) modifier.quantClickable(role = QuantPressRole.Text, onClick = onMore) else modifier
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(headerModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onMore != null) {
                LucideIconView(
                    icon = LucideIcon.ChevronRight,
                    contentDescription = "$title 더보기",
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
        }
    }
}

@Composable
fun CandidateCard(
    ticker: String,
    name: String,
    market: String?,
    subtitle: String,
    rank: String?,
    headlineLabel: String,
    headlineValue: String,
    basis: String,
    reason: HomeCardReason,
    metrics: List<HomeCardMetric>,
    updatedAt: String?,
    watched: Boolean,
    onWatch: () -> Unit,
    onOpen: () -> Unit
) {
    val previewMetrics = metrics.take(2)
    Card(
        modifier = Modifier.width(276.dp).height(228.dp).quantClickable(role = QuantPressRole.Card, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(QuantCardRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp, pressedElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TickerAvatar(ticker, market, size = 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Text(ticker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        rank?.let { HomeRankChip(it) }
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                HomeFavoriteButton(watched, onWatch)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        headlineLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        headlineValue,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HomeReasonPill(reason)
            }
            HomeCardMetricList(previewMetrics)
            HomeCardTrustFooter(updatedAt, metrics)
        }
    }
}

data class HomeCardReason(val title: String, val detail: String, val tone: DetailTone)
data class HomeCardMetric(val label: String, val value: String)

@Composable
private fun HomeFavoriteButton(watched: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (watched) QuantFavorite.copy(alpha = 0.14f) else Color.Transparent)
            .quantClickable(role = QuantPressRole.Icon, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        LucideIconView(
            icon = LucideIcon.Heart,
            contentDescription = if (watched) "관심 해제" else "관심 추가",
            modifier = Modifier.size(if (watched) 24.dp else 23.dp),
            tint = if (watched) QuantFavorite else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HomeRankChip(rank: String) {
    Text(
        rank,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun HomeCardTrustFooter(updatedAt: String?, metrics: List<HomeCardMetric>) {
    val missingCount = metrics.count { it.value == "-" } + if (updatedAt.isNullOrBlank()) 1 else 0
    val detail = if (missingCount > 0) "부족 ${missingCount}개" else "근거 확인"
    val detailColor = if (missingCount > 0) QuantWarning else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            formattedUpdateTimestamp(updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = detailColor,
            maxLines = 1
        )
    }
}

@Composable
private fun HomeReasonPill(reason: HomeCardReason) {
    val color = homeToneColor(reason.tone)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            reason.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
        Text(
            reason.detail,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeCardMetricList(metrics: List<HomeCardMetric>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        metrics.forEach { metric ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    metric.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    metric.value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun homeSubtitle(market: String?, text: String): String {
    val marketLabel = if (market.equals(Market.KR.title, ignoreCase = true)) "KR" else "US"
    return "$marketLabel · ${text.ifBlank { "후보" }}"
}

fun portfolioHomeMetrics(stock: PortfolioStock, currency: String): List<HomeCardMetric> {
    return listOf(
        HomeCardMetric("Score", score(stock.totalScore)),
        HomeCardMetric("ROIC", pct(stock.roic, signed = false)),
        HomeCardMetric("성장", pct(stock.revGrowth)),
        HomeCardMetric("마진", pct(stock.grossMargin, signed = false)),
        HomeCardMetric("시총", cap(stock.marketCap, currency))
    )
}

fun portfolioHomeReason(stock: PortfolioStock): HomeCardReason {
    val expected = stock.expectedReturn
    val roic = stock.roic
    val growth = stock.revGrowth
    return when {
        expected != null && expected.isFinite() && expected > 0.0 ->
            HomeCardReason("기대수익", "모델 기준 ${pct(expected)} 후보입니다.", DetailTone.Primary)
        roic != null && roic.isFinite() && roic >= 0.15 ->
            HomeCardReason("퀄리티", "ROIC ${pct(roic, signed = false)}로 자본 효율이 좋습니다.", DetailTone.Positive)
        growth != null && growth.isFinite() && growth >= 0.15 ->
            HomeCardReason("성장", "매출 성장 ${pct(growth)}가 확인됩니다.", DetailTone.Positive)
        expected != null && expected.isFinite() && expected < 0.0 ->
            HomeCardReason("주의", "기대수익이 음수라 타이밍 확인이 필요합니다.", DetailTone.Warning)
        else ->
            HomeCardReason("확인", "상세에서 차트와 팩터 균형을 같이 보세요.", DetailTone.Neutral)
    }
}

fun portfolioHomeBasis(stock: PortfolioStock): String {
    val parts = buildList {
        add("기대수익")
        if (stock.totalScore?.isFinite() == true) add("종합점수")
        if (stock.roic?.isFinite() == true) add("ROIC")
        if (stock.revGrowth?.isFinite() == true) add("성장")
        if (stock.grossMargin?.isFinite() == true) add("마진")
    }.take(4)
    return "선정 기준 ${parts.joinToString(" · ")}"
}

fun smallCapHomeMetrics(stock: SmallCapStock, currency: String): List<HomeCardMetric> {
    return listOf(
        HomeCardMetric("시총", cap(stock.marketCap, currency)),
        HomeCardMetric("ROIC", pct(stock.roic, signed = false)),
        HomeCardMetric("성장", pct(stock.revGrowth)),
        HomeCardMetric("FCF", pct(stock.fcfMargin, signed = false)),
        HomeCardMetric("거래량", multipleText(stock.volumeSurge))
    )
}

fun smallCapHomeReason(stock: SmallCapStock): HomeCardReason {
    val score = stock.totalScore
    val accel = stock.revAccel
    val volume = stock.volumeSurge
    val debt = stock.debtEbitda
    return when {
        score != null && score.isFinite() && score >= 70.0 ->
            HomeCardReason("상위점수", "스몰캡 점수 ${"%.0f".format(score)}점으로 선별됐습니다.", DetailTone.Primary)
        accel != null && accel.isFinite() && accel > 0.0 ->
            HomeCardReason("성장가속", "매출 성장 가속 신호가 있습니다.", DetailTone.Positive)
        volume != null && volume.isFinite() && volume >= 1.5 ->
            HomeCardReason("거래량", "평소 대비 ${multipleText(volume)} 거래량입니다.", DetailTone.Primary)
        debt != null && debt.isFinite() && debt > 4.0 ->
            HomeCardReason("주의", "Debt/EBITDA 부담을 먼저 확인하세요.", DetailTone.Warning)
        else ->
            HomeCardReason("확인", "성장성, 현금흐름, 재무 리스크를 같이 보세요.", DetailTone.Neutral)
    }
}

fun smallCapHomeBasis(stock: SmallCapStock): String {
    val parts = buildList {
        add("총점")
        if (stock.marketCap?.isFinite() == true) add("시총")
        if (stock.revAccel?.isFinite() == true) add("성장가속")
        if (stock.fcfMargin?.isFinite() == true) add("FCF")
        if (stock.volumeSurge?.isFinite() == true) add("거래량")
    }.take(4)
    return "선정 기준 ${parts.joinToString(" · ")}"
}

fun earningsHomeMetrics(stock: EarningsStock): List<HomeCardMetric> {
    return listOf(
        HomeCardMetric("EPS", pct(stock.surprisePct)),
        HomeCardMetric("수익", pct(stock.returnSince)),
        HomeCardMetric("경과", daysText(stock.daysSince)),
        HomeCardMetric("거래량", multipleText(stock.volumeSurge)),
        HomeCardMetric("발표", compactDateText(stock.earningsDate))
    )
}

fun earningsHomeReason(stock: EarningsStock): HomeCardReason {
    val signal = stock.signalStrength
    val surprise = stock.surprisePct
    val returnSince = stock.returnSince
    val days = stock.daysSince
    return when {
        signal != null && signal.isFinite() && signal >= 1.0 ->
            HomeCardReason("강한시그널", "서프라이즈와 가격 반응이 함께 나왔습니다.", DetailTone.Primary)
        surprise != null && surprise.isFinite() && surprise > 0.0 ->
            HomeCardReason("서프라이즈", "EPS가 예상보다 ${pct(surprise)} 높았습니다.", DetailTone.Positive)
        returnSince != null && returnSince.isFinite() && returnSince > 0.0 ->
            HomeCardReason("가격반응", "발표 후 수익률 ${pct(returnSince)}입니다.", DetailTone.Positive)
        days != null && days.isFinite() && days <= 7.0 ->
            HomeCardReason("최근이벤트", "발표 후 ${days.toInt()}일째라 반응 확인 구간입니다.", DetailTone.Primary)
        else ->
            HomeCardReason("확인", "EPS, 수익률, 거래량 반응을 함께 보세요.", DetailTone.Neutral)
    }
}

fun earningsHomeBasis(stock: EarningsStock): String {
    val parts = buildList {
        add("EPS")
        if (stock.signalStrength?.isFinite() == true) add("Signal")
        if (stock.returnSince?.isFinite() == true) add("발표 후 수익률")
        if (stock.daysSince?.isFinite() == true) add("경과일")
        if (stock.volumeSurge?.isFinite() == true) add("거래량")
    }.take(4)
    return "선정 기준 ${parts.joinToString(" · ")}"
}

fun compactDateText(value: String?): String {
    val text = formattedUpdateTimestamp(value)
    return if (text == "-") "-" else text.take(10)
}

fun multipleText(value: Double?): String {
    return value?.takeIf { it.isFinite() }?.let { "x%.1f".format(it) } ?: "-"
}

fun daysText(value: Double?): String {
    return value?.takeIf { it.isFinite() }?.let { "${it.toInt()}일" } ?: "-"
}

@Composable
private fun homeToneColor(tone: DetailTone): Color {
    return when (tone) {
        DetailTone.Positive -> QuantGreen
        DetailTone.Warning -> QuantWarning
        DetailTone.Negative -> QuantNegative
        DetailTone.Primary -> MaterialTheme.colorScheme.primary
        DetailTone.Neutral -> MaterialTheme.colorScheme.onSurface
    }
}
