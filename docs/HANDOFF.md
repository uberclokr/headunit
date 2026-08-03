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
3. **API auth token** (both repos). The head unit's HTTP API gates `/api/*`
   on an `X-Helm-Token` header. The token lives in a **gitignored**
   `secret.properties` at each repo root (`HELM_API_TOKEN=…`), compiled into
   `BuildConfig.API_TOKEN`. On a fresh clone, create `secret.properties` in
   **both** `headunit` and `helm-companion` with the **same** value, or the
   companion gets 401s. Absent file → head unit logs a warning and leaves
   `/api/*` open (the landing page + `/companion.apk` are always open so a
   fresh phone can still fetch the app). Regenerate: `openssl rand -hex 24`.
4. **Native deps are vendored** under `app/src/main/cpp/third_party/`
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
- **LoRaWAN**: roof **MikroTik wAP LR8G** gateway forwards raw packets via the
  **Semtech UDP legacy packet forwarder** (RouterOS `IoT>Lora` → Network
  Server = the head unit's LAN IP, port **1700** up+down). The head unit IS
  the network server (`lora/` package, in-app LNS — no cloud). Point the
  gateway at the head unit's `192.168.1.x` STA address. **SenseCAP T1000-A**
  trackers: position packets `0x06`/`0x09`, big-endian, lon@9..12 lat@13..16
  as int32÷1e6, battery last byte. Bind cards in the LoRa pane (OTAA or ABP).
- **Viofo A329S dashcam**: joins the head unit's own 5 GHz SoftAP
  **`helmnet`** (no hyphen) / pass `<redacted — set in Settings>`, lands at
  **192.168.133.208**. A `/32` VPN-bypass route is pinned so the head unit
  reaches it off-tunnel. Clip index API: `GET /?custom=1&cmd=3015`;
  files at `http://<ip>/DCIM/Movie/<name>`; channel switch `cmd=3028&par=N`
  (0=front 1=interior 2=rear).
- **Renogy house battery**: BT-2 BLE module at **`<your-renogy-mac>`** on
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

1. **Reverse detection — NOT on the OBD bus (P/R sweep done 2026-07-21).**
   `VehicleState.reverse` still has no live source. A full live capture
   session (engine idling, brake held, doors shut, P↔R) swept **all eight
   11-bit ID ranges 0x000–0x7FF** via the ELM `ATMA` monitor with
   `ATCM/ATCF` range filters (`am broadcast -a com.xterra.helm.ELM_CMD --es
   cmd 'ATH1;ATCM700;ATCF<base>;MON:2500'`, split ELM output on `\r`).
   **No clean gear-position bit exists on the OBD-visible bus** — every
   Park↔Reverse difference was a rolling counter (0x2DE byte2, 0x284/285,
   0x29E tail, 0x23D), a drifting analog sensor (0x2A0 wanders C8–CA), or a
   load-related 16-bit value that shifts slightly in-gear (0x233 ≈0x7D88→
   0x7EA2, same effect in D/N-under-load). Static frames (0x5C5, 0x60D,
   0x625, 0x2A5) are byte-identical in both gears. The ELM also drops frames
   (`BUFFER FULL`) on this 500 kbit bus.

   **Root cause confirmed 2026-07-21: the TCM is behind the gateway and not
   on the OBD-II bus.** A functional `0100` enumerates only `0x7E8` (ECM) —
   no other module answers. Physical/functional requests to the TCM (0x7E1)
   — mode 03, sessions (1003/1092/1081), tester-present, 22F190 — all return
   `NO DATA`. So the gear-on-`0x7E9` mode-22 read is real but needs a tool
   that routes through the gateway (CONSULT-III); a plain ELM327 on the OBD
   port cannot reach 0x7E1/0x7E9, and the TCM's gear broadcasts never appear
   on the OBD-visible bus. OBD-via-ELM for gear is therefore a dead end.
   Two paths remain:
   - **Reverse-lamp GPIO opto tap (recommended)** — unambiguous (lamp on iff
     reverse), code already exists (`GpioReverseSensor` + `docs/HARDWARE.md`);
     wire the PC817 opto to a hat GPIO and re-enable the watcher.
   - **Raw SocketCAN sniffer** (roadmap #10) — line-rate, no buffer drops,
     can watch a byte flip at the instant of the shift; needs the JNI +
     kernel CAN built first (currently a stub).
   Once a source exists, feed `VehicleState.reverse`; `ReverseOverlayService`
   already collects it.
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
3. **Viofo cabin feed as a system camera (webcam)** — backburner #3, scoped
   2026-07-20. Goal: expose the cabin channel as a camera that arbitrary
   video-comms apps enumerate (the "integrated / USB camera" they look for).
   Not an app feature — it's HAL/kernel integration, viable here only because
   we own the image + root + rebuild kernels:
   - **Path (recommended): `v4l2loopback` + the AOSP External Camera HAL.**
     Android already turns USB UVC webcams into `LENS_FACING_EXTERNAL`
     cameras; feed that same path. Build/load `v4l2loopback` → a userspace
     pump (ffmpeg/GStreamer, Rockchip `rkmpp` HW H.264 decode) writes the
     Viofo RTSP into `/dev/videoN` → External HAL enumerates it for all apps.
     Verify the AOSP external provider is present/enabled on the image and
     add a SELinux rule for the HAL to open the loopback node.
   - Alt: Android-14 VirtualCamera API — sanctioned but `CREATE_VIRTUAL_DEVICE`
     is signature/role-gated (we can meet it) and primary-display visibility
     to normally-launched apps is the risk; prefer the loopback route.
   - **Audio is separate.** Cameras carry no audio in Android; expose it as a
     distinct audio-input device (ALSA loopback + audio HAL) — or, cleaner,
     pair the virtual cam with the head unit's own mic (ties to open item #2).
     Owner's call 2026-07-20: **split the Viofo audio into its own audio
     stream device and add a manual A/V-sync slider** for when latency can't
     be auto-determined.
   - Constraints: A329S serves ONE live channel at a time (contends with the
     CAM pane / reverse cam) — but overriding the CAM widget during an active
     call is acceptable (owner: no reverse cam needed mid-call); dashcam
     encode + Wi-Fi + decode latency is "webcam-fine," not low-latency.
4. **LoRaWAN tracking — RF shakedown** (built 2026-07-20, needs live gear).
   In-app LNS (`lora/`) is verified in software: AES-CMAC/MIC/decrypt and the
   SenseCAP decode are unit-tested, and on-device the Semtech UDP server binds
   :1700 when enabled. Untested end-to-end because it needs the gateway + T1000
   cards:
   - Point the wAP LR8G's RouterOS Network Server at the head unit's LAN IP
     (`IoT>Lora`, Semtech UDP legacy, :1700). Enable the LNS in the LoRa pane.
   - **ABP is the low-risk path**: provision a card ABP (SenseCAP app → DevAddr
     + NwkSKey + AppSKey), enter the same in the bind form → uplinks decode
     with no downlink. Verify a node appears in the list with a fix + on nav.
   - **OTAA join is the unvalidated bit**: the JoinAccept *crypto* is tested,
     but the downlink RX-window **timing** (`SemtechForwarder.sendDownlink`,
     currently RX2 tmst+6 s per region) is not — a T1000 that won't join is
     almost certainly this. Watch `logcat -s Helm | grep -i lora`; tune the
     window/region if joins fail, or bind that card ABP as a fallback.
5. **Thermal (UVC) camera** — parked; the `libausbc` JitPack dependency is
   broken and needs re-sourcing before `ThermalView` can build for real.
6. Config still partly inline (some constants) vs. the settings panel —
   ongoing migration, not urgent.
