package com.farrow.app.data.social

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.0.5: fast scrapes — target detection (skip navigation), dedupe identical to v1.0.4, poll/scroll policy, JS. */
class FastScrapeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        json.decodeFromString(SiteConfig.serializer(), f.readText())
    }

    @Test fun homeTargetSkipsNavigationOnlyOnHome() {
        val url = x.url("timeline"); val t = FastScrape.target(url)
        assertTrue(FastScrape.onTarget("https://x.com/home", url, t))
        assertTrue(FastScrape.onTarget("https://x.com/home/", url, t))
        assertTrue(FastScrape.onTarget("https://twitter.com/home?lang=en", url, t))
        assertTrue(FastScrape.onTarget("https://mobile.x.com/home", url, t))
        assertFalse(FastScrape.onTarget("https://x.com/homeless", url, t))
        assertFalse(FastScrape.onTarget("https://x.com/alice/status/1", url, t))
        assertFalse(FastScrape.onTarget("https://x.com/i/flow/login", url, t))
        assertFalse(FastScrape.onTarget("https://example.com/home", url, t))
        assertFalse(FastScrape.onTarget(null, url, t))
    }

    @Test fun profileAndSearchTargets() {
        val p = x.url("profile", mapOf("handle" to "Alice_1")); val tp = FastScrape.target(p)
        assertTrue(FastScrape.onTarget("https://x.com/alice_1", p, tp))
        assertFalse(FastScrape.onTarget("https://x.com/alice_1/status/5", p, tp))
        assertFalse(FastScrape.onTarget("https://x.com/alice_12", p, tp))
        val s = x.url("search", mapOf("query" to "kotlin coroutines")); val ts = FastScrape.target(s)
        assertEquals("kotlin coroutines", ts.q)
        assertTrue(FastScrape.onTarget("https://x.com/search?q=kotlin%20coroutines&src=typed_query&f=live", s, ts))
        assertTrue(FastScrape.onTarget("https://x.com/search?q=kotlin+coroutines&f=live", s, ts))
        assertFalse(FastScrape.onTarget("https://x.com/search?q=kotlin", s, ts))
    }

    @Test fun repliesTargetByStatusId() {
        val u = "https://x.com/i/status/123"
        val t = FastScrape.target(u, ThreadReplies.statusId(u, x.replies!!))
        assertTrue(FastScrape.onTarget("https://x.com/bob/status/123", u, t))
        assertFalse(FastScrape.onTarget("https://x.com/bob/status/1234", u, t))
        assertFalse(FastScrape.onTarget("https://x.com/bob/status/123/photo/1", u, t))
    }

    /** The v1.0.4 loop body, verbatim, as the reference. */
    private fun oldMerge(rounds: List<JsonArray>, dedupe: String?, limit: Int): List<JsonObject> {
        val seen = LinkedHashMap<String, JsonObject>()
        rounds.forEach { arr -> arr.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val key = dedupe?.let { o[it]?.jsonPrimitive?.contentOrNull } ?: o.toString()
            if (key !in seen) seen[key] = o
        } }
        return seen.values.take(limit)
    }

    private fun post(url: String?, text: String) = buildJsonObject { put("url", url); put("text", text) }

    @Test fun mergeIsIdenticalToV104() {
        val r1 = JsonArray(listOf(post("u1", "a"), post("u2", "b"), post("u1", "a-dup"), post(null, "c"), post(null, "c")))
        val r2 = JsonArray(listOf(post("u2", "b2"), post("u3", "d"), post(null, "e"), JsonPrimitive("junk")))
        for (limit in listOf(1, 3, 5, 20)) {
            val seen = LinkedHashMap<String, JsonObject>()
            assertEquals(3, FastScrape.merge(seen, r1, "url"))
            assertEquals(2, FastScrape.merge(seen, r2, "url"))
            assertEquals(oldMerge(listOf(r1, r2), "url", limit), seen.values.take(limit))
        }
        val s2 = LinkedHashMap<String, JsonObject>()
        FastScrape.merge(s2, r1, null); FastScrape.merge(s2, r2, null)
        assertEquals(oldMerge(listOf(r1, r2), null, 20), s2.values.toList())
    }

    @Test fun parseBatch() {
        val b = FastScrape.parseBatch("""{"u":"https://x.com/home","n":5,"top":false,"mb":"off","items":[{"url":"u1"}]}""")!!
        assertEquals("https://x.com/home", b.url); assertEquals(5, b.count); assertFalse(b.mediaOn); assertFalse(b.scrolledTop)
        assertEquals(1, FastScrape.items(b).size)
        assertTrue(FastScrape.parseBatch("""{"u":"x","n":0,"top":true,"mb":"on"}""")!!.let { it.mediaOn && it.scrolledTop && FastScrape.items(it).isEmpty() })
        assertNull(FastScrape.parseBatch("not json")); assertNull(FastScrape.parseBatch(null))
    }

    @Test fun pollPolicy() {
        val N = FastScrape.Next.values().associateBy { it.name }
        fun d(have: Int, loaded: Boolean = true, growth: Long = 0, sinceScroll: Long? = null, scrolls: Int = 0, max: Int = 8, left: Long = 10_000) =
            FastScrape.decide(have, loaded, 5, growth, sinceScroll, scrolls, max, left)
        assertEquals(N["DONE"], d(5)); assertEquals(N["DONE"], d(7))                    // early exit, no scroll
        assertEquals(N["POLL"], d(0, loaded = false))                                     // waiting for the first posts
        assertEquals(N["GIVE_UP"], d(0, loaded = false, left = 0))
        assertEquals(N["POLL"], d(3, growth = 100))                                       // still growing: no scroll yet
        assertEquals(N["SCROLL"], d(3, growth = FastScrape.SETTLE_MS))                     // settled below limit → scroll
        assertEquals(N["POLL"], d(3, growth = 500, sinceScroll = 400, scrolls = 1))        // waiting for the scroll's posts
        assertEquals(N["SCROLL"], d(3, growth = 2_000, sinceScroll = FastScrape.SCROLL_WAIT_MS, scrolls = 1))
        assertEquals(N["DONE"], d(3, growth = 2_000, sinceScroll = FastScrape.SCROLL_WAIT_MS, scrolls = 2)) // fruitless 2nd scroll
        assertEquals(N["SCROLL"], d(4, growth = FastScrape.SETTLE_MS, sinceScroll = 3_000, scrolls = 2))  // last scroll helped
        assertEquals(N["DONE"], d(4, growth = FastScrape.SETTLE_MS, sinceScroll = 3_000, scrolls = 8))    // maxScrolls
        assertEquals(N["SCROLL"], d(0, growth = FastScrape.SETTLE_MS))                    // post without replies: scroll once…
        assertEquals(N["DONE"], d(0, growth = 5_000, sinceScroll = 2_000, scrolls = 2))   // …then stop, not 20 s
    }

    @Test fun xBlocksMediaAndJsKeepsTheV104Extraction() {
        assertTrue(x.scrape!!.blockMedia); assertTrue(x.replies!!.blockMedia)
        val t = FastScrape.target(x.url("timeline"))
        val js = FastScrape.extractJs(x.scrape!!, 5, t, scrollTop = true, scroll = false, statusUrl = true)
        assertTrue(js.contains("o[k]=attr==='text'?(el.innerText||'').trim():(attr==='href'?el.href:el.getAttribute(attr));"))
        assertTrue(js.contains("__fwMB") && js.contains("keys.size>=5"))
        val off = FastScrape.extractJs(x.scrape!!.copy(blockMedia = false), 5, t, false, true)
        assertFalse(off.contains("__fwMB=st")); assertTrue(off.contains("window.scrollBy"))
        val g = FastScrape.grabJs(x.replies!!, 5, FastScrape.target("https://x.com/a/status/9", "9"), false, false)
        assertTrue(g.contains("c.querySelectorAll('svg,img,video,picture,style,script,noscript').forEach(e=>e.remove());"))
        // For the jsdom check in docs/ARCHITECTURE.md (v1.0.5): node scripts evaluate these on the fixtures.
        File("build/tmp/fastscrape").apply { mkdirs() }.let { d ->
            File(d, "extract.js").writeText(js); File(d, "extract_nomedia.js").writeText(off)
            File(d, "grab.js").writeText(g); File(d, "media_off.js").writeText(FastScrape.MEDIA_OFF)
        }
    }
}

/** v1.0.5: x_reply always works on the canonical post URL and navigates unless already exactly on it. */
class StatusUrlTest {
    private fun c(s: String?) = StatusUrl.canonical(s)

    @Test fun normalization() {
        assertEquals("https://x.com/alice/status/1234567", c("https://x.com/alice/status/1234567")!!.url)
        assertEquals("https://x.com/alice/status/1234567", c("https://twitter.com/alice/status/1234567?s=20&t=abc#x")!!.url)
        assertEquals("https://x.com/alice/status/1234567", c("https://mobile.x.com/alice/status/1234567/photo/1")!!.url)
        assertEquals("https://x.com/alice/status/1234567", c("http://www.twitter.com/alice/status/1234567/video/2")!!.url)
        assertEquals("https://x.com/alice/status/1234567", c("x.com/alice/status/1234567/analytics")!!.url)
        assertEquals("https://x.com/alice/status/1234567", c("  https://x.com/alice/statuses/1234567/  ")!!.url)
        assertEquals("https://x.com/i/status/1234567", c("1234567")!!.url)
        assertEquals("https://x.com/i/status/1234567", c("https://x.com/i/web/status/1234567")!!.url)
        assertEquals("1234567", c("https://x.com/i/status/1234567")!!.id)
    }

    @Test fun rejectsNonPosts() {
        listOf(null, "", "https://x.com/home", "https://x.com/alice", "https://x.com/search?q=status/1234567",
            "https://example.com/alice/status/1234567", "https://x.com.evil.com/alice/status/1234567", "ftp://x.com/a/status/1234567",
            "javascript:alert(1)", "12ab", "https://x.com/alice/status/").forEach { assertNull(it, c(it)) }
    }

    @Test fun navigationDecision() {
        val p = c("https://twitter.com/Alice/status/1234567/photo/1?s=20")!!
        // The phone report: the browser was on /home → must navigate (v1.0.4 counted feed articles as "the post").
        assertTrue(StatusUrl.needsNavigation("https://x.com/home", p))
        assertTrue(StatusUrl.needsNavigation("https://x.com/alice", p))
        assertTrue(StatusUrl.needsNavigation("https://x.com/search?q=alice&f=live", p))
        assertTrue(StatusUrl.needsNavigation("https://x.com/alice/status/1234567/photo/1", p))
        assertTrue(StatusUrl.needsNavigation("https://x.com/alice/status/12345678", p))
        assertTrue(StatusUrl.needsNavigation("https://x.com/bob/status/1234567", p))
        assertTrue(StatusUrl.needsNavigation("https://x.com/compose/post", p))
        assertTrue(StatusUrl.needsNavigation(null, p))
        assertFalse(StatusUrl.needsNavigation("https://x.com/alice/status/1234567", p))
        assertFalse(StatusUrl.needsNavigation("https://x.com/ALICE/status/1234567/?s=20", p))
        val byId = c("1234567")!!
        assertFalse(StatusUrl.needsNavigation("https://x.com/carol/status/1234567", byId)) // /i/status redirected
        assertTrue(StatusUrl.needsNavigation("https://x.com/home", byId))
    }

    @Test fun scrapeItemsCarryTheCanonicalStatusUrl() {
        val o = buildJsonObject { put("url", "https://x.com/bob/status/7654321/analytics"); put("status_url", "https://x.com/bob/status/7654321") }
        assertEquals("https://x.com/bob/status/7654321", FastScrape.withStatusUrl(o)["status_url"]!!.jsonPrimitive.content)
        val fallback = buildJsonObject { put("url", "https://x.com/bob/status/7654321/photo/1") }
        assertEquals("https://x.com/bob/status/7654321", FastScrape.withStatusUrl(fallback)["status_url"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, FastScrape.withStatusUrl(buildJsonObject { put("url", JsonNull) })["status_url"])
    }
}
