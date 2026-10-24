package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
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

    private var observer: BroadcastReceiver? = null
    fun observe(onMessage: (String) -> Unit) {
        close()
        observer = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(UpdateDownloadService.EXTRA_VERSION) != prefs.getString(KEY_VERSION, "")) return
                if (intent?.action == UpdateDownloadService.ACTION_PROGRESS) {
                    onMessage(intent.getStringExtra(UpdateDownloadService.EXTRA_MESSAGE).orEmpty())
                } else if (intent?.getBooleanExtra(UpdateDownloadService.EXTRA_SUCCESS, false) == true) {
                    runCatching { recoverPendingUpdate(true, onMessage) }
                        .onFailure { onMessage("安装包已下载，点击升级继续安装：${it.message}") }
                } else onMessage("升级下载失败：${intent?.getStringExtra(UpdateDownloadService.EXTRA_ERROR).orEmpty()}")
            }
        }
        ContextCompat.registerReceiver(app, observer!!, IntentFilter(UpdateDownloadService.ACTION_FINISHED).apply {
            addAction(UpdateDownloadService.ACTION_PROGRESS)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun close() { observer?.let { runCatching { app.unregisterReceiver(it) } }; observer = null }

    fun checkForUpdate(onMessage: (String) -> Unit) {
        if (!checking.compareAndSet(false, true)) { onMessage("正在检查更新，请稍候"); return }
        onMessage("正在检查新版本…")
        Thread {
            try {
                if (recoverPendingUpdate(true, onMessage)) return@Thread
                val release = latestRelease()
                require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "暂无正式更新" }
                val version = release.optString("tag_name").trimStart('v', 'V')
                require(validVersion(version)) { "正式版本号无效" }
                val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == "HDResourceExtractor-v$version.apk" }
                when {
                    compareVersions(version, installed) <= 0 -> onMessage("已是最新版本（$installed）")
                    asset == null -> onMessage("新版本 $version 暂无安装包")
                    else -> {
                        val url = asset.optString("browser_download_url")
                        require(url.startsWith("https://github.com/combustible12/HDResourceExtractor/releases/download/")) { "更新地址无效" }
                        prefs.edit().putLong(KEY_EXPECTED_SIZE, asset.optLong("size", -1L))
                            .putString(KEY_DIGEST, asset.optString("digest").takeIf { it.startsWith("sha256:") }.orEmpty()).commit()
                        enqueue(url, version, onMessage)
                    }
                }
            } catch (e: Exception) { onMessage("检查更新失败：${e.message}") }
            finally { checking.set(false) }
        }.start()
    }

    private fun readUpdate(url: String): Pair<String, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000; useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "HDResourceExtractor-Android")
            setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "text/html")
        }
        return try {
            require(conn.responseCode in 200..299) { "更新服务器 HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() } to conn.url.toString()
        } finally { conn.disconnect() }
    }

    private fun latestRelease(): JSONObject {
        val base = "https://github.com/combustible12/HDResourceExtractor/releases"
        try { return JSONObject(readUpdate("https://api.github.com/repos/combustible12/HDResourceExtractor/releases/latest").first) }
        catch (apiFailure: Exception) {
            try {
                val page = readUpdate("$base/latest")
                val tag = java.net.URI(page.second).path.substringAfter("/releases/tag/", "")
                require(tag.startsWith("v") && validVersion(tag.drop(1))) { "正式版本号无效" }
                val filename = "HDResourceExtractor-$tag.apk"
                val assets = readUpdate("$base/expanded_assets/$tag").first
                val path = "/combustible12/HDResourceExtractor/releases/download/$tag/$filename"
                require(assets.contains("href=\"$path\"") || assets.contains("href='$path'")) { "新版本暂无安装包" }
                return JSONObject().put("tag_name", tag).put("assets", JSONArray().put(JSONObject()
                    .put("name", filename).put("browser_download_url", "https://github.com$path")))
            } catch (webFailure: Exception) {
                throw IllegalStateException("无法连接更新服务：${apiFailure.message}；备用发布页：${webFailure.message}", webFailure)
            }
        }
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
            if (url.isNotBlank()) try { UpdateDownloadService.start(app, url, targetVersion) }
            catch (e: Exception) {
                prefs.edit().putString(KEY_STATUS, STATUS_FAILED).putString(KEY_ERROR, e.message).commit()
                throw e
            }
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
        prefs.edit().putString(KEY_VERSION, version).putString(KEY_URL, url)
            .putString(KEY_PATH, apk.absolutePath).putString(KEY_STATUS, STATUS_DOWNLOADING)
            .putInt(KEY_PROGRESS, -1).remove(KEY_ERROR).remove("download_id").commit()
        try { UpdateDownloadService.start(app, url, version) }
        catch (e: Exception) {
            prefs.edit().putString(KEY_STATUS, STATUS_FAILED).putString(KEY_ERROR, e.message).commit()
            throw e
        }
        onMessage("发现新版本 $version，正在连接下载服务器…")
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
        validateApk(app, apk, prefs.getString(KEY_VERSION, "").orEmpty())
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
        app.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        onMessage("安装包已下载，请确认系统安装提示")
    }

    private fun isValidApk(file: File, version: String): Boolean = runCatching {
        validateApk(app, file, version); true
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
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_DIGEST = "expected_digest"
        const val KEY_VALIDATOR = "download_validator"
        const val KEY_AWAIT_PERMISSION = "await_permission"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAILED = "failed"
        @Volatile private var promptedPath = ""
        private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun validVersion(value: String) = Regex("[0-9]+(\\.[0-9]+){1,3}").matches(value)

        @Suppress("DEPRECATION")
        internal fun validateApk(context: Context, file: File, version: String) {
            require(validVersion(version)) { "更新版本号无效" }
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("安装包校验失败")
            val installed = pm.getPackageInfo(context.packageName, flags)
            require(info.packageName == context.packageName && info.versionName == version) { "安装包版本或应用不匹配" }
            val next = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
            require(next > current) { "安装包版本不高于当前版本" }
            val old = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
            val fresh = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            require(!old.isNullOrEmpty() && !fresh.isNullOrEmpty() && old.size == fresh.size && old.all { signer -> fresh.any { it == signer } }) {
                "安装包签名与当前应用不一致，无法覆盖升级；请使用同一正式签名的安装包"
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val size = prefs.getLong(KEY_EXPECTED_SIZE, -1L)
            require(file.length() > 0L && (size <= 0 || file.length() == size)) { "安装包下载不完整" }
            val digest = prefs.getString(KEY_DIGEST, "").orEmpty()
            if (digest.startsWith("sha256:")) require(DownloadCache.digest(file).equals(digest.substringAfter(':'), true)) { "安装包摘要校验失败" }
        }

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
