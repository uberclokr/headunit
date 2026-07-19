# Helm

An always-on head-unit dashboard for the Xterra build — Khadas Edge2 (RK3588,
Android 14), 10" multitouch, house-battery powered.

**Design language: "instrument glass."** Deep blue-black panels, phosphor-amber
for controls (night-vision safe), glacier-cyan for live data, red reserved for
genuine alerts. Monospace numerals so gauges never jitter. Two big panes with a
draggable divider instead of a desktop window manager — at arm's length in a
moving truck, two targets you can hit blind beats ten you can't.

```
┌─────────────────────────────────────────────────────┐
│ 14:32   ● CAN   BATT 13.8V   ECT 88°        SPD 47  │
├───────────────────────┬──╢ ╟────────────────────────┤
│                       │                             │
│         NAV           │       MEDIA / GAUGES /      │
│      (map pane)       │       CAMS / SDR / ...      │
│                       │                             │
├───────────────────────┴─────────────────────────────┤
│ NAV  MEDIA  VEHICLE  CAM·FWD  THERMAL  DRONE  SDR ⇄ │
└─────────────────────────────────────────────────────┘
```

## Documentation map

- `CLAUDE.md` — working contract for Claude Code sessions: mission,
  architecture rules, honest status, do-nots. **Start here.**
- `docs/FEATURES.md` — per-feature spec: goal, behavior, status,
  acceptance criteria, open work.
- `docs/ROADMAP.md` — phased priorities (shakedown → recon/EOC → depth).
- `docs/HARDWARE.md` — wiring, one-time device commands, bench checklist.
- `docs/SOCKETCAN.md`, `docs/THERMAL.md` — subsystem build guides.

## What's implemented

| Subsystem | Status | Files |
|---|---|---|
| Pane/dock UI, widget picker, drag divider | ✅ complete | `ui/` |
| CAN via USB ELM327 (RPM, speed, temps, MAF→MPG, fuel, voltage) | ✅ complete | `can/Elm327Manager.kt` |
| Reverse detection via GPIO reverse-lamp tap | ✅ complete (set pin #) | `can/GpioReverseSensor.kt` |
| Auto full-screen backup cam overlay on reverse | ✅ complete | `cameras/ReverseOverlayService.kt` |
| RTSP camera panes (Viofo A329 ×3 + drone feed), low-latency LibVLC | ✅ complete (set IPs) | `cameras/RtspView.kt` |
| Universal media transport (YT Music, Audible, any MediaSession) | ✅ complete | `media/MediaRepository.kt` |
| Spotify rich control (App Remote SDK) | ✅ needs CLIENT_ID + AAR | `media/SpotifyRemote.kt` |
| SDR: rtl_tcp client, WBFM demod → speakers, spectrum + waterfall, band scan | ✅ complete | `sdr/` |
| Raw SocketCAN sniffer (IO-hat transceiver) | 🔧 JNI stub + full guide | `docs/SOCKETCAN.md` |
| USB thermal (UVC) | 🔧 lib wired, 20-line fragment to add | `docs/THERMAL.md` |
| Freeform side-by-side launching of *external* apps | ✅ complete | `system/AppLauncher.kt` |
| Renogy BLE house battery → POWER pane + Android system battery | ✅ set MAC + kind | `power/` |

This project was generated, not compiled — expect an hour of Android Studio
shakedown (imports, lib version bumps) before first flash. The architecture
and the hard parts (ELM protocol, FM DSP, overlay service, session control)
are done.

## Build

1. Open in Android Studio (Hedgehog+ / AGP 8.5).
2. Download `spotify-app-remote-release-0.8.0.aar` from
   github.com/spotify/android-sdk/releases → `app/libs/`. Register an app at
   developer.spotify.com, set `CLIENT_ID` and redirect URI in
   `SpotifyRemote.kt` (add the redirect to the Spotify dashboard too).
3. `local.properties`: add `MAPS_API_KEY=<key>` (Maps SDK for Android).
4. Build → install → set Helm as HOME when prompted.

## Device setup (one time, adb or root shell)

```bash
# Side-by-side freeform for external apps (Maps beside Helm, etc.)
settings put global enable_freeform_support 1
settings put global force_resizable_activities 1

# GPIO for the reverse-lamp tap (pick your IO-hat pin, see below)
echo 113 > /sys/class/gpio/export
echo in > /sys/class/gpio/gpio113/direction
chmod 644 /sys/class/gpio/gpio113/value
```

Grant on first launch when prompted: **Display over other apps** (reverse
overlay), **Notification access** (media session control), **Location**
(map + speed cross-check).

## Wiring

**Reverse-lamp → GPIO:** reverse-lamp 12 V → 2.2 kΩ → PC817 optocoupler LED →
lamp ground; opto transistor pulls the GPIO to 3V3 (collector to 3V3 via 10 k,
emitter to GND, GPIO at collector = active-low, or invert `ACTIVE_LEVEL`).
Never feed 12 V near the hat directly.

**OBD:** any CH340/FTDI/CP2102 ELM327 USB dongle into the Edge2 hub. Genuine
OBDLink SX is worth it — clone ELMs drop ~30% of fast-poll responses.

**Viofo A329 LAN:** give the cam a static DHCP lease; verify stream paths with
`ffprobe rtsp://<ip>/live` (`/live2` rear, `/live3` interior — confirm on your
firmware) and set them in `CameraRegistry.kt`. Rear channel runs at
150 ms network-caching for the backup view.

**RTL-SDR:** easiest path is the "SDR Driver" app (handles USB permission,
serves rtl_tcp on 127.0.0.1:1234) or Termux `rtl_tcp -a 127.0.0.1`. Helm
auto-connects on opening the SDR pane. Antenna: roof-mag-mount FM whip; add
an FM bandstop later if broadcast blasts your other SDR work.

## House battery (Renogy BLE → system battery)

`power/RenogyBleClient.kt` speaks Renogy's Modbus-over-GATT (write FFD1,
notify FFF1 — the renogy-bt protocol). Two register maps are built in:

- `SMART_BATTERY` — RBT-series batteries with built-in BLE: SOC, V, signed A,
  remaining/total Ah, per-cell volts, pack temp, runtime estimate.
- `BT2_CONTROLLER` — Rover/Wanderer/DCC via a BT-1/BT-2 dongle: SOC, V, A.

Setup: find the MAC in nRF Connect, set `mac`, `kind`, and (hub installs)
`deviceId` in `BatteryRepository.kt`. If you're running the standalone 500 A
shunt monitor, sniff one app exchange with nRF Connect and adjust the
register constants — transport is identical.

**System-battery mirroring:** `SystemBatteryBridge` pushes SOC/charge state
into Android via root `dumpsys battery set level/status/ac` + `unplug`. The
status-bar icon, quick settings, and every app's BatteryManager then report
the house pack — the OS genuinely believes it's the device battery. Re-pushed
each 5 s poll; `dumpsys battery reset` (or reboot) restores stock behavior.
Watch for Battery Saver engaging at low SOC — that's arguably a feature on a
draining pack, or kill it with `settings put global low_power_trigger_level 0`.
For a root-free permanent image, the alternative is a virtual power_supply
kernel node that healthd reads natively; the su path gets identical results
with zero image changes.

## Latency notes (backup camera)

IP-camera reverse view is inherently ~0.3–0.5 s glass-to-glass even tuned
(camera encode + network + decode). Fine for guidance; if you ever want
sub-100 ms, add a $20 analog CVBS camera on a USB capture dongle as the
reverse source and keep the Viofo as the recorded/browsable rear view. The
overlay service doesn't care where the frame comes from — swap `RtspView`
for a UVC view in `ReverseOverlayService`.

## Architecture

```
HelmApp (service locator)
 ├── CanRepository ── Elm327Manager (USB serial poll)
 │        └───────── GpioReverseSensor (sysfs poll) ──▶ VehicleState flow
 ├── MediaRepository ── MediaSessionManager (+NotificationListener)
 ├── CameraRegistry ── RtspView (LibVLC) / ThermalView (UVC)
 ├── SdrRepository ── RtlTcpClient → WbfmDemodulator → AudioTrack
 │                              └──▶ Fft → spectrum flow → waterfall
 └── VehicleService (foreground, START_STICKY, boot receiver)
        └── ReverseOverlayService (TYPE_APPLICATION_OVERLAY rear cam)
```

Everything is a `StateFlow`; widgets are dumb collectors, so any widget can
dock in any pane and panes can be added (3-pane, saved layouts) without
touching the data layer.
