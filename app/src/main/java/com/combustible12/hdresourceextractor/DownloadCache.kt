package com.combustible12.hdresourceextractor

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Persistent files, atomic sidecars and SHA-256 validation; never trust file length alone. */
object DownloadCache {
    fun dir(context: Context, url: String): File = File(context.filesDir, "downloads/${ResourceIdentity.of(url)}").apply { mkdirs() }
    fun clear(context: Context, url: String) { dir(context, url).deleteRecursively(); clearPending(context, ResourceIdentity.of(url)) }
    private fun clearPending(context: Context, identity: String) {
        val prefs = context.getSharedPreferences("saved_resources", Context.MODE_PRIVATE)
        val key = "pending_$identity"
        prefs.getString(key, null)?.let { uri -> runCatching { context.contentResolver.delete(android.net.Uri.parse(uri), null, null) } }
        prefs.edit().remove(key).commit()
    }
    fun clean(context: Context, retained: Set<String>) {
        // An unreadable task list is not proof that its partial downloads are disposable.
        if (context.getSharedPreferences("download_task_store", Context.MODE_PRIVATE)
                .getBoolean("preserve_recovery_cache", false)) return
        File(context.filesDir, "downloads").listFiles()?.filter { it.name !in retained }?.forEach { it.deleteRecursively() }
        val prefs = context.getSharedPreferences("saved_resources", Context.MODE_PRIVATE)
        prefs.all.keys.filter { it.startsWith("pending_") && it.removePrefix("pending_") !in retained }
            .forEach { clearPending(context, it.removePrefix("pending_")) }
        context.cacheDir.listFiles()?.filter { it.name.startsWith("dash_mux_") || it.name.startsWith("hls_segment_") }?.forEach { it.delete() }
    }
    fun digest(file: File): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(128 * 1024); while (true) {
            val n = input.read(b); if (n < 0) break; d.update(b, 0, n)
        } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
    fun write(file: File, value: JSONObject) {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(value.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    fun json(file: File): JSONObject = runCatching { JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }.getOrElse { JSONObject() }

    fun fetch(url: String, target: File, agent: String, referer: String, cookie: String,
        control: SegmentTaskQueue.Control?, range: String? = null, progress: ((Long, Long) -> Unit)? = null): File {
        val metaFile = File(target.path + ".json")
        val identity = ResourceIdentity.hash(url + "|" + range.orEmpty())
        var meta = json(metaFile)
        if (!meta.optBoolean("complete") && meta.optString("identity") == identity && target.exists() &&
            meta.optLong("bytes", -1) in 0..target.length()) {
            // Discard only bytes written after the last durable checkpoint.
            RandomAccessFile(target, "rw").use { it.setLength(meta.optLong("bytes")) }
        }
        if (meta.optString("identity") != identity || !target.exists() || meta.optLong("bytes") != target.length() ||
            meta.optString("sha256").isEmpty() || digest(target) != meta.optString("sha256")) {
            target.delete(); meta = JSONObject()
        }
        if (meta.optBoolean("complete") && target.length() > 0) return target
        // Partial resources require a server validator. Otherwise start clean.
        var offset = if (meta.optString("validator").isNotBlank()) target.length() else 0L
        if (offset == 0L) target.delete()
        val bounds = range?.let { Regex("(\\d+)-(\\d+)").matchEntire(it) ?: error("无效字节范围") }
        val start = bounds?.groupValues?.get(1)?.toLong() ?: 0L
        val end = bounds?.groupValues?.get(2)?.toLong()
        if (end != null && end < start) error("无效字节范围")
        var failure: Exception? = null
        repeat(3) {
            control?.check()
            val conn = URL(url).openConnection() as HttpURLConnection
            var expected = -1L
            var total = -1L
            var validator = ""
            var responseAccepted = false
            try {
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (agent.isNotBlank()) conn.setRequestProperty("User-Agent", agent)
                if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
                if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
                if (range != null || offset > 0) conn.setRequestProperty("Range", "bytes=${start + offset}-${end ?: ""}")
                if (offset > 0) conn.setRequestProperty("If-Range", meta.optString("validator"))
                control?.attach(conn)
                val code = conn.responseCode
                validator = conn.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: conn.getHeaderField("Last-Modified").orEmpty()
                if (code == 416 && offset > 0) {
                    offset = 0; target.delete(); meta = JSONObject(); return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                if (code == 206) {
                    val cr = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(conn.getHeaderField("Content-Range").orEmpty())
                        ?: error("服务器续传范围无效")
                    if (cr.groupValues[1].toLong() != start + offset) error("服务器续传起点不符")
                    if (end != null && cr.groupValues[2].toLong() != end) error("服务器返回范围不完整")
                    if (offset > 0 && validator != meta.optString("validator")) {
                        offset = 0; target.delete(); meta = JSONObject(); return@repeat
                    }
                    expected = cr.groupValues[2].toLong() - cr.groupValues[1].toLong() + 1
                    total = end?.let { it - start + 1 } ?: cr.groupValues[3].toLongOrNull() ?: -1L
                } else {
                    // Range ignored: discard the previous partial file and use a fresh response.
                    offset = 0; target.delete()
                    expected = conn.contentLengthLong
                    total = end?.let { it - start + 1 } ?: expected
                }
                responseAccepted = true
                RandomAccessFile(target, "rw").use { out ->
                    out.setLength(offset); out.seek(offset)
                    val hasher = java.security.MessageDigest.getInstance("SHA-256")
                    if (offset > 0) target.inputStream().use { old ->
                        val b = ByteArray(128 * 1024)
                        while (true) { control?.check(); val n = old.read(b); if (n < 0) break; hasher.update(b, 0, n) }
                    }
                    var checkpointAt = android.os.SystemClock.elapsedRealtime()
                    conn.inputStream.use { input ->
                        if (code != 206 && start > 0) {
                            var skip = start
                            val buffer = ByteArray(128 * 1024)
                            while (skip > 0) { control?.check(); val n = input.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt()); if (n < 0) error("服务器文件短于字节范围"); skip -= n }
                        }
                        var received = 0L
                        val limit = if (range != null && code != 206) end!! - start + 1 else expected
                        val buffer = ByteArray(128 * 1024)
                        while (limit < 0 || received < limit) {
                            control?.check()
                            val count = input.read(buffer, 0, if (limit < 0) buffer.size else minOf(buffer.size.toLong(), limit - received).toInt())
                            if (count < 0) break
                            out.write(buffer, 0, count); hasher.update(buffer, 0, count); received += count
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - checkpointAt >= 2000) {
                                out.fd.sync()
                                val hash = runCatching { (hasher.clone() as java.security.MessageDigest).digest().joinToString("") { "%02x".format(it) } }.getOrElse { digest(target) }
                                write(metaFile, JSONObject().put("identity", identity).put("bytes", out.length())
                                    .put("validator", validator).put("sha256", hash).put("complete", false))
                                checkpointAt = now
                            }
                            progress?.invoke(offset + received, total)
                        }
                        if (limit >= 0 && received != limit) error("响应提前结束")
                        if (out.length() == 0L) error("空文件")
                    }
                    out.fd.sync()
                    if (total > 0 && out.length() != total) error("文件尚未完整，继续续传")
                }
                control?.check()
                write(metaFile, JSONObject().put("identity", identity).put("bytes", target.length())
                    .put("validator", validator).put("contentType", conn.contentType.orEmpty()).put("finalUrl", conn.url.toString()).put("sha256", digest(target)).put("complete", true))
                return target
            } catch (e: Exception) {
                // Preserve only bytes actually flushed to disk, plus their validator and digest.
                if (responseAccepted && target.exists() && target.length() > 0) {
                    write(metaFile, JSONObject().put("identity", identity).put("bytes", target.length())
                        .put("validator", validator).put("sha256", digest(target)).put("complete", false))
                }
                control?.check()
                failure = e; meta = json(metaFile)
                offset = if (meta.optString("validator").isNotEmpty()) target.length() else 0
                if (offset == 0L) target.delete()
            } finally { control?.detach(conn); conn.disconnect() }
        }
        throw failure ?: IllegalStateException("服务器续传状态反复变化，请重试")
    }
}
