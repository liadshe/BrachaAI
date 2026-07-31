package com.brachaai.app

/**
 * Single source for the backend origin.
 *
 * Previously inlined at each call site in [AudioProcessor]; [TokenRefresher] would have made
 * a third copy. Overridable per call site so tests can point at a MockWebServer.
 */
object BackendConfig {
    const val BASE_URL = "http://193.106.55.154:3000"
}
