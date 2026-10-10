package com.verdroid.app.data.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** OpenRouter OpenAI-compatible API. Base URL: https://openrouter.ai/api/v1/ */
interface OpenRouterApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Header(TAG_MODEL) model: String,
        @Header(TAG_KEY_LABEL) keyLabel: String,
        @Body request: ChatRequest,
    ): Response<ResponseBody>

    @GET("key")
    suspend fun key(
        @Header("Authorization") authorization: String,
        @Header(TAG_KEY_LABEL) keyLabel: String,
    ): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1/"
        /** Internal tag headers: read and stripped by [RateLimitInterceptor] before the request leaves the device. */
        const val TAG_MODEL = "X-Verdroid-Model"
        const val TAG_KEY_LABEL = "X-Verdroid-Key-Label"
        const val REFERER = "https://github.com/chr243/Verdroid"
        const val TITLE = "Verdroid"
    }
}
