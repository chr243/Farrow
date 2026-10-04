package com.farrow.app.data.social

import com.farrow.app.agent.tools.SiteScopes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ThreadRepliesTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        json.decodeFromString(SiteConfig.serializer(), f.readText())
    }
    private val fixture: String get() = javaClass.getResource("/fixtures/x_status_replies.html")!!.readText()

    @Test fun `bundled x json has the replies and text scope specs`() {
        val r = x.replies!!
        assertEquals("[data-testid=\"primaryColumn\"]", r.scope)
        assertTrue(r.exclude.contains("[data-testid=\"SideNav_AccountSwitcher_Button\"]"))
        assertTrue(r.exclude.contains("[data-testid=\"inline_reply_offscreen\"]"))
        assertNotNull(x.textScope)
        assertTrue(x.version >= 6)
    }

    @Test fun `only real reply articles after the focal post, never the account banner, composer or focal post`() {
        val res = ThreadReplies.parse(fixture, "https://x.com/alice/status/100", x.replies!!)
        val urls = res.replies.map { it["url"]!!.jsonPrimitive.content }
        assertEquals(listOf("https://x.com/bob/status/101", "https://x.com/Me_Account/status/102", "https://x.com/dave/status/103"), urls)
        // The first reply is Bob, not the logged-in account from the side nav.
        assertEquals("bob", res.replies.first()["handle"]!!.jsonPrimitive.content)
        assertEquals("Looks great!", res.replies.first()["text"]!!.jsonPrimitive.content)
        assertEquals("0 Replies. Reply", res.replies.first()["replies"]!!.jsonPrimitive.content)
        // Logged-in handle detected from AppTabBar_Profile_Link; own real replies are kept and flagged.
        assertEquals("me_account", res.selfHandle)
        assertEquals(listOf(false, true, false), res.replies.map { it["is_self"]!!.jsonPrimitive.boolean })
        // Focal post reported separately, with its own permalink (not the quoted link).
        assertEquals("https://x.com/alice/status/100", res.focal!!["url"]!!.jsonPrimitive.content)
        assertEquals("https://x.com/dave/status/103", res.replies[2]["url"]!!.jsonPrimitive.content)
        // Nothing from outside the conversation / after "Discover more".
        val all = res.replies.joinToString { it.toString() }
        assertFalse(all.contains("Me Account @Me_Account"))
        assertFalse(all.contains("status/999")); assertFalse(all.contains("carol")); assertFalse(all.contains("eve"))
        assertFalse(all.contains("Post your reply"))
        assertTrue(res.dropped.contains("no status link (not a post)"))
        assertTrue(res.dropped.contains("before the focal post"))
    }

    @Test fun `scope-only HTML with the self link passed separately (what the browser sends)`() {
        val doc = org.jsoup.Jsoup.parse(fixture)
        val column = doc.selectFirst("[data-testid=primaryColumn]")!!.outerHtml()
        val res = ThreadReplies.parse(column, "https://x.com/i/status/100", x.replies!!, selfHref = "/Me_Account")
        assertEquals(3, res.replies.size)
        assertEquals("me_account", res.selfHandle)
    }

    @Test fun `without a recognisable focal post the post itself is still excluded`() {
        val html = fixture.replace("tabindex=\"-1\"", "tabindex=\"0\"").replace("/alice/status/100\"><time", "/alice/status/100x\"><time")
        val res = ThreadReplies.parse(html, "https://x.com/alice/status/555", x.replies!!)
        assertFalse(res.replies.any { it["url"]!!.jsonPrimitive.contentOrNull!!.endsWith("/status/555") })
        assertFalse(res.replies.any { it.toString().contains("SideNav") })
    }

    @Test fun `handles and site matching`() {
        assertEquals("me_account", ThreadReplies.handleFromHref("/Me_Account"))
        assertEquals("bob", ThreadReplies.handleFromHref("https://x.com/bob"))
        assertNull(ThreadReplies.handleFromHref("/bob/status/1"))
        assertTrue(SiteScopes.matches("https://x.com/alice/status/1", x))
        assertTrue(SiteScopes.matches("https://mobile.twitter.com/alice/status/1", x))
        assertFalse(SiteScopes.matches("https://example.com/x.com", x))
    }

    @Test fun `scoped page text JS removes the excluded parts`() {
        val js = SocialAutomation.scopedTextJs(x.textScope!!)
        assertTrue(js.contains("primaryColumn"))
        assertTrue(js.contains("SideNav_AccountSwitcher_Button"))
        assertTrue(js.startsWith("(()=>{") && js.endsWith("})()"))
    }
}
