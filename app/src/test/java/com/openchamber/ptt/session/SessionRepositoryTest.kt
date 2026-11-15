package com.openchamber.ptt.session

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.ServerProfile
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
        assertEquals("/api/session", recorded.path)
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
}
