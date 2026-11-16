package com.openchamber.ptt.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.voice.DictationClient
import com.openchamber.ptt.voice.DictationException
import com.openchamber.ptt.voice.MlKitSttClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal data class CaptureOutcome(
    val transcript: String?,
    val durationMs: Long,
    val sttLatencyMs: Long,
    val fallbackUsed: Boolean,
    val hitMaxCap: Boolean,
)

internal interface PcmCaptureSource {
    fun startRecording()
    fun readBlocking(buffer: ByteArray, sizeInBytes: Int): Int
    fun stopIfRecording()
    fun release()
}

internal class CaptureController {
    private val lock = Any()
    private val stopFlag = AtomicBoolean(false)
    private var captureSource: PcmCaptureSource? = null
    private var released = false

    val isStopRequested: Boolean
        get() = stopFlag.get()

    fun attach(source: PcmCaptureSource): Boolean = synchronized(lock) {
        if (stopFlag.get() || released) {
            false
        } else {
            captureSource = source
            true
        }
    }

    fun attach(record: AudioRecord): Boolean = attach(AndroidPcmCaptureSource(record))

    fun requestStop() {
        stopFlag.set(true)
        val source = synchronized(lock) {
            if (released) null else captureSource
        }
        source?.stopIfRecording()
    }

    fun release() {
        val sourceToRelease = synchronized(lock) {
            if (released) return
            released = true
            val src = captureSource
            captureSource = null
            src
        }
        sourceToRelease?.release()
    }
}

private class AndroidPcmCaptureSource(
    private val audioRecord: AudioRecord,
) : PcmCaptureSource {
    override fun startRecording() {
        audioRecord.startRecording()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING")
        }
    }

    override fun readBlocking(buffer: ByteArray, sizeInBytes: Int): Int =
        audioRecord.read(buffer, 0, sizeInBytes, AudioRecord.READ_BLOCKING)

    override fun stopIfRecording() {
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
    }

    override fun release() {
        stopIfRecording()
        runCatching { audioRecord.release() }
    }
}

internal class AudioBurstRecorder internal constructor(
    private val scope: CoroutineScope,
    private val dictationClient: DictationClient,
    private val mlKitSttClient: MlKitSttClient,
    private val voiceUxLogger: VoiceUxLogger,
    private val currentState: () -> PttState,
    private val sessionKeyProvider: () -> String?,
    private val lastTtsStopElapsedMsProvider: () -> Long,
    private val hasRecordAudioPermission: () -> Boolean,
    private val pinBluetoothScoProvider: () -> Boolean,
    private val sttEngineProvider: () -> SttEngine,
    private val sttFallbackToParakeetProvider: () -> Boolean,
    private val onPlayWarningCue: () -> Unit,
    private val onMaxCapAutoStage: () -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit,
    private val maxUtteranceMs: Long = PttForegroundService.MAX_UTTERANCE_MS,
    private val maxUtteranceWarningMs: Long = PttForegroundService.MAX_UTTERANCE_WARNING_MS,
    private val bargeInCooldownMs: Long = PttForegroundService.BARGE_IN_COOLDOWN_MS,
    private val maxFallbackPcmBytes: Int = PttForegroundService.MAX_FALLBACK_PCM_BYTES,
    private val chunkSizeBytes: Int = PttForegroundService.CHUNK_SIZE_BYTES,
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val captureSourceFactory: (useCommunicationSource: Boolean) -> PcmCaptureSource = { useComm ->
        createAndroidCaptureSource(useComm, chunkSizeBytes)
    },
    private val mlKitTranscribe: suspend (
        engine: SttEngine,
        audioChunks: Flow<ByteArray>,
        onPartial: (String) -> Unit,
    ) -> String = { engine, audioChunks, onPartial ->
        mlKitSttClient.transcribePcmStream(
            engine = engine,
            audioChunks = audioChunks,
            onPartial = onPartial,
        )
    },
) {
    private val lock = Any()
    private var activeCaptureJob: Job? = null
    private var activeCaptureController: CaptureController? = null

    fun startBurst(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        onStoppedEarly: () -> Unit,
        onOutcome: suspend (
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            outcome: CaptureOutcome?,
        ) -> Unit,
        onFailure: (serverId: String, error: Throwable) -> Unit,
    ) {
        val controller = CaptureController()
        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                executeBurst(
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                    captureController = controller,
                    onStoppedEarly = onStoppedEarly,
                    onOutcome = onOutcome,
                    onFailure = onFailure,
                )
            } finally {
                synchronized(lock) {
                    if (activeCaptureController === controller) {
                        activeCaptureController = null
                    }
                    if (activeCaptureJob === jobRef) {
                        activeCaptureJob = null
                    }
                }
            }
        }
        jobRef = job

        val prevJob: Job?
        val prevController: CaptureController?
        synchronized(lock) {
            prevJob = activeCaptureJob
            prevController = activeCaptureController
            activeCaptureController = controller
            activeCaptureJob = job
        }
        prevController?.requestStop()
        prevJob?.cancel(CancellationException("Superseded by new recording"))
        job.start()
    }

    fun requestStop() {
        synchronized(lock) {
            activeCaptureController?.requestStop()
        }
    }

    fun cancel(reason: String) {
        val jobToCancel: Job?
        val controllerToStop: CaptureController?
        synchronized(lock) {
            jobToCancel = activeCaptureJob
            activeCaptureJob = null
            controllerToStop = activeCaptureController
            activeCaptureController = null
        }
        controllerToStop?.requestStop()
        jobToCancel?.cancel(CancellationException(reason))
    }

    internal suspend fun executeBurst(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        captureController: CaptureController,
        onStoppedEarly: () -> Unit,
        onOutcome: suspend (
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            outcome: CaptureOutcome?,
        ) -> Unit,
        onFailure: (serverId: String, error: Throwable) -> Unit,
    ) {
        try {
            val cooldownRemaining = PttForegroundService.remainingBargeInCooldownMs(
                lastTtsStopElapsedMs = lastTtsStopElapsedMsProvider(),
                nowElapsedMs = clockElapsedMs(),
                cooldownMs = bargeInCooldownMs,
            )
            if (cooldownRemaining > 0L) {
                delay(cooldownRemaining)
            }

            if (captureController.isStopRequested || currentState() != PttState.RECORDING) {
                onStoppedEarly()
                return
            }

            val outcome = recordAndTranscribe(serverUrl, captureController)
            onOutcome(serverId, serverUrl, pinnedSession, outcome)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onFailure(serverId, t)
        }
    }

    internal suspend fun recordAndTranscribe(
        serverUrl: String,
        captureController: CaptureController,
    ): CaptureOutcome? = coroutineScope {
        if (!hasRecordAudioPermission()) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }

        val source = captureSourceFactory(pinBluetoothScoProvider())
        if (!captureController.attach(source)) {
            runCatching { source.release() }
            return@coroutineScope null
        }

        val sttEngine = sttEngineProvider()
        val fallbackToParakeet = sttFallbackToParakeetProvider()
        val bufferedPcm = if (sttEngine != SttEngine.OPENCHAMBER_PARAKEET && fallbackToParakeet) {
            ByteArrayOutputStream()
        } else {
            null
        }

        val captureStartedMs = clockElapsedMs()
        val hitMaxCapFlag = AtomicBoolean(false)
        val chunkChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val captureJob = launch(ioDispatcher) {
            val warningTimer = launch {
                delay(maxUtteranceWarningMs)
                if (currentState() == PttState.RECORDING && !captureController.isStopRequested) {
                    onPlayWarningCue()
                }
            }
            val maxCapTimer = launch {
                delay(maxUtteranceMs)
                hitMaxCapFlag.set(true)
                captureController.requestStop()
                if (currentState() == PttState.RECORDING) {
                    onMaxCapAutoStage()
                }
            }

            try {
                source.startRecording()

                val readBuffer = ByteArray(chunkSizeBytes)
                while (isActive && !captureController.isStopRequested) {
                    val bytesRead = source.readBlocking(readBuffer, readBuffer.size)
                    if (bytesRead > 0) {
                        val copy = readBuffer.copyOf(bytesRead)
                        if (bufferedPcm != null && bufferedPcm.size() + copy.size <= maxFallbackPcmBytes) {
                            bufferedPcm.write(copy)
                        }
                        chunkChannel.trySend(copy)
                    } else if (bytesRead < 0) {
                        if (captureController.isStopRequested) {
                            break
                        }
                        throw IllegalStateException("AudioRecord.read failed with code $bytesRead")
                    }
                }
            } finally {
                warningTimer.cancel()
                maxCapTimer.cancel()
                captureController.release()
                chunkChannel.close()
            }
        }

        if (sttEngine != SttEngine.OPENCHAMBER_PARAKEET) {
            try {
                val sttStartMs = clockElapsedMs()
                val mlKitResult = mlKitTranscribe(
                    sttEngine,
                    chunkChannel.receiveAsFlow(),
                ) { partial ->
                    if (partial.isNotBlank()) {
                        onPartialTranscript(partial)
                    }
                }
                captureJob.join()
                val captureDur = (clockElapsedMs() - captureStartedMs).coerceAtLeast(0L)
                val sttDur = (clockElapsedMs() - sttStartMs).coerceAtLeast(0L)
                logCaptureTelemetry(mlKitResult, captureDur, sttDur, sttEngine, fallbackUsed = false, hitMaxCapFlag.get())
                return@coroutineScope CaptureOutcome(mlKitResult, captureDur, sttDur, false, hitMaxCapFlag.get())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val isEmptyOnly = (t as? DictationException)?.isEmptyTranscript == true
                if (isEmptyOnly || !fallbackToParakeet || bufferedPcm == null) {
                    withContext(NonCancellable) {
                        captureController.requestStop()
                        captureJob.cancel()
                        chunkChannel.close()
                    }
                    if (isEmptyOnly) return@coroutineScope null
                    throw t
                }
                captureJob.join()
                val pcmBytes = bufferedPcm.toByteArray()
                if (pcmBytes.isEmpty()) return@coroutineScope null
                onStatusUpdate("ML Kit fallback → Parakeet…")
                val fallbackStartMs = clockElapsedMs()
                return@coroutineScope try {
                    val res = dictationClient.transcribe(
                        serverUrl = serverUrl,
                        audioChunks = flowOf(pcmBytes),
                        onPartial = { partial ->
                            if (partial.isNotBlank()) {
                                onPartialTranscript(partial)
                            }
                        },
                    )
                    val captureDur = (clockElapsedMs() - captureStartedMs).coerceAtLeast(0L)
                    val sttDur = (clockElapsedMs() - fallbackStartMs).coerceAtLeast(0L)
                    logCaptureTelemetry(res, captureDur, sttDur, sttEngine, fallbackUsed = true, hitMaxCapFlag.get())
                    CaptureOutcome(res, captureDur, sttDur, true, hitMaxCapFlag.get())
                } catch (de: DictationException) {
                    if (de.isEmptyTranscript) null else throw de
                }
            }
        }

        val dictationSession = try {
            dictationClient.startSession(
                serverUrl = serverUrl,
                onPartial = { partial ->
                    if (partial.isNotBlank()) {
                        onPartialTranscript(partial)
                    }
                },
            )
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                captureController.requestStop()
                captureJob.cancel()
                captureJob.join()
                chunkChannel.close()
            }
            throw t
        }

        try {
            for (chunk in chunkChannel) {
                dictationSession.sendChunk(chunk)
            }
            captureJob.join()
            val finStartMs = clockElapsedMs()
            val transcript = dictationSession.finish()
            val captureDur = (clockElapsedMs() - captureStartedMs).coerceAtLeast(0L)
            val sttFinishDur = (clockElapsedMs() - finStartMs).coerceAtLeast(0L)
            logCaptureTelemetry(transcript, captureDur, sttFinishDur, sttEngine, fallbackUsed = false, hitMaxCapFlag.get())
            return@coroutineScope CaptureOutcome(transcript, captureDur, sttFinishDur, false, hitMaxCapFlag.get())
        } catch (de: DictationException) {
            if (de.isEmptyTranscript) {
                return@coroutineScope null
            }
            throw de
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                captureController.requestStop()
                captureJob.cancel()
                runCatching { dictationSession.cancel() }
            }
            throw t
        }
    }

    private fun logCaptureTelemetry(
        transcript: String?,
        durationMs: Long,
        sttLatencyMs: Long,
        sttEngine: SttEngine,
        fallbackUsed: Boolean,
        hitMaxCap: Boolean,
    ) {
        val words = transcript?.trim()?.split(Regex("\\s+"))?.count { it.isNotEmpty() } ?: 0
        val state = currentState()
        val stopMode = when (state) {
            PttState.TRANSCRIBING_STAGE -> "stage"
            PttState.TRANSCRIBING_SEND -> "send"
            else -> state.name.lowercase()
        }
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_CAPTURE,
            event = "completed",
            sessionKey = sessionKeyProvider(),
            metrics = mapOf(
                "durationMs" to durationMs,
                "sttLatencyMs" to sttLatencyMs,
                "sttEngine" to sttEngine.name,
                "fallbackUsed" to fallbackUsed,
                "hitMaxCap" to hitMaxCap,
                "wordCount" to words,
                "stopMode" to stopMode,
            ),
        )
    }

    companion object {
        @SuppressLint("MissingPermission")
        private fun createAndroidCaptureSource(
            useCommunicationSource: Boolean,
            chunkSizeBytes: Int,
        ): PcmCaptureSource {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                PttForegroundService.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                throw IllegalStateException("AudioRecord.getMinBufferSize failed ($minBufferBytes)")
            }

            val audioSource = if (useCommunicationSource) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            val bufferSizeBytes = maxOf(minBufferBytes * 2, chunkSizeBytes * 2)

            val audioRecord = AudioRecord(
                audioSource,
                PttForegroundService.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord.release() }
                throw IllegalStateException("AudioRecord failed to initialize")
            }
            return AndroidPcmCaptureSource(audioRecord)
        }
    }
}
