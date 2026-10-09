package com.farrow.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.farrow.app.data.a11y.FarrowAccessibilityService
import com.farrow.app.data.crypto.CryptoCredentials
import com.farrow.app.data.git.GitCredentialStore
import com.farrow.app.shizuku.ShellBackendStatus
import com.farrow.app.shizuku.ShellExecutor
import com.farrow.app.shizuku.ShizukuManager
import com.farrow.app.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceControlUi(
    val accessibilityOn: Boolean = false,
    val gitTokenMasked: String? = null,
    val gitUser: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val cryptoKeyMasked: String? = null,
    val cryptoConfigured: Boolean = false,
    val testOutput: String? = null,
    val testing: Boolean = false,
    val shizukuBindError: String? = null,
    val shizukuInfo: String? = null,
    val message: String? = null,
    val rishInstalled: Boolean = false,
    val rishInfo: String? = null,
)

@HiltViewModel
class DeviceControlViewModel @Inject constructor(
    private val shizuku: ShizukuManager,
    private val git: GitCredentialStore,
    private val crypto: CryptoCredentials,
    private val shell: ShellExecutor,
    private val rish: com.farrow.app.shizuku.RishStore,
    private val rishRunner: com.farrow.app.shizuku.RishRunner,
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
            it.copy(accessibilityOn = FarrowAccessibilityService.isRunning, gitTokenMasked = git.maskedToken,
                shizukuBindError = shizuku.lastBindError, shizukuInfo = shizuku.serverInfo(),
                gitUser = git.username, authorName = git.authorName, authorEmail = git.authorEmail,
                cryptoKeyMasked = crypto.maskedKey, cryptoConfigured = crypto.configured,
                rishInstalled = rish.isInstalled(),
                rishInfo = if (rish.isInstalled()) "${rish.script.path} + ${rish.companion?.name}" else null)
        }
    }

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

    fun saveCrypto(key: String, secret: String, passphrase: String) {
        if (key.isNotBlank()) crypto.apiKey = key
        if (secret.isNotBlank()) crypto.apiSecret = secret
        if (passphrase.isNotBlank()) crypto.passphrase = passphrase
        refresh()
        _ui.update { it.copy(message = "Crypto API credentials saved (encrypted)") }
    }

    fun clearCrypto() { crypto.clear(); refresh(); _ui.update { it.copy(message = "Crypto API credentials removed") } }

    /** Files picked in the rish picker: copy rish and its companion (rish_shizuku.dex) into files/rish. */
    fun installRish(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val msg = runCatching {
                val picked = uris.mapNotNull { u -> readPicked(u)?.let { u to it } }
                val res = rish.install(picked.map { it.second }) { name ->
                    // Only rish picked: read its sibling directly (works with All files access).
                    picked.firstNotNullOfOrNull { (u, p) -> if (com.farrow.app.shizuku.RishStore.isScript(p)) siblingOf(u, name) else null }
                }
                when (res) {
                    is com.farrow.app.shizuku.RishStore.Result.Installed -> "rish installed: copied ${res.script.name} and ${res.companion.name} into Farrow's internal folder"
                    is com.farrow.app.shizuku.RishStore.Result.NeedCompanion -> "Also select ${res.name} (pick both files exported by Shizuku)"
                    is com.farrow.app.shizuku.RishStore.Result.Invalid -> res.reason
                }
            }.getOrElse { "Could not copy rish: ${it.message}" }
            refresh()
            _ui.update { it.copy(message = msg) }
        }
    }

    private fun readPicked(uri: android.net.Uri): com.farrow.app.shizuku.RishStore.Picked? {
        val cr = context.contentResolver
        val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > 20_000_000) return null
        return com.farrow.app.shizuku.RishStore.Picked(name, bytes)
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

    fun removeRish() { rish.remove(); refresh(); _ui.update { it.copy(message = "rish removed") } }

    fun testRish() {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, testOutput = "Running id through rish…") }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val out = runCatching { rishRunner.run("id", 15) }
                .map { r -> "[rish] exit ${r.exitCode}\n${r.stdout.trim()}${r.stderr.trim().takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""}" }
                .getOrElse { "Error: ${it.message}" }
            _ui.update { it.copy(testing = false, testOutput = out.trim()) }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
}
