package com.openchamber.ptt.tts

import kotlinx.serialization.Serializable

@Serializable
enum class TtsEngine(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
) {
    GEMINI_BRIDGE(
        title = "Gemini Bridge",
        shortTitle = "Gemini",
        subtitle = "Cloud via :4005 bridge",
    ),
    LOCAL_KOKORO(
        title = "Local Kokoro",
        shortTitle = "Kokoro",
        subtitle = "On-host sherpa-onnx WAV",
    ),
    MLKIT_GENAI(
        title = "ML Kit GenAI TTS",
        shortTitle = "ML Kit",
        subtitle = "On-device Gemini Nano voices",
    ),
    ANDROID_SYSTEM(
        title = "Android System TTS",
        shortTitle = "System",
        subtitle = "On-device OS TextToSpeech",
    ),
    OPENAI_CLOUD(
        title = "OpenAI Cloud",
        shortTitle = "OpenAI",
        subtitle = "Direct /api/tts/speak",
    ),
}

@Serializable
data class TtsEngineOption(
    val engine: TtsEngine,
    val enabled: Boolean,
)

@Serializable
enum class TtsInputMode(
    val title: String,
    val subtitle: String,
) {
    SUMMARIZED(
        title = "Spoken",
        subtitle = "Translate technical replies for eyes-free listening at your chosen detail level",
    ),
    SANITIZED(
        title = "Sanitized",
        subtitle = "Strip markdown, code blocks, and URLs via /api/text/summarize",
    ),
    RAW(
        title = "Raw",
        subtitle = "Speak exact reply text without translating or sanitizing",
    ),
}

@Serializable
enum class SpokenDetailLevel(
    val title: String,
    val subtitle: String,
) {
    ESSENTIALS(
        title = "Essentials",
        subtitle = "All outcomes, options, and questions — abstracts away internal code mechanics",
    ),
    BALANCED(
        title = "Balanced",
        subtitle = "Outcomes, options, and named files or modules in spoken prose",
    ),
    DETAILED(
        title = "Detailed",
        subtitle = "Full technical briefing — explains behavior, symbols, metrics, and options",
    ),
}

@Serializable
enum class SttEngine(
    val title: String,
    val subtitle: String,
) {
    OPENCHAMBER_PARAKEET(
        title = "OpenChamber Parakeet",
        subtitle = "Host sherpa-onnx WS + Small Model cleanup",
    ),
    MLKIT_GENAI_ADVANCED(
        title = "ML Kit GenAI (Advanced)",
        subtitle = "On-device Gemini Nano speech recognition",
    ),
    MLKIT_GENAI_BASIC(
        title = "ML Kit GenAI (Basic)",
        subtitle = "On-device ML Kit standard model (API 31+)",
    ),
}

data class OpenAiVoicePreset(
    val id: String,
    val label: String,
    val description: String,
)

object VoicePipelineDefaults {
    const val DEFAULT_OPENAI_VOICE = "alloy"

    val openAiVoices: List<OpenAiVoicePreset> = listOf(
        OpenAiVoicePreset("alloy", "Alloy", "Neutral and balanced"),
        OpenAiVoicePreset("ash", "Ash", "Clear and conversational"),
        OpenAiVoicePreset("ballad", "Ballad", "Warm and expressive"),
        OpenAiVoicePreset("coral", "Coral", "Bright and friendly"),
        OpenAiVoicePreset("echo", "Echo", "Smooth male timbre"),
        OpenAiVoicePreset("fable", "Fable", "Animated storyteller"),
        OpenAiVoicePreset("onyx", "Onyx", "Deep and authoritative"),
        OpenAiVoicePreset("nova", "Nova", "Crisp and upbeat"),
        OpenAiVoicePreset("sage", "Sage", "Calm and measured"),
        OpenAiVoicePreset("shimmer", "Shimmer", "Soft and gentle"),
    )

    private val mlKitVoiceProfiles: Map<String, String> = mapOf(
        "star02" to "Female · medium pitch",
        "star04" to "Male · medium-high pitch",
        "star05" to "Male · low pitch",
        "star08" to "Female · low pitch",
        "star11" to "Male · medium pitch, youthful",
        "star12" to "Female · medium-high pitch",
        "star13" to "Male · medium pitch",
        "star16" to "Female · medium-low pitch",
    )

    fun describeMlKitVoice(name: String, genderLabel: String, localeTag: String): String {
        val profile = mlKitVoiceProfiles[name.lowercase()]
        val parts = buildList {
            if (profile != null) {
                add(profile)
            } else if (genderLabel.isNotBlank()) {
                add(genderLabel)
            }
            if (localeTag.isNotBlank()) {
                add(localeTag)
            }
        }
        return parts.joinToString(" · ")
    }

    val defaultTtsPipeline: List<TtsEngineOption> = listOf(
        TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true),
        TtsEngineOption(TtsEngine.LOCAL_KOKORO, enabled = true),
        TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
        TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
        TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = false),
    )

    fun normalizeTtsPipeline(raw: List<TtsEngineOption>): List<TtsEngineOption> {
        val seen = LinkedHashSet<TtsEngine>()
        val normalized = mutableListOf<TtsEngineOption>()
        for (item in raw) {
            if (seen.add(item.engine)) {
                normalized += item
            }
        }
        for (fallback in defaultTtsPipeline) {
            if (seen.add(fallback.engine)) {
                normalized += fallback
            }
        }
        return normalized
    }

    fun primaryEnabledEngine(pipeline: List<TtsEngineOption>): TtsEngine? =
        pipeline.firstOrNull { it.enabled }?.engine

    fun resolveActiveVoiceChipLabel(
        pipeline: List<TtsEngineOption>,
        geminiVoice: String,
        mlKitVoice: String?,
        openAiVoice: String,
        systemVoiceLabel: String?,
    ): String {
        return when (primaryEnabledEngine(pipeline)) {
            TtsEngine.GEMINI_BRIDGE -> "Gemini · ${geminiVoice.ifBlank { "Kore" }}"
            TtsEngine.MLKIT_GENAI -> "ML Kit · ${mlKitVoice?.takeIf { it.isNotBlank() } ?: "Auto"}"
            TtsEngine.OPENAI_CLOUD -> "OpenAI · ${openAiVoice.ifBlank { DEFAULT_OPENAI_VOICE }}"
            TtsEngine.LOCAL_KOKORO -> "Kokoro · Host"
            TtsEngine.ANDROID_SYSTEM -> "System · ${systemVoiceLabel?.takeIf { it.isNotBlank() } ?: "Default"}"
            null -> "TTS disabled"
        }
    }
}

@Serializable
enum class VoiceRole(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
) {
    AGENT(
        title = "Main Agent",
        shortTitle = "Agent",
        subtitle = "Session replies and Read aloud",
    ),
    OPERATOR(
        title = "Operator",
        shortTitle = "Operator",
        subtitle = "Local answers, tuning confirmations, permission alerts, and draft readback",
    ),
    CUE(
        title = "Vocal Cues",
        shortTitle = "Cues",
        subtitle = "Short state updates and latency-masking phrases",
    ),
}

@Serializable
data class RoleVoiceAssignment(
    val preferredEngine: TtsEngine? = null,
    val geminiVoice: String? = null,
    val openAiVoice: String? = null,
    val kokoroSpeakerId: Int? = null,
    val mlKitVoice: String? = null,
    val systemVoice: String? = null,
)

@Serializable
data class RoleVoiceProfileMap(
    val agent: RoleVoiceAssignment = RoleVoiceAssignment(),
    val operator: RoleVoiceAssignment = RoleVoiceAssignment(),
    val cue: RoleVoiceAssignment = RoleVoiceAssignment(),
) {
    operator fun get(role: VoiceRole): RoleVoiceAssignment = when (role) {
        VoiceRole.AGENT -> agent
        VoiceRole.OPERATOR -> operator
        VoiceRole.CUE -> cue
    }

    fun withRole(role: VoiceRole, assignment: RoleVoiceAssignment): RoleVoiceProfileMap = when (role) {
        VoiceRole.AGENT -> copy(agent = assignment)
        VoiceRole.OPERATOR -> copy(operator = assignment)
        VoiceRole.CUE -> copy(cue = assignment)
    }
}

data class ResolvedEngineVoice(
    val engine: TtsEngine,
    val voiceId: String?,
    val kokoroSpeakerId: Int = TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
    val isAutoAssigned: Boolean,
)

object VoiceRoleResolver {
    private val GEMINI_ROLE_CANDIDATES = listOf(
        "Puck", "Zephyr", "Charon", "Aoede", "Fenrir", "Leda", "Orus",
    )

    private val OPENAI_ROLE_CANDIDATES = listOf(
        "sage", "ash", "echo", "shimmer", "coral", "nova", "onyx", "ballad", "fable",
    )

    fun resolvePipelineForRole(
        basePipeline: List<TtsEngineOption>,
        roleAssignment: RoleVoiceAssignment,
    ): List<TtsEngineOption> {
        val normalized = VoicePipelineDefaults.normalizeTtsPipeline(basePipeline)
        val preferred = roleAssignment.preferredEngine
        return if (preferred != null) {
            listOf(TtsEngineOption(preferred, enabled = true)) +
                normalized.filter { it.enabled && it.engine != preferred }
        } else {
            normalized.filter { it.enabled }
        }
    }

    fun resolveVoiceForEngine(
        engine: TtsEngine,
        role: VoiceRole,
        profiles: RoleVoiceProfileMap,
        availableGeminiVoices: List<String> = emptyList(),
        availableMlKitVoices: List<String> = emptyList(),
        availableSystemVoices: List<String> = emptyList(),
    ): ResolvedEngineVoice = when (engine) {
        TtsEngine.GEMINI_BRIDGE -> {
            val pool = (GEMINI_ROLE_CANDIDATES + availableGeminiVoices)
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase() }
            val explicitAgent = profiles.agent.geminiVoice?.trim()?.takeIf { it.isNotEmpty() }
            val agentVoice = explicitAgent ?: TtsClient.DEFAULT_TTS_VOICE
            val explicitOperator = profiles.operator.geminiVoice?.trim()?.takeIf { it.isNotEmpty() }
            val operatorVoice = explicitOperator
                ?: pool.firstOrNull { !it.equals(agentVoice, ignoreCase = true) }
                ?: agentVoice
            val explicitCue = profiles.cue.geminiVoice?.trim()?.takeIf { it.isNotEmpty() }
            val cueVoice = explicitCue
                ?: pool.firstOrNull {
                    !it.equals(agentVoice, ignoreCase = true) &&
                        !it.equals(operatorVoice, ignoreCase = true)
                }
                ?: operatorVoice
            when (role) {
                VoiceRole.AGENT -> ResolvedEngineVoice(engine, agentVoice, isAutoAssigned = explicitAgent == null)
                VoiceRole.OPERATOR -> ResolvedEngineVoice(engine, operatorVoice, isAutoAssigned = explicitOperator == null)
                VoiceRole.CUE -> ResolvedEngineVoice(engine, cueVoice, isAutoAssigned = explicitCue == null)
            }
        }

        TtsEngine.OPENAI_CLOUD -> {
            val explicitAgent = TtsClient.normalizeOpenAiVoiceOrNull(profiles.agent.openAiVoice)
            val agentVoice = explicitAgent ?: VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
            val explicitOperator = TtsClient.normalizeOpenAiVoiceOrNull(profiles.operator.openAiVoice)
            val operatorVoice = explicitOperator
                ?: OPENAI_ROLE_CANDIDATES.firstOrNull { !it.equals(agentVoice, ignoreCase = true) }
                ?: agentVoice
            val explicitCue = TtsClient.normalizeOpenAiVoiceOrNull(profiles.cue.openAiVoice)
            val cueVoice = explicitCue
                ?: OPENAI_ROLE_CANDIDATES.firstOrNull {
                    !it.equals(agentVoice, ignoreCase = true) &&
                        !it.equals(operatorVoice, ignoreCase = true)
                }
                ?: operatorVoice
            when (role) {
                VoiceRole.AGENT -> ResolvedEngineVoice(engine, agentVoice, isAutoAssigned = explicitAgent == null)
                VoiceRole.OPERATOR -> ResolvedEngineVoice(engine, operatorVoice, isAutoAssigned = explicitOperator == null)
                VoiceRole.CUE -> ResolvedEngineVoice(engine, cueVoice, isAutoAssigned = explicitCue == null)
            }
        }

        TtsEngine.MLKIT_GENAI -> {
            val pool = availableMlKitVoices
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase() }
            fun pickExplicit(raw: String?): String? {
                val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                if (pool.isEmpty()) return clean
                return pool.firstOrNull { it.equals(clean, ignoreCase = true) }
            }
            val explicitAgent = pickExplicit(profiles.agent.mlKitVoice)
            val agentVoice = explicitAgent ?: pool.firstOrNull()
            val explicitOperator = pickExplicit(profiles.operator.mlKitVoice)
            val operatorVoice = explicitOperator
                ?: pool.firstOrNull { agentVoice == null || !it.equals(agentVoice, ignoreCase = true) }
                ?: agentVoice
            val explicitCue = pickExplicit(profiles.cue.mlKitVoice)
            val cueVoice = explicitCue
                ?: pool.firstOrNull {
                    (agentVoice == null || !it.equals(agentVoice, ignoreCase = true)) &&
                        (operatorVoice == null || !it.equals(operatorVoice, ignoreCase = true))
                }
                ?: operatorVoice
                ?: agentVoice
            when (role) {
                VoiceRole.AGENT -> ResolvedEngineVoice(engine, agentVoice, isAutoAssigned = explicitAgent == null)
                VoiceRole.OPERATOR -> ResolvedEngineVoice(engine, operatorVoice, isAutoAssigned = explicitOperator == null)
                VoiceRole.CUE -> ResolvedEngineVoice(engine, cueVoice, isAutoAssigned = explicitCue == null)
            }
        }

        TtsEngine.ANDROID_SYSTEM -> {
            val pool = availableSystemVoices
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase() }
            val explicitAgent = profiles.agent.systemVoice?.trim()?.takeIf { it.isNotEmpty() }
            val agentVoice = explicitAgent ?: pool.firstOrNull()
            val explicitOperator = profiles.operator.systemVoice?.trim()?.takeIf { it.isNotEmpty() }
            val operatorVoice = explicitOperator
                ?: pool.firstOrNull { agentVoice == null || !it.equals(agentVoice, ignoreCase = true) }
                ?: agentVoice
            val explicitCue = profiles.cue.systemVoice?.trim()?.takeIf { it.isNotEmpty() }
            val cueVoice = explicitCue
                ?: pool.firstOrNull {
                    (agentVoice == null || !it.equals(agentVoice, ignoreCase = true)) &&
                        (operatorVoice == null || !it.equals(operatorVoice, ignoreCase = true))
                }
                ?: operatorVoice
                ?: agentVoice
            when (role) {
                VoiceRole.AGENT -> ResolvedEngineVoice(engine, agentVoice, isAutoAssigned = explicitAgent == null)
                VoiceRole.OPERATOR -> ResolvedEngineVoice(engine, operatorVoice, isAutoAssigned = explicitOperator == null)
                VoiceRole.CUE -> ResolvedEngineVoice(engine, cueVoice, isAutoAssigned = explicitCue == null)
            }
        }

        TtsEngine.LOCAL_KOKORO -> {
            val explicitId = profiles[role].kokoroSpeakerId
            ResolvedEngineVoice(
                engine = engine,
                voiceId = null,
                kokoroSpeakerId = explicitId ?: TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
                isAutoAssigned = explicitId == null,
            )
        }
    }

    fun formatRoleSummaryLabel(
        role: VoiceRole,
        basePipeline: List<TtsEngineOption>,
        profiles: RoleVoiceProfileMap,
        availableGeminiVoices: List<String> = emptyList(),
        availableMlKitVoices: List<String> = emptyList(),
        availableSystemVoiceLabels: Map<String, String> = emptyMap(),
    ): String {
        val rolePipeline = resolvePipelineForRole(basePipeline, profiles[role])
        val primary = VoicePipelineDefaults.primaryEnabledEngine(rolePipeline) ?: return "TTS disabled"
        val resolved = resolveVoiceForEngine(
            engine = primary,
            role = role,
            profiles = profiles,
            availableGeminiVoices = availableGeminiVoices,
            availableMlKitVoices = availableMlKitVoices,
            availableSystemVoices = availableSystemVoiceLabels.keys.toList(),
        )
        val autoSuffix = if (resolved.isAutoAssigned) " (Auto)" else ""
        return when (primary) {
            TtsEngine.GEMINI_BRIDGE -> {
                val name = resolved.voiceId?.takeIf { it.isNotBlank() } ?: TtsClient.DEFAULT_TTS_VOICE
                "Gemini · $name$autoSuffix"
            }
            TtsEngine.OPENAI_CLOUD -> {
                val id = resolved.voiceId?.takeIf { it.isNotBlank() } ?: VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
                val label = VoicePipelineDefaults.openAiVoices
                    .firstOrNull { it.id.equals(id, ignoreCase = true) }
                    ?.label
                    ?: id.replaceFirstChar { it.uppercaseChar() }
                "OpenAI · $label$autoSuffix"
            }
            TtsEngine.MLKIT_GENAI -> {
                val name = resolved.voiceId?.takeIf { it.isNotBlank() }
                if (name == null) "ML Kit · Auto" else "ML Kit · $name$autoSuffix"
            }
            TtsEngine.ANDROID_SYSTEM -> {
                val id = resolved.voiceId?.takeIf { it.isNotBlank() }
                if (id == null) {
                    "System · Default"
                } else {
                    val label = availableSystemVoiceLabels[id]
                        ?: id.substringAfterLast('-').ifBlank { id }
                    "System · $label$autoSuffix"
                }
            }
            TtsEngine.LOCAL_KOKORO -> {
                if (resolved.isAutoAssigned) {
                    "Kokoro · Host"
                } else {
                    "Kokoro · #${resolved.kokoroSpeakerId}"
                }
            }
        }
    }
}
