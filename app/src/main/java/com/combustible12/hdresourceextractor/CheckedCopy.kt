package com.combustible12.hdresourceextractor

internal fun copyChecked(input: java.io.InputStream, output: java.io.OutputStream, control: SegmentTaskQueue.Control?) {
    val buffer = ByteArray(128 * 1024)
    while (true) {
        control?.check()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    control?.check()
}
