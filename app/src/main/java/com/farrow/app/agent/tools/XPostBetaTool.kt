package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.social.*
import kotlinx.serialization.json.*
import java.io.IOException

/**
 * x_post_beta (off by default; Tools page: "Beta: faster X posting"): a 1:1 copy of x_post (SocialToolFactory's
 * `x_post` + [XPostBetaAutomation], a copy of SocialAutomation) whose only difference is instant typing: no
 * letter-by-letter keystrokes, straight to x_post's insertText path. Same guard, wedge restart/retry and result JSON.
 */
class XPostBetaTool(
    private val store: SelectorStore,
    private val bridge: BridgeClient,
    private val guard: SessionGuard,
    private val maxChars: Int = 280,
) : AgentTool {
    private val site = SelectorStore.X
    private val automation: XPostBetaAutomation get() = XPostBetaAutomation(store.get(site), bridge)
    private val displayName: String get() = runCatching { store.get(site).displayName }.getOrDefault(site)

    override val name = NAME
    override val description = DESCRIPTION
    override val parameters = schema(listOf("text"), "text" to prop("string", "Post text (max $maxChars characters)"))

    override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))

    // Copy of SocialToolFactory.guarded.
    private suspend fun guarded(ctx: ToolContext, ready: Boolean = true, block: suspend (XPostBetaAutomation) -> String): String {
        if (!bridge.isAvailable()) return errorJson("Browser bridge is not running. Open Settings > Internal browser setup.")
        return try {
            val a = automation
            if (ready) a.ensureBrowserReady(30)
            block(a)
        } catch (e: SessionExpiredException) {
            guard.flag(ctx.taskId, site, displayName, e.message ?: "")
            errorJson("session_expired: ${e.message}. The task will pause until the user re-logs in.")
        } catch (e: AutomationException) {
            errorJson(e.message ?: "automation failed")
        } catch (e: StepFailedException) {
            errorJson(e.message ?: "automation step failed")
        } catch (e: LoginFailedException) {
            errorJson(e.message ?: "login failed")
        } catch (e: IOException) {
            errorJson("bridge error: ${e.message}")
        }
    }

    // Copy of x_post's execute.
    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val text = args.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return errorJson("text is required")
        if (text.length > maxChars) return errorJson("text is ${text.length} chars; max is $maxChars")
        return guarded(ctx, ready = false) { a0 ->
            var a = a0
            var recovered: String? = null
            // v1.0.14: a wedged browser (TBP stuck/busy, navigation failing, hard step timeout before anything was
            // typed) → restart the browser once and retry. Never after typing or the Post click (no double post).
            val reason: String? = try {
                a.ensureBrowserReady(30); a.post(text); null
            } catch (e: AutomationException) {
                "not ready: ${e.message}"
            } catch (e: StepFailedException) {
                if (!a.wedgedBeforeTyping(e)) throw e
                e.message ?: "step ${e.index} failed"
            }
            if (reason != null) {
                val t0 = System.currentTimeMillis()
                runCatching { kotlinx.coroutines.withTimeoutOrNull(120_000) { bridge.resetDaemonAtomic() } }
                a = automation
                a.ensureBrowserReady(60)
                recovered = "browser restarted in ${(System.currentTimeMillis() - t0) / 1000} s after: ${reason.take(200)}"
                a.post(text)
            }
            buildJsonObject { put("ok", true); put("site", site); put("posted_chars", text.length)
                recovered?.let { put("recovered", it) }
                put("timings_ms", buildJsonObject { a.lastReadyMs?.let { put("ready", it) }; a.lastPostTimings.forEach { (k, v) -> put(k, v) } })
                put("steps", JsonArray(a.lastStepLog.map { JsonPrimitive(it) })) }.toString()
        }
    }

    companion object {
        const val NAME = "x_post_beta"
        /** First sentence = the Tools page label. */
        const val DESCRIPTION = "Beta: faster X posting. Same as x_post (internal browser, logged-in account, same steps and " +
            "checks) but types the text instantly instead of letter by letter. Use x_post unless the user turned this beta on or asked for it."
    }
}
