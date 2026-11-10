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
| Locking the TV | Android's own **screen pinning**. A soft lock: fine for a quick distraction, but a curious kid can get out of it | **Kiosk**: the only real lockout. The show *is* the home screen; Home, Back and Recents are blocked; it boots straight into the show |
| Permissions | You tap "Allow" a few times | Granted silently |
| Watch remote | Not yet (it will arrive with the Play Store version) | Yes |
| Android Auto | Yes, with one developer toggle | Yes, with no toggle (see B5) |

Start with Part A. You can move a TV to Part B later without reinstalling. **If a child will be using the
screen unsupervised, use Part B:** only device owner locks the device down properly.

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

**Screen pinning is a soft lock, not a real one.** It's an Android convenience feature, and this app
can't make it any stronger without device owner:

- The way to unpin varies by device, so don't plan around a particular shortcut. Holding Back + Recents
  never unpinned the tablet we tested, and on other devices it may unpin straight away.
- The power button still works, and pinning ends if the device restarts.
- Toddler play mode and the grown-up menu are only as locked as the screen underneath them.

Give the TV a screen lock too (PIN or pattern in Android Settings), so Android can ask for it when the
pin ends. For a lockout you can rely on, use **device owner (B2)**.

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

### Windows: one-step tablet setup

On Windows 10 or 11, `install/BackSeatTV-Tablet-Setup.bat` does B1 and B2 for you. It downloads
Google's adb tools, finds the tablet, checks it has no accounts, installs the app, makes it the
device owner, and starts the locked show.

1. Put `BackSeatTV-<version>.apk` in the same folder as the `.bat`. Optional: add an `Anancy` folder
   with your shows, and it copies them across too.
2. On the tablet: factory reset (skip every sign-in), then turn on **USB debugging**. Android doesn't
   let any script do this part.
3. Plug it in, double-click the `.bat`, tap **Allow** on the tablet when asked, and type `Y` to confirm.

Samsung tablets may also need the [Samsung USB driver](https://developer.samsung.com/android-usb-driver)
if Windows can't see them. Running the script again on a finished tablet just updates the app.

### B1. Install

```bash
adb install -r BackSeatTV-<version>.apk
```

Then pick the role and add media as in **A2**. Over adb you can also push media and pre-pick the
role:

```bash
adb push "Bluey" /sdcard/Anancy/Bluey
adb shell am start -n app.anancy/.AdbSetRole --es role player      # or: --es role remote
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
  It only keeps little hands in the app on a device-owner kiosk (B2). With screen pinning it's a soft lock.
- **Switching a device's role:** open the **set-up check** and tap **Change role** on the Role line.
  On a phone remote it's at the bottom of **Settings** ("This phone: set-up check"). On a TV it's in
  the grown-up menu → Settings → Set-up check.
- **Use the remote as a screen too.** On the phone remote, tap **BackSeat TV ⌄** at the top and tick
  **This phone as playback**. The show plays in a small card at the top of the screens list, marked
  **"<name> (this device)"**, and you keep the whole remote. Tap the card to include or leave out that
  screen. The corners button on the card makes it **large**: full-screen, with the same unlock gesture,
  Pin the screen and volume-button lock as a TV. Back (or the grown-up menu → Remote) returns it to
  the card. Tick **Sync Playback** in the same menu to keep the selected screens in step.

  **The watch is the best way to use a large show.** Once it's full-screen the phone's remote is
  hidden, but the watch (B4) keeps driving it. Select only "(this device)" to control just the phone,
  or several screens and the watch's buttons reach all of them.
- **Any device can be the remote, including a tablet.** Two tablets can work as a pair: one set as
  the TV, the other as its remote. The watch talks to TVs over its own Bluetooth, so it works
  alongside either one. Following the remote's choice of screens needs the device the watch is
  paired to, which is normally a phone.
- **A TV can stream from another TV.** A TV with no shows of its own can play the library of a TV
  on the same network.
- **More than one TV:** set each one up as a TV. The phone remote can drive them separately, all at
  once, or **synced** so they play the same thing in step.

## Building from source

Java 17, Android SDK platform/build-tools 35, Gradle 8.7 on your `PATH` (there's no wrapper).

```bash
cd anancy
gradle :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk (watch APK embedded)
gradle :app:testDebugUnitTest     # unit tests
```

The watch APK is built alongside, at `anancy/wear/build/outputs/apk/debug/wear-debug.apk`.

For signed release APKs, put your key's details in `anancy/release-signing.properties` (the format is
at the top of `anancy/signing.gradle`) and run `gradle assembleRelease`. Android won't update an app
with one signed by a different key, so a device running a debug build has to be switched over: remove
device owner if it has it, uninstall, then install the release and set it up again.
