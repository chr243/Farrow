package com.farrow.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.farrow.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * In-app updater: public GitHub API (no token) → latest release → Farrow-*.apk into cacheDir/updates → system installer
 * (FileProvider + ACTION_VIEW). Same debug key as the installed app, so it installs over it.
 */
@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    // Own client: the shared one carries OpenRouter interceptors.
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val lock = Mutex()

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Dot on the gear / the Settings row: an update newer than the installed version is known (survives restarts). */
    private val _available = MutableStateFlow(prefs.getString(KEY_LATEST, null)?.let { UpdateLogic.isNewer(it, currentVersion) } == true)
    val updateAvailable: StateFlow<Boolean> = _available.asStateFlow()

    /** Silent check on app start, at most every 6 h; errors are ignored. */
    suspend fun autoCheck() {
        if (!UpdateLogic.autoCheckDue(prefs.getLong(KEY_LAST_CHECK, 0), System.currentTimeMillis())) return
        runCatching { check(silent = true) }
    }

    suspend fun check(silent: Boolean = false): UpdateState = lock.withLock {
        if (!silent) _state.value = UpdateState.Checking
        val r = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(UpdateLogic.LATEST_URL)
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Farrow/$currentVersion").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("GitHub answered HTTP ${resp.code}")
                    UpdateLogic.parseRelease(resp.body?.string().orEmpty(), BuildConfig.BUILD_TYPE) ?: throw IOException("unreadable release info")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val st = UpdateState.Error("Update check failed: ${e.message ?: e.javaClass.simpleName}")
            if (!silent) _state.value = st
            return st
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putString(KEY_LATEST, r.version).apply()
        val newer = UpdateLogic.isNewer(r.version, currentVersion)
        _available.value = newer
        val st = if (newer) UpdateState.Available(r) else UpdateState.UpToDate(r.version)
        // A silent check never overwrites a download in progress or a visible result.
        if (!silent || _state.value is UpdateState.Idle) _state.value = st
        st
    }

    /** Downloads the release's APK with progress, verifies its size; then [install]. */
    suspend fun download(release: ReleaseInfo): UpdateState {
        val asset = release.asset ?: return UpdateState.Error("Release ${release.tag} has no Farrow APK", release).also { _state.value = it }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safe = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(dir, safe)
        val tmp = File(dir, "$safe.part")
        _state.value = UpdateState.Downloading(release, 0, asset.size)
        return try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(asset.url).header("User-Agent", "Farrow/$currentVersion")
                    .header("Accept", "application/octet-stream").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("download failed: HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty download")
                    var done = 0L; var lastEmit = 0L
                    body.byteStream().use { input -> tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (done - lastEmit > 128 * 1024) { lastEmit = done; _state.value = UpdateState.Downloading(release, done, asset.size) }
                        }
                    } }
                    if (done != asset.size) throw IOException("size mismatch: got $done bytes, expected ${asset.size}")
                }
                if (!tmp.renameTo(out)) throw IOException("could not save the APK")
            }
            UpdateState.ReadyToInstall(release, out).also { _state.value = it }
        } catch (e: Exception) {
            tmp.delete()
            if (e is kotlinx.coroutines.CancellationException) { _state.value = UpdateState.Available(release); throw e }
            UpdateState.Error("Download failed: ${e.message ?: e.javaClass.simpleName}", release).also { _state.value = it }
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** "Install unknown apps" screen for Farrow (when [canInstall] is false). */
    fun unknownSourcesIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** System installer for the downloaded APK (installs over the current app: same package + signing key). */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun reset() { if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_LATEST = "latest_version"
    }
}
