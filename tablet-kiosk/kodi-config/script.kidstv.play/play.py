import sys
import json
import xbmc
import xbmcvfs

# RunScript(script.kidstv.play,<arg>)
#   <arg> = a smart-playlist basename (e.g. Bluey, ShuffleAll)  -> load that .xsp
#   <arg> = a media FILE path (/storage/.../x.mp4, or special://...) -> play that single file
# CLEARS the video queue first either way, so a front-seat switch REPLACES the queue instead of
# appending (raw PlayMedia appends -> queue bloat). Playing a raw file is done via the PlayMedia
# builtin IN-PROCESS: on this Kodi build, JSON-RPC Player.Open{file}/Playlist.Add{file} silently
# fail to queue/start a raw path, but PlayMedia (run inside Kodi) plays it fine. This is how an
# uploaded file (phone -> tablet) gets played.
arg = sys.argv[1] if len(sys.argv) > 1 else "ShuffleAll"

# RunScript(script.kidstv.play,rebuild) -> wipe + regenerate the content-agnostic all-shows shuffle
# from the CURRENT library, then play it. Use after content changes (e.g. you hid/removed a folder
# that shouldn't be in the kid mix). The shuffle is "all episodes EXCEPT anything under a folder whose
# name contains '!hidden'", so hiding content is just putting '!hidden' in its folder name. (The marker
# is literal on purpose - a '_' or '%' would be a SQL LIKE wildcard and blank the whole library.)
SHUFFLE_XSP = "special://profile/playlists/video/ShuffleAll.xsp"
if arg == "rebuild":
    try:
        xbmcvfs.delete(SHUFFLE_XSP)
        f = xbmcvfs.File(SHUFFLE_XSP, 'w')
        f.write(
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<smartplaylist type="episodes">\n'
            '    <name>Shuffle All Shows</name>\n'
            '    <match>all</match>\n'
            '    <rule field="path" operator="doesnotcontain"><value>!hidden</value></rule>\n'
            '    <order direction="ascending">random</order>\n'
            '</smartplaylist>\n'
        )
        f.close()
    except Exception:
        pass
    xbmc.executebuiltin('CleanLibrary(video)')   # drop library entries for files that are gone
    arg = "ShuffleAll"                            # fall through and play the fresh shuffle

if arg == "savem3u":
    # RunScript(script.kidstv.play, savem3u, <name>, <tmp_m3u_path>) -> write a ready-made .m3u into
    # the playlists folder so it shows up as a tile. No playback. The remote/bridge can't write Kodi's
    # own dir (scoped storage), so it hands us the finished .m3u as a temp file (which we then delete).
    name = sys.argv[2] if len(sys.argv) > 2 else "Playlist"
    tmp = sys.argv[3] if len(sys.argv) > 3 else ""
    name = name.replace("/", "").replace("\\", "").strip()
    if name[-4:].lower() == ".m3u":
        name = name[:-4].strip()
    if not name:
        name = "Playlist"
    try:
        src = xbmcvfs.File(tmp)
        data = src.read()
        src.close()
        out = xbmcvfs.File("special://profile/playlists/video/%s.m3u" % name, 'w')
        out.write(data)
        out.close()
        xbmcvfs.delete(tmp)
    except Exception:
        pass
    sys.exit()

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
