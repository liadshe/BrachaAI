package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Robolectric: BriefingStore/Briefing use android.util.Log and org.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BriefingSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: BriefingStore
    private lateinit var tokenStore: FakeTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = BriefingStore(File(temp.newFolder("files"), "briefings.json"))
        tokenStore = FakeTokenStore().apply { setTokens("access-1", "refresh-1") }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sync(): BriefingSync {
        val base = server.url("/").toString().trimEnd('/')
        return BriefingSync(
            client = BriefingClient(tokenStore, TokenRefresher(tokenStore, baseUrl = base), baseUrl = base),
            store = store,
        )
    }

    private val payload = """
        [{"contactId":"c1","name":"David Cohen","phone":"+972501234567",
          "lastCall":{"summary":"Promised a quote.","dateTime":"2026-07-28T10:00:00.000Z"},
          "openTasks":[],"openTaskCount":0}]
    """.trimIndent()

    @Test
    fun `a successful sync writes the snapshot`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        assertTrue(sync().syncNow())

        assertEquals("David Cohen", store.readAll().single().name)
    }

    @Test
    fun `a failed sync leaves the previous snapshot intact`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        sync().syncNow()

        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(sync().syncNow())

        assertEquals("David Cohen", store.readAll().single().name)
    }

    @Test
    fun `an empty address book clears the snapshot`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
        sync().syncNow()

        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(sync().syncNow())

        assertTrue(store.readAll().isEmpty())
    }
}
