package com.qubit.quantbridge

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
fun InAppNewsBrowserDialog(
    item: NewsItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val articleUrl = remember(item.url) { secureArticleUrl(item.url) }
    val originalUrl = remember(item.url) { item.url.trim().takeIf { it.isNotBlank() } }
    var isLoading by remember(articleUrl) { mutableStateOf(articleUrl != null) }
    var loadError by remember(articleUrl) {
        mutableStateOf(if (articleUrl == null) "기사 주소가 올바르지 않습니다." else null)
    }
    var showTimeoutHint by remember(articleUrl) { mutableStateOf(false) }
    val openInBrowser: () -> Unit = {
        runCatching {
            val target = originalUrl ?: articleUrl ?: return@runCatching
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
    }

    LaunchedEffect(articleUrl, isLoading, loadError) {
        showTimeoutHint = false
        if (isLoading && loadError == null) {
            delay(6_500)
            if (isLoading && loadError == null) showTimeoutHint = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, top = 12.dp, end = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            newsSourceText(item) ?: "뉴스",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = openInBrowser,
                        enabled = originalUrl != null || articleUrl != null
                    ) {
                        LucideIconView(
                            icon = LucideIcon.Globe2,
                            contentDescription = "브라우저로 열기",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        LucideIconView(
                            icon = LucideIcon.X,
                            contentDescription = "기사 닫기",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    articleUrl?.let { safeArticleUrl ->
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    overScrollMode = WebView.OVER_SCROLL_ALWAYS
                                    isVerticalScrollBarEnabled = true
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val requestedUrl = request?.url?.toString().orEmpty()
                                            val secureUrl = secureArticleUrl(requestedUrl)
                                            if (secureUrl != null && requestedUrl.startsWith("http://", ignoreCase = true)) {
                                                view?.loadUrl(secureUrl)
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            isLoading = true
                                            loadError = null
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            if (request?.isForMainFrame != false) {
                                                isLoading = false
                                                loadError = webViewNewsErrorMessage(error)
                                            }
                                        }
                                    }
                                    loadUrl(safeArticleUrl)
                                }
                            },
                            update = { webView ->
                                if (webView.url != safeArticleUrl) {
                                    isLoading = true
                                    loadError = null
                                    webView.loadUrl(safeArticleUrl)
                                }
                            }
                        )
                    }

                    loadError?.let { message ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(20.dp)
                        ) {
                            EmptyCard(
                                title = "기사 로딩 실패",
                                message = message,
                                lucideIcon = LucideIcon.Newspaper,
                                actionLabel = "브라우저로 열기",
                                onAction = openInBrowser
                            )
                        }
                    } ?: run {
                        if (isLoading) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 6.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Text(
                                        if (showTimeoutHint) "로딩이 지연되고 있어요" else "기사 불러오는 중",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (showTimeoutHint) {
                                        TextButton(onClick = openInBrowser) {
                                            Text("브라우저로 열기")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun secureArticleUrl(rawUrl: String): String? {
    val clean = rawUrl.trim()
    if (clean.isBlank()) return null
    if (clean.startsWith("//")) return "https:$clean"
    val uri = runCatching { Uri.parse(clean) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase(Locale.US)) {
        "https" -> clean
        "http" -> uri.buildUpon().scheme("https").build().toString()
        null -> "https://$clean"
        else -> null
    }
}

internal fun webViewNewsErrorMessage(error: WebResourceError?): String {
    val raw = error?.description?.toString().orEmpty()
    if (raw.contains("CLEARTEXT", ignoreCase = true) || raw.contains("cleartext", ignoreCase = true)) {
        return "이 기사 사이트가 보안 연결을 제공하지 않아 앱 내부에서 열 수 없습니다. 브라우저로 열어주세요."
    }
    return raw.ifBlank { "기사 페이지를 불러오지 못했습니다." }
}
