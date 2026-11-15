package com.openchamber.ptt.auth

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var authClient: AuthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        authClient = AuthClient(httpClient = OpenChamberHttp.createClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authenticate posts expected payload to auth session and returns oc_client token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"authenticated":true,"token":"oc_client_abc123xyz"}"""),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val token = authClient.authenticate(
            serverUrl = "$baseUrl/",
            password = "secret-password",
            deviceName = "Pixel 9 Pro",
        )

        assertEquals("oc_client_abc123xyz", token)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/session", recorded.path)
        assertTrue(
            recorded.getHeader("Content-Type")?.startsWith("application/json") == true,
        )

        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("secret-password", bodyJson["password"]?.jsonPrimitive?.content)
        assertEquals(true, bodyJson["trustDevice"]?.jsonPrimitive?.boolean)
        assertEquals(true, bodyJson["issueClientToken"]?.jsonPrimitive?.boolean)
        assertEquals("OpenChamber PTT", bodyJson["clientLabel"]?.jsonPrimitive?.content)
        assertEquals("mobile", bodyJson["clientKind"]?.jsonPrimitive?.content)
        assertEquals("android", bodyJson["devicePlatform"]?.jsonPrimitive?.content)
        assertEquals("Pixel 9 Pro", bodyJson["deviceName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `authenticate throws AuthException with status code on unauthorized response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Invalid password"}"""),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        try {
            authClient.authenticate(
                serverUrl = baseUrl,
                password = "wrong-password",
                deviceName = "Pixel 9 Pro",
            )
            fail("Expected AuthException")
        } catch (e: AuthException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.message?.contains("Invalid password") == true)
        }
    }
}
