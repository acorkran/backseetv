import sys
import json
import xbmc

# RunScript(script.kidstv.play,<arg>)
#   <arg> = a smart-playlist basename (e.g. Bluey, ShuffleAll)  -> load that .xsp
#   <arg> = a media FILE path (/storage/.../x.mp4, or special://...) -> play that single file
# CLEARS the video queue first either way, so a front-seat switch REPLACES the queue instead of
# appending (raw PlayMedia appends -> queue bloat). Playing a raw file is done via the PlayMedia
# builtin IN-PROCESS: on this Kodi build, JSON-RPC Player.Open{file}/Playlist.Add{file} silently
# fail to queue/start a raw path, but PlayMedia (run inside Kodi) plays it fine. This is how an
# uploaded file (phone -> tablet) gets played.
arg = sys.argv[1] if len(sys.argv) > 1 else "ShuffleAll"

MEDIA_EXT = (".mp4", ".mkv", ".avi", ".m4v", ".mov", ".webm", ".ts", ".flv")
is_file = arg.startswith("/") or arg.startswith("special://") or arg.lower().endswith(MEDIA_EXT)


def j(method, params):
    xbmc.executeJSONRPC(json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}))


j("Playlist.Clear", {"playlistid": 1})
if is_file:
    # single uploaded/local file: play it in-process and loop it (a chosen clip repeats for the kid)
    xbmc.executebuiltin('PlayMedia("%s")' % arg)
    xbmc.sleep(500)
    xbmc.executebuiltin("PlayerControl(RepeatOne)")
else:
    path = "special://profile/playlists/video/%s.xsp" % arg
    j("Playlist.Add", {"playlistid": 1, "item": {"directory": path}})
    j("Player.Open", {"item": {"playlistid": 1}})
    xbmc.sleep(500)
    # only the all-shows mix shuffles; per-show playlists play in episode order
    xbmc.executebuiltin("PlayerControl(RandomOn)" if arg == "ShuffleAll" else "PlayerControl(RandomOff)")
    xbmc.executebuiltin("PlayerControl(RepeatAll)")
