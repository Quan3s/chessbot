#!/usr/bin/env bash
# Build and sign ChessBot Assist APK without Gradle.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
LIB="$ROOT/libs/chesslib-1.3.3.jar"
NAME="ChessBot-Assist-v1.5.5"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] resources"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 28 \
    --version-code 11 \
    --version-name "1.5.5" \
    --auto-add-overlay \
    "$OUT/res.zip"

echo "[2/6] java"
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
    -classpath "$PLATFORM:$LIB" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[3/6] dex"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$PLATFORM" --min-api 26 --output "$OUT/dex" \
    "$OUT/classes.jar" "$LIB"

echo "[4/6] package"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
mkdir -p "$OUT/lib/arm64-v8a" "$OUT/lib/armeabi-v7a"
cp "$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so" "$OUT/lib/arm64-v8a/"
cp "$ROOT/app/src/main/jniLibs/armeabi-v7a/libstockfish.so" "$OUT/lib/armeabi-v7a/"
python3 - "$OUT" <<'PY'
import os
import sys
import zipfile

out = sys.argv[1]
apk = os.path.join(out, "unaligned.apk")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    for abi in ("arm64-v8a", "armeabi-v7a"):
        z.write(os.path.join(out, "lib", abi, "libstockfish.so"),
                "lib/%s/libstockfish.so" % abi)
PY

echo "[5/6] align"
"$BT/zipalign" -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

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
    "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$NAME.apk" | head -4
ls -lh "$OUT/$NAME.apk"
echo "OK -> $OUT/$NAME.apk"
