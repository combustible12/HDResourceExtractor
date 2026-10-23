package com.combustible12.hdresourceextractor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ResumableVideo(private val context: Context) {
    companion object {
        private val running = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private fun key(url: String) = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private val prefs = context.getSharedPreferences("resumable_http_v1", Context.MODE_PRIVATE)
    fun pause(url: String) { prefs.edit().putBoolean(key(url) + "_paused", true).apply() }
    fun resumePending(onComplete: (Boolean, String) -> Unit) {
        val url = prefs.getString("pending_url", "").orEmpty()
        if (url.isNotBlank() && !prefs.getBoolean(key(url) + "_paused", false)) {
            download(url, prefs.getString(key(url) + "_agent", "").orEmpty(),
                prefs.getString(key(url) + "_referer", "").orEmpty(),
                prefs.getString(key(url) + "_cookie", "").orEmpty(), onComplete)
        }
    }
    fun download(url: String, agent: String, referer: String, cookie: String, onComplete: (Boolean, String) -> Unit) {
        val k = key(url)
        if (!running.add(k)) { onComplete(false, "此视频正在下载"); return }
        prefs.edit().putString("pending_url", url).putString(k + "_agent", agent)
            .putString(k + "_referer", referer).putString(k + "_cookie", cookie)
            .putBoolean(k + "_paused", false).apply()
        Thread {
            try {
                val resolver = context.contentResolver
                var uri = prefs.getString(k + "_uri", null)?.let(Uri::parse)
                var written = prefs.getLong(k + "_written", 0L)
                if (uri != null) {
                    val ok = try { resolver.openFileDescriptor(uri, "rw")?.use { it.statSize >= written } ?: false }
                        catch (_: Exception) { false }
                    if (!ok) { uri = null; written = 0L }
                }
                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "hd_video_" + System.currentTimeMillis() + ".mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HDResourceExtractor")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val collection = if (Build.VERSION.SDK_INT >= 29)
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    uri = resolver.insert(collection, values) ?: error("无法创建视频文件")
                    written = 0L
                    prefs.edit().putString(k + "_uri", uri.toString()).putLong(k + "_written", 0L).commit()
                }
                val target = uri ?: error("文件不可用")
                var completed = false
                repeat(5) { attempt ->
                    if (completed) return@repeat
                    if (prefs.getBoolean(k + "_paused", false)) error("已暂停，进度已保留")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20000
                    connection.readTimeout = 60000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (agent.isNotBlank()) connection.setRequestProperty("User-Agent", agent)
                    if (referer.isNotBlank()) connection.setRequestProperty("Referer", referer)
                    if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
                    if (written > 0) connection.setRequestProperty("Range", "bytes=$written-")
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) error("HTTP $status")
                        if (written > 0 && status == 206) {
                            val actual = Regex("""bytes\s+(\d+)-""").find(connection.getHeaderField("Content-Range").orEmpty())
                                ?.groupValues?.getOrNull(1)?.toLongOrNull()
                            if (actual != written) error("服务器续传范围不匹配")
                        } else if (written > 0 && status == 200) written = 0L
                        val total = connection.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                            ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(written)
                        resolver.openFileDescriptor(target, "rw")?.use { fd ->
                            java.io.FileOutputStream(fd.fileDescriptor).use { output ->
                                output.channel.truncate(written)
                                output.channel.position(written)
                                connection.inputStream.use { input ->
                                    val bytes = ByteArray(131072)
                                    while (true) {
                                        if (prefs.getBoolean(k + "_paused", false)) error("已暂停，进度已保留")
                                        val count = input.read(bytes)
                                        if (count < 0) break
                                        output.write(bytes, 0, count)
                                        written += count
                                        prefs.edit().putLong(k + "_written", written).apply()
                                    }
                                }
                                output.flush()
                            }
                        } ?: error("媒体文件不可写")
                        if (total != null && written < total) error("连接提前结束")
                        completed = true
                    } catch (e: Exception) {
                        if (attempt == 4 || prefs.getBoolean(k + "_paused", false)) throw e
                    } finally { connection.disconnect() }
                }
                if (!completed || written <= 0) error("下载未完成")
                if (Build.VERSION.SDK_INT >= 29) resolver.update(target, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                DownloadedMedia(context).saved(url, target)
                prefs.edit().remove(k + "_uri").remove(k + "_written").remove("pending_url").apply()
                onComplete(true, "视频已下载保存")
            } catch (e: Exception) {
                onComplete(false, "下载已中断，进度保留：" + (e.message ?: "未知错误"))
            } finally { running.remove(k) }
        }.start()
    }
}