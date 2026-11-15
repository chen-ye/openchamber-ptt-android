package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DictationClientTest {
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
    fun `transcribe sends exact start chunk finish frames and handles partials and final`() = runTest {
        val serverReceivedFrames = LinkedBlockingQueue<String>()
        val chunk0 = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val chunk1 = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ready"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        serverReceivedFrames.add(text)
                        val obj = json.parseToJsonElement(text).jsonObject
                        val type = obj["type"]?.jsonPrimitive?.content
                        val dictationId = obj["dictationId"]?.jsonPrimitive?.content ?: return
                        when (type) {
                            "start" -> {
                                webSocket.send("""{"type":"ack","dictationId":"$dictationId","ackSeq":-1}""")
                            }

                            "chunk" -> {
                                val seq = obj["seq"]?.jsonPrimitive?.int ?: return
                                webSocket.send("""{"type":"ack","dictationId":"$dictationId","ackSeq":$seq}""")
                                if (seq == 0) {
                                    webSocket.send("""{"type":"partial","dictationId":"other-id","text":"ignore me"}""")
                                    webSocket.send("""{"type":"partial","dictationId":"$dictationId","text":"hello"}""")
                                }
                            }

                            "finish" -> {
                                webSocket.send("""{"type":"finish_accepted","dictationId":"$dictationId","timeoutMs":10000}""")
                                webSocket.send("""{"type":"final","dictationId":"$dictationId","text":"  hello world  "}""")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )

        val client = OpenChamberHttp.createClient(
            tokenProvider = { "oc_client_dictation_token" },
        )
        val dictationClient = DictationClient(httpClient = client)
        val partials = mutableListOf<String>()

        val baseUrl = server.url("/").toString().trimEnd('/')
        val transcript = dictationClient.transcribe(
            serverUrl = baseUrl,
            audioChunks = flowOf(chunk0, chunk1),
            dictationId = "dict-123",
            onPartial = { partials.add(it) },
        )

        assertEquals("hello world", transcript)
        assertEquals(listOf("hello"), partials)

        val recorded = server.takeRequest()
        assertEquals("/api/dictation/ws", recorded.path)
        assertEquals("Bearer oc_client_dictation_token", recorded.getHeader("Authorization"))
        assertEquals("http://${server.hostName}:${server.port}", recorded.getHeader("Origin"))

        val startFrame = json.parseToJsonElement(serverReceivedFrames.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("start", startFrame["type"]?.jsonPrimitive?.content)
        assertEquals("dict-123", startFrame["dictationId"]?.jsonPrimitive?.content)
        assertEquals("audio/pcm;rate=16000;bits=16", startFrame["format"]?.jsonPrimitive?.content)
        val options = startFrame["options"]!!.jsonObject
        assertEquals("local", options["provider"]?.jsonPrimitive?.content)
        assertEquals("en", options["language"]?.jsonPrimitive?.content)
        assertTrue(options["correct"]?.jsonPrimitive?.boolean == true)

        val chunkFrame0 = json.parseToJsonElement(serverReceivedFrames.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("chunk", chunkFrame0["type"]?.jsonPrimitive?.content)
        assertEquals("dict-123", chunkFrame0["dictationId"]?.jsonPrimitive?.content)
        assertEquals(0, chunkFrame0["seq"]?.jsonPrimitive?.int)
        assertEquals(Base64.getEncoder().encodeToString(chunk0), chunkFrame0["audio"]?.jsonPrimitive?.content)

        val chunkFrame1 = json.parseToJsonElement(serverReceivedFrames.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("chunk", chunkFrame1["type"]?.jsonPrimitive?.content)
        assertEquals("dict-123", chunkFrame1["dictationId"]?.jsonPrimitive?.content)
        assertEquals(1, chunkFrame1["seq"]?.jsonPrimitive?.int)
        assertEquals(Base64.getEncoder().encodeToString(chunk1), chunkFrame1["audio"]?.jsonPrimitive?.content)

        val finishFrame = json.parseToJsonElement(serverReceivedFrames.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("finish", finishFrame["type"]?.jsonPrimitive?.content)
        assertEquals("dict-123", finishFrame["dictationId"]?.jsonPrimitive?.content)
        assertEquals(1, finishFrame["finalSeq"]?.jsonPrimitive?.int)
    }

    @Test
    fun `rejects empty and whitespace-only transcripts`() = runTest {
        for (emptyText in listOf("", "   \n\t  ")) {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"ready"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            val type = obj["type"]?.jsonPrimitive?.content
                            val dictationId = obj["dictationId"]?.jsonPrimitive?.content ?: return
                            when (type) {
                                "start" -> {
                                    webSocket.send("""{"type":"ack","dictationId":"$dictationId","ackSeq":-1}""")
                                }

                                "finish" -> {
                                    val escaped = emptyText.replace("\n", "\\n").replace("\t", "\\t")
                                    webSocket.send("""{"type":"final","dictationId":"$dictationId","text":"$escaped"}""")
                                }
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )

            val dictationClient = DictationClient(httpClient = OpenChamberHttp.createClient())
            val baseUrl = server.url("/").toString().trimEnd('/')

            try {
                dictationClient.transcribe(
                    serverUrl = baseUrl,
                    audioChunks = flowOf(byteArrayOf(0x01, 0x02)),
                    dictationId = "dict-empty",
                )
                fail("Expected DictationException for empty/whitespace transcript")
            } catch (ex: DictationException) {
                assertTrue(ex.isEmptyTranscript)
                assertFalse(ex.retryable)
            }
        }
    }

    @Test
    fun `propagates server error frames as DictationException`() = runTest {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ready"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val obj = json.parseToJsonElement(text).jsonObject
                        val type = obj["type"]?.jsonPrimitive?.content
                        val dictationId = obj["dictationId"]?.jsonPrimitive?.content ?: return
                        if (type == "start") {
                            webSocket.send(
                                """{"type":"error","dictationId":"$dictationId","error":"STT worker unavailable","retryable":true,"reasonCode":"worker_down"}""",
                            )
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )

        val dictationClient = DictationClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        try {
            dictationClient.transcribe(
                serverUrl = baseUrl,
                audioChunks = flowOf(byteArrayOf(0x01, 0x02)),
                dictationId = "dict-err",
            )
            fail("Expected DictationException")
        } catch (ex: DictationException) {
            assertEquals("STT worker unavailable", ex.message)
            assertTrue(ex.retryable)
            assertEquals("worker_down", ex.reasonCode)
            assertFalse(ex.isEmptyTranscript)
        }
    }

    @Test
    fun `waits for server ack -1 before sending chunk frames`() = runTest {
        val frameOrder = LinkedBlockingQueue<String>()
        val chunkReceivedBeforeAck = java.util.concurrent.atomic.AtomicBoolean(false)
        val ackSent = java.util.concurrent.atomic.AtomicBoolean(false)

        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ready"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val obj = json.parseToJsonElement(text).jsonObject
                        val type = obj["type"]?.jsonPrimitive?.content ?: return
                        val dictationId = obj["dictationId"]?.jsonPrimitive?.content ?: return
                        frameOrder.add(type)
                        when (type) {
                            "start" -> {
                                Thread {
                                    Thread.sleep(120)
                                    ackSent.set(true)
                                    webSocket.send("""{"type":"ack","dictationId":"$dictationId","ackSeq":-1}""")
                                }.start()
                            }

                            "chunk" -> {
                                if (!ackSent.get()) {
                                    chunkReceivedBeforeAck.set(true)
                                }
                                val seq = obj["seq"]?.jsonPrimitive?.int ?: return
                                webSocket.send("""{"type":"ack","dictationId":"$dictationId","ackSeq":$seq}""")
                            }

                            "finish" -> {
                                webSocket.send("""{"type":"finish_accepted","dictationId":"$dictationId","timeoutMs":10000}""")
                                webSocket.send("""{"type":"final","dictationId":"$dictationId","text":"delayed ack ok"}""")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )

        val dictationClient = DictationClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val transcript = dictationClient.transcribe(
            serverUrl = baseUrl,
            audioChunks = flowOf(byteArrayOf(0x01, 0x02)),
            dictationId = "dict-delay-ack",
        )

        assertEquals("delayed ack ok", transcript)
        assertFalse("chunk arrived before server emitted ack -1", chunkReceivedBeforeAck.get())
        assertEquals("start", frameOrder.poll(2, TimeUnit.SECONDS))
        assertEquals("chunk", frameOrder.poll(2, TimeUnit.SECONDS))
        assertEquals("finish", frameOrder.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun `propagates HTTP 401 statusCode on websocket upgrade rejection`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val dictationClient = DictationClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        try {
            dictationClient.transcribe(
                serverUrl = baseUrl,
                audioChunks = flowOf(byteArrayOf(0x01, 0x02)),
                dictationId = "dict-401",
            )
            fail("Expected DictationException")
        } catch (ex: DictationException) {
            assertEquals(401, ex.statusCode)
        }
    }
}
