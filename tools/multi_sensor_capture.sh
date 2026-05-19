#!/bin/bash
# multi_sensor_capture.sh — Simultaneous multi-sensor capture for fruit ripeness
#
# Captures data from ALL available sensors in one session:
#   1. VL53L1 ToF laser (940nm) — histogram via BPF kprobe
#   2. VD6282 rear spectral (R,G,B,IR@850nm,Clear,Vis) — via sensor framework
#   3. Camera RGB snapshot — via adb screencap/camera API
#
# Usage: ./multi_sensor_capture.sh [duration_sec] [label]
#   duration_sec  - capture duration (default 10)
#   label         - sample label, e.g. "banana_day1" (default "sample")

set -e

DURATION=${1:-10}
LABEL=${2:-sample}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTDIR="capture_${LABEL}_${TIMESTAMP}"
mkdir -p "$OUTDIR"

echo "╔══════════════════════════════════════╗"
echo "║  Multi-Sensor Fruit Ripeness Capture  ║"
echo "╠══════════════════════════════════════╣"
echo "║  Duration: ${DURATION}s"
echo "║  Label:    ${LABEL}"
echo "║  Output:   ${OUTDIR}/"
echo "╚══════════════════════════════════════╝"
echo ""

# Check ADB
if ! ssh h1 "adb shell echo ok" 2>/dev/null | grep -q ok; then
    echo "ERROR: ADB not connected"
    exit 1
fi

# 1. Open camera (activates VD6282 + provides live preview)
echo "[1/4] Opening camera..."
ssh h1 'adb shell am start -a android.media.action.STILL_IMAGE_CAMERA' 2>/dev/null
sleep 2

# 2. Start ToF sensor (needs to stay running for BPF)
echo "[2/4] Starting ToF sensor..."
TOF_COUNT=$((DURATION * 31))  # ~31 Hz at budget 33K
ssh h1 "adb shell 'su -c \"nohup /data/local/tmp/tof -f -n $TOF_COUNT > /dev/null 2>&1 &\"'" 2>/dev/null
sleep 1

# 3. Start BPF histogram capture in background
echo "[3/4] Starting BPF histogram capture..."
HIST_FILE="${OUTDIR}/tof_histogram.csv"
ssh h1 "adb shell 'su -c \"/data/local/tmp/bpf_hist_stream -n $TOF_COUNT\"'" 2>/dev/null > "$HIST_FILE" &
BPF_PID=$!

# 4. Capture VD6282 spectral data
echo "[4/4] Starting spectral capture..."
SPEC_COUNT=$((DURATION * 62))  # ~62 Hz
SPEC_FILE="${OUTDIR}/spectral_vd6282.csv"
ssh h1 "adb shell 'CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -s 65545 -n $SPEC_COUNT'" 2>/dev/null > "$SPEC_FILE" &
SPEC_PID=$!

# 5. Take camera snapshot
echo "Taking camera snapshot..."
sleep 2
ssh h1 "adb shell 'input keyevent KEYCODE_CAMERA'" 2>/dev/null
sleep 1
# Pull latest camera image
LATEST=$(ssh h1 "adb shell 'ls -t /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -1'" 2>/dev/null)
if [ -n "$LATEST" ]; then
    ssh h1 "adb pull '$LATEST' '${OUTDIR}/camera_rgb.jpg'" 2>/dev/null
fi

# Wait for captures to finish
echo ""
echo "Waiting for captures to complete..."
wait $BPF_PID 2>/dev/null || true
wait $SPEC_PID 2>/dev/null || true

# Summary
echo ""
echo "═══════════════════════════════════════"
echo "Capture complete: $OUTDIR/"
echo ""
for f in "$OUTDIR"/*; do
    LINES=$(wc -l < "$f" 2>/dev/null | tr -d ' ' || echo "binary")
    SIZE=$(ls -lh "$f" | awk '{print $5}')
    echo "  $(basename $f): $LINES lines, $SIZE"
done
echo ""
echo "Run analysis:"
echo "  python3 tools/analyze_ripeness.py $OUTDIR/"
