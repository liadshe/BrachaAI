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
        // The parent is a regular file, so every write must fail. The state cannot be
        // persisted, so a fresh load has no knowledge of it. Losing the index is survivable
        // (recordings get re-processed, which costs a re-transcription but never deletes
        // one); throwing here would kill the pipeline. The guarantee is that put() completes
        // without exception, even if the write fails — the cost is re-processing, which is
        // the safe direction.
        val blocker = tempFolder.newFile("not-a-directory")
        val index = RecordingIndex(File(blocker, "recordings.json"))

        index.put("a.m4a", RecordingState(done = true))

        assertFalse("a failed write must not report a persisted state", index.stateOf("a.m4a").done)
    }

    /**
     * There is no in-memory cache behind [RecordingIndex.put], so a failed write is a lost
     * write — not "state kept for this run", as the log line used to claim. The caller has to
     * be able to tell, because a lost `done` re-uploads the call and a lost `stuck` re-notifies
     * and re-burns attempts forever.
     */
    @Test
    fun putReportsWhetherTheStateActuallyReachedDisk() {
        val index = RecordingIndex(newIndexFile())

        assertTrue("a writable index reports success", index.put("a.m4a", RecordingState(done = true)))

        // The parent is a regular file, so every write below it must fail.
        val blocker = tempFolder.newFile("blocker")
        val unwritable = RecordingIndex(File(blocker, "recordings.json"))

        assertFalse(
            "a write that never reached disk must not be reported as persisted",
            unwritable.put("b.m4a", RecordingState(stuck = true))
        )
    }

    /**
     * Without file-as-source-of-truth, two separate RecordingIndex instances pointing at
     * the same file hold independent in-memory snapshots loaded once at construction. Even
     * with a process-wide lock on persist(), instance B's write overwrites instance A's with
     * B's stale snapshot, losing entries entirely. Trace the old code:
     *   index1.states = {}; index2.states = {}
     *   index1.put("a") → lock, states1["a"]=state, persist(states1={a}), unlock. file={a}
     *   index2.put("b") → lock, states2["b"]=state, persist(states2={b}), unlock. file={b}  (lost a!)
     *   index1.put("c") → lock, states1["c"]=state, persist(states1={a,c}), unlock. file={a,c}
     *   index2.put("d") → lock, states2["d"]=state, persist(states2={b,d}), unlock. file={b,d}  (lost a,c!)
     *   fresh.allNames() loads {b,d}, test fails.
     *
     * With file-as-source-of-truth, each operation loads fresh inside the lock:
     *   index1.put("a") → lock, load {}, add "a"→{a}, persist, unlock. file={a}
     *   index2.put("b") → lock, load {a}, add "b"→{a,b}, persist, unlock. file={a,b}
     *   index1.put("c") → lock, load {a,b}, add "c"→{a,b,c}, persist, unlock. file={a,b,c}
     *   index2.put("d") → lock, load {a,b,c}, add "d"→{a,b,c,d}, persist, unlock. file={a,b,c,d}
     *   fresh.allNames() loads {a,b,c,d}, test passes.
     */
    @Test
    fun concurrentInstancesDoNotLoseData() {
        val file = newIndexFile()
        val index1 = RecordingIndex(file)
        val index2 = RecordingIndex(file)

        // Interleave puts from two instances
        index1.put("call-a.m4a", RecordingState(attempts = 1, done = true))
        index2.put("call-b.m4a", RecordingState(attempts = 2, done = true))
        index1.put("call-c.m4a", RecordingState(attempts = 3, done = true))
        index2.put("call-d.m4a", RecordingState(attempts = 4, done = true))

        // Load fresh from disk — must have all entries
        val reloaded = RecordingIndex(file)

        val allNames = reloaded.allNames()
        assertEquals(
            "file-as-source-of-truth guarantees no entries are lost to concurrent writes",
            setOf("call-a.m4a", "call-b.m4a", "call-c.m4a", "call-d.m4a"),
            allNames
        )
    }
}
