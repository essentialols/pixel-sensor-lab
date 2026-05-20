# Handover: Pixel 7 Pro Fruit Ripeness Sensor System

**Last updated:** 2026-05-19, Session 3
**Status:** Feature-complete measurement + background recording system, awaiting fruit data collection

## Quick Start

```bash
# One command to launch everything
./launch.sh

# Or manually:
# 1. Start daemon (root required)
ssh h1 'adb shell su -c "nohup /data/local/tmp/ripeness_daemon -p 8765 -t 3600 > /dev/null 2>&1 &"'
# 2. Launch app
ssh h1 'adb shell am start -n com.spectral.ripeness/.RipenessActivity'
```

## Build

```bash
# Daemon (native C++, NDK)
NDK=~/Library/Android/sdk/ndk/27.0.12077973
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++"
$CC -std=c++17 -O2 -static-libstdc++ \
    --sysroot="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot" \
    -o app/ripeness_daemon tools/ripeness_daemon.cpp -ldl -llog

# APK (Java, build-tools 35)
BUILD_TOOLS=~/Library/Android/sdk/build-tools/35.0.0
PLATFORM=~/Library/Android/sdk/platforms/android-35
javac -source 1.8 -target 1.8 -classpath "$PLATFORM/android.jar" \
    -d app/obj app/src/RipenessActivity.java app/src/RecordingService.java app/src/RecordingService.java
$BUILD_TOOLS/d8 --output app/dex $(find app/obj -name "*.class")
$BUILD_TOOLS/aapt package -f -M app/AndroidManifest.xml -S app/res \
    -I "$PLATFORM/android.jar" -F app/ripeness-unsigned.apk
cd app/dex && zip -j ../ripeness-unsigned.apk classes.dex && cd ../..
$BUILD_TOOLS/apksigner sign --ks ~/.android/debug.keystore \
    --ks-pass pass:android --out app/ripeness.apk app/ripeness-unsigned.apk
```

## Sensors (6 active)

| #   | Sensor          | Chip     | Access Method                 | Data                        | Rate   |
| --- | --------------- | -------- | ----------------------------- | --------------------------- | ------ |
| 1   | Rear spectral   | VD6282   | USF cookie injection (daemon) | R,G,B,IR,CLR1,CLR2 + gain   | 7.8 Hz |
| 2   | ToF rangefinder | VL53L1   | LWIS + ioctl (daemon)         | 24-bin histogram + distance | ~30 Hz |
| 3   | Rear camera     | GN1      | Camera2 API (APK)             | 640x480 preview + JPEG      | 30 fps |
| 4   | Front ALS       | TMD3719  | SensorManager (APK)           | Ambient lux                 | ~4 Hz  |
| 5   | IMU             | LSM6DSV  | SensorManager (APK)           | Stability detection         | ~50 Hz |
| 6   | Barometer       | ICP20100 | SensorManager (APK)           | Pressure (hPa)              | ~25 Hz |

## Architecture

```
Phone (rooted, SELinux permissive)
  ripeness_daemon (root, native C++)
    VD6282 spectral: dlopen libusf.so -> UsfSpectralApi -> cookie injection
    VL53L1 ToF: LWIS power-on -> ioctl START -> I2C histogram
    Output: JSON lines on TCP :8765 or stdout (raw + RALS-normalized + indices)
  RipenessActivity (APK, user-space)
    Connects to daemon on localhost:8765
    Camera2 preview + capture
    SensorManager: ALS, IMU, barometer
    Record button: saves labeled JSONL to /data/local/tmp/
  RecordingService (APK, foreground service)
    Connects to daemon TCP, records labeled JSONL in background
    Survives activity pause, notification with stop action
```

## Key Technique: USF Cookie Injection

The VD6282 spectral sensor runs inside the AOC but only exposes lux to Android.
We bypass this by:

1. dlopen `/vendor/lib64/libusf.so`
2. Call `UsfSpectralApi::Create` (allocates UsfSpectralImpl, 1232 bytes)
3. Manually call `ConnectCallback` (symbol: `_ZN3usf15UsfSpectralImpl15ConnectCallbackEv`)
4. Get internal `UsfApiImpl*` at object offset 0x18
5. Call `UsfApiImpl::StartSampling(type=12, period=100ms, mode=continuous)`
6. Write returned handle to session slot 0 at object offset 0x58
7. Set active flag at offset 0x28

Data flows: AOC -> USF transport -> SensorEventCallback -> our callback

## Sensor Performance

| Metric              | VD6282 Spectral    | VL53L1 ToF               |
| ------------------- | ------------------ | ------------------------ |
| Noise CV            | 0.16-0.34%         | 3.6mm (distance)         |
| Allan deviation min | 98 (Blue, 4s tau)  | 2.3um (from ToF project) |
| Sample rate         | 7.8 Hz (AOC limit) | ~30 Hz                   |
| ADC                 | 24-bit             | 24-bin histogram         |

## Data Collection

**In-app:** Tap "Record: OFF" -> enter label (e.g., `banana_green`) -> point rear sensor at fruit -> tap "Record: ON" to stop.

**Background:** Tap "BG" -> enter label -> app can be backgrounded. Stop via notification or BG STOP button.

**Calibrate:** Tap "CAL" with sensor on white reference surface. Bars switch to % reflectance. Recorded data includes reflectance when calibrated.

**CLI:** `./tools/capture_fruit.sh banana green 50`

**Analysis:** `python3 tools/train_ripeness.py data/fruit/`

## Files

| File                                     | Purpose                               |
| ---------------------------------------- | ------------------------------------- |
| `tools/ripeness_daemon.cpp`              | Native daemon (spectral + ToF)        |
| `app/src/RipenessActivity.java`          | APK source                            |
| `app/src/RecordingService.java`          | Background recording service          |
| `tools/spectral_usf/spectral_reader.cpp` | Standalone spectral capture           |
| `tools/analyze_spectral.py`              | Spectral statistics + Allan deviation |
| `tools/calibrate_spectral.py`            | RALS normalization                    |
| `tools/ripeness_indices.py`              | Fruit ripeness spectral indices       |
| `tools/analyze_fused.py`                 | Multi-sensor JSONL analysis           |
| `tools/train_ripeness.py`                | Nearest-centroid classifier           |
| `tools/capture_fruit.sh`                 | Labeled data collection               |
| `launch.sh`                              | One-command deploy + launch           |
| `SESSION3_FINDINGS.md`                   | Full technical writeup                |
| `LITERATURE_REVIEW.md`                   | 8 papers on low-channel spectroscopy  |
| `SENSOR_COMPARISON.md`                   | vs professional instruments           |
| `EXPERIMENTS.yaml`                       | 14 experiments (E001-E014)            |

## What's Next

1. **Collect fruit data** (banana green/yellow/brown recommended first, calibrate against white reference)
2. **Train classifier** on calibrated reflectance data (RALS + white-ref features auto-extracted)
3. **Evaluate** against Lauretti 2025 benchmarks (93.72% with 11-ch)
4. **Optimize** feature selection (which channels matter most for each fruit)
5. **Active illumination** profiles (torch on vs ambient, paired measurements)
