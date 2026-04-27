# TbWatchSensors — Galaxy Watch 8 raw biometric streamer

Türkay's personal research project. Standalone Wear OS app for the Galaxy
Watch 8 (40 mm / 44 mm). Reads every data type exposed by the Samsung Health
Sensor SDK and forwards everything over a custom BLE GATT service.

**Bundle ID:** `com.tbiliyor.watchsensors`
**Wellness/research only.** Not a medical device.

## Data types

All ten data types from Samsung Health Sensor SDK 1.4.1 are wired through:

| Type | Tracker | Mode | BLE characteristic |
|---|---|---|---|
| Accelerometer raw | `ACCELEROMETER_CONTINUOUS` | continuous | `7b1d0004-…` |
| Heart rate (+ IBI) | `HEART_RATE_CONTINUOUS` | continuous | `7b1d0003-…` |
| PPG raw (G/R/IR) | `PPG_CONTINUOUS` | continuous | `7b1d0002-…` |
| Skin temperature | `SKIN_TEMPERATURE_CONTINUOUS` | continuous | `7b1d0005-…` |
| EDA | `EDA_CONTINUOUS` | continuous | `7b1d0008-…` |
| ECG raw | `ECG_ON_DEMAND` | on-demand | `7b1d0009-…` |
| BIA | `BIA_ON_DEMAND` | on-demand | `7b1d000a-…` |
| MF-BIA | `MF_BIA_ON_DEMAND` | on-demand | `7b1d000b-…` |
| SpO2 | `SPO2_ON_DEMAND` | on-demand | `7b1d000c-…` |
| Sweat loss | `SWEAT_LOSS` | workout toggle | `7b1d000d-…` |

All five continuous trackers stream simultaneously when streaming is
enabled. The five on-demand measurements are triggered individually by
control-characteristic commands.

## BLE control commands

Write a single byte to characteristic `7b1d0006-…`:

| Code | Meaning |
|---|---|
| `0x01` | Start continuous streaming |
| `0x02` | Stop continuous streaming |
| `0x10` | Trigger ECG measurement (~30 s) |
| `0x11` | Trigger BIA measurement |
| `0x12` | Trigger multi-frequency BIA |
| `0x13` | Trigger SpO2 measurement |
| `0x14` | Toggle sweat-loss tracking (workout) |
| `0x1F` | Cancel any active on-demand measurement |
| `0x20` | Force a status notification now |
| `0xF0` | Reset session counter |

## Watch UI

Eleven-page horizontal pager, swipe left/right:

```
[Status] → [Vario] → [PPG] → [Heart] → [Motion] → [EDA+SpO2]
        → [ECG+Sweat] → [BIA] → [Ambient] → [GPS] → [BLE]
```

Auto-scaling layout adjusts typography and spacing based on the screen
size reported by `BoxWithConstraints`. Tested mental model: 432 dp = 1.0 ×
scale (Watch 8 40 mm); 480 dp = 1.11 ×; emulator "Small Round" 360 dp = 0.83 ×.

## Architecture

```
+-----------------------------------+
|  Galaxy Watch 8 (Wear OS 5)       |
|                                   |
|  Samsung Health Sensor SDK        |
|     ↓ ConnectionListener +        |        BLE GATT custom UUIDs
|       TrackerEventListener        |  ----------------------------->  any BLE central
|     ↓                             |   PPG / HR / ACC / Temp / EDA
|  SamsungSensorBridge              |   + ECG / BIA / MF-BIA /
|                                   |     SpO2 / Sweat
|  Android SensorManager            |   + Gyro / Mag / Baro / Light
|     ↓                             |   + GPS
|  AndroidSensorBridge              |
|                                   |
|  LocationManager + GnssStatus     |
|     ↓                             |
|  GpsBridge                        |
|                                   |
|  All bridges reference-counted    |
|  (UI / BLE owners), idle when     |
|  no owner — zero power draw.      |
|                                   |
|     ↓ Kotlin Flow per data type   |
|  SensorStreamService              |
|     ↓ collectLatest →             |
|  SensorGattServer (peripheral)    |
|                                   |
|  Variometer (barometric)          |
|     ↑ baroFlow tap                |
|     ↓ vertical-speed (m/s)        |
|  VarioAudioEngine                 |
|     → AudioTrack PCM_FLOAT        |
|                                   |
|  Compose dashboard, 11 pages      |
+-----------------------------------+
```

## Files

- `watch/build.gradle.kts` — module build, Wear-Compose deps, Samsung SDK aar
- `watch/libs/samsung-health-sensor-api-1.4.1.aar` — partner-approval-only SDK
- `watch/src/main/AndroidManifest.xml`
- `watch/src/main/java/tb/sw/`
    - `SamsungSensorBridge.kt` — SDK wrapper, all 10 Samsung trackers
    - `AndroidSensorBridge.kt` — gyroscope, magnetometer, barometer, light
    - `GpsBridge.kt` — GNSS / GPS via LocationManager + GnssStatus
    - `SensorGattServer.kt` — BLE GATT peripheral, 16 characteristics
    - `SensorStreamService.kt` — foreground service, command dispatcher,
      reference-counted sensor lifecycle
    - `WatchStats.kt` — singleton state holder
    - `presentation/MainActivity.kt` — Compose UI, 11-page pager,
      auto-scaling for any round Wear OS screen
    - `shared/SensorProtocol.kt` — UUID constants and packet formats
    - `vario/`
        - `Variometer.kt` — barometric altitude → vertical speed
          with two-stage IIR filtering
        - `VarioAudioEngine.kt` — synthesised paragliding-vario tone,
          AudioTrack-based, harmonic-rich timbre
        - `VarioState.kt` — singleton glue, StateFlow for UI

## Behavior

1. App starts → `SensorStreamService` runs as foreground service with a
   wake lock.
2. `SamsungSensorBridge.connect()` connects to Samsung Health Tracking
   Service. Trackers stay off.
3. `AndroidSensorBridge` and `GpsBridge` are constructed but idle.
4. `SensorGattServer.start()` registers the GATT service, advertises it.
5. **Sensors are off** until either the UI binds (foreground page) or
   a BLE central writes `0x01` (start streaming). Reference-counted
   ownership means a tracker runs as long as *any* owner holds it,
   stops only when both UI and BLE release.
6. On `0x01` (start streaming): all continuous trackers start
   (5 Samsung + 4 Android sensors + GPS); data fans out to the
   matching characteristics.
7. On `0x10`–`0x14` (on-demand): the matching Samsung tracker starts;
   result arrives on its dedicated characteristic; tracker auto-stops.
8. On `0x02`: continuous trackers stop, advertising continues.
9. The barometer flow is also tapped by the variometer. Pressure
   samples flow to both the BLE Baro characteristic and the on-watch
   variometer page in parallel.

---

## Variometer

The watch doubles as a paragliding / soaring variometer: the kind
of audible pulsing-tone instrument pilots use to detect lift while
flying. It uses the watch's barometric pressure sensor, with no
external hardware.

**Signal pipeline:**

```
pressure (hPa)
    → altitude (m)            via ICAO troposphere formula
    → low-pass filter         IIR, τ ≈ 200 ms (smooths sensor noise)
    → derivative              vertical speed = Δalt / Δt
    → low-pass filter         IIR, τ ≈ 400 ms (smooths the derivative)
    → ±5 cm/s deadband        kills jitter while at rest
```

The two-stage filter is a standard hobby-vario design. Filtering
altitude alone would delay lift detection too much; filtering the
derivative alone would be jumpy. The combination gives both quick
response and a clean reading.

**Audio engine** (`VarioAudioEngine.kt`):

- AudioTrack PCM_FLOAT mono 44.1 kHz, 1024-frame buffer,
  MAX_PRIORITY render thread
- Carrier: harmonic-rich "piezo buzzer" timbre —
  `1.0·sin(φ) + 0.55·sin(3φ) + 0.25·sin(5φ) + 0.05·sin(2φ)`
- **Climb tone:** pitch glides 700 → 1600 Hz over 0..6 m/s climb;
  cadence 2.5 → 8 Hz (faster pulsing as climb gets stronger);
  cosine attack/release ramps so there are no clicks between beeps.
- **Sink alarm:** continuous 200 Hz drone with 2.2 Hz FM vibrato
  ±40 Hz, same harmonic timbre.
- **Dead band** between climb and sink thresholds is silent, so
  level flight produces no sound.

The audio behaviour is a direct port from a tested iOS/Swift
variometer — same constants, same envelope, same harmonic mix.

**Vario page:**

- Big vertical-speed readout in m/s — green for climb, red for sink,
  colour intensity scales with magnitude
- Altitude (m) and pressure (hPa) underneath
- **START / STOP button** at the bottom toggles audio. Visual
  feedback only continues whether or not audio is on.

The variometer also works on the ground for testing — small height
changes (raising the wrist by ~0.5 m) produce visible m/s
deflections.

---

## Building and running

### Prerequisites

- Android Studio Koala (2024.1.1) or newer
- Android SDK 34
- Galaxy Watch 8 (40 mm or 44 mm) with developer options enabled
- The Samsung Health Sensor SDK aar in `watch/libs/`

### Build

```
./gradlew :watch:assembleDebug
```

Output: `watch/build/outputs/apk/debug/watch-debug.apk`

### Install on the watch

Either via Android Studio Run, or manually:

```
adb -s <watch-serial> install watch/build/outputs/apk/debug/watch-debug.apk
```

---

## Health Platform developer mode (REQUIRED before sensor data flows)

The Samsung Health Sensor SDK normally requires a partner-approval
registration of your app's package name and release-key signature.
Without that registration the SDK returns `SDK_POLICY_ERROR` on
`connectService()` and no sensor data is delivered.

For testing and personal use, Samsung provides **Health Platform
Developer Mode**. When enabled, the SDK skips the package/signature
check, so an unsigned debug APK can read sensor data immediately —
no partner application required.

**Per Samsung's documentation, this mode is for testing/debugging
only and must not be enabled for end users.** For personal research
use it is the supported path; only public release requires the
partner application.

### Enabling

On the Galaxy Watch 8:

1. Open **Settings**.
2. Go to **Apps**.
3. Scroll down and tap **Health Platform**.
4. Tap the **Health Platform** title text **about ten times in
   rapid succession**.
5. The label changes to **Health Platform [Dev mode]**, confirming
   developer mode is on.

### Disabling

Tap the title once to turn it back off.

### Verifying it worked

After enabling Dev mode, launch TbWatchSensors. The Status page should
show **Connected** in green next to the Samsung SDK label. If it still
says *SDK error 1* or similar, Dev mode isn't on.

---

## Sample-rate references

Samsung SDK doesn't publicly nail down exact rates, but in practice
on Galaxy Watch 8:

| Stream | Rate |
|---|---|
| PPG continuous | ~25 Hz, three channels |
| Heart rate continuous | ~1 Hz, with IBI list |
| Accelerometer continuous | ~25 Hz |
| Skin temperature continuous | ~0.1 Hz (every ~10 s) |
| EDA continuous | ~25 Hz (varies by device) |
| ECG on-demand | ~500 Hz during ~30 s session |
| SpO2 on-demand | one final reading after ~30 s |
| Gyroscope (Android) | ~16 Hz (SENSOR_DELAY_UI) |
| Magnetometer (Android) | ~16 Hz |
| Barometer (Android) | ~5–25 Hz, device-dependent |
| Light (Android) | event-driven, on change |
| GPS / GNSS | ~1 Hz when fixed (dual-band L1+L5) |

Aggregate BLE bandwidth during full streaming: ~1.5 kB/s (well below
BLE practical limits).

---

## Research roadmap

Directions for working with the streamed data. None of these run on
the watch itself — the watch is a data source. Signal processing
happens in Python on a host receiving the BLE stream.

**A) HRV pipeline (easiest first step)**
PPG → peak detection → RR-interval extraction → time-domain metrics
(RMSSD, SDNN) and frequency-domain metrics (LF/HF). 5-minute sessions
plus all-day trend. Python prototype using `heartpy` or `neurokit2`.

**B) Sleep staging**
Record PPG + ACC + skin-temperature overnight, train a sleep-stage
classifier using `yasa` or `sleepecg`. For ground truth, cross-check
against another sleep tracker or a clinical-grade device.

**C) Autonomic nervous system monitoring**
Stress, meditation, athletic recovery. Built on top of the HRV
pipeline. Multiple short measurements through the day; track trend
vs. self-reported state.

**D) Thermal / local perfusion**
Hot shower, cold exposure, pre-/post-exercise microcirculation
changes. Captures both PPG amplitude shifts and skin-temperature
shifts simultaneously. Requires a controlled lab-style protocol.

**E) Respiratory biofeedback**
Extract respiration rate from PPG amplitude/period modulation, give
the user a visual or audio cue (slow breathing trainer, anxiety
management, meditation aid). Could integrate with the existing
audio engine.

**F) Long-term personal baseline**
Months of daily 5-minute measurements at the same time. Learn the
person's individual normal. Detect anomalies — early-illness signal,
fatigue, post-exercise recovery quality.

Suggested approach: skip on-device computation. Record raw
PPG/HR/ACC/Temp via BLE, write to parquet/CSV, build up a dataset.
Explore in a Jupyter notebook with `matplotlib`, `scipy.signal`, and
`neurokit2` (which provides ~30 ready-made PPG metrics).

---

## Disclaimers

This software exposes raw sensor signals. It does not infer or
display blood glucose, diagnose any condition, or estimate any
medical metric. Anything done with these signals downstream is the
consumer's responsibility. Never rely on it for medical decisions.

— Türkay
