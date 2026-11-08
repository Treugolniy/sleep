package com.example.sleepjournal

data class SleepEntry(
    val nightDate: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val mood: Int? = null,
    val rested: Boolean? = null,
    val energy: Int? = null,
    val stress: Int? = null,
    val awakenings: Int? = null,
    val caffeineLate: Boolean? = null,
    val nap: Boolean? = null,
    val note: String = ""
) {
    val durationMillis: Long?
        get() = endedAt?.let { (it - startedAt).coerceAtLeast(0L) }
}
