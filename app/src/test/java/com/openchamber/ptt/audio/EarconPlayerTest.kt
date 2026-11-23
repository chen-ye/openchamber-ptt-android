package com.openchamber.ptt.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EarconPlayerTest {
    @Test
    fun `cueName lookup round-trips every Earcon entry and rejects unknown names`() {
        for (earcon in Earcon.entries) {
            assertEquals(earcon, Earcon.fromCueName(earcon.cueName))
        }
        assertNull(Earcon.fromCueName("nonexistent_cue"))
    }

    @Test
    fun `JVM fallback synthesizes non-empty PCM16 clips for all cues and stops after release`() {
        val played = CopyOnWriteArrayList<Pair<Earcon, EarconPlayer.DecodedClip>>()
        val latch = CountDownLatch(Earcon.entries.size)
        val player = EarconPlayer(
            context = null,
            clipObserver = { earcon, clip ->
                played.add(earcon to clip)
                latch.countDown()
            },
        )
        try {
            for (earcon in Earcon.entries) {
                assertTrue(player.play(earcon.cueName))
            }
            assertTrue("Expected all fallback earcons to dispatch", latch.await(3, TimeUnit.SECONDS))
            assertEquals(Earcon.entries.size, played.size)
            for ((_, clip) in played) {
                assertTrue(clip.pcmBytes.isNotEmpty())
                assertEquals(0, clip.pcmBytes.size % 2)
                assertEquals(16_000, clip.sampleRateHz)
                assertEquals(1, clip.channelCount)
                assertTrue(clip.durationMs >= 90)
            }
            assertFalse(player.play("unknown_cue"))
        } finally {
            player.release()
        }

        // Calls after release are ignored cleanly
        val afterReleaseCount = played.size
        player.play(Earcon.ARMED)
        Thread.sleep(50L)
        assertEquals(afterReleaseCount, played.size)
        assertNotNull(EarconPlayer.toneSpecFor(Earcon.ARMED))
    }

    @Test
    fun `playbackSpec aligns EarconPlayer usage with isScoPinned state`() {
        assertEquals(
            EarconPlayer.PlaybackSpec(
                usage = AudioAttributes.USAGE_MEDIA,
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
            ),
            EarconPlayer.playbackSpecFor(useCommunicationUsage = false),
        )
        assertEquals(
            EarconPlayer.PlaybackSpec(
                usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
            ),
            EarconPlayer.playbackSpecFor(useCommunicationUsage = true),
        )

        val scoPinned = AtomicBoolean(false)
        val observedSpecs = CopyOnWriteArrayList<Pair<Earcon, EarconPlayer.PlaybackSpec>>()
        val latch = CountDownLatch(2)
        val player = EarconPlayer(
            context = null,
            useCommunicationUsage = { scoPinned.get() },
            playbackSpecObserver = { earcon, spec ->
                observedSpecs.add(earcon to spec)
                latch.countDown()
            },
        )
        try {
            player.play(Earcon.ARMED)
            scoPinned.set(true)
            player.play(Earcon.REC_START)

            assertTrue("Expected both earcon playback specs", latch.await(3, TimeUnit.SECONDS))
            assertEquals(2, observedSpecs.size)
            assertEquals(
                Earcon.ARMED to EarconPlayer.PlaybackSpec(
                    usage = AudioAttributes.USAGE_MEDIA,
                    contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                ),
                observedSpecs[0],
            )
            assertEquals(
                Earcon.REC_START to EarconPlayer.PlaybackSpec(
                    usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                ),
                observedSpecs[1],
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `public constructor accepts cue lifecycle hooks and stop is safe without active track`() {
        val player = EarconPlayer(
            context = null,
            useCommunicationUsage = { false },
            onCuePlaybackStarted = { _ -> },
            onCuePlaybackStopped = {},
        )
        try {
            player.play(Earcon.ARMED)
            player.stop()
            player.stop()
        } finally {
            player.release()
        }
    }
}
