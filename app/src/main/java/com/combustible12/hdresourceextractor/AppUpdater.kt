package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Manual, user-initiated updater using published GitHub Releases APK assets. */
class AppUpdater(private val context: Context) {
    fun checkForUpdate(onMessage: (String) -> Unit) {
        onMessage("正在检查新版本…")
        Thread {
            try {
                val conn = (URL("https://api.github.com/repos/combustible12/HDResourceExtractor/releases/latest")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "HDResourceExtractor-Android")
                }
                val raw = try {
                    if (conn.responseCode == 404) {
                        onMessage("尚无正式发布版本")
                        return@Thread
                    }
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode)
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                val release = JSONObject(raw)
                val tag = release.optString("tag_name").trimStart('v', 'V')
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                val assets: JSONArray = release.optJSONArray("assets") ?: JSONArray()
                var apk = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").endsWith(".apk", true)) {
                        apk = asset.optString("browser_download_url")
                        break
                    }
                }
                if (compareVersions(tag, installed) <= 0) {
                    onMessage("已是最新版本（" + installed + "）")
                } else if (!apk.startsWith("https://")) {
                    onMessage("发现新版本 " + tag + "，但未发布安装包")
                } else {
                    onMessage("发现新版本 " + tag + "，开始下载…")
                    downloadAndInstall(apk, tag, onMessage)
                }
            } catch (e: Exception) {
                onMessage("检查更新失败：" + (e.message ?: "网络不可用"))
            }
        }.start()
    }

    /** Called from the home screen; recovers a completed APK even after process death. */
    fun recoverPendingUpdate(onMessage: (String) -> Unit) {
        val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        val id = prefs.getLong("download_id", prefs.getLong("install_id", -1L))
        if (id <= 0) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    prefs.edit().remove("download_id").apply()
                    return
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    prefs.edit().remove("download_id").putLong("install_id", id).apply()
                    val uri = dm.getUriForDownloadedFile(id) ?: return
                    openInstaller(uri)
                    onMessage("更新安装包已下载，已打开系统安装界面")
                } else if (status == DownloadManager.STATUS_FAILED) {
                    prefs.edit().remove("download_id").apply()
                    onMessage("上次更新下载失败，可再次检查升级")
                } else {
                    onMessage("上次更新仍在下载中，可在系统通知栏查看")
                }
            }
        } catch (e: Exception) {
            onMessage("恢复更新任务失败：" + (e.message ?: "未知错误"))
        }
    }

    private fun openInstaller(uri: Uri) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw IllegalStateException("请允许此应用安装更新，然后再次打开 App 继续安装")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val diff = (aa.getOrElse(i) { 0 }).compareTo(bb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun downloadAndInstall(url: String, version: String, onMessage: (String) -> Unit) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("HDResourceExtractor " + version)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, "HDResourceExtractor-update.apk"
                )
            var id = -1L
            context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().putString("version", version).apply()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().remove("download_id").apply()
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                    val succeeded = cursor?.use {
                        it.moveToFirst() &&
                            it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                            DownloadManager.STATUS_SUCCESSFUL
                    } ?: false
                    if (!succeeded) {
                        onMessage("安装包下载失败，请检查网络")
                        return
                    }
                    val fileUri = dm.getUriForDownloadedFile(id)
                    if (fileUri == null) {
                        onMessage("安装包无法读取")
                        return
                    }
                    try {
                        context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().putLong("install_id", id).apply()
                        openInstaller(fileUri)
                        onMessage("安装包已下载，请确认系统安装提示")
                    } catch (e: Exception) {
                        onMessage("无法打开系统安装界面：" + (e.message ?: "安装权限不足"))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            try {
                id = dm.enqueue(request)
                context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().putLong("download_id", id).apply()
            } catch (e: Exception) {
                context.unregisterReceiver(receiver)
                throw e
            }
        } catch (e: Exception) {
            onMessage("下载启动失败：" + (e.message ?: "未知错误"))
        }
    }
}
