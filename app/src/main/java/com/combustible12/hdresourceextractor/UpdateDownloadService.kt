package com.combustible12.hdresourceextractor

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Foreground, resumable APK downloader independent of the OEM DownloadManager. */
class UpdateDownloadService : Service() {
    private var worker: Thread? = null
    @Volatile private var stopped = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用升级", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示升级安装包的下载进度"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE)
        val url = intent?.getStringExtra(EXTRA_URL) ?: prefs.getString(AppUpdater.KEY_URL, "").orEmpty()
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: prefs.getString(AppUpdater.KEY_VERSION, "").orEmpty()
        startForeground(NOTIFICATION_ID, notification(version, "正在连接下载服务器…", -1, true))
        if (worker?.isAlive != true) worker = Thread { download(url, version) }.apply { start() }
        return START_STICKY
    }

    private fun download(url: String, version: String) {
        var connection: HttpURLConnection? = null
        try {
            require(url.startsWith("https://github.com/combustible12/HDResourceExtractor/releases/download/")) { "更新地址无效" }
            val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: error("无法创建下载目录")
            val target = File(directory, "HDResourceExtractor-$version.apk")
            val partial = File(directory, "${target.name}.part")
            val existing = partial.length()
            val prefs = getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE)
            val previousValidator = prefs.getString(AppUpdater.KEY_VALIDATOR, "").orEmpty()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "HDResourceExtractor-Android/$version")
                setRequestProperty("Accept", "application/octet-stream")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                if (existing > 0 && previousValidator.isNotBlank()) setRequestProperty("If-Range", previousValidator)
            }
            val code = connection.responseCode
            if (code == 416 && existing > 0) {
                partial.delete()
                prefs.edit().remove(AppUpdater.KEY_VALIDATOR).commit()
                connection.disconnect(); connection = null
                download(url, version)
                return
            }
            require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) { "HTTP $code" }
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
            val validator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: connection.getHeaderField("Last-Modified").orEmpty()
            if (append) {
                val rangeStart = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
                    .matchEntire(connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                val changed = rangeStart != existing || previousValidator.isNotBlank() && validator.isNotBlank() && previousValidator != validator
                if (changed) {
                    partial.delete()
                    prefs.edit().remove(AppUpdater.KEY_VALIDATOR).commit()
                    connection.disconnect(); connection = null
                    download(url, version)
                    return
                }
            }
            val base = if (append) existing else 0L
            val responseLength = connection.contentLengthLong
            val total = if (responseLength > 0) base + responseLength else -1L
            if (!append && partial.exists()) partial.delete()
            if (validator.isNotBlank()) prefs.edit().putString(AppUpdater.KEY_VALIDATOR, validator).commit()
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = base
                    var lastNotify = 0L
                    while (!stopped) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotify >= 500L) {
                            lastNotify = now
                            val percent = if (total > 0) (downloaded * 100.0 / total).roundToInt().coerceIn(0, 100) else -1
                            val message = if (percent >= 0) "安装包下载中 · $percent%" else "安装包下载中 · ${downloaded / 1024} KB"
                            prefs.edit().putInt(AppUpdater.KEY_PROGRESS, percent).commit()
                            show(notification(version, message, percent, true))
                            sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                                .putExtra(EXTRA_VERSION, version).putExtra(EXTRA_MESSAGE, message))
                        }
                    }
                    output.fd.sync()
                }
            }
            check(!stopped) { "下载已中断" }
            check(partial.length() > 0L && (total <= 0L || partial.length() == total)) { "安装包下载不完整" }
            val info = packageManager.getPackageArchiveInfo(partial.absolutePath, 0) ?: error("安装包校验失败")
            check(info.packageName == packageName && info.versionName == version) { "安装包版本不匹配" }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "无法保存安装包" }
            getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE).edit()
                .putString(AppUpdater.KEY_PATH, target.absolutePath)
                .putString(AppUpdater.KEY_STATUS, AppUpdater.STATUS_COMPLETE)
                .putInt(AppUpdater.KEY_PROGRESS, 100).remove(AppUpdater.KEY_ERROR).commit()
            finish(version, true, "新版本 $version 已下载，点击继续安装", null)
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("校验") || e.message.orEmpty().contains("版本不匹配")) {
                getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?.resolve("HDResourceExtractor-$version.apk.part")?.delete()
                getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE).edit().remove(AppUpdater.KEY_VALIDATOR).commit()
            }
            getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE).edit()
                .putString(AppUpdater.KEY_STATUS, AppUpdater.STATUS_FAILED)
                .putString(AppUpdater.KEY_ERROR, e.message ?: "未知错误").commit()
            finish(version, false, "升级下载失败，点击返回重试", e.message ?: "未知错误")
        } finally { connection?.disconnect() }
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

    override fun onDestroy() { stopped = true; super.onDestroy() }
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
