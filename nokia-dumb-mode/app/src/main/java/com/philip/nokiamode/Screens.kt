package com.philip.nokiamode

import android.Manifest
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class Screen(val host: MainActivity) {
    /** Status bar title; empty shows the signal bars instead. */
    open val title: String = ""
    open val showTime: Boolean = true
    open val leftSoft: String = "Select"
    open val centerSoft: String = ""
    open val rightSoft: String = "Back"

    /** Games set this so a held key auto-repeats instead of sending a long press. */
    open val repeatKeys: Boolean = false

    /** Builds the LCD content. Called on every refresh, so keep state in fields. */
    abstract fun render(): View

    /** Returns true if the key was consumed; otherwise OK acts as Select, END goes home and SOFT_R goes back. */
    open fun onKey(k: Key): Boolean = false
    open fun onLongKey(k: Key): Boolean = false
    open fun onShow() {}
    open fun onHide() {}
    open fun onTick() {}

    protected fun column(gravity: Int = Gravity.TOP): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    protected fun row(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    protected fun text(
        s: String, size: Float = 17f, gravity: Int = Gravity.START,
        bold: Boolean = false, color: Int = host.fg, multiline: Boolean = false,
    ): TextView = host.lcdText(s, size, bold).apply {
        this.gravity = gravity
        setTextColor(color)
        if (multiline) {
            isSingleLine = false
            ellipsize = null
        }
    }

    protected fun icon(mask: Mask?, color: Int = host.fg) = MaskView(host, mask, color)

    protected fun selection(radius: Float = 5f) = GradientDrawable().apply {
        cornerRadius = host.dp(radius)
        setColor(host.fg)
    }

    protected fun LinearLayout.add(v: View, weight: Float = 0f, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT): View {
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (weight > 0) 0 else height, weight))
        return v
    }
}

// ------------------------------------------------------------------ boot / notes

class SplashScreen(host: MainActivity) : Screen(host) {
    override val leftSoft = ""
    override val rightSoft = ""
    override val showTime = false
    private val done = Runnable { host.home() }

    override fun render(): View = column(Gravity.CENTER).apply {
        add(icon(Wallpaper.logo(host)), 1f)
        add(text("CONNECTING PEOPLE", 13f, Gravity.CENTER).apply { setPadding(0, host.dpi(6f), 0, 0) })
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
    private val p get() = host.prefs
    private val inCall get() = CallManager.calls.isNotEmpty()

    override val leftSoft get() = when {
        inCall -> "Call"
        p.newMessages > 0 -> "Read"
        p.missedCalls > 0 -> "Show"
        else -> "Menu"
    }
    override val rightSoft get() = Shortcuts.label(host, p.shortcut(Key.SOFT_R))
    override val showTime get() = p.wallpaper != "clock"
    private var shownMinute = ""

    private fun now(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    override fun render(): View {
        shownMinute = now("HH:mm")
        val date = now("EEE, MMM d").uppercase(Locale.getDefault())
        return column().apply {
            when (p.wallpaper) {
                "clock" -> {
                    add(text(shownMinute, 46f, Gravity.CENTER, bold = true))
                    add(text(date, 16f, Gravity.CENTER))
                    add(icon(Wallpaper.logo(host)).apply { setPadding(0, host.dpi(6f), 0, 0) }, 1f)
                }
                "photo" -> {
                    val v = PixelBitmapView(host)
                    v.bitmap = Wallpaper.photoBitmap(host, host.fg, host.bg)
                    if (v.bitmap == null) add(icon(Wallpaper.logo(host)), 1f) else add(v, 1f)
                    add(text(date, 16f, Gravity.CENTER).apply { setPadding(0, host.dpi(4f), 0, 0) })
                }
                else -> {
                    add(icon(if (p.wallpaper == "mountain") Wallpaper.mask else Wallpaper.logo(host)), 1f)
                    add(text(date, 18f, Gravity.CENTER).apply { setPadding(0, host.dpi(6f), 0, host.dpi(2f)) })
                }
            }
            banner()?.let { add(it) }
        }
    }

    /** "2 new messages" / "1 missed call" / "Call in progress" line under the date. */
    private fun banner(): View? {
        val (mask, msg) = when {
            inCall -> Icons.PHONE to "Call in progress"
            p.newMessages > 0 -> Icons.MESSAGES to "${p.newMessages} new message${if (p.newMessages > 1) "s" else ""}"
            p.missedCalls > 0 -> Icons.PHONE to "${p.missedCalls} missed call${if (p.missedCalls > 1) "s" else ""}"
            else -> return null
        }
        return row().apply {
            gravity = Gravity.CENTER
            addView(icon(mask), android.widget.LinearLayout.LayoutParams(host.dpi(18f), host.dpi(15f)).apply { marginEnd = host.dpi(6f) })
            addView(text(msg, 15f, bold = true))
        }
    }

    override fun onTick() {
        if (now("HH:mm") != shownMinute) host.refresh()
    }

    override fun onKey(k: Key): Boolean {
        when {
            k == Key.SOFT_L -> when {
                inCall -> host.push(CallScreen(host))
                p.newMessages > 0 -> host.push(ConversationsScreen(host))
                p.missedCalls > 0 -> { p.missedCalls = 0; host.push(CallLogScreen(host)) }
                else -> host.push(MainMenu(host))
            }
            k == Key.OK -> host.push(MainMenu(host))
            k in Shortcuts.keys -> Shortcuts.open(host, p.shortcut(k))
            k == Key.CALL -> { p.missedCalls = 0; host.push(CallLogScreen(host)) }
            k.digit != null -> host.push(DialerScreen(host, k.digit.toString()))
            else -> return k == Key.END
        }
        return true
    }
}

/** Configurable home-screen keys (Settings > Shortcuts). */
object Shortcuts {
    val keys = listOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.SOFT_R)

    val actions = listOf(
        "menu" to "Menu", "messages" to "Messages", "write" to "Write message", "contacts" to "Contacts",
        "calls" to "Recent calls", "dialer" to "Dial number", "camera" to "Camera", "gallery" to "Gallery",
        "clock" to "Clock", "alarm" to "Set alarm", "games" to "Games", "composer" to "Composer",
        "apps" to "Apps", "settings" to "Settings",
    )

    fun keyName(k: Key) = when (k) {
        Key.UP -> "Up"
        Key.DOWN -> "Down"
        Key.LEFT -> "Left"
        Key.RIGHT -> "Right"
        Key.SOFT_R -> "Right key"
        else -> k.name
    }

    fun default(k: Key): String = when (k) {
        Key.UP -> "messages"
        Key.DOWN -> "contacts"
        Key.LEFT -> "camera"
        Key.RIGHT -> "calls"
        else -> "apps"
    }

    fun label(host: MainActivity, id: String): String {
        if (id.startsWith("app:")) {
            val pkg = id.removePrefix("app:")
            return try {
                host.packageManager.getApplicationLabel(host.packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
        }
        return actions.firstOrNull { it.first == id }?.second ?: id
    }

    fun open(host: MainActivity, id: String) {
        when (id) {
            "menu" -> host.push(MainMenu(host))
            "messages" -> host.push(MessagesScreen(host))
            "write" -> host.writeMessage()
            "contacts" -> host.push(ContactsScreen(host))
            "calls" -> host.push(CallLogScreen(host))
            "dialer" -> host.push(DialerScreen(host, ""))
            "camera" -> host.push(CameraScreen(host))
            "gallery" -> host.push(GalleryScreen(host))
            "clock" -> host.push(ClockMenu(host))
            "alarm" -> host.push(TimeEntryScreen(host, alarm = true))
            "games" -> host.push(GamesMenu(host))
            "composer" -> host.push(ComposerMenu(host))
            "apps" -> host.push(AppsScreen(host))
            "settings" -> host.push(SettingsScreen(host))
            else -> if (id.startsWith("app:")) host.launchPackage(id.removePrefix("app:"))
        }
    }
}

class ShortcutsScreen(host: MainActivity) : ListScreen(host, "Shortcuts") {
    override fun loadItems() = Shortcuts.keys.map { k ->
        Item("${Shortcuts.keyName(k)}: ${Shortcuts.label(host, host.prefs.shortcut(k))}", Icons.KEY, sub = true) {
            host.push(SimpleList(host, Shortcuts.keyName(k)) {
                val apps = host.prefs.allowedApps.map { "app:$it" to Shortcuts.label(host, "app:$it") }.sortedBy { it.second.lowercase() }
                (Shortcuts.actions + apps).map { (id, label) ->
                    Item(label, if (id.startsWith("app:")) AppIcons.mask(host, id.removePrefix("app:")) else null) {
                        host.prefs.setShortcut(k, id)
                        host.pop()
                    }
                }
            })
        }
    }
}

// ------------------------------------------------------------------ grid & list

/** 3-column icon grid like the reference Menu and Apps screens. */
open class GridScreen(host: MainActivity, private val name: String) : Screen(host) {
    class Cell(val label: String, val icon: Mask?, val action: () -> Unit)

    protected var cells: List<Cell> = emptyList()
    protected var sel = 0
    private val cols = 3
    private val rows = 3
    open val emptyText = "(empty)"

    override val title get() = name

    open fun loadCells(): List<Cell> = emptyList()

    override fun onShow() {
        cells = loadCells()
        sel = sel.coerceIn(0, (cells.size - 1).coerceAtLeast(0))
    }

    override fun render(): View = column().apply {
        if (cells.isEmpty()) {
            gravity = Gravity.CENTER
            add(text(emptyText, 16f, Gravity.CENTER, multiline = true))
            return@apply
        }
        val totalRows = (cells.size + cols - 1) / cols
        val firstRow = (sel / cols - rows + 1).coerceAtLeast(0).coerceAtMost((totalRows - rows).coerceAtLeast(0))
        for (r in firstRow until firstRow + rows) {
            val line = row()
            for (c in 0 until cols) {
                val i = r * cols + c
                val cell = column(Gravity.CENTER_HORIZONTAL)
                cells.getOrNull(i)?.let { item ->
                    val selected = i == sel
                    val box = icon(item.icon, if (selected) host.bg else host.fg)
                    val p = host.dpi(5f)
                    box.setPadding(p, p, p, p)
                    if (selected) box.background = selection(7f)
                    cell.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        setMargins(host.dpi(8f), host.dpi(2f), host.dpi(8f), host.dpi(1f))
                    })
                    cell.add(text(item.label, 12f, Gravity.CENTER, bold = selected))
                }
                line.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            add(line, 1f)
        }
    }

    override fun onKey(k: Key): Boolean {
        val n = cells.size
        if (n == 0) return false
        when (k) {
            Key.LEFT -> sel = (sel - 1 + n) % n
            Key.RIGHT -> sel = (sel + 1) % n
            Key.UP -> sel = if (sel - cols >= 0) sel - cols else sel
            Key.DOWN -> sel = if (sel + cols < n) sel + cols else sel
            Key.SOFT_L, Key.CALL -> { cells[sel].action(); return true }
            else -> {
                val d = k.digit?.digitToIntOrNull() ?: return false
                if (d in 1..n) { sel = d - 1; cells[sel].action() }
                return true
            }
        }
        host.refresh()
        return true
    }
}

open class ListScreen(host: MainActivity, private val name: String) : Screen(host) {
    class Item(val label: String, val icon: Mask? = null, val sub: Boolean = false, val action: () -> Unit)

    protected var items: List<Item> = emptyList()
    protected var sel = 0
    open val emptyText = "(empty)"
    open val visibleRows = 5

    override val title get() = name

    open fun loadItems(): List<Item> = emptyList()

    /** Optional fixed row above the list (e.g. the contact search box). */
    open fun header(): View? = null

    override fun onShow() {
        items = loadItems()
        sel = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun reload() {
        onShow()
        host.refresh()
    }

    override fun render(): View = column().apply {
        val head = header()
        var rows = visibleRows
        if (head != null) {
            add(head, 1f)
            rows--
        }
        if (items.isEmpty()) {
            add(text(emptyText, 16f, Gravity.CENTER, multiline = true).apply { gravity = Gravity.CENTER }, rows.toFloat())
            return@apply
        }
        val start = (sel - rows / 2).coerceAtMost(items.size - rows).coerceAtLeast(0)
        for (slot in 0 until rows) {
            val i = start + slot
            val item = items.getOrNull(i)
            if (item == null) {
                add(View(host), 1f)
                continue
            }
            add(rowFor(item, i == sel), 1f)
        }
    }

    private fun rowFor(item: Item, selected: Boolean): View {
        val color = if (selected) host.bg else host.fg
        return row().apply {
            setPadding(host.dpi(6f), 0, host.dpi(6f), 0)
            if (selected) background = selection()
            if (item.icon != null) {
                addView(icon(item.icon, color), LinearLayout.LayoutParams(host.dpi(20f), host.dpi(18f)).apply { marginEnd = host.dpi(9f) })
            }
            addView(text(item.label, 17f, color = color, bold = selected), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected && item.sub) addView(icon(Icons.CHEVRON, color), LinearLayout.LayoutParams(host.dpi(9f), host.dpi(13f)))
        }
    }

    override fun onKey(k: Key): Boolean {
        val n = items.size
        when (k) {
            Key.UP -> if (n > 0) sel = (sel - 1 + n) % n
            Key.DOWN -> if (n > 0) sel = (sel + 1) % n
            Key.SOFT_L, Key.CALL -> { if (n > 0) items[sel].action(); return true }
            Key.RIGHT -> { if (n > 0 && items[sel].sub) items[sel].action(); return true }
            Key.LEFT -> { host.pop(); return true }
            else -> return false
        }
        host.refresh()
        return true
    }
}

class SimpleList(host: MainActivity, name: String, private val provider: () -> List<ListScreen.Item>) : ListScreen(host, name) {
    override fun loadItems() = provider()
}

// ------------------------------------------------------------------ main menu

class MainMenu(host: MainActivity) : GridScreen(host, "Menu") {
    override fun loadCells() = listOf(
        Cell("Phone", Icons.PHONE) { host.push(PhoneMenu(host)) },
        Cell("Messages", Icons.MESSAGES) { host.push(MessagesScreen(host)) },
        Cell("Contacts", Icons.CONTACTS) { host.push(ContactsScreen(host)) },
        Cell("Camera", Icons.CAMERA) { host.push(CameraScreen(host)) },
        Cell("Gallery", Icons.GALLERY) { host.push(GalleryScreen(host)) },
        Cell("Clock", Icons.CLOCK) { host.push(ClockMenu(host)) },
        Cell("Games", Icons.GAMES) { host.push(GamesMenu(host)) },
        Cell("Composer", Icons.NOTE) { host.push(ComposerMenu(host)) },
        Cell("Apps", Icons.APPS) { host.push(AppsScreen(host)) },
        Cell("Settings", Icons.SETTINGS) { host.push(SettingsScreen(host)) },
    )
}

// ------------------------------------------------------------------ calls

class PhoneMenu(host: MainActivity) : ListScreen(host, "Phone") {
    override fun loadItems() = listOf(
        Item("Dial number", Icons.PHONE) { host.push(DialerScreen(host, "")) },
        Item("Recent calls", Icons.CLOCK, sub = true) { host.push(CallLogScreen(host)) },
        Item("Contacts", Icons.CONTACTS, sub = true) { host.push(ContactsScreen(host)) },
    )
}

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
        add(text(number.toString(), if (number.length > 11) 24f else 36f, Gravity.END, bold = true, multiline = true))
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
            k == Key.SOFT_L || k == Key.CALL || k == Key.OK -> { confirm(); return true }
            else -> return false
        }
        host.refresh()
        return true
    }

    override fun onLongKey(k: Key): Boolean {
        when (k) {
            // the short press already typed the 0
            Key.D0 -> { if (number.endsWith('0')) number.setLength(number.length - 1); number.append('+') }
            Key.SOFT_R -> number.clear()
            else -> return false
        }
        host.refresh()
        return true
    }
}

class CallingScreen(host: MainActivity, private val number: String, private val name: String) : Screen(host) {
    override val leftSoft = "Options"
    override val rightSoft = "End"
    private val dial = Runnable {
        host.close(this)
        host.placeCall(number)
    }

    override fun render(): View = column(Gravity.CENTER).apply {
        add(icon(Icons.PHONE), height = host.dpi(64f))
        add(text("Calling...", 17f, Gravity.CENTER).apply { setPadding(0, host.dpi(8f), 0, 0) })
        add(text(name, 22f, Gravity.CENTER, bold = true))
    }

    override fun onShow() { host.handler.postDelayed(dial, 1300) }
    override fun onHide() { host.handler.removeCallbacks(dial) }

    override fun onKey(k: Key): Boolean {
        when (k) {
            Key.SOFT_R, Key.END -> host.close(this)
            Key.SOFT_L, Key.OK, Key.CALL -> { host.handler.removeCallbacks(dial); dial.run() }
            else -> {}
        }
        return true
    }
}

class ContactsScreen(
    host: MainActivity,
    private val onPick: ((String, String) -> Unit)? = null,
) : ListScreen(host, "Contacts") {
    private class Contact(val name: String, val number: String)

    private var all: List<Contact> = emptyList()
    private var shown: List<Contact> = emptyList()
    private val query = StringBuilder()

    override val rightSoft get() = if (query.isEmpty()) "Back" else "Clear"
    override val emptyText get() = when {
        !host.hasPermission(Manifest.permission.READ_CONTACTS) -> "No permission to read contacts"
        query.isNotEmpty() -> "No matches"
        else -> "No contacts"
    }

    override fun header(): View = row().apply {
        val p = host.dpi(6f)
        setPadding(p, 0, p, 0)
        background = GradientDrawable().apply {
            cornerRadius = host.dp(4f)
            setStroke(host.dpi(1.5f), host.fg)
        }
        addView(icon(Icons.SEARCH), LinearLayout.LayoutParams(host.dpi(15f), host.dpi(15f)).apply { marginEnd = p })
        addView(text(if (query.isEmpty()) "Search..." else query.toString(), 16f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
            Item(c.name, Icons.CONTACTS) {
                if (onPick != null) {
                    host.pop()
                    onPick.invoke(c.name, c.number)
                } else {
                    host.push(SimpleList(host, c.name) {
                        listOf(
                            Item("Call", Icons.PHONE) { host.call(c.number, c.name) },
                            Item("Send message", Icons.MESSAGES) { host.writeMessage(c.number) },
                            Item(c.number, null) {},
                        )
                    })
                }
            }
        }
    }

    override fun onKey(k: Key): Boolean {
        when {
            k == Key.CALL && onPick == null -> {
                shown.getOrNull(sel)?.let { host.call(it.number, it.name) }
                return true
            }
            k.digit?.isDigit() == true -> { query.append(k.digit); sel = 0; reload(); return true }
            k == Key.SOFT_R && query.isNotEmpty() -> { query.deleteCharAt(query.length - 1); sel = 0; reload(); return true }
            k == Key.LEFT || k == Key.RIGHT -> return true
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

class CallLogScreen(host: MainActivity) : ListScreen(host, "Recent calls") {
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
                    val who = if (name.isNullOrBlank()) num else name
                    out.add(Item("$mark $who", Icons.PHONE) { host.call(num, who) })
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }
}

// ------------------------------------------------------------------ messages

class MessagesScreen(host: MainActivity) : ListScreen(host, "Messages") {
    private val messengers = listOf(
        "com.facebook.orca" to "Messenger",
        "org.telegram.messenger" to "Telegram",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WA Business",
        "com.viber.voip" to "Viber",
        "org.thoughtcrime.securesms" to "Signal",
    )

    override fun loadItems(): List<Item> {
        val list = arrayListOf(
            Item("All Messages", Icons.MESSAGES, sub = true) { host.push(ConversationsScreen(host)) },
            Item("Write message", Icons.SMS_NEW) { host.writeMessage() },
        )
        messengers.filter { host.isInstalled(it.first) }.forEach { (pkg, label) ->
            list.add(Item(label, AppIcons.mask(host, pkg) ?: Icons.MESSAGES) { host.launchPackage(pkg) })
        }
        return list
    }
}

/** "Number or search" step after writing an SMS. */
class SendToScreen(host: MainActivity, private val onNumber: (String) -> Unit) : ListScreen(host, "Send to") {
    override fun loadItems() = listOf(
        Item("Enter number", Icons.PHONE) { host.replace(DialerScreen(host, "", "Number") { onNumber(it) }) },
        Item("Search contacts", Icons.CONTACTS) { host.replace(ContactsScreen(host) { _, num -> onNumber(num) }) },
    )
}

/** Classic multi-tap text entry: press 2 twice for "b", # cycles case. */
class TextEntryScreen(
    host: MainActivity,
    private val prompt: String,
    override val leftSoft: String = "Send",
    private val onDone: (String) -> Unit,
) : Screen(host) {
    private val buf = StringBuilder()
    private var beforeKey = ""
    private var lastKey: Key? = null
    private var lastTime = 0L
    private var cycle = 0
    private var mode = 1 // 0 = abc, 1 = Abc (sentence), 2 = ABC

    private val letters = mapOf(
        '1' to ".,?!'-1", '2' to "abc2", '3' to "def3", '4' to "ghi4", '5' to "jkl5",
        '6' to "mno6", '7' to "pqrs7", '8' to "tuv8", '9' to "wxyz9", '0' to " 0",
    )

    override val title get() = "$prompt  ${listOf("abc", "Abc", "ABC")[mode]}"
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
                beforeKey = buf.toString()
                val chars = letters.getValue(d)
                if (k == lastKey && now - lastTime < 1000 && buf.isNotEmpty()) {
                    cycle = (cycle + 1) % chars.length
                    buf.deleteCharAt(buf.length - 1)
                } else {
                    cycle = 0
                }
                val c = chars[cycle]
                buf.append(if (upper()) c.uppercaseChar() else c)
                lastKey = k
                lastTime = now
            }
            k == Key.HASH -> { mode = (mode + 1) % 3; lastKey = null }
            k == Key.STAR -> { buf.append('*'); lastKey = null }
            // Right only confirms the letter (so the same key can type the next one); 0 types a space.
            k == Key.RIGHT -> lastKey = null
            k == Key.SOFT_R && buf.isNotEmpty() -> { buf.deleteCharAt(buf.length - 1); lastKey = null }
            k == Key.SOFT_L || k == Key.OK -> {
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
        if (k == Key.SOFT_R) { buf.clear(); host.refresh(); return true }
        val d = k.digit ?: return false
        if (!d.isDigit()) return false
        // replace the letter the short press typed with the digit itself
        buf.setLength(0)
        buf.append(beforeKey)
        buf.append(d)
        lastKey = null
        host.refresh()
        return true
    }
}

// ------------------------------------------------------------------ clock

class ClockMenu(host: MainActivity) : ListScreen(host, "Clock") {
    override fun loadItems() = listOf(
        Item("Alarm clock", Icons.CLOCK) { host.push(TimeEntryScreen(host, alarm = true)) },
        Item("View alarms", Icons.CLOCK) { host.safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
        Item("Countdown timer", Icons.CLOCK) { host.push(TimeEntryScreen(host, alarm = false)) },
    )
}

/** Digit entry for an alarm time (HH:MM) or a countdown length in minutes. */
class TimeEntryScreen(host: MainActivity, private val alarm: Boolean) : Screen(host) {
    private val digits = StringBuilder()
    override val title = if (alarm) "Alarm time" else "Minutes"
    override val leftSoft = if (alarm) "OK" else "Start"
    override val rightSoft get() = if (digits.isEmpty()) "Back" else "Clear"
    private val max = if (alarm) 4 else 3

    override fun render(): View = column(Gravity.CENTER).apply {
        val shown = if (alarm) digits.toString().padEnd(4, '_').let { "${it.substring(0, 2)}:${it.substring(2)}" }
        else if (digits.isEmpty()) "_" else digits.toString()
        add(text(shown, 46f, Gravity.CENTER, bold = true))
    }

    private fun confirm() {
        if (alarm) {
            val h = digits.take(2).toString().toIntOrNull()
            val m = digits.drop(2).toString().toIntOrNull()
            if (digits.length != 4 || h == null || m == null || h > 23 || m > 59) { host.note("Invalid time"); return }
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Nokia alarm")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            host.pop()
            if (host.safeStart(i)) host.note("Alarm on\n%02d:%02d".format(h, m))
        } else {
            val m = digits.toString().toIntOrNull() ?: return
            if (m == 0) return
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, m * 60)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Nokia timer")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            host.pop()
            if (host.safeStart(i)) host.note("Timer set\n$m min")
        }
    }

    override fun onKey(k: Key): Boolean {
        when {
            k.digit?.isDigit() == true -> if (digits.length < max) digits.append(k.digit)
            k == Key.SOFT_R && digits.isNotEmpty() -> digits.deleteCharAt(digits.length - 1)
            k == Key.SOFT_L || k == Key.OK -> { confirm(); return true }
            k.digit != null -> return true
            else -> return false
        }
        host.refresh()
        return true
    }
}

// ------------------------------------------------------------------ apps

class AppsScreen(host: MainActivity) : GridScreen(host, "Apps") {
    override fun loadCells(): List<Cell> {
        val pm = host.packageManager
        val apps = host.prefs.allowedApps.mapNotNull { pkg ->
            try {
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                Cell(label, AppIcons.mask(host, pkg)) { host.launchPackage(pkg) }
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.label.lowercase() }
        return apps + Cell("More", Icons.APPS) { host.requirePin { host.push(AppPickerScreen(host)) } }
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
                Item("${if (pkg in allowed) "[x]" else "[ ]"} $label", AppIcons.mask(host, pkg)) {
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
        add(icon(Icons.LOCK), height = host.dpi(40f))
        add(text(if (pin.isEmpty()) "_" else "*".repeat(pin.length), 34f, Gravity.CENTER, bold = true))
    }

    override fun onKey(k: Key): Boolean {
        when {
            k.digit?.isDigit() == true -> if (pin.length < 8) pin.append(k.digit)
            k == Key.SOFT_R && pin.isNotEmpty() -> pin.deleteCharAt(pin.length - 1)
            k == Key.SOFT_L || k == Key.CALL || k == Key.OK -> {
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
    /** Opens a quick panel where Android has one; full settings screens are PIN-locked as an escape hatch. */
    private fun panelOrLocked(panel: String?, settings: String) {
        if (panel != null && Build.VERSION.SDK_INT >= 29) host.safeStart(Intent(panel))
        else host.requirePin { host.safeStart(Intent(settings)) }
    }

    override fun loadItems(): List<Item> {
        val p = host.prefs
        return listOf(
            Item("Wi-Fi", Icons.WIFI, sub = true) {
                panelOrLocked(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else null, Settings.ACTION_WIFI_SETTINGS)
            },
            Item("Bluetooth", Icons.BLUETOOTH, sub = true) { host.requirePin { host.safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
            Item("Sound", Icons.SOUND, sub = true) {
                panelOrLocked(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else null, Settings.ACTION_SOUND_SETTINGS)
            },
            Item("Display", Icons.DISPLAY, sub = true) { host.push(DisplaySettings(host)) },
            Item("Key tones: ${if (p.keySounds) "On" else "Off"}", Icons.SOUND) { p.keySounds = !p.keySounds; reload() },
            Item("Ringtone: ${p.ringtone.ifEmpty { "Phone default" }}", Icons.NOTE, sub = true) {
                host.push(SimpleList(host, "Ringtone") {
                    (p.tunes.map { it.first } + "").map { n ->
                        Item(n.ifEmpty { "Phone default" }, Icons.NOTE) {
                            p.ringtone = n
                            p.tunes.firstOrNull { it.first == n }?.let { Composer.play(Note.parseAll(it.second)) }
                            host.pop()
                        }
                    }
                })
            },
            Item("Shortcuts", Icons.KEY, sub = true) { host.push(ShortcutsScreen(host)) },
            Item(if (PhoneRole.isDefault(host)) "Phone app: Kippoks" else "Phone app: Other", Icons.PHONE, sub = true) {
                if (PhoneRole.isDefault(host)) host.requirePin { PhoneRole.openDefaultApps(host) } else PhoneRole.request(host)
            },
            Item("Allowed apps", Icons.APPS, sub = true) { host.requirePin { host.push(AppPickerScreen(host)) } },
            Item("Change code", Icons.KEY) {
                host.requirePin {
                    host.push(PinScreen(host, "New code") { a ->
                        host.push(PinScreen(host, "Verify code") { b ->
                            if (a == b) { p.pin = a; host.note("Code changed") } else host.note("Codes do not match")
                        })
                    })
                }
            },
            Item("Exit dumb mode", Icons.EXIT) { host.requirePin { host.exitDumbMode() } },
            Item("About", Icons.ABOUT) { host.push(NoteScreen(host, "Kippoks 3.0\nDefault code 0000", 0)) },
        )
    }
}

class DisplaySettings(host: MainActivity) : ListScreen(host, "Display") {
    private fun wallpaperName(w: String) = when (w) {
        "mountain" -> "Mountains"
        "clock" -> "Big clock"
        "photo" -> "Photo"
        else -> "Kippoks"
    }

    override fun loadItems(): List<Item> {
        val p = host.prefs
        return listOf(
            Item("Screen: ${host.palette.label}", Icons.DISPLAY) { host.setTheme(host.palette.next(), host.body); reload() },
            Item("Phone: ${host.body.label}", Icons.PHONE) { host.setTheme(host.palette, host.body.next()); reload() },
            Item("Wallpaper: ${wallpaperName(p.wallpaper)}", Icons.GALLERY) {
                val opts = listOf("logo", "mountain", "clock") + if (Wallpaper.hasPhoto(host)) listOf("photo") else emptyList()
                p.wallpaper = opts[(opts.indexOf(p.wallpaper) + 1) % opts.size]
                reload()
            },
            Item("Wallpaper from camera", Icons.CAMERA) { host.push(CameraScreen(host, forWallpaper = true)) },
            Item("Wallpaper from gallery", Icons.GALLERY) { host.push(GalleryScreen(host, forWallpaper = true)) },
            Item("Light: ${p.backlightSec}s", Icons.ABOUT) {
                val opts = listOf(5, 10, 15, 30, 60)
                p.backlightSec = opts[(opts.indexOf(p.backlightSec) + 1) % opts.size]
                host.wake()
                reload()
            },
            Item("B&W phone: ${if (p.grayscale) "On" else "Off"}", Icons.DISPLAY) {
                val want = !p.grayscale
                if (Grayscale.set(host, want)) {
                    p.grayscale = want
                    reload()
                } else {
                    host.push(NoteScreen(host, "Needs PC setup:\nadb shell pm grant\n${host.packageName}\nandroid.permission.\nWRITE_SECURE_SETTINGS", 0))
                }
            },
        )
    }
}
