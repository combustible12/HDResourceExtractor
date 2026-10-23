package com.combustible12.hdresourceextractor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QueueRaceTest {
    @Test fun pauseResumeCancelCannotResurrectOldGeneration() {
        val app = RuntimeEnvironment.getApplication()
        val store = DownloadTaskStore(app)
        val queue = SegmentTaskQueue.get(app)
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = TestHttpServer {
            requests.incrementAndGet(); entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            TestHttpServer.Response(200, body = byteArrayOf(1, 2, 3))
        }
        try {
            val task = store.add(server.url, "FILE", name = "file.mp4")
            queue.start(task.id)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.pause(task.id); queue.resume(task.id); queue.cancel(task.id)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (queue.isActive(task.id) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(queue.isActive(task.id)); assertEquals("cancelled", store.get(task.id)?.status)
            val before = requests.get()
            assertFalse(queue.resume(task.id)); queue.recover()
            Thread.sleep(100)
            assertEquals(before, requests.get()); assertEquals("cancelled", store.get(task.id)?.status)
        } finally { release.countDown(); server.close() }
    }
}
