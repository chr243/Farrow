package com.verdroid.app.agent

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {
    @Test fun `backoff grows and is capped at five minutes`() {
        val r = Random(42)
        val d0 = BackoffPolicy.backoffMs(0, r)
        assertTrue(d0 in 2_500L..5_000L)
        val d3 = BackoffPolicy.backoffMs(3, r)
        assertTrue(d3 in 20_000L..40_000L)
        repeat(50) { assertTrue(BackoffPolicy.backoffMs(20, r) <= BackoffPolicy.CAP_MS) }
    }

    @Test fun `daily quota resumes at utc midnight and explicit reset wins over cap`() {
        val now = 1_000_000L
        assertEquals(9_999_999L, BackoffPolicy.resumeAt(now, 0, true, 9_999_999L, null, null))
        val explicit = now + 20 * 60_000L
        assertEquals(explicit, BackoffPolicy.resumeAt(now, 0, false, 0L, explicit, null))
        val backoff = BackoffPolicy.resumeAt(now, 30, false, 0L, null, now + 60 * 60_000L)
        assertTrue(backoff <= now + BackoffPolicy.CAP_MS)
    }

    @Test fun `checkpoint codec round trips and tolerates v1`() {
        val cp = TaskCheckpoint(step = 3, completedSteps = listOf(CompletedStep(3, "m", listOf("list_dir"), 1)))
        assertEquals(cp, CheckpointCodec.decode(CheckpointCodec.encode(cp), 0))
        assertEquals(7, CheckpointCodec.decode("""{"version":1,"step":7}""", 7).step)
        assertEquals(2, CheckpointCodec.decode("garbage", 2).step)
    }
}
