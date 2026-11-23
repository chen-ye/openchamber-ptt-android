package com.openchamber.ptt.service

import android.content.SharedPreferences
import com.openchamber.ptt.audio.CueEvent
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.SessionReplyCursor
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.AudioCueMode
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.TtsPlaybackResult
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.voice.DirectVoiceAnswer
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.PromptOrganizer
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.SessionTurnProjection
import com.openchamber.ptt.voice.TurnEvent
import com.openchamber.ptt.voice.UnreadReplyPreview
import com.openchamber.ptt.voice.VoiceControlPlane
import com.openchamber.ptt.voice.VoiceReplyContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AgentTurnRunnerTest {
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(20)

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
        val controller: SessionResponseController?,
        val speakRequests: CopyOnWriteArrayList<TtsSpeakRequest>,
        val directAnswers: CopyOnWriteArrayList<DirectVoiceAnswer?>,
        val postedAlerts: CopyOnWriteArrayList<String>,
        val cancelAlertCount: AtomicInteger,
        val organizingChanges: CopyOnWriteArrayList<Boolean>,
        val organizingStoppedLatch: CountDownLatch,
        var lastReply: String?,
        var lastTranscript: String?,
        var lastSpokenSummary: String?,
        var statusText: String,
        var stagedDraft: StagedDraft?,
        var pendingQuickTranscript: String?,
        var activeKeyOverride: String? = null,
        var voiceReplyContextOverride: VoiceReplyContext? = null,
        var targetMessageIdOverride: String? = null,
    )

    private fun createHarness(
        scope: CoroutineScope,
        baseUrl: String,
        initialState: PttState = PttState.WAITING_AGENT,
        permissionAlertDelayMs: Long = 30L,
        wireController: Boolean = false,
        onFinishedLatch: CountDownLatch? = null,
        onTtsFinishedCallback: () -> Unit = {},
        speakHook: suspend (TtsSpeakRequest) -> TtsPlaybackResult = { TtsPlaybackResult.COMPLETED },
        stopTts: () -> Unit = {},
        withStandalonePlaybackFocus: suspend (suspend () -> Unit) -> Unit = { it() },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onPlayCue: (CueEvent) -> Unit = {},
        stillWorkingCueDelayMs: Long = AgentTurnRunner.DEFAULT_STILL_WORKING_DELAY_MS,
        observeTurnEventsOverride: ((String, String, () -> Unit) -> kotlinx.coroutines.flow.Flow<TurnEvent>)? = null,
        onSyncForManualReadAloudOverride: (suspend () -> Unit)? = null,
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
        val sessionClient = SessionClient(http)
        val eventStreamClient = EventStreamClient(http, initialReconnectDelayMs = 10L, maxReconnectDelayMs = 50L)
        val sm = PttStateMachine(initialState = initialState)
        val speakRequests = CopyOnWriteArrayList<TtsSpeakRequest>()
        val directAnswers = CopyOnWriteArrayList<DirectVoiceAnswer?>()
        val postedAlerts = CopyOnWriteArrayList<String>()
        val cancelAlertCount = AtomicInteger(0)
        val organizingChanges = CopyOnWriteArrayList<Boolean>()
        val organizingStoppedLatch = CountDownLatch(1)

        lateinit var harness: Harness
        val controller = if (wireController) {
            SessionResponseController(
                scope = scope,
                tokenStore = store,
                sessionClient = sessionClient,
                eventStreamClient = eventStreamClient,
                currentState = { sm.currentState },
                isPlaybackActive = { harness.runner.isPlaybackActive || sm.currentState == PttState.SPEAKING_TTS },
                onTriggerAutoSpeak = { sm.dispatch(PttEvent.PlayQueuedReply) },
                onFeedUpdated = { feed ->
                    harness.lastReply = feed.focusedResponse?.assistantText
                },
            )
        } else {
            null
        }

        val runner = AgentTurnRunner(
            scope = scope,
            tokenStore = store,
            sessionClient = sessionClient,
            eventStreamClient = eventStreamClient,
            promptOrganizer = PromptOrganizer(http),
            voiceControlPlane = VoiceControlPlane(http),
            voiceUxLogger = VoiceUxLogger(logFile = null),
            speakTts = { req ->
                speakRequests += req
                speakHook(req)
            },
            stopTts = stopTts,
            withStandalonePlaybackFocus = withStandalonePlaybackFocus,
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
            setOrganizingDraft = { active ->
                organizingChanges += active
                if (!active) organizingStoppedLatch.countDown()
            },
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
                onTtsFinishedCallback()
                sm.dispatch(PttEvent.TtsFinished)
                onFinishedLatch?.countDown()
            },
            onError = { msg, authExpired ->
                sm.dispatch(PttEvent.Error(msg, isAuthExpired = authExpired))
                onFinishedLatch?.countDown()
            },
            onPostPermissionAlertNotification = { tool -> postedAlerts += tool },
            onCancelPermissionAlertNotification = { cancelAlertCount.incrementAndGet() },
            observeTurnEvents = observeTurnEventsOverride ?: if (controller != null) {
                controller::observeEventsForActiveTurn
            } else {
                { url, sid, ready ->
                    eventStreamClient.observeTurnEvents(serverUrl = url, targetSessionId = sid, onReady = ready)
                }
            },
            buildVoiceReplyContext = {
                harness.voiceReplyContextOverride
                    ?: controller?.buildVoiceReplyContext()
                    ?: VoiceReplyContext(focusedReply = harness.lastReply)
            },
            currentReplyContextTargetMessageId = {
                harness.targetMessageIdOverride ?: controller?.currentReplyContextTargetMessageId()
            },
            onDirectAnswer = { ans ->
                directAnswers += ans
                controller?.setDirectAnswer(ans)
            },
            setActivePromptTurn = { active -> controller?.setActivePromptTurn(active) },
            ensureAutoSpeakAnchorBeforeTurn = { id, ts -> controller?.ensureAutoSpeakAnchorBeforeTurn(id, ts) },
            ensureCursorSeededBeforeTurn = { id, ts -> controller?.ensureCursorSeededBeforeTurn(id, ts) },
            syncForPromptTurnCompletion = if (controller != null) {
                { sentUserId, preSendId ->
                    controller.syncForPromptTurnCompletion(
                        sentUserMessageId = sentUserId,
                        preSendLatestRecordId = preSendId,
                    )
                }
            } else {
                null
            },
            onSyncForManualReadAloud = onSyncForManualReadAloudOverride ?: if (controller != null) {
                {
                    controller.syncSessionTurns(
                        reason = "manual_read_aloud",
                        triggerAutoSpeak = false,
                        throwOnError = true,
                    )
                }
            } else {
                null
            },
            hasCachedResponses = if (controller != null) {
                controller::hasCachedResponses
            } else {
                { !harness.lastReply.isNullOrBlank() }
            },
            nextAutoSpeakItem = { controller?.nextItemToAutoSpeak() },
            nextUnreadItem = { controller?.nextUnreadItem() },
            itemsForManualReadAloud = { controller?.itemsForManualReadAloud().orEmpty() },
            markItemHeard = { item -> controller?.markItemHeard(item) },
            setSpeakingMessage = { id -> controller?.setSpeakingMessage(id) },
            onSpeechAudibleStart = { id, dur -> controller?.notifySpeechPlaybackStarted(id, dur) },
            clockElapsedMs = { System.currentTimeMillis() },
            ioDispatcher = ioDispatcher,
            permissionAlertDelayMs = permissionAlertDelayMs,
            eventStreamReadyTimeoutMs = 2_000L,
            onPlayCue = onPlayCue,
            stillWorkingCueDelayMs = stillWorkingCueDelayMs,
        )

        harness = Harness(
            store = store,
            serverId = srv.id,
            sessionKey = key,
            stateMachine = sm,
            runner = runner,
            controller = controller,
            speakRequests = speakRequests,
            directAnswers = directAnswers,
            postedAlerts = postedAlerts,
            cancelAlertCount = cancelAlertCount,
            organizingChanges = organizingChanges,
            organizingStoppedLatch = organizingStoppedLatch,
            lastReply = null,
            lastTranscript = null,
            lastSpokenSummary = null,
            statusText = "",
            stagedDraft = null,
            pendingQuickTranscript = null,
        )
        controller?.bindSession(
            BoundServerSession(
                serverId = srv.id,
                serverUrl = baseUrl,
                pinnedSession = pinned,
                sessionKey = key,
            ),
            forceClear = true,
        )
        return harness
    }

    @Test
    fun `VoiceControlPlane LiveTuning and AnswerDirectly use VoiceReplyContext and DirectVoiceAnswer without clobbering lastSpokenSummary`() =
        runBlocking {
            val liveInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"detailed"},"tuning_mode":{"choice":"raw"}}}"""
            val answerInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"}}}"""
            val decisionQueue = ArrayDeque(
                listOf(
                    """{"status":200,"body":"${liveInner.replace("\"", "\\\"")}"}""",
                    """{"status":200,"body":"${answerInner.replace("\"", "\\\"")}"}""",
                ),
            )
            val decisionPayloads = CopyOnWriteArrayList<String>()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/guests/gemini-decisions-bridge/service/request" -> {
                        decisionPayloads += request.body.readUtf8()
                        MockResponse().setResponseCode(200).setBody(decisionQueue.removeFirst())
                    }
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
                h1.lastSpokenSummary = "Original spoken summary."
                h1.pendingQuickTranscript = "Switch to detailed raw mode"

                h1.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue(done1.await(3, TimeUnit.SECONDS))

                assertEquals("Original coding reply stays intact.", h1.lastReply)
                assertEquals("Original spoken summary.", h1.lastSpokenSummary)
                assertTrue(h1.directAnswers.isEmpty())
                assertEquals(SpokenDetailLevel.DETAILED, h1.store.getEffectiveSpokenDetailLevel(h1.serverId))
                assertEquals(TtsInputMode.RAW, h1.store.getEffectiveTtsInputMode(h1.serverId))
                assertEquals(1, h1.speakRequests.size)
                assertEquals(VoiceRole.OPERATOR, h1.speakRequests[0].role)
                assertEquals(TtsInputMode.RAW, h1.speakRequests[0].inputModeOverride)
                assertEquals(AgentTurnRunner.TTS_KIND_CONTROL, h1.speakRequests[0].kind)
                assertFalse(h1.speakRequests[0].notifySpokenSummary)

                // Second call: AnswerDirectly populates DirectVoiceAnswer and preserves BOTH lastReply & lastSpokenSummary when enabled
                val done2 = CountDownLatch(1)
                val h2 = createHarness(scope, baseUrl, initialState = PttState.WAITING_AGENT, onFinishedLatch = done2)
                h2.store.saveQuickTurnDirectAnswer(true, customServerId = null)
                h2.lastReply = "Option 1: Gemini. Option 2: Local Kokoro."
                h2.lastSpokenSummary = "Summary: Gemini or Kokoro."
                h2.targetMessageIdOverride = "msg_focus_2"
                h2.voiceReplyContextOverride = VoiceReplyContext(
                    focusedReply = "Option 1: Gemini. Option 2: Local Kokoro.",
                    focusedTriggerLabel = "Build worker",
                    sameTurnSiblings = listOf("Checked local voices."),
                    pendingUnreadPreviews = listOf(
                        UnreadReplyPreview("msg_unread_3", "Lint", "0 errors found."),
                    ),
                )
                h2.pendingQuickTranscript = "What was option 2?"

                h2.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue(done2.await(3, TimeUnit.SECONDS))

                assertEquals("Option 1: Gemini. Option 2: Local Kokoro.", h2.lastReply)
                assertEquals("Summary: Gemini or Kokoro.", h2.lastSpokenSummary)
                assertEquals(1, h2.directAnswers.size)
                assertEquals(
                    DirectVoiceAnswer(
                        question = "What was option 2?",
                        spokenAnswer = "Option two uses Kokoro locally.",
                        targetMessageId = "msg_focus_2",
                    ),
                    h2.directAnswers[0],
                )
                assertEquals(AgentTurnRunner.TTS_KIND_CONTROL, h2.speakRequests[0].kind)
                assertEquals(VoiceRole.OPERATOR, h2.speakRequests[0].role)
                assertEquals(TtsInputMode.RAW, h2.speakRequests[0].inputModeOverride)
                assertFalse(h2.speakRequests[0].notifySpokenSummary)
                assertEquals("Option two uses Kokoro locally.", h2.speakRequests[0].text)

                val secondState = Json.parseToJsonElement(decisionPayloads[1])
                    .jsonObject["body"]!!.jsonObject["state"]!!.jsonObject
                assertEquals(true, secondState["has_pending_unread"]?.jsonPrimitive?.booleanOrNull)
                assertTrue(secondState["last_assistant_reply"]!!.jsonPrimitive.content.contains("Build worker"))
                assertTrue(secondState["pending_unread_replies"]!!.jsonPrimitive.content.contains("[UNREAD · Lint]"))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `PassThrough turn sends voice-prefixed prompt ignores SyntheticArrived handles delayed permission and speaks multi-step reply sequentially`() =
        runBlocking {
            val passInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"pass_through"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"}}}"""
            val promptSentLatch = CountDownLatch(1)
            val replyReadCount = AtomicInteger(0)
            var sentPromptBody = ""
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/guests/gemini-decisions-bridge/service/request" ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"status":200,"body":"${passInner.replace("\"", "\\\"")}"}""")
                    messagesPath -> {
                        if (replyReadCount.getAndIncrement() == 0) {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":"msg_old","type":"assistant","time":{"created":100,"completed":150},"content":[{"type":"text","text":"old reply"}]}],"cursor":{}}""")
                        } else {
                            MockResponse().setResponseCode(200)
                                .setBody(
                                    """
                                    {
                                      "data": [
                                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":500}},
                                        {"id":"msg_step2","type":"assistant","finish":"stop","time":{"created":400,"completed":450},"content":[{"type":"text","text":"All tests passed."}]},
                                        {"id":"msg_step1","type":"assistant","finish":"tool-calls","time":{"created":300,"completed":350},"content":[{"type":"text","text":"Running gradle suite."}]},
                                        {"id":"msg_user","type":"user","time":{"created":250},"text":"[voice]\nrun gradle tests"},
                                        {"id":"msg_old","type":"assistant","time":{"created":100,"completed":150},"content":[{"type":"text","text":"old reply"}]}
                                      ],
                                      "cursor":{}
                                    }
                                    """.trimIndent(),
                                )
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
                                    webSocket.send("""{"type":"event","eventId":"e0","payload":{"type":"session.synthetic","data":{"sessionID":"ses_1","metadata":{"source":"subagent"}}}}""")
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
                    wireController = true,
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
                assertEquals("msg_step2", harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId)
                assertTrue(harness.controller!!.feedState.value.unreadQueue.isEmpty())

                val permissionSpeak = harness.speakRequests.firstOrNull { it.kind == AgentTurnRunner.TTS_KIND_PERMISSION }
                assertNotNull(permissionSpeak)
                assertEquals(VoiceRole.OPERATOR, permissionSpeak?.role)
                assertEquals(TtsInputMode.RAW, permissionSpeak?.inputModeOverride)
                assertFalse(permissionSpeak?.notifySpokenSummary ?: true)

                val replySpeaks = harness.speakRequests.filter { it.kind == AgentTurnRunner.TTS_KIND_REPLY }
                assertEquals(listOf("Running gradle suite.", "All tests passed."), replySpeaks.map { it.text })
                assertEquals("Payments", replySpeaks.first().sessionLabel)
                assertTrue(replySpeaks.all { it.role == VoiceRole.AGENT })
                assertTrue(replySpeaks.all { it.notifySpokenSummary })
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `commit staged draft clears draft only after sendPrompt and 401 clears only failing server token`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath ->
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
                assertEquals(VoiceRole.OPERATOR, h1.speakRequests[0].role)
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
                assertTrue(h2.organizingStoppedLatch.await(3, TimeUnit.SECONDS))

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
    fun `readLastReply refreshes from server speaks sequentially and handles offline cache vs 401`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val responseMode = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != messagesPath) return MockResponse().setResponseCode(404)
                    return when (responseMode.get()) {
                        0 -> MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "data": [
                                {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":400}},
                                {"id":"m2","type":"assistant","finish":"stop","time":{"created":300,"completed":350},"content":[{"type":"text","text":"Second part."}]},
                                {"id":"m1","type":"assistant","finish":"tool-calls","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First part."}]},
                                {"id":"u1","type":"user","time":{"created":100},"text":"Explain"}
                              ],
                              "cursor":{}
                            }
                            """.trimIndent(),
                        )
                        1 -> MockResponse().setResponseCode(503).setBody("Offline")
                        else -> MockResponse().setResponseCode(401).setBody("Unauthorized")
                    }
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val done1 = CountDownLatch(1)
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = done1,
                )
                harness.runner.readLastReply()
                assertTrue(done1.await(3, TimeUnit.SECONDS))

                assertEquals(listOf("First part.", "Second part."), harness.speakRequests.map { it.text })
                assertEquals(AgentTurnRunner.TTS_KIND_REPLY, harness.speakRequests[0].kind)
                assertTrue(harness.speakRequests[0].isManualRead)
                assertTrue(harness.speakRequests.all { it.role == VoiceRole.AGENT })
                assertTrue(harness.speakRequests[0].notifySpokenSummary)
                assertEquals("Payments", harness.speakRequests[0].sessionLabel)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)

                // Non-401 failure with populated cache falls back to cached turns
                val done2 = CountDownLatch(1)
                val h2 = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = done2,
                )
                responseMode.set(0)
                h2.controller!!.syncSessionTurns("seed")
                responseMode.set(1)
                h2.runner.readLastReply()
                assertTrue(done2.await(3, TimeUnit.SECONDS))
                assertEquals(listOf("First part.", "Second part."), h2.speakRequests.map { it.text })
                assertEquals(PttState.ARMED_IDLE, h2.stateMachine.currentState)

                // 401 failure MUST fail with auth error even when cache is populated
                val done3 = CountDownLatch(1)
                val h3 = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = done3,
                )
                responseMode.set(0)
                h3.controller!!.syncSessionTurns("seed")
                responseMode.set(2)
                h3.runner.readLastReply()
                assertTrue(done3.await(3, TimeUnit.SECONDS))
                assertEquals(PttState.ERROR, h3.stateMachine.currentState)
                assertNull(h3.store.getServer(h3.serverId)?.token)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `speakPendingReplies drains queued unread items sequentially including arrivals during playback`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val bodyRef = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First async reply."}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"Start"}
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
                val doneLatch = CountDownLatch(1)
                lateinit var harness: Harness
                harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = doneLatch,
                    speakHook = { req ->
                        if (req.text == "First async reply.") {
                            // Mid-playback async arrival
                            bodyRef.set(
                                """
                                {
                                  "data": [
                                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":600}},
                                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":500,"completed":550},"content":[{"type":"text","text":"Arrived mid playback."}]},
                                    {"id":"syn_2","type":"synthetic","time":{"created":400},"metadata":{"source":"subagent","agent":"build"},"text":"<subagent>done</subagent>"},
                                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First async reply."}]},
                                    {"id":"u1","type":"user","time":{"created":100},"text":"Start"}
                                  ],
                                  "cursor":{}
                                }
                                """.trimIndent(),
                            )
                            harness.controller!!.syncSessionTurns("mid_playback_arrival")
                        }
                        TtsPlaybackResult.COMPLETED
                    },
                )
                harness.store.saveReplyCursor(
                    harness.sessionKey,
                    SessionReplyCursor(
                        armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE,
                        updatedAtMs = 1L,
                    ),
                )
                harness.controller!!.syncSessionTurns("initial")
                harness.runner.speakPendingReplies()

                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
                assertEquals(
                    listOf("First async reply.", "Arrived mid playback."),
                    harness.speakRequests.map { it.text },
                )
                assertTrue(harness.speakRequests.all { it.role == VoiceRole.AGENT })
                assertEquals("a2", harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId)
                assertTrue(harness.controller.feedState.value.unreadQueue.isEmpty())
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `speakPendingReplies and readLastReply fail loudly instead of stranding SPEAKING_TTS when pinned session is missing`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val done1 = CountDownLatch(1)
                val h1 = createHarness(scope, baseUrl, initialState = PttState.SPEAKING_TTS, onFinishedLatch = done1)
                h1.store.updatePinnedSession(h1.serverId, null)
                h1.runner.speakPendingReplies()
                assertTrue(done1.await(3, TimeUnit.SECONDS))
                assertEquals(PttState.ERROR, h1.stateMachine.currentState)

                val done2 = CountDownLatch(1)
                val h2 = createHarness(scope, baseUrl, initialState = PttState.SPEAKING_TTS, onFinishedLatch = done2)
                h2.store.updatePinnedSession(h2.serverId, null)
                h2.runner.readLastReply()
                assertTrue(done2.await(3, TimeUnit.SECONDS))
                assertEquals(PttState.ERROR, h2.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `when quickTurnDirectAnswer is disabled by default even answer_directly passes prompt straight through while LiveTuning stays active`() =
        runBlocking {
            val answerInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"answer_directly"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"}}}"""
            val promptSentLatch = CountDownLatch(1)
            val replyReadCount = AtomicInteger(0)
            var sentPromptBody = ""
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/guests/gemini-decisions-bridge/service/request" ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"status":200,"body":"${answerInner.replace("\"", "\\\"")}"}""")
                    "/api/small-model/generate" ->
                        MockResponse().setResponseCode(500).setBody("Must not be called when direct answers disabled")
                    messagesPath -> {
                        if (replyReadCount.getAndIncrement() == 0) {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":"msg_old","type":"assistant","content":[{"type":"text","text":"Previous reply"}]}]}""")
                        } else {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":300}},{"id":"msg_new","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Session answered directly."}]},{"id":"msg_old","type":"assistant","content":[{"type":"text","text":"Previous reply"}]}]}""")
                        }
                    }
                    "/api/session/ses_1/prompt" -> {
                        sentPromptBody = request.body.readUtf8()
                        promptSentLatch.countDown()
                        MockResponse().setResponseCode(200).setBody("""{"id":"msg_u2"}""")
                    }
                    "/api/global/event/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"type":"ready","scope":"global"}""")
                                Thread {
                                    promptSentLatch.await(2, TimeUnit.SECONDS)
                                    webSocket.send("""{"type":"event","eventId":"e1","payload":{"type":"session.execution.succeeded","data":{"sessionID":"ses_1"}}}""")
                                }.start()
                            }
                        },
                    )
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
                    initialState = PttState.WAITING_AGENT,
                    onFinishedLatch = doneLatch,
                )
                assertFalse(harness.store.getEffectiveQuickTurnDirectAnswer(harness.serverId))
                harness.lastReply = "Previous reply"
                harness.pendingQuickTranscript = "What was option 2?"

                harness.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue(doneLatch.await(5, TimeUnit.SECONDS))

                assertTrue(sentPromptBody.contains("[voice]\\nWhat was option 2?"))
                assertTrue(harness.directAnswers.filterNotNull().isEmpty())
                assertEquals(listOf("Session answered directly."), harness.speakRequests.map { it.text })
                assertEquals(AgentTurnRunner.TTS_KIND_REPLY, harness.speakRequests[0].kind)
                assertEquals(VoiceRole.AGENT, harness.speakRequests[0].role)

                // And LiveTuning still works even when quickTurnDirectAnswer is disabled
                val liveInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"detailed"},"tuning_mode":{"choice":"none"}}}"""
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/api/guests/gemini-decisions-bridge/service/request" ->
                            MockResponse().setResponseCode(200)
                                .setBody("""{"status":200,"body":"${liveInner.replace("\"", "\\\"")}"}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val liveDone = CountDownLatch(1)
                val hLive = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.WAITING_AGENT,
                    onFinishedLatch = liveDone,
                )
                assertFalse(hLive.store.getEffectiveQuickTurnDirectAnswer(hLive.serverId))
                hLive.pendingQuickTranscript = "Switch to detailed"
                hLive.runner.dispatchPrompt(fromStagedDraft = false)
                assertTrue(liveDone.await(3, TimeUnit.SECONDS))
                assertEquals(SpokenDetailLevel.DETAILED, hLive.store.getEffectiveSpokenDetailLevel(hLive.serverId))
                assertEquals(AgentTurnRunner.TTS_KIND_CONTROL, hLive.speakRequests.single().kind)
                assertEquals(VoiceRole.OPERATOR, hLive.speakRequests.single().role)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `finalizeQuickTranscript preserves raw transcript verbatim without calling small-model generate`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val smallModelHits = AtomicInteger(0)
                val baseUrl = server.url("/").toString().trimEnd('/')
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == OpenChamberHttp.SMALL_MODEL_GENERATE_PATH) {
                            smallModelHits.incrementAndGet()
                        }
                        return MockResponse().setResponseCode(500)
                    }
                }
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.TRANSCRIBING_SEND,
                )

                val raw = "  um actually check foo_bar.kt --no-verify please  "
                assertFalse(harness.store.getEffectiveQuickTranscriptCleanup(harness.serverId))
                harness.runner.finalizeQuickTranscript(
                    serverId = harness.serverId,
                    serverUrl = baseUrl,
                    sessionKey = harness.sessionKey,
                    pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                    rawTranscript = raw,
                )

                assertEquals(0, smallModelHits.get())
                assertEquals("um actually check foo_bar.kt --no-verify please", harness.pendingQuickTranscript)
                assertEquals("um actually check foo_bar.kt --no-verify please", harness.lastTranscript)
                assertEquals(PttState.SEND_GRACE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `finalizeQuickTranscript runs small-model cleanup when quickTranscriptCleanup is enabled`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val smallModelHits = AtomicInteger(0)
                val baseUrl = server.url("/").toString().trimEnd('/')
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == OpenChamberHttp.SMALL_MODEL_GENERATE_PATH) {
                            smallModelHits.incrementAndGet()
                            return MockResponse()
                                .setResponseCode(200)
                                .setBody("""{"text":"maybe check foo_bar.kt --no-verify please"}""")
                        }
                        return MockResponse().setResponseCode(404)
                    }
                }
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.TRANSCRIBING_SEND,
                )
                harness.store.saveQuickTranscriptCleanup(true)

                val raw = "  um maybe check foo_bar.kt --no-verify please  "
                harness.runner.finalizeQuickTranscript(
                    serverId = harness.serverId,
                    serverUrl = baseUrl,
                    sessionKey = harness.sessionKey,
                    pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                    rawTranscript = raw,
                )

                assertEquals(1, smallModelHits.get())
                assertEquals("maybe check foo_bar.kt --no-verify please", harness.pendingQuickTranscript)
                assertEquals("maybe check foo_bar.kt --no-verify please", harness.lastTranscript)
                assertEquals(PttState.SEND_GRACE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `LiveTuning persists audioCueMode in TokenStore`() = runBlocking {
        val liveCueInner = """{"model":"gemini-decision-1","answers":{"route":{"choice":"live_tuning"},"tuning_detail":{"choice":"none"},"tuning_mode":{"choice":"none"},"tuning_cues":{"choice":"vocal"}}}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/guests/gemini-decisions-bridge/service/request" ->
                    MockResponse().setResponseCode(200)
                        .setBody("""{"status":200,"body":"${liveCueInner.replace("\"", "\\\"")}"}""")
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
                initialState = PttState.WAITING_AGENT,
                onFinishedLatch = doneLatch,
            )
            assertEquals(AudioCueMode.HYBRID, harness.store.getEffectiveAudioCueMode(harness.serverId))
            harness.pendingQuickTranscript = "Turn on vocal cues"

            harness.runner.dispatchPrompt(fromStagedDraft = false)
            assertTrue(doneLatch.await(3, TimeUnit.SECONDS))

            assertEquals(AudioCueMode.VOCAL, harness.store.getEffectiveAudioCueMode(harness.serverId))
            assertEquals("Switched to Vocal cues.", harness.speakRequests.single().text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `runAgentTurn emits STILL_WORKING cue once after delay and cancels on early permission or completion`() =
        runBlocking {
            val eventsFlow = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 8)
            val playedCues = CopyOnWriteArrayList<CueEvent>()
            val stillWorkingLatch = CountDownLatch(1)
            val promptSentLatch = AtomicReference(CountDownLatch(1))
            val replyReadCount = AtomicInteger(0)
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> {
                        if (replyReadCount.getAndIncrement() == 0) {
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":"msg_old","type":"assistant","time":{"created":100,"completed":150},"content":[{"type":"text","text":"old"}]}],"cursor":{}}""")
                        } else {
                            MockResponse().setResponseCode(200)
                                .setBody(
                                    """
                                    {
                                      "data": [
                                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                                        {"id":"msg_new","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Done."}]},
                                        {"id":"msg_u1","type":"user","time":{"created":180},"text":"[voice]\nRun slow build"},
                                        {"id":"msg_old","type":"assistant","time":{"created":100,"completed":150},"content":[{"type":"text","text":"old"}]}
                                      ],
                                      "cursor":{}
                                    }
                                    """.trimIndent(),
                                )
                        }
                    }
                    "/api/session/ses_1/prompt" -> {
                        promptSentLatch.get().countDown()
                        MockResponse().setResponseCode(200).setBody("""{"id":"msg_u1"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val done1 = CountDownLatch(1)
                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.WAITING_AGENT,
                    onFinishedLatch = done1,
                    onPlayCue = { cue ->
                        playedCues += cue
                        if (cue == CueEvent.STILL_WORKING) {
                            stillWorkingLatch.countDown()
                        }
                    },
                    stillWorkingCueDelayMs = 80L,
                    observeTurnEventsOverride = { _, _, onReady ->
                        onReady()
                        eventsFlow
                    },
                )

                // 1. Slow turn -> STILL_WORKING fires once
                val turn1Job = scope.launch {
                    harness.runner.runAgentTurn(
                        serverId = harness.serverId,
                        serverUrl = baseUrl,
                        pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                        promptText = "Run slow build",
                        fromStagedDraft = false,
                    )
                }
                assertTrue(promptSentLatch.get().await(2, TimeUnit.SECONDS))
                assertTrue(stillWorkingLatch.await(2, TimeUnit.SECONDS))
                assertEquals(listOf(CueEvent.STILL_WORKING), playedCues.toList())

                eventsFlow.emit(TurnEvent.Completed)
                turn1Job.join()
                assertTrue(done1.await(2, TimeUnit.SECONDS))
                assertEquals(listOf(CueEvent.STILL_WORKING), playedCues.toList())
                assertFalse(harness.speakRequests.last().isManualRead)

                // 2. PermissionAsked before delay cancels stillWorkingJob
                playedCues.clear()
                replyReadCount.set(0)
                promptSentLatch.set(CountDownLatch(1))
                val sm2 = harness.stateMachine
                sm2.dispatch(PttEvent.Arm())
                sm2.dispatch(PttEvent.SingleTap)
                sm2.dispatch(PttEvent.SingleTap)
                sm2.dispatch(PttEvent.TranscriptReady("run check"))
                sm2.dispatch(PttEvent.GraceTimerExpired)
                assertEquals(PttState.WAITING_AGENT, sm2.currentState)

                val turn2Job = scope.launch {
                    harness.runner.runAgentTurn(
                        serverId = harness.serverId,
                        serverUrl = baseUrl,
                        pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                        promptText = "Run quick permission check",
                        fromStagedDraft = false,
                    )
                }
                assertTrue(promptSentLatch.get().await(2, TimeUnit.SECONDS))
                eventsFlow.emit(TurnEvent.PermissionAsked("bash"))
                kotlinx.coroutines.delay(140L)
                assertTrue(CueEvent.STILL_WORKING !in playedCues)

                eventsFlow.emit(TurnEvent.Interrupted("cancelled"))
                turn2Job.join()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `runAgentTurn survives earlier turn Completed via TurnStillRunning and settles on sentUserMessageId turn`() =
        runBlocking {
            val eventsFlow = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 8)
            val promptSentLatch = CountDownLatch(1)
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Earlier reply"}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                  ],
                  "cursor":{}
                }
                """.trimIndent(),
            )

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    "/api/session/ses_1/prompt" -> {
                        promptSentLatch.countDown()
                        MockResponse().setResponseCode(200).setBody("""{"id":"u2"}""")
                    }
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
                    initialState = PttState.WAITING_AGENT,
                    wireController = true,
                    onFinishedLatch = doneLatch,
                    observeTurnEventsOverride = { _, _, onReady ->
                        onReady()
                        eventsFlow
                    },
                )

                val turnJob = scope.launch {
                    harness.runner.runAgentTurn(
                        serverId = harness.serverId,
                        serverUrl = baseUrl,
                        pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                        promptText = "Second prompt",
                        fromStagedDraft = false,
                    )
                }
                assertTrue(promptSentLatch.await(2, TimeUnit.SECONDS))

                // Earlier turn's Completed arrives while u2 is not yet visible -> TurnStillRunning keeps WAITING_AGENT
                eventsFlow.emit(TurnEvent.Completed)
                kotlinx.coroutines.delay(80L)
                assertEquals(PttState.WAITING_AGENT, harness.stateMachine.currentState)
                assertTrue(harness.speakRequests.isEmpty())

                // Real reply for u2 completes
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":400,"completed":450},"content":[{"type":"text","text":"Final reply for u2"}]},
                        {"id":"u2","type":"user","time":{"created":300},"text":"[voice]\nSecond prompt"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":200}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":150,"completed":180},"content":[{"type":"text","text":"Earlier reply"}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                eventsFlow.emit(TurnEvent.Completed)
                turnJob.join()
                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))

                assertEquals(listOf("Final reply for u2"), harness.speakRequests.map { it.text })
                assertEquals("a2", harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `runAgentTurn retries FlushLagRetryNeeded and recovers Reply 1 after 250ms`() =
        runBlocking {
            val eventsFlow = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 8)
            val promptSentLatch = CountDownLatch(1)
            val getCount = AtomicInteger(0)
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> {
                        when (getCount.getAndIncrement()) {
                            0 -> MockResponse().setResponseCode(200).setBody("""{"data":[],"cursor":{}}""")
                            1 -> MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "data": [
                                    {"id":"a1","type":"assistant","time":{"created":200},"content":[{"type":"text","text":"Partial..."}]},
                                    {"id":"u1","type":"user","time":{"created":100},"text":"[voice]\nPrompt 1"}
                                  ],
                                  "cursor":{}
                                }
                                """.trimIndent(),
                            )
                            else -> MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "data": [
                                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Recovered after lag."}]},
                                    {"id":"u1","type":"user","time":{"created":100},"text":"[voice]\nPrompt 1"}
                                  ],
                                  "cursor":{}
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                    "/api/session/ses_1/prompt" -> {
                        promptSentLatch.countDown()
                        MockResponse().setResponseCode(200).setBody("""{"id":"u1"}""")
                    }
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
                    initialState = PttState.WAITING_AGENT,
                    wireController = true,
                    onFinishedLatch = doneLatch,
                    observeTurnEventsOverride = { _, _, onReady ->
                        onReady()
                        eventsFlow
                    },
                )

                val turnJob = scope.launch {
                    harness.runner.runAgentTurn(
                        serverId = harness.serverId,
                        serverUrl = baseUrl,
                        pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                        promptText = "Prompt 1",
                        fromStagedDraft = false,
                    )
                }
                assertTrue(promptSentLatch.await(2, TimeUnit.SECONDS))
                eventsFlow.emit(TurnEvent.Completed)
                turnJob.join()

                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
                assertTrue(getCount.get() >= 3)
                assertEquals(listOf("Recovered after lag."), harness.speakRequests.map { it.text })
                assertEquals("a1", harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `PreemptWithManualRead does not call markItemHeard on ABORTED reply and mid-sync abort skips speaking`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val body = """
                {
                  "data": [
                    {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":500}},
                    {"id":"a2","type":"assistant","finish":"stop","time":{"created":450,"completed":480},"content":[{"type":"text","text":"Second reply"}]},
                    {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply"}]},
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
                val doneLatch = CountDownLatch(1)
                val firstSpeakStarted = CountDownLatch(1)
                val releaseFirstSpeak = CompletableDeferred<Unit>()
                val stoppedFlag = AtomicBoolean(false)
                var heardAtManualStart: String? = "UNSET"
                lateinit var harness: Harness
                harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = doneLatch,
                    stopTts = {
                        stoppedFlag.set(true)
                        releaseFirstSpeak.complete(Unit)
                    },
                    speakHook = { req ->
                        if (!req.isManualRead && req.messageId == "a1") {
                            firstSpeakStarted.countDown()
                            releaseFirstSpeak.await()
                            if (stoppedFlag.get()) TtsPlaybackResult.ABORTED else TtsPlaybackResult.COMPLETED
                        } else {
                            if (req.isManualRead && req.messageId == "a1") {
                                heardAtManualStart = harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId
                            }
                            TtsPlaybackResult.COMPLETED
                        }
                    },
                )
                harness.store.saveReplyCursor(
                    harness.sessionKey,
                    SessionReplyCursor(armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE, updatedAtMs = 1L),
                )
                harness.controller!!.syncSessionTurns("seed")
                harness.runner.speakPendingReplies()

                assertTrue(firstSpeakStarted.await(3, TimeUnit.SECONDS))
                harness.runner.readLastReply()
                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))

                // The aborted auto-speak of a1 must NOT have marked a1 heard before ManualRead started!
                assertNull(heardAtManualStart)
                // ManualRead then spoke both a1 & a2 to COMPLETED and marked a2 heard at the end
                assertEquals(listOf("a1", "a1", "a2"), harness.speakRequests.map { it.messageId })
                assertEquals("a2", harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)

                // Part 2: Mid-sync cancellation inside PreemptWithManualRead aborts BEFORE speakTts
                val syncEntered = CountDownLatch(1)
                val allowSyncExit = CompletableDeferred<Unit>()
                val h2 = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onSyncForManualReadAloudOverride = {
                        syncEntered.countDown()
                        allowSyncExit.await()
                    },
                )
                h2.runner.readLastReply()
                assertTrue(syncEntered.await(3, TimeUnit.SECONDS))
                h2.runner.cancelSpeechJobs()
                allowSyncExit.complete(Unit)
                kotlinx.coroutines.delay(80L)
                assertTrue(h2.speakRequests.isEmpty())
                assertFalse(h2.runner.isPlaybackActive)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `EnqueueOperatorSpeech queues FIFO without killing active reply speech then drains trailing items before onTtsFinished`() =
        runBlocking {
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val bodyRef = AtomicReference(
                """
                {
                  "data": [
                    {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                    {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply."}]},
                    {"id":"u1","type":"user","time":{"created":100},"text":"One"}
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
                val doneLatch = CountDownLatch(1)
                val finishedCount = AtomicInteger(0)
                val stopCalls = AtomicInteger(0)
                val a1Started = CountDownLatch(1)
                val allowA1Finish = CompletableDeferred<Unit>()

                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = doneLatch,
                    onTtsFinishedCallback = { finishedCount.incrementAndGet() },
                    stopTts = { stopCalls.incrementAndGet() },
                    speakHook = { req ->
                        if (req.messageId == "a1") {
                            a1Started.countDown()
                            allowA1Finish.await()
                        }
                        TtsPlaybackResult.COMPLETED
                    },
                )

                harness.store.saveReplyCursor(
                    harness.sessionKey,
                    SessionReplyCursor(armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE, updatedAtMs = 1L),
                )
                harness.controller!!.syncSessionTurns("seed")
                harness.runner.speakPendingReplies()
                assertTrue(a1Started.await(3, TimeUnit.SECONDS))

                // Enqueue operator speech + add trailing reply a2 while a1 is still speaking
                harness.runner.enqueueOperatorSpeech(
                    serverId = harness.serverId,
                    serverUrl = baseUrl,
                    pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                    text = "Switched to Detailed.",
                    speechKind = AgentTurnRunner.TTS_KIND_CONTROL,
                )
                bodyRef.set(
                    """
                    {
                      "data": [
                        {"id":"idle_2","type":"idle","outcome":"succeeded","time":{"created":600}},
                        {"id":"a2","type":"assistant","finish":"stop","time":{"created":500,"completed":550},"content":[{"type":"text","text":"Trailing auto reply."}]},
                        {"id":"u2","type":"user","time":{"created":400},"text":"Two"},
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"First reply."}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                harness.controller.syncSessionTurns("trailing_arrival")

                // Unblock a1 -> FIFO order must be a1 -> operator speech -> trailing a2, then single onTtsFinished()
                assertEquals(0, stopCalls.get())
                assertEquals(0, finishedCount.get())
                allowA1Finish.complete(Unit)

                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
                assertEquals(0, stopCalls.get())
                assertEquals(1, finishedCount.get())
                assertEquals(
                    listOf("First reply.", "Switched to Detailed.", "Trailing auto reply."),
                    harness.speakRequests.map { it.text },
                )
                assertEquals(
                    listOf(VoiceRole.AGENT, VoiceRole.OPERATOR, VoiceRole.AGENT),
                    harness.speakRequests.map { it.role },
                )
                assertEquals("a2", harness.store.getReplyCursor(harness.sessionKey)?.lastHeardMessageId)
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `PermissionAsked and PermissionResolved cancel only permission speech without killing unrelated speech`() =
        runBlocking {
            val eventsFlow = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 8)
            val promptSentLatch = CountDownLatch(1)
            val messagesPath = "/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}"
            val currentBody = AtomicReference("""{"data":[],"cursor":{}}""")

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                    "/api/session/ses_1/prompt" -> {
                        promptSentLatch.countDown()
                        MockResponse().setResponseCode(200).setBody("""{"id":"u1"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val baseUrl = server.url("/").toString().trimEnd('/')
                val doneLatch = CountDownLatch(1)
                val focusEntries = AtomicInteger(0)
                val focusExits = AtomicInteger(0)
                val stopCalls = AtomicInteger(0)
                val permStarted = CountDownLatch(1)
                val allowPermExit = CompletableDeferred<Unit>()
                val permStopped = AtomicBoolean(false)

                val harness = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.WAITING_AGENT,
                    permissionAlertDelayMs = 20L,
                    wireController = true,
                    onFinishedLatch = doneLatch,
                    stopTts = {
                        stopCalls.incrementAndGet()
                        permStopped.set(true)
                        allowPermExit.complete(Unit)
                    },
                    withStandalonePlaybackFocus = { block ->
                        focusEntries.incrementAndGet()
                        try {
                            block()
                        } finally {
                            focusExits.incrementAndGet()
                        }
                    },
                    speakHook = { req ->
                        if (req.kind == AgentTurnRunner.TTS_KIND_PERMISSION) {
                            permStarted.countDown()
                            allowPermExit.await()
                            if (permStopped.get()) TtsPlaybackResult.ABORTED else TtsPlaybackResult.COMPLETED
                        } else {
                            TtsPlaybackResult.COMPLETED
                        }
                    },
                    observeTurnEventsOverride = { _, _, onReady ->
                        onReady()
                        eventsFlow
                    },
                )

                val turnJob = scope.launch {
                    harness.runner.runAgentTurn(
                        serverId = harness.serverId,
                        serverUrl = baseUrl,
                        pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                        promptText = "Run bash",
                        fromStagedDraft = false,
                    )
                }
                assertTrue(promptSentLatch.await(2, TimeUnit.SECONDS))

                eventsFlow.emit(TurnEvent.PermissionAsked("bash"))
                assertTrue(permStarted.await(3, TimeUnit.SECONDS))
                assertEquals(PttState.PERMISSION_BLOCKED, harness.stateMachine.currentState)
                assertEquals(1, focusEntries.get())

                // Resolving permission cancels ONLY permission speech and must NOT call onTtsFinished()
                eventsFlow.emit(TurnEvent.PermissionResolved)
                kotlinx.coroutines.delay(60L)
                assertEquals(1, stopCalls.get())
                assertEquals(1, focusExits.get())
                assertEquals(1L, doneLatch.count)
                assertEquals(PttState.WAITING_AGENT, harness.stateMachine.currentState)

                // Turn completes with reply -> speaks cleanly
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Command finished."}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"[voice]\nRun bash"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                eventsFlow.emit(TurnEvent.Completed)
                turnJob.join()
                assertTrue(doneLatch.await(3, TimeUnit.SECONDS))
                assertEquals(
                    listOf(AgentTurnRunner.TTS_KIND_PERMISSION, AgentTurnRunner.TTS_KIND_REPLY),
                    harness.speakRequests.map { it.kind },
                )
                assertEquals(PttState.ARMED_IDLE, harness.stateMachine.currentState)

                // Part 2: PermissionResolved while unrelated speech is active must NOT call stopTts()
                val unrelatedEvents = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 4)
                val h2PromptSent = CountDownLatch(1)
                val h2UnrelatedDone = CountDownLatch(1)
                val h2StopCalls = AtomicInteger(0)
                val h2SpeakStarted = CountDownLatch(1)
                val h2AllowSpeakFinish = CompletableDeferred<Unit>()
                currentBody.set(
                    """
                    {
                      "data": [
                        {"id":"idle_1","type":"idle","outcome":"succeeded","time":{"created":300}},
                        {"id":"a1","type":"assistant","finish":"stop","time":{"created":200,"completed":250},"content":[{"type":"text","text":"Unrelated active speech."}]},
                        {"id":"u1","type":"user","time":{"created":100},"text":"One"}
                      ],
                      "cursor":{}
                    }
                    """.trimIndent(),
                )
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        messagesPath -> MockResponse().setResponseCode(200).setBody(currentBody.get())
                        "/api/session/ses_1/prompt" -> {
                            h2PromptSent.countDown()
                            MockResponse().setResponseCode(200).setBody("""{"id":"u2"}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val h2 = createHarness(
                    scope = scope,
                    baseUrl = baseUrl,
                    initialState = PttState.SPEAKING_TTS,
                    wireController = true,
                    onFinishedLatch = h2UnrelatedDone,
                    stopTts = { h2StopCalls.incrementAndGet() },
                    speakHook = {
                        h2SpeakStarted.countDown()
                        h2AllowSpeakFinish.await()
                        TtsPlaybackResult.COMPLETED
                    },
                    observeTurnEventsOverride = { _, _, onReady ->
                        onReady()
                        unrelatedEvents
                    },
                )
                h2.store.saveReplyCursor(
                    h2.sessionKey,
                    SessionReplyCursor(armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE, updatedAtMs = 1L),
                )
                h2.controller!!.syncSessionTurns("seed")
                h2.runner.speakPendingReplies()
                assertTrue(h2SpeakStarted.await(3, TimeUnit.SECONDS))

                val bgTurn = scope.launch {
                    h2.runner.runAgentTurn(
                        serverId = h2.serverId,
                        serverUrl = baseUrl,
                        pinnedSession = PinnedSession("ses_1", "/proj", "Payments"),
                        promptText = "Check",
                        fromStagedDraft = false,
                    )
                }
                assertTrue(h2PromptSent.await(2, TimeUnit.SECONDS))
                unrelatedEvents.emit(TurnEvent.PermissionResolved)
                kotlinx.coroutines.delay(50L)
                assertEquals(0, h2StopCalls.get())

                h2AllowSpeakFinish.complete(Unit)
                assertTrue(h2UnrelatedDone.await(3, TimeUnit.SECONDS))
                assertEquals("a1", h2.store.getReplyCursor(h2.sessionKey)?.lastHeardMessageId)
                bgTurn.cancel()
            } finally {
                scope.cancel()
            }
        }
}
