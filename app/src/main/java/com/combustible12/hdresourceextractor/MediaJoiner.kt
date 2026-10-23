package com.combustible12.hdresourceextractor

import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/** Shared origin preserves A/V skew. Each period appends at the prior period's end. */
object MediaJoiner {
    fun join(periods: List<Map<String, File>>, destination: File, webm: Boolean, control: SegmentTaskQueue.Control?, timeOffsets: List<Map<String, Long>> = emptyList()) {
        destination.delete()
        val writer = MediaMuxer(destination.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val formats = mutableMapOf<String, MediaFormat>()
        val outputs = mutableMapOf<String, Int>()
        val last = mutableMapOf<String, Long>()
        var started = false
        var offset = 0L
        try {
            for ((periodIndex, period) in periods.withIndex()) {
                val readers = mutableMapOf<String, MediaExtractor>()
                try {
                    for ((type, file) in period) {
                        val ex = MediaExtractor(); readers[type] = ex
                        ex.setDataSource(file.path)
                        val index = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("$type/") == true }
                            ?: error("缺少 $type 媒体轨道")
                        val format = ex.getTrackFormat(index)
                        if (!started) { formats[type] = format; outputs[type] = writer.addTrack(format) }
                        else require(sameFormat(formats[type], format)) { "Period 编码参数发生变化，无法无损合并；原片段已保留" }
                        ex.selectTrack(index)
                    }
                    if (!started) { writer.start(); started = true }
                    require(readers.keys == outputs.keys) { "Period 音视频轨道数量发生变化" }
                    val shifts = timeOffsets.getOrNull(periodIndex).orEmpty()
                    val origin = readers.entries.filter { it.value.sampleTime >= 0 }
                        .minOfOrNull { (type, ex) -> ex.sampleTime - (shifts[type] ?: 0L) } ?: error("媒体没有样本")
                    var periodEnd = offset
                    val done = mutableSetOf<String>()
                    val steps = mutableMapOf<String, Long>()
                    val rawLast = mutableMapOf<String, Long>()
                    val corrections = mutableMapOf<String, Long>()
                    var buffer = ByteBuffer.allocateDirect(1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    while (done.size < readers.size) {
                        control?.check()
                        val type = readers.keys.filter { it !in done }.minByOrNull { type ->
                            readers.getValue(type).sampleTime.let { if (it < 0) Long.MAX_VALUE else it - (shifts[type] ?: 0L) }
                        } ?: break
                        val ex = readers.getValue(type)
                        val raw = ex.sampleTime
                        if (raw < 0) { done.add(type); continue }
                        val needed = if (android.os.Build.VERSION.SDK_INT >= 28) ex.sampleSize else 16L * 1024 * 1024
                        require(needed <= 64L * 1024 * 1024) { "媒体样本过大" }
                        if (needed > buffer.capacity()) buffer = ByteBuffer.allocateDirect(needed.toInt())
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) { done.add(type); continue }
                        val prev = rawLast[type]
                        val step = steps[type] ?: if (type == "audio") 20000L else 33333L
                        // Small backwards PTS can be B-frame reordering; MP4 accepts it. Large resets need an epoch shift.
                        var correction = corrections[type] ?: 0L
                        if (prev != null && raw < prev - 1_000_000L) correction += prev - raw + step
                        corrections[type] = correction
                        if (prev != null && raw > prev && raw - prev < 1_000_000L) steps[type] = raw - prev
                        rawLast[type] = raw
                        val pts = (offset + raw - (shifts[type] ?: 0L) - origin + correction).coerceAtLeast(offset)
                        // WebM requires monotonically increasing timestamps.
                        val time = if (webm) maxOf(pts, (last[type] ?: -1) + 1) else pts
                        last[type] = time
                        info.set(0, size, time, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        writer.writeSampleData(outputs.getValue(type), buffer, info)
                        periodEnd = maxOf(periodEnd, time + (steps[type] ?: step))
                        ex.advance()
                    }
                    offset = periodEnd
                } finally { readers.values.forEach { it.release() } }
            }
            require(started) { "没有可合并轨道" }
            writer.stop()
        } catch (e: Exception) { destination.delete(); throw e }
        finally { writer.release() }
    }
    private fun sameFormat(a: MediaFormat?, b: MediaFormat): Boolean {
        if (a == null) return false
        for (key in listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)) {
            if (a.containsKey(key) != b.containsKey(key)) return false
            if (a.containsKey(key) && (if (key == MediaFormat.KEY_MIME) a.getString(key) != b.getString(key) else a.getInteger(key) != b.getInteger(key))) return false
        }
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            if (a.getByteBuffer(key)?.duplicate() != b.getByteBuffer(key)?.duplicate()) return false
        }
        return true
    }
}
