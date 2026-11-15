package com.openchamber.ptt.tts

class WavParseException(message: String) : IllegalArgumentException(message)

data class WavAudio(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmData: ByteArray,
    val audioFormat: Int = FORMAT_PCM,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WavAudio) return false
        return sampleRate == other.sampleRate &&
            channels == other.channels &&
            bitsPerSample == other.bitsPerSample &&
            audioFormat == other.audioFormat &&
            pcmData.contentEquals(other.pcmData)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + audioFormat
        result = 31 * result + pcmData.contentHashCode()
        return result
    }

    companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_IEEE_FLOAT = 3
        const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}

object WavParser {
    private const val MIN_RIFF_HEADER_BYTES = 12
    private const val MIN_FMT_CHUNK_BYTES = 16
    private const val EXTENSIBLE_FMT_MIN_BYTES = 40
    private const val STREAMING_SIZE_SENTINEL_THRESHOLD = 0x7FFF0000L

    fun parse(bytes: ByteArray): WavAudio {
        if (bytes.size < MIN_RIFF_HEADER_BYTES) {
            throw WavParseException("WAV data too short (${bytes.size} bytes)")
        }

        if (!matchesFourCc(bytes, 0, "RIFF") || !matchesFourCc(bytes, 8, "WAVE")) {
            throw WavParseException("Invalid WAV header: expected RIFF/WAVE")
        }

        var foundFmt = false
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcmData: ByteArray? = null

        var offset = MIN_RIFF_HEADER_BYTES
        while (offset + 8 <= bytes.size) {
            val chunkId = readFourCc(bytes, offset)
            val chunkSize = readUInt32LE(bytes, offset + 4)
            val payloadStart = offset + 8
            val available = (bytes.size - payloadStart).toLong()

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < MIN_FMT_CHUNK_BYTES || available < MIN_FMT_CHUNK_BYTES) {
                        throw WavParseException("Invalid WAV 'fmt ' chunk size: $chunkSize")
                    }
                    val rawFormat = readUInt16LE(bytes, payloadStart)
                    channels = readUInt16LE(bytes, payloadStart + 2)
                    val rawSampleRate = readUInt32LE(bytes, payloadStart + 4)
                    bitsPerSample = readUInt16LE(bytes, payloadStart + 14)

                    audioFormat = if (rawFormat == WavAudio.FORMAT_EXTENSIBLE) {
                        if (chunkSize < EXTENSIBLE_FMT_MIN_BYTES || available < EXTENSIBLE_FMT_MIN_BYTES) {
                            throw WavParseException("Truncated WAVE_FORMAT_EXTENSIBLE 'fmt ' chunk")
                        }
                        readUInt16LE(bytes, payloadStart + 24)
                    } else {
                        rawFormat
                    }

                    if (audioFormat != WavAudio.FORMAT_PCM && audioFormat != WavAudio.FORMAT_IEEE_FLOAT) {
                        throw WavParseException("Unsupported WAV audio format: $audioFormat")
                    }
                    if (channels <= 0) {
                        throw WavParseException("Invalid WAV channel count: $channels")
                    }
                    if (rawSampleRate <= 0L || rawSampleRate > Int.MAX_VALUE) {
                        throw WavParseException("Invalid WAV sample rate: $rawSampleRate")
                    }
                    if (bitsPerSample <= 0) {
                        throw WavParseException("Invalid WAV bits per sample: $bitsPerSample")
                    }

                    sampleRate = rawSampleRate.toInt()
                    foundFmt = true
                }

                "data" -> {
                    val dataLength = if (chunkSize >= STREAMING_SIZE_SENTINEL_THRESHOLD) {
                        available
                    } else {
                        if (chunkSize > available) {
                            throw WavParseException(
                                "WAV 'data' chunk truncated: declared $chunkSize bytes, only $available available",
                            )
                        }
                        chunkSize
                    }
                    val end = payloadStart + dataLength.toInt()
                    pcmData = bytes.copyOfRange(payloadStart, end)
                }
            }

            if (chunkSize >= STREAMING_SIZE_SENTINEL_THRESHOLD) {
                break
            }

            // RIFF chunks are word-aligned (padded to an even byte boundary).
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextOffset = payloadStart.toLong() + paddedSize
            if (nextOffset <= offset.toLong() || nextOffset > bytes.size.toLong()) {
                break
            }
            offset = nextOffset.toInt()
        }

        if (!foundFmt) {
            throw WavParseException("Missing WAV 'fmt ' chunk")
        }
        val extractedPcm = pcmData ?: throw WavParseException("Missing WAV 'data' chunk")

        return WavAudio(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            pcmData = extractedPcm,
            audioFormat = audioFormat,
        )
    }

    private fun matchesFourCc(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + 4 > bytes.size) return false
        return bytes[offset] == tag[0].code.toByte() &&
            bytes[offset + 1] == tag[1].code.toByte() &&
            bytes[offset + 2] == tag[2].code.toByte() &&
            bytes[offset + 3] == tag[3].code.toByte()
    }

    private fun readFourCc(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
}
