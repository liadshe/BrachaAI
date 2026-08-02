package com.brachaai.app

import android.media.MediaMetadataRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioDurationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val duration = AudioDuration()

    private fun recordingWithDurationMs(name: String, ms: String?): java.io.File {
        val file = tempFolder.newFile(name)
        if (ms != null) {
            ShadowMediaMetadataRetriever.addMetadata(
                file.absolutePath,
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                ms
            )
        }
        return file
    }

    @Test
    fun readsTheDurationInWholeSeconds() {
        val file = recordingWithDurationMs("call.m4a", "272000")

        assertEquals(272, duration.secondsOf(file))
    }

    @Test
    fun roundsToTheNearestSecond() {
        val file = recordingWithDurationMs("call.m4a", "272600")

        assertEquals(273, duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownWhenTheFileCarriesNoDuration() {
        val file = recordingWithDurationMs("silent.m4a", null)

        assertNull(duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownForAZeroLengthRecording() {
        val file = recordingWithDurationMs("empty.m4a", "0")

        assertNull(duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownForUnparseableMetadata() {
        val file = recordingWithDurationMs("weird.m4a", "not-a-number")

        assertNull(duration.secondsOf(file))
    }

    @Test
    fun reportsUnknownForAMissingFileRatherThanThrowing() {
        // Must never throw: this runs on the upload path, and a duration we cannot
        // measure must not cost a transcript.
        assertNull(duration.secondsOf(java.io.File(tempFolder.root, "gone.m4a")))
    }
}
