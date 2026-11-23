package com.openchamber.ptt.service

import android.content.SharedPreferences
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.SessionReplyCursor
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.TtsCache
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.voice.AssistantResponseItem
import com.openchamber.ptt.voice.DirectVoiceAnswer
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.SessionTurnProjection
import com.openchamber.ptt.voice.TurnEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SessionResponseControllerTest {
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(20)

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class InMemoryPrefs : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = SENTINEL }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in pending) {
                    if (v === SENTINEL || v == null) map.remove(k) else map[k] = v
                }
            }
        }
        private object SENTINEL
    }

    private fun createController(
        scope: CoroutineScope,
        baseUrl: String,
        stateProvider: () -> PttState,
        playbackProvider: () -> Boolean = { false },
        onAutoSpeak: () -> Unit = {},
        lookupCachedSpokenSummary: ((String) -> String?)? = null,
        clockElapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
    ): Triple<TokenStore, BoundServerSession, SessionResponseController> {
        val store = TokenStore(InMemoryPrefs())
        val srv = store.upsertServer(baseUrl, label = "Local", token = "tok_test", makeActive = true)
        val pinned = PinnedSession("ses_1", "/repo", "Main")
        store.updatePinnedSession(srv.id, pinned)
        val session = BoundServerSession(
            serverId = srv.id,
            serverUrl = baseUrl,
            pinnedSession = pinned,
            sessionKey = TokenStore.sessionKeyFor(srv.id, pinned),
        )
        val http = OpenChamberHttp.createClient(
            tokenForUrlProvider = { url -> store.getTokenForUrl(url) },
            directoryForUrlProvider = { url -> store.getDirectoryForUrl(url) },
        )
        val controller = SessionResponseController(
            scope = scope,
            tokenStore = store,
            sessionClient = SessionClient(http),
            eventStreamClient = EventStreamClient(http, initialReconnectDelayMs = 10L, maxReconnectDelayMs = 50L),
            currentState = stateProvider,
            isPlaybackActive = playbackProvider,
            onTriggerAutoSpeak = onAutoSpeak,
            lookupCachedSpokenSummary = lookupCachedSpokenSummary,
            clockElapsedMs = clockElapsedMs,
        )
        controller.bindSession(session, forceClear = true)
        return Triple(store, session, controller)
    }

    @Test
    fun `armed WS triggers auto-speak on execution succeeded and synthetic arrived without duplicating on idle status`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference("""{"data":[],"cursor":{}}""")
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val sm = PttStateMachine(initialState = PttState.ARMED_IDLE)
                val autoSpeakLatch = CountDownLatch(1)
                val autoSpeakCount = AtomicInteger(0)
                lateinit var ctrl: SessionResponseController

                val (_, _, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { sm.currentState },
                    onAutoSpeak = {
                        autoSpeakCount.incrementAndGet()
                        sm.dispatch(PttEvent.PlayQueuedReply)
                        autoSpeakLatch.countDown()
                    },
                )
                ctrl = controller

                // Initial sync on empty session seeds SESSION_START_BASELINE
                ctrl.syncSessionTurns("arm")
                val wsSubscribedLatch = CountDownLatch(1)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                        "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    wsSocketRef.set(webSocket)
                                    webSocket.send("""{"type":"ready","scope":"global"}""")
                                    wsSubscribedLatch.countDown()
                                }
                            },
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                ctrl.setArmed(true)
                assertTrue(wsSubscribedLatch.await(3, TimeUnit.SECONDS))
                // Wait deterministically until controller's WS collector finishes processing ready frame
                val activeTurnReady = CountDownLatch(1)
                ctrl.observeEventsForActiveTurn(baseUrl, "ses_1") {
                    activeTurnReady.countDown()
                }
                val collector = async(Dispatchers.IO) {
                    ctrl.observeEventsForActiveTurn(baseUrl, "ses_1") { activeTurnReady.countDown() }
                        .take(1)
                        .toList()
                }
                assertTrue(activeTurnReady.await(3, TimeUnit.SECONDS))
                collector.cancel()

                // Async synthetic result arrives, then finishes
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Worker finished build."}]},
                        {"id":"syn_1","type":"synthetic","time":{"created":100},"metadata":{"source":"openchamber-session","title":"Worker"},"text":"<openchamber-session title=\"Worker\">done</openchamber-session>"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                val ws = wsSocketRef.get()!!
                ws.send("""{"type":"event","eventId":"e1","payload":{"type":"session.synthetic","data":{"sessionID":"ses_1","metadata":{"source":"openchamber-session"}}}}""")
                ws.send("""{"type":"event","eventId":"e2","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")

                assertTrue("Expected auto-speak trigger", autoSpeakLatch.await(3, TimeUnit.SECONDS))
                assertEquals(1, autoSpeakCount.get())
                assertEquals(PttState.SPEAKING_TTS, sm.currentState)
                val item = ctrl.nextItemToAutoSpeak()!!
                assertEquals("a1", item.messageId)

                // Speak & finish item, returning to ARMED_IDLE
                ctrl.setSpeakingMessage(item.messageId)
                ctrl.markItemHeard(item)
                ctrl.setSpeakingMessage(null)
                sm.dispatch(PttEvent.TtsFinished)
                assertEquals(PttState.ARMED_IDLE, sm.currentState)

                // Back-to-back duplicate openchamber:session-status: idle must NOT duplicate or trigger auto-speak
                val dupSyncDone = CountDownLatch(1)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        messagesPath -> {
                            dupSyncDone.countDown()
                            MockResponse().setResponseCode(200).setBody(currentBody.get())
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                ws.send("""{"type":"event","eventId":"e3","payload":{"type":"openchamber:session-status","properties":{"sessionID":"ses_1","status":"idle"}}}""")
                assertTrue(dupSyncDone.await(3, TimeUnit.SECONDS))
                ctrl.syncSessionTurns("fence_after_dup")

                assertEquals(1, autoSpeakCount.get())
                assertNull(ctrl.nextItemToAutoSpeak())
                assertTrue(ctrl.feedState.value.unreadQueue.isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `non-interrupting queueing defers auto-speak during RECORDING SEND_GRACE and SPEAKING_TTS until ARMED_IDLE or STAGED_DRAFT`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference("""{"data":[],"cursor":{}}""")
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val currentState = AtomicReference(PttState.RECORDING)
                val triggerCount = AtomicInteger(0)
                val (_, _, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { currentState.get() },
                    onAutoSpeak = { triggerCount.incrementAndGet() },
                )

                controller.syncSessionTurns("arm")
                controller.setArmed(true)
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))
                val readyFence = CountDownLatch(1)
                val readyJob = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { readyFence.countDown() }
                        .take(1)
                        .toList()
                }
                assertTrue(readyFence.await(3, TimeUnit.SECONDS))
                readyJob.cancel()

                val ws = wsSocketRef.get()!!

                // 1) Async completion arrives while RECORDING -> queues without interrupting
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Async 1"}]}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                ws.send("""{"type":"event","eventId":"e1","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                withTimeout(3_000L) {
                    controller.feedState.first { it.unreadQueue.map(AssistantResponseItem::messageId) == listOf("a1") }
                }
                assertEquals(0, triggerCount.get())

                // 2) Second async arrives while SEND_GRACE -> still queues without interrupting
                currentState.set(PttState.SEND_GRACE)
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":350}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":330},"content":[{"type":"text","text":"Async 2"}]},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Async 1"}]}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                ws.send("""{"type":"event","eventId":"e2","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                withTimeout(3_000L) {
                    controller.feedState.first { it.unreadQueue.map(AssistantResponseItem::messageId) == listOf("a1", "a2") }
                }
                assertEquals(0, triggerCount.get())

                // 3) Third async arrives while SPEAKING_TTS -> still does not emit PlayQueuedReply
                currentState.set(PttState.SPEAKING_TTS)
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a3","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Async 3"}]},
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":350}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":330},"content":[{"type":"text","text":"Async 2"}]},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Async 1"}]}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                ws.send("""{"type":"event","eventId":"e3","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                withTimeout(3_000L) {
                    controller.feedState.first { it.unreadQueue.map(AssistantResponseItem::messageId) == listOf("a1", "a2", "a3") }
                }
                assertEquals(0, triggerCount.get())

                // Returning to STAGED_DRAFT triggers PlayQueuedReply
                currentState.set(PttState.STAGED_DRAFT)
                controller.maybeTriggerAutoSpeakIfIdle()
                assertEquals(1, triggerCount.get())

                // Returning to ARMED_IDLE also triggers when unread remains and not paused
                currentState.set(PttState.ARMED_IDLE)
                controller.maybeTriggerAutoSpeakIfIdle()
                assertEquals(2, triggerCount.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `barge-in during item 1 of 3 respects 1_5s audible threshold advances cursor pauses auto-speak and feeds VoiceReplyContext`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":900}},
                    {"id":"a3","type":"assistant","finish":"stop","time":{"created":850,"completed":880},"content":[{"type":"text","text":"Subagent finished linting."}]},
                    {"id":"syn_3","type":"synthetic","time":{"created":800},"metadata":{"source":"subagent","agent":"explore"},"text":"<subagent agent=\"explore\">done</subagent>"},
                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":600}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":550,"completed":580},"content":[{"type":"text","text":"Worker A built APK."}]},
                    {"id":"syn_2","type":"synthetic","time":{"created":500},"metadata":{"source":"openchamber-session","title":"Worker A"},"text":"<openchamber-session title=\"Worker A\">done</openchamber-session>"},
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Primary fix applied."}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"Fix the bug"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val triggerCount = AtomicInteger(0)
                var nowElapsedMs = 10_000L
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = { triggerCount.incrementAndGet() },
                    clockElapsedMs = { nowElapsedMs },
                )

                // Seed cursor & armed anchor at session start so all 3 items are post-arm unread
                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(
                        armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE,
                        updatedAtMs = 10L,
                    ),
                )
                controller.setArmed(true)
                controller.ensureAutoSpeakAnchorBeforeTurn(SessionTurnProjection.SESSION_START_BASELINE)
                controller.syncSessionTurns("initial")
                assertEquals(listOf("a1", "a2", "a3"), controller.feedState.value.unreadQueue.map { it.messageId })

                // 1) Barge in after < 1.5s audible -> keeps a1 unread, pauses auto-speak, preserves focus on a1
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 5_000L)
                nowElapsedMs += 1_499L
                controller.onUserBargeIn()

                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertTrue(controller.feedState.value.autoSpeakPaused)
                assertEquals("a1", controller.feedState.value.focusedMessageId)
                assertNull(controller.feedState.value.speakingMessageId)
                assertEquals(listOf("a1", "a2", "a3"), controller.feedState.value.unreadQueue.map { it.messageId })

                // 2) Resume item 1 and barge in after >= 1.5s audible -> marks a1 heard and keeps 2 & 3 unread
                nowElapsedMs = 20_000L
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 5_000L)
                nowElapsedMs += 1_500L
                controller.onUserBargeIn()

                assertEquals("a1", store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertTrue(controller.feedState.value.autoSpeakPaused)
                assertEquals("a1", controller.feedState.value.focusedMessageId)
                assertNull(controller.feedState.value.speakingMessageId)
                assertEquals(listOf("a2", "a3"), controller.feedState.value.unreadQueue.map { it.messageId })

                // Returning idle / duplicate sync must NOT unpause or auto-speak a2/a3
                controller.maybeTriggerAutoSpeakIfIdle()
                controller.syncSessionTurns("duplicate_idle", triggerAutoSpeak = true)
                assertEquals(0, triggerCount.get())
                assertTrue(controller.feedState.value.autoSpeakPaused)

                // VoiceReplyContext must feed interrupted item 1 + unread [2, 3]
                val ctx = controller.buildVoiceReplyContext()
                assertEquals("Primary fix applied.", ctx.focusedReply)
                assertEquals(listOf("a2", "a3"), ctx.pendingUnreadPreviews.map { it.messageId })
                assertEquals(listOf("Worker A", "explore"), ctx.pendingUnreadPreviews.map { it.sourceLabel })

                // When a brand-new 4th completed item arrives after Established anchor, autoSpeakPaused resets
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_4","type":"idle","outcome":"succeeded","time":{"created":1200}},
                        {"id":"a4","type":"assistant","finish":"stop","time":{"created":1100,"completed":1150},"content":[{"type":"text","text":"Fourth reply."}]},
                        {"id":"u4","type":"user","time":{"created":1000},"text":"Next"},
                        {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":900}},
                        {"id":"a3","type":"assistant","finish":"stop","time":{"created":850,"completed":880},"content":[{"type":"text","text":"Subagent finished linting."}]},
                        {"id":"syn_3","type":"synthetic","time":{"created":800},"metadata":{"source":"subagent","agent":"explore"},"text":"<subagent agent=\"explore\">done</subagent>"},
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":600}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":550,"completed":580},"content":[{"type":"text","text":"Worker A built APK."}]},
                        {"id":"syn_2","type":"synthetic","time":{"created":500},"metadata":{"source":"openchamber-session","title":"Worker A"},"text":"<openchamber-session title=\"Worker A\">done</openchamber-session>"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Primary fix applied."}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"Fix the bug"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                controller.syncSessionTurns("ws_new_4", triggerAutoSpeak = true)
                assertFalse(controller.feedState.value.autoSpeakPaused)
                assertEquals(1, triggerCount.get())
                assertEquals("a2", controller.nextItemToAutoSpeak()?.messageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `concurrent markAllResponsesRead while syncSessionTurns HTTP GET is in flight is not overwritten by stale cursor`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val reqCount = AtomicInteger(0)
            val httpStarted = CountDownLatch(1)
            val allowHttpFinish = CountDownLatch(1)
            val payload = """
                {
                  "data": [
                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":600}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":500,"completed":550},"content":[{"type":"text","text":"Reply 2"}]},
                    {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Reply 1"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"},
                    {"id":"a0","type":"assistant","finish":"stop","time":{"created":50,"completed":80},"content":[{"type":"text","text":"Baseline"}]}
                  ],
                  "cursor":{}
                }
            """.trimIndent()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != messagesPath) return MockResponse().setResponseCode(404)
                    val call = reqCount.getAndIncrement()
                    if (call == 1) {
                        httpStarted.countDown()
                        allowHttpFinish.await(5, TimeUnit.SECONDS)
                    }
                    return MockResponse().setResponseCode(200).setBody(payload)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                )
                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(
                        lastHeardMessageId = "a0",
                        lastHeardCreatedAtMs = 50L,
                        armedBaselineMessageId = "a0",
                        armedBaselineCreatedAtMs = 50L,
                        updatedAtMs = 50L,
                    ),
                )
                controller.syncSessionTurns("initial")
                assertEquals(listOf("a1", "a2"), controller.feedState.value.unreadQueue.map { it.messageId })

                // Start an in-flight sync that blocks on the server
                val inflightSync = async(Dispatchers.IO) {
                    controller.syncSessionTurns("inflight")
                }
                assertTrue(httpStarted.await(3, TimeUnit.SECONDS))

                // Mark all read concurrently while HTTP GET is in flight
                controller.markAllResponsesRead()
                assertEquals("a2", store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertTrue(controller.feedState.value.unreadQueue.isEmpty())

                // Release in-flight HTTP GET (returns stale pre-read window)
                allowHttpFinish.countDown()
                val finalFeed = inflightSync.await()

                assertEquals("a2", store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertTrue(finalFeed.unreadQueue.isEmpty())
                assertTrue(controller.feedState.value.unreadQueue.isEmpty())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `first sync on existing desktop session suppresses history while empty session uses SESSION_START_BASELINE so turn 1 speaks`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val bodyRef = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":400,"completed":450},"content":[{"type":"text","text":"Desktop 2"}]},
                    {"id":"u2","type":"user","time":{"created":300},"text":"Two"},
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":100,"completed":150},"content":[{"type":"text","text":"Desktop 1"}]},
                    {"id":"u1","type":"user","time":{"created":50},"text":"One"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(bodyRef.get())
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                // Case 1: Existing desktop session
                val (store1, session1, ctrl1) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                )
                ctrl1.syncSessionTurns("desktop_init")
                val cursor1 = store1.getReplyCursor(session1.sessionKey)
                assertEquals("idle_2", cursor1?.armedBaselineMessageId)
                assertTrue(ctrl1.feedState.value.unreadQueue.isEmpty())
                assertNull(ctrl1.nextItemToAutoSpeak())

                // New turn 3 arrives after baseline -> only a3 speaks
                bodyRef.set(
                    """
                    {
                      "data": [
                        {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":700}},
                        {"id":"a3","type":"assistant","finish":"stop","time":{"created":650,"completed":680},"content":[{"type":"text","text":"Turn 3"}]},
                        {"id":"u3","type":"user","time":{"created":600},"text":"Three"},
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":400,"completed":450},"content":[{"type":"text","text":"Desktop 2"}]},
                        {"id":"u2","type":"user","time":{"created":300},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":100,"completed":150},"content":[{"type":"text","text":"Desktop 1"}]},
                        {"id":"u1","type":"user","time":{"created":50},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                ctrl1.syncSessionTurns("turn_3")
                assertEquals(listOf("a3"), ctrl1.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("a3", ctrl1.nextItemToAutoSpeak()?.messageId)

                // Case 2: Brand-new empty session
                bodyRef.set("""{"data":[],"cursor":{}}""")
                val (store2, session2, ctrl2) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                )
                ctrl2.syncSessionTurns("empty_init")
                val cursor2 = store2.getReplyCursor(session2.sessionKey)
                assertEquals(SessionTurnProjection.SESSION_START_BASELINE, cursor2?.armedBaselineMessageId)
                assertTrue(ctrl2.feedState.value.unreadQueue.isEmpty())

                // Turn 1 arrives on new session -> must enter unreadQueue and speak!
                bodyRef.set(
                    """
                    {
                      "data": [
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"First reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"Hello"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                ctrl2.syncSessionTurns("turn_1")
                assertEquals(listOf("a1"), ctrl2.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("a1", ctrl2.nextItemToAutoSpeak()?.messageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `observeEventsForActiveTurn shares armed WebSocket and selectResponse stepResponse manage focus cleanly`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val wsUpgradeCount = AtomicInteger(0)
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)
            val bodyRef = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":350},"content":[{"type":"text","text":"Step 2 reply"}]},
                    {"id":"a1","type":"assistant","finish":"tool-calls","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Step 1 reply"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"Do two steps"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(bodyRef.get())
                    "/api/global/event/ws" -> {
                        wsUpgradeCount.incrementAndGet()
                        MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    wsSocketRef.set(webSocket)
                                    webSocket.send("""{"type":"ready","scope":"global"}""")
                                    wsReadyLatch.countDown()
                                }
                            },
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                )
                controller.syncSessionTurns("init")
                controller.setArmed(true)
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))

                // Active turn observation reuses the armed WS without opening a second connection
                val readyCalled = CountDownLatch(1)
                controller.setActivePromptTurn(true)
                val collected = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") {
                        readyCalled.countDown()
                    }.take(1).toList()
                }
                assertTrue(readyCalled.await(3, TimeUnit.SECONDS))
                wsSocketRef.get()!!.send("""{"type":"event","eventId":"e9","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                assertEquals(listOf(TurnEvent.Completed), collected.await())
                assertEquals(1, wsUpgradeCount.get())
                controller.setActivePromptTurn(false)

                // Manual navigation via stepResponse / selectResponse sets userNavigatedFocus and overrides cursor focus in VoiceReplyContext
                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(lastHeardMessageId = "a2", lastHeardCreatedAtMs = 300L),
                )
                controller.syncSessionTurns("heard_both")
                assertEquals("a2", controller.feedState.value.focusedResponse?.messageId)

                controller.stepResponse(-1)
                assertEquals("a1", controller.feedState.value.focusedResponse?.messageId)
                assertEquals("Step 1 reply", controller.buildVoiceReplyContext().focusedReply)

                // ensureAutoSpeakAnchorBeforeTurn does NOT overwrite an existing armed anchor
                controller.ensureAutoSpeakAnchorBeforeTurn("a2")
                bodyRef.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":700}},
                        {"id":"a3","type":"assistant","finish":"stop","time":{"created":600,"completed":650},"content":[{"type":"text","text":"Step 3 reply"}]},
                        {"id":"u2","type":"user","time":{"created":500},"text":"Followup"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":350},"content":[{"type":"text","text":"Step 2 reply"}]},
                        {"id":"a1","type":"assistant","finish":"tool-calls","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Step 1 reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"Do two steps"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                controller.syncSessionTurns("new_turn_arrives")
                // New turn clears userNavigatedFocus and moves UI focus to new unread a3,
                // while VoiceReplyContext keeps focusedReply on last-heard a2 and exposes a3 in pendingUnreadPreviews
                assertEquals("a3", controller.feedState.value.focusedResponse?.messageId)
                assertEquals("a3", controller.nextItemToAutoSpeak()?.messageId)
                val ctxWhileUnread = controller.buildVoiceReplyContext()
                assertEquals("Step 2 reply", ctxWhileUnread.focusedReply)
                assertEquals(listOf("a3"), ctxWhileUnread.pendingUnreadPreviews.map { it.messageId })

                // Multi-step turn 2 [a3, a4]: once both are heard, selectResponse navigates within Turn 2 and back to latest
                bodyRef.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":800}},
                        {"id":"a4","type":"assistant","finish":"stop","time":{"created":700,"completed":750},"content":[{"type":"text","text":"Step 4 reply"}]},
                        {"id":"a3","type":"assistant","finish":"tool-calls","time":{"created":600,"completed":650},"content":[{"type":"text","text":"Step 3 reply"}]},
                        {"id":"u2","type":"user","time":{"created":500},"text":"Followup"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":350},"content":[{"type":"text","text":"Step 2 reply"}]},
                        {"id":"a1","type":"assistant","finish":"tool-calls","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Step 1 reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"Do two steps"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                controller.syncSessionTurns("turn2_two_steps")
                controller.markAllResponsesRead()
                val ctxAfterHeard = controller.buildVoiceReplyContext()
                assertEquals("Step 4 reply", ctxAfterHeard.focusedReply)
                assertTrue(ctxAfterHeard.pendingUnreadPreviews.isEmpty())

                controller.selectResponse("a3")
                assertEquals("Step 3 reply", controller.buildVoiceReplyContext().focusedReply)
                controller.selectResponse("a4")
                assertEquals("Step 4 reply", controller.buildVoiceReplyContext().focusedReply)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `arm then immediate RECORDING and discard or transient arm error does not auto-speak pre-arm unread desktop history`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val reqCount = AtomicInteger(0)
            val armFetchStarted = CountDownLatch(1)
            val allowArmFetchFinish = CountDownLatch(1)
            val desktopHistory = """
                {
                  "data": [
                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":400}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Old desktop unread"}]},
                    {"id":"u2","type":"user","time":{"created":300},"text":"Desktop prompt"},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Earlier heard"}]}
                  ],
                  "cursor":{}
                }
            """.trimIndent()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> {
                        val idx = reqCount.getAndIncrement()
                        if (idx == 0) {
                            armFetchStarted.countDown()
                            allowArmFetchFinish.await(3, TimeUnit.SECONDS)
                            MockResponse().setResponseCode(503).setBody("Transient outage")
                        } else {
                            MockResponse().setResponseCode(200).setBody(desktopHistory)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val currentState = AtomicReference(PttState.ARMED_IDLE)
                val autoSpeakCount = AtomicInteger(0)
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { currentState.get() },
                    onAutoSpeak = { autoSpeakCount.incrementAndGet() },
                )
                // Previous session left a1 heard while desktop added a2 offline
                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(
                        lastHeardMessageId = "a1",
                        lastHeardCreatedAtMs = 200L,
                        updatedAtMs = 200L,
                    ),
                )
                controller.setArmed(true)
                controller.requestBackgroundSync("arm", triggerAutoSpeak = false)
                assertTrue(armFetchStarted.await(3, TimeUnit.SECONDS))

                // User taps PTT immediately -> RECORDING, then discards -> ARMED_IDLE before sync finishes
                currentState.set(PttState.RECORDING)
                allowArmFetchFinish.countDown()
                // Wait for failing first sync to settle while in RECORDING
                controller.syncSessionTurns("post_fail_wait", triggerAutoSpeak = false)

                // Even if user was RECORDING on first successful sync (e.g. ON_RESUME refresh without captureAutoSpeakAnchor),
                // needsArmedAnchorCapture must still anchor a2 so returning to ARMED_IDLE never auto-speaks old desktop history!
                currentState.set(PttState.ARMED_IDLE)
                controller.maybeTriggerAutoSpeakIfIdle()
                assertEquals(0, autoSpeakCount.get())
                assertEquals(listOf("a2"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertNull(controller.nextItemToAutoSpeak())

                // Returning from refresh must also clear stale directAnswer on new arrivals
                controller.setDirectAnswer(DirectVoiceAnswer("q", "a", "a2"))
                val nextTurnBody = """
                    {
                      "data": [
                        {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":600}},
                        {"id":"a3","type":"assistant","finish":"stop","time":{"created":550,"completed":580},"content":[{"type":"text","text":"Fresh async arrival"}]},
                        {"id":"syn_3","type":"synthetic","time":{"created":500},"metadata":{"source":"openchamber-session","title":"Worker"},"text":"<openchamber-session title=\"Worker\">ok</openchamber-session>"},
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":400}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Old desktop unread"}]},
                        {"id":"u2","type":"user","time":{"created":300},"text":"Desktop prompt"},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Earlier heard"}]}
                      ],
                      "cursor":{}
                    }
                """.trimIndent()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        messagesPath -> MockResponse().setResponseCode(200).setBody(nextTurnBody)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                controller.syncSessionTurns("ws_new_3", triggerAutoSpeak = true)
                assertEquals(1, autoSpeakCount.get())
                assertEquals("a3", controller.nextItemToAutoSpeak()?.messageId)
                assertNull(controller.feedState.value.directAnswer)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `syncSessionTurns stops on Page 1 when lastHeardMessageId is present even if armedBaselineMessageId is older`() =
        runBlocking {
            val page1Path = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val page1Count = AtomicInteger(0)
            val page2Count = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    page1Path -> {
                        page1Count.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "data": [
                                {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":500}},
                                {"id":"a3","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Newest"}]},
                                {"id":"u3","type":"user","time":{"created":400},"text":"Turn 3"},
                                {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":350},"content":[{"type":"text","text":"Already heard"}]}
                              ],
                              "cursor": {"next":"page_2_cursor"}
                            }
                            """.trimIndent(),
                        )
                    }
                    else -> {
                        page2Count.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"base_0","type":"assistant","text":"Old baseline"}],"cursor":{}}""")
                    }
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                )
                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(
                        lastHeardMessageId = "a2",
                        lastHeardCreatedAtMs = 300L,
                        armedBaselineMessageId = "base_0",
                        armedBaselineCreatedAtMs = 50L,
                        updatedAtMs = 300L,
                    ),
                )
                val feed = controller.syncSessionTurns("fast_sync")
                assertEquals(1, page1Count.get())
                assertEquals(0, page2Count.get())
                assertEquals(listOf("a3"), feed.unreadQueue.map { it.messageId })
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `stepping between responses and restarting hydrates focusedSpokenSummary from TtsCache and refreshes on mode change`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val raw1 = "Detailed response one with `CodeOne.kt` and enough characters to exceed the threshold for spoken translation. ".repeat(3)
            val raw2 = "Detailed response two with `CodeTwo.kt` and enough characters to exceed the threshold for spoken translation. ".repeat(3)
            val body = """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":300,"completed":350},"content":[{"type":"text","text":"$raw2"}]},
                    {"id":"a1","type":"assistant","finish":"tool-calls","time":{"created":200,"completed":250},"content":[{"type":"text","text":"$raw1"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"Check two responses"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(body)
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val cacheRoot = tempFolder.newFolder("controller_cache")
            val cache1 = TtsCache(cacheRoot)
            cache1.putPreparedText(raw1, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, "Spoken summary one.")
            cache1.putPreparedText(raw2, TtsInputMode.SUMMARIZED, SpokenDetailLevel.BALANCED, "Spoken summary two.")
            cache1.putPreparedText(raw2, TtsInputMode.SUMMARIZED, SpokenDetailLevel.ESSENTIALS, "Essentials summary two.")
            cache1.close()

            // Brand-new TtsCache instance simulates app restart
            val cache2 = TtsCache(cacheRoot)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var currentMode = TtsInputMode.SUMMARIZED
            var currentDetail = SpokenDetailLevel.BALANCED
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val (_, _, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    lookupCachedSpokenSummary = { raw ->
                        if (currentMode == TtsInputMode.SUMMARIZED) {
                            cache2.getPreparedText(raw, currentMode, currentDetail)
                        } else {
                            null
                        }
                    },
                )

                val feed = controller.syncSessionTurns("init_hydrate")
                assertEquals("a2", feed.focusedResponse?.messageId)
                assertEquals("Spoken summary two.", feed.focusedSpokenSummary)

                controller.stepResponse(-1)
                assertEquals("a1", controller.feedState.value.focusedResponse?.messageId)
                assertEquals("Spoken summary one.", controller.feedState.value.focusedSpokenSummary)

                controller.stepResponse(+1)
                assertEquals("a2", controller.feedState.value.focusedResponse?.messageId)
                assertEquals("Spoken summary two.", controller.feedState.value.focusedSpokenSummary)

                // Detail level change refreshes focused readout from cache
                currentDetail = SpokenDetailLevel.ESSENTIALS
                controller.refreshFocusedSpokenSummaryFromCache()
                assertEquals("Essentials summary two.", controller.feedState.value.focusedSpokenSummary)

                // Switching to RAW clears cached spoken summary
                currentMode = TtsInputMode.RAW
                controller.refreshFocusedSpokenSummaryFromCache()
                assertNull(controller.feedState.value.focusedSpokenSummary)
            } finally {
                scope.cancel()
                cache2.close()
            }
        }

    @Test
    fun `20-min stale windowCache before arm and cross-session bindSession never auto-speaks pre-arm desktop history`() =
        runBlocking {
            val ses1Path = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val ses2Path = "/api/session/ses_2/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val ses1Body = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_0","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a0","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Initial reply"}]},
                    {"id":"u0","type":"user","time":{"created":100},"text":"Start"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            val ses2Body = AtomicReference(
                """
                {
                  "data": [
                    {"id":"s2_idle_0","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"s2_a0","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Session 2 old"}]},
                    {"id":"s2_u0","type":"user","time":{"created":100},"text":"S2 Start"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    ses1Path -> MockResponse().setResponseCode(200).setBody(ses1Body.get())
                    ses2Path -> MockResponse().setResponseCode(200).setBody(ses2Body.get())
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val triggerCount = AtomicInteger(0)
                val (store, session1, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = { triggerCount.incrementAndGet() },
                )

                // Populate windowCache for ses_1 and ses_2 while disarmed, with a0/s2_a0 heard
                controller.syncSessionTurns("disarmed_seed_1")
                controller.markAllResponsesRead()
                val srvId = session1.serverId
                val session2 = BoundServerSession(
                    serverId = srvId,
                    serverUrl = baseUrl,
                    pinnedSession = PinnedSession("ses_2", "/repo", "Second"),
                )
                controller.bindSession(session2, forceClear = false)
                controller.syncSessionTurns("disarmed_seed_2")
                controller.markAllResponsesRead()
                controller.bindSession(session1, forceClear = false)

                // 20 minutes pass while disarmed: desktop adds a1 and a2 on ses_1, and s2_a1 on ses_2
                ses1Body.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":600}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":550,"completed":580},"content":[{"type":"text","text":"Desktop 20m later 2"}]},
                        {"id":"u2","type":"user","time":{"created":500},"text":"Desktop 2"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Desktop 20m later 1"}]},
                        {"id":"u1","type":"user","time":{"created":300},"text":"Desktop 1"},
                        {"id":"idle_0","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a0","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Initial reply"}]},
                        {"id":"u0","type":"user","time":{"created":100},"text":"Start"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                ses2Body.set(
                    """
                    {
                      "data": [
                        {"id":"s2_idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                        {"id":"s2_a1","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Session 2 desktop history"}]},
                        {"id":"s2_u1","type":"user","time":{"created":300},"text":"S2 Desktop"},
                        {"id":"s2_idle_0","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"s2_a0","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Session 2 old"}]},
                        {"id":"s2_u0","type":"user","time":{"created":100},"text":"S2 Start"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )

                // Arm ses_1 -> must stay Uninitialized until post-arm HTTP sync (must NOT use stale windowCache)
                controller.setArmed(true)
                assertNull(controller.nextItemToAutoSpeak())
                controller.syncSessionTurns("arm", triggerAutoSpeak = true)
                assertEquals(0, triggerCount.get())
                assertNull(controller.nextItemToAutoSpeak())
                assertEquals(listOf("a1", "a2"), controller.feedState.value.unreadQueue.map { it.messageId })

                // Cross-session bindSession(ses_2, forceClear = false) while armed -> must NOT anchor from stale windowCache
                controller.bindSession(session2, forceClear = false)
                assertNull(controller.nextItemToAutoSpeak())
                controller.syncSessionTurns("session_switch_armed", triggerAutoSpeak = true)
                assertEquals(0, triggerCount.get())
                assertNull(controller.nextItemToAutoSpeak())
                assertEquals(listOf("s2_a1"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("s2_a0", store.getReplyCursor(session2.sessionKey)?.lastHeardMessageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `cursor null plus live WS Completed during arm aligns cursor armedBaseline so Reply 1 speaks`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Reply 1"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"Prompt 1"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val autoSpeakLatch = CountDownLatch(1)
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = { autoSpeakLatch.countDown() },
                )
                assertNull(store.getReplyCursor(session.sessionKey))

                controller.setArmed(true)
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))
                val readyFence = CountDownLatch(1)
                val readyJob = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { readyFence.countDown() }
                        .take(1)
                        .toList()
                }
                assertTrue(readyFence.await(3, TimeUnit.SECONDS))
                readyJob.cancel()

                // Live WS Completed arrives while watermark is Uninitialized and cursor is null
                wsSocketRef.get()!!.send(
                    """{"type":"event","eventId":"e1","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""",
                )

                assertTrue("Expected auto-speak for Reply 1", autoSpeakLatch.await(3, TimeUnit.SECONDS))
                assertEquals(
                    SessionTurnProjection.SESSION_START_BASELINE,
                    store.getReplyCursor(session.sessionKey)?.armedBaselineMessageId,
                )
                assertEquals(listOf("a1"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("a1", controller.nextItemToAutoSpeak()?.messageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `torn HTTP GET 1 returning after WS Completed waits for coalesced resync without poisoning cursor`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val reqCount = AtomicInteger(0)
            val get1Started = CountDownLatch(1)
            val allowGet1Finish = CountDownLatch(1)
            val get2Completed = CountDownLatch(1)
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            // GET #1 already sees [u1, a1, idle_1] while WS Completed races ahead of GET #1's response;
            // GET #1 must NOT poison cursor == null to idle_1 before coalesced GET #2 runs.
            val bodyWithReply1 = """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Reply 1"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"Prompt 1"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> {
                        val idx = reqCount.getAndIncrement()
                        if (idx == 0) {
                            get1Started.countDown()
                            allowGet1Finish.await(3, TimeUnit.SECONDS)
                            MockResponse().setResponseCode(200).setBody(bodyWithReply1)
                        } else {
                            get2Completed.countDown()
                            MockResponse().setResponseCode(200).setBody(bodyWithReply1)
                        }
                    }
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val triggerCount = AtomicInteger(0)
                val autoSpeakLatch = CountDownLatch(1)
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = {
                        triggerCount.incrementAndGet()
                        autoSpeakLatch.countDown()
                    },
                )

                controller.setArmed(true)
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))
                val readyFence = CountDownLatch(1)
                val readyJob = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { readyFence.countDown() }
                        .take(1)
                        .toList()
                }
                assertTrue(readyFence.await(3, TimeUnit.SECONDS))
                readyJob.cancel()

                // Start GET #1 ("arm") with fetchLiveSeq == 0
                val ready = CountDownLatch(1)
                val eventObserved = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { ready.countDown() }.first()
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS))

                val sync1 = async(Dispatchers.IO) {
                    controller.syncSessionTurns("arm", triggerAutoSpeak = false)
                }
                assertTrue(get1Started.await(3, TimeUnit.SECONDS))

                // WS Completed arrives while GET #1 is in flight -> liveCompletionSeq = 1, resyncRequested = true
                wsSocketRef.get()!!.send(
                    """{"type":"event","eventId":"e1","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""",
                )
                assertEquals(TurnEvent.Completed, eventObserved.await())

                // Release GET #1 -> must defer finalization & cursor seeding to coalesced GET #2
                allowGet1Finish.countDown()
                sync1.await()
                assertTrue(get2Completed.await(3, TimeUnit.SECONDS))
                assertTrue(autoSpeakLatch.await(3, TimeUnit.SECONDS))

                assertEquals(1, triggerCount.get())
                assertEquals(
                    SessionTurnProjection.SESSION_START_BASELINE,
                    store.getReplyCursor(session.sessionKey)?.armedBaselineMessageId,
                )
                assertEquals(listOf("a1"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("a1", controller.nextItemToAutoSpeak()?.messageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `SyntheticArrived during Uninitialized does not roll back pre-arm history`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val reqCount = AtomicInteger(0)
            val get1Started = CountDownLatch(1)
            val allowGet1Finish = CountDownLatch(1)
            val preArmOnlyBody = """
                {
                  "data": [
                    {"id":"idle_0","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a0","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Pre-arm desktop reply"}]},
                    {"id":"u0","type":"user","time":{"created":100},"text":"Pre-arm"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            val afterTurn1Body = """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Async after arm"}]},
                    {"id":"syn_1","type":"synthetic","time":{"created":300},"metadata":{"source":"openchamber-session","title":"Worker"},"text":"<openchamber-session title=\"Worker\">done</openchamber-session>"},
                    {"id":"idle_0","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a0","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Pre-arm desktop reply"}]},
                    {"id":"u0","type":"user","time":{"created":100},"text":"Pre-arm"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> {
                        val idx = reqCount.getAndIncrement()
                        if (idx == 0) {
                            get1Started.countDown()
                            allowGet1Finish.await(3, TimeUnit.SECONDS)
                            MockResponse().setResponseCode(200).setBody(preArmOnlyBody)
                        } else {
                            MockResponse().setResponseCode(200).setBody(afterTurn1Body)
                        }
                    }
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val autoSpeakLatch = CountDownLatch(1)
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = { autoSpeakLatch.countDown() },
                )

                controller.setArmed(true)
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))
                val readyFence = CountDownLatch(1)
                val twoEvents = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { readyFence.countDown() }
                        .take(2)
                        .toList()
                }
                assertTrue(readyFence.await(3, TimeUnit.SECONDS))

                // Hold initial arm GET #1 while BOTH SyntheticArrived and Completed arrive in Uninitialized state.
                val armSync = async(Dispatchers.IO) {
                    controller.syncSessionTurns("arm", triggerAutoSpeak = false)
                }
                assertTrue(get1Started.await(3, TimeUnit.SECONDS))

                val ws = wsSocketRef.get()!!
                ws.send("""{"type":"event","eventId":"e1","payload":{"type":"session.synthetic","data":{"sessionID":"ses_1","metadata":{"source":"openchamber-session"}}}}""")
                ws.send("""{"type":"event","eventId":"e2","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                assertEquals(2, twoEvents.await().size)

                // Release GET #1; SyntheticArrived must NOT increment liveCompletionCount,
                // so rollback is 1 turn (idle_0), NOT 2 (SESSION_START_BASELINE), keeping pre-arm a0 silent.
                allowGet1Finish.countDown()
                armSync.await()

                assertTrue(autoSpeakLatch.await(3, TimeUnit.SECONDS))
                assertEquals("idle_0", store.getReplyCursor(session.sessionKey)?.armedBaselineMessageId)
                assertEquals(listOf("a1"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("a1", controller.nextItemToAutoSpeak()?.messageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `short-clip half-duration barge-in and onSpeechInterruptedByTransport preserve unread on interruption`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val body = """
                {
                  "data": [
                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Second item"}]},
                    {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Short clip"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(body)
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val triggerCount = AtomicInteger(0)
                var nowElapsedMs = 1_000L
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = { triggerCount.incrementAndGet() },
                    clockElapsedMs = { nowElapsedMs },
                )

                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE, updatedAtMs = 1L),
                )
                controller.setArmed(true)
                controller.ensureAutoSpeakAnchorBeforeTurn(SessionTurnProjection.SESSION_START_BASELINE)
                controller.syncSessionTurns("initial")
                assertEquals(listOf("a1", "a2"), controller.feedState.value.unreadQueue.map { it.messageId })

                // Preparing barge-in (before notifySpeechPlaybackStarted) does NOT mark a1 heard
                controller.setSpeakingMessage("a1")
                controller.onUserBargeIn(nowElapsedMs + 5_000L)
                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertEquals(listOf("a1", "a2"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertTrue(controller.feedState.value.autoSpeakPaused)

                // Transport interruption even after 2.5s audible NEVER calls markItemHeardLocked
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 5_000L)
                nowElapsedMs += 2_500L
                controller.onSpeechInterruptedByTransport()
                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertEquals(listOf("a1", "a2"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertTrue(controller.feedState.value.autoSpeakPaused)
                assertNull(controller.feedState.value.speakingMessageId)
                assertEquals("a1", controller.feedState.value.focusedMessageId)
                controller.maybeTriggerAutoSpeakIfIdle()
                assertEquals(0, triggerCount.get())

                // Zero / negative estimatedDurationMs is sanitized to null (requires full 1.5s, not 0ms/2)
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 0L)
                nowElapsedMs += 600L
                controller.onUserBargeIn()
                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)

                // Short clip (< 3_000ms) < half duration stays unread; >= half duration commits heard
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 1_200L)
                nowElapsedMs += 599L
                controller.onUserBargeIn()
                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)

                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 1_200L)
                nowElapsedMs += 600L
                controller.onUserBargeIn()
                assertEquals("a1", store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertEquals(listOf("a2"), controller.feedState.value.unreadQueue.map { it.messageId })
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `syncForPromptTurnCompletion distinguishes TurnStillRunning FlushLagRetryNeeded ReadyToSpeak and SettledNoSpeech`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.WAITING_AGENT },
                )
                controller.syncSessionTurns("baseline")
                controller.ensureAutoSpeakAnchorBeforeTurn("idle_1", 300L)
                controller.ensureCursorSeededBeforeTurn("idle_1", 300L)

                // 1) sentUserMessageId not yet in window and preSendLatestRecordId matches tail -> TurnStillRunning
                assertEquals(
                    PromptTurnSettlement.TurnStillRunning,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 2) User step just opened with 0 assistant items (!lastTurn.isIdle) -> TurnStillRunning
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.TurnStillRunning,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 3) Single-step incomplete assistant (flush lag) -> FlushLagRetryNeeded
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"a2","type":"assistant","time":{"created":450},"content":[{"type":"text","text":"Partial..."}]},
                        {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.FlushLagRetryNeeded,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 4) Multi-step with completed step 1 + incomplete blank step 2 -> still FlushLagRetryNeeded
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"a2_2","type":"assistant","time":{"created":470}},
                        {"id":"a2_1","type":"assistant","finish":"tool-calls","time":{"created":450,"completed":460},"content":[{"type":"text","text":"Step 1"}]},
                        {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.FlushLagRetryNeeded,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 5) Completed reply -> ReadyToSpeak
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a2_2","type":"assistant","finish":"stop","time":{"created":470,"completed":490},"content":[{"type":"text","text":"Step 2"}]},
                        {"id":"a2_1","type":"assistant","finish":"tool-calls","time":{"created":450,"completed":460},"content":[{"type":"text","text":"Step 1"}]},
                        {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.ReadyToSpeak,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 6) Settled tool-only turn with no speakable text -> SettledNoSpeech
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_3","type":"idle","outcome":"succeeded","time":{"created":650}},
                        {"id":"u3","type":"user","time":{"created":600},"text":"Silent tool"},
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a2_2","type":"assistant","finish":"stop","time":{"created":470,"completed":490},"content":[{"type":"text","text":"Step 2"}]},
                        {"id":"a2_1","type":"assistant","finish":"tool-calls","time":{"created":450,"completed":460},"content":[{"type":"text","text":"Step 1"}]},
                        {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                controller.markAllResponsesRead()
                assertEquals(
                    PromptTurnSettlement.SettledNoSpeech,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u3", preSendLatestRecordId = "idle_2"),
                )
                assertEquals("a2_2", store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `armed WS SyntheticArrived syncs feed during activePromptTurn while gating auto-speak until idle`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference("""{"data":[],"cursor":{}}""")
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val triggerCount = AtomicInteger(0)
                val currentState = AtomicReference(PttState.ARMED_IDLE)
                val (_, _, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { currentState.get() },
                    onAutoSpeak = { triggerCount.incrementAndGet() },
                )

                controller.setArmed(true)
                controller.syncSessionTurns("arm")
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))
                val readyFence = CountDownLatch(1)
                val readyJob = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { readyFence.countDown() }
                        .take(1)
                        .toList()
                }
                assertTrue(readyFence.await(3, TimeUnit.SECONDS))
                readyJob.cancel()

                // Even while activePromptTurn == true and state == ARMED_IDLE, WS SyntheticArrived still syncs feed
                // without triggering auto-speak until activePromptTurn resets to false.
                controller.setActivePromptTurn(true)
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Async while active"}]},
                        {"id":"syn_1","type":"synthetic","time":{"created":100},"metadata":{"source":"openchamber-session","title":"Worker"},"text":"<openchamber-session title=\"Worker\">done</openchamber-session>"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                wsSocketRef.get()!!.send(
                    """{"type":"event","eventId":"e1","payload":{"type":"session.synthetic","data":{"sessionID":"ses_1","metadata":{"source":"openchamber-session"}}}}""",
                )
                withTimeout(3_000L) {
                    controller.feedState.first { it.unreadQueue.map(AssistantResponseItem::messageId) == listOf("a1") }
                }
                assertEquals(0, triggerCount.get())

                controller.setActivePromptTurn(false)
                controller.maybeTriggerAutoSpeakIfIdle()
                assertEquals(1, triggerCount.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `back-to-back TurnEvent Completed frames during Uninitialized roll back only 1 turn`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val reqCount = AtomicInteger(0)
            val get1Started = CountDownLatch(1)
            val allowGet1Finish = CountDownLatch(1)
            val preArmThreeTurns = """
                {
                  "data": [
                    {"id":"idle_old3","type":"idle","outcome":"succeeded","time":{"created":600}},
                    {"id":"a_old3","type":"assistant","finish":"stop","time":{"created":550,"completed":580},"content":[{"type":"text","text":"Old 3"}]},
                    {"id":"u_old3","type":"user","time":{"created":500},"text":"U3"},
                    {"id":"idle_old2","type":"idle","outcome":"succeeded","time":{"created":400}},
                    {"id":"a_old2","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Old 2"}]},
                    {"id":"u_old2","type":"user","time":{"created":300},"text":"U2"},
                    {"id":"idle_old1","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a_old1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Old 1"}]},
                    {"id":"u_old1","type":"user","time":{"created":100},"text":"U1"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            val afterTurn4Body = """
                {
                  "data": [
                    {"id":"idle_4","type":"idle","outcome":"succeeded","time":{"created":800}},
                    {"id":"a4","type":"assistant","finish":"stop","time":{"created":750,"completed":780},"content":[{"type":"text","text":"Turn 4 live reply"}]},
                    {"id":"syn_4","type":"synthetic","time":{"created":700},"metadata":{"source":"openchamber-session","title":"Worker"},"text":"<openchamber-session title=\"Worker\">done</openchamber-session>"},
                    {"id":"idle_old3","type":"idle","outcome":"succeeded","time":{"created":600}},
                    {"id":"a_old3","type":"assistant","finish":"stop","time":{"created":550,"completed":580},"content":[{"type":"text","text":"Old 3"}]},
                    {"id":"u_old3","type":"user","time":{"created":500},"text":"U3"},
                    {"id":"idle_old2","type":"idle","outcome":"succeeded","time":{"created":400}},
                    {"id":"a_old2","type":"assistant","finish":"stop","time":{"created":350,"completed":380},"content":[{"type":"text","text":"Old 2"}]},
                    {"id":"u_old2","type":"user","time":{"created":300},"text":"U2"},
                    {"id":"idle_old1","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a_old1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Old 1"}]},
                    {"id":"u_old1","type":"user","time":{"created":100},"text":"U1"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            val wsSocketRef = AtomicReference<WebSocket?>(null)
            val wsReadyLatch = CountDownLatch(1)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> {
                        val idx = reqCount.getAndIncrement()
                        if (idx == 0) {
                            get1Started.countDown()
                            allowGet1Finish.await(3, TimeUnit.SECONDS)
                            MockResponse().setResponseCode(200).setBody(preArmThreeTurns)
                        } else {
                            MockResponse().setResponseCode(200).setBody(afterTurn4Body)
                        }
                    }
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                wsSocketRef.set(webSocket)
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                wsReadyLatch.countDown()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val autoSpeakLatch = CountDownLatch(1)
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    onAutoSpeak = { autoSpeakLatch.countDown() },
                )

                controller.setArmed(true)
                assertTrue(wsReadyLatch.await(3, TimeUnit.SECONDS))
                val readyFence = CountDownLatch(1)
                val threeCompleted = async(Dispatchers.IO) {
                    controller.observeEventsForActiveTurn(baseUrl, "ses_1") { readyFence.countDown() }
                        .take(3)
                        .toList()
                }
                assertTrue(readyFence.await(3, TimeUnit.SECONDS))

                val armSync = async(Dispatchers.IO) {
                    controller.syncSessionTurns("arm", triggerAutoSpeak = false)
                }
                assertTrue(get1Started.await(3, TimeUnit.SECONDS))

                val ws = wsSocketRef.get()!!
                ws.send("""{"type":"event","eventId":"e1","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                ws.send("""{"type":"event","eventId":"e2","payload":{"type":"openchamber:session-status","properties":{"sessionID":"ses_1","status":"idle"}}}""")
                ws.send("""{"type":"event","eventId":"e3","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                assertEquals(listOf(TurnEvent.Completed, TurnEvent.Completed, TurnEvent.Completed), threeCompleted.await())

                allowGet1Finish.countDown()
                armSync.await()

                assertTrue(autoSpeakLatch.await(3, TimeUnit.SECONDS))
                assertEquals("idle_old3", store.getReplyCursor(session.sessionKey)?.armedBaselineMessageId)
                assertEquals(listOf("a4"), controller.feedState.value.unreadQueue.map { it.messageId })
                assertEquals("a4", controller.nextItemToAutoSpeak()?.messageId)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `syncForPromptTurnCompletion returns TurnStillRunning when earlier subagent reply is unread while sentUserMessageId is absent or tool-calls`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Baseline reply"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val (_, _, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.WAITING_AGENT },
                )
                controller.syncSessionTurns("baseline")
                controller.ensureAutoSpeakAnchorBeforeTurn("idle_1", 300L)
                controller.ensureCursorSeededBeforeTurn("idle_1", 300L)

                // 1) Earlier subagent unread reply exists while u2 is still absent from window -> TurnStillRunning
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_sub","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a_sub","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Subagent finished."}]},
                        {"id":"syn_sub","type":"synthetic","time":{"created":400},"metadata":{"source":"subagent","agent":"explore"},"text":"<subagent agent=\"explore\">done</subagent>"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Baseline reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.TurnStillRunning,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 2) u2 opened with 0 assistant records while a_sub is unread -> TurnStillRunning
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"u2","type":"user","time":{"created":600},"text":"Two"},
                        {"id":"idle_sub","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a_sub","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Subagent finished."}]},
                        {"id":"syn_sub","type":"synthetic","time":{"created":400},"metadata":{"source":"subagent","agent":"explore"},"text":"<subagent agent=\"explore\">done</subagent>"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Baseline reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.TurnStillRunning,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 3) u2 finished only a tool-calls step (!lastTurn.isIdle) -> TurnStillRunning
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"a2_1","type":"assistant","finish":"tool-calls","time":{"created":650,"completed":680},"content":[{"type":"text","text":"Let me check..."}]},
                        {"id":"u2","type":"user","time":{"created":600},"text":"Two"},
                        {"id":"idle_sub","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a_sub","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Subagent finished."}]},
                        {"id":"syn_sub","type":"synthetic","time":{"created":400},"metadata":{"source":"subagent","agent":"explore"},"text":"<subagent agent=\"explore\">done</subagent>"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Baseline reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.TurnStillRunning,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )

                // 4) u2 produces final reply -> ReadyToSpeak
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":750}},
                        {"id":"a2_2","type":"assistant","finish":"stop","time":{"created":700,"completed":730},"content":[{"type":"text","text":"Final answer."}]},
                        {"id":"a2_1","type":"assistant","finish":"tool-calls","time":{"created":650,"completed":680},"content":[{"type":"text","text":"Let me check..."}]},
                        {"id":"u2","type":"user","time":{"created":600},"text":"Two"},
                        {"id":"idle_sub","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a_sub","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Subagent finished."}]},
                        {"id":"syn_sub","type":"synthetic","time":{"created":400},"metadata":{"source":"subagent","agent":"explore"},"text":"<subagent agent=\"explore\">done</subagent>"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Baseline reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                assertEquals(
                    PromptTurnSettlement.ReadyToSpeak,
                    controller.syncForPromptTurnCompletion(sentUserMessageId = "u2", preSendLatestRecordId = "idle_1"),
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `resetSpeechProgressToPreparing and notifySpeechPlaybackStarted update progress across fallbacks`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val body = """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Fallback item"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                  ],
                  "cursor":{}
                }
            """.trimIndent()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(body)
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                var nowElapsedMs = 1_000L
                val (store, session, controller) = createController(
                    scope = scope,
                    baseUrl = baseUrl,
                    stateProvider = { PttState.ARMED_IDLE },
                    clockElapsedMs = { nowElapsedMs },
                )
                store.saveReplyCursor(
                    session.sessionKey,
                    SessionReplyCursor(armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE, updatedAtMs = 1L),
                )
                controller.setArmed(true)
                controller.ensureAutoSpeakAnchorBeforeTurn(SessionTurnProjection.SESSION_START_BASELINE)
                controller.syncSessionTurns("init")

                // Resetting after an aborted engine prevents synthesis wait from counting as audible playback
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 5_000L)
                controller.resetSpeechProgressToPreparing("a1")
                nowElapsedMs += 2_000L
                controller.onUserBargeIn()
                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertEquals(listOf("a1"), controller.feedState.value.unreadQueue.map { it.messageId })

                // Re-notifying updates start timestamp & estimated duration on the succeeding engine
                nowElapsedMs = 5_000L
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 5_000L)
                nowElapsedMs = 10_000L
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 1_000L)
                nowElapsedMs = 10_400L // 400ms < 500ms half-duration
                controller.onUserBargeIn()
                assertNull(store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)

                nowElapsedMs = 20_000L
                controller.setSpeakingMessage("a1")
                controller.notifySpeechPlaybackStarted("a1", estimatedDurationMs = 1_000L)
                nowElapsedMs = 20_500L // 500ms >= 500ms half-duration
                controller.onUserBargeIn()
                assertEquals("a1", store.getReplyCursor(session.sessionKey)?.lastHeardMessageId)
                assertTrue(controller.feedState.value.unreadQueue.isEmpty())
            } finally {
                scope.cancel()
            }
        }
}
