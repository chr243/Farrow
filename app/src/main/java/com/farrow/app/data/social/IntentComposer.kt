package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.0.7: the reply intent composer (x.com/intent/post?in_reply_to=<id> → /compose/post). Before typing, the composer
 * must show X's reply context ("Replying to @author"), so a failed in_reply_to can never become a standalone post.
 */
object IntentComposer {
    /** {u: location, ctx: text of the composer dialog (or page) around our box, at most 1500 chars}. */
    fun contextJs(boxCss: String, dialogCss: String): String {
        val b = JsonPrimitive(boxCss).toString(); val d = JsonPrimitive(dialogCss).toString()
        return """(()=>{const b=document.querySelector($b);const r=(b&&b.closest($d))||document.querySelector('main')||document.body;
            const t=(r.innerText||r.textContent||'').slice(0,1500);return JSON.stringify({u:location.href,ctx:t})})()"""
    }

    private fun parse(raw: String?): Pair<String?, String>? = raw?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()?.let { o ->
            o["u"]?.jsonPrimitive?.contentOrNull to (o["ctx"]?.jsonPrimitive?.contentOrNull ?: "")
        }
    }

    /** The "Replying to …" line, if any. */
    fun replyLine(ctx: String, replyingTo: String): String? {
        val re = Regex(replyingTo, RegexOption.IGNORE_CASE)
        return ctx.lines().map { it.trim() }.withIndex().firstOrNull { re.containsMatchIn(it.value) }?.let { (i, l) ->
            // X sometimes renders "Replying to" and "@user" as separate lines.
            if (l.contains('@')) l else (l + " " + ctx.lines().drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()).trim()
        }
    }

    /** Null = it is a reply composer for [user] (or any author when user is unknown/"i"); else why not. */
    fun check(raw: String?, replyingTo: String, user: String?): String? {
        val (url, ctx) = parse(raw) ?: return "the intent composer did not answer"
        val path = runCatching { java.net.URI(url ?: "").path }.getOrNull().orEmpty()
        if (path.startsWith("/i/flow/")) return "X sent the intent to $path (login/flow), not to a composer"
        val line = replyLine(ctx, replyingTo) ?: return "the composer does not say 'Replying to @…' (it would be a standalone post)"
        if (user != null && user != "i" && !Regex("@" + Regex.escape(user) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(line))
            return "the composer replies to someone else ($line), not @$user"
        return null
    }

    fun summary(raw: String?, replyingTo: String): String =
        parse(raw)?.let { (u, ctx) -> (replyLine(ctx, replyingTo) ?: "?") + " on " + (u ?: "?") } ?: "?"
}
