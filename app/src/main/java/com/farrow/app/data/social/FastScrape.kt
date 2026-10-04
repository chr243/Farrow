package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/**
 * Fast scrapes (v1.0.5). Each poll is ONE console eval that installs the media filter, extracts every post on the
 * page, reports where the page is, and can scroll; the Kotlin side only merges and decides. Pure, so it is unit-tested.
 */
object FastScrape {
    /** Gap between polls (each poll is already one console round trip). */
    const val POLL_MS = 150L
    /** Count stagnant this long → scroll (after the first posts are there). */
    const val SETTLE_MS = 700L
    /** After a scroll, wait this long for new posts before counting the scroll as fruitless. */
    const val SCROLL_WAIT_MS = 1_500L
    /** Media filter TTL: lapses by itself if the restore eval never comes (extended by every poll). */
    const val MEDIA_TTL_MS = 45_000L

    private fun js(s: String): String = JsonPrimitive(s).toString()

    /**
     * Page-scope media filter (no browser restart, no privileged context: TBP drives Firefox without Marionette).
     * While on: `src`/`srcset`/`poster` of img/source/video (property and setAttribute) are held back instead of
     * loading, CSS background images are suppressed and `play()` is refused like an autoplay block. `__fwMBoff()` puts
     * every held value back (the images then load normally), so web_screenshot / x_post media work afterwards.
     */
    val MEDIA_ON: String get() = mediaOnJs(MEDIA_TTL_MS)

    /** [MEDIA_ON] with a custom lifetime (web tools / x_reply pages keep it longer; never shortens a longer one). */
    fun mediaOnJs(ttlMs: Long): String = """var w=window;if(w.__fwMB){w.__fwMB.until=Math.max(w.__fwMB.until,Date.now()+$ttlMs);}else{(function(){
      var st={until:Date.now()+$ttlMs,saved:[],held:[]};var on=function(){return Date.now()<st.until};
      var E=Element.prototype,M=HTMLMediaElement.prototype;
      var media=function(el,k){k=String(k).toLowerCase();
        if(el instanceof HTMLImageElement||el instanceof HTMLSourceElement)return k==='src'||k==='srcset'?k:null;
        if(el instanceof HTMLMediaElement)return k==='src'||k==='poster'?k:null;return null};
      var hold=function(el,k,v){if(!el.__fwH){el.__fwH={};st.held.push(el)}el.__fwH[k]=String(v)};
      [[HTMLImageElement.prototype,'src'],[HTMLImageElement.prototype,'srcset'],[HTMLSourceElement.prototype,'src'],
       [HTMLSourceElement.prototype,'srcset'],[M,'src'],[HTMLVideoElement.prototype,'poster']].forEach(function(p){
        var o=Object.getOwnPropertyDescriptor(p[0],p[1]);if(!o||!o.set)return;st.saved.push([p[0],p[1],o]);
        Object.defineProperty(p[0],p[1],{configurable:true,enumerable:o.enumerable,
          get:function(){return this.__fwH&&(p[1] in this.__fwH)?this.__fwH[p[1]]:o.get.call(this)},
          set:function(v){if(on())hold(this,p[1],v);else o.set.call(this,v)}})});
      var sa=E.setAttribute;st.sa=sa;E.setAttribute=function(k,v){var m=on()&&media(this,k);if(m){hold(this,m,v);return}
        return sa.apply(this,arguments)};
      var pl=M.play;st.pl=pl;M.play=function(){if(on())return Promise.reject(new DOMException('media blocked while scraping','NotAllowedError'));
        return pl.apply(this,arguments)};
      var css=document.createElement('style');css.textContent='*{background-image:none!important}';
      (document.head||document.documentElement).appendChild(css);st.css=css;
      document.querySelectorAll('video').forEach(function(v){try{pl&&v.pause()}catch(e){}});
      w.__fwMBoff=function(){if(w.__fwMB!==st)return 0;w.__fwMB=null;clearInterval(st.t);
        st.saved.forEach(function(s){Object.defineProperty(s[0],s[1],s[2])});E.setAttribute=st.sa;M.play=st.pl;
        try{st.css.remove()}catch(e){}var n=0;st.held.forEach(function(el){var h=el.__fwH;delete el.__fwH;
          for(var k in h){try{st.sa.call(el,k,h[k]);n++}catch(e){}}});return n};
      st.t=setInterval(function(){if(!on())w.__fwMBoff()},2000);w.__fwMB=st;})();}""".trimIndent()

    /** Restore eval (only needed when the last poll didn't already release the filter). */
    /** Releases held media (web_screenshot load_images=true) and the Grok shield. */
    const val MEDIA_OFF = "(()=>{if(window.__fwGrokOff)window.__fwGrokOff();return String(window.__fwMBoff?window.__fwMBoff():0)})()"
    /** Page filter lifetime for scrapes, x_reply/x_post and web tools. */
    const val PAGE_MEDIA_TTL_MS = 10 * 60_000L

    /** JS regex source matching the target page's path (+ the search query, if any). */
    data class Target(val pathRx: String, val q: String?)

    fun target(url: String, statusId: String? = null): Target {
        if (statusId != null) return Target("^/[^/]+/status/$statusId/?$", null)
        val u = runCatching { URI(url) }.getOrNull()
        val path = (u?.path ?: "/").trimEnd('/').ifEmpty { "/" }
        return Target("^" + escapeRx(path) + "/?$", queryQ(u?.rawQuery))
    }

    private fun queryQ(raw: String?): String? = raw?.split('&')?.firstOrNull { it.startsWith("q=") }?.removePrefix("q=")
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    private fun escapeRx(t: String) = buildString { t.forEach { c -> if (c in "\\^$.|?*+()[]{}/") append('\\'); append(c) } }

    /** Same test as the page does: same host (www./mobile. ignored, twitter.com = x.com), path regex, search query. */
    fun onTarget(href: String?, targetUrl: String, t: Target): Boolean {
        val u = href?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val tu = runCatching { URI(targetUrl) }.getOrNull() ?: return false
        fun host(h: String?) = (h ?: "").lowercase().removePrefix("www.").removePrefix("mobile.").let { if (it == "twitter.com") "x.com" else it }
        if (host(u.host) != host(tu.host)) return false
        val path = (u.path ?: "/").ifEmpty { "/" }
        if (!Regex(t.pathRx, RegexOption.IGNORE_CASE).containsMatchIn(path)) return false
        return t.q == null || queryQ(u.rawQuery) == t.q
    }

    private fun prelude(block: Boolean, t: Target?, scroll: Boolean): String = buildString {
        // v1.0.7: images stay blocked after the scrape too (until Firefox's own image pref applies); the Grok shield is released.
        if (block) append(mediaOnJs(PAGE_MEDIA_TTL_MS)).append('\n').append(GrokShield.ON_STMT).append('\n')
        append("const onT=").append(
            if (t == null) "false"
            else "new RegExp(${js(t.pathRx)},'i').test(decodeURIComponent(location.pathname))" +
                (t.q?.let { "&&new URLSearchParams(location.search).get('q')===${js(it)}" } ?: "")
        ).append(";\nlet top=false;if(onT&&TOP&&window.scrollY>200){window.scrollTo(0,0);top=true}\n")
        append("const SCROLL=").append(scroll).append(";\n")
    }

    private fun epilogue(block: Boolean, releaseExpr: String): String =
        "if(SCROLL&&!top)window.scrollBy(0,Math.round(innerHeight*0.9));\n" +
            "let mb=${if (block) "'on'" else "'off'"};if(${if (block) "true" else "false"}&&($releaseExpr)){if(window.__fwGrokOff)window.__fwGrokOff();mb='off'}\n"

    /**
     * One poll for timeline / profile / search: the v1.0.4 field extraction, unchanged (same fields, same order), plus
     * `u` (location), `n` (unique posts in the DOM, same key as [merge]), `top` (scrolled back up — discard this
     * batch), `mb` (media filter state). The filter is released in this same eval once the page holds [limit] posts.
     */
    fun extractJs(spec: ScrapeSpec, limit: Int, t: Target?, scrollTop: Boolean, scroll: Boolean, statusUrl: Boolean = false): String {
        val fields = JsonObject(spec.fields.mapValues { JsonPrimitive(it.value) }).toString()
        val block = spec.blockMedia
        return "(()=>{const TOP=$scrollTop;\n" + prelude(block, t, scroll) + """const F=$fields;const out=[];
            document.querySelectorAll(${js(spec.item)}).forEach(it=>{const o={};
              for(const k in F){const spec=F[k];const at=spec.lastIndexOf('@');
                const sel=at>=0?spec.slice(0,at):spec;const attr=at>=0?spec.slice(at+1):'text';
                let el=null;try{el=sel?it.querySelector(sel):it}catch(e){}
                if(!el){o[k]=null;continue}
                o[k]=attr==='text'?(el.innerText||'').trim():(attr==='href'?el.href:el.getAttribute(attr));}
${if (statusUrl) PERMALINK else ""}
              out.push(o)});
            const D=${spec.dedupeField?.let { js(it) } ?: "null"};const keys=new Set();
            out.forEach(o=>keys.add(D!==null&&o[D]!=null?String(o[D]):JSON.stringify(o)));""".trimIndent() + "\n" +
            epilogue(block, "!top&&keys.size>=$limit") +
            "return JSON.stringify({u:location.href,n:keys.size,top:top,mb:mb,items:out})})()"
    }

    /** X: the post's own permalink (status link with a <time>, not inside a quoted post) → `status_url` (normalized in Kotlin). */
    private const val PERMALINK = """{const l=[...it.querySelectorAll('a[href*="/status/"]')].find(a=>a.querySelector('time')&&!(a.parentElement&&a.parentElement.closest('[role="link"]')&&it.contains(a.parentElement.closest('[role="link"]'))));
              o.status_url=l?l.href:null;}"""

    /** Adds the canonical `https://x.com/<user>/status/<id>` (x_reply's url) as `status_url`, from the permalink or `url`. */
    fun withStatusUrl(o: JsonObject): JsonObject {
        val raw = o["status_url"]?.jsonPrimitive?.contentOrNull ?: o["url"]?.jsonPrimitive?.contentOrNull
        val c = StatusUrl.canonical(raw)
        return JsonObject(o.toMutableMap().apply { put("status_url", c?.let { JsonPrimitive(it.url) } ?: kotlinx.serialization.json.JsonNull) })
    }

    /** One poll for kind=replies: the v1.0.4 HTML grab (parsed by [ThreadReplies]) plus `u`/`n`/`top`/`mb`. */
    fun grabJs(spec: RepliesSpec, limit: Int, t: Target?, scrollTop: Boolean, scroll: Boolean): String {
        val block = spec.blockMedia
        return "(()=>{const TOP=$scrollTop;\n" + prelude(block, t, scroll) +
            """const r=document.querySelector(${js(spec.scope)})||document.body;const n=r.querySelectorAll(${js(spec.item)}).length;
            const c=r.cloneNode(true);
            c.querySelectorAll('svg,img,video,picture,style,script,noscript').forEach(e=>e.remove());
            c.querySelectorAll('*').forEach(e=>{e.removeAttribute('class');e.removeAttribute('style')});
            const p=${spec.selfLink?.let { "document.querySelector(${js(it)})" } ?: "null"};""".trimIndent() + "\n" +
            // focal post + limit replies on the page → enough (the Kotlin parse decides; a re-poll re-installs the filter)
            epilogue(block, "!top&&n>${limit}") +
            "return JSON.stringify({h:c.outerHTML,self:p?p.getAttribute('href'):null,u:location.href,n:n,top:top,mb:mb})})()"
    }

    data class Batch(val url: String?, val count: Int, val scrolledTop: Boolean, val mediaOn: Boolean, val obj: JsonObject)

    fun parseBatch(raw: String?): Batch? {
        val o = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        return Batch(
            url = o["u"]?.jsonPrimitive?.contentOrNull,
            count = o["n"]?.jsonPrimitive?.intOrNull ?: 0,
            scrolledTop = o["top"]?.jsonPrimitive?.booleanOrNull == true,
            mediaOn = o["mb"]?.jsonPrimitive?.contentOrNull == "on",
            obj = o,
        )
    }

    /** v1.0.4 dedupe, unchanged: key = dedupe field (if present) else the whole object; first occurrence wins. */
    fun merge(seen: LinkedHashMap<String, JsonObject>, items: JsonArray, dedupeField: String?): Int {
        val before = seen.size
        items.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val key = dedupeField?.let { o[it]?.jsonPrimitive?.contentOrNull } ?: o.toString()
            if (key !in seen) seen[key] = o
        }
        return seen.size - before
    }

    fun items(b: Batch): JsonArray = runCatching { b.obj["items"]!!.jsonArray }.getOrElse { JsonArray(emptyList()) }

    /** What the poll loop does next (pure, so the policy is unit-tested). */
    enum class Next { DONE, POLL, SCROLL, GIVE_UP }

    /**
     * [have] unique results so far, [loaded] posts are on the page (a post without replies still is), [lastGrowthMs] since the count last grew, [sinceScrollMs] since the last scroll
     * (null = never scrolled), [firstWaitLeftMs] until the first-post deadline. Stop rules as in v1.0.4: limit reached,
     * [maxScrolls] used, or a scroll (after the second) brought nothing new.
     */
    fun decide(have: Int, loaded: Boolean, limit: Int, lastGrowthMs: Long, sinceScrollMs: Long?, scrolls: Int, maxScrolls: Int,
               firstWaitLeftMs: Long): Next {
        val scrollFruitless = sinceScrollMs != null && lastGrowthMs >= sinceScrollMs
        return when {
            have >= limit -> Next.DONE
            !loaded -> if (firstWaitLeftMs <= 0) Next.GIVE_UP else Next.POLL
            scrollFruitless && sinceScrollMs!! < SCROLL_WAIT_MS -> Next.POLL
            !scrollFruitless && lastGrowthMs < SETTLE_MS -> Next.POLL
            scrolls >= maxScrolls -> Next.DONE
            scrollFruitless && scrolls > 1 -> Next.DONE
            else -> Next.SCROLL
        }
    }
}

/** Per-step timings of one scrape (tool result `timings_ms` + `steps`). */
class ScrapeTimings {
    private val t0 = System.currentTimeMillis()
    val ms = LinkedHashMap<String, Long>()
    val steps = mutableListOf<String>()
    fun add(key: String, v: Long, note: String? = null) {
        ms[key] = (ms[key] ?: 0) + v
        steps += "$key ${v} ms" + (note?.let { " ($it)" } ?: "")
    }
    suspend fun <T> time(key: String, note: (T) -> String? = { null }, block: suspend () -> T): T {
        val s = System.currentTimeMillis()
        val r = block()
        add(key, System.currentTimeMillis() - s, note(r))
        return r
    }
    fun total(): Long = (System.currentTimeMillis() - t0).also { ms["total"] = it }
}
