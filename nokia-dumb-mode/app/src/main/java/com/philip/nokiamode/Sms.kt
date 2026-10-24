package com.philip.nokiamode

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-wide change events from receivers to whatever screen is showing. */
object AppEvents {
    var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun post(event: String) = main.post { listener?.invoke(event) }
}

/**
 * Texts are read from and sent through Android's SMS store while the phone's own Messages app stays
 * the default SMS app, so picture and group messages keep working there.
 */
object SmsRepo {
    val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)

    class Conversation(val thread: Long, val address: String, val snippet: String, val date: Long, val unread: Boolean)
    class Message(val body: String, val date: Long, val incoming: Boolean)

    fun conversations(ctx: Context, prefs: Prefs): List<Conversation> {
        val out = LinkedHashMap<Long, Conversation>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC",
            )?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows++ < 3000) {
                    val thread = c.getLong(0)
                    if (thread in out) continue
                    val date = c.getLong(3)
                    val incoming = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX
                    out[thread] = Conversation(thread, c.getString(1) ?: "", (c.getString(2) ?: "").replace('\n', ' '), date,
                        incoming && date > prefs.threadSeen(thread))
                }
            }
        } catch (e: SecurityException) {
        }
        return out.values.toList()
    }

    fun messages(ctx: Context, thread: Long): List<Message> {
        val out = ArrayList<Message>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                Telephony.Sms.THREAD_ID + "=?", arrayOf(thread.toString()), Telephony.Sms.DATE + " DESC LIMIT 200",
            )?.use { c ->
                while (c.moveToNext()) out.add(Message(c.getString(0) ?: "", c.getLong(1), c.getInt(2) == Telephony.Sms.MESSAGE_TYPE_INBOX))
            }
        } catch (e: SecurityException) {
        }
        return out.reversed()
    }

    @Suppress("DEPRECATION")
    fun send(ctx: Context, number: String, body: String): Boolean = try {
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(body)
        val sent = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SmsSentReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val intents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sent) } }
        sms.sendMultipartTextMessage(number, null, parts, intents, null)
        true
    } catch (e: Exception) {
        false
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = Prefs(context)
        prefs.newMessages = prefs.newMessages + 1
        AppEvents.post("sms")
        // The default SMS app stores the message a moment later; refresh again once it's there.
        Handler(Looper.getMainLooper()).postDelayed({ AppEvents.post("sms") }, 1500)
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppEvents.post(if (resultCode == Activity.RESULT_OK) "sms_sent" else "sms_failed")
    }
}

fun MainActivity.sendSms(number: String, body: String) {
    if (!hasPermission(Manifest.permission.SEND_SMS)) {
        ask(*SmsRepo.permissions)
        note("Allow SMS, then\ntry again")
        return
    }
    note(if (SmsRepo.send(this, number, body)) "Sending..." else "Message failed", 900)
}

class ConversationsScreen(host: MainActivity) : ListScreen(host, "Messages") {
    override val visibleRows = 5
    override val emptyText get() =
        if (!host.hasPermission(Manifest.permission.READ_SMS)) "Allow SMS access\nto see messages" else "No messages"

    override fun onShow() {
        host.prefs.newMessages = 0
        if (SmsRepo.permissions.any { !host.hasPermission(it) }) host.ask(*SmsRepo.permissions)
        super.onShow()
    }

    override fun loadItems(): List<Item> {
        val write = Item("Write message", Icons.SMS_NEW) { host.writeMessage() }
        if (!host.hasPermission(Manifest.permission.READ_SMS)) return listOf(write)
        return listOf(write) + SmsRepo.conversations(host, host.prefs).map { c ->
            val name = host.lookupName(c.address) ?: c.address
            Item((if (c.unread) "* " else "") + name, Icons.MESSAGES, sub = true) { host.push(ThreadScreen(host, c.thread, c.address, name)) }
        }
    }
}

fun MainActivity.writeMessage(to: String? = null) {
    push(TextEntryScreen(this, "Message") { body ->
        if (to != null) sendSms(to, body)
        else push(SendToScreen(this) { number -> sendSms(number, body) })
    })
}

/** One message per page, newest last, like the 3310 inbox. */
class ThreadScreen(host: MainActivity, private val thread: Long, private val address: String, private val name: String) : Screen(host) {
    private var msgs: List<SmsRepo.Message> = emptyList()
    private var idx = 0

    override val title get() = name
    override val leftSoft = "Reply"
    override val centerSoft get() = if (msgs.isEmpty()) "" else "${idx + 1}/${msgs.size}"

    override fun onShow() {
        val wasLast = msgs.isEmpty() || idx == msgs.size - 1
        msgs = SmsRepo.messages(host, thread)
        if (wasLast) idx = msgs.size - 1
        idx = idx.coerceIn(0, (msgs.size - 1).coerceAtLeast(0))
        msgs.lastOrNull()?.let { host.prefs.setThreadSeen(thread, it.date) }
    }

    override fun render(): View = column().apply {
        val m = msgs.getOrNull(idx)
        if (m == null) {
            add(text("No messages", 16f, Gravity.CENTER))
            return@apply
        }
        val time = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(m.date))
        add(text((if (m.incoming) name else "You") + "  " + time, 13f, bold = true))
        add(text(m.body, 17f, multiline = true).apply { setPadding(0, host.dpi(4f), 0, 0) }, 1f)
    }

    override fun onKey(k: Key): Boolean {
        when (k) {
            Key.UP, Key.LEFT -> if (idx > 0) idx--
            Key.DOWN, Key.RIGHT -> if (idx < msgs.size - 1) idx++
            Key.SOFT_L, Key.OK -> { host.writeMessage(address); return true }
            Key.CALL -> { host.call(address, name); return true }
            else -> return false
        }
        host.refresh()
        return true
    }
}
