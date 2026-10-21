package io.github.markalexanderhub.spareeye

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The three Telegram Bot API calls this app needs. Talks only to api.telegram.org. */
class TelegramClient(token: String) {
    private val base = "https://api.telegram.org/bot$token"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()

    data class Chat(val id: Long, val name: String)

    /** The bot's @username, or throws if the token is wrong. */
    fun botName(): String {
        val r = call(Request.Builder().url("$base/getMe").build())
        return "@" + r.getJSONObject("result").optString("username")
    }

    /** The most recent chat that wrote to the bot. The user sends the bot a message first. */
    fun findChat(): Chat? {
        val r = call(Request.Builder().url("$base/getUpdates?limit=100").build())
        val updates = r.getJSONArray("result")
        for (i in updates.length() - 1 downTo 0) {
            val u = updates.getJSONObject(i)
            val m = u.optJSONObject("message") ?: u.optJSONObject("edited_message")
                ?: u.optJSONObject("channel_post") ?: continue
            val chat = m.optJSONObject("chat") ?: continue
            val id = chat.optLong("id", 0L)
            if (id == 0L) continue
            val name = chat.optString("title").ifEmpty {
                listOf(chat.optString("first_name"), chat.optString("last_name"))
                    .filter { it.isNotEmpty() }.joinToString(" ")
            }.ifEmpty { "@" + chat.optString("username") }
            return Chat(id, name)
        }
        return null
    }

    fun sendMessage(chatId: Long, text: String) {
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("text", text)
            .build()
        call(Request.Builder().url("$base/sendMessage").post(body).build())
    }

    fun sendPhoto(chatId: Long, jpeg: ByteArray, caption: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption)
            .addFormDataPart("photo", "alert.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        call(Request.Builder().url("$base/sendPhoto").post(body).build())
    }

    private fun call(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { throw IOException("Telegram answered with something odd (HTTP ${resp.code})") }
            if (!json.optBoolean("ok", false)) {
                throw IOException(json.optString("description", "Telegram said no (HTTP ${resp.code})"))
            }
            return json
        }
    }
}
