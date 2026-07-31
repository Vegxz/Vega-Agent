package com.vepro.code

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

    /** One parsed search result. */
    private data class Hit(val title: String, val url: String, val snippet: String)

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

    private val MEDIA_EXT = Regex(
        "\\.(mp3|m4a|aac|flac|wav|ogg|opus|mp4|mkv|webm|mov|avi|m4v|3gp|apk|zip|rar|7z|tar|gz|pdf|docx?|xlsx?|pptx?|epub|jpg|jpeg|png|gif|webp|svg|bmp|torrent|iso|exe|dmg)(\\?|#|$)",
        RegexOption.IGNORE_CASE
    )

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
        val diagnostics = ArrayList<String>()
        try {
            token.throwIfCancelled()
            val encoded = URLEncoder.encode(cleaned, "UTF-8")

            var results = trySearchEngine(
                "DuckDuckGo HTML",
                "https://html.duckduckgo.com/html/?q=$encoded", 0, token, diagnostics
            )
            if (results.isEmpty()) {
                results = trySearchEngine(
                    "DuckDuckGo Lite",
                    "https://lite.duckduckgo.com/lite/?q=$encoded", 1, token, diagnostics
                )
            }
            if (results.isEmpty()) {
                results = trySearchEngine(
                    "Bing",
                    "https://www.bing.com/search?q=$encoded&count=10", 2, token, diagnostics
                )
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

            if (results.isEmpty()) {
                val none = StringBuilder("No web results for: ").append(cleaned)
                    .append("\n(hint: simplify the query, remove site: operators, or try another language.)")
                if (diagnostics.isNotEmpty()) {
                    none.append("\nSearch engines tried: ").append(join(diagnostics))
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
    private fun trySearchEngine(
        name: String,
        url: String,
        parser: Int,
        token: CancellationToken,
        diagnostics: MutableList<String>
    ): List<Hit> {
        try {
            val response = httpGetResult(url, 12000, 18000, 900000, token)
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
        else -> parseBing(html)
    }

    // ---- page fetch --------------------------------------------------------

    fun fetch(url: String?): String = fetch(url, CancellationToken())

    fun fetch(url: String?, token: CancellationToken): String {
        if (url.isNullOrBlankJava()) {
            return "ERROR: url is required"
        }
        try {
            token.throwIfCancelled()
            val cleaned = Util.cleanUrl(url)
            val response = httpGetResult(cleaned, 15000, 30000, 3000000, token)
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
                        " at " + response.finalUrl +
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
            output.append(Util.truncate(readable, 45000))
            appendLinks(output, page, finalUrl, token)
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
     */
    @Throws(CancellationToken.CancelledException::class)
    private fun appendLinks(
        sb: StringBuilder,
        html: String,
        baseUrl: String,
        token: CancellationToken
    ) {
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
            sb.append("\n\n=== DOWNLOADABLE (pass one of these to download_file) ===\n")
            var shown = 0
            var checked = 0
            val dead = ArrayList<String>()
            for ((target, label) in downloads) {
                token.throwIfCancelled()
                if (shown >= 25) {
                    break
                }
                var note = ""
                // Pre-verify the first few direct file links with a HEAD request
                // so the model only downloads links that actually exist.
                if (checked < 4 && MEDIA_EXT.containsMatchIn(target)) {
                    checked++
                    val probe = headCheck(target, token)
                    if (probe[0] == -1L) {
                        dead.add(target)
                        continue
                    }
                    if (probe[0] == 1L) {
                        note = if (probe[1] > 0) {
                            "   [OK ✓ " + Util.humanSize(probe[1]) + "]"
                        } else {
                            "   [OK ✓]"
                        }
                    }
                }
                shown++
                sb.append("• ").append(target)
                if (label.isNotEmpty()) {
                    sb.append("   — ").append(label)
                }
                sb.append(note).append('\n')
            }
            var k = 0
            while (k < dead.size && k < 3) {
                sb.append("✗ DEAD LINK (HTTP 404 — do NOT use): ").append(dead[k]).append('\n')
                k++
            }
        }

        if (links.isNotEmpty()) {
            sb.append("\n=== LINKS (open with web_fetch to keep browsing) ===\n")
            var i = 0
            for ((target, label) in links) {
                token.throwIfCancelled()
                if (++i > 40) {
                    break
                }
                sb.append("• ").append(if (label.isEmpty()) "(link)" else label)
                    .append("  →  ").append(target).append('\n')
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
     * Quick liveness probe. Returns {status, size}: status 1 = confirmed live,
     * -1 = confirmed dead (404/410), 0 = unknown (HEAD refused / timeout).
     */
    @Throws(CancellationToken.CancelledException::class)
    private fun headCheck(url: String, token: CancellationToken): LongArray {
        var connection: HttpURLConnection? = null
        var watch: CancellationToken.Registration? = null
        try {
            token.throwIfCancelled()
            NetworkPolicy.requireSafeHttps(url)
            val active = URL(url).openConnection() as HttpURLConnection
            connection = active
            watch = token.watchConnection(active)
            active.requestMethod = "HEAD"
            active.instanceFollowRedirects = false
            active.connectTimeout = 3500
            active.readTimeout = 3500
            applyBrowserHeaders(active, null, false)
            val code = active.responseCode
            token.throwIfCancelled()
            if (code in 200..299) {
                return longArrayOf(1, active.contentLengthLong)
            }
            if (code == 404 || code == 410) {
                return longArrayOf(-1, 0)
            }
            return longArrayOf(0, 0)
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            if (token.isCancelled) {
                throw CancellationToken.CancelledException()
            }
            return longArrayOf(0, 0)
        } finally {
            watch?.close()
            connection?.disconnect()
            Thread.interrupted()
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
        token: CancellationToken
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
            input = if (code >= 400) active.errorStream else active.inputStream

            val output = ByteArrayOutputStream(Math.min(maxBytes, 8192))
            if (input != null) {
                val buffer = ByteArray(8192)
                while (output.size() < maxBytes) {
                    token.throwIfCancelled()
                    val count = input.read(
                        buffer, 0, Math.min(buffer.size, maxBytes - output.size())
                    )
                    if (count < 0) {
                        break
                    }
                    output.write(buffer, 0, count)
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
            throw Exception("timeout while reading $url")
        } finally {
            if (input != null) {
                try {
                    input.close()
                } catch (ignored: Exception) {
                }
            }
            watch?.close()
            connection?.disconnect()
            Thread.interrupted()
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

    /** DuckDuckGo wraps result urls as /l/?uddg=<encoded>; unwrap them. */
    private fun decodeDuckHref(href: String): String {
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

    internal fun looksBlocked(body: String?): Boolean {
        if (body.isNullOrEmpty()) {
            return false
        }
        val lower = body.lowercase()
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
        return htmlToText(body).length < CHALLENGE_TEXT_MAX
    }

    /**
     * A page with less readable text than this, plus a challenge phrase, is an
     * interstitial. Real articles clear it by an order of magnitude; genuine
     * Cloudflare/WAF pages carry only a sentence or two.
     */
    private const val CHALLENGE_TEXT_MAX = 900

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
        return if (message.isNullOrEmpty()) name else message
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

    /** The innermost <article>/<main> block, or the page minus obvious chrome. */
    private fun extractMainHtml(html: String): String? {
        for (tag in arrayOf("article", "main")) {
            val open = Regex("(?is)<$tag[^>]*>").find(html) ?: continue
            val close = html.lastIndexOf("</$tag", ignoreCase = true)
            if (close > open.range.last) {
                val block = html.substring(open.range.last + 1, close)
                if (htmlToText(block).length >= 400) {
                    return block
                }
            }
        }
        // No semantic wrapper: strip the elements that are chrome by definition.
        var out = html
        for (tag in arrayOf("nav", "header", "footer", "aside", "form")) {
            out = out.replace(Regex("(?is)<$tag[^>]*>.{0,200000}?</$tag>"), " ")
        }
        return if (out.length == html.length) null else out
    }

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

    private fun unescape(text: String?): String {
        if (text == null) {
            return ""
        }
        // `&amp;` must be decoded LAST: doing it first turns "&amp;lt;" into
        // "&lt;" which the later replacements then wrongly decode to "<"
        // (classic double-unescape bug).
        return text.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&nbsp;", " ").replace("&rsquo;", "'").replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&amp;", "&")
    }
}
