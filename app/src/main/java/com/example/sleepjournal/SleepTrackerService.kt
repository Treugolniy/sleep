package com.example.sleepjournal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SleepTrackerService : Service() {
    private var receiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                val startedAt = SleepPrefs.startedAt(this@SleepTrackerService)
                val nightDate = SleepPrefs.nightDate(this@SleepTrackerService)
                val unlockedAt = System.currentTimeMillis()
                if (startedAt > 0L && nightDate != null) {
                    when (SleepTrackingPolicy.actionForUnlock(startedAt, unlockedAt)) {
                        UnlockAction.RESET_SLEEP_START -> restartSleepWindow(nightDate, unlockedAt)
                        UnlockAction.FINISH_SLEEP_SESSION -> finishSession(unlockedAt)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
                val nightDate = intent.getStringExtra(EXTRA_NIGHT_DATE)
                    ?: LocalDate.now().toString()
                SleepPrefs.start(this, startedAt, nightDate)
                SleepDatabase(this).startNight(nightDate, startedAt)
                activateTracking()
            }
            ACTION_STOP -> finishSession()
            else -> {
                if (SleepPrefs.startedAt(this) > 0L && SleepPrefs.nightDate(this) != null) {
                    activateTracking()
                } else {
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun activateTracking() {
        if (!receiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(unlockReceiver, filter)
            }
            receiverRegistered = true
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun restartSleepWindow(previousNightDate: String, newStartedAt: Long) {
        val newNightDate = Instant.ofEpochMilli(newStartedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        // Move the provisional entry if the reset crossed midnight; otherwise update its start.
        SleepDatabase(this).restartNight(previousNightDate, newNightDate, newStartedAt)
        SleepPrefs.start(this, newStartedAt, newNightDate)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        sendBroadcast(Intent(ACTION_SLEEP_RESTARTED).setPackage(packageName))
    }

    private fun finishSession(endedAt: Long = System.currentTimeMillis()) {
        val nightDate = SleepPrefs.nightDate(this)
        if (nightDate != null && SleepPrefs.startedAt(this) > 0L) {
            SleepDatabase(this).finishNight(nightDate, endedAt)
        }
        SleepPrefs.clear(this)
        sendBroadcast(Intent(ACTION_SLEEP_ENDED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, SleepTrackerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Дневник сна: идёт запись")
            .setContentText("До 2 ч разблокировка сбросит начало; после 2 ч — завершит запись")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Завершить", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Отслеживание сна",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает, пока идёт запись ночного сна"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.sleepjournal.START_SLEEP"
        const val ACTION_STOP = "com.example.sleepjournal.STOP_SLEEP"
        const val ACTION_SLEEP_ENDED = "com.example.sleepjournal.SLEEP_ENDED"
        const val ACTION_SLEEP_RESTARTED = "com.example.sleepjournal.SLEEP_RESTARTED"
        const val EXTRA_STARTED_AT = "extra_started_at"
        const val EXTRA_NIGHT_DATE = "extra_night_date"

        private const val CHANNEL_ID = "sleep_tracking"
        private const val NOTIFICATION_ID = 4201
    }
}
