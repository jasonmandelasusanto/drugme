package com.drugme.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.drugme.app.R
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * First-run flow: the medical disclaimer, then the two things that decide whether
 * reminders actually arrive.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val context = LocalContext.current

    when (step) {
        0 -> DisclaimerStep(onAccept = { step = 1 })
        1 -> RemindersStep(onNext = { step = 2 })
        else -> BatteryStep(context = context, onFinish = onFinished)
    }
}

private const val ONBOARDING_STEPS = 3

/**
 * Step progress for the onboarding flow.
 *
 * Deliberately starts filled, not empty: step 1 shows a third complete, not 0%. Seeing
 * progress already made is what creates momentum to finish a multi-step setup rather than
 * abandon it at the first screen.
 */
@Composable
private fun OnboardingProgress(step: Int) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            "Step $step of $ONBOARDING_STEPS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { step.toFloat() / ONBOARDING_STEPS },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The medical disclaimer.
 *
 * Not legal boilerplate to click past. This app tells people when to take medication; if
 * someone treats it as a clinical instrument, the failure modes are real. It is placed
 * first, before any feature, and requires an explicit acknowledgement.
 */
@Composable
private fun DisclaimerStep(onAccept: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingProgress(step = 1)
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(88.dp).clip(CircleShape).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(20.dp))
        Text("Before you start", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Bullet("DrugMe is a reminder tool, not medical advice. It records what you tell it and reminds you at the times you choose.")
        Bullet("It is not a substitute for your doctor or pharmacist. Always follow their instructions over anything shown here.")
        Bullet("Don't rely on it as your only safeguard. Phones run out of battery, get switched off, and silence notifications.")
        Bullet("Condition information comes from RxNorm, a public reference vocabulary. It describes what a drug is generally used for — it is not a recommendation for you.")

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = accepted, onCheckedChange = { accepted = it })
            Text("I understand", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RemindersStep(onNext: () -> Unit) {
    val context = LocalContext.current

    // The system permission dialog is requested from here — right after the rationale above,
    // not blindly at app launch where it would surface over the disclaimer. We advance
    // whether the user grants or denies: denial is survivable (doses are still tracked), so
    // it must not trap them on this screen.
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> onNext() }

    fun continueOn() {
        val needsRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsRequest) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else onNext()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingProgress(step = 2)
        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Reminders", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "DrugMe needs permission to show notifications. Without it, the app can still track " +
                "your doses, but it can't tell you when one is due — which is the whole point.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = ::continueOn, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

/**
 * Battery exemption and vendor guidance.
 *
 * The most important screen in onboarding on a Xiaomi, Huawei, Oppo, vivo or Samsung
 * device — and the one the app cannot enforce. All it can do is explain and point.
 */
@Composable
private fun BatteryStep(context: Context, onFinish: () -> Unit) {
    val guidance = remember { OemBatteryGuidance.forCurrentDevice() }
    var optimized by remember { mutableStateOf(OemBatteryGuidance.isBatteryOptimized(context)) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        OnboardingProgress(step = 3)
        Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Keep reminders working", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Android saves battery by pausing apps. If it pauses DrugMe, your reminders stop " +
                "arriving — silently, with no warning.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(16.dp))
        if (optimized) {
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            OemBatteryGuidance.requestIgnoreBatteryOptimizationsIntent(context)
                        )
                    }
                    optimized = OemBatteryGuidance.isBatteryOptimized(context)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow DrugMe to run in the background")
            }
        } else {
            Text(
                "Battery optimisation is already off for DrugMe.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Only shown on devices whose vendor is known to override the standard setting.
        guidance?.let { g ->
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "One more step on ${g.manufacturer}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        g.instructions,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { OemBatteryGuidance.openBestSettings(context, g) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open ${g.manufacturer} settings")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
    }
}
