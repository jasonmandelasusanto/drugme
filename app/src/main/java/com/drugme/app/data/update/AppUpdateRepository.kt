package com.drugme.app.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.drugme.app.BuildConfig
import com.drugme.app.MainActivity
import com.drugme.app.R
import com.drugme.app.data.medical.NetworkStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore by preferencesDataStore(name = "app_updates")

data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val version: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val downloadSize: Long? = null,
    val downloaded: Boolean = false,
    /** Set after a successful check finds no version newer than this installation. */
    val upToDate: Boolean = false,
    val error: String? = null,
) {
    val available: Boolean get() = version != null
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
    val digest: String? = null,
)

@Singleton
class AppUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val networkStatus: NetworkStatus,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private val checkMutex = Mutex()

    init {
        scope.launch { restoreState() }
    }

    /** A user-requested check always contacts GitHub immediately. */
    suspend fun checkAndDownload(): Result<Boolean> = runCheck(onlyIfDue = false)

    /** Automatic foreground/worker check, shared-throttled to one attempt per 24 hours. */
    suspend fun checkIfDue(): Result<Boolean> = runCheck(onlyIfDue = true)

    private suspend fun runCheck(onlyIfDue: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            checkMutex.withLock {
                if (BuildConfig.DEBUG) return@withLock Result.success(false)

                val now = clock.millis()
                val lastCheck = context.updateDataStore.data.first()[KEY_LAST_CHECK_AT]
                if (onlyIfDue && !isUpdateCheckDue(now, lastCheck)) {
                    return@withLock Result.success(false)
                }

                if (!networkStatus.isOnline()) {
                    val failure = IllegalStateException("No internet connection.")
                    if (!onlyIfDue) {
                        _state.value = _state.value.copy(
                            checking = false,
                            downloading = false,
                            error = failure.message,
                        )
                    }
                    // Do not consume today's automatic check while offline. A later foreground
                    // or the connected-network WorkManager fallback can still perform it.
                    return@withLock Result.failure(failure)
                }

                // Record the online attempt before contacting GitHub. Reopening after a server
                // failure must not hammer it repeatedly; Settings remains an explicit retry path.
                context.updateDataStore.edit { it[KEY_LAST_CHECK_AT] = now }
                checkAndDownloadLocked()
            }
        }

    private suspend fun checkAndDownloadLocked(): Result<Boolean> = runCatching {
        _state.value = _state.value.copy(checking = true, upToDate = false, error = null)
        val release = fetchLatestRelease()
        val version = release.tag.removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) {
            clearPersisted()
            _state.value = AppUpdateState(upToDate = true)
            return@runCatching false
        }
        val saved = context.updateDataStore.data.first()
        if (saved[KEY_VERSION] == version && updateFile().isFile) {
            restoreState()
            return@runCatching true
        }
        val asset = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                !it.name.contains("debug", ignoreCase = true)
        } ?: error("The latest release does not contain a release APK.")
        val expectedDigest = asset.digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?: error("The release APK has no SHA-256 digest; refusing an unverified update.")

        _state.value = AppUpdateState(
            checking = false,
            downloading = true,
            version = version,
            title = release.name ?: "DrugMe $version",
            notes = release.body,
            downloadSize = asset.size,
        )
        val target = updateFile()
        download(asset.downloadUrl, target)
        require(sha256(target).equals(expectedDigest, ignoreCase = true)) {
            "The downloaded APK failed its SHA-256 check."
        }
        require(hasMatchingSigner(target)) {
            "The downloaded APK was not signed by the installed DrugMe signing key."
        }
        persist(release, asset, target)
        _state.value = _state.value.copy(downloading = false, downloaded = true)
        notifyUpdateReady(version)
        true
    }.onFailure { failure ->
        _state.value = _state.value.copy(
            checking = false,
            downloading = false,
            upToDate = false,
            error = failure.message ?: "Could not check for updates",
        )
    }

    /**
     * Starts a modern PackageInstaller session. Returns false when Android first needs the
     * user to trust DrugMe as an install source.
     */
    fun installDownloaded(): Boolean {
        val apk = updateFile()
        if (!apk.isFile || !state.value.downloaded) return false
        if (!context.packageManager.canRequestPackageInstalls()) return false

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        return true
    }

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    private suspend fun restoreState() {
        val prefs = context.updateDataStore.data.first()
        val version = prefs[KEY_VERSION] ?: return
        val file = updateFile()
        _state.value = AppUpdateState(
            version = version,
            title = prefs[KEY_TITLE],
            notes = prefs[KEY_NOTES],
            downloadSize = prefs[KEY_SIZE],
            downloaded = file.isFile,
        )
    }

    private suspend fun persist(release: GithubRelease, asset: GithubAsset, file: File) {
        context.updateDataStore.edit {
            it[KEY_VERSION] = release.tag.removePrefix("v")
            it[KEY_TITLE] = release.name ?: "DrugMe ${release.tag}"
            it[KEY_NOTES] = release.body.orEmpty()
            it[KEY_SIZE] = asset.size
            it[KEY_PATH] = file.absolutePath
        }
    }

    private suspend fun clearPersisted() {
        context.updateDataStore.edit {
            it.remove(KEY_VERSION)
            it.remove(KEY_TITLE)
            it.remove(KEY_NOTES)
            it.remove(KEY_SIZE)
            it.remove(KEY_PATH)
        }
    }

    private fun fetchLatestRelease(): GithubRelease {
        val connection = URL(API_LATEST).openConnection() as HttpURLConnection
        return connection.useConnection {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "DrugMe/${BuildConfig.VERSION_NAME}")
            check(responseCode in 200..299) { "GitHub returned HTTP $responseCode." }
            val release = inputStream.bufferedReader().use { json.decodeFromString<GithubRelease>(it.readText()) }
            check(!release.draft && !release.prerelease) { "The latest release is not a stable release." }
            release
        }
    }

    private fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.useConnection {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "DrugMe/${BuildConfig.VERSION_NAME}")
            check(responseCode in 200..299) { "APK download returned HTTP $responseCode." }
            inputStream.use { input -> temp.outputStream().use(input::copyTo) }
        }
        target.delete()
        check(temp.renameTo(target)) { "Could not finish the APK download." }
    }

    @Suppress("DEPRECATION")
    private fun hasMatchingSigner(apk: File): Boolean {
        val currentSigners: Set<String>
        val archiveSigners: Set<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val current = context.packageManager.getPackageInfo(context.packageName, flags)
            val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
            if (archive.packageName != context.packageName ||
                PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(current)
            ) return false
            currentSigners = current.signingInfo?.apkContentsSigners
                ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            archiveSigners = archive.signingInfo?.apkContentsSigners
                ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        } else {
            val current = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
            val archive = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNATURES,
            ) ?: return false
            if (archive.packageName != context.packageName ||
                PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(current)
            ) return false
            currentSigners = current.signatures?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            archiveSigners = archive.signatures?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        }
        return currentSigners.isNotEmpty() && currentSigners == archiveSigners
    }

    private fun notifyUpdateReady(version: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            UPDATE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("DrugMe $version is ready")
                .setContentText("Open DrugMe to review and install the update.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun updateFile() = File(context.filesDir, "updates/drugme-update.apk")

    private companion object {
        const val API_LATEST = "https://api.github.com/repos/jasonmandelasusanto/drugme/releases/latest"
        const val ACTION_INSTALL_STATUS = "com.drugme.app.UPDATE_INSTALL_STATUS"
        const val CHANNEL_UPDATES = "app_updates"
        const val UPDATE_NOTIFICATION_ID = 701_001
        val KEY_VERSION = stringPreferencesKey("version")
        val KEY_TITLE = stringPreferencesKey("title")
        val KEY_NOTES = stringPreferencesKey("notes")
        val KEY_PATH = stringPreferencesKey("path")
        val KEY_SIZE = longPreferencesKey("size")
        val KEY_LAST_CHECK_AT = longPreferencesKey("last_check_at")
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmation?.let(context::startActivity)
            }
            else -> Unit
        }
    }
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T =
    try {
        block()
    } finally {
        disconnect()
    }

internal fun isNewer(candidate: String, current: String): Boolean {
    fun parts(value: String) = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val left = parts(candidate)
    val right = parts(current)
    repeat(maxOf(left.size, right.size)) { index ->
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a > b
    }
    return false
}

internal fun isUpdateCheckDue(nowMillis: Long, lastCheckMillis: Long?): Boolean =
    lastCheckMillis == null ||
        nowMillis < lastCheckMillis ||
        nowMillis - lastCheckMillis >= Duration.ofHours(24).toMillis()

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
