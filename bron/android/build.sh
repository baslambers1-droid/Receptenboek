#!/bin/bash
# Builds Receptenboek.apk with plain SDK tools (aapt2, javac, dx, apksigner).
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-/root/dl/sdk/android.jar}
BT=/usr/lib/android-sdk/build-tools/debian
KEYSTORE=${KEYSTORE:-signing/receptenboek.jks}
rm -rf build && mkdir -p build/res build/classes build/gen
aapt2 compile --dir res -o build/res/res.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --java build/gen -A assets build/res/res.zip --auto-add-overlay
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java')
$BT/dx --dex --min-sdk-version=26 --output=build/classes.dex build/classes
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KEYSTORE" --ks-pass pass:receptenboek --key-pass pass:receptenboek \
  --out build/Receptenboek.apk build/aligned.apk
apksigner verify build/Receptenboek.apk && echo "OK: build/Receptenboek.apk"
