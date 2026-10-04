package com.farrow.app.data.social

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Replies scrape spec (selectors/x.json "replies"). Everything outside [scope] (side nav, account switcher, sidebar)
 * is ignored, [exclude] subtrees are removed, and only [item] articles AFTER the focal post count as replies.
 */
@Serializable
data class RepliesSpec(
    /** The conversation column, e.g. `[data-testid="primaryColumn"]`. */
    val scope: String,
    /** A real reply/post article, e.g. `article[data-testid="tweet"]`. */
    val item: String,
    /** Subtrees that are never replies (nav, header, account switcher, inline reply composer, sidebar…). */
    val exclude: List<String> = emptyList(),
    /** Fallback focal-post selector when no article links to the status id (X: `article[tabindex="-1"]`). */
    val focal: String? = null,
    /** Headings that end the replies (X: "Discover more" recommendations). Regex, case-insensitive. */
    val stopHeading: String? = null,
    /** Text inside each article; "css" or "css@attr" (attr defaults to text, `href` is made absolute). */
    val fields: Map<String, String> = emptyMap(),
    /** Link to the logged-in account outside the scope, e.g. `a[data-testid="AppTabBar_Profile_Link"]`. */
    val selfLink: String? = null,
    /** Regex with one group: the status id in a post URL. */
    val statusIdPattern: String = "/status/(\\d+)",
)

/** Generic page-text scoping for a site (selectors/x.json "textScope"): keep [scope], drop [exclude] subtrees. */
@Serializable
data class TextScope(val scope: String, val exclude: List<String> = emptyList())

data class RepliesResult(val focal: JsonObject?, val replies: List<JsonObject>, val dropped: List<String>, val selfHandle: String?)

/** Pure (Jsoup) reply extraction, so it is unit-tested on fixture HTML; the browser only supplies the HTML. */
object ThreadReplies {
    /** The handle from a profile href like "/jane_doe" (null for status/other links). */
    fun handleFromHref(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val path = if ("://" in href) href.substringAfter("://").substringAfter('/', "") else href
        return path.substringBefore('?').trim('/').takeIf { it.isNotBlank() && '/' !in it }?.lowercase()
    }

    fun statusId(url: String?, spec: RepliesSpec): String? = url?.let { Regex(spec.statusIdPattern).find(it)?.groupValues?.getOrNull(1) }

    /**
     * [html]: the page (or the scope element's outerHTML); [statusUrl]: the post whose replies we want; [selfHref]: the
     * logged-in profile link href when it lives outside the scope HTML. [baseUri] makes hrefs absolute.
     */
    fun parse(html: String, statusUrl: String, spec: RepliesSpec, selfHref: String? = null, baseUri: String = "https://x.com/"): RepliesResult {
        val doc = Jsoup.parse(html, baseUri)
        val self = handleFromHref(selfHref ?: spec.selfLink?.let { doc.selectFirst(it)?.attr("href") })
        val root = doc.selectFirst(spec.scope) ?: doc.body()
        if (spec.exclude.isNotEmpty()) root.select(spec.exclude.joinToString(", ")).remove()
        val focalId = statusId(statusUrl, spec)
        val stop = spec.stopHeading?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val articles = root.select(spec.item).filter { a -> a.parents().none { it.`is`(spec.item) } } // no nested quotes
        val isArticle = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Element, Boolean>()).apply { addAll(articles) }
        val focal = articles.firstOrNull { a -> focalId != null && postUrl(a, spec)?.let { statusId(it, spec) } == focalId }
            ?: spec.focal?.let { f -> articles.firstOrNull { it.`is`(f) } }
        val dropped = mutableListOf<String>()
        val replies = LinkedHashMap<String, JsonObject>()
        var seenFocal = focal == null // no focal found: every article is a candidate (the focal id is still excluded)
        var stopped = false
        for (el in root.allElements) {
            if (stopped) break
            if (seenFocal && stop != null && (el.tagName() == "h2" || el.attr("role") == "heading") && stop.containsMatchIn(el.text())) { stopped = true; continue }
            if (el !in isArticle) continue
            if (el === focal) { seenFocal = true; continue }
            if (!seenFocal) { dropped += "before the focal post"; continue }
            val url = postUrl(el, spec)
            val id = statusId(url, spec)
            when {
                url == null || id == null -> dropped += "no status link (not a post)"
                id == focalId -> dropped += "the focal post"
                else -> replies.getOrPut(url) { item(el, url, spec, self) }
            }
        }
        return RepliesResult(focal?.let { a -> postUrl(a, spec)?.let { item(a, it, spec, self) } }, replies.values.toList(), dropped, self)
    }

    /** The post's own permalink: the status link wrapping its timestamp (quoted posts' links are ignored). */
    private fun postUrl(a: Element, spec: RepliesSpec): String? {
        val links = a.select("a[href]").filter { Regex(spec.statusIdPattern).containsMatchIn(it.attr("href")) }
        val l = links.firstOrNull { it.selectFirst("time") != null } ?: links.firstOrNull()
        return l?.absUrl("href")?.ifBlank { null }?.substringBefore('?')?.let { u -> Regex("^(.*?" + spec.statusIdPattern + ")").find(u)?.groupValues?.get(1) ?: u }
    }

    private fun item(a: Element, url: String, spec: RepliesSpec, self: String?): JsonObject {
        val handle = handleFromHref(url.substringBefore("/status/"))
        return buildJsonObject {
            put("url", url)
            put("handle", handle)
            spec.fields.forEach { (k, f) ->
                val at = f.lastIndexOf('@')
                val sel = if (at >= 0) f.substring(0, at) else f
                val attr = if (at >= 0) f.substring(at + 1) else "text"
                val e = if (sel.isBlank()) a else a.selectFirst(sel)
                val v = when { e == null -> null; attr == "text" -> e.text().trim().ifBlank { null }
                    attr == "href" -> e.absUrl("href"); else -> e.attr(attr).ifBlank { null } }
                put(k, v?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            }
            put("is_self", self != null && handle == self)
        }
    }
}
