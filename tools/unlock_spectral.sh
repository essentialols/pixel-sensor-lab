#!/bin/bash
# unlock_spectral.sh — Unlock raw spectral data on Pixel 7 Pro
#
# This script:
#  1. Pulls the sensor registry from the phone
#  2. Patches it to expose the raw spectral sensor
#  3. Deploys the patched registry using mount --bind
#  4. Restarts the sensor HAL
#
# Requires: Rooted phone, adb, python3

set -e

REG_PATH="/vendor/etc/sensors/registry/cheetah_proto.reg"
LOCAL_REG="cheetah_proto.reg"
PATCHED_REG="cheetah_proto_patched.reg"

echo "=== Pixel 7 Pro Spectral Unlocker ==="

# 1. Pull registry
echo "[1/4] Pulling registry from $REG_PATH..."
ssh h1 "adb pull $REG_PATH $LOCAL_REG"

# 2. Patch registry
echo "[2/4] Patching registry..."
python3 tools/patch_registry.py "$LOCAL_REG" "$PATCHED_REG"

# 3. Deploy
echo "[3/4] Deploying patched registry to /data/local/tmp/..."
ssh h1 "adb push $PATCHED_REG /data/local/tmp/cheetah_proto.reg"

echo "Applying mount --bind (requires root)..."
ssh h1 "adb shell su -c 'mount --bind /data/local/tmp/cheetah_proto.reg $REG_PATH'"

# 4. Restart HAL
echo "[4/4] Restarting Sensor HAL..."
ssh h1 "adb shell su -c 'stop vendor.sensors-hal-2-x && start vendor.sensors-hal-2-x'"

echo ""
echo "SUCCESS: Registry patched and HAL restarted."
echo "Wait 5 seconds for initialization..."
sleep 5

echo "Checking sensor list for new Spectral sensor (type 65547)..."
ssh h1 "adb shell 'CLASSPATH=/data/local/tmp/spectral.dex app_process / SpectralCapture -l'" | grep "65547" || echo "New sensor not found yet. Try 'adb shell dumpsys sensorservice' to debug."

echo ""
echo "To capture data, run:"
echo "  ./capture_spectral.sh 100 65547"
