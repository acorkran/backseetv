import sys
import json
import xbmc

# RunScript(script.kidstv.play,<Name>) - Name is the .xsp basename (e.g. Bluey, ShuffleAll).
# CLEARS the video queue then loads+plays that smart playlist, so front-seat playlist
# switches REPLACE the queue instead of appending (PlayMedia appends -> queue bloat).
name = sys.argv[1] if len(sys.argv) > 1 else "ShuffleAll"
path = "special://profile/playlists/video/%s.xsp" % name


def j(method, params):
    xbmc.executeJSONRPC(json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}))


j("Playlist.Clear", {"playlistid": 1})
j("Playlist.Add", {"playlistid": 1, "item": {"directory": path}})
j("Player.Open", {"item": {"playlistid": 1}})
xbmc.sleep(500)
# only the all-shows mix shuffles; per-show playlists play in episode order
xbmc.executebuiltin("PlayerControl(RandomOn)" if name == "ShuffleAll" else "PlayerControl(RandomOff)")
xbmc.executebuiltin("PlayerControl(RepeatAll)")
