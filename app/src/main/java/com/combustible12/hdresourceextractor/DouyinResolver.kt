package com.combustible12.hdresourceextractor

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a Douyin share URL without relying on WebView page rendering.
 * Network failures are surfaced to the caller; unrelated feed recommendations are never returned.
 */
internal object DouyinResolver {
    private const val APP_UA = "com.ss.android.ugc.aweme/310101 (Linux; U; Android 13; zh_CN; Pixel 7; Build/TQ3A.230805.001)"
    private val idPattern = Regex("(?:/video/|/note/|/share/video/|[?&](?:modal_id|aweme_id)=)([0-9]{15,22})")

    data class Result(val id: String, val media: List<String>, val videoAlternates: List<String> = emptyList(), val cover: String? = null)

    fun resolve(link: String): Result {
        val target = resolveId(link) ?: error("短链接未返回有效作品 ID")
        val endpoint = "https://aweme.snssdk.com/aweme/v1/feed/?aweme_id=$target"
        val response = request(endpoint, APP_UA)
        val json = JSONObject(response)
        val items = json.optJSONArray("aweme_list") ?: error("作品接口没有返回视频信息")
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("aweme_id") != target) continue
            val video = item.optJSONObject("video")
            val urls = linkedSetOf<String>()
            fun addAddress(address: JSONObject?) {
                val list = address?.optJSONArray("url_list") ?: return
                for (j in 0 until list.length()) {
                    val value = list.optString(j).replace("playwm", "play")
                    if (value.startsWith("https://") || value.startsWith("http://")) urls.add(value)
                }
            }
            addAddress(video?.optJSONObject("play_addr"))
            addAddress(video?.optJSONObject("download_addr"))
            val rates = video?.optJSONArray("bit_rate")
            if (rates != null) for (j in 0 until rates.length()) {
                addAddress(rates.optJSONObject(j)?.optJSONObject("play_addr"))
            }
            val cover = video?.optJSONObject("origin_cover")?.optJSONArray("url_list")?.optString(0)
                ?: video?.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0)
            val videoUrls = urls.toList()
            urls.clear()
            val images = item.optJSONArray("images")
            if (images != null) for (j in 0 until images.length()) {
                val image = images.optJSONObject(j) ?: continue
                val list = image.optJSONArray("url_list")
                if (list != null) for (k in 0 until list.length()) {
                    val value = list.optString(k)
                    if (value.startsWith("http://") || value.startsWith("https://")) urls.add(value)
                }
            }
            if (videoUrls.isEmpty() && urls.isEmpty()) error("已找到作品，但没有可下载媒体地址")
            // One video per work; all bitrate/CDN variants are download fallbacks, not extra cards.
            return Result(target, (videoUrls.take(1) + urls).distinct(),
                videoUrls.drop(1), cover?.takeIf { it.startsWith("http") })
        }
        error("接口返回的作品与目标 ID 不一致")
    }

    private fun resolveId(link: String): String? {
        idPattern.find(link)?.let { return it.groupValues[1] }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(link).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("User-Agent", APP_UA)
            }
            conn.inputStream.use { it.readNBytesCompat(512) }
            val finalUrl = conn.url.toString()
            return idPattern.find(finalUrl)?.groupValues?.get(1)
                ?: idPattern.find(conn.getHeaderField("Location").orEmpty())?.groupValues?.get(1)
        } finally { conn?.disconnect() }
    }

    private fun request(url: String, agent: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", agent)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) error("抖音作品接口返回 HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int) {
        val buffer = ByteArray(limit)
        read(buffer)
    }
}
