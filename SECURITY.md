# Security & Privacy Review — Helm

Point-in-time audit of the head unit (`headunit`) and phone companion
(`helm-companion`), read-only, covering network exposure, auth, secrets,
Android permissions/exports, root usage, and data egress.

**Threat model.** Single-owner vehicle, deeply technical owner, deliberately
quiet traffic profile. Realistic adversaries: (a) a device in RF range that
joins the vehicle's WiFi AP, (b) a peer on the WireGuard VPN, (c) a malicious app
co-resident on the head unit, (d) — relevant now — **anyone reading a public
source mirror**. The head unit *is* the WiFi AP and hosts control services on it,
so "on the AP" is a real attacker position, not just the trusted owner.

## Verdict

- ✅ **Quiet-traffic rule holds.** No analytics / crash / telemetry SDKs in
  either app (no Firebase / Crashlytics / GA / Sentry / Segment / etc.). No
  hardcoded API keys, passwords, or tokens in *tracked* source; `secret.properties`
  is gitignored and untracked in both repos.
- ⚠️ **Open items exist** around unauthenticated LAN services, a status API that
  can fail open, exported debug broadcasts that reach the vehicle bus, and
  weak-by-default / in-source credentials that matter for a public mirror.

## Findings (ranked)

| # | Sev | Finding | Where | Status |
|---|-----|---------|-------|--------|
| 1 | HIGH | Exported debug broadcasts → arbitrary CAN/ECU injection (`ELM_CMD`), nav hijack, metered-data burn | `system/VehicleService.kt:164` | OPEN |
| 2 | HIGH | RTSP camera relay unauthenticated, bound to all interfaces | `system/RtspRelay.kt` | OPEN |
| 3 | HIGH | Static, in-repo AP credentials (`helmnet` / `helmrecon`) | `system/SettingsRepository.kt`, `HotspotManager.kt` | OPEN |
| 4 | MED | Status API binds `0.0.0.0`, non-constant-time token, **fails open** when token unset (exposes precise GPS) | `system/ApiServer.kt:36` | OPEN |
| 5 | MED | Hardcoded private hardware IDs (router BSSID, Renogy MAC) in source | `SettingsRepository.kt`, `HotspotManager.kt` | OPEN |
| 6 | LOW | `local.properties` tracked in the head-unit repo | repo root | **FIXED** |
| 7 | LOW | Latent root command injection via Hotspot settings (owner-only input today) | `system/HotspotManager.kt:60` | OPEN |
| 8 | LOW | `allowBackup="true"` on the companion | `helm-companion` manifest | OPEN |
| 9 | INFO | Precise GPS / BLE MACs logged to logcat (local only) | `VehicleService.kt`, `RenogyBleClient.kt` | OPEN |
| 10 | INFO | Dead Google leftovers: unused `play-services-location` dep + stale `geo.API_KEY` meta-data | `app/build.gradle.kts`, manifest | OPEN |

### 1 — Exported debug receivers (HIGH)
`VehicleService.kt:164-172` registers nine receivers `RECEIVER_EXPORTED` with no
permission, so **any installed app** (not just `adb`) can fire them:
- `com.xterra.helm.ELM_CMD` → `can.injectElm(str)` pushes an attacker-controlled
  string straight onto the OBD/ELM327 link (arbitrary CAN headers + TX frames on
  a moving vehicle's bus). **Highest-impact item.**
- `NAV_GO` silently redirects turn-by-turn; `NAV_CACHE` triggers a 300k-tile
  download (metered-Starlink DoS); `NAV_SEARCH` fires an attacker string to Photon.
- **Fix:** `RECEIVER_NOT_EXPORTED` and/or gate the block behind `BuildConfig.DEBUG`
  (adb still reaches non-exported receivers on debuggable builds). At minimum,
  `ELM_CMD` must not be cross-app reachable in release.

### 2 — Unauthenticated RTSP relay (HIGH)
`RtspRelay.kt` binds `:8554` on `0.0.0.0` and pumps any TCP client straight
through to the camera — no auth. Anyone on the AP (see #3) or the VPN can watch
front/cabin/rear cameras. It only ever dials the fixed camera IP, so it's **not**
an open proxy/SSRF — exposure is strictly the camera stream.
- **Fix:** bind to the WG/tun interface only, or gate on the `X-Helm-Token`, or
  firewall `:8554` to the WG subnet.

### 3 — Static in-repo AP credentials (HIGH)
Default SoftAP `ssid = "helmnet"`, `pass = "helmrecon"` — a single guessable WPA2
password committed to source and shared with the dashcam. Because the head unit
is the AP and co-hosts the unauthenticated relay (#2) and the token-gated API (#4)
on `0.0.0.0`, whoever has this password lands on the same L2 as all of it.
- **Fix:** provision a high-entropy AP password per vehicle (already persisted in
  `SettingsRepository`); don't ship a shared default; treat the AP L2 as untrusted.

### 4 — Status API hardening (MEDIUM)
`ApiServer.kt:39` binds `:8080` on `0.0.0.0` (reachable from the AP, not just WG).
Auth is a shared bearer (`BuildConfig.API_TOKEN`) — verified present in this
build, so `/api/*` is authenticated today — but: the compare (`:66`) is **not
constant-time**, and if the token is empty the whole `/api/*` surface (which
includes precise lat/lon + full vehicle state) is **served unauthenticated** with
only a `Log.w`. `/companion.apk` and the landing page are intentionally open.
- **Fix:** fail *closed* when no token is set; `MessageDigest.isEqual` for the
  compare; bind to the tun interface.

### 5 — Hardcoded hardware IDs (MEDIUM)
Router BSSID `7a:f8:…` and Renogy BLE MAC `6C:B2:…` are in source (and duplicated
across files). Minor location/hardware fingerprinting; **matters for a public
mirror.** Move to `secret.properties` / on-device settings.

### 6 — `local.properties` tracked (LOW) — FIXED
It was committed in the head-unit repo (companion correctly ignores it). It only
held `sdk.dir` + an empty `MAPS_API_KEY`, so nothing leaked yet — but it's the
file meant to hold the real Maps key. Untracked and gitignored in this change.

### 7 — Latent root injection (LOW)
`HotspotManager.kt:60/81` interpolates `ssid`/`pass`/`staBssid` **unquoted** into
`su 0 sh -c "…"` (`RootShell.kt:16`). Owner-only input today (Settings pane, never
the API), so not remotely exploitable, but a `;`/`$()` in those fields runs as
root. **Fix:** validate/quote before `RootShell.run`, or pass argv without `sh -c`.

### 9 / 10 — Cleanup
GPS (4-dp) and BLE MACs reach `logcat` only (local). The `play-services-location`
dependency is declared but never referenced, and a `com.google.android.geo.API_KEY`
meta-data lingers from before the MapLibre migration — both dormant (no egress),
remove to keep the surface honest. Also `VehicleService.kt:183-187` calls
`ApiServer.start()` / `RtspRelay.start()` twice (idempotent, harmless).

## Privacy / data egress

The only third-party outbound traffic is three **functional, keyless** services;
none are analytics/telemetry:

- **Photon geocoder** (`photon.komoot.io`) — `nav/route/Geocoder.kt`. On an explicit
  address/business search, sends the **query string + precise GPS fix** to a public
  third-party instance. Fires only on manual search (or the `NAV_SEARCH` self-test),
  fails soft, routing itself stays offline. The single most sensitive datum that
  leaves the vehicle; acceptable and documented, but be aware of it.
- **USGS tiles** (`basemap.nationalmap.gov`) + **AWS terrarium DEM** (`s3.amazonaws.com`)
  — map/elevation tiles; `{z}/{y}/{x}` reveals the viewport (≈ vehicle position) to
  a US-gov/AWS server. Standard for any online basemap. Note: the **companion**
  fetches USGS tiles over the *phone's own* link, so it discloses the vehicle
  position regardless of how quiet the vehicle keeps its Starlink.

Everything else is first-party/local: Starlink dish (LAN gRPC), ApiServer/RtspRelay
(WG), StyleServer (loopback), PoiSync (owner's Unraid over WG), companion API (WG).
`GpsSystemBridge` mock-provider and `HelmNotificationListener` stay on-device.
The companion requests **no location permission** and renders position from the API.

## Verified fine (do not re-investigate)

- Secrets are **not** in git (both repos). No WebView / `loadUrl` /
  `addJavascriptInterface` / `DexClassLoader` / dynamic code anywhere.
- Other `RootShell` call sites (`VncManager`, `ViofoLocator`, `NetRepository`,
  `SystemBatteryBridge`) use constants or regex-validated IP/MAC/id shapes — not
  injectable. `HomeLinkRepository` shells `ping` via argv, not `sh -c`.
- `ApiServer` `/companion.apk` uses a fixed path — no path traversal.
  `/api/cam/channel` range-checks `ch` to `0..2`.
- `StyleServer` binds `127.0.0.1` only. `StarlinkClient` is outbound-only to the
  fixed dish. `BootReceiver`/`MainActivity` exports are required by design;
  `HelmNotificationListener` is system-permission-guarded.

## Before mirroring to a public repo

1. Decide **public vs private** — if private, most of the below is optional.
2. If public: rotate/redact #3 (AP password) and #5 (BSSID/Renogy MAC) out of
   source; they are real, in-use values.
3. Review `docs/img/*.png` — the screenshots show the vehicle's **real GPS
   location** (Newport, OR area), the `helmnet` SSID, and the NAS IP. Redact or
   re-shoot from a neutral location if that matters to you.
4. Consider closing #1 (`ELM_CMD` export), #2 (relay auth), and #4 (API fail-open)
   before the code is public — they read as inviting once anyone can see them.
