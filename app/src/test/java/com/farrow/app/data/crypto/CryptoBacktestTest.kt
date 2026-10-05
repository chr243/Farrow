package com.farrow.app.data.crypto

import org.junit.Assert.*
import org.junit.Test

class CryptoBacktestTest {
    private fun candles(n: Int, start: Double = 100.0, step: Double = 1.0) =
        (0 until n).map { i ->
            val c = start + i * step
            Candle(1_700_000_000L + i * 86_400L, c, c + 1, c - 1, c, 1.0)
        }

    @Test fun `sma windows fill after the period`() {
        val s = CryptoBacktest.sma(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertNull(s[0]); assertNull(s[1])
        assertEquals(2.0, s[2]!!, 1e-9)
        assertEquals(3.0, s[3]!!, 1e-9)
        assertEquals(4.0, s[4]!!, 1e-9)
    }

    @Test fun `uptrend sma crossover makes a profitable long`() {
        // Fall then rise so fast SMA crosses above slow after the trough, then ride the uptrend.
        val down = candles(40, 140.0, -1.0)
        val up = candles(50, 100.0, 1.5).mapIndexed { i, c -> c.copy(time = down.last().time + (i + 1) * 86_400L) }
        val r = CryptoBacktest.smaCrossover(down + up, "BTC-USD", fast = 5, slow = 20, feePct = 0.0)
        assertTrue("trades=${r.trades}", r.trades.isNotEmpty())
        assertTrue("return ${r.totalReturnPct}", r.totalReturnPct > 0)
        assertTrue(r.maxDrawdownPct >= 0)
        assertEquals(90, r.candles)
        assertTrue(r.equity.isNotEmpty())
    }

    @Test fun `rejects bad windows`() {
        assertThrows(IllegalArgumentException::class.java) {
            CryptoBacktest.smaCrossover(candles(10), "BTC-USD", fast = 5, slow = 5)
        }
    }
}
