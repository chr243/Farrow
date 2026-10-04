package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BridgeClient
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1.0.6: generic web_click / web_type must not drive X's composers. A phone run typed with web_type and clicked
 * button[aria-label="Reply"] at once: X never registered the submit, blurred, showed "Save post?" and truncated the text.
 * Blocked on x.com / twitter.com: tweetTextarea_*, tweetButton*, Reply/Post buttons, the compose dialog, the reply
 * bubble, the "Save post?" sheet, and any editor inside a compose dialog.
 */
object ComposerGuard {
    const val MESSAGE = "Use x_reply (comments/replies) or x_post (new posts); generic clicks and typing on X composers are blocked. " +
        "Do not retry with web_click/web_type."

    private val SELECTOR_PATTERNS = listOf(
        Regex("tweetTextarea", RegexOption.IGNORE_CASE),
        Regex("tweetButton", RegexOption.IGNORE_CASE),
        Regex("""data-testid\s*[*^$|~]?=\s*["']?(reply|unsentButton|confirmationSheet\w*|sheetDialog)\b""", RegexOption.IGNORE_CASE),
        Regex("""aria-label\s*[*^$|~]?=\s*["']?\s*(reply|post|répondre|repondre|poster|publier|tweet|save|discard|enregistrer)\s*["']?\s*]""", RegexOption.IGNORE_CASE),
        Regex("""compose/(post|tweet)""", RegexOption.IGNORE_CASE),
        Regex("""inline_reply""", RegexOption.IGNORE_CASE),
    )

    /** Why [selector] obviously targets an X compose surface, or null. */
    fun selectorReason(selector: String): String? =
        SELECTOR_PATTERNS.firstOrNull { it.containsMatchIn(selector) }?.let { "selector targets an X composer (${it.pattern.take(40)})" }

    fun isXHost(host: String?): Boolean {
        val h = (host ?: "").lowercase().removePrefix("www.").removePrefix("mobile.")
        return h == "x.com" || h == "twitter.com"
    }

    /**
     * Page probe → "host|verdict": verdict = ok | composer:<why>. The element is resolved like TBP does
     * (`querySelector`) and checked with `closest()` against the compose surfaces.
     */
    fun probeJs(selector: String): String {
        val sel = JsonPrimitive(selector).toString()
        return """(()=>{const h=location.hostname;let e=null;try{e=document.querySelector($sel)}catch(x){}
            if(!e)return h+'|ok';
            const C='[data-testid^="tweetTextarea_"],[data-testid^="tweetButton"],[data-testid="reply"],[data-testid="toolBar"],[data-testid="confirmationSheetDialog"],[data-testid="inline_reply_offscreen"],[data-testid="unsentButton"]';
            const hit=e.closest(C);if(hit)return h+'|composer:'+(hit.getAttribute('data-testid')||'compose element');
            const d=e.closest('[role="dialog"],[aria-modal="true"],[data-testid="sheetDialog"]');
            if(d&&d.querySelector('[data-testid^="tweetTextarea_"],[data-testid^="tweetButton"],[data-testid^="confirmationSheet"]'))return h+'|composer:compose dialog';
            const b=e.closest('button,[role="button"]');const lab=b?((b.getAttribute('aria-label')||b.innerText||'').trim()):'';
            if(b&&/^(reply|post|répondre|repondre|poster|publier|tweet|save|discard|enregistrer|supprimer)$/i.test(lab))return h+'|composer:'+lab+' button';
            if((e.isContentEditable||e.closest('[contenteditable="true"]'))&&e.closest('[data-testid="primaryColumn"],[role="dialog"]'))return h+'|composer:editor';
            return h+'|ok'})()"""
    }

    /** Decision from the probe result (null = allowed). [probe] null = the page did not answer. */
    fun decide(selector: String, probe: String?): String? {
        val bySelector = selectorReason(selector)
        if (probe == null) return bySelector?.let { MESSAGE } // fail closed only for obvious X selectors
        val host = probe.substringBefore('|')
        val verdict = probe.substringAfter('|', "ok")
        if (!isXHost(host)) return null
        return if (verdict.startsWith("composer") || bySelector != null) MESSAGE else null
    }

    /** Null = go ahead; else the error JSON for the tool. */
    suspend fun check(bridge: BridgeClient, selector: String): String? {
        val r = runCatching { bridge.eval(probeJs(selector), 20) }.getOrNull()
        val v = r?.takeIf { it.ok }?.valueString()?.trim()?.trim('"')
        val why = decide(selector, v) ?: return null
        val what = v?.substringAfter('|', "")?.takeIf { it.startsWith("composer") }?.removePrefix("composer:")
        return kotlinx.serialization.json.buildJsonObject {
            put("error", JsonPrimitive(why))
            put("blocked", JsonPrimitive(true))
            put("selector", JsonPrimitive(selector))
            what?.let { put("target", JsonPrimitive(it)) }
        }.toString()
    }
}
