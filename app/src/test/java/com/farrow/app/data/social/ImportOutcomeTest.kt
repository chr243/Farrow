package com.farrow.app.data.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ImportOutcomeTest {
    private fun parse(s: String) = ImportOutcome.parse(Json.parseToJsonElement(s).jsonObject)

    @Test fun `logged in from the bridge verdict, no eval needed`() {
        val o = parse("""{"present":["auth_token","ct0"],"missing":[],"url":"https://x.com/home","logged_in":true,"key_cookies_ok":true,"daemon_down":false}""")
        assertEquals(LoginState.LOGGED_IN, o.status("X").state)
        assertFalse(o.daemonDown)
    }

    @Test fun `redirect to the login flow means rejected cookies`() {
        val st = parse("""{"present":["auth_token","ct0"],"missing":[],"url":"https://x.com/i/flow/login","logged_in":false,"key_cookies_ok":true}""").status("X")
        assertEquals(LoginState.LOGGED_OUT, st.state)
        assertTrue(st.reason, st.reason.contains("/i/flow/login"))
    }

    @Test fun `unknown url falls back to the cookie store`() {
        assertEquals(LoginState.LOGGED_IN, parse("""{"present":["auth_token"],"missing":[],"url":null,"logged_in":null,"key_cookies_ok":true}""").status("X").state)
        val st = parse("""{"present":[],"missing":["auth_token"],"url":null,"logged_in":null,"key_cookies_ok":false}""").status("X")
        assertEquals(LoginState.LOGGED_OUT, st.state)
        assertTrue(st.reason.contains("auth_token"))
    }

    @Test fun `daemon down carries the log`() {
        val o = parse("""{"daemon_down":true,"daemon_log":"Firefox process died (rc=1)","error":"TBP daemon did not listen"}""")
        assertTrue(o.daemonDown)
        assertEquals("Firefox process died (rc=1)", o.daemonLog)
        assertEquals("TBP daemon did not listen", o.error)
    }
}
