package com.openchamber.ptt.audio

import android.os.SystemClock
import com.openchamber.ptt.tts.TtsPlayer
import kotlinx.coroutines.delay

class AudioOutputCoordinator(
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lock = Any()
    private var activeCueEndElapsedMs: Long = 0L
    private var activeCueUseCommunication: Boolean = false
    private var lastSinkActiveElapsedMs: Long = 0L
    private var hasSinkBeenActive: Boolean = false

    fun onCueStarted(durationMs: Int, useCommunicationUsage: Boolean = false) {
        onCueStarted(durationMs.toLong(), useCommunicationUsage)
    }

    fun onCueStarted(durationMs: Long, useCommunicationUsage: Boolean = false) {
        val now = clockElapsedMs()
        val endMs = now + durationMs.coerceAtLeast(0L)
        synchronized(lock) {
            activeCueEndElapsedMs = maxOf(activeCueEndElapsedMs, endMs)
            activeCueUseCommunication = useCommunicationUsage
            if (!useCommunicationUsage) {
                hasSinkBeenActive = true
                lastSinkActiveElapsedMs = maxOf(lastSinkActiveElapsedMs, endMs)
            }
        }
    }

    fun onCueStopped(useCommunicationUsage: Boolean = false) {
        val now = clockElapsedMs()
        synchronized(lock) {
            if (activeCueEndElapsedMs > 0L) {
                val wasCommunicationCue = activeCueUseCommunication
                activeCueEndElapsedMs = 0L
                activeCueUseCommunication = false
                if (!useCommunicationUsage && !wasCommunicationCue) {
                    hasSinkBeenActive = true
                    lastSinkActiveElapsedMs = now
                }
            }
        }
    }

    fun invalidateSinkWarmth() {
        synchronized(lock) {
            hasSinkBeenActive = false
            lastSinkActiveElapsedMs = 0L
        }
    }

    fun onSpeechPlaybackStarted() {
        val now = clockElapsedMs()
        synchronized(lock) {
            hasSinkBeenActive = true
            lastSinkActiveElapsedMs = maxOf(lastSinkActiveElapsedMs, now)
        }
    }

    fun onSpeechPlaybackFinished() {
        val now = clockElapsedMs()
        synchronized(lock) {
            hasSinkBeenActive = true
            lastSinkActiveElapsedMs = maxOf(lastSinkActiveElapsedMs, now)
        }
    }

    suspend fun awaitCueDrainAndResolvePrerollMs(
        useCommunicationUsage: Boolean,
        postCueGapMs: Long = DEFAULT_POST_CUE_GAP_MS,
    ): Int {
        val safeGapMs = postCueGapMs.coerceAtLeast(0L)
        while (true) {
            val now = clockElapsedMs()
            val remainingCueMs = synchronized(lock) {
                (activeCueEndElapsedMs - now).coerceAtLeast(0L)
            }
            if (remainingCueMs <= 0L) {
                break
            }
            delay(remainingCueMs + safeGapMs)
            val afterWait = clockElapsedMs()
            synchronized(lock) {
                val wasCommCue = activeCueUseCommunication
                if (activeCueEndElapsedMs <= afterWait) {
                    activeCueEndElapsedMs = 0L
                    activeCueUseCommunication = false
                }
                if (!wasCommCue && !useCommunicationUsage) {
                    hasSinkBeenActive = true
                    lastSinkActiveElapsedMs = maxOf(lastSinkActiveElapsedMs, afterWait)
                }
            }
        }

        if (useCommunicationUsage) {
            return 0
        }

        val now = clockElapsedMs()
        val isCold = synchronized(lock) {
            !hasSinkBeenActive || (now - lastSinkActiveElapsedMs) > COLD_SINK_IDLE_THRESHOLD_MS
        }
        return if (isCold) DEFAULT_PREROLL_MS else 0
    }

    fun stopAll(
        earconPlayer: EarconPlayer? = null,
        ttsPlayer: TtsPlayer? = null,
        cuePlayer: CuePlayer? = null,
    ) {
        cuePlayer?.stop()
        earconPlayer?.stop()
        ttsPlayer?.stop()
        onCueStopped()
    }

    companion object {
        const val DEFAULT_POST_CUE_GAP_MS = 70L
        const val COLD_SINK_IDLE_THRESHOLD_MS = 2_500L
        const val DEFAULT_PREROLL_MS = 160
    }
}
