package com.treugolniy.sleepjournal

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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Runs only after the user explicitly starts a sleep session in the app. */
class SleepTrackerService : Service() {
    private var receiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT) return

            val startedAt = SleepBridgeStore.startedAt(this@SleepTrackerService)
            val nightDate = SleepBridgeStore.nightDate(this@SleepTrackerService) ?: return
            if (startedAt <= 0L) return

            val unlockedAt = System.currentTimeMillis()
            when (SleepTrackingPolicy.actionForUnlock(startedAt, unlockedAt)) {
                UnlockAction.RESET_SLEEP_START -> {
                    val newDate = Instant.ofEpochMilli(unlockedAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                    SleepBridgeStore.recordReset(this@SleepTrackerService, nightDate, newDate, unlockedAt)
                    notificationManager().notify(NOTIFICATION_ID, buildNotification())
                }
                UnlockAction.FINISH_SLEEP_SESSION -> finishSession(unlockedAt)
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
                SleepBridgeStore.start(this, startedAt, nightDate)
                activateTracking()
                return START_STICKY
            }
            ACTION_STOP -> {
                finishSession(System.currentTimeMillis())
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            else -> {
                if (SleepBridgeStore.isActive(this)) {
                    activateTracking()
                    return START_STICKY
                }
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun activateTracking() {
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
    }

    private fun finishSession(endedAt: Long) {
        SleepBridgeStore.recordFinish(this, endedAt)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, SleepTrackerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            android.R.drawable.ic_media_pause,
            "Завершить",
            stopIntent
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Дневник сна · запись идёт")
            .setContentText("До 2 ч — перенос начала; через 2 ч — завершение")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Отслеживание сна",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает активную запись сна"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by the system or during shutdown.
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.treugolniy.sleepjournal.START_SLEEP"
        const val ACTION_STOP = "com.treugolniy.sleepjournal.STOP_SLEEP"
        const val EXTRA_STARTED_AT = "extra_started_at"
        const val EXTRA_NIGHT_DATE = "extra_night_date"

        private const val CHANNEL_ID = "sleep_tracking"
        private const val NOTIFICATION_ID = 4201
    }
}
