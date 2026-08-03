package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameParserTest {

    @Test
    fun `splits name, date and time from the right`() {
        val parsed = parseFilename("Mom_260415_165702.m4a")

        assertEquals("Mom", parsed.contactName)
        assertEquals("260415", parsed.date)
        assertEquals("165702", parsed.time)
    }

    @Test
    fun `a name containing underscores survives the split`() {
        assertEquals("Dana_Levi", parseFilename("Dana_Levi_260415_165702.m4a").contactName)
    }

    @Test
    fun `the recorder's Call prefix is not part of the contact's name`() {
        // Recorders name the file after the call, not the person: "Call Mom_...".
        // Left in place it becomes the contact, so the app shows "Call Mom".
        assertEquals("Mom", parseFilename("Call Mom_260415_165702.m4a").contactName)
        assertEquals("Mom", parseFilename("call Mom_260415_165702.m4a").contactName)
        assertEquals("Mom", parseFilename("CALL Mom_260415_165702.m4a").contactName)
        assertEquals("Mom", parseFilename("Call  Mom_260415_165702.m4a").contactName)
        assertEquals("Mom", parseFilename("Call_Mom_260415_165702.m4a").contactName)
    }

    @Test
    fun `only the leading Call is dropped, and only once`() {
        assertEquals("Call Center", parseFilename("Call Call Center_260415_165702.m4a").contactName)
    }

    @Test
    fun `a name that merely starts with the letters call is left alone`() {
        // "Callie" is a name; "Call" followed by a separator is the recorder's label.
        assertEquals("Callie", parseFilename("Callie_260415_165702.m4a").contactName)
        assertEquals("Caller ID", parseFilename("Caller ID_260415_165702.m4a").contactName)
    }

    @Test
    fun `a filename that is nothing but the prefix keeps it rather than going nameless`() {
        assertEquals("Call", parseFilename("Call_260415_165702.m4a").contactName)
        assertEquals("Call", parseFilename("Call _260415_165702.m4a").contactName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a filename without a date and time is rejected`() {
        parseFilename("Mom_260415.m4a")
    }
}
