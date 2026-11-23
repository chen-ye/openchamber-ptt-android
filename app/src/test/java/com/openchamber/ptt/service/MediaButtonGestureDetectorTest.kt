package com.openchamber.ptt.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonGestureDetectorTest {
    private class FakeScheduler : TapTimerScheduler {
        var nowMs: Long = 1_000L
        private data class Task(val fireAtMs: Long, val block: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            val task = Task(fireAtMs = nowMs + delayMs, block = block)
            tasks += task
            return { task.cancelled = true }
        }

        fun advanceBy(ms: Long) {
            nowMs += ms
            while (true) {
                val next = tasks
                    .filter { !it.cancelled && it.fireAtMs <= nowMs }
                    .minByOrNull { it.fireAtMs }
                    ?: break
                next.cancelled = true
                next.block()
            }
        }
    }

    @Test
    fun `IMMEDIATE_SINGLE fires SINGLE_TAP with zero wait`() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<Triple<MediaGesture, RawMediaInput, Long>>()
        val detector = MediaButtonGestureDetector(
            scheduler = scheduler,
            clockMs = { scheduler.nowMs },
            onGesture = { g, raw, wait -> emitted += Triple(g, raw, wait) },
        )

        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.IMMEDIATE_SINGLE)
        assertEquals(listOf(Triple(MediaGesture.SINGLE_TAP, RawMediaInput.PRIMARY_TAP, 0L)), emitted)
    }

    @Test
    fun `DOUBLE_ONLY waits for single tap and fires DOUBLE_TAP immediately on second tap`() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<MediaGesture>()
        val detector = MediaButtonGestureDetector(
            scheduler = scheduler,
            doubleTapWindowMs = 350L,
            clockMs = { scheduler.nowMs },
            onGesture = { g, _, _ -> emitted += g },
        )

        // Single tap waits 350ms
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_ONLY)
        assertTrue(emitted.isEmpty())
        scheduler.advanceBy(349L)
        assertTrue(emitted.isEmpty())
        scheduler.advanceBy(1L)
        assertEquals(listOf(MediaGesture.SINGLE_TAP), emitted)

        // Double tap fires immediately on 2nd tap without waiting extra triple-tap window
        emitted.clear()
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_ONLY)
        scheduler.advanceBy(150L)
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_ONLY)
        assertEquals(listOf(MediaGesture.DOUBLE_TAP), emitted)
    }

    @Test
    fun `DOUBLE_AND_TRIPLE distinguishes single, double, triple, and AVRCP NEXT or PREVIOUS`() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<MediaGesture>()
        val detector = MediaButtonGestureDetector(
            scheduler = scheduler,
            doubleTapWindowMs = 350L,
            tripleTapWindowMs = 240L,
            clockMs = { scheduler.nowMs },
            onGesture = { g, _, _ -> emitted += g },
        )

        // Double tap waits 240ms for potential 3rd tap, then fires DOUBLE_TAP
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        scheduler.advanceBy(120L)
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        assertTrue(emitted.isEmpty())
        scheduler.advanceBy(240L)
        assertEquals(listOf(MediaGesture.DOUBLE_TAP), emitted)

        // Triple tap fires immediately on 3rd tap
        emitted.clear()
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        scheduler.advanceBy(100L)
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        scheduler.advanceBy(100L)
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        assertEquals(listOf(MediaGesture.TRIPLE_TAP), emitted)

        // Hardware AVRCP NEXT / PREVIOUS / STOP cancel pending primary taps and fire immediately
        emitted.clear()
        detector.onInput(RawMediaInput.PRIMARY_TAP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        detector.onInput(RawMediaInput.NEXT, TapGroupingMode.DOUBLE_AND_TRIPLE)
        detector.onInput(RawMediaInput.PREVIOUS, TapGroupingMode.DOUBLE_AND_TRIPLE)
        detector.onInput(RawMediaInput.STOP, TapGroupingMode.DOUBLE_AND_TRIPLE)
        scheduler.advanceBy(1_000L)
        assertEquals(
            listOf(MediaGesture.DOUBLE_TAP, MediaGesture.TRIPLE_TAP, MediaGesture.DISARM),
            emitted,
        )
    }

    @Test
    fun `SINGLE_ONLY headset tap mode forces IMMEDIATE_SINGLE across all states`() {
        for (state in PttState.entries) {
            assertEquals(
                TapGroupingMode.IMMEDIATE_SINGLE,
                MediaButtonGestureDetector.groupingModeFor(state, HeadsetTapMode.SINGLE_ONLY),
            )
        }
        assertEquals(
            TapGroupingMode.DOUBLE_ONLY,
            MediaButtonGestureDetector.groupingModeFor(PttState.ARMED_IDLE, HeadsetTapMode.MULTI_TAP),
        )
        assertEquals(
            TapGroupingMode.DOUBLE_AND_TRIPLE,
            MediaButtonGestureDetector.groupingModeFor(PttState.RECORDING, HeadsetTapMode.MULTI_TAP),
        )
    }
}
