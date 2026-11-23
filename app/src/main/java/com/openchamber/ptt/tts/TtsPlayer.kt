package com.openchamber.ptt.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.openchamber.ptt.audio.AudioCodecDecoder
import com.openchamber.ptt.audio.AudioOutputCoordinator
import com.openchamber.ptt.audio.AudioPreroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TtsPlaybackResult {
    COMPLETED,
    ABORTED,
}

internal interface PcmTrackPlayer {
    suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean = false)
    fun stopNow()
}

data class SystemTtsVoiceSummary(
    val name: String,
    val label: String,
    val localeTag: String,
    val isNetworkRequired: Boolean,
)

internal interface SystemTtsSpeaker {
    val availableVoices: List<String>
        get() = emptyList()
    suspend fun speak(
        text: String,
        preferredVoiceName: String? = null,
        useCommunicationUsage: Boolean = false,
    )
    suspend fun speak(
        text: String,
        preferredVoiceName: String? = null,
        useCommunicationUsage: Boolean = false,
        onPlaybackStarted: (() -> Unit)?,
    ) {
        onPlaybackStarted?.invoke()
        speak(text, preferredVoiceName, useCommunicationUsage)
    }
    fun stop()
    fun shutdown()
}

data class TtsSpeakStats(
    val inputMode: TtsInputMode,
    val prepareMs: Long,
    val rawChars: Int,
    val spokenChars: Int,
    val engine: TtsEngine,
    val role: VoiceRole = VoiceRole.AGENT,
    val voiceId: String? = null,
    val kind: String = "reply",
    val prerollMs: Int = 0,
    val textCacheHit: Boolean = false,
    val audioCacheHit: Boolean = false,
)

class TtsPlayer internal constructor(
    private val ttsClient: TtsClient,
    private val pcmPlayerFactory: () -> PcmTrackPlayer,
    internal val systemTtsSpeaker: SystemTtsSpeaker,
    internal val mlKitTtsSpeaker: SystemTtsSpeaker? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val useCommunicationUsage: () -> Boolean = { false },
    private val pipelineProvider: () -> List<TtsEngineOption> = { VoicePipelineDefaults.defaultTtsPipeline },
    private val roleProfilesProvider: () -> RoleVoiceProfileMap = { RoleVoiceProfileMap() },
    private val availableGeminiVoicesProvider: () -> List<String> = { emptyList() },
    private val availableMlKitVoicesProvider: () -> List<String> = {
        (mlKitTtsSpeaker as? MlKitTtsSpeaker)?.availableVoiceNames.orEmpty()
    },
    private val availableSystemVoicesProvider: () -> List<String> = {
        (systemTtsSpeaker as? AndroidTextToSpeechSpeaker)?.availableLocalVoiceNames.orEmpty()
    },
    private val inputModeProvider: () -> TtsInputMode = { TtsInputMode.SUMMARIZED },
    private val detailLevelProvider: () -> SpokenDetailLevel = { SpokenDetailLevel.BALANCED },
    private val encodedAudioDecoder: (ByteArray, String?) -> WavAudio = { bytes, contentType ->
        AudioCodecDecoder.decodeEncodedBytes(bytes, contentType).toWavAudio()
    },
    private val audioOutputCoordinator: AudioOutputCoordinator? = null,
    private val onStatusEvent: (String) -> Unit = {},
    private val onSpokenTextSynthesized: ((String) -> Unit)? = null,
    private val onSpeakStats: ((TtsSpeakStats) -> Unit)? = null,
    private val onLatencyBridgeNeeded: ((kind: String) -> Unit)? = null,
    private val latencyBridgeDelayMs: Long = DEFAULT_LATENCY_BRIDGE_DELAY_MS,
    private val keepAliveSinkPlayer: (suspend (WavAudio, Boolean) -> Unit)? = null,
    private val onSpokenTextSynthesizedForMessage: ((String?, String) -> Unit)? = null,
) {
    constructor(
        context: Context,
        ttsClient: TtsClient,
        useCommunicationUsage: () -> Boolean = { false },
        pipelineProvider: () -> List<TtsEngineOption> = { VoicePipelineDefaults.defaultTtsPipeline },
        roleProfilesProvider: () -> RoleVoiceProfileMap = { RoleVoiceProfileMap() },
        availableGeminiVoicesProvider: () -> List<String> = { emptyList() },
        inputModeProvider: () -> TtsInputMode = { TtsInputMode.SUMMARIZED },
        detailLevelProvider: () -> SpokenDetailLevel = { SpokenDetailLevel.BALANCED },
        audioOutputCoordinator: AudioOutputCoordinator? = null,
        onStatusEvent: (String) -> Unit = {},
        onSpokenTextSynthesized: ((String) -> Unit)? = null,
        onSpeakStats: ((TtsSpeakStats) -> Unit)? = null,
        onLatencyBridgeNeeded: ((kind: String) -> Unit)? = null,
        latencyBridgeDelayMs: Long = DEFAULT_LATENCY_BRIDGE_DELAY_MS,
        onSpokenTextSynthesizedForMessage: ((String?, String) -> Unit)? = null,
    ) : this(
        ttsClient = ttsClient,
        pcmPlayerFactory = { AndroidAudioTrackPlayer() },
        systemTtsSpeaker = AndroidTextToSpeechSpeaker(
            appContext = context.applicationContext,
        ),
        mlKitTtsSpeaker = MlKitTtsSpeaker(),
        ioDispatcher = Dispatchers.IO,
        useCommunicationUsage = useCommunicationUsage,
        pipelineProvider = pipelineProvider,
        roleProfilesProvider = roleProfilesProvider,
        availableGeminiVoicesProvider = availableGeminiVoicesProvider,
        inputModeProvider = inputModeProvider,
        detailLevelProvider = detailLevelProvider,
        audioOutputCoordinator = audioOutputCoordinator,
        onStatusEvent = onStatusEvent,
        onSpokenTextSynthesized = onSpokenTextSynthesized,
        onSpeakStats = onSpeakStats,
        onLatencyBridgeNeeded = onLatencyBridgeNeeded,
        latencyBridgeDelayMs = latencyBridgeDelayMs,
        keepAliveSinkPlayer = null,
        onSpokenTextSynthesizedForMessage = onSpokenTextSynthesizedForMessage,
    )

    private val lock = Any()
    private val playbackMutex = Mutex()
    private val abortEpoch = AtomicLong(0L)
    private val busyFlag = AtomicBoolean(false)
    private val audibleOutputFlag = AtomicBoolean(false)
    private var activeInnerJob: Job? = null
    private var activeTrackPlayer: PcmTrackPlayer? = null
    private var activeKeepAlivePlayer: PcmTrackPlayer? = null
    private var playbackGeneration: Long = 0L
    private var released: Boolean = false

    val isSpeaking: Boolean
        get() = busyFlag.get()

    val isAudibleOutputActive: Boolean
        get() = audibleOutputFlag.get()

    suspend fun speak(
        serverUrl: String,
        text: String,
        role: VoiceRole = VoiceRole.AGENT,
        sessionLabel: String? = null,
        inputModeOverride: TtsInputMode? = null,
        kind: String = "reply",
        notifySpokenSummary: Boolean = true,
        isManualRead: Boolean = false,
        messageId: String? = null,
        onAudibleStart: ((messageId: String?, estimatedDurationMs: Long?) -> Unit)? = null,
        onAudibleReset: ((messageId: String?) -> Unit)? = null,
    ) {
        speakWithResult(
            serverUrl = serverUrl,
            text = text,
            role = role,
            sessionLabel = sessionLabel,
            inputModeOverride = inputModeOverride,
            kind = kind,
            notifySpokenSummary = notifySpokenSummary,
            isManualRead = isManualRead,
            messageId = messageId,
            onAudibleStart = onAudibleStart,
            onAudibleReset = onAudibleReset,
        )
    }

    suspend fun speakWithResult(
        serverUrl: String,
        text: String,
        role: VoiceRole = VoiceRole.AGENT,
        sessionLabel: String? = null,
        inputModeOverride: TtsInputMode? = null,
        kind: String = "reply",
        notifySpokenSummary: Boolean = true,
        isManualRead: Boolean = false,
        messageId: String? = null,
        onAudibleStart: ((messageId: String?, estimatedDurationMs: Long?) -> Unit)? = null,
        onAudibleReset: ((messageId: String?) -> Unit)? = null,
    ): TtsPlaybackResult {
        val rawTrimmed = text.trim()
        if (rawTrimmed.isEmpty()) return TtsPlaybackResult.COMPLETED

        return runSerializedPlaybackSession(onAborted = { TtsPlaybackResult.ABORTED }) { myGeneration ->
            var bridgeJob: Job? = null
            try {
                val bridgeEligible = kind == KIND_REPLY || kind == BRIDGE_KIND_MANUAL_REPLY || isManualRead
                val bridgeKind = if (isManualRead || kind == BRIDGE_KIND_MANUAL_REPLY) {
                    BRIDGE_KIND_MANUAL_REPLY
                } else {
                    KIND_REPLY
                }
                if (bridgeEligible && (onLatencyBridgeNeeded != null || !useCommunicationUsage())) {
                    bridgeJob = launch(ioDispatcher) {
                        delay(latencyBridgeDelayMs)
                        ensureGenerationActive(myGeneration)
                        onLatencyBridgeNeeded?.invoke(bridgeKind)
                        val useCommForBridge = useCommunicationUsage()
                        audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(
                            useCommunicationUsage = useCommForBridge,
                        )
                        if (!useCommForBridge && isActive) {
                            val chunkWav = AudioPreroll.createDitheredSilenceWav(
                                durationMs = KEEP_ALIVE_CHUNK_MS.toInt(),
                                sampleRate = ON_DEVICE_PREROLL_SAMPLE_RATE_HZ,
                                channels = 1,
                                ditherPeakLsb = COMFORT_DITHER_PEAK_LSB,
                            )
                            var keepAliveElapsedMs = 0L
                            while (isActive && keepAliveElapsedMs < MAX_KEEP_ALIVE_MS) {
                                ensureGenerationActive(myGeneration)
                                if (useCommunicationUsage()) break
                                val t0 = System.nanoTime()
                                playKeepAliveChunk(chunkWav, myGeneration)
                                val wallMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
                                audioOutputCoordinator?.onSpeechPlaybackFinished()
                                val remainingVirtualMs = (KEEP_ALIVE_CHUNK_MS - wallMs).coerceAtLeast(0L)
                                if (remainingVirtualMs > 0L) {
                                    delay(remainingVirtualMs)
                                }
                                keepAliveElapsedMs += maxOf(wallMs, KEEP_ALIVE_CHUNK_MS)
                            }
                        }
                    }
                }

                suspend fun finishSynthesisBridge() {
                    val job = bridgeJob ?: return
                    bridgeJob = null
                    val keepAlive = synchronized(lock) {
                        activeKeepAlivePlayer.also { activeKeepAlivePlayer = null }
                    }
                    runCatching { keepAlive?.stopNow() }
                    job.cancelAndJoin()
                }

                val effectiveMode = inputModeOverride ?: inputModeProvider()
                val prepareStartMs = System.currentTimeMillis()
                val preparedText = ttsClient.prepareTextForTtsDetailed(
                    serverUrl = serverUrl,
                    text = rawTrimmed,
                    inputMode = effectiveMode,
                    sessionLabel = sessionLabel,
                    detailLevel = detailLevelProvider(),
                )
                val spokenText = preparedText.text
                    .trim()
                    .ifEmpty { rawTrimmed }
                val textCacheHit = preparedText.cacheHit
                val prepareElapsedMs = (System.currentTimeMillis() - prepareStartMs).coerceAtLeast(0L)

                ensureGenerationActive(myGeneration)

                val useComm = useCommunicationUsage()
                val profiles = roleProfilesProvider()
                val enabledPipeline = VoiceRoleResolver.resolvePipelineForRole(
                    basePipeline = pipelineProvider(),
                    roleAssignment = profiles[role],
                )
                if (enabledPipeline.isEmpty()) {
                    throw TtsException(message = "All TTS engines are disabled in Settings")
                }

                val geminiVoicePool = availableGeminiVoicesProvider()
                val mlKitVoicePool = availableMlKitVoicesProvider()
                val systemVoicePool = availableSystemVoicesProvider()

                val failures = mutableListOf<String>()
                var lastStatusCode: Int? = null
                var played = false
                var usedEngine: TtsEngine? = null
                var usedVoiceId: String? = null
                var usedPrerollMs = 0
                var usedAudioCacheHit = false

                for (option in enabledPipeline) {
                    ensureGenerationActive(myGeneration)
                    val engine = option.engine
                    val resolvedVoice = VoiceRoleResolver.resolveVoiceForEngine(
                        engine = engine,
                        role = role,
                        profiles = profiles,
                        availableGeminiVoices = geminiVoicePool,
                        availableMlKitVoices = mlKitVoicePool,
                        availableSystemVoices = systemVoicePool,
                    )
                    var audibleStartedForAttempt = false
                    val success = try {
                        when (engine) {
                            TtsEngine.MLKIT_GENAI -> {
                                val speaker = mlKitTtsSpeaker
                                    ?: throw TtsException(message = "ML Kit GenAI TTS speaker not configured")
                                ensureGenerationActive(myGeneration)
                                finishSynthesisBridge()
                                val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                                ensureGenerationActive(myGeneration)
                                val routeLabel = if (useComm) "SCO" else "Media"
                                onStatusEvent("ML Kit GenAI TTS ($routeLabel)")
                                if (notifySpokenSummary) {
                                    onSpokenTextSynthesized?.invoke(spokenText)
                                    onSpokenTextSynthesizedForMessage?.invoke(messageId, spokenText)
                                }
                                val estimatedMs = (spokenText.length * 65L).coerceAtLeast(600L)
                                var playbackStarted = false
                                try {
                                    audioOutputCoordinator?.onSpeechPlaybackStarted()
                                    playbackStarted = true
                                    if (prerollMs > 0) {
                                        val wakeupWav = AudioPreroll.createDitheredSilenceWav(
                                            durationMs = prerollMs,
                                            sampleRate = ON_DEVICE_PREROLL_SAMPLE_RATE_HZ,
                                            channels = 1,
                                        )
                                        playParsedWav(wakeupWav, myGeneration, useComm)
                                        ensureGenerationActive(myGeneration)
                                    }
                                    speaker.speak(
                                        text = spokenText,
                                        preferredVoiceName = resolvedVoice.voiceId,
                                        useCommunicationUsage = useComm,
                                        onPlaybackStarted = {
                                            if (tryMarkAudibleOutputStarted(myGeneration)) {
                                                audibleStartedForAttempt = true
                                                onAudibleStart?.invoke(messageId, estimatedMs)
                                            }
                                        },
                                    )
                                } finally {
                                    audibleOutputFlag.set(false)
                                    if (playbackStarted) {
                                        audioOutputCoordinator?.onSpeechPlaybackFinished()
                                    }
                                }
                                usedVoiceId = resolvedVoice.voiceId
                                usedPrerollMs = prerollMs
                                usedAudioCacheHit = false
                                true
                            }
                            TtsEngine.ANDROID_SYSTEM -> {
                                ensureGenerationActive(myGeneration)
                                finishSynthesisBridge()
                                val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                                ensureGenerationActive(myGeneration)
                                val routeLabel = if (useComm) "SCO" else "Media"
                                onStatusEvent("Android System TTS ($routeLabel)")
                                if (notifySpokenSummary) {
                                    onSpokenTextSynthesized?.invoke(spokenText)
                                    onSpokenTextSynthesizedForMessage?.invoke(messageId, spokenText)
                                }
                                val estimatedMs = (spokenText.length * 65L).coerceAtLeast(600L)
                                var playbackStarted = false
                                try {
                                    audioOutputCoordinator?.onSpeechPlaybackStarted()
                                    playbackStarted = true
                                    if (prerollMs > 0) {
                                        val wakeupWav = AudioPreroll.createDitheredSilenceWav(
                                            durationMs = prerollMs,
                                            sampleRate = ON_DEVICE_PREROLL_SAMPLE_RATE_HZ,
                                            channels = 1,
                                        )
                                        playParsedWav(wakeupWav, myGeneration, useComm)
                                        ensureGenerationActive(myGeneration)
                                    }
                                    systemTtsSpeaker.speak(
                                        text = spokenText,
                                        preferredVoiceName = resolvedVoice.voiceId,
                                        useCommunicationUsage = useComm,
                                        onPlaybackStarted = {
                                            if (tryMarkAudibleOutputStarted(myGeneration)) {
                                                audibleStartedForAttempt = true
                                                onAudibleStart?.invoke(messageId, estimatedMs)
                                            }
                                        },
                                    )
                                } finally {
                                    audibleOutputFlag.set(false)
                                    if (playbackStarted) {
                                        audioOutputCoordinator?.onSpeechPlaybackFinished()
                                    }
                                }
                                usedVoiceId = resolvedVoice.voiceId
                                usedPrerollMs = prerollMs
                                usedAudioCacheHit = false
                                true
                            }
                            TtsEngine.GEMINI_BRIDGE,
                            TtsEngine.LOCAL_KOKORO,
                            TtsEngine.OPENAI_CLOUD,
                            -> {
                                val engineVoice = resolvedVoice.voiceId ?: when (engine) {
                                    TtsEngine.OPENAI_CLOUD -> VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
                                    else -> TtsClient.DEFAULT_TTS_VOICE
                                }
                                val synthesized = ttsClient.synthesizeWithEngineDetailed(
                                    serverUrl = serverUrl,
                                    text = spokenText,
                                    voice = engineVoice,
                                    engine = engine,
                                    kokoroSpeakerId = resolvedVoice.kokoroSpeakerId,
                                )
                                val audio = synthesized.audio
                                ensureGenerationActive(myGeneration)
                                val wav = if (isRiffWav(audio.bytes)) {
                                    WavParser.parse(audio.bytes)
                                } else {
                                    withContext(ioDispatcher) {
                                        ensureGenerationActive(myGeneration)
                                        encodedAudioDecoder(audio.bytes, audio.contentType)
                                    }
                                }
                                ensureGenerationActive(myGeneration)
                                finishSynthesisBridge()
                                val bytesPerSample = (wav.bitsPerSample / 8).coerceAtLeast(1)
                                val frameBytes = (wav.channels * bytesPerSample).coerceAtLeast(1)
                                val speechDurationMs = if (wav.sampleRate > 0) {
                                    (((wav.pcmData.size / frameBytes).toLong() * 1000L) / wav.sampleRate.toLong()).coerceAtLeast(1L)
                                } else {
                                    1L
                                }
                                val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                                ensureGenerationActive(myGeneration)
                                val playableWav = AudioPreroll.prependDitheredPreroll(wav, prerollMs)
                                if (notifySpokenSummary) {
                                    onSpokenTextSynthesized?.invoke(spokenText)
                                    onSpokenTextSynthesizedForMessage?.invoke(messageId, spokenText)
                                }
                                val routeLabel = if (useComm) "SCO" else "Media"
                                val durSec = if (wav.sampleRate > 0 && wav.channels > 0 && wav.bitsPerSample > 0) {
                                    wav.pcmData.size.toDouble() / (wav.sampleRate * wav.channels * (wav.bitsPerSample / 8))
                                } else {
                                    0.0
                                }
                                onStatusEvent("${engine.title} (${wav.sampleRate}Hz, ${"%.1f".format(durSec)}s, $routeLabel)")
                                var playbackStarted = false
                                try {
                                    audioOutputCoordinator?.onSpeechPlaybackStarted()
                                    playbackStarted = true
                                    if (!tryMarkAudibleOutputStarted(myGeneration)) {
                                        throw CancellationException("TTS stopped")
                                    }
                                    audibleStartedForAttempt = true
                                    onAudibleStart?.invoke(messageId, speechDurationMs)
                                    playParsedWav(playableWav, myGeneration, useComm)
                                } finally {
                                    audibleOutputFlag.set(false)
                                    if (playbackStarted) {
                                        audioOutputCoordinator?.onSpeechPlaybackFinished()
                                    }
                                }
                                usedVoiceId = resolvedVoice.voiceId
                                usedPrerollMs = prerollMs
                                usedAudioCacheHit = synthesized.cacheHit
                                true
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        if (audibleStartedForAttempt) {
                            onAudibleReset?.invoke(messageId)
                        }
                        val code = (t as? TtsException)?.statusCode
                        if (code == 401 || lastStatusCode == null) {
                            lastStatusCode = code ?: lastStatusCode
                        }
                        val msg = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
                        failures += "${engine.title}: $msg"
                        runCatching { Log.w(TAG, "TTS engine ${engine.title} failed", t) }
                        false
                    }
                    if (success) {
                        played = true
                        usedEngine = engine
                        break
                    }
                }

                ensureGenerationActive(myGeneration)
                finishSynthesisBridge()

                if (!played) {
                    throw TtsException(
                        statusCode = lastStatusCode,
                        message = "TTS failed (${failures.joinToString("; ")})",
                    )
                }
                if (usedEngine != null) {
                    onSpeakStats?.invoke(
                        TtsSpeakStats(
                            inputMode = effectiveMode,
                            prepareMs = prepareElapsedMs,
                            rawChars = rawTrimmed.length,
                            spokenChars = spokenText.length,
                            engine = usedEngine,
                            role = role,
                            voiceId = usedVoiceId,
                            kind = kind,
                            prerollMs = usedPrerollMs,
                            textCacheHit = textCacheHit,
                            audioCacheHit = usedAudioCacheHit,
                        ),
                    )
                }
                TtsPlaybackResult.COMPLETED
            } finally {
                cancelBridgeJob(bridgeJob)
            }
        }
    }

    suspend fun speakPreview(
        serverUrl: String,
        text: String,
        engine: TtsEngine,
        voiceId: String? = null,
        kokoroSpeakerId: Int = TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
        role: VoiceRole = VoiceRole.AGENT,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        runSerializedPlaybackSession(onAborted = {}) { myGeneration ->
            val useComm = useCommunicationUsage()
            var usedVoiceId: String? = voiceId?.trim()?.takeIf { it.isNotEmpty() }
            var usedPrerollMs = 0
            var usedAudioCacheHit = false

            try {
                when (engine) {
                    TtsEngine.MLKIT_GENAI -> {
                        val speaker = mlKitTtsSpeaker
                            ?: throw TtsException(message = "ML Kit GenAI TTS speaker not configured")
                        ensureGenerationActive(myGeneration)
                        val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                        ensureGenerationActive(myGeneration)
                        var playbackStarted = false
                        try {
                            audioOutputCoordinator?.onSpeechPlaybackStarted()
                            playbackStarted = true
                            if (prerollMs > 0) {
                                val wakeupWav = AudioPreroll.createDitheredSilenceWav(
                                    durationMs = prerollMs,
                                    sampleRate = ON_DEVICE_PREROLL_SAMPLE_RATE_HZ,
                                    channels = 1,
                                )
                                playParsedWav(wakeupWav, myGeneration, useComm)
                                ensureGenerationActive(myGeneration)
                            }
                            speaker.speak(
                                text = trimmed,
                                preferredVoiceName = usedVoiceId,
                                useCommunicationUsage = useComm,
                                onPlaybackStarted = {
                                    tryMarkAudibleOutputStarted(myGeneration)
                                },
                            )
                        } finally {
                            audibleOutputFlag.set(false)
                            if (playbackStarted) {
                                audioOutputCoordinator?.onSpeechPlaybackFinished()
                            }
                        }
                        usedPrerollMs = prerollMs
                    }

                    TtsEngine.ANDROID_SYSTEM -> {
                        ensureGenerationActive(myGeneration)
                        val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                        ensureGenerationActive(myGeneration)
                        var playbackStarted = false
                        try {
                            audioOutputCoordinator?.onSpeechPlaybackStarted()
                            playbackStarted = true
                            if (prerollMs > 0) {
                                val wakeupWav = AudioPreroll.createDitheredSilenceWav(
                                    durationMs = prerollMs,
                                    sampleRate = ON_DEVICE_PREROLL_SAMPLE_RATE_HZ,
                                    channels = 1,
                                )
                                playParsedWav(wakeupWav, myGeneration, useComm)
                                ensureGenerationActive(myGeneration)
                            }
                            systemTtsSpeaker.speak(
                                text = trimmed,
                                preferredVoiceName = usedVoiceId,
                                useCommunicationUsage = useComm,
                                onPlaybackStarted = {
                                    tryMarkAudibleOutputStarted(myGeneration)
                                },
                            )
                        } finally {
                            audibleOutputFlag.set(false)
                            if (playbackStarted) {
                                audioOutputCoordinator?.onSpeechPlaybackFinished()
                            }
                        }
                        usedPrerollMs = prerollMs
                    }

                    TtsEngine.GEMINI_BRIDGE,
                    TtsEngine.LOCAL_KOKORO,
                    TtsEngine.OPENAI_CLOUD,
                    -> {
                        if (serverUrl.isBlank()) {
                            throw TtsException(message = "Connect to an OpenChamber server to preview ${engine.title}")
                        }
                        val engineVoice = usedVoiceId ?: when (engine) {
                            TtsEngine.OPENAI_CLOUD -> VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
                            else -> TtsClient.DEFAULT_TTS_VOICE
                        }
                        val synthesized = ttsClient.synthesizeWithEngineDetailed(
                            serverUrl = serverUrl,
                            text = trimmed,
                            voice = engineVoice,
                            engine = engine,
                            kokoroSpeakerId = kokoroSpeakerId,
                        )
                        val audio = synthesized.audio
                        ensureGenerationActive(myGeneration)
                        val wav = if (isRiffWav(audio.bytes)) {
                            WavParser.parse(audio.bytes)
                        } else {
                            withContext(ioDispatcher) {
                                ensureGenerationActive(myGeneration)
                                encodedAudioDecoder(audio.bytes, audio.contentType)
                            }
                        }
                        ensureGenerationActive(myGeneration)
                        val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                        ensureGenerationActive(myGeneration)
                        val playableWav = AudioPreroll.prependDitheredPreroll(wav, prerollMs)
                        var playbackStarted = false
                        try {
                            audioOutputCoordinator?.onSpeechPlaybackStarted()
                            playbackStarted = true
                            if (!tryMarkAudibleOutputStarted(myGeneration)) {
                                throw CancellationException("TTS stopped")
                            }
                            playParsedWav(playableWav, myGeneration, useComm)
                        } finally {
                            audibleOutputFlag.set(false)
                            if (playbackStarted) {
                                audioOutputCoordinator?.onSpeechPlaybackFinished()
                            }
                        }
                        usedVoiceId = if (engine == TtsEngine.LOCAL_KOKORO) null else engineVoice
                        usedPrerollMs = prerollMs
                        usedAudioCacheHit = synthesized.cacheHit
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ensureGenerationActive(myGeneration)
                throw t
            }

            onSpeakStats?.invoke(
                TtsSpeakStats(
                    inputMode = TtsInputMode.RAW,
                    prepareMs = 0L,
                    rawChars = trimmed.length,
                    spokenChars = trimmed.length,
                    engine = engine,
                    role = role,
                    voiceId = usedVoiceId,
                    kind = KIND_PREVIEW,
                    prerollMs = usedPrerollMs,
                    textCacheHit = false,
                    audioCacheHit = usedAudioCacheHit,
                ),
            )
        }
    }

    suspend fun playWav(wavBytes: ByteArray) {
        val wav = WavParser.parse(wavBytes)
        runSerializedPlaybackSession(onAborted = {}) { myGeneration ->
            var playbackStarted = false
            try {
                val useComm = useCommunicationUsage()
                ensureGenerationActive(myGeneration)
                val prerollMs = audioOutputCoordinator?.awaitCueDrainAndResolvePrerollMs(useComm) ?: 0
                ensureGenerationActive(myGeneration)
                val playableWav = AudioPreroll.prependDitheredPreroll(wav, prerollMs)
                audioOutputCoordinator?.onSpeechPlaybackStarted()
                playbackStarted = true
                if (!tryMarkAudibleOutputStarted(myGeneration)) {
                    throw CancellationException("TTS stopped")
                }
                playParsedWav(playableWav, myGeneration, useComm)
            } finally {
                audibleOutputFlag.set(false)
                if (playbackStarted) {
                    audioOutputCoordinator?.onSpeechPlaybackFinished()
                }
            }
        }
    }

    fun stop() {
        val innerToCancel: Job?
        val trackToStop: PcmTrackPlayer?
        val keepAliveToStop: PcmTrackPlayer?
        synchronized(lock) {
            abortEpoch.incrementAndGet()
            playbackGeneration += 1L
            busyFlag.set(false)
            audibleOutputFlag.set(false)
            innerToCancel = activeInnerJob
            activeInnerJob = null
            trackToStop = activeTrackPlayer
            activeTrackPlayer = null
            keepAliveToStop = activeKeepAlivePlayer
            activeKeepAlivePlayer = null
        }
        runCatching { keepAliveToStop?.stopNow() }
        runCatching { trackToStop?.stopNow() }
        runCatching { mlKitTtsSpeaker?.stop() }
        runCatching { systemTtsSpeaker.stop() }
        innerToCancel?.cancel(CancellationException("TTS stopped by barge-in"))
    }

    fun release() {
        synchronized(lock) {
            released = true
        }
        stop()
        runCatching { mlKitTtsSpeaker?.shutdown() }
        runCatching { systemTtsSpeaker.shutdown() }
    }

    private suspend fun <T> runSerializedPlaybackSession(
        onAborted: () -> T,
        block: suspend CoroutineScope.(myGeneration: Long) -> T,
    ): T {
        val entryEpoch = abortEpoch.get()
        return playbackMutex.withLock {
            val myGeneration = synchronized(lock) {
                if (abortEpoch.get() != entryEpoch) {
                    -1L
                } else if (released) {
                    -2L
                } else {
                    playbackGeneration += 1L
                    busyFlag.set(true)
                    playbackGeneration
                }
            }
            if (myGeneration == -1L) {
                return@withLock onAborted()
            }
            if (myGeneration == -2L) {
                throw CancellationException("TtsPlayer has been released")
            }

            val callerJob = currentCoroutineContext().job
            val callerCancelHandle = callerJob.invokeOnCompletion { cause ->
                if (cause != null) {
                    abortGenerationHardwareAndInnerJob(myGeneration)
                }
            }

            var innerResult: T? = null
            var hasResult = false
            var aborted = false

            try {
                try {
                    coroutineScope {
                        val inner = launch(start = CoroutineStart.LAZY) {
                            innerResult = block(myGeneration)
                            hasResult = true
                        }
                        val registered = synchronized(lock) {
                            if (!released &&
                                playbackGeneration == myGeneration &&
                                abortEpoch.get() == entryEpoch &&
                                callerJob.isActive
                            ) {
                                activeInnerJob = inner
                                true
                            } else {
                                false
                            }
                        }
                        if (!registered) {
                            inner.cancel(CancellationException("TTS stopped"))
                            aborted = true
                        } else {
                            inner.start()
                            inner.join()
                            if (inner.isCancelled) {
                                aborted = true
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    if (!currentCoroutineContext().isActive) {
                        abortGenerationHardwareAndInnerJob(myGeneration)
                        throw ce
                    }
                    aborted = true
                }

                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("TTS caller cancelled")
                }
                if (aborted || !hasResult) {
                    return@withLock onAborted()
                }
                @Suppress("UNCHECKED_CAST")
                return@withLock innerResult as T
            } finally {
                callerCancelHandle.dispose()
                audibleOutputFlag.set(false)
                busyFlag.set(false)
                endPlaybackSession(myGeneration)
            }
        }
    }

    private fun abortGenerationHardwareAndInnerJob(expectedGeneration: Long) {
        val innerToCancel: Job?
        val trackToStop: PcmTrackPlayer?
        val keepAliveToStop: PcmTrackPlayer?
        synchronized(lock) {
            if (playbackGeneration != expectedGeneration) {
                return
            }
            playbackGeneration += 1L
            busyFlag.set(false)
            audibleOutputFlag.set(false)
            innerToCancel = activeInnerJob
            activeInnerJob = null
            trackToStop = activeTrackPlayer
            activeTrackPlayer = null
            keepAliveToStop = activeKeepAlivePlayer
            activeKeepAlivePlayer = null
        }
        runCatching { keepAliveToStop?.stopNow() }
        runCatching { trackToStop?.stopNow() }
        runCatching { mlKitTtsSpeaker?.stop() }
        runCatching { systemTtsSpeaker.stop() }
        innerToCancel?.cancel(CancellationException("TTS caller cancelled"))
    }

    private fun tryMarkAudibleOutputStarted(expectedGeneration: Long): Boolean =
        synchronized(lock) {
            if (released || playbackGeneration != expectedGeneration) {
                false
            } else {
                audibleOutputFlag.set(true)
                true
            }
        }

    private fun cancelBridgeJob(bridgeJob: Job?) {
        val keepAlive = synchronized(lock) {
            activeKeepAlivePlayer.also { activeKeepAlivePlayer = null }
        }
        runCatching { keepAlive?.stopNow() }
        bridgeJob?.cancel()
    }

    private suspend fun playKeepAliveChunk(
        wav: WavAudio,
        myGeneration: Long,
    ) {
        if (wav.pcmData.isEmpty()) return
        val custom = keepAliveSinkPlayer
        if (custom != null) {
            custom.invoke(wav, false)
            return
        }
        val player = pcmPlayerFactory()
        synchronized(lock) {
            if (released || playbackGeneration != myGeneration) {
                throw CancellationException("TTS stopped")
            }
            activeKeepAlivePlayer = player
        }
        try {
            withContext(ioDispatcher) {
                player.play(wav, false)
            }
        } catch (ce: CancellationException) {
            runCatching { player.stopNow() }
            throw ce
        } finally {
            synchronized(lock) {
                if (activeKeepAlivePlayer === player) {
                    activeKeepAlivePlayer = null
                }
            }
        }
    }

    private suspend fun playParsedWav(
        wav: WavAudio,
        myGeneration: Long,
        useComm: Boolean,
    ) {
        if (wav.pcmData.isEmpty()) return

        val player = pcmPlayerFactory()
        synchronized(lock) {
            if (released || playbackGeneration != myGeneration) {
                throw CancellationException("TTS stopped")
            }
            activeTrackPlayer = player
        }
        try {
            withContext(ioDispatcher) {
                player.play(wav, useComm)
            }
        } catch (ce: CancellationException) {
            runCatching { player.stopNow() }
            throw ce
        } finally {
            synchronized(lock) {
                if (activeTrackPlayer === player) {
                    activeTrackPlayer = null
                }
            }
        }
    }

    private fun ensureGenerationActive(expectedGeneration: Long) {
        val active = synchronized(lock) {
            !released && playbackGeneration == expectedGeneration
        }
        if (!active) {
            throw CancellationException("TTS stopped")
        }
    }

    private fun endPlaybackSession(myGeneration: Long) {
        synchronized(lock) {
            if (playbackGeneration == myGeneration) {
                activeInnerJob = null
                activeTrackPlayer = null
                activeKeepAlivePlayer = null
            }
        }
    }
    companion object {
        private const val TAG = "TtsPlayer"
        private const val ON_DEVICE_PREROLL_SAMPLE_RATE_HZ = 24_000
        const val DEFAULT_LATENCY_BRIDGE_DELAY_MS = 450L
        const val KIND_REPLY = "reply"
        const val KIND_PREVIEW = "preview"
        const val BRIDGE_KIND_MANUAL_REPLY = "manual_reply"
        internal const val KEEP_ALIVE_CHUNK_MS = 200L
        internal const val MAX_KEEP_ALIVE_MS = 12_000L
        internal const val COMFORT_DITHER_PEAK_LSB = 18

        suspend fun querySystemTtsVoices(context: Context): List<SystemTtsVoiceSummary> =
            withContext(Dispatchers.IO) {
                val appCtx = context.applicationContext
                val ready = CompletableDeferred<Boolean>()
                var engine: TextToSpeech? = null
                try {
                    engine = TextToSpeech(appCtx) { status ->
                        ready.complete(status == TextToSpeech.SUCCESS)
                    }
                    val ok = withTimeoutOrNull(5_000L) { ready.await() } ?: false
                    if (!ok) return@withContext emptyList()
                    val defaultLang = java.util.Locale.getDefault().language
                    val rawVoices = runCatching { engine.voices }.getOrNull().orEmpty()
                    rawVoices
                        .filter { v ->
                            v.locale != null &&
                                (v.locale.language.equals(defaultLang, ignoreCase = true) ||
                                    v.locale.language.equals("en", ignoreCase = true))
                        }
                        .sortedWith(
                            compareBy<android.speech.tts.Voice>(
                                { !it.locale.language.equals(defaultLang, ignoreCase = true) },
                                { it.isNetworkConnectionRequired },
                                { it.name },
                            ),
                        )
                        .distinctBy { it.name }
                        .take(30)
                        .map { v ->
                            SystemTtsVoiceSummary(
                                name = v.name,
                                label = formatSystemVoiceLabel(v),
                                localeTag = v.locale?.toLanguageTag().orEmpty(),
                                isNetworkRequired = v.isNetworkConnectionRequired,
                            )
                        }
                } finally {
                    runCatching { engine?.shutdown() }
                }
            }

        internal fun formatSystemVoiceLabel(voice: android.speech.tts.Voice): String {
            val tag = voice.locale?.toLanguageTag().orEmpty()
            val tail = voice.name.substringAfterLast('-').ifBlank { voice.name }
            return if (tag.isNotBlank()) "$tail ($tag)" else voice.name
        }

        internal fun isRiffWav(bytes: ByteArray): Boolean =
            bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() &&
                bytes[11] == 'E'.code.toByte()
    }
}

private class AndroidAudioTrackPlayer : PcmTrackPlayer {
    private val stopped = AtomicBoolean(false)
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null

    override suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean) {
        if (stopped.get() || wav.pcmData.isEmpty()) return

        val channelMask = when (wav.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw WavParseException("Unsupported channel count for AudioTrack: ${wav.channels}")
        }

        val encoding = resolveAudioEncoding(wav)
        val bytesPerSample = wav.bitsPerSample / 8
        val frameSize = (wav.channels * bytesPerSample).coerceAtLeast(1)

        val minBufferSize = AudioTrack.getMinBufferSize(wav.sampleRate, channelMask, encoding)
        if (minBufferSize <= 0) {
            throw IllegalStateException(
                "Invalid AudioTrack minBufferSize ($minBufferSize) for ${wav.sampleRate}Hz/${wav.channels}ch/${wav.bitsPerSample}bit",
            )
        }

        val targetBufferSize = maxOf(minBufferSize * 2, WRITE_CHUNK_BYTES)
        val alignedBufferSize = ((targetBufferSize + frameSize - 1) / frameSize) * frameSize

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (useCommunicationUsage) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(wav.sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(alignedBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            throw IllegalStateException("AudioTrack failed to initialize (state=${track.state})")
        }

        synchronized(trackLock) {
            if (stopped.get()) {
                runCatching { track.release() }
                throw CancellationException("TTS stopped")
            }
            audioTrack = track
        }

        try {
            track.play()

            val pcm = wav.pcmData
            val alignedChunkSize = ((WRITE_CHUNK_BYTES / frameSize).coerceAtLeast(1)) * frameSize
            var offset = 0

            while (offset < pcm.size && !stopped.get() && currentCoroutineContext().isActive) {
                val length = minOf(alignedChunkSize, pcm.size - offset)
                val written = track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    if (stopped.get()) {
                        throw CancellationException("TTS stopped")
                    }
                    throw IllegalStateException("AudioTrack.write failed with code $written")
                }
                if (written == 0) {
                    delay(10L)
                } else {
                    offset += written
                }
            }

            if (stopped.get() || !currentCoroutineContext().isActive) {
                throw CancellationException("TTS stopped")
            }

            waitForDrain(track, totalFrames = pcm.size / frameSize, sampleRate = wav.sampleRate)
        } finally {
            synchronized(trackLock) {
                if (audioTrack === track) {
                    audioTrack = null
                }
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    override fun stopNow() {
        stopped.set(true)
        val track = synchronized(trackLock) { audioTrack }
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
        }
    }

    private suspend fun waitForDrain(
        track: AudioTrack,
        totalFrames: Int,
        sampleRate: Int,
    ) {
        if (totalFrames <= 0) return

        val remainingFrames = (totalFrames.toLong() - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL))
            .coerceAtLeast(0L)
        val expectedDrainMs = if (sampleRate > 0) (remainingFrames * 1000L) / sampleRate else 0L
        val deadlineMs = System.currentTimeMillis() + maxOf(MIN_DRAIN_WAIT_MS, expectedDrainMs + DRAIN_GRACE_MS)

        while (!stopped.get() && currentCoroutineContext().isActive) {
            val headPosition = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (headPosition >= totalFrames.toLong()) {
                break
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                break
            }
            if (System.currentTimeMillis() >= deadlineMs) {
                break
            }
            delay(DRAIN_POLL_INTERVAL_MS)
        }
    }

    private fun resolveAudioEncoding(wav: WavAudio): Int {
        if (wav.audioFormat == WavAudio.FORMAT_IEEE_FLOAT && wav.bitsPerSample == 32) {
            return AudioFormat.ENCODING_PCM_FLOAT
        }
        if (wav.audioFormat != WavAudio.FORMAT_PCM) {
            throw WavParseException("Unsupported WAV encoding format=${wav.audioFormat}, bits=${wav.bitsPerSample}")
        }
        return when (wav.bitsPerSample) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                throw WavParseException("24-bit PCM requires Android 12+ (API 31)")
            }
            32 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_32BIT
            } else {
                throw WavParseException("32-bit PCM requires Android 12+ (API 31)")
            }
            else -> throw WavParseException("Unsupported PCM bit depth: ${wav.bitsPerSample}")
        }
    }

    companion object {
        private const val WRITE_CHUNK_BYTES = 4096
        private const val DRAIN_POLL_INTERVAL_MS = 20L
        private const val MIN_DRAIN_WAIT_MS = 1_500L
        private const val DRAIN_GRACE_MS = 1_500L
    }
}

private class AndroidTextToSpeechSpeaker(
    private val appContext: Context,
) : SystemTtsSpeaker {
    private class PendingUtterance(
        val done: CompletableDeferred<Unit>,
        onStart: (() -> Unit)? = null,
    ) {
        private val startCallback = AtomicReference(onStart)

        fun fireOnStartOnce() {
            startCallback.getAndSet(null)?.invoke()
        }
    }

    private val lock = Any()
    private val pendingUtterances = ConcurrentHashMap<String, PendingUtterance>()

    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var initialized: Boolean = false
    private var released: Boolean = false

    @Volatile
    var availableLocalVoiceNames: List<String> = emptyList()
        private set

    override val availableVoices: List<String>
        get() = availableLocalVoiceNames

    override suspend fun speak(
        text: String,
        preferredVoiceName: String?,
        useCommunicationUsage: Boolean,
    ) {
        speak(
            text = text,
            preferredVoiceName = preferredVoiceName,
            useCommunicationUsage = useCommunicationUsage,
            onPlaybackStarted = null,
        )
    }

    override suspend fun speak(
        text: String,
        preferredVoiceName: String?,
        useCommunicationUsage: Boolean,
        onPlaybackStarted: (() -> Unit)?,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val engine = ensureInitialized()
            ?: throw TtsException(message = "Android TextToSpeech engine failed to initialize")

        val preferredName = preferredVoiceName?.trim()?.takeIf { it.isNotEmpty() }
        if (preferredName != null) {
            runCatching {
                val voices = engine.voices
                val matched = voices?.firstOrNull { it.name == preferredName }
                    ?: voices?.firstOrNull { it.name.equals(preferredName, ignoreCase = true) }
                if (matched != null) {
                    engine.voice = matched
                }
            }
        } else {
            runCatching {
                engine.defaultVoice?.let { engine.voice = it }
            }
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (useCommunicationUsage) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        runCatching { engine.setAudioAttributes(attributes) }

        val maxLen = runCatching { TextToSpeech.getMaxSpeechInputLength() }
            .getOrDefault(DEFAULT_MAX_SPEECH_INPUT_LENGTH)
            .coerceAtLeast(200)
        val chunks = splitTextForSpeech(trimmed, maxLen)
        if (chunks.isEmpty()) return

        for ((index, chunk) in chunks.withIndex()) {
            val utteranceId = UUID.randomUUID().toString()
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            speakSingleUtterance(
                engine = engine,
                text = chunk,
                queueMode = queueMode,
                utteranceId = utteranceId,
                onStart = if (index == 0) onPlaybackStarted else null,
            )
        }
    }

    override fun stop() {
        val engine = synchronized(lock) { tts }
        runCatching { engine?.stop() }
        val iterator = pendingUtterances.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.done.cancel(CancellationException("Android TTS stopped"))
            iterator.remove()
        }
    }

    override fun shutdown() {
        val engineToShutdown: TextToSpeech?
        synchronized(lock) {
            released = true
            initialized = false
            initDeferred?.cancel(CancellationException("Android TTS shut down"))
            initDeferred = null
            engineToShutdown = tts
            tts = null
        }
        stop()
        runCatching { engineToShutdown?.shutdown() }
    }

    private suspend fun ensureInitialized(): TextToSpeech? {
        val deferred = synchronized(lock) {
            if (released) return null
            if (initialized && tts != null) return tts
            initDeferred?.let { return@synchronized it }

            val newDeferred = CompletableDeferred<Boolean>()
            initDeferred = newDeferred

            var createdEngine: TextToSpeech? = null
            createdEngine = TextToSpeech(appContext) { status ->
                val success = status == TextToSpeech.SUCCESS
                synchronized(lock) {
                    if (released) {
                        runCatching { createdEngine?.shutdown() }
                        newDeferred.complete(false)
                    } else if (success) {
                        initialized = true
                        createdEngine?.let { availableLocalVoiceNames = extractLocalVoiceNames(it) }
                        newDeferred.complete(true)
                    } else {
                        initialized = false
                        initDeferred = null
                        tts = null
                        runCatching { createdEngine?.shutdown() }
                        newDeferred.complete(false)
                    }
                }
            }

            createdEngine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == null) return
                        pendingUtterances[utteranceId]?.fireOnStartOnce()
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.done?.complete(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.done?.completeExceptionally(
                            TtsException(message = "Android TextToSpeech playback error"),
                        )
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.done?.completeExceptionally(
                            TtsException(message = "Android TextToSpeech playback error ($errorCode)"),
                        )
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.done?.cancel(
                            CancellationException("Android TextToSpeech utterance stopped"),
                        )
                    }
                },
            )

            tts = createdEngine
            newDeferred
        }

        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            deferred.await()
        } ?: false

        if (!ready) {
            synchronized(lock) {
                if (!initialized) {
                    initDeferred = null
                    runCatching { tts?.shutdown() }
                    tts = null
                }
            }
            return null
        }

        val activeEngine = synchronized(lock) { if (!released && initialized) tts else null }
        if (activeEngine != null && availableLocalVoiceNames.isEmpty()) {
            availableLocalVoiceNames = extractLocalVoiceNames(activeEngine)
        }
        return activeEngine
    }

    private fun extractLocalVoiceNames(engine: TextToSpeech): List<String> {
        val defaultLang = Locale.getDefault().language
        val rawVoices = runCatching { engine.voices }.getOrNull().orEmpty()
        return rawVoices
            .filter { v ->
                !v.isNetworkConnectionRequired &&
                    v.locale != null &&
                    (v.locale.language.equals(defaultLang, ignoreCase = true) ||
                        v.locale.language.equals("en", ignoreCase = true))
            }
            .sortedWith(
                compareBy<android.speech.tts.Voice>(
                    { !it.locale.language.equals(defaultLang, ignoreCase = true) },
                    { it.name },
                ),
            )
            .map { it.name }
            .distinct()
    }

    private suspend fun speakSingleUtterance(
        engine: TextToSpeech,
        text: String,
        queueMode: Int,
        utteranceId: String,
        onStart: (() -> Unit)? = null,
    ) = suspendCancellableCoroutine { continuation ->
        val deferred = CompletableDeferred<Unit>()
        pendingUtterances[utteranceId] = PendingUtterance(done = deferred, onStart = onStart)

        deferred.invokeOnCompletion { cause ->
            pendingUtterances.remove(utteranceId)
            if (continuation.isCancelled) return@invokeOnCompletion
            if (cause == null) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(cause)
            }
        }

        continuation.invokeOnCancellation {
            pendingUtterances.remove(utteranceId)
            deferred.cancel(CancellationException("TTS utterance cancelled"))
            runCatching { engine.stop() }
        }

        val speakResult = engine.speak(text, queueMode, null, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            pendingUtterances.remove(utteranceId)
            deferred.completeExceptionally(
                TtsException(message = "Android TextToSpeech.speak returned ERROR"),
            )
        }
    }

    companion object {
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val DEFAULT_MAX_SPEECH_INPUT_LENGTH = 3_900
    }
}

internal fun splitTextForSpeech(text: String, maxLen: Int): List<String> {
    if (text.length <= maxLen) return listOf(text)
    val chunks = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val end = minOf(index + maxLen, text.length)
        if (end == text.length) {
            chunks += text.substring(index).trim()
            break
        }
        val splitAt = text.lastIndexOf(' ', end).let { spaceIdx ->
            if (spaceIdx > index + (maxLen / 2)) spaceIdx else end
        }
        val part = text.substring(index, splitAt).trim()
        if (part.isNotEmpty()) {
            chunks += part
        }
        index = if (splitAt > index) splitAt else end
    }
    return chunks.filter { it.isNotEmpty() }
}
