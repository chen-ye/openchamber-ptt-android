package com.openchamber.ptt.tts

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TtsClientTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sanitizeForTts posts mode tts to summarize endpoint and returns summary`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"summary":"The build passed with zero warnings."}"""),
        )

        val httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_secret" })
        val ttsClient = TtsClient(httpClient = httpClient)
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = ttsClient.sanitizeForTts(
            serverUrl = baseUrl,
            text = "```bash\n./gradlew test\n```\nBuild passed with 0 warnings.",
        )

        assertEquals("The build passed with zero warnings.", result)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/text/summarize", req.path)
        assertEquals("Bearer oc_secret", req.getHeader("Authorization"))
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("tts", body["mode"]?.jsonPrimitive?.content)
        assertTrue(body["text"]?.jsonPrimitive?.content?.contains("Build passed") == true)
    }

    @Test
    fun `sanitizeForTts falls back to trimmed text on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"model unavailable"}"""))

        val ttsClient = TtsClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val result = ttsClient.sanitizeForTts(
            serverUrl = baseUrl,
            text = "  Raw assistant reply.  ",
        )

        assertEquals("Raw assistant reply.", result)
    }

    @Test
    fun `ttsHttpClient enforces readTimeout of at least 60 seconds`() {
        val defaultClient = OpenChamberHttp.createClient()
        assertEquals(30_000, defaultClient.readTimeoutMillis)

        val ttsClient = TtsClient(httpClient = defaultClient)
        assertTrue(
            "Expected readTimeoutMillis >= 60_000, got ${ttsClient.ttsHttpClient.readTimeoutMillis}",
            ttsClient.ttsHttpClient.readTimeoutMillis >= 60_000,
        )
    }

    @Test
    fun `synthesizeWithEngine GEMINI_BRIDGE posts expected payload to tts speak and returns audio`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(1, 2, 3, 4),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" })
        val ttsClient = TtsClient(httpClient = httpClient)
        val baseUrl = server.url("/").toString().trimEnd('/')

        val audio = ttsClient.synthesizeWithEngine(
            serverUrl = baseUrl,
            text = "Hello pilot",
            engine = TtsEngine.GEMINI_BRIDGE,
        )

        assertEquals("audio/wav", audio.contentType)
        assertArrayEquals(wavBytes, audio.bytes)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/tts/speak", req.path)
        assertEquals("Bearer oc_tok", req.getHeader("Authorization"))

        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("Hello pilot", body["text"]?.jsonPrimitive?.content)
        assertEquals("http://127.0.0.1:4005/v1", body["baseURL"]?.jsonPrimitive?.content)
        assertEquals("Kore", body["voice"]?.jsonPrimitive?.content)
        assertEquals("google/gemini-3.8-flash-lite-tts", body["model"]?.jsonPrimitive?.content)
        assertEquals(1.0, body["speed"]!!.jsonPrimitive.double, 0.0001)
    }

    @Test
    fun `synthesizeWithEngine LOCAL_KOKORO posts speakerId 0 to dictation tts speak`() = runTest {
        val kokoroWav = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(9, 8, 7, 6),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav; charset=binary")
                .setBody(Buffer().write(kokoroWav)),
        )

        val ttsClient = TtsClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val audio = ttsClient.synthesizeWithEngine(
            serverUrl = baseUrl,
            text = "Kokoro check",
            engine = TtsEngine.LOCAL_KOKORO,
        )

        assertEquals("audio/wav", audio.contentType)
        assertArrayEquals(kokoroWav, audio.bytes)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/dictation/tts/speak", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("Kokoro check", body["text"]?.jsonPrimitive?.content)
        assertEquals(0, body["speakerId"]!!.jsonPrimitive.int)
        assertEquals(1.0, body["speed"]!!.jsonPrimitive.double, 0.0001)
    }

    @Test
    fun `synthesizeWithEngine OPENAI_CLOUD defaults non-OpenAI voice to alloy and omits baseURL`() = runTest {
        val wavBytes = WavParserTest.buildStandardWav(
            sampleRate = 24000,
            channels = 1,
            bitsPerSample = 16,
            pcm = byteArrayOf(4, 3, 2, 1),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wavBytes)),
        )

        val ttsClient = TtsClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val audio = ttsClient.synthesizeWithEngine(
            serverUrl = baseUrl,
            text = "OpenAI fallback",
            voice = "Sulafat",
            engine = TtsEngine.OPENAI_CLOUD,
        )

        assertArrayEquals(wavBytes, audio.bytes)
        val req = server.takeRequest()
        assertEquals("/api/tts/speak", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("alloy", body["voice"]?.jsonPrimitive?.content)
        assertEquals("gpt-4o-mini-tts", body["model"]?.jsonPrimitive?.content)
        assertTrue(!body.containsKey("baseURL"))
    }

    @Test
    fun `synthesizeWithEngine throws TtsException with HTTP statusCode on failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val ttsClient = TtsClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        try {
            ttsClient.synthesizeWithEngine(
                serverUrl = baseUrl,
                text = "Expired token",
                engine = TtsEngine.GEMINI_BRIDGE,
            )
            fail("Expected TtsException")
        } catch (ex: TtsException) {
            assertEquals(401, ex.statusCode)
        }
    }

    @Test
    fun `fetchVoices parses guest service proxy response`() = runTest {
        val innerJson = """{"object":"list","default_voice":"Kore","voices":[{"id":"Kore","name":"Kore","display_name":"Kore","category":"featured","language":"en-US","language_label":"English (US)","gender":"female","accent":"American","style":"Firm","pitch":"medium","description":"Grounded"},{"id":"Puck","name":"Puck","display_name":"Puck","category":"featured","language":"en-US","language_label":"English (US)","gender":"male","accent":"American","style":"Upbeat","pitch":"medium","description":"Upbeat"}]}"""
        val escaped = innerJson.replace("\"", "\\\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":200,"body":"$escaped"}"""))

        val client = TtsClient(OpenChamberHttp.createClient(tokenProvider = { "oc_client_test" }))
        val catalog = client.fetchVoices(server.url("/").toString().trimEnd('/'))

        assertEquals("Kore", catalog.defaultVoice)
        assertEquals(2, catalog.voices.size)
        assertEquals("Puck", catalog.voices[1].id)

        val req = server.takeRequest()
        assertEquals("/api/guests/gemini-tts-bridge/service/request", req.path)
        assertTrue(req.body.readUtf8().contains("/v1/audio/voices"))
    }
}
