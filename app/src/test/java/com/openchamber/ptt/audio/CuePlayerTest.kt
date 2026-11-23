package com.openchamber.ptt.audio

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.AudioCueMode
import com.openchamber.ptt.tts.RoleVoiceProfileMap
import com.openchamber.ptt.tts.SystemTtsSpeaker
import com.openchamber.ptt.tts.TtsCache
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.WavAudio
import com.openchamber.ptt.tts.WavParserTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CuePlayerTest {
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
    fun `VocalCuePackWarmer warms all 19 phrases with VoiceRole CUE distinct voice and skips cached phrases on re-run`() =
        runTest {
            val synthesizedVoices = CopyOnWriteArrayList<String>()
            val synthesizedTexts = CopyOnWriteArrayList<String>()
            val requestCount = AtomicInteger(0)
            val wavBytes = WavParserTest.buildStandardWav(
                sampleRate = 24_000,
                channels = 1,
                bitsPerSample = 16,
                pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    val body = request.body.readUtf8()
                    val voice = Regex(""""voice":"([^"]+)"""").find(body)?.groupValues?.get(1).orEmpty()
                    val text = Regex(""""text":"([^"]+)"""").find(body)?.groupValues?.get(1).orEmpty()
                    synthesizedVoices += voice
                    synthesizedTexts += text
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(wavBytes))
                }
            }

            val cache = TtsCache(tempFolder.newFolder("cue_warm_cache"))
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val client = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache)
                val warmer = VocalCuePackWarmer(
                    scope = this,
                    ttsClient = client,
                    ttsCache = cache,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { AudioCueMode.HYBRID },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.Unconfined,
                )

                assertEquals(19, VocalCueCatalog.ALL_PHRASES.size)
                warmer.warmIfNeeded()

                assertEquals(19, requestCount.get())
                assertTrue(synthesizedVoices.all { it == "Zephyr" })
                assertEquals(VocalCueCatalog.ALL_PHRASES.toSet(), synthesizedTexts.toSet())
                assertEquals(19, warmer.state.value.warmedCount)
                assertEquals(19, warmer.state.value.totalCount)
                assertFalse(warmer.state.value.isWarming)
                assertEquals(TtsEngine.GEMINI_BRIDGE, warmer.state.value.engine)
                assertEquals("Zephyr", warmer.state.value.voiceId)

                // Second run hits cache completely -> 0 extra HTTP requests
                warmer.warmIfNeeded()
                assertEquals(19, requestCount.get())
                assertEquals(19, warmer.state.value.warmedCount)
                assertFalse(warmer.state.value.isWarming)
            } finally {
                cache.close()
            }
        }

    @Test
    fun `live-mic guard forces non-verbal earcons for REC_START MAX_DURATION_WARNING and PERMISSION_NEEDED even in VOCAL mode`() =
        runTest {
            val wavBytes = WavParserTest.buildStandardWav(
                sampleRate = 24_000,
                channels = 1,
                bitsPerSample = 16,
                pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(wavBytes))
            }

            val cache = TtsCache(tempFolder.newFolder("cue_guard_cache"))
            val playedEarcons = CopyOnWriteArrayList<Earcon>()
            val playedCustomClips = CopyOnWriteArrayList<String?>()
            val earconLatch = CountDownLatch(3)
            val earconPlayer = EarconPlayer(
                context = null,
                clipObserver = { earcon, _ ->
                    playedEarcons += earcon
                    earconLatch.countDown()
                },
                customClipObserver = { tag, _, _ ->
                    playedCustomClips += tag
                },
            )
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val client = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache)
                val warmer = VocalCuePackWarmer(
                    scope = this,
                    ttsClient = client,
                    ttsCache = cache,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { AudioCueMode.VOCAL },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.Unconfined,
                )
                warmer.warmIfNeeded()

                val cuePlayer = CuePlayer(
                    scope = this,
                    earconPlayer = earconPlayer,
                    ttsCache = cache,
                    warmer = warmer,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { AudioCueMode.VOCAL },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.Unconfined,
                )

                cuePlayer.play(CueEvent.REC_START)
                cuePlayer.play(CueEvent.MAX_DURATION_WARNING)
                cuePlayer.play(CueEvent.PERMISSION_NEEDED)

                assertTrue(earconLatch.await(3, TimeUnit.SECONDS))
                assertEquals(
                    listOf(Earcon.REC_START, Earcon.STAGED_PAUSE, Earcon.PERMISSION_NEEDED),
                    playedEarcons.toList(),
                )
                assertTrue(playedCustomClips.isEmpty())
            } finally {
                earconPlayer.release()
                cache.close()
            }
        }

    @Test
    fun `HYBRID vs VOCAL vs TONES dispatch matrix routes tones vocal clips and silent cues accurately`() =
        runTest {
            val wavPcm = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
            val wavBytes = WavParserTest.buildStandardWav(
                sampleRate = 24_000,
                channels = 1,
                bitsPerSample = 16,
                pcm = wavPcm,
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(wavBytes))
            }

            val cache = TtsCache(tempFolder.newFolder("cue_matrix_cache"))
            val events = CopyOnWriteArrayList<String>()
            val latchRef = arrayOfNulls<CountDownLatch>(1)
            val earconPlayer = EarconPlayer(
                context = null,
                clipObserver = { earcon, _ ->
                    events += "earcon:${earcon.cueName}"
                    latchRef[0]?.countDown()
                },
                customClipObserver = { tag, clip, _ ->
                    assertArrayEquals(wavPcm, clip.pcmBytes)
                    events += "vocal:$tag"
                    latchRef[0]?.countDown()
                },
            )
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                var activeMode = AudioCueMode.HYBRID
                val client = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache)
                val warmer = VocalCuePackWarmer(
                    scope = this,
                    ttsClient = client,
                    ttsCache = cache,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { activeMode },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.Unconfined,
                )
                warmer.warmIfNeeded()

                val cuePlayer = CuePlayer(
                    scope = this,
                    earconPlayer = earconPlayer,
                    ttsCache = cache,
                    warmer = warmer,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { activeMode },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.Unconfined,
                )

                // 1. HYBRID: SEND_GRACE -> tone, QUICK_STOP_ACK -> vocal, EMPTY_SPEECH -> vocal, STILL_WORKING -> vocal
                activeMode = AudioCueMode.HYBRID
                latchRef[0] = CountDownLatch(4)
                cuePlayer.play(CueEvent.SEND_GRACE)
                cuePlayer.play(CueEvent.QUICK_STOP_ACK)
                cuePlayer.play(CueEvent.EMPTY_SPEECH)
                cuePlayer.play(CueEvent.STILL_WORKING)
                assertTrue(latchRef[0]!!.await(3, TimeUnit.SECONDS))
                assertEquals(
                    listOf(
                        "earcon:send_grace",
                        "vocal:quick_stop_ack",
                        "vocal:empty_speech",
                        "vocal:still_working",
                    ),
                    events.toList(),
                )
                events.clear()

                // 2. VOCAL: SEND_GRACE -> vocal, QUICK_STOP_ACK -> vocal, EMPTY_SPEECH -> vocal, STILL_WORKING -> vocal
                activeMode = AudioCueMode.VOCAL
                latchRef[0] = CountDownLatch(4)
                cuePlayer.play(CueEvent.SEND_GRACE)
                cuePlayer.play(CueEvent.QUICK_STOP_ACK)
                cuePlayer.play(CueEvent.EMPTY_SPEECH)
                cuePlayer.play(CueEvent.STILL_WORKING)
                assertTrue(latchRef[0]!!.await(3, TimeUnit.SECONDS))
                assertEquals(
                    listOf(
                        "vocal:send_grace",
                        "vocal:quick_stop_ack",
                        "vocal:empty_speech",
                        "vocal:still_working",
                    ),
                    events.toList(),
                )
                events.clear()

                // 3. TONES: QUICK_STOP_ACK & STILL_WORKING & REPLY_PREPARING & READING_REPLY silent,
                // SEND_GRACE -> earcon:send_grace, EMPTY_SPEECH -> earcon:cancel_discard
                activeMode = AudioCueMode.TONES
                latchRef[0] = CountDownLatch(2)
                cuePlayer.play(CueEvent.QUICK_STOP_ACK)
                cuePlayer.play(CueEvent.STILL_WORKING)
                cuePlayer.play(CueEvent.REPLY_PREPARING)
                cuePlayer.play(CueEvent.READING_REPLY)
                cuePlayer.play(CueEvent.SEND_GRACE)
                cuePlayer.play(CueEvent.EMPTY_SPEECH)
                assertTrue(latchRef[0]!!.await(3, TimeUnit.SECONDS))
                assertEquals(
                    listOf("earcon:send_grace", "earcon:cancel_discard"),
                    events.toList(),
                )
            } finally {
                earconPlayer.release()
                cache.close()
            }
        }

    @Test
    fun `cold cache miss falls back instantaneously to earcon and triggers background warming`() =
        runTest {
            val wavBytes = WavParserTest.buildStandardWav(
                sampleRate = 24_000,
                channels = 1,
                bitsPerSample = 16,
                pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            )
            val warmComplete = CountDownLatch(19)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    warmComplete.countDown()
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setBody(Buffer().write(wavBytes))
                }
            }

            val cache = TtsCache(tempFolder.newFolder("cue_miss_cache"))
            val earcons = CopyOnWriteArrayList<Earcon>()
            val customTags = CopyOnWriteArrayList<String?>()
            val fallbackLatch = CountDownLatch(1)
            val vocalLatch = CountDownLatch(1)
            val earconPlayer = EarconPlayer(
                context = null,
                clipObserver = { earcon, _ ->
                    earcons += earcon
                    fallbackLatch.countDown()
                },
                customClipObserver = { tag, _, _ ->
                    customTags += tag
                    vocalLatch.countDown()
                },
            )
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val client = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache)
                val warmer = VocalCuePackWarmer(
                    scope = this,
                    ttsClient = client,
                    ttsCache = cache,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { AudioCueMode.HYBRID },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.IO,
                )
                val cuePlayer = CuePlayer(
                    scope = this,
                    earconPlayer = earconPlayer,
                    ttsCache = cache,
                    warmer = warmer,
                    serverUrlProvider = { baseUrl },
                    cueModeProvider = { AudioCueMode.HYBRID },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    ioDispatcher = Dispatchers.IO,
                )

                // Cache is cold -> EMPTY_SPEECH falls back immediately to Earcon.CANCEL_DISCARD and triggers warmer
                cuePlayer.play(CueEvent.EMPTY_SPEECH)
                assertTrue(fallbackLatch.await(3, TimeUnit.SECONDS))
                assertEquals(listOf(Earcon.CANCEL_DISCARD), earcons.toList())
                assertTrue(customTags.isEmpty())

                // Once background warmer completes, playing EMPTY_SPEECH uses pre-cached vocal PcmClip
                assertTrue(warmComplete.await(5, TimeUnit.SECONDS))
                withContext(Dispatchers.Default) {
                    withTimeout(3_000L) {
                        warmer.state.first { !it.isWarming && it.warmedCount == 19 }
                    }
                }

                cuePlayer.play(CueEvent.EMPTY_SPEECH)
                assertTrue(vocalLatch.await(3, TimeUnit.SECONDS))
                assertEquals(listOf("empty_speech"), customTags.toList())
            } finally {
                earconPlayer.release()
                cache.close()
            }
        }

    @Test
    fun `CuePlayer decodes non-WAV MP3 cached vocal clip via encodedAudioDecoder`() = runTest {
        val fakeMp3Bytes = byteArrayOf(0x49, 0x44, 0x33, 0x01, 0x02, 0x03)
        val decodedPcm = byteArrayOf(0x21, 0x22, 0x23, 0x24)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(Buffer().write(fakeMp3Bytes))
        }

        val cache = TtsCache(tempFolder.newFolder("cue_mp3_cache"))
        val playedPcms = CopyOnWriteArrayList<ByteArray>()
        val decodeCount = AtomicInteger(0)
        val latch = CountDownLatch(2)
        val earconPlayer = EarconPlayer(
            context = null,
            customClipObserver = { _, clip, _ ->
                playedPcms += clip.pcmBytes
                latch.countDown()
            },
        )
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val client = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache)
            val warmer = VocalCuePackWarmer(
                scope = this,
                ttsClient = client,
                ttsCache = cache,
                serverUrlProvider = { baseUrl },
                cueModeProvider = { AudioCueMode.HYBRID },
                pipelineProvider = { listOf(TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true)) },
                roleProfilesProvider = { RoleVoiceProfileMap() },
                ioDispatcher = Dispatchers.Unconfined,
            )
            warmer.warmIfNeeded()

            val cuePlayer = CuePlayer(
                scope = this,
                earconPlayer = earconPlayer,
                ttsCache = cache,
                warmer = warmer,
                serverUrlProvider = { baseUrl },
                cueModeProvider = { AudioCueMode.HYBRID },
                pipelineProvider = { listOf(TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true)) },
                roleProfilesProvider = { RoleVoiceProfileMap() },
                encodedAudioDecoder = { _, _ ->
                    decodeCount.incrementAndGet()
                    WavAudio(
                        sampleRate = 24_000,
                        channels = 1,
                        bitsPerSample = 16,
                        pcmData = decodedPcm,
                        audioFormat = WavAudio.FORMAT_PCM,
                    )
                },
                ioDispatcher = Dispatchers.Unconfined,
            )

            // Play twice -> decodes once and hits memoized PcmClip on second play
            cuePlayer.play(CueEvent.STAGE_ORGANIZING)
            cuePlayer.play(CueEvent.STAGE_ORGANIZING)

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(1, decodeCount.get())
            assertEquals(2, playedPcms.size)
            assertArrayEquals(decodedPcm, playedPcms[0])
            assertArrayEquals(decodedPcm, playedPcms[1])

            // Clearing cache resets warmer state and decoded RAM cache cleanly
            cache.clear()
            cuePlayer.clearDecodedClipCache()
            warmer.resetAfterCacheClear()
            assertEquals(0, warmer.state.value.warmedCount)
            assertFalse(warmer.state.value.isWarming)
        } finally {
            earconPlayer.release()
            cache.close()
        }
    }

    @Test
    fun `on-device latency bridge cues play when TTS is not yet audible and coordinate drain with AudioOutputCoordinator`() =
        runTest {
            val cache = TtsCache(tempFolder.newFolder("cue_on_device_cache"))
            val earconPlayer = EarconPlayer(context = null)
            val speakStarted = CompletableDeferred<Unit>()
            val allowSpeakFinish = CompletableDeferred<Unit>()
            val spokenPhrases = CopyOnWriteArrayList<String>()
            var ttsAudible = false
            val coordinator = AudioOutputCoordinator(clockElapsedMs = { System.nanoTime() / 1_000_000L })

            val fakeSystemSpeaker = object : SystemTtsSpeaker {
                override val availableVoices: List<String> = listOf("sys-cue")
                override suspend fun speak(text: String, preferredVoiceName: String?, useCommunicationUsage: Boolean) {
                    spokenPhrases += text
                    speakStarted.complete(Unit)
                    allowSpeakFinish.await()
                }
                override fun stop() {
                    allowSpeakFinish.complete(Unit)
                }
                override fun shutdown() = Unit
            }

            try {
                val client = TtsClient(OpenChamberHttp.createClient(), ttsCache = cache)
                val warmer = VocalCuePackWarmer(
                    scope = this,
                    ttsClient = client,
                    ttsCache = cache,
                    serverUrlProvider = { null },
                    cueModeProvider = { AudioCueMode.HYBRID },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    availableSystemVoicesProvider = { listOf("sys-cue") },
                    ioDispatcher = Dispatchers.IO,
                )
                val cuePlayer = CuePlayer(
                    scope = this,
                    earconPlayer = earconPlayer,
                    ttsCache = cache,
                    warmer = warmer,
                    serverUrlProvider = { null },
                    cueModeProvider = { AudioCueMode.HYBRID },
                    pipelineProvider = { listOf(TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true)) },
                    roleProfilesProvider = { RoleVoiceProfileMap() },
                    availableSystemVoicesProvider = { listOf("sys-cue") },
                    isTtsAudibleProvider = { ttsAudible },
                    audioOutputCoordinator = coordinator,
                    systemTtsSpeaker = fakeSystemSpeaker,
                    ioDispatcher = Dispatchers.IO,
                )

                // Even while synthesis is busy (ttsAudible == false), REPLY_PREPARING speaks and arms AudioOutputCoordinator
                cuePlayer.play(CueEvent.REPLY_PREPARING)
                withContext(Dispatchers.Default) {
                    withTimeout(3_000L) { speakStarted.await() }
                }
                assertEquals(listOf("Summarizing."), spokenPhrases.toList())

                // Drain awaits active on-device cue until it stops
                val drainJob = async(Dispatchers.Default) {
                    coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = false)
                }
                allowSpeakFinish.complete(Unit)
                val preroll = withContext(Dispatchers.Default) {
                    withTimeout(3_000L) { drainJob.await() }
                }
                assertEquals(0, preroll)

                // When ttsAudible == true, on-device vocal cue is suppressed instead of talking over active speech
                ttsAudible = true
                cuePlayer.play(CueEvent.READING_REPLY)
                assertEquals(listOf("Summarizing."), spokenPhrases.toList())
            } finally {
                allowSpeakFinish.complete(Unit)
                earconPlayer.release()
                cache.close()
            }
        }
}
