package com.vepro.code

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Provider-aware, actively cancellable streaming LLM client. */
class LlmClient private constructor(
    baseUrl: String?,
    apiKey: String?,
    model: String?,
    provider: String?,
    private val maxTokens: Int,
    private val temperature: Float,
    thinkingLevel: String?,
    private val thinkingBudget: Int,
    private val router: KeyRouter?
) {
    private val baseUrl: String = baseUrl?.trimJava() ?: ""
    private val apiKey: String = apiKey?.trimJava() ?: ""
    private val model: String = model?.trimJava() ?: ""
    private val provider: String = provider?.trimJava() ?: Prefs.PROV_AUTO
    private val thinkingLevel: String = thinkingLevel ?: "medium"

    @Volatile
    private var throttleUntilMs = 0L

    /**
     * Inactivity timeout for a response, in seconds — how long the client waits
     * for the NEXT byte before giving up. Not a cap on total response time: a
     * healthy stream keeps resetting it, so a ten-minute answer never trips it.
     *
     * User-settable because the right value is entirely deployment-dependent: a
     * slow local llama.cpp on a phone can take minutes to produce its first
     * token, while a hosted endpoint that has gone silent for 30s is simply dead.
     * Defaulted, not required, so nothing breaks if it is never touched.
     */
    var readTimeoutSeconds: Int = Prefs.DEFAULT_TIMEOUT_SECONDS

    constructor(prefs: Prefs) : this(
        prefs.baseUrl(), prefs.apiKey(), prefs.model(), prefs.provider(), prefs.maxTokens(),
        prefs.temperature(), prefs.effectiveThinkingLevel(), prefs.thinkingBudgetForLevel(),
        buildRouter(prefs)
    ) {
        readTimeoutSeconds = prefs.timeoutSeconds()
    }

    internal constructor(
        baseUrl: String?,
        apiKey: String?,
        model: String?,
        provider: String?,
        maxTokens: Int,
        temperature: Float,
        thinkingLevel: String?,
        thinkingBudget: Int
    ) : this(
        baseUrl, apiKey, model, provider, maxTokens, temperature, thinkingLevel,
        thinkingBudget, null
    )

    /** Test hook: a client whose requests rotate through the given key chain. */
    internal constructor(
        baseUrl: String?,
        keyChain: List<String>?,
        model: String?,
        provider: String?,
        maxTokens: Int,
        temperature: Float,
        thinkingLevel: String?,
        thinkingBudget: Int
    ) : this(
        baseUrl,
        if (keyChain.isNullOrEmpty()) "" else keyChain[0],
        model, provider, maxTokens, temperature, thinkingLevel, thinkingBudget,
        if (keyChain.isNullOrEmpty()) null else KeyRouter(keyChain, 0, null)
    )

    /**
     * The key for the CURRENT attempt: the router's cursor when a key chain
     * exists, otherwise the single configured key.
     */
    private fun currentApiKey(): String = router?.currentKey() ?: apiKey

    interface StreamCallback {
        fun onDone(text: String?)
        fun onError(message: String)
        fun onThinking(text: String)
        fun onToken(text: String)

        /** A retry starts a new stream, so the UI must discard partial output. */
        fun onRetry() {}

        /**
         * The answer was cut off rather than finished — the provider reported
         * `finish_reason: length` / `stop_reason: max_tokens` / `MAX_TOKENS`,
         * or the body simply ended without the protocol's own end-of-stream
         * marker (a truncated chunked response, a proxy that hung up).
         *
         * Called immediately before [onDone], whose text is the partial answer.
         * The caller is expected to RESUME from it, not to discard it: this is
         * the difference between an answer that stops mid-code-block and one
         * that finishes.
         */
        fun onTruncated() {}

        fun isCancelled(): Boolean = false
    }

    internal class LlmException(
        message: String?,
        val httpCode: Int,
        val retryable: Boolean,
        val retryAfterMs: Long,
        requestId: String?,
        /**
         * The provider's own error text, with no request id / cf-ray mixed in.
         * Key rotation matches against THIS, never [message]: a request id is a
         * hex blob that routinely contains "429", which used to make a plain
         * 400 "context_length_exceeded" look like a rate limit and burn one key
         * per retry, up to fifty of them, before the user saw anything.
         */
        val serverDetail: String = ""
    ) : Exception(message) {
        val requestId: String = requestId ?: ""

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** Per-attempt counters (reset on every retry). */
    private class AttemptState {
        var contentChars = 0

        /**
         * Set when the provider says it stopped because it ran out of output
         * budget, or when the body ended without the protocol's terminal
         * event. Either way the answer is unfinished and must be resumed.
         */
        var truncated = false

        /** Set once the provider reports a genuine, natural end of the answer. */
        var finishedCleanly = false
    }

    // ---- public entry points ----------------------------------------------

    fun streamChat(messages: JSONArray, callback: StreamCallback) {
        streamChat(messages, CancellationToken(), callback)
    }

    fun streamChat(messages: JSONArray, token: CancellationToken?, callback: StreamCallback) {
        val active = token ?: CancellationToken()
        router?.beginRequest()
        var attempt = 0

        while (!isCancelled(active, callback)) {
            val throttleDelay = throttleUntilMs - System.currentTimeMillis()
            if (throttleDelay > 0 && !active.sleep(Math.min(throttleDelay, 30000L))) {
                callback.onDone("")
                return
            }
            attempt++
            val state = AttemptState()
            try {
                streamAttempt(messages, active, callback, state)
                return
            } catch (cancelled: CancellationToken.CancelledException) {
                callback.onDone("")
                return
            } catch (error: LlmException) {
                // Key Router: a rate-limited key is swapped out silently and the
                // request restarts with the next key — mid-task, no user-facing
                // error — until every key has been tried.
                if (router != null && KeyRouter.isRateLimit(error) && router.rotate()) {
                    callback.onRetry()
                    throttleUntilMs = 0L
                    attempt = 0
                    continue
                }
                if (shouldRetry(error, attempt, active, callback, state)) {
                    callback.onRetry()
                    val delay = retryDelay(error, attempt)
                    if (!active.sleep(delay)) {
                        callback.onDone("")
                        return
                    }
                    continue
                }
                // The connection broke AFTER part of the answer had already
                // been streamed. Retrying would re-send the whole prompt and
                // throw away what arrived, so this used to become an error card
                // that replaced the partial answer outright. It is really just
                // a truncation: hand the partial back and let the caller
                // resume from it.
                if (state.contentChars > 0 && error.retryable) {
                    callback.onTruncated()
                    callback.onDone("")
                    return
                }
                callback.onError("⚠ " + error.message)
                return
            } catch (error: Exception) {
                if (isCancelled(active, callback)) {
                    callback.onDone("")
                    return
                }
                // Same rule as above, and the reason it has to come FIRST: a
                // truncated chunked body surfaces on Android as a plain
                // ProtocolException("unexpected end of stream"), which
                // isTransientNetworkFailure() matches — so the retry path used
                // to fire onRetry() and wipe a perfectly good half-written
                // answer off the screen before re-billing the whole prompt.
                if (state.contentChars > 0 && isTransientNetworkFailure(error)) {
                    callback.onTruncated()
                    callback.onDone("")
                    return
                }
                if (isTransientNetworkFailure(error) && attempt < 3) {
                    callback.onRetry()
                    if (!active.sleep(
                            retryDelay(LlmException("network", 0, true, 0L, ""), attempt)
                        )
                    ) {
                        callback.onDone("")
                        return
                    }
                    continue
                }
                callback.onError("⚠ " + friendlyMessage(error))
                return
            }
        }
        callback.onDone("")
    }

    private fun shouldRetry(
        error: LlmException,
        attempt: Int,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState
    ): Boolean {
        if (!error.retryable || attempt >= 3 || isCancelled(token, callback)) {
            return false
        }
        // Never retry a TIMEOUT. The user set a deadline; silently spending it
        // two more times turns a 10-second limit into a 30-second wait, which is
        // exactly the "timeout doesn't work" complaint. Other retryable faults
        // (503, transient resets) still get their attempts.
        if (error.httpCode == 408) {
            return false
        }
        // The model already streamed part of an answer before the connection
        // broke. Retrying re-sends the whole prompt, pays for it a second time
        // and throws away what did arrive, so a mid-stream timeout is treated
        // as terminal for this attempt.
        if (state.contentChars > 0) {
            return false
        }
        // Never make the user wait silently for a long provider reset window.
        return error.retryAfterMs <= 30000L
    }

    // ---- per-protocol dispatch --------------------------------------------

    @Throws(Exception::class)
    private fun streamAttempt(
        messages: JSONArray,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState
    ) {
        when (resolveProtocol(provider, baseUrl, model)) {
            PROTOCOL_ANTHROPIC -> streamAnthropic(messages, token, callback, state)
            PROTOCOL_GEMINI -> streamGemini(messages, token, callback, state)
            else -> streamOpenAi(messages, token, callback, state)
        }
    }

    @Throws(Exception::class)
    private fun streamOpenAi(
        messages: JSONArray,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState
    ) {
        streamWithRedirects(
            openAiBody(messages, true), PROTOCOL_OPENAI, token, callback, state
        )
    }

    @Throws(Exception::class)
    private fun streamAnthropic(
        messages: JSONArray,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState
    ) {
        streamWithRedirects(
            anthropicBody(messages, true), PROTOCOL_ANTHROPIC, token, callback, state
        )
    }

    @Throws(Exception::class)
    private fun streamGemini(
        messages: JSONArray,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState
    ) {
        streamWithRedirects(
            geminiBody(messages), PROTOCOL_GEMINI, token, callback, state
        )
    }

    /** Signals a 3xx from inside the IO thread so the caller can re-target. */
    private class RedirectException(val location: String) : Exception("redirect")

    /**
     * Opens the streaming request, following up to [MAX_REDIRECTS] redirects by
     * hand.
     *
     * `instanceFollowRedirects` is deliberately off (it would silently downgrade
     * POST to GET and drop the body), but nothing used to handle the 3xx either:
     * the reader saw an empty body and reported the NON-retryable "the server
     * returned an empty response", which is exactly what a gateway that moved
     * `/v1` to `/v1/` — or a provider that relocated a host — looks like from
     * the outside. Re-issuing the POST at `Location` is what every HTTP client
     * does for 307/308 and what these gateways expect.
     */
    @Throws(Exception::class)
    private fun streamWithRedirects(
        body: JSONObject,
        protocol: String,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState
    ) {
        var target = endpoint(protocol, true)
        var hops = 0
        while (true) {
            val connection = open(target, "text/event-stream")
            applyAuth(connection, protocol)
            try {
                executeStreaming(connection, body, token, callback, state, protocol)
                return
            } catch (moved: RedirectException) {
                hops++
                if (hops > MAX_REDIRECTS || moved.location.isEmpty()) {
                    throw LlmException(Fa.ERR_REDIRECT, 0, false, 0L, "")
                }
                target = URL(URL(target), moved.location).toString()
                NetworkPolicy.requireUserEndpoint(target)
            }
        }
    }

    /**
     * Runs the request on a dedicated daemon IO thread and polls it from the
     * caller, so an OEM socket that ignores `disconnect()` can never wedge the
     * agent service — cancellation returns to the caller regardless.
     */
    @Throws(Exception::class)
    private fun executeStreaming(
        connection: HttpURLConnection,
        body: JSONObject,
        token: CancellationToken,
        callback: StreamCallback,
        state: AttemptState,
        protocol: String
    ) {
        val result = AtomicReference("")
        val failure = AtomicReference<Throwable?>()

        val io = Thread({
            val connectionWatch = token.watchConnection(connection)
            var readerWatch: CancellationToken.Registration? = null
            var reader: BufferedReader? = null
            val complete = StringBuilder()
            try {
                token.throwIfCancelled()
                writeBody(connection, body)
                val code = connection.responseCode
                if (code in 300..399) {
                    throw RedirectException(header(connection, "Location"))
                }
                if (code >= 400) {
                    val errorBody = readAll(connection.errorStream, 128000, token)
                    throw mapHttp(connection, code, errorBody)
                }
                val input = connection.inputStream
                val contentType = header(connection, "Content-Type").lowercase(Locale.US)

                if (contentType.contains("application/json") &&
                    !contentType.contains("event-stream")
                ) {
                    val response = readAll(input, 1000000, token)
                    val text = parseComplete(response, protocol, callback)
                    if (text.isBlankJava()) {
                        val detail = extractError(response)
                        throw LlmException(
                            if (detail.isEmpty()) {
                                Fa.ERR_NO_TEXT
                            } else {
                                detail
                            },
                            0, false, 0L, header(connection, "x-request-id")
                        )
                    }
                    token.throwIfCancelled()
                    callback.onToken(text)
                    state.contentChars += text.length
                    observeRateWindow(connection)
                    result.set(text)
                    return@Thread
                }

                val streamReader = BufferedReader(
                    InputStreamReader(input, StandardCharsets.UTF_8)
                )
                reader = streamReader
                readerWatch = token.onCancel { closeQuietly(streamReader) }

                val eventData = StringBuilder()
                var sawPayload = false
                var sawTerminal = false
                while (true) {
                    token.throwIfCancelled()
                    val line = streamReader.readLine()
                    if (line == null) {
                        if (eventData.isNotEmpty()) {
                            token.throwIfCancelled()
                            val payload = eventData.toString()
                            sawPayload = consumeEvent(
                                payload, protocol, callback, complete, state
                            ) || sawPayload
                            // A stream that ends with `data: [DONE]` (or a
                            // `message_stop`) directly on EOF — no trailing blank
                            // line, as several gateways send — must still count as
                            // a clean terminal. Otherwise a COMPLETE answer was
                            // flagged truncated and needlessly resumed, which is
                            // where extra resume seams (and their corruption) came
                            // from.
                            if (isTerminalEvent(payload, protocol)) {
                                sawTerminal = true
                            }
                        }
                        break
                    }
                    if (line.isEmpty()) {
                        if (eventData.isNotEmpty()) {
                            token.throwIfCancelled()
                            val payload = eventData.toString()
                            sawPayload = consumeEvent(
                                payload, protocol, callback, complete, state
                            ) || sawPayload
                            eventData.setLength(0)
                            // The protocol said it is finished. Waiting for the
                            // socket to close instead used to block for the full
                            // 120 s read timeout against any gateway that keeps
                            // the body open (LiteLLM / vLLM keep-alives), which
                            // then surfaced as a *retryable* 408 and re-sent —
                            // and re-billed — the entire request up to 3 times.
                            if (isTerminalEvent(payload, protocol)) {
                                sawTerminal = true
                                break
                            }
                        }
                        continue
                    }
                    if (line.startsWith("data:")) {
                        if (eventData.isNotEmpty()) {
                            eventData.append('\n')
                        }
                        eventData.append(line.substring(5).trimJava())
                    } else if (line[0] == '{' || line[0] == '[') {
                        if (eventData.isNotEmpty()) {
                            eventData.append('\n')
                        }
                        eventData.append(line.trimJava())
                    } else if (eventData.isNotEmpty() && !isSseField(line)) {
                        // The tail of a `data:` payload that a proxy split across
                        // lines. Dropping it lost BOTH halves — the first no
                        // longer parsed either — so a whole delta vanished.
                        eventData.append(line.trimJava())
                    }
                }
                token.throwIfCancelled()
                // A stream that terminated cleanly but carried no text is a
                // legitimate empty completion, not a broken response.
                if (!sawPayload && !sawTerminal && complete.isEmpty()) {
                    throw LlmException(
                        Fa.ERR_EMPTY_REPLY,
                        0, false, 0L, header(connection, "x-request-id")
                    )
                }
                // Text arrived, then the body just ended — no `[DONE]`, no
                // `message_stop`, and no finish reason saying the model was
                // done. That is a truncated response (a proxy hung up, the
                // gateway capped the body, the upstream died), and reporting it
                // as a clean completion is what made answers stop mid-sentence
                // with no error at all.
                if (!sawTerminal && !state.finishedCleanly && complete.isNotEmpty()) {
                    state.truncated = true
                }
                observeRateWindow(connection)
                result.set(complete.toString())
            } catch (timeout: SocketTimeoutException) {
                if (token.isCancelled) {
                    failure.set(CancellationToken.CancelledException())
                } else {
                    failure.set(
                        LlmException(
                            Fa.ERR_STREAM_GAP,
                            408, true, 0L, header(connection, "x-request-id")
                        )
                    )
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                readerWatch?.close()
                closeQuietly(reader)
                connectionWatch.close()
                try {
                    connection.disconnect()
                } catch (ignored: Exception) {
                }
                Thread.interrupted()
            }
        }, "vepro-llm-io")

        // A broken OEM socket implementation must never keep the agent service alive.
        io.isDaemon = true
        io.start()

        // A REAL inactivity deadline, enforced here rather than trusting the
        // socket's readTimeout alone.
        //
        // HttpURLConnection.readTimeout only fires while a read() is actually
        // blocked on the socket. A server that accepts the connection and then
        // stalls — or a proxy that dribbles keep-alive bytes, or an SSL handshake
        // that hangs — never trips it, which is exactly why setting the timeout to
        // 10s appeared to do nothing. This loop measures wall-clock time since the
        // last byte the stream actually produced, so the configured value is
        // honoured no matter where the stall is.
        val limitMs = Math.max(
            Prefs.MIN_TIMEOUT_SECONDS,
            Math.min(Prefs.MAX_TIMEOUT_SECONDS, readTimeoutSeconds)
        ) * 1000L
        var lastProgressAt = System.currentTimeMillis()
        var seenChars = 0
        var timedOut = false

        while (io.isAlive) {
            if (callback.isCancelled() && !token.isCancelled) {
                token.cancel()
            }
            if (token.isCancelled) {
                io.join(120L)
                callback.onDone("")
                return
            }
            // Any new output means the stream is alive: restart the clock.
            if (state.contentChars != seenChars) {
                seenChars = state.contentChars
                lastProgressAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastProgressAt >= limitMs) {
                timedOut = true
                // Close the socket so the IO thread unwinds instead of lingering.
                token.cancel()
                io.join(200L)
                break
            }
            try {
                io.join(80L)
            } catch (ignored: InterruptedException) {
                if (token.isCancelled) {
                    callback.onDone("")
                    return
                }
            }
        }

        if (timedOut) {
            // Partial output is worth keeping — the caller resumes from it.
            if (state.contentChars > 0) {
                callback.onTruncated()
                callback.onDone(result.get())
                return
            }
            throw LlmException(Fa.ERR_TIMEOUT, 408, false, 0L, "")
        }

        val error = failure.get()
        if (error == null) {
            if (state.truncated) {
                callback.onTruncated()
            }
            callback.onDone(result.get())
            return
        }
        if (error is CancellationToken.CancelledException || token.isCancelled) {
            throw CancellationToken.CancelledException()
        }
        if (error is LlmException) {
            throw error
        }
        if (error is Exception) {
            throw error
        }
        throw Exception(error)
    }

    // ---- SSE event handling ------------------------------------------------

    /**
     * True when this SSE payload is the protocol's own end-of-stream marker.
     * OpenAI-compatible servers send `[DONE]`; Anthropic sends a `message_stop`
     * event; Gemini's `alt=sse` stream simply closes, so there is nothing to
     * match and the loop still exits on EOF.
     */
    private fun isTerminalEvent(payload: String?, protocol: String): Boolean {
        val data = payload?.trimJava() ?: ""
        if (data.isEmpty()) {
            return false
        }
        if (data == "[DONE]") {
            return true
        }
        if (PROTOCOL_ANTHROPIC != protocol || data.length < 2 || data[0] != '{') {
            return false
        }
        return try {
            "message_stop" == JSONObject(data).optStr("type")
        } catch (malformed: Exception) {
            false
        }
    }

    @Throws(Exception::class)
    private fun consumeEvent(
        payload: String?,
        protocol: String,
        callback: StreamCallback,
        complete: StringBuilder,
        state: AttemptState
    ): Boolean {
        val data = payload?.trimJava() ?: ""
        if (data.isEmpty() || data == "[DONE]") {
            return false
        }
        // EVERY object in the buffer, not just the first.
        //
        // Consecutive `data:` lines that arrive before a blank line are joined
        // with '\n', so one flush can legitimately carry several deltas.
        // JSONObject(String) parses the FIRST object and silently ignores the
        // rest — so those deltas were dropped outright. With providers that emit
        // one to three characters per delta, that is exactly how "edit_file"
        // arrived as "edit_ile" and "Download" as "ownload": a lost delta is a
        // lost character, and the corrupted value then went straight into a tool
        // call as a bad tool name or a bad path.
        if (data.indexOf('\n') >= 0) {
            // One event whose JSON was split across lines by a middlebox: the
            // rejoined buffer parses as a whole even though neither half would.
            if (!splitsIntoEvents(data)) {
                // One event whose JSON a middlebox split across lines.
                return consumeOneEvent(data, protocol, callback, complete, state)
            }
            var any = false
            for (line in data.split("\n")) {
                val piece = line.trimJava()
                if (piece.isEmpty() || piece == "[DONE]") {
                    continue
                }
                any = consumeOneEvent(piece, protocol, callback, complete, state) || any
            }
            return any
        }
        return consumeOneEvent(data, protocol, callback, complete, state)
    }

    /** SSE framing lines that are never part of a data payload. */
    private fun isSseField(line: String): Boolean {
        if (line.startsWith(":")) {
            return true // comment / heartbeat
        }
        return line.startsWith("event:") || line.startsWith("id:") ||
            line.startsWith("retry:")
    }

    /**
     * True when each line of the buffer is its own complete JSON event.
     *
     * Cannot be decided by "does the whole buffer parse?": JSONObject(String)
     * parses the FIRST value and silently ignores everything after it, so a
     * buffer of three coalesced deltas parses perfectly while two of them are
     * discarded — which is precisely the corruption this code exists to stop.
     * Checking that every line stands alone is the honest test.
     */
    private fun splitsIntoEvents(data: String): Boolean {
        var seen = 0
        for (raw in data.split("\n")) {
            val line = raw.trimJava()
            if (line.isEmpty()) {
                continue
            }
            if (line == "[DONE]") {
                seen++
                continue
            }
            try {
                JSONObject(line)
            } catch (notAnObject: Exception) {
                return false
            }
            seen++
        }
        return seen > 1
    }

    /** Consumes exactly one JSON event payload. */
    @Throws(Exception::class)
    private fun consumeOneEvent(
        data: String,
        protocol: String,
        callback: StreamCallback,
        complete: StringBuilder,
        state: AttemptState
    ): Boolean {
        val json: JSONObject = try {
            JSONObject(data)
        } catch (malformed: Exception) {
            // Ignore SSE comments/heartbeats but fail on substantial malformed
            // data — ONLY while nothing has been streamed yet. Once real text is
            // on screen, a gateway that injects a plain-text notice or a
            // keep-alive longer than 32 chars must not be allowed to kill a
            // healthy answer; skipping the chunk is always the better trade.
            if (data.length > 32 && state.contentChars == 0) {
                throw LlmException(
                    Fa.ERR_BAD_STREAM + Util.truncate(data, 160),
                    0, false, 0L, ""
                )
            }
            return false
        }
        val apiError = json.optJSONObject("error")
        if (apiError != null) {
            val detail = extractError(json.toString())
            throw LlmException(
                if (detail.isEmpty()) Fa.ERR_SERVER else detail,
                0, isRetryableStreamError(apiError), 0L, ""
            )
        }
        return when (protocol) {
            PROTOCOL_ANTHROPIC -> consumeAnthropic(json, callback, complete, state)
            PROTOCOL_GEMINI -> consumeGemini(json, callback, complete, state)
            else -> consumeOpenAi(json, callback, complete, state)
        }
    }

    // ---- request bodies ----------------------------------------------------

    @Throws(Exception::class)
    private fun openAiBody(messages: JSONArray, stream: Boolean): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", messages)
        body.put("stream", stream)
        val reasoning = isReasoningModel(model)
        if (!reasoning) {
            body.put("temperature", clampTemperature(temperature))
        } else if (supportsReasoningEffort(baseUrl)) {
            body.put("reasoning_effort", openAiEffort(thinkingLevel, baseUrl))
        }
        if (maxTokens > 0) {
            if (usesMaxCompletionTokens(baseUrl, model)) {
                body.put("max_completion_tokens", maxTokens)
            } else {
                body.put("max_tokens", maxTokens)
            }
        }
        return body
    }

    @Throws(Exception::class)
    private fun anthropicBody(messages: JSONArray, stream: Boolean): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("stream", stream)
        var outputTokens = if (maxTokens > 0) maxTokens else 4096
        val thinking = baseUrl.lowercase(Locale.US).contains("api.anthropic.com") &&
            supportsAnthropicThinking(model)
        if (thinking) {
            var budget = Math.min(Math.max(1024, thinkingBudget), 60000)
            outputTokens = Math.min(64000, Math.max(outputTokens, budget + 4096))
            budget = Math.min(budget, outputTokens - 1024)
            body.put(
                "thinking",
                JSONObject().put("type", "enabled").put("budget_tokens", budget)
            )
            body.put("temperature", 1.0)
        } else {
            body.put("temperature", clampTemperatureFor(PROTOCOL_ANTHROPIC, temperature))
        }
        body.put("max_tokens", outputTokens)

        val system = StringBuilder()
        val converted = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val role = message.optStr("role", "user")
            val content = message.opt("content")
            if (role == "system") {
                if (system.isNotEmpty()) {
                    system.append("\n\n")
                }
                system.append(content?.toString() ?: "")
                continue
            }
            val out = JSONObject()
            out.put("role", if (role == "assistant") "assistant" else "user")
            out.put("content", anthropicContent(content))
            converted.put(out)
        }
        if (system.isNotEmpty()) {
            body.put("system", system.toString())
        }
        body.put("messages", converted)
        return body
    }

    @Throws(Exception::class)
    private fun geminiBody(messages: JSONArray): JSONObject {
        val body = JSONObject()
        val contents = JSONArray()
        val system = StringBuilder()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val role = message.optStr("role", "user")
            val rawContent = message.opt("content")
            if (role == "system") {
                if (system.isNotEmpty()) {
                    system.append("\n\n")
                }
                system.append(rawContent?.toString() ?: "")
                continue
            }
            val content = JSONObject()
            content.put("role", if (role == "assistant") "model" else "user")
            content.put("parts", geminiParts(rawContent))
            contents.put(content)
        }
        if (system.isNotEmpty()) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", system.toString()))
                )
            )
        }
        body.put("contents", contents)

        val generation = JSONObject()
        generation.put("temperature", clampTemperature(temperature))
        if (maxTokens > 0) {
            generation.put("maxOutputTokens", maxTokens)
        }
        if (isOfficialGeminiEndpoint(baseUrl) && isGeminiThinkingModel(model)) {
            generation.put(
                "thinkingConfig",
                JSONObject()
                    // Gemini has model-specific caps; never reuse the larger
                    // cross-provider preference budget directly.
                    .put("thinkingBudget", geminiThinkingBudget(thinkingLevel, model))
                    .put("includeThoughts", true)
            )
        }
        body.put("generationConfig", generation)
        return body
    }

    // ---- connection --------------------------------------------------------

    @Throws(Exception::class)
    private fun endpoint(protocol: String, stream: Boolean): String =
        endpointFor(baseUrl, model, protocol, stream)

    @Throws(Exception::class)
    private fun open(endpoint: String, accept: String): HttpURLConnection {
        // The user typed this base URL themselves — a LAN box running Ollama or
        // a local proxy is a legitimate, common setup, not an SSRF attempt.
        NetworkPolicy.requireUserEndpoint(endpoint)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        val timeoutMs = Math.max(
            Prefs.MIN_TIMEOUT_SECONDS,
            Math.min(Prefs.MAX_TIMEOUT_SECONDS, readTimeoutSeconds)
        ) * 1000
        // Connecting must never outlast the user's own limit: with a fixed 25s
        // connect timeout, a 10s setting could still take 25s to fail against an
        // unreachable host. Capped at 25s so a very long setting doesn't make a
        // dead host hang for half an hour before the first byte.
        connection.connectTimeout = Math.min(25000, timeoutMs)
        // Bounded INACTIVITY timeout (user-configurable in Settings); active
        // cancellation normally returns instantly. Clamped so a bad stored value
        // can never make the app hang forever or time out instantly. The
        // streaming loop additionally enforces a wall-clock deadline, because
        // readTimeout only fires while a read() is genuinely blocked.
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "VegaAgent/1.0 (Android)")
        return connection
    }

    private fun applyAuth(connection: HttpURLConnection, protocol: String) {
        val key = currentApiKey()
        if (PROTOCOL_ANTHROPIC == protocol) {
            if (key.isNotEmpty()) {
                connection.setRequestProperty("x-api-key", key)
            }
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            return
        }
        if (PROTOCOL_GEMINI == protocol) {
            if (key.isNotEmpty()) {
                connection.setRequestProperty("x-goog-api-key", key)
            }
            return
        }
        val lower = baseUrl.lowercase(Locale.US)
        if (key.isNotEmpty()) {
            if (lower.contains(".openai.azure.com") ||
                lower.contains(".services.ai.azure.com")
            ) {
                connection.setRequestProperty("api-key", key)
            } else {
                connection.setRequestProperty("Authorization", "Bearer $key")
            }
        }
        if (lower.contains("openrouter.ai")) {
            connection.setRequestProperty("HTTP-Referer", "https://vega.agent")
            connection.setRequestProperty("X-Title", Fa.APP_NAME)
        }
    }

    /** Non-streaming request retained for connection testing, never used as an error fallback. */
    @Throws(Exception::class)
    /**
     * One non-streaming request, with the configured timeout enforced as a real
     * DEADLINE (a watchdog that disconnects the socket), not just a readTimeout.
     * Used by the Settings "test connection" button and the non-stream fallback.
     */
    internal fun completeOnce(messages: JSONArray, token: CancellationToken): String {
        val protocol = resolveProtocol(provider, baseUrl, model)
        val body = when (protocol) {
            PROTOCOL_ANTHROPIC -> anthropicBody(messages, false)
            PROTOCOL_GEMINI -> geminiBody(messages)
            else -> openAiBody(messages, false)
        }
        val connection = open(endpoint(protocol, false), "application/json")
        applyAuth(connection, protocol)
        val registration = token.watchConnection(connection)
        // Hard deadline: a server that accepts the socket and then stalls does not
        // trip readTimeout, so without this the configured timeout was advisory.
        val limitMs = Math.max(
            Prefs.MIN_TIMEOUT_SECONDS,
            Math.min(Prefs.MAX_TIMEOUT_SECONDS, readTimeoutSeconds)
        ) * 1000L
        val watchdog = Thread({
            try {
                Thread.sleep(limitMs)
                connection.disconnect()
            } catch (ignored: InterruptedException) {
            } catch (ignored: Exception) {
            }
        }, "vepro-http-deadline")
        watchdog.isDaemon = true
        watchdog.start()
        try {
            token.throwIfCancelled()
            writeBody(connection, body)
            val code = connection.responseCode
            val response = if (code >= 400) {
                readAll(connection.errorStream, 128000, token)
            } else {
                readAll(connection.inputStream, 1000000, token)
            }
            if (code >= 400) {
                throw mapHttp(connection, code, response)
            }
            return parseComplete(response, protocol, null)
        } finally {
            watchdog.interrupt()
            registration.close()
            connection.disconnect()
            Thread.interrupted()
        }
    }

    // ---- rate-limit bookkeeping -------------------------------------------

    private fun mapHttp(connection: HttpURLConnection, code: Int, body: String): LlmException {
        val detail = extractError(body)
        val requestId = firstNonEmpty(
            header(connection, "x-request-id"),
            header(connection, "request-id"),
            header(connection, "cf-ray")
        )
        val retryAfter = parseRetryAfter(connection)
        val message = httpMessage(code, detail, retryAfter, requestId)
        val retryable = code == 408 || code == 425 || code == 429 || code == 498 ||
            code == 529 || code >= 500
        return LlmException(message, code, retryable, retryAfter, requestId, detail)
    }

    private fun observeRateWindow(connection: HttpURLConnection) {
        val remainingRequests = parseLongHeader(connection, "x-ratelimit-remaining-requests")
        val remainingTokens = parseLongHeader(connection, "x-ratelimit-remaining-tokens")
        val tokenLimit = parseLongHeader(connection, "x-ratelimit-limit-tokens")
        var delay = 0L
        if (remainingRequests == 0L) {
            delay = Math.max(
                delay,
                parseProviderDuration(header(connection, "x-ratelimit-reset-requests"))
            )
        }
        var lowTokenMark = if (tokenLimit > 0) Math.max(1000L, tokenLimit / 10L) else 1000L
        if (baseUrl.lowercase(Locale.US).contains("groq.com")) {
            lowTokenMark = Math.max(
                lowTokenMark, Math.max(2048L, maxTokens.toLong()) + 2048L
            )
        }
        if (remainingTokens >= 0L && remainingTokens <= lowTokenMark) {
            delay = Math.max(
                delay,
                parseProviderDuration(header(connection, "x-ratelimit-reset-tokens"))
            )
        }
        if (delay > 0L && delay <= 30000L) {
            throttleUntilMs = Math.max(throttleUntilMs, System.currentTimeMillis() + delay)
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 4

        internal const val PROTOCOL_OPENAI = "openai"
        internal const val PROTOCOL_ANTHROPIC = "anthropic"
        internal const val PROTOCOL_GEMINI = "gemini"

        /**
         * Key chain = primary key first, then the Key Router list (deduplicated).
         * Rotation is sticky via Prefs so a limited key stays rotated across runs.
         */
        private fun buildRouter(prefs: Prefs): KeyRouter? {
            val chain = ArrayList<String>()
            val primary = prefs.apiKey()
            if (primary.isNotBlankJava()) {
                chain.add(primary.trimJava())
            }
            for (key in prefs.apiKeys()) {
                if (key.isNotBlankJava() && !chain.contains(key.trimJava())) {
                    chain.add(key.trimJava())
                }
            }
            if (chain.isEmpty()) {
                return null
            }
            return KeyRouter(
                chain, prefs.routerIndex(),
                object : KeyRouter.IndexStore {
                    override fun get(): Int = prefs.routerIndex()
                    override fun set(index: Int) = prefs.setRouterIndex(index)
                }
            )
        }

        private fun retryDelay(error: LlmException, attempt: Int): Long {
            if (error.retryAfterMs > 0) {
                return Math.max(250L, error.retryAfterMs)
            }
            val base = if (attempt == 1) 900L else 2200L
            return base + ThreadLocalRandom.current().nextLong(150L, 650L)
        }

        // ---- stream consumers ---------------------------------------------

        /**
         * Classifies a provider's terminal reason.
         *
         * Every provider spells this differently — OpenAI `finish_reason:
         * "length"`, Anthropic `stop_reason: "max_tokens"`, Gemini
         * `finishReason: "MAX_TOKENS"`, and a long tail of gateways that echo
         * one of the three — but they all mean the same thing: the answer is
         * unfinished. Nothing in this client used to read any of them, which is
         * why hitting the output cap looked identical to a clean finish and the
         * run simply ended, usually mid-code-block.
         */
        private fun noteFinishReason(state: AttemptState, raw: String?) {
            val reason = (raw ?: "").trimJava().lowercase(Locale.US)
            if (reason.isEmpty() || reason == "null") {
                return
            }
            if (reason.contains("length") || reason.contains("max_token") ||
                reason.contains("max token") || reason == "max_output_tokens" ||
                reason == "maxtokens" || reason == "truncated" || reason == "incomplete"
            ) {
                state.truncated = true
                state.finishedCleanly = false
                return
            }
            // "stop", "end_turn", "stop_sequence", "tool_use", "STOP", ...
            state.truncated = false
            state.finishedCleanly = true
        }

        private fun consumeOpenAi(
            json: JSONObject,
            callback: StreamCallback,
            complete: StringBuilder,
            state: AttemptState
        ): Boolean {
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return false
            }
            val choice = choices.optJSONObject(0) ?: return false
            noteFinishReason(
                state,
                firstNonEmpty(
                    choice.optStr("finish_reason", ""),
                    choice.optStr("finishReason", ""),
                    choice.optStr("stop_reason", "")
                )
            )
            val delta = choice.optJSONObject("delta")
                ?: choice.optJSONObject("message")
                ?: return false

            var emitted = false
            val thinking = firstNonEmpty(
                delta.optStr("reasoning_content", ""),
                delta.optStr("reasoning", ""),
                delta.optStr("thinking", ""),
                delta.optStr("analysis", "")
            )
            if (thinking.isNotEmpty()) {
                callback.onThinking(thinking)
                emitted = true
            }
            val text = contentText(delta.opt("content"))
            if (text.isNotEmpty()) {
                complete.append(text)
                state.contentChars += text.length
                callback.onToken(text)
                emitted = true
            }
            return emitted
        }

        private fun consumeAnthropic(
            json: JSONObject,
            callback: StreamCallback,
            complete: StringBuilder,
            state: AttemptState
        ): Boolean {
            val type = json.optStr("type", "")
            if (type == "message_delta" || type == "message_start") {
                // `message_delta` carries the terminal stop_reason; `max_tokens`
                // there is exactly the case that used to end an answer
                // mid-code-block with no error and no way to tell.
                val holder = json.optJSONObject("delta") ?: json.optJSONObject("message")
                noteFinishReason(state, holder?.optStr("stop_reason", "") ?: "")
                return false
            }
            if (type != "content_block_delta") {
                return false
            }
            val delta = json.optJSONObject("delta") ?: return false
            val deltaType = delta.optStr("type", "")
            if (deltaType == "thinking_delta") {
                val thinking = delta.optStr("thinking", "")
                if (thinking.isNotEmpty()) {
                    callback.onThinking(thinking)
                    return true
                }
                return false
            }
            if (deltaType == "signature_delta") {
                // The signature is an opaque base64 blob (thinking-block integrity),
                // NOT human-readable reasoning — swallow it so it never leaks into the
                // visible "model reasoning" panel.
                return true
            }
            val text = delta.optStr("text", "")
            if (text.isNotEmpty()) {
                complete.append(text)
                state.contentChars += text.length
                callback.onToken(text)
                return true
            }
            return false
        }

        private fun consumeGemini(
            json: JSONObject,
            callback: StreamCallback,
            complete: StringBuilder,
            state: AttemptState
        ): Boolean {
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return false
            }
            val candidate = candidates.optJSONObject(0)
            noteFinishReason(state, candidate?.optStr("finishReason", "") ?: "")
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: return false

            var emitted = false
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optStr("text", "")
                if (text.isEmpty()) {
                    continue
                }
                if (part.optBoolean("thought", false)) {
                    callback.onThinking(text)
                } else {
                    complete.append(text)
                    state.contentChars += text.length
                    callback.onToken(text)
                }
                emitted = true
            }
            return emitted
        }

        // ---- content shaping ----------------------------------------------

        @Throws(Exception::class)
        private fun anthropicContent(content: Any?): Any {
            if (content !is JSONArray) {
                return content?.toString() ?: ""
            }
            val parts = JSONArray()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optStr("type", "")) {
                    "text" -> parts.put(
                        JSONObject().put("type", "text")
                            .put("text", part.optStr("text", ""))
                    )

                    "image_url" -> {
                        val image = part.optJSONObject("image_url")
                        val dataUri = image?.optStr("url", "") ?: ""
                        val sourcePart = dataUriSource(dataUri)
                        if (sourcePart != null) {
                            parts.put(
                                JSONObject().put("type", "image").put("source", sourcePart)
                            )
                        }
                    }
                }
            }
            return parts
        }

        @Throws(Exception::class)
        private fun geminiParts(content: Any?): JSONArray {
            val parts = JSONArray()
            if (content !is JSONArray) {
                parts.put(JSONObject().put("text", content?.toString() ?: ""))
                return parts
            }
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optStr("type", "")) {
                    "text" -> parts.put(
                        JSONObject().put("text", part.optStr("text", ""))
                    )

                    "image_url" -> {
                        val image = part.optJSONObject("image_url")
                        val dataUri = image?.optStr("url", "") ?: ""
                        val inline = dataUriSource(dataUri)
                        if (inline != null) {
                            parts.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject()
                                        .put("mimeType", inline.optStr("media_type"))
                                        .put("data", inline.optStr("data"))
                                )
                            )
                        }
                    }
                }
            }
            return parts
        }

        @Throws(Exception::class)
        private fun dataUriSource(dataUri: String?): JSONObject? {
            val comma = dataUri?.indexOf(',') ?: -1
            if (comma <= 5 || dataUri == null || !dataUri.startsWith("data:")) {
                return null
            }
            val metadata = dataUri.substring(5, comma)
            val mime = metadata.split(";")[0]
            return JSONObject().put("type", "base64").put("media_type", mime)
                .put("data", dataUri.substring(comma + 1))
        }

        // ---- endpoints -----------------------------------------------------

        @Throws(Exception::class)
        internal fun endpointFor(
            base: String?,
            model: String?,
            protocol: String,
            stream: Boolean
        ): String {
            var normalized = trimTrailingSlashes(base?.trimJava() ?: "")
            if (PROTOCOL_GEMINI == protocol) {
                var lower = normalized.lowercase(Locale.US)
                if (lower.contains(":generatecontent") ||
                    lower.contains(":streamgeneratecontent")
                ) {
                    var colon = lower.lastIndexOf(":generatecontent")
                    if (colon < 0) {
                        colon = lower.lastIndexOf(":streamgeneratecontent")
                    }
                    val prefix = normalized.substring(0, colon)
                    return prefix + if (stream) {
                        ":streamGenerateContent?alt=sse"
                    } else {
                        ":generateContent"
                    }
                }
                if (lower.endsWith("/openai")) {
                    normalized = normalized.substring(0, normalized.length - "/openai".length)
                    lower = normalized.lowercase(Locale.US)
                }
                if (lower.endsWith("/models")) {
                    normalized = normalized.substring(0, normalized.length - "/models".length)
                }
                var cleanModel = model?.trimJava() ?: ""
                if (cleanModel.startsWith("models/")) {
                    cleanModel = cleanModel.substring("models/".length)
                }
                val encoded = URLEncoder.encode(cleanModel, "UTF-8").replace("+", "%20")
                return appendPath(
                    normalized,
                    "models/" + encoded + if (stream) {
                        ":streamGenerateContent?alt=sse"
                    } else {
                        ":generateContent"
                    }
                )
            }
            val suffix = if (PROTOCOL_ANTHROPIC == protocol) "messages" else "chat/completions"
            val lower = normalized.lowercase(Locale.US)
            if (ENDPOINT_ALREADY_FULL.matches(lower)) {
                return normalized
            }
            return appendPath(normalized, suffix)
        }

        private val ENDPOINT_ALREADY_FULL =
            Regex(".*(/messages|/chat/completions)(\\?.*)?$")

        private val MESSAGES_TAIL = Regex(".*(/messages)(\\?.*)?$")

        internal fun resolveProtocol(selected: String?, base: String?, model: String?): String {
            if (Prefs.PROV_ANTHRO == selected) {
                return PROTOCOL_ANTHROPIC
            }
            if (Prefs.PROV_GEMINI == selected) {
                return PROTOCOL_GEMINI
            }
            if (Prefs.PROV_OPENAI == selected) {
                return PROTOCOL_OPENAI
            }
            val url = base?.lowercase(Locale.US) ?: ""
            if (url.contains("anthropic") || MESSAGES_TAIL.matches(url)) {
                return PROTOCOL_ANTHROPIC
            }
            if ((url.contains("generativelanguage.googleapis.com") ||
                    url.contains(":generatecontent") ||
                    url.contains(":streamgeneratecontent")) && !url.contains("/openai")
            ) {
                return PROTOCOL_GEMINI
            }
            // Unknown/custom gateways default to the de-facto OpenAI wire protocol.
            // Model names are intentionally not used: OpenRouter may serve Claude or
            // Gemini models through an OpenAI-compatible endpoint.
            return PROTOCOL_OPENAI
        }

        @Throws(Exception::class)
        private fun writeBody(connection: HttpURLConnection, body: JSONObject) {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            val output = connection.outputStream
            try {
                output.write(bytes)
                output.flush()
            } finally {
                output.close()
            }
        }

        // ---- response parsing ----------------------------------------------

        @Throws(Exception::class)
        private fun parseComplete(
            response: String,
            protocol: String,
            callback: StreamCallback?
        ): String {
            val json = JSONObject(response)
            if (PROTOCOL_ANTHROPIC == protocol) {
                val content = json.optJSONArray("content")
                val text = StringBuilder()
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        val type = part.optStr("type")
                        if (type == "text") {
                            text.append(part.optStr("text", ""))
                        } else if (callback != null && type == "thinking") {
                            val thinking = part.optStr("thinking", "")
                            if (thinking.isNotEmpty()) {
                                callback.onThinking(thinking)
                            }
                        }
                    }
                }
                return text.toString()
            }
            if (PROTOCOL_GEMINI == protocol) {
                val candidates = json.optJSONArray("candidates")
                val candidate = candidates?.optJSONObject(0)
                val content = candidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val value = part.optStr("text", "")
                        if (part.optBoolean("thought", false)) {
                            if (callback != null && value.isNotEmpty()) {
                                callback.onThinking(value)
                            }
                        } else {
                            text.append(value)
                        }
                    }
                }
                return text.toString()
            }
            val choices = json.optJSONArray("choices")
            val choice = choices?.optJSONObject(0)
            val message = choice?.optJSONObject("message") ?: return ""
            val thinking = firstNonEmpty(
                message.optStr("reasoning_content", ""),
                message.optStr("reasoning", ""),
                message.optStr("thinking", ""),
                message.optStr("analysis", "")
            )
            if (callback != null && thinking.isNotEmpty()) {
                callback.onThinking(thinking)
            }
            return contentText(message.opt("content"))
        }

        // ---- error messages ------------------------------------------------

        internal fun httpMessage(code: Int, body: String?): String =
            httpMessage(code, extractError(body), 0L, "")

        internal fun httpMessage(
            code: Int,
            detail: String?,
            retryAfterMs: Long,
            requestId: String?
        ): String {
            val base = when (code) {
                400 -> Fa.ERR_BADREQ
                401 -> Fa.ERR_AUTH
                403 -> Fa.ERR_FORBIDDEN
                404 -> Fa.ERR_NOTFOUND
                408 -> Fa.ERR_TIMEOUT
                413 -> Fa.ERR_TOO_LARGE
                422 -> Fa.ERR_UNPROCESSABLE
                425 -> Fa.ERR_NOT_READY
                429 -> classify429(detail)
                498 -> Fa.ERR_NO_CAPACITY + " (HTTP 498)"
                529 -> Fa.ERR_OVERLOAD + " (HTTP 529)"
                else -> if (code >= 500) {
                    Fa.ERR_SERVER + " (HTTP " + code + ")"
                } else {
                    "HTTP $code"
                }
            }
            val result = StringBuilder(base)
            if (detail != null && detail.isNotBlankJava() && !base.contains(detail.trimJava())) {
                result.append(Fa.ERR_DETAILS_LINE).append(Util.truncate(detail.trimJava(), 500))
            }
            if (retryAfterMs > 0) {
                val seconds = Math.max(1L, (retryAfterMs + 999L) / 1000L)
                val units = if (seconds == 1L) Fa.ERR_SECOND else Fa.ERR_SECONDS
                result.append(Fa.ERR_RETRY_IN.format(units.format(seconds.toString())))
            }
            if (!requestId.isNullOrEmpty()) {
                result.append(Fa.ERR_REQ_ID).append(requestId)
            }
            return result.toString()
        }

        private fun classify429(detail: String?): String {
            val lower = detail?.lowercase(Locale.US) ?: ""
            if (lower.contains("quota") || lower.contains("billing") ||
                lower.contains("insufficient_quota")
            ) {
                return Fa.ERR_QUOTA
            }
            if (lower.contains("capacity") || lower.contains("overload") ||
                lower.contains("flex tier")
            ) {
                return Fa.ERR_MODEL_BUSY
            }
            return Fa.ERR_RATE_ACTIVE
        }

        private fun parseLongHeader(connection: HttpURLConnection, name: String): Long {
            val value = header(connection, name)
            if (value.isEmpty()) {
                return -1L
            }
            return try {
                value.trimJava().toLong()
            } catch (ignored: Exception) {
                -1L
            }
        }

        private val DURATION_PART = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)")

        internal fun parseProviderDuration(value: String?): Long {
            if (value.isNullOrBlankJava()) {
                return 0L
            }
            val text = value.trimJava().lowercase(Locale.US)
            try {
                return Math.max(0L, Math.round(text.toDouble() * 1000.0))
            } catch (ignored: Exception) {
            }
            var total = 0.0
            for (match in DURATION_PART.findAll(text)) {
                val amount = match.groupValues[1].toDouble()
                when (match.groupValues[2]) {
                    "h" -> total += amount * 3600000.0
                    "m" -> total += amount * 60000.0
                    "s" -> total += amount * 1000.0
                    else -> total += amount
                }
            }
            return Math.max(0L, Math.round(total))
        }

        private fun parseRetryAfter(connection: HttpURLConnection): Long {
            val value = header(connection, "Retry-After")
            if (value.isEmpty()) {
                return 0L
            }
            return try {
                Math.max(0L, Math.round(value.trimJava().toDouble() * 1000.0))
            } catch (ignored: Exception) {
                0L
            }
        }

        private fun extractError(body: String?): String {
            if (body.isNullOrBlankJava()) {
                return ""
            }
            return try {
                val json = JSONObject(body)
                val error = json.opt("error")
                if (error is JSONObject) {
                    val message = error.optStr("message", "")
                    val code = error.optStr("code", error.optStr("type", ""))
                    message + if (code.isEmpty() || message.contains(code)) {
                        ""
                    } else {
                        " [$code]"
                    }
                } else if (error != null && error !== JSONObject.NULL) {
                    error.toString()
                } else {
                    firstNonEmpty(json.optStr("message", ""), json.optStr("detail", ""))
                }
            } catch (ignored: Exception) {
                Util.truncate(body.replace(Regex("\\s+"), " ").trimJava(), 500)
            }
        }

        private fun isTransientNetworkFailure(error: Exception): Boolean {
            val name = error.javaClass.simpleName
            return name.contains("SocketTimeout") || name.contains("SocketException") ||
                name.contains("EOFException") || name.contains("ProtocolException")
        }

        internal fun friendlyMessage(error: Exception): String {
            if (error is CancellationToken.CancelledException) {
                return Fa.ERR_STOPPED_BY_YOU
            }
            val name = error.javaClass.simpleName
            if (name.contains("UnknownHost") || name.contains("ConnectException") ||
                name.contains("NoRouteToHost")
            ) {
                return Fa.ERR_NO_NET
            }
            if (name.contains("SSL") || name.contains("Certificate")) {
                return Fa.ERR_TLS + safeExceptionMessage(error)
            }
            if (name.contains("Timeout") || name.contains("SocketTimeout")) {
                return Fa.ERR_TIMEOUT
            }
            return safeExceptionMessage(error)
        }

        /** Returns null when the connection works, otherwise a human message. */
        fun testConnection(
            base: String?,
            key: String?,
            model: String?,
            provider: String?,
            timeoutSeconds: Int = Prefs.DEFAULT_TIMEOUT_SECONDS
        ): String? {
            return try {
                // 512, not 16. A 16-token ceiling is fine for a plain chat model
                // but GUARANTEES an empty answer from a reasoning model (o1/o3,
                // gpt-5, R1, QwQ, Gemini thinking…): those spend their budget on
                // internal reasoning first, so the visible text comes back blank
                // and the test reported "connection works but the answer was
                // unreadable" for an endpoint that chats perfectly. Thinking is
                // also disabled below for the same reason.
                val client = LlmClient(base, key, model, provider, 512, 0.2f, "low", 0)
                // Honour the user's configured timeout here too. Without this the
                // test always used the 120s default, so someone who raised it for a
                // slow local model got a working chat but a FAILING test button.
                client.readTimeoutSeconds = timeoutSeconds
                val messages = JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply OK")
                )
                val result = client.completeOnce(messages, CancellationToken())
                // Reaching here at all means the endpoint accepted the request and
                // returned a well-formed body: auth, URL, model name and network
                // are all proven good. An empty TEXT field after that is a quirk of
                // the model (pure-reasoning output, a filtered reply), NOT a broken
                // connection — reporting it as a failure is what made the button
                // disagree with a chat that plainly works.
                null
            } catch (error: LlmException) {
                error.message
            } catch (error: Exception) {
                friendlyMessage(error)
            }
        }

        /** Backwards-compatible overload for older callers. */
        fun testConnection(
            base: String?,
            key: String?,
            model: String?,
            anthropic: Boolean
        ): String? = testConnection(
            base, key, model, if (anthropic) Prefs.PROV_ANTHRO else Prefs.PROV_OPENAI
        )

        // ---- small helpers -------------------------------------------------

        @Throws(Exception::class)
        private fun readAll(input: InputStream?, maxBytes: Int, token: CancellationToken): String {
            if (input == null) {
                return ""
            }
            val output = ByteArrayOutputStream()
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
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }

        private fun isCancelled(token: CancellationToken, callback: StreamCallback): Boolean =
            token.isCancelled || callback.isCancelled()

        private fun isRetryableStreamError(error: JSONObject): Boolean {
            val type = error.optStr("type", error.optStr("code", ""))
                .lowercase(Locale.US)
            return type.contains("overload") || type.contains("rate") ||
                type.contains("timeout") || type.contains("server")
        }

        private fun isReasoningModel(model: String?): Boolean {
            var name = model?.lowercase(Locale.US) ?: ""
            val slash = name.lastIndexOf('/')
            if (slash >= 0) {
                name = name.substring(slash + 1)
            }
            return name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4") ||
                name.startsWith("gpt-5") || name.contains("reasoner") ||
                name.contains("deepseek-r") || name.contains("qwq") ||
                name.endsWith("-r1") || name.contains("-thinking")
        }

        private fun isGeminiThinkingModel(model: String?): Boolean {
            val name = model?.lowercase(Locale.US) ?: ""
            return name.startsWith("gemini-2.5") || name.startsWith("gemini-3")
        }

        private fun isOfficialGeminiEndpoint(base: String?): Boolean {
            val url = base?.lowercase(Locale.US) ?: ""
            return url.contains("generativelanguage.googleapis.com") && !url.contains("/openai")
        }

        private fun geminiThinkingBudget(level: String?, model: String?): Int {
            val name = model?.lowercase(Locale.US) ?: ""
            val flash = name.contains("flash")
            return when (level) {
                "low" -> 1024
                "medium" -> if (flash) 4096 else 8192
                "high" -> if (flash) 8192 else 16384
                "xhigh" -> if (flash) 16384 else 24576
                "max" -> if (flash) 24576 else 32768
                else -> if (flash) 4096 else 8192
            }
        }

        private fun usesMaxCompletionTokens(base: String?, model: String?): Boolean {
            val url = base?.lowercase(Locale.US) ?: ""
            return url.contains("api.openai.com") || url.contains("groq.com") ||
                url.contains("generativelanguage.googleapis.com") || isReasoningModel(model)
        }

        private fun supportsReasoningEffort(base: String?): Boolean {
            val url = base?.lowercase(Locale.US) ?: ""
            return url.contains("api.openai.com") || url.contains("openrouter.ai") ||
                url.contains("generativelanguage.googleapis.com")
        }

        private fun openAiEffort(level: String?, base: String?): String {
            val b = base?.lowercase(Locale.US) ?: ""
            // OpenAI and Groq only accept low/medium/high — clamp the two extra tiers
            // there. Other endpoints (OpenRouter, custom gateways) get the real value.
            val strict = b.contains("api.openai.com") || b.contains("groq.com")
            return when (level) {
                "low" -> "low"
                "medium" -> "medium"
                "high" -> "high"
                "xhigh" -> if (strict) "high" else "xhigh"
                "max" -> if (strict) "high" else "max"
                else -> "medium"
            }
        }

        private fun clampTemperature(value: Float): Double =
            Math.max(0.0, Math.min(2.0, value.toDouble()))

        /**
         * Anthropic rejects temperature > 1.0 outright, but the settings slider
         * runs to 2.00 (correct for OpenAI). Clamping per protocol turns a hard
         * HTTP 400 into the nearest value the provider accepts.
         */
        private fun clampTemperatureFor(protocol: String, value: Float): Double {
            val ceiling = if (PROTOCOL_ANTHROPIC == protocol) 1.0 else 2.0
            return Math.max(0.0, Math.min(ceiling, value.toDouble()))
        }

        /**
         * True only for Claude models that actually support extended thinking.
         * Sending `thinking:{type:enabled}` to claude-3-5-* is an unconditional
         * HTTP 400 with no fallback, so the old "name contains claude" test
         * bricked every request on those models.
         */
        internal fun supportsAnthropicThinking(model: String?): Boolean {
            val m = model?.lowercase(Locale.US) ?: ""
            if (!m.contains("claude")) {
                return false
            }
            if (m.contains("thinking")) {
                return true
            }
            // 3.7 and everything from 4 onwards; 3.0/3.5 explicitly do not.
            if (m.contains("3-5") || m.contains("3.5") ||
                m.contains("3-0") || m.contains("3.0") ||
                m.contains("claude-3-opus") || m.contains("claude-3-sonnet") ||
                m.contains("claude-3-haiku")
            ) {
                return false
            }
            return m.contains("3-7") || m.contains("3.7") ||
                m.contains("claude-4") || m.contains("claude-opus-4") ||
                m.contains("claude-sonnet-4") || m.contains("claude-haiku-4") ||
                m.contains("-4-") || m.contains("claude-5") || m.contains("-5-")
        }

        /**
         * Joins [suffix] onto [base]'s path, preserving [base]'s query string.
         *
         * The suffix may itself carry a query (Gemini's streaming suffix ends
         * in `?alt=sse`). Appending the base's `?key=…` after that produced
         * `…?alt=sse?key=SECRET`, which Google parses as the single parameter
         * `alt = "sse?key=SECRET"` and rejects with a 400 — and `?key=` is
         * Google's own documented auth form, so this was a realistic config.
         */
        private fun appendPath(base: String, suffix: String): String {
            val query = base.indexOf('?')
            val path = if (query >= 0) base.substring(0, query) else base
            var tail = if (query >= 0) base.substring(query) else ""
            val joined = trimTrailingSlashes(path) + "/" + suffix
            if (tail.isEmpty()) {
                return joined
            }
            if (tail.startsWith("?")) {
                tail = tail.substring(1)
            }
            if (tail.isEmpty()) {
                return joined
            }
            return joined + (if (joined.contains("?")) "&" else "?") + tail
        }

        private fun trimTrailingSlashes(value: String): String {
            var result = value
            while (result.endsWith("/")) {
                result = result.substring(0, result.length - 1)
            }
            return result
        }

        private fun header(connection: HttpURLConnection?, name: String): String {
            if (connection == null) {
                return ""
            }
            return connection.getHeaderField(name)?.trimJava() ?: ""
        }

        private fun contentText(content: Any?): String {
            if (content == null || content === JSONObject.NULL) {
                return ""
            }
            if (content is String) {
                return content
            }
            if (content is JSONArray) {
                val text = StringBuilder()
                for (i in 0 until content.length()) {
                    content.optJSONObject(i)?.let { text.append(it.optStr("text", "")) }
                }
                return text.toString()
            }
            return content.toString()
        }

        private fun firstNonEmpty(vararg values: String?): String {
            for (value in values) {
                if (!value.isNullOrEmpty()) {
                    return value
                }
            }
            return ""
        }

        private fun safeExceptionMessage(error: Exception): String {
            val message = error.message
            return if (message.isNullOrBlankJava()) Fa.ERR_UNKNOWN else message
        }

        private fun closeQuietly(reader: BufferedReader?) {
            if (reader == null) {
                return
            }
            try {
                reader.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
