package com.verdroid.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdroid.app.data.git.GitCredentialStore
import com.verdroid.app.shizuku.ShellBackendStatus
import com.verdroid.app.shizuku.ShellExecutor
import com.verdroid.app.shizuku.ShizukuManager
import com.verdroid.app.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceControlUi(
    val gitTokenMasked: String? = null,
    val gitUser: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val testOutput: String? = null,
    val testing: Boolean = false,
    val shizukuBindError: String? = null,
    val shizukuInfo: String? = null,
    val message: String? = null,
    val rishInstalled: Boolean = false,
    val rishInfo: String? = null,
    val rishNeedsAccess: Boolean = false,
)

@HiltViewModel
class DeviceControlViewModel @Inject constructor(
    private val shizuku: ShizukuManager,
    private val git: GitCredentialStore,
    private val shell: ShellExecutor,
    private val rish: com.verdroid.app.shizuku.RishStore,
    private val rishRunner: com.verdroid.app.shizuku.RishRunner,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val shizukuState: StateFlow<ShizukuState> = shizuku.state
    val backendStatus: StateFlow<ShellBackendStatus> = shell.status
    private val _ui = MutableStateFlow(DeviceControlUi())
    val ui: StateFlow<DeviceControlUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        shizuku.refresh()
        _ui.update {
            it.copy(gitTokenMasked = git.maskedToken,
                shizukuBindError = shizuku.lastBindError, shizukuInfo = shizuku.serverInfo(),
                gitUser = git.username, authorName = git.authorName, authorEmail = git.authorEmail,
                rishInstalled = rish.isStaged(),
                rishInfo = if (rish.isStaged()) "staged → ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR}" else null)
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val deployed = runCatching { rish.isDeployed(::shellExec) }.getOrDefault(false)
            val info = when {
                deployed -> "✅ ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR} (chmod +x)"
                rish.isStaged() -> "Staged — needs Shizuku to copy into ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR}"
                else -> null
            }
            _ui.update { it.copy(rishInstalled = deployed || rish.isStaged(), rishInfo = info) }
        }
    }

    private suspend fun shellExec(command: String, timeoutMs: Long) = shell.exec(command, null, timeoutMs)


    fun requestShizukuPermission() = shizuku.requestPermission()

    /** Test (id): runs through the same backend chain as run_shell and reports which backend answered. */
    fun testShell() {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, testOutput = "Running id…") }
        viewModelScope.launch {
            val out = runCatching { shell.exec("id; getprop ro.product.model", null, 10_000) }
                .map { r -> "[${r.backend}] exit ${r.exitCode}\n${r.stdout.trim()}${r.stderr.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""}" }
                .getOrElse { "Error: ${it.message}" }
            _ui.update { it.copy(testing = false, testOutput = out.trim()) }
            refresh()
        }
    }

    fun diagnoseShizuku() {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, testOutput = "Diagnosing the Shizuku UserService…") }
        viewModelScope.launch {
            val out = runCatching { shell.diagnoseShizuku() }.getOrElse { "Error: ${it.message}" }
            _ui.update { it.copy(testing = false, testOutput = out.trim()) }
            refresh()
        }
    }

    fun saveGit(token: String, user: String, name: String, email: String) {
        if (token.isNotBlank()) git.token = token
        if (user.isNotBlank()) git.username = user
        if (name.isNotBlank()) git.authorName = name
        if (email.isNotBlank()) git.authorEmail = email
        refresh()
        _ui.update { it.copy(message = "Git settings saved (encrypted)") }
    }

    fun clearGitToken() { git.token = null; refresh() }

    private fun describeInstall(res: com.verdroid.app.shizuku.RishStore.Result): String = when (res) {
        is com.verdroid.app.shizuku.RishStore.Result.Installed ->
            if (res.deployed) "rish ready: ${res.scriptName} + ${res.companionName} in ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR} (chmod +x)"
            else "rish staged (${res.scriptName} + ${res.companionName}) but not copied to ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR}: ${res.detail ?: "is Shizuku running?"}"
        is com.verdroid.app.shizuku.RishStore.Result.NeedCompanion -> "Also select ${res.name} (pick both files exported by Shizuku)"
        is com.verdroid.app.shizuku.RishStore.Result.Invalid -> res.reason
    }

    private suspend fun stageThenDeploy(stage: () -> com.verdroid.app.shizuku.RishStore.Result): String {
        val staged = stage()
        if (staged !is com.verdroid.app.shizuku.RishStore.Result.Installed) return describeInstall(staged)
        return describeInstall(runCatching { rish.deploy(::shellExec) }.getOrElse {
            com.verdroid.app.shizuku.RishStore.Result.Installed(staged.scriptName, staged.companionName, false, it.message)
        })
    }

    /** Files picked in the rish picker: stage, then copy into /data/local/tmp/verdroid_rish and chmod +x (via Shizuku). */
    fun installRish(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val msg = runCatching {
                val picked = uris.mapNotNull { u -> readPicked(u)?.let { u to it } }
                stageThenDeploy {
                    rish.stage(picked.map { it.second }) { name ->
                        picked.firstNotNullOfOrNull { (u, p) -> if (com.verdroid.app.shizuku.RishStore.isScript(p)) siblingOf(u, name) else null }
                    }
                }
            }.getOrElse { "Could not copy rish: ${it.message}" }
            refresh()
            _ui.update { it.copy(message = msg) }
        }
    }

    private fun readPicked(uri: android.net.Uri): com.verdroid.app.shizuku.RishStore.Picked? {
        val cr = context.contentResolver
        val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > 20_000_000) return null
        return com.verdroid.app.shizuku.RishStore.Picked(name, bytes)
    }

    /** "primary:Download/rish" → /storage/emulated/0/Download/<name>, if readable. */
    private fun siblingOf(uri: android.net.Uri, name: String): ByteArray? = runCatching {
        val id = android.provider.DocumentsContract.getDocumentId(uri)
        val path = when {
            id.startsWith("raw:") -> id.removePrefix("raw:")
            id.startsWith("primary:") -> "/storage/emulated/0/" + id.removePrefix("primary:")
            else -> null
        } ?: return@runCatching null
        java.io.File(java.io.File(path).parentFile, name).takeIf { it.canRead() }?.readBytes()
    }.getOrNull()

    /** "Find rish": scan Download/Documents/Input, stage, then deploy to /data/local/tmp/verdroid_rish. */
    fun findRish() {
        if (!runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)) {
            _ui.update { it.copy(message = "Find rish needs All files access — grant it (Settings → Permissions), or use Pick rish file.", rishNeedsAccess = true) }
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val msg = runCatching {
                val roots = com.verdroid.app.shizuku.RishStore.searchRoots(android.os.Environment.getExternalStorageDirectory())
                val found = rish.findAndStage(roots)
                    ?: return@runCatching "No rish + rish_shizuku.dex found in Download, Documents or Documents/Verdroid/Input. Export them from Shizuku " +
                        "(Use Shizuku in terminal apps → Export files) into one of those folders, or use Pick rish file."
                if (found !is com.verdroid.app.shizuku.RishStore.Result.Installed) return@runCatching describeInstall(found)
                describeInstall(runCatching { rish.deploy(::shellExec) }.getOrElse {
                    com.verdroid.app.shizuku.RishStore.Result.Installed(found.scriptName, found.companionName, false, it.message)
                })
            }.getOrElse { "Find rish failed: ${it.message}" }
            refresh()
            _ui.update { it.copy(message = msg, rishNeedsAccess = false) }
        }
    }

    fun allFilesAccessIntent(): android.content.Intent =
        com.verdroid.app.data.storage.SharedFolder.accessIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /** "Fix rish permissions": chmod +x both files in /data/local/tmp/verdroid_rish (also done after every deploy and before rish_run). */
    fun fixRishPermissions() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val line = runCatching { rish.fixPermissions(::shellExec) }.getOrNull()
            refresh()
            _ui.update { it.copy(message = if (line == null) "Nothing in ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR} yet — Find or Pick rish first (needs Shizuku)" else "chmod +x applied: $line") }
        }
    }

    fun removeRish() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { rish.remove(::shellExec) }
            refresh()
            _ui.update { it.copy(message = "rish removed") }
        }
    }

    fun testRish() {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, testOutput = "Running id through rish…") }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val out = runCatching { rishRunner.run("id", 15) }
                .map { r -> "[rish @ ${com.verdroid.app.shizuku.RishStore.DEPLOY_DIR}] exit ${r.exitCode}\n${r.stdout.trim()}${r.stderr.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""}" }
                .getOrElse { "Error: ${it.message}" }
            _ui.update { it.copy(testing = false, testOutput = out.trim()) }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
}
