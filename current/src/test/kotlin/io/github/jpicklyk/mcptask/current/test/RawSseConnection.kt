package io.github.jpicklyk.mcptask.current.test

import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * A raw TCP client for one HTTP/1.1 GET that opens a server-sent-events stream: it sends the request itself, so the test
 * controls the half-close ([halfClose]) and the full close ([close]) of the socket and sees exactly the bytes (chunk framing
 * included) the server wrote. Ktor's `testApplication` cannot model a client that goes away, and a real HTTP client library
 * would hide the difference between a half-close and a close.
 *
 * Reads are bounded: [awaitEof] and [readFor] poll with a short socket timeout up to a caller-given deadline, never a bare
 * sleep.
 */
class RawSseConnection(
    port: Int,
    path: String,
    headers: Map<String, String> = emptyMap()
) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port).apply { soTimeout = POLL_MS }
    private val received = StringBuilder()
    private var eof = false

    /** The status line's code, or null until [awaitHead] has seen the full head. */
    var status: Int? = null
        private set

    init {
        val request =
            buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(port).append("\r\n")
                append("Accept: text/event-stream\r\n")
                headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("\r\n")
            }
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    /** Everything read so far (head and body, chunk framing included). */
    val text: String get() = received.toString()

    /** True once the server ended the stream (EOF) or reset the connection. */
    val closedByServer: Boolean get() = eof

    /** Number of non-overlapping occurrences of [needle] in everything read so far. */
    fun count(needle: String): Int = text.windowed(needle.length).count { it == needle }

    /** Reads for up to [timeoutMs] (or until EOF); returns true when the server had ended the stream by then. */
    fun readFor(timeoutMs: Long): Boolean = pump(timeoutMs) { false }

    /** Reads until the server ends the stream, for up to [timeoutMs]; true when it did. */
    fun awaitEof(timeoutMs: Long): Boolean = pump(timeoutMs) { false }

    /**
     * Reads until the server ended the stream, for up to [timeoutMs]: either the connection closed (EOF) or the chunked response
     * completed (the terminating `0` chunk after the head), which on a kept-alive connection is all a finished SSE call writes.
     */
    fun awaitStreamEnd(timeoutMs: Long): Boolean {
        val terminator = "\r\n0\r\n\r\n"
        pump(timeoutMs) { it.contains("\r\n\r\n") && it.endsWith(terminator) }
        return eof || text.endsWith(terminator)
    }

    /** Reads until the response head has arrived (status parsed), for up to [timeoutMs]; true when it did. */
    fun awaitHead(timeoutMs: Long): Boolean {
        pump(timeoutMs) { it.contains("\r\n\r\n") }
        if (status == null && text.contains("\r\n\r\n")) {
            status =
                Regex("^HTTP/1\\.[01] (\\d{3})")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
        }
        return status != null
    }

    /** Reads until [stopWhen] holds for the text so far, EOF, or the deadline; returns [closedByServer]. */
    fun awaitText(
        timeoutMs: Long,
        stopWhen: (String) -> Boolean
    ): Boolean = pump(timeoutMs) { stopWhen(it) }

    /** Half-closes the client's side (FIN after the request): the client still reads. */
    fun halfClose() {
        socket.shutdownOutput()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun pump(
        timeoutMs: Long,
        stopWhen: (String) -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val buffer = ByteArray(4096)
        val input = socket.getInputStream()
        while (!eof && System.nanoTime() < deadline) {
            if (stopWhen(text)) return eof
            try {
                val n = input.read(buffer)
                if (n < 0) {
                    eof = true
                } else {
                    received.append(String(buffer, 0, n, Charsets.ISO_8859_1))
                }
            } catch (_: SocketTimeoutException) {
                // nothing arrived in this poll slice; re-check the deadline
            } catch (_: IOException) {
                eof = true
            }
        }
        return eof
    }

    private companion object {
        const val POLL_MS = 50
    }
}

/** Polls [condition] every 25 ms for up to [timeoutMs] (real time); true as soon as it holds, false at the deadline. */
fun awaitCondition(
    timeoutMs: Long,
    condition: () -> Boolean
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition()) {
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(25)
    }
    return true
}
