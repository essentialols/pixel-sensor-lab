# Session 3: VD6282 Spectral Sensor USF Protocol Deep Dive

**Date**: 2026-05-19
**Goal**: Get raw 6-channel spectral data from VD6282 via the USF (Unified Sensor Framework) protocol
**Context**: Session 2 mapped the AOC barrier. Session 3 attempts to bypass it.

## Key Discovery: UsfSpectralApi in libusf.so

The vendor library `/vendor/lib64/libusf.so` contains a complete, unused `UsfSpectralApi` class with methods to read spectral data from the AOC. This API was likely built for Google's internal camera/factory tools.

Key classes:
- `usf::UsfSpectralApi` (abstract, public API)
- `usf::UsfSpectralImpl` (concrete, 1232-byte object)
- `usf::UsfSpectralApiCallbackInterface` (callback for data delivery)

Methods discovered via symbol analysis:
- `Create/Destroy/Init/Deinit` (lifecycle)
- `ConnectCallback/SensorEventCallback` (data flow)
- `SetSamplingPeriod/SetGain/SetRegister` (configuration)
- `GetParam/GetDeviceUid/GetSensorName` (queries)
- `EnableFlickerSensor/DisableAndDeleteSensor` (sensor control)
- `SampleConverter::ConvertUncompressed` (raw data parsing)

## What Works

1. **USF client connection**: Our native tool connects to the AOC via libusf.so and discovers all 44 sensors including "VD6282 Spectral Sensor-t:12 h:18"
2. **ConnectCallback**: After calling it manually (it doesn't fire automatically due to event loop dependency), it runs successfully and calls our callback
3. **StartSampling**: Returns 0 (success) on UsfApiImpl but doesn't actually register with AOC

## What Doesn't Work (Yet)

1. **Automatic event loop**: The UsfSpectralApi creates a transport thread but the ConnectCallback/SensorEventCallback dispatch requires the transport thread to be running its epoll event loop. Our process has the thread but events aren't dispatched automatically.
2. **Session management**: The SpectralApi requires "sessions" to map sensor subscriptions. These are normally created by the camera system or factory tools. Without a session, `SetSamplingPeriod` and `LookUpServer` fail with "No ambient light sensor present."
3. **HAL binary patch**: Patching the `PopulateHalSensorInfo` jump table (1-byte: offset 0x13289 from 0x00 to 0x9c) correctly maps type 12 to `com.google.sensor.color (0x10008)`. However, `CreateHalClientSensorList` has an earlier filter that prevents the spectral sensor from getting a BasicSensor object.

## Architecture Findings

### USF Protocol Stack
```
Android App → SensorManager → SensorHAL (sensors.usf.so) → UsfApiImpl → UsfTransport → /dev/acd-com.google.usf → AOC firmware
```

### UsfSpectralImpl Object Layout (1232 bytes)
```
offset 0x00: vtable ptr (UsfSpectralApi primary)
offset 0x08: vtable ptr (secondary interface, SampleConverter-related)  
offset 0x18: UsfApi* (internal transport API)
offset 0x20: UsfSpectralApiCallbackInterface* (user callback)
offset 0x28+: sensor session array (8 slots, 0x90 bytes each)
offset 0x4a8: mutex
offset 0x4d0: end of object
```

### UsfSpectralApiCallbackInterface vtable (confirmed by disassembly)
```
slot 0,1: virtual destructor (D1, D0)
slot 2 (offset 0x10): OnSensorEventCallback (called from SensorEventCallback)
slot 3 (offset 0x18): OnDisconnectCallback
slot 4 (offset 0x20): OnConnectCallback (called from ConnectCallback)
```

### AOC Sensor Stats (from usf_stats)
The VD6282 Spectral Sensor is actively sampling at 10Hz inside the AOC:
- sample_events: 56,145+
- period_ns: 100,000,000 (10Hz)
- Client: RLS (0x30001) processes 6-ch raw data into single lux
- The data EXISTS but is consumed internally by RLS before reaching Android

### HAL Sensor Type Mapping (PopulateHalSensorInfo jump table at 0x13280)
```
AOC type  3 → Android type 65536+ (mapped)
AOC type 10 → com.google.sensor.color (0x10008 = 65544)
AOC type 12 → DEFAULT (com.google.sensor.unknown) ← spectral sensor
AOC type 13 → DEFAULT (com.google.sensor.unknown) ← flicker sensor
AOC type 46 → com.google.sensor.rear_light (65545) ← what we see today
```

## Tools Built

- `tools/spectral_usf/spectral_reader.cpp` - Native Android binary using UsfSpectralApi via dlopen
- `tools/spectral_usf/libs/` - Pulled vendor libraries for cross-compilation
- `tools/patch_registry.py` - Registry patcher (needs format correction)
- `tools/unlock_spectral.sh` - Registry deployment script

## Build System
```bash
# Requires Android NDK (installed to ~/Library/Android/sdk/ndk/27.0.12077973)
NDK=~/Library/Android/sdk/ndk/27.0.12077973
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android33-clang++"
SYSROOT="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot"
$CC -std=c++17 -O2 -static-libstdc++ --sysroot="$SYSROOT" \
    -o spectral_reader spectral_reader.cpp -ldl -llog
```

## Next Steps (Ranked by Feasibility)

1. **Fix the event loop**: The transport thread uses epoll. Find a way to pump the event loop or register a proper event handler so SensorEventCallback fires automatically.

2. **Find the HAL's CreateHalClientSensorList filter**: The 1-byte HAL patch is correct but a higher-level filter prevents type 12 from being registered. Disassemble CreateHalClientSensorList (0x3a410, 2352 bytes) to find and patch this filter.

3. **Camera-triggered capture**: Opening the camera activates the VD6282 spectral sensor and may create a SpectralApi session. Run our tool while the camera is open to see if data flows.

4. **Build a minimal UsfTransportClient**: Instead of using UsfSpectralApi, build a raw transport client (like usf_stats does) that sends FlatBuffer RegSampleChannelReq messages directly.

5. **Write a CHRE nanoapp**: Deploy via /dev/acd-com.google.chre to read spectral data from inside the AOC and forward it to the host.
