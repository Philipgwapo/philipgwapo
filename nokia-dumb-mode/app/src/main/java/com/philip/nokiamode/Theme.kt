package com.philip.nokiamode

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/** LCD colour schemes: lit background, pixel colour, and the same pair with the backlight off. */
enum class Palette(val label: String, val bg: Int, val fg: Int, val dimBg: Int, val dimFg: Int) {
    SAGE("Sage", 0xFFC9D0B8.toInt(), 0xFF111511.toInt(), 0xFF7D8474.toInt(), 0xFF0E110E.toInt()),
    GREEN("Classic green", 0xFFC7F0D8.toInt(), 0xFF2E3B2B.toInt(), 0xFF7F9486.toInt(), 0xFF26311F.toInt()),
    PAPER("Black on white", 0xFFF2F2EE.toInt(), 0xFF000000.toInt(), 0xFF8E8E8A.toInt(), 0xFF000000.toInt()),
    NIGHT("White on black", 0xFF050505.toInt(), 0xFFF2F2F2.toInt(), 0xFF000000.toInt(), 0xFF6E6E6E.toInt()),
    AMBER("Amber", 0xFFF2B33D.toInt(), 0xFF2A1A00.toInt(), 0xFF8F6A27.toInt(), 0xFF1E1300.toInt()),
    BLUE("Blue", 0xFF9CC8F2.toInt(), 0xFF0B1E3A.toInt(), 0xFF5C7690.toInt(), 0xFF08172C.toInt());

    fun next(): Palette = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: SAGE
    }
}

/** Phone body styles, matching the black and white handsets in the reference design. */
enum class Body(
    val label: String,
    val top: Int, val bottom: Int,
    val key: Int, val keyEdge: Int, val keyPressed: Int,
    val keyText: Int, val keyTextDim: Int,
    val logo: Int,
) {
    BLACK("Black", 0xFF2A2A2A.toInt(), 0xFF0E0E0E.toInt(), 0xFF262626.toInt(), 0xFF050505.toInt(), 0xFF454545.toInt(),
        0xFFF5F5F5.toInt(), 0xFF8A8A8A.toInt(), 0xFFE8E8E8.toInt()),
    WHITE("White", 0xFFF7F7F5.toInt(), 0xFFDCDCD8.toInt(), 0xFFFBFBFA.toInt(), 0xFFB5B5B0.toInt(), 0xFFDADAD6.toInt(),
        0xFF121212.toInt(), 0xFF8A8A8A.toInt(), 0xFFE8E8E8.toInt());

    fun next(): Body = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: BLACK
    }
}

object Fonts {
    /** Bundled Pixelify Sans (SIL OFL), falling back to bold monospace if it fails to load. */
    lateinit var pixel: Typeface
        private set
    lateinit var pixelBold: Typeface
        private set
    val keys: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val logo: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    fun init(context: Context) {
        if (::pixel.isInitialized) return
        pixel = load(context, 500)
        pixelBold = load(context, 700)
    }

    private fun load(context: Context, weight: Int): Typeface = try {
        Typeface.Builder(context.assets, "fonts/PixelifySans.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build() ?: fallback()
    } catch (e: Exception) {
        fallback()
    }

    private fun fallback() = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

fun blend(a: Int, b: Int, t: Float = 0.5f): Int = Color.rgb(
    (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
    (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
    (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
)

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).toInt()
