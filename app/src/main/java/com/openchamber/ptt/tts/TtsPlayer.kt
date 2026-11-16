package com.openchamber.ptt.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface PcmTrackPlayer {
    suspend fun play(wav: WavAudio, useCommunicationUsage: Boolean = false)
    suspend fun playEncoded(bytes: ByteArray, useCommunicationUsage: Boolean = false) {
        throw UnsupportedOperationException("Encoded audio playback is not supported by this player")
    }
    fun stopNow()
}

data class SystemTtsVoiceSummary(
    val name: String,
    val label: String,
    val localeTag: String,
    val isNetworkRequired: Boolean,
)

internal interface SystemTtsSpeaker {
    suspend fun speak(text: String, useCommunicationUsage: Boolean = false)
    fun stop()
    fun shutdown()
}

data class TtsSpeakStats(
    val inputMode: TtsInputMode,
    val prepareMs: Long,
    val rawChars: Int,
    val spokenChars: Int,
    val engine: TtsEngine,
    val kind: String = "reply",
)

class TtsPlayer internal constructor(
    private val ttsClient: TtsClient,
    private val pcmPlayerFactory: () -> PcmTrackPlayer,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val mlKitTtsSpeaker: SystemTtsSpeaker? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val useCommunicationUsage: () -> Boolean = { false },
    private val pipelineProvider: () -> List<TtsEngineOption> = { VoicePipelineDefaults.defaultTtsPipeline },
    private val openAiVoiceProvider: () -> String = { VoicePipelineDefaults.DEFAULT_OPENAI_VOICE },
    private val inputModeProvider: () -> TtsInputMode = { TtsInputMode.SUMMARIZED },
    private val detailLevelProvider: () -> SpokenDetailLevel = { SpokenDetailLevel.BALANCED },
    private val onStatusEvent: (String) -> Unit = {},
    private val onSpokenTextSynthesized: ((String) -> Unit)? = null,
    private val onSpeakStats: ((TtsSpeakStats) -> Unit)? = null,
) {
    constructor(
        context: Context,
        ttsClient: TtsClient,
        useCommunicationUsage: () -> Boolean = { false },
        pipelineProvider: () -> List<TtsEngineOption> = { VoicePipelineDefaults.defaultTtsPipeline },
        mlKitVoiceNameProvider: () -> String? = { null },
        openAiVoiceProvider: () -> String = { VoicePipelineDefaults.DEFAULT_OPENAI_VOICE },
        systemVoiceNameProvider: () -> String? = { null },
        inputModeProvider: () -> TtsInputMode = { TtsInputMode.SUMMARIZED },
        detailLevelProvider: () -> SpokenDetailLevel = { SpokenDetailLevel.BALANCED },
        onStatusEvent: (String) -> Unit = {},
        onSpokenTextSynthesized: ((String) -> Unit)? = null,
        onSpeakStats: ((TtsSpeakStats) -> Unit)? = null,
    ) : this(
        ttsClient = ttsClient,
        pcmPlayerFactory = { AndroidAudioTrackPlayer(context.applicationContext) },
        systemTtsSpeaker = AndroidTextToSpeechSpeaker(
            appContext = context.applicationContext,
            preferredVoiceNameProvider = systemVoiceNameProvider,
        ),
        mlKitTtsSpeaker = MlKitTtsSpeaker(preferredVoiceNameProvider = mlKitVoiceNameProvider),
        ioDispatcher = Dispatchers.IO,
        useCommunicationUsage = useCommunicationUsage,
        pipelineProvider = pipelineProvider,
        openAiVoiceProvider = openAiVoiceProvider,
        inputModeProvider = inputModeProvider,
        detailLevelProvider = detailLevelProvider,
        onStatusEvent = onStatusEvent,
        onSpokenTextSynthesized = onSpokenTextSynthesized,
        onSpeakStats = onSpeakStats,
    )

    private val lock = Any()
    private var activeJob: Job? = null
    private var activeTrackPlayer: PcmTrackPlayer? = null
    private var playbackGeneration: Long = 0L
    private var released: Boolean = false

    val isSpeaking: Boolean
        get() = synchronized(lock) { activeJob?.isActive == true }

    suspend fun speak(
        serverUrl: String,
        text: String,
        voice: String = TtsClient.DEFAULT_TTS_VOICE,
        sessionLabel: String? = null,
        inputModeOverride: TtsInputMode? = null,
        kind: String = "reply",
        notifySpokenSummary: Boolean = true,
    ) {
        val rawTrimmed = text.trim()
        if (rawTrimmed.isEmpty()) return

        val myGeneration = beginPlaybackSession()
        try {
            val effectiveMode = inputModeOverride ?: inputModeProvider()
            val prepareStartMs = System.currentTimeMillis()
            val spokenText = ttsClient.prepareTextForTts(
                serverUrl = serverUrl,
                text = rawTrimmed,
                inputMode = effectiveMode,
                sessionLabel = sessionLabel,
                detailLevel = detailLevelProvider(),
            )
                .trim()
                .ifEmpty { rawTrimmed }
            val prepareElapsedMs = (System.currentTimeMillis() - prepareStartMs).coerceAtLeast(0L)

            ensureGenerationActive(myGeneration)

            val useComm = useCommunicationUsage()
            val enabledPipeline = pipelineProvider().filter { it.enabled }
            if (enabledPipeline.isEmpty()) {
                throw TtsException(message = "All TTS engines are disabled in Settings")
            }

            val failures = mutableListOf<String>()
            var lastStatusCode: Int? = null
            var played = false
            var usedEngine: TtsEngine? = null

            for (option in enabledPipeline) {
                ensureGenerationActive(myGeneration)
                val engine = option.engine
                val success = try {
                    when (engine) {
                        TtsEngine.MLKIT_GENAI -> {
                            val speaker = mlKitTtsSpeaker
                                ?: throw TtsException(message = "ML Kit GenAI TTS speaker not configured")
                            val routeLabel = if (useComm) "SCO" else "Media"
                            onStatusEvent("ML Kit GenAI TTS ($routeLabel)")
                            if (notifySpokenSummary) onSpokenTextSynthesized?.invoke(spokenText)
                            speaker.speak(spokenText, useComm)
                            true
                        }
                        TtsEngine.ANDROID_SYSTEM -> {
                            val routeLabel = if (useComm) "SCO" else "Media"
                            onStatusEvent("Android System TTS ($routeLabel)")
                            if (notifySpokenSummary) onSpokenTextSynthesized?.invoke(spokenText)
                            systemTtsSpeaker.speak(spokenText, useComm)
                            true
                        }
                        TtsEngine.GEMINI_BRIDGE,
                        TtsEngine.LOCAL_KOKORO,
                        TtsEngine.OPENAI_CLOUD,
                        -> {
                            val engineVoice = when (engine) {
                                TtsEngine.OPENAI_CLOUD -> openAiVoiceProvider()
                                else -> voice
                            }
                            val audio = ttsClient.synthesizeWithEngine(serverUrl, spokenText, engineVoice, engine)
                            ensureGenerationActive(myGeneration)
                            if (notifySpokenSummary) onSpokenTextSynthesized?.invoke(spokenText)
                            val routeLabel = if (useComm) "SCO" else "Media"
                            if (isRiffWav(audio.bytes)) {
                                val wav = WavParser.parse(audio.bytes)
                                ensureGenerationActive(myGeneration)
                                val durSec = if (wav.sampleRate > 0 && wav.channels > 0 && wav.bitsPerSample > 0) {
                                    wav.pcmData.size.toDouble() / (wav.sampleRate * wav.channels * (wav.bitsPerSample / 8))
                                } else {
                                    0.0
                                }
                                onStatusEvent("${engine.title} (${wav.sampleRate}Hz, ${"%.1f".format(durSec)}s, $routeLabel)")
                                playParsedWav(wav, myGeneration, useComm)
                            } else {
                                onStatusEvent("${engine.title} (${audio.contentType}, $routeLabel)")
                                playEncodedAudio(audio.bytes, myGeneration, useComm)
                            }
                            true
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
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
                        kind = kind,
                    ),
                )
            }
        } finally {
            endPlaybackSession(myGeneration)
        }
    }

    suspend fun playWav(wavBytes: ByteArray) {
        val wav = WavParser.parse(wavBytes)
        val myGeneration = beginPlaybackSession()
        try {
            playParsedWav(wav, myGeneration, useCommunicationUsage())
        } finally {
            endPlaybackSession(myGeneration)
        }
    }

    fun stop() {
        val jobToCancel: Job?
        val trackToStop: PcmTrackPlayer?
        synchronized(lock) {
            playbackGeneration += 1L
            jobToCancel = activeJob
            activeJob = null
            trackToStop = activeTrackPlayer
            activeTrackPlayer = null
        }
        runCatching { trackToStop?.stopNow() }
        runCatching { mlKitTtsSpeaker?.stop() }
        runCatching { systemTtsSpeaker.stop() }
        jobToCancel?.cancel(CancellationException("TTS stopped by barge-in"))
    }

    fun release() {
        synchronized(lock) {
            released = true
        }
        stop()
        runCatching { mlKitTtsSpeaker?.shutdown() }
        runCatching { systemTtsSpeaker.shutdown() }
    }

    private suspend fun playEncodedAudio(
        bytes: ByteArray,
        myGeneration: Long,
        useComm: Boolean,
    ) {
        if (bytes.isEmpty()) return

        val player = pcmPlayerFactory()
        synchronized(lock) {
            if (released || playbackGeneration != myGeneration) {
                throw CancellationException("TTS stopped")
            }
            activeTrackPlayer = player
        }
        try {
            withContext(ioDispatcher) {
                player.playEncoded(bytes, useComm)
            }
        } finally {
            synchronized(lock) {
                if (activeTrackPlayer === player) {
                    activeTrackPlayer = null
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
        } finally {
            synchronized(lock) {
                if (activeTrackPlayer === player) {
                    activeTrackPlayer = null
                }
            }
        }
    }

    private suspend fun beginPlaybackSession(): Long {
        val callerJob = currentCoroutineContext().job
        val previousJob: Job?
        val previousTrack: PcmTrackPlayer?
        val myGen: Long
        synchronized(lock) {
            if (released) {
                throw CancellationException("TtsPlayer has been released")
            }
            playbackGeneration += 1L
            myGen = playbackGeneration
            previousJob = activeJob
            previousTrack = activeTrackPlayer
            activeJob = callerJob
            activeTrackPlayer = null
        }
        runCatching { previousTrack?.stopNow() }
        runCatching { mlKitTtsSpeaker?.stop() }
        runCatching { systemTtsSpeaker.stop() }
        previousJob?.cancel(CancellationException("Superseded by newer TTS playback"))
        return myGen
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
                activeJob = null
                activeTrackPlayer = null
            }
        }
    }
    companion object {
        private const val TAG = "TtsPlayer"

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

private class AndroidAudioTrackPlayer(
    private val appContext: Context,
) : PcmTrackPlayer {
    private val stopped = AtomicBoolean(false)
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null

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

    override suspend fun playEncoded(bytes: ByteArray, useCommunicationUsage: Boolean) {
        if (stopped.get() || bytes.isEmpty()) return

        val tempFile = File.createTempFile("tts_", ".audio", appContext.cacheDir)
        try {
            tempFile.writeBytes(bytes)
            val completion = CompletableDeferred<Unit>()
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

            val mp = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener { completion.complete(Unit) }
                setOnErrorListener { _, what, extra ->
                    completion.completeExceptionally(
                        IllegalStateException("MediaPlayer error ($what/$extra)"),
                    )
                    true
                }
                prepare()
            }

            synchronized(trackLock) {
                if (stopped.get()) {
                    runCatching { mp.release() }
                    throw CancellationException("TTS stopped")
                }
                mediaPlayer = mp
            }

            try {
                mp.start()
                completion.await()
            } finally {
                synchronized(trackLock) {
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                }
                runCatching {
                    if (mp.isPlaying) mp.stop()
                }
                runCatching { mp.release() }
            }
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    override fun stopNow() {
        stopped.set(true)
        val (track, mp) = synchronized(trackLock) { audioTrack to mediaPlayer }
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
        }
        mp?.let {
            runCatching {
                if (it.isPlaying) it.stop()
            }
            runCatching { it.release() }
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
    private val preferredVoiceNameProvider: () -> String? = { null },
) : SystemTtsSpeaker {
    private val lock = Any()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var initialized: Boolean = false
    private var released: Boolean = false

    override suspend fun speak(text: String, useCommunicationUsage: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val engine = ensureInitialized()
            ?: throw TtsException(message = "Android TextToSpeech engine failed to initialize")

        val preferredName = preferredVoiceNameProvider()?.trim()?.takeIf { it.isNotEmpty() }
        if (preferredName != null) {
            runCatching {
                engine.voices?.firstOrNull { it.name == preferredName }?.let { matched ->
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
            speakSingleUtterance(engine, chunk, queueMode, utteranceId)
        }
    }

    override fun stop() {
        val engine = synchronized(lock) { tts }
        runCatching { engine?.stop() }
        val iterator = pendingUtterances.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.cancel(CancellationException("Android TTS stopped"))
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
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.complete(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.completeExceptionally(
                            TtsException(message = "Android TextToSpeech playback error"),
                        )
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.completeExceptionally(
                            TtsException(message = "Android TextToSpeech playback error ($errorCode)"),
                        )
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == null) return
                        pendingUtterances.remove(utteranceId)?.cancel(
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

        return synchronized(lock) { if (!released && initialized) tts else null }
    }

    private suspend fun speakSingleUtterance(
        engine: TextToSpeech,
        text: String,
        queueMode: Int,
        utteranceId: String,
    ) = suspendCancellableCoroutine { continuation ->
        val deferred = CompletableDeferred<Unit>()
        pendingUtterances[utteranceId] = deferred

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
