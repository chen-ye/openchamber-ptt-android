package com.openchamber.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.annotation.RawRes
import com.openchamber.ptt.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Bundled `studio` theme cues from `romainsimon/uisfx` (CC0-1.0), pre-decoded to PCM16 and
 * played via [AudioTrack] (`MODE_STATIC`) using `USAGE_VOICE_COMMUNICATION` while SCO is pinned
 * and `USAGE_MEDIA` otherwise so Android 14+ maintains media-button routing to
 * `OpenChamberPttMediaSession` while screen-locked.
 */
enum class Earcon(val cueName: String) {
    ARMED("armed"),
    REC_START("rec_start"),
    STAGED_PAUSE("staged_pause"),
    SEND_GRACE("send_grace"),
    REC_SENT("rec_sent"),
    TURN_COMPLETE("turn_complete"),
    TURN_INTERRUPTED("turn_interrupted"),
    CANCEL_DISCARD("cancel_discard"),
    UNDO("undo"),
    PERMISSION_NEEDED("permission_needed"),
    AUTH_EXPIRED("auth_expired"),
    ERROR("error"),
    DISARMED("disarmed"),
    ;

    companion object {
        fun fromCueName(name: String): Earcon? = entries.firstOrNull { it.cueName == name }
    }
}

class EarconPlayer internal constructor(
    context: Context? = null,
    @Suppress("unused") private val streamType: Int = AudioManager.STREAM_MUSIC,
    private val volumePercent: Int = DEFAULT_VOLUME_PERCENT,
    private val useCommunicationUsage: () -> Boolean = { false },
    private val onCuePlaybackStarted: ((durationMs: Int) -> Unit)? = null,
    private val onCuePlaybackStopped: (() -> Unit)? = null,
    private val clipObserver: ((Earcon, DecodedClip) -> Unit)? = null,
    private val playbackSpecObserver: ((Earcon, PlaybackSpec) -> Unit)? = null,
) {
    constructor(
        context: Context? = null,
        streamType: Int = AudioManager.STREAM_MUSIC,
        volumePercent: Int = DEFAULT_VOLUME_PERCENT,
        useCommunicationUsage: () -> Boolean = { false },
        onCuePlaybackStarted: ((durationMs: Int) -> Unit)? = null,
        onCuePlaybackStopped: (() -> Unit)? = null,
    ) : this(
        context = context,
        streamType = streamType,
        volumePercent = volumePercent,
        useCommunicationUsage = useCommunicationUsage,
        onCuePlaybackStarted = onCuePlaybackStarted,
        onCuePlaybackStopped = onCuePlaybackStopped,
        clipObserver = null,
        playbackSpecObserver = null,
    )

    private val appContext = context?.applicationContext
    private val lock = Any()
    private var preloadExecutor: ExecutorService? = if (appContext != null) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "EarconPreload").apply { isDaemon = true }
        }
    } else {
        null
    }
    private var playExecutor: ExecutorService? = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EarconPlay").apply { isDaemon = true }
    }
    private var releaseScheduler: ScheduledExecutorService? = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "EarconRelease").apply { isDaemon = true }
    }
    private var activeTrack: AudioTrack? = null
    private var activeReleaseFuture: ScheduledFuture<*>? = null
    private var activeEarcon: Earcon? = null
    private var stopGeneration: Long = 0L
    private var cueActive: Boolean = false
    private val decodedClips = ConcurrentHashMap<Earcon, DecodedClip>()

    @Volatile
    private var released = false

    init {
        if (appContext != null) {
            preloadExecutor?.execute {
                if (!released) {
                    preloadAllClips(appContext)
                }
            }
        }
    }

    fun play(earcon: Earcon) {
        val useCommunication = useCommunicationUsage()
        var wasActive = false
        var myStopGeneration = 0L
        val exec = synchronized(lock) {
            if (released) return
            wasActive = stopActiveTrackLocked()
            activeEarcon = earcon
            myStopGeneration = stopGeneration
            playExecutor
        } ?: return

        if (wasActive) {
            onCuePlaybackStopped?.invoke()
        }

        exec.execute {
            val stillValid = synchronized(lock) { !released && stopGeneration == myStopGeneration }
            if (!stillValid) return@execute
            val clip = decodedClips[earcon] ?: appContext?.let { ctx ->
                decodeRawOgg(ctx, rawResFor(earcon), myStopGeneration)?.also { decodedClips.putIfAbsent(earcon, it) }
            } ?: synthesizeFallbackClip(earcon)
            val activeForPlay = synchronized(lock) { !released && stopGeneration == myStopGeneration }
            if (!activeForPlay) return@execute
            clipObserver?.invoke(earcon, clip)
            playClip(earcon, clip, useCommunication, myStopGeneration)
        }
    }

    fun play(cueName: String): Boolean {
        val earcon = Earcon.fromCueName(cueName) ?: return false
        play(earcon)
        return true
    }

    fun stop() {
        val wasActive = synchronized(lock) {
            stopGeneration += 1L
            stopActiveTrackLocked()
        }
        if (wasActive) {
            onCuePlaybackStopped?.invoke()
        }
    }

    fun stopUnless(keep: Earcon) {
        val wasActive = synchronized(lock) {
            if (activeEarcon == keep) {
                false
            } else {
                stopGeneration += 1L
                stopActiveTrackLocked()
            }
        }
        if (wasActive) {
            onCuePlaybackStopped?.invoke()
        }
    }

    fun release() {
        val preloadToShutdown: ExecutorService?
        val playToShutdown: ExecutorService?
        val schedulerToShutdown: ScheduledExecutorService?
        val wasActive: Boolean
        synchronized(lock) {
            released = true
            stopGeneration += 1L
            preloadToShutdown = preloadExecutor
            preloadExecutor = null
            playToShutdown = playExecutor
            playExecutor = null
            schedulerToShutdown = releaseScheduler
            releaseScheduler = null
            wasActive = stopActiveTrackLocked()
        }
        if (wasActive) {
            onCuePlaybackStopped?.invoke()
        }
        runCatching { preloadToShutdown?.shutdownNow() }
        runCatching { playToShutdown?.shutdownNow() }
        runCatching { schedulerToShutdown?.shutdownNow() }
        decodedClips.clear()
    }

    private fun stopActiveTrackLocked(): Boolean {
        activeReleaseFuture?.cancel(false)
        activeReleaseFuture = null
        activeEarcon = null
        val prev = activeTrack
        activeTrack = null
        val wasCueActive = cueActive
        cueActive = false
        if (prev != null) {
            runCatching { prev.stop() }
            runCatching { prev.release() }
        }
        return wasCueActive
    }

    private fun synthesizeFallbackClip(earcon: Earcon): DecodedClip {
        val spec = toneSpecFor(earcon)
        val pcm = synthesizeSinePcm16(
            frequencyHz = spec.frequencyHz,
            durationMs = spec.durationMs,
            amplitude = (volumePercent.coerceIn(0, 100) / 100.0) * 0.35,
        )
        return DecodedClip(
            pcmBytes = pcm,
            sampleRateHz = SYNTH_SAMPLE_RATE_HZ,
            channelCount = 1,
            durationMs = spec.durationMs,
        )
    }

    private fun preloadAllClips(context: Context) {
        for (earcon in Earcon.entries) {
            if (released) return
            if (decodedClips.containsKey(earcon)) continue
            runCatching {
                decodeRawOgg(context, rawResFor(earcon))?.let { clip ->
                    decodedClips.putIfAbsent(earcon, clip)
                }
            }
        }
    }

    private fun playClip(
        earcon: Earcon,
        clip: DecodedClip,
        useCommunication: Boolean,
        myStopGeneration: Long,
    ) {
        if (clip.pcmBytes.isEmpty()) return
        val spec = playbackSpecFor(useCommunication)
        playbackSpecObserver?.invoke(earcon, spec)
        val channelMask = if (clip.channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(spec.usage)
                        .setContentType(spec.contentType)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(clip.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(clip.pcmBytes.size)
                .build()
        }.getOrNull() ?: return

        var prevWasActive = false
        val scheduler = synchronized(lock) {
            if (released || stopGeneration != myStopGeneration) {
                runCatching { track.release() }
                return
            }
            prevWasActive = stopActiveTrackLocked()
            activeTrack = track
            releaseScheduler
        }
        if (prevWasActive) {
            onCuePlaybackStopped?.invoke()
        }

        val started = try {
            val gain = (volumePercent.coerceIn(0, 100) / 100f)
            runCatching { track.setVolume(gain) }
            track.write(clip.pcmBytes, 0, clip.pcmBytes.size)
            track.play()
            true
        } catch (_: Throwable) {
            false
        }

        if (!started || scheduler == null) {
            synchronized(lock) {
                if (activeTrack === track) {
                    activeTrack = null
                }
            }
            runCatching { track.stop() }
            runCatching { track.release() }
            return
        }

        val shouldNotifyStart = synchronized(lock) {
            if (!released && activeTrack === track && stopGeneration == myStopGeneration) {
                cueActive = true
                true
            } else {
                false
            }
        }
        if (shouldNotifyStart) {
            onCuePlaybackStarted?.invoke(clip.durationMs)
        } else {
            synchronized(lock) {
                if (activeTrack === track) {
                    activeTrack = null
                }
            }
            runCatching { track.stop() }
            runCatching { track.release() }
            return
        }

        val delayMs = clip.durationMs.toLong().coerceAtLeast(40L) + 40L
        val future = runCatching {
            scheduler.schedule(
                {
                    val shouldNotifyStop = synchronized(lock) {
                        if (activeTrack === track) {
                            activeTrack = null
                            activeReleaseFuture = null
                            activeEarcon = null
                            val wasCueActive = cueActive
                            cueActive = false
                            wasCueActive
                        } else {
                            false
                        }
                    }
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    if (shouldNotifyStop) {
                        onCuePlaybackStopped?.invoke()
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }.getOrNull()

        synchronized(lock) {
            if (activeTrack === track) {
                activeReleaseFuture = future
            } else {
                future?.cancel(false)
            }
        }
    }

    private fun decodeRawOgg(
        context: Context,
        @RawRes resId: Int,
        expectedStopGeneration: Long? = null,
    ): DecodedClip? {
        val pcmClip = AudioCodecDecoder.decodeRawResource(
            context = context,
            resId = resId,
            shouldStop = {
                released || (expectedStopGeneration != null && synchronized(lock) { stopGeneration != expectedStopGeneration })
            },
        ) ?: return null
        return DecodedClip(
            pcmBytes = pcmClip.pcmBytes,
            sampleRateHz = pcmClip.sampleRateHz,
            channelCount = pcmClip.channelCount.coerceIn(1, 2),
            durationMs = pcmClip.durationMs.coerceAtLeast(20),
        )
    }

    internal data class DecodedClip(
        val pcmBytes: ByteArray,
        val sampleRateHz: Int,
        val channelCount: Int,
        val durationMs: Int,
    )

    internal data class ToneSpec(
        val frequencyHz: Double,
        val durationMs: Int,
    )

    internal data class PlaybackSpec(
        val usage: Int,
        val contentType: Int,
    )

    companion object {
        private const val DEFAULT_VOLUME_PERCENT = 85
        private const val SYNTH_SAMPLE_RATE_HZ = 16_000

        internal fun playbackSpecFor(useCommunicationUsage: Boolean): PlaybackSpec =
            if (useCommunicationUsage) {
                PlaybackSpec(
                    usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                )
            } else {
                PlaybackSpec(
                    usage = AudioAttributes.USAGE_MEDIA,
                    contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                )
            }

        @RawRes
        internal fun rawResFor(earcon: Earcon): Int = when (earcon) {
            Earcon.ARMED -> R.raw.earcon_armed
            Earcon.REC_START -> R.raw.earcon_rec_start
            Earcon.STAGED_PAUSE -> R.raw.earcon_staged_pause
            Earcon.SEND_GRACE -> R.raw.earcon_send_grace
            Earcon.REC_SENT -> R.raw.earcon_rec_sent
            Earcon.TURN_COMPLETE -> R.raw.earcon_turn_complete
            Earcon.TURN_INTERRUPTED -> R.raw.earcon_turn_interrupted
            Earcon.CANCEL_DISCARD -> R.raw.earcon_cancel_discard
            Earcon.UNDO -> R.raw.earcon_undo
            Earcon.PERMISSION_NEEDED -> R.raw.earcon_permission_needed
            Earcon.AUTH_EXPIRED -> R.raw.earcon_auth_expired
            Earcon.ERROR -> R.raw.earcon_error
            Earcon.DISARMED -> R.raw.earcon_disarmed
        }

        internal fun toneSpecFor(earcon: Earcon): ToneSpec = when (earcon) {
            Earcon.ARMED -> ToneSpec(660.0, 140)
            Earcon.REC_START -> ToneSpec(880.0, 90)
            Earcon.STAGED_PAUSE -> ToneSpec(587.33, 130)
            Earcon.SEND_GRACE -> ToneSpec(523.25, 110)
            Earcon.REC_SENT -> ToneSpec(659.25, 140)
            Earcon.TURN_COMPLETE -> ToneSpec(784.0, 180)
            Earcon.TURN_INTERRUPTED -> ToneSpec(493.88, 120)
            Earcon.CANCEL_DISCARD -> ToneSpec(392.0, 140)
            Earcon.UNDO -> ToneSpec(440.0, 130)
            Earcon.PERMISSION_NEEDED -> ToneSpec(784.0, 220)
            Earcon.AUTH_EXPIRED -> ToneSpec(293.66, 220)
            Earcon.ERROR -> ToneSpec(330.0, 240)
            Earcon.DISARMED -> ToneSpec(440.0, 130)
        }

        internal fun synthesizeSinePcm16(
            frequencyHz: Double,
            durationMs: Int,
            amplitude: Double,
        ): ByteArray {
            val sampleCount = (SYNTH_SAMPLE_RATE_HZ * durationMs / 1000).coerceAtLeast(1)
            val fadeSamples = min(sampleCount / 4, SYNTH_SAMPLE_RATE_HZ / 100)
            val out = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val env = when {
                    fadeSamples > 0 && i < fadeSamples -> i.toDouble() / fadeSamples
                    fadeSamples > 0 && i >= sampleCount - fadeSamples -> (sampleCount - 1 - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                val s = sin(2.0 * PI * frequencyHz * i / SYNTH_SAMPLE_RATE_HZ) * amplitude * env
                val pcmVal = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i * 2] = (pcmVal and 0xFF).toByte()
                out[i * 2 + 1] = ((pcmVal ushr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
