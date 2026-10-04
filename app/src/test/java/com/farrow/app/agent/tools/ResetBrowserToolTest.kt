package com.farrow.app.agent.tools

import com.farrow.app.data.browser.BrowserOpKind
import com.farrow.app.data.browser.BrowserOpsState
import com.farrow.app.data.tools.ToolPrefs
import com.farrow.app.data.tools.ToolStatus
import com.farrow.app.data.tools.ToolEnv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResetBrowserToolTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun CoroutineScope.fake(ok: Boolean, after: Long = 200, msg: String = if (ok) "✅ Browser reset" else "❌ Reset failed"): Pair<MutableStateFlow<BrowserOpsState>, () -> Boolean> {
        val st = MutableStateFlow(BrowserOpsState())
        val start = {
            if (st.value.busy) false else {
                st.value = BrowserOpsState(kind = BrowserOpKind.RESET, label = "Reset the internal browser", resetting = true)
                launch { delay(after); st.value = BrowserOpsState(lastKind = BrowserOpKind.RESET, lastOk = ok, lastResult = msg, resetMessage = msg,
                    resetLog = if (ok) null else "daemon log…") }
                true
            }
        }
        return st to start
    }

    @Test fun `starts the background reset, waits for it and reports ok and seconds`() = runBlocking {
        val (st, start) = fake(ok = true)
        val r = obj(ResetBrowserTool(start, st).execute(JsonObject(emptyMap())))
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("✅ Browser reset", r["message"]!!.jsonPrimitive.content)
        assertTrue(r["seconds"]!!.jsonPrimitive.double in 0.1..5.0)
    }

    @Test fun `a failed reset returns the error and the log tail`() = runBlocking {
        val (st, start) = fake(ok = false)
        val r = obj(ResetBrowserTool(start, st).execute(JsonObject(emptyMap())))
        assertEquals(false, r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("❌ Reset failed", r["error"]!!.jsonPrimitive.content)
        assertEquals("daemon log…", r["log_tail"]!!.jsonPrimitive.content)
    }

    @Test fun `a reset already running is awaited, not started twice`() = runBlocking {
        val (st, start) = fake(ok = true)
        start()
        var starts = 0
        val r = obj(ResetBrowserTool({ starts++; start() }, st).execute(JsonObject(emptyMap())))
        assertEquals(0, starts)
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun `another setup action running is an error, a reset that never ends times out`() = runBlocking {
        val busy = MutableStateFlow(BrowserOpsState(kind = BrowserOpKind.SETUP, label = "Set up everything"))
        assertTrue(obj(ResetBrowserTool({ false }, busy).execute(JsonObject(emptyMap())))["error"]!!.jsonPrimitive.content.contains("Set up everything"))
        val (st, start) = fake(ok = true, after = 10_000)
        val r = obj(ResetBrowserTool(start, st, timeoutMs = 300).execute(JsonObject(emptyMap())))
        assertEquals(false, r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("still running"))
        coroutineContext.cancelChildren()
    }

    @Test fun `on by default on the Tools page, needs the bridge`() {
        assertFalse(ResetBrowserTool.NAME in ToolPrefs.effective(emptySet(), emptySet()))
        assertTrue(ToolStatus.of(ResetBrowserTool.NAME, ToolEnv(bridgeUp = true)).ready)
        assertFalse(ToolStatus.of(ResetBrowserTool.NAME, ToolEnv()).ready)
    }
}
