#!/usr/bin/env bash
# Render HQ neural clips for NON-English languages with Piper. Companion to gen-audio.sh.
# Voices are license-clean for commercial use:
#   es -> davefx (CC0, public domain)        fr -> siwis (CC-BY 4.0, attribute SIWIS)
# Word lists MUST match web/app.js LANGS. Produces web/audio/<id>/{A..Z,0..9}.wav.
# Needs: curl, tar, ffmpeg (same as gen-audio.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"          # web/
OUT="$ROOT/audio"
PIPER_HOME="${PIPER_HOME:-$HOME/.local/share/piper}"
PIPER_BIN="$PIPER_HOME/piper/piper"
ESPEAK_DATA="$PIPER_HOME/piper/espeak-ng-data"
VOICES_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
PAD_MS="${PAD_MS:-200}"
mkdir -p "$OUT"

say() {
  local model="$1" wav="$2" text="$3" tmp="${2}.raw"
  printf '%s\n' "$text" | "$PIPER_BIN" -m "$model" --espeak_data "$ESPEAK_DATA" -f "$tmp" >/dev/null 2>&1
  ffmpeg -y -loglevel error -i "$tmp" -af "adelay=${PAD_MS}" "$wav" </dev/null
  rm -f "$tmp"
}

fetch_model() {
  local path="$1" model="$PIPER_HOME/$(basename "$1").onnx"
  if [ ! -f "$model" ]; then
    echo "downloading $(basename "$path") ..." >&2
    curl -fSL -o "$model" "$VOICES_BASE/$path.onnx" >&2
    curl -fSL -o "$model.json" "$VOICES_BASE/$path.onnx.json" >&2
  fi
  echo "$model"   # only the path goes to stdout (captured by the caller)
}

render() {
  # $1=voice id, $2=model path, $3=connector ("de"/"comme"), then 26 words + 10 numbers via globals
  local id="$1" model; model="$(fetch_model "$2")"; local conn="$3"
  local dir="$OUT/$id"; mkdir -p "$dir"; echo "rendering $id ..."
  local L
  for L in {A..Z}; do say "$model" "$dir/$L.wav" "$L $conn ${WORDS[$L]}."; done
  local n; for n in {0..9}; do say "$model" "$dir/$n.wav" "${NUMS[$n]}"; done
}

# ---- Spanish (davefx, CC0) ----
declare -A WORDS=(
  [A]=Abeja [B]=Ballena [C]=Conejo [D]=Delfín [E]=Elefante [F]=Foca [G]=Gato
  [H]=Hipopótamo [I]=Iguana [J]=Jirafa [K]=Koala [L]=León [M]=Mamá [N]=Nutria
  [O]=Oso [P]=Perro [Q]=Queso [R]=Rana [S]=Serpiente [T]=Tigre [U]=Unicornio
  [V]=Vaca [W]=Wombat [X]=Xilófono [Y]=Yoyó [Z]=Zorro )
NUMS=(cero uno dos tres cuatro cinco seis siete ocho nueve)
render "davefx" "es/es_ES/davefx/medium/es_ES-davefx-medium" "de"

# ---- French (siwis, CC-BY 4.0) ----
declare -A WORDS=(
  [A]=Abeille [B]=Baleine [C]=Chat [D]=Dauphin [E]=Éléphant [F]=Fraise [G]=Gâteau
  [H]=Hibou [I]=Igloo [J]=Jus [K]=Koala [L]=Lion [M]=Maman [N]=Nuage [O]=Ours
  [P]=Poisson [Q]=Quokka [R]=Renard [S]=Serpent [T]=Tigre [U]=Ukulélé [V]=Vache
  [W]=Wagon [X]=Xylophone [Y]=Yack [Z]=Zèbre )
NUMS=(zéro un deux trois quatre cinq six sept huit neuf)
render "siwis" "fr/fr_FR/siwis/medium/fr_FR-siwis-medium" "comme"

echo "done: es (davefx) + fr (siwis) clips in $OUT"
