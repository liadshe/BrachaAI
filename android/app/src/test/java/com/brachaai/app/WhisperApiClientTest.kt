package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Covers only the error mapping. The happy path is exercised on device; what matters here
 * is that a caller can tell a retryable failure from a permanent one, because that decides
 * whether a recording is retried forever or marked stuck.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhisperApiClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        // One test shuts the server down mid-test to simulate being offline, so a second
        // shutdown here must not fail the test.
        try {
            server.shutdown()
        } catch (ignored: Exception) {
        }
    }

    private fun client() = WhisperApiClient("test-key", server.url("/v1").toString().trimEnd('/'))

    private fun audio(): File = tempFolder.newFile("audio.mp3").apply { writeText("not really mp3") }

    @Test
    fun transcribeSurfacesThePermanentStatusCode() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":{"message":"file too large"}}"""))

        val thrown = try {
            client().transcribeAudio(audio())
            null
        } catch (e: WhisperHttpException) {
            e
        }

        assertEquals(413, thrown?.statusCode)
    }

    @Test
    fun transcribeSurfacesARetryableStatusCode() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))

        val thrown = try {
            client().transcribeAudio(audio())
            null
        } catch (e: WhisperHttpException) {
            e
        }

        assertEquals(429, thrown?.statusCode)
    }

    @Test
    fun transcribeReturnsTheText() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"shalom"}"""))

        assertEquals("shalom", client().transcribeAudio(audio()))
    }

    @Test
    fun correctSpellingSurfacesTheStatusCode() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream is down"))

        val thrown = try {
            client().correctSpelling("some transcript")
            null
        } catch (e: WhisperHttpException) {
            e
        }

        assertEquals(500, thrown?.statusCode)
    }

    @Test
    fun aNetworkFailureIsStillAPlainIOExceptionWithNoStatus() {
        // The offline case: nothing answers at all, so there is no status to carry. Callers
        // must treat this as retryable, and they key that off "not a WhisperHttpException".
        server.shutdown()

        val thrown = try {
            client().transcribeAudio(audio())
            null
        } catch (e: Exception) {
            e
        }

        assertTrue("expected a plain IOException, got $thrown", thrown is IOException)
        assertTrue("a connection failure must not masquerade as an HTTP status", thrown !is WhisperHttpException)
    }
}
