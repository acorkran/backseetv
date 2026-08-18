import xbmc
import json

# Boot default = shuffle of ALL shows (no repeats). The smart playlist is randomly
# ordered on every load, so each boot starts on a different episode (a static .m3u would
# always start on the same first file). RepeatAll + RandomOn keep it looping/reshuffling.
# If the library DB isn't ready yet on a cold boot the start_boot() retry loop just tries
# again until it plays. Yatse can switch to any smart playlist (Bluey / Big Lizard /
# Mini Kids / Naptime / Shuffle All) live - and the self-heal resumes WHATEVER is loaded,
# not this default. (AllShows.m3u is kept as a library-independent fallback if needed.)
BOOT = 'special://profile/playlists/video/ShuffleAll.xsp'


def jrpc(method, params=None):
    q = {"jsonrpc": "2.0", "id": 1, "method": method}
    if params is not None:
        q["params"] = params
    try:
        return json.loads(xbmc.executeJSONRPC(json.dumps(q)))
    except Exception:
        return {}


def playlist_count():
    r = jrpc("Playlist.GetItems", {"playlistid": 1, "limits": {"start": 0, "end": 1}})
    try:
        return r["result"]["limits"]["total"]
    except Exception:
        return 0


def start_boot():
    # Clear+Add+Open, NOT PlayMedia. PlayMedia APPENDS to the queue, and this runs in a
    # retry loop until playback starts - so on a slow cold boot it stacked several copies
    # of the 967-item shuffle into a giant queue. Clearing first makes every retry a clean
    # replace (final queue = 967, no matter how many times it's called).
    jrpc("Playlist.Clear", {"playlistid": 1})
    jrpc("Playlist.Add", {"playlistid": 1, "item": {"directory": BOOT}})
    jrpc("Player.Open", {"item": {"playlistid": 1}})
    xbmc.sleep(800)
    xbmc.executebuiltin('PlayerControl(RandomOn)')
    xbmc.executebuiltin('PlayerControl(RepeatAll)')


def resume():
    # Bring back whatever was selected (a Yatse smart playlist populates video playlist 1),
    # else fall back to the boot shuffle. Playing playlist 1 preserves the current choice.
    if playlist_count() > 0:
        jrpc("Player.Open", {"item": {"playlistid": 1, "position": 0}})
    else:
        start_boot()


def clean_watch_state():
    # This is a shuffle appliance - "watched" and "in progress / unfinished" flags are
    # meaningless clutter (and the kid stops episodes constantly). Wipe any resume points
    # and watched counts so the library stays pristine. Cheap: only touches the handful of
    # episodes that are actually dirty.
    for filt in ({"field": "inprogress", "operator": "true", "value": ""},
                 {"field": "playcount", "operator": "greaterthan", "value": "0"}):
        r = jrpc("VideoLibrary.GetEpisodes",
                 {"filter": filt, "limits": {"start": 0, "end": 500}})
        for ep in r.get("result", {}).get("episodes", []):
            jrpc("VideoLibrary.SetEpisodeDetails",
                 {"episodeid": ep["episodeid"], "resume": {"position": 0, "total": 0},
                  "playcount": 0, "lastplayed": ""})


class KidsMonitor(xbmc.Monitor):
    """Nap mode: the bridge sends a NotifyAll('kidstvsleep'/'kidstvwake') when Yatse's
       shutdown/reboot come through, so we stop restarting playback while the tablet is
       'off' (screen dark) and resume when it's woken."""
    def __init__(self):
        xbmc.Monitor.__init__(self)
        self.sleeping = False

    def onNotification(self, sender, method, data):
        if 'kidstvsleep' in method:
            self.sleeping = True
        elif 'kidstvwake' in method:
            self.sleeping = False


monitor = KidsMonitor()
player = xbmc.Player()

# let Kodi finish its startup before touching the player
if monitor.waitForAbort(4):
    raise SystemExit

# ALWAYS start silent (only here - never touched again, so the front-seat level sticks)
xbmc.executebuiltin('SetVolume(0)')

# initial start: retry briskly through cold-boot path/library lag
tries = 0
while not monitor.abortRequested() and not player.isPlaying() and tries < 45:
    start_boot()
    if monitor.waitForAbort(2):
        raise SystemExit
    tries += 1

clean_watch_state()   # start clean

# Keep the show alive forever: restart if playback ever fully stops (Kodi drops it after
# a spell backgrounded - e.g. an admin escape). A 2-tick confirm avoids false restarts on
# the brief gap between episodes. Skipped entirely while asleep (nap mode). Also sweep the
# watch-state clutter every ~5 min so it never builds up.
ticks = 0
while not monitor.abortRequested():
    if monitor.waitForAbort(4):
        break
    try:
        ticks += 1
        if ticks % 75 == 0:
            clean_watch_state()
        if monitor.sleeping:
            continue
        if not player.isPlaying():
            if monitor.waitForAbort(2):
                break
            if monitor.sleeping or player.isPlaying():
                continue
            resume()
    except Exception:
        pass   # never let the self-heal loop die on a transient error

