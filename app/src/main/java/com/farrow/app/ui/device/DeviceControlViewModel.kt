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
)

@HiltViewModel
class DeviceControlViewModel @Inject constructor(
    private val shizuku: ShizukuManager,
    private val git: GitCredentialStore,
    private val crypto: CryptoCredentials,
    private val shell: ShellExecutor,
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
                cryptoKeyMasked = crypto.maskedKey, cryptoConfigured = crypto.configured)
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

    fun consumeMessage() = _ui.update { it.copy(message = null) }
}
