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
- **`remote-app/`** — the front-seat remote (`com.kidstv.remote`). Talks straight to Kodi's raw
  JSON-RPC (port 9090) by IP, so it behaves identically over WiFi or a Bluetooth-PAN tether.
  **Zero-config:** since the phone is the hotspot, the app sweeps its own subnet for Kodi and finds
  the tablet automatically — no IP to type. Playlist tiles + transport / seek / volume / D-pad, and
  a power button for nap/wake.

That's the whole project — two Android apps. (The Pi tooling from the old approach was removed; it's
in git history if ever needed.)

## Status

Working and road-tested. **Not yet generalized** for other setups — the remote's playlist list is
still hardcoded to one library (see the planned generic-playlist work), so it isn't scrubbed for
public release yet.

## Building the apps

No Gradle. From either app dir: `./build.sh` (needs an Android SDK with build-tools + a platform).
It compiles, signs with the local `debug.keystore`, and produces a `*.apk` you can `adb install`.
