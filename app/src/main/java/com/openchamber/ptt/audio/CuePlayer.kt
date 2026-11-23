package com.openchamber.ptt.audio

import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.AudioCueMode
import com.openchamber.ptt.tts.ResolvedEngineVoice
import com.openchamber.ptt.tts.RoleVoiceProfileMap
import com.openchamber.ptt.tts.SystemTtsSpeaker
import com.openchamber.ptt.tts.TtsAudio
import com.openchamber.ptt.tts.TtsCache
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsPlayer
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.tts.VoiceRoleResolver
import com.openchamber.ptt.tts.WavAudio
import com.openchamber.ptt.tts.WavParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class CueEvent(
    val cueName: String,
    val fallbackEarcon: Earcon?,
    val vocalPhrase: String?,
    val spokenInHybrid: Boolean,
    val playInTonesMode: Boolean = true,
) {
    ARMED("armed", Earcon.ARMED, "Armed.", spokenInHybrid = false),
    ARMED_WITH_DRAFT("armed_with_draft", Earcon.ARMED, "Draft ready.", spokenInHybrid = true),
    REC_START("rec_start", Earcon.REC_START, vocalPhrase = null, spokenInHybrid = false),
    MAX_DURATION_WARNING("max_duration_warning", Earcon.STAGED_PAUSE, vocalPhrase = null, spokenInHybrid = false),
    QUICK_STOP_ACK("quick_stop_ack", Earcon.STAGED_PAUSE, "Got it.", spokenInHybrid = true, playInTonesMode = false),
    STAGE_ORGANIZING("stage_organizing", Earcon.STAGED_PAUSE, "Staging draft.", spokenInHybrid = true),
    STAGE_APPENDING("stage_appending", Earcon.STAGED_PAUSE, "Adding to draft.", spokenInHybrid = true),
    EMPTY_SPEECH("empty_speech", Earcon.CANCEL_DISCARD, "Didn't catch that.", spokenInHybrid = true),
    SEND_GRACE("send_grace", Earcon.SEND_GRACE, "Sending.", spokenInHybrid = false),
    UNDO_TO_DRAFT("undo_to_draft", Earcon.UNDO, "Saved to draft.", spokenInHybrid = false),
    CANCEL_DISCARD("cancel_discard", Earcon.CANCEL_DISCARD, "Discarded.", spokenInHybrid = false),
    PROMPT_SENT("prompt_sent", Earcon.REC_SENT, "Sent.", spokenInHybrid = false),
    STILL_WORKING("still_working", null, "Still working.", spokenInHybrid = true, playInTonesMode = false),
    REPLY_PREPARING("reply_preparing", null, "Summarizing.", spokenInHybrid = true, playInTonesMode = false),
    READING_REPLY("reading_reply", null, "Reading reply.", spokenInHybrid = true, playInTonesMode = false),
    TURN_COMPLETE("turn_complete", Earcon.TURN_COMPLETE, "Reply ready.", spokenInHybrid = false),
    TURN_COMPLETE_NO_TTS("turn_complete_no_tts", Earcon.TURN_COMPLETE, "Done.", spokenInHybrid = true),
    TURN_INTERRUPTED("turn_interrupted", Earcon.TURN_INTERRUPTED, "Interrupted.", spokenInHybrid = true),
    PERMISSION_NEEDED("permission_needed", Earcon.PERMISSION_NEEDED, vocalPhrase = null, spokenInHybrid = false),
    AUTH_EXPIRED("auth_expired", Earcon.AUTH_EXPIRED, "Login expired.", spokenInHybrid = true),
    ERROR("error", Earcon.ERROR, "Connection error.", spokenInHybrid = true),
    DISARMED("disarmed", Earcon.DISARMED, "Disarmed.", spokenInHybrid = false),
}

object VocalCueCatalog {
    val ALL_PHRASES: List<String> = CueEvent.entries
        .mapNotNull { it.vocalPhrase?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
}

data class CuePackWarmState(
    val isWarming: Boolean = false,
    val warmedCount: Int = 0,
    val totalCount: Int = VocalCueCatalog.ALL_PHRASES.size,
    val engine: TtsEngine? = null,
    val voiceId: String? = null,
)

internal fun resolveCueEngineVoice(
    pipeline: List<TtsEngineOption>,
    profiles: RoleVoiceProfileMap,
    availableGeminiVoices: List<String>,
    availableMlKitVoices: List<String>,
    availableSystemVoices: List<String>,
): ResolvedEngineVoice? {
    val rolePipeline = VoiceRoleResolver.resolvePipelineForRole(pipeline, profiles[VoiceRole.CUE])
    val primary = VoicePipelineDefaults.primaryEnabledEngine(rolePipeline) ?: return null
    return VoiceRoleResolver.resolveVoiceForEngine(
        engine = primary,
        role = VoiceRole.CUE,
        profiles = profiles,
        availableGeminiVoices = availableGeminiVoices,
        availableMlKitVoices = availableMlKitVoices,
        availableSystemVoices = availableSystemVoices,
    )
}

internal fun resolveCloudVoiceName(resolved: ResolvedEngineVoice): String =
    resolved.voiceId?.takeIf { it.isNotBlank() } ?: when (resolved.engine) {
        TtsEngine.OPENAI_CLOUD -> VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
        else -> TtsClient.DEFAULT_TTS_VOICE
    }

internal fun displayCueVoiceId(resolved: ResolvedEngineVoice): String? = when (resolved.engine) {
    TtsEngine.LOCAL_KOKORO -> "#${resolved.kokoroSpeakerId}"
    TtsEngine.GEMINI_BRIDGE, TtsEngine.OPENAI_CLOUD -> resolveCloudVoiceName(resolved)
    TtsEngine.MLKIT_GENAI, TtsEngine.ANDROID_SYSTEM -> resolved.voiceId
}

class VocalCuePackWarmer internal constructor(
    private val scope: CoroutineScope,
    private val ttsClient: TtsClient,
    private val ttsCache: TtsCache,
    private val serverUrlProvider: () -> String?,
    private val cueModeProvider: () -> AudioCueMode,
    private val pipelineProvider: () -> List<TtsEngineOption>,
    private val roleProfilesProvider: () -> RoleVoiceProfileMap,
    private val availableGeminiVoicesProvider: () -> List<String> = { emptyList() },
    private val availableMlKitVoicesProvider: () -> List<String> = { emptyList() },
    private val availableSystemVoicesProvider: () -> List<String> = { emptyList() },
    private val voiceUxLogger: VoiceUxLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val warmMutex = Mutex()
    private var warmJob: Job? = null
    private var activeSignature: WarmSignature? = null

    private val _state = MutableStateFlow(CuePackWarmState())
    val state: StateFlow<CuePackWarmState> = _state.asStateFlow()

    fun ensureWarmedAsync() {
        val signature = buildSignature()
        val prevJob: Job?
        synchronized(lock) {
            if (signature.mode == AudioCueMode.TONES) {
                prevJob = warmJob
                warmJob = null
                activeSignature = null
                _state.value = CuePackWarmState(
                    isWarming = false,
                    warmedCount = 0,
                    totalCount = VocalCueCatalog.ALL_PHRASES.size,
                    engine = signature.resolved?.engine,
                    voiceId = signature.resolved?.let(::displayCueVoiceId),
                )
            } else if (warmJob?.isActive == true && activeSignature == signature) {
                return
            } else {
                prevJob = warmJob
                activeSignature = signature
                val job = scope.launch(ioDispatcher) {
                    warmInternal(signature)
                }
                warmJob = job
                job.invokeOnCompletion {
                    synchronized(lock) {
                        if (warmJob === job) {
                            warmJob = null
                        }
                    }
                }
            }
        }
        prevJob?.cancel()
    }

    suspend fun warmIfNeeded() {
        val prevJob: Job?
        val signature = buildSignature()
        synchronized(lock) {
            prevJob = warmJob
            warmJob = null
            activeSignature = if (signature.mode == AudioCueMode.TONES) null else signature
        }
        prevJob?.cancel()
        withContext(ioDispatcher) {
            warmInternal(signature)
        }
    }

    fun cancel() {
        val prevJob: Job?
        synchronized(lock) {
            prevJob = warmJob
            warmJob = null
            activeSignature = null
        }
        prevJob?.cancel()
    }

    fun resetAfterCacheClear() {
        val prevJob: Job?
        val signature = buildSignature()
        synchronized(lock) {
            prevJob = warmJob
            warmJob = null
            activeSignature = null
            val resolved = signature.resolved
            val isOnDevice = resolved?.engine == TtsEngine.MLKIT_GENAI ||
                resolved?.engine == TtsEngine.ANDROID_SYSTEM
            _state.value = CuePackWarmState(
                isWarming = false,
                warmedCount = if (signature.mode != AudioCueMode.TONES && isOnDevice) {
                    VocalCueCatalog.ALL_PHRASES.size
                } else {
                    0
                },
                totalCount = VocalCueCatalog.ALL_PHRASES.size,
                engine = resolved?.engine,
                voiceId = resolved?.let(::displayCueVoiceId),
            )
        }
        prevJob?.cancel()
    }

    private suspend fun warmInternal(signature: WarmSignature) {
        warmMutex.withLock {
            val total = VocalCueCatalog.ALL_PHRASES.size
            val resolved = signature.resolved
            if (signature.mode == AudioCueMode.TONES || resolved == null) {
                _state.value = CuePackWarmState(
                    isWarming = false,
                    warmedCount = 0,
                    totalCount = total,
                    engine = resolved?.engine,
                    voiceId = resolved?.let(::displayCueVoiceId),
                )
                return
            }

            val displayVoice = displayCueVoiceId(resolved)
            if (resolved.engine == TtsEngine.MLKIT_GENAI || resolved.engine == TtsEngine.ANDROID_SYSTEM) {
                _state.value = CuePackWarmState(
                    isWarming = false,
                    warmedCount = total,
                    totalCount = total,
                    engine = resolved.engine,
                    voiceId = displayVoice,
                )
                return
            }

            val baseUrl = signature.normalizedServerUrl
            if (baseUrl.isNullOrBlank()) {
                _state.value = CuePackWarmState(
                    isWarming = false,
                    warmedCount = 0,
                    totalCount = total,
                    engine = resolved.engine,
                    voiceId = displayVoice,
                )
                return
            }

            val cloudVoice = resolveCloudVoiceName(resolved)
            val missing = mutableListOf<String>()
            var warmedCount = 0
            for (phrase in VocalCueCatalog.ALL_PHRASES) {
                val key = ttsCache.audioCacheKey(
                    serverUrl = baseUrl,
                    engine = resolved.engine,
                    voice = cloudVoice,
                    spokenText = phrase,
                    speed = TtsClient.DEFAULT_TTS_SPEED,
                    kokoroSpeakerId = resolved.kokoroSpeakerId,
                )
                if (ttsCache.getAudio(key) != null) {
                    warmedCount += 1
                } else {
                    missing += phrase
                }
            }

            _state.value = CuePackWarmState(
                isWarming = missing.isNotEmpty(),
                warmedCount = warmedCount,
                totalCount = total,
                engine = resolved.engine,
                voiceId = displayVoice,
            )

            if (missing.isEmpty()) {
                return
            }

            var synthesizedCount = 0
            try {
                for (phrase in missing) {
                    val result = ttsClient.synthesizeWithEngineDetailed(
                        serverUrl = baseUrl,
                        text = phrase,
                        voice = cloudVoice,
                        engine = resolved.engine,
                        kokoroSpeakerId = resolved.kokoroSpeakerId,
                    )
                    if (!result.cacheHit) {
                        synthesizedCount += 1
                    }
                    warmedCount += 1
                    _state.value = CuePackWarmState(
                        isWarming = warmedCount < total,
                        warmedCount = warmedCount.coerceAtMost(total),
                        totalCount = total,
                        engine = resolved.engine,
                        voiceId = displayVoice,
                    )
                }
                if (synthesizedCount > 0) {
                    voiceUxLogger?.log(
                        category = VoiceUxLogger.CAT_CUE,
                        event = "pack_warmed",
                        metrics = mapOf(
                            "engine" to resolved.engine.name,
                            "voice" to displayVoice,
                            "synthesized" to synthesizedCount,
                            "total" to total,
                        ),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                voiceUxLogger?.log(
                    category = VoiceUxLogger.CAT_CUE,
                    event = "pack_warm_failed",
                    metrics = mapOf(
                        "engine" to resolved.engine.name,
                        "voice" to displayVoice,
                        "warmed" to warmedCount,
                        "error" to (t.message ?: t::class.java.simpleName),
                    ),
                )
            } finally {
                _state.value = _state.value.copy(isWarming = false)
            }
        }
    }

    private fun buildSignature(): WarmSignature {
        val mode = cueModeProvider()
        val rawUrl = serverUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedUrl = rawUrl?.let { OpenChamberHttp.normalizeBaseUrl(it) }
        val resolved = resolveCueEngineVoice(
            pipeline = pipelineProvider(),
            profiles = roleProfilesProvider(),
            availableGeminiVoices = availableGeminiVoicesProvider(),
            availableMlKitVoices = availableMlKitVoicesProvider(),
            availableSystemVoices = availableSystemVoicesProvider(),
        )
        return WarmSignature(
            mode = mode,
            normalizedServerUrl = normalizedUrl,
            resolved = resolved,
        )
    }

    private data class WarmSignature(
        val mode: AudioCueMode,
        val normalizedServerUrl: String?,
        val resolved: ResolvedEngineVoice?,
    )
}

class CuePlayer internal constructor(
    private val scope: CoroutineScope,
    private val earconPlayer: EarconPlayer,
    private val ttsCache: TtsCache,
    private val warmer: VocalCuePackWarmer,
    private val serverUrlProvider: () -> String?,
    private val cueModeProvider: () -> AudioCueMode,
    private val pipelineProvider: () -> List<TtsEngineOption>,
    private val roleProfilesProvider: () -> RoleVoiceProfileMap,
    private val availableGeminiVoicesProvider: () -> List<String> = { emptyList() },
    private val availableMlKitVoicesProvider: () -> List<String> = { emptyList() },
    private val availableSystemVoicesProvider: () -> List<String> = { emptyList() },
    private val useCommunicationUsage: () -> Boolean = { false },
    private val isTtsAudibleProvider: () -> Boolean = { false },
    private val audioOutputCoordinator: AudioOutputCoordinator? = null,
    private val mlKitTtsSpeaker: SystemTtsSpeaker? = null,
    private val systemTtsSpeaker: SystemTtsSpeaker? = null,
    private val encodedAudioDecoder: (ByteArray, String?) -> WavAudio = { bytes, contentType ->
        AudioCodecDecoder.decodeEncodedBytes(bytes, contentType).toWavAudio()
    },
    private val voiceUxLogger: VoiceUxLogger? = null,
    private val sessionKeyProvider: () -> String? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val decodedClipCache = ConcurrentHashMap<String, PcmClip>()
    private var onDeviceCueJob: Job? = null
    private var activeOnDeviceSpeaker: SystemTtsSpeaker? = null
    private var activeOnDeviceUseComm: Boolean = false

    fun play(cue: CueEvent) {
        val mode = cueModeProvider()
        val phrase = cue.vocalPhrase?.trim()?.takeIf { it.isNotEmpty() }
        val modeSpeaksCue = mode == AudioCueMode.VOCAL || (mode == AudioCueMode.HYBRID && cue.spokenInHybrid)

        if (phrase == null || !modeSpeaksCue) {
            cancelOnDeviceCue()
            if (mode != AudioCueMode.TONES || cue.playInTonesMode) {
                cue.fallbackEarcon?.let(earconPlayer::play)
            }
            return
        }

        val resolved = resolveCueEngineVoice(
            pipeline = pipelineProvider(),
            profiles = roleProfilesProvider(),
            availableGeminiVoices = availableGeminiVoicesProvider(),
            availableMlKitVoices = availableMlKitVoicesProvider(),
            availableSystemVoices = availableSystemVoicesProvider(),
        )
        if (resolved == null) {
            cancelOnDeviceCue()
            cue.fallbackEarcon?.let(earconPlayer::play)
            return
        }

        if (resolved.engine == TtsEngine.MLKIT_GENAI || resolved.engine == TtsEngine.ANDROID_SYSTEM) {
            val speaker = if (resolved.engine == TtsEngine.MLKIT_GENAI) mlKitTtsSpeaker else systemTtsSpeaker
            if (speaker != null && !isTtsAudibleProvider()) {
                launchOnDeviceCue(cue = cue, phrase = phrase, speaker = speaker, resolved = resolved)
            } else {
                cancelOnDeviceCue()
                cue.fallbackEarcon?.let(earconPlayer::play)
            }
            return
        }

        cancelOnDeviceCue()
        val rawUrl = serverUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
        if (rawUrl == null) {
            cue.fallbackEarcon?.let(earconPlayer::play)
            return
        }

        val baseUrl = OpenChamberHttp.normalizeBaseUrl(rawUrl)
        val cloudVoice = resolveCloudVoiceName(resolved)
        val cacheKey = ttsCache.audioCacheKey(
            serverUrl = baseUrl,
            engine = resolved.engine,
            voice = cloudVoice,
            spokenText = phrase,
            speed = TtsClient.DEFAULT_TTS_SPEED,
            kokoroSpeakerId = resolved.kokoroSpeakerId,
        )

        val cachedAudio = ttsCache.getAudio(cacheKey)
        if (cachedAudio != null) {
            val clip = resolveDecodedClip(cacheKey, cachedAudio)
            if (clip != null) {
                earconPlayer.playCustomClip(clip, keepTag = cue.cueName)
                voiceUxLogger?.log(
                    category = VoiceUxLogger.CAT_CUE,
                    event = "vocal_played",
                    sessionKey = sessionKeyProvider(),
                    metrics = mapOf(
                        "cue" to cue.cueName,
                        "engine" to resolved.engine.name,
                        "voice" to displayCueVoiceId(resolved),
                    ),
                )
                return
            }
        } else {
            warmer.ensureWarmedAsync()
            voiceUxLogger?.log(
                category = VoiceUxLogger.CAT_CUE,
                event = "vocal_miss_fallback",
                sessionKey = sessionKeyProvider(),
                metrics = mapOf(
                    "cue" to cue.cueName,
                    "engine" to resolved.engine.name,
                    "voice" to displayCueVoiceId(resolved),
                ),
            )
        }

        cue.fallbackEarcon?.let(earconPlayer::play)
    }

    fun stop() {
        cancelOnDeviceCue()
        earconPlayer.stop()
    }

    fun stopUnlessRecStart() {
        cancelOnDeviceCue()
        earconPlayer.stopUnless(Earcon.REC_START)
    }

    fun clearDecodedClipCache() {
        decodedClipCache.clear()
    }

    fun release() {
        cancelOnDeviceCue()
        warmer.cancel()
        decodedClipCache.clear()
    }

    private fun launchOnDeviceCue(
        cue: CueEvent,
        phrase: String,
        speaker: SystemTtsSpeaker,
        resolved: ResolvedEngineVoice,
    ) {
        val prevJob: Job?
        val prevSpeaker: SystemTtsSpeaker?
        val prevUseComm: Boolean
        synchronized(lock) {
            prevJob = onDeviceCueJob
            prevSpeaker = activeOnDeviceSpeaker
            prevUseComm = activeOnDeviceUseComm
            onDeviceCueJob = null
            activeOnDeviceSpeaker = null
            activeOnDeviceUseComm = false
        }
        if (prevJob != null) {
            val wasActive = prevJob.isActive
            prevJob.cancel()
            if (wasActive) {
                runCatching { prevSpeaker?.stop() }
            }
            audioOutputCoordinator?.onCueStopped(prevUseComm)
        }
        earconPlayer.stop()

        val useComm = useCommunicationUsage()
        val estimatedMs = (phrase.length * 65L).coerceAtLeast(400L)
        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            var fellBackToEarcon = false
            try {
                speaker.speak(
                    text = phrase,
                    preferredVoiceName = resolved.voiceId,
                    useCommunicationUsage = useComm,
                )
                voiceUxLogger?.log(
                    category = VoiceUxLogger.CAT_CUE,
                    event = "vocal_on_device",
                    sessionKey = sessionKeyProvider(),
                    metrics = mapOf(
                        "cue" to cue.cueName,
                        "engine" to resolved.engine.name,
                        "voice" to resolved.voiceId,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                fellBackToEarcon = true
                val shouldStopBeforeFallback = synchronized(lock) {
                    if (onDeviceCueJob === jobRef) {
                        onDeviceCueJob = null
                        activeOnDeviceSpeaker = null
                        activeOnDeviceUseComm = false
                        true
                    } else {
                        false
                    }
                }
                if (shouldStopBeforeFallback) {
                    audioOutputCoordinator?.onCueStopped(useComm)
                }
                cue.fallbackEarcon?.let(earconPlayer::play)
            } finally {
                val shouldNotifyStop = synchronized(lock) {
                    if (onDeviceCueJob === jobRef) {
                        onDeviceCueJob = null
                        activeOnDeviceSpeaker = null
                        activeOnDeviceUseComm = false
                        true
                    } else {
                        false
                    }
                }
                if (shouldNotifyStop && !fellBackToEarcon) {
                    audioOutputCoordinator?.onCueStopped(useComm)
                }
            }
        }
        jobRef = job
        synchronized(lock) {
            onDeviceCueJob = job
            activeOnDeviceSpeaker = speaker
            activeOnDeviceUseComm = useComm
        }
        audioOutputCoordinator?.onCueStarted(estimatedMs, useCommunicationUsage = useComm)
        job.start()
    }

    private fun cancelOnDeviceCue() {
        val job: Job?
        val speaker: SystemTtsSpeaker?
        val useComm: Boolean
        synchronized(lock) {
            job = onDeviceCueJob
            speaker = activeOnDeviceSpeaker
            useComm = activeOnDeviceUseComm
            onDeviceCueJob = null
            activeOnDeviceSpeaker = null
            activeOnDeviceUseComm = false
        }
        if (job != null) {
            val wasActive = job.isActive
            job.cancel()
            if (wasActive) {
                runCatching { speaker?.stop() }
            }
            audioOutputCoordinator?.onCueStopped(useComm)
        }
    }

    private fun resolveDecodedClip(cacheKey: String, audio: TtsAudio): PcmClip? {
        decodedClipCache[cacheKey]?.let { return it }
        val clip = runCatching {
            val wav = if (TtsPlayer.isRiffWav(audio.bytes)) {
                WavParser.parse(audio.bytes)
            } else {
                encodedAudioDecoder(audio.bytes, audio.contentType)
            }
            if (wav.audioFormat != WavAudio.FORMAT_PCM || wav.bitsPerSample != 16 || wav.pcmData.isEmpty()) {
                return null
            }
            val bytesPerSec = wav.sampleRate * wav.channels * 2
            val durationMs = if (bytesPerSec > 0) {
                ((wav.pcmData.size * 1000L) / bytesPerSec).toInt().coerceAtLeast(20)
            } else {
                100
            }
            PcmClip(
                pcmBytes = wav.pcmData,
                sampleRateHz = wav.sampleRate,
                channelCount = wav.channels.coerceIn(1, 2),
                durationMs = durationMs,
            )
        }.getOrNull() ?: return null

        if (decodedClipCache.size >= MAX_DECODED_CLIP_CACHE_ENTRIES) {
            decodedClipCache.clear()
        }
        decodedClipCache.putIfAbsent(cacheKey, clip)
        return clip
    }

    companion object {
        private const val MAX_DECODED_CLIP_CACHE_ENTRIES = 64
    }
}
