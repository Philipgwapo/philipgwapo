package com.philip.nokiamode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import java.io.File
import kotlin.random.Random

/** A 1-bit image: the building block for every icon, the wallpaper and the camera viewfinder. */
class Mask(val w: Int, val h: Int, val bits: BooleanArray = BooleanArray(w * h)) {
    operator fun get(x: Int, y: Int) = x in 0 until w && y in 0 until h && bits[y * w + x]
    operator fun set(x: Int, y: Int, v: Boolean) {
        if (x in 0 until w && y in 0 until h) bits[y * w + x] = v
    }

    companion object {
        fun art(vararg rows: String): Mask {
            val w = rows.maxOf { it.length }
            val m = Mask(w, rows.size)
            rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> m[x, y] = c == '#' } }
            return m
        }
    }
}

/** Draws a [Mask] scaled up with crisp square pixels, centred, in a single colour. */
class MaskView(context: Context, var mask: Mask?, var color: Int) : View(context) {
    private val paint = Paint()
    var gap = 0f

    override fun onDraw(canvas: Canvas) {
        val m = mask ?: return
        val aw = (width - paddingLeft - paddingRight).toFloat()
        val ah = (height - paddingTop - paddingBottom).toFloat()
        val cell = minOf(aw / m.w, ah / m.h)
        if (cell <= 0f) return
        val ox = paddingLeft + (aw - cell * m.w) / 2f
        val oy = paddingTop + (ah - cell * m.h) / 2f
        val g = cell * gap
        paint.color = color
        for (y in 0 until m.h) for (x in 0 until m.w) {
            if (m.bits[y * m.w + x]) {
                val l = ox + x * cell
                val t = oy + y * cell
                canvas.drawRect(l, t, l + cell - g + 0.5f, t + cell - g + 0.5f, paint)
            }
        }
    }
}

/** Signal strength bars, rising left to right. */
class SignalView(context: Context, private val level: Int, private val color: Int) : View(context) {
    private val paint = Paint().apply { this.color = color }
    override fun onDraw(canvas: Canvas) {
        val bars = 4
        val bw = width / (bars * 1.6f)
        // antenna stem
        canvas.drawRect(0f, 0f, bw * 0.6f, height.toFloat(), paint)
        canvas.drawRect(0f, 0f, bw * 1.6f, bw * 0.6f, paint)
        for (i in 0 until bars) {
            val x = bw * 1.6f + i * bw * 1.5f
            val top = height * (1f - (i + 1f) / bars)
            paint.alpha = if (i < level) 255 else 70
            canvas.drawRect(x, top, x + bw, height.toFloat(), paint)
        }
        paint.alpha = 255
    }
}

class BatteryView(context: Context, private val level: Int, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun onDraw(canvas: Canvas) {
        val s = height * 0.14f
        val nub = width * 0.1f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s
        canvas.drawRect(s / 2, s / 2, width - nub - s / 2, height - s / 2, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(width - nub, height * 0.3f, width.toFloat(), height * 0.7f, paint)
        val inner = width - nub - s * 3
        val segs = 4
        val sw = inner / segs
        for (i in 0 until minOf(level, segs)) {
            val l = s * 2 + i * sw
            canvas.drawRect(l, s * 2, l + sw - s * 0.6f, height - s * 2, paint)
        }
    }
}

/** Draws a small bitmap scaled up without smoothing (camera viewfinder, gallery photos). */
class PixelBitmapView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var overlay: ((Canvas, RectF) -> Unit)? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b != null) {
            val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale
            val h = b.height * scale
            dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            canvas.drawBitmap(b, null, dst, paint)
        } else {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
        }
        overlay?.invoke(canvas, dst)
    }
}

object Dither {
    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /** Ordered 4x4 Bayer dither of a luminance buffer into a two-colour bitmap. */
    fun toBitmap(lum: IntArray, w: Int, h: Int, fg: Int, bg: Int, out: Bitmap? = null): Bitmap {
        var lo = 255
        var hi = 0
        for (v in lum) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = maxOf(hi - lo, 40)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (lum[y * w + x] - lo) * 255 / range
            val t = (bayer[(y and 3) * 4 + (x and 3)] * 16) + 8
            px[y * w + x] = if (v > t) bg else fg
        }
        val b = if (out != null && out.width == w && out.height == h && out.isMutable) out
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** Scales a photo to [targetW] wide and dithers it. */
    fun photo(src: Bitmap, targetW: Int, fg: Int, bg: Int): Bitmap {
        val w = targetW
        val h = maxOf(1, src.height * targetW / src.width)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { i ->
            val c = px[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return toBitmap(lum, w, h, fg, bg)
    }
}

/** Converts installed app icons to 1-bit masks so they match the LCD. */
object AppIcons {
    private const val SIZE = 22
    private val cache = HashMap<String, Mask>()

    fun mask(context: Context, pkg: String): Mask? = cache[pkg] ?: try {
        val d = context.packageManager.getApplicationIcon(pkg)
        fromDrawable(d).also { cache[pkg] = it }
    } catch (e: Exception) {
        null
    }

    private fun fromDrawable(d: Drawable): Mask {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Themed (monochrome) icons give a clean silhouette on Android 13+.
        val mono = if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null
        if (mono != null) {
            val pad = (SIZE * 0.25f).toInt()
            mono.setBounds(-pad, -pad, SIZE + pad, SIZE + pad)
            mono.draw(c)
            return threshold(bmp, useAlphaOnly = true)
        }
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(c)
        return threshold(bmp, useAlphaOnly = false)
    }

    private fun threshold(bmp: Bitmap, useAlphaOnly: Boolean): Mask {
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val m = Mask(SIZE, SIZE)
        if (useAlphaOnly) {
            px.forEachIndexed { i, c -> m.bits[i] = Color.alpha(c) > 110 }
            return m
        }
        val lums = px.filter { Color.alpha(it) > 128 }.map { lum(it) }
        val mean = if (lums.isEmpty()) 128 else lums.sum() / lums.size
        px.forEachIndexed { i, c -> m.bits[i] = Color.alpha(c) > 128 && lum(c) < mean }
        // Mostly-dark icons would become a solid blob; invert those.
        if (m.bits.count { it } > m.bits.size * 0.6) {
            px.forEachIndexed { i, c -> m.bits[i] = Color.alpha(c) > 128 && lum(c) >= mean }
        }
        return m
    }

    private fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}

/** Idle-screen wallpapers: the Kippoks logo, a pixel mountain scene, or the user's own photo. */
object Wallpaper {
    val mask: Mask by lazy { build() }

    private var logoMask: Mask? = null
    private const val PHOTO_FILE = "wallpaper.png"
    private var photo: Bitmap? = null
    private var photoLoaded = false
    private var dithered: Bitmap? = null
    private var ditherKey = 0L

    /** The Kippoks mountain from the user's logo over solid pixel lettering (the outline font is unreadable at LCD size). */
    fun logo(context: Context): Mask = logoMask ?: buildLogo(context).also { logoMask = it }

    private fun buildLogo(context: Context): Mask {
        val w = 96
        val mountain = try {
            context.assets.open("kippoks_mountain.png").use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        val mw = 74
        val mh = if (mountain == null) 0 else maxOf(1, mountain.height * mw / mountain.width)
        val top = Mask(w, mh)
        if (mountain != null) {
            val small = Bitmap.createScaledBitmap(mountain, mw, mh, true)
            val ox = (w - mw) / 2
            for (y in 0 until mh) for (x in 0 until mw) {
                val c = small.getPixel(x, y)
                top[ox + x, y] = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 > 110
            }
        }
        val th = 18
        val text = Bitmap.createBitmap(w, th, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            typeface = Fonts.pixelBold
            isAntiAlias = false
            color = Color.BLACK
            textSize = 16f
            letterSpacing = 0.05f
        }
        while (paint.measureText("KIPPOKS") > w - 2 && paint.textSize > 8f) paint.textSize -= 0.5f
        while (paint.measureText("KIPPOKS") < w - 10) paint.textSize += 0.5f
        val tw = paint.measureText("KIPPOKS")
        Canvas(text).drawText("KIPPOKS", (w - tw) / 2f, th - 3f, paint)
        val gap = 3
        val out = Mask(w, mh + gap + th)
        for (y in 0 until mh) for (x in 0 until w) out[x, y] = top[x, y]
        for (y in 0 until th) for (x in 0 until w) out[x, mh + gap + y] = Color.alpha(text.getPixel(x, y)) > 128
        return out
    }

    fun hasPhoto(context: Context) = File(context.filesDir, PHOTO_FILE).exists()

    /** Stores a small greyscale copy; it is dithered in the current screen colours when shown. */
    fun setPhoto(context: Context, src: Bitmap): Boolean = try {
        val w = 200
        val small = Bitmap.createScaledBitmap(src, w, maxOf(1, src.height * w / src.width), true)
        File(context.filesDir, PHOTO_FILE).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        photo = small
        photoLoaded = true
        dithered = null
        true
    } catch (e: Exception) {
        false
    }

    fun photoBitmap(context: Context, fg: Int, bg: Int): Bitmap? {
        if (!photoLoaded) {
            photoLoaded = true
            photo = try {
                android.graphics.BitmapFactory.decodeFile(File(context.filesDir, PHOTO_FILE).absolutePath)
            } catch (e: Exception) {
                null
            }
        }
        val p = photo ?: return null
        val key = (fg.toLong() shl 32) xor (bg.toLong() and 0xFFFFFFFFL)
        if (dithered == null || key != ditherKey) {
            dithered = Dither.photo(p, 120, fg, bg)
            ditherKey = key
        }
        return dithered
    }

    private fun build(): Mask {
        val w = 96
        val h = 44
        val m = Mask(w, h)
        val rnd = Random(7)
        // sun
        for (y in -4..4) for (x in -4..4) if (x * x + y * y <= 17) m[74 + x, 7 + y] = true
        // birds
        for ((bx, by) in listOf(80 to 15, 87 to 19)) {
            m[bx - 2, by - 1] = true; m[bx - 1, by] = true; m[bx, by + 1] = true
            m[bx + 1, by] = true; m[bx + 2, by - 1] = true
        }
        // peaks: x, top y, slope
        val peaks = listOf(Triple(40, 9, 1.05f), Triple(64, 19, 0.9f), Triple(17, 25, 0.85f), Triple(84, 28, 0.8f))
        val ground = 38
        for (x in 0 until w) {
            var ridge = ground.toFloat()
            var owner = peaks[0]
            for (p in peaks) {
                val r = p.second + kotlin.math.abs(x - p.first) * p.third
                if (r < ridge) { ridge = r; owner = p }
            }
            val top = ridge.toInt()
            for (y in top until h) {
                val litSide = x > owner.first
                val depth = y - top
                val on = when {
                    y >= ground -> rnd.nextInt(10) < 6
                    // snow streaks near the ridge on the sunny side
                    litSide && depth in 1..6 && (x + y) % 3 == 0 -> false
                    litSide && depth > 2 -> (x + y) % 2 == 0 || rnd.nextInt(10) < 3
                    else -> true
                }
                m[x, y] = on
            }
        }
        return m
    }
}

/** 16px pixel icons modelled on the reference screens. */
object Icons {
    val PHONE = Mask.art(
        "................",
        ".###............",
        "#####...........",
        "#####...........",
        "####............",
        ".###............",
        ".###............",
        "..###...........",
        "..####..........",
        "...####.........",
        "....#####..###..",
        ".....##########.",
        ".......########.",
        ".........#####..",
        "................",
    )
    val MESSAGES = Mask.art(
        "................",
        ".##############.",
        "################",
        "###..........###",
        "################",
        "###..........###",
        "################",
        "###......#######",
        "################",
        ".##############.",
        "....###.........",
        "....##..........",
        "....#...........",
    )
    val CONTACTS = Mask.art(
        "................",
        "......####......",
        ".....######.....",
        "....########....",
        "....########....",
        "....########....",
        ".....######.....",
        "......####......",
        "................",
        "...##########...",
        "..############..",
        ".##############.",
        ".##############.",
        ".##############.",
    )
    val CAMERA = Mask.art(
        "................",
        ".....####.......",
        "....######......",
        ".##############.",
        "################",
        "######....######",
        "#####......#####",
        "#####......#####",
        "#####......#####",
        "######....######",
        "################",
        ".##############.",
    )
    val GALLERY = Mask.art(
        "################",
        "#..............#",
        "#..........##..#",
        "#.........####.#",
        "#..........##..#",
        "#....#.........#",
        "#...###........#",
        "#..#####...#...#",
        "#.#######.###..#",
        "#########.######",
        "################",
    )
    val CLOCK = Mask.art(
        ".....######.....",
        "...##########...",
        "..#####..#####..",
        ".######..######.",
        ".######..######.",
        "#######..#######",
        "#######.....####",
        "################",
        ".##############.",
        ".##############.",
        "..############..",
        "...##########...",
        ".....######.....",
    )
    val GAMES = Mask.art(
        "................",
        "..############..",
        ".##############.",
        "###.#######..###",
        "##...#####.##.##",
        "###.#######..###",
        "################",
        "#####......#####",
        "####........####",
        ".##..........##.",
    )
    val APPS = Mask.art(
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
    val SETTINGS = Mask.art(
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
    val WIFI = Mask.art(
        "....########....",
        "..############..",
        ".####......####.",
        "###..######..###",
        "#..##########..#",
        "..####....####..",
        "....##.##.##....",
        "......####......",
        ".......##.......",
    )
    val BLUETOOTH = Mask.art(
        ".....##.......",
        ".....###......",
        ".....##.#.....",
        "..#..##..#....",
        "...#.##.#.....",
        "....####......",
        ".....##.......",
        "....####......",
        "...#.##.#.....",
        "..#..##..#....",
        ".....##.#.....",
        ".....###......",
        ".....##.......",
    )
    val SOUND = Mask.art(
        ".......#........",
        "......##....#...",
        ".....###..#..#..",
        "#########..#..#.",
        "#########..#..#.",
        "#########..#..#.",
        "#########..#..#.",
        ".....###..#..#..",
        "......##....#...",
        ".......#........",
    )
    val DISPLAY = Mask.art(
        "################",
        "#..............#",
        "#..............#",
        "#..............#",
        "#..............#",
        "#..............#",
        "################",
        "......####......",
        "....########....",
    )
    val ABOUT = Mask.art(
        ".....######.....",
        "...##########...",
        "..#####..#####..",
        ".######..######.",
        ".##############.",
        "######...#######",
        "#######..#######",
        "#######..#######",
        ".######..######.",
        ".######..######.",
        "..####....####..",
        "...##########...",
        ".....######.....",
    )
    val KEY = Mask.art(
        "..####..........",
        ".######.........",
        "###..###########",
        "###..###########",
        ".######...##.##.",
        "..####....##.##.",
    )
    val EXIT = Mask.art(
        "########........",
        "#......#........",
        "#......#...#....",
        "#......#...##...",
        "#.....#########.",
        "#.....##########",
        "#.....#########.",
        "#......#...##...",
        "#......#...#....",
        "#......#........",
        "########........",
    )
    val LOCK = Mask.art(
        "....######....",
        "...##....##...",
        "...#......#...",
        "...#......#...",
        ".############.",
        ".#####..#####.",
        ".#####..#####.",
        ".######.#####.",
        ".############.",
        ".############.",
    )
    val SEARCH = Mask.art(
        "..####......",
        ".#....#.....",
        "#......#....",
        "#......#....",
        "#......#....",
        ".#....#.....",
        "..####.#....",
        ".......##...",
        "........##..",
        ".........##.",
    )
    val CHEVRON = Mask.art(
        "#....",
        "##...",
        ".##..",
        "..##.",
        ".##..",
        "##...",
        "#....",
    )
    val SNAKE = Mask.art(
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
    val NOTE = Mask.art(
        ".......#########",
        ".......#########",
        ".......#.......#",
        ".......#.......#",
        ".......#.......#",
        ".......#.......#",
        ".......#.......#",
        "...#####...#####",
        "..######..######",
        "..######..######",
        "...####....####.",
    )
    val SPACE = Mask.art(
        "##..............",
        "####............",
        ".######.........",
        "..##########....",
        "###############.",
        "################",
        "###############.",
        "..##########....",
        ".######.........",
        "####............",
        "##..............",
    )
    val BLOCKS = Mask.art(
        "....####........",
        "....####........",
        "....########....",
        "....########....",
        "................",
        "####....########",
        "####....########",
        "########....####",
        "########....####",
        "################",
        "################",
    )
    val BRICKS = Mask.art(
        "#####.#####.####",
        "#####.#####.####",
        "................",
        "##.#####.#####.#",
        "##.#####.#####.#",
        "................",
        "........##......",
        "........##......",
        "................",
        "................",
        ".....######.....",
    )
    val PAIRS = Mask.art(
        "######..######..",
        "#....#..#....#..",
        "#.##.#..#.##.#..",
        "#.##.#..#.##.#..",
        "#....#..#....#..",
        "######..######..",
        "................",
        "######..######..",
        "######..######..",
        "######..######..",
        "######..######..",
        "######..######..",
    )
    val SMS_NEW = Mask.art(
        "##########..##..",
        "#........#.####.",
        "#.######.#####..",
        "#.......#####...",
        "#.#####.###.#...",
        "#.......##..#...",
        "#..........##...",
        "###########.....",
    )
}
