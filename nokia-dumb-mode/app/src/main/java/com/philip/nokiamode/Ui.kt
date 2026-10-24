package com.philip.nokiamode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Classic Nokia 3310 palette: green backlit LCD with dark pixels. */
object Lcd {
    const val BG_LIT = 0xFFC7F0D8.toInt()
    const val BG_DIM = 0xFF7F9486.toInt()
    const val FG = 0xFF2E3B2B.toInt()
    const val BEZEL = 0xFF0B0F0B.toInt()
    const val BODY_TOP = 0xFF2A3B4C.toInt()
    const val BODY_BOTTOM = 0xFF111A24.toInt()
    const val KEY = 0xFF3A4048.toInt()
    const val KEY_PRESSED = 0xFF5C646E.toInt()
    const val KEY_GLOW = 0xFFB8FFC8.toInt()
    const val KEY_GLOW_SHADOW = 0xFF4CFF7A.toInt()
    const val KEY_DIM = 0xFF707670.toInt()
    const val CALL_GREEN = 0xFF3DDC84.toInt()
    const val END_RED = 0xFFFF5252.toInt()

    val font: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).toInt()

/** Draws a pixel-art icon from rows of '#' (on) and '.' (off), like the old menu icons. */
class PixelView(context: Context, private val art: List<String>) : View(context) {
    private val paint = Paint().apply { color = Lcd.FG }

    override fun onDraw(canvas: Canvas) {
        val rows = art.size
        val cols = art.maxOf { it.length }
        if (rows == 0 || cols == 0) return
        val cell = minOf(width.toFloat() / cols, height.toFloat() / rows)
        val gap = cell * 0.12f
        val ox = (width - cell * cols) / 2f
        val oy = (height - cell * rows) / 2f
        art.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch == '#') {
                    val x = ox + c * cell
                    val y = oy + r * cell
                    canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                }
            }
        }
    }
}

/** Vertical stack of bars that grow wider toward the top (signal / battery indicator). */
class BarsView(context: Context, private val level: Int, private val label: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Lcd.FG }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Lcd.FG
        typeface = Lcd.font
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val bars = 4
        val labelH = width * 0.9f
        val slot = (height - labelH) / bars
        for (i in 0 until bars) {
            // i = 0 is the bottom bar
            val filled = i < level
            val w = width * (0.35f + 0.65f * (i + 1) / bars)
            val top = height - labelH - (i + 1) * slot + slot * 0.25f
            val bottom = top + slot * 0.6f
            paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = width * 0.06f
            canvas.drawRect((width - w) / 2f, top, (width + w) / 2f, bottom, paint)
        }
        textPaint.textSize = labelH * 0.8f
        canvas.drawText(label, width / 2f, height - labelH * 0.15f, textPaint)
    }
}

enum class Key(val digit: Char?, val main: String, val sub: String) {
    SOFT_L(null, "—", ""),
    UP(null, "▲", ""),
    SOFT_R(null, "—", ""),
    CALL(null, "CALL", ""),
    DOWN(null, "▼", ""),
    END(null, "END", ""),
    D1('1', "1", "o_o"),
    D2('2', "2", "abc"),
    D3('3', "3", "def"),
    D4('4', "4", "ghi"),
    D5('5', "5", "jkl"),
    D6('6', "6", "mno"),
    D7('7', "7", "pqrs"),
    D8('8', "8", "tuv"),
    D9('9', "9", "wxyz"),
    STAR('*', "*", "+"),
    D0('0', "0", "␣"),
    HASH('#', "#", "⇧");

    companion object {
        fun ofDigit(c: Char): Key? = entries.firstOrNull { it.digit == c }
    }
}

/** On-screen Nokia keypad with a green backlight that dims along with the LCD. */
class Keypad(private val context: Context, private val onKey: (Key, Boolean) -> Unit) {
    private val keyViews = mutableMapOf<Key, TextView>()

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val rows = Key.entries.chunked(3)
        rows.forEach { rowKeys ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowKeys.forEach { k -> row.addView(makeKey(k), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun makeKey(k: Key): TextView {
        val tv = TextView(context)
        val text = if (k.sub.isEmpty()) SpannableString(k.main) else SpannableString("${k.main}\n${k.sub}").apply {
            setSpan(RelativeSizeSpan(0.5f), k.main.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.typeface = Lcd.font
        tv.textSize = if (k.digit != null) 22f else 16f
        tv.setLineSpacing(0f, 0.85f)
        tv.includeFontPadding = false
        val m = context.dpi(4f)
        tv.setPadding(m, m, m, m)
        tv.background = keyBackground()
        tv.isClickable = true
        tv.isHapticFeedbackEnabled = true
        tv.setOnClickListener { onKey(k, false) }
        tv.setOnLongClickListener { onKey(k, true); true }
        keyViews[k] = tv
        return tv
    }

    private fun keyBackground(): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = context.dp(22f)
            setColor(color)
            setStroke(context.dpi(1f), Color.BLACK)
        }
        val inset = context.dpi(4f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.InsetDrawable(shape(Lcd.KEY_PRESSED), inset))
            addState(intArrayOf(), android.graphics.drawable.InsetDrawable(shape(Lcd.KEY), inset))
        }
    }

    fun setLit(lit: Boolean) {
        keyViews.forEach { (k, tv) ->
            val base = when (k) {
                Key.CALL -> Lcd.CALL_GREEN
                Key.END -> Lcd.END_RED
                else -> if (lit) Lcd.KEY_GLOW else Lcd.KEY_DIM
            }
            tv.setTextColor(if (lit) base else blend(base, Lcd.KEY_DIM))
            if (lit) tv.setShadowLayer(context.dp(8f), 0f, 0f, if (k == Key.CALL || k == Key.END) base else Lcd.KEY_GLOW_SHADOW)
            else tv.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    private fun blend(a: Int, b: Int): Int = Color.rgb(
        (Color.red(a) + Color.red(b)) / 2,
        (Color.green(a) + Color.green(b)) / 2,
        (Color.blue(a) + Color.blue(b)) / 2,
    )
}

/** Pixel art for the one-item-per-page main menu. */
object Icons {
    val MESSAGES = listOf(
        "################",
        "##............##",
        "#.#..........#.#",
        "#..#........#..#",
        "#...#......#...#",
        "#....#....#....#",
        "#.....#..#.....#",
        "#......##......#",
        "#..............#",
        "#..............#",
        "################",
    )
    val NAMES = listOf(
        ".############...",
        ".#..........#...",
        "##..######..#...",
        ".#..........#...",
        "##..####....#...",
        ".#..........#...",
        "##..#####...#...",
        ".#..........#...",
        "##..........#...",
        ".############...",
    )
    val CALLS = listOf(
        ".##############.",
        "################",
        "###..........###",
        "##....####....##",
        "......#..#......",
        ".....######.....",
        "....##.##.##....",
        "....########....",
        "....##.##.##....",
        "....########....",
    )
    val SNAKE = listOf(
        "................",
        ".####...........",
        ".#..#...........",
        ".##.#....####...",
        "....#....#..#...",
        "....######..#...",
        "............#.#.",
        "............###.",
        "..............#.",
    )
    val CLOCK = listOf(
        ".....######.....",
        "...##......##...",
        "..#....#.....#..",
        ".#.....#......#.",
        ".#.....#......#.",
        "#......#.......#",
        "#......####....#",
        "#..............#",
        ".#............#.",
        ".#............#.",
        "..#..........#..",
        "...##......##...",
        ".....######.....",
    )
    val APPS = listOf(
        "####..####..####",
        "####..####..####",
        "####..####..####",
        "................",
        "####..####..####",
        "####..####..####",
        "####..####..####",
        "................",
        "####..####..####",
        "####..####..####",
        "####..####..####",
    )
    val SETTINGS = listOf(
        "......####......",
        "...#..####..#...",
        "..###.####.###..",
        "...##########...",
        "....###..###....",
        "#####......#####",
        "#####......#####",
        "....###..###....",
        "...##########...",
        "..###.####.###..",
        "...#..####..#...",
        "......####......",
    )
}
