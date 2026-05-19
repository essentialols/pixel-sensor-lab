# Spectral Sensor Findings — Pixel 7 Pro Fruit Ripeness

## Discovery: Two Spectral Sensors Available

The Pixel 7 Pro has **two** spectral light sensors, both managed by the AOC sensor hub:

### 1. TMD3719 (AMS/OSRAM) — Front ALS/Proximity
- **Location**: Front of phone (display side)
- **Channels**: 6 — Red, Green, Blue, Clear, Leakage, Wideband
- **IR proximity**: 3 integrated 940nm VCSELs
- **Android sensor type**: 5 (TYPE_LIGHT) / 8 (TYPE_PROXIMITY)
- **Android handle**: 0x01010005 / 0x01010007
- **Current data**: Only reports single lux value (29.04, 0.00, 0.00)
- **Status**: Always active (used for auto-brightness)

### 2. VD6282 (STMicro) — Rear Light Sensor ★ KEY SENSOR
- **Location**: Rear of phone (camera side) — **points at fruit!**
- **Channels**: 6 — Red, Green, Blue, IR (850nm peak), Clear, Visible
- **24-bit ADC** per channel
- **Max rate**: 62.5 Hz continuous
- **FIFO**: 3000 events
- **Android sensor type**: 65545 (com.google.sensor.rear_light)
- **Android handle**: 0x0101001c
- **Current data**: NOT active (no reader registered)
- **Status**: Activates when camera opens

## TMD3719 Spectral Characteristics (from datasheet)

| Channel   | UV/IR Filter | Test LED   | Response (counts/µW/cm²) |
|-----------|-------------|------------|--------------------------|
| Clear     | Yes         | White 3K   | 657 typ                  |
| Wideband  | No          | White 3K   | 332 typ (0.48× Clear)    |
| Red       | Yes         | Red 615nm  | ~97% of Clear            |
| Green     | Yes         | Green 525nm| ~76% of Clear            |
| Blue      | Yes         | Blue 465nm | ~88% of Clear            |
| Leakage   | Special     | White 3K   | ~1% of Clear             |

The spectral response spans 300-1100nm, with:
- RGBL and Clear channels: UV/IR blocking filter (~400-700nm)
- Wideband: No filter (full 300-1100nm response)
- IR1, IR2: Infrared sensitivity (850-1000nm range)

**Key for fruit ripeness**: 
- Red channel detects chlorophyll degradation (~670nm absorption)
- Wideband − Clear = IR content (water absorption at 940-970nm)
- Blue channel detects carotenoid changes

## VD6282 Spectral Characteristics (from datasheet)

| Channel  | Purpose          | ADC    | Gain Range   |
|----------|-----------------|--------|-------------|
| Red      | Red spectrum    | 24-bit | 0.7x - 66x |
| Green    | Green spectrum  | 24-bit | 0.7x - 66x |
| Blue     | Blue spectrum   | 24-bit | 0.7x - 66x |
| IR       | Near-infrared   | 24-bit | 0.7x - 66x |
| Clear    | Unfiltered      | 24-bit | 0.7x - 66x |
| Visible  | VIS-only filter | 24-bit | 0.7x - 66x |

- IR channel peaks at **850nm** (FWHM ~30nm)
- Hybrid color filters with high photocount response
- 25 photodiodes (5×5 matrix) with individual channel filters
- Exposure time: 1.6ms to 1.6s (programmable)
- Flicker detection: 100 Hz to 2 kHz

**Key for fruit ripeness**:
- IR channel at 850nm — close to 940nm water absorption
- Clear − Visible = IR contribution
- 24-bit resolution = excellent dynamic range
- 62.5 Hz rate = can track rapid changes

## Access Path

Both sensors are behind the **AOC sensor hub** — NOT directly on any I2C bus accessible to Linux. The AOC has its own I2C controller.

### What works:
- `dumpsys sensorservice` — shows sensor list and last events
- `app_process` with binder IPC — can list sensors (verified working)
- Camera opening activates VD6282

### What doesn't work:
- Direct I2C access (i2ctransfer) — sensors not on accessible buses
- Static musl binaries with dlopen — dlopen is a no-op in static musl
- `app_process` with `ActivityThread.systemMain()` — gets SIGKILL
- BPF kprobe on `__i2c_transfer` — AOC's I2C is internal, not Linux I2C

### Remaining approach to try:
1. **app_process with IEventQueueCallback** — register via binder to receive raw events
2. **Camera-triggered capture** — open camera, poll `dumpsys sensorservice` rapidly
3. **AOC channel interface** — `/dev/aoc` or `/sys/bus/aoc/devices/com.google.usf/`
4. **CHRE nanoapps** — custom nanoapp running on AOC that reads sensors directly

## Combined Sensor Arsenal for Fruit Ripeness

| Sensor | Channels | Wavelengths | Rate | Access |
|--------|----------|-------------|------|--------|
| VL53L1 ToF laser | 24-bin histogram | 940nm | 345 Hz | BPF kprobe ✓ |
| VD6282 Rear Light | R,G,B,IR,Clear,Vis | Broadband + 850nm | 62.5 Hz | Needs activation |
| Camera RGB | Full Bayer | ~400-700nm | 30 fps | Standard API |
| TMD3719 Front ALS | R,G,B,Clear,Leak,Wide | ~400-1100nm | 4 Hz | Active (lux only) |

Total independent channels for fruit ripeness: **30+**
- ToF: 6 channels (centroid, photons, skewness, tail, Fano, decorrelation)
- VD6282: 6 spectral channels  
- Camera: 3 channels (R,G,B) at each pixel
- TMD3719: up to 6 spectral channels (if raw access unlocked)
