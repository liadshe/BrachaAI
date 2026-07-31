package com.brachaai.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenRefresherTest {

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

    private fun refresherFor(store: TokenStore) =
        TokenRefresher(store, server.url("/").toString().trimEnd('/'))

    private fun okBody(access: String, refresh: String) = MockResponse()
        .setResponseCode(200)
        .setBody(JSONObject().put("token", access).put("refreshToken", refresh).toString())

    @Test
    fun storesTheRotatedPairAndReturnsTheNewAccessToken() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(okBody("fresh", "r2"))

        assertEquals("fresh", refresherFor(store).refresh("stale"))
        assertEquals("fresh", store.getToken())
        assertEquals("r2", store.getRefreshToken())
    }

    @Test
    fun sendsTheStoredRefreshTokenAsTheNativeClient() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(okBody("fresh", "r2"))

        refresherFor(store).refresh("stale")

        val request = server.takeRequest()
        assertEquals("/api/auth/refresh", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("r1", body.getString("refreshToken"))
        // Must be "native": the web session rotates independently, and claiming to be the
        // web client here would rotate the user's browser session out from under them.
        assertEquals("native", body.getString("client"))
    }

    @Test
    fun returnsNullAndClearsWhenTheRefreshTokenIsRejected() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "dead")
        server.enqueue(MockResponse().setResponseCode(401))

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(1, store.clearCount)
    }

    @Test
    fun clearsOnA403Too() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "dead")
        server.enqueue(MockResponse().setResponseCode(403))

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(1, store.clearCount)
    }

    @Test
    fun keepsTokensOnATransientServerError() {
        // A 500 is not evidence the session is over; wiping here would log the device out
        // because the backend hiccuped, and the pending queue would never drain.
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(MockResponse().setResponseCode(500))

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(0, store.clearCount)
        assertEquals("r1", store.getRefreshToken())
    }

    @Test
    fun keepsTokensWhenTheResponseIsMissingAToken() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(JSONObject().put("token", "fresh").toString())
        )

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals("stale", store.getToken())
        assertEquals("r1", store.getRefreshToken())
    }

    @Test
    fun returnsNullWhenNoRefreshTokenIsStored() {
        val store = FakeTokenStore(accessToken = "stale", refreshToken = null)

        assertNull(refresherFor(store).refresh("stale"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun handsBackTheStoredTokenWhenAnotherCallerAlreadyRefreshed() {
        // Single-flight: the caller's token is stale, but the store already holds a newer
        // one, so there is nothing to do and no request to make.
        val store = FakeTokenStore(accessToken = "already-fresh", refreshToken = "r2")

        assertEquals("already-fresh", refresherFor(store).refresh("stale"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun concurrentRefreshesIssueASingleRequest() {
        // Refresh tokens are single-use. Without the guard, four parallel queue flushes
        // would rotate each other out and the last three would 401 into a logout.
        val store = FakeTokenStore(accessToken = "stale", refreshToken = "r1")
        server.enqueue(okBody("fresh", "r2"))
        val refresher = refresherFor(store)

        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.submit {
                start.await()
                refresher.refresh("stale")
                done.countDown()
            }
        }
        start.countDown()
        done.await(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(1, server.requestCount)
        assertEquals("fresh", store.getToken())
    }
}
