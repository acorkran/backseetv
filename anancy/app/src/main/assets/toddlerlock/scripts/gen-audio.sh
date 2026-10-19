#!/usr/bin/env bash
# OPTIONAL: generate high-quality neural voice clips with Piper (local, FOSS,
# Apache-2.0). Toddler Lock works WITHOUT this — it falls back to your OS's
# built-in speech. Run this only if you want the nicer "Neural voice" option.
#
# Produces web/audio/<voice>/{A..Z,0..9}.wav + a voices.json manifest. The piper
# binary and voice models are cached in ~/.local/share/piper (gitignored).
# Needs: curl, tar, ffmpeg.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"          # web/
OUT="$ROOT/audio"
PIPER_HOME="${PIPER_HOME:-$HOME/.local/share/piper}"
PIPER_DIR="$PIPER_HOME/piper"
PIPER_BIN="$PIPER_DIR/piper"
PIPER_VER="2023.11.14-2"
VOICES_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
PAD_MS="${PAD_MS:-200}"

mkdir -p "$OUT" "$PIPER_HOME"

if [ ! -x "$PIPER_BIN" ]; then
  echo "downloading piper $PIPER_VER ..."
  curl -fSL -o "$PIPER_HOME/piper.tgz" "https://github.com/rhasspy/piper/releases/download/$PIPER_VER/piper_linux_$(uname -m).tar.gz"
  tar -xzf "$PIPER_HOME/piper.tgz" -C "$PIPER_HOME"; rm -f "$PIPER_HOME/piper.tgz"
fi
ESPEAK_DATA="$PIPER_DIR/espeak-ng-data"

# id | label | model path under VOICES_BASE
# Only COMMERCIALLY-LICENSED voices (verified June 2026): jenny (Dioco — "commercial use
# permitted"), cori (LibriVox — public domain), ljspeech (LJ Speech — public domain).
# Amy was dropped: its mimic3 source license couldn't be confirmed for commercial use.
VOICES=(
  "jenny|Jenny (British)|en/en_GB/jenny_dioco/medium/en_GB-jenny_dioco-medium"
  "cori|Cori (British, HQ)|en/en_GB/cori/high/en_GB-cori-high"
  "linda|Linda (American)|en/en_US/ljspeech/medium/en_US-ljspeech-medium"
)
declare -A ANIMAL=(
  [A]=Alligator [B]=Bear [C]=Cat [D]=Dad [E]=Elephant [F]=Frog [G]=Goat [H]=Hippo
  [I]=Iguana [J]=Jellyfish [K]=Koala [L]=Lion [M]=Mum [N]=Narwhal [O]=Octopus
  [P]=Penguin [Q]=Quokka [R]=Rabbit [S]=Snake [T]=Tiger [U]=Unicorn [V]=Vulture
  [W]=Whale [X]=Fox [Y]=Yak [Z]=Zebra )
# AUDIO-ONLY respellings (on-screen letter/word unchanged). LSAY: spell a letter for
# audio. Only "A"->"Eigh" (bare A is the article); plain capital "I" already renders soft
# alongside the "igwana" spelling below. ASAY: respell animals espeak gets wrong.
# "igwana" -> Iguana as "ig-WAH-na" (NO "h": "igwahna" mis-stresses it "EEG-wah-na").
declare -A LSAY=( [A]=Eigh )
declare -A ASAY=( [I]=igwana )
WORDS=(zero one two three four five six seven eight nine)

say() {
  local model="$1"; local wav="$2"; local text="$3"; local tmp="${wav}.raw"
  printf '%s\n' "$text" | "$PIPER_BIN" -m "$model" --espeak_data "$ESPEAK_DATA" -f "$tmp" >/dev/null 2>&1
  ffmpeg -y -loglevel error -i "$tmp" -af "adelay=${PAD_MS}" "$wav" </dev/null
  rm -f "$tmp"
}

entries=()
for spec in "${VOICES[@]}"; do
  IFS='|' read -r id label path <<<"$spec"
  model="$PIPER_HOME/$(basename "$path").onnx"
  if [ ! -f "$model" ]; then
    echo "downloading voice: $id ($label) ..."
    curl -fSL -o "$model" "$VOICES_BASE/$path.onnx"
    curl -fSL -o "$model.json" "$VOICES_BASE/$path.onnx.json"
  fi
  vdir="$OUT/$id"; mkdir -p "$vdir"; echo "rendering $id ..."
  # "<letter> is for <animal>." — no mid-word pause, but a TRAILING period so every clip
  # gets the same declarative falling intonation instead of wandering/rising.
  for L in {A..Z}; do
    lsay="$L";              [ -n "${LSAY[$L]:-}" ] && lsay="${LSAY[$L]}"
    asay="${ANIMAL[$L]}";   [ -n "${ASAY[$L]:-}" ] && asay="${ASAY[$L]}"
    say "$model" "$vdir/$L.wav" "$lsay is for $asay."
  done
  for N in {0..9}; do say "$model" "$vdir/$N.wav" "${WORDS[$N]}"; done
  entries+=("{\"id\":\"$id\",\"label\":\"$label\",\"dir\":\"$id\"}")
done
printf '[%s]\n' "$(IFS=,; echo "${entries[*]}")" > "$OUT/voices.json"
echo "done: $((${#VOICES[@]} * 36)) clips in $OUT"

# The playground waits each clip's OWN length before it will speak another letter, so the
# timing map has to be rebuilt whenever the clips are. Never ship one without the other.
"$(dirname "$0")/gen-timings.sh"
