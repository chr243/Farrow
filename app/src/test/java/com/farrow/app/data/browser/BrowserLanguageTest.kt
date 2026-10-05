package com.farrow.app.data.browser

import com.farrow.app.agent.AgentLoop
import org.junit.Assert.*
import org.junit.Test

class BrowserLanguageTest {
    @Test fun `google search goes to google com in English`() {
        val u = BrowserLanguage.localizeSearchUrl("https://www.google.fr/search?q=m%C3%A9t%C3%A9o&hl=fr", "en")
        assertTrue(u, u.startsWith("https://www.google.com/search?"))
        assertTrue(u, u.contains("hl=en") && u.contains("gl=us") && u.contains("pws=0") && !u.contains("hl=fr"))
        assertTrue(u, u.contains("q=m%C3%A9t%C3%A9o"))
    }

    @Test fun `duckduckgo gets kl and other urls are untouched`() {
        assertTrue(BrowserLanguage.localizeSearchUrl("https://html.duckduckgo.com/html/?q=x&kl=fr-fr").contains("kl=us-en"))
        assertFalse(BrowserLanguage.localizeSearchUrl("https://html.duckduckgo.com/html/?q=x").contains("fr-fr"))
        assertEquals("https://x.com/home", BrowserLanguage.localizeSearchUrl("https://x.com/home"))
    }

    @Test fun `accept header and default`() {
        assertEquals("en-US,en;q=0.9", BrowserLanguage.acceptHeader("en"))
        assertEquals("en", BrowserLanguage.of("zz").code)
        assertTrue(BrowserLanguage.acceptHeader("fr").startsWith("fr-FR,fr;q=0.9"))
    }

    @Test fun `system prompt prefers English sources by default`() {
        assertTrue(AgentLoop.systemPrompt("en").contains("Prefer English-language sources"))
        assertTrue(AgentLoop.systemPrompt("fr").contains("Français"))
    }

    @Test fun `system prompt prefers curl over the browser and gates live crypto trading`() {
        val p = AgentLoop.systemPrompt("en")
        assertTrue(p.contains("web_fetch"))
        assertTrue(p.contains("Prefer curl/web_fetch") || p.contains("prefer these first"))
        assertTrue(p.contains("crypto_place_order"))
        assertTrue(p.contains("OFF by default") || p.contains("off by default"))
        assertTrue(p.contains("Revolut has no public crypto"))
        assertTrue(p.contains("reset_browser"))
        assertTrue(p.contains("x_post"))
    }
}
