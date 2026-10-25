package com.alican.devicescope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.UUID

/**
 * Holds the web server alive while the user wants remote monitoring.
 * Runs as a foreground service so Android does not kill it in the background.
 */
class MonitorService : Service() {

    private var device: List<Section> = emptyList()
    private var history: List<Section> = emptyList()
    private var cacheTime = 0L

    private val thermalCollector by lazy { ThermalCollector(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServerState.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        startForeground(NOTIFICATION_ID, buildNotification("Başlatılıyor…"))

        Thread({
            val collector = DeviceInfoCollector(applicationContext)
            val historyCollector = HistoryCollector(applicationContext)
            device = collector.collect()
            history = historyCollector.collect()
            cacheTime = System.currentTimeMillis()

            val token = ServerState.ensureToken(applicationContext)
            var chosenPort = port
            var server: WebServer? = null
            for (candidate in port until port + 12) {
                val s = WebServer(applicationContext, candidate, token, { payload() }, { benchmarksPayload() })
                if (s.start()) {
                    chosenPort = candidate
                    server = s
                    break
                }
            }

            if (server == null) {
                ServerState.lastError = "Port açılamadı (${port}–${port + 11} dolu)"
                stopSelf()
                return@Thread
            }

            ServerState.port = chosenPort
            ServerState.server = server
            ServerState.ip = WebServer.localIpv4()
            ServerState.lastError = null

            notify(buildNotification(buildString {
                append(ServerState.ip ?: "IP yok")
                append(":")
                append(chosenPort)
                append(" · anahtar ")
                append(token.take(6))
                append("…")
            }))
        }, "ds-server-boot").start()

        return START_STICKY
    }

    private fun payload(): String {
        if (device.isEmpty() || System.currentTimeMillis() - cacheTime > 120_000L) {
            try {
                device = DeviceInfoCollector(applicationContext).collect()
                history = HistoryCollector(applicationContext).collect()
                cacheTime = System.currentTimeMillis()
            } catch (_: Throwable) {
            }
        }
        val snap = thermalCollector.read()
        return JsonReport.state(device, history, snap)
    }

    /**
     * Benchmark index for the browser panel: summaries of every saved run. Only
     * values that were actually measured are exported; missing categories become
     * JSON null so the panel can leave the cell empty instead of showing a zero.
     */
    private fun benchmarksPayload(): String {
        val runs = try {
            com.alican.devicescope.bench.ResultStore.summaries(applicationContext)
        } catch (_: Throwable) {
            emptyList()
        }
        val sb = StringBuilder("{\"runs\":[")
        runs.forEachIndexed { index, r ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"suite\":\"").append(r.suite).append("\",")
            sb.append("\"timestamp\":").append(r.timestamp).append(',')
            sb.append("\"device\":\"").append(escape(r.device)).append("\",")
            sb.append("\"total\":").append(num(r.total)).append(',')
            sb.append("\"single\":").append(numOrNull(r.single)).append(',')
            sb.append("\"multi\":").append(numOrNull(r.multi)).append(',')
            sb.append("\"memory\":").append(numOrNull(r.memory)).append(',')
            sb.append("\"storage\":").append(numOrNull(r.storage)).append(',')
            sb.append("\"gpu\":").append(numOrNull(r.gpu)).append(',')
            sb.append("\"ux\":").append(numOrNull(r.ux)).append(',')
            sb.append("\"sustained\":").append(numOrNull(r.sustained)).append(',')
            sb.append("\"stability\":").append(numOrNull(r.stability)).append(',')
            sb.append("\"api\":").append(if (r.api == null) "null" else "\"${escape(r.api)}\"")
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun num(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun numOrNull(v: Double?): String =
        if (v == null || v <= 0.0 || v.isNaN()) "null" else num(v)

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DeviceScope · uzaktan izleme açık")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pending)

        val stop = Intent(this, MonitorService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Durdur", stopPending).build()
        )
        return builder.build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Cihaz izleme", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Uzaktan izleme sunucusu çalışırken gösterilir"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        ServerState.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.alican.devicescope.STOP"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "devicescope_monitor"
        private const val NOTIFICATION_ID = 4201

        fun start(ctx: Context, port: Int) {
            val intent = Intent(ctx, MonitorService::class.java).putExtra(EXTRA_PORT, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}

object ServerState {

    @Volatile
    var server: WebServer? = null

    @Volatile
    var port: Int = 8080

    @Volatile
    var ip: String? = null

    @Volatile
    var lastError: String? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun ensureToken(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("devicescope", Context.MODE_PRIVATE)
        var token = prefs.getString("token", null)
        if (token.isNullOrBlank()) {
            token = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("token", token).apply()
        }
        return token
    }

    fun token(ctx: Context): String = ensureToken(ctx)

    fun url(ctx: Context): String? {
        val host = ip ?: return null
        return "http://$host:$port/?k=${token(ctx)}"
    }

    fun stop() {
        server?.shutdown()
        server = null
    }
}
