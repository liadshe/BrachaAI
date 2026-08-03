package com.brachaai.app

// A simple data class to hold our extracted info
data class ParsedFile(
    val contactName: String,
    val date: String, // Format: YYMMDD
    val time: String  // Format: HHMMSS
)

/**
 * The label recorders put in front of the other party's name — "Call Mom_260415_165702".
 * It names the recording, not the person, so it must not become the contact.
 *
 * Anchored and followed by a separator so a contact genuinely called "Callie" or
 * "Caller ID" is left alone, and applied once so "Call Call Center" keeps its name.
 */
private val CALL_PREFIX = Regex("^call[ _]+", RegexOption.IGNORE_CASE)

/**
 * Strips the recorder's label. A filename that is *only* the label ("Call_260415_165702")
 * keeps it — a nameless contact is worse than a badly named one.
 */
private fun String.withoutCallPrefix(): String {
    val stripped = CALL_PREFIX.replaceFirst(this, "").trim()
    return if (stripped.isEmpty()) this.trim() else stripped
}

fun parseFilename(filename: String): ParsedFile {
    // 1. Remove the file extension (e.g., ".m4a")
    val nameWithoutExtension = filename.substringBeforeLast(".")

    // 2. Split the string by the underscore character
    val parts = nameWithoutExtension.split("_")

    // 3. Ensure we have at least Name, Date, and Time parts
    if (parts.size < 3) {
        throw IllegalArgumentException("Invalid filename format. Expected Name_YYMMDD_HHMMSS")
    }

    // 4. Extract from right to left (this safely handles names that have spaces or underscores)
    val time = parts.last()
    val date = parts[parts.size - 2]
    val contactName = parts.dropLast(2).joinToString("_").withoutCallPrefix()

    return ParsedFile(contactName, date, time)
}

/**
 * Converts the filename's date + time (YYMMDD + HHMMSS, device local time) into
 * epoch millis. Returns null when the filename carried an unparseable stamp.
 */
fun ParsedFile.toEpochMillis(): Long? = try {
    java.text.SimpleDateFormat("yyMMddHHmmss", java.util.Locale.US)
        .apply { isLenient = false }
        .parse(date + time)
        ?.time
} catch (e: Exception) {
    null
}