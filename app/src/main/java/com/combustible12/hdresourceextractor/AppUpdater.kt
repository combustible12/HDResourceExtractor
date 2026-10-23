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
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
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
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
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
            } catch (e: Exception) {
                context.unregisterReceiver(receiver)
                throw e
            }
        } catch (e: Exception) {
            onMessage("下载启动失败：" + (e.message ?: "未知错误"))
        }
    }
}
