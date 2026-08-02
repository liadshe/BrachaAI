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
     * container, absent, nonsensical, or out-of-range metadata, or a zero-length recording.
     *
     * Never throws, including from allocating the retriever itself: its constructor calls
     * into native code and can throw when the native retriever can't be allocated, which is
     * exactly the kind of resource pressure this runs under (a large recording plus an
     * FFmpeg transcode already in flight). This runs on the upload path, where the
     * transcript is the thing worth protecting; a duration is not worth failing a call over.
     */
    fun secondsOf(file: File): Int? {
        val retriever = try {
            MediaMetadataRetriever()
        } catch (e: Exception) {
            Log.w(TAG, "Could not create a metadata retriever", e)
            return null
        }
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()

            if (millis == null || millis <= 0) {
                Log.d(TAG, "No usable duration in ${file.name}")
                null
            } else {
                // millis + 500 can overflow a Long back into negative territory on
                // corrupt-container metadata near Long.MAX_VALUE, and the overflowed
                // value would then truncate silently through .toInt(). Check the
                // converted result, not just the raw millis, so a bogus duration
                // still comes out as "unknown" rather than a negative Int.
                (((millis + 500) / 1000).toInt()).takeIf { it > 0 }
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
