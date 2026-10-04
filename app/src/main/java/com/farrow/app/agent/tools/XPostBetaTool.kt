package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.social.*
import kotlinx.serialization.json.*
import java.io.IOException

/**
 * x_post_beta (off by default; Tools page: "Beta: faster X posting"): the same job as x_post through [XPostBeta]'s
 * faster flow. Separate from SocialToolFactory/x_post, which stay unchanged. A failure before any text was inserted
 * (browser busy or stuck, a phase timed out, navigation failed) restarts the browser once and retries. It never
 * retries after the text was inserted, so it can't double-post.
 */
class XPostBetaTool(
    private val store: SelectorStore,
    private val bridge: BridgeClient,
    private val guard: SessionGuard,
    private val maxChars: Int = 280,
) : AgentTool {
    override val name = NAME
    override val description = DESCRIPTION
    override val parameters = schema(listOf("text"), "text" to prop("string", "Post text (max $maxChars characters)"))

    override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
        val text = args.str("text")?.trim().orEmpty()
        if (text.isEmpty()) return errorJson("text is required")
        if (text.length > maxChars) return errorJson("text is ${text.length} chars; max is $maxChars")
        if (!bridge.isAvailable()) return errorJson("Browser bridge is not running. Open Settings > Internal browser setup.")
        val config = store.get(SelectorStore.X)
        var b = XPostBeta(config, bridge)
        var recovered: String? = null
        var first: JsonObject? = null
        return try {
            try { b.post(text) } catch (e: AutomationException) {
                // Only before the text was inserted: after it, a retry could post twice.
                if (!b.failedBeforeTyping) throw e
                first = attempt(b)
                val t0 = System.currentTimeMillis()
                runCatching { kotlinx.coroutines.withTimeoutOrNull(120_000) { bridge.resetDaemonAtomic() } }
                recovered = "browser restarted in ${(System.currentTimeMillis() - t0) / 1000} s after: ${(e.message ?: "").lineSequence().first().take(200)}"
                b = XPostBeta(config, bridge)
                b.post(text)
            }
            buildJsonObject {
                put("ok", true); put("site", SelectorStore.X); put("posted_chars", text.length); put("flow", "beta")
                recovered?.let { put("recovered", it) }
                first?.let { put("first_attempt", it) }
                put("timings_ms", buildJsonObject { b.lastTimings.forEach { (k, v) -> put(k, v) } })
                put("steps", JsonArray(b.lastSteps.map { JsonPrimitive(it) }))
            }.toString()
        } catch (e: SessionExpiredException) {
            guard.flag(ctx.taskId, SelectorStore.X, config.displayName, e.message ?: "")
            errorJson("session_expired: ${e.message}. The task will pause until the user re-logs in.")
        } catch (e: AutomationException) {
            failure(e.message ?: "x_post_beta failed", b, recovered, first)
        } catch (e: IOException) {
            failure("bridge error: ${e.message}", b, recovered, first)
        }
    }

    /** timings_ms + steps of one attempt (the first one is kept when the browser was restarted and retried). */
    private fun attempt(b: XPostBeta) = buildJsonObject {
        put("timings_ms", buildJsonObject { b.lastTimings.forEach { (k, v) -> put(k, v) } })
        put("steps", JsonArray(b.lastSteps.map { JsonPrimitive(it) }))
    }

    private fun failure(msg: String, b: XPostBeta, recovered: String?, first: JsonObject?) = buildJsonObject {
        put("error", msg)
        put("text_inserted", !b.failedBeforeTyping)
        recovered?.let { put("recovered", it) }
        first?.let { put("first_attempt", it) }
        put("timings_ms", buildJsonObject { b.lastTimings.forEach { (k, v) -> put(k, v) } })
        put("steps", JsonArray(b.lastSteps.map { JsonPrimitive(it) }))
    }.toString()

    companion object {
        const val NAME = "x_post_beta"
        /** First sentence = the Tools page label. */
        const val DESCRIPTION = "Beta: faster X posting. Publishes a new post on X like x_post (internal browser, logged-in " +
            "account) with a faster flow: JS actions first, TBP click only as a fallback, a single insertText. Returns timings_ms. " +
            "Use x_post unless the user turned this beta on or asked for it."
    }
}
