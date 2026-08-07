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
