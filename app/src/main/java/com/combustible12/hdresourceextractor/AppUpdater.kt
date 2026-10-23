package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** User-initiated update; the pending APK remains recoverable until actually installed. */
class AppUpdater(context: Context) {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val dm get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    fun checkForUpdate(onMessage: (String) -> Unit) {
        if (!checking.compareAndSet(false, true)) { onMessage("正在检查更新，请稍候"); return }
        onMessage("正在检查新版本…")
        Thread {
            try {
                if (recoverPendingUpdate(true, onMessage)) return@Thread
                val conn = (URL("https://api.github.com/repos/combustible12/HDResourceExtractor/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 20000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "HDResourceExtractor-Android")
                }
                val release = try {
                    if (conn.responseCode == 404) { onMessage("尚无正式发布版本"); return@Thread }
                    require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                } finally { conn.disconnect() }
                val version = release.optString("tag_name").trimStart('v', 'V')
                val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) }
                when {
                    compareVersions(version, installed) <= 0 -> onMessage("已是最新版本（$installed）")
                    asset == null -> onMessage("新版本 $version 暂无安装包")
                    else -> {
                        val url = asset.optString("browser_download_url")
                        require(url.startsWith("https://github.com/combustible12/HDResourceExtractor/releases/download/")) { "更新地址无效" }
                        enqueue(url, version, onMessage)
                    }
                }
            } catch (e: Exception) { onMessage("检查更新失败：${e.message}") }
            finally { checking.set(false) }
        }.start()
    }
    fun recoverPendingUpdate(force: Boolean = false, onMessage: (String) -> Unit): Boolean {
        val id = prefs.getLong("download_id", -1)
        if (id <= 0) return false
        val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        if (compareVersions(prefs.getString("version", "0").orEmpty(), installed) <= 0) {
            dm.remove(id); prefs.edit().remove("download_id").commit(); return false
        }
        try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val returningFromSettings = prefs.getBoolean("await_permission", false) &&
                            (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls())
                        if (force || promptedId != id || returningFromSettings) {
                            promptedId = id; install(id, onMessage)
                        } else onMessage("新版安装包已下载，点击一键升级继续安装")
                        return true
                    }
                    DownloadManager.STATUS_FAILED -> { dm.remove(id); prefs.edit().remove("download_id").commit(); return false }
                    else -> { onMessage("新版安装包正在下载，可在通知栏查看"); return true }
                }
            }
            prefs.edit().remove("download_id").commit()
        } catch (e: Exception) { onMessage("读取更新任务失败：${e.message}"); return true }
        return false
    }
    private fun install(id: Long, onMessage: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putBoolean("await_permission", true).commit()
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMessage("安装包已下载，请允许安装此来源的应用；返回后继续安装")
            return
        }
        prefs.edit().putBoolean("await_permission", false).commit()
        val uri = dm.getUriForDownloadedFile(id) ?: error("安装包无法读取，请重新下载")
        app.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        onMessage("安装包已下载，请确认系统安装提示")
    }
    private fun enqueue(url: String, version: String, onMessage: (String) -> Unit) {
        val filename = "HDResourceExtractor-$version.apk"
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(filename)?.delete()
        var id = -1L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                try { recoverPendingUpdate(true, onMessage) }
                catch (e: Exception) { onMessage("更新下载完成，请再次点击升级：${e.message}") }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        try {
            id = dm.enqueue(DownloadManager.Request(Uri.parse(url)).setTitle("HDResourceExtractor $version")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, filename))
            check(prefs.edit().putString("version", version).putLong("download_id", id).commit())
            onMessage("发现新版本 $version，正在下载，完成后打开安装界面")
        } catch (e: Exception) { runCatching { app.unregisterReceiver(receiver) }; throw e }
    }
    companion object {
        @Volatile private var promptedId = -1L
        private val checking = java.util.concurrent.atomic.AtomicBoolean(false)
        internal fun compareVersions(a: String, b: String): Int {
            val aa = a.split('.').map { it.toIntOrNull() ?: 0 }; val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(aa.size, bb.size)) { val d = aa.getOrElse(i) { 0 }.compareTo(bb.getOrElse(i) { 0 }); if (d != 0) return d }
            return 0
        }
    }
}
