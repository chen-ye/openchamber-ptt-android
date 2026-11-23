package com.openchamber.ptt.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePipelineDefaultsTest {
    @Test
    fun `resolveActiveVoiceChipLabel follows top enabled engine`() {
        val geminiFirst = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true),
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
        )
        assertEquals(
            "Gemini · Sulafat",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = geminiFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = "star02",
                openAiVoice = "onyx",
                systemVoiceLabel = "en-us-local",
            ),
        )

        val mlKitFirst = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false),
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
        )
        assertEquals(
            "ML Kit · star02",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = mlKitFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = "star02",
                openAiVoice = "onyx",
                systemVoiceLabel = null,
            ),
        )

        val openAiFirst = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false),
            TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true),
        )
        assertEquals(
            "OpenAI · onyx",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = openAiFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "onyx",
                systemVoiceLabel = null,
            ),
        )

        val kokoroFirst = listOf(
            TtsEngineOption(TtsEngine.LOCAL_KOKORO, enabled = true),
        )
        assertEquals(
            "Kokoro · Host",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = kokoroFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "alloy",
                systemVoiceLabel = null,
            ),
        )

        val systemFirst = listOf(
            TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
        )
        assertEquals(
            "System · Default",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = systemFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "alloy",
                systemVoiceLabel = null,
            ),
        )

        assertEquals(
            "TTS disabled",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false)),
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "alloy",
                systemVoiceLabel = null,
            ),
        )
    }

    @Test
    fun `describeMlKitVoice maps documented voice profiles`() {
        assertEquals(
            "Female · medium pitch · en-US",
            VoicePipelineDefaults.describeMlKitVoice("star02", "Female", "en-US"),
        )
        assertEquals(
            "Male · low pitch · en-US",
            VoicePipelineDefaults.describeMlKitVoice("star05", "Male", "en-US"),
        )
    }

    @Test
    fun `VoiceRoleResolver guarantees collision-free distinct defaults across Gemini and OpenAI`() {
        val defaultProfiles = RoleVoiceProfileMap()

        assertEquals(
            ResolvedEngineVoice(TtsEngine.GEMINI_BRIDGE, "Kore", isAutoAssigned = true),
            VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.GEMINI_BRIDGE, VoiceRole.AGENT, defaultProfiles),
        )
        assertEquals(
            ResolvedEngineVoice(TtsEngine.GEMINI_BRIDGE, "Puck", isAutoAssigned = true),
            VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.GEMINI_BRIDGE, VoiceRole.OPERATOR, defaultProfiles),
        )
        assertEquals(
            ResolvedEngineVoice(TtsEngine.GEMINI_BRIDGE, "Zephyr", isAutoAssigned = true),
            VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.GEMINI_BRIDGE, VoiceRole.CUE, defaultProfiles),
        )

        assertEquals(
            ResolvedEngineVoice(TtsEngine.OPENAI_CLOUD, "alloy", isAutoAssigned = true),
            VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.AGENT, defaultProfiles),
        )
        assertEquals(
            ResolvedEngineVoice(TtsEngine.OPENAI_CLOUD, "sage", isAutoAssigned = true),
            VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.OPERATOR, defaultProfiles),
        )
        assertEquals(
            ResolvedEngineVoice(TtsEngine.OPENAI_CLOUD, "ash", isAutoAssigned = true),
            VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.CUE, defaultProfiles),
        )
    }

    @Test
    fun `VoiceRoleResolver shifts auto voices when Agent picks Puck or sage`() {
        val puckProfiles = RoleVoiceProfileMap(
            agent = RoleVoiceAssignment(geminiVoice = "Puck"),
        )
        assertEquals("Puck", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.GEMINI_BRIDGE, VoiceRole.AGENT, puckProfiles).voiceId)
        assertEquals("Zephyr", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.GEMINI_BRIDGE, VoiceRole.OPERATOR, puckProfiles).voiceId)
        assertEquals("Charon", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.GEMINI_BRIDGE, VoiceRole.CUE, puckProfiles).voiceId)

        val sageProfiles = RoleVoiceProfileMap(
            agent = RoleVoiceAssignment(openAiVoice = "sage"),
        )
        assertEquals("sage", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.AGENT, sageProfiles).voiceId)
        assertEquals("ash", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.OPERATOR, sageProfiles).voiceId)
        assertEquals("echo", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.CUE, sageProfiles).voiceId)

        // Invalid explicit OpenAI voice falls back cleanly to auto-assigned non-colliding voice
        val invalidOpenAi = RoleVoiceProfileMap(
            agent = RoleVoiceAssignment(openAiVoice = "alloy"),
            operator = RoleVoiceAssignment(openAiVoice = "Kore"),
        )
        val resolvedOp = VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.OPENAI_CLOUD, VoiceRole.OPERATOR, invalidOpenAi)
        assertEquals("sage", resolvedOp.voiceId)
        assertEquals(true, resolvedOp.isAutoAssigned)
    }

    @Test
    fun `VoiceRoleResolver resolves ML Kit and System voices across 1, 2, and 3+ installed voices`() {
        val emptyProfiles = RoleVoiceProfileMap()

        // 1 installed ML Kit voice -> all roles fall back to it
        val oneMlKit = listOf("star02")
        assertEquals("star02", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.AGENT, emptyProfiles, availableMlKitVoices = oneMlKit).voiceId)
        assertEquals("star02", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.OPERATOR, emptyProfiles, availableMlKitVoices = oneMlKit).voiceId)
        assertEquals("star02", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.CUE, emptyProfiles, availableMlKitVoices = oneMlKit).voiceId)

        // 2 installed ML Kit voices -> AGENT gets first, OPERATOR & CUE share second
        val twoMlKit = listOf("star02", "star04")
        assertEquals("star02", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.AGENT, emptyProfiles, availableMlKitVoices = twoMlKit).voiceId)
        assertEquals("star04", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.OPERATOR, emptyProfiles, availableMlKitVoices = twoMlKit).voiceId)
        assertEquals("star04", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.CUE, emptyProfiles, availableMlKitVoices = twoMlKit).voiceId)

        // 3 installed ML Kit voices -> all 3 distinct
        val threeMlKit = listOf("star02", "star04", "star05")
        assertEquals("star02", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.AGENT, emptyProfiles, availableMlKitVoices = threeMlKit).voiceId)
        assertEquals("star04", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.OPERATOR, emptyProfiles, availableMlKitVoices = threeMlKit).voiceId)
        assertEquals("star05", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.MLKIT_GENAI, VoiceRole.CUE, emptyProfiles, availableMlKitVoices = threeMlKit).voiceId)

        // 3 installed Android System voices -> all 3 distinct, and shifts if AGENT picks middle voice
        val threeSys = listOf("en-us-a", "en-us-b", "en-us-c")
        val sysShifted = RoleVoiceProfileMap(agent = RoleVoiceAssignment(systemVoice = "en-us-b"))
        assertEquals("en-us-b", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.ANDROID_SYSTEM, VoiceRole.AGENT, sysShifted, availableSystemVoices = threeSys).voiceId)
        assertEquals("en-us-a", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.ANDROID_SYSTEM, VoiceRole.OPERATOR, sysShifted, availableSystemVoices = threeSys).voiceId)
        assertEquals("en-us-c", VoiceRoleResolver.resolveVoiceForEngine(TtsEngine.ANDROID_SYSTEM, VoiceRole.CUE, sysShifted, availableSystemVoices = threeSys).voiceId)
    }

    @Test
    fun `VoiceRoleResolver pins preferredEngine first and falls back to enabled basePipeline`() {
        val base = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true),
            TtsEngineOption(TtsEngine.LOCAL_KOKORO, enabled = true),
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = false),
            TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
            TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = false),
        )
        val pinned = VoiceRoleResolver.resolvePipelineForRole(
            basePipeline = base,
            roleAssignment = RoleVoiceAssignment(preferredEngine = TtsEngine.OPENAI_CLOUD),
        )
        assertEquals(
            listOf(
                TtsEngine.OPENAI_CLOUD,
                TtsEngine.GEMINI_BRIDGE,
                TtsEngine.LOCAL_KOKORO,
                TtsEngine.ANDROID_SYSTEM,
            ),
            pinned.map { it.engine },
        )
        assertEquals(true, pinned.all { it.enabled })

        val unpinned = VoiceRoleResolver.resolvePipelineForRole(base, RoleVoiceAssignment())
        assertEquals(
            listOf(TtsEngine.GEMINI_BRIDGE, TtsEngine.LOCAL_KOKORO, TtsEngine.ANDROID_SYSTEM),
            unpinned.map { it.engine },
        )
    }

    @Test
    fun `formatRoleSummaryLabel produces concise UI labels`() {
        val base = VoicePipelineDefaults.defaultTtsPipeline
        val profiles = RoleVoiceProfileMap(
            agent = RoleVoiceAssignment(geminiVoice = "Kore"),
            operator = RoleVoiceAssignment(),
            cue = RoleVoiceAssignment(preferredEngine = TtsEngine.OPENAI_CLOUD, openAiVoice = "sage"),
        )
        assertEquals("Gemini · Kore", VoiceRoleResolver.formatRoleSummaryLabel(VoiceRole.AGENT, base, profiles))
        assertEquals("Gemini · Puck (Auto)", VoiceRoleResolver.formatRoleSummaryLabel(VoiceRole.OPERATOR, base, profiles))
        assertEquals("OpenAI · Sage", VoiceRoleResolver.formatRoleSummaryLabel(VoiceRole.CUE, base, profiles))
    }
}
