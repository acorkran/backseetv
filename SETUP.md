# BackSeat TV — install and setup

BackSeat TV is **one Android app** (package `app.anancy`; "Anancy" is the codename). The same APK
runs in two roles, chosen the first time you open it:

- **TV (player)** — the screen that plays your shows: a tablet, an old phone, any Android 8+ device.
- **Remote** — your phone. The phone also carries the **Wear OS watch remote** and the **Android
  Auto** car dash; neither is a separate app.

Everything is offline: no internet, no accounts, no cloud.

There are two ways to set it up:

| | **No computer** (Part A) | **With adb** (Part B) |
|---|---|---|
| Install | Download the APK and open it | `adb install` |
| Locking the TV | Android's own **screen pinning**. Good, but a determined kid can escape it if the TV has no screen lock | **Kiosk**: the show *is* the home screen; Home, Back and Recents are blocked; it boots straight into the show |
| Permissions | You tap "Allow" a few times | Granted silently |
| Watch remote | Not yet (it will arrive with the Play Store version) | Yes |
| Android Auto | Yes, with one developer toggle | Yes, with no toggle (see B5) |

Start with Part A. You can move a TV to Part B later without reinstalling.

---

## Part A — no computer

### A1. Install the app (TV and phone)

1. On each device, download `BackSeatTV-<version>.apk` from the repo's **Releases** page.
2. Open the file. Android will ask to allow installs from your browser or file manager — allow it
   for that app only.
3. If Play Protect warns about an unknown app, choose **Install anyway**. It warns because the app
   isn't from the Play Store.

### A2. Set up the TV

1. Open **BackSeat TV** and choose **This is the TV (player)**.
2. Allow **Nearby devices** (Bluetooth) when asked. This is how the remotes reach it.
3. **All files access**: the TV shows *"BackSeat TV can't read your storage"*. Tap **Open set-up
   check**, then **All files access**, and turn it on for BackSeat TV.
4. **Add your shows.** Use a USB cable to a computer, a file manager, or an SD card. Put
   **one folder per show** inside a top-level folder called `Anancy`:

   ```
   Anancy/
     Bluey/         Bluey S01E01.mp4, Bluey S01E02.mp4 ...
     Ms Rachel/     ...
   ```

   - It finds `Anancy` (or `KidsTV` / `Kidodi`) on internal storage **and** on any SD card.
   - Your shows are somewhere else? The set-up check has **Media folder** to point it there.
   - Files play in name order. `.mp4`, `.mkv` and `.m4v` are supported.
5. The library appears and starts playing, **muted**. You get a **Shuffle All** playlist plus one
   playlist per show.

### A3. Lock it (screen pinning)

1. Get into the grown-up menu: **tap with four fingers, four times, within 3 seconds**, anywhere on
   the show.
2. Copy the code shown on screen (it changes every time; a toddler can't read it in time).
3. **Settings → Pin the screen → on**.

While pinned, Home, Recents and the notification shade do nothing. The four-finger taps still get
you out.

**Give the TV a screen lock** (PIN or pattern in Android Settings). Android's own unpin shortcut —
hold Back + Recents — then asks for that PIN, so the kid can't use it.

### A4. Set up the phone remote

1. Open **BackSeat TV** on the phone, choose **This is a Remote**, and allow **Nearby devices**.
2. The TV shows up by itself over Bluetooth within a few seconds. There's nothing to pair and no
   network needed, so it works in the car.
3. Nothing found? Tap **Run the set-up check**. It names what's wrong (Bluetooth off, a denied
   permission, the other device not set as the TV) and offers the fix.
4. When both devices are on the same Wi-Fi, the remote also uses Wi-Fi automatically. That's
   faster, and it's what the live camera and uploads use.
5. If Bluetooth can't work at all, pair by QR. On the TV, open the set-up check (grown-up menu →
   Settings → **Set-up check**) and tap **Show pairing QR**. On the phone, scan it from the
   remote's set-up check with the rear camera.

### A5. Android Auto

Android Auto only lists apps from the Play Store unless you tell it otherwise. On the phone:

1. Open **Settings → Connected devices → Android Auto**.
2. Scroll to the bottom and tap **Version** about 10 times. Accept the developer-settings prompt.
3. Tap **⋮ → Developer settings → Unknown sources → on**.
4. Plug into the car. **BackSeat TV** is in the media apps.

The dash shows what's playing on the TV and controls it. It plays no sound of its own.

> **If BackSeat TV vanishes from the car after an Android Auto update:** the update turned
> Unknown sources back off. Tapping the old icon can crash Android Auto to a blank screen.
> Repeat steps 1–3 (or use B5 so it never happens again).

---

## Part B — with adb (full kiosk, watch, no Android Auto toggle)

You need a computer with **adb** (Android platform-tools) and **USB debugging** on each device
(Settings → About → tap **Build number** 7 times → Developer options → USB debugging).

### B1. Install

```bash
adb install -r BackSeatTV-<version>.apk
```

Then pick the role and add media as in **A2**. Over adb you can also push media and pre-pick the
role:

```bash
adb push "Bluey" /sdcard/Anancy/Bluey
adb shell am start -n app.anancy/.SetupActivity --es role player      # or: --es role remote
```

### B2. Make the TV a kiosk (device owner)

Device owner can only be set on a device with **no accounts added**: no Google, no Samsung. Remove
them first, or factory-reset and skip sign-in.

```bash
adb shell dpm set-device-owner app.anancy/.admin.AdminReceiver
```

Then open BackSeat TV, choose **This is the TV**, and turn on **Settings → Kiosk lock**, or:

```bash
adb shell settings put global anancy_kiosk 1
```

What the kiosk gives you:

- The show **is** the home screen, and the tablet boots straight into it.
- Home, Back and Recents are blocked.
- Permissions are granted silently.
- Wi-Fi joins happen with no prompts.
- The remotes can turn the screen on and off.

To get out, use the four-finger taps and the code, then **Exit to launcher**. To undo the kiosk
completely: **Settings → Remove device owner** (no factory reset needed).

Android 10 tablets: Bluetooth may be off after a reset. Run
`adb shell settings put global bluetooth_on 1` and reboot.

### B3. Unlock options (optional)

Grown-up menu → **Settings → Unlock method**:

- **Randomized code** (default): copy a random code shown on screen.
- **Preset PIN**: type your own PIN. The default is `1234`; change it there or with
  `adb shell settings put global anancy_pin <PIN>`.
- **Pattern** (experimental).

The gesture itself (fingers, taps, time window) is tunable in the same screen.

### B4. Watch remote (Wear OS)

The first install needs adb on the watch:

1. On the watch, turn on **Developer options → Wireless debugging** and pair it from the computer
   (`adb pair <ip:port>`, then `adb connect <ip:port>`).
2. Install the watch app:

   ```bash
   adb -s <watch-ip:port> install -r -i app.anancy BackSeatTV-wear-<version>.apk
   ```

After that the watch **updates itself** whenever the phone app is updated (over the
phone↔watch Bluetooth link), so you never repeat step 2.

The watch follows the phone:

- **Phone set as the TV:** the watch controls that phone only.
- **Phone as remote, aimed at one screen:** the watch talks to that screen directly.
- **Phone as remote, aimed at several screens or a synced group:** watch buttons go through the
  phone, so they reach every screen the phone remote would.

### B5. Android Auto without the developer toggle

Install the **phone** with the Play Store recorded as the installer. Android Auto then lists it like
any Play app, and Android Auto updates can't hide it:

```bash
adb install -r -i com.android.vending BackSeatTV-<version>.apk
```

Every later update to the phone app must use the same `-i com.android.vending` flag. Updating any
other way drops it, and you're back to needing Unknown sources (A5).

### B6. Updating

Install over the top with `adb install -r …` (plus `-i com.android.vending` on the phone, if you
used B5). Settings and the library are kept. Never uninstall a device-owner TV — reinstall over it.

---

## Optional extras

- **Camera check-in (still snapshots):** grown-up menu → Settings → Camera. The front camera
  updates a still image about once a second for the phone and car dash; nothing is recorded. Over
  adb the permission is `adb shell pm grant app.anancy android.permission.CAMERA` (a kiosk grants
  it itself).
- **Toddler play mode:** grown-up menu → Toddler play mode. A tap-the-letters screen over the show.
- **Switching a device's role:** open the **set-up check** and tap **Change role** on the Role line.
  On a phone remote it's in the **BackSeat TV ⌄** menu at the top ("This phone: set-up check · use as
  the TV"). On a TV it's in the grown-up menu → Settings → Set-up check.
- **Any device can be the remote, including a tablet.** Two tablets can work as a pair: one set as
  the TV, the other as the remote, with a Wear OS watch paired to the remote tablet. Nobody's phone is
  needed, and the watch drives whatever the remote tablet is pointed at.
- **A TV can stream from another TV.** A TV with no shows of its own can play the library of a TV
  on the same network.
- **More than one TV:** set each one up as a TV. The phone remote can drive them separately, all at
  once, or **synced** so they play the same thing in step.

## Building from source

Java 17, Android SDK platform/build-tools 34, Gradle 8.7 on your `PATH` (there's no wrapper).

```bash
cd anancy
gradle :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk (watch APK embedded)
gradle :app:testDebugUnitTest     # unit tests
```

The watch APK is built alongside, at `anancy/wear/build/outputs/apk/debug/wear-debug.apk`.
