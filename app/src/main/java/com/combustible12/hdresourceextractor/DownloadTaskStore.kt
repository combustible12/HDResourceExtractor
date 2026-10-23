package com.combustible12.hdresourceextractor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable download queue metadata, shared by platform and segmented downloaders. */
class DownloadTaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("download_task_store", Context.MODE_PRIVATE)
    private val lock = Any()
    data class Task(val id: String, val url: String, val kind: String, val status: String,
                    val progress: Int, val detail: String, val systemId: Long = -1L)
    private fun decode(): MutableList<Task> {
        val arr = JSONArray(prefs.getString("tasks", "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                Task(o.getString("id"), o.getString("url"), o.getString("kind"),
                    o.getString("status"), o.optInt("progress", 0), o.optString("detail"),
                    o.optLong("systemId", -1L))
            } catch (_: Exception) { null }
        }.toMutableList()
    }
    private fun save(tasks: List<Task>) {
        val arr = JSONArray()
        tasks.takeLast(200).forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("url", t.url).put("kind", t.kind)
                .put("status", t.status).put("progress", t.progress)
                .put("detail", t.detail).put("systemId", t.systemId))
        }
        prefs.edit().putString("tasks", arr.toString()).commit()
    }
    fun all(): List<Task> = synchronized(lock) { decode().asReversed() }
    fun get(id: String): Task? = synchronized(lock) { decode().firstOrNull { it.id == id } }
    fun add(url: String, kind: String, systemId: Long = -1L): Task = synchronized(lock) {
        val tasks = decode()
        val task = Task(java.util.UUID.randomUUID().toString(), url, kind, "queued", 0, "等待下载", systemId)
        tasks.add(task)
        save(tasks)
        task
    }
    fun update(id: String, status: String, progress: Int? = null, detail: String = "") = synchronized(lock) {
        val tasks = decode()
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = tasks[index]
            tasks[index] = old.copy(status = status, progress = progress ?: old.progress, detail = detail)
            save(tasks)
        }
    }
    fun delete(id: String) = synchronized(lock) {
        val tasks = decode()
        tasks.removeAll { it.id == id }
        save(tasks)
    }
}
