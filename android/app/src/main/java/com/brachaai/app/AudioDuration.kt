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
                // corrupt-container metadata near Long.MAX_VALUE. Range-check the
                // computed seconds as a Long, before truncating to Int: an overflowed
                // negative Long keeps only its low 32 bits through .toInt(), which does
                // not reliably land negative (e.g. Long.MAX_VALUE here truncates to a
                // positive ~1.5 billion), so checking the Int would let a bogus
                // duration through as a huge-but-plausible-looking call length. Checking
                // the Long instead — and rejecting anything above Int.MAX_VALUE too, not
                // just anything <= 0 — means a bogus duration still comes out as
                // "unknown" rather than a corrupted-but-live seconds value.
                val seconds = (millis + 500) / 1000
                seconds.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
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
