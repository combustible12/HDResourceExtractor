package com.combustible12.hdresourceextractor

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Tiny real HTTP/1.1 server, using only Android/JVM-common java.net APIs. */
internal class TestHttpServer(private val respond: (Map<String, String>) -> Response) : Closeable {
    data class Response(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray)
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}/file"
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "test-http").apply { isDaemon = true } }
    init {
        executor.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                executor.execute {
                    runCatching { client.use { connection ->
                        connection.soTimeout = 5000
                        val reader = connection.getInputStream().bufferedReader()
                        reader.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val response = respond(headers)
                        val out = connection.getOutputStream()
                        val head = buildString {
                            append("HTTP/1.1 ${response.code} OK\r\n")
                            append("Content-Length: ${response.body.size}\r\nConnection: close\r\n")
                            response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                            append("\r\n")
                        }
                        out.write(head.toByteArray(Charsets.ISO_8859_1)); out.write(response.body); out.flush()
                    } }
                }
            }
        }
    }
    override fun close() { socket.close(); executor.shutdownNow() }
}
