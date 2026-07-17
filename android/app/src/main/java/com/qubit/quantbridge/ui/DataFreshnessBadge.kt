@file:Suppress("FunctionNaming", "InvalidPackageDeclaration", "MagicNumber", "ReturnCount")

package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantWarning
import java.util.Locale
import kotlin.math.max

enum class DataFreshnessBadgeTone {
    Fresh,
    Delayed,
    Stale,
    Partial
}

data class DataFreshnessDisplay(
    val text: String,
    val detail: String,
    val tone: DataFreshnessBadgeTone,
    val usesDot: Boolean
)

fun dataFreshnessDisplay(
    source: String?,
    updatedAt: String?,
    nowMillis: Long = System.currentTimeMillis()
): DataFreshnessDisplay? {
    val cleanSource = source.orEmpty().trim().lowercase(Locale.US)
    if (cleanSource == "storage_snapshot") {
        return DataFreshnessDisplay(
            text = "부분 데이터",
            detail = "서버 저장 스냅샷 기준",
            tone = DataFreshnessBadgeTone.Partial,
            usesDot = true
        )
    }
    if (cleanSource != "storage") return null

    val instant = parsedUpdateInstant(updatedAt) ?: return null
    val ageMillis = max(0L, nowMillis - instant.toEpochMilli())
    val tone = when {
        ageMillis <= 10 * 60_000L -> DataFreshnessBadgeTone.Fresh
        ageMillis <= 60 * 60_000L -> DataFreshnessBadgeTone.Delayed
        else -> DataFreshnessBadgeTone.Stale
    }
    return DataFreshnessDisplay(
        text = relativeFreshnessText(ageMillis),
        detail = formattedUpdateTimestamp(updatedAt),
        tone = tone,
        usesDot = false
    )
}

private fun relativeFreshnessText(ageMillis: Long): String {
    val minutes = (ageMillis / 60_000L).toInt()
    if (minutes < 2) return "방금 전"
    if (minutes < 60) return "${minutes}분 전"
    val hours = minutes / 60
    if (hours < 24) return "${hours}시간 전"
    return "${max(1, hours / 24)}일 전"
}

@Composable
fun DataFreshnessBadge(source: String?, updatedAt: String?, compact: Boolean = true) {
    val display = dataFreshnessDisplay(source, updatedAt) ?: return
    val color = freshnessColor(display.tone)
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (display.usesDot) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            } else {
                LucideIconView(
                    icon = LucideIcon.CalendarClock,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = color
                )
            }
            Text(
                if (compact) display.text else "${display.text} · ${display.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun freshnessColor(tone: DataFreshnessBadgeTone): Color {
    return when (tone) {
        DataFreshnessBadgeTone.Fresh -> MaterialTheme.colorScheme.onSurfaceVariant
        DataFreshnessBadgeTone.Delayed, DataFreshnessBadgeTone.Partial -> QuantWarning
        DataFreshnessBadgeTone.Stale -> QuantDanger
    }
}
