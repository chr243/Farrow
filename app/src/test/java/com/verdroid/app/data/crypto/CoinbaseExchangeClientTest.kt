package com.verdroid.app.data.crypto

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CoinbaseExchangeClientTest {
    @Test fun `sign matches Coinbase HMAC-SHA256 base64 scheme`() {
        val secret = Base64.getEncoder().encodeToString("secret".toByteArray())
        val sig = CoinbaseExchangeClient.sign(secret, "1234567890", "GET", "/accounts", "")
        assertEquals(44, sig.length)
        assertEquals(sig, CoinbaseExchangeClient.sign(secret, "1234567890", "GET", "/accounts", ""))
        assertNotEquals(sig, CoinbaseExchangeClient.sign(secret, "1234567890", "POST", "/orders", "{}"))
    }

    @Test fun `parseCandles sorts oldest first`() {
        val raw = Json.parseToJsonElement("""[[200,1,2,1.5,1.8,10],[100,1,2,1.1,1.2,5]]""")
        val c = CoinbaseExchangeClient.parseCandles(raw)
        assertEquals(listOf(100L, 200L), c.map { it.time })
        assertEquals(1.2, c[0].close, 0.0)
    }

    @Test fun `transport mock serves ticker`() = runBlocking {
        val client = CoinbaseExchangeClient()
        client.transport = { method, path, _ ->
            assertEquals("GET", method)
            assertEquals("/products/BTC-USD/ticker", path)
            """{"price":"100.5","bid":"100","ask":"101","volume":"3","time":"t"}"""
        }
        val t = client.get("/products/BTC-USD/ticker").jsonObject
        assertEquals("100.5", t["price"]!!.jsonPrimitive.content)
    }

    @Test fun `auth without keys fails clearly`() = runBlocking {
        val client = CoinbaseExchangeClient()
        client.transport = { _, _, _ -> error("should not call") }
        val e = runCatching { client.getAuth("/accounts") }.exceptionOrNull()
        assertTrue("$e", e is CryptoApiException && e.message!!.contains("API key not set"))
    }
}
