package com.vepro.code

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** JVM regression suite for provider routing, retry and active cancellation. */
object CoreRegressionTests {

    private var assertions = 0

    @JvmStatic
    fun main(args: Array<String>) {
        // Every network test here talks to a loopback HTTP server. Model-chosen
        // URLs are blocked from reaching private/loopback addresses by default
        // (that is the whole point of NetworkPolicy.requireSafeHttps), so the
        // suite opts in explicitly — exactly as a user would with the
        // "local network access" switch in Settings.
        NetworkPolicy.allowLocalNetwork = true
        testProviderResolutionAndEndpoints()
        testProviderBodies()
        testReasoningBudgetsAreAscending()
        testReasoningProtocolsAreDistinct()
        testReasoningNeverPromisesFabricatedThought()
        testErrorClassificationAndDurations()
        testRunStateStopsBeforeServiceStarts()
        testRunSlotExclusivityAndStaleFinish()
        testApprovalWaitIsCancellable()
        testCancellationSignalNeverBlocksCaller()
        testOpenAiStreamingAndBoundedRetry()
        testKeyRouterRotatesOnRateLimit()
        testKeyRouterExhaustionShowsError()
        testNormalizeEscapesRepairsDoubleEscapedOutput()
        testJsonResponseWithoutDuplicateFallback()
        testJsonResponsePreservesReasoning()
        testClientErrorIsNeverReplayed()
        testWebFetch429IsAttributedToWebsite()
        testStopBeforeFirstByte()
        testStopDuringStream()
        testNetworkPolicySplitsUserEndpointFromModelUrls()
        testEndpointQueryMergingAndCapabilityGates()
        testCleanUrlKeepsLegitimateUrlsIntact()
        testLengthFinishReasonIsReportedAsTruncation()
        testCleanStopIsNotReportedAsTruncation()
        testAbruptEndOfStreamIsReportedAsTruncation()
        testAnthropicMaxTokensIsReportedAsTruncation()
        testMalformedChunkMidStreamDoesNotKillTheAnswer()
        testContinuationRequestCarriesThePartialAnswer()
        testOpenFenceIsClosedOnGiveUp()
        testToolCallParsingIgnoresUnknownNames()
        testFenceSplitOnlyCountsLineOpeningFences()
        testOpenThinkTagInsideAFenceDoesNotEatTheAnswer()
        testAcceptModeGatesReadsNotJustWrites()
        testModePreferenceFailsClosed()
        testUnknownToolNameStillReachesTheEngine()
        testFenceClosingAgreesWithTheRenderer()
        testQuotedThinkTagDoesNotHideARealOne()
        testLineNumberPrefixesAreStrippedFromOldString()
        testQuotedReasoningTagsSurviveInCodeAndBackticks()
        testStaticAndStreamedRenderingAgreeOnEscapedOutput()
        testToolCallStrippingAgreesWithParsing()
        testReadWindowKeepsFileGeometry()
        testAStreamingToolCallIsNeverVisible()
        testABrokenToolCallIsRecognisedAsAnAttempt()
        testOnlyAPreambleIsTreatedAsAPromise()
        testPlanStepsRejectRulesAndBareNumbers()
        testSearchResultsYieldHostsAndCounts()
        testATrailSurvivesBeingSavedAndReloaded()
        testAWorkflowClaimsThePhaseItBelongsTo()
        testAWorkflowRecordsRealAgents()
        testEveryQueuedApprovalIsReleasedByCancel()
        testTheApprovalQueueSurfacesOneAtATime()
        testAThrownStepFailsTheOpenPhases()
        testFaultBackoffWidensAndIsBounded()
        testATrailSurvivesConcurrentReadsAndWrites()
        testAStripIsOnlyShownForWorkThatHappened()
        testSearchResultsSurviveAndDeduplicate()
        testAPinnedChatSurvivesAndSortsFirst()
        testADiffNarrowsToWhatChanged()
        testAFileChangeRidesOnItsTrailStep()
        testAnUnnamedPhaseKeepsATitle()
        testAStoppedRunSaysSo()
        testAPersianPlanMatchesAnEnglishTaskName()
        testTheStripSummarisesWhatItCost()
        println("PASS CoreRegressionTests: $assertions assertions")
    }

    /**
     * The first fix only guarded the OPEN tag. A *closed* `<think>…</think>`
     * quoted inside a fence or inline backticks was still hoovered into the
     * reasoning panel, leaving an empty code card where the example had been.
     */
    private fun testQuotedReasoningTagsSurviveInCodeAndBackticks() {
        val fenced = "```xml\n<think>step by step</think>\n```\nAnswer."
        val a = Think.split(fenced)
        eq("", a.thinking)
        truth(a.visible.contains("<think>step by step</think>"), "a fenced example was gutted")

        val inline = "Use `<thinking>x</thinking>` tags to mark reasoning."
        val b = Think.split(inline)
        eq("", b.thinking)
        truth(b.visible.contains("<thinking>x</thinking>"), "an inline code span was gutted")

        // A real reasoning block outside code is still hidden.
        val real = "<think>private</think>Visible answer."
        val c = Think.split(real)
        eq("private", c.thinking)
        eq("Visible answer.", c.visible)
    }

    /**
     * `render()` normalises escaped output before splitting, exactly as
     * `Streaming.update` does — otherwise a code block flips to raw backticks
     * the moment the step finalises, and again on every reload.
     */
    private fun testStaticAndStreamedRenderingAgreeOnEscapedOutput() {
        val escaped = "Here you go:\\n```kotlin\\nval x = 1\\n```\\nThat's it."
        val repaired = MarkdownRenderer.normalizeEscapes(escaped)
        eq(3, MarkdownRenderer.splitFences(repaired).size)
        truth(
            MarkdownRenderer.splitFences(escaped).size == 1,
            "test premise broken: the raw form should not split"
        )
    }

    private fun testToolCallStrippingAgreesWithParsing() {
        // Unfenced call: executed, so it must not also be left on screen.
        val unfenced = "Sure. {\"tool\": \"read_file\", \"args\": {\"path\": \"a\"}} done"
        eq("read_file", AgentEngine.parseToolCall(unfenced)?.name)
        truth(
            !AgentEngine.stripToolCalls(unfenced).contains("read_file"),
            "an executed unfenced tool call was left in the transcript"
        )
        // A JSON document that merely has a "tool" key is not a call.
        val catalogue = "```json\n{\"tool\": \"hammer\", \"price\": 10}\n```"
        truth(AgentEngine.parseToolCall(catalogue) == null, "a catalogue entry was executed")
        eq(catalogue, AgentEngine.stripToolCalls(catalogue))
        // Non-string tool names are not calls either.
        truth(AgentEngine.parseToolCall("{\"tool\": 42}") == null, "a numeric tool name parsed")
        truth(
            AgentEngine.parseToolCall("{\"tool\": {\"a\": 1}}") == null,
            "an object tool name parsed"
        )
        // A hallucinated tool with NO args must still reach the engine, so it
        // comes back as "unknown tool" and the model can correct itself rather
        // than the run ending on a stray JSON block.
        eq("bash", AgentEngine.parseToolCall("{\"tool\": \"bash\"}")?.name)
        // A known tool named only by "name" is a call; args default to empty.
        val named = AgentEngine.parseToolCall("{\"name\": \"recall\"}")
        eq("recall", named?.name)
        eq(0, named?.args?.length())
    }

    /**
     * A read window must be a faithful copy of the file: same line numbers, no
     * newlines the file does not have. A line longer than the cap is truncated
     * and LABELLED, never split into extra numbered lines.
     */
    private fun testReadWindowKeepsFileGeometry() {
        fun window(body: String, from: Int = 0, endLine: Int = 0, maxBytes: Int = 20000) =
            Tools.readWindow(
                java.io.BufferedReader(java.io.StringReader(body)), from, endLine, maxBytes, null
            )

        // An over-long line stays ONE line, is labelled, and does not renumber
        // the lines after it.
        val long = window("a".repeat(Tools.LINE_CHAR_CAP + 500) + "\nB\nC\n")
        eq(3, long.emitted)
        truth(long.text.contains("line truncated"), "an over-cap line was not labelled")
        truth(long.text.contains("\n2\tB\n"), "line numbering shifted after a long line")
        truth(long.text.contains("\n3\tC\n"), "line numbering shifted after a long line")
        truth(long.atEof, "the end of a 3-line file was not detected")

        // A line of EXACTLY the cap must not produce a phantom empty line.
        val exactCap = window("a".repeat(Tools.LINE_CHAR_CAP) + "\nnext\n")
        eq(2, exactCap.emitted)
        truth(exactCap.text.contains("\n2\tnext\n"), "a cap-length line spawned a phantom line")

        // CRLF survives, so a multi-line old_string from a Windows file matches.
        truth(
            window("one\r\ntwo\r\n").text.startsWith("1\tone\r\n"),
            "the carriage return was stripped, so no CRLF edit could ever match"
        )

        // A file of exactly MAX_READ_LINES lines really is at its end — offering
        // a continuation there would send the model after nothing.
        val exact = window((1..Tools.MAX_READ_LINES).joinToString("\n") { "L$it" } + "\n")
        eq(Tools.MAX_READ_LINES, exact.emitted)
        truth(exact.atEof, "a complete file offered a continuation that returns nothing")
        // One more line, and the continuation is real.
        val over = window((1..Tools.MAX_READ_LINES + 1).joinToString("\n") { "L$it" } + "\n")
        eq(Tools.MAX_READ_LINES, over.emitted)
        truth(!over.atEof, "a truncated window claimed to be the end of the file")
        eq(Tools.MAX_READ_LINES, over.lastLine)

        // Empty and degenerate inputs.
        eq(0, window("").emitted)
        truth(window("").atEof, "an empty file was not reported as ended")
        eq(1, window("no trailing newline").emitted)
        eq(0, window("short\n", from = 99).emitted)

        // The byte cap stops the window without losing the resume point.
        val capped = window("aaaa\nbbbb\ncccc\n", maxBytes = 6)
        eq(1, capped.emitted)
        eq(1, capped.lastLine)
        truth(!capped.atEof, "the byte cap claimed the file had ended")
    }

    /**
     * A hallucinated tool must still be *seen* as a tool call. Rejecting it
     * outright makes `parseToolCall` return null, the engine treats the raw JSON
     * as a final answer, and the run ends instead of letting the model correct
     * itself from "unknown tool".
     */
    private fun testUnknownToolNameStillReachesTheEngine() {
        val call = AgentEngine.parseToolCall(
            "```json\n{\"tool\": \"run_shell\", \"args\": {\"cmd\": \"ls\"}}\n```"
        )
        eq("run_shell", call?.name)
        // ...while a bare "name" on unknown-tool-shaped JSON stays a code block.
        truth(
            AgentEngine.parseToolCall("```json\n{\"name\": \"run_shell\"}\n```") == null,
            "a bare name key was treated as an invocation"
        )
    }

    private fun testFenceClosingAgreesWithTheRenderer() {
        // A ``` quoted mid-sentence is not a fence for either of them.
        val quoted = "To open a block, type ``` at the start of a line."
        eq(quoted, AgentEngine.closeOpenFence(quoted))
        eq(1, MarkdownRenderer.splitFences(quoted).size)
        // A mid-line ``` must not make a genuinely open fence look balanced.
        val mixed = "type ``` inline\n```kotlin\nval x = 1"
        val closed = AgentEngine.closeOpenFence(mixed)
        truth(closed.endsWith("```"), "an open fence was left open by a mid-line decoy")
        eq(3, MarkdownRenderer.splitFences(closed).size)
    }

    private fun testQuotedThinkTagDoesNotHideARealOne() {
        val md = "```md\nPut reasoning in <think> tags.\n```\nAnswer: 42. <think>double-check"
        val parts = Think.split(md)
        eq("double-check", parts.thinking)
        truth(parts.visible.contains("Answer: 42."), "the answer was swallowed")
        truth(!parts.visible.contains("double-check"), "an open reasoning tail leaked to the user")
    }

    private fun testLineNumberPrefixesAreStrippedFromOldString() {
        eq("val x = 1\nval y = 2", Tools.stripLineNumbers("12\tval x = 1\n13\tval y = 2"))
        // Real code that merely contains a tab must be left alone.
        truth(Tools.stripLineNumbers("val x\t= 1") == null, "ordinary code was mangled")
        truth(Tools.stripLineNumbers("no tabs here") == null, "unprefixed text was rewritten")
    }

    // ---- truncation / never-stop-mid-answer --------------------------------

    /**
     * The bug this suite exists to pin down: an answer that ends because it hit
     * `max_tokens` used to be indistinguishable from one that finished, so the
     * run stopped dead — usually inside a code block — with no error and no way
     * to resume.
     */
    private fun testLengthFinishReasonIsReportedAsTruncation() {
        val server = server(
            HttpHandler { exchange ->
                val sse = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\".phone.light {\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"\\n  --bg: var(--\"}," +
                        "\"finish_reason\":\"length\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq(".phone.light {\n  --bg: var(--", callback.done)
            eq(1, callback.truncations.get())
            truth(callback.error == null, "truncation must not surface as an error")
        } finally {
            server.stop(0)
        }
    }

    private fun testCleanStopIsNotReportedAsTruncation() {
        val server = server(
            HttpHandler { exchange ->
                val sse = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}," +
                        "\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq("done", callback.done)
            eq(0, callback.truncations.get())
        } finally {
            server.stop(0)
        }
    }

    /** No `[DONE]`, no finish reason — the body just stops. */
    private fun testAbruptEndOfStreamIsReportedAsTruncation() {
        val server = server(
            HttpHandler { exchange ->
                val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"half an ans\"}}]}\n\n"
                    .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq("half an ans", callback.done)
            eq(1, callback.truncations.get())
            truth(callback.error == null, "an abrupt end must not surface as an error")
        } finally {
            server.stop(0)
        }
    }

    private fun testAnthropicMaxTokensIsReportedAsTruncation() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            val sse = (
                "data: {\"type\":\"content_block_delta\",\"delta\":" +
                    "{\"type\":\"text_delta\",\"text\":\"cut off here\"}}\n\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":" +
                    "{\"stop_reason\":\"max_tokens\"}}\n\n" +
                    "data: {\"type\":\"message_stop\"}\n\n"
                ).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(sse) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            val base = "http://127.0.0.1:" + server.address.port + "/v1"
            val callback = RecordingCallback()
            LlmClient(base, "test", "claude-test", "anthropic", 256, 0.2f, "low", 1024)
                .streamChat(messages(), CancellationToken(), callback)
            eq("cut off here", callback.done)
            eq(1, callback.truncations.get())
        } finally {
            server.stop(0)
        }
    }

    /**
     * A gateway injecting a plain-text notice longer than 32 chars used to abort
     * a perfectly healthy stream. Once real text is on screen, skipping the
     * chunk beats killing the answer.
     */
    private fun testMalformedChunkMidStreamDoesNotKillTheAnswer() {
        val server = server(
            HttpHandler { exchange ->
                val sse = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\"first half \"}}]}\n\n" +
                        "data: upstream notice: your gateway is being migrated, please stand by\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"second half\"}," +
                        "\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq("first half second half", callback.done)
            truth(callback.error == null, "a mid-stream junk chunk killed the answer")
        } finally {
            server.stop(0)
        }
    }

    private fun testContinuationRequestCarriesThePartialAnswer() {
        val base = messages()
        val resumed = AgentEngine.continuationMessages(base, "half an answer")
        eq(base.length() + 2, resumed.length())
        val assistant = resumed.optJSONObject(resumed.length() - 2)
        eq("assistant", assistant?.optString("role"))
        eq("half an answer", assistant?.optString("content"))
        val nudge = resumed.optJSONObject(resumed.length() - 1)
        eq("user", nudge?.optString("role"))
        truth(
            nudge?.optString("content")?.contains("[CONTINUE]") == true,
            "resume turn does not carry the continue marker"
        )
        // The base array must be reusable for the next resume round.
        truth(base.length() != resumed.length(), "continuationMessages mutated its input")
    }

    private fun testOpenFenceIsClosedOnGiveUp() {
        eq("a\n```kt\ncode\n```", AgentEngine.closeOpenFence("a\n```kt\ncode"))
        eq("balanced ``` and ``` again", AgentEngine.closeOpenFence("balanced ``` and ``` again"))
        eq("", AgentEngine.closeOpenFence(""))
    }

    // ---- block classification ---------------------------------------------

    private fun testToolCallParsingIgnoresUnknownNames() {
        // A package.json shown to the user is not a tool call, and must survive.
        val shown = "Here you go:\n```json\n{\"name\": \"my-app\", \"version\": \"1.0.0\"}\n```"
        truth(AgentEngine.parseToolCall(shown) == null, "a JSON snippet parsed as a tool call")
        eq(shown, AgentEngine.stripToolCalls(shown))
        // A real call still parses and is still stripped from the transcript.
        val real = "```json\n{\"tool\": \"read_file\", \"args\": {\"path\": \"a.txt\"}}\n```"
        eq("read_file", AgentEngine.parseToolCall(real)?.name)
        eq("", AgentEngine.stripToolCalls(real).trimJava())
    }

    private fun testFenceSplitOnlyCountsLineOpeningFences() {
        // A fence inside a code block that is showing markdown must not flip the
        // parity for everything after it.
        val md = "intro\n```md\nuse ``` to open a block\n```\noutro"
        val segs = MarkdownRenderer.splitFences(md)
        eq(3, segs.size)
        eq("intro\n", segs[0])
        truth(segs[1].contains("use ``` to open"), "inline fence was treated as a delimiter")
        eq("\noutro", segs[2])
    }

    private fun testOpenThinkTagInsideAFenceDoesNotEatTheAnswer() {
        val md = "Example:\n```xml\n<think>not reasoning</think2>\n```\nAnd the real answer."
        val parts = Think.split(md)
        eq("", parts.thinking)
        truth(
            parts.visible.contains("And the real answer."),
            "a quoted <think> tag swallowed the rest of the answer"
        )
        // A genuine open reasoning tail still hides its trailing text.
        val live = Think.split("visible part <think>still reasoning")
        eq("still reasoning", live.thinking)
        eq("visible part", live.visible)
    }

    // ---- modes -------------------------------------------------------------

    private fun testAcceptModeGatesReadsNotJustWrites() {
        truth(Tools.needsApproval(Tools.ToolNames.READ_FILE), "reads are not gated in ACCEPT mode")
        truth(Tools.needsApproval(Tools.ToolNames.LIST_DIR), "listing is not gated")
        truth(Tools.needsApproval(Tools.ToolNames.WEB_SEARCH), "web search is not gated")
        truth(Tools.needsApproval(Tools.ToolNames.DELETE), "delete is not gated")
        truth(Tools.isKnownTool(Tools.ToolNames.EDIT_FILE), "edit_file is not a known tool")
        truth(!Tools.isKnownTool("my-app"), "an arbitrary name passed as a known tool")
        // "always allow" only ever applies to the tool it was granted for.
        AgentEngine.clearSessionAllowances()
        truth(!AgentEngine.isAllowedForSession(Tools.ToolNames.READ_FILE), "stale allowance")
        AgentEngine.allowForSession(Tools.ToolNames.READ_FILE)
        truth(AgentEngine.isAllowedForSession(Tools.ToolNames.READ_FILE), "allowance not honoured")
        truth(!AgentEngine.isAllowedForSession(Tools.ToolNames.DELETE), "allowance leaked")
        AgentEngine.clearSessionAllowances()
    }

    private fun testModePreferenceFailsClosed() {
        truth(Prefs.isValidMode(Prefs.MODE_ACCEPT), "accept rejected")
        truth(Prefs.isValidMode(Prefs.MODE_PLAN), "plan rejected")
        truth(Prefs.isValidMode(Prefs.MODE_AUTO), "auto rejected")
        truth(!Prefs.isValidMode("garbage"), "a bogus mode validated, granting full autonomy")
        truth(!Prefs.isValidMode(null), "null validated as a mode")
    }

    private fun testProviderResolutionAndEndpoints() {
        eq(
            "openai",
            LlmClient.resolveProtocol("auto", "https://api.groq.com/openai/v1", "llama-3.3")
        )
        eq(
            "anthropic",
            LlmClient.resolveProtocol("auto", "https://api.anthropic.com/v1", "claude-x")
        )
        eq(
            "gemini",
            LlmClient.resolveProtocol(
                "auto", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"
            )
        )
        eq(
            "openai",
            LlmClient.resolveProtocol(
                "openai", "https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-2.5-flash"
            )
        )
        eq(
            "openai",
            LlmClient.resolveProtocol("auto", "https://openrouter.ai/api/v1", "claude-3.7-sonnet")
        )
        eq(
            "https://api.groq.com/openai/v1/chat/completions",
            LlmClient.endpointFor("https://api.groq.com/openai/v1/", "llama-3.3", "openai", true)
        )
        eq(
            "https://api.anthropic.com/v1/messages",
            LlmClient.endpointFor("https://api.anthropic.com/v1", "claude", "anthropic", true)
        )
        eq(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            LlmClient.endpointFor(
                "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash",
                "gemini", true
            )
        )
        eq(
            "https://example.test/deploy/chat/completions?api-version=2025-01-01",
            LlmClient.endpointFor(
                "https://example.test/deploy/chat/completions?api-version=2025-01-01", "x",
                "openai", true
            )
        )
    }

    private fun testProviderBodies() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "user").put("content", "hello"))

        val groq = LlmClient(
            "https://api.groq.com/openai/v1", "key", "llama-3.3-70b-versatile",
            "openai", 4096, 0.7f, "medium", 8000
        )
        val openAi = invokeBody(groq, "openAiBody", messages, true)
        truth(openAi.has("max_completion_tokens"), "Groq must receive max_completion_tokens")
        truth(openAi.has("temperature"), "non-reasoning Groq model must receive temperature")
        truth(
            !openAi.has("reasoning_effort"),
            "Groq Llama must not receive OpenAI reasoning_effort"
        )

        val openRouter = LlmClient(
            "https://openrouter.ai/api/v1", "key", "meta-llama/llama-3.3-70b-instruct",
            "openai", 4096, 0.7f, "medium", 8000
        )
        val openRouterBody = invokeBody(openRouter, "openAiBody", messages, true)
        truth(
            openRouterBody.has("max_tokens") && !openRouterBody.has("max_completion_tokens"),
            "generic OpenRouter model must use max_tokens"
        )

        val anthropic = LlmClient(
            "https://api.anthropic.com/v1", "key", "claude-sonnet-4-5",
            "anthropic", 4096, 0.7f, "medium", 8000
        )
        val anthropicBody = invokeBody(anthropic, "anthropicBody", messages, true)
        truth(anthropicBody.has("system"), "Anthropic system prompt must be separated")
        truth(
            anthropicBody.getJSONArray("messages").length() == 1,
            "Anthropic messages must exclude system row"
        )
        truth(
            anthropicBody.getInt("max_tokens") >
                anthropicBody.getJSONObject("thinking").getInt("budget_tokens"),
            "Anthropic output cap must exceed thinking budget"
        )

        val gemini = LlmClient(
            "https://generativelanguage.googleapis.com/v1beta", "key", "gemini-2.5-flash",
            "gemini", 4096, 0.7f, "medium", 8000
        )
        val geminiMethod =
            LlmClient::class.java.getDeclaredMethod("geminiBody", JSONArray::class.java)
        geminiMethod.isAccessible = true
        val geminiBody = geminiMethod.invoke(gemini, messages) as JSONObject
        truth(geminiBody.has("systemInstruction"), "Gemini systemInstruction missing")
        eq("user", geminiBody.getJSONArray("contents").getJSONObject(0).getString("role"))
        val geminiThinking =
            geminiBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        eq(4096, geminiThinking.getInt("thinkingBudget"))
        truth(
            geminiThinking.getBoolean("includeThoughts"),
            "Gemini thoughts must be returned for the reasoning UI"
        )

        val geminiGateway = LlmClient(
            "https://gateway.example/v1beta", "key", "gemini-2.5-flash",
            "gemini", 4096, 0.7f, "medium", 8000
        )
        val gatewayBody = geminiMethod.invoke(geminiGateway, messages) as JSONObject
        truth(
            !gatewayBody.getJSONObject("generationConfig").has("thinkingConfig"),
            "Gemini-specific thinkingConfig must not be sent to a generic endpoint"
        )

        val openAiReasoning = LlmClient(
            "https://api.openai.com/v1", "key", "gpt-5", "openai", 4096, 0.7f, "max", 60000
        )
        val openAiReasoningBody = invokeBody(openAiReasoning, "openAiBody", messages, true)
        eq("high", openAiReasoningBody.getString("reasoning_effort"))
        truth(
            !openAiReasoningBody.has("temperature"),
            "OpenAI reasoning model must not receive temperature"
        )

        val genericReasoning = LlmClient(
            "https://gateway.example/v1", "key", "gpt-5", "openai", 4096, 0.7f, "max", 60000
        )
        val genericReasoningBody = invokeBody(genericReasoning, "openAiBody", messages, true)
        truth(
            !genericReasoningBody.has("reasoning_effort"),
            "OpenAI reasoning_effort must not be sent to an unknown compatible endpoint"
        )

        val openRouterReasoning = LlmClient(
            "https://openrouter.ai/api/v1", "key", "gpt-5", "openai", 4096, 0.7f, "xhigh", 32000
        )
        val openRouterReasoningBody =
            invokeBody(openRouterReasoning, "openAiBody", messages, true)
        eq("xhigh", openRouterReasoningBody.getString("reasoning_effort"))

        val maxAnthropic = LlmClient(
            "https://api.anthropic.com/v1", "key", "claude-sonnet-4-5", "anthropic", 4096, 0.7f,
            "max", Prefs.thinkingBudgetForLevel("max")
        )
        val maxAnthropicBody = invokeBody(maxAnthropic, "anthropicBody", messages, true)
        eq(60000, maxAnthropicBody.getJSONObject("thinking").getInt("budget_tokens"))
        eq(64000, maxAnthropicBody.getInt("max_tokens"))
    }

    private fun testReasoningProtocolsAreDistinct() {
        val blocks = HashMap<String, String>()
        for (level in arrayOf("low", "medium", "high", "xhigh", "max")) {
            blocks[level] = AgentEngine.reasoningGuidance(level)
        }
        truth(blocks["low"] != blocks["medium"], "low and medium protocols collapsed")
        truth(blocks["medium"] != blocks["high"], "medium and high protocols collapsed")
        truth(blocks["high"] != blocks["xhigh"], "high and xhigh protocols collapsed")
        val max = blocks["max"] ?: ""
        truth(
            max.contains("several genuinely independent solution paths"),
            "max must require independent solution paths"
        )
        truth(
            max.contains("security/privacy") && max.contains("performance/resource"),
            "max must cover security and performance"
        )
        truth(max.contains("compatibility/provider"), "max must cover compatibility")
        truth(
            max.contains("use tools") && max.contains("separate final review"),
            "max must be tool-driven and require final review"
        )
    }

    private fun testReasoningNeverPromisesFabricatedThought() {
        val rule = AgentEngine.reasoningIntegrityRule()
        truth(
            rule.contains("Never invent private chain-of-thought"),
            "reasoning integrity rule missing"
        )
        truth(
            rule.contains("provider exposes native reasoning"),
            "native reasoning distinction missing"
        )
    }

    private fun testReasoningBudgetsAreAscending() {
        val levels = arrayOf("low", "medium", "high", "xhigh", "max")
        var previous = 0
        for (level in levels) {
            val budget = Prefs.thinkingBudgetForLevel(level)
            truth(budget > previous, "thinking budget did not increase at $level")
            previous = budget
        }
        eq(8000, Prefs.thinkingBudgetForLevel("unknown"))
    }

    private fun invokeBody(
        client: LlmClient,
        name: String,
        messages: JSONArray,
        stream: Boolean
    ): JSONObject {
        val method = LlmClient::class.java.getDeclaredMethod(
            name, JSONArray::class.java, Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(client, messages, stream) as JSONObject
    }

    private fun testErrorClassificationAndDurations() {
        val quota = LlmClient.httpMessage(
            429,
            "{\"error\":{\"message\":\"insufficient quota for this project\",\"type\":\"insufficient_quota\"}}"
        )
        truth(
            quota.contains("out of quota or credit") && quota.contains("insufficient quota"),
            "429 quota detail was lost"
        )
        truth(
            !LlmClient.httpMessage(400, "{\"error\":{\"message\":\"unsupported field\"}}")
                .contains(Fa.ERR_RATE),
            "400 must never be mislabeled as rate limit"
        )
        eq(7660L, LlmClient.parseProviderDuration("7.66s"))
        eq(179560L, LlmClient.parseProviderDuration("2m59.56s"))
        eq(250L, LlmClient.parseProviderDuration("250ms"))
    }

    private fun testRunStateStopsBeforeServiceStarts() {
        val chat = Chat("test-chat", "test", System.currentTimeMillis())
        val runId = AgentBus.beginStarting(chat.id, chat)
        truth(runId != 0L && AgentBus.isBusy(), "run slot was not claimed synchronously")
        truth(AgentBus.requestCancel(), "stop during STARTING was ignored")
        truth(
            AgentBus.isStopping() && AgentBus.token().isCancelled,
            "starting run did not enter STOPPING"
        )
        truth(AgentBus.markRunning(runId), "service owner could not attach to a stopping run")
        truth(AgentBus.isStopping(), "service start incorrectly cleared STOPPING")
        AgentBus.finish(runId)
        truth(!AgentBus.isBusy(), "run slot was not released")
    }

    private fun testRunSlotExclusivityAndStaleFinish() {
        val chat = Chat("exclusive-chat", "test", System.currentTimeMillis())
        val ids = LongArray(8)
        val starters = arrayOfNulls<Thread>(ids.size)
        for (i in starters.indices) {
            val index = i
            starters[i] = Thread { ids[index] = AgentBus.beginStarting(chat.id, chat) }
            starters[i]?.start()
        }
        var winners = 0
        var winner = 0L
        for (i in starters.indices) {
            starters[i]?.join()
            if (ids[i] != 0L) {
                winners++
                winner = ids[i]
            }
        }
        eq(1, winners)
        AgentBus.finish(winner)
        val next = AgentBus.beginStarting(chat.id, chat)
        truth(next != 0L && next != winner, "run IDs were not unique")
        AgentBus.finish(winner)
        truth(AgentBus.isBusy(), "stale finish cleared the newer run")
        AgentBus.requestCancel()
        AgentBus.finish(next)
    }

    private fun testApprovalWaitIsCancellable() {
        val token = CancellationToken()
        val result = BooleanArray(1)
        val waiter = Thread(
            { result[0] = AgentBus.awaitApproval("write_file", JSONObject(), token) },
            "approval-waiter"
        )
        waiter.start()
        Thread.sleep(40L)
        token.cancel()
        waiter.join(1000L)
        truth(!waiter.isAlive, "approval latch ignored cancellation")
        truth(!result[0], "cancelled approval was accepted")
    }

    private fun testCancellationSignalNeverBlocksCaller() {
        val token = CancellationToken()
        token.onCancel {
            try {
                Thread.sleep(5000L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val started = System.nanoTime()
        token.cancel()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        truth(elapsedMs < 100L, "cancel() was blocked by a cleanup listener: ${elapsedMs}ms")
    }

    private fun testOpenAiStreamingAndBoundedRetry() {
        val calls = AtomicInteger()
        val server = server(
            HttpHandler { exchange ->
                val call = calls.incrementAndGet()
                if (call == 1) {
                    val error =
                        "{\"error\":{\"message\":\"brief token window\",\"type\":\"rate_limit_error\"}}"
                            .toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.responseHeaders.add("Retry-After", "0.05")
                    exchange.sendResponseHeaders(429, error.size.toLong())
                    exchange.responseBody.use { it.write(error) }
                    return@HttpHandler
                }
                val sse = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq("hello world", callback.done)
            eq(2, calls.get())
            eq(1, callback.retries.get())
            truth(callback.error == null, "successful retry still reported error")
        } finally {
            server.stop(0)
        }
    }

    private fun testKeyRouterRotatesOnRateLimit() {
        val auths = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = server(
            HttpHandler { exchange ->
                val auth = exchange.requestHeaders.getFirst("Authorization")
                auths.add(auth ?: "")
                if (auth != "Bearer key-2") {
                    val error =
                        "{\"error\":{\"message\":\"rate limit reached: rpm exceeded\",\"type\":\"rate_limit_error\"}}"
                            .toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(429, error.size.toLong())
                    exchange.responseBody.use { it.write(error) }
                    return@HttpHandler
                }
                val sse = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\"rotated ok\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        )
        try {
            val callback = RecordingCallback()
            val base = "http://127.0.0.1:" + server.address.port + "/v1"
            LlmClient(
                base, listOf("key-1", "key-2"), "test-model", "openai", 256, 0.2f, "low", 1024
            ).streamChat(messages(), CancellationToken(), callback)
            eq("rotated ok", callback.done)
            eq(2, auths.size)
            eq("Bearer key-1", auths[0])
            eq("Bearer key-2", auths[1])
            truth(callback.error == null, "silent rotation surfaced an error: " + callback.error)
        } finally {
            server.stop(0)
        }
    }

    private fun testKeyRouterExhaustionShowsError() {
        val auths = java.util.Collections.synchronizedList(ArrayList<String>())
        val server = server(
            HttpHandler { exchange ->
                val auth = exchange.requestHeaders.getFirst("Authorization")
                auths.add(auth ?: "")
                val error =
                    "{\"error\":{\"message\":\"quota exceeded\",\"type\":\"rate_limit_error\"}}"
                        .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(429, error.size.toLong())
                exchange.responseBody.use { it.write(error) }
            }
        )
        try {
            val callback = RecordingCallback()
            val base = "http://127.0.0.1:" + server.address.port + "/v1"
            LlmClient(
                base, listOf("key-1", "key-2"), "test-model", "openai", 256, 0.2f, "low", 1024
            ).streamChat(messages(), CancellationToken(), callback)
            truth(callback.error != null, "exhausted router did not surface an error")
            truth(
                auths.contains("Bearer key-1") && auths.contains("Bearer key-2"),
                "not every key was tried: $auths"
            )
            truth(callback.done == null, "error path must never report done")
        } finally {
            server.stop(0)
        }
    }

    private fun testNormalizeEscapesRepairsDoubleEscapedOutput() {
        eq("line1\nline2\nline3", MarkdownRenderer.normalizeEscapes("line1\\nline2\\nline3"))
        eq(
            "bash\nexport A=1\nexport B=2",
            MarkdownRenderer.normalizeEscapes("bash\\nexport A=1\\nexport B=2")
        )
        eq(
            "say \"hi\"\nnext\nend",
            MarkdownRenderer.normalizeEscapes("say \\\"hi\\\"\\nnext\\nend")
        )
        // genuine code: real newlines dominate, literal \n must stay untouched
        eq("echo \"a\\nb\"\nreal", MarkdownRenderer.normalizeEscapes("echo \"a\\nb\"\nreal"))
        eq("", MarkdownRenderer.normalizeEscapes(""))
        eq("", MarkdownRenderer.normalizeEscapes(null))
        eq("plain text", MarkdownRenderer.normalizeEscapes("plain text"))
    }

    private fun testJsonResponseWithoutDuplicateFallback() {
        val calls = AtomicInteger()
        val server = server(
            HttpHandler { exchange ->
                calls.incrementAndGet()
                val json = "{\"choices\":[{\"message\":{\"content\":\"json fallback\"}}]}"
                    .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, json.size.toLong())
                exchange.responseBody.use { it.write(json) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq("json fallback", callback.done)
            eq(1, calls.get())
            truth(callback.error == null, "valid JSON fallback reported an error")
        } finally {
            server.stop(0)
        }
    }

    private fun testJsonResponsePreservesReasoning() {
        val server = server(
            HttpHandler { exchange ->
                val json =
                    "{\"choices\":[{\"message\":{\"reasoning_content\":\"plan first\",\"content\":\"final answer\"}}]}"
                        .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, json.size.toLong())
                exchange.responseBody.use { it.write(json) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq("final answer", callback.done)
            eq("plan first", callback.thinking.toString())
        } finally {
            server.stop(0)
        }
    }

    private fun testClientErrorIsNeverReplayed() {
        val calls = AtomicInteger()
        val server = server(
            HttpHandler { exchange ->
                calls.incrementAndGet()
                val json =
                    "{\"error\":{\"message\":\"unsupported request field\",\"type\":\"invalid_request_error\"}}"
                        .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, json.size.toLong())
                exchange.responseBody.use { it.write(json) }
            }
        )
        try {
            val callback = RecordingCallback()
            client(server).streamChat(messages(), CancellationToken(), callback)
            eq(1, calls.get())
            val error = callback.error
            truth(
                error != null && error.contains("unsupported request field"),
                "client error detail was lost"
            )
            truth(
                error != null && !error.contains(Fa.ERR_RATE),
                "client error was mislabeled as a rate limit"
            )
        } finally {
            server.stop(0)
        }
    }

    private fun testWebFetch429IsAttributedToWebsite() {
        val server = server(
            HttpHandler { exchange ->
                val body = "throttled by target site".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Retry-After", "3")
                exchange.sendResponseHeaders(429, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        )
        try {
            val url = "http://127.0.0.1:" + server.address.port + "/v1/chat/completions"
            val result = Web.fetch(url, CancellationToken())
            truth(
                result.contains("target website") && result.contains("HTTP 429"),
                "website throttle was not attributed to the target site: $result"
            )
        } finally {
            server.stop(0)
        }
    }

    private fun testStopBeforeFirstByte() {
        val entered = CountDownLatch(1)
        val server = server(
            HttpHandler { exchange ->
                entered.countDown()
                try {
                    Thread.sleep(10000L)
                    exchange.sendResponseHeaders(200, 0)
                    exchange.close()
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (ignored: IOException) {
                }
            }
        )
        try {
            val token = CancellationToken()
            val callback = RecordingCallback()
            val worker = Thread(
                { client(server).streamChat(messages(), token, callback) },
                "cancel-before-byte"
            )
            worker.start()
            truth(entered.await(2, TimeUnit.SECONDS), "request never reached delayed server")
            val started = System.nanoTime()
            token.cancel()
            worker.join(2000L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            truth(!worker.isAlive, "Stop before first byte did not unblock the HTTP request")
            truth(elapsedMs < 1900L, "Stop before first byte was too slow: ${elapsedMs}ms")
            truth(callback.error == null, "user cancellation surfaced as an API error")
        } finally {
            server.stop(0)
        }
    }

    private fun testStopDuringStream() {
        val firstChunkSent = CountDownLatch(1)
        val server = server(
            HttpHandler { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.use { out ->
                        out.write(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                        out.flush()
                        firstChunkSent.countDown()
                        try {
                            Thread.sleep(10000L)
                        } catch (ignored: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                } catch (ignored: IOException) {
                }
            }
        )
        try {
            val token = CancellationToken()
            val callback = RecordingCallback()
            val worker = Thread(
                { client(server).streamChat(messages(), token, callback) },
                "cancel-mid-stream"
            )
            worker.start()
            truth(
                firstChunkSent.await(2, TimeUnit.SECONDS),
                "server did not emit its first chunk"
            )
            truth(
                callback.tokenReceived.await(2, TimeUnit.SECONDS),
                "client did not consume its first chunk"
            )
            val started = System.nanoTime()
            token.cancel()
            worker.join(2000L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            truth(!worker.isAlive, "Stop during stream did not unblock readLine")
            truth(elapsedMs < 1900L, "Stop during stream was too slow: ${elapsedMs}ms")
            truth(
                callback.tokens.toString().contains("partial"),
                "partial token was lost before cancellation"
            )
            truth(callback.error == null, "mid-stream cancellation surfaced as an API error")
        } finally {
            server.stop(0)
        }
    }

    private fun server(handler: HttpHandler): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions", handler)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        return server
    }

    private fun client(server: HttpServer): LlmClient {
        val base = "http://127.0.0.1:" + server.address.port + "/v1"
        return LlmClient(base, "test", "test-model", "openai", 256, 0.2f, "low", 1024)
    }

    private fun messages(): JSONArray =
        JSONArray().put(JSONObject().put("role", "user").put("content", "hello"))

    private class RecordingCallback : LlmClient.StreamCallback {
        val tokens = StringBuilder()
        val retries = AtomicInteger()
        val truncations = AtomicInteger()
        val tokenReceived = CountDownLatch(1)
        val thinking = StringBuilder()

        @Volatile
        var done: String? = null

        @Volatile
        var error: String? = null

        override fun onTruncated() {
            truncations.incrementAndGet()
        }

        override fun onDone(text: String?) {
            done = text
        }

        override fun onError(message: String) {
            error = message
        }

        override fun onThinking(text: String) {
            thinking.append(text)
        }

        override fun onToken(text: String) {
            tokens.append(text)
            tokenReceived.countDown()
        }

        override fun onRetry() {
            tokens.setLength(0)
            retries.incrementAndGet()
        }
    }

    // ---- v7 regression suites ---------------------------------------------

    /**
     * The user's configured endpoint and a URL the model picked are two
     * different trust levels and must not share one rule. Also pins the
     * behaviour that fixed "دسترسی به نشانی داخلی یا خصوصی مجاز نیست": a public
     * hostname is never resolved, so DNS that answers with a private sentinel
     * (ISP filtering, a VPN, private DNS) can no longer block the request.
     */
    private fun testNetworkPolicySplitsUserEndpointFromModelUrls() {
        val restore = NetworkPolicy.allowLocalNetwork
        try {
            // --- the user's own endpoint -----------------------------------
            truth(userEndpointOk("https://api.openai.com/v1"), "public https endpoint rejected")
            truth(userEndpointOk("https://api.anthropic.com"), "anthropic endpoint rejected")
            // A LAN model server over plain HTTP is the single most common
            // self-hosted setup and used to be refused outright.
            truth(userEndpointOk("http://192.168.1.40:11434/v1"), "LAN endpoint rejected")
            truth(userEndpointOk("http://127.0.0.1:1234/v1"), "loopback endpoint rejected")
            truth(userEndpointOk("http://localhost:8080/v1"), "localhost endpoint rejected")
            truth(userEndpointOk("http://10.0.0.5:8000/v1"), "private endpoint rejected")
            // ...but a key must never go to a public host in the clear.
            truth(!userEndpointOk("http://api.openai.com/v1"), "plaintext public endpoint allowed")
            truth(!userEndpointOk("ftp://api.openai.com"), "non-http scheme allowed")
            truth(!userEndpointOk("https://user:pass@api.openai.com"), "userinfo allowed")
            truth(!userEndpointOk(""), "empty endpoint allowed")
            truth(!userEndpointOk("https://169.254.169.254/"), "metadata endpoint allowed")

            // --- URLs the model picked --------------------------------------
            NetworkPolicy.allowLocalNetwork = false
            truth(modelUrlOk("https://example.com/page"), "public https blocked for tools")
            // The regression that made every provider unreachable behind a
            // filtering resolver: a public NAME must pass without a lookup.
            truth(modelUrlOk("https://api.openai.com/v1/models"), "public host blocked for tools")
            truth(!modelUrlOk("http://example.com/page"), "plaintext allowed for tools")
            truth(!modelUrlOk("https://127.0.0.1/admin"), "loopback allowed for tools")
            truth(!modelUrlOk("https://192.168.1.1/"), "LAN allowed for tools")
            truth(!modelUrlOk("https://10.1.2.3/"), "10/8 allowed for tools")
            truth(!modelUrlOk("https://172.16.5.4/"), "172.16/12 allowed for tools")
            truth(!modelUrlOk("https://169.254.169.254/latest/meta-data/"), "metadata allowed")
            truth(!modelUrlOk("https://metadata.google.internal/"), "gcp metadata allowed")
            truth(!modelUrlOk("https://localhost:9000/"), "localhost allowed for tools")
            truth(!modelUrlOk("https://[::1]/"), "ipv6 loopback allowed for tools")
            truth(!modelUrlOk("https://[fd00::1]/"), "ipv6 ULA allowed for tools")

            NetworkPolicy.allowLocalNetwork = true
            truth(modelUrlOk("https://192.168.1.1/"), "LAN blocked after opt-in")
            truth(modelUrlOk("http://192.168.1.1/"), "LAN http blocked after opt-in")
            truth(!modelUrlOk("https://169.254.169.254/"), "metadata reachable after opt-in")
        } finally {
            NetworkPolicy.allowLocalNetwork = restore
        }
    }

    private fun userEndpointOk(url: String): Boolean = try {
        NetworkPolicy.requireUserEndpoint(url)
        true
    } catch (blocked: Exception) {
        false
    }

    private fun modelUrlOk(url: String): Boolean = try {
        NetworkPolicy.requireSafeHttps(url)
        true
    } catch (blocked: Exception) {
        false
    }

    /**
     * A base URL carrying its own query must not produce two '?' — Google's
     * documented `?key=` form did exactly that against the Gemini streaming
     * suffix and every request came back HTTP 400.
     */
    private fun testEndpointQueryMergingAndCapabilityGates() {
        val gemini = LlmClient.endpointFor(
            "https://generativelanguage.googleapis.com/v1beta?key=SECRET",
            "gemini-2.5-pro", LlmClient.PROTOCOL_GEMINI, true
        )
        eq(1, gemini.count { it == '?' })
        truth(gemini.contains("alt=sse"), "gemini stream suffix lost: $gemini")
        truth(gemini.contains("key=SECRET"), "gemini key parameter lost: $gemini")
        truth(gemini.contains(":streamGenerateContent"), "gemini verb lost: $gemini")

        // Unchanged shapes must stay byte-identical.
        eq(
            "https://api.openai.com/v1/chat/completions",
            LlmClient.endpointFor(
                "https://api.openai.com/v1", "gpt-4o", LlmClient.PROTOCOL_OPENAI, true
            )
        )

        // Extended thinking is an Anthropic 400 on models that lack it.
        truth(
            LlmClient.supportsAnthropicThinking("claude-sonnet-4-5"),
            "thinking gate rejects claude 4"
        )
        truth(
            LlmClient.supportsAnthropicThinking("claude-3-7-sonnet-latest"),
            "thinking gate rejects claude 3.7"
        )
        truth(
            !LlmClient.supportsAnthropicThinking("claude-3-5-sonnet-20241022"),
            "thinking gate accepts claude 3.5"
        )
        truth(
            !LlmClient.supportsAnthropicThinking("claude-3-haiku-20240307"),
            "thinking gate accepts claude 3 haiku"
        )
        truth(!LlmClient.supportsAnthropicThinking("gpt-4o"), "thinking gate accepts a non-claude")
    }

    /**
     * cleanUrl repairs URLs the model pasted out of page text. It must not
     * "repair" correct ones: both of these used to come back 404, and the agent
     * then reported the link as dead and moved on.
     */
    private fun testCleanUrlKeepsLegitimateUrlsIntact() {
        eq(
            "https://en.wikipedia.org/wiki/Java_(programming_language)",
            Util.cleanUrl("https://en.wikipedia.org/wiki/Java_(programming_language)")
        )
        eq(
            "https://site.ir/music/Ali%20Reza%20-%20Bahar%20320.mp3",
            Util.cleanUrl("https://site.ir/music/Ali Reza - Bahar 320.mp3")
        )
        // Still strips the punctuation it exists to strip.
        eq("https://example.com/x", Util.cleanUrl("<https://example.com/x>"))
        eq("https://example.com/x", Util.cleanUrl("https://example.com/x."))
        eq("https://example.com/a/b", Util.cleanUrl("(https://example.com/a/b)"))
    }

    /**
     * The json card. `stripToolCalls` only ever hid a COMPLETE, VALID call, and
     * the fence regex needs a closing ``` — so for the entire time a call was
     * streaming there was nothing to strip and the renderer drew a live "json"
     * code block. This is the exact byte sequence the UI flusher sees, one chunk
     * at a time.
     */
    private fun testAStreamingToolCallIsNeverVisible() {
        val prose = "مرحله ۱ — جست‌وجوی قیمت لحظه‌ای"
        val whole = prose + "\n```json\n{\"tool\": \"web_search\", " +
            "\"args\": {\"query\": \"dollar price\"}}\n```"
        // Every prefix of the message — i.e. every frame of the stream.
        for (cut in prose.length..whole.length) {
            val visible = AgentEngine.stripToolCalls(whole.substring(0, cut))
            truth(
                !visible.contains("web_search") && !visible.contains("\"tool\""),
                "a partially-streamed tool call was visible at $cut chars: $visible"
            )
            truth(
                !visible.contains("```"),
                "a bare code fence was visible at $cut chars: $visible"
            )
        }
        // The prose that introduced it still survives — it becomes the phase line.
        truth(
            AgentEngine.stripToolCalls(whole).contains("مرحله"),
            "the step's own prose was stripped along with the call"
        )
        // Unfenced, half-typed, and mid-key: all three shapes.
        // From `{"` onwards. A LONE `{` is deliberately still shown: it is the one
        // character a tool call shares with every brace in every language, and
        // cutting on it would strip the opening brace off any streamed snippet.
        for (partial in arrayOf(
            "Working on it {\"", "Working on it {\"to",
            "Working on it {\"tool", "Working on it {\"tool\": \"web_sea"
        )) {
            val visible = AgentEngine.stripToolCalls(partial)
            truth(
                !visible.contains("tool") && !visible.contains("{"),
                "a half-typed unfenced call leaked: $visible"
            )
        }
        // Streamed CODE must survive untouched: an unbalanced brace inside a fence
        // is a language construct, not a tool call.
        val code = "Here:\n```kotlin\nfun main() {\n    val x = 1"
        truth(
            AgentEngine.stripToolCalls(code).contains("fun main() {"),
            "a streamed code block lost its opening brace"
        )
        val plainFence = "Config below.\n```\n{ \"a\": 1"
        truth(
            AgentEngine.stripToolCalls(plainFence).contains("```"),
            "a plain streamed fence was hidden"
        )
        // A complete but INVALID call (trailing comma) must not sit on screen
        // for ever either — the engine asks for a repair instead.
        val broken = "```json\n{\"tool\": \"web_search\", \"args\": {\"query\": \"x\",},}\n```"
        truth(AgentEngine.parseToolCall(broken) == null, "test premise: this should not parse")
        eq("", AgentEngine.stripToolCalls(broken))
    }

    /**
     * The run-ending bug: a malformed call left prose in the message, so the loop
     * declared the task FINISHED. A typo is not a decision to stop.
     */
    private fun testABrokenToolCallIsRecognisedAsAnAttempt() {
        // Malformed, unclosed, unfenced, wrong key — all attempts.
        for (attempt in arrayOf(
            "```json\n{\"tool\": \"web_search\", \"args\": {\"query\": \"x\",},}\n```",
            "```json\n{\"tool\": \"web_search\", \"args\": {\"query\": \"x\"",
            "Let me search. {\"tool\": \"web_search\" \"args\": {}}",
            "```json\n{\"function\": \"web_search\", \"arguments\": {}}\n```"
        )) {
            truth(
                AgentEngine.looksLikeAttemptedCall(attempt),
                "a fumbled tool call was not recognised: $attempt"
            )
        }
        // Genuine answers are NOT attempts, however much JSON they contain.
        for (answer in arrayOf(
            "قیمت دلار امروز ۹۵۰ هزار ریال است.",
            "Here is the config:\n```json\n{\"name\": \"app\", \"version\": 2}\n```",
            "The result is 42.",
            ""
        )) {
            truth(
                !AgentEngine.looksLikeAttemptedCall(answer),
                "a final answer was mistaken for a fumbled call: $answer"
            )
        }
    }

    /** The finish probe must never fire on a real answer. */
    private fun testOnlyAPreambleIsTreatedAsAPromise() {
        for (preamble in arrayOf(
            "Now I'll search for the current rate:",
            "حالا جست‌وجو می‌کنم…",
            "Let me read the file first.",
            "در ادامه، این مراحل را اجرا می‌کنم:"
        )) {
            truth(AgentEngine.promisesMore(preamble), "a preamble was accepted as an answer: $preamble")
        }
        for (answer in arrayOf(
            "قیمت دلار آزاد امروز ۹۵۰,۰۰۰ ریال است.",
            "Done — the file is saved to /sdcard/Download/notes.txt.",
            "",
            // Long enough to be a delivered answer, even though it says "next".
            "Here is the full comparison you asked for. ".repeat(12) + "Next I'll wait."
        )) {
            truth(!AgentEngine.promisesMore(answer), "a real answer was probed: $answer")
        }
    }

    /**
     * The plan sheet showed rows reading "--": a markdown rule matched the old
     * bullet test and stripping one `-` off `---` left the literal text `--`.
     */
    private fun testPlanStepsRejectRulesAndBareNumbers() {
        for (junk in arrayOf("---", "***", "___", "- - -", "|---|---|", "1.", "2)", "-", "* ")) {
            truth(!AgentEngine.isPlanStep(junk), "junk accepted as a plan step: '$junk'")
        }
        for (step in arrayOf(
            "1. جست‌وجوی قیمت لحظه‌ای دلار",
            "2) Check the official rate",
            "- Compare with last week",
            "3. **Summarise**"
        )) {
            truth(AgentEngine.isPlanStep(step), "a real step was rejected: '$step'")
            truth(
                AgentEngine.stripPlanBullet(step).isNotEmpty(),
                "a real step stripped to nothing: '$step'"
            )
        }
        eq("Summarise", AgentEngine.stripPlanBullet("3. **Summarise**"))
        // The whole plan, rules and all.
        val plan = "Here is the plan:\n\n1. Search the live price\n---\n2. Check the official rate\n" +
            "|---|---|\n3.\n- Compare with last week\n"
        val lines = AgentEngine.planLines(plan)
        eq(3, lines.size)
        eq("Search the live price", lines[0])
        eq("Compare with last week", lines[2])
        for (line in lines) {
            truth(line.isNotBlankJava(), "planLines produced a blank step")
        }
    }

    /** The strip's "5 results" chip and source circles come from the real output. */
    private fun testSearchResultsYieldHostsAndCounts() {
        val result = "1. Dollar price today\n   snippet\n   https://www.tgju.org/dollar\n" +
            "2. USD to IRR\n   snippet\n   https://bonbast.com/\n" +
            "3. Rate history\n   snippet\n   https://tgju.org/profile/price_dollar_rl\n"
        eq(3, AgentEngine.countedResults(result))
        val hosts = AgentEngine.hostsIn(result)
        eq(2, hosts.size)
        eq("tgju.org", hosts[0])
        eq("bonbast.com", hosts[1])
        eq("tgju.org", AgentEngine.hostOf("https://www.tgju.org/dollar"))
        eq("", AgentEngine.hostOf("not a url"))
        eq("", AgentEngine.hostOf(""))
        eq(0, AgentEngine.countedResults("No web results for: dollar"))
    }

    /** Reopening a chat must show the same run history, not an empty strip. */
    private fun testATrailSurvivesBeingSavedAndReloaded() {
        val trail = Trail()
        trail.startedAt = 1000L
        trail.endedAt = 15000L
        trail.phase = "Checking the rate"
        trail.collapsed = true
        val step = TrailStep(TrailStep.SEARCH, "Searching", "dollar price")
        step.resultCount = 5
        step.addDomains(listOf("tgju.org"))
        step.status = TrailStep.RUNNING
        trail.addStep(step, 60)
        trail.addPages(listOf("tgju.org", "bonbast.com"))

        val message = Message("assistant", "the answer")
        message.trail = trail
        message.isStep = true
        val reloaded = Message.fromJson(message.toJson())
        val back = reloaded.trail
        truth(back != null, "the trail did not survive the round trip")
        eq("Checking the rate", back?.phase)
        eq(14000L, back?.elapsedMs(99999L))
        eq(1, back?.steps()?.size)
        eq(5, back?.steps()?.get(0)?.resultCount)
        eq("tgju.org", back?.steps()?.get(0)?.domains()?.get(0))
        eq(2, back?.pages()?.size)
        truth(reloaded.isStep, "the folded-step flag did not survive")
        // A run that died mid-flight must not reload as a spinner that never
        // stops — and must not reload claiming to have SUCCEEDED either. STOPPED is
        // the honest answer: the process went away while this step was open, so how
        // far it got is unknown, and for an edit the file on disk may be half written.
        eq(TrailStep.STOPPED, back?.steps()?.get(0)?.status)
        truth(back?.running == false, "a reloaded trail claims to still be running")
        // An ordinary message stays untouched — and cheap.
        val plain = Message("assistant", "hi")
        truth(Message.fromJson(plain.toJson()).trail == null, "a plain message grew a trail")
        truth(!plain.toJson().has("isStep"), "a plain message writes a redundant flag")
    }


    /**
     * A trail is persisted with the conversation, so an edit cannot store two whole
     * copies of the file — a 400 KB source file would be 800 KB of JSON per edit.
     * [Diff.hunk] narrows both sides to the region that actually changed, and the
     * counts stay exact because they are measured by a real LCS over that region.
     */
    private fun testADiffNarrowsToWhatChanged() {
        val before = StringBuilder()
        val after = StringBuilder()
        for (i in 1..400) {
            before.append("line ").append(i).append("\n")
            after.append("line ").append(i).append("\n")
        }
        before.append("val x = 1\n")
        after.append("val x = 2\n")
        for (i in 1..400) {
            before.append("tail ").append(i).append("\n")
            after.append("tail ").append(i).append("\n")
        }
        val hunk = Diff.hunk(before.toString(), after.toString())
        eq(1, hunk.added)
        eq(1, hunk.removed)
        truth(hunk.before.contains("val x = 1"), "the removed line is missing")
        truth(hunk.after.contains("val x = 2"), "the added line is missing")
        // Narrowed: three lines of context either side, not 400.
        truth(
            hunk.before.length < 200,
            "the stored hunk is the whole file (" + hunk.before.length + " chars)"
        )
        truth(!hunk.before.contains("line 1\n"), "the hunk reaches back to the file start")
        truth(hunk.clipped, "a narrowed hunk does not report that it was narrowed")

        // An identical pair is not a change at all.
        val same = Diff.hunk("a\nb", "a\nb")
        truth(same.isEmpty(), "an unchanged file reports a change")

        // A created file is all additions, and has no left-hand side.
        val made = Diff.created("one\ntwo\nthree")
        eq(3, made.added)
        eq(0, made.removed)
        eq("", made.before)

        // The cap holds on a pathological input: one enormous line each side.
        val huge = Diff.hunk("x".repeat(50000), "y".repeat(50000))
        truth(
            huge.before.length <= Diff.MAX_SIDE_CHARS,
            "the stored hunk blew past its cap: " + huge.before.length
        )
    }

    /** The change has to reach the row that made it, and survive a reload. */
    private fun testAFileChangeRidesOnItsTrailStep() {
        val step = TrailStep(TrailStep.TOOL, "Edited file", "src/Foo.kt")
        truth(!step.hasDiff(), "a fresh step already claims to hold a diff")
        step.noteChange("src/Foo.kt", Diff.hunk("a\nb\nc", "a\nB\nc"))
        truth(step.hasDiff(), "the change did not land on the step")
        truth(step.hasChangeCounts(), "the change counts did not land on the step")
        eq(1, step.added)
        eq(1, step.removed)
        eq("src/Foo.kt", step.filePath)

        val trail = Trail()
        trail.startedAt = 1000L
        trail.endedAt = 4000L
        trail.addStep(step, 60)
        val back = Trail.fromJson(trail.toJson())
        val restored = back?.steps()?.get(0)
        truth(restored?.hasDiff() == true, "the diff did not survive the round trip")
        eq(1, restored?.added)
        eq("src/Foo.kt", restored?.filePath)
        // The run-level totals are what the collapsed strip shows.
        eq(1, back?.changeTotals()?.get(0))
        eq(1, back?.changeTotals()?.get(1))
        eq(1, back?.editedFiles()?.size)
        // Timing was recorded from the first version and never rendered; it has to
        // be readable to be rendered.
        step.endedAt = step.startedAt + 1500L
        eq(1500L, step.durationMs(0L))
    }

    /**
     * An unnamed `task` used to add a phase with an EMPTY title: a blank row live,
     * and then no row at all after a reload, because a titleless phase cannot be
     * deserialized — which took the whole board with it when it was the only one.
     */
    private fun testAnUnnamedPhaseKeepsATitle() {
        val board = Workflow()
        val phase = board.claim("", "Audit the parser")
        eq("Audit the parser", phase.title)
        phase.status = WorkPhase.DONE
        val back = Workflow.fromJson(board.toJson())
        truth(back != null, "a board with one unnamed phase vanished on reload")
        eq(1, back?.size())
        eq("Audit the parser", back?.phases()?.get(0)?.title)
    }

    /** Stopping a run must be recorded as stopping it, not as finishing it. */
    private fun testAStoppedRunSaysSo() {
        val trail = Trail()
        trail.startedAt = 1000L
        trail.running = true
        val step = TrailStep(TrailStep.TOOL, "Editing", "src/Foo.kt")
        trail.addStep(step, 60)
        trail.settle(5000L, interrupted = true)
        eq(TrailStep.STOPPED, trail.steps()[0].status)
        truth(!trail.running, "a settled trail still claims to be running")
        eq(1, trail.failedCount())

        // The ordinary finish is unchanged.
        val clean = Trail()
        clean.startedAt = 1000L
        clean.running = true
        clean.addStep(TrailStep(TrailStep.TOOL, "Reading", "src/Bar.kt"), 60)
        clean.settle(5000L)
        eq(TrailStep.DONE, clean.steps()[0].status)
        eq(0, clean.failedCount())

        // Same rule on the board.
        val board = Workflow()
        board.add(WorkPhase(1, "first phase"))
        board.phases()[0].status = WorkPhase.RUNNING
        board.settle(interrupted = true)
        eq(WorkPhase.STOPPED, board.phases()[0].status)
        eq(1, board.failedCount())
    }

    /**
     * The lead agent writes its plan in the user's language and names its `task`
     * calls in whatever language it likes. Matching only lowercased, so Persian plan
     * lines scored zero against every task name — numbering, colons and the
     * zero-width non-joiner all counted as part of the words — and the board
     * silently filled its rows in call order instead.
     */
    private fun testAPersianPlanMatchesAnEnglishTaskName() {
        val board = Workflow()
        board.add(WorkPhase(1, "۱. بررسی فایل‌های پروژه:"))
        board.add(WorkPhase(2, "۲. نوشتن تست برای پارسر:"))
        // Same words, different punctuation and numbering.
        val second = board.claim("نوشتن تست پارسر", "fallback")
        eq(2, second.index)
        second.status = WorkPhase.DONE
        val first = board.claim("بررسی فایل‌های پروژه", "fallback")
        eq(1, first.index)
        // Latin still works, and still needs two shared words rather than one.
        val other = Workflow()
        other.add(WorkPhase(1, "Audit the streaming parser"))
        other.add(WorkPhase(2, "Rewrite the settings screen"))
        eq(2, other.claim("rewrite settings", "fallback").index)
    }

    /** The collapsed strip's one-liner, and the panel's stats, come from these. */
    private fun testTheStripSummarisesWhatItCost() {
        val trail = Trail()
        trail.startedAt = 1000L
        trail.endedAt = 9000L
        eq(8000L, trail.elapsedMs(99999L))
        eq(0, trail.workCount())
        eq(0, trail.thoughtCount())

        val think = TrailStep(TrailStep.THINK, "", "weighing the options")
        think.status = TrailStep.RUNNING
        trail.addStep(think, 60)
        // Thinking alone is not work — a one-line reply must not carry a strip.
        truth(!trail.didWork(), "reasoning alone counts as work")
        truth(trail.hasThoughts(), "a reasoning row is not reported as one")
        eq(1, trail.thoughtCount())
        // The open reasoning row is the one the engine rewrites as tokens arrive.
        truth(trail.openThought() === think, "the live reasoning row is not reachable")
        think.status = TrailStep.DONE
        truth(trail.openThought() == null, "a closed reasoning row is still open")

        val search = TrailStep(TrailStep.SEARCH, "Searching", "dollar rate")
        search.status = TrailStep.DONE
        trail.addStep(search, 60)
        truth(trail.didWork(), "a search does not count as work")
        eq(1, trail.workCount())

        val edit = TrailStep(TrailStep.TOOL, "Edited file", "src/A.kt")
        edit.noteChange("src/A.kt", Diff.hunk("a\nb", "a\nc"))
        edit.status = TrailStep.DONE
        trail.addStep(edit, 60)
        eq(2, trail.workCount())
        eq(1, trail.editedFiles().size)
        eq(1, trail.changeTotals()[0])
        eq(1, trail.changeTotals()[1])
    }

    /** A delegated task must land on the plan phase it belongs to. */
    /**
     * A real delegation binds to the plan row the LEAD AGENT NAMED, and the board
     * counts agents rather than plan lines.
     *
     * This is what replaced the word-overlap guessing. The `task` tool now carries
     * an explicit `phase` number — which the model can always supply, because it
     * wrote the numbered plan itself — so the binding is a recorded fact instead of
     * a similarity score. And the card's numbers come from `launched` / `liveCount`
     * / `doneCount`, never from `size()`: the old subheading said "Split across N
     * sub-agents" where N was the count of bulleted lines the model happened to
     * type, so it read "Split across 7 sub-agents" after one delegation, before any
     * agent had finished anything.
     */
    /**
     * Every queued approval is released when the run is cancelled.
     *
     * This is the deadlock the approval queue was built to remove, asserted
     * directly. `pendingApproval` used to be a SINGLE volatile field: two agent
     * threads asking at once meant the second assignment clobbered the first, and
     * the clobbered approval became unreachable from the bus. `requestCancel()`
     * and `finish()` both read that one field, so they rejected the survivor and
     * only the survivor — leaving the orphan on an untimed `latch.await()` that
     * nothing in the process could ever count down. The run never finished, so the
     * global run slot was never released, so the app was permanently "busy" until
     * the process died.
     *
     * Three concurrent agents, one cancel, all three must return.
     */
    private fun testEveryQueuedApprovalIsReleasedByCancel() {
        val runId = AgentBus.beginStarting("approval-queue-test", null)
        truth(runId != 0L, "the bus refused to start a run for the test")
        // DISTINCT approvals, not callback count. The bus republishes its head on
        // every enqueue — which is correct, because a listener that attached late
        // has to learn what is already waiting — and the UI dedupes by identity.
        // What must hold is that only ONE approval is ever offered to the user at a
        // time, not that the callback fires once.
        val shown = java.util.Collections.synchronizedSet(java.util.HashSet<Long>())
        val previous = AgentBus.listener
        AgentBus.listener = object : AgentBus.UiListener {
            override fun onApprovalRequested(approval: AgentBus.PendingApproval) {
                shown.add(approval.id)
            }

            override fun onComplete() {}
            override fun onDelta(message: Message) {}
            override fun onError(error: String) {}
            override fun onNewAssistantMessage(message: Message) {}
            override fun onStepFinalized(message: Message) {}
            override fun onThinking(message: Message) {}
            override fun onToolMessage(message: Message) {}
            override fun onToolRunning(tool: String, detail: String) {}
            override fun onTrailChanged(owner: Message) {}
        }
        try {
            val decisions = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
            val ready = java.util.concurrent.CountDownLatch(3)
            val done = java.util.concurrent.CountDownLatch(3)
            for (i in 0 until 3) {
                val index = i
                val worker = Thread({
                    ready.countDown()
                    val approved = AgentBus.awaitApproval(
                        "edit_file", null, CancellationToken(), "Agent " + (index + 1)
                    )
                    decisions[index] = approved
                    done.countDown()
                }, "approval-test-$index")
                worker.isDaemon = true
                worker.start()
            }
            truth(ready.await(3L, java.util.concurrent.TimeUnit.SECONDS), "workers never started")
            // Give all three time to enqueue before cancelling.
            var spins = 0
            while (AgentBus.outstandingApprovals() < 3 && spins < 200) {
                Thread.sleep(10L)
                spins++
            }
            eq(3, AgentBus.outstandingApprovals())
            // Only ONE reaches the screen: a person answers one question at a time.
            eq(1, shown.size)

            AgentBus.requestCancel(true)
            truth(
                done.await(5L, java.util.concurrent.TimeUnit.SECONDS),
                "a queued approval was never released — this is the original deadlock"
            )
            eq(3, decisions.size)
            for (i in 0 until 3) {
                truth(decisions[i] == false, "a cancelled approval did not report a rejection")
            }
            eq(0, AgentBus.outstandingApprovals())
        } finally {
            AgentBus.finish(runId)
            AgentBus.listener = previous
        }
    }

    /**
     * Answering the head of the queue surfaces the next one.
     *
     * The queue is invisible to the user by design — they see one sheet, answer
     * it, and the next appears. What must NOT happen is the second ask being
     * silently dropped because the first overwrote it, which is what the single
     * field did.
     */
    private fun testTheApprovalQueueSurfacesOneAtATime() {
        val runId = AgentBus.beginStarting("approval-order-test", null)
        truth(runId != 0L, "the bus refused to start a run for the test")
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val previous = AgentBus.listener
        AgentBus.listener = object : AgentBus.UiListener {
            override fun onApprovalRequested(approval: AgentBus.PendingApproval) {
                // Distinct heads in the order they were offered. The bus
                // republishes its current head on every enqueue, so a plain
                // append would count the same approval twice.
                if (seen.isEmpty() || seen[seen.size - 1] != approval.tool) {
                    seen.add(approval.tool)
                }
            }

            override fun onComplete() {}
            override fun onDelta(message: Message) {}
            override fun onError(error: String) {}
            override fun onNewAssistantMessage(message: Message) {}
            override fun onStepFinalized(message: Message) {}
            override fun onThinking(message: Message) {}
            override fun onToolMessage(message: Message) {}
            override fun onToolRunning(tool: String, detail: String) {}
            override fun onTrailChanged(owner: Message) {}
        }
        try {
            val first = java.util.concurrent.atomic.AtomicReference<Boolean>(null)
            val second = java.util.concurrent.atomic.AtomicReference<Boolean>(null)
            val done = java.util.concurrent.CountDownLatch(2)
            Thread({
                first.set(AgentBus.awaitApproval("write_file", null, CancellationToken(), "Agent 1"))
                done.countDown()
            }, "approval-order-1").also { it.isDaemon = true }.start()
            var spins = 0
            while (AgentBus.outstandingApprovals() < 1 && spins < 200) {
                Thread.sleep(10L)
                spins++
            }
            Thread({
                second.set(AgentBus.awaitApproval("delete_path", null, CancellationToken(), "Agent 2"))
                done.countDown()
            }, "approval-order-2").also { it.isDaemon = true }.start()
            spins = 0
            while (AgentBus.outstandingApprovals() < 2 && spins < 200) {
                Thread.sleep(10L)
                spins++
            }
            eq(2, AgentBus.outstandingApprovals())
            // The head is the first asked, and it is the only one shown so far.
            eq(1, seen.size)
            truth(seen[0] == "write_file", "the queue surfaced the wrong approval first")

            AgentBus.currentApproval()?.decide(true)
            spins = 0
            while (seen.size < 2 && spins < 200) {
                Thread.sleep(10L)
                spins++
            }
            truth(seen.size >= 2, "answering the head never surfaced the next approval")
            truth(seen[1] == "delete_path", "the second approval was dropped")

            // Ending the run rejects whatever is still waiting.
            AgentBus.finish(runId)
            truth(done.await(5L, java.util.concurrent.TimeUnit.SECONDS), "a waiter never returned")
            truth(first.get() == true, "an approved action was not reported as approved")
            truth(second.get() == false, "a run-end did not reject the outstanding approval")
        } finally {
            AgentBus.listener = previous
        }
    }

    private fun testAWorkflowRecordsRealAgents() {
        val board = Workflow()
        board.seed(listOf("Investigate the parser", "Implement the strip", "Verify the build"))
        eq(3, board.size())
        // A board with a plan but no delegations has NO agents. This is the number
        // the old card got wrong.
        eq(0, board.launched)
        eq(0, board.liveCount())

        // Three agents, launched onto the rows the lead named.
        val a = board.launch(1, "parser internals", "fallback")
        val b = board.launch(3, "full build check", "fallback")
        val c = board.launch(2, "the activity strip", "fallback")
        eq(3, board.launched)
        eq(3, board.liveCount())
        eq(1, a.index)
        eq(3, b.index)
        eq(2, c.index)
        // Each row knows which agent is on it and what it is about.
        eq(1, a.agentId)
        eq(2, b.agentId)
        eq(3, c.agentId)
        truth(a.topic == "parser internals", "the phase did not record its agent's topic")
        truth(board.liveTopics().size == 3, "liveTopics did not report every working agent")
        truth(
            board.liveTopics().contains("full build check"),
            "liveTopics omitted an agent that is working"
        )
        // The board did not grow: three launches onto three named rows.
        eq(3, board.size())

        // A phase number nobody planned gets its own row rather than stealing one.
        val extra = board.launch(9, "something unplanned", "fallback")
        eq(4, board.size())
        eq(4, extra.agentId)
        eq(4, board.launched)

        a.status = WorkPhase.DONE
        eq(1, board.doneCount())
        eq(3, board.liveCount())

        // Nothing may be left spinning, and every closed row must have an end time.
        board.settle()
        truth(!board.running, "a settled board still claims to be running")
        eq(0, board.liveCount())
        for (phase in board.phases()) {
            truth(phase.status != WorkPhase.RUNNING, "a phase was left running after settle()")
            if (phase.agentId != 0) {
                truth(phase.endedAt > 0L, "a launched phase closed with no end time")
            }
        }
    }

    /**
     * The abnormal exits close their rows too.
     *
     * A row left RUNNING is worse than one marked failed: the next tool to finish
     * used to resolve it, so an unrelated `read_file` would mark a delegated phase
     * DONE and write its own first line into that phase's note.
     */
    private fun testAThrownStepFailsTheOpenPhases() {
        val board = Workflow()
        board.seed(listOf("one", "two"))
        board.launch(1, "first", "fallback")
        board.launch(2, "second", "fallback")
        eq(2, board.liveCount())
        board.failOpen("IllegalStateException: boom")
        eq(0, board.liveCount())
        eq(2, board.failedCount())
        for (phase in board.phases()) {
            truth(phase.note.contains("boom"), "a failed phase did not record why")
            truth(phase.endedAt > 0L, "a failed phase closed with no end time")
        }
    }

    private fun testAWorkflowClaimsThePhaseItBelongsTo() {
        val board = Workflow()
        board.add(WorkPhase(1, "Investigate the parser in AgentEngine"))
        board.add(WorkPhase(2, "Implement the activity strip"))
        board.add(WorkPhase(3, "Verify the whole build"))
        // Matched on shared words, not on exact text.
        val second = board.claim("implement activity strip", "fallback")
        eq(2, second.index)
        second.status = WorkPhase.DONE
        // A name with nothing in common gets its OWN row — it does NOT fall through
        // to the first unstarted one.
        //
        // This assertion is inverted from what it used to say, and the old
        // behaviour it enshrined is the bug. Below the two-shared-word confidence
        // bar, `claim` used to hand the work to whichever row happened to be first
        // pending, so the board would confidently attribute a real sub-agent's work
        // to a plan line that had nothing to do with it — and nothing anywhere
        // recorded the true mapping. An extra row is honest; a mis-attributed row
        // is not.
        val unrelated = board.claim("zzz nothing alike", "fallback")
        eq(4, unrelated.index)
        truth(board.size() == 4, "an unmatched claim did not grow the board")
        unrelated.status = WorkPhase.DONE
        eq(2, board.doneCount())
        // Delegating more than was planned GROWS the board rather than hiding it.
        val third = board.claim("Verify the whole build", "fallback")
        eq(3, third.index)
        third.status = WorkPhase.RUNNING
        eq(3, board.activeIndex() + 1)
        val extra = board.claim("an unplanned extra phase", "fallback")
        eq(5, extra.index)
        eq(5, board.size())
        // Nothing may be left spinning when the run ends.
        board.settle()
        truth(!board.running, "a settled board still claims to be running")
        for (phase in board.phases()) {
            truth(phase.status != WorkPhase.RUNNING, "a phase was left running after settle()")
        }
    }

    /** Retries must back off, and the backoff must stay bounded. */
    private fun testFaultBackoffWidensAndIsBounded() {
        var previous = 0L
        for (attempt in 1..6) {
            val wait = AgentEngine.faultBackoffMs(attempt)
            truth(wait >= previous, "the backoff narrowed at attempt $attempt")
            truth(wait in 1L..10000L, "the backoff left its bounds at attempt $attempt: $wait")
            previous = wait
        }
        truth(
            AgentEngine.faultBackoffMs(2) > AgentEngine.faultBackoffMs(1),
            "the backoff does not widen at all"
        )
    }

    /**
     * The engine mutates a trail on its worker thread while the UI lays it out on
     * the main one, microseconds apart. Unguarded, that is not a theoretical race:
     * the 60-row cap's `removeAt(0)` makes a reader index past the end, and an
     * `add` that grows the backing array can hand a reader a null element.
     *
     * This hammers both sides at once. Any escape of a raw collection reference
     * shows up here as a ConcurrentModificationException or an
     * IndexOutOfBoundsException, deterministically enough to matter.
     */
    private fun testATrailSurvivesConcurrentReadsAndWrites() {
        val trail = Trail()
        trail.startedAt = System.currentTimeMillis()
        trail.running = true
        val board = Workflow()
        board.add(WorkPhase(1, "first phase"))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)

        // The reader does exactly what TrailView.bind and WorkflowView.bind do.
        val reader = Thread {
            try {
                while (!stop.get()) {
                    val steps = trail.steps()
                    for (i in steps.indices) {
                        val step = steps[i]
                        step.domains().size
                        step.detail.length
                        step.status
                    }
                    trail.pages().size
                    trail.elapsedMs(System.currentTimeMillis())
                    trail.isEmpty()
                    trail.active()
                    for (phase in board.phases()) {
                        phase.title.length
                        phase.status
                        phase.steps
                    }
                    board.doneCount()
                    board.activeIndex()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        reader.start()

        try {
            for (i in 0 until 4000) {
                val step = TrailStep(TrailStep.SEARCH, "Searching", "query $i")
                // The cap is small on purpose: it forces removeAt(0) constantly,
                // which is the exact mutation that broke readers.
                trail.addStep(step, 12)
                step.addDomains(listOf("host$i.example", "shared.example"))
                trail.addPages(listOf("host$i.example"))
                step.resultCount = i % 9
                step.status = TrailStep.DONE
                if (i % 40 == 0) {
                    board.claim("phase $i", "fallback").status = WorkPhase.RUNNING
                }
                if (i % 500 == 0) {
                    trail.settle(System.currentTimeMillis())
                    trail.running = true
                }
            }
        } finally {
            stop.set(true)
            reader.join(5000)
        }
        failure.get()?.let {
            throw AssertionError("concurrent trail access threw ${it.javaClass.simpleName}: ${it.message}")
        }
        // The cap really did bite, and the state is still coherent afterwards.
        eq(12, trail.steps().size)
        truth(trail.pages().size > 100, "pages were not accumulated")
        trail.settle(System.currentTimeMillis())
        board.settle()
        for (step in trail.steps()) {
            truth(step.status != TrailStep.RUNNING, "settle() left a step running")
        }
        for (phase in board.phases()) {
            truth(phase.status != WorkPhase.RUNNING, "settle() left a phase running")
        }
    }

    /**
     * The strip is a record of WORK. A turn that only thought — a greeting, a
     * question about something already on screen — must not carry one, or every
     * reply gets a "reviewed for 2 seconds" line over it: noise dressed as
     * progress. Every turn thinks, so thinking cannot be the test.
     */
    private fun testAStripIsOnlyShownForWorkThatHappened() {
        val quiet = Trail()
        quiet.startedAt = System.currentTimeMillis()
        truth(!quiet.didWork(), "an empty trail claims to have done work")
        eq(0, quiet.workCount())

        // Reasoning alone is still not work.
        val thought = TrailStep(TrailStep.THINK, "", "Let me think about that.")
        thought.status = TrailStep.DONE
        quiet.addStep(thought, 60)
        truth(!quiet.didWork(), "thinking alone counted as work")
        eq(0, quiet.workCount())
        truth(!quiet.isEmpty(), "a trail with a row still reports itself empty")

        // One real action flips it.
        quiet.addStep(TrailStep(TrailStep.SEARCH, "Searching", "dollar price"), 60)
        truth(quiet.didWork(), "a search did not count as work")
        eq(1, quiet.workCount())

        // Every non-thinking kind counts.
        for (kind in arrayOf(TrailStep.FETCH, TrailStep.TOOL, TrailStep.TASK)) {
            val trail = Trail()
            trail.addStep(TrailStep(kind, "label", "detail"), 60)
            truth(trail.didWork(), "kind $kind did not count as work")
        }
    }

    /**
     * The results behind a row. They were always built and always discarded, so a
     * row could say "10 results" and offer no way to see one.
     */
    private fun testSearchResultsSurviveAndDeduplicate() {
        val step = TrailStep(TrailStep.SEARCH, "Searching", "dollar price")
        truth(!step.hasResults(), "a fresh step claims to have results")
        step.addResults(
            listOf(
                Web.SearchResult("Dollar price today", "https://www.tgju.org/dollar"),
                Web.SearchResult("USD to IRR", "https://bonbast.com/")
            )
        )
        truth(step.hasResults(), "results did not stick")
        eq(2, step.results().size)
        // The same url twice is one result, however it is titled — search engines
        // repeat themselves across pages and the panel must not.
        step.addResults(
            listOf(Web.SearchResult("A different title", "https://www.tgju.org/dollar"))
        )
        eq(2, step.results().size)
        // The host is derived, and drops the www so the cluster and the card agree.
        eq("tgju.org", step.results()[0].host())
        eq("bonbast.com", step.results()[1].host())

        // Round trip: reopening a chat must still be able to open a result.
        val trail = Trail()
        trail.addStep(step, 60)
        val message = Message("assistant", "the answer")
        message.trail = trail
        val back = Message.fromJson(message.toJson()).trail
        val restored = back?.steps()?.get(0)
        eq(2, restored?.results()?.size)
        eq("https://www.tgju.org/dollar", restored?.results()?.get(0)?.url)
        eq("Dollar price today", restored?.results()?.get(0)?.title)

        // A result with no url is not a result; a missing title falls back to it.
        truth(
            Web.SearchResult.fromJson(org.json.JSONObject().put("title", "x")) == null,
            "a urlless result was accepted"
        )
        val bare = Web.SearchResult.fromJson(
            org.json.JSONObject().put("url", "https://example.com/a")
        )
        eq("https://example.com/a", bare?.title)
    }

    /** Pinning has to outlive the process, and it has to change the order. */
    private fun testAPinnedChatSurvivesAndSortsFirst() {
        val chat = Chat("id-1", "A conversation", 1000L)
        truth(!chat.pinned, "a new chat is pinned")
        // An unpinned chat writes no key at all, so the common case costs nothing.
        truth(!chat.toJson().has("pinned"), "an unpinned chat writes a redundant key")
        chat.pinned = true
        val back = Chat.fromJson(chat.toJson())
        truth(back.pinned, "the pin did not survive the round trip")
        eq("A conversation", back.title)

        // Pinned first, then most recent — the drawer's whole ordering rule.
        val rows = mutableListOf(
            ChatStore.Summary("old-unpinned", "old", 100L, false),
            ChatStore.Summary("new-unpinned", "new", 900L, false),
            ChatStore.Summary("old-pinned", "kept", 200L, true)
        )
        rows.sortWith(
            compareByDescending<ChatStore.Summary> { it.pinned }.thenByDescending { it.updatedAt }
        )
        eq("old-pinned", rows[0].id)
        eq("new-unpinned", rows[1].id)
        eq("old-unpinned", rows[2].id)
    }

    private fun eq(expected: Any?, actual: Any?) {
        assertions++
        if (expected != actual) {
            throw AssertionError("expected=$expected actual=$actual")
        }
    }

    private fun truth(value: Boolean, message: String) {
        assertions++
        if (!value) {
            throw AssertionError(message)
        }
    }
}
