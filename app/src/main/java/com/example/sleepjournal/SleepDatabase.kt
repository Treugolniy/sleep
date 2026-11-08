package com.example.sleepjournal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SleepDatabase(context: Context) : SQLiteOpenHelper(context, "sleep_journal.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sleep_entries (
                night_date TEXT PRIMARY KEY NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                mood INTEGER,
                rested INTEGER,
                energy INTEGER,
                stress INTEGER,
                awakenings INTEGER,
                caffeine_late INTEGER,
                nap INTEGER,
                note TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Increase the database version and add migrations here when the data model changes.
    }

    fun startNight(nightDate: String, startedAt: Long) {
        val values = ContentValues().apply {
            put("night_date", nightDate)
            put("started_at", startedAt)
            putNull("ended_at")
        }
        writableDatabase.insertWithOnConflict("sleep_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun restartNight(previousNightDate: String, newNightDate: String, startedAt: Long) {
        if (previousNightDate != newNightDate) {
            writableDatabase.delete(
                "sleep_entries",
                "night_date = ? AND ended_at IS NULL",
                arrayOf(previousNightDate)
            )
        }
        startNight(newNightDate, startedAt)
    }

    fun finishNight(nightDate: String, endedAt: Long) {
        val values = ContentValues().apply { put("ended_at", endedAt) }
        writableDatabase.update("sleep_entries", values, "night_date = ?", arrayOf(nightDate))
    }

    fun saveAnswers(entry: SleepEntry) {
        val values = ContentValues().apply {
            put("mood", entry.mood)
            if (entry.rested == null) putNull("rested") else put("rested", if (entry.rested) 1 else 0)
            put("energy", entry.energy)
            put("stress", entry.stress)
            put("awakenings", entry.awakenings)
            if (entry.caffeineLate == null) putNull("caffeine_late") else put("caffeine_late", if (entry.caffeineLate) 1 else 0)
            if (entry.nap == null) putNull("nap") else put("nap", if (entry.nap) 1 else 0)
            put("note", entry.note)
        }
        writableDatabase.update("sleep_entries", values, "night_date = ?", arrayOf(entry.nightDate))
    }

    fun getAll(): List<SleepEntry> {
        val result = mutableListOf<SleepEntry>()
        readableDatabase.query(
            "sleep_entries", null, null, null, null, null, "night_date DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun nullableInt(column: String): Int? {
                    val index = cursor.getColumnIndexOrThrow(column)
                    return if (cursor.isNull(index)) null else cursor.getInt(index)
                }
                fun nullableLong(column: String): Long? {
                    val index = cursor.getColumnIndexOrThrow(column)
                    return if (cursor.isNull(index)) null else cursor.getLong(index)
                }
                fun nullableBoolean(column: String): Boolean? = nullableInt(column)?.let { it != 0 }

                result += SleepEntry(
                    nightDate = cursor.getString(cursor.getColumnIndexOrThrow("night_date")),
                    startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    endedAt = nullableLong("ended_at"),
                    mood = nullableInt("mood"),
                    rested = nullableBoolean("rested"),
                    energy = nullableInt("energy"),
                    stress = nullableInt("stress"),
                    awakenings = nullableInt("awakenings"),
                    caffeineLate = nullableBoolean("caffeine_late"),
                    nap = nullableBoolean("nap"),
                    note = cursor.getString(cursor.getColumnIndexOrThrow("note")) ?: ""
                )
            }
        }
        return result
    }
}

object SleepPrefs {
    private const val FILE = "sleep_tracker_state"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_NIGHT_DATE = "night_date"

    fun start(context: Context, startedAt: Long, nightDate: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STARTED_AT, startedAt)
            .putString(KEY_NIGHT_DATE, nightDate)
            .apply()
    }

    fun startedAt(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_STARTED_AT, 0L)

    fun nightDate(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_NIGHT_DATE, null)

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_STARTED_AT)
            .remove(KEY_NIGHT_DATE)
            .apply()
    }
}
