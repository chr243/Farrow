package com.farrow.app.ui.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.data.social.LoginState
import com.farrow.app.ui.components.BackScaffold

/** Opened from the chat's "Re-login" button (session expired) or Settings > Social accounts. */
@Composable
fun ReloginScreen(onBack: () -> Unit, vm: ReloginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    val cookies = remember { mutableStateMapOf<String, String>() }
    var selectorsJson by remember { mutableStateOf("") }
    var otherWays by remember { mutableStateOf(false) }
    val webLogin = androidx.activity.compose.rememberLauncherForActivityResult(WebLoginActivity.Contract()) { vm.importFromWebView(it) }
    var autoLaunched by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val spec = vm.webLoginSpec
        if (vm.autoWebLogin && spec != null && !autoLaunched) {
            autoLaunched = true
            webLogin.launch(WebLoginActivity.Request(spec.site, vm.prefs.clearWebViewCookies.value))
        }
    }

    val bridgeUpdate by vm.bridgeUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.checkBridgeVersion() }
    LaunchedEffect(state.message) { state.message?.let { if (!state.busy) { snackbar.showSnackbar(it); vm.consumeMessage() } } }

    BackScaffold("${state.displayName} login", onBack, actions = { TextButton(onClick = vm::check, enabled = !state.busy) { Text("Check") } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.farrow.app.ui.browser.BridgeUpdateBanner(bridgeUpdate, onUpdate = vm::updateBridge)
                val st = state.status
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Session", style = MaterialTheme.typography.titleSmall)
                        Text(when (st?.state) {
                            LoginState.LOGGED_IN -> "✅ Logged in"
                            LoginState.LOGGED_OUT -> "🔐 Logged out (${st?.reason})"
                            LoginState.UNKNOWN -> "❔ Unknown (${st?.reason})"
                            null -> if (state.bridgeUp == false) "Bridge not running" else "Unknown (not checked yet)"
                        })
                        st?.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(state.checkedAt?.let { "Last checked " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) }
                            ?: "Never checked. Tap Check to check the session.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.checkTimings?.let { Text("⏱ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (state.verifying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(state.progress ?: "Working…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                            }
                        }
                    }
                }

                state.error?.let { err ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("❌ $err", color = MaterialTheme.colorScheme.onErrorContainer)
                            com.farrow.app.ui.components.CopyButton(listOfNotNull(err, state.diagUrl?.let { "Page: $it" },
                                state.diagScreenshotError?.let { "Screenshot: $it" }, state.diagText?.let { "Page text:\n$it" }).joinToString("\n"), label = "Copy error")
                            state.diagUrl?.let { Text("Page: $it", style = MaterialTheme.typography.bodySmall) }
                            state.diagScreenshot?.let { bmp ->
                                Image(bmp.asImageBitmap(), contentDescription = "Browser screenshot at the failure",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
                            }
                            state.diagScreenshotError?.let { Text("(no screenshot: $it)", style = MaterialTheme.typography.bodySmall) }
                            state.diagText?.takeIf { it.isNotBlank() }?.let {
                                Text("Page text:", style = MaterialTheme.typography.labelMedium)
                                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 25)
                                com.farrow.app.ui.components.CopyButton(it, label = "Copy page text")
                            }
                            if (state.daemonDown) {
                                state.daemonLog?.let { log ->
                                    Text("daemon.log (last lines):", style = MaterialTheme.typography.labelMedium)
                                    Text(log, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 30)
                                    com.farrow.app.ui.components.CopyButton(log, label = "Copy daemon.log")
                                }
                                Button(onClick = vm::startBrowser, enabled = !state.busy) { Text("Start browser") }
                            }
                            TextButton(onClick = vm::dismissError) { Text("Dismiss") }
                        }
                    }
                }

                state.cookieReport?.takeIf { it.isNotBlank() }?.let { rep ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cookies in the internal browser", style = MaterialTheme.typography.titleSmall)
                            Text(rep, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 30)
                            com.farrow.app.ui.components.CopyButton(rep, label = "Copy cookie report")
                        }
                    }
                }

                vm.webLoginSpec?.let { spec ->
                    val clearAfter by vm.prefs.clearWebViewCookies.collectAsStateWithLifecycle()
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Log in to ${spec.displayName}", style = MaterialTheme.typography.titleMedium)
                            Text("Opens ${spec.loginUrl} in an in-app browser. Log in yourself (2FA / captcha included); it closes " +
                                "automatically once ${spec.required.joinToString(" + ")} exist, and the cookies are imported into the internal browser and verified.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { webLogin.launch(WebLoginActivity.Request(spec.site, clearAfter)) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text("Log in to ${spec.displayName}") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = clearAfter, onCheckedChange = vm.prefs::setClearWebViewCookies)
                                Spacer(Modifier.width(8.dp))
                                Text("Clear the in-app browser's cookies after import", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                TextButton(onClick = { otherWays = !otherWays }) { Text(if (otherWays) "▾ Other ways (paste cookies, automated login)" else "▸ Other ways (paste cookies, automated login)") }
                if (otherWays || vm.webLoginSpec == null) {
                // Cookie import first: it's the most reliable way past X's bot checks.
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paste cookies", style = MaterialTheme.typography.titleMedium)
                        Text("On a computer where you're logged in to ${state.displayName}: DevTools → Application → Cookies, copy ${vm.cookieNames.joinToString(" and ")}. " +
                            "Or paste a full export: Cookie-Editor JSON, Netscape cookies.txt, or a `name=value; …` header.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        vm.cookieNames.forEach { name ->
                            OutlinedTextField(cookies[name].orEmpty(), { cookies[name] = it }, label = { Text(name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(pasted, { pasted = it }, label = { Text("…or paste a cookie export (JSON / Netscape / header)") },
                            minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
                        val found = remember(pasted) { if (pasted.isBlank()) emptySet() else vm.parseCookies(pasted).keys }
                        if (pasted.isNotBlank()) Text(
                            if (found.isEmpty()) "No cookies recognised" else "Found ${found.size} cookies" +
                                vm.cookieNames.joinToString("") { n -> if (n in found) " · $n ✓" else " · $n ✗" },
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.loginWithCookies(pasted, cookies.toMap()) },
                            enabled = !state.busy && (pasted.isNotBlank() || cookies.values.any { it.isNotBlank() })) { Text("Import & verify on x.com/home") }
                    }
                }

                HorizontalDivider()
                Text("…or log in inside the internal browser", style = MaterialTheme.typography.titleSmall)
                Text("Credentials are typed into ${state.displayName} by the bridge and are never stored; only the resulting cookies are saved (~/.farrow/sessions in Termux). Each step times out after ~15 s and a screenshot is shown on failure.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(username, { username = it }, label = { Text("Username / email / phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(challenge, { challenge = it }, label = { Text("Unusual-activity answer: phone or @username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it }, label = { Text("2FA / confirmation code (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.loginWithCredentials(username, password, code, challenge); password = "" },
                    enabled = !state.busy && username.isNotBlank() && password.isNotBlank()) { Text("Log in") }
                OutlinedButton(onClick = { vm.openInCustomTab() }) { Text("Open login page in Chrome (view only)") }
                Text("Note: a Chrome Custom Tab has its own cookie jar; logging in there does not log in the internal browser.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()
                Text("Selectors file (v${state.selectorsVersion}${if (state.selectorsOverridden) ", custom" else ", bundled"})", style = MaterialTheme.typography.titleSmall)
                Text("All DOM selectors and step scripts for ${state.displayName} live in one JSON file (assets/selectors/${state.site}.json). Paste an updated file to override it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(selectorsJson, { selectorsJson = it }, label = { Text("Selectors JSON") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.updateSelectors(selectorsJson) }, enabled = selectorsJson.isNotBlank()) { Text("Update") }
                    if (state.selectorsOverridden) OutlinedButton(onClick = vm::resetSelectors) { Text("Reset to bundled") }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
