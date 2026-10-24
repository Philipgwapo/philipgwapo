package com.philip.nokiamode

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class GamesMenu(host: MainActivity) : ListScreen(host, "Games") {
    override fun loadItems(): List<Item> {
        val p = host.prefs
        return listOf(
            Item("Snake II", Icons.SNAKE) { host.push(SnakeScreen(host)) },
            Item("Space Impact  ${p.highScore("space")}", Icons.SPACE) { host.push(SpaceImpact(host)) },
            Item("Blocks  ${p.highScore("blocks")}", Icons.BLOCKS) { host.push(BlocksGame(host)) },
            Item("Brick breaker  ${p.highScore("bricks")}", Icons.BRICKS) { host.push(BrickBreaker(host)) },
            Item("Pairs", Icons.PAIRS) { host.push(PairsGame(host)) },
        )
    }
}

/**
 * Shared game loop: a fixed-rate tick, pause when leaving the screen, a high score per game, and
 * held keys auto-repeat (see [Screen.repeatKeys]) so movement is smooth.
 */
abstract class GameScreen(host: MainActivity, private val id: String, private val name: String) : Screen(host) {
    protected var running = false
    protected var over = false
    protected var score = 0
    private var shownScore = -1
    abstract val stepMs: Long

    protected val paint = Paint().apply { isAntiAlias = false }
    private val canvasView = object : View(host) {
        override fun onDraw(canvas: Canvas) {
            paint.color = host.fg
            draw(canvas, width.toFloat(), height.toFloat())
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            if (over) {
                finish()
                return
            }
            if (score != shownScore) {
                shownScore = score
                host.refresh()
            } else {
                canvasView.invalidate()
            }
            host.handler.postDelayed(this, stepMs)
        }
    }

    override val title get() = if (over) "Game over! $score" else "$name  $score"
    override val showTime = false
    override val repeatKeys = true
    override val leftSoft get() = when {
        over -> "Again"
        running -> "Pause"
        else -> "Play"
    }
    override val centerSoft get() = "Top ${host.prefs.highScore(id)}"

    abstract fun reset()
    abstract fun step()
    abstract fun draw(c: Canvas, w: Float, h: Float)

    /** Game controls; return false for keys the game doesn't use. */
    abstract fun input(k: Key): Boolean

    init {
        host.handler.post { reset(); canvasView.invalidate() }
    }

    override fun render(): View {
        (canvasView.parent as? android.view.ViewGroup)?.removeView(canvasView)
        canvasView.invalidate()
        return canvasView
    }

    private fun start() {
        if (over) {
            reset()
            score = 0
            over = false
        }
        running = true
        host.handler.removeCallbacks(loop)
        host.handler.post(loop)
        host.refresh()
    }

    private fun pause() {
        running = false
        host.handler.removeCallbacks(loop)
        host.refresh()
    }

    protected fun finish() {
        running = false
        over = true
        host.handler.removeCallbacks(loop)
        if (score > host.prefs.highScore(id)) host.prefs.setHighScore(id, score)
        host.refresh()
    }

    override fun onHide() {
        if (running) pause()
    }

    override fun onKey(k: Key): Boolean {
        if (k == Key.SOFT_L) {
            if (running) pause() else start()
            return true
        }
        if (over) return k == Key.OK && run { start(); true }
        if (!input(k)) return false
        if (!running) start()
        canvasView.invalidate()
        return true
    }

    /** Draws a sprite in logical pixels: '#' rows from [art], scaled by [s], offset to (ox, oy). */
    protected fun sprite(c: Canvas, art: Array<String>, x: Float, y: Float, s: Float, ox: Float, oy: Float) {
        art.forEachIndexed { r, line ->
            line.forEachIndexed { col, ch ->
                if (ch == '#') {
                    val l = ox + (x + col) * s
                    val t = oy + (y + r) * s
                    c.drawRect(l, t, l + s, t + s, paint)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Space Impact

class SpaceImpact(host: MainActivity) : GameScreen(host, "space", "Space") {
    override val stepMs = 40L
    private val fw = 84f
    private val fh = 42f

    private val ship = arrayOf("##......", "#####...", ".#######", "#####...", "##......")
    private val foes = listOf(
        arrayOf("..###", ".#.#.", "#####", ".#.#.", "..###"),
        arrayOf(".##.#", "#####", "##...", "#####", ".##.#"),
        arrayOf("...##", ".####", "##.##", ".####", "...##"),
    )

    private class Foe(var x: Float, val baseY: Float, val kind: Int, val phase: Float, var hp: Int)
    private class Shot(var x: Float, var y: Float, val dx: Float)

    private var px = 4f
    private var py = 18f
    private var lives = 3
    private var tick = 0
    private var cooldown = 0
    private var invulnerable = 0
    private val foesList = ArrayList<Foe>()
    private val shots = ArrayList<Shot>()
    private val stars = Array(14) { floatArrayOf(Random.nextFloat() * 84f, Random.nextFloat() * 42f) }

    override fun reset() {
        px = 4f; py = 18f; lives = 3; tick = 0; cooldown = 0; invulnerable = 0
        foesList.clear(); shots.clear()
    }

    override fun input(k: Key): Boolean {
        when (k) {
            Key.UP, Key.D2 -> py = (py - 2f).coerceAtLeast(0f)
            Key.DOWN, Key.D8 -> py = (py + 2f).coerceAtMost(fh - 5f)
            Key.LEFT, Key.D4 -> px = (px - 2f).coerceAtLeast(0f)
            Key.RIGHT, Key.D6 -> px = (px + 2f).coerceAtMost(fw / 2)
            Key.OK, Key.D5, Key.CALL -> fire()
            else -> return false
        }
        return true
    }

    private fun fire() {
        if (cooldown > 0) return
        shots.add(Shot(px + 8f, py + 2f, 3f))
        cooldown = 4
    }

    override fun step() {
        tick++
        if (cooldown > 0) cooldown--
        if (invulnerable > 0) invulnerable--
        val speed = 0.7f + minOf(score, 600) / 600f
        stars.forEach { it[0] -= 0.5f; if (it[0] < 0) { it[0] = fw; it[1] = Random.nextFloat() * fh } }
        if (tick % maxOf(18, 45 - score / 20) == 0) {
            foesList.add(Foe(fw, 3f + Random.nextFloat() * (fh - 12f), Random.nextInt(foes.size), Random.nextFloat() * 6f, 1 + score / 300))
        }
        foesList.forEach { it.x -= speed }
        foesList.filter { Random.nextInt(260) == 0 }.forEach { shots.add(Shot(it.x - 1f, foeY(it) + 2f, -1.6f)) }
        shots.forEach { it.x += it.dx }
        shots.removeAll { it.x < -2 || it.x > fw + 2 }

        val hitShots = HashSet<Shot>()
        for (s in shots.filter { it.dx > 0 }) {
            val f = foesList.firstOrNull { s.x in it.x..it.x + 5f && s.y in foeY(it)..foeY(it) + 5f } ?: continue
            hitShots.add(s)
            f.hp--
            if (f.hp <= 0) {
                foesList.remove(f)
                score += 5
            }
        }
        shots.removeAll(hitShots)
        foesList.removeAll { it.x < -6f }

        if (invulnerable == 0) {
            val hit = foesList.firstOrNull { overlap(it.x, foeY(it), 5f, 5f) } != null ||
                shots.any { it.dx < 0 && it.x in px..px + 8f && it.y in py..py + 5f }
            if (hit) {
                lives--
                invulnerable = 40
                foesList.removeAll { overlap(it.x, foeY(it), 5f, 5f) }
                shots.removeAll { it.dx < 0 }
                if (lives <= 0) over = true
            }
        }
    }

    private fun foeY(f: Foe) = f.baseY + sin(f.x / 7f + f.phase) * 3f

    private fun overlap(x: Float, y: Float, w: Float, h: Float) = x < px + 8f && x + w > px && y < py + 5f && y + h > py

    override fun draw(c: Canvas, w: Float, h: Float) {
        val s = minOf(w / fw, h / fh)
        val ox = (w - fw * s) / 2f
        val oy = (h - fh * s) / 2f
        stars.forEach { c.drawRect(ox + it[0] * s, oy + it[1] * s, ox + it[0] * s + s * 0.6f, oy + it[1] * s + s * 0.6f, paint) }
        if (invulnerable % 6 < 3) sprite(c, ship, px, py, s, ox, oy)
        foesList.forEach { sprite(c, foes[it.kind], it.x, foeY(it), s, ox, oy) }
        shots.forEach { c.drawRect(ox + it.x * s, oy + it.y * s, ox + (it.x + 2f) * s, oy + (it.y + 1f) * s, paint) }
        for (i in 0 until lives) sprite(c, arrayOf("#.#", "###", ".#."), fw - 4f - i * 4f, 0f, s, ox, oy)
    }
}

// ------------------------------------------------------------------ Blocks

class BlocksGame(host: MainActivity) : GameScreen(host, "blocks", "Blocks") {
    override val stepMs = 30L
    private val cols = 10
    private val rows = 20
    private val board = Array(rows) { IntArray(cols) }

    private val shapes = arrayOf(
        arrayOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),
        arrayOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),
        arrayOf(2 to 0, 0 to 1, 1 to 1, 2 to 1),
        arrayOf(1 to 0, 2 to 0, 1 to 1, 2 to 1),
        arrayOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),
        arrayOf(1 to 0, 0 to 1, 1 to 1, 2 to 1),
        arrayOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
    )
    private var piece = 0
    private var next = 0
    private var rot = 0
    private var x = 3
    private var y = 0
    private var lines = 0
    private var frames = 0
    private var lastRotate = 0L

    private fun cells(p: Int = piece, r: Int = rot, ox: Int = x, oy: Int = y): List<Pair<Int, Int>> = shapes[p].map { (cx, cy) ->
        var a = cx
        var b = cy
        val size = if (p == 0) 4 else if (p == 3) 2 else 3
        repeat(r % 4) {
            val t = a
            a = size - 1 - b
            b = t
        }
        (ox + a) to (oy + b)
    }

    private fun fits(cs: List<Pair<Int, Int>>) = cs.all { (cx, cy) -> cx in 0 until cols && cy < rows && (cy < 0 || board[cy][cx] == 0) }

    override fun reset() {
        board.forEach { it.fill(0) }
        lines = 0
        next = Random.nextInt(shapes.size)
        spawn()
    }

    private fun spawn() {
        piece = next
        next = Random.nextInt(shapes.size)
        rot = 0
        x = 3
        y = 0
        if (!fits(cells())) over = true
    }

    private fun lock() {
        cells().forEach { (cx, cy) -> if (cy >= 0) board[cy][cx] = 1 }
        var cleared = 0
        var r = rows - 1
        while (r >= 0) {
            if (board[r].all { it != 0 }) {
                for (k in r downTo 1) board[k] = board[k - 1].copyOf()
                board[0] = IntArray(cols)
                cleared++
            } else {
                r--
            }
        }
        lines += cleared
        score += intArrayOf(0, 10, 30, 60, 100)[cleared]
        spawn()
    }

    private fun drop(): Boolean {
        if (fits(cells(oy = y + 1))) {
            y++
            return true
        }
        lock()
        return false
    }

    override fun step() {
        frames++
        if (frames >= maxOf(4, 20 - lines / 4)) {
            frames = 0
            drop()
        }
    }

    override fun input(k: Key): Boolean {
        when (k) {
            Key.LEFT, Key.D4 -> if (fits(cells(ox = x - 1))) x--
            Key.RIGHT, Key.D6 -> if (fits(cells(ox = x + 1))) x++
            Key.DOWN, Key.D8 -> if (drop()) score++
            Key.UP, Key.D2, Key.D5, Key.OK -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastRotate < 180) return true
                lastRotate = now
                val r = (rot + 1) % 4
                when {
                    fits(cells(r = r)) -> rot = r
                    fits(cells(r = r, ox = x - 1)) -> { rot = r; x-- }
                    fits(cells(r = r, ox = x + 1)) -> { rot = r; x++ }
                }
            }
            Key.D0 -> {
                while (fits(cells(oy = y + 1))) { y++; score += 2 }
                lock()
            }
            else -> return false
        }
        return true
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val s = h / rows
        val ox = s * 0.5f
        val g = s * 0.12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.15f
        c.drawRect(ox - g, 0f, ox + cols * s + g, h, paint)
        paint.style = Paint.Style.FILL
        fun cell(cx: Int, cy: Int, bx: Float, by: Float) =
            c.drawRect(bx + cx * s + g, by + cy * s + g, bx + (cx + 1) * s - g, by + (cy + 1) * s - g, paint)
        for (r in 0 until rows) for (col in 0 until cols) if (board[r][col] != 0) cell(col, r, ox, 0f)
        cells().forEach { (cx, cy) -> if (cy >= 0) cell(cx, cy, ox, 0f) }
        // side panel
        val px = ox + cols * s + s * 1.2f
        paint.textSize = s * 1.1f
        paint.typeface = Fonts.pixelBold
        c.drawText("Next", px, s * 1.2f, paint)
        cells(p = next, r = 0, ox = 0, oy = 0).forEach { (cx, cy) -> cell(cx, cy, px, s * 1.8f) }
        c.drawText("Lines", px, s * 7f, paint)
        c.drawText("$lines", px, s * 8.3f, paint)
        paint.textSize = s * 0.8f
        paint.typeface = Fonts.pixel
        c.drawText("2 turn  0 drop", px, h - s * 0.4f, paint)
    }
}

// ------------------------------------------------------------------ Brick breaker

class BrickBreaker(host: MainActivity) : GameScreen(host, "bricks", "Bricks") {
    override val stepMs = 16L
    private val fw = 80f
    private val fh = 48f
    private val brickCols = 8
    private val brickRows = 5
    private val bricks = Array(brickRows) { BooleanArray(brickCols) }
    private var paddle = 33f
    private val paddleW = 14f
    private var bx = 0f
    private var by = 0f
    private var vx = 0f
    private var vy = 0f
    private var stuck = true
    private var lives = 3
    private var level = 1

    override fun reset() {
        lives = 3
        level = 1
        fillBricks()
        serve()
    }

    private fun fillBricks() = bricks.forEach { it.fill(true) }

    private fun serve() {
        stuck = true
        bx = paddle + paddleW / 2
        by = fh - 5f
    }

    override fun input(k: Key): Boolean {
        when (k) {
            Key.LEFT, Key.D4 -> paddle = (paddle - 3f).coerceAtLeast(0f)
            Key.RIGHT, Key.D6 -> paddle = (paddle + 3f).coerceAtMost(fw - paddleW)
            Key.OK, Key.D5, Key.UP, Key.D2 -> launch()
            else -> return false
        }
        if (stuck) bx = paddle + paddleW / 2
        return true
    }

    private fun launch() {
        if (!stuck) return
        stuck = false
        val sp = 0.55f + level * 0.08f
        vx = if (Random.nextBoolean()) sp else -sp
        vy = -sp
    }

    override fun step() {
        if (stuck) return
        bx += vx
        by += vy
        if (bx < 1f) { bx = 1f; vx = abs(vx) }
        if (bx > fw - 1f) { bx = fw - 1f; vx = -abs(vx) }
        if (by < 1f) { by = 1f; vy = abs(vy) }
        // paddle: the hit position steers the ball
        if (vy > 0 && by >= fh - 4f && by <= fh - 2f && bx in paddle - 1f..paddle + paddleW + 1f) {
            val rel = (bx - paddle) / paddleW - 0.5f
            val sp = kotlin.math.sqrt(vx * vx + vy * vy)
            vx = rel * 2f * sp
            vy = -kotlin.math.sqrt((sp * sp - vx * vx).coerceAtLeast(sp * sp * 0.2f))
        }
        if (by > fh) {
            lives--
            if (lives <= 0) over = true else serve()
            return
        }
        val bw = fw / brickCols
        val col = (bx / bw).toInt()
        val row = ((by - 4f) / 3.5f).toInt()
        if (by >= 4f && row in 0 until brickRows && col in 0 until brickCols && bricks[row][col]) {
            bricks[row][col] = false
            vy = -vy
            score += 10 * level
            if (bricks.all { r -> r.none { it } }) {
                level++
                fillBricks()
                serve()
            }
        }
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val s = minOf(w / fw, h / fh)
        val ox = (w - fw * s) / 2f
        val oy = (h - fh * s) / 2f
        val bw = fw / brickCols
        for (r in 0 until brickRows) for (col in 0 until brickCols) {
            if (!bricks[r][col]) continue
            val l = ox + (col * bw + 0.4f) * s
            val t = oy + (4f + r * 3.5f) * s
            c.drawRect(l, t, l + (bw - 0.8f) * s, t + 2.6f * s, paint)
        }
        c.drawRect(ox + paddle * s, oy + (fh - 3f) * s, ox + (paddle + paddleW) * s, oy + (fh - 1.6f) * s, paint)
        c.drawRect(ox + (bx - 0.9f) * s, oy + (by - 0.9f) * s, ox + (bx + 0.9f) * s, oy + (by + 0.9f) * s, paint)
        for (i in 0 until lives) c.drawRect(ox + (fw - 3f - i * 3f) * s, oy + 0.5f * s, ox + (fw - 1.5f - i * 3f) * s, oy + 2f * s, paint)
    }
}

// ------------------------------------------------------------------ Pairs

/** Memory game: find the 8 matching pairs in as few turns as possible. */
class PairsGame(host: MainActivity) : Screen(host) {
    private val symbols = listOf(Icons.PHONE, Icons.MESSAGES, Icons.CONTACTS, Icons.CAMERA, Icons.GALLERY, Icons.CLOCK, Icons.SETTINGS, Icons.NOTE)
    private var deck = IntArray(16)
    private val found = BooleanArray(16)
    private var open = ArrayList<Int>()
    private var cursor = 0
    private var turns = 0
    private val best get() = host.prefs.highScore("pairs")
    private val hide = Runnable {
        open.clear()
        host.refresh()
    }

    init { deal() }

    private fun deal() {
        deck = ((0 until 8) + (0 until 8)).shuffled().toIntArray()
        found.fill(false)
        open.clear()
        turns = 0
        cursor = 0
    }

    private val done get() = found.all { it }

    override val title get() = if (done) "You won! $turns" else "Pairs  $turns"
    override val showTime = false
    override val leftSoft get() = if (done) "Again" else "Turn"
    override val centerSoft get() = if (best > 0) "Best $best" else ""

    private val board = object : View(host) {
        private val p = Paint()
        override fun onDraw(c: Canvas) {
            val cell = minOf(width / 4f, height / 4f)
            val ox = (width - cell * 4) / 2f
            val oy = (height - cell * 4) / 2f
            val g = cell * 0.08f
            for (i in 0 until 16) {
                val l = ox + (i % 4) * cell + g
                val t = oy + (i / 4) * cell + g
                val r = l + cell - 2 * g
                val b = t + cell - 2 * g
                p.color = host.fg
                val faceUp = found[i] || i in open
                if (faceUp) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = if (i == cursor) g * 1.6f else g * 0.5f
                    c.drawRect(l, t, r, b, p)
                    p.style = Paint.Style.FILL
                    drawMask(c, symbols[deck[i]], l + g * 2, t + g * 2, r - g * 2, b - g * 2)
                } else {
                    p.style = Paint.Style.FILL
                    c.drawRect(l, t, r, b, p)
                    if (i == cursor) {
                        p.color = host.bg
                        c.drawRect(l + g * 1.5f, t + g * 1.5f, r - g * 1.5f, b - g * 1.5f, p)
                        p.color = host.fg
                        c.drawRect(l + g * 3f, t + g * 3f, r - g * 3f, b - g * 3f, p)
                    }
                }
            }
        }

        private fun drawMask(c: Canvas, m: Mask, l: Float, t: Float, r: Float, b: Float) {
            val s = minOf((r - l) / m.w, (b - t) / m.h)
            val x0 = l + ((r - l) - s * m.w) / 2
            val y0 = t + ((b - t) - s * m.h) / 2
            for (y in 0 until m.h) for (x in 0 until m.w) if (m[x, y]) c.drawRect(x0 + x * s, y0 + y * s, x0 + (x + 1) * s, y0 + (y + 1) * s, p)
        }
    }

    override fun render(): View {
        (board.parent as? android.view.ViewGroup)?.removeView(board)
        board.invalidate()
        return board
    }

    override fun onHide() = host.handler.removeCallbacks(hide)

    private fun flip() {
        if (done) { deal(); host.refresh(); return }
        if (found[cursor] || cursor in open) return
        if (open.size == 2) { host.handler.removeCallbacks(hide); open.clear() }
        open.add(cursor)
        if (open.size == 2) {
            turns++
            val (a, b) = open
            if (deck[a] == deck[b]) {
                found[a] = true
                found[b] = true
                open.clear()
                if (done && (best == 0 || turns < best)) host.prefs.setHighScore("pairs", turns)
            } else {
                host.handler.postDelayed(hide, 800)
            }
        }
    }

    override fun onKey(k: Key): Boolean {
        when (k) {
            Key.LEFT, Key.D4 -> cursor = (cursor + 15) % 16
            Key.RIGHT, Key.D6 -> cursor = (cursor + 1) % 16
            Key.UP, Key.D2 -> cursor = (cursor + 12) % 16
            Key.DOWN, Key.D8 -> cursor = (cursor + 4) % 16
            Key.SOFT_L, Key.OK, Key.D5 -> flip()
            else -> return false
        }
        host.refresh()
        return true
    }
}
