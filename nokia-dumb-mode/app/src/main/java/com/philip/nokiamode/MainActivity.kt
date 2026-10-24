package com.philip.nokiamode

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

class MainActivity : Activity() {
    lateinit var prefs: Prefs
        private set
    val handler = Handler(Looper.getMainLooper())

    private val stack = ArrayList<Screen>()
    val current: Screen? get() = stack.lastOrNull()

    private lateinit var lcd: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var indicatorView: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Lcd.BODY_TOP, Lcd.BODY_BOTTOM))
            val p = dpi(14f)
            setPadding(p, dpi(10f), p, p)
        }

        val logo = TextView(this).apply {
            text = "NOKIA"
            textSize = 22f
            letterSpacing = 0.25f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setTextColor(0xFFC9D2DC.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(logo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpi(8f)
        })

        lcd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dpi(10f)
            setPadding(p, dpi(6f), p, dpi(6f))
        }
        header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleView = lcdText("", 16f).apply { gravity = Gravity.CENTER }
        indicatorView = lcdText("", 14f).apply { gravity = Gravity.END }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(indicatorView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        lcd.addView(header)

        content = FrameLayout(this)
        lcd.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        softL = lcdText("", 16f).apply { gravity = Gravity.START }
        softC = lcdText("", 13f).apply { gravity = Gravity.CENTER }
        softR = lcdText("", 16f).apply { gravity = Gravity.END }
        footer.addView(softL, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(softC, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(softR, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        lcd.addView(footer)

        root.addView(lcd, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 44f))

        keypad = Keypad(this) { k, long -> press(k, long) }
        root.addView(keypad.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 56f).apply {
            topMargin = dpi(12f)
        })

        applyLight()
        return root
    }

    fun lcdText(s: String, sizeSp: Float = 18f): TextView = TextView(this).apply {
        text = s
        textSize = sizeSp
        typeface = Lcd.font
        setTextColor(Lcd.FG)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    val lcdBg: Int get() = if (lit) Lcd.BG_LIT else Lcd.BG_DIM

    private fun applyLight() {
        lcd.background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            setColor(lcdBg)
            setStroke(dpi(4f), Lcd.BEZEL)
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
        titleView.text = s.title
        indicatorView.text = s.indicator
        header.visibility = if (s.title.isEmpty() && s.indicator.isEmpty()) View.GONE else View.VISIBLE
        softL.text = s.leftSoft
        softC.text = s.centerSoft
        softR.text = s.rightSoft
    }

    fun note(text: String, ms: Long = 1500) = push(NoteScreen(this, text, ms))

    // ---------------------------------------------------------------- keys

    fun press(k: Key, long: Boolean = false) {
        wake()
        feedback(k)
        val s = current ?: return
        val handled = (long && s.onLongKey(k)) || s.onKey(k)
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.SOFT_L
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

    // ---------------------------------------------------------------- actions

    private fun requestMissingPermissions() {
        val wanted = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        current?.onShow()
        refresh()
    }

    fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

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

    fun call(number: String) {
        if (number.isBlank()) return
        val action = if (hasPermission(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        safeStart(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
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

    /** Runs [then] only after the user types the security code. */
    fun requirePin(then: () -> Unit) {
        push(PinScreen(this, "Security code:") { entered ->
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

    fun isOn(a: Activity) = Settings.Secure.getInt(a.contentResolver, ENABLED, 0) == 1

    fun set(a: Activity, on: Boolean): Boolean = try {
        Settings.Secure.putInt(a.contentResolver, MODE, 0)
        Settings.Secure.putInt(a.contentResolver, ENABLED, if (on) 1 else 0)
        true
    } catch (e: SecurityException) {
        false
    }
}
