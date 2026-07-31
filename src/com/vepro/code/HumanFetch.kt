package com.vepro.code

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener

/**
 * "Human mode" page loader.
 *
 * A bare HttpURLConnection can never pass a JavaScript anti-bot wall (Cloudflare
 * "Just a moment…", generic JS challenges) because there is no JS engine to run
 * the check. This class loads the page in a REAL [WebView] on the main
 * thread — a genuine browser engine — lets its JavaScript execute and clear the
 * challenge exactly as it would for a person, then harvests the resulting HTML
 * and the clearance cookies. Those cookies are cached per-host so the ordinary
 * [Web] fetch and `download_file` can replay them and inherit the solved session.
 *
 * Honest limits: challenges that require a human to actually click images
 * (interactive reCAPTCHA / hCaptcha / Turnstile) cannot be solved automatically
 * here — those are reported back so the agent can tell the user instead of
 * silently spinning.
 */
object HumanFetch {

    @Volatile
    private var appContext: Context? = null

    private val COOKIES = ConcurrentHashMap<String, String>()

    /** Wire up an application Context once (called from Activity/Service onCreate). */
    fun init(ctx: Context?) {
        if (appContext == null && ctx != null) {
            appContext = ctx.applicationContext
        }
    }

    fun available(): Boolean = appContext != null

    /** Cookies harvested for a host, for replay on a plain HttpURLConnection. */
    fun cookiesFor(host: String?): String? {
        if (host.isNullOrEmpty()) {
            return null
        }
        var cookie = COOKIES[host]
        if (cookie.isNullOrEmpty() && appContext != null) {
            try {
                cookie = CookieManager.getInstance().getCookie("https://$host/")
            } catch (ignored: Throwable) {
            }
        }
        return cookie
    }

    class Result internal constructor(
        html: String?,
        val finalUrl: String?,
        val blocked: Boolean,
        val interactiveCaptcha: Boolean
    ) {
        val html: String = html ?: ""
    }

    /**
     * Load [url] in a WebView, wait for JS challenges to settle, and return
     * the final HTML + cookies. Returns null when WebView is unavailable
     * (no Context wired). Blocks the calling worker thread up to [timeoutMs].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(url: String, timeoutMs: Long, token: CancellationToken?): Result? {
        try {
            NetworkPolicy.requireSafeHttps(url)
        } catch (blocked: Exception) {
            return null
        }
        val ctx = appContext ?: return null

        val out = AtomicReference<Result?>(null)
        val finalized = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val holder = arrayOfNulls<WebView>(1)

        main.post {
            try {
                val wv = WebView(ctx)
                holder[0] = wv
                val settings = wv.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.userAgentString = Web.UA
                // Skip images: we only need the DOM + cookies, and this is far faster.
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                } catch (ignored: Throwable) {
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val pollHolder = arrayOfNulls<Runnable>(1)

                fun schedulePoll(delayMs: Long) {
                    pollHolder[0]?.let { main.postDelayed(it, delayMs) }
                }

                /** Harvest cookies for the host of [finalUrl] into the cache. */
                fun harvestCookies(finalUrl: String) {
                    try {
                        val host = URL(finalUrl).host
                        val cookie = CookieManager.getInstance().getCookie(finalUrl)
                        if (!cookie.isNullOrEmpty()) {
                            COOKIES[host] = cookie
                        }
                        CookieManager.getInstance().flush()
                    } catch (ignored: Throwable) {
                    }
                }

                fun tearDown(target: WebView) {
                    try {
                        target.stopLoading()
                        target.destroy()
                    } catch (ignored: Throwable) {
                    }
                }

                fun finish(htmlIn: String, chalIn: Boolean, capIn: Boolean) {
                    if (!finalized.compareAndSet(false, true)) {
                        return
                    }
                    var html = htmlIn
                    var chal = chalIn
                    var cap = capIn
                    var finalUrl: String = url
                    try {
                        finalUrl = wv.url ?: url
                    } catch (ignored: Throwable) {
                    }
                    if (!safeUrl(finalUrl)) {
                        html = ""
                        chal = true
                        cap = false
                        finalUrl = url
                    }
                    harvestCookies(finalUrl)
                    out.set(Result(html, finalUrl, chal, cap))
                    tearDown(wv)
                    done.countDown()
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = request == null || !safeUrl(request.url.toString())

                    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        nextUrl: String?
                    ): Boolean = !safeUrl(nextUrl)

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request == null || safeUrl(request.url.toString())) {
                            return null
                        }
                        return blockedResponse()
                    }

                    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        resourceUrl: String?
                    ): WebResourceResponse? =
                        if (safeUrl(resourceUrl)) null else blockedResponse()

                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (!safeUrl(u)) {
                            view?.stopLoading()
                            finalized.set(true)
                            try {
                                view?.destroy()
                            } catch (ignored: Throwable) {
                            }
                            done.countDown()
                            return
                        }
                        schedulePoll(800L)
                    }
                }

                pollHolder[0] = Runnable {
                    if (finalized.get()) {
                        return@Runnable
                    }
                    val timedOut = System.currentTimeMillis() > deadline ||
                        (token != null && token.isCancelled)
                    // BUGFIX: finish() can destroy the WebView between the
                    // `finalized` check above and this call; evaluateJavascript
                    // on a destroyed WebView throws on the main thread. Treat
                    // that narrow race as "already finished" instead of crashing.
                    try {
                        wv.evaluateJavascript("document.documentElement.outerHTML") { value ->
                            val html = decode(value)
                            val chal = Web.looksBlocked(html)
                            val cap = looksCaptcha(html)
                            // Keep waiting while a NON-interactive JS challenge
                            // is still resolving and we have time budget left.
                            if (chal && !cap && !timedOut) {
                                schedulePoll(1200L)
                            } else {
                                finish(html, chal, cap)
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }

                // Hard stop: if nothing ever settles, finalize with whatever we have.
                main.postDelayed({
                    if (!finalized.get()) {
                        // BUGFIX: same destroy race as the poll above — never let a
                        // post-destroy evaluateJavascript crash the main thread.
                        try {
                            wv.evaluateJavascript("document.documentElement.outerHTML") { value ->
                                val html = decode(value)
                                if (finalized.compareAndSet(false, true)) {
                                    var finalUrl: String = url
                                    try {
                                        finalUrl = wv.url ?: url
                                    } catch (ignored: Throwable) {
                                    }
                                    harvestCookies(finalUrl)
                                    out.set(
                                        Result(
                                            html,
                                            finalUrl,
                                            Web.looksBlocked(html),
                                            looksCaptcha(html)
                                        )
                                    )
                                    tearDown(wv)
                                    done.countDown()
                                }
                            }
                        } catch (ignored: Throwable) {
                        }
                    }
                }, timeoutMs + 400)

                wv.loadUrl(url)
            } catch (t: Throwable) {
                done.countDown()
            }
        }

        try {
            done.await(timeoutMs + 4000, TimeUnit.MILLISECONDS)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Safety: make sure the WebView is torn down on the main thread.
        main.post {
            val wv = holder[0]
            if (wv != null && !finalized.get()) {
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (ignored: Throwable) {
                }
            }
        }
        return out.get()
    }

    private fun safeUrl(value: String?): Boolean = try {
        NetworkPolicy.requireSafeHttps(value)
        true
    } catch (blocked: Exception) {
        false
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    /** WebView delivers evaluateJavascript results as a JSON-encoded string. */
    private fun decode(value: String?): String {
        if (value == null || value == "null") {
            return ""
        }
        return try {
            JSONTokener(value).nextValue()?.toString() ?: ""
        } catch (t: Throwable) {
            value
        }
    }

    /** True when the page embeds an interactive click-CAPTCHA widget. */
    internal fun looksCaptcha(html: String?): Boolean {
        if (html.isNullOrEmpty()) {
            return false
        }
        val h = html.lowercase()
        return h.contains("g-recaptcha") || h.contains("h-captcha") ||
            h.contains("cf-turnstile") || h.contains("recaptcha/api.js") ||
            h.contains("hcaptcha.com/1/api.js") ||
            h.contains("challenges.cloudflare.com/turnstile")
    }
}
