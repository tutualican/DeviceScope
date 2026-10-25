package com.alican.devicescope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.alican.devicescope.bench.BenchController

/**
 * Keeps the process alive and visible while the marathon runs.
 *
 * The service does not run measurements itself: [BenchController] owns the run so
 * that killing the activity (or rotating the phone) cannot interrupt 15 minutes
 * of work. The service only guarantees that Android keeps the process in the
 * foreground and gives the user a way to stop the run from the notification.
 */
class TestModeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            try {
                BenchController.shared(applicationContext).cancelMarathon()
            } catch (_: Throwable) {
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 15) ?: 15
        val text = intent?.getStringExtra(EXTRA_TEXT)
        val notification = buildNotification(minutes, text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceScope::marathon")
            lock.setReferenceCounted(false)
            lock.acquire((120 * 60 * 1000L))
            wakeLock = lock
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(minutes: Int, text: String?): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Test modu · $minutes dk maraton")
            .setContentText(text ?: "Ekranı kapatmayın, cihazı hareket ettirmeyin.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)

        val stop = Intent(this, TestModeService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Durdur", stopPending).build()
        )
        return builder.build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Test modu",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maraton çalışırken gösterilir"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.alican.devicescope.STOP_TEST_MODE"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "devicescope_test_mode"
        private const val NOTIFICATION_ID = 4210

        fun start(ctx: Context, minutes: Int, text: String? = null) {
            val intent = Intent(ctx, TestModeService::class.java)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun update(ctx: Context, minutes: Int, text: String) {
            try {
                start(ctx, minutes, text)
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TestModeService::class.java))
        }
    }
}
