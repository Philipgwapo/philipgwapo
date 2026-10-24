package com.philip.nokiamode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class Screen(val host: MainActivity) {
    open val title: String = ""
    open val indicator: String = ""
    open val leftSoft: String = "Select"
    open val centerSoft: String = ""
    open val rightSoft: String = "Back"

    /** Builds the LCD content. Called on every refresh, so keep state in fields. */
    abstract fun render(): View

    /** Returns true if the key was consumed; otherwise END goes home and SOFT_R goes back. */
    open fun onKey(k: Key): Boolean = false
    open fun onLongKey(k: Key): Boolean = false
    open fun onShow() {}
    open fun onHide() {}

    protected fun column(gravity: Int = Gravity.TOP): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    protected fun text(s: String, size: Float = 18f, gravity: Int = Gravity.START, inverse: Boolean = false, multiline: Boolean = false): TextView =
        host.lcdText(s, size).apply {
            this.gravity = gravity
            if (multiline) {
                isSingleLine = false
                ellipsize = null
            }
            if (inverse) {
                setBackgroundColor(Lcd.FG)
                setTextColor(host.lcdBg)
            }
            val p = host.dpi(3f)
            setPadding(p, p, p, p)
        }

    protected fun LinearLayout.add(v: View, weight: Float = 0f): View {
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, weight))
        return v
    }
}

// ------------------------------------------------------------------ boot / notes

class SplashScreen(host: MainActivity) : Screen(host) {
    override val leftSoft = ""
    override val rightSoft = ""
    private val done = Runnable { host.home() }

    override fun render(): View = column(Gravity.CENTER).apply {
        add(text("NOKIA", 40f, Gravity.CENTER))
        add(text("CONNECTING PEOPLE", 12f, Gravity.CENTER))
    }

    override fun onShow() { host.handler.postDelayed(done, 1800) }
    override fun onHide() { host.handler.removeCallbacks(done) }
    override fun onKey(k: Key): Boolean { host.home(); return true }
}

class NoteScreen(host: MainActivity, private val msg: String, private val ms: Long) : Screen(host) {
    override val leftSoft = ""
    override val rightSoft = "OK"
    private val close = Runnable { host.close(this) }

    override fun render(): View = column(Gravity.CENTER).apply {
        add(text(msg, 18f, Gravity.CENTER, multiline = true))
    }

    override fun onShow() { if (ms > 0) host.handler.postDelayed(close, ms) }
    override fun onHide() { host.handler.removeCallbacks(close) }
    override fun onKey(k: Key): Boolean {
        if (k == Key.END) return false
        host.close(this)
        return true
    }
}

// ------------------------------------------------------------------ idle screen

class HomeScreen(host: MainActivity) : Screen(host) {
    override val leftSoft = "Menu"
    override val rightSoft = "Names"
    private val tick = object : Runnable {
        override fun run() {
            host.refresh()
            host.handler.postDelayed(this, 1000)
        }
    }

    override fun render(): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val side = host.dpi(22f)
        row.addView(BarsView(host, signalLevel(), "Y"), LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))
        val mid = column(Gravity.CENTER).apply {
            add(text("NOKIA", 20f, Gravity.CENTER))
            add(text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 44f, Gravity.CENTER))
            add(text(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()), 16f, Gravity.CENTER))
        }
        row.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        row.addView(BarsView(host, batteryLevel(), "B"), LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))
        return row
    }

    private fun batteryLevel(): Int {
        val bm = host.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ((pct + 24) / 25).coerceIn(0, 4)
    }

    private fun signalLevel(): Int = try {
        val tm = host.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (android.os.Build.VERSION.SDK_INT >= 28) tm.signalStrength?.level ?: 0 else 4
    } catch (e: Exception) {
        0
    }

    override fun onShow() { host.handler.post(tick) }
    override fun onHide() { host.handler.removeCallbacks(tick) }

    override fun onKey(k: Key): Boolean {
        when {
            k == Key.SOFT_L -> host.push(MenuScreen(host))
            k == Key.SOFT_R || k == Key.DOWN -> host.push(ContactsScreen(host))
            k == Key.UP -> host.push(MessagesMenu(host))
            k == Key.CALL -> host.push(CallLogScreen(host))
            k.digit != null -> host.push(DialerScreen(host, k.digit.toString()))
            else -> return k == Key.END
        }
        return true
    }

    override fun onLongKey(k: Key): Boolean {
        if (k == Key.D0) { host.push(DialerScreen(host, "+")); return true }
        return false
    }
}

// ------------------------------------------------------------------ main menu

class MenuScreen(host: MainActivity) : Screen(host) {
    private class Entry(val name: String, val icon: List<String>, val open: () -> Unit)

    private val entries = listOf(
        Entry("Messages", Icons.MESSAGES) { host.push(MessagesMenu(host)) },
        Entry("Names", Icons.NAMES) { host.push(ContactsScreen(host)) },
        Entry("Call register", Icons.CALLS) { host.push(CallLogScreen(host)) },
        Entry("Games", Icons.SNAKE) { host.push(SnakeScreen(host)) },
        Entry("Clock", Icons.CLOCK) { host.push(ClockMenu(host)) },
        Entry("Apps", Icons.APPS) { host.push(AppsScreen(host)) },
        Entry("Settings", Icons.SETTINGS) { host.push(SettingsScreen(host)) },
    )
    private var sel = 0

    override val title get() = entries[sel].name
    override val indicator get() = "${sel + 1}"

    override fun render(): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val icon = PixelView(host, entries[sel].icon)
        val p = host.dpi(12f)
        icon.setPadding(p, p, p, p)
        row.addView(icon, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(p, p, p, p) })
        // scroll bar on the right, like the original
        val bar = column()
        entries.indices.forEach { i ->
            bar.addView(View(host).apply { setBackgroundColor(if (i == sel) Lcd.FG else 0) },
                LinearLayout.LayoutParams(host.dpi(6f), 0, 1f))
        }
        bar.setBackgroundColor(0x332E3B2B)
        row.addView(bar, LinearLayout.LayoutParams(host.dpi(6f), LinearLayout.LayoutParams.MATCH_PARENT))
        return row
    }

    override fun onKey(k: Key): Boolean {
        when (k) {
            Key.UP -> sel = (sel - 1 + entries.size) % entries.size
            Key.DOWN -> sel = (sel + 1) % entries.size
            Key.SOFT_L, Key.CALL -> { entries[sel].open(); return true }
            else -> {
                val d = k.digit?.digitToIntOrNull() ?: return false
                if (d in 1..entries.size) { sel = d - 1; entries[sel].open() }
                return true
            }
        }
        host.refresh()
        return true
    }
}

// ------------------------------------------------------------------ generic list

open class ListScreen(host: MainActivity, private val name: String) : Screen(host) {
    class Item(val label: String, val action: () -> Unit)

    protected var items: List<Item> = emptyList()
    protected var sel = 0
    open val emptyText = "(empty)"
    private val rows = 6

    override val title get() = name
    override val centerSoft get() = if (items.isEmpty()) "" else "${sel + 1}/${items.size}"

    open fun loadItems(): List<Item> = emptyList()

    override fun onShow() {
        items = loadItems()
        sel = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun reload() {
        onShow()
        host.refresh()
    }

    override fun render(): View = column().apply {
        if (items.isEmpty()) {
            gravity = Gravity.CENTER
            add(text(emptyText, 16f, Gravity.CENTER, multiline = true))
            return@apply
        }
        val start = (sel - rows / 2).coerceAtMost(items.size - rows).coerceAtLeast(0)
        for (i in start until minOf(items.size, start + rows)) {
            add(text(items[i].label, 17f, inverse = i == sel))
        }
    }

    override fun onKey(k: Key): Boolean {
        val n = items.size
        when (k) {
            Key.UP -> if (n > 0) sel = (sel - 1 + n) % n
            Key.DOWN -> if (n > 0) sel = (sel + 1) % n
            Key.SOFT_L, Key.CALL -> { if (n > 0) items[sel].action(); return true }
            else -> return false
        }
        host.refresh()
        return true
    }
}

class SimpleList(host: MainActivity, name: String, private val provider: () -> List<ListScreen.Item>) : ListScreen(host, name) {
    override fun loadItems() = provider()
}

// ------------------------------------------------------------------ calls

class DialerScreen(
    host: MainActivity,
    initial: String,
    private val prompt: String = "",
    private val onOk: ((String) -> Unit)? = null,
) : Screen(host) {
    private val number = StringBuilder(initial)

    override val title get() = prompt
    override val leftSoft get() = if (onOk == null) "Call" else "OK"
    override val rightSoft get() = if (number.isEmpty()) "Back" else "Clear"

    override fun render(): View = column(Gravity.BOTTOM).apply {
        add(text(number.toString(), if (number.length > 12) 22f else 32f, Gravity.END, multiline = true))
    }

    private fun confirm() {
        val n = number.toString()
        if (n.isEmpty()) return
        host.pop()
        if (onOk != null) onOk.invoke(n) else host.call(n)
    }

    override fun onKey(k: Key): Boolean {
        when {
            k.digit != null -> number.append(k.digit)
            k == Key.SOFT_R && number.isNotEmpty() -> number.deleteCharAt(number.length - 1)
            k == Key.SOFT_L || k == Key.CALL -> { confirm(); return true }
            else -> return false
        }
        host.refresh()
        return true
    }

    override fun onLongKey(k: Key): Boolean {
        when (k) {
            Key.D0 -> number.append('+')
            Key.SOFT_R -> number.clear()
            else -> return false
        }
        host.refresh()
        return true
    }
}

class ContactsScreen(
    host: MainActivity,
    private val onPick: ((String, String) -> Unit)? = null,
) : ListScreen(host, "Names") {
    private class Contact(val name: String, val number: String)

    private var all: List<Contact> = emptyList()
    private var shown: List<Contact> = emptyList()
    private val query = StringBuilder()

    override val title get() = if (query.isEmpty()) "Names" else "Find: $query"
    override val rightSoft get() = if (query.isEmpty()) "Back" else "Clear"
    override val emptyText get() = when {
        !host.hasPermission(Manifest.permission.READ_CONTACTS) -> "No permission to read contacts"
        query.isNotEmpty() -> "No matches"
        else -> "No names"
    }

    override fun onShow() {
        all = loadContacts()
        super.onShow()
    }

    private fun loadContacts(): List<Contact> {
        if (!host.hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        host.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out.putIfAbsent(name + num.filter { it.isDigit() }, Contact(name, num))
            }
        }
        return out.values.toList()
    }

    override fun loadItems(): List<Item> {
        val q = query.toString()
        shown = all.filter { q.isEmpty() || T9.matches(it.name, it.number, q) }
        return shown.map { c ->
            Item(c.name) {
                if (onPick != null) {
                    host.pop()
                    onPick.invoke(c.name, c.number)
                } else {
                    host.push(SimpleList(host, c.name) {
                        listOf(
                            Item("Call ${c.number}") { host.call(c.number) },
                            Item("Send message") { host.push(TextEntryScreen(host, "Message to ${c.name}") { body -> host.sms(c.number, body) }) },
                        )
                    })
                }
            }
        }
    }

    override fun onKey(k: Key): Boolean {
        when {
            k == Key.CALL && onPick == null -> {
                shown.getOrNull(sel)?.let { host.call(it.number) }
                return true
            }
            k.digit?.isDigit() == true -> { query.append(k.digit); sel = 0; reload(); return true }
            k == Key.SOFT_R && query.isNotEmpty() -> { query.deleteCharAt(query.length - 1); sel = 0; reload(); return true }
        }
        return super.onKey(k)
    }
}

object T9 {
    private val map = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
    )

    fun digitOf(c: Char): Char {
        val l = c.lowercaseChar()
        if (l.isDigit()) return l
        return map.entries.firstOrNull { l in it.value }?.key ?: '1'
    }

    /** Matches if any word of the name starts with the key sequence, or the number contains it. */
    fun matches(name: String, number: String, q: String): Boolean {
        if (number.filter { it.isDigit() }.contains(q)) return true
        return name.split(' ', '-', '.', '_').any { w -> w.map(::digitOf).joinToString("").startsWith(q) }
    }
}

class CallLogScreen(host: MainActivity) : ListScreen(host, "Call register") {
    override val emptyText get() =
        if (!host.hasPermission(Manifest.permission.READ_CALL_LOG)) "No permission to read calls" else "No calls"

    override fun loadItems(): List<Item> {
        if (!host.hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<Item>()
        val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE)
        try {
            host.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < 30) {
                    val num = c.getString(0) ?: ""
                    val name = c.getString(1)
                    val mark = when (c.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "<"
                        CallLog.Calls.OUTGOING_TYPE -> ">"
                        CallLog.Calls.MISSED_TYPE -> "!"
                        else -> "-"
                    }
                    val label = "$mark ${if (name.isNullOrBlank()) num else name}"
                    out.add(Item(label) { host.call(num) })
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }
}

// ------------------------------------------------------------------ messages

class MessagesMenu(host: MainActivity) : ListScreen(host, "Messages") {
    override fun loadItems() = listOf(
        Item("Write message") {
            host.push(TextEntryScreen(host, "Message") { body ->
                host.push(ContactsOrNumber(host) { number -> host.sms(number, body) })
            })
        },
        Item("Inbox") { host.openMessagesApp() },
    )
}

/** "Number or search" step after writing an SMS. */
class ContactsOrNumber(host: MainActivity, private val onNumber: (String) -> Unit) : ListScreen(host, "Send to") {
    override fun loadItems() = listOf(
        Item("Enter number") { host.replace(DialerScreen(host, "", "Number:") { onNumber(it) }) },
        Item("Search names") { host.replace(ContactsScreen(host) { _, num -> onNumber(num) }) },
    )
}

/** Classic multi-tap text entry: press 2 twice for "b", # cycles case. */
class TextEntryScreen(host: MainActivity, private val prompt: String, private val onDone: (String) -> Unit) : Screen(host) {
    private val buf = StringBuilder()
    private var lastKey: Key? = null
    private var lastTime = 0L
    private var cycle = 0
    private var mode = 1 // 0 = abc, 1 = Abc (sentence), 2 = ABC

    private val letters = mapOf(
        '1' to ".,?!'-1", '2' to "abc2", '3' to "def3", '4' to "ghi4", '5' to "jkl5",
        '6' to "mno6", '7' to "pqrs7", '8' to "tuv8", '9' to "wxyz9", '0' to " 0",
    )

    override val title get() = prompt
    override val indicator get() = listOf("abc", "Abc", "ABC")[mode]
    override val leftSoft = "Send"
    override val rightSoft get() = if (buf.isEmpty()) "Back" else "Clear"
    override val centerSoft get() = "${160 - buf.length}"

    override fun render(): View = column().apply {
        add(text("${buf}_", 17f, multiline = true))
    }

    private fun upper(): Boolean = mode == 2 || (mode == 1 && (buf.isEmpty() || buf.trimEnd().endsWith('.') && buf.endsWith(' ')))

    override fun onKey(k: Key): Boolean {
        val now = SystemClock.uptimeMillis()
        val d = k.digit
        when {
            d != null && d in letters -> {
                val chars = letters.getValue(d)
                if (k == lastKey && now - lastTime < 1000 && buf.isNotEmpty()) {
                    cycle = (cycle + 1) % chars.length
                    buf.deleteCharAt(buf.length - 1)
                } else {
                    cycle = 0
                }
                val up = upper()
                val c = chars[cycle]
                buf.append(if (up) c.uppercaseChar() else c)
                lastKey = k
                lastTime = now
            }
            k == Key.HASH -> { mode = (mode + 1) % 3; lastKey = null }
            k == Key.STAR -> { buf.append('*'); lastKey = null }
            k == Key.SOFT_R && buf.isNotEmpty() -> { buf.deleteCharAt(buf.length - 1); lastKey = null }
            k == Key.SOFT_L -> {
                if (buf.isEmpty()) return true
                val s = buf.toString()
                host.pop()
                onDone(s)
                return true
            }
            else -> return false
        }
        host.refresh()
        return true
    }

    override fun onLongKey(k: Key): Boolean {
        val d = k.digit ?: return if (k == Key.SOFT_R) { buf.clear(); host.refresh(); true } else false
        if (!d.isDigit()) return false
        buf.append(d)
        lastKey = null
        host.refresh()
        return true
    }
}

// ------------------------------------------------------------------ clock

class ClockMenu(host: MainActivity) : ListScreen(host, "Clock") {
    override fun loadItems() = listOf(
        Item("Alarm clock") { host.push(AlarmScreen(host)) },
        Item("View alarms") { host.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
        Item("Countdown timer") { host.push(TimerScreen(host)) },
    )
}

class AlarmScreen(host: MainActivity) : Screen(host) {
    private val digits = StringBuilder()
    override val title = "Alarm time:"
    override val leftSoft = "OK"
    override val rightSoft get() = if (digits.isEmpty()) "Back" else "Clear"

    override fun render(): View = column(Gravity.CENTER).apply {
        val s = digits.toString().padEnd(4, '_')
        add(text("${s.substring(0, 2)}:${s.substring(2)}", 44f, Gravity.CENTER))
    }

    override fun onKey(k: Key): Boolean {
        when {
            k.digit?.isDigit() == true && digits.length < 4 -> digits.append(k.digit)
            k == Key.SOFT_R && digits.isNotEmpty() -> digits.deleteCharAt(digits.length - 1)
            k == Key.SOFT_L -> {
                val h = digits.take(2).toString().toIntOrNull()
                val m = digits.drop(2).toString().toIntOrNull()
                if (digits.length != 4 || h == null || m == null || h > 23 || m > 59) { host.note("Invalid time"); return true }
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h)
                    .putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Nokia alarm")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                host.pop()
                if (host.safeStart(i)) host.note("Alarm on\n%02d:%02d".format(h, m))
                return true
            }
            k.digit != null -> return true
            else -> return false
        }
        host.refresh()
        return true
    }
}

class TimerScreen(host: MainActivity) : Screen(host) {
    private val digits = StringBuilder()
    override val title = "Minutes:"
    override val leftSoft = "Start"
    override val rightSoft get() = if (digits.isEmpty()) "Back" else "Clear"

    override fun render(): View = column(Gravity.CENTER).apply {
        add(text(if (digits.isEmpty()) "_" else digits.toString(), 44f, Gravity.CENTER))
    }

    override fun onKey(k: Key): Boolean {
        when {
            k.digit?.isDigit() == true && digits.length < 3 -> digits.append(k.digit)
            k == Key.SOFT_R && digits.isNotEmpty() -> digits.deleteCharAt(digits.length - 1)
            k == Key.SOFT_L -> {
                val m = digits.toString().toIntOrNull() ?: return true
                if (m == 0) return true
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, m * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Nokia timer")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                host.pop()
                if (host.safeStart(i)) host.note("Timer set\n$m min")
                return true
            }
            k.digit != null -> return true
            else -> return false
        }
        host.refresh()
        return true
    }
}

// ------------------------------------------------------------------ apps

class AppsScreen(host: MainActivity) : ListScreen(host, "Apps") {
    override val emptyText = "No apps allowed.\nAdd them in\nSettings > Allowed apps"

    override fun loadItems(): List<Item> {
        val pm = host.packageManager
        return host.prefs.allowedApps.mapNotNull { pkg ->
            try {
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                Item(label) { host.launchPackage(pkg) }
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }
}

class AppPickerScreen(host: MainActivity) : ListScreen(host, "Allowed apps") {
    override fun loadItems(): List<Item> {
        val pm = host.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allowed = host.prefs.allowedApps
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != host.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, label) ->
                Item("${if (pkg in allowed) "[x]" else "[ ]"} $label") {
                    val now = host.prefs.allowedApps
                    host.prefs.allowedApps = if (pkg in now) now - pkg else now + pkg
                    reload()
                }
            }
    }
}

// ------------------------------------------------------------------ settings

class PinScreen(host: MainActivity, private val prompt: String, private val onDone: (String) -> Unit) : Screen(host) {
    private val pin = StringBuilder()
    override val title get() = prompt
    override val leftSoft = "OK"
    override val rightSoft get() = if (pin.isEmpty()) "Back" else "Clear"

    override fun render(): View = column(Gravity.CENTER).apply {
        add(text(if (pin.isEmpty()) "_" else "*".repeat(pin.length), 36f, Gravity.CENTER))
    }

    override fun onKey(k: Key): Boolean {
        when {
            k.digit?.isDigit() == true && pin.length < 8 -> pin.append(k.digit)
            k == Key.SOFT_R && pin.isNotEmpty() -> pin.deleteCharAt(pin.length - 1)
            k == Key.SOFT_L || k == Key.CALL -> {
                if (pin.length < 4) { host.note("Min. 4 digits"); return true }
                val s = pin.toString()
                host.pop()
                onDone(s)
                return true
            }
            k.digit != null -> return true
            else -> return false
        }
        host.refresh()
        return true
    }
}

class SettingsScreen(host: MainActivity) : ListScreen(host, "Settings") {
    override fun loadItems(): List<Item> {
        val p = host.prefs
        return listOf(
            Item("Allowed apps") { host.requirePin { host.push(AppPickerScreen(host)) } },
            Item("Light: ${p.backlightSec}s") {
                val opts = listOf(5, 10, 15, 30, 60)
                p.backlightSec = opts[(opts.indexOf(p.backlightSec) + 1) % opts.size]
                host.wake()
                reload()
            },
            Item("Key tones: ${if (p.keySounds) "On" else "Off"}") { p.keySounds = !p.keySounds; reload() },
            Item("B&W phone: ${if (p.grayscale) "On" else "Off"}") {
                val want = !p.grayscale
                if (Grayscale.set(host, want)) {
                    p.grayscale = want
                    reload()
                } else {
                    host.push(NoteScreen(host, "Needs PC setup:\nadb shell pm grant\n${host.packageName}\nandroid.permission.\nWRITE_SECURE_SETTINGS", 0))
                }
            },
            Item("Change code") {
                host.requirePin {
                    host.push(PinScreen(host, "New code:") { a ->
                        host.push(PinScreen(host, "Verify code:") { b ->
                            if (a == b) { p.pin = a; host.note("Code changed") } else host.note("Codes do not match")
                        })
                    })
                }
            },
            Item("Exit dumb mode") { host.requirePin { host.exitDumbMode() } },
            Item("About") { host.push(NoteScreen(host, "Nokia Mode 1.0\nDefault code 0000", 0)) },
        )
    }
}
