package com.verdroid.app.data.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Where a model in the priority list is served from. */
enum class ModelProvider(val label: String) { OPENROUTER("OpenRouter"), KILO("Kilo") }

/**
 * Model ids in the priority list: plain ids are OpenRouter; `kilo:<id>` is the Kilo Gateway (no key needed for its free
 * models). Chat labels show "kilo-auto/free (Kilo)".
 */
object ModelIds {
    const val KILO_PREFIX = "kilo:"
    const val KILO_AUTO_FREE = "kilo:kilo-auto/free"

    fun provider(id: String): ModelProvider = if (id.trim().startsWith(KILO_PREFIX)) ModelProvider.KILO else ModelProvider.OPENROUTER
    fun isKilo(id: String) = provider(id) == ModelProvider.KILO
    /** The id the provider's API expects (prefix removed). */
    fun bare(id: String): String = id.trim().removePrefix(KILO_PREFIX)
    /** Label for chats / lists: "kilo-auto/free (Kilo)", OpenRouter ids unchanged. */
    fun label(id: String): String = if (isKilo(id)) "${bare(id)} (Kilo)" else id
    /** One-time migration for existing installs: Kilo Auto Free on top unless a Kilo model is already listed. */
    fun withKiloFirst(models: List<String>): List<String> = if (models.any(::isKilo)) models else listOf(KILO_AUTO_FREE) + models
}

/** Kilo Gateway (OpenAI-compatible). Deliberately has NO Authorization header: free models are anonymous. */
interface KiloApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header(OpenRouterApi.TAG_MODEL) model: String,
        @Header(OpenRouterApi.TAG_KEY_LABEL) keyLabel: String,
        @Body request: ChatRequest,
    ): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://api.kilo.ai/api/gateway/"
        /** Anonymous free-model limit per IP (Kilo docs); Kilo sends no rate-limit headers, so the app counts. */
        const val HOURLY_LIMIT = 200
        const val KEY_LABEL = "Kilo (no key)"
    }
}

/** Sliding one-hour request counter (pure; persisted by [KiloUsage]). */
class HourlyCounter(val limit: Int, private val windowMs: Long = 60 * 60_000L, initial: List<Long> = emptyList()) {
    private val stamps = ArrayDeque(initial.sorted())
    @Synchronized private fun prune(now: Long) { while (stamps.isNotEmpty() && stamps.first() <= now - windowMs) stamps.removeFirst() }
    @Synchronized fun used(now: Long = System.currentTimeMillis()): Int { prune(now); return stamps.size }
    @Synchronized fun canUse(now: Long = System.currentTimeMillis()) = used(now) < limit
    @Synchronized fun record(now: Long = System.currentTimeMillis()) { prune(now); stamps.addLast(now) }
    /** When the next request is allowed again (now if under the limit). */
    @Synchronized fun nextFreeAt(now: Long = System.currentTimeMillis()): Long { prune(now); return if (stamps.size < limit) now else stamps.first() + windowMs }
    @Synchronized fun snapshot(): List<Long> = stamps.toList()
}

/** Persisted Kilo request counter (200/hour per IP for anonymous free models). */
@Singleton
class KiloUsage @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kilo_usage", Context.MODE_PRIVATE)
    val counter = HourlyCounter(KiloApi.HOURLY_LIMIT,
        initial = prefs.getString("stamps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() })
    fun record() { counter.record(); prefs.edit().putString("stamps", counter.snapshot().joinToString(",")).apply() }
}

/** Result of trying the Kilo models of the priority list. */
sealed interface KiloResult {
    data class Success(val completion: ParsedCompletion, val label: String, val id: String = "") : KiloResult
    /** Nothing answered. [resumeAt] = when a Kilo model / the hourly budget frees up (null = not rate-limited). */
    data class Unavailable(val message: String, val resumeAt: Long?, val rateLimited: Boolean) : KiloResult
}

/**
 * Calls Kilo models in order with their own cooldowns: 429 → cooldown (≥ 60 s), 5xx / timeout / bad reply → cooldown,
 * 401/403 → 1 h. The in-app hourly counter keeps us under Kilo's anonymous limit.
 */
class KiloProvider(
    private val api: KiloApi,
    private val counter: HourlyCounter,
    private val onRequest: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cooldowns = HashMap<String, Long>()
    @Synchronized fun cooldownUntil(model: String) = cooldowns[model] ?: 0L
    @Synchronized private fun cool(model: String, until: Long) { cooldowns[model] = until }

    suspend fun complete(models: List<String>, messages: List<ApiMessage>, tools: JsonArray?, cooldownMs: Long,
                         status: suspend (String) -> Unit = {},
                         prepare: suspend (modelId: String, List<ApiMessage>) -> List<ApiMessage> = { _, m -> m }): KiloResult {
        var lastError = "No Kilo model available"
        var rateLimited = false
        for (id in models) {
            val now = clock()
            if (cooldownUntil(id) > now) { rateLimited = true; lastError = "${ModelIds.label(id)} is cooling down"; continue }
            if (!counter.canUse(now)) {
                return KiloResult.Unavailable("Kilo hourly limit (${counter.limit}/h) reached", counter.nextFreeAt(now), true)
            }
            val model = ModelIds.bare(id)
            status("Calling ${ModelIds.label(id)}…")
            counter.record(now); onRequest()
            val response = try {
                api.chatCompletions(model, KiloApi.KEY_LABEL,
                    ChatRequest(model = model, messages = prepare(id, messages), tools = tools, toolChoice = if (tools != null) "auto" else null))
            } catch (e: CancellationException) { throw e } catch (e: IOException) {
                lastError = "${ModelIds.label(id)}: network error/timeout (${e.message ?: e.javaClass.simpleName})"
                cool(id, clock() + cooldownMs); continue
            }
            val body = try { (if (response.isSuccessful) response.body() else response.errorBody())?.string() } catch (e: IOException) { null }
            val err = ApiErrorDetector.detect(response.code(), body) ?: run {
                val parsed = body?.let { CompletionParser.parse(it) }
                if (parsed != null) return KiloResult.Success(parsed, ModelIds.label(id), id)
                DetectedApiError(ApiErrorKind.UPSTREAM, 502, "Empty or unparsable completion", null)
            }
            lastError = "${ModelIds.label(id)}: ${err.message}"
            val t = clock()
            when (err.kind) {
                ApiErrorKind.RATE_LIMIT -> { rateLimited = true; cool(id, t + maxOf(cooldownMs, 60_000L)) }
                ApiErrorKind.AUTH, ApiErrorKind.PAYMENT -> cool(id, t + 60 * 60_000L)
                else -> cool(id, t + cooldownMs)
            }
        }
        val resumeAt = models.map { cooldownUntil(it) }.filter { it > clock() }.minOrNull()
        return KiloResult.Unavailable(lastError, if (rateLimited) resumeAt else null, rateLimited)
    }
}

/** Removes the memory block from system messages (setting "Send memories to Kilo models" off). */
object MemoryRedaction {
    const val START = "<verdroid-memory>"
    const val END = "</verdroid-memory>"
    private val BLOCK = Regex(Regex.escape(START) + ".*?" + Regex.escape(END), RegexOption.DOT_MATCHES_ALL)

    fun wrap(block: String) = if (block.isBlank()) "" else "$START\n$block\n$END"

    fun strip(messages: List<ApiMessage>): List<ApiMessage> = messages.map { m ->
        if (m.role == "system" && m.content?.contains(START) == true)
            m.copy(content = m.content.replace(BLOCK, "(Memories are not shared with this model.)")) else m
    }
}

/** Images for models that can't see them: dropped, with a short note in the text. */
object ImageStrip {
    const val NOTE = "(An image was included here, but this model can't see images; rely on the text, file name/path and tool results instead, and tell the user a vision model is needed to look at it.)"
    fun strip(messages: List<ApiMessage>): List<ApiMessage> = messages.map { m ->
        if (m.images.isNullOrEmpty()) m else m.copy(images = null, content = listOfNotNull(m.content?.takeIf { it.isNotBlank() }, NOTE).joinToString("\n"))
    }
}
