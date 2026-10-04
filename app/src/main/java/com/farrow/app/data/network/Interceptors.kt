package com.farrow.app.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Adds the OpenRouter attribution headers to every request. */
class OpenRouterHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder()
            .header("HTTP-Referer", OpenRouterApi.REFERER)
            .header("X-Title", OpenRouterApi.TITLE)
            .build()
    )
}

data class RateLimitSnapshot(
    val keyLabel: String,
    val model: String?,
    val statusCode: Int,
    val headers: RateLimitHeaders,
    val capturedAt: Long,
)

/** Latest X-RateLimit-* values seen per API key label. */
@Singleton
class RateLimitHeaderStore @Inject constructor() {
    private val _latest = MutableStateFlow<Map<String, RateLimitSnapshot>>(emptyMap())
    val latest: StateFlow<Map<String, RateLimitSnapshot>> = _latest.asStateFlow()

    fun record(snapshot: RateLimitSnapshot) {
        _latest.update { it + (snapshot.keyLabel to snapshot) }
    }

    fun get(keyLabel: String): RateLimitSnapshot? = _latest.value[keyLabel]
}

/**
 * Captures X-RateLimit-Limit / -Remaining / -Reset on every response. Also strips the internal
 * tag headers (model/key label) so they never reach OpenRouter.
 */
class RateLimitInterceptor(private val store: RateLimitHeaderStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val model = original.header(OpenRouterApi.TAG_MODEL)
        val keyLabel = original.header(OpenRouterApi.TAG_KEY_LABEL) ?: "unknown"
        val request = original.newBuilder()
            .removeHeader(OpenRouterApi.TAG_MODEL)
            .removeHeader(OpenRouterApi.TAG_KEY_LABEL)
            .build()
        val response = chain.proceed(request)
        val headers = RateLimitHeaders.parse { response.header(it) }
        if (!headers.isEmpty || response.code == 429) {
            store.record(RateLimitSnapshot(keyLabel, model, response.code, headers, System.currentTimeMillis()))
        }
        return response
    }
}
