package com.farrow.app.data.mcp

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Tiny HTTP/1.1 server for tests (android.jar has no com.sun.net.httpserver). One request per connection. */
class MiniHttpServer {
    class Req(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String) {
        fun header(name: String) = headers[name.lowercase()]
    }
    class Res(val out: OutputStream) {
        private var sent = false
        fun reply(code: Int, body: String = "", type: String = "application/json", headers: Map<String, String> = emptyMap()) {
            val b = body.toByteArray()
            val h = StringBuilder("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> h.append("$k: $v\r\n") }
            out.write((h.toString() + "\r\n").toByteArray()); out.write(b); out.flush(); sent = true
        }
        /** Starts an open-ended event stream; write events with [event]. */
        fun startStream() {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); sent = true
        }
        fun event(text: String) { out.write(text.toByteArray()); out.flush() }
        val isSent get() = sent
    }

    private val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = ss.localPort
    private val routes = mutableMapOf<String, (Req, Res) -> Unit>()

    fun route(path: String, h: (Req, Res) -> Unit) { routes[path] = h }

    fun start() {
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        val inp = s.getInputStream().buffered()
        fun line(): String { val sb = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
        val (method, target) = line().split(" ").let { it[0] to it[1] }
        val headers = mutableMapOf<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(":").trim().lowercase()] = l.substringAfter(":").trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len).also { var off = 0; while (off < len) { val n = inp.read(it, off, len - off); if (n < 0) break; off += n } }
        val req = Req(method, target.substringBefore("?"), target.substringAfter("?", "").ifBlank { null }, headers, body.decodeToString())
        val res = Res(s.getOutputStream())
        val h = routes[req.path]
        runCatching { if (h == null) res.reply(404, "not found") else h(req, res) }
        if (!res.isSent) runCatching { res.reply(500, "no reply") }
    }

    fun stop() = ss.close()
}
