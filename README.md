# pixel-sensor-lab

Reverse engineering the sensors of a rooted Pixel 7 Pro (Tensor G2, codename "cheetah").

Each sensor gets its own project repo with kernel-level tools, raw data extraction, and precision characterization -- all running on-device via `adb shell`. No external hardware, no purchases, software-only.

## Hardware platform

| Property   | Detail                                                |
| ---------- | ----------------------------------------------------- |
| Device     | Google Pixel 7 Pro                                    |
| SoC        | Google Tensor G2 (gs201)                              |
| Codename   | cheetah (device), pantah (family)                     |
| OS         | LineageOS, Magisk root, SELinux permissive            |
| Kernel     | 5.10 (android-gs-pantah)                              |
| Sensor hub | AOC (Always-On Computer) -- custom Google coprocessor |
| Interface  | ADB over USB to rooted device                         |

## Sensor inventory

### Completed projects

| Sensor                | Chip           | Kernel module                  | Project                                           | Status                                                                                      |
| --------------------- | -------------- | ------------------------------ | ------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Laser ToF rangefinder | STMicro VL53L1 | `stmvl53l1.ko`                 | [pixel-tof-rangefinder](../pixel-tof-rangefinder) | **Done** -- 2.3um precision, 345Hz, 24-bin photon histograms, 87% CRLB-efficient            |
| Rear spectral sensor  | STMicro VD6282 | AOC sensor hub (`aoc_core.ko`) | _(this repo)_                                     | **Done** -- 6-ch raw spectral (R/G/B/IR/CLR1/CLR2) at 7.8Hz, CV 0.16-0.34%, RALS-calibrated |

### Active projects

| Sensor                | Chip            | Kernel module                       | Project                                                 | Status                                                                                       |
| --------------------- | --------------- | ----------------------------------- | ------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| UWB radio rangefinder | Qorvo DW3000    | `dw3000.ko` + `mcps802154*.ko`      | [pixel-uwb-rangefinder](../pixel-uwb-rangefinder)       | **Session 5** -- DW3000 on spi16.0, all modules built from source, custom kernel building    |
| Dual magnetometer     | MEMSIC MMC56X3X | AOC sensor hub                      | [pixel-mag-gradiometer](../pixel-mag-gradiometer)       | **Session 1** -- 0.061 uT sensitivity via accidental fluxgate effect, metal detector app     |
| Fingerprint camera    | Goodix Delmar   | `goodixfp.ko`                       | [pixel-fingerprint-camera](../pixel-fingerprint-camera) | **Session 4** -- DMA captures during enrollment, TEE feature data extracted, 20+ experiments |
| EdgeTPU (CPA/matmul)  | DarwiNN v2      | `edgetpu_platform` (`/dev/janeiro`) | [pixel-tpu-cpa](../pixel-tpu-cpa) (canonical repo)      | **E043** -- 43 experiments, full firmware decompile, 1.92 TOPS measured, INT4 execution next |

### Candidate sensors (not yet started)

| #   | Sensor                            | Chip (likely)          | Kernel module / access path                    | RE potential                                                                                                                                                                  | Difficulty |
| --- | --------------------------------- | ---------------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| 1   | **IMU (accel + gyro)**            | LSM6DSR (STMicro)      | AOC sensor hub (`aoc_core.ko`) via IIO / sysfs | High-rate raw inertial data, dead reckoning, seismometry. AOC firmware is a blob but the channel protocol is open.                                                            | Medium     |
| 2   | ~~**Magnetometer**~~              | MMC56X3X (MEMSIC)      | AOC sensor hub                                 | **STARTED** -- see [pixel-mag-gradiometer](../pixel-mag-gradiometer). 0.061 uT fluxgate sensitivity.                                                                          | Medium     |
| 3   | **Barometer**                     | BMP390 (Bosch)         | AOC sensor hub                                 | High-resolution pressure. Floor detection, weather sensing, micro-altitude, door open/close detection. At high rates: breathing, pulse.                                       | Medium     |
| 4   | **Spectral ALS / proximity**      | TMD3719 (AMS/OSRAM)    | AOC sensor hub                                 | 6+ spectral channels + flicker detection. Light source classification, colorimetry, flicker frequency analysis. Hidden capabilities beyond simple lux.                        | High       |
| 5   | **Grip / SAR sensor**             | SX9330 (Semtech)       | AOC sensor hub                                 | Capacitive proximity. Hand detection, grip pattern recognition, body proximity. Normally used for SAR compliance.                                                             | Medium     |
| 6   | ~~**Fingerprint sensor**~~        | Goodix (optical UDFPS) | `goodixfp.ko`                                  | **STARTED** -- see [pixel-fingerprint-camera](../pixel-fingerprint-camera). B/W camera via OLED transillumination.                                                            | Very high  |
| 7   | **GNSS receiver**                 | Broadcom BCM47765      | `bcm47765.ko`                                  | Dual-frequency L1+L5 raw measurements. Carrier phase, raw pseudorange, precision surveying from phone. Android exposes GnssMeasurements API but kernel-level may unlock more. | Medium     |
| 8   | **Haptic driver**                 | Cirrus Logic CS40L26   | `cs40l26-core.ko`                              | Built-in accelerometer for closed-loop feedback. Seismometer, material identification via resonance analysis.                                                                 | Low-medium |
| 9   | **NFC controller**                | ST21NFC                | `st21nfc.ko`                                   | NFC-A/B/F/V, card emulation, reader mode. Raw RF field sensing, passive tag interrogation, metal/material detection.                                                          | Medium     |
| 10  | **Touchscreen digitizer**         | Synaptics              | `syna_touch.ko`                                | Raw capacitance grid. Moisture detection, material proximity sensing, pressure mapping beyond standard touch events.                                                          | Medium     |
| 11  | **Camera sensors (main/UW/tele)** | GN1/IMX386/IMX787      | `lwis.ko` (LWIS framework)                     | Raw Bayer, HDR stacking, astrophotography, computational imaging. Google's LWIS is documented in AOSP.                                                                        | High       |
| 12  | **Temperature sensor**            | On-die (Tensor G2)     | sysfs thermal zones                            | Thermal characterization, die temperature correlation to sensor drift. Limited but useful for calibration.                                                                    | Low        |

## Methodology

Each sensor RE project follows the same phased approach, proven on the ToF project (220+ experiments across 9 sessions):

### Phase 1: Reconnaissance

- Identify kernel driver, device nodes, sysfs interfaces
- Map ioctl surface (which commands exist, which are compiled out)
- Read device tree source for I2C address, interrupt lines, power regulators
- Dump initial register map and identify chip revision

### Phase 2: Protocol mapping

- Reverse engineer driver communication protocol (ioctls, I2C, SPI)
- Identify power-on sequence and initialization
- Map register space for raw data access
- Build first working probe tool (read one measurement)

### Phase 3: Raw data extraction

- Bypass HAL/framework to get kernel-direct raw data
- Characterize sample rate, noise floor, resolution
- Build streaming capture tool with CSV output
- Establish baseline measurements for stock vs raw comparison

### Phase 4: Precision characterization

- Allan deviation analysis (noise type identification)
- Budget/timing parameter sweeps
- Thermal drift characterization
- Signal processing optimization (matched filter, MLE, Kalman)

### Phase 5: Applications

- Push beyond intended use (seismometry from ToF, etc.)
- Multi-sensor fusion experiments
- Novel sensing modalities

## Build system

All projects share the same toolchain:

```bash
# Cross-compile for Pixel 7 Pro (aarch64)
CC = aarch64-linux-gnu-gcc  # or aarch64-unknown-linux-musl-gcc
CFLAGS = -static -Wall -Wextra -O2

# Deploy
adb push <binary> /data/local/tmp/
adb shell su -c /data/local/tmp/<binary>
```

## Experiment tracking

Each project maintains:

| File               | Purpose                                                               |
| ------------------ | --------------------------------------------------------------------- |
| `EXPERIMENTS.yaml` | Numbered experiment log (E001+) with script, purpose, result, verdict |
| `HANDOVER.md`      | Current status, breakthrough metrics, key commands, session history   |
| `FRONTIER.md`      | What's verified, what's dead-end, what's next                         |
| `REQUIREMENTS.md`  | Hard constraints and success criteria                                 |
| `data/`            | Raw CSV captures, named by tool + parameters + duration               |
| `tools/`           | Analysis scripts (Python), helper utilities                           |

## AOSP kernel source references

| Resource                     | URL                                                                                                         |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Pixel 7/7 Pro kernel (gs201) | [LineageOS/android_kernel_google_gs201](https://github.com/LineageOS/android_kernel_google_gs201)           |
| Google kernel modules (all)  | [android.googlesource.com/kernel/google-modules](https://android.googlesource.com/kernel/google-modules/)   |
| UWB DW3000 driver            | [google-modules/uwb/qorvo/dw3000](https://android.googlesource.com/kernel/google-modules/uwb/qorvo/dw3000/) |
| AOC (sensor hub) driver      | [google-modules/aoc](https://android.googlesource.com/kernel/google-modules/aoc/)                           |
| LWIS (camera/sensor) driver  | [google-modules/lwis](https://android.googlesource.com/kernel/google-modules/lwis/)                         |
| Device config (pantah)       | [device/google/pantah](https://android.googlesource.com/device/google/pantah/)                              |
| Module prebuilts             | [GrapheneOS/device_google_pantah-kernel](https://github.com/GrapheneOS/device_google_pantah-kernel)         |

## Kernel modules on device (sensor-relevant)

```
stmvl53l1.ko        # VL53L1 ToF laser sensor (reverse engineered)
dw3000.ko            # Qorvo DW3000 UWB transceiver
mcps802154.ko        # IEEE 802.15.4 MAC layer for UWB
mcps802154_region_fira.ko   # FiRa UWB ranging protocol
mcps802154_region_nfcc_coex.ko
mcps802154_region_pctt.ko
aoc_core.ko          # Always-On Computer (sensor hub: IMU, mag, baro, ALS, grip)
aoc_channel_dev.ko   # AOC IPC channels
aoc_char_dev.ko      # AOC character device interface
aoc_control_dev.ko   # AOC control interface
goodixfp.ko          # Goodix fingerprint sensor
bcm47765.ko          # Broadcom GNSS receiver
cs40l26-core.ko      # Cirrus Logic haptic driver (has accelerometer)
cs40l26-i2c.ko
snd-soc-cs40l26.ko
st21nfc.ko           # ST NFC controller
lwis.ko              # Lightweight Imaging Subsystem (camera sensor control)
syna_touch.ko        # Synaptics touchscreen
focal_touch.ko       # Focaltech touch (alternate)
```
