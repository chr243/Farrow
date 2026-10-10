package com.farrow.app.data.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** One OHLCV candle: time is Unix seconds. */
data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

/** Key material for signed Coinbase Exchange requests. */
data class CryptoKeys(val apiKey: String?, val apiSecret: String?, val passphrase: String?) {
    val configured: Boolean get() = !apiKey.isNullOrBlank() && !apiSecret.isNullOrBlank() && !passphrase.isNullOrBlank()
}

/**
 * Coinbase Exchange REST client (public market data + optional HMAC-authenticated trading).
 * Revolut has no public crypto trading API — this is the fallback for candles, ticker, book, balances and orders.
 */
@Singleton
class CoinbaseExchangeClient(
    private val credentials: CryptoCredentialSource? = null,
) {
    @Inject constructor(credentials: CryptoCredentials) : this(credentials as CryptoCredentialSource)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = DEFAULT_BASE
    /** Override keys for tests (null = read from [credentials]). */
    var keysOverride: CryptoKeys? = null
    /** Override for tests: (method, path, body) → response body string. */
    var transport: (suspend (String, String, String?) -> String)? = null

    private fun keys(): CryptoKeys = keysOverride
        ?: CryptoKeys(credentials?.apiKey, credentials?.apiSecret, credentials?.passphrase)

    suspend fun get(path: String, query: String = ""): JsonElement = request("GET", path + query, null, auth = false)
    suspend fun getAuth(path: String, query: String = ""): JsonElement = request("GET", path + query, null, auth = true)
    suspend fun postAuth(path: String, body: JsonObject): JsonElement =
        request("POST", path, Json.encodeToString(JsonObject.serializer(), body), auth = true)
    suspend fun deleteAuth(path: String): JsonElement = request("DELETE", path, null, auth = true)

    private suspend fun request(method: String, pathAndQuery: String, body: String?, auth: Boolean): JsonElement =
        withContext(Dispatchers.IO) {
            val path = pathAndQuery.substringBefore('?').ifBlank { "/" }
            val q = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
            val fullPath = if (q.isEmpty()) path else "$path?$q"
            if (auth && !keys().configured) throw CryptoApiException(NO_KEYS)
            val raw = transport?.invoke(method, fullPath, body) ?: httpCall(method, fullPath, body, auth)
            val el = runCatching { Json.parseToJsonElement(raw) }.getOrElse {
                throw CryptoApiException("invalid JSON from Coinbase: ${raw.take(200)}")
            }
            if (el is JsonObject && el["message"] != null && el.size <= 3)
                throw CryptoApiException(el["message"]!!.jsonPrimitive.content)
            el
        }

    private fun httpCall(method: String, pathAndQuery: String, body: String?, auth: Boolean): String {
        val url = baseUrl.trimEnd('/') + pathAndQuery
        val builder = Request.Builder().url(url)
        if (auth) {
            val k = keys()
            if (!k.configured) throw CryptoApiException(NO_KEYS)
            val ts = (System.currentTimeMillis() / 1000.0).toString()
            val pathOnly = pathAndQuery.substringBefore('?')
            val sign = sign(k.apiSecret!!, ts, method, pathOnly, body.orEmpty())
            builder.header("CB-ACCESS-KEY", k.apiKey!!)
                .header("CB-ACCESS-SIGN", sign)
                .header("CB-ACCESS-TIMESTAMP", ts)
                .header("CB-ACCESS-PASSPHRASE", k.passphrase!!)
        }
        builder.header("Accept", "application/json").header("User-Agent", "Farrow/1.0")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "{}").toRequestBody(JSON))
            else -> error("unsupported $method")
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                throw CryptoApiException(msg ?: "HTTP ${resp.code}: ${text.take(300)}")
            }
            return text
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://api.exchange.coinbase.com"
        const val NO_KEYS = "Coinbase Exchange API key not set. Open Settings > Tools > Coinbase Exchange key, or use public market tools without a key."
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** CB-ACCESS-SIGN = base64(HMAC-SHA256(base64-decoded secret, timestamp + method + path + body)). */
        fun sign(secretBase64: String, timestamp: String, method: String, requestPath: String, body: String): String {
            val key = Base64.getDecoder().decode(secretBase64)
            val prehash = timestamp + method.uppercase() + requestPath + body
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(prehash.toByteArray(StandardCharsets.UTF_8)))
        }

        /** Candles JSON: [time, low, high, open, close, volume], newest first → oldest-first list. */
        fun parseCandles(el: JsonElement): List<Candle> {
            val arr = el as? JsonArray ?: throw CryptoApiException("candles response is not an array")
            return arr.map { row ->
                val a = row.jsonArray
                Candle(
                    time = a[0].jsonPrimitive.long,
                    low = a[1].jsonPrimitive.double,
                    high = a[2].jsonPrimitive.double,
                    open = a[3].jsonPrimitive.double,
                    close = a[4].jsonPrimitive.double,
                    volume = a[5].jsonPrimitive.double,
                )
            }.sortedBy { it.time }
        }

        val GRANULARITIES = setOf(60, 300, 900, 3600, 21600, 86400)
    }
}

class CryptoApiException(message: String) : Exception(message)
