# Nokia Mode — dumb-phone launcher for Android

Turns your Android phone into a Nokia 3310-style phone: a green backlit LCD, a T9 keypad that is always on screen, and nothing else unless you allow it.

## Features
- **Look:** a black or white Nokia body with a black screen bezel and a pixel font (Pixelify Sans, SIL OFL). Every screen has a status bar (title or signal, time, battery) and Select/Back soft keys.
- **Screen colors** (Settings → Display): Sage, Classic green, Black on white, White on black, Amber, Blue. **Phone body:** Black or White.
- **Idle screen:** pixel mountain wallpaper and the date, or a big clock. Soft keys **Menu / Apps**. Type digits to dial right away. ▲ Messages, ▼/▶ Contacts, ◀ Camera, CALL = recent calls.
- **Keypad:** two soft keys, a 4-way navigation key with OK in the middle, green call / red end keys, and 0–9 * #. Plays DTMF tones and vibrates. Long-press 0 for `+`.
- **Backlight:** the screen and keypad dim after 5–60 s and light up again on any key press.
- **Menu:** a 3×3 icon grid: Phone, Messages, Contacts, Camera, Gallery, Clock, Games, Apps, Settings.
- **Calls:** shows a Nokia-style "Calling… Mama" screen, then hands the call to your phone app (incoming calls are untouched).
- **Contacts:** T9 search (type `626` to find "Mama"). Call or send a message from there.
- **Messages:** All Messages (SMS inbox + multi-tap T9 writing), plus WhatsApp, Messenger, Telegram, Viber or Signal if they're installed, shown with pixelated icons.
- **Camera:** a live black & white dithered viewfinder on the LCD. Photos save in full quality to `Pictures/NokiaMode`. **Gallery** shows them in LCD style.
- **Apps:** a grid of the apps you allow, with pixelated icons. **More** (needs the code) picks which apps are allowed.
- **Settings:** Wi-Fi and Sound open Android's quick panels. Bluetooth and the full settings screens need the code.
- **Snake II**, alarm and countdown timer.
- **Exit dumb mode:** needs the security code. **Default code: `0000`.**

## Install
1. Download the APK: GitHub → **Actions** → *Build Nokia Mode APK* → latest run → **nokia-mode-apk** artifact.
2. Install it. Your phone will ask you to allow installing from this source.
3. Open **Nokia Mode** and allow the phone, contacts and call log permissions.
4. Press the Home button and pick **Nokia Mode** → *Always*. You can also go to Settings → Apps → Default apps → Home app.

## Make the whole phone black & white (optional)
Inside Nokia Mode the screen is already green and black. To turn **every other app** grayscale as well, run this once from a PC with USB debugging enabled:

```
adb shell pm grant com.philip.nokiamode android.permission.WRITE_SECURE_SETTINGS
```

Then go to Settings → **B&W phone: On**. Grayscale turns off automatically when you exit dumb mode.

## Limits (without root)
A normal app can't block everything. Someone can still swipe down the notification shade, or change the default Home app in Android Settings. For a lock you can't escape, use Android's *Digital Wellbeing* / *Focus mode* alongside this app, or set it up as a Device Owner kiosk (this needs a factory reset).

## Build locally
Open `nokia-dumb-mode/` in Android Studio, or run `./gradlew assembleRelease`.
