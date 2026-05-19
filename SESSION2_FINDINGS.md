# Session 2: VD6282 Spectral Channel Access Investigation

**Date**: 2026-05-19
**Goal**: Get raw 6-channel spectral data (R,G,B,IR,Clear,Vis) from VD6282 rear light sensor
**Context**: Continuing from session 1 which built the APK and binder tools. Custom kernel being patched by parallel session.

## Summary

The VD6282 rear spectral sensor works via our APK, but **only outputs a single processed lux value**. The AOC (Always-On Computer) sensor hub consolidates 6 raw spectral channels into one number before exposing it to Android. All framework-level approaches are exhausted. Unlocking raw channels requires either modifying the AOC sensor registry or runtime AOC reconfiguration.

## Approaches Tested

### 1. APK SensorActivity (SensorManager framework)
- **Status**: Works
- **Result**: 16-float event, but only v0 has data (~14 lux), v1-v15 = 0.0
- **Rate**: 7.8 Hz (127ms period), 62.5 Hz max per spec
- **Note**: APK rebuilt with targetSdkVersion=33 after kernel flash wiped it

### 2. Camera-triggered capture
- **Status**: No improvement
- **Method**: Opened Google Camera, then read VD6282
- **Result**: Same single-lux output. Camera activation doesn't change VD6282 data format.

### 3. SpectralCapture binder tool (app_process)
- **Status**: Blocked by Android security
- **Method**: Direct binder IPC to sensorservice, bypassing SensorManager framework
- **Result**: readStrongBinder() returns null. Reply parcel contains flat binder object header (0x73622A85) with handle=0.
- **Root cause**: Android 13 restricts sensor event connections to proper app UIDs

### 4. Multi-sensor simultaneous read
- **Status**: Works, but no new spectral data
- **Sensors read**: VD6282 (65545), TMD3719 ALS (5), Auto Brightness (131088), TMD3719 Proximity (8)
- **Discovery**: Auto Brightness (type 131088) reports 3 values: ALS lux / RLS lux / fused lux

### 5. SensorAdditionalInfo callback
- **Status**: Not available
- **Result**: isAdditionalInfoSupported=false for all light/spectral sensors

## Key Discovery: AOC Sensor Registry Architecture

Found in /vendor/etc/sensors/registry/cheetah_proto.reg:

    +/dev/vd6282/0
      bus_name=i2c0          # AOC-internal I2C, NOT Linux-accessible
      bus_addr=0x20

    +/dev/vd6282/0/spectral  # RAW 6-channel data — NOT exposed to Android
      auto_gain=1
      r_change_thresh=-1 -1
      g_change_thresh=100 0.1
      b_change_thresh=-1 -1
      c1_change_thresh=100 0.1
      c2_change_thresh=-1 -1
      ir_change_thresh=-1 -1

    +/dev/vd6282/0/rls       # This is what type 65545 exposes — single lux
      lux_scale=58.823529

    +/dev/vd6282/0/flicker   # Flicker detection — not exposed
      auto_gain=1

Android sensor type 65545 maps to rls (rear light sensor = processed lux).
The spectral sub-sensor has raw per-channel thresholds but no Android type mapping.

## Confirmed Dead Ends

- Direct I2C (i2ctransfer): VD6282 on AOC's internal i2c0, not Linux-visible
- BPF kprobe on __i2c_transfer: Only intercepts Linux I2C; AOC has own controller
- Static musl + dlopen: dlopen is no-op in static musl
- app_process + ActivityThread.systemMain(): SIGKILL on Android 13
- app_process + binder ISensorEventConnection: Null binder -- security restriction
- Camera-triggered mode change: Same data format with/without camera

## Files Modified

- tools/SpectralCapture.java -- Added hardcoded handle fallback, raw Parcel debug dump
- /tmp/sensor_apk/src/SensorActivity.java -- Multi-sensor mode, SensorEventCallback with onSensorAdditionalInfo
- /tmp/sensor_apk/AndroidManifest.xml -- Added uses-sdk minSdkVersion=24 targetSdkVersion=33

## Remaining Approaches

1. Modify sensor registry -- Edit cheetah_proto.reg to enable spectral sub-sensor. Requires /vendor rw + reboot.
2. AOC runtime reconfiguration -- /dev/aoc, usf_sh_mem_doorbell, aoc_chan sysfs
3. CHRE nanoapp -- Custom nanoapp with raw sensor access inside AOC
4. Camera HAL sniffing -- Intercept VD6282 spectral reads done by camera for AWB
5. Kernel module for AOC I2C passthrough -- Expose AOC's i2c0 to Linux
