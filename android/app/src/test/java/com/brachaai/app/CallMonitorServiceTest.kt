package com.brachaai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CallMonitorServiceTest {
    @Test
    fun watchPathPointsToCallRecordingsDirectory() {
        assertEquals(
            "/storage/emulated/0/Recordings/Call",
            CallMonitorService.WATCH_PATH
        )
    }
}
