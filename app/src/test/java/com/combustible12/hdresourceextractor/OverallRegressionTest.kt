package com.combustible12.hdresourceextractor

import android.content.Context
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OverallRegressionTest {
    private fun target() = File.createTempFile("overall", ".bin").apply { deleteOnExit() }

    @Test fun httpRejectionMustNotDestroyPreviouslyValidatedPartialBytes() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        TestHttpServer { TestHttpServer.Response(403, body = "denied".toByteArray()) }.use { server ->
            val file = target().apply { writeBytes(bytes) }
            DownloadCache.write(File(file.path + ".json"), JSONObject()
                .put("identity", ResourceIdentity.hash(server.url + "|"))
                .put("bytes", bytes.size).put("validator", "\"original\"")
                .put("sha256", DownloadCache.digest(file)).put("complete", false))
            try { DownloadCache.fetch(server.url, file, "", "", "", null); fail("403 must fail") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("403")) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("\"original\"", DownloadCache.json(File(file.path + ".json")).optString("validator"))
        }
    }
    @Test fun redirectedDownloadsRememberTheActualResourceBase() {
        TestHttpServer { TestHttpServer.Response(200, body = "#EXTM3U\n#EXT-X-ENDLIST".toByteArray()) }.use { destination ->
            TestHttpServer { TestHttpServer.Response(302, mapOf("Location" to destination.url), ByteArray(0)) }.use { redirect ->
                val file = target().apply { delete() }
                DownloadCache.fetch(redirect.url, file, "", "", "", null)
                assertEquals(destination.url, DownloadCache.json(File(file.path + ".json")).getString("finalUrl"))
            }
        }
    }
    @Test fun hlsUsesRedirectedBaseForRelativePlaylistAndSegments() {
        val requested = mutableListOf<String>()
        val playlist = HlsDownloader(RuntimeEnvironment.getApplication()).resolve("https://short.test/watch", 0) { url ->
            requested.add(url)
            if (requested.size == 1) "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo/index.m3u8" to "https://cdn.test/path/master.m3u8"
            else "#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST" to "https://media.test/real/index.m3u8"
        }
        assertEquals("https://cdn.test/path/video/index.m3u8", requested[1])
        assertEquals("https://media.test/real/part.ts", playlist.groups.single().single().url)
    }
    @Test fun audioMustUseAudioCollectionRatherThanVideo() {
        assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaPublisher.collection("audio/mp4", 28))
        assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaPublisher.collection("audio/ogg", 29))
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaPublisher.collection("video/mp4", 28))
    }
    @Test fun serverErrorBodiesCannotMasqueradeAsSavedImages() {
        for (body in listOf("\uFEFF<script>login</script>", "[1,2]", "<?xml version=\"1.0\"?><Error>denied</Error>")) {
            val file = target().apply { writeText(body) }
            try { MediaSniffer.detect(file, "image/jpeg", "image.jpg"); fail("must reject error text") }
            catch (_: IllegalArgumentException) { }
        }
        val svg = target().apply { writeText("\uFEFF<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>") }
        assertEquals("image/svg+xml", MediaSniffer.detect(svg, "image/svg+xml", "x.svg"))
    }
    @Test fun corruptTaskJsonRestoresLastGoodRowsWithoutDeletingTheOriginal() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("download_task_store", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = DownloadTaskStore(app)
        val task = store.add("https://cdn.test/original.jpg", "FILE", name = "original.jpg")
        prefs.edit().putString("tasks", "broken-json").commit()
        assertEquals(task, store.get(task.id))
        assertEquals("broken-json", prefs.getString("tasks_corrupt_backup", ""))
    }
    @Test fun cancellationStopsLargeSegmentConcatenationBeforeTheNextChunk() {
        val control = SegmentTaskQueue.Control()
        val bytes = ByteArray(512 * 1024)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                control.paused.set(true)
            }
        }
        try { copyChecked(bytes.inputStream(), output, control); fail("pause must stop copying") }
        catch (stopped: SegmentTaskQueue.Stopped) { assertTrue(stopped.paused) }
        assertTrue(output.size() < bytes.size)
    }
    @Test fun disconnectCannotBlockTheCallingUiThread() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("http://127.0.0.1/")) {
            override fun connect() { }
            override fun usingProxy() = false
            override fun disconnect() { entered.countDown(); release.await(4, TimeUnit.SECONDS) }
        }
        val control = SegmentTaskQueue.Control().apply { attach(connection) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { control.stop(true) }.get(1, TimeUnit.SECONDS)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            try { control.check(); fail("pause must already be visible") }
            catch (stopped: SegmentTaskQueue.Stopped) { assertTrue(stopped.paused) }
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
