package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.TtsInputMode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `evaluateTurn routes answer_directly to small-model using lastReply`() = runTest {
        val decisionInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"}}}"""
        val escapedInner = decisionInner.replace("\"", "\\\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"body":"$escapedInner"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Option two uses **Kokoro** locally."}"""))

        val vcp = VoiceControlPlane(OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val decision = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "What was option 2?",
            lastReply = "Option 1: Gemini. Option 2: Local Kokoro.",
            currentDetailLevel = SpokenDetailLevel.ESSENTIALS,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )

        assertTrue(decision is VoiceControlDecision.AnswerDirectly)
        assertEquals(
            "Option two uses Kokoro locally.",
            (decision as VoiceControlDecision.AnswerDirectly).spokenAnswer,
        )
        assertEquals(2, server.requestCount)
        assertEquals("/api/guests/gemini-decisions-bridge/service/request", server.takeRequest().path)
        assertEquals("/api/small-model/generate", server.takeRequest().path)
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
            lastReply = "Some prior reply.",
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
            lastReply = "Previous reply.",
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
            lastReply = "Prior reply",
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        ) as VoiceControlDecision.LiveTuning
        assertEquals(TtsInputMode.RAW, rawOnly.inputMode)
        assertEquals(null, rawOnly.detailLevel)
        assertEquals("Switched to Raw mode.", rawOnly.confirmationSpeech)

        val both = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "Switch to sanitized mode and essentials detail",
            lastReply = "Prior reply",
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        ) as VoiceControlDecision.LiveTuning
        assertEquals(TtsInputMode.SANITIZED, both.inputMode)
        assertEquals(SpokenDetailLevel.ESSENTIALS, both.detailLevel)
        assertEquals("Switched to Sanitized mode and Essentials detail.", both.confirmationSpeech)

        val fallback = vcp.evaluateTurn(
            serverUrl = baseUrl,
            utterance = "Not actually tuning anything",
            lastReply = "Prior reply",
            currentDetailLevel = SpokenDetailLevel.BALANCED,
            currentInputMode = TtsInputMode.SUMMARIZED,
        )
        assertEquals(VoiceControlDecision.PassThrough, fallback)
    }
}
