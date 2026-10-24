package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Owns the update progress notification because OEM download managers may hide theirs. */
class UpdateDownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private var downloadId = -1L
    private var version = ""

    private val poll = object : Runnable {
        override fun run() {
            if (downloadId <= 0) {
                stopSelf()
                return
            }
            var status = DownloadManager.STATUS_PENDING
            var downloaded = 0L
            var total = -1L
            manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    stopSelf()
                    return
                }
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> finishNotification("新版本 $version 已下载，点击继续安装")
                DownloadManager.STATUS_FAILED -> finishNotification("升级安装包下载失败，点击返回重试")
                else -> {
                    val percent = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(percent, true))
                    handler.postDelayed(this, 750)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "应用升级", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示升级安装包的下载进度"
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("app_update", Context.MODE_PRIVATE)
        downloadId = intent?.getLongExtra(EXTRA_ID, -1L)?.takeIf { it > 0 } ?: prefs.getLong("download_id", -1L)
        version = intent?.getStringExtra(EXTRA_VERSION) ?: prefs.getString("version", "新版本").orEmpty()
        startForeground(NOTIFICATION_ID, notification(-1, true))
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_STICKY
    }

    private fun notification(percent: Int, ongoing: Boolean, overrideText: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = overrideText ?: if (percent >= 0) "安装包下载中 · $percent%" else "正在连接下载服务器…"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("HDResourceExtractor $version")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply {
                if (ongoing) {
                    if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun finishNotification(text: String) {
        handler.removeCallbacks(poll)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(100, false, text))
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 310
        private const val EXTRA_ID = "download_id"
        private const val EXTRA_VERSION = "version"

        fun start(context: Context, id: Long, version: String) {
            val intent = Intent(context, UpdateDownloadService::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_VERSION, version)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
