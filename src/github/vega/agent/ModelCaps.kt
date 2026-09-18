package github.vega.agent

/**
 * Known per-model limits: the context window (input + output, in tokens) and
 * the maximum output tokens the model accepts.
 *
 * When the user picks a preset — or types a model we recognise — settings
 * auto-fills "max tokens" and "context window" with these ceilings, so every
 * model runs at the highest limits its provider allows without the user
 * having to look them up. Unknown models leave the current values alone.
 *
 * Values are the providers' documented maxima, rounded down to a safe
 * margin. This table is data, not policy: nothing here is a secret and it
 * is safe to ship in a public tree.
 */
object ModelCaps {

    /** [contextTokens] covers the whole request; [maxOutputTokens] the answer. */
    data class Caps(val contextTokens: Int, val maxOutputTokens: Int)

    private val TABLE: Map<String, Caps> = mapOf(
        // ---- current presets ------------------------------------------------
        "gpt-6-astra" to Caps(contextTokens = 1_000_000, maxOutputTokens = 128_000),
        "claude-fable-5.1" to Caps(contextTokens = 1_000_000, maxOutputTokens = 128_000),
        "gemini-3.8-flash" to Caps(contextTokens = 2_000_000, maxOutputTokens = 65_536),
        "qwen/qwen3.8-27b" to Caps(contextTokens = 131_072, maxOutputTokens = 32_768),
        "deepseek-flash" to Caps(contextTokens = 128_000, maxOutputTokens = 64_000),
        // ---- previous presets / common hand-typed models ---------------------
        "gpt-4o" to Caps(contextTokens = 128_000, maxOutputTokens = 16_384),
        "gpt-4o-mini" to Caps(contextTokens = 128_000, maxOutputTokens = 16_384),
        "claude-sonnet-4-5" to Caps(contextTokens = 1_000_000, maxOutputTokens = 64_000),
        "claude-opus-4-1" to Caps(contextTokens = 200_000, maxOutputTokens = 32_000),
        "gemini-2.5-flash" to Caps(contextTokens = 1_000_000, maxOutputTokens = 65_536),
        "gemini-2.0-flash" to Caps(contextTokens = 1_000_000, maxOutputTokens = 8_192),
        "deepseek-chat" to Caps(contextTokens = 128_000, maxOutputTokens = 8_192),
        "deepseek-reasoner" to Caps(contextTokens = 128_000, maxOutputTokens = 64_000),
        "deepseek-v4" to Caps(contextTokens = 128_000, maxOutputTokens = 64_000),
        "llama-4-maverick-17b-128e-instruct" to Caps(contextTokens = 131_072, maxOutputTokens = 8_192),
        "meta-llama/llama-4-maverick-17b-128e-instruct" to Caps(contextTokens = 131_072, maxOutputTokens = 8_192),
        "qwen-qwq-32b" to Caps(contextTokens = 131_072, maxOutputTokens = 32_768),
        "llama-3.3-70b-versatile" to Caps(contextTokens = 131_072, maxOutputTokens = 8_192)
    )

    /**
     * Caps for [model], or null when the model is unknown.
     *
     * Matches the full id first ("openai/gpt-6-astra"), then the part after
     * the last slash ("gpt-6-astra"), so provider-prefixed ids resolve to
     * the same caps as the bare model name.
     */
    fun forModel(model: String): Caps? {
        val m = model.trimJava()
        if (m.isEmpty()) return null
        TABLE[m]?.let { return it }
        val slash = m.lastIndexOf('/')
        if (slash >= 0 && slash + 1 < m.length) {
            TABLE[m.substring(slash + 1)]?.let { return it }
        }
        return null
    }

    /**
     * Total input budget in characters for the current configuration:
     * the explicit context-window setting wins, then the model's known
     * ceiling, then the historical default. Tokens are estimated at
     * ~4 characters, the same rough ratio the output reserve uses.
     */
    fun inputChars(prefs: Prefs): Int {
        val tokens = prefs.contextTokens().takeIf { it > 0 }
            ?: forModel(prefs.model())?.contextTokens
            ?: DEFAULT_CONTEXT_TOKENS
        return tokens * 4
    }

    /** The historical fixed budget, kept as the fallback for unknown models. */
    const val DEFAULT_CONTEXT_TOKENS = 30_000
}
