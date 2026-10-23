package com.combustible12.hdresourceextractor

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Two concurrent segmented downloads, with cooperative pause and cancellation. */
class SegmentTaskQueue private constructor(context: Context) {
    class Stopped(val paused: Boolean): RuntimeException(if (paused) "任务已暂停" else "任务已取消")
    class Control {
        val paused = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        fun check() {
            if (cancelled.get()) throw Stopped(false)
            if (paused.get()) throw Stopped(true)
        }
    }
    private val app = context.applicationContext
    private val store = DownloadTaskStore(app)
    private val executor = Executors.newFixedThreadPool(2)
    private val controls = ConcurrentHashMap<String, Control>()
    private val actions = ConcurrentHashMap<String, (Control, (Int, String) -> Unit) -> Unit>()

    fun submit(id: String, action: (Control, (Int, String) -> Unit) -> Unit) {
        actions[id] = action
        val control = Control()
        controls[id] = control
        store.update(id, "queued", detail = "排队等待")
        executor.execute {
            try {
                control.check()
                store.update(id, "running", detail = "正在下载")
                action(control) { percent, detail ->
                    control.check()
                    store.update(id, "running", percent.coerceIn(0, 99), detail)
                }
                control.check()
                store.update(id, "completed", 100, "下载完成")
                actions.remove(id)
            } catch (e: Stopped) {
                store.update(id, if (e.paused) "paused" else "cancelled", detail = e.message.orEmpty())
                if (!e.paused) actions.remove(id)
            } catch (e: Exception) {
                store.update(id, "failed", detail = e.message ?: "下载失败")
            } finally { controls.remove(id, control) }
        }
    }

    fun isActive(id: String): Boolean = controls.containsKey(id)
    fun pause(id: String) {
        val control = controls[id]
        if (control != null) {
            control.paused.set(true)
            store.update(id, "paused", detail = "暂停中，保留已完成片段")
        }
        else if (store.get(id)?.status == "queued") store.update(id, "paused", detail = "已暂停")
    }
    fun cancel(id: String) {
        controls[id]?.cancelled?.set(true)
        actions.remove(id)
        store.update(id, "cancelled", detail = "已取消")
    }
    fun resume(id: String): Boolean {
        if (controls.containsKey(id)) return false
        val action = actions[id] ?: return false
        submit(id, action)
        return true
    }
    companion object {
        @Volatile private var instance: SegmentTaskQueue? = null
        fun get(context: Context): SegmentTaskQueue =
            instance ?: synchronized(this) {
                instance ?: SegmentTaskQueue(context).also { instance = it }
            }
    }
}
