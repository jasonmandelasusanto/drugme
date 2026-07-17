package com.drugme.app.ui.vault

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.auth.AuthUser
import com.drugme.app.data.auth.SignInResult
import com.drugme.app.data.crypto.VaultManager
import com.drugme.app.data.crypto.VaultState
import com.drugme.app.data.prefs.SettingsRepository
import com.drugme.app.data.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Strength floor for the passphrase, expressed in terms a person can act on. */
data class PassphraseCheck(
    val longEnough: Boolean,
    val notCommon: Boolean,
) {
    val ok: Boolean get() = longEnough && notCommon
}

data class VaultUiState(
    val user: AuthUser? = null,
    val vault: VaultState = VaultState.NoUser,
    val busy: Boolean = false,
    val error: String? = null,

    /** Shown once, immediately after setup. Never retrievable again. */
    val recoveryCode: String? = null,
    val recoveryAcknowledged: Boolean = false,
    val wrongSecret: Boolean = false,

    /** Null until read from disk, so the first frame doesn't flash onboarding at a returning user. */
    val onboardingComplete: Boolean? = null,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val vault: VaultManager,
    private val syncEngine: SyncEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            auth.authState.collect { user ->
                val vaultState = vault.refresh(user?.uid)
                _state.value = _state.value.copy(user = user, vault = vaultState)
            }
        }
        viewModelScope.launch {
            // Persisted, not remembered: onboarding held in composition state would replay
            // the disclaimer and battery setup on every cold start.
            settings.onboardingComplete.collect { done ->
                _state.value = _state.value.copy(onboardingComplete = done)
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }

    fun checkPassphrase(value: String) = PassphraseCheck(
        // 10, not 8: this passphrase is the only thing standing between a stolen blob and
        // an offline Argon2 attack. Argon2 raises the cost per guess; it does not save a
        // passphrase that is in a wordlist.
        longEnough = value.length >= 10,
        notCommon = value.lowercase() !in COMMON_PASSPHRASES,
    )

    fun signIn(activityContext: Context) {
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = auth.signInWithGoogle(activityContext)) {
                is SignInResult.Success ->
                    _state.value = _state.value.copy(busy = false, user = result.user)
                SignInResult.Cancelled ->
                    // Dismissing the sheet is a choice, not an error.
                    _state.value = _state.value.copy(busy = false)
                SignInResult.NoAccounts ->
                    _state.value = _state.value.copy(
                        busy = false,
                        error = "No Google account on this device. Add one in Settings, or keep using DrugMe without an account.",
                    )
                is SignInResult.Failure ->
                    _state.value = _state.value.copy(busy = false, error = result.message)
            }
        }
    }

    fun setupVault(passphrase: String) {
        val uid = _state.value.user?.uid ?: return
        if (!checkPassphrase(passphrase).ok) return

        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            vault.setup(uid, passphrase.toCharArray())
                .onSuccess { code ->
                    _state.value = _state.value.copy(busy = false, recoveryCode = code)
                    syncEngine.sync()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = it.message ?: "Could not set up encryption",
                    )
                }
        }
    }

    fun unlock(passphrase: String) {
        val uid = _state.value.user?.uid ?: return
        _state.value = _state.value.copy(busy = true, wrongSecret = false, error = null)
        viewModelScope.launch {
            val ok = vault.unlock(uid, passphrase.toCharArray())
            _state.value = _state.value.copy(busy = false, wrongSecret = !ok)
            if (ok) syncEngine.sync()
        }
    }

    fun unlockWithRecoveryCode(code: String) {
        val uid = _state.value.user?.uid ?: return
        _state.value = _state.value.copy(busy = true, wrongSecret = false, error = null)
        viewModelScope.launch {
            val ok = vault.unlockWithRecoveryCode(uid, code)
            _state.value = _state.value.copy(busy = false, wrongSecret = !ok)
            if (ok) syncEngine.sync()
        }
    }

    /** Dismisses the recovery-code screen. Only reachable once the user confirms. */
    fun acknowledgeRecoveryCode() {
        _state.value = _state.value.copy(recoveryCode = null, recoveryAcknowledged = true)
    }

    fun signOut() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            auth.signOut()
            // Forget the key, keep the data: signing out is not a request to erase a
            // medication history, and the app is fully usable offline without an account.
            vault.forget()
            _state.value = VaultUiState()
        }
    }

    fun sync() = viewModelScope.launch { syncEngine.sync() }

    private companion object {
        val COMMON_PASSPHRASES = setOf(
            "password", "password1", "password123", "1234567890", "12345678910",
            "qwertyuiop", "letmein123", "iloveyou1", "administrator", "drugme1234",
        )
    }
}
