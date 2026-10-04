package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SiteConfigTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun load(site: String): SiteConfig {
        // Unit tests run with the module directory as working dir.
        val f = listOf("src/main/assets/selectors/$site.json", "app/src/main/assets/selectors/$site.json").map(::File).first { it.exists() }
        return json.decodeFromString(SiteConfig.serializer(), f.readText())
    }

    @Test fun `x selectors file parses and has the required keys`() {
        val x = load("x")
        assertEquals("x", x.site)
        listOf("loggedIn", "loginForm", "composeText", "composeSubmit").forEach { assertTrue(it, x.selectors.containsKey(it)) }
        assertNotNull(x.scrape)
        assertTrue(x.postSteps.isNotEmpty())
        assertTrue(x.loginSteps.isNotEmpty())
    }

    @Test fun `facebook selectors file parses`() {
        val fb = load("facebook")
        assertEquals("facebook", fb.site)
        assertTrue(fb.selectors.containsKey("loggedIn"))
        assertTrue(fb.version >= 2)
        assertTrue(fb.postSteps.any { it.action == "clickText" && it.match!!.contains("Publier") && it.fallbackKey == "ctrl+Return" && it.sturdy })
        assertTrue(fb.postSteps.any { it.action == "typeEditor" && it.selector == "composeText" })
        assertTrue(fb.postSteps.filter { it.action != "sleep" && !it.optional }.all { it.timeoutMs == 45_000L })
        assertEquals("https://www.facebook.com/search/posts/?q=a%26b", fb.url("search", mapOf("query" to "a&b")))
    }

    @Test fun `url placeholders are encoded`() {
        val x = load("x")
        assertEquals("https://x.com/search?q=hello%20world&src=typed_query&f=live", x.url("search", mapOf("query" to "hello world")))
        assertEquals("https://x.com/jack", x.url("profile", mapOf("handle" to "jack")))
        assertEquals("[data-testid=\"tweetTextarea_0\"]", x.sel("composeText"))
        assertEquals("div.raw", x.sel("div.raw"))
    }
}
