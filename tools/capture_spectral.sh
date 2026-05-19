#!/bin/bash
# capture_spectral.sh — Capture VD6282 spectral data from Pixel 7 Pro
#
# Usage: ./capture_spectral.sh [count] [sensor_type]
#   count       - number of events (default 200)
#   sensor_type - 65545 for VD6282 rear light (default)
#                 5 for TMD3719 ambient light
#
# Prerequisites:
#   - Phone connected via ADB (authorized)
#   - spectral.dex deployed to /data/local/tmp/
#   - For VD6282: camera app must be open (activates the sensor)

set -e

COUNT=${1:-200}
SENSOR=${2:-65545}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTFILE="spectral_${SENSOR}_${COUNT}_${TIMESTAMP}.csv"

echo "=== Pixel 7 Pro Spectral Capture ==="
echo "Sensor type: $SENSOR"
echo "Events: $COUNT"
echo "Output: $OUTFILE"

# Check ADB
if ! ssh h1 "adb shell echo ok" 2>/dev/null | grep -q ok; then
    echo "ERROR: ADB not connected. Check USB and authorization."
    exit 1
fi

# Deploy DEX if needed
ssh h1 "adb shell ls /data/local/tmp/spectral.dex 2>/dev/null" 2>/dev/null || {
    echo "Deploying spectral.dex..."
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    DEX="${SCRIPT_DIR}/../build/spectral.dex"
    if [ ! -f "$DEX" ]; then
        echo "Building DEX..."
        bash "${SCRIPT_DIR}/build_sensor_tools.sh"
        DEX="/tmp/pixel-sensor-lab-build/dex/classes.dex"
    fi
    scp "$DEX" h1:/tmp/spectral.dex
    ssh h1 "adb push /tmp/spectral.dex /data/local/tmp/spectral.dex"
}

# Check if VD6282 is active (only matters for type 65545)
if [ "$SENSOR" = "65545" ]; then
    echo ""
    echo "NOTE: VD6282 rear light sensor activates when the camera app is open."
    echo "      Make sure the camera app is running on the phone."
    echo ""
    
    # Check if sensor is currently active
    ACTIVE=$(ssh h1 'adb shell dumpsys sensorservice 2>/dev/null' 2>/dev/null | grep "0x0101001c" | head -1)
    if [ -z "$ACTIVE" ]; then
        echo "WARNING: VD6282 (0x0101001c) not showing as active in sensorservice."
        echo "         Opening camera to activate it..."
        ssh h1 'adb shell am start -a android.media.action.STILL_IMAGE_CAMERA' 2>/dev/null
        sleep 3
    fi
fi

echo "Capturing..."
ssh h1 "adb shell 'CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -s $SENSOR -n $COUNT'" 2>/dev/null > "$OUTFILE"

LINES=$(wc -l < "$OUTFILE" | tr -d ' ')
echo "Captured $LINES lines to $OUTFILE"

# Quick analysis
if [ "$LINES" -gt 1 ]; then
    echo ""
    echo "=== Quick Stats ==="
    # Show first few data lines
    head -5 "$OUTFILE"
    echo "..."
    tail -3 "$OUTFILE"
fi
