#!/usr/bin/env bash
# v2 own-player prototype: one app = player + kiosk shell + control + Wi-Fi Direct anchor,
# no Kodi. Same no-gradle toolchain as tablet-kiosk (framework android.jar only), so it
# builds fully offline. MediaPlayer is the prototype engine; ExoPlayer/Media3 is the
# production swap (needs a one-time online gradle fetch, not available offline here).
set -e
export ANDROID_HOME=$HOME/android-sdk
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDJAR="$ANDROID_HOME/platforms/android-34/android.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
rm -rf build && mkdir -p build/gen build/obj build/apk

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/7] aapt2 link -> base.apk + R.java"
"$BT/aapt2" link -o build/apk/base.apk -I "$ANDJAR" \
    --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 26 --target-sdk-version 34 \
    build/res.zip

echo "[3/7] javac"
find src build/gen -name '*.java' > build/srcs.txt
javac -d build/obj -classpath "$ANDJAR" -source 8 -target 8 -nowarn @build/srcs.txt

echo "[4/7] d8 -> classes.dex"
"$BT/d8" --min-api 26 --lib "$ANDJAR" --output build/apk $(find build/obj -name '*.class')

echo "[5/7] add classes.dex to apk"
(cd build/apk && zip -q base.apk classes.dex)

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 build/apk/base.apk build/apk/aligned.apk

echo "[7/7] sign"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=KidodiPlayer" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out kidstv-player.apk build/apk/aligned.apk
echo "BUILT: kidstv-player.apk ($(du -h kidstv-player.apk | cut -f1))"
