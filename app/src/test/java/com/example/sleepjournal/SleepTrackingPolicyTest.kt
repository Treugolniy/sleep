package com.example.sleepjournal

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTrackingPolicyTest {
    private val start = 1_000_000L
    private val twoHours = SleepTrackingPolicy.MINIMUM_SLEEP_WINDOW_MILLIS

    @Test
    fun unlockBeforeTwoHoursResetsSleepStart() {
        assertEquals(
            UnlockAction.RESET_SLEEP_START,
            SleepTrackingPolicy.actionForUnlock(start, start + 30 * 60 * 1000L)
        )
    }

    @Test
    fun unlockOneMillisecondBeforeTwoHoursResetsSleepStart() {
        assertEquals(
            UnlockAction.RESET_SLEEP_START,
            SleepTrackingPolicy.actionForUnlock(start, start + twoHours - 1L)
        )
    }

    @Test
    fun unlockAtExactlyTwoHoursFinishesSession() {
        assertEquals(
            UnlockAction.FINISH_SLEEP_SESSION,
            SleepTrackingPolicy.actionForUnlock(start, start + twoHours)
        )
    }

    @Test
    fun unlockAfterTwoHoursFinishesSession() {
        assertEquals(
            UnlockAction.FINISH_SLEEP_SESSION,
            SleepTrackingPolicy.actionForUnlock(start, start + twoHours + 1L)
        )
    }
}
