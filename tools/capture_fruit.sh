#!/bin/bash
# capture_fruit.sh - Capture labeled fruit ripeness data for training
#
# Usage: ./capture_fruit.sh <fruit> <stage> [samples]
# Example: ./capture_fruit.sh banana green 100
#          ./capture_fruit.sh banana yellow 100
#          ./capture_fruit.sh banana brown 100
#          ./capture_fruit.sh tomato green 50
#          ./capture_fruit.sh avocado firm 50
#
# Place the phone's rear sensor ~5cm from the fruit surface.
# The daemon captures spectral + ToF + RALS-normalized + indices as JSONL.
# If a running daemon is detected on port 8765, uses it instead of starting a new one.

set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
FRUIT=${1:?Usage: $0 <fruit> <stage> [samples]}
STAGE=${2:?Usage: $0 <fruit> <stage> [samples]}
SAMPLES=${3:-50}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LABEL="${FRUIT}_${STAGE}"
OUTNAME="fruit_${LABEL}_${TIMESTAMP}"
REMOTE_FILE="/data/local/tmp/${OUTNAME}.jsonl"

echo "=== Fruit Ripeness Data Capture ==="
echo "Fruit: $FRUIT  Stage: $STAGE  Samples: $SAMPLES"
echo "Hold phone rear sensor ~5cm from fruit surface."
echo ""

# Check if daemon is already running
DAEMON_PID=$(ssh h1 'adb shell su -c "pidof ripeness_daemon"' 2>/dev/null || true)
if [ -n "$DAEMON_PID" ]; then
    echo "Using running daemon (pid $DAEMON_PID)"
    STARTED_DAEMON=0
else
    scp "$REPO/app/ripeness_daemon" h1:/tmp/ 2>/dev/null
    ssh h1 "adb push /tmp/ripeness_daemon /data/local/tmp/ripeness_daemon && adb shell chmod 755 /data/local/tmp/ripeness_daemon" 2>/dev/null
    echo "Starting capture in 3 seconds..."
    sleep 3
    ssh h1 "adb shell su -c 'nohup /data/local/tmp/ripeness_daemon -d -n $SAMPLES -t 120 -o $REMOTE_FILE > /dev/null 2>&1 &'" 2>/dev/null
    STARTED_DAEMON=1
    echo "Waiting for $SAMPLES samples..."
    sleep $((SAMPLES / 7 + 5))
fi

if [ $STARTED_DAEMON -eq 0 ]; then
    echo "Capturing $SAMPLES samples via TCP..."
    ssh h1 "adb shell su -c '/data/local/tmp/ripeness_daemon -n $SAMPLES -t 120 -o $REMOTE_FILE'" 2>/dev/null
fi

# Pull
mkdir -p "$REPO/data/fruit"
ssh h1 "adb pull $REMOTE_FILE /tmp/${OUTNAME}.jsonl" 2>/dev/null
scp h1:/tmp/"${OUTNAME}.jsonl" "$REPO/data/fruit/${OUTNAME}.jsonl" 2>/dev/null

# Add label metadata
python3 -c "
import json
with open('$REPO/data/fruit/${OUTNAME}.jsonl') as f:
    lines = f.readlines()
labeled = []
for line in lines:
    line = line.strip()
    if not line: continue
    obj = json.loads(line)
    obj['label'] = {'fruit': '$FRUIT', 'stage': '$STAGE'}
    labeled.append(json.dumps(obj))
with open('$REPO/data/fruit/${OUTNAME}.jsonl', 'w') as f:
    f.write('\n'.join(labeled) + '\n')
print(f'Labeled {len(labeled)} samples as \"$LABEL\" -> data/fruit/${OUTNAME}.jsonl')
"

# Quick analysis
echo ""
python3 "$REPO/tools/analyze_fused.py" "$REPO/data/fruit/${OUTNAME}.jsonl"

# Auto-train if enough labeled data exists
TOTAL_LABELED=$(find "$REPO/data/fruit" -name "*.jsonl" -exec grep -l '"label"' {} \; | wc -l | tr -d ' ')
if [ "$TOTAL_LABELED" -ge 2 ]; then
    echo ""
    echo "=== Auto-training on $TOTAL_LABELED labeled files ==="
    python3 "$REPO/tools/train_ripeness.py" "$REPO/data/fruit/" --compare
fi
