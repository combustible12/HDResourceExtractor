package com.combustible12.hdresourceextractor

import android.content.Context
import java.io.File
import java.net.URL

/** Clear static MPD: inherited addressing, byte ranges and per-Period track selection. */
class DashDownloader(private val context: Context) {
    data class Track(val type: String, val bandwidth: Long, val urls: List<String>, val period: Int = 0,
        val codecs: String = "", val mime: String = "", val timeOffsetUs: Long = 0L)
    fun download(url: String, agent: String, referer: String, cookie: String, callback: (Boolean, String) -> Unit,
        control: SegmentTaskQueue.Control? = null, progress: ((Int, String) -> Unit)? = null) {
        val work = {
            try {
                control?.check()
                if (!MediaPublisher.saved(context, url)) {
                    val dir = DownloadCache.dir(context, url)
                    // Re-fetch the manifest; segment identities bind checkpoints to the actual selected URLs.
                    val manifest = File(dir, "manifest"); manifest.delete(); File(manifest.path + ".json").delete()
                    DownloadCache.fetch(url, manifest, agent, referer, cookie, control)
                    val tracks = parse(url, manifest.readText())
                    val groups = tracks.groupBy { it.period }.toSortedMap()
                    val selected = groups.map { (_, options) ->
                        fun choose(type: String): Track? = options.filter { it.type == type }
                            .sortedWith(compareByDescending<Track> { compatible(it.codecs) }.thenByDescending { it.bandwidth }).firstOrNull()
                        listOfNotNull(choose("video") ?: error("Period 缺少视频轨"), choose("audio"))
                    }
                    val total = selected.flatten().sumOf { it.urls.size }
                    var count = 0
                    val periods = selected.map { group -> group.associate { track ->
                        val parts = track.urls.map { raw ->
                            control?.check()
                            val source = raw.substringBefore("#__hd_range=")
                            val range = raw.substringAfter("#__hd_range=", "").ifBlank { null }
                            val part = DownloadCache.fetch(source, File(dir, ResourceIdentity.hash(raw)), agent, referer, cookie, control, range)
                            count++; progress?.invoke(count * 85 / total, "片段 $count/$total")
                            part
                        }
                        val output = File(dir, "period_${track.period}_${track.type}")
                        output.outputStream().use { out -> parts.forEach { part ->
                            control?.check(); part.inputStream().use { it.copyTo(out, 128 * 1024) }
                        } }
                        track.type to output
                    } }
                    progress?.invoke(90, "正在合并音视频")
                    val merged = File(dir, "merged")
                    // AV1 is valid in both MP4 and WebM; do not infer the container from av01 alone.
                    val webm = selected.flatten().any { it.mime.contains("webm", true) || it.codecs.startsWith("vp", true) }
                    MediaJoiner.join(periods, merged, webm, control, selected.map { group -> group.associate { it.type to it.timeOffsetUs } })
                    MediaPublisher.save(context, url, merged, "hd_dash_${System.currentTimeMillis()}." + if (webm) "webm" else "mp4",
                        if (webm) "video/webm" else "video/mp4", control)
                    DownloadCache.clear(context, url)
                }
                callback(true, "DASH 已合并保存")
            } catch (e: Exception) { control?.check(); callback(false, "DASH：${e.message}；已保留片段，可重试合并") }
        }
        if (control == null) Thread { work() }.start() else work()
    }
    private fun compatible(codec: String): Boolean = codec.isBlank() || listOf("avc", "hvc", "hev", "mp4a", "vp8", "vp9", "vp09", "opus", "vorbis").any { codec.startsWith(it, true) }

    companion object {
    private fun segment(url: String, range: String): String = if (range.isBlank()) url else url + "#__hd_range=" + range
    /** Resolve static, clear MPDs with inherited SegmentTemplate, SegmentTimeline or SegmentList. */
    internal fun parse(manifest: String, xml: String): List<Track> {
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
        if (periods.isEmpty()) error("MPD 缺少 Period")
        var inferredStart = 0.0
        for ((periodIndex, period) in periods.withIndex()) {
        val periodBase = base(mpdBase, period)
        val periodStart = if (period.hasAttribute("start")) seconds(period.getAttribute("start")) else inferredStart
        val nextStart = periods.getOrNull(periodIndex + 1)?.getAttribute("start")?.takeIf { it.isNotBlank() }?.let(::seconds)
        val periodSeconds = if (period.hasAttribute("duration")) seconds(period.getAttribute("duration"))
            else (nextStart ?: seconds(root.getAttribute("mediaPresentationDuration"))) - periodStart
        inferredStart = periodStart + periodSeconds
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
                // The nearest addressing mode replaces a different mode inherited from its parent.
                val mode = hierarchy.asReversed().firstNotNullOfOrNull { n ->
                    listOf("SegmentList", "SegmentTemplate", "SegmentBase").firstOrNull { child(n, it) != null }
                }
                val lists = if (mode == "SegmentList") inherited(hierarchy, "SegmentList") else emptyList()
                val templates = if (mode == "SegmentTemplate") inherited(hierarchy, "SegmentTemplate") else emptyList()
                val urls = mutableListOf<String>()
                if (lists.isNotEmpty()) {
                    val init = lists.asReversed().firstNotNullOfOrNull { child(it, "Initialization") }
                    if (init != null) urls.add(segment(URL(URL(repBase), init.getAttribute("sourceURL")).toString(), init.getAttribute("range")))
                    val segments = lists.asReversed().firstOrNull { children(it, "SegmentURL").isNotEmpty() }
                    segments?.let { list -> for (seg in children(list, "SegmentURL")) {
                        urls.add(segment(URL(URL(repBase), seg.getAttribute("media")).toString(), seg.getAttribute("mediaRange")))
                    } }
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
                                val boundary = next ?: ((periodSeconds * scale).toLong() + (attr(templates, "presentationTimeOffset", "0").toLongOrNull() ?: 0L))
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
                } else if (mode == "SegmentBase" || repBase != manifest) {
                    // A complete ISO-BMFF/WebM representation can be downloaded without parsing SIDX.
                    urls.add(repBase)
                }
                val addressing = when (mode) {
                    "SegmentTemplate" -> templates
                    "SegmentList" -> lists
                    "SegmentBase" -> inherited(hierarchy, "SegmentBase")
                    else -> emptyList()
                }
                val scale = (attr(addressing, "timescale", "1").toLongOrNull() ?: 1L).coerceAtLeast(1)
                val presentationOffset = attr(addressing, "presentationTimeOffset", "0").toLongOrNull() ?: 0L
                if (urls.isNotEmpty()) tracks.add(Track(type, bandwidth, urls, periodIndex,
                    attr(hierarchy, "codecs"), attr(hierarchy, "mimeType"), (presentationOffset.toDouble() * 1_000_000 / scale).toLong()))
            }
        }
        }
        require(periods.indices.all { p -> tracks.any { it.period == p && it.type == "video" } }) { "部分 Period 缺少可下载视频轨" }
        if (tracks.isEmpty()) error("MPD 未找到可下载的非加密音视频轨道")
        return tracks
    }

}
}
