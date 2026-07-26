package com.drugme.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.data.crypto.VaultState
import com.drugme.app.ui.onboarding.OnboardingScreen
import com.drugme.app.ui.vault.RecoveryCodeScreen
import com.drugme.app.ui.vault.SignInScreen
import com.drugme.app.ui.vault.UnlockScreen
import com.drugme.app.ui.vault.VaultSetupScreen
import com.drugme.app.ui.vault.VaultViewModel

/**
 * Decides what the user sees before the main app.
 *
 * The ordering is deliberate. Onboarding (disclaimer, notifications, battery) comes first
 * and is unconditional; sign-in is **optional and skippable**. The reminder engine is
 * entirely local, so gating it behind an account would withhold the one feature that
 * matters for a benefit — cloud backup — the user may not want. An account is offered,
 * never required.
 */
@Composable
fun DrugMeApp(
    onFixExactAlarms: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A Surface gives every gate screen below a themed background and, crucially, sets
    // LocalContentColor to onSurface. Without it these screens (bare Columns, no Scaffold)
    // fall back to the framework default black text — invisible in dark mode.
    Surface(Modifier.fillMaxSize()) {
        when {
            // Null means "not read from disk yet". Rendering onboarding while unknown would
            // flash the disclaimer at every returning user for a frame.
            state.onboardingComplete == null || state.backupPromptDismissed == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.onboardingComplete == false ->
                OnboardingScreen(
                    savedDarkMode = state.darkMode,
                    onDarkModeSelected = viewModel::setDarkMode,
                    onFinished = viewModel::completeOnboarding,
                )

            // Shown once, right after setup, and never again.
            state.recoveryCode != null -> RecoveryCodeScreen(
                code = state.recoveryCode!!,
                onAcknowledge = viewModel::acknowledgeRecoveryCode,
            )

            state.user == null && state.backupPromptDismissed == false -> SignInScreen(
                busy = state.busy,
                error = state.error,
                onSignIn = { (context as? Activity)?.let { viewModel.signIn(it) } },
                onSkip = viewModel::dismissBackupPrompt,
            )

            state.user != null && state.vault is VaultState.NeedsSetup -> VaultSetupScreen(
                busy = state.busy,
                error = state.error,
                check = viewModel::checkPassphrase,
                onCreate = viewModel::setupVault,
            )

            state.user != null && state.vault is VaultState.Locked -> UnlockScreen(
                busy = state.busy,
                wrongSecret = state.wrongSecret,
                onUnlock = viewModel::unlock,
                onUnlockWithCode = viewModel::unlockWithRecoveryCode,
                onSignOut = viewModel::signOut,
            )

            else -> DrugMeNavHost(
                onFixExactAlarms = onFixExactAlarms,
                onSignIn = { viewModel.showBackupPrompt() },
                onSignedOut = viewModel::showBackupPrompt,
            )
        }
    }
}
