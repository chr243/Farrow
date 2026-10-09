package com.farrow.app.agent.tools

import com.farrow.app.data.termux.TermuxResult
import com.farrow.app.data.termux.TermuxRunner
import com.farrow.app.data.tools.TermuxPackageJobs
import com.farrow.app.data.tools.TermuxPackages
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
        assertEquals("mkdir -p ~/farrow-work && cd ~/farrow-work && timeout -k 5 30 bash -lc 'echo '\\''hi'\\'''", cmd)
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
}
