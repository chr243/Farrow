package com.farrow.app.data.browser

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Runs the generated wizard scripts with the host's bash (skipped when bash is unavailable). */
class StepScriptsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun which(cmd: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, cmd) }.firstOrNull { it.canExecute() }?.absolutePath

    private fun runBash(script: String, home: File, extraPath: String? = null, timeoutS: Long = 30): Int {
        val pb = ProcessBuilder("bash", "-c", script).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["FARROW_NO_WAIT"] = "1"
        pb.environment()["FARROW_ALL_NO_WAIT"] = "1"
        if (extraPath != null) pb.environment()["PATH"] = extraPath + File.pathSeparator + System.getenv("PATH")
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        assertTrue("script timed out", p.waitFor(timeoutS, TimeUnit.SECONDS))
        return p.exitValue()
    }

    private fun logs(home: File) = File(home, ".farrow/logs")

    @Test fun `successful step writes log, status 0 and success line`() {
        assumeTrue(which("bash") != null)
        val home = tmp.newFolder("h1")
        runBash(StepScripts.wrap(7, "demo", "echo hello\ncat > ~/x.txt <<'EOF'\nheredoc ok\nEOF\ncat ~/x.txt"), home)
        assertEquals("0", File(logs(home), "step-7.status").readText().trim())
        val log = File(logs(home), "step-7.log").readText()
        assertTrue(log, log.contains("hello") && log.contains("heredoc ok") && log.contains("✅ Step 7 succeeded"))
    }

    @Test fun `failing step records exit code and failure line`() {
        assumeTrue(which("bash") != null)
        val home = tmp.newFolder("h2")
        runBash(StepScripts.wrap(3, "demo", "echo oops\nreturn 3"), home)
        assertEquals("3", File(logs(home), "step-3.status").readText().trim())
        assertTrue(File(logs(home), "step-3.log").readText().contains("❌ Step 3 failed (exit 3)"))
    }

    @Test fun `apt prelude parses and detects no running apt`() {
        assumeTrue(which("bash") != null)
        val home = tmp.newFolder("h3")
        runBash(StepScripts.wrap(2, "apt", "if farrow_apt_busy; then echo BUSY; else echo IDLE; fi\necho \"${'$'}APT\"", usesApt = true), home)
        val log = File(logs(home), "step-2.log").readText()
        assertTrue(log, log.contains("IDLE") || log.contains("BUSY"))
        assertTrue(log, log.contains("--force-confold"))
        assertEquals("0", File(logs(home), "step-2.status").readText().trim())
    }

    private fun demoSteps(failAt: Int?) = (2..5).map { n ->
        n to StepScripts.wrap(n, "demo $n", if (n == failAt) "echo broken $n\nreturn $n" else "echo ran $n\ncat > ~/out$n <<'EOF'\nok\nEOF")
    }

    @Test fun `setup all skips done steps, runs the rest and reports success`() {
        assumeTrue(which("bash") != null)
        val home = tmp.newFolder("all1")
        val rc = runBash(StepScripts.setupAll(demoSteps(null), mapOf(2 to "true", 3 to "false", 4 to "[ -f ~/nope ]"), from = 2), home)
        assertEquals(0, rc)
        assertEquals("skipped", File(logs(home), "step-2.status").readText().trim())
        assertFalse(File(home, "out2").exists())
        (3..5).forEach { assertEquals("0", File(logs(home), "step-$it.status").readText().trim()); assertTrue(File(home, "out$it").exists()) }
        assertEquals("0", File(logs(home), "step-all.status").readText().trim())
        assertTrue(File(logs(home), "step-2.log").readText().contains("already done"))
    }

    private fun fakeAm(): Pair<File, File> {
        val bin = tmp.newFolder("ambin")
        val rec = File(bin, "am.args")
        File(bin, "am").writeText("#!/bin/bash\necho \"${'$'}*\" >> ${rec.absolutePath}\n"); File(bin, "am").setExecutable(true)
        File(bin, "termux-wake-lock").writeText("#!/bin/bash\ntouch ${bin.absolutePath}/wakelock\n"); File(bin, "termux-wake-lock").setExecutable(true)
        return bin to rec
    }

    @Test fun `background setup (no TTY) ends at once with exit 0 and never waits or opens the app`() {
        assumeTrue(which("bash") != null)
        val home = tmp.newFolder("bg")
        val (bin, rec) = fakeAm()
        val pb = ProcessBuilder("bash", "-c", StepScripts.setupAll(demoSteps(null), emptyMap(), from = 2)).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        pb.environment().remove("FARROW_NO_WAIT"); pb.environment().remove("FARROW_ALL_NO_WAIT")
        val p = pb.start()
        p.outputStream.close()
        p.inputStream.bufferedReader().readText()
        assertTrue("background setup must not wait", p.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        assertEquals("0", File(logs(home), "step-all.status").readText().trim())
        assertFalse("no am start without a Termux UI", rec.exists())
        assertTrue("wake lock kept so the bridge survives", File(bin, "wakelock").exists())
    }

    @Test fun `foreground setup (TTY) brings Farrow to the front and exits 0 so Termux closes the session`() {
        assumeTrue(which("bash") != null && which("script") != null)
        val home = tmp.newFolder("fg")
        val (bin, rec) = fakeAm()
        val scriptFile = File(home, "all.sh").apply { writeText(StepScripts.setupAll(demoSteps(null), emptyMap(), from = 2)) }
        val pb = ProcessBuilder("script", "-qec", "bash ${scriptFile.absolutePath}", "/dev/null").redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        pb.environment().remove("FARROW_NO_WAIT"); pb.environment().remove("FARROW_ALL_NO_WAIT")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("foreground setup must not wait for Enter", p.waitFor(20, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        assertTrue(out, rec.exists())
        assertEquals("start --activity-reorder-to-front -n ${StepScripts.APP_COMPONENT}", rec.readText().trim())
        assertTrue(out, out.contains("Returning to Farrow"))
    }

    @Test fun `setup all stops at the failing step and records it`() {
        assumeTrue(which("bash") != null)
        val home = tmp.newFolder("all2")
        val rc = runBash(StepScripts.setupAll(demoSteps(4), emptyMap(), from = 3), home)
        assertEquals(1, rc)
        assertFalse(File(logs(home), "step-2.status").exists()) // before `from`
        assertEquals("0", File(logs(home), "step-3.status").readText().trim())
        assertEquals("4", File(logs(home), "step-4.status").readText().trim())
        assertFalse(File(logs(home), "step-5.status").exists())
        assertEquals("4:4", File(logs(home), "step-all.status").readText().trim())
        assertEquals("4", File(logs(home), "step-all.current").readText().trim())
        // The progress query reports it the way the app parses it.
        val out = ProcessBuilder("bash", "-c", StepScripts.setupAllQuery((2..5).toList())).apply { environment()["HOME"] = home.absolutePath }
            .start().inputStream.bufferedReader().readText()
        val keys = StepScripts.parseKeys(out)
        assertEquals("4:4", keys["ALL"]); assertEquals("4", keys["CUR"]); assertEquals("none", keys["S2"]); assertEquals("4", keys["S4"])
        assertTrue(out, out.substringAfter("---").contains("broken 4"))
    }

    @Test fun `installed probe reports each check`() {
        assumeTrue(which("bash") != null)
        val out = ProcessBuilder("bash", "-c", StepScripts.installedQuery(mapOf(2 to "command -v bash", 3 to "command -v definitely-not-a-cmd", 4 to "[ 1 = 1 ]")))
            .start().inputStream.bufferedReader().readText()
        val k = StepScripts.parseKeys(out)
        assertEquals("1", k["C2"]); assertEquals("0", k["C3"]); assertEquals("1", k["C4"]); assertEquals("ok", k["PROBE"])
    }

    @Test fun `bridge sha matches what the heredoc writes`() {
        assumeTrue(which("bash") != null && which("sha256sum") != null)
        val home = tmp.newFolder("sha")
        val body = "print('hi')\nx = '${'$'}HOME'\n\n"
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest((body.trimEnd() + "\n").toByteArray()).joinToString("") { "%02x".format(it) }
        runBash("cat > ~/b.py <<'FARROW_BRIDGE_EOF'\n${body.trimEnd()}\nFARROW_BRIDGE_EOF\n[ \"$(sha256sum ~/b.py | cut -c1-64)\" = $expected ] && touch ~/match", home)
        assertTrue(File(home, "match").exists())
    }

    private fun http(port: Int, path: String, post: Boolean = false): String {
        val c = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("X-Bridge-Token", "testtoken")
        c.connectTimeout = 2_000; c.readTimeout = 20_000
        if (post) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write("{}".toByteArray()) } }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Fake TBP daemon with the real one's semantics: pid file first, slow browser start, session lock, unix socket. */
    private val fakeDaemon = """
        |import json, os, signal, socket, sys, time
        |H = os.path.expanduser("~/.tbp"); os.makedirs(H, exist_ok=True)
        |LOCK = os.path.join(os.environ["TMPDIR"], ".tbp_browser.lock")
        |open(os.path.join(H, "daemon.pid"), "w").write(str(os.getpid()))
        |with open(os.path.join(H, "starts"), "a") as f: f.write("x")
        |time.sleep(1.5)  # Xvfb + Firefox
        |def alive(p):
        |    try: os.kill(p, 0); return True
        |    except OSError: return False
        |if os.path.exists(LOCK):
        |    old = int(open(LOCK).read().strip() or 0)
        |    if alive(old) and old != os.getpid():
        |        open(os.path.join(H, "daemon.log"), "a").write("RuntimeError: Another browser session is running (PID %d)\n" % old); sys.exit(1)
        |open(LOCK, "w").write(str(os.getpid()))
        |sp = os.path.join(H, "daemon.sock")
        |if os.path.exists(sp): os.unlink(sp)
        |s = socket.socket(socket.AF_UNIX); s.bind(sp); s.listen(5)
        |def bye(*a):
        |    for f in (sp, LOCK, os.path.join(H, "daemon.pid")):
        |        try: os.unlink(f)
        |        except OSError: pass
        |    os._exit(0)
        |signal.signal(signal.SIGTERM, bye)
        |open(os.path.join(H, "daemon.log"), "a").write("fake daemon log line\n")
        |while True:
        |    c, _ = s.accept(); d = c.recv(65536)
        |    if not d: c.close(); continue
        |    time.sleep(float(os.environ.get("FAKE_STATUS_DELAY", "0")))
        |    c.sendall((json.dumps({"id": 1, "success": True, "data": {"pid": os.getpid(), "browser": "firefox"}}) + "\n").encode()); c.close()
    """.trimMargin()

    @Test fun `bridge daemon start is single-flight, never doubles or kills a live daemon, survives a lost pid file, resets`() {
        val python3 = which("python3")
        assumeTrue(which("bash") != null && python3 != null && which("pkill") != null)
        val home = tmp.newFolder("d1")
        val bin = tmp.newFolder("dbin")
        val tmpDir = tmp.newFolder("dtmp")
        File(bin, "fake_daemon.py").writeText(fakeDaemon)
        // Fake tbp: start spawns the daemon detached and waits for its socket (like TBP's start).
        File(bin, "tbp").writeText("""
            |#!/bin/bash
            |case "${'$'}1" in
            |  status) if [ -f ~/.tbp/daemon.pid ] && kill -0 "${'$'}(cat ~/.tbp/daemon.pid)" 2>/dev/null; then echo '{"success": true}'
            |          else echo '{"success": false, "error": "No daemon running"}'; fi ;;
            |  start) setsid $python3 ${bin.absolutePath}/fake_daemon.py </dev/null >/dev/null 2>&1 &
            |         for i in ${'$'}(seq 50); do [ -S ~/.tbp/daemon.sock ] && { echo "Daemon ready. Browser: firefox"; exit 0; }; sleep 0.2; done; echo "start failed"; exit 1 ;;
            |  stop) [ -f ~/.tbp/daemon.pid ] && kill "${'$'}(cat ~/.tbp/daemon.pid)" 2>/dev/null; echo '{"success": true}' ;;
            |  *) echo '{"success": true}' ;;
            |esac
        """.trimMargin()); File(bin, "tbp").setExecutable(true)
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        val port = ServerSocket(0).use { it.localPort }
        val pb = ProcessBuilder(python3, asset.absolutePath, "--port", "$port", "--token", "testtoken").redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        pb.environment()["FARROW_UNRESPONSIVE_WAIT_S"] = "3"
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        val bridge = pb.start()
        val tbpDir = File(home, ".tbp"); val lock = File(tmpDir, ".tbp_browser.lock"); val pidFile = File(tbpDir, "daemon.pid")
        fun starts() = File(tbpDir, "starts").takeIf { it.exists() }?.readText()?.length ?: 0
        fun alive(pid: Long) = ProcessBuilder("kill", "-0", "$pid").start().waitFor() == 0
        var orphan: Process? = null
        try {
            var up = false
            repeat(50) { if (!up) { up = runCatching { java.net.Socket("127.0.0.1", port).close(); true }.getOrDefault(false); if (!up) Thread.sleep(100) } }
            assertTrue("bridge did not start", up)
            assertTrue(http(port, "/health").contains("\"daemon\": false"))
            assertTrue(http(port, "/daemon/status").contains("\"running\": false"))

            // 1) Concurrent starts (setup + web tool + app launch) -> exactly one `tbp start`, all succeed.
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())
            (1..3).map { Thread { results += http(port, "/daemon/start", post = true) }.apply { start() } }.forEach { it.join(40_000) }
            assertEquals(3, results.size)
            assertTrue(results.toString(), results.all { it.contains("\"ok\": true") && it.contains("\"running\": true") })
            assertEquals(1, starts())
            val first = pidFile.readText().trim().toLong()
            assertEquals(first, lock.readText().trim().toLong())

            // 2) daemon.pid lost (TBP's ensure_daemon race) but the socket answers -> healthy, pid repaired.
            pidFile.delete()
            val st = http(port, "/daemon/status")
            assertTrue(st, st.contains("\"running\": true") && st.contains("Daemon ready") && st.contains("fake daemon log line"))
            assertEquals(first, pidFile.readText().trim().toLong())
            assertTrue(http(port, "/daemon/start", post = true).contains("already_running"))
            assertEquals(1, starts())

            // 3) A start already in progress (pid alive, socket not listening yet) is waited for, never doubled.
            ProcessBuilder("kill", "$first").start().waitFor()
            repeat(30) { if (pidFile.exists()) Thread.sleep(100) }
            ProcessBuilder("setsid", python3, File(bin, "fake_daemon.py").absolutePath).apply {
                environment()["HOME"] = home.absolutePath; environment()["TMPDIR"] = tmpDir.absolutePath; environment()["FAKE_STATUS_DELAY"] = "8"
            }.redirectErrorStream(true).redirectOutput(File(tmpDir, "direct.log")).start()
            repeat(30) { if (!pidFile.exists()) Thread.sleep(100) }
            val r3 = http(port, "/daemon/start", post = true)
            assertTrue(r3, r3.contains("already_running"))
            assertEquals(2, starts())
            assertEquals(1, File(home, ".farrow/tbp.log").readLines().count { it.startsWith("--- ") })
            // A slow status reply (devtools console busy) doesn't make the daemon look dead.
            val t0 = System.currentTimeMillis()
            assertTrue(http(port, "/health").contains("\"daemon\": true"))
            assertTrue("health took too long", System.currentTimeMillis() - t0 < 4_000)

            // 4) Hung: daemon died hard, but a live process holds lock + pid and nothing listens → needs_reset, no kill,
            // no new start (only Reset may kill).
            val direct = pidFile.readText().trim().toLong()
            ProcessBuilder("kill", "-9", "$direct").start().waitFor()
            Thread.sleep(300)
            val orphanPid = File(tmpDir, "orphan.pid")
            orphan = ProcessBuilder(python3, "-c", "import os, time; open('${orphanPid.absolutePath}', 'w').write(str(os.getpid())); time.sleep(120)").start()
            repeat(50) { if (!orphanPid.exists() || orphanPid.length() == 0L) Thread.sleep(100) }
            lock.writeText(orphanPid.readText().trim()); pidFile.writeText(orphanPid.readText().trim())
            val h4 = http(port, "/health")
            assertTrue(h4, h4.contains("\"daemon\": false") && h4.contains("\"daemon_unresponsive\": true"))
            val r4 = http(port, "/daemon/start", post = true)
            assertTrue(r4, r4.contains("\"needs_reset\": true") && r4.contains("Reset browser"))
            assertTrue("orphan must not be auto-killed", orphan.isAlive)
            assertEquals(2, starts())

            // 5) Reset browser kills it and starts fresh.
            val r5 = http(port, "/daemon/reset", post = true)
            assertTrue(r5, r5.contains("\"ok\": true") && r5.contains("\"started\": true"))
            assertTrue(orphan.waitFor(5, TimeUnit.SECONDS))
            assertEquals(3, starts())

            // 6) Stale lock with a dead PID (after a crash) gets removed before starting.
            val third = pidFile.readText().trim().toLong()
            ProcessBuilder("kill", "-9", "$third").start().waitFor()
            Thread.sleep(300)
            lock.writeText("999999")
            val r6 = http(port, "/daemon/start", post = true)
            assertTrue(r6, r6.contains("\"ok\": true") && r6.contains("removed"))
            assertEquals(4, starts())
            assertFalse(alive(third))
            assertTrue(http(port, "/health").contains("\"daemon\": true"))
        } finally {
            bridge.destroy()
            orphan?.destroyForcibly()
            runCatching { ProcessBuilder("kill", "-9", pidFile.readText().trim()).start().waitFor() }
        }
    }

    /** Fake `tbp` CLI for the bridge tests: start spawns the fake daemon detached (unless ~/.tbp/nostart exists). */
    private fun fakeTbp(bin: File, python3: String) {
        File(bin, "fake_daemon.py").writeText(fakeDaemon)
        File(bin, "tbp").writeText("""
            |#!/bin/bash
            |echo "${'$'}1" >> ~/.tbp/calls
            |case "${'$'}1" in
            |  status) echo '{"success": true}' ;;
            |  start) [ -f ~/.tbp/nostart ] && { echo "start failed"; exit 1; }
            |         setsid $python3 ${bin.absolutePath}/fake_daemon.py </dev/null >/dev/null 2>&1 &
            |         for i in ${'$'}(seq 50); do [ -S ~/.tbp/daemon.sock ] && { echo "Daemon ready. Browser: firefox"; exit 0; }; sleep 0.2; done; echo "start failed"; exit 1 ;;
            |  stop) [ -f ~/.tbp/daemon.pid ] && kill "${'$'}(cat ~/.tbp/daemon.pid)" 2>/dev/null; echo '{"success": true}' ;;
            |  *) echo '{"success": true, "data": {"result": "ok"}}' ;;
            |esac
        """.trimMargin()); File(bin, "tbp").setExecutable(true)
    }

    private fun postCmd(port: Int, body: String): String {
        val c = java.net.URL("http://127.0.0.1:$port/cmd").openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("X-Bridge-Token", "testtoken")
        c.connectTimeout = 2_000; c.readTimeout = 120_000
        c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    @Test fun `cookie import always restarts the daemon and confirms its socket, reports daemon_down with the log otherwise`() {
        val python3 = which("python3")
        assumeTrue(which("bash") != null && python3 != null && which("setsid") != null)
        val home = tmp.newFolder("imp"); val bin = tmp.newFolder("impbin"); val tmpDir = tmp.newFolder("imptmp")
        val tbpDir = File(home, ".tbp"); File(tbpDir, "firefox_profile").mkdirs()
        fakeTbp(bin, python3!!)
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        val port = ServerSocket(0).use { it.localPort }
        val pb = ProcessBuilder(python3, asset.absolutePath, "--port", "$port", "--token", "testtoken").redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath; pb.environment()["TMPDIR"] = tmpDir.absolutePath
        pb.environment()["FARROW_RESTART_WAIT_S"] = "4"; pb.environment()["FARROW_WINDOW_WAIT_S"] = "0"; pb.environment()["TBP_DISPLAY"] = ":4242"
        pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        pb.redirectOutput(File(tmpDir, "bridge.log"))
        val bridge = pb.start()
        val pidFile = File(tbpDir, "daemon.pid"); val sock = File(tbpDir, "daemon.sock")
        fun sockUp() = ProcessBuilder(python3, "-c", "import socket; s = socket.socket(socket.AF_UNIX); s.connect('${sock.absolutePath}')")
            .start().waitFor() == 0
        try {
            var up = false
            repeat(50) { if (!up) { up = runCatching { java.net.Socket("127.0.0.1", port).close(); true }.getOrDefault(false); if (!up) Thread.sleep(100) } }
            assertTrue("bridge did not start", up)
            assertTrue(http(port, "/daemon/start", post = true).contains("\"running\": true"))
            val before = pidFile.readText().trim()
            val cookies = """[{"name":"auth_token","value":"${"a".repeat(40)}","domain":"x.com","httpOnly":true,"secure":true},{"name":"ct0","value":"${"c".repeat(32)}","domain":".x.com"}]"""
            val r = postCmd(port, """{"cmd":"cookies_import","args":{"name":"x","then":"https://x.com/home","cookies":$cookies}}""")
            assertTrue(r, r.contains("\"ok\": true") && r.contains("\"daemon_down\": false"))
            assertTrue(r, r.contains("\"after_write\": 2") && r.contains("\"after_restart\": 2") && r.contains("\"before\": 0"))
            val calls = File(tbpDir, "calls").readLines()
            val i = calls.indexOf("stop")
            assertTrue(calls.toString(), i >= 0 && calls.drop(i).contains("start") && calls.drop(i).contains("goto"))
            val after = pidFile.readText().trim()
            assertNotEquals("daemon must be a fresh process", before, after)
            assertTrue("socket must be up after the import", sockUp())
            assertTrue(http(port, "/health").contains("\"daemon\": true"))

            // Restart fails (twice) → clear daemon_down error with the daemon.log tail, never a silent "browser off".
            File(tbpDir, "nostart").writeText("")
            val r2 = postCmd(port, """{"cmd":"cookies_import","args":{"name":"x","then":"https://x.com/home","cookies":$cookies}}""")
            assertTrue(r2, r2.contains("\"ok\": false") && r2.contains("\"daemon_down\": true") && r2.contains("fake daemon log line"))
            assertTrue(r2, r2.contains("did not come back"))
            assertTrue(File(tbpDir, "calls").readLines().count { it == "start" } >= 4)
        } finally {
            bridge.destroy()
            runCatching { ProcessBuilder("kill", "-9", pidFile.readText().trim()).start().waitFor() }
        }
    }

    @Test fun `bridge cookie store write, Google consent seeding, leak guard and proc scan`() {
        val python3 = which("python3")
        assumeTrue(python3 != null)
        val home = tmp.newFolder("py"); File(home, ".tbp/firefox_profile").mkdirs()
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        val script = File(home, "t.py")
        script.writeText("""
            |import importlib.util, subprocess, time, sqlite3
            |spec = importlib.util.spec_from_file_location("b", ${'"'}${asset.absolutePath}${'"'}); b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
            |cs = [b.normalize_cookie(c) for c in ({"name": "auth_token", "value": "v1", "domain": "x.com", "httpOnly": True}, {"name": "ct0", "value": "v2", "domain": ".x.com"})]
            |assert b.count_rows(".x.com") == 0
            |b.write_cookie_db(cs); b.checkpoint_db()
            |assert b.count_rows(".x.com") == 2, b.count_rows(".x.com")
            |b.write_cookie_db(cs)
            |assert b.count_rows(".x.com") == 2, "rewrite must replace, not duplicate"
            |rows = {c["name"]: c for c in b.read_cookie_db(".x.com")}
            |assert rows["auth_token"]["httpOnly"] and rows["auth_token"]["secure"] and rows["auth_token"]["domain"] == ".x.com"
            |seeded = b.seed_google_consent([])
            |assert ".google.com" in seeded and ".google.fr" in seeded and ".youtube.com" in seeded, seeded
            |assert b.seed_google_consent([]) == [], "seeding is once only"
            |assert any(c["name"] == "SOCS" for c in b.read_cookie_db(".google.fr"))
            |assert b.count_rows(".x.com") == 2
            |assert b.has_leak("https://www.google.com/search?client=firefox-b-d&q=var+_r%3Btry%7B_r%3DJSON.stringify(%7Br%3Aeval(%22document.cookie")
            |assert b.has_leak('var _r;try{_r=JSON.stringify({r:eval("document.cookie - Google Search — Mozilla Firefox')
            |assert not b.has_leak("https://x.com/home") and not b.has_leak("Home / X — Mozilla Firefox")
            |assert b.CONSENT_PAGE_RX.search("https://consent.google.com/ml?continue=x") and b.CONSENT_PAGE_RX.search("Avant d'accéder à Google")
            |assert "tout refuser" in b.CONSENT_JS and "alle ablehnen" in b.CONSENT_JS and b.CONSENT_JS.index("rej.test") < b.CONSENT_JS.index("acc.test")
            |assert b.LOGGED_OUT_RX.search("/i/flow/login") and not b.LOGGED_OUT_RX.search("/home")
            |assert {"eval", "url", "title"} <= b.JS_CMDS
            |p = subprocess.Popen(["sleep", "37.5"]); time.sleep(0.2)
            |assert p.pid in b.procs_matching(r"sleep 37\.5"); b.pkill(r"sleep 37\.5", []); p.wait(5)
            |assert not b.procs_matching(r"sleep 37\.5")
            |import os
            |ujs = os.path.join(b.TBP_PROFILE, "user.js"); open(ujs, "w").write('user_pref("intl.accept_languages", "fr-FR, fr");\nuser_pref("browser.tabs.warnOnClose", false);\n')
            |assert b.browser_lang() == "en"
            |b.write_locale_prefs([]); t = open(ujs).read()
            |assert 'user_pref("intl.accept_languages", "en-US, en");' in t and "fr-FR" not in t and 'user_pref("intl.locale.requested", "en-US");' in t, t
            |assert 'user_pref("javascript.use_us_english_locale", true);' in t and "warnOnClose" in t
            |b.write_locale_prefs([]); assert open(ujs).read().count("intl.accept_languages") == 1
            |g = b.localize_search_url("https://www.google.fr/search?q=caf%C3%A9&hl=fr")
            |assert g.startswith("https://www.google.com/search?") and "hl=en" in g and "gl=us" in g and "pws=0" in g and "hl=fr" not in g and "q=caf%C3%A9" in g, g
            |assert "kl=us-en" in b.localize_search_url("https://html.duckduckgo.com/html/?q=x")
            |assert b.localize_search_url("https://x.com/home") == "https://x.com/home"
            |assert b.url_matches("https://x.com/compose/post", "https://x.com/compose/post") and b.url_matches("https://www.x.com/compose/post?a=1", "https://x.com/compose/post")
            |assert not b.url_matches("https://x.com/home", "https://x.com/compose/post") and not b.url_matches(None, "https://x.com/")
            |st = b.cmd_site_status({"domain": ".x.com", "key_cookies": ["auth_token", "ct0"]})["data"]
            |assert st["state"] == "LOGGED_IN" and st["missing"] == [], st
            |pdb = sqlite3.connect(b.PLACES_DB); pdb.execute("CREATE TABLE moz_places (id INTEGER PRIMARY KEY, url TEXT)"); pdb.execute("CREATE TABLE moz_historyvisits (id INTEGER PRIMARY KEY, place_id INTEGER, visit_date INTEGER)")
            |pdb.execute("INSERT INTO moz_places VALUES (1, 'https://x.com/home'), (2, 'https://x.com/i/flow/login')"); pdb.execute("INSERT INTO moz_historyvisits VALUES (1, 1, 100), (2, 2, 200)"); pdb.commit(); pdb.close()
            |assert b.last_visited_url() == "https://x.com/i/flow/login"
            |st = b.cmd_site_status({"domain": ".x.com", "key_cookies": ["auth_token", "ct0"], "logged_out_patterns": ["/i/flow/login"]})["data"]
            |assert st["state"] == "LOGGED_OUT" and "login page" in st["reason"], st
            |st = b.cmd_site_status({"domain": ".x.com", "key_cookies": ["auth_token", "kdt"]})["data"]
            |assert st["state"] == "LOGGED_OUT" and st["missing"] == ["kdt"], st
            |assert "ready" in b.NO_DAEMON_CMDS and "eval" not in b.NO_DAEMON_CMDS and {"nav", "ready", "site_status"} <= set(b.COMMANDS)
            |assert {"editor_type", "key", "focus"} <= set(b.COMMANDS) and tuple(map(int, b.VERSION.split("."))) >= tuple(map(int, "1.8.0".split(".")))
            |assert b.norm_text(" hi\u200b \n there ") == "hi there"
            |ed = {"t": ""}
            |def ftbp(*a, timeout=90):
            |    e = a[1] if len(a) > 1 else ""
            |    if "execCommand('insertText'" in e: ed["t"] = "hello world"; return {"ok": True, "data": {"result": "inserted"}}
            |    if "innerText" in e and "focus()" not in e: return {"ok": True, "data": {"result": ed["t"]}}
            |    return {"ok": True, "data": {"result": "focused"}}
            |real_tbp, real_which = b.tbp, b.shutil.which
            |b.tbp = ftbp; b.shutil.which = lambda n: None
            |r = b.cmd_editor_type({"selector": "[data-testid=tweetTextarea_0]", "text": "hello world"})
            |assert r["ok"] and r["data"]["method"] == "insertText", r
            |b.tbp = lambda *a, timeout=90: {"ok": True, "data": {"result": "missing"}}
            |assert not b.cmd_editor_type({"selector": "#x", "text": "a"})["ok"] and not b.cmd_focus({"selector": "#x"})["ok"]
            |b.tbp, b.shutil.which = real_tbp, real_which
            |r = b.cmd_set_language({"lang": "fr"}); assert r["ok"] and b.browser_lang() == "fr" and "fr-FR" in open(ujs).read()
            |assert not b.cmd_set_language({"lang": "xx"})["ok"]
            |print("ALL OK")
        """.trimMargin())
        val p = ProcessBuilder(python3, script.absolutePath).redirectErrorStream(true)
        p.environment()["HOME"] = home.absolutePath; p.environment()["TBP_DISPLAY"] = ":4242"
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS))
        assertTrue(out, out.contains("ALL OK"))
    }

    @Test fun `status query output parses`() {
        val p = StepScripts.parseStatus("STATUS=1\n---\nline a\nline b\n")
        assertEquals("1", p.status)
        assertEquals("line a\nline b", p.tail)
        assertEquals("none", StepScripts.parseStatus("").status)
    }

    @Test fun `start step launches the bridge, waits for the port and survives`() {
        val python3 = which("python3")
        assumeTrue(which("bash") != null && python3 != null && which("pkill") != null)
        val home = tmp.newFolder("h5")
        val bin = tmp.newFolder("bin")
        File(bin, "python").writeText("#!/bin/sh\nexec $python3 \"$@\"\n"); File(bin, "python").setExecutable(true)
        val state = File(home, ".farrow").apply { mkdirs() }
        val asset = listOf("src/main/assets/tbp_bridge.py", "app/src/main/assets/tbp_bridge.py").map(::File).first { it.exists() }
        asset.copyTo(File(state, "tbp_bridge.py"))
        File(state, "token").writeText("testtoken")
        val port = ServerSocket(0).use { it.localPort }
        try {
            runBash(StepScripts.wrap(5, "start bridge", StepScripts.startBridgeBody(port)), home, extraPath = bin.absolutePath)
            val log = File(logs(home), "step-5.log").readText()
            assertEquals(log, "0", File(logs(home), "step-5.status").readText().trim())
            assertTrue(log, log.contains("Bridge listening on 127.0.0.1:$port"))
            // Still running after the step's shell exited.
            Thread.sleep(300)
            java.net.Socket("127.0.0.1", port).use { assertTrue(it.isConnected) }
        } finally {
            ProcessBuilder("pkill", "-f", "[t]bp_bridge.py --port $port").start().waitFor(5, TimeUnit.SECONDS)
        }
    }
}
