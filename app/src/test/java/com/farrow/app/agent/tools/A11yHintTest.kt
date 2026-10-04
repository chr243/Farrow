package com.farrow.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class A11yHintTest {
    @Test fun `web-looking screen calls point to the browser tools`() = runTest {
        assertTrue(looksLikeWebTask("https://x.com/home")); assertTrue(looksLikeWebTask("post a tweet")); assertTrue(looksLikeWebTask("google.com"))
        assertFalse(looksLikeWebTask("Hello mum")); assertFalse(looksLikeWebTask(null))
        // Service off (unit test): the error explains that the internal browser needs no accessibility permission.
        val r = ScreenTapTool().execute(buildJsonObject { put("x", 1); put("y", 2) })
        assertTrue(r, r.contains("web_click") && r.contains("no accessibility permission"))
        assertTrue(ScreenTapTool().description.startsWith("Phone screen (accessibility)"))
        assertTrue(WebClickTool(com.farrow.app.data.browser.BridgeClient(object : com.farrow.app.data.browser.BridgeEndpoint {
            override val port = 1; override val token = "t" })).description.contains("no accessibility permission"))
    }
}
