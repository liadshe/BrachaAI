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
import java.util.concurrent.TimeUnit

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

    @Test
    fun `concurrent triggers are serialized rather than racing on the snapshot`() {
        // BriefingClient and BriefingStore are both final production classes, so there is no
        // seam to fake fetchAll() and observe overlap directly. Instead this drives real
        // concurrency through MockWebServer: two responses, each with a body delay, requested
        // from two real threads against one BriefingSync instance. If syncNow() were not
        // @Synchronized the two HTTP round trips would overlap and the wall-clock time would
        // be roughly one delay; serialized, it is roughly the sum of both. That is a genuine
        // timing observation of the lock, not a re-statement of the annotation.
        val delayMs = 300L
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(payload)
                .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(payload)
                .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
        )

        val briefingSync = sync()
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        val start = System.nanoTime()
        val threads = List(2) { Thread { results.add(briefingSync.syncNow()) } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(2, results.size)
        assertTrue("both concurrent syncs should still succeed", results.all { it })
        assertTrue(
            "expected the two calls to be serialized (~${delayMs * 2}ms), took ${elapsedMs}ms; " +
                "if this is close to ${delayMs}ms, syncNow() is no longer serializing callers",
            elapsedMs >= delayMs * 2 - 100
        )
    }
}
