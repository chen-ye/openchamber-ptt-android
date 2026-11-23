package com.openchamber.ptt.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.RawRes
import com.openchamber.ptt.tts.WavAudio
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

data class PcmClip(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val durationMs: Int,
) {
    fun toWavAudio(): WavAudio = WavAudio(
        sampleRate = sampleRateHz,
        channels = channelCount,
        bitsPerSample = 16,
        pcmData = pcmBytes,
        audioFormat = WavAudio.FORMAT_PCM,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmClip) return false
        return sampleRateHz == other.sampleRateHz &&
            channelCount == other.channelCount &&
            durationMs == other.durationMs &&
            pcmBytes.contentEquals(other.pcmBytes)
    }

    override fun hashCode(): Int {
        var result = pcmBytes.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channelCount
        result = 31 * result + durationMs
        return result
    }
}

object AudioCodecDecoder {
    private const val TAG = "AudioCodecDecoder"
    private const val CODEC_TIMEOUT_US = 10_000L
    internal const val MAX_POST_EOS_POLLS = 500

    fun decodeRawResource(
        context: Context,
        @RawRes resId: Int,
        shouldStop: () -> Boolean = { false },
    ): PcmClip? {
        val extractor = MediaExtractor()
        return try {
            context.resources.openRawResourceFd(resId).use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            decodeFromExtractor(extractor, shouldStop)
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Failed decoding raw resource $resId", t) }
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun decodeEncodedBytes(
        bytes: ByteArray,
        mimeHint: String? = null,
        shouldStop: () -> Boolean = { false },
    ): PcmClip {
        require(bytes.isNotEmpty()) { "Cannot decode empty audio payload" }
        val extractor = MediaExtractor()
        val dataSource = ByteArrayMediaDataSource(bytes)
        return try {
            extractor.setDataSource(dataSource)
            decodeFromExtractor(extractor, shouldStop)
                ?: throw IllegalStateException(
                    "Failed to decode encoded audio${mimeHint?.let { " ($it)" }.orEmpty()}",
                )
        } finally {
            runCatching { extractor.release() }
            runCatching { dataSource.close() }
        }
    }

    private fun decodeFromExtractor(
        extractor: MediaExtractor,
        shouldStop: () -> Boolean,
    ): PcmClip? {
        var codec: MediaCodec? = null
        try {
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
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var postEosPolls = 0

            while (!sawOutputEos && !shouldStop()) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
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

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIdx)
                        val producedBytes = bufferInfo.size
                        if (outBuffer != null && producedBytes > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + producedBytes)
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floatBuffer = outBuffer.slice().order(outBuffer.order()).asFloatBuffer()
                                val floatCount = floatBuffer.remaining()
                                val pcm16Chunk = ByteArray(floatCount * 2)
                                for (i in 0 until floatCount) {
                                    val clamped = floatBuffer.get(i).coerceIn(-1f, 1f)
                                    val sample = (clamped * Short.MAX_VALUE)
                                        .roundToInt()
                                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                    pcm16Chunk[i * 2] = (sample and 0xFF).toByte()
                                    pcm16Chunk[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
                                }
                                pcmOut.write(pcm16Chunk)
                            } else {
                                val chunk = ByteArray(producedBytes)
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
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        } else if (sawInputEos && producedBytes <= 0) {
                            postEosPolls += 1
                            if (postEosPolls >= MAX_POST_EOS_POLLS) {
                                break
                            }
                        } else if (producedBytes > 0) {
                            postEosPolls = 0
                        }
                    }
                    sawInputEos -> {
                        postEosPolls += 1
                        if (postEosPolls >= MAX_POST_EOS_POLLS) {
                            break
                        }
                    }
                }
            }

            if (shouldStop() && !sawOutputEos) return null
            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.isEmpty()) return null
            val normalizedChannels = channelCount.coerceAtLeast(1)
            val bytesPerSecond = sampleRate * normalizedChannels * 2
            val durationMs = if (bytesPerSecond > 0) {
                ((pcmBytes.size * 1000L) / bytesPerSecond).toInt().coerceAtLeast(1)
            } else {
                0
            }
            return PcmClip(
                pcmBytes = pcmBytes,
                sampleRateHz = sampleRate,
                channelCount = normalizedChannels,
                durationMs = durationMs,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private class ByteArrayMediaDataSource(
        private val data: ByteArray,
    ) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= data.size.toLong()) return -1
            if (size <= 0) return 0
            val start = position.toInt()
            val toCopy = minOf(size, data.size - start)
            System.arraycopy(data, start, buffer, offset, toCopy)
            return toCopy
        }

        override fun close() = Unit
    }
}

object AudioPreroll {
    const val DEFAULT_PREROLL_SEED = 0x5EE1D

    fun prependDitheredPreroll(
        wav: WavAudio,
        prerollMs: Int,
        ditherPeakLsb: Int = 3,
        seed: Int = DEFAULT_PREROLL_SEED,
    ): WavAudio {
        if (prerollMs <= 0 || wav.pcmData.isEmpty() || wav.sampleRate <= 0 || wav.channels <= 0) {
            return wav
        }
        val prerollFrames = ((wav.sampleRate.toLong() * prerollMs) / 1000L).toInt()
        if (prerollFrames <= 0) return wav

        val prerollBytes = if (wav.audioFormat == WavAudio.FORMAT_PCM && wav.bitsPerSample == 16) {
            generateTpdfDitherPcm16(
                sampleCount = prerollFrames * wav.channels,
                ditherPeakLsb = ditherPeakLsb,
                seed = seed,
            )
        } else {
            if (wav.bitsPerSample <= 0 || wav.bitsPerSample % 8 != 0) {
                return wav
            }
            val frameSize = wav.channels * (wav.bitsPerSample / 8)
            val bytes = ByteArray(prerollFrames * frameSize)
            if (wav.audioFormat == WavAudio.FORMAT_PCM && wav.bitsPerSample == 8) {
                bytes.fill(0x80.toByte())
            }
            bytes
        }

        val combined = ByteArray(prerollBytes.size + wav.pcmData.size)
        System.arraycopy(prerollBytes, 0, combined, 0, prerollBytes.size)
        System.arraycopy(wav.pcmData, 0, combined, prerollBytes.size, wav.pcmData.size)
        return wav.copy(pcmData = combined)
    }

    fun createDitheredSilenceWav(
        durationMs: Int,
        sampleRate: Int = 24_000,
        channels: Int = 1,
        ditherPeakLsb: Int = 3,
        seed: Int = DEFAULT_PREROLL_SEED,
    ): WavAudio {
        val safeRate = sampleRate.coerceAtLeast(1)
        val safeChannels = channels.coerceAtLeast(1)
        if (durationMs <= 0 || sampleRate <= 0 || channels <= 0) {
            return WavAudio(
                sampleRate = safeRate,
                channels = safeChannels,
                bitsPerSample = 16,
                pcmData = ByteArray(0),
                audioFormat = WavAudio.FORMAT_PCM,
            )
        }
        val frames = ((safeRate.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(1)
        val pcm = generateTpdfDitherPcm16(
            sampleCount = frames * safeChannels,
            ditherPeakLsb = ditherPeakLsb,
            seed = seed,
        )
        return WavAudio(
            sampleRate = safeRate,
            channels = safeChannels,
            bitsPerSample = 16,
            pcmData = pcm,
            audioFormat = WavAudio.FORMAT_PCM,
        )
    }

    private fun generateTpdfDitherPcm16(
        sampleCount: Int,
        ditherPeakLsb: Int,
        seed: Int,
    ): ByteArray {
        val out = ByteArray(sampleCount * 2)
        if (ditherPeakLsb <= 0) return out
        val rng = Random(seed)
        val bound = ditherPeakLsb + 1
        for (i in 0 until sampleCount) {
            val r1 = rng.nextInt(bound)
            val r2 = rng.nextInt(bound)
            val sample = (r1 - r2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        return out
    }
}
