package github.vega.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
     * Injected on every page finish. A MutationObserver re-arms a 700 ms
     * debounce on each DOM mutation; when the debounce elapses with
     * `document.readyState == 'complete'`, one `requestAnimationFrame`
     * round-trip confirms the page actually painted, and the page records
     * the moment in `window.__vegaStableAt`. The native side watches that
     * timestamp with the cheap [STABLE_QUERY] below and only then pays for a
     * full DOM harvest.
     *
     * Deliberately one-shot per quiet period: after a successful signal the
     * debounce does NOT re-arm itself — only a new DOM mutation does — so a
     * quiet page signals once and then stays silent instead of stamping a
     * fresh timestamp every 700 ms forever (which would drag the native
     * side back into harvest-on-every-poll). A retry is scheduled only
     * while `document.readyState` is still incomplete.
     *
     * (A `@JavascriptInterface` bridge would be the textbook signal path,
     * but the offline API stubs do not carry `addJavascriptInterface`, so
     * the flag + state query uses only already-stubbed APIs.)
     */
    private const val STABLE_JS = "(function(){" +
        "if(window.__vegaStableInstalled){try{window.__vegaStableArm();}catch(e){}return;}" +
        "window.__vegaStableInstalled=true;" +
        "window.__vegaStableAt=0;" +
        "var timer=null;" +
        "function arm(){" +
        "if(timer){clearTimeout(timer);}" +
        "timer=setTimeout(function(){timer=null;" +
        "try{requestAnimationFrame(function(){signal();});}catch(e){signal();}" +
        "},700);}" +
        "function signal(){" +
        "try{if(document.readyState==='complete'){window.__vegaStableAt=Date.now();}" +
        "else{arm();}}catch(e){}" +
        "};" +
        "window.__vegaStableArm=arm;" +
        "try{" +
        "new MutationObserver(function(){arm();})" +
        ".observe(document.documentElement,{childList:true,subtree:true,attributes:true,characterData:true});" +
        "}catch(e){}" +
        "arm();" +
        "})();"

    /**
     * Cheap stability state query: returns the observer-maintained
     * `window.__vegaStableAt` timestamp (or 0). Runs on the old poll's
     * cadence but costs a number read, not a DOM serialization.
     */
    private const val STABLE_QUERY = "window.__vegaStableAt||0"

    /**
     * Load [url] in a WebView, wait for JS challenges to settle, and return
     * the final HTML + cookies. Returns null when WebView is unavailable
     * (no Context wired). Blocks the calling worker thread up to [timeoutMs].
     *
     * Delegates to [fetchStable]; kept so every existing caller keeps working.
     */
    fun fetch(url: String, timeoutMs: Long, token: CancellationToken?): Result? =
        fetchStable(url, timeoutMs, token)

    /**
     * Like [fetch], but stability detection is observer-driven instead of
     * blind polling: a MutationObserver watches the DOM and re-arms a 700 ms
     * debounce on every mutation; when the debounce elapses with
     * `document.readyState == 'complete'` and no DOM mutation for 700 ms,
     * the page records a stability timestamp in `window.__vegaStableAt`
     * (a `@JavascriptInterface` bridge would need an Android API the offline
     * stubs do not carry, so the native side reads the flag with the
     * already-stubbed `evaluateJavascript` state query instead).
     *
     * The old code re-serialized the whole DOM every 800/1200 ms from the
     * main thread regardless of page activity; now a cheap number query
     * runs on that cadence and the expensive DOM harvest happens only when
     * the observer reports the page stable — quiet pages finish fast while
     * busy challenge pages keep getting time until the hard [timeoutMs]
     * deadline.
     *
     * Hard guarantees are unchanged: safe-URL gating, cookie harvesting, the
     * [timeoutMs] hard stop (awaited directly, no extra 4 s tail), immediate
     * teardown on cancellation, and no foreign interrupt state swallowed.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetchStable(url: String, timeoutMs: Long, token: CancellationToken?): Result? {
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
                settings.userAgentString = Web.nextUa()
                // Skip images: we only need the DOM + cookies, and this is far faster.
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                } catch (ignored: Throwable) {
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                // Declared up front: finish()/finalizeNow() reference them, and
                // Kotlin local functions cannot see vals declared after them.
                var hardStop: Runnable? = null
                var stabilityCheck: Runnable? = null
                var cancelReg: CancellationToken.Registration? = null
                // Throttles DOM harvests when stability signals arrive in bursts.
                var lastHarvestAt = 0L
                // Last stability timestamp already acted on — only a NEWER
                // signal triggers another harvest.
                var lastSeenStableAt = 0L
                // scheduleStabilityCheck and onStableSignal call each other,
                // and Kotlin local functions cannot forward-reference — so
                // the signal handler lives behind this var, assigned after
                // scheduleStabilityCheck is declared.
                var onStableSignal: () -> Unit = {}

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
                    // The stability check and the hard stop have both served
                    // their purpose: drop them so they do not keep the
                    // (destroyed) WebView and this whole closure alive on the
                    // main thread's queue.
                    stabilityCheck?.let { main.removeCallbacks(it) }
                    hardStop?.let { main.removeCallbacks(it) }
                    cancelReg?.close()
                    cancelReg = null
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


                /**
                 * Harvests the DOM once and finishes — unless a
                 * NON-interactive JS challenge is still resolving with budget
                 * left, in which case [onSettled] reports `false` and the
                 * caller keeps watching instead of finishing. [force] (the
                 * hard stop / cancellation path) finishes no matter what the
                 * DOM holds.
                 */
                fun harvestThenFinish(force: Boolean, onSettled: (finished: Boolean) -> Unit = {}) {
                    if (finalized.get()) {
                        return
                    }
                    // BUGFIX: finish() can destroy the WebView between the
                    // `finalized` check above and this call; evaluateJavascript
                    // on a destroyed WebView throws on the main thread. Treat
                    // that narrow race as "already finished" instead of
                    // crashing.
                    try {
                        wv.evaluateJavascript("document.documentElement.outerHTML") { value ->
                            if (finalized.get()) {
                                return@evaluateJavascript
                            }
                            val html = decode(value)
                            val chal = Web.looksBlocked(html)
                            val cap = looksCaptcha(html)
                            val timedOut = System.currentTimeMillis() > deadline ||
                                (token != null && token.isCancelled)
                            // Keep waiting while a NON-interactive JS challenge
                            // is still resolving and we have budget left.
                            if (!force && chal && !cap && !timedOut) {
                                onSettled(false)
                                return@evaluateJavascript
                            }
                            finish(html, chal, cap)
                            onSettled(true)
                        }
                    } catch (ignored: Throwable) {
                        onSettled(false)
                    }
                }

                /**
                 * Cheap stability state query: reads the
                 * `window.__vegaStableAt` timestamp the injected
                 * MutationObserver maintains, instead of re-serializing the
                 * whole DOM like the old poll did. A fresh (newer than any
                 * acted-on) signal harvests immediately; a long-quiet page
                 * with no signal at all (a static challenge DOM) gets a
                 * backstop harvest so a resolved challenge is never missed.
                 */
                fun scheduleStabilityCheck(delayMs: Long) {
                    stabilityCheck?.let { main.removeCallbacks(it) }
                    val check = Runnable {
                        if (finalized.get()) {
                            return@Runnable
                        }
                        try {
                            wv.evaluateJavascript(STABLE_QUERY) { value ->
                                if (finalized.get()) {
                                    return@evaluateJavascript
                                }
                                val stableAt = decode(value).toLongOrNull() ?: 0L
                                val now = System.currentTimeMillis()
                                when {
                                    stableAt > lastSeenStableAt -> {
                                        lastSeenStableAt = stableAt
                                        onStableSignal()
                                    }
                                    now - lastHarvestAt > 10000L -> onStableSignal()
                                    else -> scheduleStabilityCheck(1200L)
                                }
                            }
                        } catch (ignored: Throwable) {
                        }
                    }
                    stabilityCheck = check
                    main.postDelayed(check, delayMs)
                }
                // Assigned here, after scheduleStabilityCheck is declared: the two
                // call each other, and Kotlin local functions cannot
                // forward-reference. Bursty stability signals are throttled —
                // the DOM harvest is the expensive part — and after a
                // declined harvest (a non-interactive challenge still
                // resolving with budget left) one delayed check is kept as a
                // backstop; the observer itself signals again on the next
                // mutation. A finished fetch never re-arms anything.
                onStableSignal = signal@ {
                    if (finalized.get()) {
                        return@signal
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastHarvestAt < 1000L) {
                        return@signal
                    }
                    lastHarvestAt = now
                    harvestThenFinish(force = false) { finished ->
                        if (!finished && !finalized.get()) {
                            scheduleStabilityCheck(1500L)
                        }
                    }
                }

                /**
                 * Hard stop: finalize with whatever the DOM holds right now.
                 * Shared with the cancellation path below: stopping the run
                 * must end the fetch NOW, not at some later timer tick.
                 */
                fun finalizeNow() {
                    harvestThenFinish(force = true)
                }

                wv.webViewClient = object : WebViewClient() {
                    /**
                     * A renderer crash (OOM in the renderer is common on the
                     * low-RAM devices this feature targets) must not kill the
                     * app process: the platform default returns false, which
                     * tells the system to take the whole app down. Settle the
                     * fetch as a failure instead.
                     *
                     * `RenderProcessGoneDetail` is API 26+; referencing the
                     * type in an override signature is safe on all API levels —
                     * the method is simply never called below 26.
                     */
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        try {
                            view?.destroy()
                        } catch (ignored: Throwable) {
                        }
                        finish("", chalIn = false, capIn = false)
                        return true
                    }

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
                            // Same teardown finish() performs: without it
                            // the hard stop, the stability check and the
                            // cancellation registration leak past this
                            // early exit.
                            stabilityCheck?.let { main.removeCallbacks(it) }
                            hardStop?.let { main.removeCallbacks(it) }
                            cancelReg?.close()
                            cancelReg = null
                            try {
                                view?.destroy()
                            } catch (ignored: Throwable) {
                            }
                            done.countDown()
                            return
                        }
                        // Arm the stability observer: a MutationObserver re-arms
                        // a 700 ms debounce on every DOM mutation; when the
                        // page is fully loaded and quiet for 700 ms, the page
                        // records window.__vegaStableAt, which the cheap
                        // state query below watches.
                        try {
                            wv.evaluateJavascript(STABLE_JS, null)
                        } catch (ignored: Throwable) {
                        }
                        // Start the cheap state query. It re-arms itself until
                        // the fetch finalizes; the hard stop and the
                        // cancellation path both end it. lastHarvestAt starts
                        // now so the no-signal backstop below only fires after
                        // a genuine 10 s of quiet, not on the first check.
                        lastHarvestAt = System.currentTimeMillis()
                        scheduleStabilityCheck(1200L)
                    }
                }

                // Hard stop: if nothing ever settles, finalize with whatever the
                // DOM holds right now. Shared with the cancellation path below:
                // stopping the run must end the fetch NOW, not at some later
                // timer tick (#20 — the deadline itself is the stop; the old
                // code waited timeoutMs + 4000).
                val stop = Runnable { finalizeNow() }
                hardStop = stop
                main.postDelayed(stop, timeoutMs)

                // Immediate cancellation: the hard stop may be far away, so a
                // cancelled token finalizes the fetch right now.
                cancelReg = token?.onCancel(Runnable {
                    main.post { finalizeNow() }
                })

                wv.loadUrl(url, Web.humanHeaders())
            } catch (t: Throwable) {
                // A half-built WebView must not leak: if setup threw before
                // the hard stop was scheduled, nothing else will ever tear
                // this WebView down. (If the throw came after the hard stop
                // was armed, that Runnable finalizes and destroys it; a
                // second destroy() here is a caught no-op.)
                try {
                    holder[0]?.destroy()
                } catch (ignored: Throwable) {
                }
                holder[0] = null
                done.countDown()
            }
        }

        try {
            // #20: await the hard stop itself — the old code added a 4 s tail
            // on top of the deadline that only ever delayed the return.
            done.await(timeoutMs + 750, TimeUnit.MILLISECONDS)
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
            h.contains("challenges.cloudflare.com/turnstile") ||
            // Inline widget markers without the script URLs above.
            h.contains("data-sitekey") || h.contains("grecaptcha.execute") ||
            h.contains("hcaptcha.execute") ||
            // Cloudflare managed / interactive challenge pages.
            h.contains("__cf_chl_") || h.contains("cf-challenge") ||
            h.contains("just a moment") && h.contains("cloudflare")
    }
}
