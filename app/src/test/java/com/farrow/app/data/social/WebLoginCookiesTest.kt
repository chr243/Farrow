package com.farrow.app.data.social

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.*

class WebLoginCookiesTest {
    @Test fun `parses CookieManager header with = in values and dedupes`() {
        val m = WebLoginCookies.parseHeader("guest_id=v1%3A123; ct0=abc; auth_token=deadbeef; twid=u%3D42; x=a=b; ct0=dup; ; bad")
        assertEquals("abc", m["ct0"])
        assertEquals("deadbeef", m["auth_token"])
        assertEquals("a=b", m["x"])
        assertEquals("v1%3A123", m["guest_id"])
        assertFalse(m.containsKey("bad"))
        assertTrue(WebLoginCookies.parseHeader(null).isEmpty())
    }

    @Test fun `required cookie detection for X and Facebook`() {
        val x = WebLoginCookies.X
        assertEquals(listOf("auth_token", "ct0"), WebLoginCookies.missing(WebLoginCookies.parseHeader("guest_id=1; personalization_id=2"), x))
        assertEquals(listOf("ct0"), WebLoginCookies.missing(WebLoginCookies.parseHeader("auth_token=t"), x))
        assertTrue(WebLoginCookies.isLoggedIn(WebLoginCookies.parseHeader("auth_token=t; ct0=c"), x))
        val fb = WebLoginCookies.FACEBOOK
        assertFalse(WebLoginCookies.isLoggedIn(WebLoginCookies.parseHeader("datr=1; xs=2"), fb))
        assertTrue(WebLoginCookies.isLoggedIn(WebLoginCookies.merge(listOf("datr=1", "c_user=100; xs=2%3Aabc")), fb))
    }

    @Test fun `merge keeps the first URL's value`() {
        val m = WebLoginCookies.merge(listOf("a=1; b=2", null, "b=3; c=4"))
        assertEquals(mapOf("a" to "1", "b" to "2", "c" to "4"), m)
    }

    @Test fun `import list uses dot domain, root path, secure and httpOnly for session cookies`() {
        val list = WebLoginCookies.toImport(WebLoginCookies.parseHeader("auth_token=t; ct0=c; twid=u%3D42"), WebLoginCookies.X)
        assertEquals(3, list.size)
        assertTrue(list.all { it.domain == ".x.com" && it.path == "/" && it.secure })
        assertTrue(list.first { it.name == "auth_token" }.httpOnly)
        assertFalse(list.first { it.name == "ct0" }.httpOnly)
        val json = WebLoginCookies.toTbpJson(list).toString()
        assertTrue(json, json.contains("\"httpOnly\":true") && json.contains("\"domain\":\".x.com\""))
        assertEquals("42", WebLoginCookies.xUserId("u%3D42"))
        assertNull(WebLoginCookies.xUserId(null))
    }

    private val tbpSchema = setOf("name", "value", "domain", "path", "secure", "httpOnly", "sameSite", "expires")

    @Test fun `TBP cookie JSON matches TBP's cookies load schema with expiry, dot domain and SameSite None + secure`() {
        val now = System.currentTimeMillis() / 1000
        val list = WebLoginCookies.toImport(WebLoginCookies.parseHeader("auth_token=t; ct0=c; twid=u%3D42; kdt=k; att=a; guest_id=v1%3A1"), WebLoginCookies.X) +
            CapturedCookie("legacy", "v", "x.com", path = "", secure = false, expires = 0, sameSite = "none")
        val arr = WebLoginCookies.toTbpJson(list)
        assertEquals(7, arr.size)
        arr.forEach { e ->
            val o = e.jsonObject
            assertEquals(o.toString(), tbpSchema, o.keys)
            assertEquals(".x.com", o["domain"]!!.jsonPrimitive.content)
            assertEquals("/", o["path"]!!.jsonPrimitive.content)
            assertEquals("None", o["sameSite"]!!.jsonPrimitive.content)
            assertTrue("SameSite=None needs secure", o["secure"]!!.jsonPrimitive.boolean)
            val exp = o["expires"]!!.jsonPrimitive.long
            assertTrue("expires ~ +1 year: $exp", exp > now + 360L * 86400 && exp < now + 370L * 86400)
        }
        assertTrue(arr.first { it.jsonObject["name"]!!.jsonPrimitive.content == "auth_token" }.jsonObject["httpOnly"]!!.jsonPrimitive.boolean)
        assertTrue(WebLoginCookies.X.optional.containsAll(listOf("twid", "kdt", "att", "guest_id")))
        assertEquals("https://x.com/", WebLoginCookies.X.origin)
    }

    @Test fun `bridge writes HttpOnly secure cookies into Firefox's cookies sqlite for schema 15 and 16 and reads them back`() {
        val python3 = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).map { java.io.File(it, "python3") }.firstOrNull { it.canExecute() }
        org.junit.Assume.assumeTrue(python3 != null)
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        val dir = kotlin.io.path.createTempDirectory("ffprof").toFile().apply { deleteOnExit() }
        val input = File(dir, "in.json")
        input.writeText(WebLoginCookies.toTbpJson(WebLoginCookies.toImport(WebLoginCookies.parseHeader("auth_token=t0k; ct0=c5; twid=u%3D42"), WebLoginCookies.X)).toString())
        // Two real Firefox schemas: 16 (Firefox ≥ 142, expiry ms, no rawSameSite) and 14 (expiry s, rawSameSite column).
        val script = """
            |import importlib.util, json, sqlite3, sys, time, os
            |spec = importlib.util.spec_from_file_location("b", sys.argv[1]); b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
            |cs = [b.normalize_cookie(c) for c in json.load(open(sys.argv[2]))]
            |out = {"now": int(time.time())}
            |for ver, raw in ((16, False), (14, True)):
            |    p = os.path.join(sys.argv[3], "c%d.sqlite" % ver)
            |    db = sqlite3.connect(p)
            |    db.execute("CREATE TABLE moz_cookies (id INTEGER PRIMARY KEY, originAttributes TEXT NOT NULL DEFAULT '', name TEXT, value TEXT, host TEXT, path TEXT, expiry INTEGER, lastAccessed INTEGER, creationTime INTEGER, isSecure INTEGER, isHttpOnly INTEGER, inBrowserElement INTEGER DEFAULT 0, sameSite INTEGER DEFAULT 0, " + ("rawSameSite INTEGER DEFAULT 0, " if raw else "") + "schemeMap INTEGER DEFAULT 0, isPartitionedAttributeSet INTEGER DEFAULT 0, CONSTRAINT moz_uniqueid UNIQUE (name, host, path, originAttributes))")
            |    db.execute("PRAGMA user_version = %d" % ver)
            |    # A host-only guest ct0 (as left by the old document.cookie import / X's guest flow) must be replaced.
            |    db.execute("INSERT INTO moz_cookies (name, value, host, path, expiry, isSecure, isHttpOnly) VALUES ('ct0', 'guest', 'x.com', '/', 1, 0, 0)")
            |    db.commit(); db.close()
            |    info = b.write_cookie_db(cs, p)
            |    db = sqlite3.connect(p)
            |    rows = [dict(zip(["name","host","path","expiry","isSecure","isHttpOnly","sameSite","schemeMap"], r)) for r in db.execute("SELECT name, host, path, expiry, isSecure, isHttpOnly, sameSite, schemeMap FROM moz_cookies")]
            |    db.close()
            |    os.makedirs(os.path.join(sys.argv[3], "state"), exist_ok=True); b.STATE_DIR = os.path.join(sys.argv[3], "state")
            |    back = b.read_cookie_db(".x.com", p)
            |    out[str(ver)] = {"info": info, "rows": rows, "back": back, "report": b.cookie_report(cs, back, "https://x.com/home")}
            |print(json.dumps(out))
        """.trimMargin()
        val p = ProcessBuilder(python3!!.absolutePath, "-c", script, asset.absolutePath, input.absolutePath, dir.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        val o = Json.parseToJsonElement(out.trim().lines().last()).jsonObject
        val now = o["now"]!!.jsonPrimitive.long
        for ((ver, scale) in listOf("16" to 1000L, "14" to 1L)) {
            val v = o[ver]!!.jsonObject
            val rows = v["rows"]!!.jsonArray.map { it.jsonObject }
            assertEquals(out, 3, rows.size) // guest ct0 replaced, not duplicated
            rows.forEach { r ->
                assertEquals(".x.com", r["host"]!!.jsonPrimitive.content)
                assertEquals("/", r["path"]!!.jsonPrimitive.content)
                assertEquals(1, r["isSecure"]!!.jsonPrimitive.int)
                assertEquals(0, r["sameSite"]!!.jsonPrimitive.int) // SameSite=None
                assertEquals(2, r["schemeMap"]!!.jsonPrimitive.int)  // set over https
                val exp = r["expiry"]!!.jsonPrimitive.long / scale
                assertTrue("schema $ver expiry $exp", exp > now + 360L * 86400 && exp < now + 370L * 86400)
            }
            assertEquals(1, rows.first { it["name"]!!.jsonPrimitive.content == "auth_token" }["isHttpOnly"]!!.jsonPrimitive.int)
            assertEquals(0, rows.first { it["name"]!!.jsonPrimitive.content == "ct0" }["isHttpOnly"]!!.jsonPrimitive.int)
            val back = v["back"]!!.jsonArray.map { it.jsonObject }
            assertTrue(back.first { it["name"]!!.jsonPrimitive.content == "auth_token" }["httpOnly"]!!.jsonPrimitive.boolean)
            val report = v["report"]!!.jsonPrimitive.content
            assertTrue(report, report.contains("✅ present") && report.contains("auth_token") && report.contains("httpOnly=True") &&
                report.contains("https://x.com/home") && !report.contains("MISSING"))
        }
    }

    @Test fun `user agent loses the WebView markers`() {
        val wv = "Mozilla/5.0 (Linux; Android 14; 23078PND5G Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.146 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; 23078PND5G Build/UKQ1.230804.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.146 Mobile Safari/537.36",
            WebLoginCookies.chromeUserAgent(wv))
    }
}
