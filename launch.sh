#!/bin/bash
# launch.sh - Start the Fruit Ripeness app (daemon + APK) in one command
#
# Usage: ./launch.sh [build]
#   ./launch.sh         Deploy and launch (if already built)
#   ./launch.sh build   Build first, then deploy and launch

set -e
REPO="$(cd "$(dirname "$0")" && pwd)"

if [ "$1" = "build" ]; then
    echo "Building..."
    "$REPO/app/build.sh"
fi

echo "=== Deploying ==="
scp "$REPO/app/ripeness_daemon" h1:/tmp/ 2>/dev/null
ssh h1 "adb push /tmp/ripeness_daemon /data/local/tmp/ripeness_daemon && adb shell chmod 755 /data/local/tmp/ripeness_daemon" 2>/dev/null
scp "$REPO/app/ripeness.apk" h1:/tmp/ 2>/dev/null
ssh h1 "adb install -r /tmp/ripeness.apk" 2>/dev/null

echo "=== Starting daemon ==="
# Kill any existing daemon
ssh h1 'adb shell su -c "pkill -f ripeness_daemon"' 2>/dev/null || true
sleep 1
# Start daemon in headless mode with TCP server and file output
ssh h1 'adb shell su -c "nohup /data/local/tmp/ripeness_daemon -d -p 8765 -o /data/local/tmp/daemon_log.jsonl > /dev/null 2>&1 &"' 2>/dev/null
sleep 3

echo "=== Launching app ==="
ssh h1 'adb shell am start -n com.spectral.ripeness/.RipenessActivity' 2>/dev/null

echo ""
echo "=== Running ==="
echo "Daemon: headless mode, TCP :8765, logging to daemon_log.jsonl"
echo "App: Fruit Ripeness Analyzer"
echo ""
echo "Controls:"
echo "  REC    - Record labeled data (foreground, with UI)"
echo "  BG     - Background recording (survives app pause)"
echo "  CAL    - White-reference calibration (hold on white surface)"
echo "  LIGHT  - Toggle torch for active illumination"
echo "  SNAP   - Capture camera frame"
echo ""
echo "To stop daemon:  ssh h1 'adb shell su -c \"pkill -f ripeness_daemon\"'"
echo "To pull data:    ./tools/pull_data.sh"
