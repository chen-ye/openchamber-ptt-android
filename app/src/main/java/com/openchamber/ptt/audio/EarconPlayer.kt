package com.openchamber.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.RawRes
import com.openchamber.ptt.R
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
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
 * played via [AudioTrack] (`USAGE_MEDIA`, `MODE_STATIC`) so Android 14+ maintains media-button
 * routing to `OpenChamberPttMediaSession` while screen-locked.
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
    private val clipObserver: ((Earcon, DecodedClip) -> Unit)? = null,
) {
    constructor(
        context: Context? = null,
        streamType: Int = AudioManager.STREAM_MUSIC,
        volumePercent: Int = DEFAULT_VOLUME_PERCENT,
    ) : this(
        context = context,
        streamType = streamType,
        volumePercent = volumePercent,
        clipObserver = null,
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
        val exec = synchronized(lock) {
            if (released) return
            stopActiveTrackLocked()
            playExecutor
        } ?: return

        exec.execute {
            if (released) return@execute
            val clip = decodedClips[earcon] ?: appContext?.let { ctx ->
                decodeRawOgg(ctx, rawResFor(earcon))?.also { decodedClips.putIfAbsent(earcon, it) }
            } ?: synthesizeFallbackClip(earcon)
            clipObserver?.invoke(earcon, clip)
            playClip(clip)
        }
    }

    fun play(cueName: String): Boolean {
        val earcon = Earcon.fromCueName(cueName) ?: return false
        play(earcon)
        return true
    }

    fun release() {
        val preloadToShutdown: ExecutorService?
        val playToShutdown: ExecutorService?
        val schedulerToShutdown: ScheduledExecutorService?
        synchronized(lock) {
            released = true
            preloadToShutdown = preloadExecutor
            preloadExecutor = null
            playToShutdown = playExecutor
            playExecutor = null
            schedulerToShutdown = releaseScheduler
            releaseScheduler = null
            stopActiveTrackLocked()
        }
        runCatching { preloadToShutdown?.shutdownNow() }
        runCatching { playToShutdown?.shutdownNow() }
        runCatching { schedulerToShutdown?.shutdownNow() }
        decodedClips.clear()
    }

    private fun stopActiveTrackLocked() {
        activeReleaseFuture?.cancel(false)
        activeReleaseFuture = null
        val prev = activeTrack
        activeTrack = null
        if (prev != null) {
            runCatching { prev.stop() }
            runCatching { prev.release() }
        }
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

    private fun playClip(clip: DecodedClip) {
        if (clip.pcmBytes.isEmpty()) return
        val channelMask = if (clip.channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

        val scheduler = synchronized(lock) {
            if (released) {
                runCatching { track.release() }
                return
            }
            stopActiveTrackLocked()
            activeTrack = track
            releaseScheduler
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

        val delayMs = clip.durationMs.toLong().coerceAtLeast(40L) + 40L
        val future = runCatching {
            scheduler.schedule(
                {
                    synchronized(lock) {
                        if (activeTrack === track) {
                            activeTrack = null
                            activeReleaseFuture = null
                        }
                    }
                    runCatching { track.stop() }
                    runCatching { track.release() }
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

    private fun decodeRawOgg(context: Context, @RawRes resId: Int): DecodedClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            context.resources.openRawResourceFd(resId).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) return null
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && !released) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIdx)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEos = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIdx)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.get(chunk)
                            // Ensure little-endian PCM16 order if platform buffer used big-endian
                            if (outBuffer.order() == ByteOrder.BIG_ENDIAN) {
                                var i = 0
                                while (i + 1 < chunk.size) {
                                    val tmp = chunk[i]
                                    chunk[i] = chunk[i + 1]
                                    chunk[i + 1] = tmp
                                    i += 2
                                }
                            }
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.isEmpty()) return null
            val bytesPerSecond = sampleRate * channelCount.coerceAtLeast(1) * 2
            val durationMs = if (bytesPerSecond > 0) {
                ((pcmBytes.size * 1000L) / bytesPerSecond).toInt().coerceAtLeast(20)
            } else {
                250
            }
            DecodedClip(
                pcmBytes = pcmBytes,
                sampleRateHz = sampleRate,
                channelCount = channelCount.coerceIn(1, 2),
                durationMs = durationMs,
            )
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Failed decoding earcon raw resource $resId; using fallback tone", t) }
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
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

    companion object {
        private const val TAG = "EarconPlayer"
        private const val DEFAULT_VOLUME_PERCENT = 85
        private const val SYNTH_SAMPLE_RATE_HZ = 16_000

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
