package com.philip.nokiamode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.random.Random

/** Snake II: no walls, the snake wraps around the screen edges. */
class SnakeGame(private val cols: Int = 20, private val rows: Int = 12) {
    enum class Dir(val dx: Int, val dy: Int) { UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0) }

    val body = ArrayDeque<Pair<Int, Int>>()
    var food = 0 to 0
        private set
    var score = 0
        private set
    var over = false
        private set
    private var dir = Dir.RIGHT
    private var pending = Dir.RIGHT

    val width get() = cols
    val height get() = rows
    val delayMs: Long get() = (220 - body.size * 4).coerceAtLeast(80).toLong()

    init { reset() }

    fun reset() {
        body.clear()
        for (i in 0 until 4) body.addFirst((2 + i) to rows / 2)
        dir = Dir.RIGHT
        pending = Dir.RIGHT
        score = 0
        over = false
        placeFood()
    }

    fun turn(d: Dir) {
        // can't reverse into yourself
        if (d.dx != -dir.dx || d.dy != -dir.dy) pending = d
    }

    fun step() {
        if (over) return
        dir = pending
        val (hx, hy) = body.first()
        val next = Math.floorMod(hx + dir.dx, cols) to Math.floorMod(hy + dir.dy, rows)
        val eating = next == food
        if (!eating) body.removeLast()
        if (next in body) {
            over = true
            return
        }
        body.addFirst(next)
        if (eating) {
            score += 7
            placeFood()
        }
    }

    private fun placeFood() {
        val free = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }.filter { it !in body }
        if (free.isNotEmpty()) food = free[Random.nextInt(free.size)]
    }
}

class SnakeView(context: Context, private val game: SnakeGame) : View(context) {
    private val paint = Paint().apply { color = Lcd.FG }
    private val border = Paint().apply {
        color = Lcd.FG
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val cell = minOf(width.toFloat() / game.width, height.toFloat() / game.height)
        val ox = (width - cell * game.width) / 2f
        val oy = (height - cell * game.height) / 2f
        val gap = cell * 0.12f
        border.strokeWidth = cell * 0.15f
        canvas.drawRect(ox, oy, ox + cell * game.width, oy + cell * game.height, border)
        fun block(x: Int, y: Int, inset: Float) {
            val l = ox + x * cell + inset
            val t = oy + y * cell + inset
            canvas.drawRect(l, t, l + cell - gap - inset * 2, t + cell - gap - inset * 2, paint)
        }
        game.body.forEach { (x, y) -> block(x, y, 0f) }
        block(game.food.first, game.food.second, cell * 0.22f)
    }
}

class SnakeScreen(host: MainActivity) : Screen(host) {
    private val game = SnakeGame()
    private var view: SnakeView? = null
    private var running = false

    private val loop = object : Runnable {
        override fun run() {
            game.step()
            if (game.over) {
                running = false
                if (game.score > host.prefs.snakeHigh) host.prefs.snakeHigh = game.score
                host.refresh()
                return
            }
            view?.invalidate()
            host.handler.postDelayed(this, game.delayMs)
        }
    }

    override val title get() = if (game.over) "Game over!" else "Snake II"
    override val indicator get() = "${game.score}"
    override val leftSoft get() = when {
        game.over -> "Again"
        running -> "Pause"
        else -> "Play"
    }
    override val centerSoft get() = "Top ${host.prefs.snakeHigh}"

    override fun render(): View {
        // The game view is kept so the loop can redraw it without rebuilding the LCD.
        return (view ?: SnakeView(host, game).also { view = it }).also {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.invalidate()
        }
    }

    private fun start() {
        if (game.over) game.reset()
        running = true
        host.handler.removeCallbacks(loop)
        host.handler.postDelayed(loop, game.delayMs)
        host.refresh()
    }

    private fun pause() {
        running = false
        host.handler.removeCallbacks(loop)
        host.refresh()
    }

    override fun onHide() { if (running) pause() }

    override fun onKey(k: Key): Boolean {
        val d = when (k) {
            Key.D2, Key.UP -> SnakeGame.Dir.UP
            Key.D8, Key.DOWN -> SnakeGame.Dir.DOWN
            Key.D4 -> SnakeGame.Dir.LEFT
            Key.D6 -> SnakeGame.Dir.RIGHT
            Key.SOFT_L, Key.D5 -> { if (running) pause() else start(); return true }
            else -> return false
        }
        if (!running && !game.over) start()
        game.turn(d)
        return true
    }
}
