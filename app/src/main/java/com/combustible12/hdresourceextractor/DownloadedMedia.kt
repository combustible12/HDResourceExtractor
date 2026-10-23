package com.combustible12.hdresourceextractor

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/** Confirm a completed system download before treating a resource as saved. */
class DownloadedMedia(private val context: Context) {
    private val prefs = context.getSharedPreferences("downloaded_media_v1", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun key(url: String): String {
        val uri = Uri.parse(url)
                val host = uri.host.orEmpty().lowercase(java.util.Locale.US)
                val signatureHosts = host.endsWith(".xhscdn.com") || host == "ci.xiaohongshu.com"
                val normalized = if (signatureHosts) uri.buildUpon().clearQuery().fragment(null).build().toString().substringBefore("!") else url.trim()
                val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun state(url: String): String {
        val key = key(url)
        val id = prefs.getLong(key + "_id", -1L)
        if (id > 0L) {
            val query = manager.query(DownloadManager.Query().setFilterById(id))
            query?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    prefs.edit().remove(key + "_id").apply()
                    return "new"
                }
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = manager.getUriForDownloadedFile(id)
                        if (uri != null && canRead(uri)) {
                            prefs.edit().putString(key + "_saved", uri.toString()).remove(key + "_id").apply()
                            return "saved"
                        }
                        prefs.edit().remove(key + "_id").apply()
                        return "new"
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(key + "_id").apply()
                        return "new"
                    }
                    else -> return "pending"
                }
            }
            return "new"
        }
        val savedUri = prefs.getString(key + "_saved", null)
        if (savedUri != null) {
            if (canRead(Uri.parse(savedUri))) return "saved"
            prefs.edit().remove(key + "_saved").apply()
        }
        return "new"
    }

    private fun canRead(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 0L } ?: false
    } catch (_: Exception) { false }

    @Synchronized
    fun cancel(url: String): Boolean {
        val id = prefs.getLong(key(url) + "_id", -1L)
        if (id <= 0L) return false
        manager.remove(id)
        prefs.edit().remove(key(url) + "_id").apply()
        return true
    }

    @Synchronized
    fun queue(url: String, id: Long) {
        prefs.edit().putLong(key(url) + "_id", id).remove(key(url) + "_saved").apply()
    }

    @Synchronized
    fun saved(url: String, uri: Uri) {
        prefs.edit().putString(key(url) + "_saved", uri.toString())
            .remove(key(url) + "_id").apply()
    }

    @Synchronized
    fun release(url: String) {
        prefs.edit().remove(key(url) + "_id").apply()
    }
}
