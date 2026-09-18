package github.vega.agent

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fast-path web extraction: streaming single-pass HTML tokenize + readability
 * scoring, built for the `web_fetch` / `web_fetch_many` tools.
 *
 * The old path downloaded the whole page and ran regexes over it; this one
 * reads the response in 16 KB blocks, tokenizes each block with
 * [FastHtmlTokenizer] (no regex in the hot path), scores content blocks with
 * [Readability2], and disconnects the moment the text budget is reached — so
 * first text arrives in under a second and a 3 MB page never costs 3 MB.
 *
 * [Web.fetch] tries this first and falls back to its careful path whenever
 * the fast path cannot vouch for a page (binary content, HTTP errors,
 * anti-bot interstitials, too-thin text).
 */
object WebFast {

    /** Options for [fetchFast]. */
    class FetchOpts(
        /** Max body characters returned. */
        val maxChars: Int = 38000,
        /** False forces the careful [Web.fetch] path (used for the fallback). */
        val fast: Boolean = true,
        /** When set, the text is re-ranked for this extraction query. */
        val extractionPrompt: String? = null
    )

    /** One resolved hyperlink. */
    class PageLink(val url: String, val label: String)

    /** The fast path's extraction result for one page. */
    class PageDigest(
        val title: String,
        val url: String,
        val text: String,
        val links: List<PageLink>,
        val downloadables: List<PageLink>
    )

    /** Streaming limits for [fetchStreaming]. */
    class StreamBudget(
        /** Stop reading once this many body-text chars were emitted. */
        val maxTextChars: Int = 42000,
        /** Absolute byte ceiling on the response body. */
        val maxBytes: Long = 3000000L,
        /** Wall-clock deadline for the whole stream. */
        val deadlineMs: Long = 45000L
    )

    /** Outcome of [fetchStreaming]. */
    class StreamInfo(
        val code: Int,
        val finalUrl: String,
        val contentType: String,
        val binary: Boolean,
        /** First ~8 KB of decoded raw markup (anti-bot sniffing). */
        val headSample: String
    )

    /**
     * Index-based, single-pass HTML tokenizer over a [Reader].
     *
     * States are Text / Tag / Skip (script, style, head, noscript,
     * template are swallowed whole). Emits text runs (entities decoded via
     * [Web.unescape], tags stripped), the document title, hyperlinks with
     * bounded labels, and block boundaries (article, main, p, div, section,
     * h1..h6 and friends) for [Readability2].
     *
     * No regex anywhere near the hot path; incomplete tags at a chunk
     * boundary simply wait for the next [feed].
     */
    class FastHtmlTokenizer(private val emit: (Event) -> Unit) {

        /** Tokenizer output. */
        sealed class Event {
            /** A run of decoded body text (entities resolved, tags stripped). */
            class Text(val text: String) : Event()
            /** A hyperlink: raw href + decoded label text. */
            class Link(val href: String, val label: String) : Event()
            /** The document title (decoded). */
            class Title(val text: String) : Event()
            /** A structural block boundary. */
            class Block(val tag: String, val open: Boolean) : Event()
        }

        companion object {
            /** One-shot tokenize of a complete document (tests, mostly). */
            fun tokenize(html: String): List<Event> {
                val out = ArrayList<Event>()
                val tokenizer = FastHtmlTokenizer { out.add(it) }
                tokenizer.feed(html)
                tokenizer.finish()
                return out
            }

            /** One-shot tokenize from a [Reader], in 16 KB chunks. */
            fun tokenize(reader: Reader): List<Event> {
                val out = ArrayList<Event>()
                val tokenizer = FastHtmlTokenizer { out.add(it) }
                tokenizer.readFrom(reader)
                return out
            }

            private val BLOCK_TAGS = hashSetOf(
                "article", "main", "p", "div", "section",
                "h1", "h2", "h3", "h4", "h5", "h6",
                "li", "ul", "ol", "blockquote", "pre",
                "header", "footer", "nav", "aside",
                "table", "tr", "td", "th",
                "figure", "figcaption", "form"
            )
            private val SKIP_TAGS = hashSetOf("script", "style", "noscript", "template", "head")
            private const val LABEL_CAP = 500
            private const val TITLE_CAP = 1000
            private const val TEXT_FLUSH = 4096
            private const val MAX_TAG_LEN = 32768
        }

        /**
         * Body-text characters emitted so far — the streaming fetcher reads
         * this to disconnect early at the text budget.
         */
        var textCharsEmitted = 0
            private set

        private val buf = StringBuilder()
        private var pos = 0
        private var skipTag: String? = null
        private var skipEnteredAt = 0
        // Bytes skipped inside the current skip-tag without finding its
        // closer, accumulated across feeds: compaction deletes counted bytes,
        // so a plain buffer-span check would never fire on a long stream.
        private var skipUnclosedBytes = 0L
        private var skipHeadAfterTitle = false
        private var inTitle = false
        private val titleBuf = StringBuilder()
        private val textBuf = StringBuilder()
        private var linkHref: String? = null
        private val linkLabel = StringBuilder()
        private var finished = false

        /** Feeds the next decoded chunk; tokenizes as much as possible. */
        fun feed(chunk: String) {
            if (finished || chunk.isEmpty()) {
                return
            }
            buf.append(chunk)
            scan()
            if (pos > 65536) {
                // Bytes discarded while skipping still count toward the
                // unclosed-skip-tag bailout below.
                if (skipTag != null) {
                    skipUnclosedBytes += pos
                }
                skipEnteredAt = Math.max(0, skipEnteredAt - pos)
                buf.delete(0, pos)
                pos = 0
            }
        }

        /**
         * Feeds the whole [reader] in 16 KB chunks, then finishes — the
         * explicit [Reader] entry point (the streaming fetcher feeds decoded
         * network chunks directly through [feed]). A reader failure
         * propagates: silently treating it as EOF would hand the caller a
         * valid-looking but truncated token stream with no signal.
         */
        fun readFrom(reader: Reader) {
            if (finished) {
                return
            }
            val chars = CharArray(16384)
            while (true) {
                val n = reader.read(chars)
                if (n <= 0) {
                    break
                }
                feed(String(chars, 0, n))
            }
            finish()
        }

        /** Flushes pending text, title and link state at end of input. */
        fun finish() {
            if (finished) {
                return
            }
            finished = true
            flushText()
            if (inTitle) {
                inTitle = false
                val t = Web.unescape(titleBuf.toString()).trimJava()
                if (t.isNotEmpty()) {
                    emit(Event.Title(t))
                }
            }
            titleBuf.setLength(0)
            val href = linkHref
            linkHref = null
            if (href != null) {
                emit(Event.Link(href, Web.unescape(linkLabel.toString()).trimJava()))
            }
            linkLabel.setLength(0)
            buf.setLength(0)
            pos = 0
        }

        private fun emitText(text: String) {
            if (text.isEmpty()) {
                return
            }
            textCharsEmitted += text.length
            emit(Event.Text(text))
        }

        private fun scan() {
            val n = buf.length
            while (pos < n) {
                val skipping = skipTag
                if (skipping != null) {
                    if (!skipForward(skipping, n)) {
                        return
                    }
                    continue
                }
                if (buf[pos] == '<') {
                    if (!tryTag(n)) {
                        return // incomplete tag — wait for more input
                    }
                } else {
                    routeChar(buf[pos])
                    pos++
                }
            }
        }

        /**
         * Swallows everything up to `</tag>`. `head` additionally lets a
         * `<title>` through (it hides inside `<head>`). Returns false when
         * the close tag has not arrived yet — the tail is kept for the next
         * [feed].
         */
        private fun skipForward(tag: String, n: Int): Boolean {
            if (tag == "head") {
                val titleAt = findOpenTag("title", pos, n)
                val headAt = findCloseTag("head", pos, n)
                if (titleAt >= 0 && (headAt < 0 || titleAt < headAt)) {
                    pos = titleAt
                    skipTag = null
                    skipHeadAfterTitle = true
                    return true
                }
            }
            val closeAt = findCloseTag(tag, pos, n)
            if (closeAt < 0) {
                // Unclosed skip-tag (a "<script>" inside a JS string, a
                // <head> that never closes): never swallow the rest of the
                // document — after 64 KB of skipped bytes give up and resume
                // tokenizing at the next '<', so structure recovers without
                // emitting the skipped junk as body text.
                skipUnclosedBytes += (n - pos).coerceAtLeast(0)
                if (skipUnclosedBytes > 65536) {
                    skipTag = null
                    skipHeadAfterTitle = false
                    skipUnclosedBytes = 0
                    // Resume AFTER the opener: skipEnteredAt still points at
                    // the opener's '<' (tryTag advances past the tag only
                    // after onOpenTag runs), so searching from it would find
                    // the opener itself and loop forever. Searching from pos
                    // also never moves backward.
                    val resumeFrom = Math.max(skipEnteredAt + 1, pos)
                    val nextTag = buf.indexOf("<", resumeFrom)
                    pos = if (nextTag >= 0) nextTag else n
                    return true
                }
                // Keep a possibly-partial "</tag" tail for the next feed —
                // but only rewind to a '<' near the end of the buffer.
                // Rewinding to an earlier '<' would swallow complete tags
                // (and their text) already sitting in the buffer.
                val lt = buf.lastIndexOf("<", n - 1)
                pos = if (lt >= pos && n - lt <= tag.length + 3) lt else n
                return false
            }
            pos = closeAt
            skipTag = null
            return true
        }

        /** Index of `</tag` (tag-name boundary checked), or -1. */
        private fun findCloseTag(tag: String, from: Int, n: Int): Int {
            var i = from
            while (i < n) {
                i = buf.indexOf("</", i)
                if (i < 0) {
                    return -1
                }
                if (matchName(i + 2, tag)) {
                    val after = i + 2 + tag.length
                    if (after >= n) {
                        return -1 // cut off — wait for more input
                    }
                    val c = buf[after]
                    if (c == '>' || c == '/' || c <= ' ') {
                        return i
                    }
                }
                i += 2
            }
            return -1
        }

        /** Index of `<tag` (not `</`, `<!--` or `<!`), or -1. */
        private fun findOpenTag(tag: String, from: Int, n: Int): Int {
            var i = from
            while (i < n) {
                i = buf.indexOf("<", i)
                if (i < 0) {
                    return -1
                }
                if (i + 1 < n && (buf[i + 1] == '/' || buf[i + 1] == '!')) {
                    i += 2
                    continue
                }
                if (matchName(i + 1, tag)) {
                    val after = i + 1 + tag.length
                    if (after >= n) {
                        return -1 // cut off — wait for more input
                    }
                    val c = buf[after]
                    if (c == '>' || c == '/' || c <= ' ') {
                        return i
                    }
                }
                i++
            }
            return -1
        }

        /** Case-insensitive tag-name match at [at] ([tag] is lowercase). */
        private fun matchName(at: Int, tag: String): Boolean {
            if (at + tag.length > buf.length) {
                return false
            }
            for (k in tag.indices) {
                val a = buf[at + k]
                if (a != tag[k] && a.lowercaseChar() != tag[k]) {
                    return false
                }
            }
            return true
        }

        /** Parses the tag at buf[pos] (== '<'). False = incomplete input. */
        private fun tryTag(n: Int): Boolean {
            // <!-- … -->
            if (buf.startsWith("<!--", pos)) {
                val end = buf.indexOf("-->", pos + 4)
                if (end < 0) {
                    return false
                }
                pos = end + 3
                return true
            }
            // <!DOCTYPE …> / <? … ?>
            if (pos + 1 < n && (buf[pos + 1] == '!' || buf[pos + 1] == '?')) {
                val end = buf.indexOf(">", pos + 2)
                if (end < 0) {
                    return false
                }
                pos = end + 1
                return true
            }
            // Not a tag at all ("a < b") — emit a literal '<'. But a '<'
            // (or '</') at the very end of the buffer is INCOMPLETE, not a
            // non-tag: the tag name may arrive in the next chunk, and
            // consuming the '<' now would unrecoverably turn the tag into
            // text (chunk-split tags must survive).
            val tagStart = isTagStart(pos, n)
            if (tagStart == null) {
                return false // incomplete — wait for more input
            }
            if (!tagStart) {
                routeChar('<')
                pos++
                return true
            }
            // Find the end of the tag, respecting quoted attribute values.
            var i = pos + 1
            var quote = '\u0000'
            while (i < n) {
                val c = buf[i]
                if (quote != '\u0000') {
                    if (c == quote) {
                        quote = '\u0000'
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '>') {
                    break
                }
                if (i - pos > MAX_TAG_LEN) {
                    // Pathological tag (a megabyte of attributes) — treat the
                    // '<' as literal text and move on instead of buffering it.
                    routeChar('<')
                    pos++
                    return true
                }
                i++
            }
            if (i >= n) {
                return false // incomplete — wait for more input
            }
            parseTag(buf.substring(pos + 1, i))
            pos = i + 1
            return true
        }

        /**
         * Whether buf[at] == '<' opens a tag. Null = undecidable: the '<'
         * (or '</') sits at the end of the buffer and the tag name may still
         * arrive — the caller must wait for more input, not emit a literal.
         */
        private fun isTagStart(at: Int, n: Int): Boolean? {
            var k = at + 1
            if (k >= n) {
                return null
            }
            if (buf[k] == '/') {
                k++
                if (k >= n) {
                    return null
                }
            }
            return buf[k].isLetter()
        }

        private fun parseTag(inner: String) {
            var s = inner.trimJava()
            var closing = false
            if (s.startsWith("/")) {
                closing = true
                s = s.substring(1).trimJava()
            }
            if (s.endsWith("/")) {
                s = s.substring(0, s.length - 1).trimJava()
            }
            var nameEnd = s.length
            for (i in s.indices) {
                val c = s[i]
                if (c <= ' ' || c == '/') {
                    nameEnd = i
                    break
                }
            }
            val name = s.substring(0, nameEnd).lowercase()
            if (name.isEmpty()) {
                return
            }
            val rest = if (nameEnd < s.length) s.substring(nameEnd + 1) else ""
            if (closing) {
                onCloseTag(name)
                return
            }
            when {
                name in SKIP_TAGS -> {
                    flushText()
                    skipTag = name
                    skipEnteredAt = pos
                    skipUnclosedBytes = 0
                }
                name == "title" -> {
                    flushText()
                    inTitle = true
                    titleBuf.setLength(0)
                }
                name == "a" -> {
                    flushText()
                    // An unclosed <a> still yields its link.
                    val pending = linkHref
                    if (pending != null) {
                        emit(Event.Link(pending, Web.unescape(linkLabel.toString()).trimJava()))
                    }
                    linkHref = parseAttr(rest, "href")?.take(2048)
                    linkLabel.setLength(0)
                }
                name == "br" || name == "hr" -> {
                    flushText()
                    emitText("\n")
                }
                name in BLOCK_TAGS -> {
                    flushText()
                    emit(Event.Block(name, true))
                }
                // Inline tags (b, span, …) carry no structure the extractor needs.
            }
        }

        private fun onCloseTag(name: String) {
            when {
                name == "title" -> closeTitle()
                name == "a" -> {
                    val href = linkHref
                    linkHref = null
                    if (href != null) {
                        flushText()
                        emit(Event.Link(href, Web.unescape(linkLabel.toString()).trimJava()))
                        linkLabel.setLength(0)
                    }
                }
                name in SKIP_TAGS -> {
                    // A stray skip-tag close (</head> with no opener) also
                    // ends a dangling <title>, so body text stops routing
                    // into the title buffer.
                    closeTitle()
                    skipHeadAfterTitle = false
                }
                name in BLOCK_TAGS -> {
                    flushText()
                    emit(Event.Block(name, false))
                }
            }
        }

        private fun closeTitle() {
            if (!inTitle) {
                return
            }
            inTitle = false
            val t = Web.unescape(titleBuf.toString()).trimJava()
            titleBuf.setLength(0)
            if (t.isNotEmpty()) {
                emit(Event.Title(t))
            }
            if (skipHeadAfterTitle) {
                // The title was captured out of a skipped <head> — resume
                // skipping the rest of it.
                skipHeadAfterTitle = false
                flushText()
                skipTag = "head"
                skipEnteredAt = pos
                skipUnclosedBytes = 0
            }
        }

        private fun routeChar(c: Char) {
            when {
                inTitle -> {
                    if (titleBuf.length < TITLE_CAP) {
                        titleBuf.append(c)
                    }
                }
                linkHref != null -> {
                    if (linkLabel.length < LABEL_CAP) {
                        linkLabel.append(c)
                    }
                }
                else -> {
                    textBuf.append(c)
                    if (textBuf.length >= TEXT_FLUSH) {
                        flushText()
                    }
                }
            }
        }

        private fun flushText() {
            if (textBuf.isEmpty()) {
                return
            }
            var end = textBuf.length
            // Never split a character reference across the flush boundary.
            val amp = textBuf.lastIndexOf("&")
            if (amp >= 0 && amp > end - 12) {
                var hasSemi = false
                for (i in amp until end) {
                    if (textBuf[i] == ';') {
                        hasSemi = true
                        break
                    }
                }
                if (!hasSemi) {
                    end = amp
                }
            }
            if (end > 0) {
                emitText(Web.unescape(textBuf.substring(0, end)))
                textBuf.delete(0, end)
            }
        }

        /**
         * Tolerantly extracts one attribute value: `href="…"`, `href='…'`,
         * `href=…` and whitespace around `=`. Case-insensitive names.
         */
        private fun parseAttr(attrs: String, wanted: String): String? {
            var i = 0
            val n = attrs.length
            while (i < n) {
                while (i < n && attrs[i] <= ' ') {
                    i++
                }
                val nameStart = i
                while (i < n && attrs[i] != '=' && attrs[i] > ' ') {
                    i++
                }
                val attrName = attrs.substring(nameStart, i).lowercase()
                while (i < n && attrs[i] <= ' ') {
                    i++
                }
                if (i < n && attrs[i] == '=') {
                    i++
                    while (i < n && attrs[i] <= ' ') {
                        i++
                    }
                    if (i >= n) {
                        return null
                    }
                    val value: String
                    val q = attrs[i]
                    if (q == '"' || q == '\'') {
                        i++
                        val valueStart = i
                        while (i < n && attrs[i] != q) {
                            i++
                        }
                        value = attrs.substring(valueStart, i)
                        if (i < n) {
                            i++
                        }
                    } else {
                        val valueStart = i
                        while (i < n && attrs[i] > ' ') {
                            i++
                        }
                        value = attrs.substring(valueStart, i)
                    }
                    if (attrName == wanted) {
                        return value
                    }
                }
                // Valueless attribute (or garbage) — skip to the next one.
                while (i < n && attrs[i] > ' ') {
                    i++
                }
            }
            return null
        }
    }

    /**
     * Readability-style main-content scoring over [FastHtmlTokenizer] events.
     *
     * Each block frame accumulates its text; the score is body text minus a
     * link-density penalty, with paragraph and comma bonuses (strong prose
     * signals) and semantic multipliers (`article`/`main` win over
     * `nav`/`footer`/`header`/`aside`). The highest-scoring frame with a
     * meaningful amount of text wins; otherwise the whole page is returned.
     */
    object Readability2 {
        private class Frame(val tag: String) {
            val text = StringBuilder()
            var linkChars = 0
            var paras = 0
            var commas = 0
        }

        /** Returns the winning frame's text, or the whole page as fallback. */
        fun extractMainText(events: List<FastHtmlTokenizer.Event>): String {
            val stack = ArrayList<Frame>()
            val closed = ArrayList<Frame>()
            val all = Frame("body")
            for (e in events) {
                when (e) {
                    is FastHtmlTokenizer.Event.Text -> {
                        appendText(stack, all, e.text, inLink = false)
                    }
                    is FastHtmlTokenizer.Event.Link -> {
                        // Link labels count as body text (as in the careful
                        // path) but carry the link penalty.
                        if (e.label.isNotEmpty()) {
                            appendText(stack, all, e.label, inLink = true)
                        }
                    }
                    is FastHtmlTokenizer.Event.Block -> {
                        if (e.open) {
                            if (e.tag == "p") {
                                for (f in stack) {
                                    f.paras++
                                }
                            }
                            stack.add(Frame(e.tag))
                            if (stack.size > 64) {
                                // Pathological nesting — drop the outermost.
                                closed.add(stack.removeAt(0))
                            }
                        } else {
                            // Pop to the matching opener; stray closers are ignored.
                            var idx = stack.size - 1
                            while (idx >= 0 && stack[idx].tag != e.tag) {
                                idx--
                            }
                            if (idx >= 0) {
                                while (stack.size > idx) {
                                    closed.add(stack.removeAt(stack.size - 1))
                                }
                            }
                        }
                    }
                    is FastHtmlTokenizer.Event.Title -> {
                        // Titles are reported separately, not in the body.
                    }
                }
            }
            for (f in stack) {
                closed.add(f)
            }
            var best: Frame? = null
            var bestScore = 0.0
            for (f in closed) {
                val s = score(f)
                if (s > bestScore) {
                    bestScore = s
                    best = f
                }
            }
            val body = best?.text.toString().trimJava()
            return if (body.length >= 200) body else all.text.toString().trimJava()
        }

        private fun appendText(
            stack: List<Frame>,
            all: Frame,
            text: String,
            inLink: Boolean
        ) {
            var commas = 0
            for (c in text) {
                if (c == ',') {
                    commas++
                }
            }
            for (f in stack) {
                f.text.append(text)
                f.commas += commas
                if (inLink) {
                    f.linkChars += text.length
                }
            }
            all.text.append(text)
            all.commas += commas
            if (inLink) {
                all.linkChars += text.length
            }
        }

        private fun score(f: Frame): Double {
            val len = f.text.length.toDouble()
            if (len < 120.0) {
                return 0.0
            }
            val linkDensity = f.linkChars / len
            var s = len * (1.0 - 1.6 * linkDensity)
            // Paragraph breaks and commas are strong prose signals.
            s += f.paras * 120.0 + f.commas * 24.0
            // Semantic hints: content containers win, chrome loses.
            when (f.tag) {
                "article", "main" -> s *= 1.6
                "nav", "footer", "header", "aside" -> s *= 0.3
                "li", "td", "th" -> s *= 0.5
            }
            return s
        }
    }

    private class CountingInputStream(wrapped: InputStream) : FilterInputStream(wrapped) {
        var count = 0L
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
            }
            return n
        }

        override fun read(): Int {
            val n = super.read()
            if (n >= 0) {
                count++
            }
            return n
        }
    }

    private fun isBinaryMedia(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.startsWith("image/") || ct.startsWith("video/") ||
            ct.startsWith("audio/") || ct.startsWith("font/")
    }

    /**
     * Response charset: the Content-Type header first, then a `<meta
     * charset>` sniff over the first 4 KB, else UTF-8.
     */
    private fun detectCharset(contentType: String, sniff: ByteArray, sniffLen: Int): Charset {
        fun forName(name: String): Charset? {
            val clean = name.trimJava().trim('"', '\'')
            if (clean.isEmpty()) {
                return null
            }
            return try {
                if (Charset.isSupported(clean)) Charset.forName(clean) else null
            } catch (e: Exception) {
                null
            }
        }
        val headerEq = contentType.lowercase().indexOf("charset=")
        if (headerEq >= 0) {
            var v = contentType.substring(headerEq + 8)
            val semi = v.indexOf(';')
            if (semi >= 0) {
                v = v.substring(0, semi)
            }
            forName(v)?.let { return it }
        }
        if (sniffLen > 0) {
            val head = String(sniff, 0, sniffLen, StandardCharsets.ISO_8859_1).lowercase()
            // Scan EVERY <meta> in the 4 KB window, not just the first: the
            // charset declaration often sits behind viewport/og: tags.
            var from = 0
            while (true) {
                val meta = head.indexOf("<meta", from)
                if (meta < 0) {
                    break
                }
                val seg = head.substring(meta, Math.min(head.length, meta + 500))
                val csEq = seg.indexOf("charset=")
                if (csEq >= 0) {
                    var v = seg.substring(csEq + 8).trimJava()
                    if (v.startsWith("\"") || v.startsWith("'")) {
                        v = v.substring(1)
                    }
                    var end = 0
                    while (end < v.length) {
                        val c = v[end]
                        if (!(c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == ':')) {
                            break
                        }
                        end++
                    }
                    forName(v.substring(0, end))?.let { return it }
                }
                from = meta + 5
            }
        }
        return StandardCharsets.UTF_8
    }

    /**
     * Streams [url] in 16 KB blocks with the same URL policy, browser headers,
     * cookie replay and redirect validation as [Web]'s careful path, feeding
     * each decoded block to the tokenizer and [onEvent].
     *
     * Disconnects early the moment [StreamBudget.maxTextChars] body-text
     * characters were emitted — a long page costs only its head, not the
     * whole download. Returns null only when the URL itself is unusable;
     * HTTP statuses and binary content come back as a [StreamInfo] for the
     * caller to judge.
     */
    @Throws(Exception::class)
    fun fetchStreaming(
        url: String?,
        token: CancellationToken,
        budget: StreamBudget,
        onEvent: (FastHtmlTokenizer.Event) -> Unit
    ): StreamInfo? {
        token.throwIfCancelled()
        NetworkPolicy.requireSafeHttps(url)
        val cleaned = Util.cleanUrl(url) ?: return null
        var current = cleaned
        var connection: HttpURLConnection? = null
        var watch: CancellationToken.Registration? = null
        try {
            var active = URL(current).openConnection() as HttpURLConnection
            connection = active
            watch = token.watchConnection(active)
            active.requestMethod = "GET"
            active.instanceFollowRedirects = false
            active.connectTimeout = 15000
            active.readTimeout = 30000
            Web.applyBrowserHeaders(active, null, true)
            var code = active.responseCode
            var redirects = 0
            while (code in 300..399 && redirects < 6) {
                val location = active.getHeaderField("Location") ?: break
                val next = URL(URL(current), location).toString()
                NetworkPolicy.requireSafeHttps(next)
                watch?.close()
                active.disconnect()
                val previous = current
                current = next
                active = URL(current).openConnection() as HttpURLConnection
                connection = active
                watch = token.watchConnection(active)
                active.requestMethod = "GET"
                active.instanceFollowRedirects = false
                active.connectTimeout = 15000
                active.readTimeout = 30000
                Web.applyBrowserHeaders(active, previous, true)
                code = active.responseCode
                redirects++
            }
            token.throwIfCancelled()
            val finalUrl = active.url.toString()
            NetworkPolicy.requireSafeHttps(finalUrl)
            val contentType = active.contentType ?: ""
            if (isBinaryMedia(contentType)) {
                return StreamInfo(code, finalUrl, contentType, binary = true, headSample = "")
            }
            val rawInput: InputStream =
                if (code >= 400) {
                    active.errorStream ?: return StreamInfo(code, finalUrl, contentType, false, "")
                } else {
                    active.inputStream
                }
            val counting = CountingInputStream(rawInput)
            // Sniff the first 4 KB for <meta charset>, then decode the whole
            // stream incrementally through one InputStreamReader so split
            // multi-byte sequences survive block boundaries.
            val sniff = ByteArray(4096)
            var sniffLen = 0
            while (sniffLen < sniff.size) {
                token.throwIfCancelled()
                val n = counting.read(sniff, sniffLen, sniff.size - sniffLen)
                if (n < 0) {
                    break
                }
                sniffLen += n
            }
            val charset = detectCharset(contentType, sniff, sniffLen)
            val reader: Reader = InputStreamReader(
                SequenceInputStream(ByteArrayInputStream(sniff, 0, sniffLen), counting),
                charset
            )
            val rawHead = StringBuilder()
            val tokenizer = FastHtmlTokenizer(onEvent)
            val block = CharArray(16384)
            val deadline = System.currentTimeMillis() + budget.deadlineMs
            try {
                while (true) {
                    token.throwIfCancelled()
                    val n = reader.read(block, 0, block.size)
                    if (n < 0) {
                        break
                    }
                    val chunk = String(block, 0, n)
                    if (rawHead.length < 8192) {
                        rawHead.append(chunk, 0, Math.min(chunk.length, 8192 - rawHead.length))
                    }
                    tokenizer.feed(chunk)
                    // EARLY TERMINATION: the text budget is reached — the rest
                    // of the page would only cost bandwidth.
                    if (tokenizer.textCharsEmitted >= budget.maxTextChars) {
                        break
                    }
                    if (counting.count > budget.maxBytes) {
                        break
                    }
                    if (System.currentTimeMillis() > deadline) {
                        break
                    }
                }
            } finally {
                try {
                    reader.close()
                } catch (ignored: Exception) {
                }
            }
            tokenizer.finish()
            return StreamInfo(code, finalUrl, contentType, false, rawHead.toString())
        } finally {
            // #12: no Thread.interrupted() — nothing above clears the flag.
            watch?.close()
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Fast page read: streams the page through the tokenizer, scores content
     * with [Readability2], and returns title + main text + links under
     * [FetchOpts.maxChars].
     *
     * Returns null whenever the fast path cannot vouch for the page — binary
     * content, HTTP errors, anti-bot interstitials, or too-thin text — so
     * the caller ([Web.fetch]) falls back to the careful path. Never throws
     * except on cancellation.
     */
    fun fetchFast(url: String?, token: CancellationToken, opts: FetchOpts): PageDigest? {
        try {
            return fetchFastOrThrow(url, token, opts)
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (e: Exception) {
            // The careful path takes over and reports readably.
            return null
        }
    }

    @Throws(Exception::class)
    private fun fetchFastOrThrow(
        url: String?,
        token: CancellationToken,
        opts: FetchOpts
    ): PageDigest? {
        token.throwIfCancelled()
        val events = ArrayList<FastHtmlTokenizer.Event>(4096)
        var title = ""
        val rawLinks = ArrayList<Pair<String, String>>()
        val info = fetchStreaming(
            url, token,
            StreamBudget(
                maxTextChars = opts.maxChars + 4000,
                maxBytes = 3000000L,
                deadlineMs = 45000L
            )
        ) { event ->
            when (event) {
                is FastHtmlTokenizer.Event.Title -> if (title.isEmpty()) {
                    title = event.text
                }
                is FastHtmlTokenizer.Event.Link -> rawLinks.add(event.href to event.label)
                else -> {
                }
            }
            events.add(event)
        } ?: return null
        // Anything the fast path cannot vouch for goes to the careful path:
        // binary content, HTTP errors (which own the human-retry logic
        // there), and anti-bot interstitials.
        if (info.binary) {
            return null
        }
        if (info.code < 200 || info.code >= 300) {
            return null
        }
        if (Web.looksBlocked(info.headSample)) {
            return null
        }
        var text = Readability2.extractMainText(events)
        if (text.length < 200) {
            return null // too thin — let the careful path try
        }
        if (text.length > opts.maxChars) {
            text = text.substring(0, opts.maxChars)
        }
        // Resolve + classify links (first-seen label wins, as in the careful
        // path). The tokenizer already decoded entities — never unescape
        // twice.
        val seen = HashSet<String>()
        val links = ArrayList<PageLink>()
        val downloadables = ArrayList<PageLink>()
        for ((href, label) in rawLinks) {
            token.throwIfCancelled()
            val abs = Web.resolveUrl(info.finalUrl, href) ?: continue
            if (!seen.add(abs)) {
                continue
            }
            val cleanLabel = label.replace(Regex("\\s+"), " ").trimJava().take(90)
            val downloadable = Web.MEDIA_EXT.containsMatchIn(abs) ||
                cleanLabel.contains("download", ignoreCase = true) ||
                cleanLabel.contains("دانلود")
            val link = PageLink(abs, cleanLabel)
            if (downloadable) {
                if (downloadables.size < 40) {
                    downloadables.add(link)
                }
            } else {
                if (links.size < 60) {
                    links.add(link)
                }
            }
        }
        val prompt = opts.extractionPrompt
        if (!prompt.isNullOrBlankJava()) {
            // Extract-then-compress: the mini-subagent pass happens in the
            // engine loop — the fast path just makes the text compact and
            // query-relevant.
            text = rankChunksForPrompt(text, prompt, opts.maxChars)
        }
        return PageDigest(
            title = title.trimJava().take(300),
            url = info.finalUrl,
            text = text,
            links = links,
            downloadables = downloadables
        )
    }

    /**
     * Fast-reads several pages in parallel on a bounded daemon pool and
     * returns one digest per URL, numbered `=== [n] title — url ===`
     * (1-based, matching the input order; failures keep their number with
     * a short note). Cancellation or the ~30 s overall deadline tears down
     * the futures and the pool.
     */
    fun prefetch(
        urls: List<String>,
        token: CancellationToken,
        perPageChars: Int = 8000,
        parallelism: Int = 4
    ): List<String> {
        if (urls.isEmpty()) {
            return emptyList()
        }
        val poolSize = Math.min(Math.max(parallelism, 1), urls.size)
        val pool = Executors.newFixedThreadPool(poolSize, Web.daemonFactory("vega-prefetch"))
        try {
            val futures = urls.mapIndexed { index, url ->
                pool.submit(Callable { fetchDigestForPrefetch(index, url, token, perPageChars) })
            }
            val out = ArrayList<String>()
            val deadline = System.currentTimeMillis() + 30000L
            for (i in futures.indices) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0 || token.isCancelled) {
                    break
                }
                try {
                    out.add(futures[i].get(remaining, TimeUnit.MILLISECONDS))
                } catch (e: Exception) {
                    if (isCancellation(e)) {
                        throw CancellationToken.CancelledException()
                    }
                    out.add(prefetchFailure(i, urls[i]))
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

    private fun isCancellation(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is CancellationToken.CancelledException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun fetchDigestForPrefetch(
        index: Int,
        url: String,
        token: CancellationToken,
        perPageChars: Int
    ): String {
        return try {
            token.throwIfCancelled()
            val digest = fetchFast(url, token, FetchOpts(maxChars = perPageChars))
            if (digest == null) {
                prefetchFailure(index, url)
            } else {
                val title = digest.title.ifEmpty { url }
                "=== [${index + 1}] $title — ${digest.url} ===\n${digest.text}"
            }
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (e: Exception) {
            prefetchFailure(index, url)
        }
    }

    private fun prefetchFailure(index: Int, url: String): String =
        "=== [${index + 1}] (could not be read) — $url ==="

    /**
     * Ranks ~400-char chunks of [text] by keyword overlap with [prompt]
     * (extract-then-compress): the most query-relevant chunks come first,
     * capped at [maxChars]. A note is appended so the model knows the text
     * is pre-filtered and reordered, not document order.
     */
    private fun rankChunksForPrompt(text: String, prompt: String, maxChars: Int): String {
        val keywords = prompt.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }.toSet()
        if (keywords.isEmpty()) {
            return text
        }
        // ~400-char chunks on line boundaries.
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        for (line in text.split('\n')) {
            if (cur.length + line.length + 1 > 400 && cur.isNotEmpty()) {
                chunks.add(cur.toString())
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) {
                cur.append('\n')
            }
            cur.append(line)
        }
        if (cur.isNotEmpty()) {
            chunks.add(cur.toString())
        }
        val scored = chunks.mapIndexed { i, chunk ->
            val lower = chunk.lowercase()
            var hits = 0
            for (kw in keywords) {
                if (lower.contains(kw)) {
                    hits++
                }
            }
            Triple(i, hits, chunk)
        }.sortedWith(compareByDescending<Triple<Int, Int, String>> { it.second }.thenBy { it.first })
        val note =
            "\n\n[Note: this page text was pre-filtered for your extraction " +
                "prompt — chunks are ordered by relevance, not document order.]"
        // Reserve room for the note so the result never exceeds maxChars.
        val chunkBudget = Math.max(0, maxChars - note.length)
        val out = StringBuilder()
        for ((_, _, chunk) in scored) {
            if (out.length + chunk.length + 1 > chunkBudget) {
                break
            }
            if (out.isNotEmpty()) {
                out.append("\n\n")
            }
            out.append(chunk)
        }
        out.append(note)
        return out.toString()
    }
}
