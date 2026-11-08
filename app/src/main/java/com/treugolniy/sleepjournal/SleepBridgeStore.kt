package com.treugolniy.sleepjournal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** SharedPreferences-backed state and durable event queue between the service and WebView. */
internal object SleepBridgeStore {
    private const val PREFS = "sleep_native_bridge"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_NIGHT_DATE = "night_date"
    private const val KEY_EVENTS = "pending_events"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context, startedAt: Long, nightDate: String) {
        prefs(context).edit()
            .putLong(KEY_STARTED_AT, startedAt)
            .putString(KEY_NIGHT_DATE, nightDate)
            .commit()
    }

    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED_AT, 0L)
    fun nightDate(context: Context): String? = prefs(context).getString(KEY_NIGHT_DATE, null)

    fun isActive(context: Context): Boolean = startedAt(context) > 0L && nightDate(context) != null

    fun stateJson(context: Context): String {
        val started = startedAt(context)
        val date = nightDate(context)
        return JSONObject()
            .put("active", started > 0L && date != null)
            .put("startedAt", if (started > 0L) started else JSONObject.NULL)
            .put("nightDate", date ?: JSONObject.NULL)
            .toString()
    }

    fun recordReset(context: Context, previousNightDate: String, newNightDate: String, newStartedAt: Long) {
        val preferences = prefs(context)
        val events = readEvents(preferences.getString(KEY_EVENTS, "[]"))
        events.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "reset")
                .put("previousNightDate", previousNightDate)
                .put("nightDate", newNightDate)
                .put("startedAt", newStartedAt)
                .put("at", newStartedAt)
        )
        preferences.edit()
            .putLong(KEY_STARTED_AT, newStartedAt)
            .putString(KEY_NIGHT_DATE, newNightDate)
            .putString(KEY_EVENTS, events.toString())
            .commit()
    }

    fun recordFinish(context: Context, endedAt: Long) {
        val preferences = prefs(context)
        val started = preferences.getLong(KEY_STARTED_AT, 0L)
        val date = preferences.getString(KEY_NIGHT_DATE, null)
        val editor = preferences.edit()
        if (started > 0L && date != null) {
            val events = readEvents(preferences.getString(KEY_EVENTS, "[]"))
            events.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("type", "finish")
                    .put("nightDate", date)
                    .put("startedAt", started)
                    .put("endedAt", endedAt)
                    .put("at", endedAt)
            )
            editor.putString(KEY_EVENTS, events.toString())
        }
        editor.remove(KEY_STARTED_AT)
            .remove(KEY_NIGHT_DATE)
            .commit()
    }

    fun pendingEventsJson(context: Context): String =
        prefs(context).getString(KEY_EVENTS, "[]") ?: "[]"

    fun acknowledgeEvents(context: Context, idsJson: String) {
        val ids = mutableSetOf<String>()
        val incoming = JSONArray(idsJson)
        for (index in 0 until incoming.length()) ids += incoming.optString(index)
        if (ids.isEmpty()) return

        val preferences = prefs(context)
        val existing = readEvents(preferences.getString(KEY_EVENTS, "[]"))
        val remaining = JSONArray()
        for (index in 0 until existing.length()) {
            val event = existing.optJSONObject(index) ?: continue
            if (event.optString("id") !in ids) remaining.put(event)
        }
        preferences.edit().putString(KEY_EVENTS, remaining.toString()).commit()
    }

    private fun readEvents(source: String?): JSONArray = try {
        JSONArray(source ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}
