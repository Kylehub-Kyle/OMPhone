package com.omphone.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 前台服务：维持 PTP/IP 会话、监听相机事件、落盘。
 * 断线自动重连（相机休眠/热点的 DHCP 变动都能自愈）。
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1001
        const val ACTION_CONNECT = "com.omphone.capture.CONNECT"
        const val ACTION_DISCONNECT = "com.omphone.capture.DISCONNECT"
        const val EXTRA_HOST = "host"

        fun connect(ctx: Context, host: String) {
            val i = Intent(ctx, CaptureService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, host)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun disconnect(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).apply { action = ACTION_DISCONNECT })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var camera: OmCamera? = null
    @Volatile private var host: String = ""
    @Volatile private var active = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                AppBus.edit { it.copy(error = "") }
                stopCapture("未连接")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val h = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
                if (h.isEmpty()) return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("正在连接 $h …"))
                if (active && h == host) return START_STICKY
                // 结束上一个会话（顺带清掉上一台相机的型号/日志），再开新的
                stopCapture("连接中…")
                host = h
                active = true
                AppBus.edit { it.copy(running = true, connected = false, stage = "连接中…", host = h, error = "") }
                acquireWakeLock()
                job = scope.launch { supervise(h) }
            }
        }
        return START_STICKY
    }

    /** 连接 → 监听 → 断开后自动重连。 */
    private suspend fun supervise(target: String) {
        var attempt = 0
        while (active) {
            attempt++
            // 本轮会话的结束原因：fatal 表示配置类错误，重连无意义
            var stopReason = ""
            var fatalStop = false
            val cam = OmCamera(
                host = target,
                openSink = { name, kind, size -> MediaSaver(this).open(name, kind, size) },
                rename = { orig, kind -> Naming.apply(this, orig, kind) },
                accept = { nf -> Prefs.isEnabled(this, nf.slot, nf.kind) },
                cb = object : OmCamera.Callback {
                    override fun onStage(text: String) {
                        AppBus.edit { it.copy(stage = text) }
                        notify("$text · ${AppBus.state.value.device}")
                    }

                    override fun onDevice(text: String) {
                        AppBus.edit { it.copy(device = text, connected = true, error = "") }
                    }

                    override fun onLog(text: String) {
                        Log.i(TAG, text)
                        AppBus.edit { it.copy(logLine = text) }
                    }

                    override fun onStart(name: String, orig: String, kind: FileKind, size: Long) {
                        AppBus.upsertTransfer(TransferUi(name, kind, size, 0, "downloading", orig = orig))
                        AppBus.edit { s -> s.copy(waiting = s.transfers.count { it.state == "downloading" }) }
                    }

                    override fun onProgress(orig: String, done: Long, total: Long) {
                        AppBus.edit { s ->
                            val list = s.transfers.map {
                                if (it.orig == orig) it.copy(done = done, size = total) else it
                            }
                            s.copy(transfers = list)
                        }
                    }

                    override fun onSaved(name: String, orig: String, kind: FileKind, size: Long, uri: String) {
                        AppBus.edit { s ->
                            val list = s.transfers.map {
                                if (it.orig == orig) it.copy(state = "saved", done = size, uri = uri) else it
                            }
                            s.copy(
                                transfers = list,
                                savedCount = s.savedCount + 1,
                                savedBytes = s.savedBytes + size,
                                waiting = list.count { t -> t.state == "downloading" }
                            )
                        }
                        notify("已保存 $name（${fmtSize(size)}）")
                    }

                    override fun onFailed(name: String, orig: String, reason: String) {
                        AppBus.edit { s ->
                            val list = s.transfers.map {
                                if (it.orig == orig) it.copy(state = "failed", note = reason) else it
                            }
                            s.copy(
                                transfers = list,
                                waiting = list.count { t -> t.state == "downloading" }
                            )
                        }
                        notify("$name 失败：$reason")
                    }

                    override fun onStopped(reason: String, fatal: Boolean) {
                        val wasConnected = AppBus.state.value.connected
                        AppBus.edit {
                            it.copy(connected = false, stage = "连接失败", error = reason)
                        }
                        // 之前不管什么阶段都写「连接中断」，没连上过的时候是错的
                        notify(if (wasConnected) "连接中断：$reason" else "连接失败：$reason")
                        stopReason = reason
                        fatalStop = fatal
                    }
                }
            )
            camera = cam
            try {
                cam.runSession()
            } catch (e: Exception) {
                Log.w(TAG, "session ended", e)
            }
            cam.stop()
            if (!active) break
            if (fatalStop) {
                // 配置错误（GUID 缺失/格式错等）：重试只会刷屏，直接停下等用户改配置
                active = false
                releaseWakeLock()
                AppBus.edit {
                    it.copy(running = false, connected = false, device = "", host = "", waiting = 0)
                }
                stopForeground(true)
                stopSelf()
                break
            }
            AppBus.edit { it.copy(connected = false, device = "") }
            val wait = minOf(5 * attempt, 20)
            notify("连接中断，${wait}s 后重连…")
            delay(wait * 1000L)
        }
    }

    private fun stopCapture(stage: String?) {
        val wasRunning = active || AppBus.state.value.running
        active = false
        camera?.stop()
        camera = null
        job?.cancel()
        job = null
        releaseWakeLock()
        AppBus.edit {
            // 会话结束就把「连上相机才有的信息」一起清掉，
            // 否则断开后界面上还挂着上一台相机的型号和日志
            it.copy(
                running = false,
                connected = false,
                stage = stage ?: if (wasRunning) "未连接" else it.stage,
                device = "",
                host = "",
                logLine = "",
                waiting = 0
            )
        }
    }

    override fun onDestroy() {
        stopCapture(null)
        scope.cancel()
        super.onDestroy()
    }

    // ---- 通知 ----
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "相机连接", NotificationManager.IMPORTANCE_LOW)
            ch.description = "OM Phone 与相机保持连接时显示"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("OM Phone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OMPhone:capture").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
