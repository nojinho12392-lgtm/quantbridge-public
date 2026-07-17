package com.qubit.quantbridge

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal const val ANALYSIS_PRICE_AUTO_REFRESH_MS = 300_000L
internal val PortfolioListRowMinHeight = 70.dp
internal val PortfolioListLogoSize = 43.dp
internal val PortfolioListVerticalPadding = 11.dp
internal val PortfolioListNamePriceGap = 5.dp
internal val PortfolioListUsNamePriceGap = 8.dp
val FLOATING_NAV_CONTENT_INSET = 104.dp

internal data class DiagnosticInfo(
    val title: String,
    val status: String,
    val summary: String,
    val details: List<String>
)
