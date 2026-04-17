package com.brachaai.app

// A simple data class to hold our extracted info
data class ParsedFile(
    val contactName: String,
    val date: String, // Format: YYMMDD
    val time: String  // Format: HHMMSS
)

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
    val contactName = parts.dropLast(2).joinToString("_")

    return ParsedFile(contactName, date, time)
}