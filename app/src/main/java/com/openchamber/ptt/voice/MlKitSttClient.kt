package com.openchamber.ptt.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.openchamber.ptt.tts.SttEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class MlKitAvailability {
    AVAILABLE,
    DOWNLOADABLE,
    DOWNLOADING,
    UNAVAILABLE,
}

data class MlKitStatusReport(
    val availability: MlKitAvailability,
    val detail: String? = null,
)

sealed interface MlKitDownloadEvent {
    data class Progress(val bytesDownloaded: Long) : MlKitDownloadEvent
    data object Completed : MlKitDownloadEvent
    data class Failed(val message: String) : MlKitDownloadEvent
}

class MlKitSttClient {
    suspend fun checkAvailability(
        engine: SttEngine,
        context: Context? = null,
        locale: Locale = resolveSupportedLocale(),
    ): MlKitStatusReport = withContext(Dispatchers.IO) {
        val mode = modeForEngine(engine) ?: return@withContext MlKitStatusReport(MlKitAvailability.UNAVAILABLE)
        val effectiveLocale = resolveSupportedLocale(locale)
        val pkgHint = context?.let { buildPackageDiagnostic(it) }
        val recognizer = try {
            val options: SpeechRecognizerOptions = speechRecognizerOptions {
                this.locale = effectiveLocale
                this.preferredMode = mode
            }
            SpeechRecognition.getClient(options)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext MlKitStatusReport(
                MlKitAvailability.UNAVAILABLE,
                "getClient failed: ${t.message ?: t::class.simpleName}${pkgHint?.let { " ($it)" }.orEmpty()}",
            )
        }
        try {
            val rawStatus = recognizer.checkStatus()
            val avail = when (rawStatus) {
                FeatureStatus.AVAILABLE -> MlKitAvailability.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> MlKitAvailability.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> MlKitAvailability.DOWNLOADING
                else -> MlKitAvailability.UNAVAILABLE
            }
            val detail = if (avail == MlKitAvailability.UNAVAILABLE) {
                "status=$rawStatus, locale=${effectiveLocale.toLanguageTag()}${pkgHint?.let { ", $it" }.orEmpty()}"
            } else {
                null
            }
            MlKitStatusReport(avail, detail)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            MlKitStatusReport(
                MlKitAvailability.UNAVAILABLE,
                "checkStatus failed: ${t.message ?: t::class.simpleName}${pkgHint?.let { " ($it)" }.orEmpty()}",
            )
        } finally {
            runCatching { recognizer.close() }
        }
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun downloadModel(
        engine: SttEngine,
        locale: Locale = resolveSupportedLocale(),
    ): Flow<MlKitDownloadEvent> = flow {
        val mode = modeForEngine(engine)
            ?: throw IllegalArgumentException("Not an ML Kit STT engine: $engine")
        val recognizer = createRecognizer(mode, resolveSupportedLocale(locale))
            ?: throw IllegalStateException("ML Kit GenAI SpeechRecognizer unavailable on this device")
        try {
            recognizer.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadProgress -> {
                        emit(MlKitDownloadEvent.Progress(status.totalBytesDownloaded))
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        emit(MlKitDownloadEvent.Completed)
                    }
                    is DownloadStatus.DownloadFailed -> {
                        emit(MlKitDownloadEvent.Failed(status.e.message ?: "Download failed"))
                    }
                    else -> Unit
                }
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    suspend fun transcribePcmStream(
        engine: SttEngine,
        audioChunks: Flow<ByteArray>,
        locale: Locale = resolveSupportedLocale(),
        onPartial: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val mode = modeForEngine(engine)
            ?: throw IllegalArgumentException("Not an ML Kit STT engine: $engine")
        val recognizer = createRecognizer(mode, resolveSupportedLocale(locale))
            ?: throw IllegalStateException("ML Kit GenAI SpeechRecognizer unavailable on this device")

        try {
            val status = recognizer.checkStatus()
            if (status != FeatureStatus.AVAILABLE) {
                throw IllegalStateException("ML Kit GenAI model is not ready (status=$status)")
            }

            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            val audioSource = AudioSource.fromPfd(readFd, AudioSource.Mode.STREAMING)

            val finalSegments = mutableListOf<String>()
            var latestPartial = ""
            var recognitionError: String? = null

            try {
                coroutineScope {
                    val writeJob = launch(Dispatchers.IO) {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { out ->
                            audioChunks.collect { chunk ->
                                if (chunk.isNotEmpty()) {
                                    out.write(chunk)
                                    out.flush()
                                }
                            }
                        }
                    }

                    try {
                        val request = speechRecognizerRequest {
                            this.audioSource = audioSource
                        }
                        recognizer.startRecognition(request).collect { response ->
                            when (response) {
                                is SpeechRecognizerResponse.PartialTextResponse -> {
                                    val text = response.text.trim()
                                    if (text.isNotEmpty()) {
                                        latestPartial = text
                                        val combined = (finalSegments + text).joinToString(" ").trim()
                                        onPartial(combined)
                                    }
                                }
                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    val text = response.text.trim()
                                    if (text.isNotEmpty()) {
                                        finalSegments += text
                                        latestPartial = ""
                                        onPartial(finalSegments.joinToString(" ").trim())
                                    }
                                }
                                is SpeechRecognizerResponse.ErrorResponse -> {
                                    recognitionError = response.e.message ?: "ML Kit recognition failed"
                                }
                                is SpeechRecognizerResponse.CompletedResponse -> Unit
                                else -> Unit
                            }
                        }
                    } finally {
                        writeJob.cancel()
                    }
                }
            } finally {
                runCatching { audioSource.close() }
                runCatching { writeFd.close() }
                runCatching { readFd.close() }
            }

            val combined = if (finalSegments.isNotEmpty()) {
                finalSegments.joinToString(" ").trim()
            } else {
                latestPartial.trim()
            }
            if (combined.isBlank()) {
                val err = recognitionError
                if (err != null) {
                    throw DictationException(err, retryable = true)
                }
                throw DictationException(
                    message = "ML Kit produced an empty transcript",
                    retryable = false,
                    isEmptyTranscript = true,
                )
            }
            combined
        } finally {
            runCatching { recognizer.stopRecognition() }
            runCatching { recognizer.close() }
        }
    }

    private fun createRecognizer(
        mode: Int,
        locale: Locale,
    ): SpeechRecognizer? = runCatching {
        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            this.locale = locale
            this.preferredMode = mode
        }
        SpeechRecognition.getClient(options)
    }.getOrNull()

    private fun modeForEngine(engine: SttEngine): Int? = when (engine) {
        SttEngine.MLKIT_GENAI_ADVANCED -> SpeechRecognizerOptions.Mode.MODE_ADVANCED
        SttEngine.MLKIT_GENAI_BASIC -> SpeechRecognizerOptions.Mode.MODE_BASIC
        SttEngine.OPENCHAMBER_PARAKEET -> null
    }

    companion object {
        const val MIN_GOOGLE_TTS_VERSION_CODE = 210_665_810L

        internal fun resolveSupportedLocale(locale: Locale = Locale.getDefault()): Locale {
            return if (locale.language.equals("en", ignoreCase = true)) Locale.US else locale
        }

        internal fun buildPackageDiagnostic(context: Context): String {
            val pm = context.packageManager
            val ttsPart = runCatching {
                val info = pm.getPackageInfo("com.google.android.tts", 0)
                val code = PackageInfoCompat.getLongVersionCode(info)
                val flag = if (code < MIN_GOOGLE_TTS_VERSION_CODE) " (needs >=$MIN_GOOGLE_TTS_VERSION_CODE)" else ""
                "tts=$code$flag"
            }.getOrElse { "tts=missing" }

            val svcPart = runCatching {
                val intent = Intent().setComponent(
                    ComponentName(
                        "com.google.android.tts",
                        "com.google.android.libraries.speech.transcription.recognition.grpc.GoogleAsrService",
                    ),
                )
                if (pm.resolveService(intent, 0) != null) "asrSvc=ok" else "asrSvc=missing"
            }.getOrElse { "asrSvc=err" }

            val aiCorePart = runCatching {
                val info = pm.getPackageInfo("com.google.android.aicore", 0)
                "aicore=${PackageInfoCompat.getLongVersionCode(info)}"
            }.getOrElse { "aicore=missing" }

            return "$ttsPart, $svcPart, $aiCorePart"
        }
    }
}
