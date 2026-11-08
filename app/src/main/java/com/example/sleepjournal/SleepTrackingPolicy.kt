package com.example.sleepjournal

/** Rules for interpreting device-unlock events during an active sleep session. */
internal enum class UnlockAction {
    RESET_SLEEP_START,
    FINISH_SLEEP_SESSION
}

internal object SleepTrackingPolicy {
    const val MINIMUM_SLEEP_WINDOW_MILLIS: Long = 2L * 60L * 60L * 1000L

    /**
     * An unlock before the two-hour threshold is treated as the actual bedtime marker:
     * the start timestamp moves to that unlock. At or after two hours, the unlock ends the night.
     */
    fun actionForUnlock(startedAt: Long, unlockedAt: Long): UnlockAction {
        val elapsed = unlockedAt - startedAt
        return if (elapsed < MINIMUM_SLEEP_WINDOW_MILLIS) {
            UnlockAction.RESET_SLEEP_START
        } else {
            UnlockAction.FINISH_SLEEP_SESSION
        }
    }
}
