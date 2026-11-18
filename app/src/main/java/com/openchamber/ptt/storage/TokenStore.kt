package com.openchamber.ptt.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.VoicePipelineDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@Serializable
data class PinnedSession(
    val sessionId: String,
    val directory: String,
    val title: String,
)

@Serializable
data class StagedDraft(
    val sessionKey: String,
    val rawBursts: List<String> = emptyList(),
    val organizedText: String = "",
    val previousOrganizedText: String? = null,
    val updatedAtMs: Long = 0L,
)

@Serializable
data class ServerVoiceOverride(
    val ttsPipeline: List<TtsEngineOption>? = null,
    val ttsInputMode: TtsInputMode? = null,
    val spokenDetailLevel: SpokenDetailLevel? = null,
    val geminiVoice: String? = null,
    val openAiVoice: String? = null,
    val sttEngine: SttEngine? = null,
    val sttFallbackToParakeet: Boolean? = null,
)

@Serializable
data class ServerProfile(
    val id: String,
    val label: String,
    val serverUrl: String,
    val token: String? = null,
    val lastPinnedSession: PinnedSession? = null,
    val voiceOverride: ServerVoiceOverride? = null,
) {
    val displayLabel: String
        get() = label.trim().ifEmpty { deriveServerLabel(serverUrl) }

    val isSignedIn: Boolean
        get() = !token.isNullOrBlank()

    companion object {
        fun deriveServerLabel(serverUrl: String): String {
            val normalized = OpenChamberHttp.normalizeBaseUrl(serverUrl)
            val parsed = normalized.toHttpUrlOrNull() ?: return normalized.ifBlank { "Server" }
            val host = parsed.host
            val shortHost = host.substringBefore('.').takeIf {
                it.isNotBlank() && !host.all { ch -> ch.isDigit() || ch == '.' || ch == ':' }
            } ?: host
            return shortHost
        }
    }
}

class TokenStore(
    private val prefs: SharedPreferences,
    private val json: Json = DefaultJson,
) {
    constructor(context: Context) : this(createEncryptedPreferences(context))

    val changes: StateFlow<Long> = sharedChangeVersion.asStateFlow()

    private fun notifyChanged() {
        sharedChangeVersion.update { it + 1L }
    }

    private inline fun <T> effectiveSetting(
        serverId: String?,
        overridePick: (ServerVoiceOverride) -> T?,
        globalPick: () -> T,
    ): T {
        val override = serverId?.let { getServer(it)?.voiceOverride }
        return override?.let(overridePick) ?: globalPick()
    }

    private inline fun updateSetting(
        customServerId: String?,
        globalSave: () -> Unit,
        overrideUpdate: (ServerVoiceOverride) -> ServerVoiceOverride,
    ) {
        if (customServerId == null) {
            globalSave()
            return
        }
        val cur = getServer(customServerId)?.voiceOverride ?: ServerVoiceOverride()
        setServerVoiceOverride(customServerId, overrideUpdate(cur))
    }

    fun getServers(): List<ServerProfile> {
        val raw = prefs.getString(KEY_SERVERS, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString<List<ServerProfile>>(raw) }.getOrDefault(emptyList())
    }

    fun getActiveServer(): ServerProfile? {
        val servers = getServers()
        if (servers.isEmpty()) return null
        val activeId = prefs.getString(KEY_ACTIVE_SERVER_ID, null)
        return servers.firstOrNull { it.id == activeId } ?: servers.first()
    }

    fun getServer(serverId: String): ServerProfile? =
        getServers().firstOrNull { it.id == serverId }

    fun getServerForUrl(url: HttpUrl): ServerProfile? {
        val targetOrigin = OpenChamberHttp.canonicalOrigin(url)
        val targetPath = url.encodedPath.trimEnd('/')
        return getServers()
            .mapNotNull { profile ->
                val parsed = OpenChamberHttp.normalizeBaseUrl(profile.serverUrl).toHttpUrlOrNull()
                    ?: return@mapNotNull null
                if (!OpenChamberHttp.canonicalOrigin(parsed).equals(targetOrigin, ignoreCase = true)) {
                    return@mapNotNull null
                }
                val basePath = parsed.encodedPath.trimEnd('/')
                val matchesPath = basePath.isEmpty() ||
                    targetPath == basePath ||
                    targetPath.startsWith("$basePath/")
                if (!matchesPath) return@mapNotNull null
                profile to basePath.length
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun getTokenForUrl(url: HttpUrl): String? =
        getServerForUrl(url)?.token?.takeIf { it.isNotBlank() }

    fun getDirectoryForUrl(url: HttpUrl): String? =
        getServerForUrl(url)?.lastPinnedSession?.directory?.takeIf { it.isNotBlank() }

    fun upsertServer(
        serverUrl: String,
        label: String? = null,
        token: String? = null,
        makeActive: Boolean = true,
    ): ServerProfile {
        val normalizedUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val existingList = getServers().toMutableList()
        val matchIndex = existingList.indexOfFirst {
            urlsMatch(it.serverUrl, normalizedUrl)
        }
        val cleanToken = token?.trim()?.takeIf { it.isNotBlank() }
        val cleanLabel = label?.trim()?.takeIf { it.isNotBlank() }

        val updatedProfile = if (matchIndex >= 0) {
            val prev = existingList[matchIndex]
            prev.copy(
                serverUrl = normalizedUrl,
                label = cleanLabel ?: prev.label.ifBlank { ServerProfile.deriveServerLabel(normalizedUrl) },
                token = cleanToken ?: prev.token,
            ).also { existingList[matchIndex] = it }
        } else {
            ServerProfile(
                id = UUID.randomUUID().toString(),
                label = cleanLabel ?: ServerProfile.deriveServerLabel(normalizedUrl),
                serverUrl = normalizedUrl,
                token = cleanToken,
            ).also { existingList += it }
        }

        writeServers(existingList, activeId = if (makeActive) updatedProfile.id else prefs.getString(KEY_ACTIVE_SERVER_ID, null))
        return updatedProfile
    }

    fun setActiveServer(serverId: String): ServerProfile? {
        val servers = getServers()
        val target = servers.firstOrNull { it.id == serverId } ?: return null
        prefs.edit().putString(KEY_ACTIVE_SERVER_ID, target.id).apply()
        notifyChanged()
        return target
    }

    fun updateServerLabel(serverId: String, label: String): ServerProfile? {
        val servers = getServers().toMutableList()
        val idx = servers.indexOfFirst { it.id == serverId }
        if (idx < 0) return null
        val cur = servers[idx]
        val next = cur.copy(label = label.trim().ifBlank { ServerProfile.deriveServerLabel(cur.serverUrl) })
        servers[idx] = next
        writeServers(servers, activeId = prefs.getString(KEY_ACTIVE_SERVER_ID, null))
        return next
    }

    fun updatePinnedSession(serverId: String, session: PinnedSession?) {
        val servers = getServers().toMutableList()
        val idx = servers.indexOfFirst { it.id == serverId }
        if (idx < 0) return
        servers[idx] = servers[idx].copy(lastPinnedSession = session)
        writeServers(servers, activeId = prefs.getString(KEY_ACTIVE_SERVER_ID, null))
    }

    fun clearAuth(serverId: String) {
        val servers = getServers().toMutableList()
        val idx = servers.indexOfFirst { it.id == serverId }
        if (idx < 0) return
        servers[idx] = servers[idx].copy(token = null)
        writeServers(servers, activeId = prefs.getString(KEY_ACTIVE_SERVER_ID, null))
    }

    fun removeServer(serverId: String) {
        val servers = getServers().toMutableList()
        val removed = servers.removeAll { it.id == serverId }
        if (!removed) return
        val currentActiveId = prefs.getString(KEY_ACTIVE_SERVER_ID, null)
        val nextActiveId = if (currentActiveId == serverId) {
            servers.firstOrNull { it.isSignedIn }?.id ?: servers.firstOrNull()?.id
        } else {
            currentActiveId
        }
        writeServers(servers, activeId = nextActiveId)
    }

    fun setServerVoiceOverride(serverId: String, override: ServerVoiceOverride?) {
        val servers = getServers().toMutableList()
        val idx = servers.indexOfFirst { it.id == serverId }
        if (idx < 0) return
        val normalized = override?.let { ov ->
            ov.copy(
                ttsPipeline = ov.ttsPipeline?.let { VoicePipelineDefaults.normalizeTtsPipeline(it) },
                geminiVoice = ov.geminiVoice?.trim()?.takeIf { it.isNotBlank() },
                openAiVoice = ov.openAiVoice?.trim()?.takeIf { it.isNotBlank() },
            )
        }
        servers[idx] = servers[idx].copy(voiceOverride = normalized)
        writeServers(servers, activeId = prefs.getString(KEY_ACTIVE_SERVER_ID, null))
    }

    fun getServerUrl(): String? = getActiveServer()?.serverUrl?.takeIf { it.isNotBlank() }

    fun getToken(): String? = getActiveServer()?.token?.takeIf { it.isNotBlank() }

    fun getPinnedSession(): PinnedSession? = getActiveServer()?.lastPinnedSession

    fun getTtsVoice(): String = prefs.getString(KEY_TTS_VOICE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TTS_VOICE

    fun setTtsVoice(voice: String?) {
        val cleaned = voice?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_TTS_VOICE) else putString(KEY_TTS_VOICE, cleaned)
        }.apply()
        notifyChanged()
    }

    fun getEffectiveTtsVoice(serverId: String? = getActiveServer()?.id): String =
        effectiveSetting(
            serverId = serverId,
            overridePick = { it.geminiVoice?.takeIf(String::isNotBlank) },
            globalPick = ::getTtsVoice,
        )

    fun saveTtsVoice(voice: String?, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setTtsVoice(voice) },
            overrideUpdate = { it.copy(geminiVoice = voice) },
        )

    fun getMlKitTtsVoice(): String? = prefs.getString(KEY_MLKIT_TTS_VOICE, null)?.takeIf { it.isNotBlank() }

    fun setMlKitTtsVoice(voiceName: String?) {
        val cleaned = voiceName?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_MLKIT_TTS_VOICE) else putString(KEY_MLKIT_TTS_VOICE, cleaned)
        }.apply()
        notifyChanged()
    }

    fun getOpenAiTtsVoice(): String =
        prefs.getString(KEY_OPENAI_TTS_VOICE, null)?.takeIf { it.isNotBlank() }
            ?: VoicePipelineDefaults.DEFAULT_OPENAI_VOICE

    fun setOpenAiTtsVoice(voice: String?) {
        val cleaned = voice?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_OPENAI_TTS_VOICE) else putString(KEY_OPENAI_TTS_VOICE, cleaned)
        }.apply()
        notifyChanged()
    }

    fun getEffectiveOpenAiTtsVoice(serverId: String? = getActiveServer()?.id): String =
        effectiveSetting(
            serverId = serverId,
            overridePick = { it.openAiVoice?.takeIf(String::isNotBlank) },
            globalPick = ::getOpenAiTtsVoice,
        )

    fun saveOpenAiTtsVoice(voice: String?, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setOpenAiTtsVoice(voice) },
            overrideUpdate = { it.copy(openAiVoice = voice) },
        )

    fun getSystemTtsVoice(): String? = prefs.getString(KEY_SYSTEM_TTS_VOICE, null)?.takeIf { it.isNotBlank() }

    fun setSystemTtsVoice(voiceName: String?) {
        val cleaned = voiceName?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit().apply {
            if (cleaned == null) remove(KEY_SYSTEM_TTS_VOICE) else putString(KEY_SYSTEM_TTS_VOICE, cleaned)
        }.apply()
        notifyChanged()
    }

    fun getTtsPipeline(): List<TtsEngineOption> {
        val raw = prefs.getString(KEY_TTS_PIPELINE, null)?.takeIf { it.isNotBlank() }
            ?: return VoicePipelineDefaults.defaultTtsPipeline
        val parsed = runCatching { json.decodeFromString<List<TtsEngineOption>>(raw) }.getOrNull()
            ?: return VoicePipelineDefaults.defaultTtsPipeline
        return VoicePipelineDefaults.normalizeTtsPipeline(parsed)
    }

    fun setTtsPipeline(options: List<TtsEngineOption>) {
        val normalized = VoicePipelineDefaults.normalizeTtsPipeline(options)
        prefs.edit().putString(KEY_TTS_PIPELINE, json.encodeToString(normalized)).apply()
        notifyChanged()
    }

    fun getEffectiveTtsPipeline(serverId: String? = getActiveServer()?.id): List<TtsEngineOption> =
        VoicePipelineDefaults.normalizeTtsPipeline(
            effectiveSetting(
                serverId = serverId,
                overridePick = { it.ttsPipeline },
                globalPick = ::getTtsPipeline,
            ),
        )

    fun saveTtsPipeline(options: List<TtsEngineOption>, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setTtsPipeline(options) },
            overrideUpdate = { it.copy(ttsPipeline = options) },
        )

    fun getTtsInputMode(): TtsInputMode {
        val raw = prefs.getString(KEY_TTS_INPUT_MODE, null)?.takeIf { it.isNotBlank() }
            ?: return TtsInputMode.SUMMARIZED
        return runCatching { TtsInputMode.valueOf(raw) }.getOrDefault(TtsInputMode.SUMMARIZED)
    }

    fun setTtsInputMode(mode: TtsInputMode) {
        prefs.edit().putString(KEY_TTS_INPUT_MODE, mode.name).apply()
        notifyChanged()
    }

    fun getEffectiveTtsInputMode(serverId: String? = getActiveServer()?.id): TtsInputMode =
        effectiveSetting(
            serverId = serverId,
            overridePick = { it.ttsInputMode },
            globalPick = ::getTtsInputMode,
        )

    fun saveTtsInputMode(mode: TtsInputMode, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setTtsInputMode(mode) },
            overrideUpdate = { it.copy(ttsInputMode = mode) },
        )

    fun getSpokenDetailLevel(): SpokenDetailLevel {
        val raw = prefs.getString(KEY_SPOKEN_DETAIL_LEVEL, null)?.takeIf { it.isNotBlank() }
            ?: return SpokenDetailLevel.BALANCED
        return runCatching { SpokenDetailLevel.valueOf(raw) }.getOrDefault(SpokenDetailLevel.BALANCED)
    }

    fun setSpokenDetailLevel(level: SpokenDetailLevel) {
        prefs.edit().putString(KEY_SPOKEN_DETAIL_LEVEL, level.name).apply()
        notifyChanged()
    }

    fun getEffectiveSpokenDetailLevel(serverId: String? = getActiveServer()?.id): SpokenDetailLevel =
        effectiveSetting(
            serverId = serverId,
            overridePick = { it.spokenDetailLevel },
            globalPick = ::getSpokenDetailLevel,
        )

    fun saveSpokenDetailLevel(level: SpokenDetailLevel, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setSpokenDetailLevel(level) },
            overrideUpdate = { it.copy(spokenDetailLevel = level) },
        )

    fun getSttEngine(): SttEngine {
        val raw = prefs.getString(KEY_STT_ENGINE, null)?.takeIf { it.isNotBlank() }
            ?: return SttEngine.OPENCHAMBER_PARAKEET
        return runCatching { SttEngine.valueOf(raw) }.getOrDefault(SttEngine.OPENCHAMBER_PARAKEET)
    }

    fun setSttEngine(engine: SttEngine) {
        prefs.edit().putString(KEY_STT_ENGINE, engine.name).apply()
        notifyChanged()
    }

    fun getEffectiveSttEngine(serverId: String? = getActiveServer()?.id): SttEngine =
        effectiveSetting(
            serverId = serverId,
            overridePick = { it.sttEngine },
            globalPick = ::getSttEngine,
        )

    fun saveSttEngine(engine: SttEngine, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setSttEngine(engine) },
            overrideUpdate = { it.copy(sttEngine = engine) },
        )

    fun getSttFallbackToParakeet(): Boolean = prefs.getBoolean(KEY_STT_FALLBACK_PARAKEET, true)

    fun setSttFallbackToParakeet(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STT_FALLBACK_PARAKEET, enabled).apply()
        notifyChanged()
    }

    fun getEffectiveSttFallbackToParakeet(serverId: String? = getActiveServer()?.id): Boolean =
        effectiveSetting(
            serverId = serverId,
            overridePick = { it.sttFallbackToParakeet },
            globalPick = ::getSttFallbackToParakeet,
        )

    fun saveSttFallbackToParakeet(enabled: Boolean, customServerId: String? = null) =
        updateSetting(
            customServerId = customServerId,
            globalSave = { setSttFallbackToParakeet(enabled) },
            overrideUpdate = { it.copy(sttFallbackToParakeet = enabled) },
        )

    fun getOrCreateInstallId(): String {
        val existing = prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }

    fun getAllStagedDrafts(): Map<String, StagedDraft> {
        val raw = prefs.getString(KEY_STAGED_DRAFTS, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, StagedDraft>>(raw) }.getOrDefault(emptyMap())
    }

    fun getStagedDraft(sessionKey: String): StagedDraft? {
        val key = sessionKey.trim()
        if (key.isEmpty()) return null
        return getAllStagedDrafts()[key]?.takeIf {
            it.organizedText.isNotBlank() || it.rawBursts.any { b -> b.isNotBlank() }
        }
    }

    fun saveStagedDraft(draft: StagedDraft) {
        val key = draft.sessionKey.trim()
        if (key.isEmpty()) return
        val normalized = draft.copy(
            sessionKey = key,
            rawBursts = draft.rawBursts.map { it.trim() }.filter { it.isNotEmpty() },
            organizedText = draft.organizedText.trim(),
            previousOrganizedText = draft.previousOrganizedText?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (normalized.organizedText.isEmpty() && normalized.rawBursts.isEmpty()) {
            clearStagedDraft(key)
            return
        }
        val map = getAllStagedDrafts().toMutableMap()
        map[key] = normalized
        prefs.edit().putString(KEY_STAGED_DRAFTS, json.encodeToString(map)).apply()
        notifyChanged()
    }

    fun clearStagedDraft(sessionKey: String) {
        val key = sessionKey.trim()
        if (key.isEmpty()) return
        val map = getAllStagedDrafts().toMutableMap()
        if (map.remove(key) != null) {
            if (map.isEmpty()) {
                prefs.edit().remove(KEY_STAGED_DRAFTS).apply()
            } else {
                prefs.edit().putString(KEY_STAGED_DRAFTS, json.encodeToString(map)).apply()
            }
            notifyChanged()
        }
    }

    fun migrateStagedDraft(oldSessionKey: String, newSessionKey: String): Boolean {
        val fromKey = oldSessionKey.trim()
        val toKey = newSessionKey.trim()
        if (fromKey.isEmpty() || toKey.isEmpty() || fromKey == toKey) return false
        val map = getAllStagedDrafts().toMutableMap()
        val existingOld = map.remove(fromKey) ?: return false
        val validOld = existingOld.takeIf {
            it.organizedText.isNotBlank() || it.rawBursts.any { b -> b.isNotBlank() }
        }
        if (validOld != null) {
            val existingNew = map[toKey]?.takeIf {
                it.organizedText.isNotBlank() || it.rawBursts.any { b -> b.isNotBlank() }
            }
            val winner = if (existingNew != null && existingNew.updatedAtMs >= validOld.updatedAtMs) {
                existingNew
            } else {
                validOld.copy(sessionKey = toKey)
            }
            map[toKey] = winner
        }
        if (map.isEmpty()) {
            prefs.edit().remove(KEY_STAGED_DRAFTS).apply()
        } else {
            prefs.edit().putString(KEY_STAGED_DRAFTS, json.encodeToString(map)).apply()
        }
        notifyChanged()
        return true
    }

    private fun writeServers(servers: List<ServerProfile>, activeId: String?) {
        val resolvedActiveId = activeId?.takeIf { id -> servers.any { it.id == id } }
            ?: servers.firstOrNull()?.id
        prefs.edit().apply {
            putString(KEY_SERVERS, json.encodeToString(servers))
            if (resolvedActiveId == null) {
                remove(KEY_ACTIVE_SERVER_ID)
            } else {
                putString(KEY_ACTIVE_SERVER_ID, resolvedActiveId)
            }
        }.apply()
        notifyChanged()
    }

    private fun urlsMatch(a: String, b: String): Boolean {
        val normA = OpenChamberHttp.normalizeBaseUrl(a)
        val normB = OpenChamberHttp.normalizeBaseUrl(b)
        if (normA.equals(normB, ignoreCase = true)) return true
        val parsedA = normA.toHttpUrlOrNull() ?: return false
        val parsedB = normB.toHttpUrlOrNull() ?: return false
        return OpenChamberHttp.canonicalOrigin(parsedA).equals(
            OpenChamberHttp.canonicalOrigin(parsedB),
            ignoreCase = true,
        ) && parsedA.encodedPath.trimEnd('/') == parsedB.encodedPath.trimEnd('/')
    }

    companion object {
        private val sharedChangeVersion = MutableStateFlow(0L)
        private const val PREFS_FILE_NAME = "openchamber_ptt_secure_prefs"
        private const val KEY_SERVERS = "servers_v1"
        private const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_MLKIT_TTS_VOICE = "mlkit_tts_voice"
        private const val KEY_OPENAI_TTS_VOICE = "openai_tts_voice"
        private const val KEY_SYSTEM_TTS_VOICE = "system_tts_voice"
        private const val KEY_TTS_PIPELINE = "tts_pipeline"
        private const val KEY_TTS_INPUT_MODE = "tts_input_mode"
        private const val KEY_SPOKEN_DETAIL_LEVEL = "spoken_detail_level"
        private const val KEY_STT_ENGINE = "stt_engine"
        private const val KEY_STT_FALLBACK_PARAKEET = "stt_fallback_parakeet"
        const val DEFAULT_TTS_VOICE = "Kore"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_STAGED_DRAFTS = "staged_drafts_v1"

        fun sessionKeyFor(serverId: String, session: PinnedSession): String =
            "$serverId|${session.sessionId}|${session.directory}"

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
