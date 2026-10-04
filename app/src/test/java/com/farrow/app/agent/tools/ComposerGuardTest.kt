package com.farrow.app.agent.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.0.6: web_click / web_type refuse X compose surfaces (the phone run that ended in "Save post?"). */
class ComposerGuardTest {
    @Test fun obviousComposerSelectors() {
        listOf("button[aria-label=\"Reply\"]", "[data-testid=\"tweetTextarea_0\"]", "div[data-testid='tweetButtonInline']",
            "[data-testid=tweetButton]", "button[aria-label='Répondre']", "[aria-label=\"Post\"]", "[data-testid=\"confirmationSheetConfirm\"]",
            "div[data-testid=\"reply\"]", "a[href=\"/compose/post\"]", "[data-testid=\"inline_reply_offscreen\"] div")
            .forEach { assertNotNull(it, ComposerGuard.selectorReason(it)) }
        listOf("input[name=q]", "a[href*=\"/status/123\"]", "button[aria-label=\"Like\"]", "#search", "[data-testid=\"like\"]",
            "button[aria-label=\"Reply all\"]", "div.reply-count")
            .forEach { assertNull(it, ComposerGuard.selectorReason(it)) }
    }

    @Test fun decisions() {
        val msg = ComposerGuard.MESSAGE
        // The phone report: on x.com, the Reply button by aria-label → refused.
        assertEquals(msg, ComposerGuard.decide("button[aria-label=\"Reply\"]", "x.com|composer:Reply button"))
        assertEquals(msg, ComposerGuard.decide("div[role=textbox]", "x.com|composer:tweetTextarea_0"))
        assertEquals(msg, ComposerGuard.decide("div[role=textbox]", "mobile.twitter.com|composer:editor"))
        assertEquals(msg, ComposerGuard.decide("[data-testid=\"tweetButton\"]", "x.com|ok"))        // element gone but obvious selector
        assertNull(ComposerGuard.decide("input[name=q]", "x.com|ok"))                              // X search stays allowed
        assertNull(ComposerGuard.decide("button[aria-label=\"Reply\"]", "mail.example.com|composer:Reply button")) // other sites
        assertEquals(msg, ComposerGuard.decide("[data-testid=\"tweetTextarea_0\"]", null))       // page didn't answer: fail closed
        assertNull(ComposerGuard.decide("input[name=q]", null))
        assertTrue(msg.contains("x_post") && msg.contains("blocked"))
        assertFalse(msg.contains("reply", ignoreCase = true) || msg.contains("comment", ignoreCase = true))
    }

    @Test fun dumpsProbeForTheJsdomCheck() {
        File("build/tmp/composerguard").apply { mkdirs() }.let { d ->
            listOf("button[aria-label=\"Reply\"]", "[data-testid=\"tweetTextarea_0\"]", "input[name=q]", "#inline span", "button.like", "#dm [contenteditable]")
                .forEachIndexed { i, s -> File(d, "probe$i.js").writeText(ComposerGuard.probeJs(s)) }
        }
    }
}
