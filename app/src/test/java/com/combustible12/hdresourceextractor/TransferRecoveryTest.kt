package com.combustible12.hdresourceextractor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TransferRecoveryTest {
    private val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }
    private fun server(ignoreRange: Boolean = false, test: (String, CopyOnWriteArrayList<String>) -> Unit) {
        val ranges = CopyOnWriteArrayList<String>()
        val server = TestHttpServer { headers ->
            val range = headers["range"].orEmpty(); ranges.add(range)
            val bounds = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
            val start = if (ignoreRange || bounds == null) 0 else bounds.groupValues[1].toInt()
            val end = if (ignoreRange || bounds == null) payload.lastIndex else bounds.groupValues[2].toIntOrNull() ?: payload.lastIndex
            val responseHeaders = mutableMapOf("ETag" to "\"v1\"")
            if (!ignoreRange && bounds != null) responseHeaders["Content-Range"] = "bytes $start-$end/${payload.size}"
            TestHttpServer.Response(if (!ignoreRange && bounds != null) 206 else 200, responseHeaders, payload.copyOfRange(start, end + 1))
        }
        server.use { test(it.url, ranges) }
    }
    private fun target(): File = File.createTempFile("transfer", ".bin").apply { delete(); deleteOnExit() }
    @Test fun interruptThenResumeUsesValidatedOffset() = server { url, ranges ->
        val file = target(); val first = SegmentTaskQueue.Control()
        try {
            DownloadCache.fetch(url, file, "", "", "", first, progress = { done, _ -> if (done >= 131072) { first.paused.set(true); first.check() } })
            fail("pause must interrupt")
        } catch (e: SegmentTaskQueue.Stopped) { assertTrue(e.paused) }
        val offset = file.length(); assertTrue(offset in 1 until payload.size.toLong())
        DownloadCache.fetch(url, file, "", "", "", SegmentTaskQueue.Control())
        assertArrayEquals(payload, file.readBytes())
        assertEquals("bytes=$offset-", ranges.last())
    }
    @Test fun rangeIgnoredStillSavesOnlyRequestedBytes() = server(ignoreRange = true) { url, _ ->
        val file = target()
        DownloadCache.fetch(url, file, "", "", "", null, "100-399")
        assertArrayEquals(payload.copyOfRange(100, 400), file.readBytes())
    }
    @Test fun corruptCompletedCacheIsDownloadedAgain() = server { url, ranges ->
        val file = target()
        DownloadCache.fetch(url, file, "", "", "", null)
        java.io.RandomAccessFile(file, "rw").use { it.seek(23); it.write(255) }
        DownloadCache.fetch(url, file, "", "", "", null)
        assertArrayEquals(payload, file.readBytes()); assertEquals(2, ranges.size)
    }
    @Test fun completedSegmentIsReusedWithoutRequest() = server { url, ranges ->
        val file = target()
        repeat(2) { DownloadCache.fetch(url, file, "", "", "", null, "0-399") }
        assertEquals(1, ranges.size); assertArrayEquals(payload.copyOfRange(0, 400), file.readBytes())
    }
    @Test fun htmlIsRejectedBeforePublishing() {
        val file = target().apply { writeText("<!DOCTYPE html><html>login</html>") }
        try { MediaSniffer.detect(file, "text/html", "a.jpg"); fail("must reject") } catch (_: IllegalArgumentException) { }
    }
    @Test fun upgradeVersionComparisonUsesNumbers() {
        assertTrue(AppUpdater.compareVersions("0.15.0", "0.9.9") > 0)
        assertEquals(0, AppUpdater.compareVersions("1.0", "1.0.0"))
    }
}
