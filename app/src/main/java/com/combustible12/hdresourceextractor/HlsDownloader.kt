package com.combustible12.hdresourceextractor

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL

/** Unencrypted MPEG-TS HLS VOD downloader. Streams segments to a single .ts media file. */
class HlsDownloader(private val context: Context) {
    fun download(
        url: String,
        userAgent: String,
        referer: String,
        cookie: String,
        callback: (Boolean, String) -> Unit
    ) {
        Thread {
            var target: android.net.Uri? = null
            try {
                val segments = resolveSegments(url, userAgent, referer, cookie, 0)
                if (segments.isEmpty()) throw IllegalArgumentException("HLS 播放列表没有媒体片段")
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "hd_hls_${System.currentTimeMillis()}.ts")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HDResourceExtractor")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                target = context.contentResolver.insert(collection, values)
                    ?: error("无法创建视频文件，请检查手机存储空间")
                context.contentResolver.openOutputStream(target, "w")?.use { output ->
                    segments.forEachIndexed { index, segment ->
                        var completed = false
                        for (attempt in 1..3) {
                            try {
                                val conn = connect(segment, userAgent, referer, cookie)
                                try {
                                    if (conn.responseCode !in 200..299) error("片段 HTTP ${conn.responseCode}")
                                    conn.inputStream.use { input -> input.copyTo(output, 128 * 1024) }
                                    completed = true
                                    break
                                } finally { conn.disconnect() }
                            } catch (e: Exception) {
                                if (attempt == 3) throw IllegalStateException(
                                    "片段 ${index+1}/${segments.size} 下载失败：" + e.message
                                )
                            }
                        }
                        if (!completed) error("片段下载不完整")
                    }
                } ?: error("不能打开媒体存储")
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.update(target, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                callback(true, "HLS 视频已保存至 Movies/HDResourceExtractor（TS 原始封装）")
            } catch (e: Exception) {
                target?.let { context.contentResolver.delete(it, null, null) }
                callback(false, "HLS 下载失败：" + (e.message ?: "未知错误"))
            }
        }.start()
    }

    private fun resolveSegments(
        raw: String, agent: String, referer: String, cookie: String, depth: Int
    ): List<String> {
        if (depth > 3) error("HLS 嵌套层级过深")
        val conn = connect(raw, agent, referer, cookie)
        val body = try {
            if (conn.responseCode !in 200..299) error("播放列表 HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        if (!body.trimStart().startsWith("#EXTM3U")) error("并非有效 HLS 播放列表")
        if (body.contains("#EXT-X-KEY") || body.contains("#EXT-X-SESSION-KEY")) {
            error("加密 HLS 暂不支持")
        }
        if (body.contains("#EXT-X-MAP") || body.contains("#EXT-X-BYTERANGE")) {
            error("该 HLS 使用 fMP4 或字节范围片段，暂不支持")
        }
        if (!body.contains("#EXT-X-ENDLIST")) error("直播 HLS 暂不支持完整保存")
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val options = mutableListOf<Pair<Long,String>>()
            lines.forEachIndexed { idx, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.drop(idx + 1).firstOrNull { !it.startsWith("#") }
                if (next != null) options.add(bandwidth to URL(URL(raw), next).toString())
            }
            val best = options.maxByOrNull { it.first }?.second ?: error("没有可用的 HLS 画质")
            return resolveSegments(best, agent, referer, cookie, depth + 1)
        }
        if (!lines.any { it.startsWith("#EXTINF") }) error("缺少 HLS 片段元数据")
        val segments = lines.filter { !it.startsWith("#") }.map { URL(URL(raw), it).toString() }
        if (segments.size > 10000) error("HLS 片段数量超限")
        return segments
    }

    private fun connect(raw: String, agent: String, referer: String, cookie: String): HttpURLConnection =
        (URL(raw).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("Accept-Encoding", "identity")
            if (agent.isNotBlank()) setRequestProperty("User-Agent", agent)
            if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
}
