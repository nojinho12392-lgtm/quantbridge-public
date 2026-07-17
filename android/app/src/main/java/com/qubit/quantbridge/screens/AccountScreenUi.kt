package com.qubit.quantbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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
