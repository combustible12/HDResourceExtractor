package com.combustible12.hdresourceextractor

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One scheduler owns file, HLS and DASH tasks; controls never outlive their generation. */
class SegmentTaskQueue private constructor(context: Context) {
    class Stopped(val paused: Boolean): RuntimeException(if (paused) "任务已暂停" else "任务已取消")
    class Control {
        @Volatile var committed = false
        @Volatile var retry = false
        val paused = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        @Volatile private var connection: java.net.HttpURLConnection? = null
        fun check() { if (committed) return; if (cancelled.get()) throw Stopped(false); if (paused.get()) throw Stopped(true) }
        fun attach(c: java.net.HttpURLConnection) { connection = c; check() }
        fun detach(c: java.net.HttpURLConnection) { if (connection === c) connection = null }
        @Synchronized fun stop(pause: Boolean) { if (committed) return; if (pause) paused.set(true) else cancelled.set(true); connection?.disconnect() }
    }
    private val app = context.applicationContext
    private val store = DownloadTaskStore(app)
    private val executor = Executors.newFixedThreadPool(2)
    private val controls = mutableMapOf<String, Control>()
    private val resumeAfterExit = mutableSetOf<String>()
    @Synchronized fun isActive(id: String) = controls.containsKey(id)
    @Synchronized fun recover() {
        store.all().filter { it.kind != "SYSTEM" && it.status in listOf("queued", "running") }.forEach { start(it.id) }
    }
    @Synchronized fun start(id: String) {
        if (controls.containsKey(id)) return
        val task = store.get(id) ?: return
        if (task.status !in listOf("queued", "running", "paused", "failed", "interrupted")) return
        if (controls.keys.any { key -> store.get(key)?.url?.let { ResourceIdentity.of(it) } == ResourceIdentity.of(task.url) }) return
        val c = Control(); controls[id] = c
        store.update(id, "queued", detail = "排队等待")
        executor.execute {
            try {
                c.check()
                synchronized(this) { c.check(); store.update(id, "running", detail = "正在下载") }
                var lastUpdate = 0L
                var lastPercent = -1
                val progress: (Int, String) -> Unit = { percent, detail -> synchronized(this) {
                    c.check()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (percent != lastPercent || now - lastUpdate >= 1000) {
                        store.update(id, "running", percent.coerceIn(0, 99), detail)
                        lastUpdate = now; lastPercent = percent
                    }
                } }
                val done: (Boolean, String) -> Unit = { ok, message -> if (!ok) error(message) }
                when (task.kind) {
                    "DASH" -> DashDownloader(app).download(task.url, task.agent, task.referer, task.cookie, done, c, progress)
                    "HLS" -> HlsDownloader(app).download(task.url, task.agent, task.referer, task.cookie, done, c, progress)
                    "FILE" -> FileDownloader(app).download(task, c, progress)
                    else -> error("此历史任务需重新添加")
                }
                synchronized(this) { c.check(); store.update(id, "completed", 100, "已下载保存") }
            } catch (e: Exception) {
                synchronized(this) {
                    if (c.retry) store.update(id, "queued", detail = "等待系统恢复")
                    else if (c.cancelled.get()) store.update(id, "cancelled", detail = "已取消")
                    else if (c.paused.get()) store.update(id, "paused", detail = "已暂停，保留续传缓存")
                    else store.update(id, "failed", detail = e.message ?: "下载失败，缓存已保留")
                }
            } finally {
                synchronized(this) {
                    controls.remove(id)
                    if (c.cancelled.get()) DownloadCache.clear(app, task.url)
                    if (resumeAfterExit.remove(id) && !c.cancelled.get()) start(id)
                }
            }
        }
    }
    @Synchronized fun pause(id: String) {
        resumeAfterExit.remove(id)
        if (store.get(id)?.status !in listOf("queued", "running")) return
        if (controls[id]?.committed == true) return
        controls[id]?.stop(true)
        store.update(id, "paused", detail = "已暂停，保留续传缓存")
    }
    @Synchronized fun cancel(id: String) {
        if (store.get(id)?.status == "completed" || controls[id]?.committed == true) return
        resumeAfterExit.remove(id); controls[id]?.stop(false)
        store.update(id, "cancelled", detail = "已取消")
        if (!controls.containsKey(id)) store.get(id)?.let { DownloadCache.clear(app, it.url) }
    }
    @Synchronized fun resume(id: String): Boolean {
        val task = store.get(id) ?: return false
        if (task.status !in listOf("paused", "failed", "interrupted")) return false
        val c = controls[id]
        if (c != null) { if (c.cancelled.get()) return false; resumeAfterExit.add(id) } else start(id)
        return true
    }
    @Synchronized fun suspendForSystem() {
        controls.values.forEach { if (!it.committed) { it.retry = true; it.stop(true) } }
    }
    companion object {
        @Volatile private var instance: SegmentTaskQueue? = null
        fun get(context: Context): SegmentTaskQueue = instance ?: synchronized(this) {
            instance ?: SegmentTaskQueue(context).also { instance = it }
        }
    }
}
