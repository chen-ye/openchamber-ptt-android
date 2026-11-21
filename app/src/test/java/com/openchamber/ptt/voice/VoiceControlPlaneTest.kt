package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.TtsInputMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceControlPlaneTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `evaluateTurn routes answer_directly to small-model using focusedReply`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"}}}"""
        val escapedInner = decisionInner.replace("\"", "\\\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"body":"$escapedInner"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Option two uses **Kokoro** locally."}"""))

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val decision = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "What was option 2?",
            replyContext = VoiceReplyContext(
                focusedReply = "Option 1: Gemini. Option 2: Local Kokoro.",
            ),
            currentDetailLevel = SpokenDetailLevel.ESSENTIALS,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertTrue(decision is VoiceControlDecision.AnswerDirectly)
        assertEquals(
            "Option two uses Kokoro locally.",
            (decision as VoiceControlDecision.AnswerDirectly).spokenAnswer,
        )
        assertEquals(2, server.requestCount)
        val decisionReq = server.takeRequest()
        assertEquals("/api/guests/gemini-decisions-bridge/service/request", decisionReq.path)
        val decisionState = Json.parseToJsonElement(decisionReq.body.readUtf8())
            .jsonObject["body"]!!.jsonObject["state"]!!.jsonObject
        assertEquals(false, decisionState["has_pending_unread"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            "Option 1: Gemini. Option 2: Local Kokoro.",
            decisionState["last_assistant_reply"]?.jsonPrimitive?.content,
        )
        assertFalse(decisionState.containsKey("pending_unread_replies"))

        val answerReq = server.takeRequest()
        assertEquals("/api/small-model/generate", answerReq.path)
        val answerPrompt = Json.parseToJsonElement(answerReq.body.readUtf8())
            .jsonObject["prompt"]!!.jsonPrimitive.content
        assertTrue(answerPrompt.contains("Focused assistant reply:\nOption 1: Gemini. Option 2: Local Kokoro."))
    }

    @Test
    fun `evaluateTurn routes live_tuning to SpokenDetailLevel without calling small-model`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"detailed"}}}"""
        val escapedInner = decisionInner.replace("\"", "\\\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"body":"$escapedInner"}"""))

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val decision = vcp.evaluateTurn(
            serverUrl = server.url("/").toString().trimEnd('/'),
            utterance = "Switch to detailed mode",
            replyContext = VoiceReplyContext(focusedReply = "Some prior reply."),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertTrue(decision is VoiceControlDecision.LiveTuning)
        val tuning = decision as VoiceControlDecision.LiveTuning
        assertEquals(SpokenDetailLevel.DETAILED, tuning.detailLevel)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `evaluateTurn fails open to PassThrough when decisions bridge is unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Bridge offline"))

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val decision = vcp.evaluateTurn(
            serverUrl = server.url("/").toString().trimEnd('/'),
            utterance = "Run the unit tests",
            replyContext = VoiceReplyContext(focusedReply = "Previous reply."),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertEquals(VoiceControlDecision.PassThrough, decision)
    }

    @Test
    fun `evaluateTurn parses tuning_mode and combines confirmation speech cleanly`() = runTest {
        val rawInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"raw"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${rawInner.replace("\"", "\\\"")}"}"""),
        )
        val bothInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"essentials"},"tuning_mode":{"choice":"sanitized"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${bothInner.replace("\"", "\\\"")}"}"""),
        )
        val noneInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${noneInner.replace("\"", "\\\"")}"}"""),
        )

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val rawOnly = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "Switch to raw mode",
            replyContext = VoiceReplyContext(focusedReply = "Prior reply"),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        ) as VoiceControlDecision.LiveTuning
        assertEquals(TtsInputMode.RAW, rawOnly.inputMode)
        assertEquals(null, rawOnly.detailLevel)
        assertEquals("Switched to Raw mode.", rawOnly.confirmationSpeech)

        val both = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "Switch to sanitized mode and essentials detail",
            replyContext = VoiceReplyContext(focusedReply = "Prior reply"),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        ) as VoiceControlDecision.LiveTuning
        assertEquals(TtsInputMode.SANITIZED, both.inputMode)
        assertEquals(SpokenDetailLevel.ESSENTIALS, both.detailLevel)
        assertEquals("Switched to Sanitized mode and Essentials detail.", both.confirmationSpeech)

        val fallback = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "Not actually tuning anything",
            replyContext = VoiceReplyContext(focusedReply = "Prior reply"),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )
        assertEquals(VoiceControlDecision.PassThrough, fallback)
    }

    @Test
    fun `evaluateTurn budgets siblings and unread previews within 8000 chars and populates state`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${decisionInner.replace("\"", "\\\"")}"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"The sibling check ran and a build worker also finished."}"""),
        )

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val longFocused = "F".repeat(7_500)
        val longSiblings = listOf("S1-" + "A".repeat(1_500), "S2-" + "B".repeat(1_500))
        val longPreview = "U".repeat(500)
        val unreadPreviews = listOf(
            UnreadReplyPreview(messageId = "m1", sourceLabel = "Worker A", previewText = longPreview),
            UnreadReplyPreview(messageId = "m2", sourceLabel = "Worker B", previewText = "Tests green."),
            UnreadReplyPreview(messageId = "m3", sourceLabel = "Worker C", previewText = "Z".repeat(500)),
            UnreadReplyPreview(messageId = "m4", sourceLabel = "Worker D", previewText = "W".repeat(500)),
        )

        val decision = vcp.evaluateTurn(
            serverUrl = server.url("/").toString().trimEnd('/'),
            utterance = "Did anything else finish?",
            replyContext = VoiceReplyContext(
                focusedReply = longFocused,
                focusedTriggerLabel = "Main task",
                sameTurnSiblings = longSiblings,
                pendingUnreadPreviews = unreadPreviews,
            ),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertTrue(decision is VoiceControlDecision.AnswerDirectly)
        assertEquals(2, server.requestCount)

        val decisionReqBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["body"]!!.jsonObject
        val state = decisionReqBody["state"]!!.jsonObject
        assertEquals(true, state["has_pending_unread"]?.jsonPrimitive?.booleanOrNull)

        val lastReplyState = state["last_assistant_reply"]!!.jsonPrimitive.content
        val unreadState = state["pending_unread_replies"]!!.jsonPrimitive.content
        assertTrue(lastReplyState.contains("Other steps in same turn:"))
        assertTrue(lastReplyState.contains("S1-"))
        assertTrue(unreadState.contains("[UNREAD · Worker A]: "))
        assertTrue(unreadState.contains("[UNREAD · Worker B]: Tests green."))
        assertTrue(lastReplyState.length + unreadState.length <= VoiceControlPlane.MAX_REPLY_CONTEXT_CHARS)

        val firstUnreadLine = unreadState.lineSequence().first()
        val firstSnippet = firstUnreadLine.substringAfter("[UNREAD · Worker A]: ")
        assertTrue(firstSnippet.length <= VoiceControlPlane.UNREAD_PREVIEW_MAX_CHARS)

        val answerPrompt = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["prompt"]!!.jsonPrimitive.content
        assertTrue(answerPrompt.contains("Focused assistant reply (Main task):"))
        assertTrue(answerPrompt.contains("Other steps in same turn:"))
        assertTrue(answerPrompt.contains("Queued unread replies (only if user asks what else finished):"))
    }

    @Test
    fun `evaluateTurn allows focusedReply up to 8000 chars when siblings and unread are empty`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"pass_through"},"tuning_detail":{"choice":"none"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${decisionInner.replace("\"", "\\\"")}"}"""),
        )

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val decision = vcp.evaluateTurn(
            serverUrl = server.url("/").toString().trimEnd('/'),
            utterance = "Continue",
            replyContext = VoiceReplyContext(focusedReply = "X".repeat(9_000)),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertEquals(VoiceControlDecision.PassThrough, decision)
        val state = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["body"]!!.jsonObject["state"]!!.jsonObject
        val lastReplyState = state["last_assistant_reply"]!!.jsonPrimitive.content
        assertEquals(8_000, lastReplyState.length)
        assertEquals(false, state["has_pending_unread"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `evaluateTurn routes queue-only question to answer_directly when focusedReply is null`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${decisionInner.replace("\"", "\\\"")}"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"Your background lint run finished with zero warnings."}"""),
        )

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val decision = vcp.evaluateTurn(
            serverUrl = server.url("/").toString().trimEnd('/'),
            utterance = "What is in my unread queue?",
            replyContext = VoiceReplyContext(
                focusedReply = null,
                pendingUnreadPreviews = listOf(
                    UnreadReplyPreview(
                        messageId = "m1",
                        sourceLabel = "Lint subagent",
                        previewText = "Finished with 0 warnings.",
                    ),
                ),
            ),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertTrue(decision is VoiceControlDecision.AnswerDirectly)
        assertEquals(
            "Your background lint run finished with zero warnings.",
            (decision as VoiceControlDecision.AnswerDirectly).spokenAnswer,
        )
        assertEquals(2, server.requestCount)
        val state = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["body"]!!.jsonObject["state"]!!.jsonObject
        assertEquals(true, state["has_pending_unread"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(state.containsKey("last_assistant_reply"))
        assertEquals(
            "[UNREAD · Lint subagent]: Finished with 0 warnings.",
            state["pending_unread_replies"]?.jsonPrimitive?.content,
        )

        val answerPrompt = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["prompt"]!!.jsonPrimitive.content
        assertFalse(answerPrompt.contains("Focused assistant reply"))
        assertTrue(answerPrompt.contains("Queued unread replies (only if user asks what else finished):"))
    }

    @Test
    fun `evaluateTurn bypasses answer_directly when both focusedReply and pendingUnreadPreviews are empty`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${decisionInner.replace("\"", "\\\"")}"}"""),
        )

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val decision = vcp.evaluateTurn(
            serverUrl = server.url("/").toString().trimEnd('/'),
            utterance = "What did you just say?",
            replyContext = VoiceReplyContext(),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertEquals(VoiceControlDecision.PassThrough, decision)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `evaluateTurn with allowDirectAnswer false omits reply context and answer_directly option while keeping live_tuning active`() = runTest {
        val answerInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"}}}"""
        val liveInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"essentials"},"tuning_mode":{"choice":"none"}}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${answerInner.replace("\"", "\\\"")}"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":200,"body":"${liveInner.replace("\"", "\\\"")}"}"""),
        )

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val suppressed = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "What did step 2 say?",
            replyContext = VoiceReplyContext(
                focusedReply = "Step 2 fixed the bug.",
                pendingUnreadPreviews = listOf(
                    UnreadReplyPreview("m2", "Worker", "Build passed."),
                ),
            ),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
            allowDirectAnswer = false,
        )
        assertEquals(VoiceControlDecision.PassThrough, suppressed)

        val tuning = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "Switch to essentials detail",
            replyContext = VoiceReplyContext(focusedReply = "Step 2 fixed the bug."),
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
            allowDirectAnswer = false,
        )
        assertTrue(tuning is VoiceControlDecision.LiveTuning)
        assertEquals(SpokenDetailLevel.ESSENTIALS, (tuning as VoiceControlDecision.LiveTuning).detailLevel)

        // Ensure neither call triggered /api/small-model/generate and reply context / answer_directly criteria were omitted
        assertEquals(2, server.requestCount)
        val firstBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["body"]!!.jsonObject
        val firstState = firstBody["state"]!!.jsonObject
        assertFalse(firstState.containsKey("last_assistant_reply"))
        assertFalse(firstState.containsKey("pending_unread_replies"))
        assertFalse(firstState.containsKey("has_pending_unread"))
        val routeCriteria = firstBody["questions"]!!.jsonObject["route"]!!.jsonObject["criteria"]!!.jsonObject
        assertFalse(routeCriteria.containsKey("answer_directly"))
        assertTrue(routeCriteria.containsKey("live_tuning"))
        assertTrue(routeCriteria.containsKey("pass_through"))
    }
}
