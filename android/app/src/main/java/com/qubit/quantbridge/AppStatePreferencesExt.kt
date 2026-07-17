package com.qubit.quantbridge

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qubit.quantbridge.network.AuthTokenProvider
import com.qubit.quantbridge.network.HttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

internal suspend fun QuantAppState.loadInvestmentProfile() {
    investmentProfile = userPreferences.investmentProfileSnapshot().normalized
}


internal suspend fun QuantAppState.loadInvestmentDecisions() {
    investmentDecisions = userPreferences.investmentDecisionsSnapshot()
        .associateBy { normalizedTicker(it.ticker) }
}


internal fun QuantAppState.updateInvestmentProfile(profile: InvestmentProfile) {
    val clean = profile.normalized
    investmentProfile = clean
    persistUserPreferences {
        userPreferences.setInvestmentProfile(clean)
    }
}


internal fun QuantAppState.saveLocalWatchlist() {
    persistUserPreferences {
        userPreferences.setWatchlist(watchlist.toList().map(::normalizeWatchlistItem))
    }
}


internal fun QuantAppState.saveInvestmentDecisions() {
    persistUserPreferences {
        userPreferences.setInvestmentDecisions(investmentDecisions.values.toList())
    }
}


internal fun QuantAppState.persistUserPreferences(block: suspend () -> Unit) {
    persistenceScope.launch {
        runCatching { block() }
    }
}
