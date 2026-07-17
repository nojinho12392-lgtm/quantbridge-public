package com.qubit.quantbridge

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal suspend fun QuantApi.fetchNews(query: String, market: String, limit: Int = 40): Pair<Boolean, List<NewsItem>> {
    val path = "news/issues?q=${Uri.encode(query)}&market=${Uri.encode(market)}&limit=$limit"
    val json = request(path)
    return (json.cleanBool("configured") ?: false) to parseNewsItems(json.optJSONArray("items") ?: JSONArray())
}


internal suspend fun QuantApi.authenticate(email: String, password: String, displayName: String?, signup: Boolean): Pair<String, AuthUser> {
    val body = JSONObject()
        .put("email", email.trim())
        .put("password", password)
    if (signup) body.put("display_name", displayName?.trim().orEmpty())
    val json = request(if (signup) "auth/signup" else "auth/login", method = "POST", body = body)
    return json.getString("access_token") to parseUser(json.getJSONObject("user"))
}


internal suspend fun QuantApi.me(token: String): AuthUser = parseUser(request("auth/me", token = token).getJSONObject("user"))


internal suspend fun QuantApi.logout(token: String) {
    request("auth/logout", method = "POST", token = token)
}


internal suspend fun QuantApi.deleteAccount(token: String) {
    request("auth/me", method = "DELETE", token = token)
}


internal suspend fun QuantApi.fetchWatchlist(token: String): List<WatchlistItem> {
    return parseWatchlist(request("me/watchlist", token = token).optJSONArray("items") ?: JSONArray())
}


internal suspend fun QuantApi.saveWatchlist(item: WatchlistItem, token: String) {
    val body = JSONObject()
        .put("ticker", item.ticker)
        .put("name", item.name)
        .put("market", item.market)
        .put("currency", item.currency)
        .put("note", item.note)
    request("me/watchlist", method = "POST", token = token, body = body)
}


internal suspend fun QuantApi.deleteWatchlist(ticker: String, token: String) {
    request("me/watchlist/${Uri.encode(ticker)}", method = "DELETE", token = token)
}
