package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric because Briefing/TokenRefresher log through android.util.Log and parse with
// Android's org.json — both throw "not mocked" under AGP's stub jar. Same reason as
// PendingUploadStoreTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BriefingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore

    private val briefingJson = """
        {
          "contactId": "c1",
          "name": "David Cohen",
          "phone": "+972501234567",
          "lastCall": { "summary": "Promised to send price quote.", "dateTime": "2026-07-28T10:00:00.000Z" },
          "openTasks": [ { "id": "t1", "title": "Send contract by Tuesday", "priority": "HIGH" } ],
          "openTaskCount": 1
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore().apply { setTokens("access-1", "refresh-1") }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = BriefingClient(
        tokenStore = tokenStore,
        tokenRefresher = TokenRefresher(tokenStore, baseUrl = server.url("/").toString().trimEnd('/')),
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `fetchAll parses the briefing list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$briefingJson]"))

        val result = client().fetchAll()

        assertEquals(1, result?.size)
        val briefing = result!!.single()
        assertEquals("David Cohen", briefing.name)
        assertEquals("Promised to send price quote.", briefing.lastCallSummary)
        assertEquals(1, briefing.openTaskCount)
        assertEquals("Send contract by Tuesday", briefing.openTasks.single().title)
    }

    @Test
    fun `fetchAll sends the stored access token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        client().fetchAll()

        val request = server.takeRequest()
        assertEquals("/api/briefings", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
    }

    @Test
    fun `fetchAll distinguishes an empty address book from a failure`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val result = client().fetchAll()

        assertNotNull("empty list, not null", result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `fetchAll refreshes the token once on 401 and retries`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"token":"access-2","refreshToken":"refresh-2"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[$briefingJson]"))

        val result = client().fetchAll()

        assertEquals(1, result?.size)
        server.takeRequest()
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `fetchAll gives up rather than looping when the refreshed token is also rejected`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"token":"access-2","refreshToken":"refresh-2"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        assertNull(client().fetchAll())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `fetchAll returns null when there is no token to send`() {
        tokenStore.clear()

        assertNull(client().fetchAll())
        assertEquals("no request attempted", 0, server.requestCount)
    }

    @Test
    fun `fetchAll returns null on a server error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(client().fetchAll())
    }

    @Test
    fun `fetchAll returns null on an unparseable body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        assertNull(client().fetchAll())
    }

    @Test
    fun `fetchOne requests the contact and parses a single briefing`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(briefingJson))

        val result = client().fetchOne("c1")

        assertEquals("David Cohen", result?.name)
        assertEquals("/api/briefings/c1", server.takeRequest().path)
    }

    @Test
    fun `fetchOne returns null when the contact is gone`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(client().fetchOne("c1"))
    }
}
