package com.treugolniy.sleepjournal

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTrackingPolicyTest {
    private val start = 1_000_000L

    @Test
    fun unlockBeforeTwoHoursResetsStart() {
        assertEquals(
            UnlockAction.RESET_SLEEP_START,
            SleepTrackingPolicy.actionForUnlock(start, start + 1_999L)
        )
    }

    @Test
    fun unlockAtExactlyTwoHoursFinishesSession() {
        assertEquals(
            UnlockAction.FINISH_SLEEP_SESSION,
            SleepTrackingPolicy.actionForUnlock(start, start + SleepTrackingPolicy.MINIMUM_SLEEP_WINDOW_MILLIS)
        )
    }

    @Test
    fun unlockAfterTwoHoursFinishesSession() {
        assertEquals(
            UnlockAction.FINISH_SLEEP_SESSION,
            SleepTrackingPolicy.actionForUnlock(start, start + SleepTrackingPolicy.MINIMUM_SLEEP_WINDOW_MILLIS + 1L)
        )
    }

    @Test
    fun backwardsClockDoesNotProduceNegativeSleepInterval() {
        assertEquals(
            UnlockAction.RESET_SLEEP_START,
            SleepTrackingPolicy.actionForUnlock(start, start - 5_000L)
        )
    }
}
