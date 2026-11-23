package com.openchamber.ptt.tts

import android.content.Context
import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class TtsCacheStats(
    val textEntries: Int,
    val textBytes: Long,
    val audioEntries: Int,
    val audioBytes: Long,
) {
    val totalEntries: Int
        get() = textEntries + audioEntries

    val totalBytes: Long
        get() = textBytes + audioBytes
}

class TtsCache(
    private val rootDir: File,
    private val maxTextBytes: Long = DEFAULT_MAX_TEXT_BYTES,
    private val maxAudioBytes: Long = DEFAULT_MAX_AUDIO_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(defaultCacheRoot(context))

    private val textDir = File(rootDir, TEXT_DIR_NAME)
    private val audioDir = File(rootDir, AUDIO_DIR_NAME)
    private val textLock = Any()
    private val audioLock = Any()
    private val flightLock = Any()

    private val flightScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val flights = HashMap<String, FlightEntry>()

    private val memoryTextCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_MEMORY_TEXT_ENTRIES
    }

    private val promptRevisions: Map<SpokenDetailLevel, String> by lazy {
        SpokenDetailLevel.entries.associateWith { level ->
            sha256Hex(TtsClient.buildSpokenSystemPrompt(level)).take(16)
        }
    }

    fun textCacheKey(
        rawText: String,
        inputMode: TtsInputMode,
        detailLevel: SpokenDetailLevel,
    ): String {
        val trimmed = rawText.trim()
        val detailPart = if (inputMode == TtsInputMode.SUMMARIZED) detailLevel.name else "_"
        val promptRevision = when (inputMode) {
            TtsInputMode.SUMMARIZED -> promptRevisions[detailLevel]
                ?: sha256Hex(TtsClient.buildSpokenSystemPrompt(detailLevel)).take(16)
            TtsInputMode.SANITIZED -> SANITIZE_PROMPT_REVISION
            TtsInputMode.RAW -> RAW_PROMPT_REVISION
        }
        return sha256Hex("text_v1\u0000${inputMode.name}\u0000$detailPart\u0000$promptRevision\u0000$trimmed")
    }

    fun getPreparedText(
        rawText: String,
        inputMode: TtsInputMode,
        detailLevel: SpokenDetailLevel,
    ): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        if (inputMode == TtsInputMode.RAW) return null
        if (inputMode == TtsInputMode.SUMMARIZED && !TtsClient.shouldTranslateForSpeech(trimmed)) {
            return null
        }

        val key = textCacheKey(trimmed, inputMode, detailLevel)
        val file = File(textDir, "$key$TEXT_FILE_EXT")
        synchronized(textLock) {
            val memHit = memoryTextCache[key]
            if (memHit != null) {
                if (file.isFile) {
                    runCatching { file.setLastModified(wallClockMs()) }
                    return memHit
                }
                memoryTextCache.remove(key)
            }
            if (!file.isFile) return null
            val diskText = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (diskText == null) {
                runCatching { file.delete() }
                return null
            }
            runCatching { file.setLastModified(wallClockMs()) }
            memoryTextCache[key] = diskText
            return diskText
        }
    }

    fun putPreparedText(
        rawText: String,
        inputMode: TtsInputMode,
        detailLevel: SpokenDetailLevel,
        preparedText: String,
    ) {
        val trimmedRaw = rawText.trim()
        val trimmedPrepared = preparedText.trim()
        if (trimmedRaw.isEmpty() || trimmedPrepared.isEmpty()) return
        if (inputMode == TtsInputMode.RAW) return
        if (inputMode == TtsInputMode.SUMMARIZED && !TtsClient.shouldTranslateForSpeech(trimmedRaw)) {
            return
        }

        val key = textCacheKey(trimmedRaw, inputMode, detailLevel)
        val target = File(textDir, "$key$TEXT_FILE_EXT")
        val bytes = trimmedPrepared.toByteArray(Charsets.UTF_8)
        synchronized(textLock) {
            val nowMs = wallClockMs()
            val wrote = runCatching { atomicWriteBytes(target, bytes, nowMs) }.isSuccess
            if (!wrote) return
            memoryTextCache[key] = trimmedPrepared
            evictTextLocked(protectFileName = target.name)
        }
    }

    fun canonicalVoiceAndModel(
        serverUrl: String,
        engine: TtsEngine,
        voice: String,
        kokoroSpeakerId: Int = TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
    ): Pair<String, String> = when (engine) {
        TtsEngine.GEMINI_BRIDGE -> {
            val normalizedVoice = voice.trim().ifEmpty { TtsClient.DEFAULT_TTS_VOICE }.lowercase(Locale.US)
            "gemini:$normalizedVoice" to TtsClient.DEFAULT_TTS_MODEL
        }
        TtsEngine.OPENAI_CLOUD -> {
            "openai:${TtsClient.normalizeOpenAiVoice(voice)}" to TtsClient.DEFAULT_OPENAI_MODEL
        }
        TtsEngine.LOCAL_KOKORO -> {
            val normalizedUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl).lowercase(Locale.US)
            "kokoro:$normalizedUrl#speaker=$kokoroSpeakerId" to "kokoro"
        }
        TtsEngine.MLKIT_GENAI,
        TtsEngine.ANDROID_SYSTEM,
        -> engine.name.lowercase(Locale.US) to "local"
    }

    fun audioCacheKey(
        serverUrl: String,
        engine: TtsEngine,
        voice: String,
        spokenText: String,
        speed: Double = TtsClient.DEFAULT_TTS_SPEED,
        kokoroSpeakerId: Int = TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
    ): String {
        val (canonicalVoiceKey, modelId) = canonicalVoiceAndModel(
            serverUrl = serverUrl,
            engine = engine,
            voice = voice,
            kokoroSpeakerId = kokoroSpeakerId,
        )
        val speedStr = String.format(Locale.US, "%.2f", speed)
        return sha256Hex("audio_v1\u0000${engine.name}\u0000$canonicalVoiceKey\u0000$modelId\u0000$speedStr\u0000${spokenText.trim()}")
    }

    fun getAudio(key: String): TtsAudio? {
        if (key.isBlank()) return null
        val file = File(audioDir, "$key$AUDIO_FILE_EXT")
        synchronized(audioLock) {
            if (!file.isFile) return null
            val raw = runCatching { file.readBytes() }.getOrNull()
            if (raw == null) {
                runCatching { file.delete() }
                return null
            }
            val decoded = decodeAudioEnvelope(raw)
            if (decoded == null) {
                runCatching { file.delete() }
                return null
            }
            runCatching { file.setLastModified(wallClockMs()) }
            return decoded
        }
    }

    fun putAudio(key: String, audio: TtsAudio) {
        if (key.isBlank() || audio.bytes.isEmpty()) return
        val encoded = encodeAudioEnvelope(audio) ?: return
        val target = File(audioDir, "$key$AUDIO_FILE_EXT")
        synchronized(audioLock) {
            val nowMs = wallClockMs()
            val wrote = runCatching { atomicWriteBytes(target, encoded, nowMs) }.isSuccess
            if (!wrote) return
            evictAudioLocked(protectFileName = target.name)
        }
    }

    suspend fun <T> singleFlight(flightKey: String, block: suspend () -> T): T {
        val entry: FlightEntry
        val isCreator: Boolean
        synchronized(flightLock) {
            val existing = flights[flightKey]
            if (existing != null && existing.deferred.isActive) {
                existing.waiters += 1
                entry = existing
                isCreator = false
            } else {
                val createdDeferred = flightScope.async(start = CoroutineStart.LAZY) {
                    block() as Any?
                }
                val created = FlightEntry(deferred = createdDeferred, waiters = 1)
                flights[flightKey] = created
                createdDeferred.invokeOnCompletion {
                    synchronized(flightLock) {
                        if (flights[flightKey] === created) {
                            flights.remove(flightKey)
                        }
                    }
                }
                entry = created
                isCreator = true
            }
        }

        var counted = true
        try {
            if (isCreator) {
                entry.deferred.start()
            }
            @Suppress("UNCHECKED_CAST")
            val result = entry.deferred.await() as T
            synchronized(flightLock) {
                if (counted) {
                    counted = false
                    entry.waiters = (entry.waiters - 1).coerceAtLeast(0)
                }
            }
            return result
        } catch (ce: CancellationException) {
            val shouldCancelFlight: Boolean
            synchronized(flightLock) {
                if (counted) {
                    counted = false
                    entry.waiters = (entry.waiters - 1).coerceAtLeast(0)
                }
                shouldCancelFlight = entry.waiters == 0
                if (shouldCancelFlight && flights[flightKey] === entry) {
                    flights.remove(flightKey)
                }
            }
            if (shouldCancelFlight) {
                entry.deferred.cancel(ce)
            }
            throw ce
        } catch (t: Throwable) {
            synchronized(flightLock) {
                if (counted) {
                    counted = false
                    entry.waiters = (entry.waiters - 1).coerceAtLeast(0)
                }
            }
            throw t
        }
    }

    fun stats(): TtsCacheStats {
        val (tCount, tBytes) = synchronized(textLock) {
            val files = listEntryFiles(textDir, TEXT_FILE_EXT)
            files.size to files.sumOf { it.length() }
        }
        val (aCount, aBytes) = synchronized(audioLock) {
            val files = listEntryFiles(audioDir, AUDIO_FILE_EXT)
            files.size to files.sumOf { it.length() }
        }
        return TtsCacheStats(
            textEntries = tCount,
            textBytes = tBytes,
            audioEntries = aCount,
            audioBytes = aBytes,
        )
    }

    fun clear() {
        synchronized(textLock) {
            memoryTextCache.clear()
            textDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
        synchronized(audioLock) {
            audioDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    fun close() {
        flightScope.cancel()
    }

    private fun evictTextLocked(protectFileName: String) {
        val files = listEntryFiles(textDir, TEXT_FILE_EXT)
        if (files.isEmpty()) return
        var totalBytes = files.sumOf { it.length() }
        var totalCount = files.size
        if (totalBytes <= maxTextBytes && totalCount <= MAX_TEXT_ENTRIES) return

        val sorted = files.sortedWith(
            compareBy<File>(
                { it.lastModified() },
                { if (it.name == protectFileName) 1 else 0 },
                { it.name },
            ),
        )
        for (file in sorted) {
            if (totalBytes <= maxTextBytes && totalCount <= MAX_TEXT_ENTRIES) break
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                totalBytes = (totalBytes - len).coerceAtLeast(0L)
                totalCount = (totalCount - 1).coerceAtLeast(0)
                val key = file.name.removeSuffix(TEXT_FILE_EXT)
                memoryTextCache.remove(key)
            }
        }
    }

    private fun evictAudioLocked(protectFileName: String) {
        val files = listEntryFiles(audioDir, AUDIO_FILE_EXT)
        if (files.isEmpty()) return
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= maxAudioBytes) return

        val sorted = files.sortedWith(
            compareBy<File>(
                { it.lastModified() },
                { if (it.name == protectFileName) 1 else 0 },
                { it.name },
            ),
        )
        for (file in sorted) {
            if (totalBytes <= maxAudioBytes) break
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                totalBytes = (totalBytes - len).coerceAtLeast(0L)
            }
        }
    }

    private fun listEntryFiles(dir: File, suffix: String): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.isFile && !it.name.startsWith(".") && it.name.endsWith(suffix) }
    }

    private fun atomicWriteBytes(target: File, bytes: ByteArray, modifiedAtMs: Long) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                if (target.exists()) {
                    target.delete()
                }
                if (!tmp.renameTo(target)) {
                    tmp.inputStream().use { input ->
                        FileOutputStream(target).use { out ->
                            input.copyTo(out)
                            out.flush()
                        }
                    }
                    tmp.delete()
                }
            }
            runCatching { target.setLastModified(modifiedAtMs) }
        } finally {
            if (tmp.exists()) {
                runCatching { tmp.delete() }
            }
        }
    }

    private fun encodeAudioEnvelope(audio: TtsAudio): ByteArray? {
        if (audio.bytes.isEmpty()) return null
        val ct = audio.contentType.trim().ifEmpty { "application/octet-stream" }
        val ctBytes = ct.toByteArray(Charsets.UTF_8)
        if (ctBytes.isEmpty() || ctBytes.size > 0xFFFF) return null
        val out = ByteArray(AUDIO_HEADER_SIZE + ctBytes.size + audio.bytes.size)
        out[0] = AUDIO_MAGIC[0]
        out[1] = AUDIO_MAGIC[1]
        out[2] = AUDIO_MAGIC[2]
        out[3] = AUDIO_MAGIC[3]
        out[4] = ((AUDIO_ENVELOPE_VERSION ushr 8) and 0xFF).toByte()
        out[5] = (AUDIO_ENVELOPE_VERSION and 0xFF).toByte()
        out[6] = ((ctBytes.size ushr 8) and 0xFF).toByte()
        out[7] = (ctBytes.size and 0xFF).toByte()
        System.arraycopy(ctBytes, 0, out, AUDIO_HEADER_SIZE, ctBytes.size)
        System.arraycopy(audio.bytes, 0, out, AUDIO_HEADER_SIZE + ctBytes.size, audio.bytes.size)
        return out
    }

    private fun decodeAudioEnvelope(raw: ByteArray): TtsAudio? {
        if (raw.size <= AUDIO_HEADER_SIZE) return null
        if (raw[0] != AUDIO_MAGIC[0] ||
            raw[1] != AUDIO_MAGIC[1] ||
            raw[2] != AUDIO_MAGIC[2] ||
            raw[3] != AUDIO_MAGIC[3]
        ) {
            return null
        }
        val version = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
        val ctLen = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (version != AUDIO_ENVELOPE_VERSION || ctLen <= 0 || AUDIO_HEADER_SIZE + ctLen >= raw.size) {
            return null
        }
        val contentType = String(raw, AUDIO_HEADER_SIZE, ctLen, Charsets.UTF_8).trim()
        if (contentType.isEmpty()) return null
        val audioBytes = raw.copyOfRange(AUDIO_HEADER_SIZE + ctLen, raw.size)
        if (audioBytes.isEmpty()) return null
        return TtsAudio(bytes = audioBytes, contentType = contentType)
    }

    private class FlightEntry(
        val deferred: Deferred<Any?>,
        var waiters: Int,
    )

    companion object {
        const val DEFAULT_MAX_TEXT_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_MAX_AUDIO_BYTES = 64L * 1024L * 1024L
        const val MAX_TEXT_ENTRIES = 2_000
        private const val MAX_MEMORY_TEXT_ENTRIES = 512

        private const val TEXT_DIR_NAME = "text"
        private const val AUDIO_DIR_NAME = "audio"
        private const val TEXT_FILE_EXT = ".txt"
        private const val AUDIO_FILE_EXT = ".entry"

        private const val SANITIZE_PROMPT_REVISION = "sanitize_v1"
        private const val RAW_PROMPT_REVISION = "raw_v1"

        private val AUDIO_MAGIC = byteArrayOf(
            'O'.code.toByte(),
            'C'.code.toByte(),
            'A'.code.toByte(),
            'U'.code.toByte(),
        )
        private const val AUDIO_ENVELOPE_VERSION = 1
        private const val AUDIO_HEADER_SIZE = 8

        fun defaultCacheRoot(context: Context): File =
            File(context.applicationContext.cacheDir, "openchamber_tts_cache/v1")

        internal fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                out.append(HEX_CHARS[v ushr 4])
                out.append(HEX_CHARS[v and 0x0F])
            }
            return out.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
