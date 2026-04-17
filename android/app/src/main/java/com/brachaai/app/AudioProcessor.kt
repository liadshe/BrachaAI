package com.brachaai.app

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AudioProcessor(private val openAiApiKey: String) {

    private val whisperClient = WhisperApiClient(openAiApiKey)

    suspend fun processAndSendToBackend(audioFile: File) {
        withContext(Dispatchers.IO) {
            try {
                println("1. Starting processing for: ${audioFile.name}")

                // 2. Parse the filename
                val parsedInfo = parseFilename(audioFile.name)
                println("2. Parsed Info - Name: ${parsedInfo.contactName}, Date: ${parsedInfo.date}")

                // 3. CONVERT THE AUDIO TO A TRUE MP3
                println("3. Converting audio to true MP3 format...")
                val mp3File = convertToMp3(audioFile)

                if (mp3File == null) {
                    println("ERROR: Audio conversion failed. Stopping process.")
                    return@withContext
                }

                // 4. Send the new MP3 file to Whisper AI
                println("4. Uploading MP3 to Whisper...")
                val transcriptText = whisperClient.transcribeAudio(mp3File)
                println("5. Whisper Transcript: $transcriptText")

                // 5. Send data to Node.js Backend
                println("6. Sending data to backend...")
                sendDataToNodeServer(parsedInfo, transcriptText)

                // Optional: Clean up the mp3 file after we are done so we don't waste phone storage
                mp3File.delete()

            } catch (e: Exception) {
                println("Error during processing: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Uses FFmpeg to convert ANY audio file into a standard 128k MP3.
     */
    private fun convertToMp3(inputFile: File): File? {
        // Create a new file name: originalName.mp3
        val outputFile = File(inputFile.parent, "${inputFile.nameWithoutExtension}.mp3")

        // If an old test file is stuck there, delete it first
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // Build the FFmpeg command
        // -i = input file
        // -vn = skip video (just in case)
        // -ar 44100 -ac 2 -b:a 128k = standard mp3 audio quality
        val command = "-i \"${inputFile.absolutePath}\" -vn -ar 44100 -ac 2 -b:a 128k \"${outputFile.absolutePath}\""

        // Run the conversion!
        val session = FFmpegKit.execute(command)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            println("Conversion Success! Saved to: ${outputFile.name}")
            outputFile
        } else {
            println("Conversion Failed! FFmpeg logs: ${session.failStackTrace}")
            null
        }
    }

    private fun sendDataToNodeServer(parsedInfo: ParsedFile, transcript: String) {
        val client = OkHttpClient()

        val jsonBody = JSONObject().apply {
            put("contactName", parsedInfo.contactName)
            put("date", parsedInfo.date)
            put("transcript", transcript)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("http://10.0.2.2:3000/api/calls")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                println("SUCCESS! Data sent to backend: ${response.body?.string()}")
            } else {
                println("FAILED to send to backend. Code: ${response.code}")
            }
        }
    }
}