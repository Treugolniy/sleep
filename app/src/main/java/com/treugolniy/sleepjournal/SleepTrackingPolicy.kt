package com.treugolniy.sleepjournal

/** Determines how an Android unlock event affects a user-started sleep session. */
internal enum class UnlockAction {
    RESET_SLEEP_START,
    FINISH_SLEEP_SESSION
}

internal object SleepTrackingPolicy {
    const val MINIMUM_SLEEP_WINDOW_MILLIS: Long = 2L * 60L * 60L * 1000L

    /**
     * An unlock before two full hours resets the start time to that unlock.
     * An unlock at exactly two hours or later completes the session.
     */
    fun actionForUnlock(startedAt: Long, unlockedAt: Long): UnlockAction {
        val elapsed = (unlockedAt - startedAt).coerceAtLeast(0L)
        return if (elapsed < MINIMUM_SLEEP_WINDOW_MILLIS) {
            UnlockAction.RESET_SLEEP_START
        } else {
            UnlockAction.FINISH_SLEEP_SESSION
        }
    }
}
