package github.vega.agent

import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the web/search/anti-bot/download hardening:
 * - Mojeek 4th-engine parser
 * - cross-engine URL dedupe + normalization
 * - extended CAPTCHA detection
 * - download SHA-256 verification helper
 * - human-fetch header / UA-rotation helpers
 *
 * No network is used anywhere here: every test is pure parsing/string
 * work, so the suite is sandbox-safe.
 */
object WebHardeningTests {

    private var assertions = 0

    private fun truth(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError(message)
        }
    }

    // ---- Mojeek parser ----------------------------------------------------

    fun testParseMojeekFindsH2Results() {
        val html = "<html><body><ul class=\"results-standard\">" +
            "<li><h2><a href=\"https://example.com/page1\">First result</a></h2></li>" +
            "<li><h2><a href=\"https://example.org/other\">Second result</a></h2></li>" +
            "</ul></body></html>"
        val hits = Web.parseMojeek(html)
        truth(hits.size == 2, "expected 2 hits, got " + hits.size)
        truth(hits[0].url == "https://example.com/page1", "wrong first url: " + hits[0].url)
        truth(hits[0].title == "First result", "wrong first title: " + hits[0].title)
    }

    fun testParseMojeekSkipsInternalAndRelativeLinks() {
        val html = "<h2><a href=\"https://www.mojeek.com/about\">About Mojeek</a></h2>" +
            "<h2><a href=\"/search?q=test\">internal nav</a></h2>" +
            "<h2><a href=\"https://example.com/real\">Real</a></h2>"
        val hits = Web.parseMojeek(html)
        truth(hits.size == 1, "expected 1 hit, got " + hits.size)
        truth(hits[0].url == "https://example.com/real", "wrong url: " + hits[0].url)
    }

    fun testParseMojeekEmptyOnGarbage() {
        truth(Web.parseMojeek("<html><body>no results here</body></html>").isEmpty(),
            "garbage html should yield no hits")
        truth(Web.parseMojeek("").isEmpty(), "empty html should yield no hits")
    }

    // ---- URL normalization / dedupe ---------------------------------------

    fun testNormalizeSearchUrlCollapsesVariants() {
        val a = Web.normalizeSearchUrl("https://www.Example.com/page/?utm_source=x&fbclid=1")
        val b = Web.normalizeSearchUrl("http://example.com/page")
        truth(a.isNotEmpty(), "normalization must not be empty")
        truth(a == b, "variants must collapse: $a vs $b")
    }

    fun testNormalizeSearchUrlKeepsDistinctPages() {
        val a = Web.normalizeSearchUrl("https://example.com/page1")
        val b = Web.normalizeSearchUrl("https://example.com/page2")
        truth(a != b, "distinct pages must stay distinct")
    }

    fun testNormalizeSearchUrlKeepsMeaningfulQuery() {
        val a = Web.normalizeSearchUrl("https://example.com/search?q=kotlin&page=2")
        truth(a.contains("q=kotlin"), "meaningful params must survive: $a")
        truth(!a.contains("utm_"), "tracking params must go: $a")
    }

    fun testNormalizeSearchUrlRejectsNonHttp() {
        truth(Web.normalizeSearchUrl("ftp://example.com/x").isEmpty(), "ftp must be rejected")
        truth(Web.normalizeSearchUrl("not a url").isEmpty(), "garbage must be rejected")
    }

    fun testDedupeHitsRemovesDuplicatesKeepsOrder() {
        val hits = listOf(
            Web.Hit("t1", "https://example.com/a/", ""),
            Web.Hit("t2", "http://www.example.com/a?utm_medium=y", ""),
            Web.Hit("t3", "https://example.com/b", "")
        )
        val out = Web.dedupeHits(hits)
        truth(out.size == 2, "expected 2 after dedupe, got " + out.size)
        truth(out[0].title == "t1", "first occurrence must win")
        truth(out[1].title == "t3", "distinct page must survive")
    }

    // ---- CAPTCHA detection --------------------------------------------------

    fun testLooksCaptchaCoversClassicWidgets() {
        truth(HumanFetch.looksCaptcha("<div class=\"g-recaptcha\" data-sitekey=\"x\"></div>"),
            "reCAPTCHA div must be detected")
        truth(HumanFetch.looksCaptcha("<script src=\"https://hcaptcha.com/1/api.js\"></script>"),
            "hCaptcha script must be detected")
    }

    fun testLooksCaptchaCoversNewMarkers() {
        truth(HumanFetch.looksCaptcha("<div data-sitekey=\"abc\"></div>"),
            "bare data-sitekey must be detected")
        truth(HumanFetch.looksCaptcha("<script>grecaptcha.execute('key')</script>"),
            "grecaptcha.execute must be detected")
        truth(HumanFetch.looksCaptcha("<div id=\"__cf_chl_\"></div>"),
            "Cloudflare challenge marker must be detected")
    }

    fun testLooksCaptchaIgnoresNormalPages() {
        truth(!HumanFetch.looksCaptcha("<html><body><p>Hello world</p></body></html>"),
            "normal page must not be flagged")
        truth(!HumanFetch.looksCaptcha(null), "null must not be flagged")
        truth(!HumanFetch.looksCaptcha(""), "empty must not be flagged")
    }

    // ---- download integrity -------------------------------------------------

    fun testSha256HexMatchesKnownVector() {
        val dir = Files.createTempDirectory("vega-sha").toFile()
        try {
            val f = File(dir, "abc.txt")
            f.writeText("abc")
            // SHA-256("abc") — the standard test vector.
            val want = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            truth(sha256Hex(f) == want, "sha256 mismatch: " + sha256Hex(f))
        } finally {
            dir.deleteRecursively()
        }
    }

    fun testSha256HexIsLowercaseHex64() {
        val dir = Files.createTempDirectory("vega-sha2").toFile()
        try {
            val f = File(dir, "x.bin")
            f.writeBytes(byteArrayOf(0, 1, 2, 3))
            val h = sha256Hex(f)
            truth(h.length == 64 && h.all { it in '0'..'9' || it in 'a'..'f' },
                "must be 64 lowercase hex chars: $h")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- human-fetch helpers ------------------------------------------------

    fun testHumanHeadersCarryBotTellsFixes() {
        val headers = Web.humanHeaders()
        val lang = headers["Accept-Language"] ?: ""
        truth(lang.isNotEmpty(), "Accept-Language must be present")
        truth((headers["Upgrade-Insecure-Requests"] ?: "") == "1",
            "Upgrade-Insecure-Requests must be 1")
    }

    fun testNextUaRotatesThroughPool() {
        val seen = HashSet<String>()
        repeat(8) { seen.add(Web.nextUa()) }
        truth(seen.size > 1, "UA pool must rotate, saw only: $seen")
        for (ua in seen) {
            truth(ua.contains("Mozilla/5.0") && ua.contains("Chrome"),
                "UA must look like a real mobile browser: $ua")
        }
    }
}
