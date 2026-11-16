package com.openchamber.ptt.service

import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.voice.DictationClient
import com.openchamber.ptt.voice.DictationException
import com.openchamber.ptt.voice.MlKitSttClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioBurstRecorderTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("audio-burst-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private class FakePcmCaptureSource(
        private val chunks: List<ByteArray>,
        private val blockUntilStoppedAfterChunks: Boolean = false,
        private val chunkDelayMs: Long = 5L,
    ) : PcmCaptureSource {
        private var index = 0
        val stopped = AtomicBoolean(false)
        val releaseCount = AtomicInteger(0)

        override fun startRecording() = Unit

        override fun readBlocking(buffer: ByteArray, sizeInBytes: Int): Int {
            if (stopped.get()) return -1
            if (index < chunks.size) {
                val src = chunks[index++]
                val count = minOf(src.size, sizeInBytes)
                System.arraycopy(src, 0, buffer, 0, count)
                if (chunkDelayMs > 0L) Thread.sleep(chunkDelayMs)
                return count
            }
            if (blockUntilStoppedAfterChunks) {
                while (!stopped.get()) {
                    Thread.sleep(10L)
                }
                return -1
            }
            return -1
        }

        override fun stopIfRecording() {
            stopped.set(true)
        }

        override fun release() {
            stopped.set(true)
            releaseCount.incrementAndGet()
        }
    }

    private fun enqueueDictationWs(
        finalTranscript: String,
        partialText: String = "partial text",
    ) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ready"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val obj = json.parseToJsonElement(text).jsonObject
                        val type = obj["type"]?.jsonPrimitive?.content
                        val id = obj["dictationId"]?.jsonPrimitive?.content ?: return
                        when (type) {
                            "start" -> {
                                webSocket.send("""{"type":"ack","dictationId":"$id","ackSeq":-1}""")
                            }
                            "chunk" -> {
                                val seq = obj["seq"]?.jsonPrimitive?.int ?: return
                                webSocket.send("""{"type":"ack","dictationId":"$id","ackSeq":$seq}""")
                                if (seq == 0 && partialText.isNotBlank()) {
                                    webSocket.send("""{"type":"partial","dictationId":"$id","text":"$partialText"}""")
                                }
                            }
                            "finish" -> {
                                webSocket.send("""{"type":"finish_accepted","dictationId":"$id","timeoutMs":5000}""")
                                webSocket.send("""{"type":"final","dictationId":"$id","text":"$finalTranscript"}""")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
    }

    @Test
    fun `CaptureController attach fails after stop and release is idempotent`() {
        val controller = CaptureController()
        val source = FakePcmCaptureSource(emptyList())
        assertTrue(controller.attach(source))

        controller.requestStop()
        assertTrue(controller.isStopRequested)
        assertTrue(source.stopped.get())

        val lateSource = FakePcmCaptureSource(emptyList())
        assertFalse(controller.attach(lateSource))

        controller.release()
        controller.release()
        assertEquals(1, source.releaseCount.get())
    }

    @Test
    fun `Parakeet streaming captures PCM chunks forwards non-blank partials and logs telemetry`() = runBlocking {
        enqueueDictationWs(finalTranscript = "run the unit tests", partialText = "run the")
        val logFile = File(tempDir, "ux.jsonl")
        val logger = VoiceUxLogger(logFile = logFile)
        val partials = mutableListOf<String>()
        var currentState = PttState.RECORDING
        val source = FakePcmCaptureSource(
            chunks = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)),
            blockUntilStoppedAfterChunks = true,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OpenChamberHttp.createClient(tokenProvider = { "tok" })

        try {
            val recorder = AudioBurstRecorder(
                scope = scope,
                dictationClient = DictationClient(http),
                mlKitSttClient = MlKitSttClient(),
                voiceUxLogger = logger,
                currentState = { currentState },
                sessionKeyProvider = { "srv|ses_1|/dir" },
                lastTtsStopElapsedMsProvider = { 0L },
                hasRecordAudioPermission = { true },
                pinBluetoothScoProvider = { false },
                sttEngineProvider = { SttEngine.OPENCHAMBER_PARAKEET },
                sttFallbackToParakeetProvider = { true },
                onPlayWarningCue = {},
                onMaxCapAutoStage = {},
                onPartialTranscript = { partials += it },
                onStatusUpdate = {},
                clockElapsedMs = { System.currentTimeMillis() },
                captureSourceFactory = { source },
            )

            val doneLatch = CountDownLatch(1)
            var capturedOutcome: CaptureOutcome? = null
            var capturedError: Throwable? = null
            recorder.startBurst(
                serverId = "srv",
                serverUrl = server.url("/").toString().trimEnd('/'),
                pinnedSession = PinnedSession("ses_1", "/dir", "Demo"),
                onStoppedEarly = {},
                onOutcome = { _, _, _, outcome ->
                    capturedOutcome = outcome
                    doneLatch.countDown()
                },
                onFailure = { _, err ->
                    capturedError = err
                    doneLatch.countDown()
                },
            )

            Thread.sleep(60L)
            currentState = PttState.TRANSCRIBING_SEND
            recorder.requestStop()

            assertTrue("Expected capture outcome", doneLatch.await(5, TimeUnit.SECONDS))
            capturedError?.let { throw it }
            assertNotNull(capturedOutcome)
            assertEquals("run the unit tests", capturedOutcome?.transcript)
            assertFalse(capturedOutcome?.fallbackUsed ?: true)
            assertFalse(capturedOutcome?.hitMaxCap ?: true)
            assertEquals(listOf("run the"), partials)
            assertEquals(1, source.releaseCount.get())

            val events = logger.readEvents()
            assertEquals(1, events.size)
            assertEquals(VoiceUxLogger.CAT_CAPTURE, events[0].category)
            assertEquals("send", events[0].metrics["stopMode"])
            assertEquals("false", events[0].metrics["fallbackUsed"])
            assertEquals("4", events[0].metrics["wordCount"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ML Kit error falls back to Parakeet with buffered PCM while empty transcript returns null`() = runBlocking {
        enqueueDictationWs(finalTranscript = "fallback parakeet result", partialText = "")
        val logFile = File(tempDir, "ux_fallback.jsonl")
        val logger = VoiceUxLogger(logFile = logFile)
        val statuses = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OpenChamberHttp.createClient(tokenProvider = { "tok" })

        try {
            val source1 = FakePcmCaptureSource(
                chunks = listOf(byteArrayOf(1, 2, 3, 4)),
                blockUntilStoppedAfterChunks = true,
            )
            val fallbackController = CaptureController()
            val recorderWithFallback = AudioBurstRecorder(
                scope = scope,
                dictationClient = DictationClient(http),
                mlKitSttClient = MlKitSttClient(),
                voiceUxLogger = logger,
                currentState = { PttState.TRANSCRIBING_SEND },
                sessionKeyProvider = { "srv|ses_1|/dir" },
                lastTtsStopElapsedMsProvider = { 0L },
                hasRecordAudioPermission = { true },
                pinBluetoothScoProvider = { false },
                sttEngineProvider = { SttEngine.MLKIT_GENAI_ADVANCED },
                sttFallbackToParakeetProvider = { true },
                onPlayWarningCue = {},
                onMaxCapAutoStage = {},
                onPartialTranscript = {},
                onStatusUpdate = { statuses += it },
                clockElapsedMs = { System.currentTimeMillis() },
                captureSourceFactory = { source1 },
                mlKitTranscribe = { _, _, _ ->
                    // Simulate ML Kit failing early while user finishes burst, then stopping capture.
                    Thread.sleep(30L)
                    fallbackController.requestStop()
                    throw IllegalStateException("ML Kit offline")
                },
            )

            val outcome = recorderWithFallback.recordAndTranscribe(
                serverUrl = server.url("/").toString().trimEnd('/'),
                captureController = fallbackController,
            )
            assertNotNull(outcome)
            assertEquals("fallback parakeet result", outcome?.transcript)
            assertTrue(outcome?.fallbackUsed ?: false)
            assertEquals(listOf("ML Kit fallback → Parakeet…"), statuses)

            val events = logger.readEvents()
            assertEquals("true", events.last().metrics["fallbackUsed"])

            // Empty ML Kit transcript should not hit Parakeet
            val source2 = FakePcmCaptureSource(
                chunks = listOf(byteArrayOf(9, 10)),
                blockUntilStoppedAfterChunks = true,
            )
            val recorderEmpty = AudioBurstRecorder(
                scope = scope,
                dictationClient = DictationClient(http),
                mlKitSttClient = MlKitSttClient(),
                voiceUxLogger = logger,
                currentState = { PttState.TRANSCRIBING_SEND },
                sessionKeyProvider = { "srv|ses_1|/dir" },
                lastTtsStopElapsedMsProvider = { 0L },
                hasRecordAudioPermission = { true },
                pinBluetoothScoProvider = { false },
                sttEngineProvider = { SttEngine.MLKIT_GENAI_BASIC },
                sttFallbackToParakeetProvider = { true },
                onPlayWarningCue = {},
                onMaxCapAutoStage = {},
                onPartialTranscript = {},
                onStatusUpdate = {},
                clockElapsedMs = { System.currentTimeMillis() },
                captureSourceFactory = { source2 },
                mlKitTranscribe = { _, _, _ ->
                    throw DictationException("empty", isEmptyTranscript = true)
                },
            )
            val emptyOutcome = recorderEmpty.recordAndTranscribe(
                serverUrl = server.url("/").toString().trimEnd('/'),
                captureController = CaptureController(),
            )
            assertNull(emptyOutcome)
            assertEquals(1, server.requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `barge-in cooldown aborts early without opening AudioRecord and max cap auto-stages with warning cue`() =
        runBlocking {
            val logger = VoiceUxLogger(logFile = null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val http = OpenChamberHttp.createClient()

            try {
                // 1) Cooldown abort before AudioRecord creation
                var factoryCalled = false
                var stoppedEarlyCalled = false
                val cooldownController = CaptureController()
                cooldownController.requestStop()
                var nowMs = 1_050L
                val cooldownRecorder = AudioBurstRecorder(
                    scope = scope,
                    dictationClient = DictationClient(http),
                    mlKitSttClient = MlKitSttClient(),
                    voiceUxLogger = logger,
                    currentState = { PttState.TRANSCRIBING_SEND },
                    sessionKeyProvider = { "srv|ses_1|/dir" },
                    lastTtsStopElapsedMsProvider = { 1_000L },
                    hasRecordAudioPermission = { true },
                    pinBluetoothScoProvider = { false },
                    sttEngineProvider = { SttEngine.OPENCHAMBER_PARAKEET },
                    sttFallbackToParakeetProvider = { false },
                    onPlayWarningCue = {},
                    onMaxCapAutoStage = {},
                    onPartialTranscript = {},
                    onStatusUpdate = {},
                    bargeInCooldownMs = 150L,
                    clockElapsedMs = { nowMs.also { nowMs += 100L } },
                    captureSourceFactory = {
                        factoryCalled = true
                        FakePcmCaptureSource(emptyList())
                    },
                )
                cooldownRecorder.executeBurst(
                    serverId = "srv",
                    serverUrl = "http://127.0.0.1:4096",
                    pinnedSession = PinnedSession("ses_1", "/dir", "Demo"),
                    captureController = cooldownController,
                    onStoppedEarly = { stoppedEarlyCalled = true },
                    onOutcome = { _, _, _, _ -> },
                    onFailure = { _, err -> throw err },
                )
                assertTrue(stoppedEarlyCalled)
                assertFalse(factoryCalled)

                // 2) Warning cue + max cap auto-stage
                var state = PttState.RECORDING
                var warningCueCount = 0
                var autoStageCount = 0
                val maxCapSource = FakePcmCaptureSource(
                    chunks = listOf(byteArrayOf(1, 2, 3, 4)),
                    blockUntilStoppedAfterChunks = true,
                )
                val maxCapRecorder = AudioBurstRecorder(
                    scope = scope,
                    dictationClient = DictationClient(http),
                    mlKitSttClient = MlKitSttClient(),
                    voiceUxLogger = logger,
                    currentState = { state },
                    sessionKeyProvider = { "srv|ses_1|/dir" },
                    lastTtsStopElapsedMsProvider = { 0L },
                    hasRecordAudioPermission = { true },
                    pinBluetoothScoProvider = { false },
                    sttEngineProvider = { SttEngine.MLKIT_GENAI_ADVANCED },
                    sttFallbackToParakeetProvider = { false },
                    onPlayWarningCue = { warningCueCount++ },
                    onMaxCapAutoStage = {
                        autoStageCount++
                        state = PttState.TRANSCRIBING_STAGE
                    },
                    onPartialTranscript = {},
                    onStatusUpdate = {},
                    maxUtteranceWarningMs = 35L,
                    maxUtteranceMs = 90L,
                    clockElapsedMs = { System.currentTimeMillis() },
                    captureSourceFactory = { maxCapSource },
                    mlKitTranscribe = { _, audioChunks, onPartial ->
                        val totalBytes = AtomicInteger(0)
                        audioChunks.collect { totalBytes.addAndGet(it.size) }
                        onPartial("long capture")
                        "long capture (${totalBytes.get()}b)"
                    },
                )

                val outcome = maxCapRecorder.recordAndTranscribe(
                    serverUrl = "http://127.0.0.1:4096",
                    captureController = CaptureController(),
                )
                assertNotNull(outcome)
                assertTrue(outcome?.hitMaxCap ?: false)
                assertEquals(1, warningCueCount)
                assertEquals(1, autoStageCount)
            } finally {
                scope.cancel()
            }
        }
}
