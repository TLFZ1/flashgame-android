#!/usr/bin/env bash
# Build the APK without Gradle.
# Env vars (override as needed):
#   JAVA_HOME      JDK 17
#   BUILD_TOOLS    path to android build-tools (aapt2, zipalign, apksigner, lib/d8.jar)
#   ANDROID_JAR    path to platforms/android-34/android.jar
#   KEYSTORE       signing keystore (default: app/debug.keystore if present)
#   VERSION_CODE   optional: override versionCode without touching the manifest
#   VERSION_NAME   optional: override versionName without touching the manifest
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
BUILD_TOOLS="${BUILD_TOOLS:?set BUILD_TOOLS to android build-tools dir}"
ANDROID_JAR="${ANDROID_JAR:?set ANDROID_JAR to platforms/android-34/android.jar}"
KEYSTORE="${KEYSTORE:-app/debug.keystore}"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "==> javac"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" app/src/com/dsh/flashserver/*.java

echo "==> d8"
"$JAVA_HOME/bin/java" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT"/classes/com/dsh/flashserver/*.class

echo "==> aapt2 compile"
"$BUILD_TOOLS/aapt2" compile --dir app/res -o "$OUT/res.zip"

# 版本号可在构建时覆盖（不写回 AndroidManifest.xml，git 里不留噪音）
EXTRA=()
# aapt2 默认只在 manifest 缺该字段时注入，要强制覆盖必须加 --replace-version
if [ -n "${VERSION_CODE:-}" ] || [ -n "${VERSION_NAME:-}" ]; then EXTRA+=(--replace-version); fi
if [ -n "${VERSION_CODE:-}" ]; then EXTRA+=(--version-code "$VERSION_CODE"); fi
if [ -n "${VERSION_NAME:-}" ]; then EXTRA+=(--version-name "$VERSION_NAME"); fi

echo "==> aapt2 link"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest app/AndroidManifest.xml -R "$OUT/res.zip" -A app/assets \
  --auto-add-overlay --min-sdk-version 24 --target-sdk-version 34 \
  "${EXTRA[@]+"${EXTRA[@]}"}"

echo "==> package dex"
python - "$OUT" <<'PY'
import zipfile, shutil, sys
out = sys.argv[1]
shutil.copyfile(f'{out}/base.apk', f'{out}/unsigned.apk')
with zipfile.ZipFile(f'{out}/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(f'{out}/dex/classes.dex', 'classes.dex')
PY

if [ ! -f "$KEYSTORE" ]; then
  echo "==> no keystore, creating debug one"
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "==> zipalign + sign"
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$JAVA_HOME/bin/java" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/FlashServer.apk" "$OUT/aligned.apk"

echo "==> done: $OUT/FlashServer.apk"
