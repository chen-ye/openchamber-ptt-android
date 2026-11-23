package com.openchamber.ptt.audio

import com.openchamber.ptt.tts.WavAudio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class AudioOutputCoordinatorTest {
    @Test
    fun `prependDitheredPreroll prepends deterministic bounded TPDF dither and preserves speech bytes`() {
        val speechPcm = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08)
        val wav = WavAudio(
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            pcmData = speechPcm,
            audioFormat = WavAudio.FORMAT_PCM,
        )

        val out1 = AudioPreroll.prependDitheredPreroll(wav, prerollMs = 160)
        val out2 = AudioPreroll.prependDitheredPreroll(wav, prerollMs = 160)

        val expectedPrerollSamples = (24_000 * 160 / 1000) * 1
        val expectedPrerollBytes = expectedPrerollSamples * 2
        assertEquals(expectedPrerollBytes + speechPcm.size, out1.pcmData.size)
        assertArrayEquals(out1.pcmData, out2.pcmData)

        var sumSquares = 0.0
        for (i in 0 until expectedPrerollSamples) {
            val lo = out1.pcmData[i * 2].toInt() and 0xFF
            val hi = out1.pcmData[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            assertTrue("Sample $sample exceeded ±3 LSB", sample in -3..3)
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sumSquares / expectedPrerollSamples)
        assertTrue("Expected non-zero RMS for TPDF dither, got $rms", rms > 0.5)

        val trailing = out1.pcmData.copyOfRange(expectedPrerollBytes, out1.pcmData.size)
        assertArrayEquals(speechPcm, trailing)
    }

    @Test
    fun `prependDitheredPreroll handles stereo, edge cases, and non-16-bit formats with frame alignment`() {
        val emptyWav = WavAudio(24_000, 1, 16, ByteArray(0), WavAudio.FORMAT_PCM)
        assertSame(emptyWav, AudioPreroll.prependDitheredPreroll(emptyWav, 160))

        val speechStereo = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stereoWav = WavAudio(48_000, 2, 16, speechStereo, WavAudio.FORMAT_PCM)
        assertSame(stereoWav, AudioPreroll.prependDitheredPreroll(stereoWav, prerollMs = 0))
        val stereoOut = AudioPreroll.prependDitheredPreroll(stereoWav, prerollMs = 160)
        val expectedStereoPrerollBytes = (48_000 * 160 / 1000) * 2 * 2
        assertEquals(expectedStereoPrerollBytes + speechStereo.size, stereoOut.pcmData.size)
        assertArrayEquals(
            speechStereo,
            stereoOut.pcmData.copyOfRange(expectedStereoPrerollBytes, stereoOut.pcmData.size),
        )

        val pcm8Speech = byteArrayOf(0x70, 0x90.toByte())
        val pcm8 = WavAudio(16_000, 1, 8, pcm8Speech, WavAudio.FORMAT_PCM)
        val pcm8Out = AudioPreroll.prependDitheredPreroll(pcm8, prerollMs = 10)
        val expected8PrerollBytes = (16_000 * 10 / 1000) * 1
        assertEquals(expected8PrerollBytes + pcm8Speech.size, pcm8Out.pcmData.size)
        for (i in 0 until expected8PrerollBytes) {
            assertEquals(0x80.toByte(), pcm8Out.pcmData[i])
        }
        assertArrayEquals(
            pcm8Speech,
            pcm8Out.pcmData.copyOfRange(expected8PrerollBytes, pcm8Out.pcmData.size),
        )

        val floatSpeech = ByteArray(8) { 0x3F }
        val floatWav = WavAudio(24_000, 2, 32, floatSpeech, WavAudio.FORMAT_IEEE_FLOAT)
        val floatOut = AudioPreroll.prependDitheredPreroll(floatWav, prerollMs = 10)
        val expectedFloatPrerollBytes = (24_000 * 10 / 1000) * 2 * 4
        assertEquals(expectedFloatPrerollBytes + floatSpeech.size, floatOut.pcmData.size)
        assertEquals(0, floatOut.pcmData.size % 8)
        assertArrayEquals(
            floatSpeech,
            floatOut.pcmData.copyOfRange(expectedFloatPrerollBytes, floatOut.pcmData.size),
        )
    }

    @Test
    fun `coordinator awaits active cue drain plus 70ms gap and resolves warm sink preroll as 0`() = runTest {
        val coordinator = AudioOutputCoordinator(clockElapsedMs = { testScheduler.currentTime })

        coordinator.onCueStarted(durationMs = 200)
        advanceTimeBy(50L)
        runCurrent()

        val deferred = async {
            coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = false)
        }
        runCurrent()
        // 150ms remaining + 70ms gap = 220ms wait
        assertTrue(!deferred.isCompleted)

        advanceTimeBy(219L)
        runCurrent()
        assertTrue(!deferred.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertEquals(0, deferred.await())
        assertEquals(270L, testScheduler.currentTime)
    }

    @Test
    fun `coordinator returns 160ms preroll when cold on A2DP and 0ms when warm or SCO pinned`() = runTest {
        var nowMs = 1_000L
        val coordinator = AudioOutputCoordinator(clockElapsedMs = { nowMs })

        // SCO pinned never adds A2DP cold-sink preroll
        assertEquals(0, coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = true))

        // Cold A2DP sink returns 160ms
        assertEquals(160, coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = false))

        coordinator.onSpeechPlaybackStarted()
        nowMs += 500L
        coordinator.onSpeechPlaybackFinished()

        // Warm within 2500ms threshold -> 0ms
        nowMs += 2_500L
        assertEquals(0, coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = false))

        // Idle past 2500ms threshold -> 160ms again
        nowMs += 2_501L
        assertEquals(160, coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = false))
    }

    @Test
    fun `stopAll clears active cue end time immediately`() = runTest {
        val coordinator = AudioOutputCoordinator(clockElapsedMs = { testScheduler.currentTime })
        coordinator.onCueStarted(durationMs = 500)
        coordinator.stopAll()

        val preroll = coordinator.awaitCueDrainAndResolvePrerollMs(useCommunicationUsage = false)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0, preroll)
    }
}
