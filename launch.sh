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
# Start daemon in background with TCP server
ssh h1 'adb shell su -c "nohup /data/local/tmp/ripeness_daemon -p 8765 -t 3600 > /dev/null 2>&1 &"' 2>/dev/null
sleep 3

echo "=== Launching app ==="
ssh h1 'adb shell am start -n com.spectral.ripeness/.RipenessActivity' 2>/dev/null

echo ""
echo "=== Running ==="
echo "Daemon: TCP server on port 8765 (1 hour timeout)"
echo "App: Fruit Ripeness Analyzer"
echo ""
echo "To record data: tap 'Record: OFF' in the app, enter a label"
echo "To stop daemon:  ssh h1 'adb shell su -c \"pkill -f ripeness_daemon\"'"
echo "To pull data:    ssh h1 'adb pull /data/local/tmp/fruit_*.jsonl .'"
