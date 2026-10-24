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
}
