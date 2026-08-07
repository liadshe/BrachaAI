package com.brachaai.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private lateinit var baselineMarker: File
    private val stuckNotifications = mutableListOf<Pair<String, String>>()

    // Every recording is created well in the past, so the "still being written" guard never
    // interferes; the one test that cares about that guard overrides it explicitly.
    private fun recording(name: String): File =
        File(watchDir, name).apply {
            writeText("audio")
            setLastModified(FIXED_NOW - 60_000)
        }

    /**
     * Must be called first in every test — [newQueue] and [recording] both depend on it.
     *
     * The baseline marker is created up front, i.e. every test runs as a device that has
     * already been watching for a while. Without that, the first-run baseline would adopt
     * each test's own fixtures as pre-existing history and nothing would ever be processed.
     * The tests that exercise the baseline itself delete it explicitly.
     */
    private fun setUpDirs() {
        watchDir = tempFolder.newFolder("recordings")
        val stateDir = tempFolder.newFolder("state")
        index = RecordingIndex(File(stateDir, "recordings-index.json"))
        baselineMarker = File(stateDir, "recordings-baseline").apply { writeText("") }
    }

    /** Puts the device back to "app has never run here", so the next call takes the baseline. */
    private fun clearBaseline() {
        assertTrue(baselineMarker.delete())
    }

    private fun newQueue(processor: RecordingProcessor) = PendingAudioQueue(
        watchDir = watchDir,
        index = index,
        processor = processor,
        onStuck = { name, reason -> stuckNotifications += name to reason },
        nowMs = { FIXED_NOW },
        baselineMarker = baselineMarker
    )

    // ------------------------------------------------------------ first-run baseline
    //
    // The queue processes anything in the watch directory without an index entry. On a fresh
    // install the index is empty, so without a baseline the first sweep transcribes and
    // uploads the user's entire recording history — including calls made long before the app
    // existed. These pin the guard against that.

    @Test
    fun theFirstRunAdoptsEveryPreExistingRecordingWithoutTranscribingAny() = runBlocking {
        setUpDirs()
        clearBaseline()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        recording("old-call-1.m4a")
        recording("old-call-2.m4a")
        recording("old-call-3.m4a")

        queue.sweep()

        assertTrue("history predates the app and must never be uploaded", processor.seen.isEmpty())
        assertTrue(index.stateOf("old-call-1.m4a").done)
        assertTrue(index.stateOf("old-call-2.m4a").done)
        assertTrue(index.stateOf("old-call-3.m4a").done)
        assertTrue("the baseline must be recorded so it never runs again", baselineMarker.exists())
    }

    @Test
    fun aRecordingMadeAfterTheBaselineIsStillProcessed() = runBlocking {
        setUpDirs()
        clearBaseline()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        recording("old-call.m4a")
        queue.sweep()
        assertTrue(processor.seen.isEmpty())

        // A call arriving after the app started watching is exactly what this queue is for.
        recording("new-call.m4a")
        queue.sweep()

        assertEquals(listOf("new-call.m4a"), processor.seen)
    }

    @Test
    fun theBaselineNeverSwallowsTheRecordingTheObserverJustReported() = runBlocking {
        setUpDirs()
        clearBaseline()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        recording("old-call.m4a")
        // FileObserver fires CLOSE_WRITE for a call that just ended, before any sweep has run.
        val fresh = recording("just-recorded.m4a")

        val outcome = queue.processNow(fresh)

        assertEquals(ProcessOutcome.Completed, outcome)
        assertEquals(listOf("just-recorded.m4a"), processor.seen)
        assertTrue("the pre-existing file is still adopted", index.stateOf("old-call.m4a").done)
    }

    @Test
    fun anUnreadableDirectoryDefersTheBaselineRatherThanTakingAnEmptyOne() = runBlocking {
        setUpDirs()
        clearBaseline()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val missingDir = File(watchDir, "does-not-exist")
        val queue = PendingAudioQueue(
            watchDir = missingDir,
            index = index,
            processor = processor,
            nowMs = { FIXED_NOW },
            baselineMarker = baselineMarker
        )

        queue.sweep()

        // Marking the baseline taken while blind would leave the real contents unadopted, so
        // they would all be swept as new the moment the directory became readable.
        assertFalse("the baseline must not be claimed while the folder is unreadable", baselineMarker.exists())
    }

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
    fun processNowReturnsTheOutcomeTheProcessorProduced() = runBlocking {
        setUpDirs()
        // CallMonitorService.handleNewFile decides whether to sweep the rest of the queue
        // off this return value, so it must be the real outcome, not just Unit.
        val processor = FakeProcessor(ProcessOutcome.RetryLater("no internet"))
        val queue = newQueue(processor)
        val file = recording("returns-outcome.m4a")

        val outcome = queue.processNow(file)

        assertEquals(ProcessOutcome.RetryLater("no internet"), outcome)
    }

    @Test
    fun processNowReportsAlreadyHandledForAnAlreadyFinishedRecording() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        val file = recording("already-done.m4a")
        index.put("already-done.m4a", RecordingState(done = true))

        val outcome = queue.processNow(file)

        assertEquals(
            "not Skipped: Skipped is deletion-eligible, and this same branch also fires for a " +
                "stuck recording, which must never be deletable",
            ProcessOutcome.AlreadyHandled,
            outcome
        )
        assertTrue("must not re-run the processor on an already-finished recording", processor.seen.isEmpty())
    }

    @Test
    fun processNowReportsAlreadyHandledForAStuckRecording() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        val file = recording("given-up.m4a")
        index.put("given-up.m4a", RecordingState(attempts = 5, stuck = true, lastError = "nope"))

        val outcome = queue.processNow(file)

        assertEquals(ProcessOutcome.AlreadyHandled, outcome)
        assertTrue(processor.seen.isEmpty())
        assertTrue("a stuck recording is never deleted", file.exists())
    }

    @Test
    fun processNowIgnoresAHiddenFileAndADirectory() = runBlocking {
        setUpDirs()
        // sweep() has always filtered these out; processNow applied no filter at all, so a
        // recorder that writes ".pending_x.m4a" before renaming it — or any stray entry —
        // earned an index entry, five RetryLater attempts and a user-facing "Could not
        // process" notification for something that was never a call.
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        val hidden = File(watchDir, ".pending_x.m4a").apply { writeText("half") }
        val directory = File(watchDir, "a-folder").apply { mkdirs() }

        assertEquals(ProcessOutcome.AlreadyHandled, queue.processNow(hidden))
        assertEquals(ProcessOutcome.AlreadyHandled, queue.processNow(directory))

        assertTrue("neither entry is a recording", processor.seen.isEmpty())
        assertTrue(
            "an ineligible entry must leave no trace in the index",
            index.allNames().isEmpty()
        )
    }

    /**
     * The mutex is the only thing standing between a sweep and a `FileObserver` event landing
     * on the same recording and uploading the call twice, and the design doc requires this
     * test by name. Delete the `Mutex` from [PendingAudioQueue] and this is the test that
     * fails; nothing else in the suite would notice.
     *
     * The interleaving is deterministic, not a race, because `runBlocking` gives both
     * coroutines one event-loop thread and [OverlappingProcessor] suspends (rather than
     * blocks) inside the attempt:
     *
     *  - `launch { sweep() }` takes the mutex uncontended, enters the processor, completes
     *    `entered`, and suspends in `delay`.
     *  - The parent resumes from `entered.await()` and calls `processNow` on the same file —
     *    i.e. while the sweep is provably still mid-attempt and still holding the mutex.
     *  - `processNow` suspends on the mutex. Only when the sweep finishes and releases it does
     *    `processOneLocked` run, by which point the index already says `done`, so it
     *    short-circuits to `AlreadyHandled` and never reaches the processor.
     *
     * Without the mutex, `processNow` would instead run `processOneLocked` immediately: the
     * sweep has not returned from `processor.process` yet, so nothing has written `done` to
     * the index, `before.done` is false, and the same file is handed to the processor a second
     * time — `seen` has two entries and `maxConcurrent` is 2. Both assertions below fail.
     */
    @Test
    fun aSweepAndANewFileEventNeverProcessTheSameRecordingTwice() = runBlocking {
        setUpDirs()
        val file = recording("overlap.m4a")
        val processor = OverlappingProcessor(holdMs = 100)
        val queue = newQueue(processor)

        val sweepJob = launch { queue.sweep() }
        processor.entered.await()   // the sweep is inside the attempt, holding the mutex

        val outcome = queue.processNow(file)
        sweepJob.join()

        assertEquals(
            "the same recording must not be transcribed and uploaded twice",
            listOf("overlap.m4a"),
            processor.seen
        )
        assertEquals("two attempts must never be in flight at once", 1, processor.maxConcurrent)
        assertEquals(
            "the second caller waited for the lock and then found the recording already done",
            ProcessOutcome.AlreadyHandled,
            outcome
        )
    }

    /**
     * Suspends inside the attempt and reports whether two attempts ever overlapped.
     *
     * `delay` rather than `Thread.sleep` on purpose: `runBlocking`'s event loop is single
     * threaded, so only a suspension hands control to the other coroutine. Blocking would
     * serialize the test by accident and pass even with the mutex removed.
     */
    private class OverlappingProcessor(private val holdMs: Long) : RecordingProcessor {
        val seen = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        var maxConcurrent = 0
        private var inFlight = 0

        override suspend fun process(audioFile: File): ProcessOutcome {
            seen += audioFile.name
            inFlight += 1
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            entered.complete(Unit)
            delay(holdMs)
            inFlight -= 1
            return ProcessOutcome.Completed
        }
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

        assertEquals(
            "an early return after the failure would leave good-second.m4a unseen",
            listOf("bad-first.m4a", "good-second.m4a"),
            processor.seen
        )
        assertTrue(index.stateOf("good-second.m4a").done)
    }

    @Test
    fun anUnreadableWatchDirectoryLeavesTheIndexUntouched() = runBlocking {
        setUpDirs()
        // Represents a recording the index already knows about from before the directory
        // became unreadable (e.g. storage access revoked, or not yet mounted after boot).
        index.put("existing.m4a", RecordingState(attempts = 2, lastError = "no internet"))
        // A path that does not exist makes File.listFiles() return null, the same signal
        // Android gives for "directory not accessible" as for "does not exist yet".
        val missingDir = File(watchDir, "does-not-exist")
        val queue = PendingAudioQueue(
            watchDir = missingDir,
            index = index,
            processor = FakeProcessor(ProcessOutcome.Completed),
            onStuck = { name, reason -> stuckNotifications += name to reason },
            nowMs = { FIXED_NOW },
            baselineMarker = baselineMarker
        )

        queue.sweep()

        assertTrue(
            "an unreadable directory must not be treated as an empty one",
            index.allNames().contains("existing.m4a")
        )
        assertEquals(2, index.stateOf("existing.m4a").attempts)
    }

    @Test
    fun aRecordingWithAFutureTimestampIsProcessedNotStrandedForever() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        // Clock skew: the file's mtime is ahead of "now", e.g. a fast device clock while
        // recording, or an NTP/manual correction moving the clock back afterwards. A naive
        // `now - mtime < MIN_AGE_MS` guard is true for every negative age, which would skip
        // this recording on every sweep forever.
        File(watchDir, "future.m4a").apply { writeText("audio") }.setLastModified(FIXED_NOW + 60_000)

        queue.sweep()

        assertEquals(listOf("future.m4a"), processor.seen)
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L
    }
}
