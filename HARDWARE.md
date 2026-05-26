# Pixel 7 Pro Hardware Inventory

Definitive hardware inventory derived from the actual kernel source tree
(`android.googlesource.com/kernel/gs`, branch `android-gs-pantah-5.10-android15-qpr1`),
live ADB sensor enumeration, and FCC filings.

Codename: cheetah. Platform: gs201 (Google Tensor G2). Models: GP4BC (sub-6), GE2AE (mmWave).

## Radio chips (from kernel device tree + vendor module manifest)

| Subsystem        | Chip                  | DTS compatible                                   | Driver / module                                | Bus             |
| ---------------- | --------------------- | ------------------------------------------------ | ---------------------------------------------- | --------------- |
| WiFi 6E          | Broadcom BCM4389      | `android,bcmdhd_wlan`                            | `google-modules/wlan/bcmdhd4389` (external)    | PCIe            |
| Bluetooth 5.2    | Broadcom (in BCM4389) | `goog,nitrous`                                   | `google-modules/bluetooth/broadcom` (external) | UART            |
| Cellular modem   | Samsung Exynos S5300  | `samsung,exynos-cp` (`mif,name = "s5300"`)       | In-tree cpif driver                            | PCIe            |
| UWB              | Qorvo/Decawave DW3000 | `decawave,dw3000`                                | `google-modules/uwb/qorvo/dw3000` (external)   | SPI             |
| NFC              | ST ST21NFC            | `st,st21nfc`                                     | In-tree `drivers/nfc/st21nfc.c`                | I2C (addr 0x08) |
| Secure Element 1 | ST ST54J              | `st,st54spi`                                     | In-tree `drivers/nfc/ese/st54spi.c`            | SPI             |
| Secure Element 2 | ST ST33               | `st,st33spi`                                     | In-tree `drivers/nfc/ese/st33spi.c`            | SPI             |
| GPS/GNSS         | Broadcom BCM4775      | `ssp,bcm4775`                                    | In-tree `drivers/misc/bbdpl/bcm_gps_spi.c`     | SPI             |
| FM Radio         | None                  | N/A                                              | N/A                                            | N/A             |
| mmWave 5G        | None in kernel tree   | N/A (modem-firmware controlled on GE2AE variant) | N/A                                            | N/A             |

## Additional hardware (from vendor module manifest + device tree + ADB)

Discovered from `Makefile.ext_modules.slider`, device tree includes, and ADB probing.
These are real hardware chips with dedicated drivers that don't appear in the radio or sensor lists.

| Component                | Chip                      | DTS compatible / evidence    | Bus               | Notes                                                                                                                                                                               |
| ------------------------ | ------------------------- | ---------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LDAF (Laser Autofocus)   | ST VL53L1                 | `st,stmvl53l1` @ I2C 0x29    | I2C (hsi2c_1)     | Time-of-flight laser ranging, 940nm Class 1 laser. Full driver at `drivers/input/misc/vl53l1/`. DTS: `gs201-ldaf.dtsi`. Repo: `android.googlesource.com/kernel/google-modules/lwis` |
| Speaker amplifier        | Cirrus Logic CS35L41 (x2) | `cirrus,cs35l41`             | SPI (spi7.0/7.1)  | Stereo, one per speaker. Module: `google-modules/amplifiers/cs35l41`. VMON/IMON on TDM_0.                                                                                           |
| Haptic driver 1          | Cirrus Logic CS40L26A     | (vendor module)              | I2C (bus 8, 0x43) | Advanced haptic driver with waveform memory. Module: `google-modules/amplifiers/cs40l26`                                                                                            |
| Haptic driver 2          | TI DRV2624                | (vendor module)              | I2C               | Secondary haptic actuator driver. Module: `google-modules/amplifiers/drv2624`                                                                                                       |
| Audio metrics            | Google                    | (vendor module)              | N/A               | Module: `google-modules/amplifiers/audiometrics`                                                                                                                                    |
| Touch controller 1       | FocalTech FTM5            | (vendor module)              | SPI (10 MHz)      | Capacitive touch with gesture recognition. Module: `google-modules/touch/fts/ftm5`                                                                                                  |
| Touch controller 2       | Samsung SEC               | (vendor module)              | SPI               | Secondary touch controller. Module: `google-modules/touch/sec`                                                                                                                      |
| Edge TPU                 | Google (Abrolhos)         | `/sys/class/edgetpu`         | Internal          | ML inference accelerator (4 TOPS). Module: `google-modules/edgetpu/abrolhos`                                                                                                        |
| AOC (Always-On Computer) | Google Whitechapel        | `google,mailbox-whitechapel` | Mailbox IPC       | Ultra-low-power coprocessor for CHRE nanoapps. Handles sensor fusion, Now Playing, crash detection. Module: `google-modules/aoc`                                                    |
| Camera HAL               | Google LWIS               | (vendor module)              | Various           | Light Weight Imaging Subsystem. Module: `google-modules/lwis`                                                                                                                       |
| Camera PMIC              | Dialog SLG51002           | (DTS regulators)             | I2C               | 8-output camera power management IC                                                                                                                                                 |
| Fingerprint sensor       | Goodix                    | `/sys/class/goodix_fp`       | SPI               | Under-display optical fingerprint                                                                                                                                                   |
| USB-C PD controller      | Maxim MAX77759TCPC        | (DTS)                        | I2C               | USB Type-C power delivery + sensing                                                                                                                                                 |
| Display                  | Samsung                   | (vendor module)              | MIPI DSI          | Module: `google-modules/display/samsung`                                                                                                                                            |
| GPU                      | ARM Mali-G710 MP7         | (vendor module)              | Internal          | Module: `google-modules/gpu/mali_kbase`                                                                                                                                             |
| BMS                      | Google                    | (vendor module)              | I2C               | Battery management system. Module: `google-modules/bms`                                                                                                                             |
| PDM microphones          | (SoC internal)            | `/dev/snd/pdm*`              | PDM @ 24.576 MHz  | Pulse Density Modulation mic interface                                                                                                                                              |
| LIRC (IR)                | Unknown                   | `/dev/lirc*`                 | GPIO              | Infrared emitter/receiver (likely for proximity or remote)                                                                                                                          |

### LDAF sensor details

The VL53L1 is a discrete time-of-flight laser ranging sensor from STMicroelectronics,
sitting on I2C bus hsi2c_1 at address 0x29. It has its own interrupt (GPA6 pin 5),
shutdown GPIO, and power enable GPIO. Powered at 1.8V from LDO12.

This is effectively a miniature LIDAR (940nm invisible Class 1 laser) that measures
distances up to ~4 meters with millimeter precision. It's used for camera autofocus,
but the hardware is a general-purpose ranging sensor.

LWIS kernel module repo: `https://android.googlesource.com/kernel/google-modules/lwis`
(branch `android-gs-pantah-5.10-android13-d1` for Pixel 7 Pro).

### Google's LRA-as-sensor research

Google Research published work showing the CS40L25 haptic driver can double as a sensor.
By reading back-EMF voltage from the LRA vibration motor during short actuation pulses,
the system detects surface type (hand, foam, table), pressure, and finger contact.
Published at CHI 2022. The Pixel 7 Pro has the hardware to do this.

Source: `Makefile.ext_modules.slider`, device tree files,
`arch/arm64/boot/dts/google/gs201-cheetah-common.dtsi` include chain.

### Relay corrections (verified against kernel source, 2025-05-25)

- NFC chip is ST21NFC, NOT NXP PN557 or NXP PN80T (Gemini was wrong)
- UWB chip is Qorvo DW3000, NOT NXP SR100T (Gemini was wrong)
- WiFi chip confirmed BCM4389 via vendor module name `bcmdhd4389`
- GPS is discrete Broadcom BCM4775, not the modem's built-in GNSS

## Physical sensors (from live `dumpsys sensorservice`)

43 sensors total. 6 physical sensor chips on I2C, plus software-fused and Google-proprietary sensors.

### Inertial (InvenSense ICM45631)

| Sensor                       | Type                           | Rate       | Notes                          |
| ---------------------------- | ------------------------------ | ---------- | ------------------------------ |
| Accelerometer                | `android.sensor.accelerometer` | 1.5-400 Hz | FIFO 3000, no permission       |
| Accelerometer (uncalibrated) | type 35                        | 1.5-400 Hz | Raw without calibration offset |
| Gyroscope                    | `android.sensor.gyroscope`     | 1.5-400 Hz | FIFO 3000, no permission       |
| Gyroscope (uncalibrated)     | type 16                        | 1.5-400 Hz | Raw without calibration offset |
| Gyro temperature             | private type 65538             | 1.5-50 Hz  | Internal die temperature       |
| Motion detect                | private type 65554             | One-shot   | Triggers on any movement       |
| Stationary detect            | private type 65555             | One-shot   | Triggers when stationary       |

### Pressure (InvenSense ICP20100)

| Sensor               | Type                      | Rate    | Notes                    |
| -------------------- | ------------------------- | ------- | ------------------------ |
| Barometric pressure  | `android.sensor.pressure` | 1-40 Hz | FIFO 3000, no permission |
| Pressure temperature | private type 65539        | 1-40 Hz | Pressure die temperature |

### Optical (AMS TMD3719)

Behind-OLED sensor with 6 color channels (R, G, B, clear, leakage, wideband) + flicker detection.

| Sensor        | Type                       | Rate | Notes                            |
| ------------- | -------------------------- | ---- | -------------------------------- |
| Ambient light | `android.sensor.light`     | 1 Hz | On-change trigger, no permission |
| Proximity     | `android.sensor.proximity` | 1 Hz | Wake-up capable, no permission   |

### Magnetic (MEMSIC MMC56X3X, dual)

| Sensor                   | Type                            | Rate        | Notes                          |
| ------------------------ | ------------------------------- | ----------- | ------------------------------ |
| Fused magnetometer       | `android.sensor.magnetic_field` | 1.25-100 Hz | Google software fusion of both |
| Fused mag (uncalibrated) | type 14                         | 1.25-100 Hz | Raw fused without offset       |
| Raw magnetometer 0       | private type 65553              | 1.25-100 Hz | First physical chip            |
| Raw magnetometer 1       | private type 65553              | 1.25-100 Hz | Second physical chip           |

Dual magnetometer setup is unusual. Google fuses both in software for improved compass accuracy.

### Far-Infrared (Melexis MLX90632)

| Sensor                   | Type                | Rate  | Notes                               |
| ------------------------ | ------------------- | ----- | ----------------------------------- |
| FIR temperature          | private type 131089 | 64 Hz | Signature-level permission required |
| FIR extended temperature | private type 131090 | 20 Hz | Signature-level permission required |

50-degree FOV. Range: -20C to 200C. Access requires `com.google.sensor.permission.FAR_INFRARED_TEMPERATURE`
(signature-only, not grantable even with root). Region-locked via Thermometer app.
Raw I2C reads may be possible with root + SELinux permissive.

### Rear Light (STMicroelectronics VD6282)

| Sensor     | Type               | Rate         | Notes                                         |
| ---------- | ------------------ | ------------ | --------------------------------------------- |
| Rear light | private type 65545 | 2.44-62.5 Hz | 6-channel multispectral (R,G,B,NIR,UVA,clear) |

Mounted near camera. Used for flash/torch control. 6-channel spectral data makes it
closer to a spectrometer than a simple light sensor. Flicker detection for LED PWM.

### Camera V-Sync (Google, 4x)

4 timing synchronization sensors, one per camera module. Private sensor type 65541.

### Software-fused sensors (Google)

- Gravity, linear acceleration, rotation vectors (game, geomagnetic), orientation (6)
- Step detector/counter (2)
- Gesture: significant motion, tilt, lift-to-wake, pick-up, double-twist, single-tap, long-press (7)
- Display: binned brightness, auto brightness, device orientation (3)
- AAD proximity, voice call proximity (2)
- Dynamic sensor manager (1)

## Known attacks and side channels

### Accelerometer + Gyroscope (ICM45631) -- NO PERMISSION REQUIRED

- **EarSpy**: Eavesdrops on calls via ear speaker vibrations. 98% gender ID, 92% speaker ID, 56% digits.
- **Keystroke inference**: Infers PINs from phone tilt during typing. 90.2% accuracy.
- **Acoustic injection**: Sound waves physically move MEMS element, spoofing readings.
- **SensorID**: Factory calibration errors create permanent device fingerprint. < 1 second, survives factory reset. Pixel 2/3 specifically tested.
- **Ultrasonic tracking**: Gyro detects inaudible beacons from TV/ads (SilverPush, 234 Play Store apps). Used to deanonymize Tor users.
- **Temporal misalignment**: Exploits accel/gyro timing differences to eavesdrop past 200 Hz sampling cap.

### Barometer (ICP20100) -- NO PERMISSION REQUIRED

- **Barometer as microphone** (2025): Detects pressure waves from ear speaker at 25 Hz sampling.
- **Finger tap detection** (2025): Screen press changes internal air pressure, reveals tap location.
- **Indoor floor tracking**: 10 cm altitude resolution enables floor-level surveillance.

### Ambient Light (TMD3719) -- NO PERMISSION REQUIRED

- **Screen content imaging** (MIT 2024, Science Advances): Reconstructs hand gestures from reflected light variations. Deep learning denoises to pixelated images. One frame per 3.3 min.
- **PIN skimming**: Screen brightness changes during key entry leak PIN digits.

### Magnetometer (MMC56X3X) -- NO PERMISSION REQUIRED

- **MagneticSpy**: CPU EM disturbances reveal active app (90%) and webpage (91%).
- **Speaker eavesdropping**: Picks up magnetic fields from nearby speakers proportional to audio.
- **SensorID (mag component)**: Calibration fingerprinting includes magnetometer.

### Proximity (TMD3719) -- NO PERMISSION REQUIRED

- Screen on/off timing leaks call behavior patterns.

### FIR Temperature (MLX90632) -- SIGNATURE PERMISSION

- Locked down by Google. XDA reverse engineering in progress. Theoretical: crude thermal
  imaging via scanning, presence detection, body temperature monitoring.

### Rear Light (VD6282) -- PRIVATE SENSOR TYPE

- No known attacks. 6-channel spectral data could theoretically identify light source types
  or detect optical communication. Too new and obscure for published research.

### LDAF / VL53L1 -- CAMERA SUBSYSTEM (I2C 0x29)

- **Miniature LIDAR**: 940nm Class 1 laser, measures distance to ~4m with mm precision.
  No known side-channel attacks, but ranging data could theoretically detect approaching
  objects/people, breathing rate from chest movement, or room geometry.
- **Raw access**: Exposed as `/dev/stmvl53l1` input device on rooted devices.
  LWIS module provides ioctl interface for raw ranging measurements.

### Speaker amplifier (CS35L41 x2) -- ACTIVE RESEARCH TARGET

- **VMON/IMON feedback as covert microphone** (novel, unpublished): The CS35L41 smart
  amplifier has built-in voltage (VMON) and current (IMON) monitoring ADCs for speaker
  protection. When no audio is playing, ambient sound vibrating the speaker cone generates
  back-EMF detectable on these channels. Architecturally distinct from Speake(a)r (which
  used audio codec jack retasking). NXP TFA9911 proves speaker-as-microphone via smart amp
  is commercially feasible; no academic security paper has demonstrated this as an attack.
- **Stereo capture**: Two CS35L41 chips enable binaural audio recovery.
- **Access**: I2C registers for VMON/IMON data; also exposed on I2S/TDM feedback bus.
- **Experiment repo**: `sensors/amp-eavesdrop/`

### Haptic motor (CS40L25) -- PUBLISHED ATTACK EXISTS

- **VibraPhone** (Roy & Choudhury, MobiSys 2016): Demonstrated >80% word intelligibility
  from vibration motor back-EMF. This attack is already published at a top venue.
- **LRA as sensor** (Dementyev et al., UIST 2020): Back-EMF sensing detects surface
  type, pressure, and contact.
- **Surface fingerprinting**: Different surfaces produce different vibration damping signatures.
- Not a novel research target for eavesdropping.

### Touch controller (FTM5) -- GESTURE DATA

- Capacitive touch controllers like the FTM5 have raw data modes that expose per-electrode
  capacitance values, potentially revealing objects near but not touching the screen.
- Touch timing and pressure data available to any app with touch event access.

## What the kernel source does NOT show

- **RF front-end components**: power amplifiers, antenna tuners, RF switches, SAW/BAW filters
  (controlled by S5300 modem firmware, invisible to Linux)
- **mmWave hardware** on GE2AE variant (modem-firmware controlled)
- **Fused-off SoC IP blocks**: Tensor G2 may contain disabled IP blocks with no driver
- **Analog components**: controlled by other chips' firmware, no kernel presence

## Software discovery methods (rooted device)

```bash
# Dump live device tree (may differ from source due to overlays)
dtc -I fs -O dts /proc/device-tree > /sdcard/live_dt.dts

# List all loaded kernel modules
lsmod

# List vendor modules (even unloaded ones)
find /vendor/lib/modules -name "*.ko"

# Scan I2C buses for unknown devices
for i in $(seq 0 20); do i2cdetect -y $i 2>/dev/null; done

# Enumerate all bus devices
ls /sys/bus/pci/devices/
ls /sys/bus/spi/devices/
ls /sys/bus/i2c/devices/

# Full sensor dump
dumpsys sensorservice

# Memory-mapped hardware blocks
cat /proc/iomem

# Boot log for driver probes
dmesg | grep -iE 'probe|compatible|found|detected|chip'

# Clock tree (reveals every hardware block with a clock gate)
cat /sys/kernel/debug/clk/clk_summary

# Firmware blob strings
strings /vendor/firmware/* | grep -iE 'bcm|qorvo|skyworks|qualcomm|murata|melexis|memsic|invensense'
```

## Sensor sub-repos (git submodules)

| Submodule path                | Sensor          | Status  | Key result                                                                               |
| ----------------------------- | --------------- | ------- | ---------------------------------------------------------------------------------------- |
| `sensors/mag-gradiometer/`    | MMC56X3X (dual) | Working | 0.061 uT gradient sensitivity, metal detection through walls                             |
| `sensors/tof-rangefinder/`    | VL53L1 (LDAF)   | Working | 2.3 um precision, 340 Hz, raw photon histograms via BPF kprobes. 1900x better than stock |
| `sensors/uwb-rangefinder/`    | DW3000 (UWB)    | Working | 64-bin Channel Impulse Response at 16.7fps via patched dw3000.ko                         |
| `sensors/fingerprint-camera/` | Goodix (UDFPS)  | Early   | Goal: raw monochrome frames from optical fingerprint sensor                              |
| `sensors/amp-eavesdrop/`      | CS35L41 (x2)    | Paper   | Novel: speaker amp VMON/IMON as covert microphone. 10pp USENIX Security paper draft      |

## Related repos (not submodules)

| Repo                                                                             | Purpose                                                |
| -------------------------------------------------------------------------------- | ------------------------------------------------------ |
| `~/tools/pixel-llm/`                                                             | On-device LLM inference (Qwen 1.5B), 154M ZO steps/sec |
| `~/Documents/GitHub/llm-memorization-research/experiments/03-pixel-zo-training/` | Zeroth-order LoRA training, 4 TOPS TPU benchmarks      |
| `~/Documents/GitHub/chatgpt-android-api/`                                        | ChatGPT relay via UI automation + MITM on Pixel        |
| `~/tools/frida-ssl-bypass/`                                                      | Frida SSL unpinning + system prompt rewriter           |
| `~/Documents/GitHub/homeserver-setup/`                                           | H1 server setup, includes Pixel benchmark data         |

## FCC filings

- Sub-6 model GP4BC: FCC ID A4RGP4BC
- mmWave model GE2AE: FCC ID A4RGE2AE (also covers GFE4J)

---

Last updated: 2026-05-25. Source session: kernel source audit + ADB sensor enumeration + relay cross-verification + web research.
