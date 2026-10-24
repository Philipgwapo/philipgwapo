# Kippoks — dumb-phone launcher for Android

Turns your Android phone into a Nokia 3310-style phone: a backlit pixel LCD, a keypad that is always on screen, built-in calling and texting, and nothing else unless you allow it.

## Features
- **Look:** a black or white phone body with the Kippoks logo, a pixel font (Pixelify Sans, SIL OFL), and a status bar on every screen.
- **Screen colors** (Settings → Display): Sage, Classic green, Black on white, White on black, Amber, Blue.
- **Wallpaper:** Kippoks logo, mountains, big clock, or your own photo (Display → Wallpaper from camera / gallery), turned into LCD pixels.
- **Keys respond instantly** (on touch, not on release). Holding a key in a game auto-repeats.
- **Calls inside Kippoks:** set Kippoks as your Phone app (Settings → Phone app) for Nokia-style incoming (Answer/Reject), outgoing and in-call screens (Mute, Speaker, Hold, keypad tones), with your Composer tune as the ringtone. The screen turns off at your ear. Switching back needs the code.
- **Texts inside Kippoks:** read all your conversations (old ones too), reply, and write new texts. Your phone's Messages app stays the default in the background, so picture and group messages keep working there.
- **Composer:** the 3310 composer. 1–7 notes, 0 pause, 8/9 shorter/longer, * octave, # sharp, OK plays. Save tunes and set one as the ringtone.
- **Games:** Snake II, Space Impact, Blocks, Brick breaker, Pairs.
- **Shortcuts** (Settings → Shortcuts): choose what ▲ ▼ ◀ ▶ and the right soft key open on the home screen, including any allowed app.
- **Typing:** multi-tap T9. ▶ confirms a letter without adding a space; 0 is space; # changes case; hold a key for its digit.
- **Home screen** shows "2 new messages" / "1 missed call"; the left soft key turns into **Read** / **Show**.
- **Camera & Gallery** with a pixel viewfinder; photos save in full quality to `Pictures/NokiaMode`.
- **Apps:** a grid of the apps you allow. **More** (needs the code) picks which apps are allowed.
- **Exit:** Settings → Exit dumb mode, with the security code. **Default code: `0000`.**

## Install
1. Download the APK: GitHub → **Actions** → *Build Kippoks APK* → latest run → **kippoks-apk** artifact.
2. Install it. Your phone will ask you to allow installing from this source.
3. Open **Kippoks** and allow the phone, contacts and call log permissions.
4. Press the Home button and pick **Kippoks** → *Always*. You can also go to Settings → Apps → Default apps → Home app.

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
