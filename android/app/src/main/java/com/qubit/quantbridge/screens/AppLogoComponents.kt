package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantMuted
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import java.util.Locale

@Composable
fun MarketIndicatorLogoView(
    ticker: String,
    name: String,
    size: Dp = 34.dp,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    val label = remember(ticker, name) { marketIndicatorLogoText(ticker, name) }
    val fontSize = when (label.length) {
        in 0..3 -> 12.sp
        in 4..5 -> 9.sp
        else -> 7.sp
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = tint.copy(alpha = 0.11f),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 3.dp),
                color = tint,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

internal data class LocalCompanyLogo(
    val resId: Int,
    val background: Color,
    val paddingFraction: Float = 0.08f
)

internal fun localCompanyLogo(symbol: String): LocalCompanyLogo? {
    return when (symbol.uppercase(Locale.US)) {
        "EPSN" -> LocalCompanyLogo(R.drawable.company_logo_epsn, Color.Black, 0.02f)
        "RIGL" -> LocalCompanyLogo(R.drawable.company_logo_rigl, Color.White, 0.02f)
        "UNH" -> LocalCompanyLogo(R.drawable.company_logo_unh, Color.White, 0.06f)
        "MRVL" -> LocalCompanyLogo(R.drawable.company_logo_mrvl, Color.Black, 0.04f)
        "SNDK" -> LocalCompanyLogo(R.drawable.company_logo_sndk, Color.Black, 0.02f)
        else -> null
    }
}

internal object CompanyLogoMemoryCache {
    private val preferredUrls = mutableMapOf<String, String>()
    private val failedUrls = mutableSetOf<String>()

    fun candidates(key: String, urls: List<String>): List<String> {
        preferredUrls[key]?.let { preferred ->
            if (preferred in urls) return listOf(preferred)
        }
        return urls.filterNot { it in failedUrls }
    }

    fun markSuccess(key: String, url: String) {
        preferredUrls[key] = url
    }

    fun markFailure(url: String) {
        failedUrls += url
    }
}

@Composable
internal fun FavoriteButton(watched: Boolean, onClick: () -> Unit) {
    val tint = if (watched) QuantFavorite else QuantMuted
    val favoriteLabel = if (watched) "관심 종목 제거" else "관심 종목 추가"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (watched) QuantFavorite.copy(alpha = 0.14f) else Color.Transparent)
            .quantClickable(role = QuantPressRole.Icon, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = favoriteLabel
                onClick(label = favoriteLabel) {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (watched) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (watched) 27.dp else 25.dp)
        )
    }
}

@Composable
internal fun Kpi(label: String, value: String) {
    val valueColor = when {
        value.startsWith("+") -> QuantPositive
        value.startsWith("-") -> QuantNegative
        value.startsWith("x") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column {
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
}

@Composable
internal fun SectionTitle(title: String, count: String) {
    Row(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (count.isNotBlank()) Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
