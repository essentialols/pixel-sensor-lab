#!/bin/bash
# quickstart.sh — Deploy and test spectral sensor tools
# Run this after re-authorizing ADB on the phone.

set -e

echo "=== Checking ADB ==="
if ! ssh h1 "adb shell echo ok" 2>/dev/null | grep -q ok; then
    echo "ERROR: ADB not authorized. Tap 'Allow USB Debugging' on the phone."
    exit 1
fi
echo "ADB: OK"

echo ""
echo "=== Deploying SpectralCapture ==="
scp /tmp/spec_dex/classes.dex h1:/tmp/spectral.dex 2>/dev/null
ssh h1 "adb push /tmp/spectral.dex /data/local/tmp/spectral.dex" 2>/dev/null
echo "Deployed: spectral.dex"

echo ""
echo "=== Listing sensors ==="
ssh h1 'adb shell "CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -l" 2>&1' 2>/dev/null

echo ""
echo "=== Checking VD6282 status ==="
ssh h1 'adb shell dumpsys sensorservice 2>/dev/null' 2>/dev/null | grep "VD6282\|rear_light\|0x0101001c"

echo ""
echo "=== Opening camera to activate VD6282 ==="
ssh h1 'adb shell am start -a android.media.action.STILL_IMAGE_CAMERA' 2>/dev/null
sleep 3

echo ""
echo "=== Attempting VD6282 capture (10 events) ==="
ssh h1 "adb shell 'CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -s 65545 -n 10' 2>&1" 2>/dev/null | tee /tmp/spectral_test.csv

LINES=$(wc -l < /tmp/spectral_test.csv | tr -d ' ')
echo ""
if [ "$LINES" -gt 1 ]; then
    echo "SUCCESS! Got $LINES lines of spectral data."
    echo "VD6282 channels:"
    head -5 /tmp/spectral_test.csv
else
    echo "No data captured. VD6282 may need camera to be actively previewing."
    echo ""
    echo "Try TMD3719 ambient light instead:"
    echo "  ssh h1 'adb shell \"CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -s 5 -n 10\"'"
fi
