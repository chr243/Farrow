package com.farrow.app.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farrow.app.domain.model.ApiKey
import com.farrow.app.domain.model.LimitSettings
import com.farrow.app.ui.Routes
import com.farrow.app.ui.components.formatFull
import com.farrow.app.ui.components.SmallBadge
import com.farrow.app.ui.components.SettingsGroup
import com.farrow.app.ui.components.Zebra
import com.farrow.app.ui.components.groupedRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, actions = actions,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }, content = content)
}

/** Settings (formerly the Menu tab): opened from the gear on the chat list; Back returns to the chats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigate: (String) -> Unit, onBack: () -> Unit, unreadNotifications: Int = 0, vm: SettingsViewModel = hiltViewModel()) {
    val kiloNoticeSeen by vm.appPrefs.kiloNoticeSeen.collectAsStateWithLifecycle()
    val memToKilo by vm.appPrefs.sendMemoriesToKilo.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            fun item(title: String, subtitle: String, route: String): @Composable (Int) -> Unit = { i -> MenuItem(title, subtitle, i) { onNavigate(route) } }
            if (!kiloNoticeSeen) KiloNotice(onDismiss = vm.appPrefs::dismissKiloNotice)
            SettingsGroup("Activity", listOf(
                item("Tasks", "Running, waiting and finished tasks", Routes.TASKS),
                item("Notifications",
                    if (unreadNotifications > 0) "$unreadNotifications unread · rate limits, quota warnings, completions"
                    else "Rate-limit alerts, quota warnings and completions", Routes.NOTIFICATIONS),
                item("Memory", "Short-term (per chat) and long-term memory: list, search, edit, automatic saving", Routes.memory()),
            ))
            SettingsGroup("Models", listOf(
                item("API keys", "Add, reorder and choose the primary OpenRouter key", Routes.KEYS),
                item("Model priority", "Ordered list of free models used for fallback", Routes.MODELS),
                item("Rate limits & quota", "RPM, daily limit, cooldown, low-quota warning, context budget", Routes.LIMITS),
                { i ->
                    ListItem(
                        headlineContent = { Text("Send memories to Kilo models") },
                        supportingContent = { Text("Include saved memories in prompts sent to kilo:* models (Kilo Auto Free may log prompts)") },
                        trailingContent = { Switch(memToKilo, vm.appPrefs::setSendMemoriesToKilo) },
                        colors = ListItemDefaults.colors(containerColor = Zebra.color(i)),
                        modifier = Modifier.clickable { vm.appPrefs.setSendMemoriesToKilo(!memToKilo) },
                    )
                },
            ))
            SettingsGroup("Agent tools", listOf(
                item("Tools", "Built-in agent tools (status, on/off) and Termux add-ons to install", Routes.TOOLS),
                item("MCP servers", "Remote MCP servers, their status and per-tool switches", Routes.MCP),
            ))
            SettingsGroup("Browser & accounts", listOf(
                item("Internal browser setup", "Termux + Firefox + Xvfb + Termux Browser Pilot bridge", Routes.BROWSER_SETUP),
                item("X.com account", "Login state, re-login, cookie import, selectors file", Routes.relogin("x")),
                item("Facebook account", "Login state, re-login, cookie import, selectors file", Routes.relogin("facebook")),
            ))
            SettingsGroup("Phone", listOf(
                item("Chat heads", "Auto / Bubbles / Overlay, permissions", Routes.CHAT_HEADS),
                item("Background & battery", "Keep-alive service, battery optimisation, HyperOS autostart", Routes.KEEP_ALIVE),
                item("Shizuku, accessibility & Git", "run_shell via Shizuku, screen control, Git token", Routes.SHIZUKU),
            ))
            SettingsGroup("Appearance", listOf(item("Theme", "Light / dark mode and color palette", Routes.THEME)))
            Text("Farrow v${com.farrow.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 16.dp))
        }
    }
}

/** One-time notice: the keyless Kilo Auto Free model is the first default model and may log prompts. */
@Composable
private fun KiloNotice(onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("New: Kilo Auto Free (no API key)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("kilo:kilo-auto/free is now the first model in Model priority, with your OpenRouter models as fallback. " +
                "It works without an OpenRouter key (about 200 requests/hour). Kilo's free models may log prompts and " +
                "replies, which can include your memories. Turn off \"Send memories to Kilo models\" below or remove the model " +
                "from Model priority if you prefer.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Got it") } }
        }
    }
}

@Composable
private fun MenuItem(title: String, subtitle: String, index: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Zebra.color(index)),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun ApiKeysScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val keys by vm.apiKeys.collectAsStateWithLifecycle()
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ApiKey?>(null) }
    SubScreen("API keys", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(modifier = Modifier.padding(bottom = 10.dp), text = "Keys are stored encrypted (Android Keystore + EncryptedSharedPreferences). The primary key is used first; " +
                    "others are used in order when a key is rate-limited.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(keys, key = { _, k -> k.id }) { i, k ->
                Surface(color = Zebra.color(i), shape = Zebra.shape(i, keys.size), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = k.isPrimary, onClick = { vm.setPrimary(k.id) })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(k.label.ifBlank { "Key ${i + 1}" }, fontWeight = FontWeight.SemiBold)
                                if (k.isPrimary) { Spacer(Modifier.width(6.dp)); SmallBadge("primary") }
                            }
                            Text(k.masked, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.moveKey(k.id, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { vm.moveKey(k.id, 1) }, enabled = i < keys.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = { editing = k }) { Icon(Icons.Filled.Edit, "Edit label") }
                        IconButton(onClick = { vm.removeKey(k.id) }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Text("Add key", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, label = { Text("OpenRouter API key (sk-or-…)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.addKey(label, key); label = ""; key = "" }, enabled = key.isNotBlank()) { Text("Add key") }
                }
            }
        }
    }
    editing?.let { k ->
        var newLabel by remember(k.id) { mutableStateOf(k.label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit label") },
            text = { OutlinedTextField(newLabel, { newLabel = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameKey(k.id, newLabel); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ModelPriorityScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val models by vm.models.collectAsStateWithLifecycle()
    val kiloUsed by vm.kiloUsedThisHour.collectAsStateWithLifecycle()
    var newModel by remember { mutableStateOf("") }
    SubScreen("Model priority", onBack, actions = {
        TextButton(onClick = { vm.resetModels() }) { Text("Reset") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(modifier = Modifier.padding(bottom = 10.dp), text = "Tried top to bottom. On 429 / 5xx / upstream errors the next model is used and the failed one cools down.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(modifier = Modifier.padding(bottom = 10.dp),
                    text = "kilo:… models use the Kilo Gateway with no API key. Kilo requests this hour: $kiloUsed/${com.farrow.app.data.network.KiloApi.HOURLY_LIMIT}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(models, key = { i, m -> "$i-$m" }) { i, m ->
                Surface(color = Zebra.color(i), shape = Zebra.shape(i, models.size), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                        Text(com.farrow.app.data.network.ModelIds.label(m), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.moveModel(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                        IconButton(onClick = { vm.moveModel(i, 1) }, enabled = i < models.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                        IconButton(onClick = { vm.removeModel(i) }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    OutlinedTextField(newModel, { newModel = it }, label = { Text("Model id, e.g. vendor/model:free or kilo:id") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.addModel(newModel); newModel = "" }, enabled = newModel.isNotBlank()) { Text("Add") }
                }
            }
        }
    }
}

@Composable
fun RateLimitSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val limits by vm.limits.collectAsStateWithLifecycle()
    val quota by vm.quotaInfo.collectAsStateWithLifecycle()
    val headers by vm.lastHeaders.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var rpm by remember(limits) { mutableStateOf(limits.requestsPerMinute.toString()) }
    var rpd by remember(limits) { mutableStateOf(limits.requestsPerDay.toString()) }
    var cooldown by remember(limits) { mutableStateOf(limits.modelCooldownSeconds.toString()) }
    var threshold by remember(limits) { mutableStateOf(limits.lowQuotaThreshold.toString()) }
    var budget by remember(limits) { mutableStateOf(limits.contextBudgetTokens.toString()) }
    var steps by remember(limits) { mutableStateOf(limits.maxSteps.toString()) }
    SubScreen("Rate limits & quota", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Requests per minute (per key)", rpm) { rpm = it }
            NumberField("Requests per day", rpd) { rpd = it }
            NumberField("Model cooldown (seconds)", cooldown) { cooldown = it }
            NumberField("Low-quota warning threshold", threshold) { threshold = it }
            NumberField("Context budget (tokens)", budget) { budget = it }
            NumberField("Max agent steps per run", steps) { steps = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val d = LimitSettings()
                    vm.saveLimits(LimitSettings(rpm.toIntOrNull() ?: d.requestsPerMinute, rpd.toIntOrNull() ?: d.requestsPerDay,
                        cooldown.toIntOrNull() ?: d.modelCooldownSeconds, threshold.toIntOrNull() ?: d.lowQuotaThreshold,
                        budget.toIntOrNull() ?: d.contextBudgetTokens, steps.toIntOrNull() ?: d.maxSteps))
                }) { Text("Save") }
                OutlinedButton(onClick = { vm.saveLimits(LimitSettings()) }) { Text("Defaults") }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free-tier quota (GET /key)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.refreshQuota() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
            val q = quota
            if (q == null) Text("No data yet (add a key and refresh).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(buildString {
                appendLine("Key: ${q.keyLabel}")
                appendLine("Free model requests today: used ${q.used ?: "?"} / limit ${q.limit ?: "?"} → remaining ${q.remaining ?: "?"}" +
                    if (q.estimated) " (estimated locally)" else "")
                q.usage?.let { appendLine("Credit usage: $it") }
                q.creditLimit?.let { appendLine("Credit limit: $it") }
                if (q.rateLimitRequests != null) appendLine("Rate limit: ${q.rateLimitRequests} / ${q.rateLimitInterval ?: "?"}")
                q.isFreeTier?.let { appendLine("Free tier: $it") }
                append("Fetched: ${formatFull(q.fetchedAt)}")
            }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (headers.isNotEmpty()) {
                Text("Last X-RateLimit headers", style = MaterialTheme.typography.titleSmall)
                headers.values.forEach { s ->
                    Text("${s.keyLabel} · ${s.model ?: "-"} · HTTP ${s.statusCode}\nlimit=${s.headers.rawLimit} remaining=${s.headers.rawRemaining} " +
                        "reset=${s.headers.resetAtMs?.let { formatFull(it) } ?: s.headers.rawReset}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(9)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

