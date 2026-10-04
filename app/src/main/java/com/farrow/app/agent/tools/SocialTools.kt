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
    /** Where failure screenshots of x_reply are saved (shown as a thumbnail in the tool card). */
    private val shotDir: java.io.File? = null,
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
        } catch (e: ReplyFailedException) {
            replyError(e)
        } catch (e: IOException) {
            errorJson("bridge error: ${e.message}")
        }
    }

    fun tools(postMaxChars: Int): List<AgentTool> =
        listOf(status(), post(postMaxChars), scrape()) + if (store.get(site).reply != null) listOf(reply(postMaxChars)) else emptyList()

    private fun replyError(e: ReplyFailedException): String {
        val png = e.diagnostics?.screenshotPng
        val path = if (png != null && shotDir != null) runCatching {
            java.io.File(shotDir!!.apply { mkdirs() }, "reply-fail-${System.currentTimeMillis()}.png").apply { writeBytes(png) }.absolutePath
        }.getOrNull() else null
        return buildJsonObject {
            put("ok", false); put("error", e.message ?: "reply failed")
            e.diagnostics?.url?.let { put("page_url", it) }
            e.diagnostics?.pageText?.let { put("page_text", it.take(600)) }
            path?.let { put(WebScreenshotTool.IMAGE_PATH, it) }
            e.diagnostics?.screenshotError?.let { put("screenshot_error", it) }
            if (e.steps.isNotEmpty()) put("steps", JsonArray(e.steps.map { JsonPrimitive(it) }))
            put("note", "Do not retry with web_click/web_type; fix the cause or ask the user.")
        }.toString()
    }

    /** Deterministic reply / quote (never generic click/type): see [SocialAutomation.reply]. */
    private fun reply(maxChars: Int) = object : AgentTool {
        override val name = "${prefix}_reply"
        override val description = "ALWAYS use this to reply to / comment on a $displayName post (never web_click/web_type): " +
            "opens the post, uses ITS reply composer, types the text, verifies it and submits with the composer's own Reply button " +
            "(never schedule/GIF/poll/emoji/media). Returns reply_url when found. mode=quote instead publishes a new post quoting it."
        override val parameters = schema(listOf("url", "text"),
            "url" to prop("string", "URL of the post to reply to (https://x.com/<user>/status/<id>)"),
            "text" to prop("string", "Reply text (max $maxChars characters)"),
            "mode" to prop("string", "reply (default) | quote"))
        override suspend fun execute(args: JsonObject) = execute(args, ToolContext(0))
        override suspend fun execute(args: JsonObject, ctx: ToolContext): String {
            val cfg = store.get(site)
            val url = args.str("url")?.trim()?.takeIf { u -> SiteScopes.matches(u, cfg) && Regex("/status/\\d+").containsMatchIn(u) }
                ?: return errorJson("url of a $displayName post (…/status/<id>) is required")
            val text = args.str("text")?.trim().orEmpty()
            if (text.isEmpty()) return errorJson("text is required")
            val mode = args.str("mode")?.lowercase() ?: "reply"
            return when (mode) {
                "reply" -> {
                    if (text.length > maxChars) return errorJson("text is ${text.length} chars; max is $maxChars")
                    guarded(ctx) { a ->
                        val r = a.reply(url, text)
                        buildJsonObject {
                            put("ok", true); put("site", site); put("mode", "reply"); put("in_reply_to", url)
                            put("reply_url", r.replyUrl); put("confirmed_by", r.confirmedBy)
                            put("verified", r.replyUrl != null || r.confirmedBy == "toast")
                            put("composer", r.composer.name.lowercase()); put("attempts", r.attempts)
                            put("path", r.steps.lastOrNull { it.startsWith("path:") }?.removePrefix("path: "))
                            put("steps", JsonArray(r.steps.map { JsonPrimitive(it) }))
                        }.toString()
                    }
                }
                "quote" -> {
                    // X turns a post URL at the end of a post into a quote of that post.
                    val clean = url.substringBefore('?')
                    val full = "$text $clean"
                    if (full.length > maxChars + clean.length) return errorJson("text is ${text.length} chars; max is $maxChars")
                    guarded(ctx) { a ->
                        a.post(full)
                        buildJsonObject { put("ok", true); put("site", site); put("mode", "quote"); put("quoted", clean)
                            put("steps", JsonArray(a.lastStepLog.map { JsonPrimitive(it) })) }.toString()
                    }
                }
                else -> errorJson("mode must be reply or quote")
            }
        }
    }

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
        override val description = "Scrape posts from $displayName: kind=timeline (home feed), profile (needs handle), search (needs query)" +
            (if (store.get(site).replies != null) " or replies (needs url of the post: only real reply posts below it, never the logged-in account's sidebar banner or the post itself; is_self marks your own replies)." else ".")
        override val parameters = schema(listOf("kind"),
            "kind" to prop("string", "timeline | profile | search" + if (store.get(site).replies != null) " | replies" else ""),
            "url" to prop("string", "Post URL for kind=replies (e.g. https://x.com/user/status/123)"),
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
                "replies" -> {
                    val cfg = store.get(site)
                    if (cfg.replies == null) return errorJson("kind=replies is not supported for $displayName")
                    val url = args.str("url")?.trim()?.takeIf { u -> ThreadReplies.statusId(u, cfg.replies) != null && SiteScopes.matches(u, cfg) }
                        ?: return errorJson("url of a $displayName post (…/status/<id>) is required for kind=replies")
                    val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
                    return guarded(ctx) { a ->
                        val r = a.scrapeReplies(url, limit)
                        buildJsonObject {
                            put("site", site); put("kind", kind); put("url", url)
                            r.selfHandle?.let { put("logged_in_as", it) }
                            r.focal?.let { put("post", it) }
                            put("count", r.replies.size); put("items", JsonArray(r.replies))
                            if (r.dropped.isNotEmpty()) put("ignored", JsonArray(r.dropped.map { JsonPrimitive(it) }))
                        }.toString()
                    }
                }
                else -> return errorJson("kind must be timeline, profile, search" + if (store.get(site).replies != null) " or replies" else "")
            }
            val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
            return guarded(ctx) { a ->
                val items = a.scrape(kind, params, limit)
                buildJsonObject { put("site", site); put("kind", kind); put("count", items.size); put("items", items) }.toString()
            }
        }
    }
}

/** Which site config (if any) a URL belongs to, for scoped page text in web_scrape. */
object SiteScopes {
    fun matches(url: String, cfg: SiteConfig): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.removePrefix("mobile.") ?: return false
        val domains = listOf(cfg.domain.trimStart('.')) + if (cfg.site == SelectorStore.X) listOf("twitter.com") else emptyList()
        return domains.any { d -> d.isNotBlank() && (host == d || host.endsWith(".$d")) }
    }

    fun textScopeFor(url: String, store: SelectorStore): TextScope? =
        listOf(SelectorStore.X, SelectorStore.FACEBOOK).map(store::get).firstOrNull { it.textScope != null && matches(url, it) }?.textScope
}
