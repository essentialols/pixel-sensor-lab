#!/bin/bash
# pull_data.sh - Pull all recording data from the phone and organize it
#
# Usage: ./tools/pull_data.sh [--clean]
#   --clean  Remove files from phone after successful pull

set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO/data/recordings"
CLEAN=0

for arg in "$@"; do
    if [ "$arg" = "--clean" ]; then CLEAN=1; fi
done

mkdir -p "$DEST"

echo "=== Pulling Recording Data ==="

FILES=$(ssh h1 "adb shell ls /data/local/tmp/*.jsonl 2>/dev/null" || true)
if [ -z "$FILES" ]; then
    echo "No JSONL files found on device."
    exit 0
fi

PULLED=0
for remote in $FILES; do
    fname=$(basename "$remote")
    if [ -f "$DEST/$fname" ]; then
        echo "  skip (exists): $fname"
        continue
    fi
    ssh h1 "adb pull $remote /tmp/$fname" 2>/dev/null
    scp h1:/tmp/"$fname" "$DEST/$fname" 2>/dev/null
    lines=$(wc -l < "$DEST/$fname" | tr -d ' ')
    echo "  pulled: $fname ($lines lines)"
    PULLED=$((PULLED + 1))
    if [ $CLEAN -eq 1 ]; then
        ssh h1 "adb shell rm $remote" 2>/dev/null
    fi
done

echo ""
echo "Pulled $PULLED new files to data/recordings/"

echo ""
echo "=== Recording Summary ==="
for f in "$DEST"/*.jsonl; do
    [ -f "$f" ] || continue
    fname=$(basename "$f")
    lines=$(wc -l < "$f" | tr -d ' ')
    has_label=$(python3 -c "
import json
with open('$f') as fh:
    first = json.loads(fh.readline())
    label = first.get('label', {})
    if label:
        print(f\"{label.get('fruit','?')}_{label.get('stage','?')}\")
    else:
        print('unlabeled')
" 2>/dev/null || echo "parse-error")
    has_refl=$(python3 -c "
import json
with open('$f') as fh:
    first = json.loads(fh.readline())
    print('refl' if 'refl' in first else 'raw')
" 2>/dev/null || echo "?")
    printf "  %-45s %5s lines  %-20s %s\n" "$fname" "$lines" "$has_label" "$has_refl"
done

LABELED=$(find "$DEST" -name "*.jsonl" -exec grep -l '"label"' {} \; 2>/dev/null | wc -l | tr -d ' ')
echo ""
echo "Total files: $(ls "$DEST"/*.jsonl 2>/dev/null | wc -l | tr -d ' '), labeled: $LABELED"
echo "To train: python3 tools/train_ripeness.py data/recordings/"
