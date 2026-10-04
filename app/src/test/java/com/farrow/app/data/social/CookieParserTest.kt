package com.farrow.app.data.social

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieParserTest {
    private val dom = listOf("x.com", "twitter.com")
    private val bare = listOf("auth_token", "ct0")

    @Test fun header() = assertEquals(mapOf("auth_token" to "aa", "ct0" to "bb"), CookieParser.parse("Cookie: auth_token=aa; ct0=bb", dom, bare))

    @Test fun jsonArray() = assertEquals(mapOf("auth_token" to "aa", "ct0" to "bb"), CookieParser.parse(
        """[{"name":"auth_token","value":"aa","domain":".x.com"},{"name":"ct0","value":"bb","domain":"x.com"},{"name":"sid","value":"z","domain":".google.com"}]""", dom, bare))

    @Test fun jsonObject() = assertEquals(mapOf("auth_token" to "aa", "ct0" to "bb"), CookieParser.parse("""{"auth_token":"aa","ct0":"bb"}""", dom, bare))

    @Test fun netscape() = assertEquals(mapOf("auth_token" to "aa", "ct0" to "bb"), CookieParser.parse(
        "# Netscape HTTP Cookie File\n#HttpOnly_.x.com\tTRUE\t/\tTRUE\t0\tauth_token\taa\n.x.com\tTRUE\t/\tTRUE\t0\tct0\tbb\n.foo.com\tTRUE\t/\tFALSE\t0\tq\tw", dom, bare))

    @Test fun bareValues() = assertEquals(mapOf("auth_token" to "aa", "ct0" to "bb"), CookieParser.parse("aa\nbb", dom, bare))
}
