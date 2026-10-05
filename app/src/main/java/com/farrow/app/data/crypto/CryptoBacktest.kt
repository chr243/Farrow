package com.farrow.app.data.crypto

import kotlin.math.max

/** One closed trade from a backtest. */
data class BacktestTrade(
    val entryTime: Long, val exitTime: Long,
    val entryPrice: Double, val exitPrice: Double,
    val side: String, // "long"
    val returnPct: Double,
)

/** Summary metrics of a backtest run. */
data class BacktestResult(
    val strategy: String,
    val pair: String,
    val candles: Int,
    val trades: List<BacktestTrade>,
    val totalReturnPct: Double,
    val maxDrawdownPct: Double,
    val winRatePct: Double,
    val equity: List<Pair<Long, Double>>, // time → equity multiplier (1.0 = start)
)

/**
 * Simple long-only SMA crossover on close prices: enter when fast SMA crosses above slow SMA,
 * exit when it crosses below. Fees are a flat fraction per fill (entry + exit).
 */
object CryptoBacktest {
    fun smaCrossover(
        candles: List<Candle>,
        pair: String,
        fast: Int = 10,
        slow: Int = 30,
        feePct: Double = 0.001,
    ): BacktestResult {
        require(fast >= 2 && slow > fast) { "need 2 ≤ fast < slow (got fast=$fast slow=$slow)" }
        require(candles.size >= slow + 2) { "need at least ${slow + 2} candles (got ${candles.size})" }
        val closes = candles.map { it.close }
        val fastS = sma(closes, fast)
        val slowS = sma(closes, slow)
        val trades = mutableListOf<BacktestTrade>()
        val equity = mutableListOf<Pair<Long, Double>>()
        var cash = 1.0
        var units = 0.0
        var entryTime = 0L
        var entryPrice = 0.0
        var peak = 1.0
        var maxDd = 0.0

        fun mark(i: Int) {
            val eq = if (units > 0) units * closes[i] else cash
            peak = max(peak, eq)
            maxDd = max(maxDd, if (peak > 0) (peak - eq) / peak else 0.0)
            equity += candles[i].time to eq
        }

        for (i in slow until candles.size) {
            val f = fastS[i]!!; val s = slowS[i]!!
            val prevF = fastS[i - 1]!!; val prevS = slowS[i - 1]!!
            val crossUp = prevF <= prevS && f > s
            val crossDown = prevF >= prevS && f < s
            if (units == 0.0 && crossUp) {
                entryPrice = closes[i]
                entryTime = candles[i].time
                units = (cash * (1.0 - feePct)) / entryPrice
                cash = 0.0
            } else if (units > 0.0 && crossDown) {
                val exit = closes[i]
                cash = units * exit * (1.0 - feePct)
                trades += BacktestTrade(entryTime, candles[i].time, entryPrice, exit, "long",
                    (exit / entryPrice - 1.0) * 100.0)
                units = 0.0
            }
            mark(i)
        }
        if (units > 0.0) {
            val i = candles.lastIndex
            val exit = closes[i]
            cash = units * exit * (1.0 - feePct)
            trades += BacktestTrade(entryTime, candles[i].time, entryPrice, exit, "long",
                (exit / entryPrice - 1.0) * 100.0)
            units = 0.0
            mark(i)
        }
        val wins = trades.count { it.returnPct > 0 }
        return BacktestResult(
            strategy = "sma_crossover($fast,$slow)",
            pair = pair,
            candles = candles.size,
            trades = trades,
            totalReturnPct = (cash - 1.0) * 100.0,
            maxDrawdownPct = maxDd * 100.0,
            winRatePct = if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size,
            equity = equity,
        )
    }

    /** SMA at each index; null until the window is full. */
    fun sma(values: List<Double>, window: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (window <= 0 || values.size < window) return out
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= window) sum -= values[i - window]
            if (i >= window - 1) out[i] = sum / window
        }
        return out
    }
}
