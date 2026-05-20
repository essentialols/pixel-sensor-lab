#!/bin/bash
# Build and deploy the Fruit Ripeness app (daemon + APK)
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
NDK="$HOME/Library/Android/sdk/ndk/27.0.12077973"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++"
SYSROOT="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot"
BUILD_TOOLS="$HOME/Library/Android/sdk/build-tools/35.0.0"
PLATFORM="$HOME/Library/Android/sdk/platforms/$(ls "$HOME/Library/Android/sdk/platforms/" | sort -V | tail -1)"

echo "=== Building Fruit Ripeness App ==="

# 1. Build native daemon
echo "[1/4] Building ripeness_daemon..."
$CC -std=c++17 -O2 -Wall -Wextra -Wno-c++11-extensions -static-libstdc++ \
    --sysroot="$SYSROOT" \
    -o "$REPO/app/ripeness_daemon" \
    "$REPO/tools/ripeness_daemon.cpp" -ldl -llog
echo "  Built: app/ripeness_daemon"

# 2. Compile APK
echo "[2/4] Compiling Java..."
mkdir -p "$REPO/app/obj" "$REPO/app/dex"

"$BUILD_TOOLS/aapt" package -f -m \
    -S "$REPO/app/res" \
    -J "$REPO/app/obj" \
    -M "$REPO/app/AndroidManifest.xml" \
    -I "$PLATFORM/android.jar" 2>/dev/null || true

javac -source 1.8 -target 1.8 \
    -classpath "$PLATFORM/android.jar" \
    -d "$REPO/app/obj" \
    "$REPO/app/src/RipenessActivity.java" \
    "$REPO/app/src/RecordingService.java"

echo "[3/4] Creating APK..."
"$BUILD_TOOLS/d8" --output "$REPO/app/dex" \
    $(find "$REPO/app/obj" -name "*.class")

"$BUILD_TOOLS/aapt" package -f \
    -M "$REPO/app/AndroidManifest.xml" \
    -I "$PLATFORM/android.jar" \
    -F "$REPO/app/ripeness-unsigned.apk" 2>/dev/null

cd "$REPO/app/dex"
zip -j "$REPO/app/ripeness-unsigned.apk" classes.dex 2>/dev/null

# Sign with debug key
if [ ! -f "$HOME/.android/debug.keystore" ]; then
    keytool -genkey -v -keystore "$HOME/.android/debug.keystore" \
        -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Debug, O=Android, C=US" 2>/dev/null
fi

"$BUILD_TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$REPO/app/ripeness.apk" \
    "$REPO/app/ripeness-unsigned.apk"

echo "  Built: app/ripeness.apk"

# 4. Deploy
echo "[4/4] Deploying to device..."
scp "$REPO/app/ripeness_daemon" h1:/tmp/ 2>/dev/null
ssh h1 "adb push /tmp/ripeness_daemon /data/local/tmp/ripeness_daemon && adb shell chmod 755 /data/local/tmp/ripeness_daemon" 2>/dev/null
scp "$REPO/app/ripeness.apk" h1:/tmp/ 2>/dev/null
ssh h1 "adb install -r /tmp/ripeness.apk" 2>/dev/null

echo ""
echo "=== Deploy Complete ==="
echo "To run:"
echo "  1. Start daemon:  ssh h1 'adb shell su -c \"/data/local/tmp/ripeness_daemon -p 8765\"'"
echo "  2. Launch app:    ssh h1 'adb shell am start -n com.spectral.ripeness/.RipenessActivity'"
