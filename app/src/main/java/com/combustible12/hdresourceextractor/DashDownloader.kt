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
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** Downloads clear DASH SegmentList audio/video representations and muxes their samples. */
class DashDownloader(private val context: Context) {
    data class Track(val type: String, val bandwidth: Long, val urls: List<String>)
    fun download(url: String, agent: String, referer: String, cookie: String, callback: (Boolean, String) -> Unit, control: SegmentTaskQueue.Control? = null,
                 progress: ((Int, String) -> Unit)? = null) {
        val work = {
            val temps = mutableListOf<File>()
            val prefs = context.getSharedPreferences("dash_resume", Context.MODE_PRIVATE)
            val resumeKey = "dash_" + java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val workDir = File(context.filesDir, "dash_segments").apply { mkdirs() }
            var success = false
            try {
                control?.check()
                val xml = fetch(url, agent, referer, cookie).toString(Charsets.UTF_8)
                val tracks = parse(url, xml)
                val video = tracks.filter { it.type == "video" }.maxByOrNull { it.bandwidth }
                    ?: error("DASH 未找到视频轨道")
                val audio = tracks.filter { it.type == "audio" }.maxByOrNull { it.bandwidth }
                val totalSegments = video.urls.size + (audio?.urls?.size ?: 0)
                var completedSegments = 0
                fun retrieve(track: Track): File {
                    val output = File(workDir, "${resumeKey}_${track.type}.mp4")
                    val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(track.urls.joinToString("\\n").toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val prefix = "${resumeKey}_${track.type}"
                    var savedIndex = prefs.getInt(prefix + "_index", 0)
                    var savedBytes = prefs.getLong(prefix + "_bytes", 0L)
                    if (prefs.getString(prefix + "_fingerprint", "") != fingerprint ||
                        !output.exists() || output.length() < savedBytes || savedIndex > track.urls.size) {
                        savedIndex = 0
                        savedBytes = 0L
                        prefs.edit().putString(prefix + "_fingerprint", fingerprint)
                            .putInt(prefix + "_index", 0).putLong(prefix + "_bytes", 0).commit()
                    }
                    java.io.RandomAccessFile(output, "rw").use { raf ->
                        raf.setLength(savedBytes)
                        raf.seek(savedBytes)
                        track.urls.forEachIndexed { index, segment ->
                            if (index < savedIndex) {
                                completedSegments++
                                progress?.invoke((completedSegments * 90 / totalSegments).coerceAtMost(90), "已恢复 ${track.type} ${index + 1}/${track.urls.size}")
                                return@forEachIndexed
                            }
                            control?.check()
                            var failure: Exception? = null
                            val checkpoint = raf.filePointer
                            for (attempt in 1..3) {
                                try {
                                    raf.setLength(checkpoint)
                                    raf.seek(checkpoint)
                                    fetchTo(segment, agent, referer, cookie, raf, control)
                                    failure = null
                                    break
                                } catch (e: Exception) {
                                    if (e is SegmentTaskQueue.Stopped) throw e
                                    failure = e
                                }
                            }
                            if (failure != null) error("${track.type} 片段 ${index + 1} 下载失败：${failure.message}")
                            raf.fd.sync()
                            prefs.edit().putInt(prefix + "_index", index + 1)
                                .putLong(prefix + "_bytes", raf.filePointer).commit()
                            completedSegments++
                            progress?.invoke((completedSegments * 90 / totalSegments).coerceAtMost(90), "${track.type} ${index + 1}/${track.urls.size}")
                        }
                    }
                    return output
                }
                val v = retrieve(video)
                val a = audio?.let { retrieve(it) }
                val merged = File.createTempFile("dash_mux_", ".mp4", context.cacheDir)
                temps.add(merged)
                control?.check()
                progress?.invoke(92, "正在合并音视频")
                mux(v, a, merged)
                control?.check()
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
                success = true
                callback(true, "DASH 音视频已合并保存至 Movies/HDResourceExtractor")
            } catch (e: Exception) {
                if (e is SegmentTaskQueue.Stopped) throw e
                callback(false, "DASH 下载或合并失败：" + (e.message ?: "未知错误"))
            } finally {
                temps.forEach { it.delete() }
                if (success) {
                    listOf("video", "audio").forEach { type ->
                        File(workDir, "${resumeKey}_${type}.mp4").delete()
                        val prefix = "${resumeKey}_${type}"
                        prefs.edit().remove(prefix + "_fingerprint").remove(prefix + "_index")
                            .remove(prefix + "_bytes").commit()
                    }
                }
            }
        }
        if (control == null) Thread { work() }.start() else work()
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

    /** Stream segments to disk to avoid retaining large videos in RAM. */
    private fun fetchTo(raw: String, agent: String, referer: String, cookie: String, output: java.io.RandomAccessFile, control: SegmentTaskQueue.Control?) {
        val conn = URL(raw).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (agent.isNotBlank()) conn.setRequestProperty("User-Agent", agent)
            if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            var bytes = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    control?.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    bytes += count
                }
            }
            if (bytes == 0L) error("空片段")
        } finally { conn.disconnect() }
    }

    /** Resolve static, clear MPDs with inherited SegmentTemplate, SegmentTimeline or SegmentList. */
    private fun parse(manifest: String, xml: String): List<Track> {
        if (xml.length > 5_000_000) error("MPD 清单过大")
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val root = doc.documentElement
        if (root.localName != "MPD" && root.nodeName != "MPD") error("无效的 DASH MPD 清单")
        if (root.getAttribute("type") == "dynamic") error("暂不支持直播 DASH")
        if (root.getElementsByTagNameNS("*", "ContentProtection").length > 0) error("不支持 DRM 加密 DASH")
        fun children(node: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> {
            val result = mutableListOf<org.w3c.dom.Element>()
            val list = node.childNodes
            for (i in 0 until list.length) {
                val el = list.item(i) as? org.w3c.dom.Element ?: continue
                if (el.localName == name || el.nodeName == name) result.add(el)
            }
            return result
        }
        fun child(node: org.w3c.dom.Element, name: String) = children(node, name).firstOrNull()
        fun base(parent: String, node: org.w3c.dom.Element): String {
            val value = child(node, "BaseURL")?.textContent?.trim().orEmpty()
            return if (value.isEmpty()) parent else URL(URL(parent), value).toString()
        }
        fun inherited(nodes: List<org.w3c.dom.Element>, name: String): List<org.w3c.dom.Element> =
            nodes.mapNotNull { child(it, name) }
        fun attr(nodes: List<org.w3c.dom.Element>, key: String, default: String = ""): String =
            nodes.asReversed().firstNotNullOfOrNull { it.getAttribute(key).takeIf(String::isNotEmpty) } ?: default
        fun seconds(raw: String): Double {
            val m = Regex("""P(?:([0-9.]+)D)?T?(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?""").matchEntire(raw)
                ?: return 0.0
            return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 86400 +
                (m.groupValues[2].toDoubleOrNull() ?: 0.0) * 3600 +
                (m.groupValues[3].toDoubleOrNull() ?: 0.0) * 60 +
                (m.groupValues[4].toDoubleOrNull() ?: 0.0)
        }
        fun expand(pattern: String, id: String, bandwidth: Long, number: Long, time: Long): String {
            val escaped = pattern.replace("\$\$", "\u0001")
            val token = Regex("""\$(RepresentationID|Bandwidth|Number|Time)(%0(\d+)d)?\$""")
            return token.replace(escaped) { match ->
                val value = when (match.groupValues[1]) {
                    "RepresentationID" -> id
                    "Bandwidth" -> bandwidth.toString()
                    "Number" -> number.toString()
                    else -> time.toString()
                }
                val width = match.groupValues[3].toIntOrNull()?.coerceIn(0, 16) ?: 0
                value.padStart(width, '0')
            }.replace("\u0001", "$")
        }
        val tracks = mutableListOf<Track>()
        val mpdBase = base(manifest, root)
        val periods = children(root, "Period")
        if (periods.size != 1) error("暂不支持多 Period DASH")
        val period = periods.firstOrNull() ?: error("MPD 缺少 Period")
        val periodBase = base(mpdBase, period)
        val periodSeconds = seconds(period.getAttribute("duration").ifBlank { root.getAttribute("mediaPresentationDuration") })
        for (adapt in children(period, "AdaptationSet")) {
            val adaptBase = base(periodBase, adapt)
            for (rep in children(adapt, "Representation")) {
                val type = rep.getAttribute("mimeType").ifBlank { adapt.getAttribute("mimeType") }
                    .substringBefore('/').ifBlank { rep.getAttribute("contentType").ifBlank { adapt.getAttribute("contentType") } }
                if (type !in listOf("audio", "video")) continue
                val bandwidth = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L
                val id = rep.getAttribute("id")
                val repBase = base(adaptBase, rep)
                val hierarchy = listOf(root, period, adapt, rep)
                val lists = inherited(hierarchy, "SegmentList")
                val templates = inherited(hierarchy, "SegmentTemplate")
                val urls = mutableListOf<String>()
                if (lists.isNotEmpty()) {
                    if (lists.any { child(it, "Initialization")?.hasAttribute("range") == true || children(it, "SegmentURL").any { seg -> seg.hasAttribute("mediaRange") || seg.hasAttribute("indexRange") } }) {
                        error("此 DASH 使用字节范围分段，暂不支持直接拼接")
                    }
                    val init = lists.asReversed().firstNotNullOfOrNull { child(it, "Initialization")?.getAttribute("sourceURL")?.takeIf(String::isNotEmpty) }
                    if (!init.isNullOrEmpty()) urls.add(URL(URL(repBase), init).toString())
                    val segments = lists.asReversed().firstOrNull { children(it, "SegmentURL").isNotEmpty() }
                    segments?.let { list ->
                        for (seg in children(list, "SegmentURL")) {
                            val media = seg.getAttribute("media")
                            if (media.isNotEmpty()) urls.add(URL(URL(repBase), media).toString())
                        }
                    }
                } else if (templates.isNotEmpty()) {
                    val media = attr(templates, "media")
                    if (media.isEmpty()) continue
                    val init = attr(templates, "initialization")
                    val start = attr(templates, "startNumber", "1").toLongOrNull() ?: 1L
                    val scale = (attr(templates, "timescale", "1").toLongOrNull() ?: 1L).coerceAtLeast(1L)
                    if (init.isEmpty()) error("SegmentTemplate 缺少初始化片段")
                    urls.add(URL(URL(repBase), expand(init, id, bandwidth, start, 0)).toString())
                    val timeline = templates.asReversed().firstNotNullOfOrNull { child(it, "SegmentTimeline") }
                    val stamps = mutableListOf<Long>()
                    if (timeline != null) {
                        val entries = children(timeline, "S")
                        var clock = 0L
                        for ((i, entry) in entries.withIndex()) {
                            val d = entry.getAttribute("d").toLongOrNull() ?: error("DASH 时间轴缺少 d")
                            if (d <= 0) error("DASH 时间轴 d 无效")
                            clock = entry.getAttribute("t").toLongOrNull() ?: clock
                            val repetitions = entry.getAttribute("r").toLongOrNull() ?: 0L
                            val next = entries.getOrNull(i + 1)?.getAttribute("t")?.toLongOrNull()
                            val count = if (repetitions >= 0) repetitions + 1 else {
                                val boundary = next ?: (periodSeconds * scale).toLong()
                                if (boundary <= clock) error("无法确定 DASH 时间轴结束位置")
                                (boundary - clock + d - 1) / d
                            }
                            if (count > 20000 || stamps.size + count > 20000) error("DASH 片段过多")
                            repeat(count.toInt()) { stamps.add(clock); clock += d }
                        }
                    } else {
                        val duration = attr(templates, "duration").toLongOrNull() ?: error("SegmentTemplate 缺少 duration")
                        if (duration <= 0 || periodSeconds <= 0) error("无法计算 DASH 片段数量")
                        val count = kotlin.math.ceil(periodSeconds * scale / duration).toInt()
                        if (count !in 1..20000) error("DASH 片段数量无效")
                        repeat(count) { stamps.add(it.toLong() * duration) }
                    }
                    for ((index, stamp) in stamps.withIndex()) {
                        val name = expand(media, id, bandwidth, start + index, stamp)
                        urls.add(URL(URL(repBase), name).toString())
                    }
                }
                if (urls.size > 1) tracks.add(Track(type, bandwidth, urls))
            }
        }
        if (tracks.isEmpty()) error("MPD 未找到可下载的非加密音视频轨道")
        return tracks
    }

    private fun mux(video: File, audio: File?, destination: File) {
        val extractors = if (audio == null) listOf(MediaExtractor()) else listOf(MediaExtractor(), MediaExtractor())
        var muxer: MediaMuxer? = null
        try {
            extractors[0].setDataSource(video.absolutePath)
            if (audio != null) extractors[1].setDataSource(audio.absolutePath)
            val indexes = extractors.mapIndexed { i, extractor ->
                (0 until extractor.trackCount).firstOrNull { n ->
                    extractor.getTrackFormat(n).getString(MediaFormat.KEY_MIME)?.startsWith(if (i == 0) "video/" else "audio/") == true
                } ?: error(if (i == 0) "视频轨无法解码" else "音频轨无法解码")
            }
            val writer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val outputIndexes = extractors.mapIndexed { i, extractor ->
                writer.addTrack(extractor.getTrackFormat(indexes[i]))
            }
            writer.start()
            val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { i, extractor ->
                extractor.selectTrack(indexes[i])
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size > buffer.capacity()) error("媒体样本超出缓冲区")
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    writer.writeSampleData(outputIndexes[i], buffer, info)
                    extractor.advance()
                }
            }
            writer.stop()
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            extractors.forEach { try { it.release() } catch (_: Exception) {} }
        }
    }
}
