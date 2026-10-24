package com.combustible12.hdresourceextractor

import android.content.Context
import java.io.File
import java.net.URL

/** Static clear HLS: persist and verify each segment before assembling the output. */
class HlsDownloader(private val context: Context) {
    internal data class Piece(val url: String, val range: String? = null)
    internal data class Playlist(val groups: List<List<Piece>>, val audio: String? = null)
    fun download(url: String, userAgent: String, referer: String, cookie: String,
        callback: (Boolean, String) -> Unit, control: SegmentTaskQueue.Control? = null,
        progress: ((Int, String) -> Unit)? = null) {
        val work = {
            try {
                control?.check()
                if (!MediaPublisher.saved(context, url)) {
                    val dir = DownloadCache.dir(context, url)
                    var manifestIndex = 0
                    fun load(source: String): Pair<String, String> {
                        val file = File(dir, "playlist_${manifestIndex++}")
                        file.delete(); File(file.path + ".json").delete()
                        DownloadCache.fetch(source, file, userAgent, referer, cookie, control)
                        return file.readText() to DownloadCache.json(File(file.path + ".json")).optString("finalUrl", source)
                    }
                    val video = resolve(url, 0, ::load)
                    val audio = video.audio?.let { resolve(it, 0, ::load) }
                    if (audio != null) require(audio.groups.size == video.groups.size) { "音视频不连续点数量不一致" }
                    val total = video.groups.sumOf { it.size } + (audio?.groups?.sumOf { it.size } ?: 0)
                    var done = 0
                    fun retrieve(pieces: List<Piece>, label: String): File {
                        val target = File(dir, label)
                        target.outputStream().use { out -> pieces.forEach { piece ->
                            control?.check()
                            val file = DownloadCache.fetch(piece.url, File(dir, ResourceIdentity.hash(piece.url + "|" + piece.range.orEmpty())),
                                userAgent, referer, cookie, control, piece.range)
                            file.inputStream().use { copy -> copyChecked(copy, out, control) }
                            done++; progress?.invoke(done * 85 / total, "片段 $done/$total")
                        } }
                        return target
                    }
                    val periods = video.groups.mapIndexed { index, pieces ->
                        val v = retrieve(pieces, "hls_video_$index")
                        val a = audio?.groups?.get(index)?.let { retrieve(it, "hls_audio_$index") }
                        // TS/fMP4 may carry both tracks in the same stream.
                        val hasAudio = if (a == null) containsAudio(v) else false
                        buildMap { put("video", v); if (a != null) put("audio", a) else if (hasAudio) put("audio", v) }
                    }
                    progress?.invoke(90, "正在封装视频")
                    val merged = File(dir, "merged.mp4")
                    MediaJoiner.join(periods, merged, false, control)
                    MediaPublisher.save(context, url, merged, "hd_hls_${System.currentTimeMillis()}.mp4", "video/mp4", control)
                    DownloadCache.clear(context, url)
                }
                callback(true, "HLS 已保存")
            } catch (e: Exception) { control?.check(); callback(false, "HLS：${e.message}；已保留续传缓存") }
        }
        if (control == null) Thread { work() }.start() else work()
    }
    private fun containsAudio(file: File): Boolean {
        val ex = android.media.MediaExtractor()
        try { ex.setDataSource(file.path); return (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true } }
        finally { ex.release() }
    }
    internal fun resolve(url: String, depth: Int, load: (String) -> Pair<String, String>): Playlist {
        require(depth < 6) { "HLS 嵌套过深" }
        val (body, finalUrl) = load(url)
        require(body.trimStart().startsWith("#EXTM3U")) { "无效 HLS 播放列表" }
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        fun attr(line: String, key: String): String = Regex("(?:^|[:,])$key=(?:\"([^\"]*)\"|([^,]*))").find(line)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }.orEmpty()
        fun absolute(raw: String) = URL(URL(finalUrl), raw).toString()
        val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }
        if (variants.isNotEmpty()) {
            val best = variants.maxBy { attr(lines[it], "BANDWIDTH").toLongOrNull() ?: 0 }
            val address = lines.drop(best + 1).firstOrNull { !it.startsWith('#') } ?: error("HLS 缺少媒体地址")
            val group = attr(lines[best], "AUDIO")
            val external = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:") && attr(it, "TYPE") == "AUDIO" && attr(it, "GROUP-ID") == group && attr(it, "URI").isNotBlank() }
            return resolve(absolute(address), depth + 1, load).let { it.copy(audio = external?.let { l -> absolute(attr(l, "URI")) } ?: it.audio) }
        }
        require(lines.contains("#EXT-X-ENDLIST")) { "暂不支持直播保存" }
        val groups = mutableListOf<MutableList<Piece>>()
        var current = mutableListOf<Piece>()
        var init: Piece? = null
        var nextRange: String? = null
        var previousUrl = ""
        var previousEnd = 0L
        var mapEnd = 0L
        fun range(raw: String, offset: Long): Pair<String?, Long> {
            if (raw.isBlank()) return null to 0L
            val parts = raw.split('@'); val length = parts[0].toLong()
            require(length > 0) { "HLS 片段长度无效" }
            val start = parts.getOrNull(1)?.toLong() ?: offset
            return "$start-${Math.addExact(start, length) - 1}" to Math.addExact(start, length)
        }
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:") -> require(attr(line, "METHOD") == "NONE") { "暂不支持加密 HLS" }
                line.startsWith("#EXT-X-MAP:") -> {
                    val address = attr(line, "URI"); require(address.isNotBlank())
                    val (r, end) = range(attr(line, "BYTERANGE"), mapEnd); mapEnd = end
                    init = Piece(absolute(address), r)
                    if (current.isNotEmpty()) { groups.add(current); current = mutableListOf() }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> nextRange = line.substringAfter(':')
                line == "#EXT-X-DISCONTINUITY" -> if (current.isNotEmpty()) { groups.add(current); current = mutableListOf() }
                !line.startsWith('#') -> {
                    val address = absolute(line)
                    if (current.isEmpty()) init?.let { current.add(it) }
                    val rawRange = nextRange.orEmpty()
                    require(rawRange.isBlank() || rawRange.contains('@') || previousUrl == address) { "HLS 隐式字节范围没有前序片段" }
                    val (r, end) = range(rawRange, previousEnd)
                    current.add(Piece(address, r)); previousUrl = address; previousEnd = end; nextRange = null
                }
            }
        }
        if (current.isNotEmpty()) groups.add(current)
        require(groups.isNotEmpty() && groups.sumOf { it.size } <= 20000) { "HLS 片段数量无效" }
        return Playlist(groups)
    }
}
