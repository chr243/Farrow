package com.farrow.app.data.browser

import com.farrow.app.ui.browser.MediaPrefNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** v1.0.7: Settings → Internal browser actions run app-scoped (leaving the screen used to stop a Reset midway). */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserOpsRunnerTest {
    private class MemStore(var saved: BrowserOpsState? = null) : BrowserOpsStore {
        override fun load() = saved
        override fun save(state: BrowserOpsState) { saved = state }
    }

    @Test fun `reset keeps running after the screen scope is cancelled and its result is persisted`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = MemStore()
        val runner = BrowserOpsRunner(app, store, clock = { 7L })
        val gate = CompletableDeferred<Unit>()
        val vmScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        vmScope.launch { runner.state.collect { } }
        assertTrue(runner.launch(BrowserOpKind.RESET, "Reset the internal browser") {
            runner.update { it.copy(resetting = true, pendingJob = "reset-1") }
            runner.progress("Resetting in the bridge…")
            gate.await()
            runner.finish(true, "✅ Browser reset — TBP daemon running") { it.copy(resetMessage = "✅ done", pendingJob = null) }
        })
        advanceUntilIdle()
        assertTrue(runner.current.busy)
        assertEquals("Resetting in the bridge…", runner.current.progress)
        vmScope.cancel()                         // the user leaves Settings
        advanceUntilIdle()
        assertTrue("still running after the screen closed", runner.isRunning())
        gate.complete(Unit)
        advanceUntilIdle()
        val s = runner.current
        assertFalse(s.busy); assertFalse(s.resetting)
        assertEquals(BrowserOpKind.RESET, s.lastKind)
        assertEquals(true, s.lastOk)
        assertEquals(7L, s.finishedAt)
        assertEquals("✅ Browser reset — TBP daemon running", store.saved!!.lastResult)
        // Coming back = a new runner over the same store (or a restarted app): the last result is shown.
        assertEquals("✅ Browser reset — TBP daemon running", BrowserOpsRunner(app, store).current.lastResult)
    }

    @Test fun `one action at a time, errors become the last result, no spinner left behind`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = BrowserOpsRunner(app, MemStore())
        val gate = CompletableDeferred<Unit>()
        assertTrue(runner.launch(BrowserOpKind.SETUP, "Set up everything") { gate.await(); throw IllegalStateException("Termux gone") })
        assertFalse(runner.launch(BrowserOpKind.RESET, "Reset") {})
        advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(runner.current.busy)
        assertEquals(false, runner.current.lastOk)
        assertTrue(runner.current.lastResult!!.contains("Termux gone"))
        assertTrue(runner.launch(BrowserOpKind.START, "Start") { /* returns without finishing */ })
        advanceUntilIdle()
        assertFalse(runner.current.busy)
        assertEquals(BrowserOpKind.START, runner.current.lastKind)
    }

    @Test fun `Cancel stops waiting - a bridge-side reset is reported as still finishing`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = BrowserOpsRunner(app, MemStore())
        runner.launch(BrowserOpKind.RESET, "Reset") {
            runner.update { it.copy(resetting = true, pendingJob = "reset-2") }
            CompletableDeferred<Unit>().await()
        }
        advanceUntilIdle()
        runner.cancel()
        advanceUntilIdle()
        val s = runner.current
        assertFalse(s.busy); assertFalse(runner.isRunning()); assertNull(s.pendingJob)
        assertTrue(s.lastResult!!.contains("bridge still finishes the reset"))
        assertEquals(s.lastResult, s.resetMessage)
    }

    @Test fun `restored after process death - nothing runs in-process, interrupted setup is explained, reset job resumable`() {
        val setup = BrowserOpsState(kind = BrowserOpKind.SETUP, progress = "Step 3 of 5", setupAll = SetupAllUi(running = true, phase = "Step 3 of 5"))
        val r = setup.restored()
        assertFalse(r.busy); assertFalse(r.setupAll!!.running)
        assertTrue(r.lastResult!!.contains("Interrupted"))
        val reset = BrowserOpsState(kind = BrowserOpKind.RESET, resetting = true, pendingJob = "reset-9").restored()
        assertFalse(reset.busy); assertFalse(reset.resetting)
        assertEquals("reset-9", reset.pendingJob)
        // Serializable round trip (SharedPreferences store).
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(setup, json.decodeFromString(BrowserOpsState.serializer(), json.encodeToString(BrowserOpsState.serializer(), setup)))
    }

    @Test fun `notification texts`() {
        val s = BrowserOpsState(kind = BrowserOpKind.RESET, label = "Reset", progress = "Stopping TBP…")
        assertEquals("Resetting the internal browser…", BrowserOpsNotifText.title(s))
        assertEquals("Stopping TBP…", BrowserOpsNotifText.text(s))
        assertEquals("Running setup step 3…", BrowserOpsNotifText.title(BrowserOpsState(kind = BrowserOpKind.STEP, runningStep = 3)))
        assertEquals("Updating the browser bridge…", BrowserOpsNotifText.title(BrowserOpsState(kind = BrowserOpKind.UPDATE_BRIDGE)))
    }

    @Test fun `Load images note never promises a restart`() {
        assertTrue(MediaPrefNote.of(true, null).contains("next time the internal browser starts"))
        assertTrue(MediaPrefNote.of(false, "unknown cmd: set_media").contains("Update the bridge"))
        assertFalse(MediaPrefNote.of(null, null).contains("Reset"))
    }
}
