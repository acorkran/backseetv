#!/system/bin/sh
# BackSeeTV setup helper. BackSeeTV-Tablet-Setup.bat pushes this to the tablet and runs it there,
# so the folder handling stays in a real shell (names with spaces and apostrophes) not in batch.
#
#   sh bst-media.sh list
#       One line per top-level folder, on internal storage or an SD card, that holds videos:
#       <volume>|<folder>|<videos>
#   sh bst-media.sh move <volume> <folder>
#       Moves that folder's shows into <volume>/BackSeeTV-Media. A sub-folder with videos in it is a
#       show; videos lying directly in the folder become one show named after the folder. It's a move
#       on the same card, so nothing is copied and no space is needed. Anything that isn't a video
#       stays where it was.

count() {
    find "$1" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) 2>/dev/null | wc -l
}

volumes() {
    echo /storage/emulated/0
    for v in /storage/*; do
        case "$v" in /storage/emulated|/storage/self) ;; *) [ -d "$v" ] && echo "$v" ;; esac
    done
}

case "$1" in
list)
    volumes | while read -r v; do
        for d in "$v"/*/; do
            d=${d%/}
            [ -d "$d" ] || continue
            n=${d##*/}
            case "$n" in Android|BackSeeTV-Media|.*) continue ;; esac
            c=$(count "$d")
            [ "$c" -gt 0 ] && echo "$v|$n|$c"
        done
    done
    ;;
move)
    v=$2; name=$3; src="$v/$name"; dst="$v/BackSeeTV-Media"
    [ -d "$src" ] || { echo "missing: $src"; exit 1; }
    mkdir -p "$dst"
    for s in "$src"/*/; do
        s=${s%/}
        [ -d "$s" ] || continue
        [ "$(count "$s")" -gt 0 ] || continue
        t="$dst/${s##*/}"
        if [ -e "$t" ]; then
            mkdir -p "$t" && mv "$s"/* "$t"/ 2>/dev/null && rmdir "$s" 2>/dev/null
        else
            mv "$s" "$t"
        fi
    done
    if [ -n "$(find "$src" -maxdepth 1 -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) | head -1)" ]; then
        t="$dst/$name"; mkdir -p "$t"
        find "$src" -maxdepth 1 -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.m4v' \) -exec mv {} "$t"/ \;
    fi
    rmdir "$src" 2>/dev/null
    echo "moved $name: $(count "$dst") videos now in $dst"
    ;;
*)
    echo "usage: bst-media.sh list | move <volume> <folder>"; exit 2 ;;
esac
