package com.combustible12.hdresourceextractor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All instances serialize read/modify/commit through one process-wide lock. */
class DownloadTaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("download_task_store", Context.MODE_PRIVATE)
    data class Task(val id: String, val url: String, val kind: String, val status: String,
        val progress: Int = 0, val detail: String = "", val systemId: Long = -1L,
        val agent: String = "", val referer: String = "", val cookie: String = "",
        val name: String = "", val output: String = "")
    private fun decode(): MutableList<Task> {
        val arr = JSONArray(prefs.getString("tasks", "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull { i -> runCatching {
            val o = arr.getJSONObject(i)
            Task(o.getString("id"), o.getString("url"), o.getString("kind"), o.getString("status"),
                o.optInt("progress"), o.optString("detail"), o.optLong("systemId", -1),
                o.optString("agent"), o.optString("referer"), o.optString("cookie"),
                o.optString("name"), o.optString("output"))
        }.getOrNull() }.toMutableList()
    }
    private fun save(tasks: List<Task>) {
        val arr = JSONArray()
        // Completed identities are deliberately retained: removing a row must not enable duplicate saves.
        tasks.forEach { t -> arr.put(JSONObject().put("id", t.id).put("url", t.url).put("kind", t.kind)
            .put("status", t.status).put("progress", t.progress).put("detail", t.detail)
            .put("systemId", t.systemId).put("agent", t.agent).put("referer", t.referer)
            .put("cookie", t.cookie).put("name", t.name).put("output", t.output)) }
        check(prefs.edit().putString("tasks", arr.toString()).commit()) { "无法保存任务状态" }
    }
    fun all(): List<Task> = synchronized(lock) { decode().asReversed() }
    fun get(id: String): Task? = synchronized(lock) { decode().firstOrNull { it.id == id } }
    fun add(url: String, kind: String, systemId: Long = -1L, agent: String = "", referer: String = "",
            cookie: String = "", name: String = ""): Task = synchronized(lock) {
        val tasks = decode()
        val identity = ResourceIdentity.of(url)
        check(tasks.none { ResourceIdentity.of(it.url) == identity && it.status !in listOf("failed", "cancelled") }) {
            if (tasks.any { ResourceIdentity.of(it.url) == identity && it.status == "completed" }) "此资源已下载保存" else "此资源已在下载任务中"
        }
        val task = Task(java.util.UUID.randomUUID().toString(), url, kind, "queued", systemId = systemId,
            agent = agent, referer = referer, cookie = cookie, name = name)
        tasks.add(task); save(tasks); task
    }
    fun update(id: String, status: String, progress: Int? = null, detail: String = "", output: String? = null) = synchronized(lock) {
        val tasks = decode(); val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) { val old = tasks[index]
            tasks[index] = old.copy(status = status, progress = progress ?: old.progress, detail = detail, output = output ?: old.output)
            save(tasks)
        }
    }
    fun delete(id: String) = synchronized(lock) {
        val tasks = decode(); tasks.removeAll { it.id == id && it.status != "completed" }; save(tasks)
    }
    companion object { private val lock = Any() }
}
