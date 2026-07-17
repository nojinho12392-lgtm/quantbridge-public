package com.qubit.quantbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.qubit.quantbridge.ui.theme.QuantBlue
import com.qubit.quantbridge.ui.theme.QuantDanger
import com.qubit.quantbridge.ui.theme.QuantFavorite
import com.qubit.quantbridge.ui.theme.QuantGreen
import com.qubit.quantbridge.ui.theme.QuantLine
import com.qubit.quantbridge.ui.theme.QuantMuted
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import com.qubit.quantbridge.ui.theme.QuantPurple
import com.qubit.quantbridge.ui.theme.QuantWarning
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
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    app: QuantAppState,
    onDelete: () -> Unit,
    accountViewModel: AccountViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var signup by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var showInvestmentProfileSheet by remember { mutableStateOf(false) }
    val investmentProfileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = FLOATING_NAV_CONTENT_INSET
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val currentUser = accountViewModel.user ?: app.user
        val accountLoading = accountViewModel.loading || app.accountLoading
        val accountError = accountViewModel.error ?: app.error
        if (currentUser != null) {
            item {
                AccountProfileCard(
                    user = currentUser,
                    watchlistCount = app.watchlist.size,
                    syncText = app.watchlistSyncStatus.messageText ?: "정상"
                )
            }
            item {
                AccountSettingsCard(
                    watchlistCount = app.watchlist.size,
                    syncText = app.watchlistSyncStatus.messageText ?: "정상",
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
            }
            item {
                InvestmentProfileCard(
                    profile = app.investmentProfile,
                    onEdit = { showInvestmentProfileSheet = true }
                )
            }
            item {
                AccountNotificationCard()
            }
            item {
                AccountSecurityCard()
            }
            item {
                AccountManagementCard(
                    onLogout = {
                        successMessage = null
                        app.error = null
                        accountViewModel.clearError()
                        scope.launchSafely {
                            accountViewModel.logout()
                            app.clearAccountSession(clearWatchlist = false)
                        }
                    },
                    onDelete = onDelete
                )
            }
        } else if (accountViewModel.sessionRestoring || app.accountSessionRestoring || accountViewModel.token != null || app.token != null) {
            item {
                AccountSessionCheckingCard()
            }
            item {
                InvestmentProfileCard(
                    profile = app.investmentProfile,
                    onEdit = { showInvestmentProfileSheet = true }
                )
            }
        } else {
            item {
                AccountAuthCard(
                    signup = signup,
                    name = name,
                    onNameChange = {
                        name = it
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    },
                    email = email,
                    onEmailChange = {
                        email = it
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    },
                    password = password,
                    onPasswordChange = {
                        password = it
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    },
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    errorMessage = accountError,
                    successMessage = successMessage,
                    loading = accountLoading,
                    canSubmit = !accountLoading && email.contains("@") && password.length >= 8 && (!signup || name.isNotBlank()),
                    onSubmit = {
                        scope.launchSafely {
                            val session = accountViewModel.login(email, password, name, signup)
                            if (session != null) {
                                app.adoptAccountSession(session)
                                password = ""
                                successMessage = "로그인과 Watchlist 동기화가 완료됐습니다."
                            }
                        }
                    }
                )
            }
            item {
                AccountCreateButton(
                    text = if (signup) "이미 계정이 있어요" else "새 계정 만들기",
                    onClick = {
                        signup = !signup
                        app.error = null
                        accountViewModel.clearError()
                        successMessage = null
                    }
                )
            }
            item {
                InvestmentProfileCard(
                    profile = app.investmentProfile,
                    onEdit = { showInvestmentProfileSheet = true }
                )
            }
        }
    }

    if (showInvestmentProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showInvestmentProfileSheet = false },
            sheetState = investmentProfileSheetState
        ) {
            InvestmentProfileSheet(
                profile = app.investmentProfile,
                onSave = {
                    app.updateInvestmentProfile(it)
                    showInvestmentProfileSheet = false
                },
                onDismiss = { showInvestmentProfileSheet = false }
            )
        }
    }
}

@Composable
internal fun AccountSessionCheckingCard() {
    CardBlock {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "로그인 상태 확인 중",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "저장된 계정을 불러오는 동안 입력창을 잠시 숨깁니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
internal fun AccountProfileCard(user: AuthUser, watchlistCount: Int, syncText: String) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    user.displayName.take(1).uppercase(Locale.getDefault()),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "로그인됨",
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountMiniMetric("관심", "${watchlistCount}개", Modifier.weight(1f))
            AccountMiniMetric("동기화", syncText, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun AccountMiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AccountSettingsCard(watchlistCount: Int, syncText: String, appVersion: String) {
    CardBlock {
        Text("내 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AccountSettingRow(
            icon = LucideIcon.Heart,
            title = "관심 종목",
            detail = "홈 브리핑과 관심 탭에 반영됩니다.",
            value = "${watchlistCount}개"
        )
        AccountSettingRow(
            icon = LucideIcon.LayoutDashboard,
            title = "홈 브리핑",
            detail = "관심 종목, 시장 뉴스, 실적 이벤트를 우선 보여줍니다.",
            value = "자동"
        )
        AccountSettingRow(
            icon = LucideIcon.RefreshCw,
            title = "데이터 동기화",
            detail = "로그인한 기기에서 관심 종목을 이어서 볼 수 있습니다.",
            value = syncText
        )
        AccountSettingRow(
            icon = LucideIcon.Lightbulb,
            title = "앱 정보",
            detail = "현재 설치된 큐빗 버전입니다.",
            value = appVersion
        )
    }
}

@Composable
internal fun InvestmentProfileCard(profile: InvestmentProfile, onEdit: () -> Unit) {
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.SlidersHorizontal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("내 투자 기준", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    profile.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
            TextButton(onClick = onEdit) {
                LucideIconView(
                    icon = LucideIcon.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (profile.isConfigured) "수정" else "진단")
            }
        }

        if (profile.isConfigured) {
            Text(
                profile.operatingStatement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InvestmentProfileMetricPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                InvestmentProfileMetricPill(profile.consistencyLabel, profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
            }
            InvestmentProfilePill(profile.headline, LucideIcon.Target)
            InvestmentProfilePill(profile.guardrailSummary, LucideIcon.ShieldCheck)
            profile.consistencyNotes.firstOrNull()?.let {
                InvestmentProfileNotice(it)
            }
        } else {
            InvestmentProfilePill("계좌 연결 없이도 나만의 판단 기준을 먼저 세웁니다.", LucideIcon.Lightbulb)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InvestmentProfileSheet(
    profile: InvestmentProfile,
    onSave: (InvestmentProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(profile) { mutableStateOf(profile.normalized) }
    var currentStep by remember(profile) { mutableStateOf(InvestmentProfileStep.Experience) }
    val experienceOptions = listOf("처음 시작", "기본 분석 가능", "숙련")
    val horizonOptions = listOf("1개월", "3개월", "6개월", "1년+")
    val riskOptions = listOf("보수적", "균형", "성장", "공격적")
    val styleOptions = listOf("성장주", "가치주", "배당", "퀄리티", "모멘텀")
    val avoidanceOptions = listOf("급등락", "적자 지속", "고평가", "높은 부채", "낮은 거래량")
    val dropResponseOptions = listOf("가설부터 재검토", "확인 조건까지 보류", "분할 관찰", "손실 한도 도달 시 종료")
    val overheatedResponseOptions = listOf("비교 후보 먼저 보기", "가격 안정 후 보기", "소액 관심만 유지", "모멘텀 근거 확인")
    val steps = InvestmentProfileStep.values().toList()
    val currentIndex = steps.indexOf(currentStep).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Target,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("내 투자 기준", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "후보를 보기 전에 내 판단 규칙을 먼저 정합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        InvestmentProfileWizardHeader(
            step = currentStep,
            currentIndex = currentIndex + 1,
            totalCount = steps.size
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (currentStep) {
                InvestmentProfileStep.Experience -> InvestmentProfileOptionList(
                    options = experienceOptions,
                    selected = draft.experience,
                    onSelect = { draft = draft.copy(experience = if (draft.experience == it) "" else it) }
                )
                InvestmentProfileStep.Horizon -> InvestmentProfileOptionList(
                    options = horizonOptions,
                    selected = draft.horizon,
                    onSelect = { draft = draft.copy(horizon = if (draft.horizon == it) "" else it) }
                )
                InvestmentProfileStep.Risk -> InvestmentProfileOptionList(
                    options = riskOptions,
                    selected = draft.riskTolerance,
                    onSelect = { draft = draft.copy(riskTolerance = if (draft.riskTolerance == it) "" else it) }
                )
                InvestmentProfileStep.Style -> InvestmentProfileOptionList(
                    options = styleOptions,
                    selected = draft.style,
                    onSelect = { draft = draft.copy(style = if (draft.style == it) "" else it) }
                )
                InvestmentProfileStep.Avoidances -> InvestmentProfileMultiOptionList(
                    options = avoidanceOptions,
                    selected = draft.avoidances,
                    onSelect = { label ->
                        draft = if (label in draft.avoidances) {
                            draft.copy(avoidances = draft.avoidances - label)
                        } else {
                            draft.copy(avoidances = draft.avoidances + label)
                        }
                    }
                )
                InvestmentProfileStep.DropScenario -> InvestmentProfileOptionList(
                    options = dropResponseOptions,
                    selected = draft.dropResponse,
                    onSelect = { draft = draft.copy(dropResponse = if (draft.dropResponse == it) "" else it) }
                )
                InvestmentProfileStep.HeatScenario -> InvestmentProfileOptionList(
                    options = overheatedResponseOptions,
                    selected = draft.overheatedResponse,
                    onSelect = { draft = draft.copy(overheatedResponse = if (draft.overheatedResponse == it) "" else it) }
                )
                InvestmentProfileStep.Summary -> InvestmentProfileSummaryPanel(draft.normalized)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
            Spacer(Modifier.weight(1f))
            if (currentStep != InvestmentProfileStep.Experience) {
                OutlinedButton(
                    onClick = {
                        steps.getOrNull(currentIndex - 1)?.let { currentStep = it }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("이전")
                }
            }
            Button(
                onClick = {
                    if (currentStep == InvestmentProfileStep.Summary) {
                        onSave(draft.normalized)
                    } else {
                        steps.getOrNull(currentIndex + 1)?.let { currentStep = it }
                    }
                },
                modifier = Modifier.height(52.dp)
            ) {
                Text(if (currentStep == InvestmentProfileStep.Summary) "저장" else "다음")
            }
        }
    }
}

internal enum class InvestmentProfileStep(
    val title: String,
    val subtitle: String
) {
    Experience(
        "투자 경험은 어느 정도인가요?",
        "설명 깊이와 리스크 문구의 톤을 맞추는 기준입니다."
    ),
    Horizon(
        "얼마 동안 지켜볼 생각인가요?",
        "관심 종목을 단기 신호로 볼지, 긴 흐름으로 볼지 나눕니다."
    ),
    Risk(
        "변동성은 어디까지 괜찮나요?",
        "같은 랭킹이라도 내 기준에 맞는 후보를 더 차분히 보게 합니다."
    ),
    Style(
        "끌리는 투자 스타일은 무엇인가요?",
        "성장, 가치, 배당처럼 먼저 보고 싶은 관점을 정합니다."
    ),
    Avoidances(
        "피하고 싶은 신호가 있나요?",
        "여러 개를 골라도 괜찮습니다."
    ),
    DropScenario(
        "20% 하락하면 어떻게 할까요?",
        "흔들릴 때 미리 정한 행동 기준이 있어야 가설을 차분하게 복기할 수 있습니다."
    ),
    HeatScenario(
        "좋아 보이지만 너무 올랐다면?",
        "기회처럼 보이는 순간에도 비교와 확인 조건을 먼저 둘지 정합니다."
    ),
    Summary(
        "이 기준으로 저장할까요?",
        "저장된 기준은 이 기기에 보관되며 후보를 볼 때 함께 확인할 개인 기준입니다."
    )
}

@Composable
internal fun InvestmentProfileWizardHeader(
    step: InvestmentProfileStep,
    currentIndex: Int,
    totalCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(totalCount) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (index < currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                        )
                )
            }
        }
        Text(
            "$currentIndex / $totalCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Text(step.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
        Text(step.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
    }
}

@Composable
internal fun InvestmentProfilePill(label: String, icon: LucideIcon) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        LucideIconView(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun InvestmentProfileMetricPill(title: String, value: String, icon: LucideIcon, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun InvestmentProfileNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(QuantWarning.copy(alpha = 0.09f))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        LucideIconView(
            icon = LucideIcon.TriangleAlert,
            contentDescription = null,
            tint = QuantWarning,
            modifier = Modifier.padding(top = 2.dp).size(14.dp)
        )
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp
        )
    }
}

@Composable
internal fun InvestmentProfileOptionList(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            InvestmentProfileChoiceRow(
                label = label,
                selected = selected == label,
                onClick = { onSelect(label) }
            )
        }
    }
}

@Composable
internal fun InvestmentProfileMultiOptionList(
    options: List<String>,
    selected: List<String>,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { label ->
            InvestmentProfileChoiceRow(
                label = label,
                selected = label in selected,
                onClick = { onSelect(label) }
            )
        }
    }
}

@Composable
internal fun InvestmentProfileChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .quantClickable(role = QuantPressRole.Row, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
        )
    ) {
        Text(
            label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun InvestmentProfileSummaryPanel(profile: InvestmentProfile) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                profile.operatingStatement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InvestmentProfileMetricPill("완성도", "${profile.completionPercent}%", LucideIcon.Check, Modifier.weight(1f))
                InvestmentProfileMetricPill(profile.consistencyLabel, profile.nextReviewText, LucideIcon.CalendarClock, Modifier.weight(1f))
            }
            InvestmentProfileSummaryRow("투자 경험", displayProfileValue(profile.experience))
            InvestmentProfileSummaryRow("투자 기간", displayProfileValue(profile.horizon))
            InvestmentProfileSummaryRow("위험 선호", displayProfileValue(profile.riskTolerance))
            InvestmentProfileSummaryRow("선호 스타일", displayProfileValue(profile.style))
            InvestmentProfileSummaryRow(
                "피하고 싶은 신호",
                if (profile.avoidances.isEmpty()) "선택 안 함" else profile.avoidances.joinToString(" · ")
            )
            InvestmentProfileSummaryRow("하락 시 행동", displayProfileValue(profile.dropResponse))
            InvestmentProfileSummaryRow("과열 시 행동", displayProfileValue(profile.overheatedResponse))
            profile.consistencyNotes.firstOrNull()?.let {
                InvestmentProfileNotice(it)
            }
        }
    }
}

@Composable
internal fun InvestmentProfileSummaryRow(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(
            title,
            modifier = Modifier.width(92.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            lineHeight = 19.sp
        )
    }
}

internal fun displayProfileValue(value: String): String {
    return value.ifBlank { "선택 안 함" }
}

@Composable
internal fun AccountSettingRow(icon: LucideIcon, title: String, detail: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            LucideIconView(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp
            )
        }
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AccountNotificationCard() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var title by remember(appContext) { mutableStateOf(QubitNotificationScheduler.statusTitle(appContext)) }
    var detail by remember(appContext) { mutableStateOf(QubitNotificationScheduler.statusDetail(appContext)) }
    var enabled by remember(appContext) { mutableStateOf(QubitNotificationScheduler.isEnabled(appContext)) }
    var allowed by remember(appContext) { mutableStateOf(QubitNotificationScheduler.canPostNotifications(appContext)) }

    fun refreshNotificationStatus() {
        title = QubitNotificationScheduler.statusTitle(appContext)
        detail = QubitNotificationScheduler.statusDetail(appContext)
        enabled = QubitNotificationScheduler.isEnabled(appContext)
        allowed = QubitNotificationScheduler.canPostNotifications(appContext)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || QubitNotificationScheduler.canPostNotifications(appContext)) {
            QubitNotificationScheduler.setEnabled(appContext, true)
        }
        refreshNotificationStatus()
    }

    fun enableNotifications() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            QubitNotificationScheduler.setEnabled(appContext, true)
            refreshNotificationStatus()
        }
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    }

    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.Bell,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = when {
                            enabled && !allowed -> ::openNotificationSettings
                            enabled -> {
                                {
                                    QubitNotificationScheduler.setEnabled(appContext, false)
                                    refreshNotificationStatus()
                                }
                            }
                            else -> ::enableNotifications
                        },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(
                            when {
                                enabled && !allowed -> "설정 열기"
                                enabled -> "알림 끄기"
                                else -> "알림 켜기"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (enabled && allowed) {
                        TextButton(onClick = ::openNotificationSettings, modifier = Modifier.height(38.dp)) {
                            Text("설정", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccountSecurityCard() {
    CardBlock {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                LucideIconView(
                    icon = LucideIcon.ShieldCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("기기 보안", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "화면 잠금과 생체 인증을 켜두면 로그인 상태와 개인 설정을 더 안전하게 보호할 수 있습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
internal fun AccountManagementCard(onLogout: () -> Unit, onDelete: () -> Unit) {
    CardBlock {
        Text("계정 관리", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text("로그아웃", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("계정 삭제", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun AccountAuthCard(
    signup: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    errorMessage: String?,
    successMessage: String?,
    loading: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (signup) "계정 만들기" else "로그인",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "관심 종목과 설정을 사용자별로 저장합니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }

            Spacer(Modifier.height(2.dp))

            if (signup) {
                AccountPillTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = "이름",
                    keyboardType = KeyboardType.Text
                )
            }
            AccountPillTextField(
                value = email,
                onValueChange = onEmailChange,
                placeholder = "이메일",
                keyboardType = KeyboardType.Email
            )
            AccountPillTextField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = "비밀번호",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(
                        onClick = onTogglePassword,
                        modifier = Modifier.size(42.dp)
                    ) {
                        LucideIconView(
                            icon = LucideIcon.Eye,
                            contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 보기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            successMessage?.let { message ->
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LucideIconView(LucideIcon.Check, contentDescription = null, tint = QuantGreen, modifier = Modifier.size(18.dp))
                    Text(message, color = QuantGreen, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = Color(0xFFF0E7F0),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("계정 확인 중", fontWeight = FontWeight.ExtraBold)
                } else {
                    Text(
                        if (signup) "가입하기" else "로그인",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun AccountPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(999.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    )
    val fieldBackground = if (isFocused) Color(0xFFF7EFF7) else Color(0xFFF3EAF3)
    val fieldBorder = if (isFocused) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        Color.Transparent
    }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done
            ),
            interactionSource = interactionSource,
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 22.dp, end = if (trailing == null) 22.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF9B88A0),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        innerTextField()
                    }
                    trailing?.invoke()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(shape)
                .background(fieldBackground, shape)
                .border(width = 1.dp, color = fieldBorder, shape = shape)
        )
    }
}

@Composable
internal fun AccountCreateButton(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(999.dp))
            .quantClickable(role = QuantPressRole.Row, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFFC8ECFF),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF102A3A)
            )
        }
    }
}
