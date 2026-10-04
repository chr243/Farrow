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
 * v1.11.0 bridge: "Reset browser" as a background job (atomic like the cookie import: the app leaving can't stop it
 * midway) and set_media (user.js image/autoplay prefs, never a forced restart).
 */
class BridgeResetJobTest {
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

    @Test fun `reset job completes without the app, set_media writes user js without restarting`() {
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
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        val bridgeProc = pb.start()
        val client = BridgeClient(object : BridgeEndpoint { override val port = port; override val token = "testtoken" })
        val starts = { File(home, ".tbp/starts").takeIf { it.exists() }?.readText()?.length ?: 0 }
        try {
            var up = false
            repeat(50) { if (!up) { up = runCatching { java.net.Socket("127.0.0.1", port).close(); true }.getOrDefault(false); if (!up) Thread.sleep(100) } }
            assertTrue("bridge did not start", up)
            runBlocking {
                client.command("start")
                val before = starts()
                assertTrue(before >= 1)
                val start = client.command("job_start", buildJsonObject { put("cmd", "reset") })
                assertTrue(start.raw.toString(), start.ok)
                val id = start.data!!.jsonObject["job"]!!.jsonPrimitive.content
                // The app goes away: no polling. The bridge finishes the reset on its own.
                val jobFile = File(home, ".farrow/jobs/$id.json")
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline && !(jobFile.exists() && jobFile.readText().contains("\"state\": \"done\""))) Thread.sleep(250)
                assertTrue("reset job did not finish: " + File(tmpDir, "bridge.log").readText(), jobFile.readText().contains("\"state\": \"done\""))
                assertTrue("daemon was not restarted by the reset", starts() > before)
                // Later (screen reopened / app restarted): the result is fetchable; the client helper gives the raw reply.
                val r = client.awaitJob(id, pollMs = 50)
                assertEquals(true, r.raw["running"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(true, client.resetDaemonAtomic()?.get("running")?.jsonPrimitive?.booleanOrNull)

                // Images: blocked by default at every daemon start; set_media never restarts a running browser.
                val userJs = File(home, ".tbp/firefox_profile/user.js")
                assertTrue(userJs.readText().contains("user_pref(\"permissions.default.image\", 2);"))
                val n = starts()
                val m = client.command("set_media", buildJsonObject { put("load_images", true) })
                assertTrue(m.raw.toString(), m.ok)
                assertEquals(n, starts())
                val txt = userJs.readText()
                assertTrue(txt, txt.contains("user_pref(\"permissions.default.image\", 1);"))
                assertEquals(1, Regex("permissions\\.default\\.image").findAll(txt).count())
                assertEquals("1", File(home, ".farrow/load_images").readText())
                assertTrue(txt.contains("intl.accept_languages"))   // the locale prefs are kept
            }
        } finally {
            bridgeProc.destroy()
            File(home, ".tbp/daemon.pid").takeIf { it.exists() }?.readText()?.trim()?.let { ProcessBuilder("kill", it).start().waitFor() }
        }
    }
}
