package com.brachaai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic — no Android, no Robolectric. Wi-Fi/cellular handover fires the connectivity
 * callback repeatedly within seconds, and each firing would otherwise start a full sweep.
 */
class NetworkDebouncerTest {

    @Test
    fun theFirstNetworkEventIsAlwaysAccepted() {
        assertTrue(NetworkDebouncer().accept(1_000L))
    }

    @Test
    fun aSecondEventInsideTheWindowIsDropped() {
        val debouncer = NetworkDebouncer(windowMs = 30_000L)

        assertTrue(debouncer.accept(1_000L))
        assertFalse(debouncer.accept(5_000L))
        assertFalse(debouncer.accept(30_999L))
    }

    @Test
    fun anEventPastTheWindowIsAcceptedAgain() {
        val debouncer = NetworkDebouncer(windowMs = 30_000L)
        assertTrue(debouncer.accept(1_000L))

        assertTrue(debouncer.accept(31_000L))
    }

    @Test
    fun aDroppedEventDoesNotExtendTheWindow() {
        // Otherwise a network flapping every 5s would hold the sweep off indefinitely.
        val debouncer = NetworkDebouncer(windowMs = 30_000L)
        assertTrue(debouncer.accept(0L))
        assertFalse(debouncer.accept(10_000L))
        assertFalse(debouncer.accept(20_000L))

        assertTrue(debouncer.accept(30_000L))
    }

    @Test
    fun aClockThatJumpsBackwardsStillLetsEventsThrough() {
        // SystemClock/wall-clock adjustments must not wedge the sweep off forever.
        val debouncer = NetworkDebouncer(windowMs = 30_000L)
        assertTrue(debouncer.accept(1_000_000L))

        assertTrue(debouncer.accept(500L))
    }
}
