package com.farrow.app.agent.tools

import com.farrow.app.data.crypto.CoinbaseExchangeClient
import com.farrow.app.data.crypto.CryptoCredentials
import com.farrow.app.data.crypto.CryptoKeys
import com.farrow.app.data.tools.ToolPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CryptoToolsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun tools(client: CoinbaseExchangeClient = CoinbaseExchangeClient(), configured: Boolean = false) =
        CryptoToolFactory(client, if (configured) CryptoCredentials.fake() else CryptoCredentials.fake(null, null, null)).tools()

    private fun byName(name: String, client: CoinbaseExchangeClient = CoinbaseExchangeClient(), configured: Boolean = false) =
        tools(client, configured).first { it.name == name }

    @Test fun `live trading tools are off by default`() {
        assertTrue("crypto_place_order" in ToolPrefs.DEFAULT_OFF)
        assertTrue("crypto_cancel_order" in ToolPrefs.DEFAULT_OFF)
        assertTrue("crypto_place_order" in ToolPrefs.effective(emptySet(), emptySet()))
        assertFalse("crypto_ticker" in ToolPrefs.effective(emptySet(), emptySet()))
        assertFalse("crypto_backtest" in ToolPrefs.effective(emptySet(), emptySet()))
    }

    @Test fun `markets ticker orderbook from public transport`() = runBlocking {
        val client = CoinbaseExchangeClient()
        client.transport = { _, path, _ ->
            when {
                path == "/products" -> """[{"id":"BTC-USD","base_currency":"BTC","quote_currency":"USD","status":"online","trading_disabled":false},{"id":"ETH-EUR","base_currency":"ETH","quote_currency":"EUR","status":"online","trading_disabled":false}]"""
                path.endsWith("/ticker") -> """{"price":"1","bid":"1","ask":"1","volume":"2","time":"t"}"""
                path.contains("/book") -> """{"bids":[["1","2"]],"asks":[["1.1","3"]]}"""
                else -> error(path)
            }
        }
        val markets = obj(byName("crypto_markets", client).execute(buildJsonObject { put("quote", "USD") }))
        assertEquals(true, markets["ok"]!!.jsonPrimitive.boolean)
        assertEquals(1, markets["count"]!!.jsonPrimitive.int)
        assertTrue(markets["note"]!!.jsonPrimitive.content.contains("Revolut"))
        val tick = obj(byName("crypto_ticker", client).execute(buildJsonObject { put("pair", "BTC-USD") }))
        assertEquals("1", tick["price"]!!.jsonPrimitive.content)
        val book = obj(byName("crypto_orderbook", client).execute(buildJsonObject { put("pair", "btc-usd") }))
        assertEquals(true, book["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun `place order refuses without confirm and without keys`() = runBlocking {
        val place = byName("crypto_place_order")
        val noConfirm = obj(place.execute(buildJsonObject {
            put("pair", "BTC-USD"); put("side", "buy"); put("size", "0.001")
        }))
        assertTrue(noConfirm["error"]!!.jsonPrimitive.content.contains("confirm=true"))
        val noKeys = obj(place.execute(buildJsonObject {
            put("pair", "BTC-USD"); put("side", "buy"); put("size", "0.001"); put("confirm", true)
        }))
        assertTrue(noKeys["error"]!!.jsonPrimitive.content.contains("API key not set"))
    }

    @Test fun `place order with confirm and keys posts to exchange`() = runBlocking {
        val client = CoinbaseExchangeClient()
        client.keysOverride = CryptoKeys("k", "s", "p")
        var posted: String? = null
        client.transport = { method, path, body ->
            assertEquals("POST", method); assertEquals("/orders", path); posted = body
            """{"id":"ord-1","product_id":"BTC-USD","side":"buy","type":"market","size":"0.001","status":"pending","created_at":"t"}"""
        }
        val r = obj(byName("crypto_place_order", client, configured = true).execute(buildJsonObject {
            put("pair", "BTC-USD"); put("side", "buy"); put("size", "0.001"); put("confirm", true)
        }))
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("ord-1", r["id"]!!.jsonPrimitive.content)
        assertTrue(posted!!.contains("\"side\":\"buy\""))
    }

    @Test fun `backtest runs on candle transport`() = runBlocking {
        val client = CoinbaseExchangeClient()
        // 80 daily candles, rising
        // Fall then rise (newest first like Coinbase) so SMA crossover fires.
        val series = (0 until 40).map { i -> 140.0 - i } + (0 until 50).map { i -> 100.0 + i * 1.5 }
        val rows = series.mapIndexed { i, c ->
            val t = 1_700_000_000 + i * 86_400
            "[$t,${c - 1},${c + 1},$c,$c,1]"
        }.reversed().joinToString(",")
        client.transport = { _, path, _ ->
            assertTrue(path.contains("/candles"))
            "[$rows]"
        }
        val r = obj(byName("crypto_backtest", client).execute(buildJsonObject {
            put("pair", "BTC-USD"); put("fast", 5); put("slow", 20); put("fee_pct", 0.0)
        }))
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r["total_return_pct"]!!.jsonPrimitive.double > 0)
        assertTrue(r["equity_chart"]!!.jsonObject["type"]!!.jsonPrimitive.content == "line")
    }
}
