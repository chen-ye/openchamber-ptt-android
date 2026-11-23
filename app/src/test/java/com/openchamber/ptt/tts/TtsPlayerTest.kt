package com.openchamber.ptt.tts

import com.openchamber.ptt.audio.AudioOutputCoordinator
import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TtsPlayerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

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
    fun `speak sanitizes text synthesizes WAV and plays PCM via track player`() = runTest {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = pcm,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"Sanitized text."}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        var playedWav: WavAudio? = null
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedWav = wav
            }

            override fun stopNow() = Unit
        }
        val spokenBySystemTts = mutableListOf<String>()
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                spokenBySystemTts += text
            }

            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Markdown **text**")

        assertEquals(24000, playedWav?.sampleRate)
        assertArrayEquals(pcm, playedWav?.pcmData)
        assertTrue(spokenBySystemTts.isEmpty())
        assertFalse(player.isSpeaking)
    }

    @Test
    fun `speak falls back to Android TextToSpeech when all network TTS endpoints fail`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"Spoken fallback."}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val spokenBySystemTts = mutableListOf<String>()
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                spokenBySystemTts += text
            }

            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Original text")

        assertEquals(listOf("Spoken fallback."), spokenBySystemTts)
    }

    @Test
    fun `stop immediately cancels active PCM playback without falling back to system TTS`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(1, 2, 3, 4),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"Long response."}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val playbackStarted = CompletableDeferred<Unit>()
        val playbackGate = CompletableDeferred<Unit>()
        val trackStopped = AtomicBoolean(false)
        val systemTtsStopped = AtomicBoolean(false)
        val systemTtsCalled = AtomicBoolean(false)

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playbackStarted.complete(Unit)
                playbackGate.await()
            }

            override fun stopNow() {
                trackStopped.set(true)
                playbackGate.cancel(CancellationException("Stopped"))
            }
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                systemTtsCalled.set(true)
            }

            override fun stop() {
                systemTtsStopped.set(true)
            }

            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val job = launch {
            player.speak(baseUrl, "Long response.")
        }
        playbackStarted.await()
        assertTrue(player.isSpeaking)

        player.stop()
        job.join()

        assertTrue(trackStopped.get())
        assertTrue(systemTtsStopped.get())
        assertFalse(systemTtsCalled.get())
        assertFalse(player.isSpeaking)
    }

    @Test
    fun `respects configured TTS engine enablement and fallback order`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x05, 0x06, 0x07, 0x08),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"Ordered audio."}"""))
        // 1st enabled engine is LOCAL_KOKORO -> fails with 503
        server.enqueue(MockResponse().setResponseCode(503))
        // 2nd enabled engine is OPENAI_CLOUD (GEMINI_BRIDGE is disabled!) -> succeeds without baseURL
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        var playedWav: WavAudio? = null
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedWav = wav
            }
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val customPipeline = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false),
            TtsEngineOption(TtsEngine.LOCAL_KOKORO, enabled = true),
            TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true),
            TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = false),
        )

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { customPipeline },
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Ordered audio.")

        assertEquals(24000, playedWav?.sampleRate)
        server.takeRequest() // summarize
        val firstSynth = server.takeRequest()
        assertEquals("/api/dictation/tts/speak", firstSynth.path)
        val secondSynth = server.takeRequest()
        assertEquals("/api/tts/speak", secondSynth.path)
        assertFalse(secondSynth.body.readUtf8().contains("baseURL"))
    }

    @Test
    fun `falls back from ML Kit GenAI TTS to Android System TTS without replacing it`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"Hello on device."}"""))

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val spokenBySystem = mutableListOf<String>()
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                spokenBySystem += text
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val mlKitAttempts = mutableListOf<String>()
        val fakeMlKitTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                mlKitAttempts += text
                throw TtsException(message = "ML Kit GenAI TTS unavailable")
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val pipeline = listOf(
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
            TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
        )

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            mlKitTtsSpeaker = fakeMlKitTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { pipeline },
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Hello on device.")

        assertEquals(listOf("Hello on device."), mlKitAttempts)
        assertEquals(listOf("Hello on device."), spokenBySystem)
    }

    @Test
    fun `OPENAI_CLOUD uses agent openAiVoice instead of Gemini voice`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"OpenAI voice check."}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true)) },
            roleProfilesProvider = {
                RoleVoiceProfileMap(
                    agent = RoleVoiceAssignment(geminiVoice = "Sulafat", openAiVoice = "onyx"),
                )
            },
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "OpenAI voice check.", role = VoiceRole.AGENT)

        server.takeRequest() // summarize
        val synthReq = server.takeRequest()
        assertEquals("POST", synthReq.method)
        assertEquals("/api/tts/speak", synthReq.path)
        val body = synthReq.body.readUtf8()
        assertTrue(body.contains("\"voice\":\"onyx\""))
        assertFalse(body.contains("Sulafat"))
    }

    @Test
    fun `speak preserves 401 statusCode when all enabled engines fail with 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"summary":"Auth check."}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = {
                TtsEngine.entries.map { TtsEngineOption(it, enabled = it == TtsEngine.GEMINI_BRIDGE) }
            },
            inputModeProvider = { TtsInputMode.SANITIZED },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        try {
            player.speak(baseUrl, "Auth check.")
            org.junit.Assert.fail("Expected TtsException")
        } catch (ex: TtsException) {
            assertEquals(401, ex.statusCode)
        }
    }

    @Test
    fun `splitTextForSpeech splits long text along word boundaries`() {
        val text = "alpha beta gamma delta epsilon zeta eta theta"
        val chunks = splitTextForSpeech(text, maxLen = 18)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.length <= 18 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `speak with RAW inputMode skips summarize and sends exact text`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Literal `code` text")

        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("/api/tts/speak", req.path)
        assertTrue(req.body.readUtf8().contains("Literal `code` text"))
    }

    @Test
    fun `speak with inputModeOverride RAW overrides SUMMARIZED and reports TtsSpeakStats`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val stats = mutableListOf<TtsSpeakStats>()
        val synthesizedTexts = mutableListOf<String>()
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.SUMMARIZED },
            onSpokenTextSynthesized = { synthesizedTexts += it },
            onSpeakStats = { stats += it },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val draftText = "Organized multi-sentence draft preview. ".repeat(20)
        player.speak(baseUrl, draftText, inputModeOverride = TtsInputMode.RAW)

        assertEquals(1, server.requestCount)
        assertEquals("/api/tts/speak", server.takeRequest().path)
        assertEquals(listOf(draftText.trim()), synthesizedTexts)
        assertEquals(1, stats.size)
        assertEquals(TtsInputMode.RAW, stats.first().inputMode)
        assertEquals(TtsEngine.GEMINI_BRIDGE, stats.first().engine)
        assertEquals(0, stats.first().prerollMs)
    }

    @Test
    fun `OPENAI_CLOUD decodes non-WAV audio_mpeg via encodedAudioDecoder with cold-sink preroll then warm follow-up`() = runTest {
        val fakeMp3Bytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x01, 0x02, 0x03)
        val decodedPcm = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        val decodedWav = WavAudio(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcmData = decodedPcm,
            audioFormat = WavAudio.FORMAT_PCM,
        )

        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(Buffer().write(fakeMp3Bytes)),
            )
        }

        val playedWavs = mutableListOf<WavAudio>()
        val decodedArgs = mutableListOf<Pair<ByteArray, String?>>()
        val stats = mutableListOf<TtsSpeakStats>()

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedWavs += wav
            }

            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        var nowMs = 10_000L
        val coordinator = AudioOutputCoordinator(clockElapsedMs = { nowMs })
        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            encodedAudioDecoder = { bytes, contentType ->
                decodedArgs += bytes to contentType
                decodedWav
            },
            audioOutputCoordinator = coordinator,
            onSpeakStats = { stats += it },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')

        // First cold utterance -> 160ms preroll prepended
        player.speak(baseUrl, "First cold utterance.")
        nowMs += 200L
        // Immediate warm follow-up -> 0ms preroll
        player.speak(baseUrl, "Second warm utterance.")

        assertEquals(2, decodedArgs.size)
        assertArrayEquals(fakeMp3Bytes, decodedArgs[0].first)
        assertEquals("audio/mpeg", decodedArgs[0].second)

        assertEquals(2, playedWavs.size)
        val expectedPrerollBytes = (24_000 * 160 / 1000) * 1 * 2
        assertEquals(expectedPrerollBytes + decodedPcm.size, playedWavs[0].pcmData.size)
        assertArrayEquals(
            decodedPcm,
            playedWavs[0].pcmData.copyOfRange(expectedPrerollBytes, playedWavs[0].pcmData.size),
        )
        assertArrayEquals(decodedPcm, playedWavs[1].pcmData)

        assertEquals(2, stats.size)
        assertEquals(160, stats[0].prerollMs)
        assertEquals(0, stats[1].prerollMs)
    }

    @Test
    fun `on-device engine plays 160ms wakeup buffer when cold and skips when warm`() = runTest {
        val playedWakeups = mutableListOf<WavAudio>()
        val spokenTexts = mutableListOf<String>()
        val stats = mutableListOf<TtsSpeakStats>()

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedWakeups += wav
            }

            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                spokenTexts += text
            }

            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        var nowMs = 5_000L
        val coordinator = AudioOutputCoordinator(clockElapsedMs = { nowMs })
        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystemTts,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            audioOutputCoordinator = coordinator,
            onSpeakStats = { stats += it },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Cold system TTS.")
        nowMs += 100L
        player.speak(baseUrl, "Warm system TTS.")

        assertEquals(listOf("Cold system TTS.", "Warm system TTS."), spokenTexts)
        assertEquals(1, playedWakeups.size)
        assertEquals(24_000, playedWakeups[0].sampleRate)
        assertEquals(1, playedWakeups[0].channels)
        assertEquals(16, playedWakeups[0].bitsPerSample)
        assertEquals((24_000 * 160 / 1000) * 2, playedWakeups[0].pcmData.size)

        assertEquals(2, stats.size)
        assertEquals(160, stats[0].prerollMs)
        assertEquals(0, stats[1].prerollMs)
    }

    @Test
    fun `re-reading same reply hits both text and audio caches offline and voice switch reuses text cache`() = runTest {
        val wavKore = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        val wavPuck = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x05, 0x06, 0x07, 0x08),
        )
        val longReply = "Detailed build output: ".repeat(15)

        // 1. small-model summarize for longReply
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Build passed cleanly."}"""))
        // 2. synthesize Kore
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavKore)),
        )
        // 3. synthesize Puck (no second small-model call!)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavPuck)),
        )

        val playedWavs = mutableListOf<WavAudio>()
        val stats = mutableListOf<TtsSpeakStats>()
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedWavs += wav
            }
            override fun stopNow() = Unit
        }
        val fakeSystemTts = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val cache = TtsCache(tempFolder.newFolder("player_cache"))
        try {
            val player = TtsPlayer(
                ttsClient = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache),
                pcmPlayerFactory = { fakeTrack },
                systemTtsSpeaker = fakeSystemTts,
                ioDispatcher = Dispatchers.Unconfined,
                pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                inputModeProvider = { TtsInputMode.SUMMARIZED },
                detailLevelProvider = { SpokenDetailLevel.BALANCED },
                onSpeakStats = { stats += it },
            )

            val baseUrl = server.url("/").toString().trimEnd('/')

            // First read (AGENT -> Kore): cold text + cold Kore audio (2 requests)
            player.speak(baseUrl, longReply, role = VoiceRole.AGENT)
            assertEquals(2, server.requestCount)
            assertEquals("/api/small-model/generate", server.takeRequest().path)
            assertEquals("/api/tts/speak", server.takeRequest().path)
            assertFalse(stats[0].textCacheHit)
            assertFalse(stats[0].audioCacheHit)
            assertEquals(VoiceRole.AGENT, stats[0].role)
            assertEquals("Kore", stats[0].voiceId)

            // Switch role (OPERATOR -> Puck): hits TextPrepCache, misses AudioCache (1 request total)
            player.speak(baseUrl, longReply, role = VoiceRole.OPERATOR)
            assertEquals(3, server.requestCount)
            val puckReq = server.takeRequest()
            assertEquals("/api/tts/speak", puckReq.path)
            assertTrue(puckReq.body.readUtf8().contains("\"voice\":\"Puck\""))
            assertTrue(stats[1].textCacheHit)
            assertFalse(stats[1].audioCacheHit)
            assertEquals(VoiceRole.OPERATOR, stats[1].role)
            assertEquals("Puck", stats[1].voiceId)
            assertArrayEquals(wavPuck.copyOfRange(44, wavPuck.size), playedWavs[1].pcmData)

            // Shut down server -> re-reading with AGENT (Kore) works 100% offline with 0 requests
            server.shutdown()
            player.speak(baseUrl, longReply, role = VoiceRole.AGENT)
            assertTrue(stats[2].textCacheHit)
            assertTrue(stats[2].audioCacheHit)
            assertArrayEquals(wavKore.copyOfRange(44, wavKore.size), playedWavs[2].pcmData)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `speaking with AGENT OPERATOR and CUE sends 3 distinct voices across Gemini OpenAI ML Kit and System TTS`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        repeat(6) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/wav")
                    .setBody(Buffer().write(wavBytes)),
            )
        }

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val mlKitVoicesUsed = mutableListOf<String?>()
        val fakeMlKit = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                mlKitVoicesUsed += preferredVoiceName
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val sysVoicesUsed = mutableListOf<String?>()
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                sysVoicesUsed += preferredVoiceName
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val stats = mutableListOf<TtsSpeakStats>()

        // 1. Gemini Bridge: Kore, Puck, Zephyr
        val geminiPlayer = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            onSpeakStats = { stats += it },
        )
        geminiPlayer.speak(baseUrl, "Agent line", role = VoiceRole.AGENT)
        geminiPlayer.speak(baseUrl, "Operator line", role = VoiceRole.OPERATOR)
        geminiPlayer.speak(baseUrl, "Cue line", role = VoiceRole.CUE)

        val geminiBodies = (1..3).map { server.takeRequest().body.readUtf8() }
        assertTrue(geminiBodies[0].contains("\"voice\":\"Kore\""))
        assertTrue(geminiBodies[1].contains("\"voice\":\"Puck\""))
        assertTrue(geminiBodies[2].contains("\"voice\":\"Zephyr\""))
        assertEquals(listOf("Kore", "Puck", "Zephyr"), stats.map { it.voiceId })
        assertEquals(listOf(VoiceRole.AGENT, VoiceRole.OPERATOR, VoiceRole.CUE), stats.map { it.role })

        // 2. OpenAI Cloud: alloy, sage, ash
        val openAiPlayer = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
        )
        openAiPlayer.speak(baseUrl, "Agent line", role = VoiceRole.AGENT)
        openAiPlayer.speak(baseUrl, "Operator line", role = VoiceRole.OPERATOR)
        openAiPlayer.speak(baseUrl, "Cue line", role = VoiceRole.CUE)

        val openAiBodies = (1..3).map { server.takeRequest().body.readUtf8() }
        assertTrue(openAiBodies[0].contains("\"voice\":\"alloy\""))
        assertTrue(openAiBodies[1].contains("\"voice\":\"sage\""))
        assertTrue(openAiBodies[2].contains("\"voice\":\"ash\""))

        // 3. ML Kit GenAI & Android System: 3 distinct installed voices
        val mlKitPlayer = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            mlKitTtsSpeaker = fakeMlKit,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true)) },
            availableMlKitVoicesProvider = { listOf("star02", "star04", "star05") },
            inputModeProvider = { TtsInputMode.RAW },
        )
        mlKitPlayer.speak(baseUrl, "Agent", role = VoiceRole.AGENT)
        mlKitPlayer.speak(baseUrl, "Operator", role = VoiceRole.OPERATOR)
        mlKitPlayer.speak(baseUrl, "Cue", role = VoiceRole.CUE)
        assertEquals(listOf("star02", "star04", "star05"), mlKitVoicesUsed)

        val systemPlayer = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true)) },
            availableSystemVoicesProvider = { listOf("sys-v1", "sys-v2", "sys-v3") },
            inputModeProvider = { TtsInputMode.RAW },
        )
        systemPlayer.speak(baseUrl, "Agent", role = VoiceRole.AGENT)
        systemPlayer.speak(baseUrl, "Operator", role = VoiceRole.OPERATOR)
        systemPlayer.speak(baseUrl, "Cue", role = VoiceRole.CUE)
        assertEquals(listOf("sys-v1", "sys-v2", "sys-v3"), sysVoicesUsed)
    }

    @Test
    fun `role preferredEngine routes first and falls back to basePipeline on failure`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        // 1st attempt: pinned OPENAI_CLOUD -> fails 503
        server.enqueue(MockResponse().setResponseCode(503))
        // 2nd attempt: fallback to GEMINI_BRIDGE -> succeeds with Puck
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val stats = mutableListOf<TtsSpeakStats>()
        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = {
                listOf(
                    TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true),
                    TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = false),
                )
            },
            roleProfilesProvider = {
                RoleVoiceProfileMap(
                    operator = RoleVoiceAssignment(preferredEngine = TtsEngine.OPENAI_CLOUD),
                )
            },
            inputModeProvider = { TtsInputMode.RAW },
            onSpeakStats = { stats += it },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        player.speak(baseUrl, "Fallback check", role = VoiceRole.OPERATOR)

        val firstReq = server.takeRequest()
        assertEquals("/api/tts/speak", firstReq.path)
        assertTrue(firstReq.body.readUtf8().contains("\"voice\":\"sage\""))

        val secondReq = server.takeRequest()
        assertEquals("/api/tts/speak", secondReq.path)
        assertTrue(secondReq.body.readUtf8().contains("\"voice\":\"Puck\""))

        assertEquals(1, stats.size)
        assertEquals(TtsEngine.GEMINI_BRIDGE, stats[0].engine)
        assertEquals(VoiceRole.OPERATOR, stats[0].role)
        assertEquals("Puck", stats[0].voiceId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `latency bridge skips on fast cache hit and fires at 450ms with A2DP comfort keep-alive on slow synthesis`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        var synthDelayMs = 0L
        var synthCacheHit = true
        val fakeClient = object : TtsClient(OpenChamberHttp.createClient()) {
            override suspend fun prepareTextForTtsDetailed(
                serverUrl: String,
                text: String,
                inputMode: TtsInputMode,
                sessionLabel: String?,
                detailLevel: SpokenDetailLevel,
            ): PreparedSpeechText = PreparedSpeechText(text = text, cacheHit = true)

            override suspend fun synthesizeWithEngineDetailed(
                serverUrl: String,
                text: String,
                voice: String,
                engine: TtsEngine,
                kokoroSpeakerId: Int,
            ): SynthesizedSpeechAudio {
                if (synthDelayMs > 0L) {
                    delay(synthDelayMs)
                }
                return SynthesizedSpeechAudio(
                    audio = TtsAudio(bytes = wavBytes, contentType = "audio/wav"),
                    cacheHit = synthCacheHit,
                )
            }
        }

        val playedReplies = mutableListOf<WavAudio>()
        val keepAliveChunks = mutableListOf<WavAudio>()
        val bridgeKinds = mutableListOf<String>()
        val stats = mutableListOf<TtsSpeakStats>()
        var useComm = false

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedReplies += wav
            }
            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val coordinator = AudioOutputCoordinator(clockElapsedMs = { testScheduler.currentTime })
        val player = TtsPlayer(
            ttsClient = fakeClient,
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            useCommunicationUsage = { useComm },
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            audioOutputCoordinator = coordinator,
            onSpeakStats = { stats += it },
            onLatencyBridgeNeeded = { kind -> bridgeKinds += kind },
            latencyBridgeDelayMs = 450L,
            keepAliveSinkPlayer = { wav, _ -> keepAliveChunks += wav },
        )

        // 1. Cache hit (0ms) -> bridge NEVER fires, no keep-alive chunks
        player.speak("http://localhost:4096", "Cached reply.")
        assertTrue(bridgeKinds.isEmpty())
        assertTrue(keepAliveChunks.isEmpty())
        assertEquals(1, playedReplies.size)

        // 2. Slow synthesis (1_100ms) -> bridge fires at 450ms ("reply"), emits 200ms dither chunks until 1_100ms
        bridgeKinds.clear()
        keepAliveChunks.clear()
        playedReplies.clear()
        stats.clear()
        synthDelayMs = 1_100L
        synthCacheHit = false

        val slowJob = launch {
            player.speak("http://localhost:4096", "Slow reply.", kind = TtsPlayer.KIND_REPLY, isManualRead = false)
        }
        advanceTimeBy(449L)
        runCurrent()
        assertTrue(bridgeKinds.isEmpty())
        assertTrue(keepAliveChunks.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(TtsPlayer.KIND_REPLY), bridgeKinds)
        assertTrue(keepAliveChunks.isNotEmpty())
        assertEquals((24_000 * 200 / 1000) * 2, keepAliveChunks.first().pcmData.size)

        advanceTimeBy(700L)
        runCurrent()
        slowJob.join()
        assertEquals(1, playedReplies.size)
        // Sink stayed warm via keep-alive -> 0ms cold preroll on final reply!
        assertEquals(0, stats.single().prerollMs)
        val chunksAfterCompletion = keepAliveChunks.size
        assertTrue(chunksAfterCompletion in 3..4)

        // 3. Manual read sets "manual_reply" on bridge while preserving telemetry kind = "reply"; SCO skips keep-alive dither
        bridgeKinds.clear()
        keepAliveChunks.clear()
        useComm = true
        synthDelayMs = 600L
        val manualJob = launch {
            player.speak("http://localhost:4096", "Manual read.", kind = TtsPlayer.KIND_REPLY, isManualRead = true)
        }
        advanceTimeBy(700L)
        runCurrent()
        manualJob.join()
        assertEquals(listOf(TtsPlayer.BRIDGE_KIND_MANUAL_REPLY), bridgeKinds)
        assertTrue(keepAliveChunks.isEmpty())
        assertEquals(TtsPlayer.KIND_REPLY, stats.last().kind)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `latency bridge comfort keep-alive caps at 12000ms for ultra-slow synthesis`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        val fakeClient = object : TtsClient(OpenChamberHttp.createClient()) {
            override suspend fun prepareTextForTtsDetailed(
                serverUrl: String,
                text: String,
                inputMode: TtsInputMode,
                sessionLabel: String?,
                detailLevel: SpokenDetailLevel,
            ): PreparedSpeechText = PreparedSpeechText(text = text, cacheHit = true)

            override suspend fun synthesizeWithEngineDetailed(
                serverUrl: String,
                text: String,
                voice: String,
                engine: TtsEngine,
                kokoroSpeakerId: Int,
            ): SynthesizedSpeechAudio {
                delay(20_000L)
                return SynthesizedSpeechAudio(
                    audio = TtsAudio(bytes = wavBytes, contentType = "audio/wav"),
                    cacheHit = false,
                )
            }
        }
        val keepAliveChunks = mutableListOf<WavAudio>()
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val player = TtsPlayer(
            ttsClient = fakeClient,
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            keepAliveSinkPlayer = { wav, _ -> keepAliveChunks += wav },
        )

        val job = launch {
            player.speak("http://localhost:4096", "Very slow reply.")
        }
        advanceTimeBy(20_500L)
        runCurrent()
        job.join()

        // 12_000ms cap / 200ms chunk = 60 chunks
        assertEquals(60, keepAliveChunks.size)
    }

    @Test
    fun `speakPreview synthesizes requested engine raw without summary callback, caches audio, and does not fallback`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D),
        )
        // 1. Gemini preview succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )
        // 2. OpenAI preview fails -> must throw without silently falling back to Android System TTS
        server.enqueue(MockResponse().setResponseCode(503))

        val playedWavs = mutableListOf<WavAudio>()
        val spokenSummaries = mutableListOf<String>()
        val statusEvents = mutableListOf<String>()
        val stats = mutableListOf<TtsSpeakStats>()
        val systemCalls = mutableListOf<Pair<String, String?>>()

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedWavs += wav
            }
            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                systemCalls += text to preferredVoiceName
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val cache = TtsCache(tempFolder.newFolder("preview_cache"))
        try {
            val player = TtsPlayer(
                ttsClient = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache),
                pcmPlayerFactory = { fakeTrack },
                systemTtsSpeaker = fakeSystem,
                ioDispatcher = Dispatchers.Unconfined,
                onStatusEvent = { statusEvents += it },
                onSpokenTextSynthesized = { spokenSummaries += it },
                onSpeakStats = { stats += it },
            )
            val baseUrl = server.url("/").toString().trimEnd('/')

            // First Gemini preview hits network and populates cache
            player.speakPreview(
                serverUrl = baseUrl,
                text = "Summarizing.",
                engine = TtsEngine.GEMINI_BRIDGE,
                voiceId = "Zephyr",
                role = VoiceRole.CUE,
            )
            assertEquals(1, server.requestCount)
            val reqBody = server.takeRequest().body.readUtf8()
            assertTrue(reqBody.contains("\"voice\":\"Zephyr\""))
            assertTrue(reqBody.contains("Summarizing."))
            assertTrue(spokenSummaries.isEmpty())
            assertTrue(statusEvents.isEmpty())
            assertEquals(1, stats.size)
            assertEquals(TtsPlayer.KIND_PREVIEW, stats[0].kind)
            assertEquals(VoiceRole.CUE, stats[0].role)
            assertEquals("Zephyr", stats[0].voiceId)
            assertEquals(TtsInputMode.RAW, stats[0].inputMode)
            assertFalse(stats[0].audioCacheHit)

            // Second identical preview hits TtsCache with 0 new network calls
            player.speakPreview(
                serverUrl = baseUrl,
                text = "Summarizing.",
                engine = TtsEngine.GEMINI_BRIDGE,
                voiceId = "Zephyr",
                role = VoiceRole.CUE,
            )
            assertEquals(1, server.requestCount)
            assertTrue(stats[1].audioCacheHit)
            assertEquals(2, playedWavs.size)

            // Explicit Android System preview routes requested voice directly
            player.speakPreview(
                serverUrl = "",
                text = "Switched to Spoken mode and Balanced detail.",
                engine = TtsEngine.ANDROID_SYSTEM,
                voiceId = "en-us-x-sfg-local",
                role = VoiceRole.OPERATOR,
            )
            assertEquals(listOf("Switched to Spoken mode and Balanced detail." to "en-us-x-sfg-local"), systemCalls)
            assertEquals(VoiceRole.OPERATOR, stats[2].role)
            assertEquals(TtsEngine.ANDROID_SYSTEM, stats[2].engine)

            // Failing OpenAI preview propagates error instead of falling back to System TTS
            var caught: TtsException? = null
            try {
                player.speakPreview(
                    serverUrl = baseUrl,
                    text = "Build succeeded cleanly.",
                    engine = TtsEngine.OPENAI_CLOUD,
                    voiceId = "onyx",
                    role = VoiceRole.AGENT,
                )
            } catch (ex: TtsException) {
                caught = ex
            }
            assertEquals(503, caught?.statusCode)
            assertEquals(1, systemCalls.size)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `concurrent speakWithResult calls queue FIFO on playbackMutex without cancelling caller parent jobs`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        val synthesizedOrder = CopyOnWriteArrayList<String>()
        val fakeClient = object : TtsClient(OpenChamberHttp.createClient()) {
            override suspend fun prepareTextForTtsDetailed(
                serverUrl: String,
                text: String,
                inputMode: TtsInputMode,
                sessionLabel: String?,
                detailLevel: SpokenDetailLevel,
            ): PreparedSpeechText = PreparedSpeechText(text = text, cacheHit = false)

            override suspend fun synthesizeWithEngineDetailed(
                serverUrl: String,
                text: String,
                voice: String,
                engine: TtsEngine,
                kokoroSpeakerId: Int,
            ): SynthesizedSpeechAudio {
                synthesizedOrder += text
                return SynthesizedSpeechAudio(
                    audio = TtsAudio(bytes = wavBytes, contentType = "audio/wav"),
                    cacheHit = false,
                )
            }
        }

        val playStartedGates = listOf(
            CompletableDeferred<Unit>(),
            CompletableDeferred<Unit>(),
            CompletableDeferred<Unit>(),
        )
        val playReleaseGates = listOf(
            CompletableDeferred<Unit>(),
            CompletableDeferred<Unit>(),
            CompletableDeferred<Unit>(),
        )
        val playIndex = AtomicInteger(0)
        val activeConcurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                val cur = activeConcurrency.incrementAndGet()
                maxConcurrency.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                val idx = playIndex.getAndIncrement()
                try {
                    playStartedGates[idx].complete(Unit)
                    playReleaseGates[idx].await()
                } finally {
                    activeConcurrency.decrementAndGet()
                }
            }

            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val messageCallbacks = CopyOnWriteArrayList<Pair<String?, String>>()
        val player = TtsPlayer(
            ttsClient = fakeClient,
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            onSpokenTextSynthesizedForMessage = { msgId, spoken -> messageCallbacks += msgId to spoken },
        )

        val job1 = async { player.speakWithResult("http://localhost:4096", "First", messageId = "m1") }
        playStartedGates[0].await()
        assertTrue(player.isSpeaking)

        val job2 = async { player.speakWithResult("http://localhost:4096", "Second", messageId = "m2") }
        yield()
        val job3 = async { player.speakWithResult("http://localhost:4096", "Third", messageId = "m3") }
        yield()

        assertEquals(listOf("First"), synthesizedOrder.toList())
        assertFalse(job1.isCancelled)
        assertFalse(job2.isCancelled)
        assertFalse(job3.isCancelled)

        playReleaseGates[0].complete(Unit)
        playStartedGates[1].await()
        assertEquals(listOf("First", "Second"), synthesizedOrder.toList())
        assertFalse(job1.isCancelled)

        playReleaseGates[1].complete(Unit)
        playStartedGates[2].await()
        assertEquals(listOf("First", "Second", "Third"), synthesizedOrder.toList())

        playReleaseGates[2].complete(Unit)

        assertEquals(TtsPlaybackResult.COMPLETED, job1.await())
        assertEquals(TtsPlaybackResult.COMPLETED, job2.await())
        assertEquals(TtsPlaybackResult.COMPLETED, job3.await())
        assertEquals(1, maxConcurrency.get())
        assertEquals(listOf("m1" to "First", "m2" to "Second", "m3" to "Third"), messageCallbacks.toList())
        assertFalse(player.isSpeaking)
    }

    @Test
    fun `stop during stalled cloud synthesis aborts in under 50ms, returns ABORTED to active and queued callers, and allows subsequent playback`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D),
        )
        val stalledRequestArrived = CompletableDeferred<Unit>()
        val requestedBodies = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requestedBodies += body
                return if (body.contains("Stalled cloud TTS")) {
                    stalledRequestArrived.complete(Unit)
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(wavBytes))
                }
            }
        }

        val playedPcms = CopyOnWriteArrayList<ByteArray>()
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                playedPcms += wav.pcmData
            }

            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.IO,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
        )
        val baseUrl = server.url("/").toString().trimEnd('/')

        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                val caller1 = async {
                    player.speakWithResult(baseUrl, "Stalled cloud TTS", messageId = "m-stalled")
                }
                stalledRequestArrived.await()
                assertTrue(player.isSpeaking)
                assertFalse(player.isAudibleOutputActive)

                val caller2Entered = CompletableDeferred<Unit>()
                val caller2 = async {
                    caller2Entered.complete(Unit)
                    player.speakWithResult(baseUrl, "Queued before stop", messageId = "m-queued")
                }
                caller2Entered.await()
                delay(25L)

                val stopStartNs = System.nanoTime()
                player.stop()
                val res1 = caller1.await()
                val res2 = caller2.await()
                val abortElapsedMs = (System.nanoTime() - stopStartNs) / 1_000_000L

                assertEquals(TtsPlaybackResult.ABORTED, res1)
                assertEquals(TtsPlaybackResult.ABORTED, res2)
                assertFalse(caller1.isCancelled)
                assertFalse(caller2.isCancelled)
                assertFalse(player.isSpeaking)
                assertFalse(player.isAudibleOutputActive)
                assertTrue(
                    "Expected stalled synthesis abort in < 1000ms, took ${abortElapsedMs}ms",
                    abortElapsedMs < 1_000L,
                )
                assertTrue(playedPcms.isEmpty())

                val res3 = player.speakWithResult(baseUrl, "Fresh after stop", messageId = "m-fresh")
                assertEquals(TtsPlaybackResult.COMPLETED, res3)
                assertEquals(1, playedPcms.size)
                assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), playedPcms[0])
                assertFalse(requestedBodies.any { it.contains("Queued before stop") })
            }
        }
    }

    @Test
    fun `cancelling speakPreview caller job halts hardware and releases mutex for next speakPreview without affecting abortEpoch`() = runTest {
        val wav1 = WavParserTest.buildStandardWav(24_000, 1, 16, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val wav2 = WavParserTest.buildStandardWav(24_000, 1, 16, byteArrayOf(0x55, 0x66, 0x77, 0x08))
        val wav3 = WavParserTest.buildStandardWav(24_000, 1, 16, byteArrayOf(0x09, 0x0A, 0x0B, 0x0C))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val bytes = when {
                    body.contains("Preview one") -> wav1
                    body.contains("Preview two") -> wav2
                    else -> wav3
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/wav")
                    .setBody(Buffer().write(bytes))
            }
        }

        val firstPlayEntered = CompletableDeferred<Unit>()
        val firstPlayGate = CompletableDeferred<Unit>()
        val firstStopNowCalled = CompletableDeferred<Unit>()
        val playedPcms = CopyOnWriteArrayList<ByteArray>()
        val playCount = AtomicInteger(0)

        val fakeTrackFactory = {
            val idx = playCount.getAndIncrement()
            object : PcmTrackPlayer {
                override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                    if (idx == 0) {
                        firstPlayEntered.complete(Unit)
                        firstPlayGate.await()
                    } else {
                        playedPcms += wav.pcmData
                    }
                }

                override fun stopNow() {
                    if (idx == 0) {
                        firstStopNowCalled.complete(Unit)
                        firstPlayGate.cancel(CancellationException("Preview 1 stopped"))
                    }
                }
            }
        }
        val systemStopCount = AtomicInteger(0)
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() {
                systemStopCount.incrementAndGet()
            }
            override fun shutdown() = Unit
        }

        val player = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = fakeTrackFactory,
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = Dispatchers.IO,
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
        )
        val baseUrl = server.url("/").toString().trimEnd('/')

        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                val preview1Job = launch {
                    player.speakPreview(baseUrl, "Preview one", TtsEngine.GEMINI_BRIDGE, voiceId = "Kore")
                }
                firstPlayEntered.await()

                val preview2Entered = CompletableDeferred<Unit>()
                val preview2Job = launch {
                    preview2Entered.complete(Unit)
                    player.speakPreview(baseUrl, "Preview two", TtsEngine.GEMINI_BRIDGE, voiceId = "Puck")
                }
                preview2Entered.await()
                delay(20L)

                val queuedSpeakEntered = CompletableDeferred<Unit>()
                val queuedSpeak = async {
                    queuedSpeakEntered.complete(Unit)
                    player.speakWithResult(baseUrl, "Queued speak", messageId = "m-queued")
                }
                queuedSpeakEntered.await()
                delay(20L)

                preview1Job.cancel()
                preview1Job.join()
                assertTrue(preview1Job.isCancelled)
                firstStopNowCalled.await()
                assertTrue(systemStopCount.get() >= 1)

                preview2Job.join()
                assertFalse(preview2Job.isCancelled)
                assertEquals(TtsPlaybackResult.COMPLETED, queuedSpeak.await())
                assertEquals(2, playedPcms.size)
                assertTrue(playedPcms.any { it.contentEquals(wav2.copyOfRange(44, wav2.size)) })
                assertTrue(playedPcms.any { it.contentEquals(wav3.copyOfRange(44, wav3.size)) })
                assertFalse(player.isSpeaking)
                assertFalse(player.isAudibleOutputActive)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `isAudibleOutputActive tracks only sink playback while onAudibleStart reports speech duration for cloud and on-device engines`() = runTest {
        val speechPcm = ByteArray(24_000) { (it and 0x7F).toByte() } // 24kHz mono 16-bit -> exact 500ms
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcm = speechPcm,
        )

        val playerRef = arrayOfNulls<TtsPlayer>(1)
        val prepObservedAudible = AtomicBoolean(true)
        val synthStartObservedAudible = AtomicBoolean(true)
        val synthEndObservedAudible = AtomicBoolean(true)
        val audibleDuringKeepAlive = CopyOnWriteArrayList<Boolean>()
        val cloudAudibleEvents = CopyOnWriteArrayList<Pair<String?, Long?>>()
        var playObservedAudible = false
        var playedWavSize = 0
        var playedAudibleSnapshot = emptyList<Pair<String?, Long?>>()

        val fakeClient = object : TtsClient(OpenChamberHttp.createClient()) {
            override suspend fun prepareTextForTtsDetailed(
                serverUrl: String,
                text: String,
                inputMode: TtsInputMode,
                sessionLabel: String?,
                detailLevel: SpokenDetailLevel,
            ): PreparedSpeechText {
                val p = playerRef[0]!!
                assertTrue(p.isSpeaking)
                prepObservedAudible.set(p.isAudibleOutputActive)
                return PreparedSpeechText(text = "Prepared cloud reply.", cacheHit = false)
            }

            override suspend fun synthesizeWithEngineDetailed(
                serverUrl: String,
                text: String,
                voice: String,
                engine: TtsEngine,
                kokoroSpeakerId: Int,
            ): SynthesizedSpeechAudio {
                val p = playerRef[0]!!
                assertTrue(p.isSpeaking)
                synthStartObservedAudible.set(p.isAudibleOutputActive)
                delay(600L)
                synthEndObservedAudible.set(p.isAudibleOutputActive)
                return SynthesizedSpeechAudio(
                    audio = TtsAudio(bytes = wavBytes, contentType = "audio/wav"),
                    cacheHit = false,
                )
            }
        }

        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
                val p = playerRef[0]!!
                assertTrue(p.isSpeaking)
                playObservedAudible = p.isAudibleOutputActive
                playedWavSize = wav.pcmData.size
                playedAudibleSnapshot = cloudAudibleEvents.toList()
            }

            override fun stopNow() = Unit
        }
        val fakeSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override fun stop() = Unit
            override fun shutdown() = Unit
        }

        val coordinator = AudioOutputCoordinator(clockElapsedMs = { testScheduler.currentTime })
        val cloudPlayer = TtsPlayer(
            ttsClient = fakeClient,
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.SUMMARIZED },
            audioOutputCoordinator = coordinator,
            latencyBridgeDelayMs = 450L,
            keepAliveSinkPlayer = { _, _ ->
                audibleDuringKeepAlive += playerRef[0]!!.isAudibleOutputActive
            },
        )
        playerRef[0] = cloudPlayer

        val cloudResult = cloudPlayer.speakWithResult(
            serverUrl = "http://localhost:4096",
            text = "Raw markdown reply",
            messageId = "msg-cloud",
            onAudibleStart = { id, durMs -> cloudAudibleEvents += id to durMs },
        )

        assertEquals(TtsPlaybackResult.COMPLETED, cloudResult)
        assertFalse(prepObservedAudible.get())
        assertFalse(synthStartObservedAudible.get())
        assertFalse(synthEndObservedAudible.get())
        assertTrue(audibleDuringKeepAlive.isNotEmpty() && audibleDuringKeepAlive.all { !it })
        assertTrue(playObservedAudible)
        assertEquals(speechPcm.size, playedWavSize) // keep-alive warmed sink -> 0ms cold preroll, exact 500ms duration
        assertEquals(listOf("msg-cloud" to 500L), playedAudibleSnapshot)
        assertEquals(listOf("msg-cloud" to 500L), cloudAudibleEvents.toList())
        assertFalse(cloudPlayer.isAudibleOutputActive)
        assertFalse(cloudPlayer.isSpeaking)

        // Verify cold sink without bridge prepends 160ms preroll while onAudibleStart still reports exact speech WAV duration (500ms)
        cloudAudibleEvents.clear()
        playObservedAudible = false
        val coldCoordinator = AudioOutputCoordinator(clockElapsedMs = { testScheduler.currentTime })
        val coldClient = object : TtsClient(OpenChamberHttp.createClient()) {
            override suspend fun prepareTextForTtsDetailed(
                serverUrl: String,
                text: String,
                inputMode: TtsInputMode,
                sessionLabel: String?,
                detailLevel: SpokenDetailLevel,
            ): PreparedSpeechText = PreparedSpeechText(text = text, cacheHit = true)

            override suspend fun synthesizeWithEngineDetailed(
                serverUrl: String,
                text: String,
                voice: String,
                engine: TtsEngine,
                kokoroSpeakerId: Int,
            ): SynthesizedSpeechAudio = SynthesizedSpeechAudio(
                audio = TtsAudio(bytes = wavBytes, contentType = "audio/wav"),
                cacheHit = true,
            )
        }
        val coldPlayer = TtsPlayer(
            ttsClient = coldClient,
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = fakeSystem,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
            audioOutputCoordinator = coldCoordinator,
        )
        playerRef[0] = coldPlayer
        val coldRes = coldPlayer.speakWithResult(
            serverUrl = "http://localhost:4096",
            text = "Cold reply.",
            messageId = "msg-cold",
            onAudibleStart = { id, durMs -> cloudAudibleEvents += id to durMs },
        )
        assertEquals(TtsPlaybackResult.COMPLETED, coldRes)
        assertTrue(playObservedAudible)
        assertEquals((24_000 * 160 / 1000) * 2 + speechPcm.size, playedWavSize)
        assertEquals(listOf("msg-cold" to 500L), playedAudibleSnapshot)
        assertEquals(listOf("msg-cold" to 500L), cloudAudibleEvents.toList())
        assertFalse(coldPlayer.isAudibleOutputActive)
        assertFalse(coldPlayer.isSpeaking)

        // Verify ANDROID_SYSTEM holds audibleOutputFlag false during init delay and fires onAudibleStart on playback start
        val sysPlayerRef = arrayOfNulls<TtsPlayer>(1)
        val sysAudibleEvents = CopyOnWriteArrayList<Pair<String?, Long?>>()
        var sysObservedFalseDuringInit = false
        var sysObservedTrueAfterStart = false
        val delayedSystemSpeaker = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit

            override suspend fun speak(
                text: String,
                preferredVoiceName: String?,
                useCommunicationUsage: Boolean,
                onPlaybackStarted: (() -> Unit)?,
            ) {
                val p = sysPlayerRef[0]!!
                assertTrue(p.isSpeaking)
                sysObservedFalseDuringInit = !p.isAudibleOutputActive && sysAudibleEvents.isEmpty()
                delay(50L)
                onPlaybackStarted?.invoke()
                sysObservedTrueAfterStart = p.isAudibleOutputActive
            }

            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val sysPlayer = TtsPlayer(
            ttsClient = coldClient,
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = delayedSystemSpeaker,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            pipelineProvider = { listOf(TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true)) },
            inputModeProvider = { TtsInputMode.RAW },
        )
        sysPlayerRef[0] = sysPlayer

        val sysResult = sysPlayer.speakWithResult(
            serverUrl = "http://localhost:4096",
            text = "Hello system",
            messageId = "msg-sys",
            onAudibleStart = { id, durMs -> sysAudibleEvents += id to durMs },
        )
        assertEquals(TtsPlaybackResult.COMPLETED, sysResult)
        assertTrue(sysObservedFalseDuringInit)
        assertTrue(sysObservedTrueAfterStart)
        assertEquals(listOf("msg-sys" to ("Hello system".length * 65L).coerceAtLeast(600L)), sysAudibleEvents.toList())
        assertFalse(sysPlayer.isAudibleOutputActive)
        assertFalse(sysPlayer.isSpeaking)

        // Short system utterance enforces >= 600ms floor
        sysAudibleEvents.clear()
        val shortSysRes = sysPlayer.speakWithResult(
            serverUrl = "http://localhost:4096",
            text = "Hi",
            messageId = "msg-short",
            onAudibleStart = { id, durMs -> sysAudibleEvents += id to durMs },
        )
        assertEquals(TtsPlaybackResult.COMPLETED, shortSysRes)
        assertEquals(listOf("msg-short" to 600L), sysAudibleEvents.toList())
    }

    @Test
    fun `fallback after premature onPlaybackStarted triggers onAudibleReset before succeeding engine plays`() = runTest {
        val events = CopyOnWriteArrayList<String>()
        val unavailableMlKit = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override suspend fun speak(
                text: String,
                preferredVoiceName: String?,
                useCommunicationUsage: Boolean,
                onPlaybackStarted: (() -> Unit)?,
            ) {
                throw TtsException(message = "ML Kit GenAI TTS unavailable")
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val lateFailMlKit = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override suspend fun speak(
                text: String,
                preferredVoiceName: String?,
                useCommunicationUsage: Boolean,
                onPlaybackStarted: (() -> Unit)?,
            ) {
                onPlaybackStarted?.invoke()
                throw TtsException(message = "ML Kit synthesis error mid-stream")
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val succeedingSystem = object : SystemTtsSpeaker {
            override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) = Unit
            override suspend fun speak(
                text: String,
                preferredVoiceName: String?,
                useCommunicationUsage: Boolean,
                onPlaybackStarted: (() -> Unit)?,
            ) {
                onPlaybackStarted?.invoke()
            }
            override fun stop() = Unit
            override fun shutdown() = Unit
        }
        val fakeTrack = object : PcmTrackPlayer {
            override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) = Unit
            override fun stopNow() = Unit
        }

        // 1. Unavailable ML Kit overriding 4-arg speak never fires onAudibleStart or onAudibleReset
        val p1 = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = succeedingSystem,
            mlKitTtsSpeaker = unavailableMlKit,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = {
                listOf(
                    TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
                    TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
                )
            },
            inputModeProvider = { TtsInputMode.RAW },
        )
        val r1 = p1.speakWithResult(
            serverUrl = "http://localhost:4096",
            text = "Hello",
            messageId = "m1",
            onAudibleStart = { id, _ -> events += "start:$id" },
            onAudibleReset = { id -> events += "reset:$id" },
        )
        assertEquals(TtsPlaybackResult.COMPLETED, r1)
        assertEquals(listOf("start:m1"), events.toList())

        // 2. Late mid-utterance failure after onPlaybackStarted fires onAudibleReset before fallback succeeds
        events.clear()
        val p2 = TtsPlayer(
            ttsClient = TtsClient(OpenChamberHttp.createClient()),
            pcmPlayerFactory = { fakeTrack },
            systemTtsSpeaker = succeedingSystem,
            mlKitTtsSpeaker = lateFailMlKit,
            ioDispatcher = Dispatchers.Unconfined,
            pipelineProvider = {
                listOf(
                    TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
                    TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
                )
            },
            inputModeProvider = { TtsInputMode.RAW },
        )
        val r2 = p2.speakWithResult(
            serverUrl = "http://localhost:4096",
            text = "Hello",
            messageId = "m2",
            onAudibleStart = { id, _ -> events += "start:$id" },
            onAudibleReset = { id -> events += "reset:$id" },
        )
        assertEquals(TtsPlaybackResult.COMPLETED, r2)
        assertEquals(listOf("start:m2", "reset:m2", "start:m2"), events.toList())
    }
}
