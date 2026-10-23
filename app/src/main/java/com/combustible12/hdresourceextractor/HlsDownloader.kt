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
            val prefs = context.getSharedPreferences("hls_download_progress", Context.MODE_PRIVATE)
            val key = "media_" + url.hashCode().toString()
            prefs.edit().putString("pending_url", url).putString("pending_agent", userAgent).putString("pending_referer", referer).putString("pending_cookie", cookie).apply()
            var target: android.net.Uri? = null
            try {
                val segments = resolveSegments(url, userAgent, referer, cookie, 0)
                val fmp4 = segments.firstOrNull()?.contains("__init_fmp4__") == true
                val effectiveSegments = segments.map { it.replace("__init_fmp4__", "") }
                if (effectiveSegments.isEmpty()) error("HLS 播放列表没有媒体片段")
                val resolver = context.contentResolver
                var savedIndex = prefs.getInt(key + "_index", 0).coerceIn(0, segments.size)
                var savedBytes = prefs.getLong(key + "_bytes", 0L).coerceAtLeast(0L)
                target = prefs.getString(key + "_uri", null)?.let(android.net.Uri::parse)
                if (target != null) {
                    val valid = try {
                        resolver.openFileDescriptor(target, "rw")?.use {
                            it.statSize >= savedBytes
                        } ?: false
                    } catch (_: Exception) { false }
                    if (!valid) {
                        target = null
                        savedIndex = 0
                        savedBytes = 0
                    }
                }
                if (target == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "hd_hls_${System.currentTimeMillis()}.${if (fmp4) "mp4" else "ts"}")
                        put(MediaStore.Video.Media.MIME_TYPE, if (fmp4) "video/mp4" else "video/mp2t")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HDResourceExtractor")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val collection = if (Build.VERSION.SDK_INT >= 29) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    target = resolver.insert(collection, values)
                        ?: error("无法创建视频文件，请检查手机存储空间")
                    savedIndex = 0
                    savedBytes = 0
                    prefs.edit().putString(key + "_uri", target.toString())
                        .putInt(key + "_index", 0).putLong(key + "_bytes", 0L).commit()
                }
                val uri = target ?: error("无法保存媒体")
                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                        output.channel.truncate(savedBytes)
                        output.channel.position(savedBytes)
                        effectiveSegments.forEachIndexed { index, segment ->
                            if (index < savedIndex) return@forEachIndexed
                            val temp = java.io.File.createTempFile("hls_segment_", ".ts", context.cacheDir)
                            try {
                                var downloaded = false
                                for (attempt in 1..3) {
                                    try {
                                        val conn = connect(segment, userAgent, referer, cookie)
                                        try {
                                            if (conn.responseCode !in 200..299) {
                                                error("片段 HTTP ${conn.responseCode}")
                                            }
                                            temp.outputStream().use { fileOutput ->
                                                conn.inputStream.use { it.copyTo(fileOutput, 128 * 1024) }
                                            }
                                            if (temp.length() <= 0) error("空片段")
                                            downloaded = true
                                            break
                                        } finally { conn.disconnect() }
                                    } catch (e: Exception) {
                                        temp.delete()
                                        if (attempt == 3) throw IllegalStateException(
                                            "片段 ${index + 1}/${effectiveSegments.size} 失败：" + e.message
                                        )
                                    }
                                }
                                if (!downloaded) error("片段下载失败")
                                temp.inputStream().use { it.copyTo(output, 128 * 1024) }
                                output.flush()
                                savedBytes = output.channel.position()
                                prefs.edit()
                                    .putInt(key + "_index", index + 1)
                                    .putLong(key + "_bytes", savedBytes)
                                    .commit()
                            } finally { temp.delete() }
                        }
                    }
                } ?: error("不能打开媒体存储")
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                prefs.edit().remove(key + "_uri").remove(key + "_index")
                    .remove(key + "_bytes").commit()
                DownloadedMedia(context).saved(url, uri)
                prefs.edit().remove("pending_url").remove("pending_agent").remove("pending_referer").remove("pending_cookie").apply()
                callback(true, "HLS 视频已保存至 Movies/HDResourceExtractor（TS 原始封装）")
            } catch (e: Exception) {
                // Keep the pending file and segment position to resume on the next request.
                callback(false, "HLS 下载中断：可再次点下载继续；" + (e.message ?: "未知错误"))
            }
        }.start()
    }

    fun resumePending(callback: (Boolean, String) -> Unit) {
        val prefs = context.getSharedPreferences("hls_download_progress", Context.MODE_PRIVATE)
        val url = prefs.getString("pending_url", "").orEmpty()
        if (url.isBlank()) return
        download(url, prefs.getString("pending_agent", "").orEmpty(),
            prefs.getString("pending_referer", "").orEmpty(),
            prefs.getString("pending_cookie", "").orEmpty(), callback)
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
        if (body.trimStart().startsWith("<") && body.contains("<MPD")) {
            return resolveDashSegmentList(raw, body)
        }
        if (!body.trimStart().startsWith("#EXTM3U")) error("并非有效 HLS 播放列表")
        if (body.contains("#EXT-X-KEY") || body.contains("#EXT-X-SESSION-KEY")) {
            error("加密 HLS 暂不支持")
        }
        if (body.contains("#EXT-X-BYTERANGE")) {
            error("字节范围片段暂不支持")
        }
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
        if (!body.contains("#EXT-X-ENDLIST")) error("直播 HLS 暂不支持完整保存")
        if (!lines.any { it.startsWith("#EXTINF") }) error("缺少 HLS 片段元数据")
        val segments = lines.filter { !it.startsWith("#") }.map { URL(URL(raw), it).toString() }
        if (segments.size > 10000) error("HLS 片段数量超限")
        val init = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
        if (init != null && body.contains("BYTERANGE=", ignoreCase = true)) error("初始化片段字节范围暂不支持")
        return if (init != null) listOf(URL(URL(raw), init).toString() + "__init_fmp4__") + segments else segments
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
    /** Supports clear DASH VOD SegmentList MP4 representations, not DRM or SegmentTemplate. */
    private fun resolveDashSegmentList(manifestUrl: String, xml: String): List<String> {
        if (xml.contains("ContentProtection", true)) error("DRM DASH 暂不支持")
        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        val representations = mutableListOf<Pair<Long, List<String>>>()
        var bandwidth = 0L
        var baseUrl = manifestUrl
        var init = ""
        val segments = mutableListOf<String>()
        var inRepresentation = false
        var inBase = false
        var inSegmentList = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "Representation" -> {
                        inRepresentation = true
                        bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                        baseUrl = manifestUrl
                        init = ""
                        segments.clear()
                    }
                    "BaseURL" -> if (inRepresentation) inBase = true
                    "SegmentList" -> if (inRepresentation) inSegmentList = true
                    "Initialization" -> if (inRepresentation && inSegmentList) {
                        init = parser.getAttributeValue(null, "sourceURL").orEmpty()
                    }
                    "SegmentURL" -> if (inRepresentation && inSegmentList) {
                        val media = parser.getAttributeValue(null, "media").orEmpty()
                        if (media.isNotBlank()) segments.add(media)
                    }
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.TEXT && inBase) {
                val value = parser.text?.trim().orEmpty()
                if (value.isNotBlank()) baseUrl = URL(URL(manifestUrl), value).toString()
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                when (parser.name.substringAfter(':')) {
                    "BaseURL" -> inBase = false
                    "SegmentList" -> inSegmentList = false
                    "Representation" -> {
                        if (init.isNotBlank() && segments.isNotEmpty()) {
                            val resolved = listOf(URL(URL(baseUrl), init).toString() + "__init_fmp4__") +
                                segments.map { URL(URL(baseUrl), it).toString() }
                            representations.add(bandwidth to resolved)
                        }
                        inRepresentation = false
                    }
                }
            }
            event = parser.next()
        }
        return representations.maxByOrNull { it.first }?.second
            ?: error("此 DASH 使用不支持的 SegmentTemplate 或缺少片段地址")
    }

}
