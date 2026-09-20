# Anancy — Setup guide

How to build and provision the appliance from scratch. Anancy is **one** Android app
(`anancy/`, package + applicationId `app.anancy`) with its own Media3/ExoPlayer engine — **no
Kodi, no bridge, no separate remote/camera apps**. The same APK runs in two roles, chosen on first
run: **player** (the tablet kiosk, boots into a shuffle of local media) and **remote** (a phone,
and by extension the paired watch + the car's Android Auto screen). Everything is **offline** — no
internet, no accounts, no cloud.

`adb` throughout is the Android platform-tools binary; the tablet and phone are reached over USB or
wireless adb. Reference hardware: Samsung Galaxy Tab Active3 / SM-T575 and a Lenovo TB-X606F.

---

## 0. Prerequisites

- **JDK 17** (the build compiles at `sourceCompatibility`/`targetCompatibility` 17).
- **Android SDK** with **build-tools 34** and **platform `android-34`** (the project compiles and
  targets API 34; `minSdk` 26).
- **Gradle 8.7** on your `PATH`. There is **no Gradle wrapper** in the repo — invoke the system
  `gradle`.
- **`ANDROID_SDK_ROOT`** (or `ANDROID_HOME`) pointing at the SDK. `anancy/local.properties` is
  gitignored; if you prefer, create it with `sdk.dir=/path/to/android-sdk` instead.
- **adb** (platform-tools) to install and provision devices.
- **Media** — your own kids' shows as plain video files (`.mp4` / `.mkv` / `.m4v`). No NFO,
  artwork, or scraper is needed; Anancy groups by folder (see §5).

## 1. Build

```bash
cd anancy
gradle :app:assembleDebug
```

This produces `anancy/app/build/outputs/apk/debug/app-debug.apk`.

Building `:app` **also builds the `:wear` module and embeds `wear.apk` into the phone APK's
assets** (`embedWearApk` runs before `preBuild`). That is what lets a phone in the remote role push
the watch build to a paired Wear OS watch over the Bluetooth Data Layer — you never install the
watch app by hand (see §6).

Optional — run the pure-logic unit tests (sync math + command routing, JVM-only, fast):

```bash
gradle :app:testDebugUnitTest
```

## 2. Install and pick a role (first run)

```bash
adb install -r anancy/app/build/outputs/apk/debug/app-debug.apk
```

Launch **Anancy**. On first run `RouterActivity` shows **"Set this device's role"** with two
buttons:

- **This is the TV (player)** — the tablet kiosk that plays the local library.
- **This is a Remote** — a phone (or the on-device admin remote, the watch companion, the AA dash).

The choice is stored and every later launch routes straight to that role. To re-choose, clear the
app's data. On first launch the app also requests the BLE runtime permissions it needs.

## 3. Provision the tablet as a device-owner kiosk (player only)

The player must be **device owner** to lock the task, own HOME, disable the keyguard, and blank the
screen. This can only be set on a device with **no accounts added**, and it is effectively
permanent: removing device-owner needs a **factory reset**. Do it deliberately, on the tablet you
chose the **player** role on.

```bash
adb shell dpm set-device-owner app.anancy/.admin.AdminReceiver
```

(`app.anancy/.admin.AdminReceiver` is the device-admin receiver declared in the manifest.)

On **Android 11+ (API 30+)** the player reads the library via all-files access — grant it once:

```bash
adb shell appops set app.anancy MANAGE_EXTERNAL_STORAGE allow
```

(On API 29 the device-owner player grants itself legacy read/write storage automatically — see
`Library.grantStorage` — so no appops step is needed there.)

## 4. Turn on the locked kiosk

Lock-task is **gated so dev builds aren't trapped**: the player only enters lock-task, pins itself
as HOME, and disables the keyguard when the `anancy_kiosk` flag is `1`. Enable it either from the
on-tablet **Settings screen** (admin menu → Settings), or over adb:

```bash
adb shell settings put global anancy_kiosk 1
```

Relaunch the player (or reboot). It now comes up locked: the show **is** the home screen (boot and
Home land on it), the status bar is disabled, and playback starts **muted**.

**Admin escape:** the hidden unlock gesture (default **4 four-finger taps within 3s**, tunable) opens
the unlock challenge. The default style is a **randomized timed code** shown on screen (nothing to
memorize, a toddler can't copy it in time); a fixed **PIN** (default `1234`, override with
`adb shell settings put global anancy_pin <PIN>`) and an experimental swipe **pattern** are the
alternatives. Passing it opens the **admin menu**: Remote (the full remote UI running locally
against this player), Settings, Toddler play mode, Exit to launcher, and Back to the show.

## 5. Put media on the tablet

Anancy scans for a top-level **`Anancy/`** folder on any storage volume (primary internal *and* a
portable microSD — no adoption, no hardcoded UUID) and treats **each immediate sub-folder as a
show**. Files inside a show are sorted by name (episode order). Recognized extensions: **`.mp4`,
`.mkv`, `.m4v`**.

```
<storage>/Anancy/
  Bluey/            Season 1/Bluey.S01E01.mp4 ...
  Ms Rachel/        ...
  ...
```

Push a library over adb, e.g. onto primary storage:

```bash
adb push "Bluey" /sdcard/Anancy/Bluey
```

On a portable SD the same layout lives at `/storage/<uuid>/Anancy/…`. On launch the player scans the
roots, builds a **Shuffle All** playlist plus one **per-show** playlist, and starts playing (muted).
Rescan later from the admin remote. Phone→tablet uploads land in `<Anancy>/Imports/`, which surfaces
as an **Imports** playlist.

## 6. Point the remotes at the player

**Phone remote** — install the same APK on the phone and pick **This is a Remote**. It reaches the
player over two transports with the same command vocabulary:

- **BLE (primary)** — works with **no network at all** (car, no Wi-Fi). The player advertises as a
  GATT peripheral; the remote scans and connects.
- **Wi-Fi / mDNS** — when both are on the same LAN, the player advertises `_anancy._tcp` and serves
  an HTTP control API on **:8090**. The remote auto-discovers it by name — **no IP to type**.

For the player's own local-only hotspot, the credentials are shown as a **QR code** that the remote
scans (QR pairing), so nobody types a random SSID/passphrase.

**Watch remote** — do **not** install it separately. Installing or updating the phone (remote role)
self-pushes the embedded `wear.apk` to a paired Wear OS watch over the Bluetooth Data Layer
(`PackageReplacedReceiver` → `WearPusher`). The watch app shares the `app.anancy` application id and
signing key (required for the Data Layer to route between them). It talks to the player through the
phone's remote service.

**Android Auto** — the phone app **is** the AA media app (`AnancyMediaService`, a
`MediaBrowserService`); there is no separate car app to install. To make a sideloaded media app
appear in the car: on the phone open **Android Auto → tap the Version header ~10×** to enable
developer mode, then enable **"Unknown sources."** In the car, pick **Anancy** from the media-app
picker to browse playlists and drive the tablet (play/pause, skip, volume, power). The dash mirrors
the tablet's now-playing and forwards controls over the remote service; it makes no audio of its
own.

## Notes

- **Optional camera plugin.** The player can run a headless front-camera **still-snapshot** service
  (`SnapshotService`, ~0.75 fps JPEGs, **not** a video feed), off by default. It needs the camera
  permission granted (`adb shell pm grant app.anancy android.permission.CAMERA`).
- **Updates** are just `adb install -r …/app-debug.apk` over the top; a device-owner player is
  reinstalled-over, never uninstalled.
- **`server/`** holds an optional home-server helper that pulls camera-training data off the tablet
  over adb — see [`server/README.md`](server/README.md).
- The archived v1 apps live in `legacy/` and are not built.
