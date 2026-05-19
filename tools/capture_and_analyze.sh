#!/bin/bash
# capture_and_analyze.sh - One-shot spectral capture, calibrate, and analyze
#
# Usage: ./capture_and_analyze.sh [NUM_SAMPLES] [LABEL]
# Example: ./capture_and_analyze.sh 100 "indoor_desk_lamp"

set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLES=${1:-50}
LABEL=${2:-"capture"}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTNAME="spectral_${LABEL}_${TIMESTAMP}"

echo "=== Pixel 7 Pro Spectral Capture ==="
echo "Samples: $SAMPLES  Label: $LABEL"

# Deploy binary if needed
echo "[1/4] Deploying spectral_reader..."
scp "$REPO/tools/spectral_usf/spectral_reader" h1:/tmp/ 2>/dev/null
ssh h1 "adb push /tmp/spectral_reader /data/local/tmp/spectral_reader" 2>/dev/null

# Capture
echo "[2/4] Capturing $SAMPLES samples..."
ssh h1 "adb shell su -c '/data/local/tmp/spectral_reader -n $SAMPLES -t 120 -o /data/local/tmp/spectral_out.csv'" 2>/dev/null | tail -3

# Pull
echo "[3/4] Pulling data..."
ssh h1 "adb pull /data/local/tmp/spectral_out.csv /tmp/spectral_out.csv" 2>/dev/null
scp h1:/tmp/spectral_out.csv "$REPO/data/${OUTNAME}.csv" 2>/dev/null

# Analyze
echo "[4/4] Analyzing..."
echo ""
python3 "$REPO/tools/analyze_spectral.py" "$REPO/data/${OUTNAME}.csv"
echo ""
python3 "$REPO/tools/calibrate_spectral.py" "$REPO/data/${OUTNAME}.csv" "$REPO/data/${OUTNAME}_cal.csv"

echo ""
echo "=== Done ==="
echo "Raw:        data/${OUTNAME}.csv"
echo "Calibrated: data/${OUTNAME}_cal.csv"
