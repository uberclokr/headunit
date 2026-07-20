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
- **Door ajar (NOT reverse)**: CAN ID **`0x60D`** byte0 **bit3 (0x08)** is
  the **driver-door switch** — field-confirmed 2026-07-19 when the "reverse"
  overlay fired on door-open, key on, gear in P. The 2026-07-16 capture that
  pinned bit3 to reverse was the door still ajar from getting in. Other
  byte0 bits are unmapped (remaining doors and brake are the candidates);
  map them by opening one door at a time watching `logcat -s Helm | grep
  60D`, then widen `CanRepository.DOOR_BITS`. **Reverse gear is currently
  unmapped** — see OPEN ITEMS. Capture gotcha that still applies: ELM
  monitor output obeys the headers setting — capture with `ATH1` or frames
  print with no ID.
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

1. **Reverse detection — signal unknown.** The old 0x60D bit3 source was
   the driver-door switch (see hardware facts), so `VehicleState.reverse`
   now has NO live source and the auto rear-cam never fires. Finding the
   real gear signal is a live-vehicle capture task, on the owner's signal:

   - Key on, engine running, parked, doors SHUT (so 0x60D noise doesn't
     pollute the diff). For each of P, R, N, D — hold the gear, run one
     full-bus monitor burst, label it:
     `adb -s 10.255.1.6:5555 shell am broadcast -a com.xterra.helm.ELM_CMD
     --es cmd "ATH1;MON:1500"`
     then save `adb -s 10.255.1.6:5555 logcat -s Helm -d | tail -60`.
   - Diff frames across gears: the reverse indicator is whatever ID/byte
     flips R-only (brake is held throughout, so it cancels out — that was
     the confound last time).
   - If nothing R-only shows on the OBD-visible bus, the gear signal may
     live on the body bus segment the ELM can't see → fall back to the
     GPIO reverse-lamp opto tap (`GpioReverseSensor`, hardware in
     `docs/HARDWARE.md`), which is wiring work but unambiguous.
   - Once mapped, wire it into `Elm327Manager` the way the door bit is
     done, re-enable nothing else — `ReverseOverlayService` already
     collects `VehicleState.reverse`.
2. **Engine-hum noise suppression on the mic** (backburner #2, design
   settled 2026-07-20). Scope decided: **Path B — clean the captured mic
   signal digitally, NOT acoustic cabin ANC** (ANC through the speakers is
   research-grade here: Android audio latency, single-mic quiet zone, and
   speaker LF rolloff all fight it — rejected).

   The unlock is that we have ground-truth RPM at 8 Hz, so engine hum is a
   *known* comb of tones, not a blind estimate: VQ40DE V6 boom is the 3rd
   engine order (3 × RPM/60 ≈ 35 Hz idle → ~100 Hz cruise) plus 6th/9th.

   Shared DSP core (hardware-independent, build + unit-test first, same as
   `Dsp.kt`), in a new `audio/` package:
   - `EngineHumSuppressor` (pure): a PLL locked to the 3rd order, *seeded and
     bounded by* `CanRepository.state.rpm` but tracking the mic's own hum
     between the 8 Hz updates; a narrow-Q adaptive notch comb on orders
     3/6/9; stereo-coherence suppression of the common-mode LF field.
   - `HarmonicProfileStore`: the "learn over time" piece — EMA of per-order
     magnitude bucketed by ~100-RPM bins, persisted in DataStore, driving
     spectral subtraction against the learned template.
   - `MicSource`: `AudioRecord` wrapper, IO dispatcher, infinite reconnect.
   Only tonal hum is targetable this way; broadband road/exhaust roar is
   NOT RPM-locked and is out of scope.

   Sinks, in build order:
   - **Comms / radio TX** first — cleanest (half-duplex PTT, no echo).
   - **Offline ASR** second — MUST be on-device (Vosk / whisper.cpp on the
     RK3588); Android `SpeechRecognizer` defaults to Google *cloud* and
     violates the no-third-party-network rule. Overlaps ROADMAP #16.
   - **Hands-free calls** LAST / maybe descoped — hard: Android's BT HFP
     stack owns the mic path, so interposing our DSP needs below-framework
     (HAL/modem) work on our own image, unlike the ordinary `AudioRecord`
     consumers above.

   Two design forks to resolve at build time: (a) capture source mode —
   `UNPROCESSED`/`VOICE_RECOGNITION` (no OS AGC/NS fighting our notches;
   right for ASR+comms) vs `VOICE_COMMUNICATION` (platform AEC; wanted for
   calls) — one source can't be optimal for both. (b) Any full-duplex sink
   (a call, or comms while cabin speakers play) also needs AEC with playback
   as reference — separate problem, same pipeline. The `AudioRecord` source
   mode must be confirmed on the actual Edge2 image before wiring capture;
   the DSP core needs no hardware until final tuning.
3. **Thermal (UVC) camera** — parked; the `libausbc` JitPack dependency is
   broken and needs re-sourcing before `ThermalView` can build for real.
4. Config still partly inline (some constants) vs. the settings panel —
   ongoing migration, not urgent.
