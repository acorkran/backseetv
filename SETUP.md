# BackSeeTV — install and setup

BackSeeTV is **one Android app** (package `app.anancy`; "Anancy" is the codename). The same APK
runs in two roles, chosen the first time you open it:

- **TV (player)** — the screen that plays your shows: a tablet, an old phone, any Android 8+ device.
- **Remote** — your phone. The phone also carries the **Wear OS watch remote** and the **Android
  Auto** car dash; neither is a separate app.

Everything is offline: no internet, no accounts, no cloud.

There are two ways to set it up:

| | **No computer** (Part A) | **With adb** (Part B) |
|---|---|---|
| Install | Download the APK and open it | `adb install` |
| Locking the TV | Android's own **screen pinning**. Blocks Home, Back and Recents and pins itself again if released, but asks for one tap to confirm and ends on a restart | **Kiosk**: no prompts, and it survives a restart. The show *is* the home screen; Home, Back and Recents are blocked; it boots straight into the show |
| Permissions | You tap "Allow" a few times | Granted silently |
| Watch remote | Not yet (it will arrive with the Play Store version) | Yes |
| Android Auto | Yes, with one developer toggle | Yes, with no toggle (see B5) |

Start with Part A. You can move a TV to Part B later without reinstalling. **For a screen a child uses
unsupervised, Part B is the strongest lock.**

---

## Part A — no computer

### A1. Install the app (TV and phone)

1. On each device, download `BackSeeTV-<version>.apk` from the repo's **Releases** page.
2. Open the file. Android will ask to allow installs from your browser or file manager — allow it
   for that app only.
3. If Play Protect warns about an unknown app, choose **Install anyway**. It warns because the app
   isn't from the Play Store.

### A2. Set up the TV

1. Open **BackSeeTV** and choose **This is the TV (player)**.
2. Allow **Nearby devices** (Bluetooth) when asked. This is how the remotes reach it.
3. **All files access**: the TV shows *"BackSeeTV can't read your storage"*. Tap **Open set-up
   check**, then **All files access**, and turn it on for BackSeeTV.
4. **Add your shows.** Use a USB cable to a computer, a file manager, or an SD card. Put
   **one folder per show** inside a top-level folder called `BackSeeTV-Media`:

   ```
   BackSeeTV-Media/
     Bluey/         Bluey S01E01.mp4, Bluey S01E02.mp4 ...
     Ms Rachel/     ...
   ```

   - It finds `BackSeeTV-Media` on internal storage **and** on any SD card.
   - Your shows are somewhere else? The set-up check has **Media folder** to point it there.
   - Files play in name order. `.mp4`, `.mkv` and `.m4v` are supported.
5. The library appears and starts playing, **muted**. You get a **Shuffle All** playlist plus one
   playlist per show.

### A3. Lock it (toddler lock)

The toddler lock is on from the start. Each time the show goes full screen, Android asks you to confirm the pin
with one tap (**Got it** or **OK**).

To get out, or to turn pinning off:

1. **Tap with four fingers, four times, within 3 seconds**, anywhere on the show.
2. Copy the code shown on screen (it changes every time; a toddler can't read it in time).
3. You're on the **remote page**, with the pin off. Its **⌄** menu has **Settings**, where you'll
   find **Toddler lock → off** and, at the bottom, **Exit to the tablet's home screen**.

**Back** from the remote page returns to the show and asks to pin again. On a Samsung with a screen
lock, taking the pin off also locks the phone: unlock it as usual and the remote page is waiting.

While pinned, Home, Recents and the notification shade do nothing. The four-finger taps still get
you out.

**How strong is it?** On Android 10 and 15, Home, Recents and Back do nothing while pinned, and the
screen ignores taps apart from the four-finger unlock. Three limits come from Android and need device
owner to remove:

- Android asks you to confirm each pin with one tap.
- Android keeps its own way out: touch and hold Back and Recents. If that's used, BackSeeTV asks to
  pin again straight away.
- The power button still works, and pinning ends if the device restarts.

Give the TV a screen lock too (PIN or pattern in Android Settings), so Android can ask for it when the
pin ends. For no prompts and a lock that survives a restart, use **device owner (B2)**.

**Coming back to the show after a restart (optional).** While the toddler lock is on, BackSeeTV can be
the Home app: set it in Android Settings → Default apps → Home app, or tap **Always** if Android asks.
**Exit to the tablet's home screen** (Settings) hands Home back to your normal launcher, so the device works
as usual for you afterwards. Set it again next time you lock the tablet up.

### A4. Set up the phone remote

1. Open **BackSeeTV** on the phone, choose **This is a Remote**, and allow **Nearby devices**.
2. **Pair it with the TV.** A TV no remote has paired with shows a pairing code when it starts. On the
   phone, tap **Pair a screen** and scan that code. Later, or for another phone: grown-up unlock →
   Settings → **Pair a remote**.
3. That's the only step. From then on the phone finds the TV over Bluetooth by itself, with no network
   needed, so it works in the car. The watch pairs through the phone.
4. Nothing found? Tap **Run the set-up check**. It names what's wrong (Bluetooth off, a denied
   permission, the other device not set as the TV) and offers the fix.
5. When both devices are on the same Wi-Fi, the remote also uses Wi-Fi automatically. That's
   faster, and it's what the live camera and uploads use.
6. If Bluetooth can't work at all, use **Pair over Wi-Fi** in the TV's set-up check instead: the
   same scan, plus the TV's hotspot details.

**Only paired devices can control a TV or see its camera.** The pairing code is the only way in:
whoever can see the TV's screen can pair. Another family's BackSeeTV nearby shows on your phone as
"not paired" and is never connected to, and theirs can't reach yours. Everything between paired
devices is encrypted or signed, over Bluetooth and Wi-Fi. Lost or sold a phone? On the TV: grown-up
unlock → Settings → **Unpair all remotes**, then pair the phones you still have again. On a phone,
**Connection…** lists the paired screens and can forget one.

### A5. Android Auto

Android Auto only lists apps from the Play Store unless you tell it otherwise. On the phone:

1. Open **Settings → Connected devices → Android Auto**.
2. Scroll to the bottom and tap **Version** about 10 times. Accept the developer-settings prompt.
3. Tap **⋮ → Developer settings → Unknown sources → on**.
4. Plug into the car. **BackSeeTV** is in the media apps.

The dash shows what's playing on the TV and controls it. It plays no sound of its own.

> **If BackSeeTV vanishes from the car after an Android Auto update:** the update turned
> Unknown sources back off. Tapping the old icon can crash Android Auto to a blank screen.
> Repeat steps 1–3 (or use B5 so it never happens again).

> **Wireless Android Auto takes the phone's Wi-Fi.** It connects the phone to the car over Wi-Fi,
> and most phones can only join one Wi-Fi network at a time. So while the car is projecting, the
> phone has left your home Wi-Fi, even parked in the driveway with the tablet still on it. Don't try
> to run a synced group over the house Wi-Fi from the car. Many phones (the Galaxy S21 included)
> can still run a hotspot alongside Android Auto, and BackSeeTV uses that: tick **Sync Playback**
> and the phone hosts, and a device-owner TV joins it. **Connection…** on the phone says which of
> these your phone can do. Phones that can't host alongside Android Auto keep the dash and drop the
> TV link to Bluetooth, so syncing waits until the car disconnects. A wired Android Auto connection
> leaves the phone's Wi-Fi free.

---

## Part B — with adb (full kiosk, watch, no Android Auto toggle)

You need a computer with **adb** (Android platform-tools) and **USB debugging** on each device
(Settings → About → tap **Build number** 7 times → Developer options → USB debugging).

### Windows: one-step tablet setup

On Windows 10 or 11, `install/BackSeeTV-Tablet-Setup.bat` does B1 and B2 for you. It downloads
Google's adb tools, finds the tablet, checks it has no accounts, installs the app, makes it the
device owner, and starts the locked show.

1. Put `BackSeeTV-<version>.apk` in the same folder as the `.bat`.
2. On the tablet: factory reset (skip every sign-in), then turn on **USB debugging**. Android doesn't
   let any script do this part.
3. Plug it in and double-click the `.bat`. When the tablet asks **Allow USB debugging?**, tick
   **Always allow from this computer** and tap **Allow**. If Android asks again partway through, the
   script waits and tells you. Type `Y` to confirm.
4. When it asks for your shows, drag their folder onto the window and press Enter, or press Enter to
   skip. Inside that folder, one sub-folder per show (`.mp4`, `.mkv` or `.m4v`); each show becomes a
   playlist. A folder of videos with no sub-folders becomes one show. The script checks the shows
   fit (keeping 1 GB free) before copying, and only copies what's new on a re-run. A
   `BackSeeTV-Media` folder next to the `.bat` is used without asking.

Shows on a microSD card work too, with no script: a folder called `BackSeeTV-Media` on the card, one folder
per show inside it.

Samsung tablets may also need the [Samsung USB driver](https://developer.samsung.com/android-usb-driver)
if Windows can't see them. Running the script again on a finished tablet updates the app and adds any
new shows. Each
run writes `BackSeeTV-setup-log.txt` next to the `.bat`; attach it if you report a problem.

Afterwards the kiosk doesn't need developer mode. You can tap **Revoke USB debugging authorizations**
in Developer options and then switch Developer options off. Turn them back on before updating with
the script.

### B1. Install

```bash
adb install -r BackSeeTV-<version>.apk
```

Then pick the role and add media as in **A2**. Over adb you can also push media and pre-pick the
role:

```bash
adb push "Bluey" /sdcard/BackSeeTV-Media/Bluey
adb shell am start -n app.anancy/.AdbSetRole --es role player      # or: --es role remote
```

### B2. Make the TV a kiosk (device owner)

Device owner can only be set on a device with **no accounts added**: no Google, no Samsung. Remove
them first, or factory-reset and skip sign-in.

```bash
adb shell dpm set-device-owner app.anancy/.admin.AdminReceiver
```

Then open BackSeeTV, choose **This is the TV**, and turn on **Settings → Kiosk lock**, or:

```bash
adb shell settings put global anancy_kiosk 1
```

What the kiosk gives you:

- The show **is** the home screen, and the tablet boots straight into it.
- Home, Back and Recents are blocked.
- Permissions are granted silently.
- Wi-Fi joins happen with no prompts.
- The remotes can turn the screen on and off.

To get out, use the four-finger taps and the code, then **Settings → Exit to the tablet's home
screen**. To undo the kiosk
completely: **Settings → Remove device owner** (no factory reset needed).

Android 10 tablets: Bluetooth may be off after a reset. Run
`adb shell settings put global bluetooth_on 1` and reboot.

### B3. Unlock options (optional)

Grown-up unlock → **Settings → Unlock method**:

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
   adb -s <watch-ip:port> install -r -i app.anancy BackSeeTV-wear-<version>.apk
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
adb install -r -i com.android.vending BackSeeTV-<version>.apk
```

Every later update to the phone app must use the same `-i com.android.vending` flag. Updating any
other way drops it, and you're back to needing Unknown sources (A5).

### B6. Updating

Install over the top with `adb install -r …` (plus `-i com.android.vending` on the phone, if you
used B5). Settings and the library are kept. Never uninstall a device-owner TV — reinstall over it.

---

## Optional extras

- **Camera check-in (still snapshots):** grown-up unlock → Settings → Camera. The front camera
  updates a still image about once a second for the phone and car dash; nothing is recorded. Over
  adb the permission is `adb shell pm grant app.anancy android.permission.CAMERA` (a kiosk grants
  it itself).
- **Toddler play mode:** the **Toddler** button on any remote, including the TV's own (grown-up
  unlock). A tap-the-letters screen over the show.
  Use it with the toddler lock or a device-owner kiosk (B2), or little hands can leave the app.
- **Switching a device's role:** open the **set-up check** and tap **Change role** on the Role line.
  On a phone remote it's at the bottom of **Settings** ("This phone: set-up check"). On a TV it's in
  Settings → Set-up check (after the grown-up unlock).
- **Use the remote as a screen too.** On the phone remote, tap **BackSeeTV ⌄** at the top and tick
  **This phone as playback**. The show plays in a small card at the top of the screens list, marked
  **"<name> (this device)"**, and you keep the whole remote. Tap the card to include or leave out that
  screen. The corners button on the card makes it **large**: full-screen, with the same unlock gesture,
  toddler lock and volume-button lock as a TV. Back (or the grown-up unlock) returns it to the
  card and the remote. Tick **Sync Playback** in the same menu to keep the selected screens in step.

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
  once, or **synced** so they play the same thing in step. Syncing needs Wi-Fi between the screens.
  Away from home Wi-Fi the phone starts its own hotspot when you tick **Sync Playback** and sends the
  details over Bluetooth. A device-owner TV joins it by itself. Android only offers the hotspot to
  any other TV as a suggestion, so that TV may ask first or stay where it is. If it stays out of
  the group, join it to the phone's hotspot by hand (the phone's Settings shows its name and password).

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
