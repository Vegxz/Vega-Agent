package com.vepro.code

import org.json.JSONArray
import org.json.JSONObject

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Exact stand-ins for the `java.lang.String` helpers whose Kotlin stdlib
 * lookalikes are not actually equivalent.
 *
 * This file exists because of a real bug the differential test harness caught:
 * `Think.visible("\u001B x")` returned `"x"` in the original Java but
 * `"\u001B x"` in a straight Kotlin translation.
 *
 * ### Why they differ
 *
 * `java.lang.String.trim()` is defined on **code units** — it removes every
 * character whose value is `<= ' '` (U+0020) from both ends and knows nothing
 * about Unicode. `kotlin.text.trim()` is defined on **Unicode properties** — it
 * removes every character for which `Char.isWhitespace()` is true. The two sets
 * overlap, but neither contains the other:
 *
 * | characters                     | Java `trim()` | Kotlin `trim()` |
 * |--------------------------------|---------------|-----------------|
 * | U+0000 NUL .. U+0008 BS        | stripped      | kept            |
 * | U+0009 TAB, U+000A LF, U+000D CR | stripped    | stripped        |
 * | U+000B VT, U+000C FF           | stripped      | stripped        |
 * | U+000E SO .. U+001B ESC        | stripped      | kept            |
 * | U+001C FS .. U+001F US         | stripped      | stripped        |
 * | U+0020 SPACE                   | stripped      | stripped        |
 * | U+007F DEL                     | kept          | kept            |
 * | U+00A0 NBSP                    | kept          | kept            |
 * | U+1680, U+2000..U+200A, U+2028 | kept          | **stripped**    |
 * | U+205F, U+3000 (ideographic)   | kept          | **stripped**    |
 *
 * That matters here because nearly every `trim()` in this app runs over text a
 * language model produced, or over a URL a user pasted — precisely where a stray
 * C0 control byte or an exotic Unicode separator turns up. Getting it wrong
 * silently changes request bodies, endpoint URLs, saved file names and API keys.
 *
 * The port therefore never calls the stdlib `trim()` / `isBlank()` /
 * `isNullOrBlank()` on text the Java trimmed; it calls these instead, so the
 * observable behaviour stays byte-identical. `tests/source_regressions.py`
 * enforces that no `.trim()` sneaks back into `src/`.
 */

/** Byte-for-byte `java.lang.String.trim()`. */
internal fun String.trimJava(): String {
    var start = 0
    var end = length
    while (start < end && this[start] <= ' ') {
        start++
    }
    while (start < end && this[end - 1] <= ' ') {
        end--
    }
    return if (start > 0 || end < length) substring(start, end) else this
}

/** Byte-for-byte `value.trim().isEmpty()` — the Java idiom for "blank". */
internal fun String.isBlankJava(): Boolean {
    for (index in 0 until length) {
        if (this[index] > ' ') {
            return false
        }
    }
    return true
}

/** Convenience inverse, so call sites stay readable. */
internal fun String.isNotBlankJava(): Boolean = !isBlankJava()

/**
 * `trimJava()` on a nullable receiver, mirroring the very common Java shape
 * `x == null ? "" : x.trim()`.
 */
internal fun String?.trimJavaOrEmpty(): String = this?.trimJava() ?: ""

/**
 * Byte-for-byte `value == null || value.trim().isEmpty()`.
 *
 * The contract is what makes this a drop-in replacement for the stdlib's
 * `isNullOrBlank()`: without it the compiler would not smart-cast the receiver
 * to non-null after an early `if (x.isNullOrBlankJava()) return`, and every call
 * site would need a redundant non-null assertion.
 */
@OptIn(ExperimentalContracts::class)
internal fun String?.isNullOrBlankJava(): Boolean {
    contract { returns(false) implies (this@isNullOrBlankJava != null) }
    return this == null || isBlankJava()
}

/**
 * `optString` that treats an explicit JSON `null` as absent.
 *
 * Android's `JSONObject.optString(name, fallback)` returns the fallback only
 * when the key is *missing*; when the value is `JSONObject.NULL` it returns the
 * four-character string `"null"`. Providers emit explicit nulls constantly —
 * OpenRouter and DeepSeek send `"reasoning": null` on every ordinary content
 * delta — so the literal `"null"` leaked straight into the thinking panel, into
 * tool names, and into `edit_file`'s `old_string` (where it silently searched
 * the file for the text `null`).
 *
 * Note that the bundled reference `org.json` used by the offline test harness
 * implements the *other* semantics, so this class of bug is invisible off
 * device — which is exactly why every optional read goes through this helper.
 */
internal fun JSONObject.optStr(name: String, fallback: String = ""): String {
    val value = opt(name)
    if (value == null || value === JSONObject.NULL) {
        return fallback
    }
    return value.toString()
}

/** Same contract as [optStr], for arrays. */
internal fun JSONArray.optStr(index: Int, fallback: String = ""): String {
    val value = opt(index)
    if (value == null || value === JSONObject.NULL) {
        return fallback
    }
    return value.toString()
}

/** [optStr] variant that yields null (not the string "null") when absent. */
internal fun JSONObject.optStrOrNull(name: String): String? {
    val value = opt(name)
    if (value == null || value === JSONObject.NULL) {
        return null
    }
    return value.toString()
}
