package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BriefingStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): Pair<BriefingStore, File> {
        val file = File(temp.newFolder("cache"), "briefings.json")
        return BriefingStore(file) to file
    }

    private fun briefing(
        contactId: String = "c1",
        name: String = "David Cohen",
        phone: String = "+972501234567",
        summary: String? = "Promised to send price quote.",
        tasks: List<BriefingTask> = listOf(BriefingTask("t1", "Send contract by Tuesday", "HIGH")),
        openTaskCount: Int = 1,
    ) = Briefing(contactId, name, phone, summary, tasks, openTaskCount)

    @Test
    fun `round trips a briefing through disk`() {
        val (subject, _) = store()

        subject.replaceAll(listOf(briefing()))

        val restored = subject.readAll().single()
        assertEquals("c1", restored.contactId)
        assertEquals("David Cohen", restored.name)
        assertEquals("+972501234567", restored.phone)
        assertEquals("Promised to send price quote.", restored.lastCallSummary)
        assertEquals(1, restored.openTaskCount)
        assertEquals(listOf(BriefingTask("t1", "Send contract by Tuesday", "HIGH")), restored.openTasks)
    }

    @Test
    fun `round trips a briefing with no summary and no tasks`() {
        val (subject, _) = store()

        subject.replaceAll(listOf(briefing(summary = null, tasks = emptyList(), openTaskCount = 0)))

        val restored = subject.readAll().single()
        assertNull(restored.lastCallSummary)
        assertTrue(restored.openTasks.isEmpty())
        assertEquals(0, restored.openTaskCount)
    }

    @Test
    fun `looks a contact up by any spelling of their number`() {
        val (subject, _) = store()
        subject.replaceAll(listOf(briefing(phone = "+972501234567")))

        val key = PhoneNormalizer.key("050-123-4567")!!

        assertEquals("David Cohen", subject.lookup(key)?.name)
    }

    @Test
    fun `returns null for a number that is not a known contact`() {
        val (subject, _) = store()
        subject.replaceAll(listOf(briefing(phone = "+972501234567")))

        assertNull(subject.lookup(PhoneNormalizer.key("+972529999999")!!))
    }

    @Test
    fun `an absent cache reads as empty rather than throwing`() {
        val (subject, _) = store()

        assertTrue(subject.readAll().isEmpty())
        assertNull(subject.lookup("501234567"))
    }

    @Test
    fun `a corrupt cache reads as empty rather than throwing`() {
        val (subject, file) = store()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")

        assertTrue(subject.readAll().isEmpty())
        assertNull(subject.lookup("501234567"))
    }

    @Test
    fun `replaceAll fully replaces the previous snapshot`() {
        val (subject, _) = store()
        subject.replaceAll(listOf(briefing(contactId = "c1", name = "David Cohen")))

        subject.replaceAll(listOf(briefing(contactId = "c2", name = "Ruth Levi", phone = "+972529999999")))

        assertEquals(listOf("Ruth Levi"), subject.readAll().map { it.name })
    }

    @Test
    fun `leaves no temp file behind after a write`() {
        val (subject, file) = store()

        subject.replaceAll(listOf(briefing()))

        val leftovers = file.parentFile!!.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("temp file left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `skips entries missing required fields instead of failing the whole snapshot`() {
        val (subject, file) = store()
        file.parentFile?.mkdirs()
        file.writeText(
            """{"contacts":[{"name":"No id"},{"contactId":"c2","name":"Ruth Levi","phone":"+972529999999","openTasks":[],"openTaskCount":0}]}"""
        )

        assertEquals(listOf("Ruth Levi"), subject.readAll().map { it.name })
    }
}
