package com.qubit.quantbridge

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlinx.coroutines.delay

internal fun WatchlistSyncStatus.visibleMessageText(): String? {
    return messageText?.takeUnless { message ->
        message.contains("rememberCoroutineScope", ignoreCase = true) ||
            message.contains("left the composition", ignoreCase = true)
    }
}

internal enum class WatchMarketFilter(val title: String) {
    All("ALL"),
    US("US"),
    KR("KR");

    fun matches(item: WatchlistItem): Boolean {
        return when (this) {
            All -> true
            US -> item.market.equals("US", ignoreCase = true)
            KR -> item.market.equals("KR", ignoreCase = true) || item.currency == "KRW"
        }
    }
}

internal enum class WatchSortOption(val title: String) {
    Signal("이슈순"),
    Added("최근 추가순"),
    Name("이름순"),
    Market("시장순")
}

internal enum class WatchAssetTab(val title: String) {
    All("전체"),
    Companies("기업"),
    Indicators("지수"),
    Etfs("ETF")
}

internal val WatchlistEtfTickers = setOf(
    "QQQ", "SPY", "VOO", "VTI", "DIA", "IWM", "SCHD", "SMH", "SOXX", "XLK",
    "XLF", "XLV", "VNQ", "TLT", "GLD", "ARKK", "069500", "360750", "379800",
    "305720", "305540", "453850"
)

internal fun WatchlistItem.isEtfWatchItem(): Boolean {
    if (isMarketIndicatorWatchItem()) return false
    return note.contains("ETF", ignoreCase = true) ||
        name.contains("ETF", ignoreCase = true) ||
        normalizedTicker(ticker) in WatchlistEtfTickers
}

internal fun WatchAssetTab.sectionTitle(): String {
    return when (this) {
        WatchAssetTab.All -> "관심 목록"
        WatchAssetTab.Companies -> "관심 기업"
        WatchAssetTab.Indicators -> "관심 지수"
        WatchAssetTab.Etfs -> "관심 ETF"
    }
}

internal fun WatchAssetTab.emptyTitle(): String {
    return when (this) {
        WatchAssetTab.All -> "관심 항목 없음"
        WatchAssetTab.Companies -> "관심 기업 없음"
        WatchAssetTab.Indicators -> "관심 지수 없음"
        WatchAssetTab.Etfs -> "관심 ETF 없음"
    }
}

internal fun WatchAssetTab.emptyMessage(): String {
    return when (this) {
        WatchAssetTab.All -> "기업, 지수, ETF를 관심에 추가하면 이곳에서 한 번에 확인할 수 있어요."
        WatchAssetTab.Companies -> "분석, 뉴스, 기업 상세 화면의 하트로 추가해보세요."
        WatchAssetTab.Indicators -> "주요 지수 화면에서 하트를 누르면 이곳에 모입니다."
        WatchAssetTab.Etfs -> "ETF 화면이나 ETF 상세에서 관심에 추가해보세요."
    }
}

internal data class WatchPriorityItem(
    val item: WatchlistItem,
    val insight: WatchCompanyInsight
)

internal data class WatchJudgmentTimelineItem(
    val id: String,
    val title: String,
    val detail: String,
    val source: String,
    val recordedAtMillis: Long?,
    val color: Color
)

internal data class WatchCompanyInsight(
    val title: String,
    val detail: String,
    val metrics: List<String>,
    val details: List<String>,
    val tone: DetailTone,
    val priority: Int,
    val updatedAt: String?,
    val linked: Boolean,
    val hasUpcomingEarnings: Boolean
)
