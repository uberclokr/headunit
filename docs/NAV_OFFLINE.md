# Offline turn-by-turn navigation

Fully offline routing on the Edge2 — no internet, no external routing service.
The vehicle is mobile and often off-grid, so both the routing graph and the map
tiles live on-device.

## Pieces

| Concern | Where |
|---|---|
| Routing engine | `nav/route/GraphHopperEngine.kt` (embedded GraphHopper 7.0) |
| Domain model | `nav/route/RouteModel.kt` (`Route`/`RouteStep`/`Maneuver`) |
| Guidance math | `nav/route/Navigator.kt` (pure; snap, progress, off-route) — unit-tested |
| Orchestration | `nav/route/NavRepository.kt` (route from fix, re-snap per GPS fix, reroute, arrival) |
| Map + UI | `nav/NavMap.kt`, `nav/NavGuidanceUi.kt` (route line, turn banner, cache manager) |
| Voice | `nav/NavVoice.kt` (on-device Android TTS, no network) |

The engine is behind the `RouteEngine` interface, so the graph vendor can be
swapped without touching the UI or guidance.

## The routing graph (PNW)

Coverage: **Oregon + Washington + Idaho**, `car` profile, `fastest` weighting,
Contraction Hierarchies prepared at import. On-disk **~453 MB** — fits the
Edge2 `/data` free space (checked ~2.7 GB free) with room to spare. Oregon-only
(~163 MB) is the fallback if space gets tight.

It is **not** in git (too large). It's a provisioned on-device artifact living
in the app's `filesDir`. It survives OS/app *updates* but **not** an app
uninstall or a "clear data" — after either, re-provision (below).

### Rebuild the graph (workstation, in the `ubuntu24` distrobox)

Needs Java 17+ and [osmium-tool]. GraphHopper version **must** match the
`graphhopper-core` pinned in `app/build.gradle.kts` (7.0).

```bash
# 1. State extracts from Geofabrik
for s in oregon washington idaho; do
  wget "https://download.geofabrik.de/north-america/us/$s-latest.osm.pbf" -O $s.osm.pbf
done

# 2. Merge into one PNW extract
osmium merge oregon.osm.pbf washington.osm.pbf idaho.osm.pbf -o pnw.osm.pbf

# 3. Import → graph folder (CH prepared here so query-time never needs Janino)
#    graphhopper-web-7.0.jar from the GraphHopper GitHub releases.
java -jar graphhopper-web-7.0.jar import config-pnw.yml
```

`config-pnw.yml`:

```yaml
graphhopper:
  datareader.file: pnw.osm.pbf
  graph.location: graph-pnw
  import.osm.ignored_highways: footway,cycleway,path,pedestrian,steps
  profiles:
    - name: car
      vehicle: car
      weighting: fastest
  profiles_ch:
    - profile: car
```

### Provision onto the Edge2

Push the graph folder into the app's `filesDir/graph` and give it to the app's
uid (find it with `adb shell dumpsys package com.xterra.helm | grep userId`;
it was `10147` / `u0_a147` at time of writing — verify, it changes on reinstall).

```bash
adb push graph-pnw/. /data/local/tmp/graph
adb shell 'run-as com.xterra.helm sh -c "rm -rf files/graph && mkdir files/graph"'
# SELinux is Permissive on this image; move via su and fix ownership.
adb shell su -c 'cp /data/local/tmp/graph/* /data/data/com.xterra.helm/files/graph/ && \
  chown -R u0_a147:u0_a147 /data/data/com.xterra.helm/files/graph && \
  chmod -R u+rwX /data/data/com.xterra.helm/files/graph'
adb shell am force-stop com.xterra.helm   # reload on next launch
```

Validate without the UI:

```bash
# log-only self-test (Bend -> Redmond by default)
adb shell am broadcast -a com.xterra.helm.NAV_TEST
# real trip from the live GPS fix (drives route line + banner + voice)
adb shell am broadcast -a com.xterra.helm.NAV_GO --ef tlat 45.5152 --ef tlon -122.6784 --es name Portland
adb logcat -s Helm | grep -iE 'graph loaded|route:|NAV'
```

## Two Android/ART gotchas (already handled — don't "fix" them away)

1. **OOM on load.** GraphHopper's default `RAM_STORE` pulls the whole graph
   into the app's Java heap and OOMs on a regional graph. `GraphHopperEngine`
   loads with `graph.dataaccess.default_type=MMAP` (via `GraphHopperConfig` +
   `init()`), so the graph is demand-paged from disk and heap stays flat. Same
   on-disk format as RAM_STORE — no rebuild needed. `init()` also validates
   `import.osm.ignored_highways` even on pure load, so it's set to match the
   build config.

2. **`NoClassDefFoundError: javax.lang.model.SourceVersion`.** GraphHopper 7.0
   validates every encoded-value name with `SourceVersion.isKeyword()`, a JDK
   compiler-API class absent from Android's bootclasspath. It's referenced
   lazily on the first snap, so the graph *loads* fine but the first *route*
   throws. Because `javax.lang.model` is absent on Android we ship our own
   minimal `javax/lang/model/SourceVersion.java`; the APK copy is what loads.

CH is prepared at import, so query never invokes GraphHopper's runtime
expression compiler (Janino), which won't run under ART.

## Cache management

In-app on the nav pane: the **⛃ CACHE** chip opens a manager covering both
offline-cache classes:

- **Map tiles** — MapLibre offline regions (frame a view, *CACHE THIS VIEW*),
  listed with tile counts + size, per-region delete, plus the 1 GB ambient LRU
  cache for anywhere already viewed.
- **Navigation** — the routing graph: size, loaded state, and delete (tears the
  engine down; routing is unavailable until re-provisioned per above).

[osmium-tool]: https://osmcode.org/osmium-tool/
