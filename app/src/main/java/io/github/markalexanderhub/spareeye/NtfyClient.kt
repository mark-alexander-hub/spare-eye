package io.github.markalexanderhub.spareeye

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Push alerts through ntfy (https://ntfy.sh, or a self-hosted server). No account: whoever
 * subscribes to the same topic in the ntfy app gets the notification, so the topic is long and random.
 */
class NtfyClient(server: String, topic: String) {
    private val url = server.trim().trimEnd('/').ifEmpty { Prefs.DEFAULT_NTFY_SERVER } + "/" + topic.trim()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()

    fun sendText(title: String, message: String) {
        val req = Request.Builder().url(url)
            .header("Title", header(title))
            .header("Tags", "eye")
            .post(message.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        call(req)
    }

    /** The photo goes up as an attachment; the notification shows it inline. */
    fun sendPhoto(jpeg: ByteArray, title: String, message: String) {
        val req = Request.Builder().url(url)
            .header("Title", header(title))
            .header("Message", header(message))
            .header("Filename", "spare-eye.jpg")
            .header("Tags", "rotating_light")
            .header("Priority", "high")
            .put(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        call(req)
    }

    private fun call(req: Request) {
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string()?.take(200) ?: ""
                throw IOException("ntfy answered HTTP ${resp.code} $body".trim())
            }
        }
    }

    /** HTTP headers must be ASCII; ntfy understands RFC 2047 for anything else (room names, narrow spaces in times). */
    private fun header(s: String): String {
        val clean = s.replace('\n', ' ')
        return if (clean.all { it.code in 0x20..0x7E }) clean
        else "=?UTF-8?B?" + Base64.encodeToString(clean.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="
    }
}
