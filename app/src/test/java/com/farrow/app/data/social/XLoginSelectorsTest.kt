package com.farrow.app.data.social

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** x.json v3 login script + the DomFinder JS (run in jsdom when node + jsdom are available). */
class XLoginSelectorsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val x: SiteConfig by lazy {
        val f = listOf("src/main/assets/selectors/x.json", "app/src/main/assets/selectors/x.json").map(::File).first { it.exists() }
        json.decodeFromString(SiteConfig.serializer(), f.readText())
    }

    private fun step(label: String) = x.loginSteps.first { it.label?.contains(label) == true }
    private fun js(re: String) = Regex(re, RegexOption.IGNORE_CASE) // these patterns are JS/Java compatible

    @Test fun `v3 login script structure`() {
        assertTrue(x.version >= 3)
        val labels = x.loginSteps.map { it.label.orEmpty() }
        val cookie = labels.indexOfFirst { it.contains("cookie banner (refuse") }
        val user = labels.indexOfFirst { it.contains("Email or username") }
        assertTrue("cookie banner step must come before the username step", cookie in 1 until user)
        x.loginSteps.forEach { s ->
            if (s.action == "clickText") assertTrue("clickText needs match/text: ${s.label}", s.match != null || s.text != null)
            (listOfNotNull(s.selector) + s.selectors).forEach { k -> assertTrue("unknown selector key $k", x.selectors.containsKey(k)) }
            s.match?.let { js(it) }; s.exclude?.let { js(it) }
        }
        assertTrue(x.sessionExpiredUrlPatterns.contains("/i/jf/onboarding"))
    }

    @Test fun `button and label regexes`() {
        val next = step("click Next")
        val nextRe = js(next.match!!); val ex = js(next.exclude!!)
        listOf("Next", "Continue", "Suivant", "continuer").forEach { assertTrue(it, nextRe.containsMatchIn(it)) }
        listOf("Continue with Google", "Continue with phone", "Continuer avec Apple").forEach { assertTrue(it, !nextRe.containsMatchIn(it) || ex.containsMatchIn(it)) }
        val login = js(step("click Log in").match!!)
        listOf("Log in", "Se connecter").forEach { assertTrue(it, login.containsMatchIn(it)) }
        assertFalse(login.containsMatchIn("Log in with Google"))
        val user = js(step("Email or username").match!!)
        listOf("Email or username", "Phone, email, or username", "Phone, email or username", "E-mail ou nom d'utilisateur").forEach { assertTrue(it, user.containsMatchIn(it)) }
        val refuse = js(step("cookie banner (refuse").match!!)
        assertTrue(refuse.containsMatchIn("Refuse non-essential cookies"))
        assertFalse(refuse.containsMatchIn("Accept all cookies"))
    }

    private fun nodeModules(): File? = listOfNotNull(System.getenv("FARROW_JSDOM_DIR"), "/tmp/domtest")
        .map { File(it, "node_modules") }.firstOrNull { File(it, "jsdom").isDirectory }

    @Test fun `DomFinder finds the X onboarding elements in jsdom`() {
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.canExecute() }
        val modules = nodeModules()
        assumeTrue("node + jsdom not available", node != null && modules != null)
        val user = step("Email or username")
        val next = step("click Next")
        val exprs = buildJsonObject {
            put("refuse", DomFinder.findText(step("cookie banner (refuse").match!!))
            put("refuseText", "B_marked_text")
            put("next", DomFinder.findText(next.match!!, next.exclude))
            put("nextText", "B_marked_text")
            put("user", DomFinder.findInput(user.match, x.sel("loginUsername"), true))
            put("userName", "B_marked_name")
            put("set", DomFinder.setMarked("ann"))
            put("value", "document.querySelector('input[name=text]').value + ':' + window.__inputs")
            put("shadow", DomFinder.findText("^Suivant$"))
            put("iframe", DomFinder.findInput("^(Password|Mot de passe)$", null, false))
            put("deepExists", DomFinder.exists("input[name=\"password\"]"))
            put("missing", DomFinder.findText("^Nope$"))
        }
        val html = """<html><body>
            <div id="banner"><span>Did someone say … cookies?</span><div role="button"><span>Accept all cookies</span></div>
              <div role="button"><span>Refuse non-essential cookies</span></div></div>
            <div role="dialog"><div role="button"><span>Continue with Google</span></div><div role="button">Continue with Apple</div>
              <label><div><span>Email or username</span></div><div><input name="text" autocomplete="username" type="text"></div></label>
              <button role="button"><div><span>Continue</span></div></button>
              <div id="host"></div><iframe id="fr"></iframe></div></body></html>"""
        val script = """
            const {JSDOM}=require('jsdom');
            const dom=new JSDOM(${JsonPrimitive(html)},{runScripts:'outside-only'});
            const w=dom.window; w.__farrowAssumeVisible=true; w.__inputs=0;
            const s=w.document.getElementById('host').attachShadow({mode:'open'}); s.innerHTML='<button>Suivant</button>';
            const fd=w.document.getElementById('fr').contentDocument; fd.body.innerHTML='<input name="password" type="password" placeholder="Mot de passe">';
            w.document.querySelector('input[name=text]').addEventListener('input',()=>w.__inputs++);
            const E=$exprs; const out={};
            const markedText="(()=>{const e=document.querySelector('[data-farrow-target]');return e?e.textContent.trim():null})()";
            const markedName="(()=>{const e=document.querySelector('[data-farrow-target]');return e?e.getAttribute('name'):null})()";
            for(const k of Object.keys(E)){const v=E[k]==='B_marked_text'?markedText:E[k]==='B_marked_name'?markedName:E[k];
              try{out[k]=w.eval(v)}catch(e){out[k]='ERR '+e.message}}
            console.log(JSON.stringify(out));
        """.trimIndent()
        val f = tmp.newFile("t.js").apply { writeText(script) }
        val pb = ProcessBuilder(node!!.absolutePath, f.absolutePath).redirectErrorStream(true)
        pb.environment()["NODE_PATH"] = modules!!.absolutePath
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        val o = Json.parseToJsonElement(out.lines().last { it.startsWith("{") }).jsonObject
        fun g(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        assertEquals(out, "top", g("refuse")); assertEquals("Refuse non-essential cookies", g("refuseText"))
        assertEquals(out, "top", g("next")); assertEquals("Continue", g("nextText"))
        assertEquals(out, "top", g("user")); assertEquals("text", g("userName"))
        assertEquals("ok", g("set")); assertEquals("ann:1", g("value"))
        assertEquals(out, "deep", g("shadow"))
        assertEquals(out, "deep", g("iframe"))
        assertEquals("true", g("deepExists"))
        assertEquals("no", g("missing"))
    }
}
