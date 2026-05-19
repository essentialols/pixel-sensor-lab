#!/bin/bash
# Build Java sensor tools for Pixel 7 Pro
# Requires: javac, Android SDK build-tools (d8)

set -e

D8=$(ls ~/Library/Android/sdk/build-tools/*/d8 2>/dev/null | tail -1)
if [ -z "$D8" ]; then
    echo "ERROR: d8 not found. Install Android SDK build-tools."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="/tmp/pixel-sensor-lab-build"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/cls" "$BUILD_DIR/dex"

echo "Compiling SensorCapture.java..."
javac -source 11 -target 11 -d "$BUILD_DIR/cls" "$SCRIPT_DIR/SensorCapture.java"

echo "Converting to DEX..."
$D8 --output "$BUILD_DIR/dex" "$BUILD_DIR/cls"/*.class

echo "Output: $BUILD_DIR/dex/classes.dex"
echo ""
echo "Deploy:"
echo "  adb push $BUILD_DIR/dex/classes.dex /data/local/tmp/sensor_capture.dex"
echo ""
echo "Run:"
echo "  adb shell 'CLASSPATH=/data/local/tmp/sensor_capture.dex app_process / SensorCapture -l'"
echo "  adb shell 'CLASSPATH=/data/local/tmp/sensor_capture.dex app_process / SensorCapture -s 65545 -n 100'"
