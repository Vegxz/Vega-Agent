package com.vepro.code

import java.util.Locale

/**
 * Answers one question before a run starts: **can this configuration possibly work?**
 *
 * ### Why this exists
 *
 * A user pasted three API keys from three different platforms and every one of them
 * produced no answer at all. The keys were fine. A fresh install points at
 * `https://api.openai.com/v1`, so all three were being sent to OpenAI, which
 * rejected them — and the app's response to a rejected request is to retry six
 * times with a widening pause and say nothing until the budget runs out. Roughly
 * half a minute of blank screen for a mistake that is visible in the settings before
 * a single byte leaves the phone.
 *
 * So the impossible cases are named here and reported instantly. This is deliberately
 * NOT a general validator: it only fires when a request is *certain* to fail, because
 * a false positive blocks a setup that would have worked. Anything uncertain — an
 * unrecognised key format, a self-hosted gateway, a model name this build has never
 * heard of — passes straight through and is left to the provider to judge.
 *
 * Pure functions over strings, no Android and no I/O, so the whole table is testable
 * without a device.
 */
object Preflight {

    /** Nothing wrong that can be known in advance. */
    const val OK = 0

    /** No API key at all. */
    const val NO_KEY = 1

    /** The key belongs to a different service than the endpoint it is aimed at. */
    const val KEY_ENDPOINT_MISMATCH = 2

    /** No model name. */
    const val NO_MODEL = 3

    /** The endpoint is not a URL this app can call. */
    const val BAD_ENDPOINT = 4

    /**
     * A problem worth stopping for, with the sentence to show and the vendor the key
     * actually belongs to when that is knowable.
     */
    class Problem(val code: Int, val message: String, val hint: String)

    /**
     * The vendor a key's own prefix identifies, or "" when the format says nothing.
     *
     * Every one of these prefixes is public, documented and stable; they are how the
     * services themselves tell their keys apart. A key whose shape is unfamiliar
     * yields "" and is never used to block anything.
     */
    fun vendorOfKey(key: String?): String {
        val value = key?.trimJava() ?: ""
        return when {
            value.isEmpty() -> ""
            value.startsWith("sk-ant-") -> ANTHROPIC
            value.startsWith("sk-or-") -> OPENROUTER
            value.startsWith("gsk_") -> GROQ
            value.startsWith("AIza") -> GOOGLE
            // Checked AFTER the more specific sk- forms above, which all start "sk-".
            value.startsWith("sk-") -> OPENAI
            else -> ""
        }
    }

    /** The vendor an endpoint host belongs to, or "" for anything self-hosted. */
    fun vendorOfEndpoint(baseUrl: String?): String {
        val host = hostOf(baseUrl)
        return when {
            host.isEmpty() -> ""
            host.endsWith("openai.com") -> OPENAI
            host.endsWith("anthropic.com") -> ANTHROPIC
            host.endsWith("openrouter.ai") -> OPENROUTER
            host.endsWith("groq.com") -> GROQ
            host.endsWith("googleapis.com") || host.endsWith("google.com") -> GOOGLE
            else -> ""
        }
    }

    /**
     * The one call the engine makes. Returns null when the run should proceed.
     *
     * Order matters: the missing pieces come first, because "you have not set a key"
     * is more useful than "your key does not match your endpoint" when both are true.
     */
    fun check(baseUrl: String?, key: String?, model: String?): Problem? {
        if ((key?.trimJava() ?: "").isEmpty()) {
            return Problem(NO_KEY, Fa.PRE_NO_KEY, Fa.PRE_OPEN_SETTINGS)
        }
        if ((model?.trimJava() ?: "").isEmpty()) {
            return Problem(NO_MODEL, Fa.PRE_NO_MODEL, Fa.PRE_OPEN_SETTINGS)
        }
        val host = hostOf(baseUrl)
        if (host.isEmpty()) {
            return Problem(BAD_ENDPOINT, Fa.PRE_BAD_ENDPOINT, Fa.PRE_OPEN_SETTINGS)
        }
        val keyVendor = vendorOfKey(key)
        val endpointVendor = vendorOfEndpoint(baseUrl)
        // Both sides must be RECOGNISED before a mismatch means anything. A known key
        // pointed at an unknown host is the normal shape of a gateway or a proxy, and
        // blocking that would break every legitimate self-hosted setup.
        if (keyVendor.isNotEmpty() && endpointVendor.isNotEmpty() &&
            keyVendor != endpointVendor
        ) {
            return Problem(
                KEY_ENDPOINT_MISMATCH,
                Fa.PRE_MISMATCH.format(label(keyVendor), host),
                Fa.PRE_MISMATCH_FIX.format(label(keyVendor), endpointFor(keyVendor))
            )
        }
        return null
    }

    /** The endpoint a key of this vendor should be pointed at. */
    fun endpointFor(vendor: String): String = when (vendor) {
        OPENAI -> "https://api.openai.com/v1"
        ANTHROPIC -> "https://api.anthropic.com/v1"
        OPENROUTER -> "https://openrouter.ai/api/v1"
        GROQ -> "https://api.groq.com/openai/v1"
        GOOGLE -> "https://generativelanguage.googleapis.com/v1beta"
        else -> ""
    }

    /** The name a person would use for this vendor. */
    fun label(vendor: String): String = when (vendor) {
        OPENAI -> "OpenAI"
        ANTHROPIC -> "Anthropic"
        OPENROUTER -> "OpenRouter"
        GROQ -> "Groq"
        GOOGLE -> "Google"
        else -> vendor
    }

    /** Host of a URL, lowercased, without the port. Empty when unparseable. */
    fun hostOf(url: String?): String {
        val value = url?.trimJava() ?: ""
        if (value.isEmpty()) {
            return ""
        }
        return try {
            val host = java.net.URL(value).host ?: ""
            host.lowercase(Locale.US)
        } catch (ignored: Exception) {
            ""
        }
    }

    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val OPENROUTER = "openrouter"
    const val GROQ = "groq"
    const val GOOGLE = "google"
}
