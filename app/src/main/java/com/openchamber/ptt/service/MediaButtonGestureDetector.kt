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
    private val onGroupingDecision: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null,
) {
    private data class GroupingDecision(
        val event: String,
        val outcome: String,
        val details: Map<String, String>,
    )

    private val lock = Any()
    private var pendingTapCount: Int = 0
    private var firstTapAtMs: Long = 0L
    private var cancelPendingTimer: (() -> Unit)? = null

    fun onInput(input: RawMediaInput, mode: TapGroupingMode) {
        var decision: GroupingDecision? = null
        val immediate: Triple<MediaGesture, RawMediaInput, Long>? = synchronized(lock) {
            when (input) {
                RawMediaInput.STOP -> {
                    val cleared = pendingTapCount
                    clearLocked()
                    decision = GroupingDecision(
                        event = "IMMEDIATE_STOP",
                        outcome = "ACCEPTED",
                        details = mapOf("mode" to mode.name, "clearedPending" to cleared.toString()),
                    )
                    Triple(MediaGesture.DISARM, input, 0L)
                }
                RawMediaInput.NEXT -> {
                    val cleared = pendingTapCount
                    clearLocked()
                    decision = GroupingDecision(
                        event = "IMMEDIATE_NEXT",
                        outcome = "ACCEPTED",
                        details = mapOf("mode" to mode.name, "clearedPending" to cleared.toString()),
                    )
                    Triple(MediaGesture.DOUBLE_TAP, input, 0L)
                }
                RawMediaInput.PREVIOUS -> {
                    val cleared = pendingTapCount
                    clearLocked()
                    decision = GroupingDecision(
                        event = "IMMEDIATE_PREVIOUS",
                        outcome = "ACCEPTED",
                        details = mapOf("mode" to mode.name, "clearedPending" to cleared.toString()),
                    )
                    Triple(MediaGesture.TRIPLE_TAP, input, 0L)
                }
                RawMediaInput.PRIMARY_TAP -> {
                    val res = handlePrimaryTapLocked(mode)
                    decision = res.second
                    res.first
                }
            }
        }
        decision?.let { d ->
            onGroupingDecision?.invoke(d.event, d.outcome, d.details)
        }
        if (immediate != null) {
            onGesture(immediate.first, immediate.second, immediate.third)
        }
    }

    fun cancelPending() {
        val clearedCount = synchronized(lock) {
            val count = pendingTapCount
            clearLocked()
            count
        }
        if (clearedCount > 0) {
            onGroupingDecision?.invoke(
                "CANCELLED_PENDING_TAPS",
                "IGNORED",
                mapOf("pendingCount" to clearedCount.toString()),
            )
        }
    }

    private fun handlePrimaryTapLocked(
        mode: TapGroupingMode,
    ): Pair<Triple<MediaGesture, RawMediaInput, Long>?, GroupingDecision> {
        if (mode == TapGroupingMode.IMMEDIATE_SINGLE) {
            val cleared = pendingTapCount
            clearLocked()
            return Triple(MediaGesture.SINGLE_TAP, RawMediaInput.PRIMARY_TAP, 0L) to GroupingDecision(
                event = "IMMEDIATE_SINGLE_TAP",
                outcome = "ACCEPTED",
                details = mapOf("mode" to mode.name, "clearedPending" to cleared.toString()),
            )
        }

        val now = clockMs()
        pendingTapCount += 1
        if (pendingTapCount == 1) {
            firstTapAtMs = now
            cancelPendingTimer?.invoke()
            cancelPendingTimer = scheduler.schedule(doubleTapWindowMs) {
                flushPending(MediaGesture.SINGLE_TAP)
            }
            return null to GroupingDecision(
                event = "BUFFERED_PRIMARY_TAP_1",
                outcome = "STATE_CHANGE",
                details = mapOf("mode" to mode.name, "windowMs" to doubleTapWindowMs.toString()),
            )
        }

        if (pendingTapCount == 2) {
            cancelPendingTimer?.invoke()
            cancelPendingTimer = null
            if (mode == TapGroupingMode.DOUBLE_ONLY) {
                val waitMs = (now - firstTapAtMs).coerceAtLeast(0L)
                pendingTapCount = 0
                firstTapAtMs = 0L
                return Triple(MediaGesture.DOUBLE_TAP, RawMediaInput.PRIMARY_TAP, waitMs) to GroupingDecision(
                    event = "RESOLVED_DOUBLE_TAP",
                    outcome = "ACCEPTED",
                    details = mapOf("mode" to mode.name, "waitMs" to waitMs.toString()),
                )
            }
            cancelPendingTimer = scheduler.schedule(tripleTapWindowMs) {
                flushPending(MediaGesture.DOUBLE_TAP)
            }
            return null to GroupingDecision(
                event = "BUFFERED_PRIMARY_TAP_2",
                outcome = "STATE_CHANGE",
                details = mapOf("mode" to mode.name, "windowMs" to tripleTapWindowMs.toString()),
            )
        }

        // 3rd tap (or higher) in DOUBLE_AND_TRIPLE completes TRIPLE_TAP immediately.
        val waitMs = (now - firstTapAtMs).coerceAtLeast(0L)
        clearLocked()
        return Triple(MediaGesture.TRIPLE_TAP, RawMediaInput.PRIMARY_TAP, waitMs) to GroupingDecision(
            event = "RESOLVED_TRIPLE_TAP",
            outcome = "ACCEPTED",
            details = mapOf("mode" to mode.name, "waitMs" to waitMs.toString()),
        )
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
            onGroupingDecision?.invoke(
                "TIMER_FLUSH_${gesture.name}",
                "ACCEPTED",
                mapOf("waitMs" to waitMs.toString()),
            )
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

        fun groupingModeFor(
            state: PttState,
            tapMode: HeadsetTapMode = HeadsetTapMode.MULTI_TAP,
        ): TapGroupingMode {
            if (tapMode == HeadsetTapMode.SINGLE_ONLY) {
                return TapGroupingMode.IMMEDIATE_SINGLE
            }
            return when (state) {
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
}
