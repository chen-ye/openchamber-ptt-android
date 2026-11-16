package com.openchamber.ptt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
}
