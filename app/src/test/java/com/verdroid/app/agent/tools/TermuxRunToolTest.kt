package com.verdroid.app.agent.tools

import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.tools.TermuxPackageJobs
import com.verdroid.app.data.tools.TermuxPackages
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TermuxRunToolTest {
    private class FakeTermux(
        var installed: Boolean = true,
        var permission: Boolean = true,
        var answer: (String) -> TermuxResult? = { null },
    ) : TermuxRunner {
        val commands = mutableListOf<Pair<String, Long>>()
        override fun isInstalled() = installed
        override fun hasRunCommandPermission() = permission
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult? {
            commands += command to timeoutMs
            return answer(tag)
        }
    }

    private fun args(cmd: String, timeout: Int? = null) = buildJsonObject {
        put("command", cmd); if (timeout != null) put("timeout_seconds", timeout)
    }

    @Test fun `runs the command in farrow-work under a timeout and returns its output`() = runTest {
        val t = FakeTermux(answer = { tag -> TermuxResult(tag, "hi\n", "warn", 0, -1, null) })
        val o = Json.parseToJsonElement(TermuxRunTool(t).execute(args("echo 'hi'", 30))).jsonObject
        assertEquals(0, o["exit_code"]!!.jsonPrimitive.int)
        assertEquals("hi\n", o["stdout"]!!.jsonPrimitive.content)
        assertEquals("warn", o["stderr"]!!.jsonPrimitive.content)
        val (cmd, timeoutMs) = t.commands.single()
        assertTrue(cmd, cmd.contains("timeout -k 5 30 bash -lc 'echo '\\''hi'\\''' </dev/null"))
        assertTrue(cmd.startsWith("mkdir -p ~/farrow-work && cd ~/farrow-work"))
        assertEquals(45_000L, timeoutMs)
    }

    @Test fun `clear errors for missing Termux, permission, no answer and timeouts`() = runTest {
        val t = FakeTermux(installed = false)
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("not installed"))
        t.installed = true; t.permission = false
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("permission"))
        t.permission = true
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("allow-external-apps"))
        assertTrue(TermuxRunTool(t).execute(args("")).contains("command is required"))
        t.answer = { tag -> TermuxResult(tag, "", "", 124, -1, null) }
        assertTrue(TermuxRunTool(t).execute(args("sleep 999", 9999)).contains("\"timed_out\":true"))
        assertEquals(615_000L, t.commands.last().second) // capped at 600 s + grace
        t.answer = { tag -> TermuxResult(tag, "", "", null, 2, "Bad path") }
        assertTrue(TermuxRunTool(t).execute(args("ls")).contains("Bad path"))
    }

    @Test fun `package install job reports success and failure`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val t = FakeTermux(answer = { tag ->
            if (tag.startsWith("install-jq")) TermuxResult(tag, "Setting up jq\nINSTALLED=1\nRESULT=0\n", "", 0, -1, null)
            else TermuxResult(tag, "E: Unable to locate package\nINSTALLED=0\nRESULT=100\n", "", 0, -1, null)
        })
        val jobs = TermuxPackageJobs(t, scope)
        val jq = TermuxPackages.ALL.first { it.pkg == "jq" }
        val pandoc = TermuxPackages.ALL.first { it.pkg == "pandoc" }
        assertTrue(jobs.install(jq)); assertFalse(jobs.install(jq)) // single-flight per package
        jobs.install(pandoc)
        assertTrue(jobs.jobs.value["jq"]!!.running)
        scope.advanceUntilIdle()
        assertTrue(jobs.jobs.value["jq"]!!.ok)
        val p = jobs.jobs.value["pandoc"]!!
        assertFalse(p.ok || p.running); assertTrue(p.detail!!.contains("Unable to locate package"))
        assertTrue(t.commands.first().first.contains("install jq"))
    }
    /** Like Termux: read stdout/stderr to EOF, then take the exit code. HOME → temp so ~/farrow-work is harmless. */
    private fun runLikeTermux(script: String): Triple<String, String, Int> {
        val home = java.nio.file.Files.createTempDirectory("tx").toFile()
        val pb = ProcessBuilder("bash", "-c", script)
        pb.environment()["HOME"] = home.path
        val p = pb.start()
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        val out = p.inputStream.bufferedReader().readText(); t.join()
        val rc = p.waitFor(); home.deleteRecursively()
        return Triple(out, err.toString(), rc)
    }

    /** v1.0.23 bug: a background job (or a daemon from the login profile) kept the pipes open → full-timeout waits. */
    @Test fun `returns as soon as the command ends even if it leaves a background job holding the pipes`() {
        val t0 = System.nanoTime()
        val (out, err, rc) = runLikeTermux(TermuxRunTool.script("sleep 30 & echo hi; echo oops >&2", 20))
        val secs = (System.nanoTime() - t0) / 1e9
        assertTrue("took $secs s", secs < 8)
        assertEquals("hi\n", out); assertEquals("oops\n", err); assertEquals(0, rc)
        assertEquals(3, runLikeTermux(TermuxRunTool.script("exit 3", 20)).third)
    }

    @Test fun `timeout is still a hard cap with exit 124`() {
        val t0 = System.nanoTime()
        assertEquals(124, runLikeTermux(TermuxRunTool.script("sleep 30", 1)).third)
        assertTrue((System.nanoTime() - t0) / 1e9 < 10)
    }
}
