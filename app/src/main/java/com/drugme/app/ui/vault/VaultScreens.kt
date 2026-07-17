package com.drugme.app.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.drugme.app.R
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Google sign-in, with an honest way to decline it. */
@Composable
fun SignInScreen(
    busy: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(24.dp))
        Text("Back up your medications", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            "Sign in to keep your medications if you lose or change phone. " +
                "Everything is encrypted on this device first — we can't read it, and neither can Google.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with Google")
            }
            Spacer(Modifier.height(8.dp))
            // Declining must stay a first-class path: the reminder engine works entirely
            // offline, and forcing an account for a feature nobody asked for is coercion.
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Not now — use DrugMe without an account")
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Passphrase creation. */
@Composable
fun VaultSetupScreen(
    busy: Boolean,
    error: String?,
    check: (String) -> PassphraseCheck,
    onCreate: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val result = check(passphrase)
    val matches = passphrase.isNotEmpty() && passphrase == confirm

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Create your encryption passphrase", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your passphrase locks your medicines so only you can read them. Even in the " +
                "cloud backup, we only ever keep a scrambled copy that we can't open. " +
                "Because we never see your passphrase, we can't reset it for you — so pick " +
                "something you'll remember. You'll also get a backup code on the next " +
                "screen, in case you forget.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Requirement("At least 10 characters", result.longEnough)
        Requirement("Not a commonly used password", result.notCommon)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("Confirm passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = confirm.isNotEmpty() && !matches,
            modifier = Modifier.fillMaxWidth(),
        )
        if (confirm.isNotEmpty() && !matches) {
            Text("Passphrases don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onCreate(passphrase) },
            enabled = result.ok && matches && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Encrypting…" else "Create")
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Requirement(label: String, met: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Icon plus the "met" wording, not colour alone.
        Icon(
            if (met) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = if (met) "requirement met" else "requirement not met",
            tint = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (met) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The recovery code, shown exactly once.
 *
 * This screen is deliberately blunt and deliberately hard to skip. The user is one screen
 * away from a state where forgetting a passphrase destroys their medication history
 * permanently, and they must understand that *now* — not at the moment they need it, when
 * nothing can be done. The checkbox is not a dark pattern in reverse; it is the only
 * moment we can honestly obtain informed consent for an irreversible design.
 */
@Composable
fun RecoveryCodeScreen(
    code: String,
    onAcknowledge: () -> Unit,
) {
    var saved by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Save your recovery code", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            "This code is the only other way into your data if you forget your passphrase. " +
                "Write it down and keep it somewhere safe.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    code,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { clipboard.setText(AnnotatedString(code)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Copy code")
        }

        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp)) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "If you lose both your passphrase and this code, your synced data is gone " +
                        "permanently. We cannot recover it — that is what makes it private. " +
                        "This code is shown only once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("I have saved my recovery code", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAcknowledge, enabled = saved, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

/** Unlock with passphrase, or fall back to the recovery code. */
@Composable
fun UnlockScreen(
    busy: Boolean,
    wrongSecret: Boolean,
    onUnlock: (String) -> Unit,
    onUnlockWithCode: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var usingRecovery by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            if (usingRecovery) "Enter your recovery code" else "Unlock your medications",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (usingRecovery) {
                "Enter the code you saved when you set up encryption."
            } else {
                "Enter the passphrase you created. It decrypts your data on this device."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(if (usingRecovery) "Recovery code" else "Passphrase") },
            singleLine = true,
            // The recovery code is transcribed from paper — masking it would guarantee typos
            // in the one field where a typo is most costly.
            visualTransformation = if (usingRecovery) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            isError = wrongSecret,
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrongSecret) {
            Text(
                if (usingRecovery) "That code doesn't match." else "That passphrase doesn't match.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (usingRecovery) onUnlockWithCode(secret) else onUnlock(secret) },
            enabled = secret.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Unlocking…" else "Unlock")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { usingRecovery = !usingRecovery; secret = "" },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (usingRecovery) "Use my passphrase instead" else "I forgot my passphrase")
        }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}
