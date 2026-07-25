# FEATURES.md — specification & status

Format per feature: **Goal → Behavior → Implementation → Status → Acceptance
→ Open work.** "Verified" means tested on the truck; nothing is verified yet
(codebase not compiled — see CLAUDE.md).

---

## 1. Pane / dock UI

**Goal.** Two informational or control surfaces visible at once (Maps +
Spotify, Maps + gauges, drone + thermal), rearrangeable in two taps while
driving.

**Behavior.** Status strip (clock, link dots, house SOC, ECT, speed,
REVERSE flag) → two panes with a draggable divider (28–72% clamp) → dock
bar with widget chips, swap (⇄), and SPLIT/SINGLE toggle. Pane header tap
opens a widget picker grid. Any widget in any slot.

**Implementation.** `ui/PaneManager.kt` (state), `ui/DashboardShell.kt`
(layout), `widgets/WidgetContent.kt` (router).

**Status.** Complete, uncompiled.

**Acceptance.** Divider drag is smooth at 60 fps with a live RTSP pane on
one side; widget switch < 300 ms; no state loss in the surviving pane when
toggling SPLIT/SINGLE.

**Open.** Persist layout in DataStore; optional third mini-pane row for
1200 px-wide screens; long-press dock chip = send to left pane explicitly.

---

## 2. CAN / OBD-II integration

**Goal.** Live powertrain truth: RPM, speed, coolant/intake temp, throttle,
MAF, fuel level, module voltage, instant + average MPG derived from MAF.

**Behavior.** Auto-connects to any USB ELM327 (CH340/FTDI/CP2102/PL2303
handled by usb-serial-for-android); polls fast PIDs at ~8 Hz, slow PIDs
every 10th cycle; reconnects forever on failure. Data feeds the VEHICLE
widget and the status strip.

**Implementation.** `can/Elm327Manager.kt` (protocol), `can/CanRepository.kt`
(state + `MpgIntegrator`). 2008 Xterra = ISO 15765-4 CAN 11-bit/500k; ATSP0
auto-detect is used anyway.

**Status.** Complete, uncompiled.

**Acceptance.** RPM pane latency subjectively < 250 ms vs tach; MPG within
~10% of hand-calculated tank average; unplugging/replugging the dongle
recovers within 5 s without app restart.

**Open.** DTC read/clear screen (mode 03/04); PID support probing (0100
bitmask) instead of blind polling; genuine OBDLink handles higher poll
rates — make cycle delay adaptive.

---

## 3. Raw CAN (SocketCAN, IO hat)

**Goal.** Broadcast-frame access (gear, wheel speeds, steering angle) at
full bus rate for reverse-engineering the D40 body/powertrain bus.

**Implementation plan.** MCP2515 or RK3588 CAN pins + transceiver → kernel
DT overlay → `can0` at 500k → 30-line JNI (`docs/SOCKETCAN.md` has the
complete C source) → `SnifferWidget` shows frames grouped by arbitration ID
with per-byte change highlighting.

**Status.** Stub + full guide. JNI not built; kernel config on the Khadas
image unverified.

**Acceptance.** `candump can0` shows traffic; SnifferWidget streams ≥ 500
frames/s without jank; a gear-position ID is identified and wired into
`VehicleState.reverse` as the fast source.

---

## 4. Reverse detection + auto backup camera

**Goal.** Rear camera fills the screen the moment R engages, over anything,
even if Helm's UI isn't foreground; disappears when out of gear.

**Behavior.** `GpioReverseSensor` polls a sysfs GPIO at 150 ms (reverse-lamp
12 V → PC817 opto → hat GPIO; wiring in README). `ReverseOverlayService`
(foreground, SYSTEM_ALERT_WINDOW) collects `VehicleState.reverse` and
adds/removes a full-screen LibVLC surface on the rear RTSP stream at 150 ms
network-caching.

**Status.** Complete, uncompiled. GPIO pin number is a placeholder
(`gpio113`) — set per actual hat pinout.

**Acceptance.** R-engage → image on glass < 1.0 s (stream startup
dominates); overlay never wedges (leaving R always removes it, including
if the stream died); survives Helm UI being killed.

**Open.** Keep the rear stream warm (paused pipeline) to cut startup to
~300 ms; optional guide-line overlay (static SVG over the video); CVBS/USB
capture fallback source for sub-100 ms latency (registry already
source-agnostic).

---

## 5. Camera panes (Viofo A329 ×3, drone, thermal)

**Goal.** On-demand forward / cabin / rear / drone / thermal views as
dockable panes.

**Behavior/impl.** `CameraRegistry` holds RTSP URLs (static leases assumed;
paths `/live`, `/live2`, `/live3` must be verified with ffprobe against
A329 firmware). `RtspView` = LibVLC, TCP, hw decode, 600 ms caching for
non-critical panes, 150 ms for rear/drone. Thermal = UVC via libausbc;
`ThermalView` is a documented placeholder (`docs/THERMAL.md` has the
fragment). Thermal is **nested inside the CAM pane** as a THERMAL chip
alongside REAR/FRONT/CABIN (it's a separate UVC source, so a mode toggle,
not a Viofo channel switch) — there is no standalone thermal dock button.

**Status.** RTSP complete/uncompiled; thermal needs the ~20-line fragment
and VID/PID filter.

**Acceptance.** Two simultaneous RTSP panes decode at full rate on RK3588;
stream loss shows a reconnect state rather than a frozen frame (currently
freezes — Open); thermal renders and survives USB re-enumeration.

**Open.** Reconnect-on-stall watchdog in RtspView; snapshot button (frame
grab to /sdcard/Helm/); 2×2 quad view widget; InfiRay raw-frame spot-temp
readout.

---

## 6. Audio (universal transport + Spotify rich control)

**Goal.** Control whatever is playing — Spotify, YT Music, Audible,
podcasts — from one pane with album art, without reimplementing players.

**Behavior/impl.** `MediaRepository` enumerates every active MediaSession
(requires one-time Notification Access grant); pane shows source chips,
art-backed now-playing, prev/play-pause/next. `SpotifyRemote` layers the
App Remote SDK for playlist/URI playback (needs CLIENT_ID + AAR).
Tapping a source with no session launches the app.

**Status.** Universal transport complete/uncompiled. Spotify needs
developer registration.

**Acceptance.** Chips appear within 2 s of a player starting; transport
controls affect the right app when several are active; art updates on
track change without polling jank.

**Open.** Volume routing panel (per-stream AudioManager); Spotify
playlist browser grid; steering-wheel-button → transport mapping once raw
CAN exposes SWC frames.

---

## 7. SDR (RTL-SDR: broadcast + voice-comms bands)

**Goal.** Tune broadcast FM/AM and the common US voice-comms services with a
live spectrum + waterfall; keep the pipeline generic enough to add decoders
(ADS-B, pagers) later.

**Behavior/impl.** `RtlUsbSource` (in-app librtlsdr over the head unit's own
USB) or `RtlTcpClient` (any LAN dongle) → 16 k IQ chunks → per-band
demodulator + `Fft.powerDb` (1024-pt Hann) → trace + 90-row ember waterfall.
Bands (`SdrBands`): **FM** (WBFM, scan + auto-tune), **AM** broadcast (MW via
RTL2832 direct sampling — below the R820T's 24 MHz floor), **AIR** (118–137
AM), **WX** (NOAA NBFM + SAME/WAT decode), **FRS/GMRS/MURS/MARINE** (NBFM
channel plans), **CB** (27 MHz AM, ch1–40 with 9·EMG/19·HWY tagged), **2M/70CM**
ham (NBFM). Three demods: `WbfmDemodulator`, `NbfmDemodulator`,
`AmDemodulator` (envelope). Fixed-plan bands show a scrollable channel list;
broadcast/aviation/ham tune continuously (band-scaled steps). Direct sampling
is set automatically from the tuned frequency (`IqSource.setDirectSampling`,
wired through the JNI to `rtlsdr_set_direct_sampling`).

**Status.** Verified on the Edge2 with a live dongle: FRS ch1 (462.56), CB
ch19 (27.185), band switching, channel lists, and the AM/direct-sampling path
all receive without crashing.

**Squelch.** Carrier-power gate (`SdrRepository`): RSSI computed every chunk,
audio muted below an adjustable threshold (default −20 dB) with 4 dB hysteresis
so marginal signals don't chatter; SAME still decodes pre-gate. UI shows the
threshold (±2 dB) and a live SIGNAL/muted dot. Verified on-device on FRS.

**Tone decode.** `CtcssDetector` — Goertzel bank at the 50 EIA CTCSS tones over
~0.2 s windows, dominant-bin detection; the decoded PL tone (e.g. "PL 118.8")
shows in the squelch row. DCS (134.4-baud digital) is not decoded. Verified
live on FRS.

**Scan.** `SdrRepository.scan()` — FM does a full 88–108 sweep dropping station
chips; channel bands hunt their plan and linger on any above-squelch channel
(retunes + reads the run loop's RSSI — no second IQ reader). SCAN toggle.

**Antenna/TX info.** ⓘ button at the waterfall's bottom-right pops a per-band
panel: ideal antenna type + length (from `SdrBand.antenna`), a live λ/4·λ/2 for
the tuned frequency, the tone-decode note, and TX status. Each band carries TX
legality/licensing (`SdrBand.tx`) and there's a disabled 🎙 PTT scaffold in the
header — **RX-only today**; flip `TX_RADIO` and wire PTT + a mic path when a
transmit-capable SDR replaces the RTL dongle.

**Open.** Stereo pilot + RDS; presets in DataStore; DCS decode; frequency
drag-to-tune; TX chain (PTT keying, CTCSS encode, repeater offset/tone) once TX
hardware lands.

---

## 8. House battery (Renogy BLE) + system battery mirroring

**Goal.** Pack SOC/V/A/temps/cells in a pane and in the status strip — and
Android's own battery indicator reports the house pack.

**Behavior/impl.** `RenogyBleClient`: Modbus-RTU over GATT (FFD1 write /
FFF1 notify, CRC16, fragment reassembly). `BatteryRepository`: 5 s poll,
two register maps (`SMART_BATTERY` 5000-range: SOC, signed A, remaining/
total Ah, cells, temp, runtime estimate; `BT2_CONTROLLER` 0x0100-range).
`SystemBatteryBridge`: root `dumpsys battery unplug/set ac/set status/set
level` so BatteryService, the status-bar icon, and all apps see the pack;
re-pushed only on change; `reset` restores stock.

**Status.** Complete, uncompiled. MAC/kind/deviceId are placeholders.
Standalone 500 A shunt monitor registers are NOT documented — needs one
nRF Connect capture if that's the installed unit.

**Acceptance.** SOC in strip within 15 s of boot; survives BLE dropouts
(8 s reconnect); Android quick-settings % matches pane %; charge bolt
tracks `charging`; low-SOC Battery Saver behavior decided (keep or
`low_power_trigger_level 0`).

**Open.** Charge/discharge history sparkline (Room or ring buffer);
low-SOC alert banner + optional relay shed via IO hat; multi-battery hub
support (iterate deviceId 48..).

---

## 9. External apps side-by-side (freeform)

**Goal.** Real Google Maps / OsmAnd / ATAK running beside Helm panes.

**Impl.** `system/AppLauncher.launchInBounds` — freeform windowing via
ActivityOptions launch bounds + reflected `setLaunchWindowingMode(5)`.
Device needs `enable_freeform_support` + `force_resizable_activities`.

**Status.** Complete, uncompiled. Hidden-API reflection is
image-dependent; degrade path (bounds only) in place.

**Open.** "Pin external app as pane" — a pane type that reserves its rect
and re-launches the app into it on layout changes.

---

## 10. Always-on / boot behavior

**Goal.** All links live from power-on without touching the screen; UI is
HOME so any reboot lands in Helm.

**Impl.** `BootReceiver` → `VehicleService` (foreground, START_STICKY)
starts CAN, media, battery, and spawns `ReverseOverlayService`. Kiosk
window flags + KEEP_SCREEN_ON in MainActivity.

**Acceptance.** Cold boot → reverse detection armed before the launcher is
even visible; 72 h soak with zero service deaths (watch `dumpsys activity
services`).

---

## 11. Offline nav map + turn-by-turn

**Goal.** A self-contained nav surface for a mobile, often off-grid vehicle:
topo/imagery map, waypoints, and full turn-by-turn — all without internet.

**Impl.** MapLibre Native map (`nav/NavMap.kt`): USGS topo/imagery over 3D
terrain, GPS puck from the USB receiver, follow-behind chase cam, waypoints
(GeoJSON, backed up to the NAS). Routing is embedded GraphHopper 7.0
(`nav/route/`): a pre-built Pacific-NW graph (OR+WA+ID, ~453 MB) loaded
memory-mapped so heap stays flat; CH prepared at import. Pure guidance math
(`Navigator.kt`, unit-tested) snaps the fix to the route each GPS update for
distance-to-turn, ETA, off-route and auto-reroute. UI (`NavGuidanceUi.kt`):
cyan route line, top-center turn banner (glyph + distance + street + trip
strip), tap-a-waypoint-to-navigate. Offline voice via on-device TTS
(`NavVoice.kt`).

**Search** (`nav/route/Geocoder.kt`): address/business lookup via Photon
(OSM, keyless — the one online call in the nav path; routing stays offline).
Results are re-ranked client-side by true distance from the fix so the
nearest match leads, shown with distances; picking one routes to it offline.

**Cache management** lives in the Settings pane ("OFFLINE MAPS & NAV"):
map-tile regions (per-region delete, total), the auto "viewed-area" ambient
cache (clear), and the routing graph (size, loaded state, delete). The nav
pane keeps a single **CACHE THIS VIEW** capture button (it needs the framed
viewport). Full build/provision runbook and the two Android/ART gotchas
(mmap vs OOM, the `SourceVersion` shim) in [`NAV_OFFLINE.md`](NAV_OFFLINE.md).

**Status.** Engine + guidance + search verified on the Edge2 (search
"Starbucks" → nearest at 0.1 mi → 0.4 mi offline route). UI wired to
`NavRepository` StateFlow.

**Open.** Traveled-vs-remaining route styling (split the line at the snap
point); lane/exit hints; settings-screen home/work destination presets.
