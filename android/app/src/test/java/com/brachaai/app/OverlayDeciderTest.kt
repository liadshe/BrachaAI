package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Robolectric: OverlayDecider reads through BriefingStore, which uses android.util.Log
// and org.json. The decider itself is pure, but its collaborator is not.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayDeciderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: BriefingStore

    @Before
    fun setUp() {
        store = BriefingStore(File(temp.newFolder("files"), "briefings.json"))
    }

    private fun seed(
        phone: String = "+972501234567",
        summary: String? = "Promised to send price quote.",
        tasks: List<BriefingTask> = listOf(BriefingTask("t1", "Send contract", "HIGH")),
    ) {
        store.replaceAll(listOf(Briefing("c1", "David Cohen", phone, summary, tasks, tasks.size)))
    }

    private fun decide(number: String?, canDrawOverlays: Boolean = true) =
        OverlayDecider(store).decide(number, canDrawOverlays)

    @Test
    fun `shows an overlay for a known contact`() {
        seed()

        val action = decide("+972501234567")

        assertTrue(action is OverlayAction.Show)
        assertEquals("David Cohen", (action as OverlayAction.Show).briefing.name)
        assertFalse(action.asNotification)
    }

    @Test
    fun `matches a known contact regardless of how the number is formatted`() {
        seed(phone = "+972501234567")

        assertTrue(decide("050-123-4567") is OverlayAction.Show)
    }

    @Test
    fun `does nothing for a number that is not a known contact`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide("+972529999999"))
    }

    @Test
    fun `does nothing for a withheld number`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide("-1"))
    }

    @Test
    fun `does nothing when there is no number at all`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide(null))
    }

    @Test
    fun `does nothing when the cache is empty`() {
        assertEquals(OverlayAction.DoNothing, decide("+972501234567"))
    }

    @Test
    fun `does nothing for a known contact with nothing to say`() {
        seed(summary = null, tasks = emptyList())

        assertEquals(OverlayAction.DoNothing, decide("+972501234567"))
    }

    @Test
    fun `shows a contact with tasks but no call summary`() {
        seed(summary = null)

        assertTrue(decide("+972501234567") is OverlayAction.Show)
    }

    @Test
    fun `shows a contact with a call summary but no tasks`() {
        seed(tasks = emptyList())

        assertTrue(decide("+972501234567") is OverlayAction.Show)
    }

    @Test
    fun `falls back to a notification when the overlay permission is not held`() {
        seed()

        val action = decide("+972501234567", canDrawOverlays = false)

        assertTrue((action as OverlayAction.Show).asNotification)
    }

    @Test
    fun `stays silent for an unknown caller even without the overlay permission`() {
        seed()

        assertEquals(OverlayAction.DoNothing, decide("+972529999999", canDrawOverlays = false))
    }
}
