package com.vepro.code

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Every user setting, backed by SharedPreferences. Secrets go through
 * [SecureStore] so nothing sensitive is ever stored in plaintext.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- language & appearance ----------------------------------------------

    /**
     * The interface language: "fa" or "en".
     *
     * Defaults to English rather than Persian. The first launch asks, and until
     * it has been answered the app has to be readable by someone who may read
     * either — English is the safer default for a screen whose only job is to
     * offer the choice.
     */
    fun language(): String = sp.getString("language", "en") ?: "en"

    fun setLanguage(value: String?) {
        sp.edit().putString("language", if (value == "fa") "fa" else "en").apply()
    }

    /**
     * False until the user has answered the first-launch language question.
     *
     * Kept separate from [language] so the stored default is always a real,
     * usable value if anything ever prevents the chooser from appearing — the app
     * is never left with "no language".
     */
    fun languageChosen(): Boolean = sp.getBoolean("language_chosen", false)

    fun setLanguageChosen() {
        // commit(), not apply(): this is written immediately before the whole UI
        // is torn down and rebuilt, and it must survive an OEM killing the
        // process in that window — otherwise the chooser asks again.
        sp.edit().putBoolean("language_chosen", true).commit()
    }

    fun themeMode(): String = sp.getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(value: String?) {
        sp.edit().putString("theme_mode", value).apply()
    }

    // ---- endpoint ----------------------------------------------------------

    fun baseUrl(): String = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBaseUrl(value: String?) {
        sp.edit().putString("base_url", value).apply()
    }

    fun model(): String = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(value: String?) {
        sp.edit().putString("model", value).apply()
    }

    fun provider(): String = sp.getString("provider", PROV_AUTO) ?: PROV_AUTO

    fun setProvider(value: String?) {
        sp.edit().putString("provider", value).apply()
    }

    // ---- credentials -------------------------------------------------------

    fun apiKey(): String {
        val encrypted = sp.getString("api_key_enc", null)
        return if (encrypted != null) SecureStore.decrypt(encrypted) else ""
    }

    fun setApiKey(value: String?): Boolean =
        sp.edit().putString("api_key_enc", SecureStore.encrypt(value ?: ""))
            .remove("api_key").commit()

    /**
     * True when the stored key is protected by the hardware keystore.
     *
     * False means it is saved but in the clear, because this device's keystore could
     * not be used — see [SecureStore]. Settings surfaces this; nothing else changes
     * behaviour on it.
     */
    fun apiKeyIsEncrypted(): Boolean = SecureStore.encrypted(sp.getString("api_key_enc", null))

    /**
     * Key Router: the user's fallback API keys, each AES-GCM encrypted at rest
     * exactly like the primary key. Capped at [MAX_ROUTER_KEYS].
     */
    fun apiKeys(): MutableList<String> {
        val out = mutableListOf<String>()
        val raw = sp.getString("api_keys_enc", "[]")
        try {
            val array = JSONArray(raw ?: "[]")
            for (i in 0 until array.length()) {
                val decrypted = SecureStore.decrypt(array.optStr(i, ""))
                if (decrypted.isNotBlankJava()) {
                    out.add(decrypted.trimJava())
                }
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    fun setApiKeys(keys: List<String>?): Boolean {
        val array = JSONArray()
        if (keys != null) {
            for (key in keys) {
                if (key.isBlankJava()) {
                    continue
                }
                array.put(SecureStore.encrypt(key.trimJava()))
            }
        }
        return sp.edit().putString("api_keys_enc", array.toString()).commit()
    }

    /** Adds a key; refuses blanks, duplicates and growth past the cap. */
    fun addApiKey(key: String?): Boolean {
        val candidate = key?.trimJava() ?: return false
        if (candidate.isEmpty()) {
            return false
        }
        val keys = apiKeys()
        if (keys.size >= MAX_ROUTER_KEYS || keys.contains(candidate)) {
            return false
        }
        keys.add(candidate)
        return setApiKeys(keys)
    }

    fun removeApiKey(index: Int): Boolean {
        val keys = apiKeys()
        if (index < 0 || index >= keys.size) {
            return false
        }
        keys.removeAt(index)
        val cursor = routerIndex()
        if (cursor >= keys.size) {
            setRouterIndex(0)
        } else if (index < cursor) {
            setRouterIndex(cursor - 1)
        }
        return setApiKeys(keys)
    }

    /**
     * Sticky rotation cursor: survives process restarts so a rate-limited key
     * is not retried first on the next run.
     */
    fun routerIndex(): Int = sp.getInt("router_index", 0)

    fun setRouterIndex(value: Int) {
        sp.edit().putInt("router_index", maxOf(0, value)).apply()
    }

    // ---- agent behaviour ---------------------------------------------------

    /**
     * The active run mode, always one of [MODE_ACCEPT] / [MODE_PLAN] /
     * [MODE_AUTO].
     *
     * Validated on the way out as well as in, because AUTO is the `else` branch
     * of every dispatch: an unrecognised value stored by an older build — or by
     * a corrupted preferences file — silently granted the agent unrestricted
     * tool access. Falling back to ACCEPT fails closed instead.
     */
    fun mode(): String {
        val stored = sp.getString("mode", MODE_ACCEPT) ?: MODE_ACCEPT
        return if (isValidMode(stored)) stored else MODE_ACCEPT
    }

    fun setMode(value: String?) {
        val safe = if (isValidMode(value)) value else MODE_ACCEPT
        sp.edit().putString("mode", safe).apply()
    }

    fun temperature(): Float = sp.getFloat("temp", 0.7f)

    fun setTemperature(value: Float) {
        sp.edit().putFloat("temp", value).apply()
    }

    fun maxTokens(): Int = sp.getInt("max_tokens", 10000)

    fun setMaxTokens(value: Int) {
        sp.edit().putInt("max_tokens", value).apply()
    }

    fun thinkingLevel(): String = sp.getString("think_level", "medium") ?: "medium"

    fun setThinkingLevel(value: String?) {
        sp.edit().putString("think_level", value).apply()
    }

    fun thinkingBudgetForLevel(): Int = thinkingBudgetForLevel(effectiveThinkingLevel())

    fun thinkingEnabled(): Boolean = true

    /**
     * Response inactivity timeout in seconds — how long to wait for the NEXT
     * byte from the model before giving up. A streaming answer resets it
     * continuously, so this is not a limit on how long an answer may take.
     *
     * Clamped on read as well as on write, so a value written by an older build
     * (or hand-edited) can never hang the app or make every request fail.
     */
    fun timeoutSeconds(): Int {
        val stored = sp.getInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, stored))
    }

    fun setTimeoutSeconds(value: Int) {
        val clamped = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, value))
        sp.edit().putInt("timeout_seconds", clamped).apply()
    }

    /**
     * Dynamic Workflow: the agent plans a multi-step task up front and delegates
     * each step to a focused sub-agent, the way Claude Code does, instead of
     * carrying one ever-growing context through the whole job.
     *
     * Off by default — it deliberately spends more tokens and more steps in
     * exchange for staying coherent on long tasks.
     */
    fun dynamicWorkflow(): Boolean = sp.getBoolean("dynamic_workflow", false)

    fun setDynamicWorkflow(value: Boolean) {
        sp.edit().putBoolean("dynamic_workflow", value).apply()
    }

    /**
     * The reasoning level actually used for a run.
     *
     * With Dynamic Workflow on, effort is raised to a floor of XHIGH: the mode
     * only pays off when each sub-agent genuinely decomposes and verifies its
     * step. It is NOT forced to MAX — max is a budget-burning ceiling meant for
     * one-off deep dives, and applying it to every sub-agent in a long workflow
     * is slower and more expensive without being better. A user who has already
     * chosen MAX keeps it.
     */
    fun effectiveThinkingLevel(): String {
        val chosen = thinkingLevel()
        if (!dynamicWorkflow()) {
            return chosen
        }
        return if (chosen == "max") "max" else "xhigh"
    }

    fun webSearch(): Boolean = sp.getBoolean(Tools.ToolNames.WEB_SEARCH, true)

    fun setWebSearch(value: Boolean) {
        sp.edit().putBoolean(Tools.ToolNames.WEB_SEARCH, value).apply()
    }

    /**
     * Whether the model's own tools may reach private/loopback addresses.
     * Off by default: the user's configured endpoint is always allowed
     * regardless, so this only governs URLs the model itself chose.
     */
    fun allowLocalNetwork(): Boolean = sp.getBoolean("allow_local_net", false)

    fun setAllowLocalNetwork(value: Boolean) {
        sp.edit().putBoolean("allow_local_net", value).apply()
        NetworkPolicy.allowLocalNetwork = value
    }

    fun systemPrompt(): String = sp.getString("sys_prompt", "") ?: ""

    fun setSystemPrompt(value: String?) {
        sp.edit().putString("sys_prompt", value).apply()
    }

    // ---- session bookkeeping ----------------------------------------------

    fun lastChatId(): String = sp.getString("last_chat", "") ?: ""

    fun setLastChatId(value: String?) {
        sp.edit().putString("last_chat", value).apply()
    }

    /** Whether we've already offered the battery-optimization exemption. */
    fun batteryPromptShown(): Boolean = sp.getBoolean("batt_prompt_shown", false)

    fun setBatteryPromptShown(value: Boolean) {
        sp.edit().putBoolean("batt_prompt_shown", value).apply()
    }

    /** Wipes every preference back to factory defaults (used by settings reset). */
    fun clearAll() {
        sp.edit().clear().commit()
    }

    // ---- derived -----------------------------------------------------------

    fun isConfigured(): Boolean =
        baseUrl().isNotBlankJava() && model().isNotBlankJava() &&
            (apiKey().isNotBlankJava() || apiKeys().isNotEmpty())

    fun isAnthropic(): Boolean =
        LlmClient.PROTOCOL_ANTHROPIC == LlmClient.resolveProtocol(provider(), baseUrl(), model())

    fun isGemini(): Boolean =
        LlmClient.PROTOCOL_GEMINI == LlmClient.resolveProtocol(provider(), baseUrl(), model())

    companion object {
        private const val FILE = "vepro_prefs"

        const val MAX_ROUTER_KEYS = 50

        /**
         * Response inactivity timeout bounds, in seconds. The floor keeps a
         * slow-but-working endpoint from being cut off mid-thought; the ceiling
         * (30 min) keeps a dead connection from pinning the run forever.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MIN_TIMEOUT_SECONDS = 10
        const val MAX_TIMEOUT_SECONDS = 1800

        const val MODE_ACCEPT = "accept"
        const val MODE_AUTO = "auto"
        const val MODE_PLAN = "plan"

        fun isValidMode(value: String?): Boolean =
            MODE_ACCEPT == value || MODE_AUTO == value || MODE_PLAN == value

        const val PROV_ANTHRO = "anthropic"
        const val PROV_AUTO = "auto"
        const val PROV_GEMINI = "gemini"
        const val PROV_OPENAI = "openai"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o"

        /**
         * Provider adapters clamp these preference budgets to each API/model's
         * supported range; keep the tiers monotonic and useful to Anthropic.
         */
        internal fun thinkingBudgetForLevel(level: String?): Int = when (level) {
            "low" -> 2048
            "medium" -> 8000
            "high" -> 16000
            "xhigh" -> 32000
            "max" -> 60000
            else -> 8000
        }
    }
}
