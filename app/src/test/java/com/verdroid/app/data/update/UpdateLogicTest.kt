package com.verdroid.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateLogicTest {
    @Test fun numericSemver() {
        assertTrue(UpdateLogic.isNewer("v1.0.10", "1.0.9"))
        assertTrue(UpdateLogic.isNewer("1.1.0", "1.0.99"))
        assertTrue(UpdateLogic.isNewer("v2.0.0", "1.9.9"))
        assertFalse(UpdateLogic.isNewer("v1.0.5", "1.0.5"))
        assertFalse(UpdateLogic.isNewer("1.0.4", "1.0.5"))
        assertEquals(0, UpdateLogic.compare("v1.2", "1.2.0"))
        assertTrue(UpdateLogic.compare("1.0.5-rc1", "1.0.5")!! < 0)
        assertTrue(UpdateLogic.compare("1.0.6-rc1", "1.0.5")!! > 0)
        assertEquals(0, UpdateLogic.compare("1.0.5+build7", "v1.0.5"))
        assertNull(UpdateLogic.compare("latest", "1.0.5"))
        assertFalse(UpdateLogic.isNewer("garbage", "1.0.5"))
    }

    private fun a(name: String, size: Long = 1000) = ApkAsset(name, "https://github.com/chr243/Farrow/releases/download/v1/$name", size)

    @Test fun assetPicking() {
        assertEquals("Farrow-v1.0.6-debug.apk",
            UpdateLogic.pickAsset(listOf(a("notes.txt"), a("Farrow-v1.0.6-release.apk"), a("Farrow-v1.0.6-debug.apk")))!!.name)
        assertEquals("Farrow-v1.0.6-release.apk", UpdateLogic.pickAsset(listOf(a("Farrow-v1.0.6-release.apk"), a("other.apk")), "release")!!.name)
        assertEquals("Farrow-v1.0.6.apk", UpdateLogic.pickAsset(listOf(a("other.apk"), a("Farrow-v1.0.6.apk")))!!.name)
        assertEquals("other.apk", UpdateLogic.pickAsset(listOf(a("other.apk"), a("source.zip")))!!.name)
        assertNull(UpdateLogic.pickAsset(listOf(a("Farrow-v1.0.6-debug.apk", size = 0), a("source.zip"))))
        assertNull(UpdateLogic.pickAsset(listOf(ApkAsset("Farrow-v1-debug.apk", "http://insecure/x.apk", 10))))
        assertNull(UpdateLogic.pickAsset(emptyList()))
    }

    @Test fun parsesGithubLatestRelease() {
        val body = """{"tag_name":"v1.0.6","name":"Farrow v1.0.6","draft":false,"body":"## v1.0.6\n- fixes","html_url":"https://github.com/chr243/Farrow/releases/tag/v1.0.6",
          "assets":[{"name":"Farrow-v1.0.6-debug.apk","size":12345678,"browser_download_url":"https://github.com/chr243/Farrow/releases/download/v1.0.6/Farrow-v1.0.6-debug.apk"}]}"""
        val r = UpdateLogic.parseRelease(body)!!
        assertEquals("1.0.6", r.version); assertEquals("Farrow v1.0.6", r.name); assertTrue(r.notes.contains("fixes"))
        assertEquals(12345678L, r.asset!!.size)
        assertNull(UpdateLogic.parseRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v9","draft":true}"""))
        assertNull(UpdateLogic.parseRelease("<html>"))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v1.0.7","assets":[]}""")!!.asset)
    }

    @Test fun autoCheckAtMostEverySixHours() {
        val h = 3_600_000L; val now = 100 * h
        assertTrue(UpdateLogic.autoCheckDue(0, now))
        assertFalse(UpdateLogic.autoCheckDue(now - 5 * h, now))
        assertTrue(UpdateLogic.autoCheckDue(now - 6 * h, now))
        assertTrue(UpdateLogic.autoCheckDue(now + h, now)) // clock went backwards
    }
}
