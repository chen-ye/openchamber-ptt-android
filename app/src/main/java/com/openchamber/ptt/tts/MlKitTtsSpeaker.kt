package com.openchamber.ptt.tts

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizer
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerOptions
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerRequest
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerResponse
import com.google.mlkit.genai.texttospeech.VoiceInfo
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitDownloadEvent
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Locale

data class MlKitTtsVoiceSummary(
    val name: String,
    val availability: MlKitAvailability,
    val gender: String = "",
    val localeTag: String = "",
) {
    val description: String
        get() = VoicePipelineDefaults.describeMlKitVoice(name, gender, localeTag)
}

class MlKitTtsSpeaker(
    private val defaultLocale: Locale = Locale.US,
) : SystemTtsSpeaker {
    private val clientLock = Any()
    private var synthesizer: SpeechSynthesizer? = null
    @Volatile
    private var closed = false
    @Volatile
    var availableVoiceNames: List<String> = emptyList()
        private set

    override val availableVoices: List<String>
        get() = availableVoiceNames

    private fun getOrCreateClientWithError(): Pair<SpeechSynthesizer?, String?> = synchronized(clientLock) {
        if (closed) return null to "client closed"
        synthesizer?.let { return it to null }
        return try {
            val options: SpeechSynthesizerOptions = SpeechSynthesizerOptions.builder(defaultLocale)
                .setVoiceFallbackEnabled(false)
                .build()
            val created = SpeechSynthesis.getClient(options)
            synthesizer = created
            created to null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null to "getClient failed: ${t::class.simpleName}: ${t.message ?: "unknown"}"
        }
    }

    private fun getOrCreateClient(): SpeechSynthesizer? = getOrCreateClientWithError().first

    private suspend fun resolveSupportedVoices(
        client: SpeechSynthesizer,
        preferredLocale: Locale,
    ): Pair<Locale, List<VoiceInfo>> {
        val candidates = buildList {
            add(preferredLocale)
            val sys = Locale.getDefault()
            if (sys != preferredLocale) add(sys)
            if (preferredLocale != Locale.ROOT) add(Locale.ROOT)
        }
        for (candidate in candidates) {
            val found = client.getSupportedVoices(candidate)
            if (found.isNotEmpty()) {
                return candidate to found
            }
        }
        return preferredLocale to emptyList()
    }

    suspend fun inspectStatus(
        context: android.content.Context? = null,
        locale: Locale = defaultLocale,
    ): Pair<MlKitStatusReport, List<MlKitTtsVoiceSummary>> =
        withContext(Dispatchers.IO) {
            val pkgHint = context?.let { MlKitSttClient.buildPackageDiagnostic(it) }
            val (client, clientError) = getOrCreateClientWithError()
            if (client == null) {
                val detail = listOfNotNull(clientError, pkgHint).joinToString(" | ").ifBlank { null }
                return@withContext (MlKitStatusReport(MlKitAvailability.UNAVAILABLE, detail) to emptyList())
            }
            try {
                val (matchedLocale, voices) = resolveSupportedVoices(client, locale)
                if (voices.isEmpty()) {
                    val tried = buildList {
                        add(locale)
                        val sys = Locale.getDefault()
                        if (sys != locale) add(sys)
                        if (locale != Locale.ROOT) add(Locale.ROOT)
                    }.joinToString("/") {
                        it.toLanguageTag().takeIf { tag -> tag.isNotBlank() && tag != "und" } ?: "root"
                    }
                    return@withContext (
                        MlKitStatusReport(
                            MlKitAvailability.UNAVAILABLE,
                            "0 supported voices [$tried]${pkgHint?.let { " ($it)" }.orEmpty()}",
                        ) to emptyList()
                    )
                }
                val summaries = voices.map { v ->
                    val statusCode = runCatching { client.checkStatus(v) }.getOrNull()
                    val avail = when (statusCode) {
                        FeatureStatus.AVAILABLE -> MlKitAvailability.AVAILABLE
                        FeatureStatus.DOWNLOADABLE -> MlKitAvailability.DOWNLOADABLE
                        FeatureStatus.DOWNLOADING -> MlKitAvailability.DOWNLOADING
                        else -> MlKitAvailability.UNAVAILABLE
                    }
                    val genderStr = when (v.gender) {
                        VoiceInfo.Gender.FEMALE -> "Female"
                        VoiceInfo.Gender.MALE -> "Male"
                        else -> ""
                    }
                    MlKitTtsVoiceSummary(
                        name = v.name,
                        availability = avail,
                        gender = genderStr,
                        localeTag = v.locale.toLanguageTag(),
                    )
                }
                val overall = when {
                    summaries.any { it.availability == MlKitAvailability.AVAILABLE } -> MlKitAvailability.AVAILABLE
                    summaries.any { it.availability == MlKitAvailability.DOWNLOADING } -> MlKitAvailability.DOWNLOADING
                    summaries.any { it.availability == MlKitAvailability.DOWNLOADABLE } -> MlKitAvailability.DOWNLOADABLE
                    else -> MlKitAvailability.UNAVAILABLE
                }
                availableVoiceNames = summaries
                    .filter { it.availability == MlKitAvailability.AVAILABLE }
                    .map { it.name }
                val detail = if (overall == MlKitAvailability.UNAVAILABLE) {
                    "voices=${voices.size} (${matchedLocale.toLanguageTag()})${pkgHint?.let { ", $it" }.orEmpty()}"
                } else {
                    null
                }
                MlKitStatusReport(overall, detail) to summaries
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                MlKitStatusReport(
                    MlKitAvailability.UNAVAILABLE,
                    "${t::class.simpleName}: ${t.message ?: "unknown"}${pkgHint?.let { " ($it)" }.orEmpty()}",
                ) to emptyList()
            }
        }

    fun downloadVoice(
        voiceName: String? = null,
        locale: Locale = defaultLocale,
    ): Flow<MlKitDownloadEvent> = flow {
        val client = getOrCreateClient()
            ?: throw IllegalStateException("ML Kit GenAI TTS unavailable on this device")
        val (_, voices) = resolveSupportedVoices(client, locale)
        val target = resolveVoice(voices, voiceName)
            ?: throw IllegalStateException("No supported ML Kit GenAI TTS voice found")
        client.download(target).collect { status ->
            when (status) {
                is DownloadStatus.DownloadProgress -> emit(MlKitDownloadEvent.Progress(status.totalBytesDownloaded))
                is DownloadStatus.DownloadCompleted -> emit(MlKitDownloadEvent.Completed)
                is DownloadStatus.DownloadFailed -> emit(MlKitDownloadEvent.Failed(status.e.message ?: "Download failed"))
                else -> Unit
            }
        }
    }

    override suspend fun speak(
        text: String,
        preferredVoiceName: String?,
        useCommunicationUsage: Boolean,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val client = getOrCreateClient()
            ?: throw TtsException(message = "ML Kit GenAI TTS unavailable on this device")

        val (_, voices) = runCatching { resolveSupportedVoices(client, defaultLocale) }
            .getOrDefault(defaultLocale to emptyList())
        val readyVoices = voices.filter { v ->
            runCatching { client.checkStatus(v) }.getOrNull() == FeatureStatus.AVAILABLE
        }
        availableVoiceNames = readyVoices.map { it.name }
        val selectedVoice = resolveVoice(readyVoices, preferredVoiceName)
            ?: throw TtsException(message = "No installed ML Kit GenAI TTS voice available")

        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (useCommunicationUsage) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val maxLen = runCatching { TextToSpeech.getMaxSpeechInputLength() }
            .getOrDefault(3_900)
            .coerceAtLeast(200)
        val chunks = splitTextForSpeech(trimmed, maxLen)

        withContext(Dispatchers.IO) {
            for ((index, chunk) in chunks.withIndex()) {
                val queueMode = if (index == 0) {
                    SpeechSynthesizerRequest.QueueMode.QUEUE_FLUSH
                } else {
                    SpeechSynthesizerRequest.QueueMode.QUEUE_ADD
                }
                val request = SpeechSynthesizerRequest(
                    input = SpeechSynthesizerRequest.Input.Text(chunk, selectedVoice),
                    audioAttributes = attributes,
                    queueMode = queueMode,
                )
                var errorMessage: String? = null
                client.synthesize(request).collect { response ->
                    when (response) {
                        is SpeechSynthesizerResponse.Error -> {
                            errorMessage = "ML Kit GenAI TTS error (${response.errorCode})"
                        }
                        is SpeechSynthesizerResponse.StatusUpdate -> {
                            if (response.status == SpeechSynthesizerResponse.Status.STOP) {
                                throw CancellationException("ML Kit GenAI TTS stopped")
                            }
                        }
                        else -> Unit
                    }
                }
                val err = errorMessage
                if (err != null) {
                    throw TtsException(message = err)
                }
            }
        }
    }

    override fun stop() {
        val client = synchronized(clientLock) { synthesizer } ?: return
        runCatching { client.stop() }
    }

    override fun shutdown() {
        val clientToClose = synchronized(clientLock) {
            closed = true
            val c = synthesizer
            synthesizer = null
            c
        }
        runCatching { clientToClose?.stop() }
        runCatching { clientToClose?.close() }
    }

    private fun resolveVoice(
        voices: List<VoiceInfo>,
        preferredName: String?,
    ): VoiceInfo? {
        if (voices.isEmpty()) return null
        if (!preferredName.isNullOrBlank()) {
            voices.firstOrNull { it.name.equals(preferredName, ignoreCase = true) }?.let { return it }
        }
        return voices.firstOrNull()
    }
}
