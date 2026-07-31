package com.vepro.code

import java.util.Locale

/**
 * Key Router — rotates through the user's API keys when the active key hits a
 * rate limit (HTTP 429 / RPM / quota wording). Rotation is silent: the request
 * is retried immediately with the next key — even mid-task — without showing
 * the user an error. When every key has been tried, the original error passes
 * through to the normal retry/error path.
 *
 * Pure JVM (no Android deps) so the regression suite can drive it directly.
 */
internal class KeyRouter(
    private val keys: List<String>,
    startIndex: Int,
    private val store: IndexStore?
) {
    /** Persistence hook for the sticky rotation cursor (backed by Prefs). */
    internal interface IndexStore {
        fun get(): Int
        fun set(index: Int)
    }

    /**
     * The stored cursor, wrapped into range.
     *
     * `Math.floorMod` is API 24 and the app's floor is API 23, so on Android 6
     * this threw NoSuchMethodError the moment a second key existed. The
     * expression below is its exact equivalent for a positive divisor (and
     * `keys.size` is positive here — the empty case never reaches it): `%` alone
     * would keep the sign of a negative stored cursor and index out of bounds.
     *
     * Written arithmetically rather than with an SDK_INT branch on purpose: this
     * file is pure JVM so the regression suite can drive it directly, and
     * importing android.os.Build would end that.
     */
    private var index: Int =
        if (keys.isEmpty()) 0 else ((startIndex % keys.size) + keys.size) % keys.size
    private var rotations: Int = 0

    fun size(): Int = keys.size

    fun currentIndex(): Int = index

    fun currentKey(): String = if (keys.isEmpty()) "" else keys[index]

    /** Resets the per-request rotation budget (every other key may be tried once). */
    fun beginRequest() {
        rotations = 0
    }

    /** Advances to the next key; returns false once all keys were tried. */
    fun rotate(): Boolean {
        if (keys.size < 2 || rotations >= keys.size - 1) {
            return false
        }
        index = (index + 1) % keys.size
        rotations++
        store?.set(index)
        return true
    }

    companion object {
        /**
         * Rate-limit detector: HTTP 429, or the usual RPM/quota phrasing emitted
         * by OpenAI/Anthropic/Gemini and the popular proxies.
         */
        fun isRateLimit(error: LlmClient.LlmException?): Boolean {
            if (error == null) {
                return false
            }
            if (error.httpCode == 429) {
                return true
            }
            // A 4xx that is not 429 is the request's fault, not the key's:
            // rotating on it retries an identical, identically-doomed request
            // once per key. Only 0 (no HTTP status — a gateway that reports the
            // limit in the body) and 5xx are worth text-matching at all.
            if (error.httpCode in 400..499) {
                return false
            }
            // Match the SERVER's text only. The formatted message also carries
            // the request id / cf-ray, and a hex id containing "429" turned
            // every unrelated failure into a key rotation.
            val detail = error.serverDetail
            if (detail.isEmpty()) {
                return false
            }
            val l = detail.lowercase(Locale.US)
            // "429" is matched here too, but only against the SERVER's own
            // text — never against the formatted message, which also carries
            // the request id / cf-ray, and a hex id containing "429" used to
            // turn any unrelated failure into a key rotation.
            return l.contains("429") || l.contains("rate limit") ||
                l.contains("rate_limit") || l.contains("ratelimit") ||
                l.contains("rpm") || l.contains("quota") ||
                l.contains("too many requests") || l.contains("resource exhausted") ||
                l.contains("resource_exhausted")
        }
    }
}
