package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** User-initiated updater with an app-owned, resumable APK download. */
class AppUpdater(context: Context) {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dm get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun checkForUpdate(onMessage: (String) -> Unit) {
        if (!checking.compareAndSet(false, true)) { onMessage("正在检查更新，请稍候"); return }
        onMessage("正在检查新版本…")
        Thread {
            try {
                if (recoverPendingUpdate(true, onMessage)) return@Thread
                val conn = (URL("https://api.github.com/repos/combustible12/HDResourceExtractor/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
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
                val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
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
        val targetVersion = prefs.getString(KEY_VERSION, "0").orEmpty()
        val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        if (compareVersions(targetVersion, installed) <= 0) {
            clearFinishedState()
            return false
        }
        val apk = prefs.getString(KEY_PATH, null)?.let(::File)
        if (apk?.isFile == true && isValidApk(apk, targetVersion)) {
            if (force || promptedPath != apk.absolutePath || returningFromInstallPermission()) {
                promptedPath = apk.absolutePath
                install(apk, onMessage)
            } else onMessage("新版安装包已下载，点击一键升级继续安装")
            return true
        }
        if (prefs.getString(KEY_STATUS, "") == STATUS_DOWNLOADING) {
            val url = prefs.getString(KEY_URL, "").orEmpty()
            if (url.isNotBlank()) UpdateDownloadService.start(app, url, targetVersion)
            val percent = prefs.getInt(KEY_PROGRESS, -1)
            onMessage(if (percent >= 0) "新版安装包正在下载 · $percent%" else "新版安装包正在连接下载服务器")
            return true
        }
        if (!force && prefs.getString(KEY_STATUS, "") == STATUS_FAILED) {
            onMessage("上次升级下载失败：${prefs.getString(KEY_ERROR, "未知错误")}")
            return true
        }
        // Remove false pending jobs produced by older versions on affected OEM devices.
        prefs.getLong("download_id", -1L).takeIf { it > 0 }?.let { runCatching { dm.remove(it) } }
        prefs.edit().remove("download_id").commit()
        return false
    }

    private fun enqueue(url: String, version: String, onMessage: (String) -> Unit) {
        val directory = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: error("无法创建升级缓存目录")
        val apk = File(directory, "HDResourceExtractor-$version.apk")
        if (apk.exists() && !isValidApk(apk, version)) apk.delete()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(UpdateDownloadService.EXTRA_VERSION) != version) return
                if (intent.action == UpdateDownloadService.ACTION_PROGRESS) {
                    onMessage(intent.getStringExtra(UpdateDownloadService.EXTRA_MESSAGE).orEmpty())
                    return
                }
                runCatching { app.unregisterReceiver(this) }
                if (intent.getBooleanExtra(UpdateDownloadService.EXTRA_SUCCESS, false)) {
                    runCatching { recoverPendingUpdate(true, onMessage) }
                        .onFailure { onMessage("安装包已下载，请再次点击升级：${it.message}") }
                } else onMessage("升级下载失败：${intent.getStringExtra(UpdateDownloadService.EXTRA_ERROR).orEmpty()}")
            }
        }
        val filter = IntentFilter(UpdateDownloadService.ACTION_FINISHED).apply { addAction(UpdateDownloadService.ACTION_PROGRESS) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        prefs.edit().putString(KEY_VERSION, version).putString(KEY_URL, url)
            .putString(KEY_PATH, apk.absolutePath).putString(KEY_STATUS, STATUS_DOWNLOADING)
            .putInt(KEY_PROGRESS, -1).remove(KEY_ERROR).remove("download_id").commit()
        UpdateDownloadService.start(app, url, version)
        onMessage("发现新版本 $version，已开始直接下载")
    }

    private fun returningFromInstallPermission(): Boolean =
        prefs.getBoolean(KEY_AWAIT_PERMISSION, false) &&
            (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls())

    private fun install(apk: File, onMessage: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putBoolean(KEY_AWAIT_PERMISSION, true).commit()
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onMessage("安装包已下载，请允许安装此来源的应用；返回后继续安装")
            return
        }
        prefs.edit().putBoolean(KEY_AWAIT_PERMISSION, false).commit()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        app.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        onMessage("安装包已下载，请确认系统安装提示")
    }

    private fun isValidApk(file: File, version: String): Boolean = runCatching {
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return@runCatching false
        info.packageName == app.packageName && info.versionName == version && file.length() > 0L
    }.getOrDefault(false)

    private fun clearFinishedState() {
        prefs.getString(KEY_PATH, null)?.let { runCatching { File(it).delete() } }
        prefs.edit().clear().commit()
    }

    companion object {
        const val PREFS = "app_update"
        const val KEY_VERSION = "version"
        const val KEY_URL = "download_url"
        const val KEY_PATH = "apk_path"
        const val KEY_STATUS = "download_status"
        const val KEY_PROGRESS = "download_progress"
        const val KEY_ERROR = "download_error"
        const val KEY_VALIDATOR = "download_validator"
        const val KEY_AWAIT_PERMISSION = "await_permission"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"
        @Volatile private var promptedPath = ""
        private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun compareVersions(a: String, b: String): Int {
            val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(aa.size, bb.size)) {
                val d = aa.getOrElse(i) { 0 }.compareTo(bb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
