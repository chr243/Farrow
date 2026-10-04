package com.farrow.app.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Parses OpenRouter / Kilo `GET /models` (OpenAI-style `data[]` with `architecture.input_modalities`). */
object ModelCaps {
    /** id (with [prefix], e.g. "kilo:") → accepts image input. */
    fun parse(body: String, prefix: String = ""): Map<String, Boolean> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyMap()
        val arr = (root as? JsonObject)?.get("data") as? JsonArray ?: root as? JsonArray ?: return emptyMap()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val arch = o["architecture"] as? JsonObject
            val mods = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val image = mods?.contains("image")
                ?: (arch?.get("modality")?.jsonPrimitive?.contentOrNull?.substringBefore("->")?.contains("image") == true)
            (prefix + id) to image
        }.toMap()
    }
}

/** Small persistent key/value seam so the cache logic can be unit-tested. */
interface CapsStore { fun load(): Pair<Long, Map<String, Boolean>>; fun save(at: Long, caps: Map<String, Boolean>) }

/**
 * Which models accept images, from the providers' model lists. Cached in memory and on disk for 24 h; unknown models
 * (or no network) count as text-only.
 */
open class VisionCaps(
    private val fetch: suspend (url: String) -> String?,
    private val store: CapsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    @Volatile private var cache: Map<String, Boolean>? = null
    @Volatile private var fetchedAt = 0L

    suspend fun supportsImages(modelId: String): Boolean = caps()[modelId.trim()] ?: false

    suspend fun caps(): Map<String, Boolean> = mutex.withLock {
        if (cache == null) store.load().let { (at, m) -> if (m.isNotEmpty()) { cache = m; fetchedAt = at } }
        val now = clock()
        if (cache == null || now - fetchedAt > TTL_MS) {
            val or = fetch(OPENROUTER_MODELS)?.let { ModelCaps.parse(it) }.orEmpty()
            val kilo = fetch(KILO_MODELS)?.let { ModelCaps.parse(it, ModelIds.KILO_PREFIX) }.orEmpty()
            if (or.isNotEmpty() || kilo.isNotEmpty()) {
                val merged = or + kilo
                cache = merged; fetchedAt = now; store.save(now, merged)
            }
        }
        cache.orEmpty()
    }

    companion object {
        const val TTL_MS = 24 * 3_600_000L
        const val OPENROUTER_MODELS = "https://openrouter.ai/api/v1/models"
        const val KILO_MODELS = KiloApi.BASE_URL + "models"
    }
}

private class PrefsCapsStore(context: Context) : CapsStore {
    private val prefs = context.getSharedPreferences("model_caps", Context.MODE_PRIVATE)
    private fun list(k: String) = prefs.getString(k, "").orEmpty().split('\n').filter { it.isNotBlank() }
    override fun load(): Pair<Long, Map<String, Boolean>> =
        prefs.getLong("at", 0) to (list("vision").associateWith { true } + list("text").associateWith { false })
    override fun save(at: Long, caps: Map<String, Boolean>) {
        prefs.edit().putLong("at", at)
            .putString("vision", caps.filterValues { it }.keys.joinToString("\n"))
            .putString("text", caps.filterValues { !it }.keys.joinToString("\n")).apply()
    }
}

@Singleton
class ModelCapabilities @Inject constructor(@ApplicationContext context: Context, client: OkHttpClient) : VisionCaps(
    fetch = { url ->
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
        }
    },
    store = PrefsCapsStore(context),
)
