package com.combustible12.hdresourceextractor

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class XhsVideoDownloader(private val context: Context) {

    data class Result(
        val success: Boolean,
        val message: String,
        val usedUrl: String? = null
    )

    fun download(
        candidates: List<String>,
        userAgent: String,
        referer: String,
        cookies: Map<String, String>,
        status: ((String) -> Unit)? = null,
        callback: (Result) -> Unit
    ) {
        Thread {
            if (candidates.isEmpty()) {
                callback(Result(false, "没有可用的视频源"))
                return@Thread
            }

            val maxAttemptsPerSource = 3
            var lastReason = "视频源不可用"

            candidates.forEachIndexed { index, url ->
                for (attempt in 1..maxAttemptsPerSource) {
                    status?.invoke(
                        if (attempt == 1) "正在下载视频…"
                        else "正在自动重试 $attempt/$maxAttemptsPerSource…"
                    )

                    try {
                        val fetched = fetchCandidate(
                            url = url,
                            userAgent = userAgent,
                            referer = referer,
                            cookie = cookies[url].orEmpty(),
                            candidateIndex = index
                        )
                        if (fetched != null) {
                            val saved = saveToMediaStore(fetched)
                            if (saved) {
                                callback(Result(true, "视频已保存到 Movies/HDResourceExtractor", url))
                                return@Thread
                            }
                            lastReason = "视频已获取，但写入手机存储失败"
                        } else {
                            lastReason = "视频源返回的内容无效"
                        }
                    } catch (e: Exception) {
                        lastReason = friendlyReason(e.message ?: "视频源请求失败")
                    }

                    if (attempt < maxAttemptsPerSource) {
                        status?.invoke(
                            "$lastReason，正在自动重试 ${attempt + 1}/$maxAttemptsPerSource…"
                        )
                        try {
                            Thread.sleep(1_000)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            callback(Result(false, "视频下载已中断"))
                            return@Thread
                        }
                    }
                }
            }

            callback(
                Result(
                    false,
                    "视频下载失败：已自动重试 $maxAttemptsPerSource 次；$lastReason"
                )
            )
        }.start()
    }

    private fun friendlyReason(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("connection closed") ||
                lower.contains("connection reset") ||
                lower.contains("unexpected end") ||
                lower.contains("broken pipe") -> "连接中断"
            lower.contains("timed out") || lower.contains("timeout") -> "连接超时"
            else -> raw
        }
    }

    private fun fetchCandidate(
        url: String,
        userAgent: String,
        referer: String,
        cookie: String,
        candidateIndex: Int
    ): File? {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8")) return null

        val refererModes = if (referer.isBlank()) listOf(false) else listOf(false, true)
        var lastCode = -1

        for (useReferer in refererModes) {
            var connection: HttpURLConnection? = null
            var tempFile: File? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    requestMethod = "GET"
                    if (userAgent.isNotBlank()) setRequestProperty("User-Agent", userAgent)
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                    if (useReferer) setRequestProperty("Referer", referer)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    connect()
                }

                lastCode = connection.responseCode
                if (lastCode !in 200..299) continue

                val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
                if (mime.contains("mpegurl") || mime.contains("m3u")) continue

                tempFile = File(
                    context.cacheDir,
                    "xhs_video_${System.currentTimeMillis()}_${candidateIndex}.tmp"
                )
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output, 256 * 1024)
                    }
                }

                if (tempFile.length() < 128 * 1024L) {
                    tempFile.delete()
                    continue
                }

                val looksLikeMp4 = hasMp4Signature(tempFile)
                val looksLikeVideo = mime.startsWith("video/") ||
                    mime == "application/octet-stream" ||
                    looksLikeMp4

                if (!looksLikeVideo) {
                    tempFile.delete()
                    continue
                }

                return tempFile
            } catch (e: Exception) {
                tempFile?.delete()
                throw e
            } finally {
                connection?.disconnect()
            }
        }

        if (lastCode >= 400) {
            throw IllegalStateException("服务器返回 HTTP $lastCode")
        }
        return null
    }

    private fun hasMp4Signature(file: File): Boolean {
        if (file.length() < 12) return false
        val header = ByteArray(16)
        val count = FileInputStream(file).use { it.read(header) }
        if (count < 8) return false
        val text = String(header, 0, count, Charsets.ISO_8859_1)
        return text.contains("ftyp") || text.contains("moov") || text.contains("moof")
    }

    private fun saveToMediaStore(tempFile: File): Boolean {
        val fileName = "xhs_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/HDResourceExtractor"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val target = resolver.insert(collection, values) ?: run {
            tempFile.delete()
            return false
        }

        return try {
            resolver.openOutputStream(target, "w")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output, 256 * 1024)
                }
            } ?: throw IllegalStateException("无法打开视频保存位置")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(target, ready, null, null)
            }
            true
        } catch (_: Exception) {
            resolver.delete(target, null, null)
            false
        } finally {
            tempFile.delete()
        }
    }
}
