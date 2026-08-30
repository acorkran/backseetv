# Kidodi

A self-contained, **offline** in-car kids' TV appliance: an Android tablet locked down as a
device-owner **Kodi** kiosk that boots straight into a shuffle of the shows, driven from the front
seat by a custom phone remote. Built for a toddler — no menus to escape into, no internet, no ads.

*(The name is Kid + Kodi. The repo used to be called `jellyfin-wheels`, from an earlier Raspberry
Pi + Jellyfin approach that was too weak; the project pivoted to media-local-on-tablet + Kodi.)*

## What's here

- **`tablet-kiosk/`** — the device-owner kiosk app (`com.kidstv.kiosk`) + `kodi-config/`. Boots to
  playback (the show *is* the home screen), a full-screen touch-block overlay makes the screen
  inert, hardware volume keys are bridged into Kodi's own volume, nap/wake blanks and restores the
  screen, smart playlists (shuffle-all / per-show / naptime), and a self-healing autoplay addon
  keeps the show alive. Fully local — no scraper, metadata baked as NFO/art.
- **`remote-app/`** — the front-seat phone remote (`com.kidstv.remote`). Talks straight to Kodi's raw
  JSON-RPC (port 9090) by IP, so it behaves identically over WiFi or a Bluetooth-PAN tether.
  **Zero-config:** since the phone is the hotspot, the app sweeps its own subnet for Kodi and finds
  the tablet automatically — no IP to type. A live tile per real playlist, transport / seek / volume
  / D-pad, nap/wake power, a "Go to sleep" fade, an in-app Settings page, a Files browser (tablet
  uploads + a phone folder browser + the system file picker), and a home-screen widget.
- **`androidauto-remote/`** — the car head-unit remote (`com.kidstv.aaremote`), a MediaBrowserService
  media app: browse playlists, play/pause/skip, tablet volume, and a sticky power-off, from Android
  Auto. Gradle.
- **`camera-monitor/`** — optional child monitor (`com.kidstv.cameramon`): on-device front-cam + ML
  Kit (offline) logs eyes-open / head-pitch; the kiosk watchdog auto-starts + recovers it. Gradle. A
  glanceable aid, **not** a safety monitor.

(The Pi tooling from the old approach was removed; it's in git history if ever needed.)

## Setup

See **[`SETUP.md`](SETUP.md)** for the full from-scratch process: media onto the SD, Kodi + the
autoplay addon, generating a per-show smart playlist for every show, device-owner provisioning (the
one un-reversible step), and installing the four apps.

## Status

Working and road-tested. The remote's playlist list is now dynamic (it enumerates the tablet's real
`.xsp`), but the repo isn't yet scrubbed of install-specific details (IPs, hotspot SSID, PIN) for a
public release.

## Building the apps

- `tablet-kiosk/` and `remote-app/`: **no Gradle** — `./build.sh` (needs an Android SDK with
  build-tools + a platform); compiles, signs with the local `debug.keystore`, produces a `*.apk`.
- `androidauto-remote/` and `camera-monitor/`: **Gradle** — `gradle -p <dir> assembleDebug` (they
  pull real AARs — media-compat, CameraX, ML Kit — that the no-Gradle build can't).

## License

Everything here is licensed **PolyForm Noncommercial 1.0.0** (see [`LICENSE.md`](LICENSE.md)):
free for any non-commercial use, but **commercial use requires a separate license** from the
copyright holder. It is source-available, not OSI "open source."

It runs on **Kodi** (GPL-2.0), which you install separately — Kodi itself is not part of this repo.
