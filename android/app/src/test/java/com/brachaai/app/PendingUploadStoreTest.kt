package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [PendingUploadStore] is plain file I/O, so it is driven directly here with real files.
 * Robolectric is only needed because the class logs through `android.util.Log` and parses
 * with Android's `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingUploadStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sample(name: String = "Dana") = PendingUpload(
        contactName = name,
        date = "250101_120000",
        callerNumber = "0501234567",
        transcript = "some transcript text for $name"
    )

    private fun jsonEntries(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    private fun quarantined(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(PendingUploadStore.CORRUPT_SUFFIX) }
            ?.sortedBy { it.name } ?: emptyList()

    // ---------------------------------------------------------------- enqueue

    @Test
    fun enqueueReturnsTrueAndPersistsTheEntry() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)

        assertTrue(store.enqueue(sample()))
        assertEquals(1, store.size())
        assertEquals("Dana", store.peekAll().single().second.contactName)
    }

    /**
     * Regression guard for the earlier hardening fix: `enqueue` must report `false` when the
     * entry did not actually land on disk. AudioProcessor keeps the recording on `false`, so
     * a wrongly-optimistic `true` here destroys the call.
     */
    @Test
    fun enqueueReturnsFalseWhenTheDirectoryCannotBeCreated() {
        // Parent is a regular file, so mkdirs() and every subsequent write must fail.
        val blocker = tempFolder.newFile("not-a-directory")
        val store = PendingUploadStore(File(blocker, "queue"))

        assertFalse(store.enqueue(sample()))
    }

    @Test
    fun enqueueReturnsFalseWhenTheTempPathIsUnwritable() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        // The directory disappears after construction — what storage going away mid-run
        // looks like from here. The temp-file write must fail and be reported, not swallowed.
        assertTrue(dir.delete())

        assertFalse(store.enqueue(sample()))
    }

    // --------------------------------------------------- corrupt entry handling

    @Test
    fun corruptEntryIsRenamedAsideRatherThanDeleted() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        assertTrue(store.enqueue(sample()))

        val entry = jsonEntries(dir).single()
        // Exactly what a full disk produces: the write stopped half way through.
        entry.writeText("{\"contactName\":\"Dana\",\"transcript\":\"half a transcr")

        assertTrue("corrupt entry must not be returned as a usable upload", store.peekAll().isEmpty())

        assertFalse("original name must be gone", entry.exists())
        val aside = quarantined(dir).single()
        assertTrue(
            "the transcript text must still be recoverable by hand",
            aside.readText().contains("half a transcr")
        )
    }

    @Test
    fun quarantinedEntryIsNotReReadAsAQueueEntry() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        assertTrue(store.enqueue(sample()))
        jsonEntries(dir).single().writeText("{\"contactName\":\"Dana\"")

        store.peekAll()
        val asideName = quarantined(dir).single().name

        // Second pass: nothing to hand out, nothing counted, and no second quarantine file
        // (which would mean it had been picked up as an entry again).
        assertTrue(store.peekAll().isEmpty())
        assertEquals(0, store.size())
        assertEquals(listOf(asideName), quarantined(dir).map { it.name })
    }

    @Test
    fun quarantinedEntrySurvivesOverflowEviction() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        assertTrue(store.enqueue(sample("Corrupt")))
        jsonEntries(dir).single().writeText("{ truncated")
        store.peekAll()
        val aside = quarantined(dir).single()
        // Backdate it well past the 30-day cutoff that evicts real entries.
        assertTrue(aside.setLastModified(System.currentTimeMillis() - 2 * PendingUploadStore.MAX_AGE_MS))

        // enqueue() runs evictOverflow(), which must not touch quarantined files.
        assertTrue(store.enqueue(sample("Later")))

        assertTrue("quarantined transcript must not be evicted", aside.exists())
    }

    @Test
    fun anExistingQuarantineFileIsNotClobbered() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        assertTrue(store.enqueue(sample()))
        val entry = jsonEntries(dir).single()
        val occupied = File(dir, entry.name + PendingUploadStore.CORRUPT_SUFFIX)
        occupied.writeText("an earlier rescued transcript")
        entry.writeText("{ truncated payload")

        store.peekAll()

        assertEquals("earlier rescue must be untouched", "an earlier rescued transcript", occupied.readText())
        assertEquals("both rescued transcripts must exist", 2, quarantined(dir).size)
        assertTrue(quarantined(dir).any { it.readText().contains("truncated payload") })
    }

    @Test
    fun oneCorruptEntryDoesNotHideTheHealthyOnes() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        assertTrue(store.enqueue(sample("First")))
        assertTrue(store.enqueue(sample("Second")))
        jsonEntries(dir).first().writeText("{ truncated")

        val healthy = store.peekAll()

        assertEquals(listOf("Second"), healthy.map { it.second.contactName })
        assertEquals(1, quarantined(dir).size)
    }

    // ------------------------------------------------------------- capacity

    @Test
    fun sizeReachesMaxEntriesWithoutEvictingAnything() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        repeat(PendingUploadStore.MAX_ENTRIES) { assertTrue(store.enqueue(sample("caller-$it"))) }

        assertEquals(PendingUploadStore.MAX_ENTRIES, store.size())
    }

    @Test
    fun goingOverCapacityDestroysTheOldestEntry() {
        val dir = tempFolder.newFolder("pending")
        val store = PendingUploadStore(dir)
        repeat(PendingUploadStore.MAX_ENTRIES) { assertTrue(store.enqueue(sample("caller-$it"))) }
        val oldest = jsonEntries(dir).first()

        assertTrue(store.enqueue(sample("one-too-many")))

        assertFalse(oldest.exists())
        assertEquals(PendingUploadStore.MAX_ENTRIES, store.size())
    }

    // ------------------------------------------------------------- call length

    @Test
    fun roundTripsAnUploadCarryingItsCallLength() {
        val store = PendingUploadStore(tempFolder.newFolder("queue-with-length"))
        store.enqueue(sample().copy(callLengthSeconds = 272))

        assertEquals(272, store.peekAll().single().second.callLengthSeconds)
    }

    @Test
    fun roundTripsAnUploadWithNoCallLength() {
        val store = PendingUploadStore(tempFolder.newFolder("queue-no-length"))
        store.enqueue(sample().copy(callLengthSeconds = null))

        assertNull(store.peekAll().single().second.callLengthSeconds)
    }

    @Test
    fun readsAPreUpdateEntryThatHasNoCallLengthKeyInsteadOfQuarantiningIt() {
        // Entries queued by the shipped build have no such key. Treating that as a parse
        // failure would rename every one of them to *.corrupt on the first flush after
        // the update — and their recordings are already deleted, so those transcripts
        // would be stranded.
        val dir = tempFolder.newFolder("queue-legacy")
        val store = PendingUploadStore(dir)
        File(dir, "1754000000000-000.json").writeText(
            """{"contactName":"Dana","date":"250101_120000","callerNumber":"0501234567","transcript":"shalom"}"""
        )

        val entries = store.peekAll()

        assertEquals(1, entries.size)
        assertEquals("shalom", entries.single().second.transcript)
        assertNull(entries.single().second.callLengthSeconds)
        assertTrue("the legacy entry must not have been quarantined", quarantined(dir).isEmpty())
    }
}
