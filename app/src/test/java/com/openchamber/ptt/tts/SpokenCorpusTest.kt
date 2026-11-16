package com.openchamber.ptt.tts

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
data class SpokenCorpusFile(
    val version: Int,
    val description: String,
    val samples: List<SpokenCorpusSample>,
)

@Serializable
data class SpokenCorpusSample(
    val id: String,
    val category: String,
    val sessionLabel: String,
    val expectTranslate: Boolean,
    val reply: String,
)

class SpokenCorpusTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadCorpus(): SpokenCorpusFile {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("spoken_corpus/corpus.json"),
        ) { "Missing test resource spoken_corpus/corpus.json" }
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString<SpokenCorpusFile>(reader.readText())
        }
    }

    @Test
    fun `corpus uses only RFC 2606 or loopback URLs and generic home paths`() {
        val corpus = loadCorpus()
        assertTrue("Expected at least 8 diverse corpus samples", corpus.samples.size >= 8)

        val urlHostRegex = Regex("""https?://([^/\s:)\]]+)""")
        val homeUserRegex = Regex("""/home/([^/\s`)\]]+)""")
        val allowedHosts = setOf("127.0.0.1", "localhost")
        val allowedHomeUsers = setOf("dev", "user")

        for (sample in corpus.samples) {
            assertNotNull(sample.id)
            assertTrue(sample.reply.isNotBlank())

            for (text in listOf(sample.reply, sample.sessionLabel)) {
                for (match in urlHostRegex.findAll(text)) {
                    val host = match.groupValues[1].lowercase()
                    val ok = host in allowedHosts ||
                        host == "example.com" ||
                        host == "example.org" ||
                        host == "example.net" ||
                        host.endsWith(".example.com") ||
                        host.endsWith(".example.org") ||
                        host.endsWith(".example.net")
                    assertTrue("Sample ${sample.id} uses non-allowlisted host '$host'", ok)
                }
                for (match in homeUserRegex.findAll(text)) {
                    val user = match.groupValues[1]
                    assertTrue(
                        "Sample ${sample.id} uses non-allowlisted home user '$user'",
                        user in allowedHomeUsers,
                    )
                }
            }
        }
    }

    @Test
    fun `shouldTranslateForSpeech matches expected translation gating across entire corpus`() {
        val corpus = loadCorpus()
        for (sample in corpus.samples) {
            assertEquals(
                "Unexpected shouldTranslateForSpeech for ${sample.id}",
                sample.expectTranslate,
                TtsClient.shouldTranslateForSpeech(sample.reply),
            )
        }
    }

    @Test
    fun `system prompts across all detail levels preserve anti-elision, openui-lang, and phrasing invariants`() {
        for (level in SpokenDetailLevel.entries) {
            val prompt = TtsClient.buildSpokenSystemPrompt(level)
            assertTrue(prompt.contains("NEVER omit"))
            assertTrue(prompt.contains("openui-lang"))
            assertTrue(prompt.contains("Never output markdown syntax"))
            assertTrue(prompt.contains("SSML, or IPA"))
        }
        assertTrue(TtsClient.buildSpokenSystemPrompt(SpokenDetailLevel.ESSENTIALS).contains("Essentials"))
        assertTrue(TtsClient.buildSpokenSystemPrompt(SpokenDetailLevel.BALANCED).contains("Balanced"))
        assertTrue(TtsClient.buildSpokenSystemPrompt(SpokenDetailLevel.DETAILED).contains("Detailed"))
    }

    @Test
    fun `normalizeSpokenOutput sanitizes raw artifacts without dropping identifiers`() {
        val rawSamples = listOf(
            "Session: Bluetooth Controls\n\n*Important* note on `TtsClient.kt` <short pause>!" to
                "Session: Bluetooth Controls. Important note on TtsClient.kt!",
            "### Status\n- **Fixed** [Config](https://docs.example.com/cfg) in `app/MainActivity.kt`" to
                "Status. Fixed Config in app/MainActivity.kt",
            "* First *important* item in `SEND_GRACE` [pause]" to
                "First important item in SEND GRACE",
        )
        for ((input, expected) in rawSamples) {
            val actual = TtsClient.normalizeSpokenOutput(input)
            assertEquals(expected, actual)
            assertFalse(actual.contains("```"))
            assertFalse(actual.contains("`"))
            assertFalse(actual.contains("**"))
            assertFalse(actual.contains("<short pause>"))
        }
    }
}
