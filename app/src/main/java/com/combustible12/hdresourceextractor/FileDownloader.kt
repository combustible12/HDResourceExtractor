package com.combustible12.hdresourceextractor

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import java.io.File

class FileDownloader(private val context: Context) {
    fun download(t: DownloadTaskStore.Task, c: SegmentTaskQueue.Control, progress: (Int, String) -> Unit) {
        if (MediaPublisher.saved(context, t.url)) return
        val candidates = mutableListOf(t.url to t.cookie)
        val extras = org.json.JSONArray(t.sources)
        for (i in 0 until extras.length()) extras.optJSONObject(i)?.let { item ->
            val source = item.optString("url")
            if (source.startsWith("https://") || source.startsWith("http://")) candidates.add(source to item.optString("cookie"))
        }
        var failure: Exception? = null
        for ((source, cookie) in candidates.distinctBy { it.first }) {
            try {
                c.check()
                val file = DownloadCache.fetch(source, File(DownloadCache.dir(context, t.url), "file_" + ResourceIdentity.hash(source)), t.agent, t.referer, cookie, c,
                    progress = { done, total -> progress(if (total > 0) (done * 95 / total).toInt() else 0, "已下载 ${done / 1024} KB") })
                val contentType = DownloadCache.json(File(file.path + ".json")).optString("contentType")
                val manifest = MediaSniffer.manifest(file, contentType)
                if (manifest != null) {
                    var result: Pair<Boolean, String>? = null
                    val callback: (Boolean, String) -> Unit = { ok, message -> result = ok to message }
                    if (manifest == MediaSniffer.Manifest.HLS) {
                        HlsDownloader(context).download(source, t.agent, t.referer, cookie, callback, c, progress)
                    } else {
                        DashDownloader(context).download(source, t.agent, t.referer, cookie, callback, c, progress)
                    }
                    val outcome = result ?: error("流媒体下载未返回结果")
                    if (!outcome.first) error(outcome.second)
                    MediaPublisher.aliasSaved(context, t.url, source)
                    DownloadCache.clear(context, t.url)
                    return
                }
                val mime = MediaSniffer.detect(file, contentType, t.name)
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: t.name.substringAfterLast('.', "bin")
                MediaPublisher.save(context, t.url, file, t.name.substringBeforeLast('.') + "." + ext, mime, c)
                failure = null
                break
            } catch (e: Exception) { c.check(); failure = e }
        }
        if (failure != null) throw failure
        DownloadCache.clear(context, t.url)
    }
}

/** Journal the URI before copying so process death cannot leave an untracked pending output. */
object MediaPublisher {
    private fun exists(context: Context, raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(raw), "r")?.use { descriptor ->
                descriptor.statSize != 0L
            } == true
        }.getOrDefault(false)
    }
    fun saved(context: Context, url: String): Boolean {
        val prefs = context.getSharedPreferences("saved_resources", Context.MODE_PRIVATE)
        val key = ResourceIdentity.of(url)
        val raw = prefs.getString(key, null)
        if (exists(context, raw)) return true
        if (raw != null) prefs.edit().remove(key).commit()
        return false
    }
    @Synchronized fun aliasSaved(context: Context, original: String, source: String) {
        val prefs = context.getSharedPreferences("saved_resources", Context.MODE_PRIVATE)
        val uri = prefs.getString(ResourceIdentity.of(source), null)
        check(exists(context, uri)) { "流媒体输出未保存" }
        check(prefs.edit().putString(ResourceIdentity.of(original), uri).commit())
    }
    @Synchronized fun save(context: Context, url: String, file: File, name: String, mime: String, c: SegmentTaskQueue.Control?) {
        val prefs = context.getSharedPreferences("saved_resources", Context.MODE_PRIVATE)
        val key = ResourceIdentity.of(url)
        fun finish(action: () -> Unit) {
            val work = { c?.check(); action(); c?.committed = true }
            if (c == null) work() else synchronized(c) { work() }
        }
        c?.check()
        if (saved(context, url)) { finish { }; return }
        val hash = DownloadCache.digest(file)
        c?.check()
        val contentKey = "content_$hash"
        val existing = prefs.getString(contentKey, null)
        if (exists(context, existing)) {
            finish { check(prefs.edit().putString(key, existing).commit()) }
            return
        } else if (existing != null) {
            prefs.edit().remove(contentKey).commit()
        }
        val resolver = context.contentResolver
        val pendingKey = "pending_$key"
        prefs.getString(pendingKey, null)?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        val video = mime.startsWith("video/") || mime.startsWith("audio/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) "Movies" else if (mime.startsWith("image/")) "Pictures" else "Download") + "/HDResourceExtractor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29 && !video && !mime.startsWith("image/")) MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        c?.check()
        val uri = resolver.insert(collection, values) ?: error("无法创建输出文件")
        check(prefs.edit().putString(pendingKey, uri.toString()).commit())
        try {
            // Do not hold the control lock during a large copy: pause/cancel must remain responsive.
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input ->
                val b = ByteArray(128 * 1024)
                while (true) { c?.check(); val n = input.read(b); if (n < 0) break; out.write(b, 0, n) }
            } } ?: error("无法写入输出文件")
            finish {
                if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "无法完成输出文件" }
                check(exists(context, uri.toString())) { "输出文件未成功保存" }
                check(prefs.edit().putString(key, uri.toString()).putString("content_$hash", uri.toString()).remove(pendingKey).commit())
            }
        } catch (e: Exception) { resolver.delete(uri, null, null); prefs.edit().remove(pendingKey).commit(); throw e }
    }
}
