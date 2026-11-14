#!/system/bin/sh
# BackSeeTV setup helper. BackSeeTV-Tablet-Setup.bat pushes this to the tablet and runs it there.
#
# Folder names come from the tablet's storage (an SD card can carry any name), so they never travel
# to the PC as text a shell would run, and never come back in a command. The PC only ever sees a
# number and a cleaned-up name for display, and answers with the number.
#
#   sh bst-media.sh list
#       One line per top-level folder, on internal storage or an SD card, that holds videos:
#       <number>|<internal or sd>|<display name>|<videos>
#   sh bst-media.sh move <number>
#       Moves the VIDEO files of that folder into <same card>/BackSeeTV-Media. A sub-folder with videos
#       becomes a show; videos lying directly in the folder become one show named after the folder.
#       It's a move on the same card, so nothing is copied and no space is needed. Nothing that isn't a
#       video moves, and nothing already in BackSeeTV-Media is overwritten.

count() { find "$1" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) 2>/dev/null | wc -l; }

volumes() {
    echo /storage/emulated/0
    for v in /storage/*; do
        case "$v" in /storage/emulated|/storage/self) ;; *) [ -d "$v" ] && echo "$v" ;; esac
    done
}

# Every candidate folder, in a fixed order, one full path per line. "list" and "move" both use this,
# so a number means the same folder to both.
candidates() {
    volumes | while read -r v; do
        for d in "$v"/*/; do
            d=${d%/}
            [ -d "$d" ] || continue
            case "${d##*/}" in
                Android|BackSeeTV-Media|DCIM|Pictures|.*) continue ;;   # never offer the camera's own videos
            esac
            [ "$(count "$d")" -gt 0 ] && echo "$d"
        done
    done
}

# A name safe to show in a Windows console: letters, digits, space and . _ - ( ) ' only.
safe() { printf '%s' "$1" | tr -c "A-Za-z0-9 ._()'-" '?' | cut -c1-60; }

# Move the videos under $1 into show folder $2, never overwriting.
move_videos() {
    mkdir -p "$2"
    find "$1" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) 2>/dev/null | while IFS= read -r f; do
        mv -n "$f" "$2"/
    done
}

case "$1" in
list)
    i=0
    candidates | while read -r d; do
        i=$((i + 1))
        case "$d" in /storage/emulated/0/*) where=internal ;; *) where=sd ;; esac
        echo "$i|$where|$(safe "${d##*/}")|$(count "$d")"
    done
    ;;
move)
    case "$2" in ''|*[!0-9]*) echo "move needs a number"; exit 2 ;; esac
    src=$(candidates | sed -n "${2}p")
    [ -n "$src" ] && [ -d "$src" ] || { echo "no folder $2"; exit 1; }
    case "$src" in /storage/emulated/0/*) v=/storage/emulated/0 ;; *) v=${src%/*} ;; esac
    dst="$v/BackSeeTV-Media"; name=${src##*/}
    for s in "$src"/*/; do
        s=${s%/}
        [ -d "$s" ] || continue
        [ "$(count "$s")" -gt 0 ] || continue
        move_videos "$s" "$dst/${s##*/}"
    done
    if [ -n "$(find "$src" -maxdepth 1 -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) | head -1)" ]; then
        mkdir -p "$dst/$name"
        find "$src" -maxdepth 1 -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) | while IFS= read -r f; do mv -n "$f" "$dst/$name"/; done
    fi
    find "$src" -depth -type d -empty -exec rmdir {} \; 2>/dev/null   # tidy only folders left empty
    echo "moved $(safe "$name"): $(count "$dst") videos now in BackSeeTV-Media"
    ;;
*)
    echo "usage: bst-media.sh list | move <number>"; exit 2 ;;
esac
