package com.combustible12.hdresourceextractor

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaFormat
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** Downloads clear DASH SegmentList audio/video representations and muxes their samples. */
class DashDownloader(private val context: Context) {
    data class Track(val type: String, val bandwidth: Long, val urls: List<String>)
    fun download(url: String, agent: String, referer: String, cookie: String, callback: (Boolean, String) -> Unit) {
        Thread {
            val temps = mutableListOf<File>()
            try {
                val xml = fetch(url, agent, referer, cookie).toString(Charsets.UTF_8)
                val tracks = parse(url, xml)
                val video = tracks.filter { it.type == "video" }.maxByOrNull { it.bandwidth }
                    ?: error("DASH 未找到视频轨道")
                val audio = tracks.filter { it.type == "audio" }.maxByOrNull { it.bandwidth }
                    ?: error("DASH 未找到音频轨道")
                fun retrieve(track: Track): File {
                    val output = File.createTempFile("dash_${track.type}_", ".mp4", context.cacheDir)
                    temps.add(output)
                    FileOutputStream(output).use { stream ->
                        track.urls.forEachIndexed { index, segment ->
                            var failure: Exception? = null
                            for (attempt in 1..3) {
                                try {
                                    val bytes = fetch(segment, agent, referer, cookie)
                                    if (bytes.isEmpty()) error("空片段")
                                    stream.write(bytes)
                                    failure = null
                                    break
                                } catch (e: Exception) { failure = e }
                            }
                            if (failure != null) error("${track.type} 片段 ${index + 1} 下载失败：${failure.message}")
                        }
                    }
                    return output
                }
                val v = retrieve(video)
                val a = retrieve(audio)
                val merged = File.createTempFile("dash_mux_", ".mp4", context.cacheDir)
                temps.add(merged)
                mux(v, a, merged)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "hd_dash_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HDResourceExtractor")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= 29)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val uri = context.contentResolver.insert(collection, values) ?: error("无法创建输出文件")
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        merged.inputStream().use { it.copyTo(out) }
                    } ?: error("无法写入输出文件")
                    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
                callback(true, "DASH 音视频已合并保存至 Movies/HDResourceExtractor")
            } catch (e: Exception) {
                callback(false, "DASH 下载或合并失败：" + (e.message ?: "未知错误"))
            } finally { temps.forEach { it.delete() } }
        }.start()
    }

    private fun fetch(raw: String, agent: String, referer: String, cookie: String): ByteArray {
        val conn = URL(raw).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (agent.isNotBlank()) conn.setRequestProperty("User-Agent", agent)
            if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    private fun parse(manifest: String, xml: String): List<Track> {
        if (xml.contains("ContentProtection", ignoreCase = true)) error("不支持 DRM 加密 DASH")
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(xml.reader())
        val tracks = mutableListOf<Track>()
        val bases = mutableListOf<String>()
        var adaptationType = ""
        var representationType = ""
        var bandwidth = 0L
        var init = ""
        val media = mutableListOf<String>()
        var depth = 0
        var repDepth = -1
        var baseText = false
        var repBase = ""
        var adaptationBase = ""
        var mpdBase = manifest
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name.substringAfter(':')) {
                        "AdaptationSet" -> {
                            adaptationType = parser.getAttributeValue(null, "contentType").orEmpty()
                            if (adaptationType.isBlank()) adaptationType = parser.getAttributeValue(null, "mimeType").orEmpty().substringBefore('/')
                            adaptationBase = mpdBase
                        }
                        "Representation" -> {
                            repDepth = depth
                            bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0
                            representationType = parser.getAttributeValue(null, "mimeType").orEmpty().substringBefore('/').ifBlank { adaptationType }
                            repBase = adaptationBase
                            init = ""
                            media.clear()
                        }
                        "BaseURL" -> baseText = true
                        "Initialization" -> if (repDepth > 0) init = parser.getAttributeValue(null, "sourceURL").orEmpty()
                        "SegmentURL" -> if (repDepth > 0) {
                            val part = parser.getAttributeValue(null, "media").orEmpty()
                            if (part.isNotBlank()) media.add(part)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (baseText) {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotBlank()) {
                        val resolved = URL(URL(if (repDepth > 0) repBase else if (adaptationType.isNotBlank()) adaptationBase else mpdBase), value).toString()
                        if (repDepth > 0) repBase = resolved
                        else if (adaptationType.isNotBlank()) adaptationBase = resolved
                        else mpdBase = resolved
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "BaseURL" -> baseText = false
                        "Representation" -> {
                            if (init.isNotBlank() && media.isNotEmpty() && representationType in listOf("video", "audio")) {
                                val urls = (listOf(init) + media).map { URL(URL(repBase), it).toString() }
                                tracks.add(Track(representationType, bandwidth, urls))
                            }
                            repDepth = -1
                        }
                        "AdaptationSet" -> adaptationType = ""
                    }
                    depth--
                }
            }
            event = parser.next()
        }
        if (tracks.isEmpty()) error("仅支持非加密 SegmentList（含初始化片段）的 DASH")
        return tracks
    }

    private fun mux(video: File, audio: File, destination: File) {
        val extractors = listOf(MediaExtractor(), MediaExtractor())
        var muxer: MediaMuxer? = null
        try {
            extractors[0].setDataSource(video.absolutePath)
            extractors[1].setDataSource(audio.absolutePath)
            val indexes = extractors.mapIndexed { i, extractor ->
                (0 until extractor.trackCount).firstOrNull { n ->
                    extractor.getTrackFormat(n).getString(MediaFormat.KEY_MIME)?.startsWith(if (i == 0) "video/" else "audio/") == true
                } ?: error(if (i == 0) "视频轨无法解码" else "音频轨无法解码")
            }
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputIndexes = extractors.mapIndexed { i, extractor ->
                muxer.addTrack(extractor.getTrackFormat(indexes[i]))
            }
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { i, extractor ->
                extractor.selectTrack(indexes[i])
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size > buffer.capacity()) error("媒体样本超出缓冲区")
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(outputIndexes[i], buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            extractors.forEach { try { it.release() } catch (_: Exception) {} }
        }
    }
}
