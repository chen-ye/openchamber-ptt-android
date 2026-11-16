package com.openchamber.ptt.service

interface TapTimerScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
}

enum class RawMediaInput {
    PRIMARY_TAP,
    NEXT,
    PREVIOUS,
    STOP,
}

enum class MediaGesture {
    SINGLE_TAP,
    DOUBLE_TAP,
    TRIPLE_TAP,
    DISARM,
}

enum class TapGroupingMode {
    IMMEDIATE_SINGLE,
    DOUBLE_ONLY,
    DOUBLE_AND_TRIPLE,
}

/**
 * Pure-Kotlin media-button cadence normalizer.
 *
 * Maps both single-button Bluetooth headsets (rapid 2x/3x `PLAY`/`PAUSE`/`HEADSETHOOK` taps)
 * and TWS earbuds (firmware `KEYCODE_MEDIA_NEXT`/`KEYCODE_MEDIA_PREVIOUS`) into deterministic
 * [MediaGesture] events, bypassing the multi-tap wait when the active [PttState] needs instant
 * single-tap response (e.g. `SPEAKING_TTS` barge-in or `SEND_GRACE` undo).
 */
class MediaButtonGestureDetector(
    private val scheduler: TapTimerScheduler,
    private val doubleTapWindowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
    private val tripleTapWindowMs: Long = DEFAULT_TRIPLE_TAP_WINDOW_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onGesture: (gesture: MediaGesture, rawInput: RawMediaInput, waitMs: Long) -> Unit,
) {
    private val lock = Any()
    private var pendingTapCount: Int = 0
    private var firstTapAtMs: Long = 0L
    private var cancelPendingTimer: (() -> Unit)? = null

    fun onInput(input: RawMediaInput, mode: TapGroupingMode) {
        val immediate: Triple<MediaGesture, RawMediaInput, Long>? = synchronized(lock) {
            when (input) {
                RawMediaInput.STOP -> {
                    clearLocked()
                    Triple(MediaGesture.DISARM, input, 0L)
                }
                RawMediaInput.NEXT -> {
                    clearLocked()
                    Triple(MediaGesture.DOUBLE_TAP, input, 0L)
                }
                RawMediaInput.PREVIOUS -> {
                    clearLocked()
                    Triple(MediaGesture.TRIPLE_TAP, input, 0L)
                }
                RawMediaInput.PRIMARY_TAP -> handlePrimaryTapLocked(mode)
            }
        }
        if (immediate != null) {
            onGesture(immediate.first, immediate.second, immediate.third)
        }
    }

    fun cancelPending() {
        synchronized(lock) {
            clearLocked()
        }
    }

    private fun handlePrimaryTapLocked(mode: TapGroupingMode): Triple<MediaGesture, RawMediaInput, Long>? {
        if (mode == TapGroupingMode.IMMEDIATE_SINGLE) {
            clearLocked()
            return Triple(MediaGesture.SINGLE_TAP, RawMediaInput.PRIMARY_TAP, 0L)
        }

        val now = clockMs()
        pendingTapCount += 1
        if (pendingTapCount == 1) {
            firstTapAtMs = now
            cancelPendingTimer?.invoke()
            cancelPendingTimer = scheduler.schedule(doubleTapWindowMs) {
                flushPending(MediaGesture.SINGLE_TAP)
            }
            return null
        }

        if (pendingTapCount == 2) {
            cancelPendingTimer?.invoke()
            cancelPendingTimer = null
            if (mode == TapGroupingMode.DOUBLE_ONLY) {
                val waitMs = (now - firstTapAtMs).coerceAtLeast(0L)
                pendingTapCount = 0
                firstTapAtMs = 0L
                return Triple(MediaGesture.DOUBLE_TAP, RawMediaInput.PRIMARY_TAP, waitMs)
            }
            cancelPendingTimer = scheduler.schedule(tripleTapWindowMs) {
                flushPending(MediaGesture.DOUBLE_TAP)
            }
            return null
        }

        // 3rd tap (or higher) in DOUBLE_AND_TRIPLE completes TRIPLE_TAP immediately.
        val waitMs = (now - firstTapAtMs).coerceAtLeast(0L)
        clearLocked()
        return Triple(MediaGesture.TRIPLE_TAP, RawMediaInput.PRIMARY_TAP, waitMs)
    }

    private fun flushPending(gesture: MediaGesture) {
        val waitMs: Long
        val shouldEmit: Boolean
        synchronized(lock) {
            shouldEmit = pendingTapCount > 0
            waitMs = if (shouldEmit) (clockMs() - firstTapAtMs).coerceAtLeast(0L) else 0L
            pendingTapCount = 0
            firstTapAtMs = 0L
            cancelPendingTimer = null
        }
        if (shouldEmit) {
            onGesture(gesture, RawMediaInput.PRIMARY_TAP, waitMs)
        }
    }

    private fun clearLocked() {
        cancelPendingTimer?.invoke()
        cancelPendingTimer = null
        pendingTapCount = 0
        firstTapAtMs = 0L
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS = 350L
        const val DEFAULT_TRIPLE_TAP_WINDOW_MS = 240L

        fun groupingModeFor(state: PttState): TapGroupingMode = when (state) {
            PttState.ARMED_IDLE -> TapGroupingMode.DOUBLE_ONLY
            PttState.RECORDING,
            PttState.STAGED_DRAFT,
            PttState.STAGED_DRAFT_SPEAKING,
            -> TapGroupingMode.DOUBLE_AND_TRIPLE
            PttState.DISARMED,
            PttState.TRANSCRIBING_SEND,
            PttState.TRANSCRIBING_STAGE,
            PttState.SEND_GRACE,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            -> TapGroupingMode.IMMEDIATE_SINGLE
        }
    }
}
