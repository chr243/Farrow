package com.farrow.app.data.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket

/**
 * v1.10.0 bridge: cookies_import as a background job. The app only starts it; even if it never polls again (screen
 * closed, app killed, Cancel), the bridge writes cookies.sqlite AND restarts the daemon, and the result can be fetched later.
 */
class CookieImportJobTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun which(cmd: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, cmd) }.firstOrNull { it.canExecute() }?.absolutePath

    private val fakeDaemon = """
        |import json, os, signal, socket, sys, time
        |H = os.path.expanduser("~/.tbp"); os.makedirs(H, exist_ok=True)
        |open(os.path.join(H, "daemon.pid"), "w").write(str(os.getpid()))
        |with open(os.path.join(H, "starts"), "a") as f: f.write("x")
        |time.sleep(1.0)
        |sp = os.path.join(H, "daemon.sock")
        |if os.path.exists(sp): os.unlink(sp)
        |s = socket.socket(socket.AF_UNIX); s.bind(sp); s.listen(5)
        |def bye(*a):
        |    for f in (sp, os.path.join(H, "daemon.pid")):
        |        try: os.unlink(f)
        |        except OSError: pass
        |    os._exit(0)
        |signal.signal(signal.SIGTERM, bye)
        |while True:
        |    c, _ = s.accept(); d = c.recv(65536)
        |    if not d: c.close(); continue
        |    c.sendall((json.dumps({"id": 1, "success": True, "data": {"pid": os.getpid()}}) + "\n").encode()); c.close()
    """.trimMargin()

    @Test fun `cookie import job finishes write and daemon restart without the app, result fetchable later`() {
        val python3 = which("python3")
        assumeTrue(which("bash") != null && python3 != null && which("pkill") != null && which("setsid") != null)
        val home = tmp.newFolder("home"); val bin = tmp.newFolder("bin"); val tmpDir = tmp.newFolder("tmp")
        File(bin, "fake_daemon.py").writeText(fakeDaemon)
        File(bin, "tbp").writeText("""
            |#!/bin/bash
            |case "${'$'}1" in
            |  status) if [ -f ~/.tbp/daemon.pid ] && kill -0 "${'$'}(cat ~/.tbp/daemon.pid)" 2>/dev/null; then echo '{"success": true}'
            |          else echo '{"success": false, "error": "No daemon running"}'; fi ;;
            |  start) setsid $python3 ${bin.absolutePath}/fake_daemon.py </dev/null >/dev/null 2>&1 &
            |         for i in ${'$'}(seq 50); do [ -S ~/.tbp/daemon.sock ] && { echo "Daemon ready."; exit 0; }; sleep 0.2; done; exit 1 ;;
            |  stop) [ -f ~/.tbp/daemon.pid ] && kill "${'$'}(cat ~/.tbp/daemon.pid)" 2>/dev/null; sleep 0.3; echo '{"success": true}' ;;
            |  *) echo '{"success": true, "data": {}}' ;;
            |esac
        """.trimMargin()); File(bin, "tbp").setExecutable(true)
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        val port = ServerSocket(0).use { it.localPort }
        val pb = ProcessBuilder(python3, asset.absolutePath, "--port", "$port", "--token", "testtoken").redirectErrorStream(true)
            .redirectOutput(File(tmpDir, "bridge.log"))
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        pb.environment()["FARROW_RESTART_WAIT_S"] = "15"
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        val bridgeProc = pb.start()
        val client = BridgeClient(object : BridgeEndpoint { override val port = port; override val token = "testtoken" })
        val starts = { File(home, ".tbp/starts").takeIf { it.exists() }?.readText()?.length ?: 0 }
        try {
            var up = false
            repeat(50) { if (!up) { up = runCatching { java.net.Socket("127.0.0.1", port).close(); true }.getOrDefault(false); if (!up) Thread.sleep(100) } }
            assertTrue("bridge did not start", up)
            runBlocking {
                assertEquals(true, client.command("start").raw["running"]?.jsonPrimitive?.booleanOrNull ?: true)
                val before = starts()
                assertTrue("daemon should be running before the import", before >= 1)

                val cookies = buildJsonArray {
                    add(buildJsonObject { put("name", "auth_token"); put("value", "a1"); put("domain", ".x.com"); put("path", "/"); put("expires", 2_000_000_000); put("httpOnly", true); put("secure", true) })
                    add(buildJsonObject { put("name", "ct0"); put("value", "c1"); put("domain", ".x.com"); put("path", "/"); put("expires", 2_000_000_000); put("secure", true) })
                }
                val args = buildJsonObject { put("name", "x"); put("cookies", cookies); put("then", "https://x.com/home") }
                val start = client.command("job_start", buildJsonObject { put("cmd", "cookies_import"); put("args", args) })
                assertTrue(start.raw.toString(), start.ok)
                val id = start.data!!.jsonObject["job"]!!.jsonPrimitive.content
                // A second start while it runs attaches to the same job (single flight).
                val again = client.command("job_start", buildJsonObject { put("cmd", "cookies_import"); put("args", args) })
                assertEquals(id, again.data!!.jsonObject["job"]!!.jsonPrimitive.content)
                assertEquals(true, again.data!!.jsonObject["attached"]!!.jsonPrimitive.boolean)

                // The "app" goes away now: no polling. The bridge must finish on its own.
                val jobFile = File(home, ".farrow/jobs/$id.json")
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline && !(jobFile.exists() && jobFile.readText().contains("\"state\": \"done\""))) Thread.sleep(250)
                assertTrue("job did not finish: " + File(tmpDir, "bridge.log").readText(), jobFile.readText().contains("\"state\": \"done\""))
                assertTrue("daemon was not restarted", starts() > before)
                assertTrue("daemon socket missing after the import", File(home, ".tbp/daemon.sock").exists())
                val db = File(home, ".tbp/firefox_profile/cookies.sqlite")
                assertTrue("cookies.sqlite not written", db.exists() && db.length() > 0)

                // Coming back later (reopened screen): the result is still there.
                val r = client.awaitJob(id, pollMs = 50)
                val data = r.data!!.jsonObject
                assertEquals(false, data["daemon_down"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(listOf("auth_token", "ct0"), data["present"]!!.jsonArray.map { it.jsonPrimitive.content })

                // Unknown job ids are reported, not hung on.
                val unknown = runCatching { client.awaitJob("cookies_import-0-000000", pollMs = 10) }
                assertTrue(unknown.exceptionOrNull()?.message.orEmpty().contains("unknown"))
            }
        } finally {
            bridgeProc.destroy()
            File(home, ".tbp/daemon.pid").takeIf { it.exists() }?.readText()?.trim()?.let { ProcessBuilder("kill", it).start().waitFor() }
        }
    }
}
