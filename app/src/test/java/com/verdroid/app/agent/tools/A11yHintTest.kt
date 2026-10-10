package com.verdroid.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class A11yHintTest {
    @Test fun `screen tools explain the accessibility requirement`() = runTest {
        // Service off (unit test): the error says how to enable it.
        val r = ScreenTapTool().execute(buildJsonObject { put("x", 1); put("y", 2) })
        assertTrue(r, r.contains("Accessibility service is off") && r.contains("Farrow agent control"))
        assertFalse(r, r.contains("browser") || r.contains("web_click"))
        assertTrue(ScreenTapTool().description.startsWith("Phone screen (accessibility)"))
        assertFalse(ScreenReadTool().description.contains("browser"))
    }
}
