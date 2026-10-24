# Nokia Mode — dumb-phone launcher for Android

Turns your Android phone into a Nokia 3310-style phone: a green backlit LCD, a T9 keypad that is always on screen, and nothing else unless you allow it.

## Features
- **Idle screen:** NOKIA logo, big clock, date, and signal/battery bars. Type digits to dial right away.
- **Menu**, one item per page with pixel icons: Messages, Names, Call register, Games (Snake II), Clock, Apps, Settings.
- **Keypad:** two soft keys, ▲/▼, CALL/END and 0–9 * #. Plays DTMF tones and vibrates. Long-press 0 for `+`, long-press Clear to erase everything.
- **Backlight:** the LCD and keypad glow green, then dim after 5–60 s (set in Settings). Any key lights them up again.
- **Names:** T9 search (type `564` to find "John"). Then call, or write a message.
- **Messages:** multi-tap T9 text entry (`#` switches abc/Abc/ABC). Sending opens your SMS app with the text filled in. Inbox opens your SMS app.
- **Clock:** alarm and countdown timer, using your phone's clock app.
- **Apps:** only the apps you allow (WhatsApp, Maps…). Changing the list needs the security code.
- **Snake II:** use 2/4/6/8 or ▲▼, 5 to pause. Keeps a high score.
- **Exit dumb mode:** needs the security code. **Default code: `0000`.** Change it in Settings → Change code.

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
