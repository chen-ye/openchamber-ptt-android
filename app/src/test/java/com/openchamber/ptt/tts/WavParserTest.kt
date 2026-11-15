package com.openchamber.ptt.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavParserTest {
    @Test
    fun `parses standard 44-byte mono PCM WAV header`() {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val wav = buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = pcm,
        )

        assertEquals(44 + pcm.size, wav.size)
        val parsed = WavParser.parse(wav)

        assertEquals(24000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertEquals(WavAudio.FORMAT_PCM, parsed.audioFormat)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun `parses stereo 44100Hz WAV header`() {
        val pcm = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val wav = buildStandardWav(
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 16,
            pcm = pcm,
        )

        val parsed = WavParser.parse(wav)

        assertEquals(44100, parsed.sampleRate)
        assertEquals(2, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun `parses extended chunked WAV with odd-sized metadata and 18-byte fmt chunk`() {
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val out = ByteArrayOutputStream()

        // Placeholder RIFF header; patched after chunks are written
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(0))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        // JUNK chunk (even size 4)
        out.write("JUNK".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(4))
        out.write(byteArrayOf(0, 0, 0, 0))

        // Odd-sized LIST chunk (5 bytes + 1 padding byte per RIFF spec)
        out.write("LIST".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(5))
        out.write("hello".toByteArray(Charsets.US_ASCII))
        out.write(0) // RIFF 2-byte word alignment pad byte

        // Extended 18-byte fmt chunk (cbSize = 0)
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(18))
        out.write(int16LE(1)) // PCM
        out.write(int16LE(1)) // Mono
        out.write(int32LE(22050))
        out.write(int32LE(22050 * 2))
        out.write(int16LE(2))
        out.write(int16LE(16))
        out.write(int16LE(0)) // cbSize

        // fact chunk
        out.write("fact".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(4))
        out.write(int32LE(2))

        // data chunk
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(pcm.size))
        out.write(pcm)

        val wav = out.toByteArray()
        val riffSize = int32LE(wav.size - 8)
        System.arraycopy(riffSize, 0, wav, 4, 4)

        val parsed = WavParser.parse(wav)

        assertEquals(22050, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun `parses streaming WAV with 0xFFFFFFFF sentinel data chunk size`() {
        val pcm = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val wav = buildStandardWav(
            sampleRate = 16000,
            channels = 1,
            bitsPerSample = 16,
            pcm = pcm,
        )
        // Overwrite RIFF size and data chunk size with 0xFFFFFFFF
        val sentinel = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        System.arraycopy(sentinel, 0, wav, 4, 4)
        System.arraycopy(sentinel, 0, wav, 40, 4)

        val parsed = WavParser.parse(wav)

        assertEquals(16000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun `parses WAVE_FORMAT_EXTENSIBLE PCM fmt chunk`() {
        val pcm = byteArrayOf(0x01, 0x00, 0x02, 0x00)
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(0))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(40))
        out.write(int16LE(0xFFFE)) // WAVE_FORMAT_EXTENSIBLE
        out.write(int16LE(1))
        out.write(int32LE(48000))
        out.write(int32LE(48000 * 2))
        out.write(int16LE(2))
        out.write(int16LE(16))
        out.write(int16LE(22)) // cbSize
        out.write(int16LE(16)) // validBitsPerSample
        out.write(int32LE(4)) // channelMask
        // SubFormat GUID starting with 0x00000001 (PCM)
        out.write(int16LE(1))
        out.write(ByteArray(14))

        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(pcm.size))
        out.write(pcm)

        val wav = out.toByteArray()
        System.arraycopy(int32LE(wav.size - 8), 0, wav, 4, 4)

        val parsed = WavParser.parse(wav)
        assertEquals(48000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertEquals(WavAudio.FORMAT_PCM, parsed.audioFormat)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun `throws WavParseException on invalid or incomplete WAV inputs`() {
        assertThrowsWavParseException(byteArrayOf())
        assertThrowsWavParseException("NOT_A_WAV_HEADER".toByteArray())

        // Missing data chunk
        val noData = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(int32LE(28))
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write("fmt ".toByteArray(Charsets.US_ASCII))
            write(int32LE(16))
            write(int16LE(1))
            write(int16LE(1))
            write(int32LE(16000))
            write(int32LE(32000))
            write(int16LE(2))
            write(int16LE(16))
        }.toByteArray()
        assertThrowsWavParseException(noData)

        // Unsupported audio format (e.g. A-law = 6)
        val alaw = buildStandardWav(16000, 1, 8, byteArrayOf(1, 2, 3, 4)).also {
            System.arraycopy(int16LE(6), 0, it, 20, 2)
        }
        assertThrowsWavParseException(alaw)

        // Truncated data chunk
        val truncated = buildStandardWav(16000, 1, 16, byteArrayOf(1, 2, 3, 4)).copyOf(46)
        assertThrowsWavParseException(truncated)
    }

    private fun assertThrowsWavParseException(bytes: ByteArray) {
        try {
            WavParser.parse(bytes)
            fail("Expected WavParseException")
        } catch (_: WavParseException) {
            // expected
        }
    }

    companion object {
        fun buildStandardWav(
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
            pcm: ByteArray,
        ): ByteArray {
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)
            val buffer = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(36 + pcm.size)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16)
            buffer.putShort(1)
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(pcm.size)
            buffer.put(pcm)
            return buffer.array()
        }

        private fun int16LE(value: Int): ByteArray =
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

        private fun int32LE(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
