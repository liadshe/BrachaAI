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
import org.robolectric.shadows.util.DataSource

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
    fun reportsUnknownForAMissingFile() {
        // Robolectric's ShadowMediaMetadataRetriever.setDataSource(String) never stats the
        // file, so a missing file takes the same "no metadata registered" path as
        // reportsUnknownWhenTheFileCarriesNoDuration above — it does not exercise the
        // catch block. Kept as a cheap sanity check that a nonexistent path alone isn't
        // treated specially; reportsUnknownWhenTheRetrieverThrows below is what actually
        // covers the never-throws guarantee.
        assertNull(duration.secondsOf(java.io.File(tempFolder.root, "gone.m4a")))
    }

    @Test
    fun reportsUnknownWhenTheRetrieverThrows() {
        // Must never throw: this runs on the upload path, and a duration we cannot
        // measure must not cost a transcript. Robolectric only throws from
        // setDataSource when an exception has been pre-registered via addException,
        // so this is the one test that actually reaches the catch block.
        val file = tempFolder.newFile("broken.m4a")
        ShadowMediaMetadataRetriever.addException(
            DataSource.toDataSource(file.absolutePath),
            RuntimeException("setDataSource failed")
        )

        assertNull(duration.secondsOf(file))
    }
}
