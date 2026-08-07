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
    fun processNowReturnsSkippedForAnAlreadyFinishedRecording() = runBlocking {
        setUpDirs()
        val processor = FakeProcessor(ProcessOutcome.Completed)
        val queue = newQueue(processor)
        val file = recording("already-done.m4a")
        index.put("already-done.m4a", RecordingState(done = true))

        val outcome = queue.processNow(file)

        assertEquals(
            "already-done is terminal, not a fresh failure or success",
            ProcessOutcome.Skipped,
            outcome
        )
        assertTrue("must not re-run the processor on an already-finished recording", processor.seen.isEmpty())
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
            nowMs = { FIXED_NOW }
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
