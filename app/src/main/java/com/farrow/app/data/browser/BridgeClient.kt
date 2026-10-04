package com.farrow.app.data.browser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of a bridge command. [data] is TBP's `--json` output when it could be parsed. */
data class BridgeResult(val ok: Boolean, val code: Int, val stdout: String, val stderr: String, val data: JsonElement?, val raw: JsonObject) {
    /** TBP `--json` prints {"success", "data": {...}}; this is the inner payload. */
    val payload: JsonElement?
        get() {
            val d = data
            return if (d is JsonObject && d.containsKey("data")) d["data"] else d
        }

    /** The `result` of an eval (or the whole payload for other commands). */
    val value: JsonElement?
        get() {
            val p = payload
            return if (p is JsonObject && p.containsKey("result")) p["result"] else p
        }

    fun valueString(): String? = when (val v = value) {
        null, is JsonNull -> stdout.trim().ifBlank { null }
        is JsonPrimitive -> v.contentOrNull
        else -> v.toString()
    }

    val errorMessage: String get() = stderr.ifBlank { stdout }.ifBlank { "bridge command failed (code $code)" }.take(2000)
}

data class BridgeHealth(
    val reachable: Boolean, val version: String?, val tbpInstalled: Boolean, val daemonRunning: Boolean, val error: String?,
    /** Why the TBP daemon is considered down (bridge ≥ 1.2.0). */
    val daemonError: String? = null,
    /** Bridge ≥ 1.4.0: a daemon pid is alive but its socket doesn't listen (starting or hung) — never auto-restarted. */
    val daemonUnresponsive: Boolean = false,
) {
    val bridgeOk: Boolean get() = reachable && error == null
    /** The whole browser is usable: bridge up, tbp installed and its daemon running. */
    val fullyUp: Boolean get() = bridgeOk && tbpInstalled && daemonRunning
}

/** GET /daemon/status (bridge ≥ 1.2.0). [supported] false = old bridge without the endpoint. */
data class DaemonStatus(val supported: Boolean, val running: Boolean, val error: String?, val tbpLog: String, val daemonLog: String,
                        val firefoxLog: String = "", val needsReset: Boolean = false) {
    fun logText(): String = buildString {
        error?.let { append("Daemon: ").append(it).append("\n") }
        if (needsReset) append("→ Tap Reset browser to stop it and start fresh.\n")
        if (firefoxLog.isNotBlank()) append("--- ~/.farrow/firefox-probe.log (Firefox stderr)\n").append(firefoxLog.trim()).append("\n")
        if (tbpLog.isNotBlank()) append("--- ~/.farrow/tbp.log\n").append(tbpLog.trim()).append("\n")
        if (daemonLog.isNotBlank()) append("--- ~/.tbp/daemon.log\n").append(daemonLog.trim()).append("\n")
    }.trim()
}

/** Port + shared secret for the localhost bridge. The token is generated once and baked into the install command. */
@Singleton
/** Where the bridge listens (the real one is [BridgeConfig]; tests point it at a fake server). */
interface BridgeEndpoint {
    val port: Int
    val token: String
    val baseUrl: String get() = "http://127.0.0.1:$port"
}

class BridgeConfig @Inject constructor(@ApplicationContext context: Context) : BridgeEndpoint {
    private val prefs = context.getSharedPreferences("browser_bridge", Context.MODE_PRIVATE)

    override val port: Int get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    override val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }

    fun regenerateToken(): String = newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }


    private fun newToken(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
    }
}

/**
 * OkHttp client for the Python bridge running in Termux (assets/tbp_bridge.py).
 * HTTP `POST /cmd` is used for commands; `/ws` gives a WebSocket for live events.
 * Cleartext to 127.0.0.1 is allowed by res/xml/network_security_config.xml.
 */
@Singleton
class BridgeClient(private val config: BridgeEndpoint) {
    @Inject constructor(config: BridgeConfig) : this(config as BridgeEndpoint)

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)  // cookies_import: stop + write + restart + goto
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)
    val events: SharedFlow<JsonObject> = _events.asSharedFlow()
    @Volatile private var socket: WebSocket? = null

    suspend fun health(): BridgeHealth = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${config.baseUrl}/health").header(TOKEN_HEADER, config.token).get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 401) return@withContext BridgeHealth(true, null, false, false, "Bridge token mismatch — reinstall the bridge from the wizard")
                val o = json.parseToJsonElement(body).jsonObject
                BridgeHealth(
                    reachable = true,
                    version = o["version"]?.jsonPrimitive?.contentOrNull,
                    tbpInstalled = o["tbp"]?.jsonPrimitive?.booleanOrNull == true,
                    daemonRunning = o["daemon"]?.jsonPrimitive?.booleanOrNull == true,
                    error = null,
                    daemonError = o["daemon_error"]?.jsonPrimitive?.contentOrNull,
                    daemonUnresponsive = o["daemon_unresponsive"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
        } catch (e: Exception) {
            BridgeHealth(false, null, false, false, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun getJson(path: String, post: Boolean = false): Pair<Int, JsonObject?> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("${config.baseUrl}$path").header(TOKEN_HEADER, config.token)
        val req = if (post) b.post("{}".toRequestBody(JSON_MEDIA)).build() else b.get().build()
        http.newCall(req).execute().use { resp ->
            resp.code to runCatching { json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject }.getOrNull()
        }
    }

    /** TBP daemon state + log tails. Throws IOException when the bridge is unreachable. */
    suspend fun daemonStatus(): DaemonStatus {
        val (code, o) = getJson("/daemon/status")
        if (code == 404 || o == null) return DaemonStatus(false, false, "Bridge ${'$'}code: update the bridge (Set up everything re-installs step 4)", "", "")
        return DaemonStatus(true, o["running"]?.jsonPrimitive?.booleanOrNull == true, o["error"]?.jsonPrimitive?.contentOrNull,
            o["tbp_log"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["daemon_log"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["firefox_log"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["needs_reset"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /**
     * POST /daemon/start: bridge ≥ 1.3.0 is single-flight, clears stale/orphan locks, runs `tbp start` and waits ≤ 30 s
     * for the socket. Returns null on an old bridge (404).
     */
    suspend fun startDaemon(): JsonObject? {
        val (code, o) = getJson("/daemon/start", post = true)
        return if (code == 404) null else o
    }

    /** POST /daemon/reset (bridge ≥ 1.3.0): stop everything, clear locks, start fresh. Null on an old bridge (404). */
    suspend fun resetDaemon(): JsonObject? {
        val (code, o) = getJson("/daemon/reset", post = true)
        return if (code == 404) null else o
    }

    /**
     * v1.0.7 / bridge 1.11.0: "Reset browser" as an atomic bridge-side job (like the cookie import): the bridge stops
     * TBP/Firefox/Xvfb and starts fresh in its own thread, so the app leaving, being killed or cancelling can't leave the
     * browser half-reset. [onJob] receives the job id (persisted to resume waiting). Older bridges: the blocking
     * POST /daemon/reset (null on a bridge < 1.3.0).
     */
    suspend fun resetDaemonAtomic(onJob: (String) -> Unit = {}): JsonObject? {
        val start = try { command("job_start", buildJsonObject { put("cmd", "reset") }) } catch (e: kotlinx.serialization.SerializationException) { null }
        val id = (start?.data as? JsonObject)?.get("job")?.jsonPrimitive?.contentOrNull
        if (start == null || !start.ok || id == null) return resetDaemon()
        onJob(id)
        return awaitJob(id).raw
    }

    /** Set by [BridgeAutoStarter]: starts the bridge (step 5) and the TBP daemon when needed. */
    @Volatile var autoStart: (suspend () -> Boolean)? = null

    /** Set by [BridgeAutoStarter]: the bundled bridge version and a background updater for an outdated bridge. */
    @Volatile var bundledVersion: String? = null
    @Volatile var autoUpdate: (suspend () -> Boolean)? = null

    /**
     * True when the bridge answers with tbp installed. If the bridge or the TBP daemon is down, the auto-start runs
     * first (bridge ≤ 15 s, daemon ≤ 30 s). A bridge without a running daemon still counts as available afterwards —
     * tbp commands auto-start the daemon themselves.
     */
    suspend fun isAvailable(): Boolean {
        var h = health()
        if (h.bridgeOk && BridgeVersions.isOutdated(h.version, bundledVersion)) {
            autoUpdate?.invoke()
            h = health()
        }
        if (h.fullyUp) return true
        autoStart?.invoke()?.let { if (it) return true }
        return health().let { it.bridgeOk && it.tbpInstalled }
    }

    /**
     * Sends a command; throws [IOException] when the bridge is unreachable. The HTTP call is cancelled when the
     * coroutine is (withTimeout / Cancel button), so callers never hang on a stuck bridge command.
     */
    suspend fun command(cmd: String, args: JsonObject = JsonObject(emptyMap())): BridgeResult {
        val payload = buildJsonObject { put("cmd", cmd); put("args", args) }.toString()
        val req = Request.Builder().url("${config.baseUrl}/cmd")
            .header(TOKEN_HEADER, config.token)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val body = http.newCall(req).awaitBody()
        return withContext(Dispatchers.Default) { parseResult(json.parseToJsonElement(body).jsonObject) }
    }

    /**
     * v1.10.0: [importCookies] as an atomic bridge-side job. The bridge stops Firefox, writes cookies.sqlite and ALWAYS
     * restarts the daemon in its own thread; we only start it and poll, so cancelling or losing the app can never leave
     * the browser stopped. [onJob] receives the job id (persist it to resume waiting after a restart). Bridges older
     * than 1.10.0 fall back to the blocking command.
     */
    suspend fun importCookiesAtomic(name: String, cookies: JsonArray, origin: String? = null, then: String? = null,
                                    onJob: (String) -> Unit = {}): BridgeResult {
        val args = buildJsonObject {
            put("name", name); put("cookies", cookies)
            origin?.let { put("origin", it) }; then?.let { put("then", it) }
        }
        val start = command("job_start", buildJsonObject { put("cmd", "cookies_import"); put("args", args) })
        val id = (start.data as? JsonObject)?.get("job")?.jsonPrimitive?.contentOrNull
        if (!start.ok || id == null) {
            if (start.stderr.contains("unknown cmd")) return command("cookies_import", args)
            return start
        }
        onJob(id)
        return awaitJob(id)
    }

    /** Polls `job_status` until the job is done; tolerates short connection drops (the job keeps running bridge-side). */
    suspend fun awaitJob(id: String, pollMs: Long = 1_500, maxFailures: Int = 40): BridgeResult {
        var failures = 0
        var lastError: String? = null
        while (true) {
            kotlinx.coroutines.delay(pollMs)
            val st = try { command("job_status", buildJsonObject { put("job", id) }) } catch (e: IOException) { lastError = e.message; null }
                catch (e: kotlinx.serialization.SerializationException) { lastError = e.message; null }
            val d = st?.data as? JsonObject
            if (d == null) {
                if (++failures >= maxFailures) throw IOException("Lost contact with the bridge while it finishes job $id (${lastError ?: "no answer"}). " +
                    "The bridge still finishes the job (import / reset) on its own.")
                continue
            }
            failures = 0
            when (val state = d["state"]?.jsonPrimitive?.contentOrNull) {
                "running" -> continue
                "done" -> return (d["result"] as? JsonObject)?.let(::parseResult)
                    ?: throw IOException("bridge job $id finished without a result")
                else -> throw IOException("bridge job $id is $state (the bridge restarted while it ran) — tap Start browser if it is down")
            }
        }
    }

    private fun parseResult(o: JsonObject) = BridgeResult(
        ok = o["ok"]?.jsonPrimitive?.booleanOrNull == true,
        code = o["code"]?.jsonPrimitive?.intOrNull ?: -1,
        stdout = o["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        stderr = o["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        data = o["data"]?.takeUnless { it is JsonNull },
        raw = o,
    )

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = runCatching { resp.body?.string().orEmpty() }
                    when {
                        text.isFailure -> cont.resumeWithException(IOException(text.exceptionOrNull()))
                        !resp.isSuccessful -> cont.resumeWithException(IOException("Bridge HTTP ${resp.code}: ${text.getOrThrow().take(300)}"))
                        else -> cont.resume(text.getOrThrow())
                    }
                }
            }
        })
    }

    /** PNG bytes of the current page (bridge ≥ 1.1.0: `tbp screenshot`, ImageMagick fallback). */
    suspend fun screenshot(): Result<ByteArray> = runCatching {
        val r = command("screenshot")
        if (!r.ok) throw IOException(if (r.stderr.contains("unknown cmd")) "bridge too old for screenshots — re-run step 4 of the internal browser setup" else r.errorMessage)
        val b64 = (r.data as? JsonObject)?.get("png_base64")?.jsonPrimitive?.contentOrNull ?: throw IOException("no image in reply")
        java.util.Base64.getDecoder().decode(b64)
    }

    /** Screenshot with options (bridge ≥ 1.9.0: full_page / element selector; older bridges return the viewport). */
    suspend fun screenshotShot(fullPage: Boolean = false, selector: String? = null): Result<Shot> = runCatching {
        val r = command("screenshot", buildJsonObject {
            if (fullPage) put("full_page", true)
            selector?.takeIf { it.isNotBlank() }?.let { put("selector", it) }
            put("timeout", if (fullPage) 45 else 30)
        })
        if (!r.ok) throw IOException(if (r.stderr.contains("unknown cmd")) "bridge too old for screenshots — re-run step 4 of the internal browser setup" else r.errorMessage)
        val d = r.data as? JsonObject
        val b64 = d?.get("png_base64")?.jsonPrimitive?.contentOrNull ?: throw IOException("no image in reply")
        Shot(java.util.Base64.getDecoder().decode(b64), d["mode"]?.jsonPrimitive?.contentOrNull ?: "viewport (bridge < 1.9.0)",
            (d["notes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty())
    }

    data class Shot(val png: ByteArray, val mode: String, val notes: List<String>)

    suspend fun goto(url: String, cloudflare: Boolean = false) = command("goto", buildJsonObject { put("url", url); put("cf", cloudflare) })
    suspend fun text(selector: String? = null) = command("text", buildJsonObject { selector?.let { put("selector", it) } })
    suspend fun html(selector: String? = null) = command("html", buildJsonObject { selector?.let { put("selector", it) } })
    suspend fun eval(expression: String, timeoutS: Int = 45) = command("eval", buildJsonObject { put("expression", expression); put("timeout", timeoutS) })

    /** Bridge ≥ 1.7.0: keyboard navigation (no JS, no 'load' wait); ok = the history shows the target URL. */
    suspend fun nav(url: String, timeoutS: Int = 30) = command("nav", buildJsonObject { put("url", url); put("timeout", timeoutS) })

    /** Bridge ≥ 1.7.0: socket up, no command in flight, console answering (waits ≤ [timeoutS]). */
    suspend fun ready(timeoutS: Int = 30) = command("ready", buildJsonObject { put("timeout", timeoutS) })

    /** Bridge ≥ 1.7.0: login state from cookies.sqlite + current URL, no JS. */
    suspend fun siteStatus(domain: String, keyCookies: List<String>, loggedOutPatterns: List<String>) = command("site_status", buildJsonObject {
        put("domain", domain); put("key_cookies", JsonArray(keyCookies.map { JsonPrimitive(it) }))
        put("logged_out_patterns", JsonArray(loggedOutPatterns.map { JsonPrimitive(it) }))
    })
    suspend fun currentUrl() = command("url")

    /** Bridge ≥ 1.15.0: kill the bridge's hung `tbp` CLI calls (never the daemon); "unknown cmd" on older bridges. */
    suspend fun cancel() = command("cancel")
    suspend fun press(key: String) = command("press", buildJsonObject { put("key", key) })

    /** Bridge ≥ 1.8.0: focus (editable) or JS-click the element with one eval — no `tbp click`. */
    suspend fun focus(selector: String, timeoutS: Int = 30) = command("focus", buildJsonObject { put("selector", selector); put("timeout", timeoutS) })

    /** Bridge ≥ 1.8.0: xdotool key combo (e.g. "ctrl+Return") on the main Firefox window. */
    suspend fun key(keys: String) = command("key", buildJsonObject { put("keys", keys) })

    /** Bridge ≥ 1.8.0: focus by eval → xdotool typing → verify → execCommand('insertText') fallback (Draft.js editors). */
    suspend fun editorType(selector: String, text: String, human: Boolean = true) = command("editor_type", buildJsonObject {
        put("selector", selector); put("text", text)
        if (human) put("delays_ms", JsonArray(HumanInput.typingDelays(text).map { JsonPrimitive(it) }))
    })

    /** Click with a human-like Bézier path (normalised 0..1, mapped by the bridge from the pointer to the element). */
    suspend fun click(selector: String, human: Boolean = true): BridgeResult {
        val path = HumanInput.bezierPath(Point(0.0, 0.0), Point(1.0, 1.0), steps = 28)
        return cancelOnAbandon { command("click", buildJsonObject {
            put("selector", selector)
            put("human", human)
            if (human) {
                put("mouse_path", JsonArray(path.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) }))
                put("step_delay_ms", 14)
            }
        }) }
    }

    /**
     * v1.0.14: when the caller gives up on [block] (timeout/cancel), tell the bridge (≥ 1.15.0 `cancel`) to kill the
     * hung `tbp` CLI call, fire-and-forget, so the bridge command returns and the next one isn't queued behind it.
     */
    private suspend fun <T> cancelOnAbandon(block: suspend () -> T): T = try { block() } catch (e: kotlinx.coroutines.CancellationException) {
        abandonScope.launch { runCatching { kotlinx.coroutines.withTimeoutOrNull(10_000) { cancel() } } }
        throw e
    }

    private val abandonScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Types with per-character human delays computed on the phone. */
    suspend fun type(selector: String, text: String, submit: Boolean = false, human: Boolean = true): BridgeResult =
        command("type", buildJsonObject {
            put("selector", selector)
            put("text", text)
            put("submit", submit)
            if (human) put("delays_ms", JsonArray(HumanInput.typingDelays(text).map { JsonPrimitive(it) }))
        })

    suspend fun saveCookies(name: String) = command("cookies_save", buildJsonObject { put("name", name) })
    suspend fun loadCookies(name: String) = command("cookies_load", buildJsonObject { put("name", name) })
    suspend fun listSessions() = command("cookies_list")
    suspend fun setCookie(name: String, value: String, domain: String) =
        command("cookie_set", buildJsonObject { put("name", name); put("value", value); put("domain", domain); put("secure", true) })
    /** Imports full cookies (domain/path/secure/httpOnly) through `tbp cookies --load` (bridge ≥ 1.2.0). */
    /** Bridge ≥ 1.3.0: goto [origin] → set the cookies there → verify (report in stdout / data.report) → goto [then]. */
    suspend fun importCookies(name: String, cookies: JsonArray, origin: String? = null, then: String? = null) =
        command("cookies_import", buildJsonObject {
            put("name", name); put("cookies", cookies)
            origin?.let { put("origin", it) }; then?.let { put("then", it) }
        })
    suspend fun dismissCookieBanner() = command("dismiss_cookie_banner")
    suspend fun pushFingerprint(info: JsonElement) = command("fingerprint", buildJsonObject { put("info", info) })

    /** Opens (or reuses) the event WebSocket; events are emitted on [events]. */
    fun connectEvents() {
        if (socket != null) return
        val req = Request.Builder().url("ws://127.0.0.1:${config.port}/ws?token=${config.token}").build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { _events.tryEmit(it) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { socket = null }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { socket = null }
        })
    }

    fun disconnectEvents() {
        socket?.close(1000, "bye"); socket = null
    }

    companion object {
        private const val TOKEN_HEADER = "X-Bridge-Token"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
