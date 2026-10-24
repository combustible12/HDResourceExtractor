package com.combustible12.hdresourceextractor

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import java.io.File
import kotlin.math.roundToInt

/** Foreground, resumable APK downloader independent of the OEM DownloadManager. */
class UpdateDownloadService : Service() {
    private var worker: Thread? = null
    private var control: SegmentTaskQueue.Control? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用升级", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示升级安装包的下载进度"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker?.isAlive == true) return START_NOT_STICKY
        val prefs = getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE)
        val url = intent?.getStringExtra(EXTRA_URL) ?: prefs.getString(AppUpdater.KEY_URL, "").orEmpty()
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: prefs.getString(AppUpdater.KEY_VERSION, "").orEmpty()
        startForeground(NOTIFICATION_ID, notification(version, "正在连接下载服务器…", -1, true))
        val transfer = SegmentTaskQueue.Control()
        control = transfer
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:apk-update").apply {
            acquire(10 * 60 * 1000L)
        }
        worker = Thread { download(url, version, transfer) }.apply { start() }
        return START_NOT_STICKY
    }

    private fun download(url: String, version: String, transfer: SegmentTaskQueue.Control) {
        val prefs = getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE)
        var partial: File? = null
        try {
            require(AppUpdater.validVersion(version)) { "更新版本号无效" }
            require(url.startsWith("https://github.com/combustible12/HDResourceExtractor/releases/download/")) { "更新地址无效" }
            val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: error("无法创建下载目录")
            val target = File(directory, "HDResourceExtractor-$version.apk")
            val staged = File(directory, "${target.name}.part")
            partial = staged
            var lastNotify = 0L
            // Use the same durable validator/digest checkpoints as regular downloads.
            DownloadCache.fetch(url, staged, "HDResourceExtractor-Android/$version", "", "", transfer,
                progress = { downloaded, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify >= 500L) {
                        lastNotify = now
                        val percent = if (total > 0) (downloaded * 100.0 / total).roundToInt().coerceIn(0, 99) else -1
                        val message = if (percent >= 0) "安装包下载中 · $percent%" else "安装包下载中 · ${downloaded / 1024} KB"
                        prefs.edit().putInt(AppUpdater.KEY_PROGRESS, percent).apply()
                        show(notification(version, message, percent, true))
                        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                            .putExtra(EXTRA_VERSION, version).putExtra(EXTRA_MESSAGE, message))
                    }
                })
            transfer.check()
            AppUpdater.validateApk(this, staged, version)
            transfer.check()
            if (target.exists()) check(target.delete()) { "无法替换旧安装包" }
            check(staged.renameTo(target)) { "无法保存安装包" }
            File(staged.path + ".json").delete()
            prefs.edit().putString(AppUpdater.KEY_PATH, target.absolutePath)
                .putString(AppUpdater.KEY_STATUS, AppUpdater.STATUS_COMPLETE)
                .putInt(AppUpdater.KEY_PROGRESS, 100).remove(AppUpdater.KEY_ERROR).commit()
            finish(version, true, "新版本 $version 已下载，点击继续安装", null)
        } catch (e: Exception) {
            // Invalid archives are never reused. Network interruptions keep verified partial bytes.
            if (e !is SegmentTaskQueue.Stopped && listOf("校验", "签名", "不匹配", "不高于").any { e.message.orEmpty().contains(it) }) {
                partial?.let { it.delete(); File(it.path + ".json").delete() }
            }
            prefs.edit().putString(AppUpdater.KEY_STATUS, AppUpdater.STATUS_FAILED)
                .putString(AppUpdater.KEY_ERROR, e.message ?: "未知错误").commit()
            finish(version, false, "升级下载中断，点击返回重试", e.message ?: "未知错误")
        } finally { releaseWakeLock() }
    }

    private fun notification(version: String, text: String, percent: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("HDResourceExtractor $version").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
            .apply { if (ongoing) { if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true) } }
            .build()
    }

    private fun show(value: Notification) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, value)

    private fun finish(version: String, success: Boolean, text: String, error: String?) {
        show(notification(version, text, if (success) 100 else 0, false))
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName)
            .putExtra(EXTRA_VERSION, version).putExtra(EXTRA_SUCCESS, success).putExtra(EXTRA_ERROR, error))
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        stopSelf()
    }

    @Synchronized private fun releaseWakeLock() {
        val held = wakeLock
        wakeLock = null
        if (held?.isHeld == true) held.release()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        control?.stop(true)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    override fun onDestroy() {
        control?.stop(true)
        releaseWakeLock()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_FINISHED = "com.combustible12.hdresourceextractor.UPDATE_FINISHED"
        const val ACTION_PROGRESS = "com.combustible12.hdresourceextractor.UPDATE_PROGRESS"
        const val EXTRA_VERSION = "version"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR = "error"
        const val EXTRA_MESSAGE = "message"
        private const val EXTRA_URL = "url"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 310

        fun start(context: Context, url: String, version: String) {
            val intent = Intent(context, UpdateDownloadService::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_VERSION, version)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
