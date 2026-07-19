# ROADMAP.md — priorities after first flash

## Phase 0 — shakedown (first Claude Code session)
1. `./gradlew assembleDebug` and fix everything until green. Verify library
   versions against upstream (libausbc, libvlc-all, maps-compose, Spotify AAR).
2. Add JUnit tests for the pure logic: `Fft`, `WbfmDemodulator`, Modbus
   CRC/framing, ELM327 response parsing, `MpgIntegrator`.
3. Flash to the Edge2, work through docs/HARDWARE.md checklist, replace
   every placeholder (camera IPs/paths, Renogy MAC, GPIO pin, Spotify ID,
   Maps key).
4. RtspView stall watchdog + reconnect state (known gap, features §5).
5. Persist pane layout + camera/battery config in DataStore; minimal
   settings pane (new widget) instead of recompiling for config changes.

## Phase 1 — recon/EOC core
6. **MAVLink pane.** mavlink-router on the LAN; UDP listener repo parsing
   HEARTBEAT, GLOBAL_POSITION_INT, SYS_STATUS, BATTERY_STATUS; drone
   markers on MapWidget; drone FPV already registered as RTSP source.
   Command side: DO_ORBIT / follow-me fed from head-unit GPS. Guard rails:
   commands require a confirm tap; never auto-arm.
7. ~~ATAK integration~~ — dropped 2026-07-17 (owner: too cumbersome; no
   ATAK capability wanted).
8. ~~NOAA weather radio mode~~ — DONE 2026-07-17: WX mode in the SDR pane
   (NBFM, WX1–7 chips), SAME AFSK + 1050 Hz WAT decode → shell-wide alert
   banner. SAME decode needs on-air validation (weekly NWS test).
9. ~~Starlink status widget~~ — DONE 2026-07-17: NET pane — dish gRPC
   (hand-framed h2c unary GetStatus, no grpc-java), STA/AP/WG/internet
   status. Verified against the live dish.

## Phase 2 — depth
10. SocketCAN JNI + kernel overlay; sniffer diff view; harvest gear/wheel
    speed/SWC IDs; steering-wheel buttons → media transport.
11. ADS-B mode (1090 MHz, Mode S decode) with aircraft overlay on the map —
    relevant for drone deconfliction.
12. Sortie logger: GPX track + CAN snapshot + drone track + events to
    /sdcard/Helm/sorties/, one-tap start/stop, after-action HTML export.
13. Thermal: raw-frame spot temperature (InfiRay lower-field parsing),
    palette selection, picture-in-picture over the forward cam.
14. Charge/discharge history graphs; load-shed relay control via IO hat.

## Phase 3 — polish
15. Hilt migration if repo count keeps growing; saved multi-layouts
    ("drive", "recon", "camp" presets on dock long-press).
16. Voice: on-device Whisper (the DGX can fine-tune a wake word) → pane
    commands ("show thermal left").
17. OTA self-update from a private repo endpoint.
