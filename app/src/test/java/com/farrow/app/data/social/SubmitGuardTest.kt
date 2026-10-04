package com.farrow.app.data.social

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.0.6: submit only a settled composer (exact text, stable 500 ms, button enabled); detect "Save post?". */
class SubmitGuardTest {
    @Test fun exactTextNotContains() {
        assertTrue(SubmitGuard.equalsIntended("testing.. testing...\n", "testing.. testing..."))
        assertTrue(SubmitGuard.equalsIntended("a\u00a0 b\u200b", "a b"))
        assertFalse(SubmitGuard.equalsIntended("testing...", "testing.. testing..."))      // the truncation from the phone
        assertFalse(SubmitGuard.equalsIntended("testing.. testing... testing.. testing...", "testing.. testing...")) // doubled
        assertTrue(SubmitGuard.isEmpty("\u200b\n ")); assertFalse(SubmitGuard.isEmpty("testing..."))
    }

    @Test fun settlesOnlyWhenStableAndEnabled() {
        val s = SubmitGuard.Settle("hello world", stableMs = 500)
        assertFalse(s.feed("hello", true, 0))                 // still typing
        assertFalse(s.feed("hello world", false, 100))        // button disabled
        assertTrue(s.lastWhy.contains("disabled"))
        assertFalse(s.feed("hello world", true, 200))         // stable since 200
        assertFalse(s.feed("hello world", true, 600))
        assertTrue(s.feed("hello world", true, 700))          // 500 ms stable
        val t = SubmitGuard.Settle("hi")
        assertFalse(t.feed("hi", true, 0)); assertFalse(t.feed("h", true, 300)); assertFalse(t.feed("hi", true, 400))
        assertFalse(t.feed("hi", true, 800)); assertTrue(t.feed("hi", true, 900))   // the flicker restarted the clock
        val u = SubmitGuard.Settle("x")
        assertFalse(u.feed(null, true, 0)); assertTrue(u.lastWhy.contains("0 chars"))
    }

    @Test fun xPostScriptSettlesBeforeTheSingleSubmitClick() {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        val x = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(SiteConfig.serializer(), f.readText())
        val acts = x.postSteps.map { it.action }
        val settle = acts.indexOf("settleSubmit"); val click = x.postSteps.indexOfFirst { it.action == "click" && it.selector == "composeSubmit" }
        assertTrue(settle in 0 until click)
        assertEquals(1, x.postSteps.count { it.action == "click" && it.selector == "composeSubmit" })
        assertFalse("sleep" in acts)
        assertTrue(x.reply!!.maxAttempts <= 2)
        File("build/tmp/submitguard").apply { mkdirs() }.let { d ->
            File(d, "probe.js").writeText(SubmitGuard.probeJs("[data-testid=\"tweetTextarea_0\"]", "[data-testid=\"tweetButton\"]", x.reply!!.stray))
            File(d, "save.js").writeText("String(" + SubmitGuard.saveSheetExpr(x.reply!!.stray) + ")")
        }
    }
}
