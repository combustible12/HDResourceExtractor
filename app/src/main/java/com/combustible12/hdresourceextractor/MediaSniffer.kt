package com.combustible12.hdresourceextractor

import java.io.File

/** A 200 login/challenge response is not a saved image. Detect real formats before publishing. */
object MediaSniffer {
    enum class Manifest { HLS, DASH }
    fun manifest(file: File, contentType: String): Manifest? {
        val head = file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val chars = CharArray(8192)
            val count = reader.read(chars)
            if (count <= 0) "" else String(chars, 0, count)
        }.trimStart()
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when {
            head.startsWith("#EXTM3U") || mime.contains("mpegurl") -> Manifest.HLS
            Regex("""<(?:\w+:)?MPD(?:\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(head) || mime.contains("dash+xml") -> Manifest.DASH
            else -> null
        }
    }
    fun detect(file: File, contentType: String, name: String): String {
        val bytes = file.inputStream().use { input -> val b = ByteArray(512); b.copyOf(input.read(b).coerceAtLeast(0)) }
        require(bytes.isNotEmpty()) { "下载文件为空" }
        val text = bytes.toString(Charsets.ISO_8859_1)
        val trimmed = text.trimStart().lowercase()
        require(!trimmed.startsWith("<!doctype html") && !trimmed.startsWith("<html") && !trimmed.startsWith("{\"") && !trimmed.startsWith("<head")) { "服务器返回网页或登录提示，请重新打开原页面提取" }
        val mime = contentType.substringBefore(';').trim().lowercase()
        fun prefix(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
        return when {
            prefix(0xff, 0xd8, 0xff) -> "image/jpeg"
            prefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
            text.startsWith("GIF8") -> "image/gif"
            text.startsWith("RIFF") && text.substring(8, minOf(12, text.length)) == "WEBP" -> "image/webp"
            text.startsWith("BM") -> "image/bmp"
            text.length >= 12 && text.substring(4, 8) == "ftyp" -> when (text.substring(8, 12)) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "mif1", "msf1" -> "image/heif"
                "M4A " -> "audio/mp4"
                else -> if (mime.startsWith("audio/")) mime else "video/mp4"
            }
            prefix(0x1a, 0x45, 0xdf, 0xa3) -> "video/webm"
            text.startsWith("ID3") -> "audio/mpeg"
            text.startsWith("OggS") -> "audio/ogg"
            trimmed.startsWith("<svg") || trimmed.startsWith("<?xml") && trimmed.contains("<svg") -> "image/svg+xml"
            mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") -> mime
            mime == "text/html" || mime.contains("json") -> error("此链接未返回媒体文件，请重新提取")
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
        }
    }
}
