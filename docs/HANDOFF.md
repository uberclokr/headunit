# HANDOFF.md — picking this up on a new machine

Read this after `CLAUDE.md`. It captures the operational facts that live
outside the code — the things a fresh clone (and a fresh Claude session)
won't otherwise know. The auto-memory used in prior sessions is stored under
`~/.claude/…` and does **not** travel with git; this file is the durable
substitute.

## First-run on a new workstation

1. **`local.properties` is committed but machine-specific.** Fix `sdk.dir`
   to the new machine's Android SDK path, and set `MAPS_API_KEY=` to a real
   key (blank = the MapLibre nav pane still works; only stray Maps-SDK usage
   needs it).
2. **Toolchain**: Android SDK 34, **NDK `26.1.10909125`** (the native SDR
   build pins it), CMake 3.22+. The Spotify App Remote AAR is committed at
   `app/libs/` — no re-download needed.
3. **Native deps are vendored** under `app/src/main/cpp/third_party/`
   (libusb + rtl-sdr, with an Android fd-wrap patch in `librtlsdr.c`). They
   compile as part of the Gradle build; don't fetch upstream.
4. **Build**: `./gradlew :app:assembleDebug` (arm64-only). First build on a
   clean box pulls a lot — expect a few minutes.

## Reaching the vehicle

- The head unit is at **`10.255.1.6`** — its **WireGuard** address. The new
  workstation must be **on the WG VPN** to reach it. Then
  `adb connect 10.255.1.6:5555`.
- Deploy loop: `adb -s 10.255.1.6:5555 install -r
  app/build/outputs/apk/debug/app-debug.apk` then
  `am start -n com.xterra.helm/.MainActivity`.
- Logs: `adb -s 10.255.1.6:5555 logcat -s Helm`.
- Root on-device is `su 0 sh -c '…'` (AOSP syntax, no `-c` flag needed for
  the shell), wrapped by `RootShell.kt`. adb-over-VPN drops in waves when the
  truck drives (Starlink under tree cover) — retry, don't panic.

## Hardware facts (verified on this vehicle)

- **OBD dongle**: vLinker FS (FTDI FT231X), 115200 baud. Key-off →
  `UNABLE TO CONNECT` is normal (ECU asleep).
- **Reverse gear**: from CAN ID **`0x60D`**, data **byte0 bit3 (0x08)**.
  The ELM monitor capture must run with headers on (`ATH1`) or frames print
  with no ID — this was the long-standing reverse-cam bug (see OPEN ITEMS).
- **Viofo A329S dashcam**: joins the head unit's own 5 GHz SoftAP
  **`helmnet`** (no hyphen) / pass `helmrecon`, lands at
  **192.168.133.208**. A `/32` VPN-bypass route is pinned so the head unit
  reaches it off-tunnel. Clip index API: `GET /?custom=1&cmd=3015`;
  files at `http://<ip>/DCIM/Movie/<name>`; channel switch `cmd=3028&par=N`
  (0=front 1=interior 2=rear).
- **Renogy house battery**: BT-2 BLE module at **`6C:B2:FD:85:C5:0F`** on
  the smart-battery UP port; register map at 0x0100 range, real coulomb SOC.
- **Sensors**: the Edge2 has an **accelerometer only** — no gyro, no
  magnetometer. Inclinometer + nav heading both derive from that + GPS
  course accordingly.
- **WiFi**: AP6275P does STRICT same-channel STA+AP concurrency (can't split
  bands). Boots into world regulatory domain "00" which blocks 5 GHz
  beaconing — `cmd wifi force-country-code enabled US` unlocks it
  (`HotspotManager.ensureCountry()` re-applies on boot).

## Companion app (separate repo)

The phone companion is its own project → Gitea **`phill/helm-companion`**
(clone separately). It's a thin read view over the head unit's API; shares
`Theme.kt` (copied). Head unit serves:
- `ApiServer` **:8080** — `/api/status` JSON, `/companion.apk`, landing page.
- `RtspRelay` **:8554** — TCP proxy so a VPN phone can watch the camera.
- Settings pane shows a QR + links pointing at the WG address.
To refresh the hosted APK: build the companion, push to
`/data/data/com.xterra.helm/files/companion.apk` (chmod 644).

## Git / Gitea

Both repos live on Gitea at **`192.168.1.36:3000`** (reachable over the VPN):
`phill/headunit` and `phill/helm-companion`, both private. Credentials are
provided per-push by the owner (username `phill`) — not stored. Commit
style: terse subject + feature-grouped body + Claude trailers.

## OPEN ITEMS

1. **Reverse-cam auto-switch** — the `ATH1` capture fix is deployed but
   **unconfirmed**; needs a key-on shift into Reverse to verify the overlay
   fires. Every `0x60D` byte0 transition is logged (`logcat -s Helm | grep
   60D`) to nail the exact bit map on the next drive. This is the one task
   the owner wants to tackle live, on their signal.
2. **Thermal (UVC) camera** — parked; the `libausbc` JitPack dependency is
   broken and needs re-sourcing before `ThermalView` can build for real.
3. Config still partly inline (some constants) vs. the settings panel —
   ongoing migration, not urgent.
