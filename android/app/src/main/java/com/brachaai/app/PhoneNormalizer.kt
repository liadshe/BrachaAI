package com.brachaai.app

/**
 * Reduces any spelling of a phone number to a single comparison key.
 *
 * Used on BOTH sides of every comparison — the ringing number from the telephony broadcast
 * and the `phone` string stored on each cached contact — so the two can only match if they
 * agree here. This is the only place number formats are interpreted; the backend never sees
 * a phone number.
 */
object PhoneNormalizer {

    /** Israel. A different deployment changes this one constant. */
    const val DEFAULT_COUNTRY_CODE = "972"

    /** Length of an Israeli national significant number, and the fallback truncation width. */
    private const val KEY_LENGTH = 9

    /** Below this, a "number" identifies nobody — short codes, mis-parses. */
    private const val MIN_KEY_LENGTH = 4

    /** Telephony sentinels for withheld, unavailable, and payphone callers. */
    private val WITHHELD = setOf("-1", "-2", "-3")

    fun key(raw: String?, countryCode: String = DEFAULT_COUNTRY_CODE): String? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()
        if (trimmed in WITHHELD) return null

        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // Only strip the country code when what remains is still a plausible number —
        // a bare 9-digit string starting with "972" is a national number, not a prefixed one.
        if (digits.length > KEY_LENGTH && digits.startsWith(countryCode)) {
            digits = digits.removePrefix(countryCode)
        }

        if (digits.startsWith("0")) {
            digits = digits.substring(1)
        }

        // Anything still over-length is foreign or oddly formatted. Truncating from the right
        // keeps the subscriber portion, which is what actually identifies the caller.
        if (digits.length > KEY_LENGTH) {
            digits = digits.takeLast(KEY_LENGTH)
        }

        return if (digits.length < MIN_KEY_LENGTH) null else digits
    }
}
