package com.farrow.app.data.social

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialJobRunnerTest {
    private class MemStore : SessionResultStore {
        val map = mutableMapOf<String, SocialSessionState>()
        override fun load(site: String) = map[site]
        override fun save(site: String, state: SocialSessionState) { map[site] = state }
    }

    @Test fun `job survives the observing (ViewModel) scope being cancelled`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = SocialJobRunner(app, MemStore(), clock = { 42L })
        val gate = CompletableDeferred<Unit>()
        val vmScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<Boolean>()
        vmScope.launch { runner.state("x").collect { seen += it.busy } }
        assertTrue(runner.launch("x", SessionJobKind.IMPORT, "Importing…") {
            runner.update("x") { it.copy(progress = "Step 1/2: write 9 cookies") }
            gate.await()
            runner.finish("x") { it.copy(message = "✅ Logged in") }
        })
        advanceUntilIdle()
        assertEquals("Step 1/2: write 9 cookies", runner.current("x").progress)
        assertEquals(setOf("x"), runner.active.value.keys)
        vmScope.cancel()                       // the user leaves the X login screen
        advanceUntilIdle()
        assertTrue(runner.isRunning("x"))
        gate.complete(Unit)
        advanceUntilIdle()
        val s = runner.current("x")
        assertFalse(s.busy); assertEquals("✅ Logged in", s.message); assertEquals(42L, s.finishedAt)
        assertTrue(runner.active.value.isEmpty())
        app.cancel()
    }

    @Test fun `only cancel() cancels, and it explains that the bridge still restarts the browser`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = SocialJobRunner(app, MemStore())
        var finished = false
        runner.launch("x", SessionJobKind.IMPORT, "Importing…") {
            runner.update("x") { it.copy(pendingJob = "cookies_import-1-abc") }
            CompletableDeferred<Unit>().await(); finished = true
        }
        advanceUntilIdle()
        // A second action while busy is ignored, not a restart.
        assertFalse(runner.launch("x", SessionJobKind.LOGIN, "Logging in…") {})
        runner.cancel("x")
        advanceUntilIdle()
        val s = runner.current("x")
        assertFalse(finished); assertFalse(s.busy); assertNull(s.pendingJob)
        assertTrue(s.error!!, s.error!!.contains("restarts by itself"))
        assertFalse(runner.isRunning("x"))
        app.cancel()
    }

    @Test fun `last result is persisted and shown by a new runner (reopened screen or restarted app)`() = runTest {
        val store = MemStore()
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val r1 = SocialJobRunner(app, store)
        r1.launch("facebook", SessionJobKind.IMPORT, "Importing…") {
            r1.update("facebook") { it.copy(cookieReport = "c_user present") }
            throw IllegalStateException("Imported 9 cookies but Facebook still shows: login page")
        }
        advanceUntilIdle()
        val known = KnownStatus(LoginStatus(LoginState.LOGGED_OUT, "https://facebook.com/login", emptyList(), "login page"), 7L)
        val r2 = SocialJobRunner(app, store, initialStatus = { known })
        val s = r2.state("facebook").first()
        assertFalse(s.busy); assertNull(s.progress)
        assertEquals("c_user present", s.cookieReport)
        assertTrue(s.error!!.contains("still shows"))
        assertEquals(LoginState.LOGGED_OUT, s.status?.state); assertEquals(7L, s.checkedAt)
        app.cancel()
    }

    @Test fun `a running Check is pre-empted by a new action, other jobs are not`() = runTest {
        val app = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = SocialJobRunner(app, MemStore())
        runner.launch("x", SessionJobKind.CHECK, "Checking session…") { CompletableDeferred<Unit>().await() }
        advanceUntilIdle()
        assertTrue(runner.preemptCheck("x"))
        assertTrue(runner.launch("x", SessionJobKind.IMPORT, "Importing…") { CompletableDeferred<Unit>().await() })
        advanceUntilIdle()
        assertFalse(runner.preemptCheck("x"))
        assertTrue(runner.isRunning("x"))
        runner.cancel("x"); app.cancel()
    }

    @Test fun `notification texts`() {
        assertEquals("Importing X login…", SessionNotifText.title("X", SessionJobKind.IMPORT))
        assertEquals("Checking Facebook session…", SessionNotifText.title("Facebook", SessionJobKind.CHECK))
        assertEquals("Step 1/2: write", SessionNotifText.text(SocialSessionState(progress = "Step 1/2: write")))
    }

    @Test fun `restored state never shows a stale spinner`() {
        val s = SocialSessionState(busy = true, verifying = true, progress = "Step 1/2").restored()
        assertFalse(s.busy || s.verifying); assertNull(s.progress)
    }
}
