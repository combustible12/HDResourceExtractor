package com.combustible12.hdresourceextractor

import android.app.*
import android.app.job.*
import android.content.*
import android.os.*

/** Foreground execution survives Activity destruction; durable jobs recover reclaimed processes. */
class DownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            SegmentTaskQueue.get(this@DownloadService).recover()
            val tasks = DownloadTaskStore(this@DownloadService).all()
            val active = tasks.filter { it.kind != "SYSTEM" && it.status in listOf("queued", "running") }
            if (active.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            getSystemService(NotificationManager::class.java).notify(210, notification("${active.size} 个任务 · ${active.first().progress}%"))
            handler.postDelayed(this, 1500)
        }
    }
    override fun onCreate() {
        super.onCreate()
        running = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:downloads").apply { acquire(6 * 60 * 60 * 1000L) }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "下载任务", NotificationManager.IMPORTANCE_LOW))
        startForeground(210, notification("正在恢复下载任务"))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "downloads") else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("HDResourceExtractor")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        schedule(this)
        SegmentTaskQueue.get(this).recover()
        handler.removeCallbacks(tick); handler.post(tick)
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { running = false; handler.removeCallbacksAndMessages(null); wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) {
        SegmentTaskQueue.get(this).suspendForSystem()
        schedule(this); stopSelf()
    }
    companion object {
        @Volatile var running = false
            private set
        fun start(context: Context) {
            val app = context.applicationContext
            schedule(app)
            val intent = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        }
        fun schedule(context: Context) {
            val job = JobInfo.Builder(211, ComponentName(context, DownloadRecoveryJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(15000).build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}

class DownloadRecoveryJob : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var current: JobParameters? = null
    private val poll = object : Runnable {
        override fun run() {
            val params = current ?: return
            val active = DownloadTaskStore(this@DownloadRecoveryJob).all().any { it.kind != "SYSTEM" && it.status in listOf("queued", "running") }
            if (active) handler.postDelayed(this, 2000) else { current = null; jobFinished(params, false) }
        }
    }
    override fun onStartJob(params: JobParameters): Boolean {
        if (DownloadService.running) return false
        current = params
        SegmentTaskQueue.get(this).recover()
        handler.post(poll)
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        current = null; handler.removeCallbacksAndMessages(null)
        if (!DownloadService.running) SegmentTaskQueue.get(this).suspendForSystem()
        return true
    }
}
