package com.philip.nokiamode

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.View

/** Live calls handed to us by Android while Kippoks is the default Phone app. */
object CallManager {
    val calls = ArrayList<Call>()
    var service: KippoksInCallService? = null
    var audio: CallAudioState? = null
    var listener: (() -> Unit)? = null

    @Suppress("DEPRECATION")
    fun state(c: Call): Int = if (Build.VERSION.SDK_INT >= 31) c.details.state else c.state

    /** The call the screen should show: a ringing one first, then the live one. */
    fun primary(): Call? =
        calls.firstOrNull { state(it) == Call.STATE_RINGING }
            ?: calls.firstOrNull { state(it) in listOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING) }
            ?: calls.firstOrNull { state(it) != Call.STATE_DISCONNECTED }

    fun number(c: Call): String = c.details.handle?.schemeSpecificPart ?: ""

    fun changed() = listener?.invoke()
}

class KippoksInCallService : InCallService() {
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (CallManager.calls.none { CallManager.state(it) == Call.STATE_RINGING }) Ringer.stop(this@KippoksInCallService)
            CallNotification.update(this@KippoksInCallService)
            CallManager.changed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        CallManager.calls.add(call)
        call.registerCallback(callback)
        if (CallManager.state(call) == Call.STATE_RINGING) Ringer.start(this)
        CallNotification.update(this)
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SHOW_CALL, true))
        } catch (e: Exception) {
            // Background starts can be blocked; the full-screen notification covers that case.
        }
        CallManager.changed()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        CallManager.calls.remove(call)
        if (call.details.disconnectCause?.code == DisconnectCause.MISSED) {
            val p = Prefs(this)
            p.missedCalls = p.missedCalls + 1
        }
        if (CallManager.calls.none { CallManager.state(it) == Call.STATE_RINGING }) Ringer.stop(this)
        CallNotification.update(this)
        CallManager.changed()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallManager.audio = audioState
        CallManager.changed()
    }
}

/** Plays the Composer ringtone (or the system one), respecting silent and vibrate modes. */
@Suppress("DEPRECATION")
object Ringer {
    private var system: Ringtone? = null
    private var vibrating = false

    fun start(ctx: Context) {
        stop(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = am.ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
            vibrating = true
        }
        if (mode != AudioManager.RINGER_MODE_NORMAL) return
        val prefs = Prefs(ctx)
        val tune = prefs.tunes.firstOrNull { it.first == prefs.ringtone }
        if (tune != null) {
            Composer.play(Note.parseAll(tune.second), loop = true, ringtone = true)
        } else {
            system = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
    }

    fun stop(ctx: Context) {
        Composer.stop()
        system?.stop()
        system = null
        if (vibrating) {
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            vibrating = false
        }
    }
}

/** Full-screen incoming-call notification, so calls show even when the phone is locked or asleep. */
object CallNotification {
    private const val CHANNEL = "calls"
    private const val ID = 7

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val call = CallManager.primary()
        if (call == null) {
            nm.cancel(ID)
            return
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val ringing = CallManager.state(call) == Call.STATE_RINGING
        fun open(code: Int, extra: String?) = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SHOW_CALL, true).apply { if (extra != null) putExtra(MainActivity.EXTRA_CALL_ACTION, extra) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = CallManager.number(call).ifEmpty { "Unknown" }
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (ringing) "Incoming call" else "Call in progress")
            .setContentText(who)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open(1, null))
        if (ringing) {
            b.setFullScreenIntent(open(1, null), true)
            b.addAction(Notification.Action.Builder(null, "Answer", open(2, "answer")).build())
            b.addAction(Notification.Action.Builder(null, "Reject", open(3, "reject")).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "End", open(3, "reject")).build())
        }
        try {
            nm.notify(ID, b.build())
        } catch (e: SecurityException) {
            // notifications not allowed; the in-app screen still works while Kippoks is open
        }
    }
}

object PhoneRole {
    fun isDefault(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == ctx.packageName
    } catch (e: Exception) {
        false
    }

    fun request(a: Activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = a.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                @Suppress("DEPRECATION")
                a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 7)
                return
            }
        }
        a.startActivity(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, a.packageName))
    }

    /** Hands the Phone role back: Android has no "un-set", so open the default-apps screen. */
    fun openDefaultApps(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            a.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

@Suppress("DEPRECATION")
class CallScreen(host: MainActivity) : Screen(host) {
    private val dtmf = StringBuilder()
    private var shownSecond = -1L

    private val call: Call? get() = CallManager.primary()
    private val ringing get() = call?.let { CallManager.state(it) == Call.STATE_RINGING } == true

    override val leftSoft get() = if (ringing) "Answer" else "Options"
    override val rightSoft get() = if (ringing) "Reject" else "End"

    private fun status(c: Call): String = when (CallManager.state(c)) {
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling..."
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_ACTIVE -> elapsed(c)
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
        else -> "..."
    }

    private fun elapsed(c: Call): String {
        val start = c.details.connectTimeMillis
        if (start <= 0) return "00:00"
        val s = (System.currentTimeMillis() - start) / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun render(): View = column(Gravity.CENTER).apply {
        val c = call
        if (c == null) {
            add(text("Call ended", 20f, Gravity.CENTER, bold = true))
            return@apply
        }
        val number = CallManager.number(c)
        val name = host.lookupName(number) ?: number.ifEmpty { "Unknown" }
        add(icon(Icons.PHONE), height = host.dpi(56f))
        add(text(status(c), 17f, Gravity.CENTER).apply { setPadding(0, host.dpi(6f), 0, 0) })
        add(text(name, 22f, Gravity.CENTER, bold = true))
        if (name != number && number.isNotEmpty()) add(text(number, 14f, Gravity.CENTER))
        val a = CallManager.audio
        val flags = listOfNotNull(
            if (a?.isMuted == true) "Muted" else null,
            if (a?.route == CallAudioState.ROUTE_SPEAKER) "Speaker" else null,
            if (CallManager.calls.size > 1) "${CallManager.calls.size} calls" else null,
        )
        if (flags.isNotEmpty()) add(text(flags.joinToString("  "), 13f, Gravity.CENTER))
        if (dtmf.isNotEmpty()) add(text(dtmf.toString(), 18f, Gravity.CENTER, bold = true))
    }

    override fun onTick() {
        val c = call ?: return
        if (CallManager.state(c) == Call.STATE_ACTIVE) {
            val s = (System.currentTimeMillis() - c.details.connectTimeMillis) / 1000
            if (s != shownSecond) {
                shownSecond = s
                host.refresh()
            }
        }
    }

    fun answer() {
        call?.answer(VideoProfile.STATE_AUDIO_ONLY)
        Ringer.stop(host)
    }

    fun hangUp() {
        val c = call ?: return
        if (CallManager.state(c) == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
        Ringer.stop(host)
    }

    private fun options() {
        val svc = CallManager.service ?: return
        val c = call ?: return
        host.push(SimpleList(host, "Options") {
            val a = CallManager.audio
            val muted = a?.isMuted == true
            val speaker = a?.route == CallAudioState.ROUTE_SPEAKER
            val held = CallManager.state(c) == Call.STATE_HOLDING
            listOf(
                ListScreen.Item(if (muted) "Unmute" else "Mute", Icons.SOUND) { svc.setMuted(!muted); host.pop() },
                ListScreen.Item(if (speaker) "Speaker off" else "Speaker on", Icons.SOUND) {
                    svc.setAudioRoute(if (speaker) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
                    host.pop()
                },
                ListScreen.Item(if (held) "Resume" else "Hold", Icons.PHONE) { if (held) c.unhold() else c.hold(); host.pop() },
                ListScreen.Item("End call", Icons.EXIT) { host.pop(); hangUp() },
            )
        })
    }

    override fun onKey(k: Key): Boolean {
        val c = call
        when {
            c == null -> host.close(this)
            ringing && (k == Key.SOFT_L || k == Key.CALL || k == Key.OK) -> answer()
            k == Key.SOFT_R || k == Key.END -> hangUp()
            k == Key.SOFT_L -> options()
            k.digit != null && CallManager.state(c) == Call.STATE_ACTIVE -> {
                c.playDtmfTone(k.digit)
                host.handler.postDelayed({ c.stopDtmfTone() }, 160)
                dtmf.append(k.digit)
                host.refresh()
            }
        }
        return true
    }
}
