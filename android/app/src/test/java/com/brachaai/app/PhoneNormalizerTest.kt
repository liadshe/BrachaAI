package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun `every spelling of the same mobile number collapses to one key`() {
        val expected = "501234567"
        assertEquals(expected, PhoneNormalizer.key("+972501234567"))
        assertEquals(expected, PhoneNormalizer.key("0501234567"))
        assertEquals(expected, PhoneNormalizer.key("050-123-4567"))
        assertEquals(expected, PhoneNormalizer.key("050 123 4567"))
        assertEquals(expected, PhoneNormalizer.key("+972-50-123-4567"))
        assertEquals(expected, PhoneNormalizer.key("(050) 123-4567"))
    }

    @Test
    fun `every spelling of the same landline collapses to one key`() {
        val expected = "31234567"
        assertEquals(expected, PhoneNormalizer.key("+97231234567"))
        assertEquals(expected, PhoneNormalizer.key("031234567"))
        assertEquals(expected, PhoneNormalizer.key("03-123-4567"))
    }

    @Test
    fun `foreign numbers fall back to the last nine digits rather than failing`() {
        assertEquals("155552671", PhoneNormalizer.key("+14155552671"))
    }

    @Test
    fun `withheld and private numbers have no key`() {
        assertNull(PhoneNormalizer.key("-1"))
        assertNull(PhoneNormalizer.key("-2"))
        assertNull(PhoneNormalizer.key("-3"))
    }

    @Test
    fun `absent or digitless input has no key`() {
        assertNull(PhoneNormalizer.key(null))
        assertNull(PhoneNormalizer.key(""))
        assertNull(PhoneNormalizer.key("   "))
        assertNull(PhoneNormalizer.key("abc"))
    }

    @Test
    fun `numbers too short to identify anyone have no key`() {
        assertNull(PhoneNormalizer.key("123"))
    }

    @Test
    fun `a nine digit number starting with the country code is treated as national, not stripped`() {
        // 972123456 is only 9 digits — stripping "972" would leave a 6-digit stub that
        // could collide with an unrelated contact.
        assertEquals("972123456", PhoneNormalizer.key("972123456"))
    }

    @Test
    fun `the country code is configurable`() {
        assertEquals("2071234567".takeLast(9), PhoneNormalizer.key("+442071234567", countryCode = "44"))
    }
}
