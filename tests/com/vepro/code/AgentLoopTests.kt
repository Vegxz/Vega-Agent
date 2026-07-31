package com.vepro.code

import android.content.Context
import android.content.SharedPreferences
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Runs the WHOLE agent loop, against real sockets, on the JVM.
 *
 * ### Why this file exists
 *
 * A build shipped in which sending a message produced no answer at all, and every
 * suite passed: 665 assertions covering the helpers, the parsers, the trail model and
 * the HTTP client, and **not one of them ever called `AgentEngine.run`**. The thing
 * the app is for had no test. So "all suites pass" was never evidence that the app
 * works, and the first person to find out was the user.
 *
 * These tests close that hole. They drive the real engine, with the real
 * [LlmClient], over a real loopback HTTP server, and assert on what a user would
 * actually see — an answer arriving, a failure being reported, a tool running, a
 * misconfiguration being caught before anything is sent.
 *
 * The only fakes are [Ctx] and [Sp], and they are fakes of the *platform*, not of the
 * app: an in-memory SharedPreferences and a Context pointing at a temp directory.
 * Everything inside `com.vepro.code` is the shipping code.
 */
object AgentLoopTests {

    private var assertions = 0

    /**
     * Runs on its own, against the generated stubs.
     *
     * Not folded into CoreRegressionTests because that suite is compiled against a
     * real android.jar when one is present, and android.jar is signature-only: every
     * method in it throws RuntimeException("Stub!") the moment it is called, so a
     * Context cannot even be constructed there. tools/gen_stubs.py produces classes
     * with real bodies, which is where this can actually run.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        NetworkPolicy.allowLocalNetwork = true
        val total = run()
        println("PASS AgentLoopTests: $total assertions")
    }

    fun run(): Int {
        assertions = 0
        testAPlainAnswerReachesTheUser()
        testReasoningThenAnAnswer()
        testAToolCallRunsAndTheAnswerFollows()
        testARejectedKeyIsReportedAtOnce()
        testAMisconfiguredKeyNeverLeavesThePhone()
        testTheTrailIsNeverBlankWhileRunning()
        testPreflightTable()
        testASecretSurvivesWithoutTheKeystore()
        return assertions
    }

    // ---------------------------------------------------------------- tests

    /** The whole point of the app: ask something, get an answer. */
    private fun testAPlainAnswerReachesTheUser() {
        val server = sse("Salam! ", "How can I help?")
        try {
            val ctx = Ctx(server)
            val chat = Chat("c1", "t", System.currentTimeMillis())
            chat.messages.add(Message("user", "سلام"))
            val sink = Sink()
            AgentEngine(ctx, Prefs(ctx)).run(chat, CancellationToken(), sink)

            truth(sink.completed, "the run never completed")
            truth(sink.errors.isEmpty(), "a clean answer reported an error: " + sink.errors)
            eq("Salam! How can I help?", sink.answer().trimJava())
            truth(sink.newMessages >= 1, "no assistant message was ever announced")
            // The strip must not survive a turn that used no tools.
            val trail = sink.trailOf(chat)
            truth(trail != null, "the run produced no trail at all")
            truth(trail?.didWork() == false, "a tool-less turn claims it did work")
            truth(trail?.running == false, "the trail is still running after completion")
        } finally {
            server.stop(0)
        }
    }

    /** Reasoning has to arrive as trail rows, since that is where the UI reads it. */
    private fun testReasoningThenAnAnswer() {
        val server = server { exchange ->
            val body = StringBuilder()
            body.append(chunk("{\"choices\":[{\"delta\":{\"reasoning\":\"" +
                "I should greet the user warmly and briefly, in their own language.\"}}]}"))
            body.append(chunk("{\"choices\":[{\"delta\":{\"content\":\"Hello.\"}}]}"))
            body.append("data: [DONE]\n\n")
            send(exchange, 200, body.toString())
        }
        try {
            val ctx = Ctx(server)
            val chat = Chat("c2", "t", System.currentTimeMillis())
            chat.messages.add(Message("user", "hi"))
            val sink = Sink()
            AgentEngine(ctx, Prefs(ctx)).run(chat, CancellationToken(), sink)

            eq("Hello.", sink.answer().trimJava())
            val trail = sink.trailOf(chat)
            truth(trail != null, "no trail")
            truth(
                trail?.hasThoughts() == true,
                "reasoning never became a trail row, so the panel would be empty"
            )
            val think = trail?.steps()?.firstOrNull { it.kind == TrailStep.THINK }
            truth(think != null, "no THINK row")
            truth(
                think?.status != TrailStep.RUNNING,
                "the reasoning row was left open after the run finished"
            )
            truth(
                (think?.detail ?: "").contains("greet"),
                "the reasoning row does not hold the reasoning: " + think?.detail
            )
        } finally {
            server.stop(0)
        }
    }

    /** A tool call has to run, feed back, and let the answer follow. */
    private fun testAToolCallRunsAndTheAnswerFollows() {
        val turn = AtomicInteger()
        val server = server { exchange ->
            drain(exchange)
            val body = if (turn.getAndIncrement() == 0) {
                chunk(
                    "{\"choices\":[{\"delta\":{\"content\":\"" +
                        "```json\\n{\\\"tool\\\":\\\"recall\\\",\\\"args\\\":{}}\\n```\"}}]}"
                ) + "data: [DONE]\n\n"
            } else {
                chunk("{\"choices\":[{\"delta\":{\"content\":\"Nothing saved yet.\"}}]}") +
                    "data: [DONE]\n\n"
            }
            send(exchange, 200, body)
        }
        try {
            val ctx = Ctx(server)
            val chat = Chat("c3", "t", System.currentTimeMillis())
            chat.messages.add(Message("user", "what do you remember?"))
            val sink = Sink()
            AgentEngine(ctx, Prefs(ctx)).run(chat, CancellationToken(), sink)

            truth(sink.toolsRun.contains("recall"), "the tool never ran: " + sink.toolsRun)
            eq("Nothing saved yet.", sink.answer().trimJava())
            val trail = sink.trailOf(chat)
            truth(trail?.didWork() == true, "a turn that ran a tool reports no work")
            eq(1, trail?.workCount())
            val step = trail?.steps()?.firstOrNull { it.kind != TrailStep.THINK }
            eq(TrailStep.DONE, step?.status)
            truth((step?.endedAt ?: 0L) > 0L, "the tool step was never closed")
        } finally {
            server.stop(0)
        }
    }

    /**
     * The bug this whole file exists for.
     *
     * A rejected key used to buy six retries across roughly half a minute of widening
     * backoff with NOTHING on screen — no error, no reason, no indication the app was
     * even trying. The reason has to be on the trail from the first attempt, and the
     * run has to give up quickly when it has produced nothing at all.
     */
    private fun testARejectedKeyIsReportedAtOnce() {
        val attempts = AtomicInteger()
        val server = server { exchange ->
            drain(exchange)
            attempts.incrementAndGet()
            send(
                exchange, 401,
                "{\"error\":{\"message\":\"Incorrect API key provided\",\"code\":" +
                    "\"invalid_api_key\"}}"
            )
        }
        try {
            val ctx = Ctx(server)
            val chat = Chat("c4", "t", System.currentTimeMillis())
            chat.messages.add(Message("user", "سلام"))
            val sink = Sink()
            val started = System.currentTimeMillis()
            AgentEngine(ctx, Prefs(ctx)).run(chat, CancellationToken(), sink)
            val took = System.currentTimeMillis() - started

            truth(sink.completed, "a failing run never completed")
            // The user must end up with something that says what went wrong.
            val visible = sink.errorText()
            truth(visible.isNotEmpty(), "a rejected key produced no visible error at all")
            truth(
                visible.contains("401") || visible.contains("Incorrect API key") ||
                    visible.contains(Fa.ERR_AUTH),
                "the error does not say what the provider actually said: " + visible
            )
            // Bounded: two silent attempts, not six.
            truth(
                attempts.get() <= 3,
                "a run that produced nothing still burned " + attempts.get() + " attempts"
            )
            truth(took < 20000L, "a hopeless run took " + took + "ms to report")
            // And the reason was on the trail while it was still trying.
            truth(
                sink.trailFailures > 0,
                "the failure never appeared on the trail, so the strip stayed silent"
            )
        } finally {
            server.stop(0)
        }
    }

    /**
     * An Anthropic key aimed at OpenAI cannot work, and must not cost a request.
     *
     * This is what actually happened: a new install points at OpenAI, three keys from
     * three other platforms were pasted in turn, and each one produced a blank screen.
     */
    private fun testAMisconfiguredKeyNeverLeavesThePhone() {
        val attempts = AtomicInteger()
        val server = server { exchange ->
            drain(exchange)
            attempts.incrementAndGet()
            send(exchange, 200, chunk("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"))
        }
        try {
            // A real Anthropic-shaped key, pointed at a host that reads as OpenAI.
            val ctx = Ctx(server, key = "sk-ant-api03-EXAMPLE", base = "https://api.openai.com/v1")
            val chat = Chat("c5", "t", System.currentTimeMillis())
            chat.messages.add(Message("user", "سلام"))
            val sink = Sink()
            AgentEngine(ctx, Prefs(ctx)).run(chat, CancellationToken(), sink)

            eq(0, attempts.get())
            val visible = sink.errorText()
            truth(visible.contains("Anthropic"), "the report does not name the key's vendor")
            truth(
                visible.contains("api.openai.com"),
                "the report does not name the endpoint it was aimed at: " + visible
            )
            truth(sink.completed, "the run did not complete cleanly")
        } finally {
            server.stop(0)
        }
    }

    /**
     * While a run is live the strip must always have something to say.
     *
     * The transcript was blank for the entire duration of a failing request because
     * the strip is hidden unless the trail "did work", and a request that never
     * connects does none. A live trail is never empty now — it carries a phase from
     * the moment it is created.
     */
    private fun testTheTrailIsNeverBlankWhileRunning() {
        val trail = Trail()
        trail.startedAt = System.currentTimeMillis()
        trail.running = true
        trail.phase = Fa.RUN_CONNECTING
        truth(!trail.isEmpty(), "a live trail with a phase reports itself as empty")
        truth(!trail.didWork(), "a connecting trail claims to have done work")
        // Which is exactly the combination MainActivity.addTrailRow now shows.
        truth(
            trail.didWork() || (trail.running && !trail.isEmpty()),
            "a connecting run would still be drawn as a blank transcript"
        )
        // And once it is over with nothing to show, it is not worth drawing.
        trail.settle(System.currentTimeMillis())
        truth(
            !(trail.didWork() || (trail.running && !trail.isEmpty())),
            "a finished tool-less turn still draws a strip over a one-line reply"
        )
    }

    /** The table of certain-failure cases, and the ones that must NOT be blocked. */
    private fun testPreflightTable() {
        // Blocked: nothing to send with.
        eq(Preflight.NO_KEY, Preflight.check("https://api.openai.com/v1", "", "gpt-4o")?.code)
        eq(Preflight.NO_KEY, Preflight.check("https://api.openai.com/v1", "   ", "gpt-4o")?.code)
        eq(Preflight.NO_MODEL, Preflight.check("https://api.openai.com/v1", "sk-x", "")?.code)
        eq(Preflight.BAD_ENDPOINT, Preflight.check("not a url", "sk-x", "gpt-4o")?.code)

        // Blocked: the key belongs somewhere else.
        eq(
            Preflight.KEY_ENDPOINT_MISMATCH,
            Preflight.check("https://api.openai.com/v1", "sk-ant-api03-x", "gpt-4o")?.code
        )
        eq(
            Preflight.KEY_ENDPOINT_MISMATCH,
            Preflight.check("https://api.anthropic.com/v1", "sk-proj-abc", "claude")?.code
        )
        eq(
            Preflight.KEY_ENDPOINT_MISMATCH,
            Preflight.check(
                "https://generativelanguage.googleapis.com/v1beta", "sk-or-v1-x", "gemini"
            )?.code
        )

        // NOT blocked: every legitimate combination, including the ambiguous ones.
        truth(
            Preflight.check("https://api.openai.com/v1", "sk-proj-abc", "gpt-4o") == null,
            "a matching OpenAI setup was blocked"
        )
        truth(
            Preflight.check("https://api.anthropic.com/v1", "sk-ant-api03-x", "claude") == null,
            "a matching Anthropic setup was blocked"
        )
        truth(
            Preflight.check("https://openrouter.ai/api/v1", "sk-or-v1-x", "any/model") == null,
            "a matching OpenRouter setup was blocked"
        )
        // A self-hosted gateway: known key, unknown host. Must pass — this is the
        // shape of every proxy setup, and blocking it would be worse than the bug.
        truth(
            Preflight.check("https://my-gateway.example.com/v1", "sk-ant-api03-x", "m") == null,
            "a self-hosted gateway was blocked"
        )
        // An unfamiliar key format says nothing about where it belongs.
        truth(
            Preflight.check("https://api.openai.com/v1", "abcdef123456", "gpt-4o") == null,
            "an unrecognised key format was blocked"
        )
        // Vendor detection, including the ordering trap: every sk- form starts "sk-".
        eq(Preflight.ANTHROPIC, Preflight.vendorOfKey("sk-ant-api03-x"))
        eq(Preflight.OPENROUTER, Preflight.vendorOfKey("sk-or-v1-x"))
        eq(Preflight.OPENAI, Preflight.vendorOfKey("sk-proj-x"))
        eq(Preflight.GOOGLE, Preflight.vendorOfKey("AIzaSyExample"))
        eq(Preflight.GROQ, Preflight.vendorOfKey("gsk_example"))
        eq("", Preflight.vendorOfKey("whatever"))
        eq("", Preflight.vendorOfKey(null))
        eq("api.openai.com", Preflight.hostOf("https://api.openai.com/v1"))
        eq("", Preflight.hostOf("nonsense"))
    }

    /**
     * A device whose keystore does not work must still be usable.
     *
     * `encrypt` returned null there, `setApiKey` turned that into false, and the user
     * could never save a key at all — every request then went out unauthenticated and
     * the app looked broken for reasons nothing on screen explained.
     */
    private fun testASecretSurvivesWithoutTheKeystore() {
        // No AndroidKeyStore on the JVM, so this exercises exactly that path.
        val stored = SecureStore.encrypt("sk-secret-value")
        truth(stored.isNotEmpty(), "the secret was thrown away when the keystore failed")
        eq("sk-secret-value", SecureStore.decrypt(stored))
        truth(
            !SecureStore.encrypted(stored),
            "an unprotected value claims to be encrypted, so no warning would be shown"
        )
        eq("", SecureStore.decrypt(null))
        eq("", SecureStore.decrypt(""))

        // And it round-trips through Prefs, which is what the UI actually calls.
        val ctx = Ctx(null)
        val prefs = Prefs(ctx)
        truth(prefs.setApiKey("sk-abc"), "setApiKey failed with no keystore")
        eq("sk-abc", prefs.apiKey())
        truth(!prefs.apiKeyIsEncrypted(), "Prefs reports hardware encryption it does not have")
    }

    // ---------------------------------------------------------------- plumbing

    /** An SSE server that streams [parts] as content deltas and then finishes. */
    private fun sse(vararg parts: String): HttpServer = server { exchange ->
        drain(exchange)
        val body = StringBuilder()
        for (part in parts) {
            body.append(chunk("{\"choices\":[{\"delta\":{\"content\":\"" + part + "\"}}]}"))
        }
        body.append("data: [DONE]\n\n")
        send(exchange, 200, body.toString())
    }

    private fun server(handler: HttpHandler): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        return server
    }

    private fun chunk(json: String): String = "data: " + json + "\n\n"

    private fun drain(exchange: HttpExchange) {
        try {
            exchange.requestBody.readBytes()
        } catch (ignored: Exception) {
        }
    }

    private fun send(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add(
            "Content-Type",
            if (code == 200) "text/event-stream" else "application/json"
        )
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Records everything the UI would have been told. */
    private class Sink : AgentEngine.Callback {
        val errors = ArrayList<String>()
        val toolsRun = ArrayList<String>()
        val finalized = ArrayList<Message>()
        var newMessages = 0
        var completed = false
        var trailFailures = 0

        override fun isCancelled(): Boolean = false
        override fun onComplete() {
            completed = true
        }

        override fun onDelta(message: Message, delta: String) {}
        override fun onError(error: String) {
            errors.add(error)
        }

        override fun onNewAssistantMessage(message: Message) {
            newMessages++
        }

        override fun onStepFinalized(message: Message) {
            finalized.add(message)
        }

        override fun onThinking(message: Message, thinking: String) {}
        override fun onToolMessage(message: Message, detail: String) {
            toolsRun.add(detail)
        }

        override fun onToolRunning(tool: String, detail: String) {}

        override fun onTrailChanged(owner: Message) {
            owner.trail?.let { trail ->
                for (step in trail.steps()) {
                    if (step.status == TrailStep.FAILED && step.label == Fa.RUN_FAILED_STEP) {
                        trailFailures++
                        return
                    }
                }
            }
        }

        override fun requestApproval(tool: String, args: JSONObject?): Boolean = true

        /** The assistant prose a user would read, tool calls stripped. */
        fun answer(): String {
            val sb = StringBuilder()
            for (message in finalized) {
                if (message.role != "assistant" || message.isError || message.isStep) {
                    continue
                }
                val visible = AgentEngine.stripToolCalls(Think.visible(message.content))
                if (visible.isNotBlankJava()) {
                    sb.append(visible)
                }
            }
            return sb.toString()
        }

        /** Everything the user would see marked as a failure. */
        fun errorText(): String {
            val sb = StringBuilder()
            for (message in finalized) {
                if (message.isError) {
                    sb.append(message.content).append('\n')
                }
            }
            for (error in errors) {
                sb.append(error).append('\n')
            }
            return sb.toString()
        }

        fun trailOf(chat: Chat): Trail? {
            val history: List<Message> = synchronized(chat.messages) { chat.messages.toList() }
            for (message in history) {
                message.trail?.let { return it }
            }
            return null
        }
    }

    /**
     * In-memory SharedPreferences.
     *
     * The whole interface, including the members the app never calls, because this has
     * to compile against a real android.jar as well as the generated stubs.
     */
    private class Sp : SharedPreferences {
        val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun contains(key: String): Boolean = values.containsKey(key)

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
        }

        override fun getString(key: String, defValue: String?): String? {
            val stored = values[key] ?: return defValue
            return stored as? String ?: defValue
        }

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun edit(): SharedPreferences.Editor = Ed(this)

        private class Ed(private val owner: Sp) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor {
                owner.values[key] = values
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                owner.values[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                owner.values.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                owner.values.clear()
                return this
            }

            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }

    /**
     * A Context that is just enough to construct the app's own objects: one
     * SharedPreferences and a temp directory.
     *
     * A [android.content.ContextWrapper] with a null base, so the six members the app
     * actually needs are overridden and every other platform call fails loudly. That
     * is deliberate — a test must not quietly start depending on platform behaviour
     * nobody modelled. Subclassing Context directly is not an option: against a real
     * android.jar that means implementing about a hundred abstract methods.
     */
    private class Ctx(server: HttpServer?, key: String = "sk-test-key", base: String? = null) :
        android.content.ContextWrapper(null) {

        private val prefs = Sp()
        private val dir: File = File(
            System.getProperty("java.io.tmpdir"), "vega-loop-" + System.nanoTime()
        ).also { it.mkdirs() }

        init {
            val endpoint = base ?: if (server != null) {
                "http://127.0.0.1:" + server.address.port + "/v1"
            } else {
                "https://api.openai.com/v1"
            }
            prefs.values["base_url"] = endpoint
            prefs.values["model"] = "test-model"
            prefs.values["provider"] = Prefs.PROV_OPENAI
            prefs.values["api_key_enc"] = SecureStore.encrypt(key)
            // Off, so the loop under test is the loop and nothing else.
            prefs.values["web_search"] = false
            prefs.values["dynamic_workflow"] = false
            prefs.values["mode"] = Prefs.MODE_AUTO
            prefs.values["timeout_seconds"] = 10
        }

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = dir
        override fun getCacheDir(): File = dir
        override fun getExternalFilesDir(type: String?): File = dir
        override fun getPackageName(): String = "com.vepro.code"
    }

    // ---------------------------------------------------------------- asserts

    private fun eq(expected: Any?, actual: Any?) {
        assertions++
        if (expected != actual) {
            throw AssertionError("expected <$expected> but was <$actual>")
        }
    }

    private fun truth(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError(message)
        }
    }
}
