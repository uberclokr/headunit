# CLAUDE.md — Helm head unit

Read this first. It is the working contract for any Claude Code session on
this repo.

## What this is

Helm is a custom Android 14 head-unit dashboard for a 2008 Nissan Xterra
built as a reconnaissance / emergency-operations vehicle. Hardware: Khadas
Edge2 (RK3588) + IO hat, 10" multitouch landscape screen, house-battery
powered (always on), full-time Starlink internet, vehicle LAN. Helm replaces
the Android launcher (HOME intent) and runs kiosk-style.

The owner is deeply technical (WISP operator, drone/VTOL builder, ArduPilot,
RF/SDR, embedded). Do not simplify explanations; do not add hand-holding UI.
Prefer correctness and density of information over friendliness.

## Mission (what the app aims to accomplish)

1. One glass for everything the vehicle does: navigation, audio, engine
   data, cameras, radio spectrum, house power — dockable side-by-side.
2. Safety-critical behaviors are automatic: rear camera takes the whole
   screen the instant reverse engages, regardless of foreground app.
3. Vehicle truth from the bus: OBD/CAN data (RPM, speed, temps, MPG from
   MAF) live at ~8 Hz, with a raw-CAN path for reverse-engineering the
   D40 body bus.
4. The house battery is *the* battery: Renogy BLE monitor mirrored into
   Android's BatteryService so the OS itself reports pack SOC.
5. Recon/EOC growth path: MAVLink drone telemetry + FPV (no ATAK — dropped),
   SDR modes beyond FM (ADS-B, NOAA/SAME), Starlink health, sortie logging.
6. Always-on reliability: foreground services, START_STICKY, boot receiver,
   auto-reconnect loops on every external link (USB serial, BLE, RTSP, TCP).

Full per-feature specs: `docs/FEATURES.md`. Priorities: `docs/ROADMAP.md`.

## Repo map

```
app/src/main/java/com/xterra/helm/
  HelmApp.kt            service locator; owns all repositories
  MainActivity.kt       kiosk window, permission bootstrap, DashboardShell
  ui/                   PaneManager (dock model), DashboardShell (layout),
                        theme/ ("instrument glass" palette + type)
  widgets/              one file per dockable widget; all are dumb
                        StateFlow collectors — no business logic here
  can/                  Elm327Manager (USB OBD), GpioReverseSensor,
                        SocketCanManager (JNI stub), CanRepository
  media/                MediaRepository (MediaSessionManager, universal
                        transport), SpotifyRemote (App Remote SDK),
                        HelmNotificationListener
  cameras/              CameraRegistry (RTSP URLs), RtspView (LibVLC),
                        ThermalView (UVC placeholder),
                        ReverseOverlayService (auto backup cam)
  sdr/                  RtlTcpClient, Dsp (FFT + WBFM demod),
                        SdrRepository (audio out, waterfall, band scan)
  power/                RenogyBleClient (Modbus-over-GATT),
                        BatteryRepository, SystemBatteryBridge (dumpsys)
  system/               VehicleService (always-on FGS), BootReceiver,
                        AppLauncher (freeform external apps)
docs/                   feature specs, hardware, roadmap, subsystem guides
```

## Architecture rules

- Every subsystem is a repository exposing `StateFlow`; widgets collect and
  render. Keep it that way — it's what makes panes freely dockable.
- No DI framework yet; `HelmApp` is the locator. If the graph grows past
  ~8 repos, migrate to Hilt in one PR, not incrementally.
- All external I/O lives in `Dispatchers.IO` coroutines with infinite
  retry/backoff loops. Nothing may crash the process on link loss.
- Config constants (MACs, IPs, GPIO pin, Spotify client ID) are currently
  inline `companion object` values — an acceptable v0.9 shortcut. A settings
  screen backed by DataStore is on the roadmap; don't scatter new config,
  add to the same obvious places.
- UI: Compose only, `HelmColors`/`HelmType` tokens only. Amber = controls,
  cyan = live data, red = genuine alerts only. Monospace for any number
  that updates live.

## Build & verify

```bash
./gradlew :app:assembleDebug          # requires Android SDK 34
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Helm AndroidRuntime
```

Prerequisites the build needs (see README for detail):
- `app/libs/spotify-app-remote-release-0.8.0.aar` (Spotify GitHub releases)
- `local.properties`: `MAPS_API_KEY=...`
- Device one-time: freeform settings, GPIO export, root present.

## Honest status — read before assuming anything compiles

This codebase was **generated in a single design session and has never been
compiled**. Treat the first build as a shakedown task:

- Expect missing/wrong imports and small Compose API drift (BOM 2024.06).
- Library versions in `app/build.gradle.kts` were chosen from memory —
  verify each against its repo, especially `libausbc` and `libvlc-all`.
- LibVLC callback/API usage in `RtspView.kt` and BLE writeCharacteristic
  deprecations in `RenogyBleClient.kt` (API 33 changed signatures — the
  pre-33 path is used deliberately for Edge2 vendor images; keep both).
- `AppLauncher.setLaunchWindowingMode` uses reflection on a hidden API —
  works on AOSP-ish RK3588 images; wrap failures silently (already done).
- Renogy register maps and Viofo RTSP paths follow community documentation
  and MUST be verified against the actual hardware (nRF Connect / ffprobe).
  Do not "fix" register addresses speculatively; ask for a device capture.

When you fix a compile/runtime issue, keep the comment style: terse,
explains *why*, references the hardware quirk if there is one.

## Testing on hardware

There is no emulator story for most of this (USB serial, BLE, GPIO, LibVLC
on RK3588). Structure work so pure logic is unit-testable:
- `Dsp.kt` (FFT, WBFM math), Modbus CRC/framing, ELM327 response parsing,
  and `MpgIntegrator` are pure — add JUnit tests for these first.
- Everything hardware-facing gets a fake behind its repository when needed.

## Things NOT to do

- Do not add a desktop-style window manager. Two panes + dock is the design.
- Do not replace LibVLC with Media3 for the rear camera (latency regression).
- Do not remove the pre-API-33 BLE/GATT compatibility paths.
- Do not introduce network calls to third-party telemetry/analytics. This
  vehicle's traffic profile is deliberately quiet.
- Do not auto-format the whole repo in an unrelated PR.
