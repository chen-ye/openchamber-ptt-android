package com.openchamber.ptt.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

enum class Earcon(val cueName: String) {
    ARMED("armed"),
    REC_START("rec_start"),
    REC_SENT("rec_sent"),
    PERMISSION_NEEDED("permission_needed"),
    ERROR("error"),
    DISARMED("disarmed"),
    ;

    companion object {
        fun fromCueName(name: String): Earcon? = entries.firstOrNull { it.cueName == name }
    }
}

class EarconPlayer(
    @Suppress("unused") private val streamType: Int = AudioManager.STREAM_MUSIC,
    private val volumePercent: Int = DEFAULT_VOLUME_PERCENT,
) {
    private val lock = Any()
    private var executor: ExecutorService? = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EarconPlayer").apply { isDaemon = true }
    }
    private var activeTrack: AudioTrack? = null
    @Volatile
    private var released = false

    fun play(earcon: Earcon) {
        val spec = toneSpecFor(earcon)
        val exec = synchronized(lock) {
            if (released) return
            executor
        } ?: return

        exec.execute {
            if (released) return@execute
            playTonePcm(spec)
        }
    }

    fun play(cueName: String): Boolean {
        val earcon = Earcon.fromCueName(cueName) ?: return false
        play(earcon)
        return true
    }

    fun release() {
        val execToShutdown: ExecutorService?
        val trackToStop: AudioTrack?
        synchronized(lock) {
            released = true
            execToShutdown = executor
            executor = null
            trackToStop = activeTrack
            activeTrack = null
        }
        runCatching { trackToStop?.stop() }
        runCatching { trackToStop?.release() }
        runCatching { execToShutdown?.shutdownNow() }
    }

    private fun playTonePcm(spec: ToneSpec) {
        val pcm = synthesizeSinePcm16(
            frequencyHz = spec.frequencyHz,
            durationMs = spec.durationMs,
            amplitude = (volumePercent.coerceIn(0, 100) / 100.0) * 0.35,
        )
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
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
        }.getOrNull() ?: return

        synchronized(lock) {
            if (released) {
                runCatching { track.release() }
                return
            }
            val prev = activeTrack
            activeTrack = track
            runCatching { prev?.stop() }
            runCatching { prev?.release() }
        }

        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(spec.durationMs.toLong() + 50L)
        } catch (_: Throwable) {
        } finally {
            synchronized(lock) {
                if (activeTrack === track) {
                    activeTrack = null
                }
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    internal data class ToneSpec(
        val frequencyHz: Double,
        val durationMs: Int,
    )

    companion object {
        private const val DEFAULT_VOLUME_PERCENT = 80
        private const val SAMPLE_RATE_HZ = 16_000

        internal fun toneSpecFor(earcon: Earcon): ToneSpec = when (earcon) {
            Earcon.ARMED -> ToneSpec(660.0, 160)
            Earcon.REC_START -> ToneSpec(880.0, 90)
            Earcon.REC_SENT -> ToneSpec(523.25, 140)
            Earcon.PERMISSION_NEEDED -> ToneSpec(784.0, 240)
            Earcon.ERROR -> ToneSpec(330.0, 280)
            Earcon.DISARMED -> ToneSpec(440.0, 130)
        }

        internal fun synthesizeSinePcm16(
            frequencyHz: Double,
            durationMs: Int,
            amplitude: Double,
        ): ByteArray {
            val sampleCount = (SAMPLE_RATE_HZ * durationMs / 1000).coerceAtLeast(1)
            val fadeSamples = min(sampleCount / 4, SAMPLE_RATE_HZ / 100)
            val out = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val env = when {
                    fadeSamples > 0 && i < fadeSamples -> i.toDouble() / fadeSamples
                    fadeSamples > 0 && i >= sampleCount - fadeSamples -> (sampleCount - 1 - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                val s = sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE_HZ) * amplitude * env
                val pcmVal = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i * 2] = (pcmVal and 0xFF).toByte()
                out[i * 2 + 1] = ((pcmVal ushr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
