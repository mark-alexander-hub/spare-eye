package io.github.markalexanderhub.spareeye

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** Everything the user sets up, kept on the phone only. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("spare_eye", Context.MODE_PRIVATE)

    var botToken: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var chatId: Long
        get() = sp.getLong("chat_id", 0L)
        set(v) = sp.edit().putLong("chat_id", v).apply()

    var chatName: String
        get() = sp.getString("chat_name", "") ?: ""
        set(v) = sp.edit().putString("chat_name", v).apply()

    var room: String
        get() = (sp.getString("room", "") ?: "").ifBlank { "Home" }
        set(v) = sp.edit().putString("room", v.trim()).apply()

    /** 1 = only big movements, 10 = the smallest flicker. */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 6)
        set(v) = sp.edit().putInt("sensitivity", v.coerceIn(1, 10)).apply()

    /** Seconds to wait after an alert before sending the next one. */
    var cooldownSec: Int
        get() = sp.getInt("cooldown", 45)
        set(v) = sp.edit().putInt("cooldown", v.coerceIn(10, 600)).apply()

    var useFrontCamera: Boolean
        get() = sp.getBoolean("front", false)
        set(v) = sp.edit().putBoolean("front", v).apply()

    var liveView: Boolean
        get() = sp.getBoolean("live", true)
        set(v) = sp.edit().putBoolean("live", v).apply()

    var notifyArm: Boolean
        get() = sp.getBoolean("notify_arm", true)
        set(v) = sp.edit().putBoolean("notify_arm", v).apply()

    /** Random path segment for the live view so a neighbour on the Wi-Fi can't just guess the URL. */
    val liveKey: String
        get() {
            val existing = sp.getString("live_key", null)
            if (!existing.isNullOrEmpty()) return existing
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            val key = (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            sp.edit().putString("live_key", key).apply()
            return key
        }

    val telegramReady: Boolean get() = botToken.isNotEmpty() && chatId != 0L
}
