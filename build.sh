#!/bin/bash
# Build APK không cần Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PJ=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
echo "build-tools: $BT"; echo "android.jar: $PJ"

rm -rf out && mkdir -p out/obj out/dex

"$BT/aapt2" link -o out/base.apk -I "$PJ" --manifest AndroidManifest.xml \
  --min-sdk-version 30 --target-sdk-version 34 --version-code 1 --version-name 1.0 -0 arsc
javac --release 8 -Xlint:-options -classpath "$PJ" -d out/obj $(find src -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$PJ" --output out/dex $(find out/obj -name '*.class')

cp out/base.apk out/unsigned.apk
(cd out/dex && zip -uj ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner" sign --ks release.jks --ks-pass pass:literec123 --key-pass pass:literec123 \
  --out out/app.apk out/aligned.apk
"$BT/apksigner" verify out/app.apk && ls -l out/app.apk
