#!/usr/bin/env bash
# Build and sign ChessBot Assist APK without Gradle.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
if [ ! -d "$SDK" ] && [ -d "$HOME/android-sdk" ]; then
    SDK="$HOME/android-sdk"
fi
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
WORK="$OUT/.onnx-build"
DEPS="$OUT/deps"
LIB="$ROOT/libs/chesslib-1.3.3.jar"
ORT_VERSION="1.20.0"
ORT_AAR="$DEPS/onnxruntime-android-$ORT_VERSION.aar"
ORT_SHA256="07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027"
NAME="ChessBot-Assist-v1.5.19"

mkdir -p "$OUT" "$DEPS"
rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex" "$WORK/lib/arm64-v8a" "$WORK/lib/armeabi-v7a"

if [ ! -f "$ORT_AAR" ]; then
    curl --fail --location --silent --show-error \
        "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar" \
        -o "$ORT_AAR"
fi
echo "$ORT_SHA256  $ORT_AAR" | sha256sum --check --status || {
    echo "ONNX Runtime Android package checksum mismatch: $ORT_AAR" >&2
    exit 1
}
unzip -p "$ORT_AAR" classes.jar > "$WORK/onnxruntime.jar"
for abi in arm64-v8a armeabi-v7a; do
    unzip -p "$ORT_AAR" "jni/$abi/libonnxruntime.so" > "$WORK/lib/$abi/libonnxruntime.so"
    unzip -p "$ORT_AAR" "jni/$abi/libonnxruntime4j_jni.so" > "$WORK/lib/$abi/libonnxruntime4j_jni.so"
done

echo "[1/6] resources"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$WORK/res.zip"
"$BT/aapt2" link \
    -o "$WORK/base.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$WORK/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 28 \
    --version-code 25 \
    --version-name "1.5.19" \
    --auto-add-overlay \
    "$WORK/res.zip"

echo "[2/6] java"
find "$ROOT/app/src/main/java" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
    -classpath "$PLATFORM:$LIB:$WORK/onnxruntime.jar" \
    -d "$WORK/classes" \
    @"$WORK/sources.txt"

echo "[3/6] dex"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$BT/d8" --lib "$PLATFORM" --min-api 26 --output "$WORK/dex" \
    "$WORK/classes.jar" "$LIB" "$WORK/onnxruntime.jar"

echo "[4/6] package"
cp "$WORK/base.apk" "$WORK/unaligned.apk"
cp "$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so" "$WORK/lib/arm64-v8a/"
cp "$ROOT/app/src/main/jniLibs/armeabi-v7a/libstockfish.so" "$WORK/lib/armeabi-v7a/"
python3 - "$WORK" "$ROOT/app/src/main/assets" <<'PY'
import os
import sys
import zipfile

out, assets = sys.argv[1:3]
apk = os.path.join(out, "unaligned.apk")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    for abi in ("arm64-v8a", "armeabi-v7a"):
        for name in ("libstockfish.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"):
            path = os.path.join(out, "lib", abi, name)
            if os.path.isfile(path):
                z.write(path, "lib/%s/%s" % (abi, name))
    for root, _, files in os.walk(assets):
        for name in files:
            path = os.path.join(root, name)
            z.write(path, os.path.relpath(path, os.path.dirname(assets)))
PY

echo "[5/6] align"
"$BT/zipalign" -f 4 "$WORK/unaligned.apk" "$WORK/aligned.apk"

echo "[6/6] sign"
KS="${CHESSBOT_KEYSTORE:-$ROOT/keystore.jks}"
ALIAS="${CHESSBOT_KEY_ALIAS:-chessbot}"
STORE_PASS="${CHESSBOT_STORE_PASS:?Set CHESSBOT_STORE_PASS for APK signing}"
KEY_PASS="${CHESSBOT_KEY_PASS:-$STORE_PASS}"
if [ ! -f "$KS" ]; then
    echo "Signing keystore not found: $KS" >&2
    exit 1
fi
export CHESSBOT_KEY_PASS="$KEY_PASS"
"$BT/apksigner" sign \
    --ks "$KS" \
    --ks-key-alias "$ALIAS" \
    --ks-pass "env:CHESSBOT_STORE_PASS" \
    --key-pass "env:CHESSBOT_KEY_PASS" \
    --out "$OUT/$NAME.apk" \
    "$WORK/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$NAME.apk" | head -4
ls -lh "$OUT/$NAME.apk"
echo "OK -> $OUT/$NAME.apk"
rm -rf "$WORK"
