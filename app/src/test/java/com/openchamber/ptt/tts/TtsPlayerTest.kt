package com.openchamber.ptt.tts

import com.openchamber.ptt.audio.AudioOutputCoordinator
import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.atomic.AtomicBoolean

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
}
