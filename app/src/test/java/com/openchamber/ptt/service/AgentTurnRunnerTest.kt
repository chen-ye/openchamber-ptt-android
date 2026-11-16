package com.openchamber.ptt.service

import android.content.SharedPreferences
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.PromptOrganizer
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.VoiceControlPlane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AgentTurnRunnerTest {
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

    private data class Harness(
        val store: TokenStore,
        val serverId: String,
        val sessionKey: String,
        val stateMachine: PttStateMachine,
        val runner: AgentTurnRunner,
        val speakRequests: CopyOnWriteArrayList<TtsSpeakRequest>,
        val postedAlerts: CopyOnWriteArrayList<String>,
        val cancelAlertCount: AtomicInteger,
        val organizingChanges: CopyOnWriteArrayList<Boolean>,
        var lastReply: String?,
        var lastTranscript: String?,
        var lastSpokenSummary: String?,
        var statusText: String,
        var stagedDraft: StagedDraft?,
        var pendingQuickTranscript: String?,
        var activeKeyOverride: String? = null,
    )

    private fun createHarness(
        scope: CoroutineScope,
        baseUrl: String,
        initialState: PttState = PttState.WAITING_AGENT,
        permissionAlertDelayMs: Long = 30L,
        onFinishedLatch: CountDownLatch? = null,
    ): Harness {
        val store = TokenStore(InMemoryPrefs())
        val srv = store.upsertServer(baseUrl, label = "Local", token = "tok_primary", makeActive = true)
        val pinned = PinnedSession("ses_1", "/proj", "Payments")
        store.updatePinnedSession(srv.id, pinned)
        val key = TokenStore.sessionKeyFor(srv.id, pinned)
        val other = store.upsertServer("http://10.0.0.9:4096", label = "Other", token = "tok_other", makeActive = false)
        assertNotNull(other)

        val http = OpenChamberHttp.createClient(
            tokenForUrlProvider = { url -> store.getTokenForUrl(url) },
            directoryForUrlProvider = { url -> store.getDirectoryForUrl(url) },
        )
        val sm = PttStateMachine(initialState = initialState)
        val speakRequests = CopyOnWriteArrayList<TtsSpeakRequest>()
        val postedAlerts = CopyOnWriteArrayList<String>()
        val cancelAlertCount = AtomicInteger(0)
        val organizingChanges = CopyOnWriteArrayList<Boolean>()

        lateinit var harness: Harness
        val runner = AgentTurnRunner(
            scope = scope,
            tokenStore = store,
            sessionClient = SessionClient(http),
            eventStreamClient = EventStreamClient(http, initialReconnectDelayMs = 10L, maxReconnectDelayMs = 50L),
            promptOrganizer = PromptOrganizer(http),
            voiceControlPlane = VoiceControlPlane(http),
            voiceUxLogger = VoiceUxLogger(logFile = null),
            speakTts = { req -> speakRequests += req },
            currentState = { sm.currentState },
            activeSessionKey = { harness.activeKeyOverride ?: harness.sessionKey },
            currentStagedDraft = { harness.stagedDraft },
            updateStagedDraft = { harness.stagedDraft = it },
            currentPendingQuickTranscript = { harness.pendingQuickTranscript },
            clearPendingQuickTranscript = { harness.pendingQuickTranscript = null },
            setPendingQuickTranscript = { harness.pendingQuickTranscript = it },
            currentLastReply = { harness.lastReply },
            updateLastReply = { harness.lastReply = it },
            updateLastTranscript = { harness.lastTranscript = it },
            updateLastSpokenSummary = { harness.lastSpokenSummary = it },
            updateStatusText = { harness.statusText = it },
            setOrganizingDraft = { organizingChanges += it },
            syncActiveBubble = {},
            onTranscriptReady = { text -> sm.dispatch(PttEvent.TranscriptReady(text)) },
            onDraftPreviewReady = { sm.dispatch(PttEvent.DraftPreviewReady) },
            onAgentCompleted = { hasTts ->
                sm.dispatch(PttEvent.AgentCompleted(hasTts))
                if (!hasTts) onFinishedLatch?.countDown()
            },
            onAgentInterrupted = {
                sm.dispatch(PttEvent.AgentInterrupted)
                onFinishedLatch?.countDown()
            },
            onPermissionAsked = { reason -> sm.dispatch(PttEvent.PermissionAsked(reason)) },
            onPermissionResolved = { sm.dispatch(PttEvent.PermissionResolved) },
            onTtsFinished = {
                sm.dispatch(PttEvent.TtsFinished)
                onFinishedLatch?.countDown()
            },
            onError = { msg, authExpired ->
                sm.dispatch(PttEvent.Error(msg, isAuthExpired = authExpired))
                onFinishedLatch?.countDown()
            },
            onPostPermissionAlertNotification = { tool -> postedAlerts += tool },
            onCancelPermissionAlertNotification = { cancelAlertCount.incrementAndGet() },
            clockElapsedMs = { System.currentTimeMillis() },
            permissionAlertDelayMs = permissionAlertDelayMs,
            eventStreamReadyTimeoutMs = 2_000L,
        )

        harness = Harness(
            store = store,
            serverId = srv.id,
            sessionKey = key,
            stateMachine = sm,
            runner = runner,
            speakRequests = speakRequests,
            postedAlerts = postedAlerts,
            cancelAlertCount = cancelAlertCount,
            organizingChanges = organizingChanges,
            lastReply = null,
            lastTranscript = null,
            lastSpokenSummary = null,
            statusText = "",
            stagedDraft = null,
            pendingQuickTranscript = null,
        )
        return harness
    }

    @Test
    fun `VoiceControlPlane LiveTuning and AnswerDirectly preserve lastReply and speak with RAW control kind`() =
        runBlocking {
            val liveInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"detailed"},"tuning_mode":{"choice":"raw"}}}"""
            val answerInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"}}}"""
            val decisionQueue = ArrayDeque(
                listOf(
                    """{"status":200,"body":"${liveInner.replace("\"", "\\\"")}"}""",
                    """{"status":200,"body":"${answerInner.replace("\"", "\\\"")}"}""",
                ),
            )

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/guests/gemini-decisions-bridge/service/request" ->
                        MockResponse().setResponseCode(200).setBody(decisionQueue.removeFirst())
                    "/api/small-model/generate" ->
                        MockResponse().setResponseCode(200).setBody("""{"text":"Option two uses Kokoro locally."}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val done1 = CountDownLatch(1)
                val h1 = createHarness(scope, baseUrl, initialState = PttState.WAITING_AGENT, onFinishedLatch = done1)
                h1.lastReply = "Original coding reply stays intact."
                h1.pendingQuickTranscript = "Switch to detailed raw mode"

                h1.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue(done1.await(3, TimeUnit.SECONDS))

                assertEquals("Original coding reply stays intact.", h1.lastReply)
                assertEquals(SpokenDetailLevel.DETAILED, h1.store.getEffectiveSpokenDetailLevel(h1.serverId))
                assertEquals(TtsInputMode.RAW, h1.store.getEffectiveTtsInputMode(h1.serverId))
                assertEquals(1, h1.speakRequests.size)
                assertEquals(TtsInputMode.RAW, h1.speakRequests[0].inputModeOverride)
                assertEquals(AgentTurnRunner.TTS_KIND_CONTROL, h1.speakRequests[0].kind)
                assertFalse(h1.speakRequests[0].notifySpokenSummary)

                // Second call: AnswerDirectly preserves lastReply and updates lastSpokenSummary
                val done2 = CountDownLatch(1)
                val h2 = createHarness(scope, baseUrl, initialState = PttState.WAITING_AGENT, onFinishedLatch = done2)
                h2.lastReply = "Option 1: Gemini. Option 2: Local Kokoro."
                h2.pendingQuickTranscript = "What was option 2?"

                h2.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue(done2.await(3, TimeUnit.SECONDS))

                assertEquals("Option 1: Gemini. Option 2: Local Kokoro.", h2.lastReply)
                assertEquals("Option two uses Kokoro locally.", h2.lastSpokenSummary)
                assertEquals(AgentTurnRunner.TTS_KIND_CONTROL, h2.speakRequests[0].kind)
                assertEquals("Option two uses Kokoro locally.", h2.speakRequests[0].text)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `PassThrough turn sends voice-prefixed prompt handles delayed permission alert then resolves and speaks reply`() =
        runBlocking {
            val passInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"pass_through"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"}}}"""
            val promptSentLatch = CountDownLatch(1)
            val replyReadCount = AtomicInteger(0)
            var sentPromptBody = ""

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/guests/gemini-decisions-bridge/service/request" ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"status":200,"body":"${passInner.replace("\"", "\\\"")}"}""")
                    "/api/session/ses_1/message?limit=5" -> {
                        if (replyReadCount.getAndIncrement() == 0) {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":"msg_old","type":"assistant","content":[{"type":"text","text":"old reply"}]}]}""")
                        } else {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":"msg_new","type":"assistant","content":[{"type":"text","text":"All tests passed."}]},{"id":"msg_old","type":"assistant","content":[{"type":"text","text":"old reply"}]}]}""")
                        }
                    }
                    "/api/session/ses_1/prompt" -> {
                        sentPromptBody = request.body.readUtf8()
                        promptSentLatch.countDown()
                        MockResponse().setResponseCode(200).setBody("""{"id":"msg_user"}""")
                    }
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                Thread {
                                    promptSentLatch.await(2, TimeUnit.SECONDS)
                                    webSocket.send("""{"type":"event","eventId":"e1","payload":{"type":"permission.asked","data":{"sessionID":"ses_1","action":"bash"}}}""")
                                    Thread.sleep(100L)
                                    webSocket.send("""{"type":"event","eventId":"e2","payload":{"type":"permission.resolved","data":{"sessionID":"ses_1"}}}""")
                                    Thread.sleep(30L)
                                    webSocket.send("""{"type":"event","eventId":"e3","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                                }.start()
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val doneLatch = CountDownLatch(1)
                val baseUrl = server.url("/").toString().trimEnd('/')
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.WAITING_AGENT,
                    permissionAlertDelayMs = 25L,
                    onFinishedLatch = doneLatch,
                )
                harness.pendingQuickTranscript = "run gradle tests"

                harness.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue("Expected turn completion", doneLatch.await(5, TimeUnit.SECONDS))

                assertTrue(sentPromptBody.contains("[voice]\\nrun gradle tests"))
                assertEquals(listOf("bash"), harness.postedAlerts)
                assertTrue(harness.cancelAlertCount.get() >= 2)
                assertEquals("All tests passed.", harness.lastReply)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)

                val permissionSpeak = harness.speakRequests.firstOrNull { it.kind == AgentTurnRunner.TTS_KIND_PERMISSION }
                assertNotNull(permissionSpeak)
                assertFalse(permissionSpeak?.notifySpokenSummary ?: true)

                val replySpeak = harness.speakRequests.firstOrNull { it.kind == AgentTurnRunner.TTS_KIND_REPLY }
                assertNotNull(replySpeak)
                assertEquals("All tests passed.", replySpeak?.text)
                assertEquals("Payments", replySpeak?.sessionLabel)
                assertTrue(replySpeak?.notifySpokenSummary ?: false)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `commit staged draft clears draft only after sendPrompt and 401 clears only failing server token`() =
        runBlocking {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/session/ses_1/message?limit=5" ->
                        MockResponse().setResponseCode(401).setBody("Unauthorized")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val doneLatch = CountDownLatch(1)
                val baseUrl = server.url("/").toString().trimEnd('/')
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.WAITING_AGENT,
                    onFinishedLatch = doneLatch,
                )
                val draft = StagedDraft(
                    sessionKey = harness.sessionKey,
                    rawBursts = listOf("fix bug"),
                    organizedText = "Fix bug in service",
                    updatedAtMs = 1_000L,
                )
                harness.store.saveStagedDraft(draft)
                harness.stagedDraft = draft

                harness.runner.dispatchPrompt(fromStagedDraft = true)
                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))

                assertEquals(PttState.ERROR, harness.stateMachine.currentState)
                assertNull(harness.store.getServer(harness.serverId)?.token)
                val other = harness.store.getServers().first { it.id != harness.serverId }
                assertEquals("tok_other", other.token)
                // Draft must NOT be cleared if sendPrompt never succeeded
                assertNotNull(harness.store.getStagedDraft(harness.sessionKey))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `organizeAndSpeakDraft persists draft speaks RAW preview and activeSessionKey guard prevents stale UI mutation`() =
        runBlocking {
            val organizerStarted = CountDownLatch(1)
            val allowOrganizerFinish = CountDownLatch(1)
            val modeSwitch = AtomicBoolean(false)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/small-model/generate" -> {
                        if (modeSwitch.get()) {
                            organizerStarted.countDown()
                            allowOrganizerFinish.await(3, TimeUnit.SECONDS)
                        }
                        MockResponse().setResponseCode(200)
                            .setBody("""{"text":"Organized prompt text."}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val doneLatch = CountDownLatch(1)
                val h1 = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.TRANSCRIBING_STAGE,
                    onFinishedLatch = doneLatch,
                )
                h1.runner.organizeAndSpeakDraft("uh make sure we test this")
                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))

                val saved = h1.store.getStagedDraft(h1.sessionKey)
                assertNotNull(saved)
                assertEquals("Organized prompt text.", saved?.organizedText)
                assertEquals(1, h1.speakRequests.size)
                assertEquals(TtsInputMode.RAW, h1.speakRequests[0].inputModeOverride)
                assertEquals(AgentTurnRunner.TTS_KIND_DRAFT_PREVIEW, h1.speakRequests[0].kind)
                assertFalse(h1.speakRequests[0].notifySpokenSummary)
                assertEquals(PttState.STAGED_DRAFT, h1.stateMachine.currentState)

                // Now test reorganizeCurrentDraft while active session switches mid-flight
                modeSwitch.set(true)
                val h2 = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.STAGED_DRAFT,
                )
                val existingDraft = StagedDraft(
                    sessionKey = h2.sessionKey,
                    rawBursts = listOf("first"),
                    organizedText = "Original organized draft",
                    updatedAtMs = 1_000L,
                )
                h2.store.saveStagedDraft(existingDraft)
                h2.stagedDraft = existingDraft
                h2.lastTranscript = "Other session transcript"

                h2.runner.reorganizeCurrentDraft()
                assertTrue(organizerStarted.await(3, TimeUnit.SECONDS))
                h2.activeKeyOverride = "other_srv|other_ses|/other"
                allowOrganizerFinish.countDown()
                Thread.sleep(100L)

                // Saved in TokenStore under original sessionKey, but current session's UI state wasn't overwritten
                assertEquals("Organized prompt text.", h2.store.getStagedDraft(h2.sessionKey)?.organizedText)
                assertEquals("Other session transcript", h2.lastTranscript)
                assertEquals("Original organized draft", h2.stagedDraft?.organizedText)
                assertFalse(h2.organizingChanges.last())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `readLastReply uses cached reply or fetches latest turn and speaks with reply kind`() =
        runBlocking {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/session/ses_1/message?limit=5" -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":[{"id":"m1","type":"assistant","content":[{"type":"text","text":"Fetched latest reply."}]}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val doneLatch = CountDownLatch(1)
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    onFinishedLatch = doneLatch,
                )
                harness.runner.readLastReply()
                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))

                assertEquals("Fetched latest reply.", harness.lastReply)
                assertEquals(1, harness.speakRequests.size)
                assertEquals(AgentTurnRunner.TTS_KIND_REPLY, harness.speakRequests[0].kind)
                assertTrue(harness.speakRequests[0].notifySpokenSummary)
                assertEquals("Payments", harness.speakRequests[0].sessionLabel)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }
}
