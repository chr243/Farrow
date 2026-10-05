package com.farrow.app.agent.tools

import com.farrow.app.data.crypto.*
import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.round

/**
 * Crypto tools backed by Coinbase Exchange (public market data + optional authenticated trading).
 * Revolut has no public crypto trading/exchange API (Business API is fiat accounts/FX only), so candles,
 * ticker, order book, balances and orders use Coinbase Exchange instead. Live place/cancel are off by
 * default ([ToolPrefs.DEFAULT_OFF]).
 */
class CryptoToolFactory(
    private val client: CoinbaseExchangeClient,
    private val credentials: CryptoCredentialSource,
) {
    fun tools(): List<AgentTool> = listOf(
        markets(), ticker(), candles(), orderBook(),
        balance(), orderStatus(), placeOrder(), cancelOrder(),
        backtest(),
    )

    private fun markets() = object : AgentTool {
        override val name = "crypto_markets"
        override val description = "List spot crypto trading pairs on Coinbase Exchange (public, no API key). Optional quote filter (e.g. USD, EUR)."
        override val parameters = schema(emptyList(), "quote" to prop("string", "Filter by quote currency, e.g. USD or EUR"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            val quote = args.str("quote")?.uppercase(Locale.US)
            val arr = client.get("/products").jsonArray
            val pairs = arr.mapNotNull { it as? JsonObject }
                .filter { it.str("status") == "online" && it.bool("trading_disabled") != true }
                .filter { quote == null || it.str("quote_currency") == quote }
                .map { buildJsonObject {
                    put("id", it.str("id") ?: "")
                    put("base", it.str("base_currency") ?: "")
                    put("quote", it.str("quote_currency") ?: "")
                } }
            buildJsonObject {
                put("ok", true); put("exchange", "coinbase_exchange"); put("count", pairs.size)
                put("pairs", JsonArray(pairs.take(200)))
                put("note", "Revolut has no public crypto trading API; market data is from Coinbase Exchange.")
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_markets failed") }
        }
    }

    private fun ticker() = object : AgentTool {
        override val name = "crypto_ticker"
        override val description = "Latest ticker for a Coinbase Exchange pair (public): price, bid, ask, 24h volume. Pair e.g. BTC-USD."
        override val parameters = schema(listOf("pair"), "pair" to prop("string", "Product id, e.g. BTC-USD or ETH-EUR"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            val pair = pair(args)
            val t = client.get("/products/$pair/ticker").jsonObject
            buildJsonObject {
                put("ok", true); put("pair", pair); put("exchange", "coinbase_exchange")
                put("price", t.str("price")); put("bid", t.str("bid")); put("ask", t.str("ask"))
                put("volume_24h", t.str("volume")); put("time", t.str("time"))
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_ticker failed") }
        }
    }

    private fun candles() = object : AgentTool {
        override val name = "crypto_candles"
        override val description = "Historical OHLCV candles for a Coinbase Exchange pair (public). granularity_seconds: 60, 300, 900, 3600, 21600 or 86400. Returns oldest-first."
        override val parameters = schema(listOf("pair"),
            "pair" to prop("string", "Product id, e.g. BTC-USD"),
            "granularity_seconds" to prop("integer", "Candle size in seconds (default 3600 = 1h)"),
            "limit" to prop("integer", "Max candles to return (default 100, max 300)"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            val pair = pair(args)
            val gran = (args.int("granularity_seconds") ?: 3600)
            if (gran !in CoinbaseExchangeClient.GRANULARITIES)
                return errorJson("granularity_seconds must be one of ${CoinbaseExchangeClient.GRANULARITIES.sorted()}")
            val limit = (args.int("limit") ?: 100).coerceIn(1, 300)
            val all = CoinbaseExchangeClient.parseCandles(client.get("/products/$pair/candles?granularity=$gran"))
            val slice = all.takeLast(limit)
            buildJsonObject {
                put("ok", true); put("pair", pair); put("granularity_seconds", gran); put("count", slice.size)
                put("candles", JsonArray(slice.map { c -> buildJsonObject {
                    put("time", c.time); put("open", c.open); put("high", c.high); put("low", c.low)
                    put("close", c.close); put("volume", c.volume)
                } }))
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_candles failed") }
        }
    }

    private fun orderBook() = object : AgentTool {
        override val name = "crypto_orderbook"
        override val description = "Top of the order book for a Coinbase Exchange pair (public). level 1 = best bid/ask, 2 = top 50."
        override val parameters = schema(listOf("pair"),
            "pair" to prop("string", "Product id, e.g. BTC-USD"),
            "level" to prop("integer", "1 (best) or 2 (top 50); default 1"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            val pair = pair(args)
            val level = (args.int("level") ?: 1).coerceIn(1, 2)
            val book = client.get("/products/$pair/book?level=$level").jsonObject
            buildJsonObject {
                put("ok", true); put("pair", pair); put("level", level)
                put("bids", book["bids"] ?: JsonArray(emptyList()))
                put("asks", book["asks"] ?: JsonArray(emptyList()))
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_orderbook failed") }
        }
    }

    private fun balance() = object : AgentTool {
        override val name = "crypto_balance"
        override val description = "Account balances on Coinbase Exchange (needs API key in Settings). Returns non-zero accounts."
        override val parameters = schema(emptyList())
        override suspend fun execute(args: JsonObject): String {
            return try {
            if (!credentials.configured) return errorJson(CoinbaseExchangeClient.NO_KEYS)
            val arr = client.getAuth("/accounts").jsonArray
            val bals = arr.mapNotNull { it as? JsonObject }
                .filter { (it.str("balance")?.toDoubleOrNull() ?: 0.0) > 0.0 }
                .map { buildJsonObject {
                    put("currency", it.str("currency")); put("balance", it.str("balance"))
                    put("available", it.str("available")); put("hold", it.str("hold"))
                } }
            buildJsonObject { put("ok", true); put("accounts", JsonArray(bals)); put("count", bals.size) }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_balance failed") }
        }
    }

    private fun orderStatus() = object : AgentTool {
        override val name = "crypto_order_status"
        override val description = "Order status on Coinbase Exchange by order id (needs API key)."
        override val parameters = schema(listOf("order_id"), "order_id" to prop("string", "Coinbase order UUID"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            if (!credentials.configured) return errorJson(CoinbaseExchangeClient.NO_KEYS)
            val id = args.str("order_id")?.trim().orEmpty()
            if (id.isEmpty()) return errorJson("order_id is required")
            val o = client.getAuth("/orders/$id").jsonObject
            buildJsonObject {
                put("ok", true)
                listOf("id", "product_id", "side", "type", "size", "price", "status", "filled_size", "fill_fees", "created_at", "done_at", "done_reason")
                    .forEach { k -> o[k]?.let { put(k, it) } }
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_order_status failed") }
        }
    }

    private fun placeOrder() = object : AgentTool {
        override val name = "crypto_place_order"
        override val description = "Place a live order on Coinbase Exchange (OFF by default under Tools). Only when the user explicitly asks with pair and size. Needs API key. side=buy|sell, type=market|limit (limit needs price)."
        override val parameters = schema(listOf("pair", "side", "size"),
            "pair" to prop("string", "Product id, e.g. BTC-USD"),
            "side" to prop("string", "buy or sell"),
            "size" to prop("string", "Base-currency amount as a string, e.g. 0.001"),
            "type" to prop("string", "market (default) or limit"),
            "price" to prop("string", "Limit price (required for type=limit)"),
            "confirm" to prop("boolean", "Must be true to actually place the order"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            if (args.bool("confirm") != true)
                return errorJson("refused: set confirm=true only when the user explicitly asked to place this order with this size and pair")
            if (!credentials.configured) return errorJson(CoinbaseExchangeClient.NO_KEYS)
            val pair = pair(args)
            val side = args.str("side")?.lowercase(Locale.US)
            if (side != "buy" && side != "sell") return errorJson("side must be buy or sell")
            val size = args.str("size")?.trim().orEmpty()
            if (size.isEmpty() || size.toDoubleOrNull() == null || size.toDouble() <= 0)
                return errorJson("size must be a positive number string")
            val type = (args.str("type") ?: "market").lowercase(Locale.US)
            if (type !in setOf("market", "limit")) return errorJson("type must be market or limit")
            val body = buildJsonObject {
                put("product_id", pair); put("side", side!!); put("type", type); put("size", size)
                if (type == "limit") {
                    val price = args.str("price")?.trim().orEmpty()
                    if (price.isEmpty() || price.toDoubleOrNull() == null) return errorJson("price is required for limit orders")
                    put("price", price); put("time_in_force", "GTC")
                }
            }
            val o = client.postAuth("/orders", body).jsonObject
            buildJsonObject {
                put("ok", true); put("live", true)
                listOf("id", "product_id", "side", "type", "size", "price", "status", "created_at").forEach { k -> o[k]?.let { put(k, it) } }
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_place_order failed") }
        }
    }

    private fun cancelOrder() = object : AgentTool {
        override val name = "crypto_cancel_order"
        override val description = "Cancel a live order on Coinbase Exchange (OFF by default under Tools). Needs API key and confirm=true."
        override val parameters = schema(listOf("order_id"),
            "order_id" to prop("string", "Coinbase order UUID"),
            "confirm" to prop("boolean", "Must be true to cancel"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            if (args.bool("confirm") != true) return errorJson("refused: set confirm=true only when the user asked to cancel this order")
            if (!credentials.configured) return errorJson(CoinbaseExchangeClient.NO_KEYS)
            val id = args.str("order_id")?.trim().orEmpty()
            if (id.isEmpty()) return errorJson("order_id is required")
            val r = client.deleteAuth("/orders/$id")
            buildJsonObject { put("ok", true); put("cancelled", r); put("order_id", id) }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_cancel_order failed") }
        }
    }

    private fun backtest() = object : AgentTool {
        override val name = "crypto_backtest"
        override val description = "Run a local SMA-crossover backtest on Coinbase Exchange candles (public). Returns total return %, max drawdown %, win rate, trade list, and a chart-ready equity series. No API key, no live orders."
        override val parameters = schema(listOf("pair"),
            "pair" to prop("string", "Product id, e.g. BTC-USD"),
            "granularity_seconds" to prop("integer", "Candle size (default 86400 = 1d)"),
            "fast" to prop("integer", "Fast SMA period (default 10)"),
            "slow" to prop("integer", "Slow SMA period (default 30)"),
            "fee_pct" to prop("number", "Fee fraction per fill, default 0.001 (=0.1%)"),
            "limit" to prop("integer", "Candles to fetch (default 300, max 300)"))
        override suspend fun execute(args: JsonObject): String {
            return try {
            val pair = pair(args)
            val gran = (args.int("granularity_seconds") ?: 86_400)
            if (gran !in CoinbaseExchangeClient.GRANULARITIES)
                return errorJson("granularity_seconds must be one of ${CoinbaseExchangeClient.GRANULARITIES.sorted()}")
            val limit = (args.int("limit") ?: 300).coerceIn(50, 300)
            val fast = args.int("fast") ?: 10
            val slow = args.int("slow") ?: 30
            val fee = (args["fee_pct"] as? JsonPrimitive)?.doubleOrNull ?: 0.001
            val candles = CoinbaseExchangeClient.parseCandles(client.get("/products/$pair/candles?granularity=$gran")).takeLast(limit)
            val r = CryptoBacktest.smaCrossover(candles, pair, fast, slow, fee)
            // Downsample equity for a chart (≤ 60 points).
            val step = maxOf(1, r.equity.size / 60)
            val eq = r.equity.filterIndexed { i, _ -> i % step == 0 || i == r.equity.lastIndex }
            buildJsonObject {
                put("ok", true); put("strategy", r.strategy); put("pair", r.pair); put("candles", r.candles)
                put("trades", r.trades.size)
                put("total_return_pct", round2(r.totalReturnPct))
                put("max_drawdown_pct", round2(r.maxDrawdownPct))
                put("win_rate_pct", round2(r.winRatePct))
                put("trade_list", JsonArray(r.trades.takeLast(20).map { t -> buildJsonObject {
                    put("entry_time", t.entryTime); put("exit_time", t.exitTime)
                    put("entry", t.entryPrice); put("exit", t.exitPrice)
                    put("return_pct", round2(t.returnPct)); put("side", t.side)
                } }))
                put("equity_chart", buildJsonObject {
                    put("type", "line"); put("title", "Equity · ${r.strategy} · $pair")
                    put("x_label", "time"); put("y_label", "equity")
                    put("labels", JsonArray(eq.map { JsonPrimitive(it.first.toString()) }))
                    put("series", JsonArray(listOf(buildJsonObject {
                        put("name", "equity"); put("values", JsonArray(eq.map { JsonPrimitive(round2(it.second)) }))
                    })))
                })
                put("note", "Pass equity_chart to the chart tool to plot it. This is a toy backtest, not financial advice.")
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_backtest failed") }
        }
    }

    private fun pair(args: JsonObject): String {
        val p = args.str("pair")?.trim()?.uppercase(Locale.US)?.replace('/', '-').orEmpty()
        require(p.matches(Regex("""[A-Z0-9]{2,10}-[A-Z0-9]{2,10}"""))) { "pair must look like BTC-USD (got '$p')" }
        return p
    }

    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.bool(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
    private fun round2(x: Double) = round(x * 100.0) / 100.0
}
