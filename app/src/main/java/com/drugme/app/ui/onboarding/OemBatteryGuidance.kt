package com.drugme.app.ui.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Vendor-specific battery managers, and how to reach their settings.
 *
 * This exists because correct use of the Android alarm APIs is not sufficient on a large
 * share of real phones. Xiaomi, Huawei, Oppo/Realme, Vivo and Samsung all ship background
 * process killers that ignore setAlarmClock and terminate apps outright — the app does
 * everything right and the user still misses a dose.
 *
 * It cannot be fixed in code. The only honest options are to detect the vendor, send the
 * user to the right screen, and say plainly why. Silently doing nothing would mean the app
 * appears to work and quietly doesn't, which for a medication reminder is the worst
 * outcome available.
 *
 * Intents are best-effort: OEMs rename and remove these activities between versions, so
 * every launch is guarded and falls back to the standard battery-optimisation screen.
 */
object OemBatteryGuidance {

    data class Guidance(
        val manufacturer: String,
        val instructions: String,
        val settingsIntents: List<Intent>,
    )

    fun isBatteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The standard "don't optimise me" prompt. Available on every device. */
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        )

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Vendor guidance for this device, or null if the stock behaviour is good enough. */
    fun forCurrentDevice(): Guidance? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> Guidance(
                manufacturer = "Xiaomi / Redmi / POCO",
                instructions = "MIUI stops apps in the background even when they have an alarm set. " +
                    "Open Settings, find DrugMe, then turn on \"Autostart\" and set battery saver to \"No restrictions\".",
                settingsIntents = listOf(
                    intentFor("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    intentFor("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                ),
            )

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Guidance(
                manufacturer = "Huawei / Honor",
                instructions = "EMUI closes apps it decides are idle. Open Phone Manager, go to Battery, " +
                    "then App launch, and set DrugMe to Manage manually with all switches on.",
                settingsIntents = listOf(
                    intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ),
            )

            manufacturer.contains("oppo") || manufacturer.contains("realme") -> Guidance(
                manufacturer = "OPPO / realme",
                instructions = "ColorOS freezes background apps. In Settings, open Battery, then " +
                    "App Battery Management, and allow DrugMe to run in the background.",
                settingsIntents = listOf(
                    intentFor("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    intentFor("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                ),
            )

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Guidance(
                manufacturer = "vivo / iQOO",
                instructions = "FuntouchOS restricts background apps. In i Manager, open App Manager, " +
                    "then Autostart Manager, and enable DrugMe.",
                settingsIntents = listOf(
                    intentFor("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                ),
            )

            manufacturer.contains("samsung") -> Guidance(
                manufacturer = "Samsung",
                instructions = "One UI can put apps to \"deep sleep\", which stops reminders entirely. " +
                    "In Settings, open Battery, then Background usage limits, and make sure DrugMe " +
                    "is not in the Sleeping or Deep sleeping apps lists.",
                settingsIntents = listOf(
                    intentFor("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                ),
            )

            manufacturer.contains("oneplus") -> Guidance(
                manufacturer = "OnePlus",
                instructions = "OxygenOS has aggressive battery optimisation. In Settings, open Battery, " +
                    "then Battery optimisation, and set DrugMe to \"Don't optimise\".",
                settingsIntents = listOf(
                    intentFor("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                ),
            )

            else -> null
        }
    }

    /** Opens the first intent that resolves, or the standard screen. Never throws. */
    fun openBestSettings(context: Context, guidance: Guidance?) {
        val candidates = (guidance?.settingsIntents ?: emptyList()) + batteryOptimizationSettingsIntent()
        for (intent in candidates) {
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
            }
        }
    }

    private fun intentFor(pkg: String, cls: String) = Intent().apply {
        component = ComponentName(pkg, cls)
    }
}
