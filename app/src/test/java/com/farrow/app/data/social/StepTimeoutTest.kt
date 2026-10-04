package com.farrow.app.data.social

import com.farrow.app.data.browser.BridgeClient
import com.farrow.app.data.browser.BridgeEndpoint
import com.farrow.app.data.mcp.MiniHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v1.0.14: a hung bridge call can't stretch a step past its budget, and the hung call is cancelled afterwards. */
class StepTimeoutTest {
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()

    private fun file(rel: String) = listOf(rel, "app/$rel").map(::File).first { it.exists() }
    private val x: SiteConfig by lazy {
        Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(SiteConfig.serializer(), file("src/main/assets/selectors/x.json").readText())
    }

    @Test fun `hung eval - step fails at its budget, then the bridge is told to cancel and waited idle`() = runBlocking {
        val calls = mutableListOf<String>()
        server.route("/cmd") { rq, res ->
            val cmd = Json.parseToJsonElement(rq.body).jsonObject["cmd"]!!.jsonPrimitive.content
            synchronized(calls) { calls += cmd }
            when (cmd) {
                "eval" -> { Thread.sleep(20_000); res.reply(200, """{"ok":true,"code":0,"stdout":"","stderr":""}""") }
                "cancel" -> res.reply(200, """{"ok":true,"code":0,"stdout":"","stderr":"","data":{"killed":["123 eval"],"busy":["eval"]}}""")
                "ready" -> res.reply(200, """{"ok":true,"code":0,"stdout":"","stderr":"","data":{"eval_ok":true}}""")
                else -> res.reply(200, """{"ok":false,"code":2,"stdout":"","stderr":"unknown cmd: $cmd"}""")
            }
        }
        server.start()
        val a = SocialAutomation(x, BridgeClient(object : BridgeEndpoint { override val port = server.port; override val token = "t" }))
        val t0 = System.currentTimeMillis()
        val e = runCatching { a.runSteps(listOf(AutomationStep(action = "waitFor", selector = "composeText", timeoutMs = 1_000)), emptyMap()) }
            .exceptionOrNull()
        val took = System.currentTimeMillis() - t0
        assertTrue("$e", e is StepFailedException)
        assertTrue(e!!.message, e.message!!.contains("timed out after 6 s") && e.message!!.contains("cancel: killed 1 hung call(s)") &&
            e.message!!.contains("browser idle"))
        assertTrue("took $took ms", took in 5_500L..12_000L) // budget = 1 s + 5 s, not the 20 s hang
        val after = synchronized(calls) { calls.dropWhile { it == "eval" } }
        assertEquals(listOf("cancel", "ready"), after)
        assertTrue(a.lastStepTimings.toString(), a.lastStepTimings["1_waitFor_composeText"]!! in 5_500L..7_500L)
    }
}
