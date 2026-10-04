package com.farrow.app.ui.social

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import com.farrow.app.data.social.WebLoginCookies
import com.farrow.app.data.social.WebLoginSpec

/**
 * Full-screen WebView login for X / Facebook: the user logs in by hand (2FA / captcha are theirs). Cookies are
 * polled every second and on each page load; once the required ones exist, ALL site cookies are returned as a
 * `name=value; …` header and the activity closes. The caller imports them into TBP.
 */
class WebLoginActivity : ComponentActivity() {
    private lateinit var spec: WebLoginSpec
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            checkCookies()
            handler.postDelayed(this, 1_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spec = WebLoginCookies.spec(intent.getStringExtra(EXTRA_SITE).orEmpty()) ?: run { finish(); return }
        title = "Log in to ${spec.displayName}"

        status = TextView(this).apply {
            text = "Log in to ${spec.displayName}. This closes automatically once you're logged in."
            setPadding(32, 24, 32, 24)
        }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() } }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancel)
        }
        web = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = WebLoginCookies.chromeUserAgent(WebSettings.getDefaultUserAgent(this@WebLoginActivity))
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { checkCookies() }
            override fun onPageFinished(view: WebView?, url: String?) { checkCookies() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { setResult(Activity.RESULT_CANCELED); finish() }
            }
        })
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(spec.loginUrl)
        handler.postDelayed(poll, 1_000)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    private fun checkCookies() {
        if (finished || !::spec.isInitialized) return
        val cm = CookieManager.getInstance()
        val cookies = WebLoginCookies.merge(spec.cookieUrls.map { cm.getCookie(it) })
        val missing = WebLoginCookies.missing(cookies, spec)
        if (missing.isNotEmpty()) return
        finished = true
        handler.removeCallbacks(poll)
        cm.flush()
        status.text = "✅ Logged in — importing ${cookies.size} cookies…"
        val header = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            // Only the internal browser (TBP) keeps the session.
            cm.removeAllCookies(null); cm.flush()
            runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COOKIES, header).putExtra(EXTRA_SITE, spec.site))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }

    data class Request(val site: String, val clearAfter: Boolean)

    /** Result: the `name=value; …` cookie header, or null when cancelled. */
    class Contract : ActivityResultContract<Request, String?>() {
        override fun createIntent(context: Context, input: Request): Intent =
            Intent(context, WebLoginActivity::class.java).putExtra(EXTRA_SITE, input.site).putExtra(EXTRA_CLEAR, input.clearAfter)
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_COOKIES) else null
    }

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_CLEAR = "clear_after"
        private const val EXTRA_COOKIES = "cookies"
    }
}
