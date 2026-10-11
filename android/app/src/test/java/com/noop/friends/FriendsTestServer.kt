package com.noop.friends

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A friends server that says what a test tells it to, on this machine's loopback. It speaks real HTTP
 * to the real client, so what a test reads back is what went over the wire: the request line, every
 * header as OkHttp sent it (a `Content-Length`, or the `Transfer-Encoding` the real server refuses) and
 * the body's bytes. No test talks to any other host.
 */
class FriendsTestServer : Closeable {
    class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)

        /** A header by its lower-case name. */
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Answer(val status: Int, val body: String)

    /** Answers by "METHOD path". Each request takes the next answer of its list; the last one repeats. */
    private val answers = ConcurrentHashMap<String, MutableList<Answer>>()

    /** Requests answered with a redirect to the address given, keyed like [answers]. */
    val redirects = ConcurrentHashMap<String, String>()

    /** Requests whose connection is dropped before any answer, keyed like [answers]. */
    val failures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Requests answered only after this many milliseconds, keyed like [answers]. */
    val delays = ConcurrentHashMap<String, Long>()

    val seen = CopyOnWriteArrayList<Seen>()

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { run -> Thread(run, "friends-test-server").apply { isDaemon = true } }

    /** The address a client is pointed at: plain HTTP to loopback, which [FriendsServerUrl] takes for development. */
    val url: String = "http://127.0.0.1:${socket.localPort}"

    init {
        pool.execute {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(connection) } }
            }
        }
    }

    fun answer(key: String, vararg list: Pair<Int, String>) {
        answers[key] = list.map { Answer(it.first, it.second) }.toMutableList()
    }

    fun sent(method: String, path: String): List<Seen> = seen.filter { it.method == method && it.path == path }

    fun reset() {
        answers.clear()
        redirects.clear()
        failures.clear()
        delays.clear()
        seen.clear()
    }

    override fun close() {
        runCatching { socket.close() }
        pool.shutdownNow()
    }

    private fun serve(connection: Socket) {
        connection.use { link ->
            val input = link.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val request = lines.first().split(" ")
            if (request.size < 2) return
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val body = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
                else -> readExactly(input, headers["content-length"]?.toIntOrNull() ?: 0)
            }
            val key = request[0] + " " + request[1]
            seen.add(Seen(request[0], request[1], headers, body))
            delays[key]?.let { Thread.sleep(it) }
            if (key in failures) return
            val out = link.getOutputStream()
            val redirect = redirects[key]
            if (redirect != null) {
                out.write("HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val answer = synchronized(answers) {
                val list = answers[key]
                when {
                    list == null -> Answer(404, """{"error":"not_found","message":""}""")
                    list.size > 1 -> list.removeAt(0)
                    else -> list[0]
                }
            }
            val bytes = answer.body.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 ${answer.status} Answer\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
    }

    /** The request line and headers, up to the empty line that ends them. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val byte = input.read()
            if (byte < 0) return null
            out.write(byte)
            matched = if (byte == "\r\n\r\n"[matched].code) matched + 1 else if (byte == '\r'.code) 1 else 0
        }
        return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) break
            read += n
        }
        return out.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(readExactly(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) break
            if (byte != '\r'.code) out.append(byte.toChar())
        }
        return out.toString()
    }
}
