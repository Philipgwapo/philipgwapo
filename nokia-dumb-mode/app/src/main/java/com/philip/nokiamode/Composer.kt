package com.philip.nokiamode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Gravity
import android.view.View
import kotlin.math.pow

/**
 * Nokia composer notation: duration, optional '#', note letter (or '-' for a pause) and octave,
 * e.g. "8#c2" is a sharp C eighth note in octave 2.
 */
data class Note(val dur: Int, val pitch: Char?, val sharp: Boolean, val octave: Int) {
    override fun toString() = if (pitch == null) "$dur-" else "$dur${if (sharp) "#" else ""}$pitch$octave"

    companion object {
        private val regex = Regex("""(\d+)(#?)([a-g-])(\d?)""")

        fun parse(token: String): Note? {
            val m = regex.matchEntire(token.trim().lowercase()) ?: return null
            val (d, sh, p, o) = m.destructured
            val dur = d.toIntOrNull()?.takeIf { it in listOf(1, 2, 4, 8, 16, 32) } ?: return null
            return if (p == "-") Note(dur, null, false, 1)
            else Note(dur, p[0], sh == "#", o.toIntOrNull()?.coerceIn(1, 3) ?: 1)
        }

        fun parseAll(s: String): List<Note> = s.split(' ').mapNotNull { parse(it) }
    }
}

object Composer {
    /** Public-domain melodies (Tárrega's Gran Vals, Beethoven). */
    val presets = listOf(
        "Kippoks Tune" to "8e2 8d2 4#f1 4#g1 8#c2 8b1 4d1 4e1 8b1 8a1 4#c1 4e1 2a1",
        "Ode to Joy" to "4e1 4e1 4f1 4g1 4g1 4f1 4e1 4d1 4c1 4c1 4d1 4e1 4e1 8d1 2d1",
        "Fur Elise" to "16e2 16#d2 16e2 16#d2 16e2 16b1 16d2 16c2 8a1 16- 16c1 16e1 16a1 8b1 16- 16e1 16#g1 16b1 8c2",
    )

    private const val RATE = 22050
    private const val TEMPO = 125
    private val semis = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

    private var track: AudioTrack? = null

    fun freq(n: Note): Double {
        val p = n.pitch ?: return 0.0
        val midi = 60 + 12 * n.octave + semis.getValue(p) + if (n.sharp) 1 else 0
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    /** Renders the tune as a square wave with a short decay on each note, like the original buzzer. */
    fun render(notes: List<Note>, tailSec: Double = 0.0): ShortArray {
        val whole = 4 * 60.0 / TEMPO
        val total = notes.sumOf { whole / it.dur } + tailSec
        val out = ShortArray((total * RATE).toInt().coerceAtLeast(1))
        var pos = 0
        for (n in notes) {
            val len = (whole / n.dur * RATE).toInt()
            val f = freq(n)
            val sound = (len * 0.88).toInt()
            for (i in 0 until len) {
                if (pos + i >= out.size) break
                if (f == 0.0 || i >= sound) continue
                val phase = (i * f / RATE) % 1.0
                val env = 1.0 - 0.35 * i / sound
                out[pos + i] = ((if (phase < 0.5) 1 else -1) * 5200 * env).toInt().toShort()
            }
            pos += len
        }
        return out
    }

    fun play(notes: List<Note>, loop: Boolean = false, ringtone: Boolean = false) {
        stop()
        if (notes.isEmpty()) return
        val pcm = render(notes, if (loop) 1.2 else 0.0)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (ringtone) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        } catch (e: Exception) {
            return
        }
        t.write(pcm, 0, pcm.size)
        if (loop) t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
    }

    fun playOne(n: Note) = play(listOf(n.copy(dur = maxOf(n.dur, 8))))

    fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        track = null
    }
}

class ComposerMenu(host: MainActivity) : ListScreen(host, "Composer") {
    override fun loadItems(): List<Item> {
        val ring = host.prefs.ringtone
        return listOf(Item("New tune", Icons.NOTE) { host.push(ComposerScreen(host, "", "")) }) +
            host.prefs.tunes.map { (name, notes) ->
                Item(if (name == ring) "$name *" else name, Icons.NOTE, sub = true) { host.push(ComposerScreen(host, name, notes)) }
            }
    }
}

/** The 3310 composer: 1-7 notes, 0 pause, 8/9 shorter/longer, * octave, # sharp. */
class ComposerScreen(host: MainActivity, private var name: String, notes: String) : Screen(host) {
    private val tune = Note.parseAll(notes).toMutableList()
    private var dur = tune.lastOrNull()?.dur ?: 4
    private var octave = tune.lastOrNull { it.pitch != null }?.octave ?: 1

    override val title get() = name.ifEmpty { "New tune" }
    override val leftSoft = "Options"
    override val rightSoft get() = if (tune.isEmpty()) "Back" else "Clear"
    override val centerSoft get() = "${tune.size}"

    override fun render(): View = column().apply {
        add(text(tune.joinToString(" ") + "_", 17f, multiline = true), 1f)
        add(text("1-7 note 0 pause 8/9 len * oct # sharp", 10f, Gravity.CENTER))
    }

    override fun onHide() = Composer.stop()

    private fun editLast(f: (Note) -> Note) {
        val last = tune.lastOrNull() ?: return
        val n = f(last)
        tune[tune.size - 1] = n
        dur = n.dur
        if (n.pitch != null) octave = n.octave
        if (n.pitch != null) Composer.playOne(n)
    }

    private fun save(asName: String) {
        val list = host.prefs.tunes.filter { it.first != asName } + (asName to tune.joinToString(" "))
        host.prefs.tunes = list
        name = asName
    }

    private fun options() {
        host.push(SimpleList(host, "Options") {
            listOf(
                ListScreen.Item("Play", Icons.NOTE) { host.pop(); Composer.play(tune) },
                ListScreen.Item("Save", Icons.NOTE) {
                    host.pop()
                    if (name.isEmpty()) {
                        host.push(TextEntryScreen(host, "Tune name", "Save") { n -> save(n); host.note("Saved") })
                    } else {
                        save(name)
                        host.note("Saved")
                    }
                },
                ListScreen.Item("Set as ringtone", Icons.SOUND) {
                    host.pop()
                    val doSet = { n: String -> save(n); host.prefs.ringtone = n; host.note("Ringtone set") }
                    if (name.isEmpty()) host.push(TextEntryScreen(host, "Tune name", "Save") { doSet(it) }) else doSet(name)
                },
                ListScreen.Item("Delete tune", Icons.EXIT) {
                    host.prefs.tunes = host.prefs.tunes.filter { it.first != name }
                    host.pop()
                    host.pop()
                },
            )
        })
    }

    override fun onKey(k: Key): Boolean {
        val d = k.digit
        when {
            d in '1'..'7' -> {
                val n = Note(dur, "cdefgab"[d!! - '1'], false, octave)
                tune.add(n)
                Composer.playOne(n)
            }
            d == '0' -> tune.add(Note(dur, null, false, 1))
            d == '8' -> editLast { it.copy(dur = minOf(32, it.dur * 2)) }
            d == '9' -> editLast { it.copy(dur = maxOf(1, it.dur / 2)) }
            d == '*' -> editLast { if (it.pitch == null) it else it.copy(octave = it.octave % 3 + 1) }
            d == '#' -> editLast { if (it.pitch == null || it.pitch == 'e' || it.pitch == 'b') it else it.copy(sharp = !it.sharp) }
            k == Key.OK -> Composer.play(tune)
            k == Key.SOFT_L -> { Composer.stop(); options(); return true }
            k == Key.SOFT_R && tune.isNotEmpty() -> tune.removeAt(tune.size - 1)
            else -> return false
        }
        host.refresh()
        return true
    }

    override fun onLongKey(k: Key): Boolean {
        if (k != Key.SOFT_R) return false
        tune.clear()
        host.refresh()
        return true
    }
}
