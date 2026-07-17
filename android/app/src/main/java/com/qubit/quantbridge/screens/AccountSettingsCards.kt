package com.qubit.quantbridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.qubit.quantbridge.ui.theme.QuantGreen
import kotlinx.coroutines.launch
import java.util.Locale

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
