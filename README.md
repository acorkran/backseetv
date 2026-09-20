# Anancy

[![Android CI](../../actions/workflows/android-ci.yml/badge.svg)](../../actions/workflows/android-ci.yml)

A self-contained, **fully offline** in-car and multi-screen media appliance. An Android tablet is
locked down as a **device-owner kiosk** that boots straight into a shuffle of local media —
no launcher, no menus to escape into, no internet, no ads — and is driven from a phone, a
smartwatch, or the car's Android Auto screen. Multiple screens can be driven at once, either
**independently** or **synced frame-to-frame** so several show the same thing in step.

Built as a real appliance for real drives, then hardened into a portfolio piece: one cohesive
Android app, unit-tested core logic, CI, and no cloud anywhere.

> **Name:** *Anancy* — the trickster-**storyteller spider** of West African and Caribbean
> folklore, who bargained for and owns all the world's stories. The spider's web says the
> whole idea in one image: a single story mirrored across many connected screens.
> *(Formerly **Kidodi** — the in-car kids-TV origin is now just one use case.)*

## Demo

<!-- TODO(owner): drop a screen recording here — phone remote driving two tablets in sync.
     Save it as docs/demo.gif and change the line below to: ![Anancy demo](docs/demo.gif) -->
_A 20-second clip of the phone remote driving two tablets in lock-step will go here._

## What makes it interesting

- **Clock-aligned multi-device playback.** Followers estimate the leader's monotonic clock
  with an NTP-lite offset (lowest-RTT filtered, slow-drift tracked), extrapolate where the
  leader is *now*, and converge with a seek-ahead scheduled start plus an inaudible ±1.5%
  speed nudge. Measured to ~20–50 ms apart on real hardware. The math is pure and
  unit-tested: [`player/SyncMath.java`](anancy/app/src/main/java/app/anancy/player/SyncMath.java).
- **Its own EOF-proof player.** A Media3 (ExoPlayer) engine with native shuffle-all +
  deterministic auto-advance, so the "stuck at end-of-file" wedge that plagued the old
  Kodi build structurally cannot happen — no watchdog needed.
- **Device-owner kiosk done properly.** Lock-task mode, boot-into-show as the home screen,
  a hidden gesture + PIN admin escape, self-granted permissions, and a self-update path
  that even pushes the watch build over the Bluetooth Data Layer.
- **Two transports, one control plane.** BLE is primary (works with no network at all);
  Wi-Fi/mDNS runs alongside for higher bandwidth and for screens that don't advertise BLE.
  A **pull model** (the phone is a passive bulletin the tablet polls) sidesteps the
  Wi-Fi Direct single-radio + directional-firewall problem.
- **Correct command routing.** In a synced group, playback drives the leader alone
  (followers mirror) while per-screen actions (power/light/sleep/volume) hit every member —
  the split is pure and unit-tested:
  [`remote/CommandRouter.java`](anancy/app/src/main/java/app/anancy/remote/CommandRouter.java).
- **Four control surfaces** from one app: an in-tablet admin remote, a phone remote, a
  Wear OS remote, and an Android Auto media dash.

## Architecture

One APK, two roles (chosen on first run and remembered): **player** (the tablet kiosk) and
**remote** (the phone). The same code ships both.

```
        Phone / Watch / Android Auto  ──BLE (primary)──┐
                    (remote role)      ──Wi-Fi/mDNS─────┤
                                                        ▼
                                            Tablet kiosk (player role)
                                            ┌───────────────────────────┐
                                            │ ControlServer  :8090 HTTP  │
                                            │ BleServer      GATT        │
                                            │        │                   │
                                            │        ▼                   │
                                            │ PlayerController ─ ExoPlayer│
                                            │  (queue, sync, volume)     │
                                            └───────────────────────────┘
   Synced group: one leader plays; followers poll /clock + /state and mirror in lock-step.
```

Inside `anancy/app` the code is grouped by concern (package root `app.anancy`):

| Package | Responsibility |
|---------|----------------|
| `player/` | ExoPlayer engine, queue/playlists, the clock-sync math, media session |
| `remote/` | Phone remote UI + service, command routing, settings/about screens |
| `net/`    | HTTP control server, BLE server/client, Wi-Fi client, hotspot, QR pairing, camera snapshot |
| `admin/`  | Device-owner receiver, unlock gesture, boot + package-replaced receivers |
| _(root)_  | Launcher/role router and the small shared core (`Cfg`, `Identity`) |

## Repo layout

| Path | What it is | State |
|------|------------|-------|
| [`anancy/`](anancy/) | **The app.** `:app` (phone + tablet) and `:wear` (Wear OS). Gradle. | Production |
| [`androidauto-remote/`](androidauto-remote/) | Standalone Android Auto media dash. Gradle. | Experimental |
| [`camera-monitor/`](camera-monitor/) | Front-cam + ML Kit still-image child monitor. Gradle. | Prototype |
| [`server/`](server/) | Home-server helper scripts (media sync over adb). | Utility |
| [`legacy/`](legacy/) | v1 (Kodi-bridge) apps + v2 prototypes, superseded. Not built. | Archived |

## Build & test

The app is a standard Gradle project (Java 17, Android SDK build-tools 34 / platform 34).
`local.properties` is gitignored; set `ANDROID_SDK_ROOT` or add your own.

```bash
cd anancy
gradle :app:testDebugUnitTest     # run the unit tests (SyncMath, CommandRouter)
gradle :app:assembleDebug         # build the APK (also builds + embeds the watch app)
```

CI runs both on every push — see [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml).

_(The archived `legacy/` apps use a no-Gradle `./build.sh`; they're kept only for history.)_

## Status & Android compatibility

Runs daily on a Samsung and a Lenovo tablet plus a Galaxy phone; a Wear OS watch and an
Android Auto head unit are supported. Android-TV-as-a-follower is exploratory.

The kiosk ships via GitHub / EMM (device-owner can't come from the Play Store), targets
API 34, and runs fine on Android 14/15/16. A few forward-looking items are deferred and
tracked for an online build + on-device pass:

- **targetSdk 35** for a future Play Store consumer flavor (needs the android-35 platform).
- **16 KB page-size**: bump CameraX to 1.4.1+ and ML Kit barcode to 17.3.0+ so their
  bundled native libs load on 16 KB-page Android 15+ devices (current test devices are 4 KB).
- **HotspotService → foreground service** and **edge-to-edge insets** (only bite at target 35).

## License

**PolyForm Noncommercial 1.0.0** (see [`LICENSE.md`](LICENSE.md)): free for any
non-commercial use; commercial use requires a separate license from the copyright holder.
Source-available, not OSI "open source."
