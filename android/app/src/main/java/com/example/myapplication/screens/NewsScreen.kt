package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.myapplication.ui.theme.QuantBlue
import com.example.myapplication.ui.theme.QuantFavorite
import com.example.myapplication.ui.theme.QuantGreen
import com.example.myapplication.ui.theme.QuantLine
import com.example.myapplication.ui.theme.QuantMuted
import com.example.myapplication.ui.theme.QuantNegative
import com.example.myapplication.ui.theme.QuantPositive
import com.example.myapplication.ui.theme.QuantPurple
import com.example.myapplication.ui.theme.QuantWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun NewsScreen(
    app: QuantAppState,
    contentTopPadding: Dp = 10.dp,
    contentBottomPadding: Dp = FLOATING_NAV_CONTENT_INSET,
    showControls: Boolean = true,
    showSummary: Boolean = true,
    useImpactFeed: Boolean = true,
    newsViewModel: NewsViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("ALL") }
    var selectedArticle by remember { mutableStateOf<NewsItem?>(null) }

    LaunchedEffect(Unit) {
        newsViewModel.ensureNewsLoaded("ALL")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentTopPadding,
            end = 16.dp,
            bottom = contentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (showControls) {
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftSegmentSwitch(
                        options = listOf("ALL", "US", "KR"),
                        selected = market,
                        onSelect = {
                            market = it
                            newsViewModel.refreshNews(query, market)
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BorderlessSearchField(
                            query = query,
                            onQuery = { query = it },
                            placeholder = "종목명, 지수, 키워드 검색",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { newsViewModel.refreshNews(query, market) }) {
                            LucideIconView(
                                icon = LucideIcon.Search,
                                contentDescription = "뉴스 검색",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    newsViewModel.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (showSummary) {
            item {
                HeaderCard(
                    title = "시장 영향 뉴스",
                    value = "${newsViewModel.items.size}개",
                    subtitle = "원문은 링크로 열고, 큐빗 분석과 관련 가격 반응만 표시합니다.",
                    trailing = if (newsViewModel.loading) "동기화" else market,
                    quiet = true
                )
            }
        }
        if (newsViewModel.items.isEmpty()) {
            item {
                if (newsViewModel.loading) {
                    SkeletonLoadingCard(lineCount = 2)
                } else {
                    EmptyCard(
                        "뉴스 없음",
                        "표시할 외부 기사가 없습니다. 시장 선택이나 검색어를 바꿔보세요.",
                        lucideIcon = LucideIcon.Newspaper,
                        actionLabel = "다시 불러오기",
                        onAction = { newsViewModel.refreshNews(query, market) }
                    )
                }
            }
        } else {
            itemsIndexed(newsViewModel.items, key = { _, item -> item.id }) { index, item ->
                if (useImpactFeed) {
                    NewsImpactFeedCard(
                        item = item,
                        featured = index == 0,
                        onOpen = {
                            if (item.url.isNotBlank()) {
                                selectedArticle = item
                            }
                        }
                    )
                } else {
                    NewsCard(
                        item = item,
                        onOpen = {
                            if (item.url.isNotBlank()) {
                                selectedArticle = item
                            }
                        }
                    )
                }
            }
        }
    }
    selectedArticle?.let { item ->
        InAppNewsBrowserDialog(
            item = item,
            onDismiss = { selectedArticle = null }
        )
    }
}

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

private fun secureArticleUrl(rawUrl: String): String? {
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

private fun webViewNewsErrorMessage(error: WebResourceError?): String {
    val raw = error?.description?.toString().orEmpty()
    if (raw.contains("CLEARTEXT", ignoreCase = true) || raw.contains("cleartext", ignoreCase = true)) {
        return "이 기사 사이트가 보안 연결을 제공하지 않아 앱 내부에서 열 수 없습니다. 브라우저로 열어주세요."
    }
    return raw.ifBlank { "기사 페이지를 불러오지 못했습니다." }
}

@Composable
private fun NewsCard(item: NewsItem, onOpen: () -> Unit) {
    val clickableModifier = if (item.url.isBlank()) {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .quantClickable(role = QuantPressRole.Row, onClick = onOpen)
    }
    Surface(
        modifier = clickableModifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            NewsImpactHeader(item = item, timeText = newsRelativeTimeText(item))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            NewsRelatedImpactRow(item = item, tickerLimit = 3)
            NewsQubitAnalysisBlock(item = item, maxLines = 3)
            NewsFeedSourceRow(item)
        }
    }
}

@Composable
private fun NewsImpactFeedCard(
    item: NewsItem,
    featured: Boolean,
    onOpen: () -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    val clickableModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 10.dp)
        .clip(shape)
        .quantClickable(enabled = item.url.isNotBlank(), role = QuantPressRole.Card, onClick = onOpen)

    Surface(
        modifier = clickableModifier,
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            NewsAssetHeader(item)
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 28.sp,
                maxLines = if (featured) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
            NewsRelatedImpactRow(item = item, tickerLimit = 4)
            NewsQubitAnalysisBlock(item = item, maxLines = if (featured) 3 else 2)
            NewsFeedSourceRow(item)
        }
    }
}

@Composable
private fun NewsAssetHeader(item: NewsItem) {
    val ticker = newsAssetTicker(item)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (ticker == "NEWS") {
            NewsGlobalAssetAvatar()
        } else {
            TickerAvatar(ticker, item.market, size = 50.dp)
        }
        Text(
            newsAssetName(item, ticker),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        NewsImpactSignalPill(item)
    }
}

@Composable
private fun NewsGlobalAssetAvatar() {
    Surface(
        modifier = Modifier.size(50.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            LucideIconView(
                icon = LucideIcon.Globe2,
                contentDescription = "글로벌 뉴스",
                modifier = Modifier.size(23.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun NewsImpactSignalPill(item: NewsItem) {
    val color = newsSignalColor(item)
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LucideIconView(
            icon = if (item.impactLabel.lowercase(Locale.US) == "negative") LucideIcon.TrendingDown else LucideIcon.TrendingUp,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = color
        )
        Text(
            newsSignalLabel(item),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun NewsRelatedImpactRow(item: NewsItem, tickerLimit: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            "관련",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        newsRelatedTickers(item).take(tickerLimit).forEach { ticker ->
            Text(
                ticker,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        NewsMovePill(item)
    }
}

@Composable
private fun NewsFeedSourceRow(item: NewsItem) {
    val source = newsSourceText(item) ?: newsMarketLabel(item.market).ifBlank { "시장" }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "출처: $source",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            newsRelativeTimeText(item),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        if (item.url.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LucideIconView(
                    icon = LucideIcon.Globe2,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "원문 보기",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun newsAssetTicker(item: NewsItem): String {
    return newsRelatedTickers(item).firstOrNull()?.takeIf { it.isNotBlank() }
        ?: item.ticker.takeIf { it.isNotBlank() }
        ?: when (item.market.uppercase(Locale.US)) {
            "US" -> "SPY"
            "KR" -> "KOSPI"
            else -> "NEWS"
        }
}

private fun newsAssetName(item: NewsItem, ticker: String = newsAssetTicker(item)): String {
    if (ticker == "NEWS") return newsMarketLabel(item.market).ifBlank { "시장" }
    return localizedCompanyName(ticker, ticker, item.market).ifBlank { ticker }
}

private fun newsSignalLabel(item: NewsItem): String {
    return when (item.impactLabel.lowercase(Locale.US)) {
        "positive" -> "긍정"
        "negative" -> "부정"
        else -> "중립"
    }
}

@Composable
private fun newsSignalColor(item: NewsItem): Color {
    return when (item.impactLabel.lowercase(Locale.US)) {
        "positive" -> newsImpactColor(item)
        "negative" -> newsImpactColor(item)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun newsRelativeTimeText(item: NewsItem): String {
    val raw = item.publishedAt.trim()
    if (raw.isBlank()) return "방금"
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(raw) }.getOrNull()
    if (instant != null) {
        val minutes = max(0L, Duration.between(instant, Instant.now()).toMinutes())
        return when {
            minutes < 1L -> "방금"
            minutes < 60L -> "${minutes}분 전"
            minutes < 24L * 60L -> "${minutes / 60L}시간 전"
            minutes < 7L * 24L * 60L -> "${minutes / (24L * 60L)}일 전"
            else -> DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
    }
    return formattedUpdateTimestamp(raw)
}

@Composable
fun CompactNewsCard(item: NewsItem, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(8.dp))
            .quantClickable(enabled = item.url.isNotBlank(), role = QuantPressRole.Card, onClick = onOpen),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NewsImpactHeader(item = item, timeText = newsRelativeTimeText(item))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            NewsRelatedImpactRow(item = item, tickerLimit = 2)
            NewsQubitAnalysisBlock(item = item, maxLines = 2, compact = true)
            NewsRelatedFooter(item = item, tickerLimit = 2, showOpen = false, compact = true)
        }
    }
}

@Composable
private fun NewsImpactHeader(item: NewsItem, timeText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        NewsImpactPill(item)
        if (item.market.isNotBlank()) {
            Text(
                newsMarketLabel(item.market),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        NewsMovePill(item)
        Spacer(Modifier.weight(1f))
        Text(
            timeText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NewsQubitAnalysisBlock(item: NewsItem, maxLines: Int, compact: Boolean = false) {
    val impactText = item.impactReason.ifBlank { "큐빗이 관련 종목과 가격 반응을 확인한 뒤 원문 링크 확인이 필요한 뉴스로 분류했습니다." }
    if (impactText.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            LucideIconView(
                icon = LucideIcon.LineChart,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 13.dp else 15.dp),
                tint = newsImpactColor(item)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "큐빗 분석",
                        color = newsImpactColor(item),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        item.impactLabelKo.ifBlank { newsImpactFallbackLabel(item.impactLabel) },
                        color = newsImpactColor(item),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(newsImpactColor(item).copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
                Text(
                    impactText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun NewsRelatedFooter(item: NewsItem, tickerLimit: Int, showOpen: Boolean, compact: Boolean = false) {
    val source = newsSourceText(item)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "관련",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        newsRelatedTickers(item).take(tickerLimit).forEach { ticker ->
            Text(
                ticker,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        source?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (showOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIconView(
                    icon = LucideIcon.Newspaper,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 11.dp else 12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "원문 보기",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun NewsImpactPill(item: NewsItem) {
    val color = newsImpactColor(item)
    Text(
        item.impactLabelKo.ifBlank { newsImpactFallbackLabel(item.impactLabel) },
        color = color,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun NewsMovePill(item: NewsItem) {
    val change = item.relatedChangePct ?: return
    if (!change.isFinite()) return
    val color = marketMoveColor(change)
    val label = item.relatedChangeLabel.ifBlank { "관련" }
    val horizon = newsMoveHorizonLabel(item.relatedChangeHorizon)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        LucideIconView(
            icon = if (change >= 0.0) LucideIcon.TrendingUp else LucideIcon.TrendingDown,
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = color
        )
        Text(
            "$label$horizon ${pct(change)}",
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun newsMoveHorizonLabel(value: String): String {
    val clean = value.trim()
    return when (clean) {
        "오늘", "전장" -> " $clean"
        else -> ""
    }
}

@Composable
private fun newsImpactColor(item: NewsItem): Color {
    return when (item.impactLabel.lowercase(Locale.US)) {
        "positive" -> Color(0xFFD93025)
        "negative" -> Color(0xFF1A73E8)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun newsSourceText(item: NewsItem): String? {
    val source = item.source.trim()
    if (
        source.isBlank() ||
        source == "-" ||
        source.equals("QuantBridge", ignoreCase = true) ||
        source.equals("Qubit", ignoreCase = true) ||
        source.equals("큐빗", ignoreCase = true)
    ) {
        return null
    }
    return source
}

internal fun newsImpactFallbackLabel(label: String): String {
    return when (label.lowercase(Locale.US)) {
        "positive" -> "긍정"
        "negative" -> "부정"
        else -> "중립"
    }
}

private fun newsRelatedTickers(item: NewsItem): List<String> {
    return item.relatedTickers.ifEmpty {
        if (item.ticker.isBlank()) emptyList() else listOf(item.ticker)
    }
}
