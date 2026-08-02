package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the 401 -> refresh -> retry path on the upload itself. The rest of the pipeline
 * (FFmpeg, Whisper) is not exercised — only [AudioProcessor.attemptUpload], via the
 * test-only entry point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioProcessorUploadTest {

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

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    private fun processorFor(store: TokenStore): AudioProcessor {
        val app = RuntimeEnvironment.getApplication()
        return AudioProcessor(
            openAiApiKey = "unused-in-this-test",
            cacheDir = tempFolder.newFolder("cache"),
            authStore = store,
            pendingStore = PendingUploadStore(tempFolder.newFolder("pending")),
            callerLookup = CallerLookup(app),
            settingsStore = SettingsStore(app),
            tokenRefresher = TokenRefresher(store, baseUrl()),
            baseUrl = baseUrl()
        )
    }

    private val payload = PendingUpload(
        contactName = "Dana",
        date = "2026-07-31T10:00:00Z",
        callerNumber = "0501234567",
        transcript = "shalom"
    )

    private fun refreshOk(access: String, refresh: String) = MockResponse()
        .setResponseCode(200)
        .setBody(JSONObject().put("token", access).put("refreshToken", refresh).toString())

    @Test
    fun refreshesAndRetriesOnceWhenTheAccessTokenIsExpired() {
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(401))   // upload
        server.enqueue(refreshOk("fresh", "r2"))              // refresh
        server.enqueue(MockResponse().setResponseCode(200))   // retry

        assertEquals(AudioProcessor.UploadResult.Success, processorFor(store).uploadForTest(payload))
        assertEquals(3, server.requestCount)
        assertEquals("fresh", store.getToken())

        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun reportsUnauthenticatedAndClearsWhenTheRefreshTokenIsAlsoDead() {
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "dead")
        server.enqueue(MockResponse().setResponseCode(401))   // upload
        server.enqueue(MockResponse().setResponseCode(401))   // refresh

        assertEquals(
            AudioProcessor.UploadResult.Unauthenticated,
            processorFor(store).uploadForTest(payload)
        )
        assertEquals(1, store.clearCount)
    }

    @Test
    fun doesNotRetryMoreThanOnce() {
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(401))   // upload
        server.enqueue(refreshOk("fresh", "r2"))              // refresh succeeds
        server.enqueue(MockResponse().setResponseCode(401))   // retry still 401

        assertEquals(
            AudioProcessor.UploadResult.Unauthenticated,
            processorFor(store).uploadForTest(payload)
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun keepsTheQueueAliveWhenTheRefreshFailsTransiently() {
        // A 500 on refresh must not wipe the session: the transcript stays queued and the
        // next flush retries. Clearing here would strand it until the next foreground login.
        val store = FakeTokenStore(accessToken = "expired", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(401))   // upload
        server.enqueue(MockResponse().setResponseCode(500))   // refresh

        assertEquals(
            AudioProcessor.UploadResult.Unauthenticated,
            processorFor(store).uploadForTest(payload)
        )
        assertEquals("r1", store.getRefreshToken())
    }

    @Test
    fun doesNotRefreshWhenTheUploadSucceeds() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(AudioProcessor.UploadResult.Success, processorFor(store).uploadForTest(payload))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reportsUnauthenticatedWithoutCallingOutWhenNoTokenIsStored() {
        val store = FakeTokenStore(accessToken = null, refreshToken = null)

        assertEquals(
            AudioProcessor.UploadResult.Unauthenticated,
            processorFor(store).uploadForTest(payload)
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aNonRetryablePayloadIsRejectedWithoutRefreshing() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(422))

        assertEquals(AudioProcessor.UploadResult.Rejected, processorFor(store).uploadForTest(payload))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sendsTheCallLengthInTheUploadBody() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(200))

        processorFor(store).uploadForTest(payload.copy(callLengthSeconds = 272))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(272, body.getInt("callLength"))
    }

    @Test
    fun sendsAnExplicitNullCallLengthWhenTheDurationIsUnknown() {
        val store = FakeTokenStore(accessToken = "good", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(200))

        processorFor(store).uploadForTest(payload.copy(callLengthSeconds = null))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue("callLength should be JSON null, not absent", body.isNull("callLength"))
    }
}
