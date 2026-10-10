package com.verdroid.app.data.network

import com.verdroid.app.data.mcp.MiniHttpServer
import com.verdroid.app.data.settings.ModelDefaultsMigration
import com.verdroid.app.data.settings.SettingsKeys
import com.verdroid.app.domain.model.DefaultModels
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KiloProviderTest {
    private val server = MiniHttpServer().also { it.start() }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val ok = """{"id":"x","model":"stealth/space-bunny-alpha","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}]}"""
    private val msgs = listOf(ApiMessage("user", "hello"))

    @After fun tearDown() = server.stop()

    private fun api(port: Int = server.port): KiloApi {
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(OpenRouterHeadersInterceptor()).addInterceptor(RateLimitInterceptor(RateLimitHeaderStore())).build()
        return Retrofit.Builder().baseUrl("http://127.0.0.1:$port/api/gateway/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(KiloApi::class.java)
    }

    @Test fun `kilo call sends no Authorization header and the bare model id`() = runBlocking {
        var req: MiniHttpServer.Req? = null
        server.route("/api/gateway/chat/completions") { r, res -> req = r; res.reply(200, ok) }
        val r = KiloProvider(api(), HourlyCounter(200)).complete(listOf(ModelIds.KILO_AUTO_FREE), msgs, null, 30_000)
        assertTrue(r is KiloResult.Success)
        assertEquals("kilo-auto/free (Kilo)", (r as KiloResult.Success).label)
        assertNull(req!!.header("Authorization"))
        assertNull(req!!.header(OpenRouterApi.TAG_MODEL))
        assertTrue(req!!.body.contains("\"model\":\"kilo-auto/free\""))
    }

    @Test fun `429 cools the model down and the next call does not hit the network`() = runBlocking {
        val hits = AtomicInteger()
        server.route("/api/gateway/chat/completions") { _, res -> hits.incrementAndGet(); res.reply(429, """{"error":{"message":"Rate limit exceeded","code":429}}""") }
        val p = KiloProvider(api(), HourlyCounter(200))
        val r1 = p.complete(listOf(ModelIds.KILO_AUTO_FREE), msgs, null, 10_000) as KiloResult.Unavailable
        assertTrue(r1.rateLimited); assertNotNull(r1.resumeAt)
        assertTrue(r1.resumeAt!! >= System.currentTimeMillis() + 55_000) // at least 60 s for a 429
        val r2 = p.complete(listOf(ModelIds.KILO_AUTO_FREE), msgs, null, 10_000) as KiloResult.Unavailable
        assertTrue(r2.rateLimited)
        assertEquals(1, hits.get())
    }

    @Test fun `5xx falls through to the next kilo model`() = runBlocking {
        server.route("/api/gateway/chat/completions") { r, res ->
            if (r.body.contains("kilo-auto/free")) res.reply(503, """{"error":{"message":"overloaded"}}""") else res.reply(200, ok)
        }
        val r = KiloProvider(api(), HourlyCounter(200)).complete(listOf(ModelIds.KILO_AUTO_FREE, "kilo:other/free"), msgs, null, 10_000)
        assertEquals("other/free (Kilo)", (r as KiloResult.Success).label)
    }

    @Test fun `network error or timeout cools down without rate limit`() = runBlocking {
        val dead = java.net.ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val p = KiloProvider(api(dead), HourlyCounter(200))
        val r = p.complete(listOf(ModelIds.KILO_AUTO_FREE), msgs, null, 10_000) as KiloResult.Unavailable
        assertFalse(r.rateLimited)
        assertTrue(p.cooldownUntil(ModelIds.KILO_AUTO_FREE) > System.currentTimeMillis())
    }

    @Test fun `hourly counter blocks the 201st request`() = runBlocking {
        val hits = AtomicInteger()
        server.route("/api/gateway/chat/completions") { _, res -> hits.incrementAndGet(); res.reply(200, ok) }
        val now = System.currentTimeMillis()
        val c = HourlyCounter(200, initial = List(200) { now - 1000 })
        val r = KiloProvider(api(), c).complete(listOf(ModelIds.KILO_AUTO_FREE), msgs, null, 10_000) as KiloResult.Unavailable
        assertTrue(r.rateLimited)
        assertEquals(0, hits.get())
        assertTrue(r.resumeAt!! in now..(now + 3_600_000))
    }

    @Test fun `hourly counter slides`() {
        val c = HourlyCounter(2, windowMs = 1000)
        c.record(0); c.record(500)
        assertFalse(c.canUse(600)); assertEquals(1000, c.nextFreeAt(600))
        assertTrue(c.canUse(1001)); assertEquals(1, c.used(1001))
    }

    @Test fun `model ids and labels`() {
        assertEquals(ModelProvider.KILO, ModelIds.provider("kilo:kilo-auto/free"))
        assertEquals(ModelProvider.OPENROUTER, ModelIds.provider("openrouter/free"))
        assertEquals("kilo-auto/free", ModelIds.bare("kilo:kilo-auto/free"))
        assertEquals("kilo-auto/free (Kilo)", ModelIds.label("kilo:kilo-auto/free"))
        assertEquals("openrouter/free", ModelIds.label("openrouter/free"))
        assertEquals(ModelIds.KILO_AUTO_FREE, DefaultModels.list.first())
        assertEquals(listOf(ModelIds.KILO_AUTO_FREE, "a/b"), ModelIds.withKiloFirst(listOf("a/b")))
        assertEquals(listOf("a/b", "kilo:x"), ModelIds.withKiloFirst(listOf("a/b", "kilo:x")))
    }

    @Test fun `memory block is stripped only from system messages`() {
        val sys = ApiMessage("system", "Rules.\n\n" + MemoryRedaction.wrap("Memory:\n- likes tea"))
        val out = MemoryRedaction.strip(listOf(sys, ApiMessage("user", "hi")))
        assertFalse(out[0].content!!.contains("likes tea"))
        assertTrue(out[0].content!!.startsWith("Rules."))
        assertEquals("hi", out[1].content)
        assertEquals("", MemoryRedaction.wrap("  "))
    }

    @Test fun `migration puts kilo on top of a saved list once`() = runBlocking {
        val custom = mutablePreferencesOf(SettingsKeys.MODELS to "a/b:free\nc/d:free", SettingsKeys.MODEL_DEFAULTS_VERSION to 2)
        assertTrue(ModelDefaultsMigration.shouldMigrate(custom))
        val m = ModelDefaultsMigration.migrate(custom)
        assertEquals("kilo:kilo-auto/free\na/b:free\nc/d:free", m[SettingsKeys.MODELS])
        assertEquals(DefaultModels.VERSION, m[SettingsKeys.MODEL_DEFAULTS_VERSION])
        assertFalse(ModelDefaultsMigration.shouldMigrate(m))
        // user later removed kilo: not re-added because the flag (version 3) is set
        val removed = mutablePreferencesOf(SettingsKeys.MODELS to "a/b:free", SettingsKeys.MODEL_DEFAULTS_VERSION to 3)
        assertFalse(ModelDefaultsMigration.shouldMigrate(removed))
        // legacy uncustomised list is dropped -> defaults (Kilo first) apply
        val legacy = mutablePreferencesOf(SettingsKeys.MODELS to DefaultModels.legacyV1.joinToString("\n"))
        assertNull(ModelDefaultsMigration.migrate(legacy)[SettingsKeys.MODELS])
    }
}
