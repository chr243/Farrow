package com.verdroid.app.data.network

import org.junit.Assert.*
import org.junit.Test

class RateLimitParsingTest {
    @Test fun `parses X-RateLimit headers with unix ms reset`() {
        val h = mapOf("X-RateLimit-Limit" to "50", "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1759500000000")
        val parsed = RateLimitHeaders.parse { h[it] }
        assertEquals(50, parsed.limit)
        assertEquals(0, parsed.remaining)
        assertEquals(1759500000000L, parsed.resetAtMs)
        assertFalse(parsed.isEmpty)
    }

    @Test fun `normalises reset given in seconds and tolerates missing headers`() {
        val parsed = RateLimitHeaders.parse { if (it == "X-RateLimit-Reset") "1759500000" else null }
        assertEquals(1759500000000L, parsed.resetAtMs)
        assertNull(parsed.limit)
        assertTrue(RateLimitHeaders.parse { null }.isEmpty)
    }

    @Test fun `detects rate limit error body delivered with HTTP 200`() {
        val body = """{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-min","metadata":{"error_type":"rate_limit_exceeded"}}}"""
        val e = ApiErrorDetector.detect(200, body)!!
        assertEquals(ApiErrorKind.RATE_LIMIT, e.kind)
        assertEquals(429, e.code)
        assertEquals("rate_limit_exceeded", e.errorType)
    }

    @Test fun `detects error inside choices`() {
        val body = """{"id":"x","choices":[{"index":0,"error":{"code":429,"message":"Provider returned error","metadata":{"error_type":"rate_limit_exceeded"}},"finish_reason":"error"}]}"""
        assertEquals(ApiErrorKind.RATE_LIMIT, ApiErrorDetector.detect(200, body)!!.kind)
    }

    @Test fun `classifies http status codes`() {
        assertEquals(ApiErrorKind.RATE_LIMIT, ApiErrorDetector.detect(429, "Too Many Requests")!!.kind)
        assertEquals(ApiErrorKind.PAYMENT, ApiErrorDetector.detect(402, """{"error":{"code":402,"message":"Insufficient credits"}}""")!!.kind)
        assertEquals(ApiErrorKind.UPSTREAM, ApiErrorDetector.detect(503, null)!!.kind)
        assertEquals(ApiErrorKind.AUTH, ApiErrorDetector.detect(401, """{"error":{"code":401,"message":"No auth"}}""")!!.kind)
    }

    @Test fun `normal completion is not an error and parses tool calls`() {
        val body = """{"model":"qwen/qwen3-coder:free","choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"list_dir","arguments":"{\"path\":\".\"}"}}]},
            "finish_reason":"tool_calls"}]}"""
        assertNull(ApiErrorDetector.detect(200, body))
        val p = CompletionParser.parse(body)!!
        assertEquals(1, p.toolCalls.size)
        assertEquals("list_dir", p.toolCalls[0].name)
        assertEquals("{\"path\":\".\"}", p.toolCalls[0].argumentsJson)
        assertNull(p.content)
    }

    @Test fun `key endpoint parsed defensively`() {
        val full = """{"data":{"label":"k","usage":0.5,"limit":null,"is_free_tier":true,"rate_limit":{"requests":50,"interval":"10s"},
            "free_model_daily_requests":{"used":950,"limit":1000}}}"""
        val q = KeyInfoParser.parse(full, "main", 1000, 0)!!
        assertEquals(50, q.remaining)
        assertEquals(1000, q.limit)
        assertEquals(50, q.rateLimitRequests)
        assertEquals(false, q.estimated)

        val minimal = KeyInfoParser.parse("""{"data":{}}""", "main", 1000, 12)!!
        assertEquals(988, minimal.remaining)
        assertTrue(minimal.estimated)
    }
}
