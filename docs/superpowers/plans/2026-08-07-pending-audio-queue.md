# Pending Audio Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retry recordings whose transcription failed (typically: no internet), and never delete a recording until its call has actually landed.

**Architecture:** Split "make one attempt" (`AudioProcessor`, which stops throwing and returns a `ProcessOutcome`) from "decide whether to attempt again" (`PendingAudioQueue`, which owns the retry policy). A `RecordingIndex` snapshot in app-private storage records which recordings are done or stuck; recordings themselves are never moved or copied. A `NetworkWatcher` sweeps the queue when a validated network appears.

**Tech Stack:** Kotlin, Android (minSdk 26, compileSdk 36), coroutines, OkHttp, `org.json`. Tests: JUnit4 + Robolectric 4.14.1 + `TemporaryFolder` + MockWebServer. No mocking framework anywhere in this repo — use real collaborators or hand-written fakes.

**Spec:** `docs/superpowers/specs/2026-08-07-pending-audio-queue-design.md`

## Global Constraints

- **Gradle cannot run on the development machine.** Every `./gradlew` invocation fails with a loopback error. Attempt each test-run step anyway; if it fails with the loopback error, record that and move on — **never claim a Kotlin test passed based on local execution.** Verification happens in CI.
- All Gradle commands run from the `android/` directory.
- No new dependencies. Coroutine tests use `kotlinx.coroutines.runBlocking` (available via the existing `kotlinx-coroutines-android`), not `kotlinx-coroutines-test`.
- No mocking framework. Fakes are hand-written classes in the test file.
- `minSdk = 26`, so `java.nio.file.Files` is available and is the correct tool for atomic replace (`BriefingStore` already uses it).
- Every new class goes in package `com.brachaai.app`, under `android/app/src/main/java/com/brachaai/app/`.
- Never introduce a default for call direction — `null` means unknown all the way through (see `android/CLAUDE.md`).
- `app/src/main/assets/www/` is a build artifact. This plan does not touch the frontend, so nothing there changes.

## File Structure

| File | Responsibility |
|---|---|
| `ProcessOutcome.kt` *(new)* | The four terminal states of one processing attempt, plus the `RecordingProcessor` seam that lets the queue be tested without FFmpeg or network. |
| `RecordingIndex.kt` *(new)* | Dumb durable key-value store: recording filename → `RecordingState`. Atomic snapshot replace. No policy. |
| `PendingAudioQueue.kt` *(new)* | All retry policy: what needs processing, serialization, attempt counting, the give-up rule. |
| `NetworkWatcher.kt` *(new)* | `NetworkDebouncer` (pure, testable) + a thin `ConnectivityManager` wrapper. |
| `WhisperApiClient.kt` *(modified)* | Throws `WhisperHttpException` carrying the HTTP status; gains an injectable base URL for tests. |
| `AudioProcessor.kt` *(modified)* | Implements `RecordingProcessor`. Returns outcomes instead of throwing. Owns the single deletion gate. |
| `CallMonitorService.kt` *(modified)* | Wiring, network watcher lifecycle, stuck notification. |
| `AndroidManifest.xml` *(modified)* | `ACCESS_NETWORK_STATE`. |

---

### Task 1: RecordingIndex

Durable record of which recordings are finished. Pure file I/O and `org.json` — fully testable on its own.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/RecordingIndex.kt`
- Test: `android/app/src/test/java/com/brachaai/app/RecordingIndexTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class RecordingState(val attempts: Int = 0, val done: Boolean = false, val stuck: Boolean = false, val lastError: String? = null)`
  - `class RecordingIndex(file: File)` with `fun stateOf(name: String): RecordingState`, `fun put(name: String, state: RecordingState)`, `fun pruneTo(existingNames: Set<String>)`, `fun allNames(): Set<String>`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/RecordingIndexTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [RecordingIndex] is plain file I/O. Robolectric is only needed because it logs through
 * `android.util.Log` and parses with Android's `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newIndexFile() = File(tempFolder.newFolder("index"), "recordings.json")

    @Test
    fun unknownRecordingHasTheDefaultState() {
        val index = RecordingIndex(newIndexFile())

        val state = index.stateOf("never-seen.m4a")

        assertEquals(0, state.attempts)
        assertFalse(state.done)
        assertFalse(state.stuck)
    }

    @Test
    fun statePersistsAcrossInstances() {
        val file = newIndexFile()
        RecordingIndex(file).put("Dana_250101_120000.m4a", RecordingState(attempts = 3, lastError = "timeout"))

        val reloaded = RecordingIndex(file).stateOf("Dana_250101_120000.m4a")

        assertEquals(3, reloaded.attempts)
        assertEquals("timeout", reloaded.lastError)
        assertFalse(reloaded.done)
    }

    @Test
    fun doneAndStuckFlagsRoundTrip() {
        val file = newIndexFile()
        val index = RecordingIndex(file)
        index.put("done.m4a", RecordingState(done = true))
        index.put("stuck.m4a", RecordingState(attempts = 5, stuck = true, lastError = "file too large"))

        val reloaded = RecordingIndex(file)

        assertTrue(reloaded.stateOf("done.m4a").done)
        assertTrue(reloaded.stateOf("stuck.m4a").stuck)
        assertEquals("file too large", reloaded.stateOf("stuck.m4a").lastError)
    }

    /**
     * The whole point of the atomic replace: a half-written snapshot must not be able to
     * destroy the previous one. A corrupt snapshot degrades to "nothing is done", which is
     * the safe direction — the worst case is re-transcribing a call, never deleting one.
     */
    @Test
    fun corruptSnapshotDegradesToEmptyRatherThanThrowing() {
        val file = newIndexFile()
        RecordingIndex(file).put("done.m4a", RecordingState(done = true))
        file.writeText("{\"done.m4a\":{\"done\":tr")

        val reloaded = RecordingIndex(file)

        assertFalse("a corrupt snapshot must never report a recording as done", reloaded.stateOf("done.m4a").done)
        assertTrue(reloaded.allNames().isEmpty())
    }

    @Test
    fun pruneDropsEntriesWhoseFileIsGoneAndKeepsTheRest() {
        val file = newIndexFile()
        val index = RecordingIndex(file)
        index.put("still-here.m4a", RecordingState(done = true))
        index.put("deleted-by-user.m4a", RecordingState(stuck = true))

        index.pruneTo(setOf("still-here.m4a"))

        assertEquals(setOf("still-here.m4a"), index.allNames())
        assertTrue(RecordingIndex(file).stateOf("still-here.m4a").done)
        assertFalse(RecordingIndex(file).stateOf("deleted-by-user.m4a").stuck)
    }

    @Test
    fun pruneWritesNothingWhenThereIsNothingToDrop() {
        val file = newIndexFile()
        val index = RecordingIndex(file)
        index.put("a.m4a", RecordingState(done = true))
        val stamp = file.lastModified()

        index.pruneTo(setOf("a.m4a"))

        assertEquals("an unchanged index must not be rewritten", stamp, file.lastModified())
    }

    @Test
    fun survivesAnUnwritableIndexPathWithoutThrowing() {
        // The parent is a regular file, so every write must fail. Losing the index is
        // survivable (recordings get re-processed); throwing here would kill the pipeline.
        val blocker = tempFolder.newFile("not-a-directory")
        val index = RecordingIndex(File(blocker, "recordings.json"))

        index.put("a.m4a", RecordingState(done = true))

        assertTrue(index.stateOf("a.m4a").done)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.RecordingIndexTest"
```

Expected: compilation failure — `Unresolved reference: RecordingIndex`.
If instead this fails with the Gradle loopback error, note it and continue; CI is the verification gate.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/RecordingIndex.kt`:

```kotlin
package com.brachaai.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the pipeline knows about one recording.
 *
 * The absence of an entry means "never attempted", so a recording that processes cleanly
 * on the first try under delete-after-processing costs no index write at all.
 */
data class RecordingState(
    val attempts: Int = 0,
    /** The call reached the backend, or was deliberately skipped. Never processed again. */
    val done: Boolean = false,
    /** Gave up. The recording is kept indefinitely but must never be retried. */
    val stuck: Boolean = false,
    val lastError: String? = null
)

/**
 * Durable record of which recordings are finished, so a sweep can tell a call that still
 * needs transcribing from one that is already handled.
 *
 * Deliberately dumb: it stores states and nothing else. The retry policy — how many
 * attempts are allowed, what counts as giving up — lives in [PendingAudioQueue], so that
 * policy can be tested without touching the disk and this can be tested without a policy.
 *
 * A single JSON snapshot written temp-then-move, matching `BriefingStore.replaceAll`: a
 * failed or half-finished write leaves the previous snapshot exactly as it was.
 *
 * Every failure mode here degrades to "nothing is known", which is the safe direction. The
 * cost of forgetting is re-transcribing a call; the cost of wrongly remembering would be
 * deleting a recording that never landed.
 */
class RecordingIndex(private val file: File) {

    private val lock = Any()
    private val states: MutableMap<String, RecordingState> = load()

    fun stateOf(name: String): RecordingState = synchronized(lock) {
        states[name] ?: RecordingState()
    }

    fun allNames(): Set<String> = synchronized(lock) { states.keys.toSet() }

    fun put(name: String, state: RecordingState) {
        synchronized(lock) {
            states[name] = state
            persist()
        }
    }

    /**
     * Drops every entry whose recording is no longer on disk, so the index cannot outgrow
     * the watch directory. Covers both the ordinary case (delete-after-processing removed
     * the file) and the user clearing the folder by hand.
     *
     * Writes nothing when there is nothing to drop — a sweep over an unchanged folder is
     * the common case and should not touch storage.
     */
    fun pruneTo(existingNames: Set<String>) {
        synchronized(lock) {
            val gone = states.keys.filterNot { it in existingNames }
            if (gone.isEmpty()) return
            gone.forEach { states.remove(it) }
            Log.d(TAG, "Pruned ${gone.size} index entr(ies) whose recording is gone")
            persist()
        }
    }

    private fun load(): MutableMap<String, RecordingState> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText())
            val parsed = mutableMapOf<String, RecordingState>()
            json.keys().forEach { name ->
                val entry = json.getJSONObject(name)
                parsed[name] = RecordingState(
                    attempts = entry.optInt("attempts", 0),
                    done = entry.optBoolean("done", false),
                    stuck = entry.optBoolean("stuck", false),
                    lastError = if (entry.isNull("lastError")) null else entry.optString("lastError", null)
                )
            }
            parsed
        } catch (e: Exception) {
            // Forgetting everything means recordings get re-processed, which is wasteful but
            // safe. Reporting stale or half-parsed state as "done" would strand a call.
            Log.e(TAG, "Unreadable recording index; treating every recording as unprocessed", e)
            mutableMapOf()
        }
    }

    /** Caller must hold [lock]. Never throws: losing the index must not kill the pipeline. */
    private fun persist() {
        val json = JSONObject()
        states.forEach { (name, state) ->
            json.put(
                name,
                JSONObject().apply {
                    put("attempts", state.attempts)
                    put("done", state.done)
                    put("stuck", state.stuck)
                    put("lastError", state.lastError ?: JSONObject.NULL)
                }
            )
        }

        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.toString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist recording index; state is in memory only this run", e)
            try {
                if (temp.exists()) temp.delete()
            } catch (ignored: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "RecordingIndex"

        /** Standard location, under app-private storage alongside the pending upload queue. */
        fun default(filesDir: File) = RecordingIndex(File(filesDir, "recordings-index.json"))
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.RecordingIndexTest"
```

Expected: 7 tests pass. If the loopback error appears instead, do not claim a pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/RecordingIndex.kt android/app/src/test/java/com/brachaai/app/RecordingIndexTest.kt && git commit -m "Add RecordingIndex, a durable record of processed recordings"
```

---

### Task 2: WhisperApiClient carries the HTTP status

Right now every non-2xx becomes `IOException("Unexpected code $response")`, which throws away the status code. Without it, "429, try again in a minute" is indistinguishable from "413, this file will never fit". The give-up rule in Task 3 depends on this.

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/WhisperApiClient.kt`
- Test: `android/app/src/test/java/com/brachaai/app/WhisperApiClientTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class WhisperHttpException(val statusCode: Int, message: String) : IOException(message)`
  - `WhisperApiClient(apiKey: String, baseUrl: String = "https://api.openai.com/v1")` — the second parameter is new and defaulted, so existing construction sites keep compiling.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/WhisperApiClientTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.WhisperApiClientTest"
```

Expected: compilation failure — `Unresolved reference: WhisperHttpException`, and `WhisperApiClient` taking only one argument.

- [ ] **Step 3: Write the implementation**

In `android/app/src/main/java/com/brachaai/app/WhisperApiClient.kt`, add the exception type above the class and add the `baseUrl` parameter:

```kotlin
/**
 * A non-2xx answer from OpenAI, carrying the status so the caller can tell a transient
 * failure (429, 5xx) from a permanent one (400, 413, 415, 422).
 *
 * A connection-level failure — the offline case — is deliberately NOT this type: there was
 * no HTTP response, so there is no status, and callers key "retryable" off exactly that.
 */
class WhisperHttpException(val statusCode: Int, message: String) : IOException(message)

class WhisperApiClient(
    private val apiKey: String,
    /** Injectable so the error mapping is testable against MockWebServer. */
    private val baseUrl: String = "https://api.openai.com/v1"
) {
```

Replace the URL in `transcribeAudio`:

```kotlin
            .url("$baseUrl/audio/transcriptions")
```

Replace the throw in `transcribeAudio`:

```kotlin
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "OpenAI Transcription Error ${response.code}: $errorBody")
                    throw WhisperHttpException(response.code, "Whisper transcription failed with HTTP ${response.code}")
                }
```

Replace the URL in `correctSpelling`:

```kotlin
            .url("$baseUrl/chat/completions")
```

Replace the throw in `correctSpelling`:

```kotlin
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "OpenAI GPT Error ${response.code}: $errorBody")
                    throw WhisperHttpException(response.code, "Spelling correction failed with HTTP ${response.code}")
                }
```

Leave both `catch (e: Exception) { Log.e(...); throw e }` blocks exactly as they are — they rethrow, so a `WhisperHttpException` passes through unchanged and a connection failure stays a plain `IOException`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.WhisperApiClientTest"
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/WhisperApiClient.kt android/app/src/test/java/com/brachaai/app/WhisperApiClientTest.kt && git commit -m "Carry the OpenAI HTTP status out of WhisperApiClient"
```

---

### Task 3: AudioProcessor returns outcomes and owns the deletion gate

`processAndSendToBackend` currently throws on any failure and deletes on a path tangled with `transcriptIsDurable`. It becomes a `RecordingProcessor` that returns a `ProcessOutcome` and never throws, with deletion behind one gate.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/ProcessOutcome.kt`
- Modify: `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt`
- Test: `android/app/src/test/java/com/brachaai/app/AudioProcessorTest.kt` (extend)

**Interfaces:**
- Consumes: `WhisperHttpException(statusCode)` from Task 2.
- Produces:
  - `sealed class ProcessOutcome` with `object Completed`, `object Skipped`, `data class RetryLater(val reason: String)`, `data class GiveUp(val reason: String)`
  - `interface RecordingProcessor { suspend fun process(audioFile: File): ProcessOutcome }`
  - `AudioProcessor : RecordingProcessor`, plus `internal data class PipelineResult(val outcome: ProcessOutcome, val mayDeleteRecording: Boolean)` and `internal fun applyDeletion(result: PipelineResult, audioFile: File)`

**Design note the implementer must not "simplify" away:** `mayDeleteRecording` is a *separate* flag from the outcome, and this is deliberate. When the backend is unreachable but `PendingUploadStore.enqueue` succeeds, the outcome is `Completed` — the transcript exists durably, so re-transcribing would cost money and enqueue a **duplicate** call. But `queuedTranscriptIsDurable` may still say the recording must be kept (queue at capacity, or no token was ever stored). Those are two different questions and collapsing them reintroduces either duplicate uploads or destroyed recordings.

- [ ] **Step 1: Write the failing test**

Append to `android/app/src/test/java/com/brachaai/app/AudioProcessorTest.kt`, inside the existing class, before the closing brace:

```kotlin
    // ------------------------------------------------------------ applyDeletion
    //
    // The single gate between a failed call and a destroyed recording. Every row of the
    // spec's outcome table is asserted here, because this is the only place that decides.

    private fun recordingIn(name: String): File =
        File(tempFolder.newFolder(name), "call.m4a").apply { parentFile?.mkdirs(); writeText("audio") }

    private fun processorWithDeleteFlag(delete: Boolean): AudioProcessor {
        val settingsStore = SettingsStore(RuntimeEnvironment.getApplication())
        settingsStore.deleteAudioAfterProcessing = delete
        return newProcessor(settingsStore)
    }

    @Test
    fun completedAndDeletableHonoursTheDeleteSetting() {
        val recording = recordingIn("completed-on")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = true), recording
        )
        assertFalse("a landed call with the setting on should be deleted", recording.exists())
    }

    @Test
    fun completedButSettingOffKeepsTheRecording() {
        val recording = recordingIn("completed-off")
        processorWithDeleteFlag(false).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = true), recording
        )
        assertTrue(recording.exists())
    }

    @Test
    fun skippedShortRecordingHonoursTheDeleteSetting() {
        val recording = recordingIn("skipped")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Skipped, mayDeleteRecording = true), recording
        )
        assertFalse("a deliberately skipped recording is finished, not failed", recording.exists())
    }

    @Test
    fun completedButNotDurablyQueuedKeepsTheRecordingEvenWithTheSettingOn() {
        // The transcript is queued (so it must not be re-transcribed) but the queue is not a
        // trustworthy home for it, so the recording is the only real copy.
        val recording = recordingIn("completed-not-durable")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = false), recording
        )
        assertTrue(recording.exists())
    }

    @Test
    fun retryLaterNeverDeletesEvenWithTheSettingOn() {
        val recording = recordingIn("retry")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.RetryLater("no internet"), mayDeleteRecording = false),
            recording
        )
        assertTrue("an unprocessed recording must survive regardless of the setting", recording.exists())
    }

    @Test
    fun giveUpNeverDeletesEvenWithTheSettingOn() {
        val recording = recordingIn("giveup")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.GiveUp("backend rejected it"), mayDeleteRecording = false),
            recording
        )
        assertTrue("giving up is not permission to destroy the only copy", recording.exists())
    }

    @Test
    fun aFailureOutcomeIsNeverDeletedEvenIfTheFlagSaysItMayBe() {
        // Belt and braces: the outcome alone must be able to veto deletion, so a future
        // caller that miscomputes the flag cannot destroy a recording.
        val recording = recordingIn("veto")
        processorWithDeleteFlag(true).applyDeletion(
            AudioProcessor.PipelineResult(ProcessOutcome.RetryLater("bad flag"), mayDeleteRecording = true),
            recording
        )
        assertTrue(recording.exists())
    }

    // ------------------------------------------------------- whisper status mapping

    @Test
    fun permanentWhisperStatusesGiveUp() {
        val processor = processorWithDeleteFlag(true)
        listOf(400, 413, 415, 422).forEach { code ->
            val outcome = processor.outcomeForWhisperFailure(WhisperHttpException(code, "boom"))
            assertTrue("HTTP $code should be permanent, got $outcome", outcome is ProcessOutcome.GiveUp)
        }
    }

    @Test
    fun transientWhisperStatusesRetry() {
        val processor = processorWithDeleteFlag(true)
        // 401/403 stay retryable on purpose: a bad or expired API key is fixed by a new
        // build, and marking every recording stuck on the first attempt would strand them all.
        listOf(401, 403, 429, 500, 502, 503).forEach { code ->
            val outcome = processor.outcomeForWhisperFailure(WhisperHttpException(code, "boom"))
            assertTrue("HTTP $code should be retryable, got $outcome", outcome is ProcessOutcome.RetryLater)
        }
    }

    @Test
    fun aConnectionFailureRetries() {
        val processor = processorWithDeleteFlag(true)

        val outcome = processor.outcomeForWhisperFailure(java.io.IOException("Unable to resolve host"))

        assertTrue("being offline is the whole reason this queue exists", outcome is ProcessOutcome.RetryLater)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.AudioProcessorTest"
```

Expected: compilation failure — `Unresolved reference: ProcessOutcome`, `PipelineResult`, `applyDeletion`, `outcomeForWhisperFailure`.

- [ ] **Step 3: Create `ProcessOutcome.kt`**

```kotlin
package com.brachaai.app

import java.io.File

/**
 * How one processing attempt ended.
 *
 * The split that matters is terminal-vs-retryable, because it decides two separate things:
 * whether the recording may be deleted, and whether anything should ever look at it again.
 */
sealed class ProcessOutcome {
    /**
     * The call landed: the backend accepted it, or its transcript is durably queued for
     * delivery. Either way the audio has no further use — re-transcribing it would cost
     * money and, on the queued path, upload the same call twice.
     */
    object Completed : ProcessOutcome()

    /** Deliberately not processed (under five seconds). Terminal, and not a failure. */
    object Skipped : ProcessOutcome()

    /** Transient failure — no internet, a timeout, a rate limit. Try again later. */
    data class RetryLater(val reason: String) : ProcessOutcome()

    /** Permanent failure. The recording is kept forever, but must never be retried. */
    data class GiveUp(val reason: String) : ProcessOutcome()
}

/**
 * One attempt at one recording.
 *
 * Exists so [PendingAudioQueue] — which holds all the retry policy — can be unit-tested
 * against a hand-written fake, without FFmpeg, OpenAI, or a network.
 */
interface RecordingProcessor {
    suspend fun process(audioFile: File): ProcessOutcome
}
```

- [ ] **Step 4: Rewrite `AudioProcessor.processAndSendToBackend`**

In `android/app/src/main/java/com/brachaai/app/AudioProcessor.kt`:

Make the class implement the interface. The constructor parameter list ends with
`    private val callDirectionStore: CallDirectionStore? = null` followed by `) {` — replace
that closing line with:

```kotlin
) : RecordingProcessor {
```

Replace the whole `suspend fun processAndSendToBackend(audioFile: File) { ... }` function with:

```kotlin
    /**
     * Result of one attempt, plus the separate question of whether the recording may go.
     *
     * These are two different questions and must not be collapsed. When the backend is
     * unreachable but the transcript is durably queued, the outcome is [ProcessOutcome.Completed]
     * — re-transcribing would spend money and enqueue the same call a second time — while
     * `mayDeleteRecording` can still be false because `queuedTranscriptIsDurable` says the
     * queue is not a trustworthy home for it.
     */
    internal data class PipelineResult(
        val outcome: ProcessOutcome,
        val mayDeleteRecording: Boolean
    )

    /**
     * Runs the full pipeline for one recording and reports how it ended.
     *
     * Never throws. Callers used to have to catch, and a throw meant "recording kept but
     * forgotten forever" — the bug this whole queue exists to fix.
     */
    override suspend fun process(audioFile: File): ProcessOutcome = withContext(Dispatchers.IO) {
        val result = try {
            runPipeline(audioFile)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure processing ${audioFile.name}", e)
            PipelineResult(ProcessOutcome.RetryLater(e.message ?: e.javaClass.simpleName), false)
        }
        applyDeletion(result, audioFile)
        result.outcome
    }

    /**
     * The single gate between a call that failed and a recording that is destroyed.
     *
     * Deletion requires *both* a terminal-success outcome and permission from the caller.
     * The outcome check is not redundant with the flag: it means a future caller that
     * miscomputes `mayDeleteRecording` still cannot destroy an unprocessed recording.
     *
     * `internal` so the unit tests can drive every row of the outcome table directly.
     */
    internal fun applyDeletion(result: PipelineResult, audioFile: File) {
        val terminalSuccess =
            result.outcome is ProcessOutcome.Completed || result.outcome is ProcessOutcome.Skipped
        if (terminalSuccess && result.mayDeleteRecording) {
            deleteOriginalIfEnabled(audioFile)
        } else {
            println("Keeping ${audioFile.name}; outcome=${result.outcome}")
        }
    }

    /**
     * Maps a transcription failure onto an outcome.
     *
     * A [WhisperHttpException] with a permanently-invalid status means this file will never
     * transcribe, no matter how good the connection gets. Anything else — a rate limit, a
     * 5xx, or a connection failure with no status at all — is worth another attempt.
     *
     * 401/403 are deliberately retryable: a bad or expired API key is fixed by shipping a
     * new build, and treating it as permanent would mark every recording stuck on its first
     * attempt with no way back.
     *
     * `internal` for the unit tests.
     */
    internal fun outcomeForWhisperFailure(e: Exception): ProcessOutcome {
        val status = (e as? WhisperHttpException)?.statusCode
        val reason = "transcription failed${status?.let { " (HTTP $it)" } ?: ""}: ${e.message}"
        return if (status != null && status in PERMANENT_TRANSCRIPTION_CODES) {
            ProcessOutcome.GiveUp(reason)
        } else {
            ProcessOutcome.RetryLater(reason)
        }
    }

    // suspend so the Success branch can call flushPending() directly. Wrapping it in
    // runBlocking instead would block an IO dispatcher thread for a whole queue drain.
    private suspend fun runPipeline(audioFile: File): PipelineResult {
        // Recordings under five seconds are deliberately not calls worth transcribing. That
        // is a terminal decision, not a failure, so the recording may go.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs in 1..4999) {
                println("Skipping ${audioFile.name}: duration too short ($durationMs ms)")
                return PipelineResult(ProcessOutcome.Skipped, mayDeleteRecording = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Duration check failed for ${audioFile.name}, proceeding anyway", e)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

        var mp3File: File? = null
        try {
            println("1. Starting processing for: ${audioFile.name}")

            val parsedInfo = parseFilename(audioFile.name)
            println("2. Parsed Info - Name: ${parsedInfo.contactName}, Date: ${parsedInfo.date}")

            println("3. Converting audio to true MP3 format...")
            val converted = convertToMp3(audioFile)
            if (converted == null) {
                // FFmpeg can fail on a partially-flushed recording that is fine minutes
                // later, so this is retryable rather than terminal.
                Log.e(TAG, "Audio conversion failed for ${audioFile.name}")
                return PipelineResult(ProcessOutcome.RetryLater("audio conversion failed"), false)
            }
            mp3File = converted

            println("4. Uploading MP3 to Whisper...")
            val correctedTranscript = try {
                val transcriptText = whisperClient.transcribeAudio(converted)
                println("5. Whisper Transcript: $transcriptText")
                println("6. Correcting spelling and grammar...")
                whisperClient.correctSpelling(transcriptText)
            } catch (e: Exception) {
                val outcome = outcomeForWhisperFailure(e)
                Log.e(TAG, "Transcription failed for ${audioFile.name}: $outcome", e)
                return PipelineResult(outcome, false)
            }
            println("7. Corrected Transcript: $correctedTranscript")

            if (correctedTranscript.isBlank()) {
                // A GPT-4o refusal or filtered completion returns "" without throwing.
                // Uploading it would earn a non-retryable 400, so stop here. Retryable
                // because the same audio often corrects fine on a later attempt; the
                // attempt counter stops it from running forever.
                Log.e(TAG, "Corrected transcript is blank for ${audioFile.name}; not uploading or queuing")
                return PipelineResult(ProcessOutcome.RetryLater("corrected transcript came back blank"), false)
            }

            val callStartMillis = parsedInfo.toEpochMillis()
            val callLogMatch = callStartMillis?.let { callerLookup.findNear(it) } ?: CallLogMatch.NONE
            val callerNumber = callLogMatch.number

            // Two sources, in order of authority: the call log wins whenever it is readable,
            // CallDirectionStore is the fallback for when READ_CALL_LOG was denied. When
            // neither knows, this stays null all the way to the UI. Do not add a default.
            val callType = callLogMatch.callType
                ?: callStartMillis?.let { callDirectionStore?.directionNear(it) }
            println("8. Caller number: ${callerNumber ?: "unavailable"}, type: ${callType ?: "unknown"}")

            val callLengthSeconds = callLogMatch.durationSeconds ?: audioDuration.secondsOf(audioFile)
            println("8b. Call length: ${callLengthSeconds?.let { "${it}s" } ?: "unknown"}")

            val payload = PendingUpload(
                contactName = parsedInfo.contactName,
                date = "${parsedInfo.date}_${parsedInfo.time}",
                callerNumber = callerNumber,
                transcript = correctedTranscript,
                callLengthSeconds = callLengthSeconds,
                callType = callType
            )

            println("9. Sending data to backend...")
            return when (val uploadResult = attemptUpload(payload)) {
                is UploadResult.Success -> {
                    println("SUCCESS! Data sent to backend")
                    // Network and token both just proved good — the best possible moment to
                    // also drain the transcript queue.
                    flushPending()
                    PipelineResult(ProcessOutcome.Completed, mayDeleteRecording = true)
                }
                is UploadResult.Rejected -> {
                    // The call never reached the backend and never will, so the recording is
                    // the only copy of it. Kept forever, never retried.
                    Log.e(TAG, "Backend permanently rejected upload for ${audioFile.name}; keeping the recording")
                    PipelineResult(ProcessOutcome.GiveUp("backend rejected the payload"), false)
                }
                is UploadResult.Unauthenticated, is UploadResult.Transient -> {
                    println("Upload failed; queueing transcript for retry")
                    val queued = pendingStore.enqueue(payload)
                    if (!queued) {
                        // Nothing was persisted anywhere, so the audio is still the only
                        // copy — and re-transcribing later cannot produce a duplicate.
                        PipelineResult(ProcessOutcome.RetryLater("transcript could not be queued"), false)
                    } else {
                        // The transcript IS durably queued, so this recording is finished as
                        // far as transcription goes — hence Completed. Whether the recording
                        // may also be deleted is the separate, stricter question below.
                        PipelineResult(
                            ProcessOutcome.Completed,
                            mayDeleteRecording = queuedTranscriptIsDurable(
                                enqueued = true,
                                wasUnauthenticated = uploadResult is UploadResult.Unauthenticated
                            )
                        )
                    }
                }
            }
        } finally {
            val temp = mp3File
            try {
                if (temp != null && temp.exists() && !temp.delete()) {
                    Log.w(TAG, "Could not delete temp MP3 ${temp.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete temp MP3 ${temp?.name}", e)
            }
        }
    }
```

Add to the `companion object`, below `NON_RETRYABLE_CODES`:

```kotlin
        /**
         * OpenAI statuses that mean this particular file will never transcribe — a better
         * connection cannot help. 429 and 5xx are absent on purpose: they are about load,
         * not about the file. So are 401/403 — see [outcomeForWhisperFailure].
         */
        private val PERMANENT_TRANSCRIPTION_CODES = setOf(400, 413, 415, 422)
```

Leave `queuedTranscriptIsDurable`, `deleteOriginalIfEnabled`, `flushPending`, `attemptUpload`, `postCall`, `convertToMp3` and `NON_RETRYABLE_CODES` unchanged.

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.AudioProcessorTest"
```

Expected: the six pre-existing tests plus the ten new ones pass. `CallMonitorService` will not compile yet — it still calls `processAndSendToBackend`. That is fixed in Task 6; if the module fails to compile, temporarily change that one call site to `audioProcessor.process(file)` to run the tests, and leave it.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/ProcessOutcome.kt android/app/src/main/java/com/brachaai/app/AudioProcessor.kt android/app/src/test/java/com/brachaai/app/AudioProcessorTest.kt android/app/src/main/java/com/brachaai/app/CallMonitorService.kt && git commit -m "Return outcomes from AudioProcessor instead of throwing"
```

---

### Task 4: PendingAudioQueue

All the retry policy in one place: what needs processing, one-at-a-time serialization, attempt counting, and the give-up rule.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/PendingAudioQueue.kt`
- Test: `android/app/src/test/java/com/brachaai/app/PendingAudioQueueTest.kt`

**Interfaces:**
- Consumes: `RecordingProcessor`, `ProcessOutcome` (Task 3); `RecordingIndex`, `RecordingState` (Task 1).
- Produces: `class PendingAudioQueue(watchDir: File, index: RecordingIndex, processor: RecordingProcessor, onStuck: (String, String) -> Unit, nowMs: () -> Long)` with `suspend fun processNow(file: File)`, `suspend fun sweep()`, and `companion object { const val MAX_ATTEMPTS = 5; const val MIN_AGE_MS = 10_000L }`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/PendingAudioQueueTest.kt`:

```kotlin
package com.brachaai.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Drives the retry policy against a hand-written [RecordingProcessor] — no FFmpeg, no
 * network, no mocking framework, matching how the rest of this module is tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingAudioQueueTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Returns a scripted outcome and records every file it was handed. */
    private class FakeProcessor(var outcome: ProcessOutcome) : RecordingProcessor {
        val seen = mutableListOf<String>()
        override suspend fun process(audioFile: File): ProcessOutcome {
            seen += audioFile.name
            return outcome
        }
    }

    private lateinit var watchDir: File
    private lateinit var index: RecordingIndex
    private val stuckNotifications = mutableListOf<Pair<String, String>>()

    // Every recording is created well in the past, so the "still being written" guard never
    // interferes; the one test that cares about that guard overrides it explicitly.
    private fun recording(name: String): File =
        File(watchDir, name).apply {
            writeText("audio")
            setLastModified(FIXED_NOW - 60_000)
        }

    /** Must be called first in every test — [newQueue] and [recording] both depend on it. */
    private fun setUpDirs() {
        watchDir = tempFolder.newFolder("recordings")
        index = RecordingIndex(File(tempFolder.newFolder("state"), "recordings-index.json"))
    }

    private fun newQueue(processor: RecordingProcessor) = PendingAudioQueue(
        watchDir = watchDir,
        index = index,
        processor = processor,
        onStuck = { name, reason -> stuckNotifications += name to reason },
        nowMs = { FIXED_NOW }
    )

    @Test
    fun aSuccessfulRecordingIsMarkedDoneAndNeverProcessedAgain() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        recording("Dana_250101_120000.m4a")

        queue.sweep()
        queue.sweep()

        assertEquals(listOf("Dana_250101_120000.m4a"), processor.seen)
        assertTrue(index.stateOf("Dana_250101_120000.m4a").done)
    }

    @Test
    fun aSkippedRecordingIsAlsoMarkedDone() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Skipped)
        val queue = newQueue(processor)
        recording("short.m4a")

        queue.sweep()
        queue.sweep()

        assertEquals(1, processor.seen.size)
        assertTrue(index.stateOf("short.m4a").done)
    }

    @Test
    fun aTransientFailureCountsAnAttemptAndStaysRetryable() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.RetryLater("no internet"))
        val queue = newQueue(processor)
        recording("offline.m4a")

        queue.sweep()

        val state = index.stateOf("offline.m4a")
        assertEquals(1, state.attempts)
        assertFalse(state.stuck)
        assertFalse(state.done)
        assertEquals("no internet", state.lastError)
    }

    @Test
    fun theFifthConsecutiveFailureMarksItStuckAndNotifiesExactlyOnce() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.RetryLater("no internet"))
        val queue = newQueue(processor)
        recording("doomed.m4a")

        repeat(4) { queue.sweep() }
        assertFalse("four failures is not yet giving up", index.stateOf("doomed.m4a").stuck)
        assertTrue(stuckNotifications.isEmpty())

        queue.sweep()
        assertTrue(index.stateOf("doomed.m4a").stuck)
        assertEquals(5, index.stateOf("doomed.m4a").attempts)
        assertEquals(1, stuckNotifications.size)
        assertEquals("doomed.m4a", stuckNotifications.single().first)

        // Further sweeps must not re-process it and must not re-notify.
        queue.sweep()
        queue.sweep()
        assertEquals(5, processor.seen.size)
        assertEquals(1, stuckNotifications.size)
    }

    @Test
    fun aGiveUpOutcomeIsStuckImmediatelyWithoutBurningFiveAttempts() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.GiveUp("file too large"))
        val queue = newQueue(processor)
        recording("huge.m4a")

        queue.sweep()

        assertTrue(index.stateOf("huge.m4a").stuck)
        assertEquals("file too large", index.stateOf("huge.m4a").lastError)
        assertEquals(1, stuckNotifications.size)

        queue.sweep()
        assertEquals("a stuck recording must never be retried", 1, processor.seen.size)
    }

    @Test
    fun aStuckRecordingIsNeverDeleted() = runBlocking {
        setUpDirs()
        val queue = newQueue(FakeProcessor(ProcessOutcome.GiveUp("nope")))
        val file = recording("kept.m4a")

        queue.sweep()

        assertTrue("the queue must never remove a recording", file.exists())
    }

    @Test
    fun aSuccessAfterFailuresClearsTheAttemptCount() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.RetryLater("no internet"))
        val queue = newQueue(processor)
        recording("flaky.m4a")
        queue.sweep()
        queue.sweep()
        assertEquals(2, index.stateOf("flaky.m4a").attempts)

        processor.outcome = ProcessOutcome.Completed
        queue.sweep()

        val state = index.stateOf("flaky.m4a")
        assertTrue(state.done)
        assertEquals(0, state.attempts)
    }

    @Test
    fun aRecordingStillBeingWrittenIsLeftForTheNextSweep() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        // Modified "just now", i.e. the recorder may still be flushing it.
        File(watchDir, "in-progress.m4a").apply { writeText("half") }.setLastModified(FIXED_NOW - 1_000)

        queue.sweep()

        assertTrue("a file still being written must not be transcribed", processor.seen.isEmpty())
    }

    @Test
    fun processNowHandlesAFreshRecordingRegardlessOfItsAge() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        // FileObserver fires on CLOSE_WRITE, so this file IS complete despite being new.
        val fresh = File(watchDir, "just-recorded.m4a").apply { writeText("audio") }
        fresh.setLastModified(FIXED_NOW)

        queue.processNow(fresh)

        assertEquals(listOf("just-recorded.m4a"), processor.seen)
        assertTrue(index.stateOf("just-recorded.m4a").done)
    }

    @Test
    fun sweepPrunesIndexEntriesWhoseRecordingIsGone() = runBlocking {
        setUpDirs()
        val queue = newQueue(FakeProcessor(ProcessOutcome.Completed))
        index.put("deleted-after-processing.m4a", RecordingState(done = true))

        queue.sweep()

        assertFalse(
            "the index must not outlive the folder it describes",
            index.allNames().contains("deleted-after-processing.m4a")
        )
    }

    @Test
    fun sweepIgnoresHiddenAndDirectoryEntries() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        File(watchDir, ".pending-write.m4a").apply { writeText("x") }.setLastModified(FIXED_NOW - 60_000)
        File(watchDir, "a-folder").mkdirs()

        queue.sweep()

        assertTrue(processor.seen.isEmpty())
    }

    @Test
    fun oneFailingRecordingDoesNotBlockTheOnesBehindIt() = runBlocking {
        setUpDirs()
        // Unlike the transcript queue, an audio sweep keeps going: a file that fails to
        // transcribe says nothing about the next one, and stopping would strand every call
        // behind a single bad recording.
        val processor = object : RecordingProcessor {
            val seen = mutableListOf<String>()
            override suspend fun process(audioFile: File): ProcessOutcome {
                seen += audioFile.name
                return if (audioFile.name.startsWith("bad")) ProcessOutcome.GiveUp("nope")
                else ProcessOutcome.Completed
            }
        }
        val queue = newQueue(processor)
        recording("bad-first.m4a")
        recording("good-second.m4a")

        queue.sweep()

        assertEquals(2, processor.seen.size)
        assertTrue(index.stateOf("good-second.m4a").done)
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PendingAudioQueueTest"
```

Expected: compilation failure — `Unresolved reference: PendingAudioQueue`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/PendingAudioQueue.kt`:

```kotlin
package com.brachaai.app

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Decides which recordings still need transcribing, and when to stop trying.
 *
 * This exists because the pipeline had failover for the *transcript* but none for the
 * *audio*: with no internet there is no transcript to queue, Whisper threw, and the
 * recording sat in the watch directory forever because `FileObserver` only fires for newly
 * written files. Recordings are never moved or copied — the watch directory is the queue,
 * and this class plus [RecordingIndex] is the bookkeeping over it.
 *
 * All retry policy lives here. [RecordingIndex] only stores state and [AudioProcessor] only
 * makes one attempt, so each of the three can be tested without the other two.
 */
class PendingAudioQueue(
    private val watchDir: File,
    private val index: RecordingIndex,
    private val processor: RecordingProcessor,
    /** Called once, at the moment a recording is given up on. Name, then reason. */
    private val onStuck: (String, String) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    // Serializes every attempt in the process. Without it a sweep triggered by the network
    // coming back and a FileObserver event for a just-finished call could transcribe the
    // same recording twice, uploading the call twice.
    private val mutex = Mutex()

    /**
     * Processes a recording the file observer just saw close.
     *
     * No age check: `FileObserver.CLOSE_WRITE` already means the writer is finished, which
     * is exactly the guarantee [sweep] lacks and has to approximate with [MIN_AGE_MS].
     */
    suspend fun processNow(file: File) {
        mutex.withLock {
            processOneLocked(file)
        }
    }

    /**
     * Retries everything in the watch directory that is neither done nor given up on.
     *
     * Keeps going past a failure, unlike the transcript queue's flush: one recording failing
     * to transcribe says nothing about the next, and stopping early would strand every call
     * behind a single bad file.
     */
    suspend fun sweep() {
        mutex.withLock {
            val files = candidateFiles()
            index.pruneTo(files.map { it.name }.toSet())

            val pending = files.filter { file ->
                val state = index.stateOf(file.name)
                !state.done && !state.stuck
            }
            if (pending.isEmpty()) return@withLock

            Log.i(TAG, "Sweeping ${pending.size} unprocessed recording(s)")
            pending.forEach { file ->
                // A file that is still being written is almost certainly the call happening
                // right now; the observer will pick it up on CLOSE_WRITE.
                if (nowMs() - file.lastModified() < MIN_AGE_MS) {
                    Log.d(TAG, "Skipping ${file.name} this sweep: written too recently")
                    return@forEach
                }
                processOneLocked(file)
            }
        }
    }

    /** Caller must hold [mutex]. */
    private suspend fun processOneLocked(file: File) {
        val name = file.name
        val before = index.stateOf(name)
        if (before.done || before.stuck) {
            Log.d(TAG, "Ignoring $name: already ${if (before.done) "done" else "given up on"}")
            return
        }

        when (val outcome = processor.process(file)) {
            is ProcessOutcome.Completed, is ProcessOutcome.Skipped -> {
                // attempts resets to 0: the entry now only records that this is finished, and
                // a stale count would be misleading if the file is somehow seen again.
                index.put(name, RecordingState(attempts = 0, done = true))
                Log.i(TAG, "Finished $name ($outcome)")
            }

            is ProcessOutcome.GiveUp -> {
                index.put(name, RecordingState(attempts = before.attempts + 1, stuck = true, lastError = outcome.reason))
                Log.e(TAG, "Giving up on $name: ${outcome.reason}. The recording is kept and will not be retried.")
                notifyStuck(name, outcome.reason)
            }

            is ProcessOutcome.RetryLater -> {
                val attempts = before.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    index.put(name, RecordingState(attempts = attempts, stuck = true, lastError = outcome.reason))
                    Log.e(TAG, "Giving up on $name after $attempts attempts: ${outcome.reason}. The recording is kept.")
                    notifyStuck(name, outcome.reason)
                } else {
                    index.put(name, RecordingState(attempts = attempts, lastError = outcome.reason))
                    Log.w(TAG, "Attempt $attempts/$MAX_ATTEMPTS failed for $name: ${outcome.reason}")
                }
            }
        }
    }

    /** Never lets a notification failure break the sweep — the bookkeeping already happened. */
    private fun notifyStuck(name: String, reason: String) {
        try {
            onStuck(name, reason)
        } catch (e: Exception) {
            Log.w(TAG, "Could not report $name as stuck", e)
        }
    }

    private fun candidateFiles(): List<File> =
        watchDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()

    companion object {
        private const val TAG = "PendingAudioQueue"

        /**
         * Consecutive transient failures before a recording is given up on. It is still
         * never deleted — this only stops the retrying, so a permanently-broken file cannot
         * re-run Whisper on every single reconnect.
         */
        const val MAX_ATTEMPTS = 5

        /**
         * A sweep ignores anything written this recently. `FileObserver` guarantees a file
         * is complete via CLOSE_WRITE; a sweep has no such guarantee and would otherwise
         * happily transcribe the call that is still in progress.
         */
        const val MIN_AGE_MS = 10_000L
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.PendingAudioQueueTest"
```

Expected: 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/PendingAudioQueue.kt android/app/src/test/java/com/brachaai/app/PendingAudioQueueTest.kt && git commit -m "Add PendingAudioQueue, the retry policy for unprocessed recordings"
```

---

### Task 5: NetworkWatcher

Fires a sweep when a validated network appears. The debounce is a separate pure class so it is testable without Robolectric shadows.

**Files:**
- Create: `android/app/src/main/java/com/brachaai/app/NetworkWatcher.kt`
- Test: `android/app/src/test/java/com/brachaai/app/NetworkDebouncerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class NetworkDebouncer(windowMs: Long = 30_000L)` with `fun accept(nowMs: Long): Boolean`
  - `class NetworkWatcher(context: Context, onNetworkAvailable: () -> Unit)` with `fun start()` and `fun stop()`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/brachaai/app/NetworkDebouncerTest.kt`:

```kotlin
package com.brachaai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic — no Android, no Robolectric. Wi-Fi/cellular handover fires the connectivity
 * callback repeatedly within seconds, and each firing would otherwise start a full sweep.
 */
class NetworkDebouncerTest {

    @Test
    fun theFirstNetworkEventIsAlwaysAccepted() {
        assertTrue(NetworkDebouncer().accept(1_000L))
    }

    @Test
    fun aSecondEventInsideTheWindowIsDropped() {
        val debouncer = NetworkDebouncer(windowMs = 30_000L)

        assertTrue(debouncer.accept(1_000L))
        assertFalse(debouncer.accept(5_000L))
        assertFalse(debouncer.accept(30_999L))
    }

    @Test
    fun anEventPastTheWindowIsAcceptedAgain() {
        val debouncer = NetworkDebouncer(windowMs = 30_000L)
        assertTrue(debouncer.accept(1_000L))

        assertTrue(debouncer.accept(31_000L))
    }

    @Test
    fun aDroppedEventDoesNotExtendTheWindow() {
        // Otherwise a network flapping every 5s would hold the sweep off indefinitely.
        val debouncer = NetworkDebouncer(windowMs = 30_000L)
        assertTrue(debouncer.accept(0L))
        assertFalse(debouncer.accept(10_000L))
        assertFalse(debouncer.accept(20_000L))

        assertTrue(debouncer.accept(30_000L))
    }

    @Test
    fun aClockThatJumpsBackwardsStillLetsEventsThrough() {
        // SystemClock/wall-clock adjustments must not wedge the sweep off forever.
        val debouncer = NetworkDebouncer(windowMs = 30_000L)
        assertTrue(debouncer.accept(1_000_000L))

        assertTrue(debouncer.accept(500L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.NetworkDebouncerTest"
```

Expected: compilation failure — `Unresolved reference: NetworkDebouncer`.

- [ ] **Step 3: Write the implementation**

Create `android/app/src/main/java/com/brachaai/app/NetworkWatcher.kt`:

```kotlin
package com.brachaai.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Collapses a burst of connectivity callbacks into one trigger.
 *
 * Wi-Fi/cellular handover fires `onAvailable`/`onCapabilitiesChanged` several times within
 * seconds, and each firing would otherwise start a full sweep of the watch directory.
 *
 * Pure, so it is unit-tested without Android.
 */
class NetworkDebouncer(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastAcceptedMs: Long? = null

    /**
     * @return true when the caller should act on this event.
     *
     * Only *accepted* events move the window, so a network flapping faster than the window
     * cannot hold the sweep off forever. A clock that jumps backwards also passes, rather
     * than wedging shut until wall-clock time catches up.
     */
    @Synchronized
    fun accept(nowMs: Long): Boolean {
        val last = lastAcceptedMs
        val due = last == null || nowMs - last >= windowMs || nowMs < last
        if (due) lastAcceptedMs = nowMs
        return due
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
    }
}

/**
 * Calls [onNetworkAvailable] when the device gets a working internet connection.
 *
 * Gated on `NET_CAPABILITY_VALIDATED`, not mere connectivity: a captive-portal Wi-Fi is
 * "connected" while every request still fails, and sweeping then would burn attempts off
 * the give-up counter for no reason.
 *
 * Android delivers a callback for the already-connected network at registration time, so
 * [start] also covers service start and boot — no separate startup trigger is needed.
 *
 * A thin wrapper by design: everything worth testing is in [NetworkDebouncer].
 */
class NetworkWatcher(
    context: Context,
    private val onNetworkAvailable: () -> Unit
) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val debouncer = NetworkDebouncer()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = try {
                connectivityManager?.getNetworkCapabilities(network)
            } catch (e: Exception) {
                null
            }
            // onAvailable can precede validation, in which case onCapabilitiesChanged
            // follows with the validated flag set; both routes land here.
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                fire()
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                fire()
            }
        }
    }

    private fun fire() {
        if (!debouncer.accept(System.currentTimeMillis())) return
        Log.i(TAG, "Validated network available; triggering a sweep")
        try {
            onNetworkAvailable()
        } catch (e: Exception) {
            Log.e(TAG, "Sweep trigger failed", e)
        }
    }

    fun start() {
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            // Losing the trigger costs retries, not data — the recordings are still on disk
            // and the post-upload sweep still runs. It must not take the service down.
            Log.e(TAG, "Could not register the network callback", e)
        }
    }

    fun stop() {
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister the network callback", e)
        }
    }

    companion object {
        private const val TAG = "NetworkWatcher"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.brachaai.app.NetworkDebouncerTest"
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/NetworkWatcher.kt android/app/src/test/java/com/brachaai/app/NetworkDebouncerTest.kt && git commit -m "Add NetworkWatcher to trigger sweeps when internet returns"
```

---

### Task 6: Wire it into CallMonitorService

The service constructs the queue, routes file-observer events through it, registers the watcher, sweeps after a successful upload, and posts the stuck notification.

**Files:**
- Modify: `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/CLAUDE.md`

**Interfaces:**
- Consumes: `PendingAudioQueue` (Task 4), `RecordingIndex.default(filesDir)` (Task 1), `NetworkWatcher` (Task 5), `AudioProcessor.process` (Task 3).
- Produces: nothing further.

There is no unit test for this task — `Service` lifecycle and `ConnectivityManager` registration have no JVM seam, matching how the rest of this service is treated. Verification is the on-device matrix in Step 6.

- [ ] **Step 1: Add the manifest permission**

In `android/app/src/main/AndroidManifest.xml`, after the `INTERNET` line:

```xml
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

A normal permission — granted at install, no runtime prompt, no `MainActivity` change.

- [ ] **Step 2: Wire the queue into the service**

In `android/app/src/main/java/com/brachaai/app/CallMonitorService.kt`:

Add fields alongside the existing ones:

```kotlin
    private lateinit var pendingAudioQueue: PendingAudioQueue
    private var networkWatcher: NetworkWatcher? = null
```

In `onCreate`, after `audioProcessor` is constructed and before `briefingSync`:

```kotlin
        pendingAudioQueue = PendingAudioQueue(
            watchDir = File(WATCH_PATH),
            index = RecordingIndex.default(filesDir),
            processor = audioProcessor,
            onStuck = { name, reason -> notifyStuck(name, reason) }
        )
```

In `onCreate`, replace `flushPending()` with:

```kotlin
        flushPending()
        // Registering delivers an immediate callback for the network the device is already
        // on, so this also covers service start and boot.
        networkWatcher = NetworkWatcher(this) { sweepPendingAudio() }.also { it.start() }
```

Add the sweep launcher next to `flushPending()`:

```kotlin
    private fun sweepPendingAudio() {
        serviceScope.launch {
            try {
                pendingAudioQueue.sweep()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sweep pending recordings", e)
            }
        }
    }
```

Replace `handleNewFile` entirely:

```kotlin
    private fun handleNewFile(file: File) {
        serviceScope.launch {
            try {
                pendingAudioQueue.processNow(file)
                // The call that just uploaded produces a new summary and new tasks.
                briefingSync.syncNow()
                // A success proves both network and token are good — the moment to retry
                // anything stranded earlier. Deliberately after processNow returns: the
                // queue's mutex is not reentrant, so sweeping from inside would deadlock.
                sweepPendingAudio()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process ${file.name}", e)
                notifyError(file.name, e.message ?: "Unknown error")
            }
        }
    }
```

`processNow` returns `Unit` and no longer throws on an ordinary processing failure — the
outcome is recorded in the index instead. The `catch` stays for genuinely unexpected
failures (a dead index, a vanished watch directory), which is why `notifyError` survives
alongside the new `notifyStuck`.

Add the stuck notification below `notifyError`:

```kotlin
    /**
     * Posted once, when a recording is given up on. The audio file is still on disk and is
     * never deleted; there is deliberately no retry action, so this is purely a heads-up
     * that one call will not appear in the app.
     */
    private fun notifyStuck(filename: String, reason: String) {
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setContentTitle("Could not process: $filename")
            .setContentText("The recording was kept on your phone. $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("The recording was kept on your phone. $reason"))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(errorNotificationId.getAndIncrement(), notification)
    }
```

In `onDestroy`, before `serviceScope.cancel()`:

```kotlin
        networkWatcher?.stop()
        networkWatcher = null
```

- [ ] **Step 3: Build the module**

```bash
cd android && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. `processAndSendToBackend` should no longer be referenced anywhere — confirm with:

```bash
grep -rn "processAndSendToBackend" android/app/src
```

Expected: no matches.

- [ ] **Step 4: Run the full unit test suite**

```bash
cd android && ./gradlew testDebugUnitTest
```

Expected: every test in the module passes, including the pre-existing `AudioProcessorUploadTest`, `PendingUploadStoreTest`, `AuthStoreTest`, `TokenRefresherTest`, `BriefingClientTest`, `BriefingSyncTest`, `AudioDurationTest`.

- [ ] **Step 5: Update `android/CLAUDE.md`**

In the numbered architecture list, replace the last bullet of item 2 (`AudioProcessor.kt`) — the one beginning "On failure (no token, network error…)" — with:

```markdown
   - Returns a `ProcessOutcome` (`Completed`/`Skipped`/`RetryLater`/`GiveUp`) instead of throwing. Delivery failures still queue the transcript via `PendingUploadStore`; **transcription** failures (the offline case, where there is no transcript to queue) now return `RetryLater` so `PendingAudioQueue` can retry from the audio. Deletion of the recording is behind one gate, `applyDeletion`: it needs both a terminal-success outcome and `mayDeleteRecording`. Those are two separate questions — a transcript that is durably queued means the audio must not be re-transcribed (`Completed`, or the call uploads twice) even when `queuedTranscriptIsDurable` says the recording must be kept. A backend 400/422 now keeps the recording and marks it stuck rather than deleting it
```

Add a new numbered item after item 6:

```markdown
9. **`PendingAudioQueue.kt`** / **`RecordingIndex.kt`** / **`NetworkWatcher.kt`** — Failover for the *transcription* stage. Before this, an offline Whisper call threw, the recording survived (the delete sits below the throw) but nothing ever looked at it again, because `FileObserver` only fires on `CLOSE_WRITE` for new files — so the call was lost. Recordings are never moved or copied: the watch directory *is* the queue, and `RecordingIndex` (a single atomically-replaced JSON snapshot in app-private storage) records which are `done` or `stuck`, pruned each sweep to the files that still exist. A corrupt index degrades to "nothing is done", which only ever costs a re-transcription. `PendingAudioQueue` holds all the policy — one mutex so a sweep and a `FileObserver` event cannot transcribe the same call twice, `MAX_ATTEMPTS = 5` consecutive `RetryLater`s before giving up, `GiveUp` stuck immediately, `MIN_AGE_MS` so a sweep skips the call still being recorded — and unlike the transcript flush it continues past a failure rather than stopping. Sweeps are triggered by `NetworkWatcher` (a validated network only, debounced; its registration callback covers boot and service start) and after each successful upload. **A recording is never deleted unless its call actually landed** — the "delete audio after processing" setting only decides what happens after success, never whether a failure loses data. Given-up recordings are kept forever with one notification and no in-app way back; recovery is by hand off the phone's storage
```

- [ ] **Step 6: On-device verification**

Install and check each row. This is the only verification for the wiring, since `Service` has no JVM seam.

```bash
cd android && ./gradlew installDebug
```

1. **Offline call.** Enable airplane mode, record a call over 5s, wait. Expect: an attempt logged and failed, the recording **still present** in `/storage/emulated/0/Recordings/Call`, no call in the app, and no crash. Confirm with `adb logcat -s PendingAudioQueue AudioProcessor`.
2. **Reconnect.** Disable airplane mode. Expect: `NetworkWatcher` logs "Validated network available", the sweep runs, the call appears in the app, and the recording is deleted (with the setting on).
3. **Setting off.** Turn "delete audio after processing" off, record a call while online. Expect: the call appears and the recording is kept.
4. **No double-upload.** Record a call online, then toggle airplane mode off and on to force several sweeps. Expect: the call appears exactly once in the app.
5. **Give-up.** Leave airplane mode on and toggle it repeatedly to drive five sweeps against one recording. Expect: after the fifth, one "Could not process" notification, the recording still on disk, and no further attempts on later sweeps.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/brachaai/app/CallMonitorService.kt android/app/src/main/AndroidManifest.xml android/CLAUDE.md && git commit -m "Sweep pending recordings when the network returns"
```

---

## Verification Summary

| Spec requirement | Covered by |
|---|---|
| Recordings retried from audio after a transcription failure | Tasks 3, 4, 6 |
| Recording never deleted unless the call landed | Task 3 (`applyDeletion`, 7 tests) |
| Setting only governs post-success behaviour | Task 3 |
| Give up after 5 consecutive failures, keep the file | Task 4 |
| Permanent OpenAI rejection → stuck immediately | Tasks 2, 3, 4 |
| Backend 400/422 keeps the recording (behaviour change) | Task 3 |
| Blank transcript counts attempts (behaviour change) | Task 3 |
| Trigger on validated network | Task 5, wired in Task 6 |
| Trigger after successful upload | Task 6 |
| Index pruned to existing files | Tasks 1, 4 |
| One notification per stuck recording, no UI | Tasks 4, 6 |
| `ACCESS_NETWORK_STATE` | Task 6 |
