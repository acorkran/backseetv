# Anancy — Setup guide

How to build the whole appliance from scratch: a locked-down Kodi kiosk tablet that boots into a
shuffle of kids' shows, plus the phone remote, the Android Auto remote, and the (optional) child
camera monitor. Everything is **offline** — no internet, no accounts, no cloud.

This is the process that was walked through end-to-end; the commands are what actually work on the
reference hardware (Samsung Galaxy Tab Active3 / SM-T575, Android 13). `adb` throughout is the
Android platform-tools binary; the tablet + phone are reached over USB or wireless adb.

---

## 0. Hardware + prerequisites

- **Tablet** — the playback device (reference: SM-T575). Gets locked down as a device-owner kiosk.
- **Phone** — the front-seat remote + the phone's **hotspot is the network** the tablet joins.
- **microSD card** — holds the whole media library at native quality, read-only in practice (kept
  off internal storage so it's not re-written; see `kodi-tablet-sd-expansion`).
- **A dev machine** with the Android SDK (build-tools + a platform image), Java 17, and — for the
  two Gradle apps — Gradle 8.7. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` to the SDK.
- **Media** — your own kids' shows, one folder per show, `Show/Season NN/Show.SxxEyy.Title.mp4`,
  with baked-in NFO + artwork (fully offline, no scraper). Acquiring/encoding media is out of scope
  here — see the project notes on the yt-dlp grab pipeline and the 576p/native ffmpeg pipeline.

## 1. Media onto the SD card

Lay the library out on the card as `/<mount>/Anancy/<Show>/Season NN/<episode>.mp4` with per-item
`.nfo` + local art. Kodi will read all metadata locally (no network). The card mounts as a portable
volume (no adoption needed); the reference build lives at `/storage/<uuid>/Anancy`.

## 2. Kodi: install + point at the SD + scan

1. Install Kodi (org.xbmc.kodi) on the tablet (sideload the APK; it is **not** part of this repo).
2. Add a **video source** → the SD `Anancy` folder, content type **TV shows**, scraper
   **Local information only** (`metadata.local`) so it never hits the internet.
3. **Scan** — Kodi builds `MyVideos*.db` from the local NFO/art. (On a swapless tablet a huge scan
   can OOM; add zram if needed.) One-time; the DB then just needs re-pointing if the card path ever
   changes (prefix-swap `path.strPath` / `art.url` / `episode.c18` — see `kodi-tablet-sd-expansion`).
4. Copy the **autoplay addon** into Kodi's addons dir so the show self-heals and boots automatically:
   `tablet-kiosk/kodi-config/service.anancy.autoplay/` and `script.anancy.play/` →
   `…/org.xbmc.kodi/files/.kodi/addons/`. Enable both in Kodi.

## 3. Smart playlists — generate one per show from the library

Kodi smart playlists (`.xsp`) live in
`…/org.xbmc.kodi/files/.kodi/userdata/playlists/video/`. Two curated ones ship in
`tablet-kiosk/kodi-config/playlists/`: **ShuffleAll.xsp** (the content-agnostic all-shows shuffle the
kiosk boots into — excludes any folder named `!hidden`) and **Naptime.xsp** (a calm mix).

To make a **per-show playlist for every show** in the library, generate them from Kodi's own list
(so titles match exactly, apostrophes and all) and push them over adb. With the bridge forwarded
(`adb forward tcp:18081 tcp:8080`):

```bash
python3 - <<'PY'
import json, subprocess, re, os
r = subprocess.run(["curl","-s","-X","POST","localhost:18081/jsonrpc","-d",
  '{"jsonrpc":"2.0","id":1,"method":"VideoLibrary.GetTVShows","params":{"properties":["title"]}}'],
  capture_output=True, text=True).stdout
shows = [s["title"] for s in json.loads(r)["result"]["tvshows"]]
esc = lambda t: t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
fn  = lambda t: re.sub(r'[^A-Za-z0-9]','',t)+".xsp"
os.makedirs("pl-new", exist_ok=True)
for t in shows:
    open("pl-new/"+fn(t),"w",encoding="utf-8").write(
      '<?xml version="1.0" encoding="UTF-8"?>\n<smartplaylist type="episodes">\n'
      f'    <name>{esc(t)}</name>\n    <match>all</match>\n'
      f'    <rule field="tvshow" operator="is"><value>{esc(t)}</value></rule>\n'
      '    <order direction="ascending">episode</order>\n</smartplaylist>\n')
print(f"generated {len(shows)} playlists")
PY

PLDIR=/storage/emulated/0/Android/data/org.xbmc.kodi/files/.kodi/userdata/playlists/video
for f in pl-new/*.xsp; do adb push "$f" "$PLDIR/"; done
```

**GOTCHA:** Kodi caches its smart-playlist registry — newly-*added* `.xsp` files aren't picked up
until Kodi re-reads the folder. **Restart Kodi** after pushing new playlists
(`adb shell am force-stop org.xbmc.kodi` then relaunch), or they resolve to "Invalid params" / 0
episodes even though the file is correct. Overwriting an existing `.xsp` is picked up live; only new
files need the restart.

## 4. Device-owner provisioning — the ONE un-reversible step

The kiosk app must be **device owner** to lock the task, blank the screen, and own HOME. This can
only be set on a device with **no accounts added**, and once set the app **cannot be casually
uninstalled** — removing device-owner effectively needs a **factory reset**. Do this deliberately.

```bash
adb install tablet-kiosk/anancy-kiosk.apk       # or build.sh first (see §7)
adb shell dpm set-device-owner com.kidstv.kiosk/.AdminReceiver
```

Then grant the kiosk what it needs (device-owner grants most, but these are explicit):

```bash
adb shell appops set com.kidstv.kiosk SYSTEM_ALERT_WINDOW allow          # the touch-block overlay
adb shell appops set com.kidstv.kiosk MANAGE_EXTERNAL_STORAGE allow      # phone->tablet uploads
adb shell pm grant com.kidstv.kiosk android.permission.WRITE_SECURE_SETTINGS  # watchdog/pin/import-dir globals
```

On next boot the tablet comes up as the kiosk: HOME = the kiosk, which launches Kodi, raises the
touch-block overlay, and the autoplay addon starts ShuffleAll. Admin escape = 4 four-finger taps in
3s → PIN (default 1234). Once device-owner, updates are just `adb install -r` over the top (a "fresh
install" of the kiosk is a reinstall-over, **not** an uninstall).

## 5. Phone remote

```bash
cd remote-app && ./build.sh          # no Gradle: aapt2 -> javac -> d8 -> apksigner
adb install anancy-remote.apk
```

First run is zero-config: with the phone's **hotspot on** and the tablet joined, the remote sweeps
the phone's own subnet and finds Kodi automatically — no IP to type. Playlist tiles, transport,
volume, a nap/wake power button, a "Go to sleep" fade, an in-app Settings **page**, a Files browser
(tablet uploads + a phone folder browser + the system file picker), and an optional home-screen
**widget** (Settings → Add remote widget).

## 6. Android Auto remote (optional — control from the car head unit)

```bash
gradle -p androidauto-remote --offline assembleDebug
adb install -r androidauto-remote/app/build/outputs/apk/debug/app-debug.apk
```

On the phone: Android Auto → tap the Version header ~10× to enable **developer mode**, then enable
**"Unknown sources"** (required for a sideloaded media app to appear). In the car, open **"Anancy
Remote"** from the media-app picker: browse playlists, play/pause, skip, **Vol−/Vol+** (drives the
tablet's volume, not the car's), and a sticky **power-off** custom action. (It needs
`usesCleartextTraffic` for the power/nap bridge call — already in its manifest.)

## 7. Child camera monitor (optional)

```bash
gradle -p camera-monitor --offline assembleDebug
adb install -r camera-monitor/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant io.anancy.cameramon android.permission.CAMERA
adb shell pm grant io.anancy.cameramon android.permission.POST_NOTIFICATIONS
```

On-device front-cam + bundled ML Kit (offline) logs eyes-open + head-pitch to a CSV while Kodi plays
and even screen-off. The kiosk watchdog **auto-starts and recovers it** (heartbeat on the 8080
bridge; relaunched via a headless autostart activity if it dies while the screen is on). It's a
glanceable aid, **not** a safety monitor (blind in the dark). See `child-camera-monitor` for the
data pipeline + limits.

## 8. Everyday operation

- **Network:** turn the phone's **hotspot** on for the ride; the tablet auto-joins it and both
  remotes find it. It's the one manual step.
- **Kiosk update:** `adb install -r tablet-kiosk/anancy-kiosk.apk` (reinstall-over; never uninstall).
- **Add a show later:** drop it on the SD in the same layout, Kodi Scan, regenerate playlists (§3,
  incl. the Kodi restart).
- **Admin:** 4 four-finger taps in 3s → PIN → in-kiosk admin menu (playlists, rebuild ShuffleAll,
  import dir, etc.), or a maintenance hatch via the `kiosk_off` global.

## 9. Home-base data sync (camera training data)

The camera monitor logs to a CSV on the tablet. To collect it here for training/finetuning, the
always-on home server pulls it whenever the tablet is adb-reachable — USB, or **wireless adb** once
the tablet rejoins the home Wi-Fi (mDNS-discovered). See **`server/`**:

```bash
# one-off / manual
server/camsync.sh
# automatic: every 10 min via cron (picks up trips within ~10 min of coming home)
*/10 * * * * /home/user/projects/anancy/server/camsync.sh >/dev/null 2>&1
```

New rows (line-count watermark) append to `~/anancy-data/childlog-archive.csv` + a dated per-trip
snapshot; the on-device CSV is left intact. Wireless adb needs a one-time pairing to the server.

## Build tooling summary

| App | Build | Device-owner? | Uninstallable? |
|---|---|---|---|
| `tablet-kiosk` | `./build.sh` (no Gradle) | **yes** | no — reinstall-over only |
| `remote-app` | `./build.sh` (no Gradle) | no | yes |
| `androidauto-remote` | `gradle assembleDebug` | no | yes |
| `camera-monitor` | `gradle assembleDebug` | no | yes |
