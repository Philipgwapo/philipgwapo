package com.philip.nokiamode

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nokia", Context.MODE_PRIVATE)

    var pin: String
        get() = sp.getString("pin", "0000") ?: "0000"
        set(v) = sp.edit().putString("pin", v).apply()

    var allowedApps: Set<String>
        get() = sp.getStringSet("allowed", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("allowed", v).apply()

    var backlightSec: Int
        get() = sp.getInt("backlight", 15)
        set(v) = sp.edit().putInt("backlight", v).apply()

    var keySounds: Boolean
        get() = sp.getBoolean("sounds", true)
        set(v) = sp.edit().putBoolean("sounds", v).apply()

    var grayscale: Boolean
        get() = sp.getBoolean("grayscale", false)
        set(v) = sp.edit().putBoolean("grayscale", v).apply()

    var snakeHigh: Int
        get() = sp.getInt("snake_high", 0)
        set(v) = sp.edit().putInt("snake_high", v).apply()

    var palette: String
        get() = sp.getString("palette", Palette.SAGE.name) ?: Palette.SAGE.name
        set(v) = sp.edit().putString("palette", v).apply()

    var body: String
        get() = sp.getString("body", Body.BLACK.name) ?: Body.BLACK.name
        set(v) = sp.edit().putString("body", v).apply()

    /** "mountain", "logo", "photo" or "clock". */
    var wallpaper: String
        get() = sp.getString("wallpaper", "logo") ?: "logo"
        set(v) = sp.edit().putString("wallpaper", v).apply()

    /** Home-screen shortcut for a key: an action id from [Shortcuts] or "app:<package>". */
    fun shortcut(key: Key): String = sp.getString("sc_" + key.name, Shortcuts.default(key)) ?: Shortcuts.default(key)
    fun setShortcut(key: Key, action: String) = sp.edit().putString("sc_" + key.name, action).apply()

    fun highScore(game: String) = sp.getInt("hs_$game", 0)
    fun setHighScore(game: String, v: Int) = sp.edit().putInt("hs_$game", v).apply()

    /** Saved Composer tunes as "name|notes" lines. */
    var tunes: List<Pair<String, String>>
        get() {
            val raw = sp.getString("tunes", null) ?: return Composer.presets
            return raw.split('\n').filter { '|' in it }.map { it.substringBefore('|') to it.substringAfter('|') }
        }
        set(v) = sp.edit().putString("tunes", v.joinToString("\n") { "${it.first.replace("|", "").replace("\n", " ")}|${it.second}" }).apply()

    /** Name of the tune used as ringtone, or "" for the phone's default ringtone. */
    var ringtone: String
        get() = sp.getString("ringtone", Composer.presets.first().first) ?: ""
        set(v) = sp.edit().putString("ringtone", v).apply()

    var newMessages: Int
        get() = sp.getInt("new_sms", 0)
        set(v) = sp.edit().putInt("new_sms", v).apply()

    var missedCalls: Int
        get() = sp.getInt("missed", 0)
        set(v) = sp.edit().putInt("missed", v).apply()

    /** Per-conversation "read up to" timestamps, since only the default SMS app may mark messages read. */
    fun threadSeen(thread: Long) = sp.getLong("seen_$thread", 0L)
    fun setThreadSeen(thread: Long, t: Long) = sp.edit().putLong("seen_$thread", t).apply()

    var backCamera: Boolean
        get() = sp.getBoolean("back_camera", true)
        set(v) = sp.edit().putBoolean("back_camera", v).apply()
}
