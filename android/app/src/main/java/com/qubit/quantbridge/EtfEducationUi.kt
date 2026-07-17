package com.qubit.quantbridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
import kotlinx.coroutines.delay

@Composable
internal fun EtfEducationScreen(onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "ETF 인사이트로 돌아가기")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("ETF 기본 가이드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("ETF를 볼 때 무엇부터 확인해야 하는지 정리했습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { EtfGuideHeroCard() }
        item {
            EtfGuideSection(
                icon = LucideIcon.PieChart,
                title = "ETF란 무엇인가요?",
                headline = "여러 자산을 한 바구니에 담아 주식처럼 거래하는 펀드입니다.",
                body = "ETF는 Exchange Traded Fund의 약자입니다. 특정 지수, 산업, 테마, 채권, 원자재, 통화 같은 바구니를 따라가도록 설계되고, 일반 주식처럼 장중에 사고팔 수 있습니다.",
                points = listOf(
                    EtfGuidePoint("한 종목으로 분산", "S&P 500 ETF를 사면 개별 500개 기업을 하나씩 고르지 않아도 시장 전체에 가까운 노출을 얻습니다.", LucideIcon.ListOrdered),
                    EtfGuidePoint("거래는 주식처럼", "펀드지만 거래소에 상장되어 있어 가격이 장중에 움직이고, 지정가/시장가 주문이 가능합니다.", LucideIcon.ChartCandlestick),
                    EtfGuidePoint("핵심은 추종 대상", "ETF 이름보다 어떤 지수와 규칙을 따라가는지가 실제 성과와 위험을 결정합니다.", LucideIcon.Target)
                )
            )
        }
        item {
            EtfGuideSection(
                icon = LucideIcon.SlidersHorizontal,
                title = "큐빗에서 ETF를 읽는 순서",
                headline = "가격 차트보다 먼저 ETF의 성격을 파악하면 판단 속도가 빨라집니다.",
                body = "ETF는 기업 실적보다 구성 방식이 중요합니다. 같은 AI ETF라도 반도체 중심인지, 소프트웨어 중심인지, 대형주 집중인지에 따라 완전히 다른 움직임을 보일 수 있습니다.",
                points = listOf(
                    EtfGuidePoint("1. 추종 대상", "지수형, 섹터형, 테마형, 채권형, 원자재형인지 먼저 구분합니다.", LucideIcon.Target),
                    EtfGuidePoint("2. Top10 비중", "상위 종목 비중이 높으면 사실상 몇 개 기업에 집중 투자하는 것과 비슷합니다.", LucideIcon.PieChart),
                    EtfGuidePoint("3. 총보수와 AUM", "보수는 장기 수익률을 갉아먹고, AUM이 너무 작으면 유동성이나 상장 유지 리스크가 커질 수 있습니다.", LucideIcon.Database)
                )
            )
        }
        item { EtfGuideChecklistCard() }
        item {
            EtfGuideSection(
                icon = LucideIcon.TriangleAlert,
                title = "주의해야 할 ETF",
                headline = "ETF라고 해서 모두 분산이 잘 된 것은 아닙니다.",
                body = "레버리지, 인버스, 초협소 테마, 거래량이 작은 ETF는 일반 장기 보유 ETF와 성격이 다릅니다. 특히 레버리지/인버스 ETF는 하루 수익률을 목표로 설계되는 경우가 많아 장기 성과가 직관과 다를 수 있습니다.",
                points = listOf(
                    EtfGuidePoint("레버리지/인버스", "방향을 맞춰도 장기 보유 중 복리 효과와 변동성 때문에 기대와 다른 결과가 나올 수 있습니다.", LucideIcon.Zap),
                    EtfGuidePoint("테마 집중", "AI, 우주, 2차전지처럼 이름은 넓어도 실제 구성은 일부 기업에 크게 쏠릴 수 있습니다.", LucideIcon.TriangleAlert),
                    EtfGuidePoint("환율과 금리", "해외 ETF와 채권 ETF는 주가 외에도 환율, 금리 변화가 성과에 크게 반영됩니다.", LucideIcon.Globe2)
                )
            )
        }
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .quantClickable(role = QuantPressRole.Card, onClick = onBack),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LucideIconView(
                        icon = LucideIcon.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        "ETF 목록에서 실제 상품 비교하기",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun EtfGuideHeroCard() {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = CircleShape) {
                LucideIconView(
                    icon = LucideIcon.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("핵심 결론", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "ETF는 종목 하나가 아니라 투자 규칙을 사는 상품입니다.",
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 30.sp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            "따라서 ETF를 볼 때는 오늘 가격이 올랐는지보다, 어떤 시장을 따라가는지, 무엇을 얼마나 담고 있는지, 비용과 유동성이 적절한지부터 확인하는 것이 좋습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
        )
    }
}

@Composable
internal fun EtfGuideSection(
    icon: LucideIcon,
    title: String,
    headline: String,
    body: String,
    points: List<EtfGuidePoint>
) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            headline,
            style = MaterialTheme.typography.titleLarge.copy(lineHeight = 27.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            points.forEach { point ->
                EtfGuidePointRow(point)
            }
        }
    }
}

@Composable
internal fun EtfGuidePointRow(point: EtfGuidePoint) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape = CircleShape) {
            LucideIconView(
                icon = point.icon,
                contentDescription = null,
                modifier = Modifier.padding(6.dp).size(15.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(point.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(
                point.detail,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun EtfGuideChecklistCard() {
    val checks = listOf(
        EtfGuidePoint("구성종목", "Top10 비중과 특정 기업 쏠림을 확인합니다.", LucideIcon.ListOrdered),
        EtfGuidePoint("섹터/지역 노출", "내가 생각한 ETF 성격과 실제 노출이 같은지 봅니다.", LucideIcon.Globe2),
        EtfGuidePoint("총보수", "장기 보유라면 낮은 보수가 누적 성과에 유리합니다.", LucideIcon.SlidersHorizontal),
        EtfGuidePoint("가격 흐름", "단기 변동성과 1개월 수익률이 시장 상황과 맞는지 비교합니다.", LucideIcon.LineChart)
    )
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIconView(
                icon = LucideIcon.Check,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = QuantGreen
            )
            Text("ETF 판단 체크리스트", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "목록에서 ETF를 고를 때 아래 4가지만 먼저 확인해도 대부분의 오해를 줄일 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        checks.forEach { point ->
            EtfGuidePointRow(point)
        }
    }
}
