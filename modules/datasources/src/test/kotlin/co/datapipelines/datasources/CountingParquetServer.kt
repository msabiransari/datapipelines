package co.datapipelines.datasources

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 152 — the controlled remote-object witness: a loopback HTTP server that serves ONE Parquet
 * file the way an object store does (HEAD for the length, `Range` GETs for the row groups and
 * footer) and COUNTS what the engine actually asked for. The shared-instance guards read the
 * counters before and after a physical connection replacement: a cache that survived the
 * replacement asks this server for nothing on the second scan; a cache that died with its
 * connection re-downloads the file.
 *
 * Deliberately not MinIO: the question is "how many bytes crossed the wire", and a container
 * cannot answer it without its own request log. `duckdb_external_file_cache()` is read beside
 * these counters as the engine-side half of the same witness.
 */
internal class CountingParquetServer(
    private val file: File,
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val headRequests = AtomicInteger(0)
    val getRequests = AtomicInteger(0)
    val bytesServed = AtomicLong(0)

    val url: String get() = "http://127.0.0.1:${server.address.port}/${file.name}"

    init {
        server.createContext("/") { exchange -> serve(exchange) }
        server.start()
    }

    /** Snapshot of the three counters — `(head, get, bytes)`. */
    fun counts(): Triple<Int, Int, Long> = Triple(headRequests.get(), getRequests.get(), bytesServed.get())

    fun reset() {
        headRequests.set(0)
        getRequests.set(0)
        bytesServed.set(0)
    }

    private fun serve(exchange: HttpExchange) {
        val bytes = file.readBytes()
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.responseHeaders.add("Last-Modified", "Tue, 01 Jan 2030 00:00:00 GMT")
        exchange.responseHeaders.add("ETag", "\"fixture\"")
        when (exchange.requestMethod) {
            "HEAD" -> {
                headRequests.incrementAndGet()
                exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(HTTP_OK, -1)
                exchange.close()
            }

            "GET" -> {
                getRequests.incrementAndGet()
                val range = exchange.requestHeaders.getFirst("Range")
                val (from, to) = parseRange(range, bytes.size)
                val body = bytes.copyOfRange(from, to + 1)
                if (range != null) {
                    exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${bytes.size}")
                    exchange.sendResponseHeaders(HTTP_PARTIAL_CONTENT, body.size.toLong())
                } else {
                    exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                }
                exchange.responseBody.use { it.write(body) }
                bytesServed.addAndGet(body.size.toLong())
            }

            else -> {
                exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1)
                exchange.close()
            }
        }
    }

    /** `bytes=a-b` (inclusive), `bytes=a-` and `bytes=-n` — the three forms httpfs emits. */
    private fun parseRange(
        header: String?,
        size: Int,
    ): Pair<Int, Int> {
        val spec = header?.removePrefix("bytes=") ?: return 0 to size - 1
        val (fromText, toText) = spec.split("-", limit = 2)
        return when {
            fromText.isEmpty() -> (size - toText.toInt()).coerceAtLeast(0) to size - 1
            toText.isEmpty() -> fromText.toInt() to size - 1
            else -> fromText.toInt() to toText.toInt().coerceAtMost(size - 1)
        }
    }

    override fun close() = server.stop(0)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_METHOD_NOT_ALLOWED = 405
    }
}
