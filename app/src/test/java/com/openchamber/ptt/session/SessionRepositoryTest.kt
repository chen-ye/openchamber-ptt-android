package com.openchamber.ptt.session

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {
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

    @Test
    fun `listSessions sends Bearer token and directory headers and parses sessions payload`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "ses_123",
                          "title": "PTT Android Feature",
                          "location": {
                            "directory": "/workspace/openchamber-ptt-android"
                          }
                        },
                        {
                          "id": "ses_456",
                          "title": null,
                          "location": {
                            "directory": "/workspace/work space"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val client = OpenChamberHttp.createClient(
            tokenProvider = { "oc_client_test_token_999" },
        )
        val repository = SessionRepository(httpClient = client)

        val baseUrl = server.url("/").toString().trimEnd('/')
        val requestedDirectory = "/workspace/my repo"
        val sessions = repository.listSessions(
            serverUrl = baseUrl,
            directory = requestedDirectory,
        )

        assertEquals(2, sessions.size)
        assertEquals(
            SessionSummary(
                id = "ses_123",
                title = "PTT Android Feature",
                directory = "/workspace/openchamber-ptt-android",
            ),
            sessions[0],
        )
        assertEquals(
            SessionSummary(
                id = "ses_456",
                title = "ses_456",
                directory = "/workspace/work space",
            ),
            sessions[1],
        )

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/session?limit=500", recorded.path)
        assertEquals("Bearer oc_client_test_token_999", recorded.getHeader("Authorization"))
        assertEquals(
            "%2Fworkspace%2Fmy%20repo",
            recorded.getHeader("x-opencode-directory"),
        )
        assertEquals("uri", recorded.getHeader("x-opencode-directory-encoding"))
    }

    @Test
    fun `listSessions omits directory headers when directory is not provided`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[]}"""),
        )

        val client = OpenChamberHttp.createClient(
            tokenProvider = { "oc_client_abc" },
        )
        val repository = SessionRepository(httpClient = client)

        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl, directory = null)

        assertEquals(emptyList<SessionSummary>(), sessions)

        val recorded = server.takeRequest()
        assertEquals("Bearer oc_client_abc", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("x-opencode-directory"))
        assertNull(recorded.getHeader("x-opencode-directory-encoding"))
    }

    @Test
    fun `listAllServerSessions omits directory headers even when httpClient has directoryForUrlProvider`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"ses_1","title":"Global","location":{"directory":"/repo/one"}}]}"""),
        )

        val directoryScopedHttpClient = OpenChamberHttp.createClient(
            tokenForUrlProvider = { "tok_shared" },
            directoryForUrlProvider = { "/repo/pinned" },
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        directoryScopedHttpClient.newCall(Request.Builder().url("$baseUrl/api/health").get().build()).execute().close()

        val repository = SessionRepository(httpClient = directoryScopedHttpClient)
        val results = repository.listAllServerSessions(
            listOf(
                ServerProfile(
                    id = "srv_1",
                    label = "Server 1",
                    serverUrl = baseUrl,
                    token = "tok_shared",
                    lastPinnedSession = PinnedSession("ses_1", "/repo/pinned", "Pinned"),
                ),
            ),
        )

        assertEquals(1, results.size)
        assertEquals(listOf("ses_1"), results[0].sessions.map { it.id })
        assertEquals(listOf("ses_1"), results[0].roots.map { it.root.id })

        val normalReq = server.takeRequest()
        assertEquals("/api/health", normalReq.path)
        assertEquals("Bearer tok_shared", normalReq.getHeader("Authorization"))
        assertEquals("%2Frepo%2Fpinned", normalReq.getHeader("x-opencode-directory"))
        assertEquals("uri", normalReq.getHeader("x-opencode-directory-encoding"))

        val sessionReq = server.takeRequest()
        assertEquals("/api/session?limit=500", sessionReq.path)
        assertEquals("Bearer tok_shared", sessionReq.getHeader("Authorization"))
        assertNull(sessionReq.getHeader("x-opencode-directory"))
        assertNull(sessionReq.getHeader("x-opencode-directory-encoding"))
    }

    @Test
    fun `listAllServerSessions loads healthy server even when sibling returns 401`() = runTest {
        val serverB = MockWebServer()
        serverB.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"data":[{"id":"ses_a1","title":"Alpha","location":{"directory":"/a"}}]}"""),
            )
            serverB.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

            val urlA = server.url("/").toString().trimEnd('/')
            val urlB = serverB.url("/").toString().trimEnd('/')
            val profileA = ServerProfile(id = "a", label = "Server A", serverUrl = urlA, token = "tok_a")
            val profileB = ServerProfile(id = "b", label = "Server B", serverUrl = urlB, token = "tok_b")

            val client = OpenChamberHttp.createClient(
                tokenForUrlProvider = { url ->
                    when (url.port) {
                        server.port -> "tok_a"
                        serverB.port -> "tok_b"
                        else -> null
                    }
                },
            )
            val repository = SessionRepository(httpClient = client)

            val results = repository.listAllServerSessions(listOf(profileA, profileB))
            assertEquals(2, results.size)
            assertEquals(1, results[0].sessions.size)
            assertEquals("ses_a1", results[0].sessions[0].id)
            assertFalse(results[0].isUnauthorized)

            assertTrue(results[1].sessions.isEmpty())
            assertTrue(results[1].isUnauthorized)
            assertEquals("Bearer tok_a", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer tok_b", serverB.takeRequest().getHeader("Authorization"))
        } finally {
            serverB.shutdown()
        }
    }

    @Test
    fun `listSessions excludes archived sessions with time archived timestamp`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "ses_active",
                          "title": "Active Session",
                          "location": { "directory": "/repo/active" },
                          "time": { "created": 100, "updated": 200 }
                        },
                        {
                          "id": "ses_archived",
                          "title": "Old Archived Session",
                          "location": { "directory": "/repo/old" },
                          "time": { "created": 100, "updated": 200, "archived": 300 }
                        },
                        {
                          "id": "ses_zero",
                          "title": "Zero Timestamp",
                          "location": { "directory": "/repo/zero" },
                          "time": { "created": 100, "updated": 200, "archived": 0 }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(2, sessions.size)
        assertEquals(listOf("ses_active", "ses_zero"), sessions.map { it.id })
    }

    @Test
    fun `listSessions paginates with cursor next, deduplicates, and handles trailing empty page`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_1", "title": "First", "time": { "updated": 300 } },
                        { "id": "ses_2", "title": "Second", "time": { "updated": 200 } }
                      ],
                      "cursor": { "next": "cur+page/2=" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_2", "title": "Second Duplicate", "time": { "updated": 200 } },
                        { "id": "ses_3", "title": "Third", "time": { "updated": 100 } }
                      ],
                      "cursor": { "next": "cur_page_3" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[],"cursor":{}}"""),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(listOf("ses_1", "ses_2", "ses_3"), sessions.map { it.id })
        assertEquals("Second", sessions[1].title)

        assertEquals("/api/session?limit=500", server.takeRequest().path)
        assertEquals("/api/session?limit=500&cursor=cur%2Bpage%2F2%3D", server.takeRequest().path)
        assertEquals("/api/session?limit=500&cursor=cur_page_3", server.takeRequest().path)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `listSessions continues pagination when page 1 contains only archived sessions`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_arch_1", "title": "Archived 1", "time": { "updated": 500, "archived": 600 } },
                        { "id": "ses_arch_2", "title": "Archived 2", "time": { "updated": 400, "archived": 600 } }
                      ],
                      "cursor": { "next": "page_2" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_live_1", "title": "Live 1", "time": { "updated": 300 } }
                      ],
                      "cursor": { "next": null }
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(listOf("ses_live_1"), sessions.map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `listSessions breaks cleanly on cursor loop and returns collected sessions`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_1", "title": "One", "time": { "updated": 200 } }
                      ],
                      "cursor": { "next": "loop_cur" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_2", "title": "Two", "time": { "updated": 300 } }
                      ],
                      "cursor": { "next": "loop_cur" }
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(listOf("ses_2", "ses_1"), sessions.map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `listSessions parses float scientific timestamps, zero sentinels, metadata marks, errors, and subagents`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "ses_float_unread",
                          "title": "Float & Scientific Unread",
                          "time": {
                            "created": 1700000000000.0,
                            "updated": 1.7000000005e12,
                            "busy": 1700000000100.25,
                            "idle": 1700000000500.75,
                            "viewed": 0,
                            "archived": 0.0
                          },
                          "metadata": "non-object-metadata"
                        },
                        {
                          "id": "ses_failed_unseen",
                          "title": "Failed Unseen",
                          "outcome": "failed",
                          "time": {
                            "updated": 1700000000400,
                            "idle": 1700000000400,
                            "viewed": 1700000000300
                          },
                          "metadata": ["unexpected", "array"]
                        },
                        {
                          "id": "ses_marked_unread",
                          "title": "Marked Unread",
                          "outcome": "failed",
                          "time": {
                            "updated": 1700000000300,
                            "idle": 1700000000200,
                            "viewed": 1700000000250
                          },
                          "metadata": {
                            "openchamber": {
                              "markedUnread": { "at": 1.7000000003e12 }
                            }
                          }
                        },
                        {
                          "id": "ses_marked_null_seen",
                          "title": "Seen With Null Mark",
                          "time": {
                            "updated": 1700000000200,
                            "idle": 0,
                            "viewed": 1700000000200
                          },
                          "metadata": {
                            "openchamber": {
                              "markedUnread": null
                            }
                          }
                        },
                        {
                          "id": "ses_subagent",
                          "title": "Subagent Child",
                          "parentID": " ses_float_unread ",
                          "outcome": "failed",
                          "time": {
                            "updated": 1700000000100,
                            "idle": 1700000000100,
                            "viewed": 0
                          },
                          "metadata": {
                            "openchamber": {
                              "markedUnread": { "at": 1700000000100 }
                            }
                          }
                        },
                        {
                          "id": "ses_subagent_blank_parent",
                          "title": "Blank Parent",
                          "parentID": "   ",
                          "time": {
                            "updated": 1700000000050,
                            "idle": 1700000000050
                          },
                          "metadata": true
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        val byId = sessions.associateBy { it.id }
        assertEquals(6, sessions.size)

        val floatUnread = byId.getValue("ses_float_unread")
        assertEquals(1700000000500L, floatUnread.updatedAtMs)
        assertNull(floatUnread.parentId)
        assertTrue(floatUnread.isUnread)
        assertFalse(floatUnread.isUnreadWithError)

        val failedUnseen = byId.getValue("ses_failed_unseen")
        assertTrue(failedUnseen.isUnread)
        assertTrue(failedUnseen.isUnreadWithError)

        val markedUnread = byId.getValue("ses_marked_unread")
        assertTrue(markedUnread.isUnread)
        assertFalse(markedUnread.isUnreadWithError)

        val markedNullSeen = byId.getValue("ses_marked_null_seen")
        assertFalse(markedNullSeen.isUnread)
        assertFalse(markedNullSeen.isUnreadWithError)

        val subagent = byId.getValue("ses_subagent")
        assertEquals("ses_float_unread", subagent.parentId)
        assertFalse(subagent.isUnread)
        assertFalse(subagent.isUnreadWithError)

        val blankParent = byId.getValue("ses_subagent_blank_parent")
        assertNull(blankParent.parentId)
        assertTrue(blankParent.isUnread)
    }

    @Test
    fun `listSessions sorts descending by updatedAtMs and preserves relative order on 0L`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_zero_first", "title": "Zero A" },
                        { "id": "ses_older", "title": "Older", "time": { "updated": 100 } },
                        { "id": "ses_zero_second", "title": "Zero B", "time": { "updated": 0 } },
                        { "id": "ses_newest", "title": "Newest", "time": { "updated": 500 } },
                        { "id": "ses_middle", "title": "Middle", "time": { "updated": 300 } },
                        { "id": "ses_zero_third", "title": "Zero C", "time": {} }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(
            listOf(
                "ses_newest",
                "ses_middle",
                "ses_older",
                "ses_zero_first",
                "ses_zero_second",
                "ses_zero_third",
            ),
            sessions.map { it.id },
        )
    }

    @Test
    fun `listSessions stops when new cursor returns only duplicate raw ids`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_1", "title": "First", "time": { "updated": 200 } }
                      ],
                      "cursor": { "next": "page_2" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_1", "title": "Duplicate", "time": { "updated": 200 } }
                      ],
                      "cursor": { "next": "page_3" }
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(listOf("ses_1"), sessions.map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `listSessions caps pagination at MAX_PAGES`() = runTest {
        for (page in 1..22) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": [
                            { "id": "ses_$page", "title": "Page $page", "time": { "updated": ${1000 - page} } }
                          ],
                          "cursor": { "next": "cur_$page" }
                        }
                        """.trimIndent(),
                    ),
            )
        }

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(20, sessions.size)
        assertEquals(20, server.requestCount)
    }

    @Test
    fun `listSessions throws SessionApiException instead of hanging on mid-body IOException`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"ses_partial","title":"Truncated"""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        var caught: SessionApiException? = null
        try {
            repository.listSessions(serverUrl = baseUrl)
        } catch (e: SessionApiException) {
            caught = e
        }
        assertTrue(caught != null)
    }

    @Test
    fun `listSessions excludes unarchived descendants of archived parent or ancestor across pages`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_live_root", "title": "Live Root", "time": { "updated": 500 } },
                        { "id": "ses_child_of_archived", "parentID": "ses_arch_root", "title": "Stray Child", "time": { "updated": 450 } },
                        { "id": "ses_grandchild_of_archived", "parentID": "ses_child_of_archived", "title": "Stray Grandchild", "time": { "updated": 440 } },
                        { "id": "ses_genuine_orphan", "parentID": "ses_missing_unknown", "title": "Genuine Orphan", "time": { "updated": 300 } }
                      ],
                      "cursor": { "next": "page_2" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "ses_arch_root", "title": "Archived Root", "time": { "updated": 400, "archived": 600 } }
                      ],
                      "cursor": { "next": null }
                    }
                    """.trimIndent(),
                ),
        )

        val repository = SessionRepository(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessions = repository.listSessions(serverUrl = baseUrl)

        assertEquals(listOf("ses_live_root", "ses_genuine_orphan"), sessions.map { it.id })
    }
}
