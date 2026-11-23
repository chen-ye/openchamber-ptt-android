package com.openchamber.ptt.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TtsCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `textPrepCache isolates detail levels, ignores detailLevel for SANITIZED, skips RAW and short plain text, and persists across restart`() {
        val root = tempFolder.newFolder("tts_cache")
        var nowMs = 1_000L
        val cache = TtsCache(rootDir = root, wallClockMs = { nowMs })
        val longText = "Detailed build output: ".repeat(12)

        assertNull(cache.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
        assertNull(cache.getPreparedText("Short plain prose.", TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
        assertNull(cache.getPreparedText(longText, TtsInputMode.RAW, SpokenDetailLevel.BALANCED))

        cache.putPreparedText(longText, TtsInputMode.RAW, SpokenDetailLevel.BALANCED, "ignored")
        cache.putPreparedText("Short plain prose.", TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, "ignored")
        assertNull(cache.getPreparedText(longText, TtsInputMode.RAW, SpokenDetailLevel.BALANCED))
        assertNull(cache.getPreparedText("Short plain prose.", TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))

        cache.putPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.ESSENTIALS, "Essentials prose.")
        cache.putPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, "Balanced prose.")
        cache.putPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.DETAILED, "Detailed prose.")
        cache.putPreparedText(longText, TtsInputMode.SANITIZED, SpokenDetailLevel.ESSENTIALS, "Sanitized prose.")

        assertEquals("Essentials prose.", cache.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.ESSENTIALS))
        assertEquals("Balanced prose.", cache.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
        assertEquals("Detailed prose.", cache.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.DETAILED))
        assertEquals("Sanitized prose.", cache.getPreparedText(longText, TtsInputMode.SANITIZED, SpokenDetailLevel.DETAILED))

        cache.close()

        // Brand-new instance pointing at same rootDir simulates app restart
        val restarted = TtsCache(rootDir = root, wallClockMs = { nowMs })
        try {
            assertEquals("Essentials prose.", restarted.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.ESSENTIALS))
            assertEquals("Balanced prose.", restarted.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
            assertEquals("Detailed prose.", restarted.getPreparedText(longText, TtsInputMode.SUMMARIZED, SpokenDetailLevel.DETAILED))
            assertEquals("Sanitized prose.", restarted.getPreparedText(longText, TtsInputMode.SANITIZED, SpokenDetailLevel.BALANCED))
            assertEquals(4, restarted.stats().textEntries)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun `textPrepCache evicts least recently used entries when maxTextBytes is exceeded`() {
        val root = tempFolder.newFolder("tts_text_lru")
        var nowMs = 1_000L
        // Each summary is ~32 bytes; cap at 70 bytes so only 2 entries fit
        val cache = TtsCache(rootDir = root, maxTextBytes = 70L, wallClockMs = { nowMs })
        try {
            val raw1 = "Entry one payload ".repeat(12)
            val raw2 = "Entry two payload ".repeat(12)
            val raw3 = "Entry three payload ".repeat(12)
            val summary = "Thirty two byte spoken summary!!"

            nowMs = 1_000L
            cache.putPreparedText(raw1, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, summary)
            nowMs = 2_000L
            cache.putPreparedText(raw2, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, summary)

            // Touch entry 1 so entry 2 becomes the LRU entry
            nowMs = 3_000L
            assertEquals(summary, cache.getPreparedText(raw1, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))

            nowMs = 4_000L
            cache.putPreparedText(raw3, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, summary)

            assertEquals(summary, cache.getPreparedText(raw1, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
            assertNull(cache.getPreparedText(raw2, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
            assertEquals(summary, cache.getPreparedText(raw3, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED))
            assertTrue(cache.stats().textBytes <= 70L)
            assertEquals(2, cache.stats().textEntries)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `audioCache round-trips wav and mpeg envelopes, cleans corrupt files, isolates keys, and evicts LRU`() {
        val root = tempFolder.newFolder("tts_audio")
        var nowMs = 1_000L
        val cache = TtsCache(rootDir = root, maxAudioBytes = 120L, wallClockMs = { nowMs })
        try {
            val geminiKoreKey = cache.audioCacheKey("http://host-a:4096", TtsEngine.GEMINI_BRIDGE, "Kore", "Hello")
            val geminiPuckKey = cache.audioCacheKey("http://host-a:4096", TtsEngine.GEMINI_BRIDGE, "Puck", "Hello")
            val geminiHostBKey = cache.audioCacheKey("http://host-b:4096", TtsEngine.GEMINI_BRIDGE, "Kore", "Hello")
            val openAiKey = cache.audioCacheKey("http://host-a:4096", TtsEngine.OPENAI_CLOUD, "Sulafat", "Hello")
            val kokoroAKey = cache.audioCacheKey("http://host-a:4096", TtsEngine.LOCAL_KOKORO, "Kore", "Hello")
            val kokoroBKey = cache.audioCacheKey("http://host-b:4096", TtsEngine.LOCAL_KOKORO, "Kore", "Hello")

            // Gemini voice is isolated; host URL is NOT part of Gemini/OpenAI key; Kokoro isolates server origin
            assertNotEquals(geminiKoreKey, geminiPuckKey)
            assertEquals(geminiKoreKey, geminiHostBKey)
            assertNotEquals(geminiKoreKey, openAiKey)
            assertNotEquals(kokoroAKey, kokoroBKey)

            val wavAudio = TtsAudio(bytes = ByteArray(20) { it.toByte() }, contentType = "audio/wav")
            val mp3Audio = TtsAudio(bytes = ByteArray(20) { (it + 40).toByte() }, contentType = "audio/mpeg")

            nowMs = 1_000L
            cache.putAudio(geminiKoreKey, wavAudio)
            nowMs = 2_000L
            cache.putAudio(openAiKey, mp3Audio)

            val gotWav = cache.getAudio(geminiKoreKey)!!
            assertEquals("audio/wav", gotWav.contentType)
            assertArrayEquals(wavAudio.bytes, gotWav.bytes)

            val gotMp3 = cache.getAudio(openAiKey)!!
            assertEquals("audio/mpeg", gotMp3.contentType)
            assertArrayEquals(mp3Audio.bytes, gotMp3.bytes)

            // Corrupt entry is deleted and returns null
            val corruptFile = File(root, "audio/$kokoroAKey.entry")
            corruptFile.writeBytes(byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), 0, 1, 2, 3, 4, 5))
            assertNull(cache.getAudio(kokoroAKey))
            assertTrue(!corruptFile.exists())

            // Touch geminiKoreKey, then insert 2 more entries to force LRU eviction of openAiKey
            nowMs = 3_000L
            assertEquals("audio/wav", cache.getAudio(geminiKoreKey)?.contentType)

            nowMs = 4_000L
            cache.putAudio(geminiPuckKey, TtsAudio(ByteArray(24) { 7 }, "audio/wav"))
            nowMs = 5_000L
            cache.putAudio(kokoroBKey, TtsAudio(ByteArray(24) { 9 }, "audio/wav"))

            assertNull(cache.getAudio(openAiKey))
            assertEquals("audio/wav", cache.getAudio(geminiKoreKey)?.contentType)
            assertEquals("audio/wav", cache.getAudio(geminiPuckKey)?.contentType)
            assertEquals("audio/wav", cache.getAudio(kokoroBKey)?.contentType)
            assertTrue(cache.stats().audioBytes <= 120L)

            cache.clear()
            val statsAfterClear = cache.stats()
            assertEquals(0, statsAfterClear.totalEntries)
            assertEquals(0L, statsAfterClear.totalBytes)
        } finally {
            cache.close()
        }
    }

    @Test
    fun `singleFlight shares one execution across concurrent callers and ref-counts cancellation`() = runBlocking {
        val root = tempFolder.newFolder("tts_singleflight")
        val cache = TtsCache(rootDir = root, ioDispatcher = Dispatchers.IO)
        try {
            val executions = AtomicInteger(0)
            val firstStarted = CountDownLatch(1)
            val gate1 = CompletableDeferred<Unit>()

            val c1 = async(Dispatchers.IO) {
                cache.singleFlight("shared_key") {
                    executions.incrementAndGet()
                    firstStarted.countDown()
                    gate1.await()
                    "flight_result"
                }
            }
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))

            val c2 = async(start = CoroutineStart.UNDISPATCHED) {
                cache.singleFlight("shared_key") {
                    executions.incrementAndGet()
                    "should_not_run"
                }
            }

            // Cancelling caller 1 while caller 2 is still awaiting must NOT cancel shared work
            c1.cancelAndJoin()
            gate1.complete(Unit)
            assertEquals("flight_result", c2.await())
            assertEquals(1, executions.get())

            // Cancelling ALL callers cancels the shared flight immediately
            val secondStarted = CountDownLatch(1)
            val underlyingCancelled = CountDownLatch(1)
            val stuckGate = CompletableDeferred<Unit>()

            val c3 = async(Dispatchers.IO) {
                cache.singleFlight("cancel_all_key") {
                    secondStarted.countDown()
                    try {
                        stuckGate.await()
                        "unreachable"
                    } catch (ce: CancellationException) {
                        underlyingCancelled.countDown()
                        throw ce
                    }
                }
            }
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            val c4 = async(start = CoroutineStart.UNDISPATCHED) {
                cache.singleFlight("cancel_all_key") {
                    "should_not_run_either"
                }
            }
            c3.cancelAndJoin()
            assertFalse("Expected shared job to still be alive while c4 awaits", underlyingCancelled.await(50, TimeUnit.MILLISECONDS))
            c4.cancelAndJoin()

            assertTrue(
                "Expected shared job to be cancelled once all waiters cancelled",
                underlyingCancelled.await(3, TimeUnit.SECONDS),
            )
        } finally {
            cache.close()
        }
    }
}
