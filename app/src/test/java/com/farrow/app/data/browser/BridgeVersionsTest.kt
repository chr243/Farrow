package com.farrow.app.data.browser

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BridgeVersionsTest {
    @Test fun `compare and outdated`() {
        assertTrue(BridgeVersions.compare("1.10.0", "1.9.2") > 0)
        assertTrue(BridgeVersions.compare("1.7.0", "1.8.0") < 0)
        assertEquals(0, BridgeVersions.compare("1.8", "1.8.0"))
        assertTrue(BridgeVersions.isOutdated("1.7.0", "1.8.0"))
        assertTrue(BridgeVersions.isOutdated(null, "1.8.0"))
        assertFalse(BridgeVersions.isOutdated("1.8.0", "1.8.0"))
        assertFalse(BridgeVersions.isOutdated("1.9.0", "1.8.0"))
        assertFalse(BridgeVersions.isOutdated("1.0.0", null))
    }

    @Test fun `parses the bundled bridge version`() {
        assertEquals("1.8.0", BridgeVersions.parse("import os\nVERSION = \"1.8.0\"\n"))
        assertNull(BridgeVersions.parse("no version"))
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        assertEquals("1.12.0", BridgeVersions.parse(asset.readText()))
        assertTrue(BridgeVersions.isOutdated("1.9.0", "1.10.0"))
    }
}
