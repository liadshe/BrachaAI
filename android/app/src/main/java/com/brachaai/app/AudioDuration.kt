package com.brachaai.app

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * How long a recording runs, in whole seconds.
 *
 * The fallback for [CallerLookup] — the call log is the truth about the *call*, this is
 * only the truth about the *file* — but `READ_CALL_LOG` is optional and never gates the
 * app, so without this an install missing that permission would never show a duration.
 *
 * Uses the platform's [MediaMetadataRetriever] rather than the already-linked FFmpegKit:
 * one metadata read needs no transcoder, and this way the fallback adds no dependency.
 */
class AudioDuration {

    /**
     * Whole seconds, or `null` for anything we cannot measure — missing file, unreadable
     * container, absent or nonsensical metadata, or a zero-length recording.
     *
     * Never throws. This runs on the upload path, where the transcript is the thing worth
     * protecting; a duration is not worth failing a call over.
     */
    fun secondsOf(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()

            if (millis == null || millis <= 0) {
                Log.d(TAG, "No usable duration in ${file.name}")
                null
            } else {
                ((millis + 500) / 1000).toInt()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read duration from ${file.name}", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Could not release the metadata retriever", e)
            }
        }
    }

    companion object {
        private const val TAG = "AudioDuration"
    }
}
