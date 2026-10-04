package com.farrow.app.data.social

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * How to reply to a post (selectors/x.json "reply", used by x_reply). The composer is chosen deterministically:
 * the reply box ([textarea]) of the top-most [dialog] (modal opened by the focal post's reply button), else the inline
 * reply box in the [conversation] column (never one inside a post). The submit button must be one of [submit] inside
 * the SAME composer container. [forbidden] controls (schedule, GIF, poll, emoji, location, media…) are never clicked.
 */
@Serializable
data class ReplyComposerSpec(
    val conversation: String,
    /** The focal post (the one being replied to). */
    val focal: String,
    /** Reply button (speech bubble) in a post's action bar. */
    val replyButton: String,
    /** A post in the conversation column. */
    val article: String = "article[data-testid=\"tweet\"]",
    /** How long the bubble click may take to open the composer before falling back (ms). */
    val bubbleWaitMs: Long = 5_000,
    val dialog: String = "[role=\"dialog\"]",
    val textarea: String,
    val submit: List<String>,
    val forbidden: List<String> = emptyList(),
    /** URL fragments of pages we must never be on while replying (schedule composer, unsent drafts, flows…). */
    val wrongUrlPatterns: List<String> = emptyList(),
    /** Present inside the top dialog = the wrong dialog (schedule picker, drafts). */
    val wrongDialog: List<String> = emptyList(),
    val toast: String = "[data-testid=\"toast\"]",
    /** Regex (case-insensitive) of toast texts that mean the reply was NOT posted. */
    val errorToast: String? = null,
    val maxAttempts: Int = 2,
    /** Regex of X's reply context line in a composer ("Replying to @user"); the intent path requires it. */
    val replyingTo: String = "(Replying to|En réponse à|Réponse à|Antwort an|Respondiendo a|In risposta a|Em resposta a)",
    /** Leftover dialogs to close before replying/posting. */
    val stray: StraySpec = StraySpec(),
)

enum class ComposerKind { MODAL, INLINE }

/** The post to reply to and its own reply bubble (live CSS via data-farrow-i marks). [how]: "status id" or "focal". */
data class TargetPost(val article: String, val replyButton: String?, val how: String)

sealed interface ComposerPick {
    /** [box]/[send]: CSS for the live elements (data-farrow-i marks). */
    data class Found(val kind: ComposerKind, val box: String, val send: String, val sendEnabled: Boolean, val boxText: String) : ComposerPick
    /** A dialog/page that is not a reply composer (e.g. the schedule picker): close it and retry. */
    data class Wrong(val why: String) : ComposerPick
    data class Missing(val why: String) : ComposerPick
}

/**
 * Pure composer selection over the marked HTML the browser sends ([markJs]); unit-tested on fixture HTML.
 * Elements carry `data-farrow-i` indexes so the chosen ones can be addressed in the live page.
 */
object ReplyComposer {
    const val MARK = "data-farrow-i"
    const val ROOT = "data-farrow-root"

    /** Browsers understand `[a*="x" i]`; Jsoup's attribute matching is already case-insensitive. */
    internal fun jsoupCss(css: String) = css.replace(Regex("\"\\s+i\\]"), "\"]")

    fun sel(i: String) = "[$MARK=\"$i\"]"

    fun isWrongUrl(url: String?, spec: ReplyComposerSpec): String? =
        url?.let { u -> val path = runCatching { java.net.URI(u).path }.getOrNull() ?: u; spec.wrongUrlPatterns.firstOrNull { path.contains(it) } }

    fun locate(html: String, spec: ReplyComposerSpec, url: String? = null, preferInline: Boolean = false): ComposerPick {
        isWrongUrl(url, spec)?.let { return ComposerPick.Wrong("the browser is on $url (matches $it), not the post") }
        val doc = Jsoup.parse(html)
        val roots = doc.select("[$ROOT]")
        val conversation = roots.firstOrNull { it.attr(ROOT) == "conversation" }
        // A Grok drawer/panel is never "the top dialog" (v1.0.6 read it as "a dialog without a reply box").
        val dialogs = roots.filter { it.attr(ROOT) == "dialog" && !(it.children().firstOrNull()?.let(GrokShield::isGrokOverlay) ?: false) }
        val top = dialogs.lastOrNull()
        val textarea = jsoupCss(spec.textarea)
        if (top != null && !preferInline) {
            spec.wrongDialog.map(::jsoupCss).firstOrNull { top.selectFirst(it) != null }?.let {
                return ComposerPick.Wrong("a dialog that is not the reply composer is open (has $it)")
            }
            val box = top.selectFirst(textarea)
                ?: return ComposerPick.Wrong("a dialog without a reply box is open (${top.text().take(80)})")
            return pick(ComposerKind.MODAL, box, top, spec)
        }
        val inline = conversation?.select(textarea)?.firstOrNull { b -> b.parents().none { it.tagName() == "article" } }
            ?: return ComposerPick.Missing(if (conversation == null) "the conversation column is not on the page" else "no reply box in the conversation")
        return pick(ComposerKind.INLINE, inline, conversation, spec)
    }

    /**
     * The post to reply to: the conversation article whose OWN permalink (the status link around its time) has [id]
     * (so a URL pointing at a reply targets that reply), else the focal article. Its reply bubble must be in its own
     * action bar, not inside a quoted post.
     */
    fun target(html: String, spec: ReplyComposerSpec, id: String): TargetPost? {
        val doc = Jsoup.parse(html)
        val conv = doc.selectFirst("[$ROOT=conversation]") ?: return null
        val art = jsoupCss(spec.article)
        val articles = conv.select(art).filter { a -> a.parents().none { it.`is`(art) } }
        val idRe = Regex("/status/(\\d+)")
        fun ownId(a: Element): String? {
            val links = a.select("a[href]").filter { idRe.containsMatchIn(it.attr("href")) && it.parents().none { p -> p.attr("role") == "link" } }
            val l = links.firstOrNull { it.selectFirst("time") != null } ?: return null
            return idRe.find(l.attr("href"))?.groupValues?.get(1)
        }
        val byId = articles.firstOrNull { ownId(it) == id }
        val focalCss = jsoupCss(spec.focal.substringAfterLast(' ').let { last -> if (last.startsWith("article")) last else spec.focal })
        val a = byId ?: articles.firstOrNull { it.`is`(focalCss) } ?: return null
        val btn = a.select(jsoupCss(spec.replyButton)).firstOrNull { b -> b.parents().takeWhile { it !== a }.none { it.attr("role") == "link" } }
        return TargetPost(sel(a.attr(MARK)), btn?.attr(MARK)?.takeIf { it.isNotBlank() }?.let(::sel), if (byId != null) "status id" else "focal")
    }

    private fun pick(kind: ComposerKind, box: Element, root: Element, spec: ReplyComposerSpec): ComposerPick {
        val forbidden = spec.forbidden.map(::jsoupCss)
        fun bad(e: Element) = forbidden.any { f -> e.`is`(f) }
        // The composer container = the nearest ancestor (up to the root) that also holds a submit button.
        var c: Element? = box.parent()
        var send: Element? = null
        while (c != null && send == null) {
            send = spec.submit.asSequence().map(::jsoupCss).mapNotNull { s -> c!!.select(s).firstOrNull { !bad(it) } }.firstOrNull()
            if (c === root) break
            c = c.parent()
        }
        if (send == null) return ComposerPick.Missing("no ${spec.submit.joinToString(" / ")} in the reply composer")
        val boxId = box.attr(MARK).ifBlank { return ComposerPick.Missing("reply box not marked") }
        val sendId = send.attr(MARK).ifBlank { return ComposerPick.Missing("submit button not marked") }
        if (bad(send) || bad(box)) return ComposerPick.Missing("refusing a forbidden control")
        val enabled = send.attr("aria-disabled") != "true" && !send.hasAttr("disabled")
        return ComposerPick.Found(kind, sel(boxId), sel(sendId), enabled, box.text())
    }

    /**
     * Marks candidates in the live page (conversation column + dialogs) and returns
     * JSON {h: marked HTML wrapped in data-farrow-root containers, u: location.href}.
     */
    fun markJs(spec: ReplyComposerSpec): String {
        val conv = kotlinx.serialization.json.JsonPrimitive(spec.conversation).toString()
        val dlg = kotlinx.serialization.json.JsonPrimitive(spec.dialog).toString()
        return """(()=>{document.querySelectorAll('[$MARK]').forEach(e=>e.removeAttribute('$MARK'));let n=0;
            const c=document.querySelector($conv);const ds=[...document.querySelectorAll($dlg)].filter(d=>!d.closest('[${GrokShield.ATTR}]'));
            const strip=r=>{const k=r.cloneNode(true);k.querySelectorAll('svg,img,video,picture,style,script,noscript').forEach(e=>e.remove());
              k.querySelectorAll('*').forEach(e=>{e.removeAttribute('class');e.removeAttribute('style')});return k.outerHTML};
            const mark=r=>r.querySelectorAll('[data-testid],[role],[contenteditable],button,a,select,input,textarea').forEach(e=>e.setAttribute('$MARK',String(n++)));
            let h='';if(c){mark(c);h+='<div $ROOT="conversation">'+strip(c)+'</div>'}
            ds.forEach(d=>{mark(d);h+='<div $ROOT="dialog">'+strip(d)+'</div>'});
            return JSON.stringify({h:h,u:location.href})})()""".trimIndent()
    }

    // ---------------- v1.0.8/v1.0.9: what the composer selector resolves to (step-log diagnostics) ----------------

    /** Draft.js editor inside a composer box (X: the tweetTextarea_0 div IS the contenteditable). */
    const val EDITABLE = "[contenteditable=\"true\"],[contenteditable=\"\"],.public-DraftEditor-content,[role=\"textbox\"]"

    /** v1.0.10: unique marks on THE composer being used (editor + its own submit button); steps then use these. */
    const val COMPOSE_ATTR = "data-farrow-compose"
    const val SUBMIT_ATTR = "data-farrow-submit"
    const val COMPOSE_CSS = "[$COMPOSE_ATTR=\"1\"]"
    const val SUBMIT_CSS = "[$SUBMIT_ATTR=\"1\"]"

    /**
     * v1.0.10 (v1.0.9 logged the FIRST match only): picks the composer the user sees — the [box] inside the topmost open
     * [dialog] when one holds a box, else the first [box] — marks its editor [COMPOSE_ATTR] and, when [submit] is given, the
     * submit button of the same composer (nearest ancestor holding one) [SUBMIT_ATTR]. Old marks are removed first.
     * Returns {ok, testid, cls, rect, inDialog, inConversation, inArticle, focused, boxes, chars, u, ctx, firstInDialog,
     * firstIsTarget, submit} — firstIsTarget=false is the v1.0.9 bug: document.querySelector(box) is another box (X's home
     * composer behind the reply modal), which the bridge used to focus, type into and verify.
     */
    fun composeTargetJs(box: String, dialog: String, conversation: String, submit: String? = null): String {
        fun q(v: String?) = if (v == null) "null" else kotlinx.serialization.json.JsonPrimitive(v).toString()
        return """(()=>{const T=${q(box)},D=${q(dialog)},C=${q(conversation)},E=${q(EDITABLE)},S=${q(submit)},A=${q(COMPOSE_ATTR)},B=${q(SUBMIT_ATTR)};
            document.querySelectorAll('['+A+'],['+B+']').forEach(x=>{x.removeAttribute(A);x.removeAttribute(B)});
            const boxes=[...document.querySelectorAll(T)],all=boxes.length,first=boxes[0];
            if(!first)return JSON.stringify({ok:false,why:'no composer box',boxes:all,u:location.href});
            const inDlg=boxes.filter(x=>x.closest(D)),b=inDlg.length?inDlg[inDlg.length-1]:first;
            const ed=b.matches(E)?b:(b.querySelector(E)||b);const r=ed.getBoundingClientRect(),a=document.activeElement,d=b.closest(D);
            let c=b;for(let i=0;i<12&&c.parentElement;i++){c=c.parentElement;if(c.querySelector(S||'[data-testid^="tweetButton"]'))break}
            ed.setAttribute(A,'1');let sb=null;if(S){sb=c.querySelector(S);if(sb)sb.setAttribute(B,'1')}
            return JSON.stringify({ok:true,testid:ed.getAttribute('data-testid')||b.getAttribute('data-testid')||'',cls:String(ed.className||'').slice(0,60),
              rect:[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)],inDialog:!!d,inConversation:!!b.closest(C),
              inArticle:!!b.closest('article'),focused:!!(a&&(a===ed||ed.contains(a))),boxes:all,chars:(ed.textContent||'').length,
              u:location.href,ctx:String((d||c).innerText||'').slice(0,1500),firstInDialog:!!first.closest(D),firstIsTarget:first===b,
              submit:sb?(sb.getAttribute('data-testid')||sb.tagName.toLowerCase()):''})})()""".trimIndent()
    }

    /** Parsed [composeTargetJs] result (logged in the step log of x_post and x_reply). */
    data class EditorTarget(val ok: Boolean, val why: String?, val testid: String, val cls: String, val rect: List<Int>,
                            val inDialog: Boolean, val focused: Boolean, val boxes: Int, val chars: Int,
                            /** v1.0.9 [composeTargetJs]: inside the conversation column / inside a post, page URL, composer text. */
                            val inConversation: Boolean = false, val inArticle: Boolean = false, val url: String? = null, val ctx: String = "",
                            /** v1.0.10: the first box in the document (what the generic selector hit) is/isn't the target; marked submit. */
                            val firstIsTarget: Boolean = true, val firstInDialog: Boolean = false, val submit: String = "") {
        fun describe() = if (!ok) "none (${why ?: "?"}; $boxes reply boxes on the page)" else
            "testid=${testid.ifBlank { "?" }}${if (cls.isNotBlank()) " .${cls.substringBefore(' ')}" else ""} rect=${rect.joinToString(",")} " +
                "inDialog=$inDialog focused=$focused boxes=$boxes chars=$chars" + (if (inConversation) " inConversation=true" else "") +
                (if (inArticle) " inArticle=true" else "") + (url?.let { " url=$it" } ?: "") +
                (if (!firstIsTarget) " (first composeText in the document is another box${if (firstInDialog) " in a dialog" else " outside the dialog"})" else "") +
                (if (submit.isNotBlank()) " submit=$submit" else "")

        /** {u, ctx} for [IntentComposer.check]. */
        fun contextJson() = kotlinx.serialization.json.buildJsonObject {
            put("u", kotlinx.serialization.json.JsonPrimitive(url)); put("ctx", kotlinx.serialization.json.JsonPrimitive(ctx)) }.toString()
    }

    fun parseEditorTarget(raw: String?): EditorTarget {
        val o = raw?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            ?: return EditorTarget(false, "the page did not answer", "", "", emptyList(), false, false, 0, 0)
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        fun bool(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        fun int(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
        val rect = (o["rect"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }.orEmpty()
        return EditorTarget(bool("ok"), str("why"), str("testid").orEmpty(), str("cls").orEmpty(), rect, bool("inDialog"), bool("focused"), int("boxes"), int("chars"),
            bool("inConversation"), bool("inArticle"), str("u"), str("ctx").orEmpty(),
            firstIsTarget = (o["firstIsTarget"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false", firstInDialog = bool("firstInDialog"),
            submit = str("submit").orEmpty())
    }
}
