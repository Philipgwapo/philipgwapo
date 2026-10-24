package com.philip.nokiamode

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    lateinit var prefs: Prefs
        private set
    val handler = Handler(Looper.getMainLooper())

    private val stack = ArrayList<Screen>()
    val current: Screen? get() = stack.lastOrNull()

    var palette = Palette.SAGE
        private set
    var body = Body.BLACK
        private set

    private lateinit var lcd: LinearLayout
    private lateinit var statusLeft: FrameLayout
    private lateinit var statusTime: TextView
    private lateinit var statusBattery: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var softL: TextView
    private lateinit var softC: TextView
    private lateinit var softR: TextView
    private lateinit var keypad: Keypad

    var lit = true
        private set
    private val dimRunnable = Runnable { setLit(false) }
    private var tone: ToneGenerator? = null
    private var paused = false

    private val tick = object : Runnable {
        override fun run() {
            updateStatusTime()
            current?.onTick()
            handler.postDelayed(this, 1000)
        }
    }

    val fg: Int get() = if (lit) palette.fg else palette.dimFg
    val bg: Int get() = if (lit) palette.bg else palette.dimBg

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Fonts.init(this)
        palette = Palette.of(prefs.palette)
        body = Body.of(prefs.body)
        setContentView(buildLayout())
        tone = try { ToneGenerator(AudioManager.STREAM_DTMF, 60) } catch (e: RuntimeException) { null }
        push(SplashScreen(this))
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (prefs.grayscale) Grayscale.set(this, true)
        wake()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (paused) {
            paused = false
            current?.onShow()
            refresh()
        }
    }

    override fun onPause() {
        super.onPause()
        paused = true
        current?.onHide()
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing the system Home button while we're already the launcher returns to the idle screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) home()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------- theme

    fun setTheme(p: Palette, b: Body) {
        prefs.palette = p.name
        prefs.body = b.name
        palette = p
        body = b
        setContentView(buildLayout())
        refresh()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(body.top, body.bottom))
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(14f))
        }

        // earpiece slot
        val ear = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4f)
                setColor(if (body == Body.BLACK) 0xFF4A4A4A.toInt() else 0xFF3A3A3A.toInt())
                setStroke(dpi(1f), if (body == Body.BLACK) 0xFF777777.toInt() else 0xFFB0B0B0.toInt())
            }
        }
        root.addView(ear, LinearLayout.LayoutParams(dpi(64f), dpi(7f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dpi(8f)
        })

        // black screen bezel with the logo, as on both reference handsets
        val bezel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                setColor(0xFF0B0B0B.toInt())
                setStroke(dpi(2f), if (body == Body.BLACK) 0xFF3C3C3C.toInt() else 0xFFCFCFCF.toInt())
            }
            setPadding(dpi(14f), dpi(6f), dpi(14f), dpi(16f))
        }
        val logo = TextView(this).apply {
            text = "NOKIA"
            textSize = 19f
            letterSpacing = 0.08f
            typeface = Fonts.logo
            setTextColor(body.logo)
            gravity = Gravity.CENTER
        }
        bezel.addView(logo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpi(4f)
        })

        lcd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(9f), dpi(6f), dpi(9f), dpi(5f))
        }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLeft = FrameLayout(this)
        status.addView(statusLeft, LinearLayout.LayoutParams(0, dpi(22f), 1f))
        statusTime = lcdText("", 15f)
        status.addView(statusTime, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dpi(6f)
        })
        statusBattery = FrameLayout(this)
        status.addView(statusBattery, LinearLayout.LayoutParams(dpi(22f), dpi(12f)))
        lcd.addView(status)

        content = FrameLayout(this)
        lcd.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dpi(4f)
            bottomMargin = dpi(2f)
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softL = lcdText("", 17f, bold = true).apply { gravity = Gravity.START }
        softC = lcdText("", 13f).apply { gravity = Gravity.CENTER }
        softR = lcdText("", 17f, bold = true).apply { gravity = Gravity.END }
        footer.addView(softL, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(softC, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(softR, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        lcd.addView(footer)

        bezel.addView(lcd, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bezel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 50f))

        keypad = Keypad(this, body) { k, long -> press(k, long) }
        root.addView(keypad.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 50f).apply {
            topMargin = dpi(10f)
        })

        applyLight()
        return root
    }

    fun lcdText(s: String, sizeSp: Float = 17f, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        textSize = sizeSp
        typeface = if (bold) Fonts.pixelBold else Fonts.pixel
        setTextColor(fg)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun applyLight() {
        lcd.background = GradientDrawable().apply {
            cornerRadius = dp(6f)
            setColor(bg)
        }
        keypad.setLit(lit)
        window.attributes = window.attributes.apply {
            screenBrightness = if (lit) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 0.02f
        }
    }

    private fun setLit(on: Boolean) {
        if (lit == on) return
        lit = on
        applyLight()
        refresh()
    }

    /** Any interaction turns the backlight on and restarts the dim timer. */
    fun wake() {
        setLit(true)
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, prefs.backlightSec * 1000L)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- status bar

    private fun updateStatusTime() {
        val s = current
        val t = if (s == null || !s.showTime) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        if (statusTime.text.toString() != t) statusTime.text = t
    }

    fun batteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ((pct + 24) / 25).coerceIn(0, 4)
    }

    fun signalLevel(): Int = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= 28) tm.signalStrength?.level ?: 0 else 4
    } catch (e: Exception) {
        0
    }

    // ---------------------------------------------------------------- screens

    fun push(s: Screen) {
        current?.onHide()
        stack.add(s)
        show()
    }

    fun pop() {
        if (stack.size <= 1) return
        current?.onHide()
        stack.removeAt(stack.size - 1)
        show()
    }

    /** Pops [s] only if it is still on top (for delayed auto-close). */
    fun close(s: Screen) {
        if (current === s) pop()
    }

    fun replace(s: Screen) {
        current?.onHide()
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        stack.add(s)
        show()
    }

    fun home() {
        current?.onHide()
        stack.clear()
        stack.add(HomeScreen(this))
        show()
    }

    private fun show() {
        current?.onShow()
        refresh()
    }

    /** Re-renders the top screen without re-running its onShow. */
    fun refresh() {
        val s = current ?: return
        content.removeAllViews()
        content.addView(s.render(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        statusLeft.removeAllViews()
        if (s.title.isEmpty()) {
            statusLeft.addView(SignalView(this, signalLevel(), fg), FrameLayout.LayoutParams(dpi(24f), dpi(14f), Gravity.CENTER_VERTICAL))
        } else {
            statusLeft.addView(lcdText(s.title, 16f, bold = true), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }
        statusTime.setTextColor(fg)
        statusTime.text = ""
        updateStatusTime()
        statusBattery.removeAllViews()
        statusBattery.addView(BatteryView(this, batteryLevel(), fg))

        softL.text = s.leftSoft
        softC.text = s.centerSoft
        softR.text = s.rightSoft
        listOf(softL, softC, softR).forEach { it.setTextColor(fg) }
    }

    fun note(text: String, ms: Long = 1500) = push(NoteScreen(this, text, ms))

    // ---------------------------------------------------------------- keys

    fun press(k: Key, long: Boolean = false) {
        wake()
        feedback(k)
        val s = current ?: return
        var handled = (long && s.onLongKey(k)) || s.onKey(k)
        // The centre key acts as "Select" unless the screen gives it its own meaning.
        if (!handled && k == Key.OK) handled = s.onKey(Key.SOFT_L)
        if (!handled) {
            when (k) {
                Key.END -> home()
                Key.SOFT_R -> pop()
                else -> {}
            }
        }
    }

    private fun feedback(k: Key) {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (!prefs.keySounds) return
        val t = when (k.digit) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        tone?.startTone(t, 70)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Key.ofDigit('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_STAR -> Key.STAR
            KeyEvent.KEYCODE_POUND -> Key.HASH
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.OK
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> Key.SOFT_R
            KeyEvent.KEYCODE_CALL -> Key.CALL
            KeyEvent.KEYCODE_ENDCALL -> Key.END
            else -> null
        } ?: return super.onKeyDown(keyCode, event)
        press(k)
        return true
    }

    @Deprecated("Back is handled as the right soft key")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        press(Key.SOFT_R)
    }

    // ---------------------------------------------------------------- permissions

    private fun requestMissingPermissions() {
        val wanted = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
        val missing = wanted.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    /** Asks for [perms]; the current screen's onShow runs again once the user answers. */
    fun ask(vararg perms: String) {
        val missing = perms.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        current?.onShow()
        refresh()
    }

    fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- actions

    fun safeStart(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        note("Not available")
        false
    } catch (e: SecurityException) {
        note("Not allowed")
        false
    }

    /** Shows the Nokia "Calling..." screen, then hands the call to the system phone app. */
    fun call(number: String, name: String? = null) {
        if (number.isBlank()) return
        push(CallingScreen(this, number, name ?: lookupName(number) ?: number))
    }

    fun placeCall(number: String) {
        val action = if (hasPermission(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        safeStart(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
    }

    private fun lookupName(number: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun sms(number: String, body: String = "") {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
        if (body.isNotEmpty()) i.putExtra("sms_body", body)
        safeStart(i)
    }

    fun openMessagesApp() {
        val pkg = android.provider.Telephony.Sms.getDefaultSmsPackage(this)
        val i = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun launchPackage(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) note("App not found") else safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isInstalled(pkg: String): Boolean = packageManager.getLaunchIntentForPackage(pkg) != null

    /** Runs [then] only after the user types the security code. */
    fun requirePin(then: () -> Unit) {
        push(PinScreen(this, "Security code") { entered ->
            if (entered == prefs.pin) then() else note("Wrong code")
        })
    }

    fun exitDumbMode() {
        Grayscale.set(this, false)
        prefs.grayscale = false
        val opened = try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            true
        } catch (e: ActivityNotFoundException) {
            safeStart(Intent(Settings.ACTION_SETTINGS))
        }
        if (opened) home()
    }
}

/** System-wide black & white via the hidden "simulate monochromacy" setting (needs adb grant). */
object Grayscale {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    fun set(a: Activity, on: Boolean): Boolean = try {
        Settings.Secure.putInt(a.contentResolver, MODE, 0)
        Settings.Secure.putInt(a.contentResolver, ENABLED, if (on) 1 else 0)
        true
    } catch (e: SecurityException) {
        false
    }
}
