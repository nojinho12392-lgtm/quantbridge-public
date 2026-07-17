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
internal fun CardBlock(
    modifier: Modifier = Modifier,
    useBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    QuantCard(
        modifier = modifier,
        role = if (useBorder) QuantCardRole.Information else QuantCardRole.Status,
        padding = 16.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun TickerAvatar(ticker: String, market: String?, size: Dp = 34.dp) {
    val symbol = remember(ticker) { shortTicker(ticker).uppercase(Locale.US) }
    val localLogo = remember(symbol) { localCompanyLogo(symbol) }
    val logoKey = remember(ticker, market) { "${market.orEmpty()}:$symbol" }
    val logoUrls = remember(ticker, market) {
        CompanyLogoMemoryCache.candidates(logoKey, companyLogoUrls(ticker, market))
    }
    var logoIndex by remember(logoUrls) { mutableStateOf(0) }
    val logoUrl = logoUrls.getOrNull(logoIndex)
    val shape = CircleShape
    Box(
        Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (localLogo != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = localLogo.background,
                shape = shape,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f))
            ) {
                Image(
                    painter = painterResource(localLogo.resId),
                    contentDescription = "$ticker logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(size * localLogo.paddingFraction)
                        .clip(shape),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (logoUrl == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        shortTicker(ticker).take(2),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shape = shape
            ) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = "$ticker logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(size * 0.04f)
                        .clip(shape),
                    contentScale = ContentScale.Fit,
                    onSuccess = {
                        CompanyLogoMemoryCache.markSuccess(logoKey, logoUrl)
                    },
                    onError = {
                        CompanyLogoMemoryCache.markFailure(logoUrl)
                        logoIndex += 1
                    }
                )
            }
        }
    }
}
