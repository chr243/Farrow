package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BrowserOpKind
import com.farrow.app.data.browser.BrowserOpsState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * reset_browser: the same as Settings > Internal browser setup > Reset browser. It starts BrowserOpsManager's
 * background reset (it keeps running if the task stops), waits for it to finish and reports ok/error plus the time it
 * took. If a reset is already running, it waits for that one. Any other browser setup action → error.
 */
class ResetBrowserTool(
    private val startReset: () -> Boolean,
    private val state: StateFlow<BrowserOpsState>,
    private val timeoutMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool {
    override val name = NAME
    override val description = "Reset the internal browser (restarts Firefox and the Termux Browser Pilot daemon, like " +
        "Settings > Internal browser setup > Reset browser) and wait until it is back. Use it when browser tools (web_*, x_*) " +
        "hang or fail repeatedly. Returns ok/error and seconds."
    override val parameters = schema(emptyList())

    override suspend fun execute(args: JsonObject): String {
        val t0 = clock()
        val s0 = state.value
        when {
            s0.busy && s0.kind != BrowserOpKind.RESET ->
                return errorJson("another browser setup action is running (${s0.label ?: s0.kind}); try again when it has finished")
            !s0.busy && !startReset() -> {
                if (!state.value.busy) return errorJson("the browser reset could not be started")
            }
        }
        val done = withTimeoutOrNull(timeoutMs) { state.first { !it.busy } }
        val secs = (clock() - t0) / 1000.0
        return buildJsonObject {
            if (done == null) {
                put("ok", false); put("error", "the reset is still running after ${timeoutMs / 1000} s (it continues in the background)")
            } else {
                val ok = done.lastOk == true
                put("ok", ok)
                (done.resetMessage ?: done.lastResult)?.let { put(if (ok) "message" else "error", it) }
                if (!ok) done.resetLog?.let { put("log_tail", it.takeLast(1500)) }
            }
            put("seconds", Math.round(secs * 10) / 10.0)
        }.toString()
    }

    companion object { const val NAME = "reset_browser" }
}
