package io.github.markalexanderhub.spareeye

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * A tiny web server for the live view, for the user's own Wi-Fi only.
 * Every path starts with a random key so the URL can't simply be guessed.
 *
 *   /<key>/          a page with the picture
 *   /<key>/stream    motion-JPEG, one frame every ~300 ms while someone is watching
 *   /<key>/snap.jpg  the latest single frame
 */
class MjpegServer(
    port: Int,
    private val key: String,
    private val latestFrame: () -> ByteArray?,
    private val rotationDegrees: () -> Int,
    private val roomName: () -> String,
) : NanoHTTPD(port) {

    val watchers = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val prefix = "/$key"
        if (!uri.startsWith(prefix)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found.")
        }
        return when (uri.removePrefix(prefix).trimEnd('/')) {
            "" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(prefix)).noCache()
            "/snap.jpg" -> {
                val f = latestFrame()
                if (f == null) newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(f), f.size.toLong()).noCache()
            }
            "/stream" -> newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", FrameStream()).noCache()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found.")
        }
    }

    private fun Response.noCache(): Response {
        addHeader("Cache-Control", "no-cache, no-store")
        addHeader("Pragma", "no-cache")
        return this
    }

    /** Streams multipart JPEG frames for as long as the browser keeps reading. */
    private inner class FrameStream : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        private var last: ByteArray? = null
        private var closed = false

        init { watchers.incrementAndGet() }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (pos >= buf.size) {
                val f = nextFrame() ?: return -1
                buf = frameBytes(f)
                pos = 0
            }
            val n = min(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }

        private fun nextFrame(): ByteArray? {
            repeat(150) {
                if (closed) return null
                val f = latestFrame()
                if (f != null && f !== last) { last = f; return f }
                Thread.sleep(100)
            }
            return null
        }

        private fun frameBytes(f: ByteArray): ByteArray {
            val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray()
            val tail = "\r\n".toByteArray()
            val out = ByteArray(head.size + f.size + tail.size)
            System.arraycopy(head, 0, out, 0, head.size)
            System.arraycopy(f, 0, out, head.size, f.size)
            System.arraycopy(tail, 0, out, head.size + f.size, tail.size)
            return out
        }

        override fun close() {
            if (!closed) { closed = true; watchers.decrementAndGet() }
        }
    }

    private fun page(prefix: String): String {
        val rot = rotationDegrees()
        val room = roomName().replace("<", "&lt;")
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$room · Spare Eye</title>
<style>
body{margin:0;background:#111;color:#ddd;font:15px system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;min-height:100vh}
h1{font:600 16px system-ui;margin:12px 0 6px;color:#eee}
.v{flex:1;display:flex;align-items:center;justify-content:center;width:100%}
img{max-width:96vmin;max-height:80vh;transform:rotate(${rot}deg);border-radius:8px;background:#000}
p{color:#888;font-size:13px;margin:8px 16px 14px;text-align:center}
</style></head><body>
<h1>$room</h1>
<div class="v"><img src="$prefix/stream" alt="live picture"></div>
<p>Live from your spare phone. Only people on this Wi-Fi with this exact link can see it.</p>
</body></html>"""
    }
}
