# PROPOSALS.md — OBD-derived capabilities

Forward-looking capability proposals for the Helm head unit, grounded in the
OBD/CAN values the rig already exposes plus standard OBD-II conventions, and
aimed squarely at the mission profile: **recon, search & rescue, and drone
operations in remote, off-grid terrain.**

These are proposals, not commitments — captured here so they can be scoped,
prioritized, and pulled into `ROADMAP.md` when we build them. Nothing here is
implemented yet.

## What the rig polls today (the baseline)

`Elm327Manager` runs an 8 Hz PID rotation (fast PIDs every cycle, slow ones
every Nth):

| PID | Value | Field |
|---|---|---|
| `010C` | Engine RPM | `rpm` |
| `010D` | Vehicle speed | `speedKmh` |
| `0105` | Coolant temp | `coolantC` |
| `010F` | Intake air temp | `intakeC` |
| `012F` | Fuel level % | `fuelPct` |
| `0110` | MAF → MPG | `mafGs`, `instantMpg`, `avgMpg` |
| `0111` | Throttle position | `throttlePct` |
| `0101` | Monitor status | `milOn`, `dtcCount` |
| `0142` | Control-module voltage | `starterV` (starter battery) |
| Mode 03 | Stored DTCs → J2012 | `dtcCodes` |
| `0x60D` | BCM body-bus byte0 | `doorOpen`, `bcmByte0` (reverse-eng) |

Plus non-OBD signals already fused into the same picture: GPS (position,
speed, course, altitude), the accelerometer inclinometer (roll/pitch, felt
lateral/longitudinal G), the Renogy house battery (SOC, V, A, W, temp), and
Starlink health. That fusion is what makes most of the proposals below cheap —
the new value is a derivation over data we already have.

### Step zero — capability scan

Before building anything PID-dependent, run a **supported-PID scan** (`0100`,
`0120`, `0140`, `0160` support bitmaps) to enumerate exactly what this 2008
VQ40DE ECU implements. Expect load (`0104`), baro (`0133`), MAP (`010B`), the
fuel trims, ambient (`0146`), and the distance counters (`0121`/`0131`) to be
present; **oil temp (`015C`) and engine fuel rate (`015E`) are frequently NOT
implemented on a 2008 Nissan** — don't design around them until the scan
confirms. Output the bitmap to `logcat` behind a debug broadcast, same pattern
as the existing `ELM_CMD` probe.

---

## Tier 1 — don't get stranded (highest value for remote SAR)

### 1. Round-trip range ring on the nav map
**What:** `fuel% × tank_capacity × avgMPG` → a drivable-range overlay on
MapLibre. The load-bearing variant is **"can I reach that waypoint *and get
back*"** — halve the usable range and test it against a selected POI before
committing to a route. Follow with a reserve alert: warn when remaining range
< distance to the nearest cached fuel/base POI.
**Why it matters:** in the backcountry, range math done by feel is how vehicles
get stranded on a search. Making it automatic and map-anchored is a genuine
safety behavior, in the spirit of the auto rear-cam.
**Data:** `fuelPct` (have) + tank capacity (constant, ~21 gal for the VQ40) +
`avgMPG` (have) + GPS + the nav POI layer — all in the graph already.
**Feasibility:** easy. No new PIDs. Reuses nav + POI. First cut is a radius;
proper isochrone is a later refinement.

### 2. Engine-as-generator management
**What:** you idle the engine to recharge the house pack via DC-DC. Correlate
Renogy charge current against RPM, derive **idle fuel burn from MAF** (g/s →
L/h), and surface "X h of idle fuel left" and "house pack full in Y min at this
RPM." Optionally nudge "start engine to charge" when house SOC is low and
there's no solar input.
**Why it matters:** during a drone op the vehicle is the power/comms base. The
two things that end the op are a dead house battery and an empty tank — this
ties them together and makes the tradeoff explicit.
**Data:** `mafGs` (have) → fuel flow, `rpm` (have), Renogy A/W/SOC (have).
**Feasibility:** easy–moderate. No new PIDs; needs the MAF→fuel-flow
conversion (stoich × MAF) and a small idle-burn model.

### 3. Alternator / charge-system health
**What:** with the engine running, `starterV` (`0142`) that isn't sitting above
~13.8 V while RPM is up = alternator or belt fault. Flag it.
**Why it matters:** a dead alternator on day two strands you. This is a free
early warning from a value already polled.
**Data:** `starterV` + `rpm` (both have).
**Feasibility:** trivial. Pure logic over existing state.

---

## Tier 2 — the cross-domain drone-ops standout

### 4. Density altitude from OBD baro + IAT
**What:** add barometric pressure (`0133`) and combine with IAT/ambient
(`010F`/`0146`) to compute **density altitude** — cross-checked against GPS
altitude.
**Why it matters:** DA directly sets VTOL thrust margin and prop performance.
A heavy quad launching at 6,000 ft on a hot afternoon is a very different
aircraft than at sea level, and right now that's eyeballed. The truck's ECU
becomes the field DA meter for flight planning — a cross-domain use of vehicle
telemetry that fits this platform exactly.
**Data:** `0133` (one new PID), `010F` (have), `0146` (new, if supported), GPS
alt (have).
**Feasibility:** easy once the scan confirms `0133`. Standard DA formula.

### 5. Launch-platform level + "base ready" state
**What:** reuse the IMU/tilt as a bubble level for VTOL launch and mast/antenna
deployment, and roll a one-glance "base established" check: in Park, house SOC
sufficient, doors closed (`0x60D`), engine off or idling-to-charge.
**Why it matters:** VTOL launch and mast raising want a level platform; the
readiness check turns "am I set up?" into a glance.
**Data:** tilt (have), `0x60D` doors (have), Renogy SOC (have), gear/RPM (have).
**Feasibility:** easy. Mostly presentation over existing state.

### 6. Unattended-base movement / intrusion alert
**What:** when the vehicle is an unattended base and you've walked off to fly,
push to the phone (reusing the companion's notification path) on engine start
(RPM), movement (GPS delta), or door open (`0x60D`).
**Why it matters:** anti-theft *and* "someone's at my base," for the exact
window when you're heads-down on the aircraft and away from the rig.
**Data:** `rpm`, GPS, `0x60D` (all have) + the alert infra just built in the
companion.
**Feasibility:** easy. New alert conditions on the existing WorkManager path.

---

## Tier 3 — self-reliance / early mechanical warning

### 7. Fuel-trim monitoring
**What:** read short/long-term fuel trims (`0106`–`0109`, both banks — it's a
V6) and trend LTFT. Drift rich/lean is the earliest signal of a failing MAF,
vacuum leak, or injector — days before a MIL.
**Why it matters:** highest-signal "fix it at home, not on a fire road" gauge
for a technical operator.
**Data:** four new PIDs (if the scan confirms).
**Feasibility:** easy polling; the value is in trending + a threshold alert.

### 8. Geotagged DTC + freeze-frame event log
**What:** on top of the codes/MIL we read, capture Mode 02 freeze-frame and
Mode 07 pending codes, and stamp every fault with GPS position + time.
**Why it matters:** "what threw a code, and *where*" — the creek crossing 20
miles back — instead of a bare P-code at the trailhead.
**Data:** Mode 02 / Mode 07 (new modes), GPS + clock (have).
**Feasibility:** moderate. New mode parsing; a small persisted event log.

### 9. Engine-hours / idle-hours meter
**What:** integrate RPM>0 into engine hours and idle hours; persist.
**Why it matters:** a rig that idles as a generator needs hour-based
maintenance intervals, not just odometer miles — and a 2008 ECU won't report
hours.
**Data:** `rpm` (have) + integration + persistence.
**Feasibility:** easy.

---

## Tier 4 — the vehicle as an instrument

### 10. Dead-reckoning through GPS dropouts
**What:** fuse wheel speed (`010D`/VSS integration) with IMU heading to hold a
position estimate through canyon/canopy GPS outages.
**Why it matters:** in terrain, GPS drops exactly where the terrain is worst.
For SAR this is the difference between a continuous track and one with holes in
the hardest spots.
**Data:** `speedKmh` (have), IMU (have; no gyro/mag on this unit, so heading is
course-when-moving — a real limitation to design around), last GPS fix (have).
**Feasibility:** moderate–hard. Heading without a magnetometer is the catch;
usable while rolling, degrades at very low speed. Scope carefully.

### 11. Sortie track logging with OBD annotations
**What:** log the GPS track + speed/fuel/faults → GPX/KML, overlaid with the
drone's MAVLink track on the same map. Already flagged on the roadmap.
**Why it matters:** the vehicle track and the air track in one record *is* the
recon/SAR deliverable.
**Data:** GPS + OBD (have); MAVLink track (roadmap).
**Feasibility:** moderate. Logging + export + map overlay.

---

## Suggested first tranche

**1 (round-trip range ring), 4 (density altitude), 6 (unattended-base alert).**
Each is high mission value, mostly reuses infrastructure already in place
(nav/POI, IMU, the companion alert path), and needs at most one or two new
standard PIDs. Gate the PID-dependent work behind the step-zero capability scan.
