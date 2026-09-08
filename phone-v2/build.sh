#!/usr/bin/env bash
# v2 phone remote prototype: buttons -> the tablet player's :8090 control endpoints.
# No-gradle, framework-only, offline-buildable.
set -e
export ANDROID_HOME=$HOME/android-sdk
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDJAR="$ANDROID_HOME/platforms/android-34/android.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
rm -rf build && mkdir -p build/gen build/obj build/apk
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/apk/base.apk -I "$ANDJAR" \
    --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 26 --target-sdk-version 34 build/res.zip
find src build/gen -name '*.java' > build/srcs.txt
javac -d build/obj -classpath "$ANDJAR" -source 8 -target 8 -nowarn @build/srcs.txt
"$BT/d8" --min-api 26 --lib "$ANDJAR" --output build/apk $(find build/obj -name '*.class')
(cd build/apk && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 build/apk/base.apk build/apk/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore \
    -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=KidodiV2Remote" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out kidstv-v2remote.apk build/apk/aligned.apk
echo "BUILT: kidstv-v2remote.apk ($(du -h kidstv-v2remote.apk | cut -f1))"
