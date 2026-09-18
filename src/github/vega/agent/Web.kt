package github.vega.agent

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Web search + page fetch for the agent's `web_search` / `web_fetch` tools.
 *
 * Requests are dressed as a real mobile Chrome navigation (UA + client hints +
 * fetch metadata + cookie jar) because bare HTTP clients are trivially
 * fingerprinted and blocked. When a plain fetch still hits a JavaScript
 * anti-bot wall, [HumanFetch] replays the page in a real WebView.
 */
object Web {

    // Current Chrome-on-Android UA — kept in sync with the sec-ch-ua hints below
    // so the request fingerprint looks like a real, up-to-date mobile browser.
    // A stale UA is itself an anti-bot tell, so this tracks a recent stable Chrome.
    internal const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    // The matching User-Agent Client Hints brand list. Shared by every request
    // (page fetch AND download) so the whole app presents one coherent fingerprint.
    internal const val SEC_CH_UA =
        "\"Chromium\";v=\"139\", \"Google Chrome\";v=\"139\", \"Not-A.Brand\";v=\"99\""

    /**
     * Small pool of current mobile-browser UAs. The plain HTTP path keeps the
     * single [UA] fingerprint for coherence, but the WebView human path
     * rotates through these: a challenge page that sees the exact same UA on
     * every attempt has one more reason to escalate.
     */
    private val UA_POOL = arrayOf(
        UA,
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 15; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 7a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )
    private val uaCursor = AtomicInteger(0)

    internal fun nextUa(): String {
        val n = uaCursor.getAndIncrement() and Int.MAX_VALUE
        return UA_POOL[n % UA_POOL.size]
    }

    /**
     * The headers a real mobile browser always sends on a navigation but a
     * bare WebView.loadUrl does not. Missing Accept-Language is one of the
     * cheapest bot tells there is; Upgrade-Insecure-Requests completes the
     * navigation fingerprint.
     */
    internal fun humanHeaders(): Map<String, String> = mapOf(
        "Accept-Language" to "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1"
    )

    /**
     * Daemon thread factory for the small internal pools (search race, HEAD
     * probes). Daemon so a stuck pool can never keep the process alive.
     */
    internal fun daemonFactory(name: String): ThreadFactory =
        ThreadFactory { work ->
            Thread(work, name).apply { isDaemon = true }
        }

    /** One parsed search result. */
    internal data class Hit(val title: String, val url: String, val snippet: String)

    /**
     * One web result, as DATA.
     *
     * The search tool has always known each result's title and url — it built them
     * into [Hit] and then flattened the lot into a numbered string. That string is
     * all the model ever needed, so nothing kept the structure, and the UI had
     * nothing to show but the query text.
     *
     * Keeping them lets the activity strip put the actual results behind the row
     * that produced them, each one openable in the browser.
     */
    class SearchResult(val title: String, val url: String) {

        fun host(): String = AgentEngine.hostOf(url)

        fun toJson(): JSONObject = JSONObject().put("title", title).put("url", url)

        companion object {
            fun fromJson(json: JSONObject?): SearchResult? {
                if (json == null) {
                    return null
                }
                val url = json.optStr("url", "").trimJava()
                if (url.isEmpty()) {
                    return null
                }
                val title = json.optStr("title", "").trimJava()
                return SearchResult(if (title.isEmpty()) url else title, url)
            }
        }
    }

    /** What one search produced: the text the model reads, and the results as data. */
    class SearchOutcome(val text: String, val results: List<SearchResult>)

    private val CHARSET_PARAM = Regex("charset\\s*=\\s*([\\w.:+-]+)", RegexOption.IGNORE_CASE)

    private val META_CHARSET = Regex(
        "<meta[^>]{0,400}?charset\\s*=\\s*[\"']?([\\w.:+-]+)",
        RegexOption.IGNORE_CASE
    )

    internal val MEDIA_EXT = Regex(
        "\\.(mp3|m4a|aac|flac|wav|ogg|opus|mp4|mkv|webm|mov|avi|m4v|3gp|apk|zip|rar|7z|tar|gz|pdf|docx?|xlsx?|pptx?|epub|jpg|jpeg|png|gif|webp|svg|bmp|torrent|iso|exe|dmg)(\\?|#|$)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Numeric character references (`&#1740;` / `&#x6CC;`) — the form
     * Persian/Arabic pages use most for non-ASCII text.
     */
    private val NUMERIC_REF = Regex("&#(?:[xX]([0-9a-fA-F]+)|([0-9]+));")

    init {
        // A process-wide cookie jar shared by every HttpURLConnection. Basic
        // anti-bot walls (e.g. Cloudflare) hand out a clearance cookie on the first
        // hit and expect it echoed on the next one; without a jar every request
        // looks brand-new and keeps getting challenged. Installed once, lazily.
        try {
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Makes a connection look like a real browser navigation rather than a bare
     * HTTP client: full Accept set, client-hint (sec-ch-ua*) and fetch-metadata
     * (sec-fetch-*) headers, language, and a plausible Referer. We deliberately
     * do NOT set Accept-Encoding — letting the JVM negotiate/inflate gzip itself
     * avoids handing back compressed bytes we'd fail to decode.
     */
    internal fun applyBrowserHeaders(c: HttpURLConnection, referer: String?, navigation: Boolean) {
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty(
            "Accept",
            if (navigation) {
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            } else {
                "*/*"
            }
        )
        c.setRequestProperty("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
        // Identity, not gzip: neither the streaming nor the careful fetch path
        // unwraps Content-Encoding, so a gzipped body would arrive as mojibake.
        // With no Accept-Encoding most servers already send identity; stating
        // it makes that a requirement, not a courtesy.
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("sec-ch-ua", SEC_CH_UA)
        c.setRequestProperty("sec-ch-ua-mobile", "?1")
        c.setRequestProperty("sec-ch-ua-platform", "\"Android\"")
        c.setRequestProperty("Upgrade-Insecure-Requests", "1")
        if (navigation) {
            c.setRequestProperty("Sec-Fetch-Dest", "document")
            c.setRequestProperty("Sec-Fetch-Mode", "navigate")
            c.setRequestProperty(
                "Sec-Fetch-Site",
                if (referer.isNullOrEmpty()) "none" else "same-origin"
            )
            c.setRequestProperty("Sec-Fetch-User", "?1")
        } else {
            c.setRequestProperty("Sec-Fetch-Dest", "empty")
            c.setRequestProperty("Sec-Fetch-Mode", "cors")
            c.setRequestProperty("Sec-Fetch-Site", "same-origin")
        }
        if (!referer.isNullOrEmpty()) {
            c.setRequestProperty("Referer", referer)
            try {
                val r = URL(referer)
                c.setRequestProperty("Origin", r.protocol + "://" + r.host)
            } catch (ignored: Exception) {
            }
        }
        // If a WebView "human" pass harvested clearance cookies for this host,
        // replay them here so the automated fetch inherits the solved session.
        try {
            val host = URL(c.url.toString()).host
            val jar = HumanFetch.cookiesFor(host)
            if (!jar.isNullOrEmpty()) {
                c.setRequestProperty("Cookie", jar)
            }
        } catch (ignored: Throwable) {
        }
    }

    // ---- search ------------------------------------------------------------

    fun search(query: String?): String = search(query, CancellationToken())

    /**
     * Text-only search, for every caller that just feeds the model.
     *
     * Delegates to [searchDetailed] so there is exactly one implementation and the
     * text a model sees can never disagree with the results the UI shows.
     */
    fun search(query: String?, token: CancellationToken): String =
        searchDetailed(query, token).text

    fun searchDetailed(query: String?, token: CancellationToken): SearchOutcome {
        if (query.isNullOrBlankJava()) {
            return SearchOutcome("ERROR: query is required", emptyList())
        }
        val cleaned = query.trimJava()
        // Synchronized: the engines below race on a pool and all append here.
        val diagnostics: MutableList<String> =
            java.util.Collections.synchronizedList(ArrayList<String>())
        try {
            token.throwIfCancelled()
            val encoded = URLEncoder.encode(cleaned, "UTF-8")

            // All engines race in parallel on a bounded daemon pool (#17): the
            // first non-empty result wins and the losers are cancelled. One
            // global ~40 s deadline covers every engine together — the old
            // serial chain took 110 s+ when each engine timed out alone.
            val engines = arrayOf(
                Triple("DuckDuckGo HTML", "https://html.duckduckgo.com/html/?q=$encoded", 0),
                Triple("DuckDuckGo Lite", "https://lite.duckduckgo.com/lite/?q=$encoded", 1),
                Triple("Bing", "https://www.bing.com/search?q=$encoded&count=10", 2),
                Triple("Mojeek", "https://www.mojeek.com/search?q=$encoded", 3)
            )
            var results: List<Hit> = emptyList()
            val pool = Executors.newFixedThreadPool(engines.size, daemonFactory("vega-search"))
            // One live-connection slot per engine: cancelling a future does
            // not unblock its HttpURLConnection (interrupts are ignored), so
            // losers are disconnected explicitly below.
            val live = Array(engines.size) { AtomicReference<HttpURLConnection?>() }
            try {
                val futures = engines.mapIndexed { index, (engineName, engineUrl, parser) ->
                    pool.submit(Callable {
                        trySearchEngine(engineName, engineUrl, parser, token, diagnostics, live[index])
                    })
                }
                val deadline = System.currentTimeMillis() + 40000L
                val pending = futures.toMutableList()
                while (pending.isNotEmpty() && results.isEmpty()) {
                    token.throwIfCancelled()
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        break
                    }
                    val it = pending.iterator()
                    while (it.hasNext() && results.isEmpty()) {
                        val future = it.next()
                        try {
                            val hits = future.get(Math.min(remaining, 1000L), TimeUnit.MILLISECONDS)
                            it.remove()
                            if (hits.isNotEmpty()) {
                                results = hits
                            }
                        } catch (timeout: TimeoutException) {
                            // Still running — keep waiting for it.
                        } catch (failed: Exception) {
                            it.remove()
                            rethrowIfCancelled(failed)
                        }
                    }
                }
                // Whoever is still running past the deadline (or past the
                // winner) is interrupted, its socket is disconnected, and the
                // pool is torn down. The disconnect is what makes this prompt:
                // HttpURLConnection ignores thread interrupts, so cancel(true)
                // alone would leave a loser hanging until its own 12 s / 18 s
                // timeouts. Disconnecting an already-finished connection is a
                // harmless no-op.
                for ((index, future) in futures.withIndex()) {
                    if (!future.isDone) {
                        try {
                            live[index].get()?.disconnect()
                        } catch (ignored: Exception) {
                        }
                    }
                    future.cancel(true)
                }
            } finally {
                pool.shutdownNow()
            }
            // Last resort: if every plain HTTP attempt came back blocked/empty, run
            // ONE real-browser (WebView) pass that can execute a JS anti-bot wall
            // and hand back the rendered results HTML to parse.
            if (results.isEmpty() && HumanFetch.available()) {
                results = trySearchEngineHuman(
                    "DuckDuckGo (human)",
                    "https://html.duckduckgo.com/html/?q=$encoded", 0, token, diagnostics
                )
            }
            token.throwIfCancelled()

            // Four engines race and their indexes overlap heavily: collapse
            // hits that are the same page under a different skin (trailing
            // slash, tracking params, http-vs-https) so the 8 rendered slots
            // are 8 different pages.
            val deduped = dedupeHits(results)
            results = deduped

            if (results.isEmpty()) {                val none = StringBuilder("No web results for: ").append(cleaned)
                    .append("\n(hint: simplify the query, remove site: operators, or try another language.)")
                // Snapshot: cancelled losers can still append to the shared
                // list after shutdownNow (their HttpURLConnection ignores the
                // interrupt until its own timeout), and iterating a
                // synchronizedList while it is being appended to throws.
                val diag = synchronized(diagnostics) { ArrayList(diagnostics) }
                if (diag.isNotEmpty()) {
                    none.append("\nSearch engines tried: ").append(join(diag))
                }
                return SearchOutcome(none.toString(), emptyList())
            }

            val output = StringBuilder("Web results for \"").append(cleaned).append("\":\n\n")
            val structured = ArrayList<SearchResult>()
            var shown = 0
            for (hit in results) {
                token.throwIfCancelled()
                if (shown >= 8) {
                    break
                }
                shown++
                output.append(shown).append(". ").append(hit.title).append('\n')
                if (hit.snippet.isNotEmpty()) {
                    output.append("   ").append(hit.snippet).append('\n')
                }
                output.append("   ").append(hit.url).append("\n\n")
                structured.add(
                    SearchResult(
                        if (hit.title.isBlankJava()) hit.url else hit.title.trimJava(),
                        hit.url
                    )
                )
            }
            return SearchOutcome(output.toString(), structured)
        } catch (cancelled: CancellationToken.CancelledException) {
            return SearchOutcome("CANCELLED: user stopped web search", emptyList())
        } catch (error: Exception) {
            if (token.isCancelled) {
                return SearchOutcome("CANCELLED: user stopped web search", emptyList())
            }
            return SearchOutcome(
                "ERROR: web_search failed: " + friendlyNetworkError(error), emptyList()
            )
        }
    }

    @Throws(CancellationToken.CancelledException::class)
    /**
     * Unwraps [CancellationToken.CancelledException] from a future's
     * [java.util.concurrent.ExecutionException] (or any wrapper) and
     * rethrows it; anything else stays swallowed as an engine failure.
     * Cancellation must always win over "engine failed".
     */
    private fun rethrowIfCancelled(e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is CancellationToken.CancelledException) {
                throw cause
            }
            cause = cause.cause
        }
    }

    private fun trySearchEngine(
        name: String,
        url: String,
        parser: Int,
        token: CancellationToken,
        diagnostics: MutableList<String>,
        liveConn: AtomicReference<HttpURLConnection?>? = null
    ): List<Hit> {
        try {
            val response = httpGetResult(url, 12000, 18000, 900000, token, liveConn = liveConn)
            if (response.code != 200) {
                diagnostics.add("$name HTTP ${response.code}")
                return emptyList()
            }
            if (looksBlocked(response.body)) {
                diagnostics.add("$name blocked")
                return emptyList()
            }
            val parsed = parseWith(parser, response.body)
            if (parsed.isEmpty()) {
                diagnostics.add("$name empty")
            }
            return parsed
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics.add(name + " " + friendlyNetworkError(error))
            return emptyList()
        }
    }

    /**
     * Same as [trySearchEngine] but loads the results page in a real WebView
     * (human mode), so a JavaScript anti-bot wall in front of the search engine
     * is cleared before the HTML is parsed. Used only as a last resort.
     */
    @Throws(CancellationToken.CancelledException::class)
    private fun trySearchEngineHuman(
        name: String,
        url: String,
        parser: Int,
        token: CancellationToken,
        diagnostics: MutableList<String>
    ): List<Hit> {
        try {
            token.throwIfCancelled()
            val human = HumanFetch.fetch(url, 20000, token)
            if (human == null || human.html.isEmpty()) {
                diagnostics.add("$name empty")
                return emptyList()
            }
            if (looksBlocked(human.html)) {
                diagnostics.add("$name blocked")
                return emptyList()
            }
            val parsed = parseWith(parser, human.html)
            if (parsed.isEmpty()) {
                diagnostics.add("$name empty")
            }
            return parsed
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            diagnostics.add(name + " " + friendlyNetworkError(error))
            return emptyList()
        }
    }

    private fun parseWith(parser: Int, html: String): List<Hit> = when (parser) {
        0 -> parseDuck(html)
        1 -> parseLite(html)
        2 -> parseBing(html)
        else -> parseMojeek(html)
    }

    // ---- page fetch --------------------------------------------------------

    fun fetch(url: String?): String = fetch(url, CancellationToken())

    fun fetch(url: String?, token: CancellationToken): String =
        fetch(url, token, null)

    /**
     * Fetches a page as readable text, reporting milestones through [progress].
     *
     * The default path is the streaming extractor ([WebFast.fetchFast]):
     * single-pass tokenize + readability scoring, first text in under a
     * second, hard-budgeted. Any failure falls back to the careful path
     * ([fetchCareful]) below, which keeps the old human-retry and full
     * link-section behavior.
     */
    fun fetch(url: String?, token: CancellationToken, progress: ((String) -> Unit)?): String =
        fetch(url, token, progress, WebFast.FetchOpts())

    /**
     * Fetches a page with explicit [opts] (`fast`, `max_chars`, and the
     * extraction `prompt`). Used by the `web_fetch` tool's tolerant args.
     */
    fun fetch(
        url: String?,
        token: CancellationToken,
        progress: ((String) -> Unit)?,
        opts: WebFast.FetchOpts
    ): String {
        if (url.isNullOrBlankJava()) {
            return "ERROR: url is required"
        }
        if (opts.fast) {
            try {
                token.throwIfCancelled()
                val digest = WebFast.fetchFast(url, token, opts)
                if (digest != null) {
                    progress?.invoke(Fa.WEB_PROG_EXTRACTING)
                    return formatPageDigest(digest)
                }
            } catch (cancelled: CancellationToken.CancelledException) {
                return "CANCELLED: user stopped web fetch"
            } catch (e: Exception) {
                // Fast path failed — fall through to the careful path, which
                // reports failures readably.
            }
        }
        return fetchCareful(url, token, progress)
    }

    /**
     * Renders a [WebFast.PageDigest] exactly like the careful path's output —
     * title, URL, body text, then the budgeted DOWNLOADABLE / LINKS sections —
     * so the model sees one stable shape whichever extractor ran.
     */
    internal fun formatPageDigest(digest: WebFast.PageDigest): String {
        val sb = StringBuilder()
        if (digest.title.isNotEmpty()) {
            sb.append("Title: ").append(digest.title).append('\n')
        }
        sb.append("URL: ").append(digest.url).append("\n\n")
        sb.append(digest.text)
        val base = sb.length
        val budget = 8000
        fun fits(line: String): Boolean = sb.length - base + line.length <= budget
        if (digest.downloadables.isNotEmpty()) {
            val header = "\n\n=== DOWNLOADABLE (pass one of these to download_file) ===\n"
            if (fits(header)) {
                sb.append(header)
                var shown = 0
                for (link in digest.downloadables) {
                    if (shown >= 25) {
                        break
                    }
                    val line = "• " + link.url +
                        (if (link.label.isNotEmpty()) "   — " + link.label else "") + "\n"
                    if (!fits(line)) {
                        break
                    }
                    sb.append(line)
                    shown++
                }
            }
        }
        if (digest.links.isNotEmpty()) {
            val header = "\n=== LINKS (open with web_fetch to keep browsing) ===\n"
            if (fits(header)) {
                sb.append(header)
                var shown = 0
                for (link in digest.links) {
                    if (shown >= 40) {
                        break
                    }
                    val line = "• " + (if (link.label.isEmpty()) "(link)" else link.label) +
                        "  →  " + link.url + "\n"
                    if (!fits(line)) {
                        break
                    }
                    sb.append(line)
                    shown++
                }
            }
        }
        return sb.toString()
    }

    /**
     * Fetches a page, reporting milestones through [progress]: connect start,
     * download size every 512 KB, the human-mode retry, and text extraction.
     * A 3 MB download or a 25-second WebView pass used to leave the trail
     * frozen with no hint anything was happening.
     *
     * The careful path: full download, then regex extraction. Slower than
     * [WebFast.fetchFast] but exhaustive — it is the fallback when the fast
     * path cannot vouch for a page.
     */
    private fun fetchCareful(
        url: String?,
        token: CancellationToken,
        progress: ((String) -> Unit)?
    ): String {
        if (url.isNullOrBlankJava()) {
            return "ERROR: url is required"
        }
        try {
            token.throwIfCancelled()
            val cleaned = Util.cleanUrl(url)
            progress?.invoke(Fa.WEB_PROG_CONNECTING)
            var response = httpGetResult(cleaned, 15000, 30000, 3000000, token) { kb ->
                progress?.invoke(Fa.WEB_PROG_DOWNLOADED.format(kb.toString()))
            }
            // A 429/503 WITH a Retry-After is the server asking to be asked
            // again later — honour it once (capped at 30 s) instead of
            // treating a patient server as a bot wall. Polite-client
            // behaviour, not challenge evasion: an interactive CAPTCHA still
            // surfaces as an error below.
            if ((response.code == 429 || response.code == 503) &&
                !response.retryAfter.isNullOrEmpty()
            ) {
                val waitMs = parseRetryAfterMs(response.retryAfter)
                if (waitMs in 1..30000) {
                    progress?.invoke(Fa.WEB_PROG_RETRY_AFTER.format((waitMs / 1000).toString()))
                    if (!sleepCancellable(waitMs, token)) {
                        token.throwIfCancelled()
                        response = httpGetResult(cleaned, 15000, 30000, 3000000, token) { kb ->
                            progress?.invoke(Fa.WEB_PROG_DOWNLOADED.format(kb.toString()))
                        }
                    } else {
                        token.throwIfCancelled()
                    }
                }
            }
            var page = response.body
            var finalUrl = response.finalUrl
            var httpBlocked = response.code == 403 || response.code == 503 || response.code == 429
            var humanOk = false // true once a WebView pass returns a clean page

            // If the plain fetch hit an anti-bot wall (challenge markup or a
            // 403/503/429), retry the page in a real browser engine: WebView runs
            // the challenge's JavaScript like a human would, clears it, and keeps
            // the clearance cookies for subsequent requests.
            if ((httpBlocked || looksBlocked(page)) && HumanFetch.available()) {
                token.throwIfCancelled()
                // Up to 25 seconds inside a WebView: say so, so the trail does
                // not look dead while the challenge's JavaScript runs.
                progress?.invoke(Fa.WEB_PROG_HUMAN)
                val human = HumanFetch.fetch(cleaned ?: "", 25000, token)
                if (human != null && human.html.isNotEmpty()) {
                    if (human.interactiveCaptcha) {
                        return "ERROR: this page is behind an interactive CAPTCHA (the kind " +
                            "that asks you to pick out images), which only a person can " +
                            "clear. Try a direct link to the content, or another site."
                    }
                    if (!looksBlocked(human.html)) {
                        page = human.html
                        finalUrl = human.finalUrl ?: finalUrl
                        httpBlocked = false
                        humanOk = true // WebView cleared the wall — trust this page
                    }
                }
            }

            if (!humanOk && (httpBlocked || response.code < 200 || response.code >= 300)) {
                if (looksBlocked(page)) {
                    return "ERROR: the site returned an anti-bot or access-check page even after a human-mode retry; try another result/site."
                }
                if (response.code < 200 || response.code >= 300) {
                    return "ERROR: web_fetch target returned HTTP " + response.code +
                        " at " + Util.redactUrl(response.finalUrl) +
                        statusHint(response.code, response.retryAfter)
                }
            }
            if (response.contentType.isNotEmpty() && !response.contentType.contains("text/") &&
                !response.contentType.contains("json") && !response.contentType.contains("xml") &&
                page.length < 200
            ) {
                return "ERROR: target is not a readable web page (" + response.contentType +
                    "). Use download_file for binary content."
            }
            if (looksBlocked(page)) {
                return "ERROR: the site returned an anti-bot or access-check page; try another result/site."
            }

            progress?.invoke(Fa.WEB_PROG_EXTRACTING)
            val readable = readableText(page)
            val title = extract(page, "<title[^>]*>(.*?)</title>")
            val output = StringBuilder()
            if (title != null) {
                output.append("Title: ").append(unescape(title.trimJava())).append('\n')
            }
            output.append("URL: ").append(finalUrl).append("\n\n")
            // 45k, not 18k. The old cap silently cut long articles roughly in
            // half BEFORE the model ever saw them — the reason a fetched page
            // could feel only skimmed. Links are appended after, so a
            // content-heavy page spends the budget on content.
            // ~38k of body text; ~8k is reserved for the DOWNLOADABLE / LINKS
            // sections below (#19) so text + links always fit the engine's
            // 48k tool-result cap, and the cap never cuts a URL mid-string.
            output.append(Util.truncate(readable, 38000))
            appendLinks(output, page, finalUrl, token, 8000)
            return output.toString()
        } catch (cancelled: CancellationToken.CancelledException) {
            return "CANCELLED: user stopped web fetch"
        } catch (error: Exception) {
            if (token.isCancelled) {
                return "CANCELLED: user stopped web fetch"
            }
            return "ERROR: web_fetch failed: " + friendlyNetworkError(error)
        }
    }

    /**
     * Extracts & resolves links from a page and appends two sections:
     * DOWNLOADABLE (direct media/file links, best candidates for download_file)
     * and LINKS (other navigable links) so the model can keep browsing.
     *
     * [budget] caps how many characters the two sections may add (#19): the
     * engine truncates tool results at 48k, and an unbounded link dump used
     * to push the body out or get cut mid-URL. Every line is measured before
     * it is appended, so a URL is never truncated halfway.
     */
    @Throws(CancellationToken.CancelledException::class)
    private fun appendLinks(
        sb: StringBuilder,
        html: String,
        baseUrl: String,
        token: CancellationToken,
        budget: Int = Int.MAX_VALUE
    ) {
        val base = sb.length
        fun fits(line: String): Boolean = sb.length - base + line.length <= budget
        val downloads = LinkedHashMap<String, String>()
        val links = LinkedHashMap<String, String>()

        // <a href="..."> ... </a>  (also honours the download attribute)
        //
        // Every insert below goes through [keepFirst], NOT Map.putIfAbsent:
        // putIfAbsent is API 24 and the floor is 23, so on Android 6 it throws
        // NoSuchMethodError — from inside web_fetch's happy path, where the
        // failure reads as "the page could not be parsed".
        // The label is bounded on purpose. With an unbounded reluctant `.*?`
        // every `<a href=` that never closes costs a scan to end-of-document,
        // so a 300 KB page with a few thousand unterminated anchors pegged the
        // tool thread for ~9 s — and `web_fetch` accepts 3 MB. Two kilobytes is
        // far more label than any real anchor carries.
        val anchor = Regex(
            "<a\\b([^>]*?)href=\"([^\"]+)\"([^>]*)>(.{0,2000}?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (match in anchor.findAll(html)) {
            token.throwIfCancelled()
            val pre = match.groupValues[1] + " " + match.groupValues[3]
            val href = match.groupValues[2].trimJava()
            var text = unescape(stripTags(match.groupValues[4])).trimJava()
                .replace(Regex("\\s+"), " ")
            val abs = resolveUrl(baseUrl, href) ?: continue
            val isDownload = pre.lowercase().contains("download") ||
                MEDIA_EXT.containsMatchIn(abs) ||
                text.contains("دانلود") || text.lowercase().contains("download")
            if (text.length > 90) {
                text = text.substring(0, 90) + "…"
            }
            if (isDownload) {
                keepFirst(downloads, abs, text)
            } else if (text.length > 1 || MEDIA_EXT.containsMatchIn(abs)) {
                keepFirst(links, abs, text)
            }
        }

        // direct media elements: <audio src>, <video src>, <source src>
        val mediaTag = Regex(
            "<(?:audio|video|source)\\b[^>]*?src=\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
        for (match in mediaTag.findAll(html)) {
            token.throwIfCancelled()
            val abs = resolveUrl(baseUrl, match.groupValues[1].trimJava())
            if (abs != null) {
                keepFirst(downloads, abs, "media source")
            }
        }

        // Open-Graph / Twitter media meta tags
        val metaTag = Regex(
            "<meta\\b[^>]*?(?:property|name)=\"(?:og:audio|og:video|og:image|twitter:image|twitter:player:stream)\"[^>]*?content=\"([^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
        for (match in metaTag.findAll(html)) {
            token.throwIfCancelled()
            val abs = resolveUrl(baseUrl, match.groupValues[1].trimJava())
            if (abs != null) {
                keepFirst(downloads, abs, "media (meta)")
            }
        }

        // Raw scan of the whole document INCLUDING inline JavaScript: many music
        // sites keep the real mp3 only inside a player config ("file":"https:\/\/…mp3").
        val unescapedDoc = html.replace("\\/", "/")
        val rawMedia = Regex(
            "https?://[^\\s\"'<>()\\\\]+?\\.(?:mp3|m4a|aac|flac|wav|ogg|opus|mp4|mkv|webm|m4v|jpg|jpeg|png|gif|webp|pdf|apk|zip)(?:\\?[^\\s\"'<>()\\\\]*)?",
            RegexOption.IGNORE_CASE
        )
        for (match in rawMedia.findAll(unescapedDoc)) {
            token.throwIfCancelled()
            keepFirst(downloads, unescape(match.value), "found in page source")
        }

        if (downloads.isNotEmpty()) {
            // Liveness probes for the first few media links, in parallel
            // (#16) — the model only downloads links that actually exist.
            val probeTargets = ArrayList<String>()
            for (target in downloads.keys) {
                if (probeTargets.size >= 4) {
                    break
                }
                if (MEDIA_EXT.containsMatchIn(target)) {
                    probeTargets.add(target)
                }
            }
            val probes = probeHeadsParallel(probeTargets, token)
            val header = "\n\n=== DOWNLOADABLE (pass one of these to download_file) ===\n"
            if (fits(header)) {
                sb.append(header)
                var shown = 0
                val dead = ArrayList<String>()
                for ((target, label) in downloads) {
                    token.throwIfCancelled()
                    if (shown >= 25) {
                        break
                    }
                    val probe = probes[target]
                    if (probe != null && probe.status == -1) {
                        dead.add(target)
                        continue
                    }
                    var note = ""
                    if (probe != null && probe.status == 1) {
                        note = if (probe.size > 0) {
                            "   [OK ✓ " + Util.humanSize(probe.size) + "]"
                        } else {
                            "   [OK ✓]"
                        }
                    }
                    shown++
                    val line = buildString {
                        append("• ").append(target)
                        if (label.isNotEmpty()) {
                            append("   — ").append(label)
                        }
                        append(note).append('\n')
                    }
                    if (!fits(line)) {
                        break
                    }
                    sb.append(line)
                }
                var k = 0
                while (k < dead.size && k < 3) {
                    val line = "✗ DEAD LINK (HTTP 404 — do NOT use): " + dead[k] + "\n"
                    if (!fits(line)) {
                        break
                    }
                    sb.append(line)
                    k++
                }
            }
        }

        if (links.isNotEmpty()) {
            val header = "\n=== LINKS (open with web_fetch to keep browsing) ===\n"
            if (fits(header)) {
                sb.append(header)
                var i = 0
                for ((target, label) in links) {
                    token.throwIfCancelled()
                    if (++i > 40) {
                        break
                    }
                    val line = "• " + (if (label.isEmpty()) "(link)" else label) +
                        "  →  " + target + "\n"
                    if (!fits(line)) {
                        break
                    }
                    sb.append(line)
                }
            }
        }
    }

    /**
     * `Map.putIfAbsent` for an API 23 floor.
     *
     * The default method arrived in API 24. On Android 6 the call is not merely
     * absent from the platform map implementation, it resolves to nothing at all
     * and throws `NoSuchMethodError` — an Error, so the surrounding
     * `catch (Exception)` never sees it and the whole web_fetch dies with a stack
     * trace instead of returning a page. First insert wins, exactly as before:
     * the earliest occurrence of a URL in the document carries the most
     * descriptive label.
     */
    private fun keepFirst(map: MutableMap<String, String>, key: String, value: String) {
        if (!map.containsKey(key)) {
            map[key] = value
        }
    }

    /**
     * Liveness of one HEAD probe: 1 = confirmed live, -1 = confirmed dead
     * (404/410), 0 = unknown (HEAD refused / timeout / error). [finalUrl] is
     * where the link actually lands after redirects.
     */
    private class HeadProbe(val status: Int, val size: Long, val finalUrl: String)

    /**
     * Runs the HEAD liveness probes for a page's download links IN PARALLEL
     * on a small daemon pool — 3 s per probe, ~8 s overall — instead of
     * serially (#16). The old sequential probes added up to 14 s of dead
     * time to every web_fetch with media links. Probes are skippable: the
     * moment the token is cancelled the remaining futures are dropped.
     */
    private fun probeHeadsParallel(
        targets: List<String>,
        token: CancellationToken
    ): Map<String, HeadProbe> {
        if (targets.isEmpty()) {
            return emptyMap()
        }
        val pool = Executors.newFixedThreadPool(
            Math.min(targets.size, 4), daemonFactory("vega-head")
        )
        try {
            val futures = targets.map { target ->
                pool.submit(Callable { target to headProbe(target, token) })
            }
            val out = LinkedHashMap<String, HeadProbe>()
            val deadline = System.currentTimeMillis() + 8000L
            for (future in futures) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0 || token.isCancelled) {
                    break
                }
                try {
                    val (target, probe) = future.get(remaining, TimeUnit.MILLISECONDS)
                    out[target] = probe
                } catch (timeout: TimeoutException) {
                    // Still running — its verdict is dropped with the pool.
                } catch (failed: Exception) {
                    rethrowIfCancelled(failed)
                    // Any other probe failure reads as "unknown", never dead.
                }
            }
            for (future in futures) {
                future.cancel(true)
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * One HEAD liveness probe: 3 s connect/read timeouts, manual redirect
     * following (up to 6 hops) so the verdict — and the final URL — reflect
     * where the link actually lands. A 302 → 200 link is LIVE, not unknown.
     * Never throws except on cancellation (#12: no `Thread.interrupted()`
     * here at all — nothing in the cleanup clears the flag, so a foreign
     * interrupt is simply preserved).
     */
    private fun headProbe(url: String, token: CancellationToken): HeadProbe {
        var current = url
        var connection: HttpURLConnection? = null
        var watch: CancellationToken.Registration? = null
        try {
            var redirects = 0
            while (true) {
                token.throwIfCancelled()
                NetworkPolicy.requireSafeHttps(current)
                val active = URL(current).openConnection() as HttpURLConnection
                watch?.close()
                connection = active
                watch = token.watchConnection(active)
                active.requestMethod = "HEAD"
                active.instanceFollowRedirects = false
                active.connectTimeout = 3000
                active.readTimeout = 3000
                applyBrowserHeaders(active, null, false)
                val code = active.responseCode
                token.throwIfCancelled()
                if (code in 300..399 && redirects < 6) {
                    val location = active.getHeaderField("Location")
                    watch?.close()
                    watch = null
                    active.disconnect()
                    connection = null
                    if (location == null) {
                        return HeadProbe(0, 0, current)
                    }
                    current = URL(URL(current), location).toString()
                    redirects++
                    continue
                }
                return when {
                    code in 200..299 -> HeadProbe(1, active.contentLengthLong, current)
                    code == 404 || code == 410 -> HeadProbe(-1, 0, current)
                    else -> HeadProbe(0, 0, current)
                }
            }
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            if (token.isCancelled) {
                throw CancellationToken.CancelledException()
            }
            return HeadProbe(0, 0, url)
        } finally {
            watch?.close()
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Resolves href (absolute, //host, /path or relative) against the page URL. */
    /**
     * `base` is nullable to match the Java exactly: a page whose final URL could
     * not be determined still gets its links resolved, and `URL(null)` throws
     * MalformedURLException, which the catch below turns into the same graceful
     * null/passthrough the Java produced. Declaring it non-null made Kotlin's
     * intrinsic null check throw where the original degraded quietly.
     */
    internal fun resolveUrl(base: String?, href: String?): String? {
        if (href == null) {
            return null
        }
        val target = href.trimJava()
        if (target.isEmpty() || target.startsWith("#") || target.startsWith("javascript:") ||
            target.startsWith("mailto:") || target.startsWith("tel:") || target.startsWith("data:")
        ) {
            return null
        }
        return try {
            URL(URL(base), target).toString()
        } catch (e: Exception) {
            if (target.startsWith("http")) target else null
        }
    }

    // ---- raw HTTP ----------------------------------------------------------

    /**
     * Decodes a response body using the charset the server declared, falling
     * back to a `<meta charset>` sniff and finally to UTF-8.
     *
     * Decoding everything as UTF-8 was wrong for the sites this app is pointed
     * at most: windows-1256 and iso-8859-1 are still common on Persian pages,
     * and every byte above 0x7F came back as U+FFFD — so the page text, the
     * <title> and every extracted link label were garbage, and the blocked-page
     * detector then ran on that garbage.
     */
    private fun decodeBody(raw: ByteArray, contentType: String?): String {
        val declared = charsetFrom(contentType ?: "")
        if (declared != null) {
            return String(raw, declared)
        }
        // Sniff a <meta charset> out of the first few KB, which is ASCII-safe.
        val head = String(
            raw, 0, Math.min(raw.size, 4096), StandardCharsets.ISO_8859_1
        )
        val meta = META_CHARSET.find(head)
        if (meta != null) {
            val sniffed = charsetForName(meta.groupValues[1])
            if (sniffed != null) {
                return String(raw, sniffed)
            }
        }
        return String(raw, StandardCharsets.UTF_8)
    }

    private fun charsetFrom(contentType: String): Charset? {
        val match = CHARSET_PARAM.find(contentType) ?: return null
        return charsetForName(match.groupValues[1])
    }

    private fun charsetForName(name: String): Charset? {
        val clean = name.trimJava().trim('"', '\'').lowercase()
        if (clean.isEmpty()) {
            return null
        }
        return try {
            if (Charset.isSupported(clean)) Charset.forName(clean) else null
        } catch (unsupported: Exception) {
            null
        }
    }

    /** Response of [httpGetResult]; [contentType] is lower-cased, never null. */
    private class HttpResult(
        val code: Int,
        val finalUrl: String,
        val body: String,
        contentType: String?,
        retryAfter: String?
    ) {
        val contentType: String = contentType?.lowercase() ?: ""
        val retryAfter: String = retryAfter ?: ""
    }

    @Throws(Exception::class)
    fun httpGet(url: String, timeout: Int, maxBytes: Int): String =
        httpGetResult(url, timeout, timeout, maxBytes, CancellationToken()).body

    /** Backwards-compatible helper returning {finalUrl, body}. */
    @Throws(Exception::class)
    fun httpGetEx(url: String, timeout: Int, maxBytes: Int): Array<String> {
        val result = httpGetResult(url, timeout, timeout, maxBytes, CancellationToken())
        return arrayOf(result.finalUrl, result.body)
    }

    @Throws(Exception::class)
    private fun httpGetResult(
        urlIn: String?,
        connectTimeout: Int,
        readTimeout: Int,
        maxBytes: Int,
        token: CancellationToken,
        // `downloadProgress` stays LAST so existing trailing-lambda callers
        // keep binding to it; `liveConn` is always passed by name. When
        // non-null it always holds the currently-live connection: lets a
        // caller disconnect() a loser directly, because HttpURLConnection
        // ignores thread interrupts and future.cancel(true) alone would leave
        // it hanging until its own timeouts.
        liveConn: AtomicReference<HttpURLConnection?>? = null,
        downloadProgress: ((kb: Long) -> Unit)? = null
    ): HttpResult {
        var url = urlIn
        var connection: HttpURLConnection? = null
        var watch: CancellationToken.Registration? = null
        var input: InputStream? = null
        try {
            token.throwIfCancelled()
            NetworkPolicy.requireSafeHttps(url)
            // `active` is the non-null working reference; `connection` mirrors it
            // purely so the finally block can always disconnect the latest one.
            var active = URL(url).openConnection() as HttpURLConnection
            connection = active
            liveConn?.set(active)
            watch = token.watchConnection(active)
            active.requestMethod = "GET"
            active.instanceFollowRedirects = false
            active.connectTimeout = connectTimeout
            active.readTimeout = readTimeout
            applyBrowserHeaders(active, null, true)
            var code = active.responseCode

            var redirects = 0
            while (code in 300..399 && redirects++ < 6) {
                val location = active.getHeaderField("Location") ?: break
                val next = URL(URL(url), location).toString()
                NetworkPolicy.requireSafeHttps(next)
                watch?.close()
                active.disconnect()
                active = URL(next).openConnection() as HttpURLConnection
                connection = active
                liveConn?.set(active)
                watch = token.watchConnection(active)
                active.requestMethod = "GET"
                active.instanceFollowRedirects = false
                active.connectTimeout = connectTimeout
                active.readTimeout = readTimeout
                // referer is the PREVIOUS url, matching a real browser hop
                applyBrowserHeaders(active, url, true)
                code = active.responseCode
                url = next
            }

            token.throwIfCancelled()
            NetworkPolicy.requireSafeHttps(active.url.toString())
            // Content type BEFORE bytes: a binary URL (an image/video/audio
            // link pasted as a page) used to download up to maxBytes for
            // nothing. Skip the body on 2xx; the type is still returned so the
            // caller reports "not a readable web page" with the real reason.
            // Error bodies are always read — a 403 page is how blocks are
            // detected.
            val binaryBody = code in 200..299 && isBinaryMedia(active.contentType)
            input = if (binaryBody) {
                null
            } else if (code >= 400) {
                active.errorStream
            } else {
                active.inputStream
            }

            val output = ByteArrayOutputStream(Math.min(maxBytes, 8192))
            if (input != null) {
                val buffer = ByteArray(8192)
                // One progress report per 512 KB: per-chunk would post hundreds
                // of UI updates for a display that shows four a second.
                var nextReportAt = 512 * 1024
                while (output.size() < maxBytes) {
                    token.throwIfCancelled()
                    val count = input.read(
                        buffer, 0, Math.min(buffer.size, maxBytes - output.size())
                    )
                    if (count < 0) {
                        break
                    }
                    output.write(buffer, 0, count)
                    if (downloadProgress != null && output.size() >= nextReportAt) {
                        downloadProgress(output.size() / 1024L)
                        nextReportAt += 512 * 1024
                    }
                }
            }
            val raw = output.toByteArray()
            return HttpResult(
                code,
                active.url.toString(),
                decodeBody(raw, active.contentType),
                active.contentType,
                active.getHeaderField("Retry-After")
            )
        } catch (timeout: SocketTimeoutException) {
            token.throwIfCancelled()
            throw Exception("timeout while reading " + Util.redactUrl(url))
        } finally {
            // #12: no Thread.interrupted() here — none of the calls above
            // clear the interrupt flag, so a foreign interrupt is preserved
            // as-is for the caller (the token machinery owns it).
            if (input != null) {
                try {
                    input.close()
                } catch (ignored: Exception) {
                }
            }
            watch?.close()
            connection?.disconnect()
        }
    }

    // ---- result parsers ----------------------------------------------------

    private fun parseDuck(html: String): List<Hit> {
        val out = ArrayList<Hit>()
        val resultP = Regex(
            "result__a\"[^>]*href=\"(.*?)\".*?>(.*?)</a>", RegexOption.DOT_MATCHES_ALL
        )
        val snippetP = Regex("result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)

        // Collect results (with their document positions) and snippets separately,
        // then bind each snippet to the result it actually sits under. The old code
        // advanced both matchers in lockstep, so a result WITHOUT a snippet stole
        // the next result's snippet and shifted every later pairing by one.
        val resStart = ArrayList<Int>()
        val hrefs = ArrayList<String>()
        val titles = ArrayList<String>()
        for (match in resultP.findAll(html)) {
            resStart.add(match.range.first)
            hrefs.add(decodeDuckHref(match.groupValues[1]))
            titles.add(unescape(stripTags(match.groupValues[2])).trimJava())
        }

        val snipStart = ArrayList<Int>()
        val snippets = ArrayList<String>()
        for (match in snippetP.findAll(html)) {
            snipStart.add(match.range.first)
            snippets.add(unescape(stripTags(match.groupValues[1])).trimJava())
        }

        for (i in titles.indices) {
            val title = titles[i]
            if (title.isEmpty()) {
                continue
            }
            val start = resStart[i]
            val nextStart = if (i + 1 < resStart.size) resStart[i + 1] else Int.MAX_VALUE
            var snippet = ""
            for (j in snipStart.indices) {
                val s = snipStart[j]
                if (s > start && s < nextStart) {
                    snippet = snippets[j]
                    break
                }
            }
            out.add(Hit(title, hrefs[i], snippet))
        }
        return out
    }

    private fun parseLite(html: String): List<Hit> {
        val out = ArrayList<Hit>()
        val pattern = Regex(
            "<a[^>]+class=\"result-link\"[^>]+href=\"(.*?)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in pattern.findAll(html)) {
            val href = decodeDuckHref(match.groupValues[1])
            val title = unescape(stripTags(match.groupValues[2])).trimJava()
            if (title.isNotEmpty()) {
                out.add(Hit(title, href, ""))
            }
        }
        return out
    }

    private fun parseBing(html: String): List<Hit> {
        val out = ArrayList<Hit>()
        val pattern = Regex(
            "<h2[^>]*><a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in pattern.findAll(html)) {
            val url = decodeBingHref(match.groupValues[1])
            val title = unescape(stripTags(match.groupValues[2])).trimJava()
            if (title.isNotEmpty() && url.startsWith("http") && !url.contains("bing.com/")) {
                out.add(Hit(title, url, ""))
            }
        }
        return out
    }

    /** Bing wraps result urls as /ck/a?…&u=a1<base64url>&…; unwrap them. */
    private fun decodeBingHref(href: String): String {
        try {
            if (href.contains("bing.com/ck/")) {
                val match = Regex("[?&]u=a1([^&]+)").find(href)
                if (match != null) {
                    var b64 = match.groupValues[1].replace('-', '+').replace('_', '/')
                    while (b64.length % 4 != 0) {
                        b64 += "="
                    }
                    val decoded = Base64.decode(b64, Base64.DEFAULT)
                    val target = String(decoded, StandardCharsets.UTF_8)
                    if (target.startsWith("http")) {
                        return target
                    }
                }
            }
        } catch (e: Exception) {
        }
        return href
    }

    /**
     * Collapses hits that are the same page under a different skin — the
     * engines overlap, and one page can arrive as http/https, with or without
     * a trailing slash, or with tracking parameters. Keeps the first
     * occurrence (the winning engine's ordering) and the original URL for
     * display.
     */
    internal fun dedupeHits(hits: List<Hit>): List<Hit> {
        val seen = HashSet<String>(hits.size * 2)
        val out = ArrayList<Hit>(hits.size)
        for (hit in hits) {
            val key = normalizeSearchUrl(hit.url)
            if (key.isEmpty() || !seen.add(key)) {
                continue
            }
            out.add(hit)
        }
        return out
    }

    /**
     * Canonical form used ONLY as a dedupe key — never shown to the user and
     * never passed to fetch. Scheme is ignored (http/https are the same page),
     * host lowercased, www. stripped, default ports dropped, one trailing
     * slash trimmed, and the usual tracking parameters stripped.
     */
    internal fun normalizeSearchUrl(url: String): String {
        return try {
            val u = java.net.URI(url.trimJava())
            val scheme = (u.scheme ?: "").lowercase()
            if (scheme != "http" && scheme != "https") {
                return ""
            }
            var host = (u.host ?: "").lowercase()
            if (host.isEmpty()) {
                return ""
            }
            if (host.startsWith("www.")) {
                host = host.substring(4)
            }
            val port = u.port
            val defaultPort = scheme == "http" && port == 80 || scheme == "https" && port == 443
            var path = u.rawPath ?: ""
            if (path.length > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length - 1)
            }
            val query = u.rawQuery
            val kept = StringBuilder()
            if (!query.isNullOrEmpty()) {
                for (param in query.split("&")) {
                    val name = param.substringBefore("=").lowercase()
                    if (name.startsWith("utm_") || name == "fbclid" || name == "gclid" ||
                        name == "gclsrc" || name == "msclkid" || name == "mc_cid" ||
                        name == "mc_eid" || name == "ref" || name == "ref_src"
                    ) {
                        continue
                    }
                    if (kept.isNotEmpty()) {
                        kept.append("&")
                    }
                    kept.append(param)
                }
            }
            // No scheme in the key: the same page over http and https is one hit.
            val sb = StringBuilder(host)
            if (!defaultPort && port != -1) {
                sb.append(":").append(port)
            }
            sb.append(if (path.isEmpty()) "/" else path)
            if (kept.isNotEmpty()) {
                sb.append("?").append(kept)
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    /** Mojeek renders plain <h2><a href="…"> results with no bot wall. */
    internal fun parseMojeek(html: String): List<Hit> {
        val out = ArrayList<Hit>()
        val pattern = Regex(
            "<h2[^>]*><a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in pattern.findAll(html)) {
            var url = match.groupValues[1].trimJava()
            // Relative result links ("/...") are site navigation, not results.
            if (url.startsWith("/")) {
                continue
            }
            val title = unescape(stripTags(match.groupValues[2])).trimJava()
            if (title.isNotEmpty() && url.startsWith("http") && !url.contains("mojeek.com")) {
                out.add(Hit(title, url, ""))
            }
        }
        // Fallback: some Mojeek layouts put the URL on a plain title-class
        // anchor instead of inside an h2.
        if (out.isEmpty()) {
            val alt = Regex(
                "<a[^>]+class=\"[^\"]*title[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in alt.findAll(html)) {
                val url = match.groupValues[1].trimJava()
                val title = unescape(stripTags(match.groupValues[2])).trimJava()
                if (title.isNotEmpty() && url.startsWith("http") && !url.contains("mojeek.com")) {
                    out.add(Hit(title, url, ""))
                }
            }
        }
        return out
    }

    /** DuckDuckGo wraps result urls as /l/?uddg=<encoded>; unwrap them. */    private fun decodeDuckHref(href: String): String {
        val marker = href.indexOf("uddg=")
        if (marker < 0) {
            return if (href.startsWith("//")) "https:$href" else href
        }
        var encoded = href.substring(marker + 5)
        val amp = encoded.indexOf('&')
        if (amp >= 0) {
            encoded = encoded.substring(0, amp)
        }
        return try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (e: Exception) {
            href
        }
    }

    // ---- heuristics & text -------------------------------------------------

    /**
     * Heuristic: is this an anti-bot / access-check interstitial rather than
     * a readable page?
     *
     * Only the first 64 KB are scanned: challenge pages are tiny by nature,
     * and running the full extraction chain over a multi-megabyte document
     * just to answer "is this blocked" was pure waste. Callers that need the
     * real text use the streaming extractor ([WebFast]) instead of the old
     * looksBlocked → htmlToText → extractMainHtml → htmlToText chain.
     */
    internal fun looksBlocked(body: String?): Boolean {
        if (body.isNullOrEmpty()) {
            return false
        }
        val head = if (body.length > 65536) body.substring(0, 65536) else body
        val lower = head.lowercase()
        // UNAMBIGUOUS vendor tokens: these only appear in an actual challenge
        // page's markup, never in ordinary prose, so they alone are conclusive.
        val hardMarker = lower.contains("cf-chl-") || lower.contains("__cf_chl") ||
            lower.contains("cf-browser-verification") || lower.contains("challenge-platform") ||
            lower.contains("px-captcha") || lower.contains("perimeterx") ||
            lower.contains("incapsula") || lower.contains("sucuri website firewall")
        if (hardMarker) {
            return true
        }
        // AMBIGUOUS phrases: an article ABOUT captchas, a page whose footer says
        // "attention required", or a normal login page carrying a reCAPTCHA badge
        // all contain these while being perfectly readable. Treating the words
        // alone as proof of a wall is what made web_fetch error on sites that open
        // fine in a browser — the single biggest source of false failures.
        //
        // So a soft phrase only counts when the page ALSO looks like an
        // interstitial: challenge pages are tiny and carry almost no prose, while
        // a real article has plenty. Requiring both makes a false positive need a
        // near-empty page that happens to discuss captchas.
        val softMarker = lower.contains("verify you are human") ||
            lower.contains("are you a robot") || lower.contains("unusual traffic") ||
            lower.contains("automated requests") || lower.contains("just a moment") ||
            lower.contains("checking your browser") || lower.contains("attention required") ||
            lower.contains("please enable javascript and cookies") ||
            lower.contains("enable cookies to continue") ||
            lower.contains("ddos protection") || lower.contains("captcha")
        if (!softMarker) {
            return false
        }
        return htmlToText(head).length < CHALLENGE_TEXT_MAX
    }

    /**
     * A page with less readable text than this, plus a challenge phrase, is an
     * interstitial. Real articles clear it by an order of magnitude; genuine
     * Cloudflare/WAF pages carry only a sentence or two.
     */
    private const val CHALLENGE_TEXT_MAX = 900

    /**
     * True for content types that can never be a readable page. Checked before
     * any body bytes are read, so a binary URL fails fast instead of
     * downloading megabytes the caller will discard.
     */
    private fun isBinaryMedia(contentType: String?): Boolean {
        val ct = contentType?.lowercase(Locale.US) ?: return false
        return ct.startsWith("image/") || ct.startsWith("video/") ||
            ct.startsWith("audio/") || ct.startsWith("font/")
    }

    private fun friendlyNetworkError(error: Exception): String {
        val name = error.javaClass.simpleName
        if (name.contains("UnknownHost") || name.contains("ConnectException") ||
            name.contains("NoRoute")
        ) {
            return "network unavailable"
        }
        if (name.contains("Timeout") || name.contains("SocketTimeout")) {
            return "timeout"
        }
        val message = error.message
        // Exception text can carry the request URL; strip any ?query/#fragment
        // before it reaches the model, where signed tokens like ?key= live.
        return if (message.isNullOrEmpty()) name else Util.redactUrlsInText(message)
    }

    /**
     * Parses a Retry-After value (delta-seconds; HTTP dates are not honoured —
     * waiting on a wall-clock date is not a polite retry). Returns -1 when the
     * value is absent, unparseable or absurd.
     */
    private fun parseRetryAfterMs(retryAfter: String?): Long {
        if (retryAfter.isNullOrEmpty()) {
            return -1
        }
        return try {
            val seconds = retryAfter.trimJava().toLong()
            if (seconds in 1..300) seconds * 1000 else -1
        } catch (e: Exception) {
            -1
        }
    }

    /** Sleeps up to [ms], returning true when the token cancelled the wait. */
    private fun sleepCancellable(ms: Long, token: CancellationToken): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (token.isCancelled) {
                return true
            }
            try {
                Thread.sleep(200)
            } catch (ie: InterruptedException) {
                return true
            }
        }
        return token.isCancelled
    }

    private fun statusHint(code: Int, retryAfter: String?): String {
        val hint = StringBuilder()
        if (code == 429) {
            hint.append(" (the target website, not the AI key, throttled this fetch)")
        } else if (code == 403) {
            hint.append(" (the website blocked automated access)")
        }
        if (!retryAfter.isNullOrEmpty()) {
            hint.append("; Retry-After=").append(retryAfter)
        }
        return hint.toString()
    }

    private fun join(values: List<String>): String {
        val output = StringBuilder()
        for (value in values) {
            if (output.isNotEmpty()) {
                output.append("; ")
            }
            output.append(value)
        }
        return output.toString()
    }

    private fun extract(html: String, pattern: String): String? = Regex(
        pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).find(html)?.groupValues?.get(1)

    /**
     * The page's MAIN content, with site chrome removed — nav bars, headers,
     * footers, sidebars, cookie banners and comment threads.
     *
     * A plain tag-strip hands the model a slurry of menu links, "related
     * articles" and footer boilerplate with the actual article buried inside,
     * which is why answers about a page could feel like the site was never
     * really read. This narrows to <article>/<main> when the page marks it up,
     * and otherwise drops the obvious chrome elements before stripping tags.
     *
     * Deliberately conservative: if the narrowed result is much smaller than the
     * whole page's text, the heuristic is assumed wrong and the full text is
     * used instead. Getting extra boilerplate is a far smaller failure than
     * silently dropping the article.
     */
    fun readableText(html: String?): String {
        if (html == null) {
            return ""
        }
        val whole = htmlToText(html)
        try {
            val main = extractMainHtml(html) ?: return whole
            val narrowed = htmlToText(main)
            // Keep the narrowed version only when it retained the substance.
            if (narrowed.length >= 400 && narrowed.length >= whole.length / 4) {
                return narrowed
            }
        } catch (ignored: Exception) {
        }
        return whole
    }

    /**
     * The innermost <article>/<main> block, or the page minus obvious chrome.
     *
     * Each `<article>` open is paired with its MATCHING close (reluctant
     * match), and when several candidates exist the one with the most
     * readable text wins — pairing the first open with the LAST close used
     * to swallow the whole page, navigation included, whenever a page had
     * more than one article element.
     */
    private fun extractMainHtml(html: String): String? {
        for (tag in arrayOf("article", "main")) {
            val pattern = Regex("(?is)<$tag(?:\\s[^>]*)?>(.*?)</$tag\\s*>")
            var best: String? = null
            var bestLen = 0
            for (match in pattern.findAll(html)) {
                val block = match.groupValues[1]
                val len = try {
                    htmlToText(block).length
                } catch (e: Exception) {
                    0
                }
                if (len >= 400 && len > bestLen) {
                    best = block
                    bestLen = len
                }
            }
            if (best != null) {
                return best
            }
        }
        // No semantic wrapper: strip the elements that are chrome by definition.
        var out = html
        for (tag in arrayOf("nav", "header", "footer", "aside", "form")) {
            out = out.replace(Regex("(?is)<$tag[^>]*>.{0,200000}?</$tag>"), " ")
        }
        return if (out.length == html.length) null else out
    }

    /**
     * Test hook: the article/main pairing used by [readableText], exposed so
     * the regression suite can prove the right block wins without network.
     */
    internal fun extractMainHtmlForTest(html: String): String? = extractMainHtml(html)

    fun htmlToText(html: String?): String {
        if (html == null) {
            return ""
        }
        // Same bounding rationale as the anchor pattern above: an unclosed
        // <script> in a large document made these quadratic.
        val stripped = html
            .replace(Regex("(?is)<script.{0,200000}?</script>"), " ")
            .replace(Regex("(?is)<style.{0,200000}?</style>"), " ")
            .replace(Regex("(?is)<head.{0,200000}?</head>"), " ")
            .replace(Regex("(?is)<noscript.{0,200000}?</noscript>"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)>"), "\n")
        return unescape(stripTags(stripped))
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trimJava()
    }

    private fun stripTags(html: String): String = html.replace(Regex("(?s)<[^>]*>"), "")

    /**
     * Decodes HTML entities: named (`&amp;` …), decimal (`&#1740;`) and hex
     * (`&#x6CC;`) numeric character references.
     *
     * Numeric references are decoded FIRST, before the named ones: they never
     * interact with `&amp;` the way named entities do, and Persian/Arabic
     * pages routinely encode every non-ASCII character this way — without
     * this the extracted text was mojibake-adjacent gibberish.
     */
    internal fun unescape(text: String?): String {
        if (text == null) {
            return ""
        }
        val numeric = NUMERIC_REF.replace(text) { match ->
            val hex = match.groupValues[1]
            val code = try {
                if (hex.isNotEmpty()) hex.toLong(16) else match.groupValues[2].toLong(10)
            } catch (e: Exception) {
                -1L
            }
            // Surrogates and out-of-range values are left untouched rather
            // than producing lone-surrogate garbage.
            if (code in 1..0x10FFFF && (code < 0xD800 || code > 0xDFFF)) {
                String(Character.toChars(code.toInt()))
            } else {
                match.value
            }
        }
        // `&amp;` must be decoded LAST: doing it first turns "&amp;lt;" into
        // "&lt;" which the later replacements then wrongly decode to "<"
        // (classic double-unescape bug).
        return numeric.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ").replace("&rsquo;", "'").replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&amp;", "&")
    }
}
