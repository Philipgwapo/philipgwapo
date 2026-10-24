package com.philip.nokiamode

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

enum class Key(val digit: Char?, val letters: String = "") {
    SOFT_L(null), SOFT_R(null), CALL(null), END(null),
    UP(null), DOWN(null), LEFT(null), RIGHT(null), OK(null),
    D1('1', "oo"), D2('2', "ABC"), D3('3', "DEF"),
    D4('4', "GHI"), D5('5', "JKL"), D6('6', "MNO"),
    D7('7', "PQRS"), D8('8', "TUV"), D9('9', "WXYZ"),
    STAR('*', "+"), D0('0', "_"), HASH('#', "↑");

    companion object {
        fun ofDigit(c: Char): Key? = entries.firstOrNull { it.digit == c }
    }
}

/** Base for keys: press highlight, long-press detection, and the body colour scheme. */
@SuppressLint("ViewConstructor")
abstract class PressView(context: Context, protected val keypad: Keypad) : View(context) {
    private var longFired = false
    private var downKey: Key? = null
    private val longPress = Runnable {
        downKey?.let { longFired = true; keypad.onKey(it, true) }
    }

    abstract fun keyAt(x: Float, y: Float): Key?

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downKey = keyAt(e.x, e.y) ?: return false
                longFired = false
                isPressed = true
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                isPressed = false
                val k = downKey
                if (k != null && !longFired) keypad.onKey(k, false)
                downKey = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                isPressed = false
                downKey = null
            }
        }
        invalidate()
        return true
    }

    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    protected val ink = Paint(Paint.ANTI_ALIAS_FLAG)

    protected fun drawKeyShape(canvas: Canvas, r: RectF, radius: Float) {
        val b = keypad.body
        val base = if (isPressed) b.keyPressed else b.key
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, blend(base, 0xFFFFFFFF.toInt(), 0.08f), blend(base, 0xFF000000.toInt(), 0.12f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = b.keyEdge
        stroke.strokeWidth = context.dp(1.2f)
        canvas.drawRoundRect(r, radius, radius, stroke)
    }

    protected val textColor: Int get() = if (keypad.lit) keypad.body.keyText else keypad.body.keyTextDim
}

@SuppressLint("ViewConstructor")
class KeyView(context: Context, keypad: Keypad, private val key: Key) : PressView(context, keypad) {
    private val rect = RectF()

    override fun keyAt(x: Float, y: Float) = key

    override fun onDraw(canvas: Canvas) {
        val m = context.dp(4f)
        rect.set(m, m, width - m, height - m)
        drawKeyShape(canvas, rect, rect.height() * 0.42f)
        val cx = rect.centerX()
        val cy = rect.centerY()
        when (key) {
            Key.SOFT_L, Key.SOFT_R -> {
                ink.color = textColor
                val w = rect.width() * 0.22f
                val t = context.dp(3f)
                canvas.drawRoundRect(cx - w, cy - t / 2, cx + w, cy + t / 2, t, t, ink)
            }
            Key.CALL, Key.END -> {
                val green = 0xFF2FB34A.toInt()
                val red = 0xFFE53935.toInt()
                ink.color = if (key == Key.CALL) green else red
                if (!keypad.lit) ink.color = blend(ink.color, keypad.body.keyTextDim, 0.4f)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = context.dp(4.5f)
                ink.strokeCap = Paint.Cap.ROUND
                val hw = rect.height() * 0.38f
                val arc = RectF(cx - hw, cy - hw * 0.35f, cx + hw, cy + hw * 1.25f)
                canvas.drawArc(arc, 205f, 130f, false, ink)
                ink.style = Paint.Style.FILL
            }
            else -> drawDigit(canvas, cx, cy)
        }
    }

    private fun drawDigit(canvas: Canvas, cx: Float, cy: Float) {
        val main = key.digit.toString()
        ink.color = textColor
        ink.typeface = Fonts.keys
        ink.textSize = rect.height() * 0.55f
        val mainW = ink.measureText(main)
        val sub = key.letters
        val subPaint = Paint(ink).apply { textSize = rect.height() * 0.3f }
        val subW = subPaint.measureText(sub)
        val gap = context.dp(5f)
        val total = mainW + gap + subW
        val x0 = cx - total / 2
        val base = cy - (ink.descent() + ink.ascent()) / 2
        canvas.drawText(main, x0, base, ink)
        canvas.drawText(sub, x0 + mainW + gap, base, subPaint)
    }
}

/** The square 4-way navigation key with a select button in the middle and a chrome ring. */
@SuppressLint("ViewConstructor")
class NavPadView(context: Context, keypad: Keypad) : PressView(context, keypad) {
    private val outer = RectF()
    private val inner = RectF()
    private var pressedKey: Key? = null

    override fun keyAt(x: Float, y: Float): Key {
        val dx = x - width / 2f
        val dy = y - height / 2f
        val r = minOf(width, height) / 2f
        val k = when {
            kotlin.math.abs(dx) < r * 0.38f && kotlin.math.abs(dy) < r * 0.38f -> Key.OK
            kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx > 0) Key.RIGHT else Key.LEFT
            else -> if (dy > 0) Key.DOWN else Key.UP
        }
        pressedKey = k
        return k
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) - context.dp(6f)
        outer.set((width - s) / 2f, (height - s) / 2f, (width + s) / 2f, (height + s) / 2f)
        // chrome ring
        fill.shader = LinearGradient(0f, outer.top, 0f, outer.bottom,
            intArrayOf(0xFFF4F4F4.toInt(), 0xFF9A9A9A.toInt(), 0xFFE0E0E0.toInt(), 0xFF707070.toInt()), null, Shader.TileMode.CLAMP)
        val rad = s * 0.26f
        canvas.drawRoundRect(outer, rad, rad, fill)
        val ring = s * 0.07f
        inner.set(outer.left + ring, outer.top + ring, outer.right - ring, outer.bottom - ring)
        drawKeyShape(canvas, inner, rad * 0.8f)
        // a faint highlight on the side being pressed
        if (isPressed && pressedKey != null && pressedKey != Key.OK) {
            ink.color = 0x22000000
            val c = inner
            val h = c.height() * 0.3f
            when (pressedKey) {
                Key.UP -> canvas.drawRect(c.left, c.top, c.right, c.top + h, ink)
                Key.DOWN -> canvas.drawRect(c.left, c.bottom - h, c.right, c.bottom, ink)
                Key.LEFT -> canvas.drawRect(c.left, c.top, c.left + h, c.bottom, ink)
                Key.RIGHT -> canvas.drawRect(c.right - h, c.top, c.right, c.bottom, ink)
                else -> {}
            }
        }
    }
}

/** On-screen keypad laid out like the reference handset. */
class Keypad(private val context: Context, var body: Body, private val handler: (Key, Boolean) -> Unit) {
    var lit = true
        private set
    private val views = ArrayList<View>()

    fun onKey(k: Key, long: Boolean) = handler(k, long)

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun sideColumn(top: Key, bottom: Key) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(key(top), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(key(bottom), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        nav.addView(sideColumn(Key.SOFT_L, Key.CALL), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val pad = NavPadView(context, this@Keypad).also { views.add(it) }
        nav.addView(pad, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f))
        nav.addView(sideColumn(Key.SOFT_R, Key.END), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.2f))

        listOf(
            listOf(Key.D1, Key.D2, Key.D3), listOf(Key.D4, Key.D5, Key.D6),
            listOf(Key.D7, Key.D8, Key.D9), listOf(Key.STAR, Key.D0, Key.HASH),
        ).forEach { row ->
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { k -> r.addView(key(k), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
            addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun key(k: Key): View = KeyView(context, this, k).also { views.add(it) }

    fun setLit(on: Boolean) {
        lit = on
        views.forEach { it.invalidate() }
    }
}
