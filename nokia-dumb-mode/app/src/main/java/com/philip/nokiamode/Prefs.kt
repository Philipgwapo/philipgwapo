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

    var bigClock: Boolean
        get() = sp.getBoolean("big_clock", false)
        set(v) = sp.edit().putBoolean("big_clock", v).apply()

    var backCamera: Boolean
        get() = sp.getBoolean("back_camera", true)
        set(v) = sp.edit().putBoolean("back_camera", v).apply()
}
