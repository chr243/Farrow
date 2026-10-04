package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.social.*
import kotlinx.serialization.json.*
import java.io.IOException

/**
 * Agent tools for a social site (prefix "x" for X.com, "fb" for Facebook), all backed by [SocialAutomation].
 * A login wall flags the task through [SessionGuard] so the agent loop pauses it (PauseReason.SESSION_EXPIRED).
 */
class SocialToolFactory(
    private val prefix: String,
    private val site: String,
    private val store: SelectorStore,
    private val bridge: BridgeClient,
    private val guard: SessionGuard,
) {
    private val automation: SocialAutomation get() = SocialAutomation(store.get(site), bridge)
    private val displayName: String get() = runCatching { store.get(site).displayName }.getOrDefault(site)

    private suspend fun guarded(ctx: ToolContext, block: suspend (SocialAutomation) -> String): String {
        if (!bridge.isAvailable()) return errorJson("Browser bridge is not running. Open Settings > Internal browser setup.")
        return try {
            val a = automation
            a.ensureBrowserReady(30)
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

    fun tools(postMaxChars: Int): List<AgentTool> = listOf(status(), post(postMaxChars), scrape())

    private fun status() = object : AgentTool {
        override val name = "${prefix}_status"
        override val description = "Check whether the $displayName session in the internal browser is logged in (no accessibility permission needed)."
        override val parameters = schema(emptyList())
        override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))
        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            if (!bridge.isAvailable()) return errorJson("Browser bridge is not running. Open Settings > Internal browser setup.")
            return try {
                val st = automation.restoreSession()   // cookie store + current URL, no JS
                buildJsonObject {
                    put("site", site); put("state", st.state.name); put("url", st.url); put("reason", st.reason)
                }.toString()
            } catch (e: Exception) { errorJson("${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun post(maxChars: Int) = object : AgentTool {
        override val name = "${prefix}_post"
        override val description = "Publish a new post on $displayName with the logged-in account (human-like typing). Runs in the internal browser — no accessibility permission needed."
        override val parameters = schema(listOf("text"), "text" to prop("string", "Post text (max $maxChars characters)"))
        override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))
        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            val text = args.str("text")?.trim().orEmpty()
            if (text.isEmpty()) return errorJson("text is required")
            if (text.length > maxChars) return errorJson("text is ${text.length} chars; max is $maxChars")
            return guarded(ctx) { a ->
                a.post(text)
                buildJsonObject { put("ok", true); put("site", site); put("posted_chars", text.length)
                    put("steps", JsonArray(a.lastStepLog.map { JsonPrimitive(it) })) }.toString()
            }
        }
    }

    private fun scrape() = object : AgentTool {
        override val name = "${prefix}_scrape"
        override val description = "Scrape posts from $displayName: kind=timeline (home feed), profile (needs handle) or search (needs query)."
        override val parameters = schema(listOf("kind"),
            "kind" to prop("string", "timeline | profile | search"),
            "handle" to prop("string", "Profile handle/username for kind=profile (without @)"),
            "query" to prop("string", "Search query for kind=search"),
            "limit" to prop("integer", "Max posts to return (default 20, max 100)"))
        override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))
        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            val kind = args.str("kind") ?: return errorJson("kind is required")
            val params: Map<String, String> = when (kind) {
                "timeline" -> emptyMap()
                "profile" -> mapOf("handle" to (args.str("handle")?.removePrefix("@")?.takeIf { it.isNotBlank() }
                    ?: return errorJson("handle is required for kind=profile")))
                "search" -> mapOf("query" to (args.str("query")?.takeIf { it.isNotBlank() }
                    ?: return errorJson("query is required for kind=search")))
                else -> return errorJson("kind must be timeline, profile or search")
            }
            val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
            return guarded(ctx) { a ->
                val items = a.scrape(kind, params, limit)
                buildJsonObject { put("site", site); put("kind", kind); put("count", items.size); put("items", items) }.toString()
            }
        }
    }
}
